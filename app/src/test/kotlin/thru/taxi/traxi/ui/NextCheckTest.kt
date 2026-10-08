package thru.taxi.traxi.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NextCheckTest {

    private val tenMinutes = 600.0

    @Test
    fun `counts down whole minutes, rounding up so it never says zero`() {
        // The 2026-10-07 screenshot: checked 7 minutes ago on a 10-minute schedule.
        assertEquals("next in 3 min", describeNextCheck(7 * 60.0 + 10, tenMinutes))
        assertEquals("next in 10 min", describeNextCheck(1.0, tenMinutes))
        assertEquals("next in 2 min", describeNextCheck(tenMinutes - 61, tenMinutes))
    }

    @Test
    fun `the last minute and a short grace read as imminent, not overdue`() {
        assertEquals("next in under a minute", describeNextCheck(tenMinutes - 30, tenMinutes))
        assertEquals("next in under a minute", describeNextCheck(tenMinutes + 29, tenMinutes))
    }

    @Test
    fun `past the grace it says overdue, and by how much`() {
        assertEquals("next check 1 min overdue", describeNextCheck(tenMinutes + 31, tenMinutes))
        assertEquals("next check 3 min overdue", describeNextCheck(tenMinutes + 150, tenMinutes))
    }

    @Test
    fun `a fast USB schedule is always imminent`() {
        assertEquals("next in under a minute", describeNextCheck(5.0, 12.0))
    }
}
