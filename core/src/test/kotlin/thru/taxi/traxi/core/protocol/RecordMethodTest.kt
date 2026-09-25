package thru.taxi.traxi.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flash-full behaviour — the field this app never asked the device for.
 *
 * Values from §13's observed table and from `data/dump_v2.log`, which prints
 * `Recording method on memory full: (1) OVERLAP` from the same register.
 */
class RecordMethodTest {

    @Test
    fun `the two values the device actually reports`() {
        assertEquals(Pmtk.RecordMethod.OVERLAP, Pmtk.RecordMethod.parse("1"))
        assertEquals(Pmtk.RecordMethod.STOP, Pmtk.RecordMethod.parse("2"))
        assertEquals(Pmtk.RecordMethod.OVERLAP, Pmtk.RecordMethod.parse("  1 "))
    }

    @Test
    fun `an unknown value is null, not a reassuring guess`() {
        // Defaulting to OVERLAP would invent a comforting answer about the one
        // setting whose whole hazard is that it ends recording quietly. The UI
        // says "unknown" instead, which is true and prompts a reconnect.
        assertNull(Pmtk.RecordMethod.parse("0"))
        assertNull(Pmtk.RecordMethod.parse("7"))
        assertNull(Pmtk.RecordMethod.parse(""))
        assertNull(Pmtk.RecordMethod.parse("overlap"))
    }

    @Test
    fun `the codes match the write commands in the record`() {
        // §13's recovery step 4 restores with PMTK182,1,6,1; mtkbabel's
        // -m stop sends 2. A swap here would set the opposite of what the
        // button says, silently.
        assertEquals(1, Pmtk.RecordMethod.OVERLAP.code)
        assertEquals(2, Pmtk.RecordMethod.STOP.code)
    }

    @Test
    fun `describe names the consequence, not just the mode`() {
        assertTrue(Pmtk.RecordMethod.STOP.describe().contains("ends"))
        assertTrue(Pmtk.RecordMethod.OVERLAP.describe().contains("overwrites"))
    }
}
