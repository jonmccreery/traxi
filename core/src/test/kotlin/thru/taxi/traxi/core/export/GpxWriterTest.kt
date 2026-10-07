package thru.taxi.traxi.core.export

import thru.taxi.traxi.core.format.Fix
import thru.taxi.traxi.core.format.FixQuality
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class GpxWriterTest {

    @Test
    fun `output is well-formed XML with its extension namespace declared`() {
        // Until 2026-10-03 the btd: prefix was used and never declared, so
        // every export failed a namespace-aware parse -- this one.
        val fixes = (0..2).map { i ->
            Fix(
                utcRaw = 0, epochSeconds = 1_750_000_000L + i * 10,
                valid = FixQuality.DGPS.code, latitude = 40.0 + i * 1e-4,
                longitude = -105.0, height = 3000f, speed = 4f, rcr = if (i == 1) 0x08 else 1,
            )
        }
        val out = StringWriter()
        GpxWriter().write(fixes, out, "Day 1")

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(out.toString().byteInputStream())
        val speeds = doc.getElementsByTagNameNS(GpxWriter.EXTENSION_NAMESPACE, "speed")
        assertEquals(4, speeds.length, "three track points and one waypoint")
    }
}
