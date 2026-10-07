package thru.taxi.traxi.core.analysis

import thru.taxi.traxi.core.format.Fix
import thru.taxi.traxi.core.format.FixQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayCarverTest {

    private val hour = 3600L
    private val t0 = 1_750_000_000L          // an arbitrary daytime start

    /** ~1.11 m of latitude per 1e-5 degree. */
    private fun fix(t: Long, lat: Double, lon: Double = -105.0, h: Float = 3000f, rcr: Int = 1) =
        Fix(
            utcRaw = 0, epochSeconds = t, valid = FixQuality.DGPS.code,
            latitude = lat, longitude = lon, height = h, rcr = rcr,
        )

    /** A straight walk north at ~1.1 m/s, one fix per 10 s. */
    private fun walk(from: Long, seconds: Long, lat0: Double = 40.0): List<Fix> =
        (0..seconds / 10).map { i -> fix(from + i * 10, lat0 + i * 1e-4) }

    @Test
    fun `an overnight gap ends the day and is never measured across`() {
        val day1 = walk(t0, 2 * hour)
        // Off for the night, and switched on 20 km further on.
        val day2 = walk(t0 + 14 * hour, 2 * hour, lat0 = 40.3)
        val carve = DayCarver.carve(day1 + day2)

        assertEquals(2, carve.days.size)
        val total = carve.days.sumOf { it.stats.rawMetres }
        val walked = DayCarver.carve(day1).days.single().stats.rawMetres +
            DayCarver.carve(day2).days.single().stats.rawMetres
        assertEquals(walked, total, 1e-6, "the 20 km overnight jump must not count")
    }

    @Test
    fun `a short gap splits a segment, reports its length, and keeps the day`() {
        val morning = walk(t0, hour)
        val afternoon = walk(t0 + hour + 1800, hour, lat0 = 40.2)
        val day = DayCarver.carve(morning + afternoon).days.single()

        assertEquals(2, day.segments.size)
        assertEquals(1, day.gaps.size)
        assertEquals(1800L, day.gaps.single().seconds)
        assertEquals(
            day.segments.sumOf { it.stats.rawMetres }, day.stats.rawMetres, 1e-6,
            "the jump across lunch is not distance",
        )
    }

    @Test
    fun `a night logged straight through splits at the stop, not the clock`() {
        val evening = walk(t0, 3 * hour)
        val campLat = evening.last().latitude!!
        // Ten hours in camp, logging every 10 s, wandering a few metres.
        val camp = (1..3600).map { i ->
            fix(evening.last().epochSeconds + i * 10, campLat + (i % 3) * 2e-5)
        }
        val morning = walk(camp.last().epochSeconds + 10, 3 * hour, lat0 = campLat)
        val days = DayCarver.carve(evening + camp + morning).days

        assertEquals(2, days.size)
        val split = days[1].start.epochSecond
        val campMiddle = (camp.first().epochSeconds + camp.last().epochSeconds) / 2
        // Within a few fixes: the stop also takes in the last strides into camp.
        assertTrue(kotlin.math.abs(split - campMiddle) <= 60, "split at the middle of camp")
    }

    @Test
    fun `with no night to find, the day splits at solar midnight on the trail`() {
        // 24 h of walking with no stop: nothing for the gap or night rules to
        // use. At 105 W, solar midnight is 07:00 UTC; UTC midnight would have
        // cut at 17:00 on the trail, the middle of the evening.
        val walked = walk(t0, 24 * hour)
        val days = DayCarver.carve(walked).days

        assertEquals(2, days.size)
        val solarMidnight = java.time.Instant.ofEpochSecond(t0).atOffset(java.time.ZoneOffset.UTC)
            .toLocalDate().plusDays(1).atTime(7, 0).toEpochSecond(java.time.ZoneOffset.UTC)
        assertEquals(walked.first { it.epochSeconds >= solarMidnight }.instant, days[1].start)
    }

    @Test
    fun `a stop is not moving time, however long it was`() {
        // CDT 2025-11-26 read 17.8 h moving in an 18 h day that began with
        // eight hours of camp: the first stride out booked the whole night.
        val morning = walk(t0, hour)
        val lunch = (1..360).map { i -> fix(morning.last().epochSeconds + i * 10, morning.last().latitude!!) }
        val afternoon = walk(lunch.last().epochSeconds + 10, hour, lat0 = morning.last().latitude!! + 1e-4)
        val stats = DayCarver.carve(morning + lunch + afternoon).days.single().stats

        assertEquals(2 * hour + 10, stats.movingSeconds, "two hours walked; the hour of lunch is not")
    }

    @Test
    fun `camp jitter is raw distance but not cleaned distance or moving time`() {
        val jitter = (0..360).map { i -> fix(t0 + i * 10, 40.0 + (i % 2) * 4e-5) }
        val stats = DayCarver.carve(jitter).days.single().stats

        assertTrue(stats.rawMetres > 1000, "4.4 m back and forth, 360 times")
        assertEquals(0.0, stats.cleanedMetres)
        assertEquals(0L, stats.movingSeconds)
    }

    @Test
    fun `a teleport is raw distance but not cleaned, and the walk resumes from where it was`() {
        val before = walk(t0, 600)
        val spike = fix(before.last().epochSeconds + 10, 41.0)   // ~110 km in 10 s
        val after = walk(spike.epochSeconds + 10, 600, lat0 = before.last().latitude!! + 1e-4)
        val stats = DayCarver.carve(before + spike + after).days.single().stats

        assertTrue(stats.rawMetres > 200_000)
        assertTrue(stats.cleanedMetres < 2_000, "cleaned ${stats.cleanedMetres}")
    }

    @Test
    fun `elevation noise under the hysteresis is not climbing`() {
        val noisy = (0..100).map { i ->
            fix(t0 + i * 10, 40.0 + i * 1e-4, h = 3000f + (if (i % 2 == 0) 0f else 3f))
        }
        val climb = (1..10).map { i ->
            fix(t0 + 1010 + i * 10, 40.0101 + i * 1e-4, h = 3000f + i * 10f)
        }
        val stats = DayCarver.carve(noisy + climb).days.single().stats

        assertTrue(stats.rawGainMetres > 100, "raw counts every wobble")
        assertEquals(100.0, stats.cleanedGainMetres, 3.0)
    }

    @Test
    fun `button marks are carried as waypoints of their day`() {
        val walked = walk(t0, 600).toMutableList()
        walked[30] = walked[30].copy(rcr = 0x08)
        val day = DayCarver.carve(walked).days.single()
        assertEquals(listOf(walked[30]), day.waypoints)
    }

    @Test
    fun `unusable fixes are dropped and counted, not carved`() {
        val walked = walk(t0, 600) +
            Fix(utcRaw = 0, epochSeconds = t0 + 700, valid = FixQuality.NO_FIX.code,
                latitude = 0.0, longitude = 0.0)
        val carve = DayCarver.carve(walked)
        assertEquals(1, carve.rejected.values.sum())
        assertEquals(61, carve.days.single().fixCount)
    }
}
