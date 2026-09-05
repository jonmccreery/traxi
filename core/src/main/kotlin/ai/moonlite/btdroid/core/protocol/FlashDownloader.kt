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
    }
}
