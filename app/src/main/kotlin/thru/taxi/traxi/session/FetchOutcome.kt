package thru.taxi.traxi.session

import thru.taxi.traxi.core.format.Bytes
import thru.taxi.traxi.core.format.SectorHeader
import thru.taxi.traxi.core.protocol.FlashDownloader

/**
 * What a fetch-new shows the user, derived only from facts the transfer
 * returned.
 *
 * Pure, and outside [SessionController], because the wrong answer here is the
 * bug of 2026-09-15: a fetch brought back a 30.5-minute ride — 161 fixes,
 * 8,432 bytes — and the app reported *"Already up to date — nothing new on the
 * logger."* The old test was `result.image.size - previous.size`, and both
 * dumps were 327,680 bytes, because new records land at the frontier and the
 * frontier sat mid-sector with room to spare.
 *
 * That is the §12 failure shape: a confident negative claim about capture that
 * is false. It lived in a coroutine inside a class that needs a `Context` and a
 * Bluetooth stack to build, so nothing could assert on it. This file exists so
 * something can.
 */
object FetchOutcome {

    /**
     * The seed state for an extend, before any byte is on the wire.
     *
     * Every figure comes from the resume point, so the readout cannot open by
     * contradicting itself. Seeding from `previous.size` put the counter two
     * sectors ahead of the first thing the downloader reports, so it fell
     * backwards before anything had been fetched; leaving [DownloadState
     * .sectorPosition] at its default put "128 KB" beside "Sectors done 0".
     */
    fun extendSeed(previous: ByteArray): DownloadState {
        val resumeAt = FlashDownloader.extendOffset(previous) ?: 0
        return DownloadState(
            running = true,
            bytesDownloaded = resumeAt,
            sectorPosition = resumeAt / SectorHeader.SECTOR_SIZE,
            resumedFrom = resumeAt,
        )
    }

    /**
     * The message shown when a fetch-new ends.
     *
     * Order matters. A failure or a cancel describes how the transfer stopped
     * and outranks anything about content. A full re-read comes next, because
     * after a wrap the previous dump's frontier is not a baseline and no
     * "added N" claim can honestly be made in either direction. Only then is
     * the gain worth reporting — measured from the frontier, never from length.
     */
    fun message(
        result: FlashDownloader.Result,
        previous: ByteArray,
        targetName: String,
        cancelled: Boolean,
    ): String {
        val gained = FlashDownloader.newDataBytes(previous, result.image)
        val size = Bytes.describe(result.image.size)
        return when {
            result.failure != null -> "Interrupted — $size saved and resumable"
            // Cancelled mid-extend: "already up to date" would present a stop
            // the user requested as a checked fact about the logger.
            cancelled && !result.isComplete -> "Cancelled — $size saved and resumable"
            result.fullReread -> "Re-read the whole log — $size in $targetName"
            gained > 0 -> "Added ${Bytes.describe(gained)} of new tracking to $targetName"
            else -> "Already up to date — nothing new on the logger"
        }
    }
}
