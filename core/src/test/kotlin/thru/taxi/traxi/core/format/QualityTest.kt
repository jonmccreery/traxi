package thru.taxi.traxi.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class QualityTest {

    private val t0 = 1_750_000_000L

    private fun fix(t: Long, lat: Double, valid: FixQuality = FixQuality.DGPS, rcr: Int = RecordReason.TIME) =
        Fix(utcRaw = 0, epochSeconds = t, valid = valid.code, latitude = lat, longitude = -105.0,
            height = 3000f, rcr = rcr)

    private fun press(t: Long, lat: Double) = fix(t, lat, FixQuality.NO_FIX, RecordReason.BUTTON)

    /** Enough DGPS fixes either side that the spike window has something to chew on. */
    private fun around(mark: Fix, nextLat: Double, nextAfter: Long): List<Fix> =
        (0..4).map { fix(mark.epochSeconds - 50 + it * 10, mark.latitude!!) } +
            mark +
            (0..4).map { fix(mark.epochSeconds + nextAfter + it * 10, nextLat) }

    @Test
    fun `a press with no fix is kept when a real fix nearby vouches for it`() {
        val mark = press(t0, 40.0)
        val f = Quality.filter(around(mark, nextLat = 40.0002, nextAfter = 20))
        assertEquals(1, f.rescuedMarks)
        assertEquals(1, f.kept.count { it == mark })
    }

    @Test
    fun `the morning press carrying last night's position is not kept`() {
        // Last night's fix 12 h earlier, at the same spot to the metre; the
        // first real fix 21 s later is 1.2 km away. The position is the chip's
        // stored one, and nothing vouches for it.
        val night = fix(t0 - 12 * 3600, 40.0)
        val mark = press(t0, 40.0)
        val morning = (0..9).map { fix(t0 + 21 + it * 10, 40.0108) }
        val f = Quality.filter(listOf(night, mark) + morning)
        assertEquals(0, f.rescuedMarks)
        assertEquals(1, f.rejected[Quality.Rejection.NO_FIX])
    }

    @Test
    fun `a no-fix record that is not a press is never kept`() {
        val f = Quality.filter(around(fix(t0, 40.0, FixQuality.NO_FIX), nextLat = 40.0, nextAfter = 10))
        assertEquals(0, f.rescuedMarks)
    }
}
