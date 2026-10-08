package thru.taxi.traxi.core.export

import org.junit.Assume.assumeTrue
import org.xml.sax.SAXParseException
import thru.taxi.traxi.core.format.Fix
import thru.taxi.traxi.core.format.FixQuality
import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.MtkLogParser
import thru.taxi.traxi.core.format.Quality
import thru.taxi.traxi.core.format.RecordReason
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Every export validates against the official GPX 1.1 schema.
 *
 * Well-formed is not enough: a reader that validates rejects a file with
 * elements out of order, an unknown `<fix>` value, or a coordinate written as
 * `5.0E-5`, which is a valid Kotlin double and not a valid `xsd:decimal`. The
 * schema is vendored from https://www.topografix.com/GPX/1/1/gpx.xsd
 * (fetched 2026-10-07) so this runs offline.
 */
class GpxSchemaTest {

    private val GPX = "http://www.topografix.com/GPX/1/1"

    private val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        .newSchema(javaClass.getResource("/gpx/gpx-1.1.xsd")!!)

    private fun assertValid(gpx: String, what: String) {
        try {
            schema.newValidator().validate(StreamSource(StringReader(gpx)))
        } catch (e: SAXParseException) {
            val line = gpx.lines().getOrNull(e.lineNumber - 1)?.take(300)
            fail("$what: line ${e.lineNumber}: ${e.message}\n  $line")
        }
    }

    private fun export(fixes: List<Fix>, name: String = "test"): String =
        StringWriter().also { GpxWriter().write(fixes, it, name) }.toString()

    private fun fix(
        i: Int = 0,
        lat: Double = 40.0,
        lon: Double = -105.0,
        height: Float? = 3000f,
        rcr: Int? = RecordReason.TIME,
        valid: Int? = FixQuality.SPS.code,
    ) = Fix(
        utcRaw = 0, epochSeconds = 1_750_000_000L + i * 10, valid = valid,
        latitude = lat, longitude = lon, height = height, speed = 1.5f, rcr = rcr,
        hdop = 90, vdop = 120, satellitesInUse = 8,
    )

    @Test
    fun `the full trail export validates`() {
        // The same pipeline the share button runs, over 18 months of real flash.
        val file = File(System.getProperty("traxi.dataDir") ?: "../data", "cdt_v2.bin")
        assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
        val parsed = MtkLogParser.parse(file.readBytes(), GpsRollover.AXN_130B)
        assertValid(export(Quality.filter(parsed.fixes).kept, file.name), "cdt_v2.bin")
    }

    @Test
    fun `a car ride is written as its own track, and both validate`() {
        val walk1 = (0..30).map { fix(it, lat = 40.0 + it * 1e-4) }
        val ride = (31..60).map { fix(it, lat = 40.003 + (it - 30) * 2.25e-3).copy(speed = 90f) }
        val walk2 = (61..90).map { fix(it, lat = 40.07 + (it - 60) * 1e-4) }
        val gpx = export(walk1 + ride + walk2, name = "Day 9")
        assertValid(gpx, "walk, ride, walk")

        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(gpx.byteInputStream())
        val tracks = doc.getElementsByTagNameNS(GPX, "trk")
        assertEquals(2, tracks.length)
        fun points(i: Int) = (tracks.item(i) as org.w3c.dom.Element)
            .getElementsByTagNameNS(GPX, "trkpt").length
        assertEquals(61, points(0), "both walks")
        assertEquals(30, points(1), "the ride")
        assertEquals("Day 9 (rides)", (tracks.item(1) as org.w3c.dom.Element)
            .getElementsByTagNameNS(GPX, "name").item(0).textContent)
    }

    @Test
    fun `an empty export validates`() {
        assertValid(export(emptyList()), "no fixes")
    }

    @Test
    fun `every fix quality the writer maps validates`() {
        val fixes = FixQuality.entries.mapIndexed { i, q -> fix(i, valid = q.code) }
        assertValid(export(fixes), "all qualities")
    }

    @Test
    fun `waypoints and track points together validate`() {
        val fixes = (0..4).map { fix(it, rcr = if (it % 2 == 0) RecordReason.BUTTON else RecordReason.TIME) }
        assertValid(export(fixes), "waypoints")
    }

    @Test
    fun `coordinates near zero are written as plain decimals`() {
        // Kotlin prints 0.00005 as "5.0E-5". The equator and the prime
        // meridian are real places; so is a beach at sea level.
        val fixes = listOf(
            fix(0, lat = 0.00005, lon = -0.00001, height = 0.0001f),
            fix(1, lat = -1e-9, lon = 1e-12, height = -0.00002f),
            fix(2, lat = 0.0, lon = -0.0, height = 0f),
            fix(3, lat = 89.99999999, lon = 179.99999999, height = 8848.86f),
        )
        assertValid(export(fixes), "near-zero coordinates")
    }

    @Test
    fun `a sentinel height does not break the file`() {
        val fixes = listOf(
            fix(0, height = Float.NaN),
            fix(1, height = Float.POSITIVE_INFINITY),
            fix(2, height = null),
        )
        assertValid(export(fixes), "non-finite heights")
    }

    @Test
    fun `a track name with markup characters validates`() {
        assertValid(export(listOf(fix()), name = """a & b <c> "d" 'e'"""), "track name")
    }
}
