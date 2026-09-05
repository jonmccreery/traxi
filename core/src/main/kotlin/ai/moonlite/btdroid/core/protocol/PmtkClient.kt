package ai.moonlite.btdroid.core.protocol

import ai.moonlite.btdroid.core.format.LogFormat
import ai.moonlite.btdroid.core.transport.PmtkProtocolException
import ai.moonlite.btdroid.core.transport.PmtkTimeoutException
import ai.moonlite.btdroid.core.transport.Transport

/**
 * Typed PMTK request/response over a [Transport], with ack matching, timeouts
 * and retries.
 *
 * Every method here is a **query** except those prefixed `write`. That naming
 * is load-bearing: connecting to the device must never mutate its
 * configuration, so a reviewer can confirm a code path is read-only by
 * checking that it calls no `write*` method.
 */
class PmtkClient(
    private val transport: Transport,
    val transcript: PmtkTranscript = PmtkTranscript.NONE,
    private val defaultTimeoutMillis: Long = 3_000,
    private val defaultRetries: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val assembler = NmeaLineAssembler()
    private val readBuffer = ByteArray(8 * 1024)

    /** Sentences received while waiting for a different response. */
    private val queued = ArrayDeque<NmeaSentence>()

    // ---------------- primitives ----------------

    suspend fun send(payload: String) {
        val framed = Nmea.frame(payload)
        transcript.tx(framed.trimEnd())
        transport.write(framed.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Wait for the first sentence satisfying [predicate].
     *
     * Sentences that do not match are recorded and discarded — the device may
     * be emitting periodic navigation sentences alongside PMTK responses.
     * Lines that fail checksum are counted and dropped: on a raw byte stream
     * with no framing guarantees, corrupt lines are a steady-state condition
     * rather than an error.
     */
    suspend fun awaitSentence(
        timeoutMillis: Long = defaultTimeoutMillis,
        predicate: (NmeaSentence) -> Boolean,
    ): NmeaSentence {
        queued.firstOrNull(predicate)?.let { queued.remove(it); return it }

        val deadline = clock() + timeoutMillis
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) {
                throw PmtkTimeoutException("no matching response in ${timeoutMillis}ms")
            }

            val n = transport.read(readBuffer, remaining)
            if (n < 0) throw PmtkProtocolException("transport closed while awaiting response")
            if (n == 0) continue

            // Queue the whole batch before testing for a match. Returning from
            // inside this loop would abandon every sentence parsed after the
            // matching one, and a single RFCOMM read routinely carries several
            // -- which silently drops most chunks of a block read.
            for (line in assembler.feed(readBuffer, n)) {
                val sentence = Nmea.parse(line)
                if (sentence == null) {
                    transcript.note("dropped malformed or bad-checksum line: $line")
                    continue
                }
                transcript.rx(sentence.raw)
                if (queued.size >= MAX_QUEUED) queued.removeFirst()
                queued.addLast(sentence)
            }

            queued.firstOrNull(predicate)?.let { queued.remove(it); return it }
        }
    }

    /**
     * Send [payload] and wait for a matching response, retrying on timeout.
     *
     * Retry is at the whole-exchange level because a timeout usually means the
     * request itself was lost or malformed on the wire, not that the response
     * is merely late.
     */
    suspend fun exchange(
        payload: String,
        timeoutMillis: Long = defaultTimeoutMillis,
        retries: Int = defaultRetries,
        predicate: (NmeaSentence) -> Boolean,
    ): NmeaSentence {
        var lastFailure: Exception? = null
        repeat(retries) { attempt ->
            try {
                send(payload)
                return awaitSentence(timeoutMillis, predicate)
            } catch (e: PmtkTimeoutException) {
                lastFailure = e
                transcript.note("retry ${attempt + 1}/$retries for $payload: ${e.message}")
                queued.clear()
                assembler.reset()
            }
        }
        throw PmtkTimeoutException(
            "$payload failed after $retries attempts: ${lastFailure?.message}"
        )
    }

    // ---------------- queries ----------------

    suspend fun queryFirmware(): Pmtk.Firmware {
        val sentence = exchange(Pmtk.QUERY_FIRMWARE) { it.type == "PMTK705" }
        return Pmtk.Firmware.from(sentence)
            ?: throw PmtkProtocolException("unparseable firmware response: ${sentence.raw}")
    }

    /** Raw value of a config field, from `PMTK182,3,<id>,<value>`. */
    suspend fun queryConfig(field: Pmtk.ConfigField): String {
        val id = field.id.toString()
        val sentence = exchange(Pmtk.queryConfig(field)) {
            it.matches("PMTK182", "3", id)
        }
        return sentence[3]
            ?: throw PmtkProtocolException("config $field response had no value: ${sentence.raw}")
    }

    suspend fun queryLogFormat(): LogFormat {
        val hex = queryConfig(Pmtk.ConfigField.LOG_FORMAT)
        val bits = hex.trim().toLongOrNull(16)
            ?: throw PmtkProtocolException("log format not hex: '$hex'")
        return LogFormat(bits.toInt())
    }

    /** Time criterion in seconds, converted from the device's 0.1 s units. */
    suspend fun queryTimeIntervalSeconds(): Double {
        val tenths = queryConfig(Pmtk.ConfigField.TIME_INTERVAL).trim().toIntOrNull()
            ?: throw PmtkProtocolException("time interval not numeric")
        return tenths / 10.0
    }

    suspend fun queryLogStatus(): String = queryConfig(Pmtk.ConfigField.LOG_STATUS)

    /**
     * Flash identity, decoded from the JEDEC RDID the device returns.
     *
     * @return the decoded id, or null if the device answers with something
     *   unrecognisable. Null rather than a guess: an invented flash size is
     *   worse than none, because a download sized from it truncates silently.
     */
    suspend fun queryFlashId(): Pmtk.FlashId? =
        runCatching { Pmtk.FlashId.parse(queryConfig(Pmtk.ConfigField.FLASH_SIZE)) }.getOrNull()

    /**
     * Next write address in bytes.
     *
     * A progress hint only. In OVERLAP mode data before a wrap lives past this
     * pointer, so a download must never stop here.
     */
    suspend fun queryWritePointer(): Long? = runCatching {
        queryConfig(Pmtk.ConfigField.WRITE_POINTER).trim().toLong(16)
    }.getOrNull()

    /**
     * Read [length] bytes of raw log at [address].
     *
     * The device answers with one or more `PMTK182,8,<addr>,<hex>` sentences
     * whose chunking is not specified, so this places each chunk at its own
     * reported address rather than assuming they arrive in order or in equal
     * sizes.
     *
     * @return the bytes read, and how many were actually filled. A short read
     *   is reported rather than padded, so the caller can retry or treat the
     *   region as end-of-data.
     */
    suspend fun readLogBlock(
        address: Int,
        length: Int,
        timeoutMillis: Long = 10_000,
    ): LogBlock {
        send(Pmtk.readLog(address, length))

        val out = ByteArray(length) { UNWRITTEN }
        val covered = BooleanArray(length)
        var filled = 0
        val deadline = clock() + timeoutMillis

        while (filled < length && clock() < deadline) {
            val sentence = try {
                awaitSentence(deadline - clock()) { it.matches("PMTK182", "8") }
            } catch (e: PmtkTimeoutException) {
                break
            }

            val chunkAddress = sentence[2]?.toLongOrNull(16)?.toInt() ?: continue
            val hex = sentence[3] ?: continue
            val bytes = decodeHex(hex) ?: run {
                transcript.note("chunk at %08X had odd or invalid hex".format(chunkAddress))
                null
            } ?: continue

            val offset = chunkAddress - address
            for (i in bytes.indices) {
                val target = offset + i
                if (target in 0 until length && !covered[target]) {
                    out[target] = bytes[i]
                    covered[target] = true
                    filled++
                }
            }
        }

        return LogBlock(address, out, filled)
    }

    // ---------------- writes: never called while merely connecting ----------------

    /**
     * Write a config field, verifying the ack.
     *
     * Callers are responsible for disabling logging first and restoring it
     * afterwards; this method deliberately does not do it implicitly, so the
     * full sequence is visible at the call site.
     */
    suspend fun writeConfig(field: Pmtk.ConfigField, value: String) {
        val sentence = exchange(Pmtk.writeConfig(field, value)) {
            Pmtk.Ack.from(it)?.command == "182"
        }
        val ack = Pmtk.Ack.from(sentence)
            ?: throw PmtkProtocolException("no ack for $field write")
        if (!ack.isSuccess) {
            throw PmtkProtocolException("$field write rejected, flag ${ack.flag}")
        }
        transcript.note("wrote $field = $value")
    }

    suspend fun writeLoggingEnabled(enabled: Boolean) {
        val payload = if (enabled) Pmtk.WRITE_ENABLE_LOGGING else Pmtk.WRITE_DISABLE_LOGGING
        val sentence = exchange(payload) { Pmtk.Ack.from(it)?.command == "182" }
        val ack = Pmtk.Ack.from(sentence)!!
        if (!ack.isSuccess) {
            throw PmtkProtocolException(
                "logging ${if (enabled) "enable" else "disable"} rejected, flag ${ack.flag}"
            )
        }
        transcript.note("logging ${if (enabled) "enabled" else "disabled"}")
    }

    data class LogBlock(val address: Int, val bytes: ByteArray, val filled: Int) {
        val isComplete: Boolean get() = filled == bytes.size

        override fun equals(other: Any?): Boolean =
            other is LogBlock && address == other.address &&
                filled == other.filled && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (address * 31 + filled) * 31 + bytes.contentHashCode()
    }

    companion object {
        /**
         * Sentences held while waiting for a different response. Sized well
         * above the ~15 sentences an 8 KB read can carry, so a batch is never
         * partially discarded.
         */
        private const val MAX_QUEUED = 256
        const val UNWRITTEN: Byte = 0xFF.toByte()

        /** Decode an even-length hex string, or null if malformed. */
        fun decodeHex(hex: String): ByteArray? {
            val s = hex.trim()
            if (s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[i * 2], 16)
                val lo = Character.digit(s[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
