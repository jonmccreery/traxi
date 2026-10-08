package thru.taxi.traxi.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptLogTest {

    // Shapes copied from the phone's transcript.log, 2026-10-07.
    private val file = """
        23:57:48.207 << ${'$'}PMTK182,3,8,0051F3C2*1E

        === app started 2026-10-07 09:01:48 — traxi SM-G990U1, Android 16 ===
        09:01:50.098 -- ensuring 00:1C:88:22:15:98 is bonded

        === app started 2026-10-07 19:17:35 — traxi SM-G990U1, Android 16 ===
        19:18:06.694 >> ${'$'}PMTK605*31
        19:18:07.534 << ${'$'}GPGGA,001807.000,,,,,0,0,,,M,,M,,*46
    """.trimIndent()

    @Test
    fun `splits on banners, keeping lines from before the first`() {
        val runs = TranscriptLog.runs(file)
        assertEquals(3, runs.size)
        assertNull(runs[0].banner)
        assertEquals(1, runs[0].lines.size)
        assertEquals("2026-10-07 09:01", runs[1].startedAt)
        assertEquals(listOf("09:01:50.098 -- ensuring 00:1C:88:22:15:98 is bonded"), runs[1].lines)
        assertEquals(2, runs[2].lines.size)
    }

    @Test
    fun `a run with no lines yet still counts`() {
        val runs = TranscriptLog.runs("=== app started 2026-10-07 19:49:01 — x ===\n")
        assertEquals(1, runs.size)
        assertTrue(runs.single().lines.isEmpty())
    }

    @Test
    fun `only received non-PMTK sentences are NMEA`() {
        assertTrue(TranscriptLog.isNmea("19:18:07.534 << \$GPGGA,001807.000,,,,,0,0,,,M,,M,,*46"))
        assertTrue(TranscriptLog.isNmea("19:18:07.539 << \$GPGSV,3,1,10,07,79,161,42*7F"))
        assertFalse(TranscriptLog.isNmea("19:18:07.548 << \$PMTK182,3,9,1C70171C*1E"))
        assertFalse(TranscriptLog.isNmea("19:18:06.694 >> \$PMTK605*31"))
        assertFalse(TranscriptLog.isNmea("19:18:07.669 -- since the app last saw this logger: ..."))
    }
}
