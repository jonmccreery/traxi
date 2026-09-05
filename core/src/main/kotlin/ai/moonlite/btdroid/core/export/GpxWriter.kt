package ai.moonlite.btdroid.core.export

import ai.moonlite.btdroid.core.format.Fix
import ai.moonlite.btdroid.core.format.FixQuality
import ai.moonlite.btdroid.core.format.Quality
import java.io.Writer
import java.time.format.DateTimeFormatter

/**
 * GPX 1.1 export.
 *
 * Two decisions here are direct responses to defects found in the previous
 * export pipeline:
 *
 *  - **Coordinates are written at full precision**, not rounded. The old
 *    exporter's `%.1f` elevations and `%.6f` latitudes created phantom values
 *    that later analysis mistook for device sentinels — one genuine bad record
 *    was read as 31, and continuous f32 elevations were read as
 *    decimetre-quantized. Rounding on export destroys the ability to
 *    distinguish a real sentinel from a coincidence.
 *  - **Button-pressed fixes become waypoints, not track points.** 1,419
 *    records carry `RCR = BUTTON`; flattening them into the track discards the
 *    only user-authored data in the file.
 */
class GpxWriter(
    private val creator: String = "btdroid",
    private val gapSeconds: Long = 300,
    /** Emit `VALID`/`RCR` as GPX extensions. Costs size; keeps information. */
    private val includeExtensions: Boolean = true,
) {
    private val timestamps: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    fun write(fixes: List<Fix>, out: Writer, trackName: String = "MTK log") {
        val positioned = fixes.filter { it.hasPosition }
        val waypoints = positioned.filter { it.isWaypoint }
        val segments = Quality.segment(positioned, gapSeconds)

        out.write("""<?xml version="1.0" encoding="UTF-8"?>${'\n'}""")
        out.write(
            """<gpx version="1.1" creator="$creator" """ +
                """xmlns="http://www.topografix.com/GPX/1/1">${'\n'}"""
        )

        for (fix in waypoints) writeWaypoint(fix, out)

        out.write("<trk><name>${escape(trackName)}</name>\n")
        for (segment in segments) {
            out.write("<trkseg>\n")
            for (fix in segment) writePoint("trkpt", fix, out)
            out.write("</trkseg>\n")
        }
        out.write("</trk>\n</gpx>\n")
        out.flush()
    }

    private fun writeWaypoint(fix: Fix, out: Writer) {
        writePoint("wpt", fix, out)
    }

    private fun writePoint(tag: String, fix: Fix, out: Writer) {
        // Full precision, deliberately. See the class comment.
        out.write("""<$tag lat="${fix.latitude}" lon="${fix.longitude}">""")
        fix.height?.let { out.write("<ele>$it</ele>") }
        out.write("<time>${timestamps.format(fix.instant)}</time>")

        if (tag == "wpt") out.write("<sym>Flag</sym>")

        // GPX has a standard element for fix quality; use it rather than
        // burying the most useful field in the record inside an extension.
        fix.quality?.let { q ->
            when (q) {
                FixQuality.NO_FIX -> out.write("<fix>none</fix>")
                FixQuality.SPS -> out.write("<fix>3d</fix>")
                FixQuality.DGPS -> out.write("<fix>dgps</fix>")
                FixQuality.PPS -> out.write("<fix>pps</fix>")
                else -> Unit
            }
        }
        fix.satellitesInUse?.let { out.write("<sat>$it</sat>") }
        fix.hdop?.let { out.write("<hdop>${it / 100.0}</hdop>") }
        fix.vdop?.let { out.write("<vdop>${it / 100.0}</vdop>") }

        if (includeExtensions && (fix.speed != null || fix.rcr != null)) {
            out.write("<extensions>")
            fix.speed?.let { out.write("<btd:speed>$it</btd:speed>") }
            fix.rcr?.let { out.write("<btd:rcr>$it</btd:rcr>") }
            out.write("</extensions>")
        }
        out.write("</$tag>\n")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
