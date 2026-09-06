package thru.taxi.traxi.core.transport

/**
 * Replays a previously captured byte stream. Writes are recorded and discarded.
 *
 * Useful for reproducing one exact recorded session — including its timing
 * quirks and corrupt packets — against a changed parser. For interactive
 * development use [SimulatedLoggerTransport] instead, which actually answers.
 */
class FileReplayTransport(
    private val recorded: ByteArray,
    override val description: String = "replay(${recorded.size} bytes)",
) : Transport {

    private var position = 0
    private var open = false
    private val written = mutableListOf<ByteArray>()

    override val isOpen: Boolean get() = open

    override suspend fun open() { open = true; position = 0 }

    override suspend fun write(bytes: ByteArray) { written += bytes.copyOf() }

    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
        if (!open) return -1
        if (position >= recorded.size) return -1
        val n = minOf(dest.size, recorded.size - position)
        recorded.copyInto(dest, 0, position, position + n)
        position += n
        return n
    }

    override fun close() { open = false }

    /** Everything the client sent, for assertions in tests. */
    fun writtenText(): List<String> = written.map { String(it, Charsets.US_ASCII) }
}

/**
 * A logger emulator: serves a real flash image over the PMTK protocol.
 *
 * This exists for two reasons, and the second is the important one.
 *
 * As a test seam it lets the entire protocol and download stack be exercised
 * end to end on the JVM with no hardware. As a **shipped feature** it lets the
 * app be operated, demonstrated and debugged on the phone with the logger in a
 * drawer — which matters because the phone is the only computer available in
 * the field, and a dump that half-completes at a trailhead has to be
 * re-parseable without the device.
 *
 * It deliberately reproduces the real device's misbehaviour: a wrong record
 * count, a failing flash-size query, and an unsupported `PMTK704`.
 */
class SimulatedLoggerTransport(
    private val flash: ByteArray,
    private val firmwareRelease: String = "AXN_1.30-B_1.3_C01",
    private val modelId: String = "0008",
    private val logFormatHex: String = "000A003F",
    /** Bytes per `PMTK182,8` response chunk. The real device is not uniform. */
    private val chunkSize: Int = 0x400,
    /** Next write address in bytes, as the real BT-Q1000XT reports it. */
    private val writePointer: Int = 0x0051F3C2,
    /** JEDEC RDID the real device returns: EON, EN25QH series, 8 MB. */
    private val flashIdHex: String = "1C70171C",
    private val modelName: String = "BT-Q1000XT",
    /**
     * Acknowledge `PMTK182,6,1` but do not actually erase.
     *
     * Reproduces the failure prep doc §0.2 clause 6 exists to catch: a device
     * that reports success and leaves the flash untouched, so the user believes
     * they have free space until the chip fills early in the field. Without a
     * way to simulate it, the verification step is untested code guarding
     * against a condition nobody has ever seen fail.
     */
    private val eraseSilentlyFails: Boolean = false,
    override val description: String = "simulated logger",
) : Transport {

    /**
     * Set once the flash has been erased.
     *
     * A flag rather than filling [flash] with 0xFF: the array belongs to the
     * caller, and a simulator that quietly destroys the test fixture it was
     * handed is its own kind of data-loss bug.
     */
    private var erased = false

    /** Moves to zero on erase, as the real device's does. */
    private var currentWritePointer = writePointer

    /** Reported through `PMTK182,3,7`; starts on, as a live logger would be. */
    private var logging = true

    private val _received = mutableListOf<String>()

    /**
     * Every complete sentence the client sent, payload only, in order.
     *
     * Exposed so a test can assert on the *order* of a command sequence rather
     * than only on its outcome. For erase that distinction is the whole point:
     * an erase issued before logging was disabled would still return a clean
     * result while doing the one thing the sequence exists to prevent.
     */
    val received: List<String> get() = _received

    /**
     * Pending response bytes. A plain buffer with a read cursor rather than a
     * `Deque<Byte>`: a full dump pushes ~10.8 MB of hex through here, and
     * boxing every byte makes the round-trip test take minutes instead of
     * seconds.
     */
    private var outbox = ByteArray(1 shl 16)
    private var outStart = 0
    private var outEnd = 0

    private val inbox = StringBuilder()
    private var open = false

    override val isOpen: Boolean get() = open

    override suspend fun open() {
        open = true
        outStart = 0
        outEnd = 0
        inbox.setLength(0)
    }

    override fun close() { open = false }

    private fun enqueue(bytes: ByteArray) {
        if (outEnd + bytes.size > outbox.size) {
            // Compact first; grow only if compaction is not enough.
            val live = outEnd - outStart
            outbox.copyInto(outbox, 0, outStart, outEnd)
            outStart = 0
            outEnd = live
            if (live + bytes.size > outbox.size) {
                outbox = outbox.copyOf(maxOf(outbox.size * 2, live + bytes.size))
            }
        }
        bytes.copyInto(outbox, outEnd)
        outEnd += bytes.size
    }

    override suspend fun write(bytes: ByteArray) {
        if (!open) throw IllegalStateException("transport not open")
        for (b in bytes) {
            val ch = b.toInt().toChar()
            if (ch == '\n') {
                handle(inbox.toString().trim())
                inbox.setLength(0)
            } else if (ch != '\r') {
                inbox.append(ch)
            }
        }
    }

    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
        if (!open) return -1
        val available = outEnd - outStart
        if (available == 0) return 0
        val n = minOf(dest.size, available)
        outbox.copyInto(dest, 0, outStart, outStart + n)
        outStart += n
        if (outStart == outEnd) { outStart = 0; outEnd = 0 }
        return n
    }

    // ---------------- protocol emulation ----------------

    private fun reply(payload: String) {
        enqueue(
            thru.taxi.traxi.core.protocol.Nmea.frame(payload)
                .toByteArray(Charsets.US_ASCII)
        )
    }

    private fun handle(line: String) {
        val sentence = thru.taxi.traxi.core.protocol.Nmea.parse(line) ?: return
        val f = sentence.fields
        _received += sentence.fields.joinToString(",")

        when {
            f[0] == "PMTK605" ->
                reply("PMTK705,$firmwareRelease,$modelId,$modelName,1.0")

            // Unsupported on this firmware: answer nothing, as the device does.
            f[0] == "PMTK704" -> Unit

            f[0] == "PMTK182" && f.getOrNull(1) == "2" -> {
                when (f.getOrNull(2)) {
                    "2" -> reply("PMTK182,3,2,$logFormatHex")
                    "3" -> reply("PMTK182,3,3,200")
                    "4" -> reply("PMTK182,3,4,0")
                    "5" -> reply("PMTK182,3,5,0")
                    "6" -> reply("PMTK182,3,6,1")
                    // Tracks the enable/disable commands rather than answering
                    // a constant. It used to always report 256 -- logging off
                    // -- which is both wrong for a device that is notionally
                    // recording and useless for exercising the recording
                    // indicator, since it could never change.
                    "7" -> reply("PMTK182,3,7,${if (logging) 0x0102 else 0x0100}")
                    // A byte address, not a record count -- confirmed against
                    // the real device.
                    "8" -> reply("PMTK182,3,8,%08X".format(currentWritePointer))
                    // A JEDEC RDID, not a byte count. Long assumed broken.
                    "9" -> reply("PMTK182,3,9,$flashIdHex")
                    else -> Unit
                }
            }

            f[0] == "PMTK182" && f.getOrNull(1) == "7" -> {
                val address = f.getOrNull(2)?.toLongOrNull(16)?.toInt() ?: return
                val length = f.getOrNull(3)?.toLongOrNull(16)?.toInt() ?: return
                if (length % 2 != 0) return          // device rejects odd lengths
                emitLogChunks(address, length)
            }

            f[0] == "PMTK182" && f.getOrNull(1) == "1" ->
                reply("PMTK001,182,1,3")

            f[0] == "PMTK182" && f.getOrNull(1) == "4" -> {
                logging = true
                reply("PMTK001,182,4,3")
            }

            f[0] == "PMTK182" && f.getOrNull(1) == "5" -> {
                logging = false
                reply("PMTK001,182,5,3")
            }

            f[0] == "PMTK182" && f.getOrNull(1) == "6" -> {
                if (!eraseSilentlyFails) {
                    erased = true
                    currentWritePointer = 0
                }
                reply("PMTK001,182,6,3")
            }
        }
    }

    private fun emitLogChunks(address: Int, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = minOf(chunkSize, length - offset)
            val start = address + offset
            val sb = StringBuilder(n * 2)
            for (i in 0 until n) {
                val index = start + i
                val byte = when {
                    erased -> 0xFF.toByte()
                    index in flash.indices -> flash[index]
                    else -> 0xFF.toByte()
                }
                sb.append("%02X".format(byte.toInt() and 0xFF))
            }
            reply("PMTK182,8,%08X,%s".format(start, sb))
            offset += n
        }
    }
}
