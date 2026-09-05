package ai.moonlite.btdroid.core.format

import ai.moonlite.btdroid.core.format.SectorHeader.Companion.SECTOR_SIZE

/**
 * Walks a raw MTK flash dump and decodes every record it contains.
 *
 * Deliberately a straight port of the reference `mtkparse.py` control flow, so
 * that any divergence in output against the golden pair is a real defect rather
 * than a design difference.
 *
 * The parser never trusts the device or the flash metadata:
 *  - format register is re-read from every sector header and can be changed
 *    mid-sector by a [DynamicMarker];
 *  - the sector record count is a cross-check, not a bound (it is 0xFFFF in the
 *    sector currently being written);
 *  - checksum failures are counted, not fatal.
 */
object MtkLogParser {

    data class Stats(
        var sectorsWithData: Int = 0,
        var records: Int = 0,
        var checksumFailures: Int = 0,
        var markers: Int = 0,
        val unwrittenSectors: MutableList<Int> = mutableListOf(),
        val badSectorHeaders: MutableList<Int> = mutableListOf(),
        val formatChanges: MutableList<Pair<Int, LogFormat>> = mutableListOf(),
        val headers: MutableList<SectorHeader> = mutableListOf(),
    ) {
        /**
         * Sum of the finalized sector counts. Falls short of [records] by the
         * contents of the active sector, which is expected and is exactly the
         * data a count-trusting parser would lose.
         */
        val declaredRecordCount: Int
            get() = headers.sumOf { it.recordCount ?: 0 }
    }

    data class Result(val fixes: List<Fix>, val stats: Stats)

    /**
     * @param data the entire flash image. Always the whole dump - the log is a
     *   circular buffer, so parsing only up to the write pointer silently
     *   discards everything before a wrap.
     * @param rollover week-rollover correction; see [GpsRollover].
     * @param onProgress invoked with bytes consumed, for long parses on a phone.
     */
    fun parse(
        data: ByteArray,
        rollover: GpsRollover = GpsRollover.AXN_130B,
        onProgress: ((consumed: Int, total: Int) -> Unit)? = null,
    ): Result {
        val stats = Stats()
        val fixes = ArrayList<Fix>(estimateCapacity(data.size))
        var offset = 0
        var format: LogFormat? = null
        var progressMark = 0

        while (offset < data.size) {
            if (offset % SECTOR_SIZE == 0) {
                val sectorIndex = offset / SECTOR_SIZE
                if (offset + SectorHeader.SIZE > data.size) break

                if (SectorHeader.isUnwritten(data, offset)) {
                    stats.unwrittenSectors += sectorIndex
                    offset += SECTOR_SIZE
                    continue
                }
                val header = SectorHeader.parse(data, offset)
                if (header == null) {
                    stats.badSectorHeaders += sectorIndex
                    offset += SECTOR_SIZE
                    continue
                }
                stats.sectorsWithData++
                stats.headers += header
                format = header.format
                offset += SectorHeader.SIZE
                continue
            }

            val fmt = format
            if (fmt == null) {
                offset++
                continue
            }

            val remainingInSector = SECTOR_SIZE - (offset % SECTOR_SIZE)

            // --- dynamic markers and unwritten fill ---
            if (remainingInSector >= DynamicMarker.SIZE &&
                offset + DynamicMarker.SIZE <= data.size
            ) {
                val marker = DynamicMarker.parse(data, offset)
                if (marker != null) {
                    stats.markers++
                    if (marker.type == DynamicMarker.CHANGE_FORMAT) {
                        format = LogFormat(marker.arg)
                        stats.formatChanges += offset to LogFormat(marker.arg)
                    }
                    offset += DynamicMarker.SIZE
                    continue
                }
                if (isFill(data, offset, DynamicMarker.SIZE)) {
                    offset = nextSector(offset)
                    continue
                }
            }

            // --- a log record ---
            val payloadLen = try {
                fmt.recordSize()
            } catch (e: UnsupportedLogFormatException) {
                // Cannot size records under this format; the rest of the sector
                // is unparseable. Skip it rather than misinterpreting bytes.
                offset = nextSector(offset)
                continue
            }

            if (payloadLen == 0 || offset + payloadLen + 2 > data.size) break
            if (payloadLen + 2 > remainingInSector) {
                offset = nextSector(offset)
                continue
            }

            val decoded = RecordParser.parse(data, offset, fmt, rollover) ?: break
            stats.records++
            if (!decoded.checksumOk) stats.checksumFailures++
            decoded.fix?.let { fixes += it }

            offset += payloadLen + 2

            if (onProgress != null && offset - progressMark >= PROGRESS_STRIDE) {
                progressMark = offset
                onProgress(offset, data.size)
            }
        }

        onProgress?.invoke(data.size, data.size)
        return Result(fixes, stats)
    }

    private const val PROGRESS_STRIDE = 256 * 1024

    private fun nextSector(offset: Int): Int =
        SECTOR_SIZE * (offset / SECTOR_SIZE + 1)

    private fun isFill(buf: ByteArray, offset: Int, len: Int): Boolean {
        for (i in offset until offset + len) {
            if (buf[i] != 0xFF.toByte()) return false
        }
        return true
    }

    /** Rough upper bound so the fix list does not repeatedly reallocate. */
    private fun estimateCapacity(bytes: Int): Int = bytes / 42 + 16
}
