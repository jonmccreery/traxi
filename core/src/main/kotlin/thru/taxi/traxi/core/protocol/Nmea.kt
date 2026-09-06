package thru.taxi.traxi.core.protocol

/**
 * One parsed NMEA-0183 sentence.
 *
 * [fields] excludes the leading `$` and the `*<checksum>` tail, split on commas.
 * For `$PMTK182,3,2,000A003F*29` that is `["PMTK182", "3", "2", "000A003F"]`.
 */
data class NmeaSentence(
    val fields: List<String>,
    /** The sentence as received, without CRLF. Kept verbatim for the transcript. */
    val raw: String,
) {
    /** The sentence type, e.g. `PMTK182` or `PMTK001`. */
    val type: String get() = fields.firstOrNull().orEmpty()

    operator fun get(index: Int): String? = fields.getOrNull(index)

    /** True if the first [prefix] fields match, used for ack matching. */
    fun matches(vararg prefix: String): Boolean {
        if (prefix.size > fields.size) return false
        return prefix.withIndex().all { (i, expected) -> fields[i] == expected }
    }
}

/**
 * NMEA-0183 framing, as used by the PMTK command family.
 *
 * A sentence is `$` + payload + `*` + two uppercase hex digits + CRLF, where the
 * checksum is the XOR of every byte strictly between `$` and `*`.
 */
object Nmea {

    const val CRLF = "\r\n"

    /** XOR checksum over [payload], the bytes between `$` and `*`. */
    fun checksum(payload: String): Int {
        var c = 0
        for (ch in payload) c = c xor ch.code
        return c and 0xFF
    }

    /** Wrap [payload] into a complete sentence, ready to write to a transport. */
    fun frame(payload: String): String =
        "$" + payload + "*" + "%02X".format(checksum(payload)) + CRLF

    /**
     * Parse one received line.
     *
     * @return the sentence, or null if it is malformed or fails checksum.
     *   Returning null rather than throwing is deliberate: the transport is a
     *   raw byte stream with no framing guarantees, so partial and corrupt
     *   lines are an expected steady-state condition, not an error.
     */
    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.length < 4 || trimmed[0] != '$') return null

        val star = trimmed.lastIndexOf('*')
        if (star < 1 || star + 3 > trimmed.length) return null

        val payload = trimmed.substring(1, star)
        val expected = trimmed.substring(star + 1, star + 3)
        val actual = "%02X".format(checksum(payload))
        if (!expected.equals(actual, ignoreCase = true)) return null

        return NmeaSentence(payload.split(','), trimmed)
    }
}

/**
 * Reassembles complete NMEA sentences from an arbitrarily-chunked byte stream.
 *
 * Bluetooth RFCOMM delivers whatever happens to be in the buffer, so a single
 * read may contain half a sentence, three sentences, or a sentence split across
 * two reads. This holds the remainder between calls.
 */
class NmeaLineAssembler(private val maxLineLength: Int = 64 * 1024) {

    private val buffer = StringBuilder()

    /** Feed received bytes; returns whatever complete lines that completed. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until length) {
            when (val ch = bytes[i].toInt().toChar()) {
                '\n' -> {
                    if (buffer.isNotEmpty()) {
                        lines += buffer.toString().trimEnd('\r')
                        buffer.setLength(0)
                    }
                }
                '\r' -> Unit
                else -> {
                    // Guard against a stream with no line terminators at all,
                    // which would otherwise grow this buffer without bound.
                    if (buffer.length >= maxLineLength) buffer.setLength(0)
                    buffer.append(ch)
                }
            }
        }
        return lines
    }

    fun reset() = buffer.setLength(0)
}
