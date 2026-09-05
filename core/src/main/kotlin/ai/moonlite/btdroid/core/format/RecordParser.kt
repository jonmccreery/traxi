package ai.moonlite.btdroid.core.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a single log record given the format register in force.
 *
 * Pure: `(bytes, offset, format, rollover) -> DecodedRecord`. No I/O, no state.
 * This is the piece the golden-file test exercises 126,613 times.
 */
object RecordParser {

    private const val SEPARATOR = '*'.code.toByte()

    data class DecodedRecord(val fix: Fix?, val checksumOk: Boolean)

    /**
     * Decode the record starting at [offset].
     *
     * @return the decoded record, or null if [buf] is too short to hold one.
     */
    fun parse(
        buf: ByteArray,
        offset: Int,
        format: LogFormat,
        rollover: GpsRollover,
    ): DecodedRecord? {
        val payloadLen = format.recordSize()
        if (payloadLen == 0 || offset + payloadLen + 2 > buf.size) return null

        val separator = buf[offset + payloadLen]
        val checksum = buf[offset + payloadLen + 1]
        val checksumOk = separator == SEPARATOR && xor(buf, offset, payloadLen) == checksum

        // A record whose checksum fails may be arbitrarily corrupt; decoding it
        // would produce plausible-looking garbage. Count it and move on.
        if (!checksumOk) return DecodedRecord(null, false)

        val bb = ByteBuffer.wrap(buf, offset, payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        var pos = offset

        var utcRaw: Int? = null
        var valid: Int? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var height: Float? = null
        var speed: Float? = null
        var heading: Float? = null
        var dsta: Int? = null
        var dage: Int? = null
        var pdop: Int? = null
        var hdop: Int? = null
        var vdop: Int? = null
        var satsInView: Int? = null
        var satsInUse: Int? = null
        var rcr: Int? = null
        var millisecond: Int? = null
        var distance: Double? = null

        for (field in format.fields) {
            when (field) {
                LogField.UTC -> utcRaw = bb.getInt(pos)
                LogField.VALID -> valid = bb.getShort(pos).toInt() and 0xFFFF
                LogField.LATITUDE -> latitude = bb.getDouble(pos)
                LogField.LONGITUDE -> longitude = bb.getDouble(pos)
                LogField.HEIGHT -> height = bb.getFloat(pos)
                LogField.SPEED -> speed = bb.getFloat(pos)
                LogField.HEADING -> heading = bb.getFloat(pos)
                LogField.DSTA -> dsta = bb.getShort(pos).toInt() and 0xFFFF
                LogField.DAGE -> dage = bb.getInt(pos)
                LogField.PDOP -> pdop = bb.getShort(pos).toInt() and 0xFFFF
                LogField.HDOP -> hdop = bb.getShort(pos).toInt() and 0xFFFF
                LogField.VDOP -> vdop = bb.getShort(pos).toInt() and 0xFFFF
                LogField.NSAT -> {
                    // Two independent bytes, not a little-endian u16.
                    satsInView = buf[pos].toInt() and 0xFF
                    satsInUse = buf[pos + 1].toInt() and 0xFF
                }
                LogField.ELEVATION -> Unit  // satellite elevation angle; not altitude
                LogField.AZIMUTH -> Unit
                LogField.SNR -> Unit
                LogField.RCR -> rcr = bb.getShort(pos).toInt() and 0xFFFF
                LogField.MILLISECOND -> millisecond = bb.getShort(pos).toInt() and 0xFFFF
                LogField.DISTANCE -> distance = bb.getDouble(pos)
                LogField.SID -> throw UnsupportedLogFormatException(
                    "SID/satellite block at 0x%08X is not supported".format(pos)
                )
            }
            pos += field.width
        }

        // A record without a timestamp cannot be placed in a track.
        val utc = utcRaw ?: return DecodedRecord(null, true)

        return DecodedRecord(
            Fix(
                utcRaw = utc,
                epochSeconds = rollover.apply(utc),
                valid = valid,
                latitude = latitude,
                longitude = longitude,
                height = height,
                speed = speed,
                heading = heading,
                dsta = dsta,
                dage = dage,
                pdop = pdop,
                hdop = hdop,
                vdop = vdop,
                satellitesInView = satsInView,
                satellitesInUse = satsInUse,
                rcr = rcr,
                millisecond = millisecond,
                distance = distance,
            ),
            checksumOk = true,
        )
    }

    /** XOR checksum over [len] bytes starting at [offset]. */
    fun xor(buf: ByteArray, offset: Int, len: Int): Byte {
        var c = 0
        for (i in offset until offset + len) c = c xor (buf[i].toInt() and 0xFF)
        return c.toByte()
    }
}
