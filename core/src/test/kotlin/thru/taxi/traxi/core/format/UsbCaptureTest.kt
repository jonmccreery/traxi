package thru.taxi.traxi.core.format

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-checks two independent captures of the same flash.
 *
 * `cdt_v2.bin` was produced by mtkbabel on 2026-09-04. `usb_2026-09-05.bin` was
 * produced by this project's own block-read implementation over USB CDC-ACM the
 * following day, in 83.5 s with zero retries.
 *
 * Agreement between them is the strongest available evidence that the read path
 * is correct: two different implementations, two different transports, one
 * chip. The places they *disagree* are equally informative, and are pinned
 * below, because each one is explained rather than tolerated.
 */
class UsbCaptureTest {

    companion object {
        /** Write pointer recorded by mtkbabel on 2026-09-04. */
        private const val OLD_WRITE_POINTER = 0x0051F322

        /** Write pointer the device reported over USB on 2026-09-05. */
        private const val NEW_WRITE_POINTER = 0x0051F3C2

        private var golden: ByteArray? = null
        private var usb: ByteArray? = null

        @JvmStatic
        @BeforeClass
        fun load() {
            val dir = System.getProperty("traxi.dataDir") ?: "../data"
            val g = File(dir, "cdt_v2.bin")
            val u = File(dir, "usb_2026-09-05.bin")
            assumeTrue("captures not present", g.isFile && u.isFile)
            golden = g.readBytes()
            usb = u.readBytes()
        }
    }

    @Test
    fun `both captures agree byte-for-byte up to the earlier write pointer`() {
        val g = golden!!
        val u = usb!!
        for (i in 0 until OLD_WRITE_POINTER) {
            if (g[i] != u[i]) {
                throw AssertionError("captures diverge at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `the USB capture decodes to the same fixes as the reference`() {
        val result = MtkLogParser.parse(usb!!, GpsRollover.AXN_130B)
        assertEquals(126_613, result.fixes.size)
        assertEquals(0, result.stats.checksumFailures)
        assertEquals(82, result.stats.sectorsWithData)
    }

    @Test
    fun `the only new data is ten markers, exactly the write-pointer delta`() {
        // Five power-on cycles between the two captures. Each emits a
        // distance-criterion and a speed-criterion write, both no-ops. No new
        // fixes, because the device had no GPS lock indoors.
        val u = usb!!
        var offset = OLD_WRITE_POINTER
        var markers = 0
        val types = mutableMapOf<Int, Int>()

        while (offset < NEW_WRITE_POINTER) {
            val marker = DynamicMarker.parse(u, offset)
            if (marker != null) {
                markers++
                types[marker.type] = (types[marker.type] ?: 0) + 1
                offset += DynamicMarker.SIZE
            } else {
                offset++
            }
        }

        assertEquals(10, markers)
        assertEquals(NEW_WRITE_POINTER - OLD_WRITE_POINTER, markers * DynamicMarker.SIZE)
        assertEquals(5, types[DynamicMarker.CHANGE_DISTANCE])
        assertEquals(5, types[DynamicMarker.CHANGE_SPEED])
        // Still no format change, 5.5 MB in.
        assertEquals(null, types[DynamicMarker.CHANGE_FORMAT])
    }

    @Test
    fun `everything past the reported write pointer is erased flash`() {
        // Confirms the pointer means what the arithmetic above implies, and
        // that the downloader's stop rule fired on genuine erased space rather
        // than on a dropped block.
        val u = usb!!
        for (i in NEW_WRITE_POINTER until u.size) {
            if (u[i] != 0xFF.toByte()) {
                throw AssertionError("unexpected data at 0x%08X past write pointer".format(i))
            }
        }
    }

    @Test
    fun `the capture runs past the write pointer into erased space`() {
        // The downloader deliberately does not stop at the write pointer: in
        // OVERLAP mode a wrapped log keeps its oldest records beyond it.
        // Reading further is what makes the stop rule safe.
        assertTrue(
            usb!!.size > NEW_WRITE_POINTER,
            "capture should extend past the write pointer",
        )
    }
}
