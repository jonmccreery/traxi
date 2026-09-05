package ai.moonlite.btdroid.core.protocol

import ai.moonlite.btdroid.core.format.SectorHeader

/**
 * Downloads the full flash image, with progress, resume, and empirical sizing.
 *
 * Three rules drive the design, all of them consequences of the device being
 * unreliable about its own state:
 *
 *  1. **Never trust a reported size or record count.** This device reports
 *     247,133 records when it holds 126,613, and its flash-size query fails
 *     outright. Size the download by reading until the flash stops answering
 *     with data.
 *  2. **Never stop at the write pointer.** The log is a circular buffer; in
 *     `OVERLAP` mode everything before the wrap sits *after* the pointer.
 *  3. **Resume, never restart.** A 5.4 MB dump takes 20-25 minutes over
 *     RFCOMM. Losing it at 90% because a phone went in a pocket is not
 *     acceptable when the phone is the only computer available.
 */
class FlashDownloader(
    private val client: PmtkClient,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    /**
     * Consecutive unwritten sectors that end the download.
     *
     * Two, not one: a single bad sector reading as `0xFF` in the middle of a
     * live log would otherwise truncate the dump silently. Requiring two in a
     * row costs one extra block read and removes that failure mode.
     *
     * This termination rule is correct in both buffer states. If the log has
     * not wrapped, unwritten sectors mark the genuine end of data. If it has
     * wrapped, there are no unwritten sectors at all, so the read runs to the
     * true end of flash.
     */
    private val unwrittenSectorsToStop: Int = 2,
    private val maxBytes: Int = 32 * 1024 * 1024,
) {
    data class Progress(
        val bytesDownloaded: Int,
        val sectorsRead: Int,
        val retries: Int,
    )

    data class Result(
        val image: ByteArray,
        val sectorsRead: Int,
        val retries: Int,
        val stoppedOnUnwritten: Boolean,
        /**
         * Set when the transfer ended on an error rather than reaching the end
         * of flash. The bytes already read are still returned: a partial dump
         * is worth strictly more than none, and it is resumable.
         */
        val failure: String? = null,
    ) {
        val isComplete: Boolean get() = stoppedOnUnwritten && failure == null

        override fun equals(other: Any?): Boolean =
            other is Result && sectorsRead == other.sectorsRead &&
                retries == other.retries && image.contentEquals(other.image)

        override fun hashCode(): Int = image.contentHashCode() * 31 + sectorsRead
    }

    /** Whether a previous dump can be extended, or must be re-read in full. */
    sealed interface Plan {
        /** Re-read from [fromOffset]; everything before it is already correct. */
        data class Extend(val fromOffset: Int, val previousBytes: Int) : Plan {
            val bytesToSkip: Int get() = fromOffset
        }

        data class FullRequired(val reason: String) : Plan
    }

    /**
     * Decide whether [previous] can be extended instead of re-downloaded.
     *
     * This is what makes Bluetooth usable on this hardware. The link runs at
     * 493 B/s, so a full 5.4 MB image takes about three hours — but the log is
     * append-only until it wraps, and a trip adds roughly 98 KB per day. Fetching
     * only what is new turns a three-hour transfer into a few minutes.
     *
     * **The wrap check is the whole safety argument.** In `OVERLAP` mode a full
     * log overwrites its oldest sectors first, so if the flash has wrapped, the
     * bytes a previous dump holds are no longer the bytes on the chip and
     * appending to it would produce a corrupt image that still parses. The probe
     * therefore reads real records from the very start of the log — the first
     * data to be overwritten — and compares them against what the previous dump
     * says should be there.
     *
     * The probe deliberately reads records rather than the sector header: two
     * headers look nearly identical whether or not a wrap happened, whereas
     * records carry timestamps and positions that cannot coincide. It reads
     * [PROBE_LENGTH] bytes, about a second on the slow link, against the two
     * minutes a full sector would cost.
     */
    suspend fun planIncremental(previous: ByteArray): Plan {
        // Trim to the last whole block rather than refusing. Dumps made by other
        // tools are not block-aligned -- the reference mtkbabel image is 82
        // blocks plus 2 KB -- and rejecting those would force a three-hour
        // re-read to recover two kilobytes.
        val usable = (previous.size / blockSize) * blockSize
        if (usable < 2 * blockSize) {
            return Plan.FullRequired("no previous dump worth extending")
        }

        val probe = client.readLogBlock(PROBE_OFFSET, PROBE_LENGTH)
        if (!probe.isComplete) {
            return Plan.FullRequired("could not read the flash to check for a wrap")
        }

        for (i in 0 until PROBE_LENGTH) {
            if (probe.bytes[i] != previous[PROBE_OFFSET + i]) {
                client.transcript.note(
                    "wrap probe differs at 0x%08X; the log has wrapped or been erased"
                        .format(PROBE_OFFSET + i)
                )
                return Plan.FullRequired("the log has wrapped since that dump")
            }
        }

        // Re-read the final whole block. It held the sector that was mid-write,
        // whose record count was 0xFFFF and whose tail has since gained records.
        val from = usable - blockSize
        client.transcript.note(
            "wrap probe matches; extending from 0x%08X".format(from)
        )
        return Plan.Extend(from, usable)
    }

    /**
     * Extend [previous] with whatever the device has added since.
     *
     * Falls back to a full download when [planIncremental] says the log wrapped,
     * because an extended image would otherwise silently mix two eras of data.
     */
    suspend fun downloadIncremental(
        previous: ByteArray,
        onProgress: (Progress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Result = when (val plan = planIncremental(previous)) {
        is Plan.Extend -> download(previous, plan.fromOffset, onProgress, shouldContinue)
        is Plan.FullRequired -> {
            client.transcript.note("full download required: ${plan.reason}")
            download(ByteArray(0), 0, onProgress, shouldContinue)
        }
    }

    /**
     * Read the flash from [resumeFrom] onward, appending to [existing].
     *
     * @param existing bytes already downloaded in a previous session. Passing
     *   a partial image plus its length is the whole resume story: no separate
     *   state file, no chance of the two disagreeing.
     * @param onProgress called after each block; the UI's only progress source,
     *   since no trustworthy total exists to compute a percentage from.
     * @param shouldContinue polled between blocks so a cancel takes effect
     *   promptly without abandoning the bytes already read.
     */
    suspend fun download(
        existing: ByteArray = ByteArray(0),
        resumeFrom: Int = existing.size,
        onProgress: (Progress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Result {
        require(resumeFrom % blockSize == 0) {
            "resume offset $resumeFrom must be a multiple of the $blockSize block size"
        }

        val out = java.io.ByteArrayOutputStream(maxOf(existing.size, blockSize))
        out.write(existing, 0, minOf(existing.size, resumeFrom))

        var address = resumeFrom
        var sectors = resumeFrom / SectorHeader.SECTOR_SIZE
        var retries = 0
        var consecutiveUnwritten = 0
        var stoppedOnUnwritten = false

        if (resumeFrom > 0) {
            client.transcript.note("resuming download at 0x%08X".format(resumeFrom))
        }

        // Any failure below returns the bytes already read rather than
        // propagating. Losing a transfer that is most of the way done, on a
        // link that manages 493 B/s, is the single most expensive thing this
        // class can do -- and the resume path exists precisely so it never has
        // to happen twice.
        var failure: String? = null

        try {
        while (address < maxBytes && shouldContinue()) {
            var block = client.readLogBlock(address, blockSize)

            // A short block means dropped chunks, never end of flash: an erased
            // sector still answers, with 0xFF bytes. Retry the whole block.
            var attempt = 1
            while (!block.isComplete && attempt < ATTEMPTS_PER_BLOCK && shouldContinue()) {
                retries++
                attempt++
                client.transcript.note(
                    "block 0x%08X short (%d/%d), attempt %d of %d"
                        .format(address, block.filled, blockSize, attempt, ATTEMPTS_PER_BLOCK)
                )
                block = client.readLogBlock(address, blockSize)
            }

            if (block.filled == 0) {
                // A transport failure, not erased flash. Saying "end of flash"
                // here would silently truncate the dump and present the result
                // as complete, which is the worst outcome available: the user
                // would keep a short image believing it was the whole chip.
                client.transcript.note(
                    "block 0x%08X returned nothing after $ATTEMPTS_PER_BLOCK attempts; " +
                        "stopping with a partial image".format(address)
                )
                break
            }

            if (!block.isComplete) {
                client.transcript.note(
                    "block 0x%08X incomplete (%d/%d) after $ATTEMPTS_PER_BLOCK attempts; " +
                        "keeping what arrived".format(address, block.filled, blockSize)
                )
            }

            out.write(block.bytes, 0, blockSize)
            address += blockSize
            sectors = address / SectorHeader.SECTOR_SIZE

            consecutiveUnwritten =
                if (isUnwritten(block.bytes)) consecutiveUnwritten + 1 else 0

            onProgress(Progress(address, sectors, retries))

            if (consecutiveUnwritten >= unwrittenSectorsToStop) {
                stoppedOnUnwritten = true
                client.transcript.note(
                    "stopping: $consecutiveUnwritten consecutive unwritten blocks at 0x%08X"
                        .format(address)
                )
                break
            }
        }
        } catch (e: Exception) {
            // Transport died, device stopped answering, coroutine cancelled --
            // all the same from here. Keep what arrived.
            failure = e.message ?: e.toString()
            client.transcript.note(
                "download interrupted at 0x%08X after %d KB: %s"
                    .format(address, out.size() / 1024, failure)
            )
        }

        return Result(out.toByteArray(), sectors, retries, stoppedOnUnwritten, failure)
    }

    private fun isUnwritten(block: ByteArray): Boolean =
        block.all { it == PmtkClient.UNWRITTEN }

    companion object {
        /**
         * One flash sector per request. Larger requests are not obviously
         * faster — the wire is the bottleneck, since hex encoding doubles
         * 5.4 MB to 10.8 MB — and a smaller block bounds how much work a
         * retry throws away.
         */
        const val DEFAULT_BLOCK_SIZE = SectorHeader.SECTOR_SIZE

        /**
         * Attempts per block before giving up and keeping a partial image.
         *
         * Three rather than two, because over Bluetooth a block is tens of
         * seconds of wire time and a single dropped chunk is unremarkable.
         * Cheap insurance against abandoning a 25-minute transfer.
         */
        const val ATTEMPTS_PER_BLOCK = 3

        /**
         * Where the wrap probe reads: the first records of the log, just past
         * sector 0's 512-byte header. These are the oldest data on the chip and
         * therefore the first to be overwritten when the buffer wraps.
         */
        const val PROBE_OFFSET = SectorHeader.SIZE

        /** Probe size. Even, as the protocol requires, and ~1 s on the slow link. */
        const val PROBE_LENGTH = 512
    }
}
