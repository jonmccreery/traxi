package thru.taxi.traxi.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class WrittenExtentTest {

    private fun image(written: Int, size: Int) =
        ByteArray(size) { if (it < written) 0x42 else 0xFF.toByte() }

    @Test
    fun `the pointer sits just past the last written byte`() {
        assertEquals(0x3FC00, SimulatedLoggerTransport.writtenExtent(image(0x3FC00, 0x60000)))
    }

    @Test
    fun `an erased image reports zero, and a full one its size`() {
        assertEquals(0, SimulatedLoggerTransport.writtenExtent(image(0, 0x10000)))
        assertEquals(0x10000, SimulatedLoggerTransport.writtenExtent(image(0x10000, 0x10000)))
    }
}
