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
        /**
         * How far into the flash the read has reached, in sectors -- the read
         * address divided by the sector size.
         *
         * **A position, not a tally.** A fetch-new that resumes at sector 2 and
         * ends at sector 5 reports 5, having pulled three sectors plus the wrap
         * probe. Calling it "sectors read" and labelling it "Sectors done"
         * overstated the work by the whole untouched prefix.
         */
        val sectorPosition: Int,
        val retries: Int,
        /**
         * Bytes filled in the block currently in flight, 0..[blockSizeBytes].
         * Resets to 0 at each block boundary, so the UI can draw an honest
         * per-sector bar even though the flash has no trustworthy total size.
         */
        val blockBytes: Int = 0,
        val blockSizeBytes: Int = 0,
        /**
         * Sectors actually pulled off the device in this transfer, wrap probe
         * included.
         *
         * The one figure here that measures *work*, as against [sectorPosition]
         * and [Result.sectorSpan], which measure where the read is. On a
         * fetch-new they diverge sharply: the 2026-09-15 run ended at position
         * 5 over a 5-sector image while fetching 4 sectors -- the probe plus
         * three -- because the two sectors before the frontier were never
         * asked for. 4 x 64 KB is the 256 KB that run actually spent on the
         * wire for 8,432 bytes of new riding.
         *
         * Each sector position counts once however many attempts it took;
         * re-reads are reported separately as [retries].
         */
        val sectorsFetched: Int = 0,
    )

    data class Result(
        val image: ByteArray,
        /** Sectors the finished image spans: its length over the sector size. */
        val sectorSpan: Int,
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
        /**
         * Set when a block returned **nothing at all** after every attempt.
         *
         * Distinct from every other stop, and worth its own flag because it has
         * its own cure. An erased sector still answers, with 0xFF; silence
         * means the device has stopped serving reads, which on Bluetooth is the
         * per-session wedge of the engineering record's §15. A new radio link
         * clears it and the transfer can resume from the frontier, so the
         * caller can recover from this where it cannot recover from a genuine
         * transport failure.
         *
         * Observed, not inferred: three consecutive attempts each returning
         * zero bytes is not a judgement call.
         */
        val stoppedUnanswered: Boolean = false,
        /**
         * Set when fetch-new could not extend the previous dump and re-read the
         * whole flash instead -- the log wrapped, was erased, or the probe
         * could not be read.
         *
         * The caller needs this to describe the outcome honestly. After a full
         * re-read the previous dump's frontier is not a baseline for anything,
         * so "added N KB of new tracking" is not a statement that can be made,
         * however the byte counts happen to compare.
         */
        val fullReread: Boolean = false,
        /** Sectors pulled off the device in this transfer; see [Progress.sectorsFetched]. */
        val sectorsFetched: Int = 0,
    ) {
        val isComplete: Boolean
            get() = stoppedOnUnwritten && failure == null && damagedRanges.isEmpty()

        val isDamaged: Boolean get() = damagedRanges.isNotEmpty()

        val damagedBytes: Int get() = damagedRanges.sumOf { it.last - it.first + 1 }

        override fun equals(other: Any?): Boolean =
            other is Result && sectorSpan == other.sectorSpan &&
                retries == other.retries && image.contentEquals(other.image)

        override fun hashCode(): Int = image.contentHashCode() * 31 + sectorSpan
    }

    /** Whether a previous dump can be extended, or must be re-read in full. */
    sealed interface Plan {
        /**
         * Sectors the wrap probe pulled off the device before deciding. Zero
         * when the answer came from bytes already in hand, without a read.
         */
        val sectorsProbed: Int

        /** Re-read from [fromOffset]; everything before it is already correct. */
        data class Extend(
            val fromOffset: Int,
            val previousBytes: Int,
            override val sectorsProbed: Int = 0,
        ) : Plan {
            val bytesToSkip: Int get() = fromOffset
        }

        data class FullRequired(
            val reason: String,
            override val sectorsProbed: Int = 0,
        ) : Plan
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
     *
     * Because those two minutes are the *first* thing fetch-new does, the probe
     * reports [onProgress] as its chunks accumulate and polls [shouldContinue]
     * between them, exactly as [download] does. Without that, fetch-new opens
     * with a dead bar for a whole block and a cancel waits the block out —
     * which is the regression this fixed.
     */
    suspend fun planIncremental(
        previous: ByteArray,
        onProgress: (Progress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Plan {
        // Trim to the last whole block rather than refusing. Dumps made by other
        // tools are not block-aligned -- the reference mtkbabel image is 82
        // blocks plus 2 KB -- and rejecting those would force a three-hour
        // re-read to recover two kilobytes.
        val usable = (previous.size / blockSize) * blockSize
        if (usable < 2 * blockSize) {
            return Plan.FullRequired("no previous dump worth extending")
        }

        // Work out where the extend will resume *before* probing, for two
        // reasons.
        //
        // First, it decides the no-data case without spending a block on the
        // wire -- two minutes over Bluetooth for an answer that is already
        // determined by bytes in hand.
        //
        // Second, it is what the probe reports progress against. The probe used
        // to pin at `usable`, the previous dump's whole length, and the extend
        // then began at the frontier block: the bar jumped *backwards* two
        // sectors at the handover -- 320 KB/sector 5 dropping to 128 KB/sector 2
        // in the 2026-09-15 run -- and climbed the same ground again. Nothing
        // was lost; the two phases were simply counting different things. They
        // now count the same thing, so the number only ever moves forward.
        val from = extendOffset(previous, blockSize)
            ?: return Plan.FullRequired("the previous dump holds no data")
        val fromSectors = from / SectorHeader.SECTOR_SIZE

        val probe = client.readLogBlock(
            0, blockSize, blockIdleTimeoutMillis,
            onChunk = { filledInBlock ->
                onProgress(
                    Progress(
                        bytesDownloaded = from,
                        sectorPosition = fromSectors,
                        retries = 0,
                        blockBytes = filledInBlock,
                        blockSizeBytes = blockSize,
                    )
                )
            },
            shouldContinue = shouldContinue,
        )
        // From here on the probe has been on the wire, so every outcome carries
        // its cost -- a whole 64 KB sector, about two and a half minutes over
        // Bluetooth. A fetch that gives up after probing did not do no work.
        if (!shouldContinue()) {
            return Plan.FullRequired("cancelled during the wrap probe", sectorsProbed = 1)
        }
        if (!probe.isComplete) {
            return Plan.FullRequired("could not read the flash to check for a wrap", sectorsProbed = 1)
        }

        // Compare only the records, skipping the 512-byte sector header. The
        // header is stable for a finalized sector, but the records are what a
        // wrap actually destroys and they carry timestamps and positions that
        // cannot coincide by accident.
        //
        // Three cases per byte, not two. A byte that was unwritten (0xFF) in
        // the previous dump proves nothing: on a young log the write frontier
        // is still inside block 0, and records landing there since the dump are
        // growth, not corruption. Treating growth as a wrap is how a fetch-new
        // against a post-erase dump got misdiagnosed and forced a three-hour
        // re-read. Only a byte the dump holds as *real* can convict: changed to
        // other data means the log wrapped; changed to 0xFF means it was erased.
        for (i in SectorHeader.SIZE until blockSize) {
            val had = previous[i]
            if (had == PmtkClient.UNWRITTEN) continue
            if (probe.bytes[i] != had) {
                val verdict =
                    if (probe.bytes[i] == PmtkClient.UNWRITTEN) "been erased" else "wrapped"
                client.transcript.note(
                    "wrap probe differs at 0x%08X; the log has %s since that dump"
                        .format(i, verdict)
                )
                return Plan.FullRequired("the log has $verdict since that dump", sectorsProbed = 1)
            }
        }

        // Re-read from the block holding the previous dump's write frontier —
        // the last real byte — because that is where new records actually land.
        // The old rule re-read the *final* block, which assumed growth happens
        // past the end of the dump. For a dump that captured unwritten sectors
        // (every complete dump of an unfull chip), that assumption copies the
        // stale mid-write block forward, drops every record written since, and
        // reports the result complete: silent loss in the saved image, wearing
        // an "Added N KB" message. For a data-only prefix dump the frontier IS
        // the final block, so the old shape is unchanged.
        client.transcript.note(
            "wrap probe matches; frontier at 0x%08X, extending from 0x%08X"
                .format(frontierOffset(previous, usable), from)
        )
        // Block boundary, as in [download]: empty the in-flight bar so it does
        // not sit at 64/64 KB while the first extend chunk is still on the wire.
        onProgress(Progress(from, fromSectors, 0, blockBytes = 0, blockSizeBytes = blockSize))
        return Plan.Extend(from, usable, sectorsProbed = 1)
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
        /** Ranges of [previous] that never arrived; see [download]. */
        priorDamage: List<IntRange> = emptyList(),
    ): Result {
        val plan = planIncremental(previous, onProgress, shouldContinue)
        // The probe's sectors are work this transfer did, so they are carried
        // into everything downstream reports. [download] counts only its own
        // reads and cannot know a probe ran.
        val probed = plan.sectorsProbed
        val withProbe: (Progress) -> Unit =
            { onProgress(it.copy(sectorsFetched = it.sectorsFetched + probed)) }

        if (!shouldContinue()) {
            // Not a wrap and not a failure: the user stopped it. Falling through
            // to the full-download branch would note "full download required"
            // for a transfer that was never going to run.
            client.transcript.note("cancelled during the wrap probe; nothing fetched")
            // Nothing toward the image -- but the probe still crossed the wire,
            // and a cancel does not unspend it.
            return Result(
                ByteArray(0), sectorSpan = 0, retries = 0, stoppedOnUnwritten = false,
                sectorsFetched = probed,
            )
        }
        return when (plan) {
            is Plan.Extend ->
                download(previous, plan.fromOffset, withProbe, shouldContinue, priorDamage)
                    .let { it.copy(sectorsFetched = it.sectorsFetched + probed) }
            is Plan.FullRequired -> {
                client.transcript.note("full download required: ${plan.reason}")
                // The plan is abandoned, so the position the probe reported no
                // longer describes anything. Zero it in one deliberate step: a
                // full re-read genuinely does start from nothing, and letting
                // the first block's callback drop it silently would look like
                // exactly the backwards jump this class no longer makes.
                // The work counter is not zeroed with it: the probe happened.
                onProgress(
                    Progress(0, 0, 0, blockBytes = 0, blockSizeBytes = blockSize,
                        sectorsFetched = probed)
                )
                download(ByteArray(0), 0, withProbe, shouldContinue)
                    .let { it.copy(fullReread = true, sectorsFetched = it.sectorsFetched + probed) }
            }
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
        /**
         * Ranges of [existing] that never arrived when it was downloaded.
         *
         * **Damage is a property of the image, not of the call that produced
         * it.** Without this the resume path laundered a holed dump clean: the
         * holes were copied forward inside [existing], the new result reported
         * `damagedBytes = 0` and `isComplete = true`, the `.partial` marker was
         * deleted, and the erase gate opened on an image whose 0xFF gaps are
         * indistinguishable from erased flash. See §16.1.
         *
         * Given them, this re-reads the affected blocks before extending and
         * carries whatever is still missing into [Result.damagedRanges], so the
         * dump either gets repaired or stays honestly marked.
         */
        priorDamage: List<IntRange> = emptyList(),
    ): Result {
        require(resumeFrom % blockSize == 0) {
            "resume offset $resumeFrom must be a multiple of the $blockSize block size"
        }

        var address = resumeFrom
        var sectors = resumeFrom / SectorHeader.SECTOR_SIZE
        // Sectors this call pulls off the device, as against the prefix it was
        // handed. Counted once per position; a block that needed three attempts
        // is one sector fetched and two retries.
        var fetched = 0
        var retries = 0
        var consecutiveUnwritten = 0
        var stoppedOnUnwritten = false
        var stoppedUnanswered = false
        val damaged = mutableListOf<IntRange>()

        // The prefix is repaired in place before anything is appended to it, so
        // the bytes written below are the best copy available rather than the
        // one that arrived first.
        val prefix = existing.copyOf(minOf(existing.size, resumeFrom))
        if (priorDamage.isNotEmpty()) {
            val repair = repair(prefix, priorDamage, onProgress, shouldContinue)
            damaged += repair.stillMissing
            fetched += repair.blocksRead
        }

        val out = java.io.ByteArrayOutputStream(maxOf(existing.size, blockSize))
        out.write(prefix, 0, prefix.size)

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
            var block = client.readLogBlock(
                address, blockSize, blockIdleTimeoutMillis,
                onChunk = { filledInBlock ->
                    onProgress(
                        Progress(
                            bytesDownloaded = out.size() + filledInBlock,
                            sectorPosition = sectors,
                            retries = retries,
                            blockBytes = filledInBlock,
                            blockSizeBytes = blockSize,
                            sectorsFetched = fetched,
                        )
                    )
                },
                shouldContinue = shouldContinue,
            )

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
                block = client.readLogBlock(
                address, blockSize, blockIdleTimeoutMillis,
                onChunk = { filledInBlock ->
                    onProgress(
                        Progress(
                            bytesDownloaded = out.size() + filledInBlock,
                            sectorPosition = sectors,
                            retries = retries,
                            blockBytes = filledInBlock,
                            blockSizeBytes = blockSize,
                            sectorsFetched = fetched,
                        )
                    )
                },
                shouldContinue = shouldContinue,
            )
            }

            // Cancelled mid-block: discard the partial block in flight and keep
            // only the whole blocks already written. Resume re-reads cleanly
            // from here, so nothing is lost by not waiting the block out -- and
            // over Bluetooth that wait is minutes.
            if (!shouldContinue()) break

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
                stoppedUnanswered = true
                client.transcript.note(
                    ("block 0x%08X returned nothing after $ATTEMPTS_PER_BLOCK attempts; " +
                        "the device has stopped answering reads").format(address)
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
            fetched++

            consecutiveUnwritten =
                if (isUnwritten(block.bytes)) consecutiveUnwritten + 1 else 0

            // Block boundary: the in-flight bar resets to empty for the next
            // sector, and bytesDownloaded is now the exact completed total.
            onProgress(
                Progress(
                    address, sectors, retries,
                    blockBytes = 0, blockSizeBytes = blockSize, sectorsFetched = fetched,
                )
            )

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
            out.toByteArray(), sectors, retries, stoppedOnUnwritten, failure, damaged,
            stoppedUnanswered = stoppedUnanswered,
            sectorsFetched = fetched,
        )
    }

    private fun isUnwritten(block: ByteArray): Boolean =
        block.all { it == PmtkClient.UNWRITTEN }

    /** Outcome of a repair pass: what is still missing, and what it cost. */
    private data class Repair(val stillMissing: List<IntRange>, val blocksRead: Int)

    /**
     * Re-read the blocks of [image] that are known to be missing bytes.
     *
     * The ranges are known exactly, the device serves whole blocks reliably --
     * that is the only request shape it has ever honoured -- and re-reading one
     * costs what any other block costs. So a resume can *recover* the holes a
     * bad link left rather than merely reporting them, which is the difference
     * between a dump that needs three more hours and one that needs two more
     * minutes.
     *
     * Only the previously-missing bytes are taken from the new read. A block
     * that comes back short must not be allowed to overwrite good bytes with
     * fresh 0xFF, which would turn a repair into more damage.
     *
     * Ranges outside [image] are dropped: they lie past the resume point and
     * the main loop is about to read them from scratch.
     */
    private suspend fun repair(
        image: ByteArray,
        prior: List<IntRange>,
        onProgress: (Progress) -> Unit,
        shouldContinue: () -> Boolean,
    ): Repair {
        // Split the damage per block, clamped to what the prefix actually holds.
        val byBlock = sortedMapOf<Int, MutableList<IntRange>>()
        for (range in prior) {
            var i = maxOf(range.first, 0)
            val last = minOf(range.last, image.size - 1)
            while (i <= last) {
                val blockAddress = (i / blockSize) * blockSize
                val end = minOf(last, blockAddress + blockSize - 1)
                byBlock.getOrPut(blockAddress) { mutableListOf() } += i..end
                i = end + 1
            }
        }
        if (byBlock.isEmpty()) return Repair(emptyList(), 0)

        client.transcript.note(
            "repairing ${byBlock.size} damaged block(s) before extending"
        )

        val stillMissing = mutableListOf<IntRange>()
        var blocksRead = 0

        for ((blockAddress, ranges) in byBlock) {
            val missing = BooleanArray(blockSize)
            for (range in ranges) {
                for (i in range) missing[i - blockAddress] = true
            }

            if (blockAddress + blockSize <= image.size && shouldContinue()) {
                client.transcript.note("re-reading damaged block 0x%08X".format(blockAddress))
                val block = client.readLogBlock(
                    blockAddress, blockSize, blockIdleTimeoutMillis,
                    onChunk = { filledInBlock ->
                        onProgress(
                            Progress(
                                bytesDownloaded = image.size,
                                sectorPosition = blockAddress / SectorHeader.SECTOR_SIZE,
                                retries = 0,
                                blockBytes = filledInBlock,
                                blockSizeBytes = blockSize,
                                sectorsFetched = blocksRead,
                            )
                        )
                    },
                    shouldContinue = shouldContinue,
                )
                blocksRead++

                val absent = BooleanArray(blockSize)
                for (gap in block.gaps) {
                    for (i in gap) absent[i - blockAddress] = true
                }
                for (i in 0 until blockSize) {
                    if (missing[i] && !absent[i]) {
                        image[blockAddress + i] = block.bytes[i]
                        missing[i] = false
                    }
                }
            }

            // Whatever the re-read did not bring back is still damage.
            var runStart = -1
            for (i in 0 until blockSize) {
                if (missing[i]) {
                    if (runStart < 0) runStart = i
                } else if (runStart >= 0) {
                    stillMissing += (blockAddress + runStart)..(blockAddress + i - 1)
                    runStart = -1
                }
            }
            if (runStart >= 0) {
                stillMissing += (blockAddress + runStart)..(blockAddress + blockSize - 1)
            }
        }

        val recovered = prior.sumOf { it.last - it.first + 1 } -
            stillMissing.sumOf { it.last - it.first + 1 }
        client.transcript.note(
            if (stillMissing.isEmpty()) {
                "repaired all ${Bytes.describe(recovered)} of previously missing data"
            } else {
                "repaired ${Bytes.describe(recovered)}; " +
                    "${stillMissing.sumOf { it.last - it.first + 1 }} bytes still missing"
            }
        )
        return Repair(stillMissing, blocksRead)
    }

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

        /**
         * The exclusive end of real data in [image] -- the write frontier.
         *
         * Trailing `0xFF` is unwritten flash, not content, so the frontier sits
         * one past the last byte the logger actually wrote. This is the same
         * number [planIncremental] notes to the transcript as `frontier at
         * 0x...`, which means anything derived from it can be checked against
         * the log rather than taken on trust.
         *
         * [limit] bounds the search, for callers that only trust a prefix of
         * the image. Returns 0 when there is no real data at all.
         */
        fun frontierOffset(image: ByteArray, limit: Int = image.size): Int {
            var last = minOf(limit, image.size) - 1
            while (last >= 0 && image[last] == PmtkClient.UNWRITTEN) last--
            return last + 1
        }

        /**
         * Bytes of new logging that [updated] holds and [previous] did not.
         *
         * **Measured from the write frontier, never from file size.** New
         * records land at the frontier, which usually sits mid-sector, so a
         * fetch that adds half an hour of riding produces a file of exactly the
         * same length as the one it extended -- 327,680 bytes before and after,
         * in the 2026-09-15 run that found this. A size delta therefore reports
         * 0, and the app tells the user "nothing new on the logger" about a
         * transfer that just brought back their ride. That is the §12 failure
         * shape: a confident negative claim about capture that is false.
         *
         * The log is append-only between wraps, so every byte between the two
         * frontiers is new. That assumption fails across a wrap or an erase,
         * where the result is meaningless and may be negative -- callers must
         * check [Result.fullReread] first.
         */
        fun newDataBytes(previous: ByteArray, updated: ByteArray): Int =
            frontierOffset(updated) - frontierOffset(previous)

        /**
         * The offset a fetch-new would resume from, or null if [previous]
         * cannot be extended at all.
         *
         * New records land at the write frontier, which normally sits partway
         * through a sector, so the re-read starts at the block *containing*
         * the frontier -- not past the end of the dump, and not at the end of
         * its data. Shared with the caller so the UI can say where the transfer
         * will resume without guessing, and so "resumed from" and the byte
         * counter cannot disagree.
         */
        fun extendOffset(previous: ByteArray, blockSize: Int = DEFAULT_BLOCK_SIZE): Int? {
            val usable = (previous.size / blockSize) * blockSize
            if (usable < 2 * blockSize) return null
            val frontier = frontierOffset(previous, usable)
            if (frontier == 0) return null
            return ((frontier - 1) / blockSize) * blockSize
        }
    }
}
