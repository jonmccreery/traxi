package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.format.Bytes
import thru.taxi.traxi.core.format.SectorHeader

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
    /**
     * Silence that ends a block read before it is retried, sized to the link.
     *
     * Defaults to the active transport's own value — 2.5 s over USB, 10 s over
     * Bluetooth — because how long a block takes is a property of the link, not
     * the protocol. A single hard-coded constant here was the bug: 2.5 s is
     * generous over USB and cuts every healthy Bluetooth block short, since one
     * hex-encoded chunk needs seconds on the wire and arrives interleaved with
     * live NMEA. See [Transport.blockReadIdleTimeoutMillis].
     */
    private val blockIdleTimeoutMillis: Long = client.blockReadIdleTimeoutMillis,
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
        /**
         * Byte ranges that never arrived and are therefore 0xFF in [image].
         *
         * **This is the most dangerous condition this class can produce.** A
         * missing range is indistinguishable from erased flash, so an image
         * with holes parses cleanly and looks complete. Reporting them is what
         * keeps that corruption from being silent — and no dump carrying them
         * may be treated as a verified copy, least of all as grounds to erase
         * the device.
         */
        val damagedRanges: List<IntRange> = emptyList(),
    ) {
        val isComplete: Boolean
            get() = stoppedOnUnwritten && failure == null && damagedRanges.isEmpty()

        val isDamaged: Boolean get() = damagedRanges.isNotEmpty()

        val damagedBytes: Int get() = damagedRanges.sumOf { it.last - it.first + 1 }

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
     * The probe reads a **whole block**, not a cheap slice. A 512-byte request
     * was tried and the device ignored it outright, answering with NMEA and
     * then dropping the RFCOMM link -- every read this hardware has ever served
     * was a full block, and it answers in 0x800 chunks. The simulator accepts
     * any length, which is exactly why that mistake survived to the device.
     *
     * So the probe costs one block, about two minutes on the slow link. Against
     * three hours for a full re-read that is still overwhelmingly worth it, and
     * it uses a request shape the device is known to honour.
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

        val probe = client.readLogBlock(0, blockSize)
        if (!probe.isComplete) {
            return Plan.FullRequired("could not read the flash to check for a wrap")
        }

        // Compare only the records, skipping the 512-byte sector header. The
        // header is stable for a finalized sector, but the records are what a
        // wrap actually destroys and they carry timestamps and positions that
        // cannot coincide by accident.
        for (i in SectorHeader.SIZE until blockSize) {
            if (probe.bytes[i] != previous[i]) {
                client.transcript.note(
                    "wrap probe differs at 0x%08X; the log has wrapped or been erased"
                        .format(i)
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
        val damaged = mutableListOf<IntRange>()

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
            var block = client.readLogBlock(address, blockSize, blockIdleTimeoutMillis)

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
                block = client.readLogBlock(address, blockSize, blockIdleTimeoutMillis)
            }

            if (block.filled == 0) {
                // A transport failure, not erased flash. Saying "end of flash"
                // here would silently truncate the dump and present the result
                // as complete, which is the worst outcome available: the user
                // would keep a short image believing it was the whole chip.
                // Parenthesised deliberately: `.format` binds to the literal it
                // follows, so without these the call applied to the second half
                // of the concatenation -- which has no specifiers -- and the
                // transcript printed a literal "0x%08X". The line that says
                // which block died was the one line not saying it.
                client.transcript.note(
                    ("block 0x%08X returned nothing after $ATTEMPTS_PER_BLOCK attempts; " +
                        "stopping with a partial image").format(address)
                )
                break
            }

            // Per-block timing, so a link that stalls or bursts is visible
            // rather than showing up only as a total that feels wrong.
            client.transcript.note(
                "block 0x%08X %d/%d bytes, %d chunks, %d ms (%.1f KB/s)".format(
                    address, block.filled, blockSize, block.chunks, block.elapsedMillis,
                    if (block.elapsedMillis > 0)
                        block.filled / 1.024 / block.elapsedMillis else 0.0
                )
            )

            if (!block.isComplete) {
                damaged += block.gaps
                client.transcript.note(
                    ("block 0x%08X STILL INCOMPLETE after $ATTEMPTS_PER_BLOCK attempts: " +
                        "%d bytes missing in %d gap(s). Those bytes read as 0xFF and are " +
                        "indistinguishable from erased flash.").format(
                        address, blockSize - block.filled, block.gaps.size)
                )
                block.gaps.take(4).forEach {
                    client.transcript.note("    missing 0x%08X..0x%08X".format(it.first, it.last))
                }
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
                "download interrupted at 0x%08X after %s: %s"
                    .format(address, Bytes.describe(out.size()), failure)
            )
        }

        if (damaged.isNotEmpty()) {
            // Parenthesised: .format() binds to the last literal otherwise,
            // and the placeholders in the first half print verbatim.
            client.transcript.note(
                ("DOWNLOAD DAMAGED: %d bytes missing across %d range(s). This image " +
                    "must not be trusted as a complete copy.").format(
                    damaged.sumOf { it.last - it.first + 1 }, damaged.size)
            )
        }
        return Result(
            out.toByteArray(), sectors, retries, stoppedOnUnwritten, failure, damaged
        )
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

        // The block-read idle timeout is no longer a constant here: it is a
        // property of the link, supplied by [Transport.blockReadIdleTimeoutMillis]
        // (2.5 s over USB, 10 s over Bluetooth) and taken as a constructor
        // default above. A single value here suited USB and silently broke
        // Bluetooth, which is the regression this replaced.

        /**
         * The wrap probe reads block 0 and compares everything past the sector
         * header. Those are the oldest records on the chip, and therefore the
         * first thing a wrap destroys.
         *
         * It reads a whole block because that is the only request shape this
         * device reliably serves; see [planIncremental].
         */
        const val PROBE_OFFSET = SectorHeader.SIZE
    }
}
