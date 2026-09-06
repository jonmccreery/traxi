package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.format.GpsRollover
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live navigation telemetry, decoded from sentences the logger was already
 * sending and the app was already throwing away.
 *
 * Every sentence used here is copied verbatim out of
 * `data/usb_clean_2026-09-06.log`, so these are the device's real words rather
 * than a plausible reconstruction. That matters most for the rollover: the
 * capture is known to have been taken on 2026-09-06 and its `RMC` says 2007,
 * which is the same defect the logged fixes have and is corrected the same way.
 */
class TelemetryTest {

    // Verbatim from the 2026-09-06 USB capture.
    private val gga = "\$GPGGA,155939.000,4153.0220,N,08748.2074,W,1,7,1.16,234.8,M,-34.0,M,,*6B"
    private val rmc = "\$GPRMC,155938.000,A,4153.0220,N,08748.2074,W,0.18,0.00,210107,,,A*72"
    private val gsa = "\$GPGSA,A,3,23,32,24,10,27,15,18,,,,,,1.44,1.16,0.86*04"
    private val gsv1 = "\$GPGSV,3,1,12,23,71,053,35,10,60,320,42,24,44,068,32,32,40,246,27*76"
    private val gsv2 = "\$GPGSV,3,2,12,51,37,207,,18,35,165,27,15,20,061,17,27,17,268,25*79"
    private val gsv3 = "\$GPGSV,3,3,12,08,11,303,,02,03,324,,13,02,113,,28,01,196,*70"

    private fun feed(a: TelemetryAssembler, vararg lines: String) {
        for (line in lines) {
            val s = Nmea.parse(line) ?: error("test fixture failed checksum: $line")
            a.accept(s)
        }
    }

    @Test
    fun `every fixture in this test passes its own checksum`() {
        // If a fixture were mistyped, the assembler would simply never see it
        // and most assertions below would still pass against stale defaults.
        for (line in listOf(gga, rmc, gsa, gsv1, gsv2, gsv3)) {
            assertNotNull(Nmea.parse(line), "fixture failed checksum: $line")
        }
    }

    @Test
    fun `GGA yields position, altitude and fix quality`() {
        val a = TelemetryAssembler()
        feed(a, gga)

        val t = a.current
        // 41 deg 53.0220' N and 087 deg 48.2074' W.
        assertEquals(41.88370, t.latitude!!, 1e-5)
        assertEquals(-87.803457, t.longitude!!, 1e-5)
        assertEquals(1, t.fixQuality)
        assertEquals(7, t.satellitesUsed)
        assertEquals(1.16, t.hdop!!, 1e-9)
        assertEquals(234.8, t.altitudeMeters!!, 1e-9)
        assertEquals(-34.0, t.geoidSeparationMeters!!, 1e-9)
        assertTrue(t.hasPosition)
    }

    @Test
    fun `longitude keeps its three-digit degree field`() {
        // The split between degrees and minutes is two digits before the
        // decimal point, not a fixed offset -- latitude has two degree digits
        // and longitude three. Getting this wrong puts you 30 degrees away.
        val a = TelemetryAssembler()
        feed(a, gga)
        assertEquals(-87.0, a.current.longitude!!, 1.0)
    }

    @Test
    fun `GSA yields fix type, the satellites in the solution and all three DOPs`() {
        val a = TelemetryAssembler()
        feed(a, gsa)

        val t = a.current
        assertEquals(FixType.THREE_D, t.fixType)
        assertEquals(setOf(23, 32, 24, 10, 27, 15, 18), t.usedPrns)
        assertEquals(1.44, t.pdop!!, 1e-9)
        assertEquals(1.16, t.hdop!!, 1e-9)
        assertEquals(0.86, t.vdop!!, 1e-9)
    }

    @Test
    fun `a complete GSV sequence yields every satellite in view`() {
        val a = TelemetryAssembler()
        feed(a, gsv1, gsv2, gsv3)

        val sats = a.current.satellites
        assertEquals(12, sats.size)
        assertEquals(
            setOf(23, 10, 24, 32, 51, 18, 15, 27, 8, 2, 13, 28),
            sats.map { it.prn }.toSet(),
        )

        val strongest = sats.first()
        assertEquals(10, strongest.prn)
        assertEquals(42, strongest.snrDb)
        assertEquals(60, strongest.elevationDeg)
        assertEquals(320, strongest.azimuthDeg)
    }

    @Test
    fun `a satellite in view but untracked has no SNR rather than zero`() {
        // PRN 51 is in view with elevation and azimuth but a blank SNR field.
        // Reporting that as 0 dB would say "tracked, terrible signal" when the
        // truth is "not tracked at all", and only one of those improves by
        // standing still.
        val a = TelemetryAssembler()
        feed(a, gsv1, gsv2, gsv3)

        val untracked = a.current.satellites.filter { !it.tracked }.map { it.prn }.toSet()
        assertEquals(setOf(51, 8, 2, 13, 28), untracked)
        assertNull(a.current.satellites.first { it.prn == 51 }.snrDb)
        assertEquals(37, a.current.satellites.first { it.prn == 51 }.elevationDeg)
    }

    @Test
    fun `tracked satellites agree with the count and the solution`() {
        // Independent cross-check across three sentence types: GGA says seven
        // satellites used, GSA names seven PRNs, and exactly those seven are
        // the ones GSV reports an SNR for.
        val a = TelemetryAssembler()
        feed(a, gga, gsa, gsv1, gsv2, gsv3)

        val t = a.current
        assertEquals(7, t.satellitesUsed)
        assertEquals(7, t.usedPrns.size)
        assertEquals(7, t.trackedCount)
        assertEquals(t.usedPrns, t.satellites.filter { it.tracked }.map { it.prn }.toSet())
    }

    @Test
    fun `a GSV sequence joined midway is discarded rather than published short`() {
        // Connecting partway through a sequence is normal, and publishing the
        // tail of one as though it were the whole sky would under-report.
        val a = TelemetryAssembler()
        feed(a, gsv2, gsv3)
        assertTrue(a.current.satellites.isEmpty())

        feed(a, gsv1, gsv2, gsv3)
        assertEquals(12, a.current.satellites.size)
    }

    @Test
    fun `RMC yields validity, speed and course`() {
        val a = TelemetryAssembler()
        feed(a, rmc)

        val t = a.current
        assertEquals(true, t.valid)
        assertEquals(0.18, t.speedKnots!!, 1e-9)
        assertEquals(0.00, t.courseDeg!!, 1e-9)
        assertEquals(0.333, t.speedKmh!!, 1e-3)
    }

    @Test
    fun `live time carries the same 1024-week rollover as the logged fixes`() {
        // The capture this came from was taken on 2026-09-06. The device says
        // 21 January 2007. Same defect as the flash records, same correction.
        val uncorrected = TelemetryAssembler(GpsRollover.NONE)
        feed(uncorrected, rmc)
        val raw = Instant.ofEpochMilli(uncorrected.current.utcMillis!!).atZone(ZoneOffset.UTC)
        assertEquals(2007, raw.year)

        val corrected = TelemetryAssembler(GpsRollover.AXN_130B)
        feed(corrected, rmc)
        val fixed = Instant.ofEpochMilli(corrected.current.utcMillis!!).atZone(ZoneOffset.UTC)
        assertEquals(2026, fixed.year)
        assertEquals(9, fixed.monthValue)
        assertEquals(6, fixed.dayOfMonth)
        assertEquals(15, fixed.hour)
        assertEquals(59, fixed.minute)
        assertEquals(38, fixed.second)
    }

    @Test
    fun `a GGA after an RMC is timestamped from the date the RMC carried`() {
        // GGA has no date field at all. Without carrying the last one forward,
        // the most frequent sentence on the wire would be the one that cannot
        // be timestamped.
        val a = TelemetryAssembler(GpsRollover.AXN_130B)
        feed(a, rmc, gga)
        val at = Instant.ofEpochMilli(a.current.utcMillis!!).atZone(ZoneOffset.UTC)
        assertEquals(2026, at.year)
        assertEquals(39, at.second)   // the GGA's own second, not the RMC's 38
    }

    @Test
    fun `PMTK replies are ignored and leave telemetry untouched`() {
        val a = TelemetryAssembler()
        feed(a, gga)
        val before = a.current

        val ack = Nmea.parse(Nmea.frame("PMTK001,182,3"))!!
        assertFalse(a.accept(ack))
        assertEquals(before, a.current)
    }

    @Test
    fun `a garbled sentence does not blank a position that was good`() {
        // The navigation stream shares a byte stream with bulk log chunks, so
        // partial sentences are a steady-state condition. Clearing a known-good
        // position because one GGA arrived with empty fields would make the
        // display flicker between correct and blank.
        val a = TelemetryAssembler()
        feed(a, gga)
        val good = a.current.latitude

        feed(a, "\$GPGGA,155940.000,,,,,0,0,,,M,,M,,*44")
        assertEquals(good, a.current.latitude)
    }

    @Test
    fun `nothing decoded means nothing claimed`() {
        val a = TelemetryAssembler()
        assertFalse(a.current.hasAnything)
        assertFalse(a.current.hasPosition)

        feed(a, gsa)
        assertTrue(a.current.hasAnything)
        assertFalse(a.current.hasPosition)
    }

    @Test
    fun `reset clears everything including a half-collected GSV sequence`() {
        val a = TelemetryAssembler()
        feed(a, gga, gsa, gsv1)
        a.reset()
        assertFalse(a.current.hasAnything)

        feed(a, gsv2, gsv3)
        assertTrue(a.current.satellites.isEmpty())
    }
}
