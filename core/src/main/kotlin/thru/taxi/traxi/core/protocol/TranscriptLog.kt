package thru.taxi.traxi.core.protocol

/**
 * Reading the transcript back: splitting the file into app runs, and telling
 * the conversation apart from the NMEA flood.
 *
 * Lines are `HH:mm:ss.SSS <dir> <text>` as [RingTranscript] writes them, with
 * `>>` sent, `<<` received and `--` the app's own notes. Each app start writes
 * a `=== app started yyyy-MM-dd HH:mm:ss — ... ===` banner (TranscriptFile),
 * which is the only place a date appears.
 */
object TranscriptLog {

    /** One app run: its banner (null for lines before the first) and its lines. */
    data class Run(val banner: String?, val lines: List<String>) {
        /** "2026-10-07 19:17", or null when there is no banner. */
        val startedAt: String?
            get() = banner?.let { BANNER.find(it)?.groupValues?.get(1) }
    }

    private val BANNER = Regex("""^=== app started (\d{4}-\d{2}-\d{2} \d{2}:\d{2})""")

    /** Split a transcript file into runs, oldest first. Blank lines are dropped. */
    fun runs(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        var banner: String? = null
        var lines = mutableListOf<String>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("=== app started")) {
                if (banner != null || lines.isNotEmpty()) runs += Run(banner, lines)
                banner = line
                lines = mutableListOf()
            } else {
                lines += line
            }
        }
        if (banner != null || lines.isNotEmpty()) runs += Run(banner, lines)
        return runs
    }

    /**
     * Whether a line is a received NMEA sentence -- the position stream, several
     * a second -- rather than the PMTK conversation or a note. `$PMTK` replies
     * arrive on the same stream and are not NMEA for this purpose.
     */
    fun isNmea(line: String): Boolean {
        val text = line.substringAfter(" << ", missingDelimiterValue = "")
        return text.startsWith("$") && !text.startsWith("\$PMTK")
    }
}
