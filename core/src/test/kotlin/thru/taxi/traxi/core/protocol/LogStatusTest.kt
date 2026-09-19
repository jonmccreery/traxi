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

    // ---------------- the bits past bit 1 ----------------
    //
    // §13 observed 0x0104 after a format and 0x0504 with need_format asserted,
    // and recorded that the app "could not have reported need_format, because
    // it never asks". It does ask -- every connect sends PMTK182,2,7 -- and the
    // answer carried these bits all along. Only bit 1 was ever read.

    @Test
    fun `need_format is carried in the same word and is not silently dropped`() {
        // 0x0504, observed on this device in §13.
        val status = Pmtk.LogStatus(0x0504)
        assertTrue(status.needsFormat)
        assertFalse(status.isLoggingEnabled)
        assertTrue(status.describe().contains("NEEDS FORMAT"))
    }

    @Test
    fun `a full flash is carried in the same word`() {
        val status = Pmtk.LogStatus(0x0900)
        assertTrue(status.isMemoryFull)
        assertTrue(status.describe().contains("MEMORY FULL"))
    }

    @Test
    fun `the record method observed after a format decodes as stop-when-full`() {
        // 0x0104: STOP mode, which is what a format leaves behind. In this mode
        // a full flash ends recording rather than wrapping, so it is worth
        // saying even though on its own it is a setting and not a fault.
        val status = Pmtk.LogStatus(0x0104)
        assertTrue(status.stopsWhenFull)
        assertTrue(status.faults.isEmpty())
    }

    @Test
    fun `the states that need someone to intervene are the states with faults`() {
        // The whole point of decoding past bit 1: these three all rendered
        // identically as "NOT logging" before, and they could hardly be less
        // alike.
        assertTrue(Pmtk.LogStatus(0x0100).faults.isEmpty())
        assertEquals(
            listOf("the flash needs formatting"),
            Pmtk.LogStatus(0x0504).faults,
        )
        assertEquals(
            listOf("the flash is full and the logger is set to stop"),
            Pmtk.LogStatus(0x0904).faults,
        )
    }

    @Test
    fun `a healthy recording logger reports no faults`() {
        // 0x0102 is what this device answers whenever it holds a fix, which is
        // most of the time. Anything in `faults` is something the app says out
        // loud, so it must be empty here or the alarm is worthless.
        assertTrue(Pmtk.LogStatus(0x0102).faults.isEmpty())
        assertTrue(Pmtk.LogStatus(258).faults.isEmpty())
    }

    @Test
    fun `a clear logging bit is a disabled logger, not one waiting for a fix`() {
        // Settled on the hardware 2026-09-19 (§16.11). An earlier reading of
        // data/ had 0x0100 as the ordinary no-fix state, which would have made
        // alarming on it a false alarm on every indoor connect. The transcript
        // says otherwise: the word went 256 -> 258 the instant PMTK182,4 was
        // acknowledged, GPRMC void on both sides, and the logger then sat
        // enabled and frozen for four more minutes until a fix arrived. An
        // armed logger with no fix reports 258.
        assertFalse(Pmtk.LogStatus.parse("256")!!.isLoggingEnabled)
        assertTrue(Pmtk.LogStatus.parse("258")!!.isLoggingEnabled)
    }

    @Test
    fun `a disable is not lumped in with the flash faults`() {
        // `faults` means "will not come back without intervening in the
        // hardware" -- a format, or an erase. A disable is one button press and
        // has its own, differently-worded alarm, so mixing them would send the
        // user to the §13 recovery for something Resume logging fixes.
        assertTrue(Pmtk.LogStatus(0x0100).faults.isEmpty())
        assertFalse(Pmtk.LogStatus(0x0100).isLoggingEnabled)
    }
}
