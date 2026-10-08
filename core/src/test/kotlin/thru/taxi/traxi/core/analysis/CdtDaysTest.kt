package thru.taxi.traxi.core.analysis

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.MtkLogParser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The carver against 18 months of real trail flash.
 *
 * `cdt_v2.bin` holds 70 local days with data. On 66 of the 69 nights between
 * them the logger was off for more than four hours; on the other three it
 * logged straight through (measured with `mtkparse.py`, 2026-10-03). So the
 * overnight-gap rule, plus the longest-stop split, should find exactly 70.
 */
class CdtDaysTest {

    companion object {
        private lateinit var carve: DayCarver.Carve

        @JvmStatic
        @BeforeClass
        fun carveOnce() {
            val file = File(System.getProperty("traxi.dataDir") ?: "../data", "cdt_v2.bin")
            assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
            val fixes = MtkLogParser.parse(file.readBytes(), GpsRollover.AXN_130B).fixes
            carve = DayCarver.carve(fixes)
        }
    }

    @Test
    fun `finds one day per trail day`() {
        assertEquals(70, carve.days.size)
    }

    @Test
    fun `no carved day holds more walking than a day can`() {
        // Not span: a day cut at a logged-through night carries half that
        // camp, and 2025-07-18 runs 23.3 h end to end with 7 h of walking.
        val most = carve.days.maxOf { it.stats.movingSeconds }
        assertTrue(most <= 16 * 3600, "most moving ${most / 3600.0} h")
    }

    @Test
    fun `cleaned distance is walking pace`() {
        // A vehicle in the cleaned figure shows up as an impossible average.
        for (day in carve.days.filter { it.stats.movingSeconds > 1800 }) {
            val kmh = day.stats.cleanedMetres / day.stats.movingSeconds * 3.6
            assertTrue(kmh < 7.0, "day ${day.number} at ${"%.1f".format(kmh)} km/h")
        }
    }

    @Test
    fun `the five rides are their own tracks`() {
        // Measured 2026-10-07: 16, 4, 3, 20 and 29 minutes at up to 123 km/h,
        // 99 km in all -- including the ride into town on 2025-08-20 that
        // once booked 44 km of walking.
        val rides = carve.days.flatMap { it.rides }
        assertEquals(5, rides.size)
        assertTrue(rides.any { it.start.toString().startsWith("2025-08-20") && it.stats.rawMetres > 35_000 })
        assertEquals(99.0, carve.days.sumOf { it.rideStats.rawMetres } / 1000, 1.0)
    }

    @Test
    fun `cleaned distance never exceeds raw`() {
        for (day in carve.days) {
            assertTrue(day.stats.cleanedMetres <= day.stats.rawMetres + 1e-6, "day ${day.number}")
        }
    }
}
