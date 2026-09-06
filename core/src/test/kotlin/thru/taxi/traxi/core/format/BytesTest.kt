package thru.taxi.traxi.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a real quantity never displays as nothing.
 *
 * The regression this locks down was found on hardware: the logger had
 * recorded 960 bytes after a format and the Device tab said "Written 0 KB,
 * 0% of flash used", which reads as a device that is not recording. It was.
 */
class BytesTest {

    @Test
    fun `the exact failure from the device is not repeatable`() {
        // Write pointer 0x000003C0 on an 8 MB chip.
        assertEquals("960 B", Bytes.describe(960))
        assertEquals("<1%", Bytes.describePercent(960.0 / (8 * 1024 * 1024)))
    }

    @Test
    fun `anything below a kilobyte keeps its exact count`() {
        assertEquals("1 B", Bytes.describe(1))
        assertEquals("512 B", Bytes.describe(512))
        assertEquals("1023 B", Bytes.describe(1023))
    }

    @Test
    fun `a kilobyte and above reads in kilobytes`() {
        assertEquals("1 KB", Bytes.describe(1024))
        assertEquals("18 KB", Bytes.describe(18432))
        assertEquals("5363 KB", Bytes.describe(5_492_058))
    }

    @Test
    fun `only genuine zero displays as zero`() {
        // The whole point. Zero is a real answer and must stay available --
        // it is the rounding of non-zero to zero that was the bug.
        assertEquals("0 B", Bytes.describe(0))
        assertEquals("0%", Bytes.describePercent(0.0))
    }

    @Test
    fun `a small but non-zero fraction is not swallowed`() {
        assertEquals("<1%", Bytes.describePercent(0.0001))
        assertEquals("<1%", Bytes.describePercent(0.009))
        assertEquals("1%", Bytes.describePercent(0.01))
        assertEquals("99%", Bytes.describePercent(0.999))
        assertEquals("100%", Bytes.describePercent(1.0))
    }
}
