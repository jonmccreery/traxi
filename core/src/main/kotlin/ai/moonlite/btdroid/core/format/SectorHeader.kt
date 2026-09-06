package ai.moonlite.btdroid.core.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The header at the start of every 64 KiB flash sector.
 *
 * Resolves the long-standing "56-byte pattern vs 512-byte reserved area"
 * ambiguity: both are true. The fields occupy the first 20 bytes; records begin
 * at [SIZE] (512). Verified byte-for-byte against sector 0 of the reference
 * dump.
 *
 * ```
 * 0x000  u16  recordCount        0xFFFF while the sector is still being written
 * 0x002  u32  FMT_REG            0x000A003F on this device
 * 0x006  u16  mode               0x0100 / 0x0102
 * 0x008  u32  time criterion     200 = 20.0 s
 * 0x00C  u32  distance criterion 0
 * 0x010  u32  speed criterion    0
 * 0x014..0x1F9  0xFF fill
 * 0x1FA  '*' + checksum + BB BB BB BB
 * 0x200  first record
 * ```
 */
data class SectorHeader(
    /**
     * Records in this sector, or null if the sector is still being written.
     *
     * The device only finalizes this when the sector fills; the active sector
     * reads 0xFFFF. A parser that trusts this value drops the most recent
     * sector outright - on the reference dump, the 1,461 fixes the user just
     * walked. Treat it as a cross-check, never as an authority: parse until
     * 0xFF fill instead.
     */
    val recordCount: Int?,
    val format: LogFormat,
    val mode: Int,
    /** Time logging criterion in units of 0.1 s. 200 = 20.0 s. */
    val timeIntervalTenths: Int,
    /** Distance logging criterion in units of 0.1 m. 0 = disabled. */
    val distanceIntervalTenths: Int,
    /** Speed logging criterion in units of 0.1 km/h. 0 = disabled. */
    val speedLimitTenths: Int,
) {
    val timeIntervalSeconds: Double get() = timeIntervalTenths / 10.0

    val isCountFinalized: Boolean get() = recordCount != null

    companion object {
        /** Bytes reserved at the head of each sector before the first record. */
        const val SIZE = 0x200

        /** Flash sector size. */
        const val SECTOR_SIZE = 0x10000

        private const val COUNT_UNFINALIZED = 0xFFFF
        private const val MARKER_OFFSET = 0x1FA

        /**
         * Parse a sector header, or return null if [buf] does not carry the
         * trailing `'*' <cksum> BB BB BB BB` signature that marks a written
         * sector.
         */
        fun parse(buf: ByteArray, offset: Int = 0): SectorHeader? {
            if (offset + SIZE > buf.size) return null
            val star = offset + MARKER_OFFSET
            if (buf[star] != '*'.code.toByte()) return null
            for (i in 0 until 4) {
                if (buf[star + 2 + i] != 0xBB.toByte()) return null
            }

            val bb = ByteBuffer.wrap(buf, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val rawCount = bb.getShort(offset + 0).toInt() and 0xFFFF
            return SectorHeader(
                recordCount = if (rawCount == COUNT_UNFINALIZED) null else rawCount,
                format = LogFormat(bb.getInt(offset + 2)),
                mode = bb.getShort(offset + 6).toInt() and 0xFFFF,
                timeIntervalTenths = bb.getInt(offset + 8),
                distanceIntervalTenths = bb.getInt(offset + 12),
                speedLimitTenths = bb.getInt(offset + 16),
            )
        }

        /** True if the first 16 bytes are 0xFF, i.e. the sector was never written. */
        fun isUnwritten(buf: ByteArray, offset: Int): Boolean {
            if (offset + 16 > buf.size) return true
            for (i in 0 until 16) {
                if (buf[offset + i] != 0xFF.toByte()) return false
            }
            return true
        }
    }
}

/**
 * A 16-byte in-stream control record:
 * `AA AA AA AA AA AA AA <type> <arg:i32-le> BB BB BB BB`.
 *
 * Observed across the reference dump's 580 markers:
 *
 * | Type | Arg | Count | Meaning |
 * |---|---|---|---|
 * | 0x07 | 0x0100 / 0x0102 | 200 each | log enable/disable |
 * | 0x04 | 0 | 90 | distance criterion write (no-op) |
 * | 0x05 | 0 | 90 | speed criterion write (no-op) |
 *
 * Notably [CHANGE_FORMAT] occurs **zero** times, so the 79 track segments in
 * the reference data are power cycles, not format changes. It is still handled,
 * because a config write could introduce one.
 */
data class DynamicMarker(val type: Int, val arg: Int, val offset: Int) {
    companion object {
        const val SIZE = 0x10

        const val CHANGE_FORMAT = 0x02
        const val CHANGE_PERIOD = 0x03
        const val CHANGE_DISTANCE = 0x04
        const val CHANGE_SPEED = 0x05
        const val CHANGE_OVERWRITE = 0x06
        const val LOG_STATUS = 0x07

        /**
         * Bit in a [LOG_STATUS] marker's arg meaning "recording".
         *
         * The same bit the `PMTK182,2,7` query reports, and established the
         * same way: the reference dump pairs args `0x0100` and `0x0102` in
         * near-equal numbers, and `0x02` is the only bit that differs.
         */
        const val LOGGING_ENABLED = 0x0002

        /** Parse a marker at [offset], or return null if the signature is absent. */
        fun parse(buf: ByteArray, offset: Int): DynamicMarker? {
            if (offset + SIZE > buf.size) return null
            for (i in 0 until 7) {
                if (buf[offset + i] != 0xAA.toByte()) return null
            }
            for (i in 12 until 16) {
                if (buf[offset + i] != 0xBB.toByte()) return null
            }
            val arg = ByteBuffer.wrap(buf, offset + 8, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            return DynamicMarker(buf[offset + 7].toInt() and 0xFF, arg, offset)
        }
    }
}
