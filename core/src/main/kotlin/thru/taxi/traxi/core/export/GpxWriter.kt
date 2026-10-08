package thru.taxi.traxi.core.export

import thru.taxi.traxi.core.analysis.DayCarver
import thru.taxi.traxi.core.format.Fix
import thru.taxi.traxi.core.format.FixQuality
import thru.taxi.traxi.core.format.Quality
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
 *  - **Rides are a second track**, named "<name> (rides)", found by the same
 *    rule as [DayCarver]: a walk with a car ride inside it is not a walk.
 */
class GpxWriter(
    private val creator: String = "Traxi",
    private val gapSeconds: Long = 300,
    /** Emit `VALID`/`RCR` as GPX extensions. Costs size; keeps information. */
    private val includeExtensions: Boolean = true,
) {
    private val timestamps: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    fun write(fixes: List<Fix>, out: Writer, trackName: String = "MTK log") {
        // A non-finite coordinate is not a place and has no GPX spelling.
        val positioned = fixes.filter {
            it.hasPosition && it.latitude!!.isFinite() && it.longitude!!.isFinite()
        }
        val waypoints = positioned.filter { it.isWaypoint }
        val walks = mutableListOf<List<Fix>>()
        val rides = mutableListOf<List<Fix>>()
        for (segment in Quality.segment(positioned, gapSeconds)) {
            var from = 0
            for (ride in DayCarver.rideRanges(segment, DayCarver.Config())) {
                if (ride.first > from) walks += segment.subList(from, ride.first)
                rides += segment.subList(ride.first, ride.last + 1)
                from = ride.last + 1
            }
            if (from < segment.size) walks += segment.subList(from, segment.size)
        }

        out.write("""<?xml version="1.0" encoding="UTF-8"?>${'\n'}""")
        out.write(
            """<gpx version="1.1" creator="$creator" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                // The extensions below use this prefix. Undeclared, it made
                // every export malformed XML, which strict readers reject
                // outright -- and a per-day GPX exists to be shared.
                """xmlns:btd="$EXTENSION_NAMESPACE">${'\n'}"""
        )

        for (fix in waypoints) writeWaypoint(fix, out)

        writeTrack(trackName, walks, out)
        if (rides.isNotEmpty()) writeTrack("$trackName (rides)", rides, out)
        out.write("</gpx>\n")
        out.flush()
    }

    private fun writeTrack(name: String, segments: List<List<Fix>>, out: Writer) {
        out.write("<trk><name>${escape(name)}</name>\n")
        for (segment in segments) {
            out.write("<trkseg>\n")
            for (fix in segment) writePoint("trkpt", fix, out)
            out.write("</trkseg>\n")
        }
        out.write("</trk>\n")
    }

    private fun writeWaypoint(fix: Fix, out: Writer) {
        writePoint("wpt", fix, out)
    }

    private fun writePoint(tag: String, fix: Fix, out: Writer) {
        // Full precision, deliberately. See the class comment.
        out.write("""<$tag lat="${plain(fix.latitude!!)}" lon="${plain(fix.longitude!!)}">""")
        // NaN or infinite flash has no xsd:decimal spelling; omit the element
        // rather than write a file a validating reader rejects whole.
        fix.height?.takeIf { it.isFinite() }?.let { out.write("<ele>${plain(it)}</ele>") }
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
        fix.hdop?.let { out.write("<hdop>${plain(it / 100.0)}</hdop>") }
        fix.vdop?.let { out.write("<vdop>${plain(it / 100.0)}</vdop>") }

        if (includeExtensions && (fix.speed != null || fix.rcr != null)) {
            out.write("<extensions>")
            fix.speed?.let { out.write("<btd:speed>$it</btd:speed>") }
            fix.rcr?.let { out.write("<btd:rcr>$it</btd:rcr>") }
            out.write("</extensions>")
        }
        out.write("</$tag>\n")
    }

    /**
     * The same shortest round-trip digits Kotlin prints, without the exponent.
     * `0.00005.toString()` is `5.0E-5`, which `xsd:decimal` does not accept --
     * and the equator, the prime meridian and sea level are all real places.
     */
    private fun plain(d: Double): String = java.math.BigDecimal(d.toString()).toPlainString()
    private fun plain(f: Float): String = java.math.BigDecimal(f.toString()).toPlainString()

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val EXTENSION_NAMESPACE = "urn:thru.taxi.traxi:gpx-extensions:1"
    }
}
