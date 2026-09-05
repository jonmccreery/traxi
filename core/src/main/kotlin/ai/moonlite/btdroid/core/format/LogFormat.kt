package ai.moonlite.btdroid.core.format

/**
 * A field in the MTK log-format register (`FMT_REG`).
 *
 * Field order in a record follows ascending bit position, which is the order
 * these are declared in. Widths follow mtkbabel 0.8.4.
 *
 * Two long-standing traps live in this table:
 *  - [ELEVATION] is *satellite* elevation angle. Altitude is [HEIGHT].
 *  - [SID] is variable length, so record size is not a pure function of the
 *    bitmask popcount when it is set. See [LogFormat.recordSize].
 */
/**
 * Width sentinel for fields whose size is not fixed by the format register.
 * Top-level rather than in [LogField.Companion], because enum entries are
 * initialized before their companion object.
 */
const val VARIABLE_WIDTH = -1

enum class LogField(val bit: Int, val width: Int) {
    UTC(0x00000001, 4),
    VALID(0x00000002, 2),
    LATITUDE(0x00000004, 8),
    LONGITUDE(0x00000008, 8),
    HEIGHT(0x00000010, 4),
    SPEED(0x00000020, 4),
    HEADING(0x00000040, 4),
    DSTA(0x00000080, 2),
    DAGE(0x00000100, 4),
    PDOP(0x00000200, 2),
    HDOP(0x00000400, 2),
    VDOP(0x00000800, 2),
    NSAT(0x00001000, 2),
    SID(0x00002000, VARIABLE_WIDTH),
    ELEVATION(0x00004000, 2),
    AZIMUTH(0x00008000, 2),
    SNR(0x00010000, 2),
    RCR(0x00020000, 2),
    MILLISECOND(0x00040000, 2),
    DISTANCE(0x00080000, 8);

    val isVariableWidth: Boolean get() = width == VARIABLE_WIDTH
}

/** Raised when a record cannot be sized or decoded from a given format register. */
class UnsupportedLogFormatException(message: String) : Exception(message)

/**
 * The 32-bit log-format register, describing which fields each record carries.
 *
 * `FMT_REG` can change mid-log via a [DynamicMarker] of type
 * [DynamicMarker.CHANGE_FORMAT], and is also restated in every sector header,
 * so it must be re-read per sector rather than latched once.
 */
@JvmInline
value class LogFormat(val bits: Int) {

    val fields: List<LogField>
        get() = LogField.entries.filter { bits and it.bit != 0 }

    operator fun contains(field: LogField): Boolean = bits and field.bit != 0

    /**
     * Payload width in bytes, excluding the trailing `'*'` and checksum byte.
     *
     * @throws UnsupportedLogFormatException if the satellite block is enabled.
     *   Records would have to be parsed forward rather than sized up front, and
     *   no dump from this device has ever set it. Rejecting is safer than
     *   silently misparsing every subsequent record in the sector.
     */
    fun recordSize(): Int {
        var total = 0
        for (field in fields) {
            if (field.isVariableWidth) {
                throw UnsupportedLogFormatException(
                    "format 0x%08X sets %s (variable length); not supported"
                        .format(bits, field.name)
                )
            }
            total += field.width
        }
        return total
    }

    /** Total on-flash record size including the `'*'` separator and checksum byte. */
    fun recordSizeWithChecksum(): Int = recordSize() + 2

    fun describe(): String = fields.joinToString(",") { it.name }

    override fun toString(): String = "0x%08X (%s)".format(bits, describe())

    companion object {
        /**
         * The format this device has used for its entire 18-month history:
         * `UTC,VALID,LATITUDE,LONGITUDE,HEIGHT,SPEED,RCR,DISTANCE`, 42 bytes
         * on flash. Used as a sanity default, never as an assumption.
         */
        val OBSERVED = LogFormat(0x000A003F)

        /**
         * [OBSERVED] plus `NSAT`, `HDOP` and `VDOP`. Writing this is the only
         * way to obtain per-fix satellite geometry; nothing in the existing
         * flash carries it. Grows records 42 -> 48 bytes (+14%), a proportional
         * reduction in flash-hours, so it must be a deliberate user choice.
         */
        val WITH_SATELLITE_QUALITY = LogFormat(0x000A1C3F)
    }
}
