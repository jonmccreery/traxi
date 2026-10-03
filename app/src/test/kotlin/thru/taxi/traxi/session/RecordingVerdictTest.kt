package thru.taxi.traxi.session

import kotlin.test.Test
import kotlin.test.assertEquals
import thru.taxi.traxi.session.RecordingActivity.Verdict

/**
 * The Device tab's recording verdict, judged from the reading alone.
 *
 * Recorded 2026-10-02 at a 10 s log interval: connect 23:09:19, pointer read at
 * +20 s with no fix, first fix at +133 s, next reading at +620 s. Between the
 * fix and that reading the card showed NOT RECORDING for eight minutes, because
 * it paired the +20 s reading with the fix of the present. The logger wrote
 * every fix; the 23:19:43 reading found ~50 records.
 */
class RecordingVerdictTest {

    private val s = 1_000_000_000L
    private val interval = 10.0

    private fun frozen(gap: Long, fixed: Long) = RecordingActivity(
        probes = 1,
        lastProbeAtNanos = 1L,
        lastProbeAdvanced = false,
        lastProbeGapNanos = gap,
        lastProbeFixedNanos = fixed,
    )

    @Test
    fun `the 2026-10-02 reading never becomes an accusation`() {
        val reading = frozen(gap = 20 * s, fixed = 0L)
        // Before the fix: blameless, as it was.
        assertEquals(Verdict.NO_FIX, reading.verdict(interval, hasFixNow = false))
        // After the fix: still blameless -- it was taken with nothing to write.
        assertEquals(Verdict.FIX_SINCE_LAST_CHECK, reading.verdict(interval, hasFixNow = true))
    }

    @Test
    fun `a fix that came just before the reading is not long enough to judge`() {
        // Fixed 5 s when read: under one and a half intervals, a healthy logger
        // may genuinely not have written yet.
        val reading = frozen(gap = 600 * s, fixed = 5 * s)
        assertEquals(Verdict.FIX_SINCE_LAST_CHECK, reading.verdict(interval, hasFixNow = true))
    }

    @Test
    fun `frozen under a held fix is a real alarm, whatever the sky does after`() {
        val reading = frozen(gap = 600 * s, fixed = 300 * s)
        assertEquals(Verdict.NOT_RECORDING, reading.verdict(interval, hasFixNow = true))
        assertEquals(Verdict.NOT_RECORDING, reading.verdict(interval, hasFixNow = false))
        assertEquals(300 * s, reading.frozenUnderFixNanos)
    }

    @Test
    fun `a fix older than the gap is judged over the gap`() {
        val reading = frozen(gap = 20 * s, fixed = 3_600 * s)
        assertEquals(Verdict.NOT_RECORDING, reading.verdict(interval, hasFixNow = true))
        assertEquals(20 * s, reading.frozenUnderFixNanos)
    }

    @Test
    fun `unchanged -- not looked, too soon, and advancing`() {
        assertEquals(Verdict.CHECKING, RecordingActivity().verdict(interval, hasFixNow = true))
        assertEquals(Verdict.CHECKING, frozen(gap = 10 * s, fixed = 10 * s).verdict(interval, true))
        val advancing = frozen(gap = 600 * s, fixed = 0L).copy(lastProbeAdvanced = true)
        assertEquals(Verdict.RECORDING, advancing.verdict(interval, hasFixNow = false))
    }
}
