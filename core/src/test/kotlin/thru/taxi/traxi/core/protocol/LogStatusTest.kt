package thru.taxi.traxi.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Log status decoding.
 *
 * The bit meaning is not inferred from a datasheet. The reference dump carries
 * 200 type-`0x07` markers with arg `0x0100` and 200 with `0x0102`, identified in
 * the internals doc as logging disabled and enabled; `0x02` is the only bit that
 * differs between them.
 */
class LogStatusTest {

    @Test
    fun `the two values the real device actually sends`() {
        // Observed on the wire: PMTK182,3,7,258 and PMTK182,3,7,256.
        assertTrue(Pmtk.LogStatus.parse("258")!!.isLoggingEnabled)
        assertFalse(Pmtk.LogStatus.parse("256")!!.isLoggingEnabled)
    }

    @Test
    fun `these match the marker arguments in the reference dump`() {
        assertTrue(Pmtk.LogStatus(0x0102).isLoggingEnabled)
        assertFalse(Pmtk.LogStatus(0x0100).isLoggingEnabled)
    }

    @Test
    fun `the device answers in decimal`() {
        assertEquals(258, Pmtk.LogStatus.parse("258")?.bits)
        assertEquals(258, Pmtk.LogStatus.parse("  258 ")?.bits)
    }

    @Test
    fun `a hex form decodes rather than silently reading as not-logging`() {
        // Nothing in the protocol guarantees the radix. A "0x102" that fell
        // through to zero would render as NOT RECORDING and send the user
        // chasing a problem that does not exist.
        assertTrue(Pmtk.LogStatus.parse("0x102")!!.isLoggingEnabled)
    }

    @Test
    fun `unparseable status is null, not a guess`() {
        assertNull(Pmtk.LogStatus.parse(""))
        assertNull(Pmtk.LogStatus.parse("unavailable"))
        assertNull(Pmtk.LogStatus.parse("  "))
    }

    @Test
    fun `describe names the state in words, not just the bits`() {
        assertTrue(Pmtk.LogStatus(0x0102).describe().contains("logging"))
        assertTrue(Pmtk.LogStatus(0x0100).describe().contains("NOT logging"))
    }
}
