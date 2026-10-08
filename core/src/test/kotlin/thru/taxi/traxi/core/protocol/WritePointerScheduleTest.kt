package thru.taxi.traxi.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WritePointerScheduleTest {

    private val s = 1_000_000_000L
    private val preflight = 20 * s     // twice a 10 s log interval
    private val steady = 600 * s       // Bluetooth

    private fun due(now: Long, fix: Long?, probed: Boolean = false, last: Long = 0L) =
        WritePointerSchedule.isDue(now, last, fix, probed, preflight, steady)

    /** The connect-timed rule this replaced: first probe at min(preflight, steady). */
    private fun oldDue(now: Long, probed: Boolean = false, last: Long = 0L) =
        now - last >= if (probed) steady else minOf(preflight, steady)

    @Test
    fun `a Start logging confirmation fires at its time, not before`() {
        // Steady reading at 200 s, Start acknowledged at 300 s, confirm at 320 s.
        fun confirm(now: Long) = WritePointerSchedule.isDue(
            now, lastProbeNanos = 200 * s, firstFixNanos = 100 * s, probedYet = true,
            preflightNanos = preflight, steadyNanos = steady, confirmAtNanos = 320 * s,
        )
        assertFalse(confirm(319 * s))
        assertTrue(confirm(320 * s), "ten minutes early, on purpose")
    }

    @Test
    fun `without a confirmation pending nothing changes`() {
        assertFalse(
            WritePointerSchedule.isDue(
                400 * s, 200 * s, 100 * s, true, preflight, steady, confirmAtNanos = null,
            )
        )
    }

    @Test
    fun `the 2026-10-02 connect -- no fix at 20 s, so no probe`() {
        // Recorded: connect 23:09:19, probe at +20 s against a pointer that
        // could not move, first fix at +133 s. That probe is the one removed.
        assertFalse(due(now = 20 * s, fix = null))
        assertFalse(due(now = 140 * s, fix = 133 * s))
        assertTrue(due(now = 153 * s, fix = 133 * s))
    }

    @Test
    fun `a fix already present at connect keeps the old timing`() {
        assertFalse(due(now = 19 * s, fix = 0L))
        assertTrue(due(now = 20 * s, fix = 0L))
        // A fix stamped before the loop started does not pull the probe earlier.
        assertFalse(due(now = 19 * s, fix = -30 * s))
    }

    @Test
    fun `no fix ever falls back to the steady rate`() {
        assertFalse(due(now = 599 * s, fix = null))
        assertTrue(due(now = 600 * s, fix = null))
    }

    @Test
    fun `after the pre-flight only the steady rate applies`() {
        assertFalse(due(now = 400 * s, fix = 100 * s, probed = true, last = 200 * s))
        assertTrue(due(now = 800 * s, fix = 100 * s, probed = true, last = 200 * s))
    }

    @Test
    fun `never due where the connect-timed schedule was not`() {
        // The invariant: this can only remove or delay probes, never add one.
        val fixes = listOf<Long?>(null, -5 * s, 0L, 1 * s, 19 * s, 21 * s, 300 * s, 700 * s)
        for (fix in fixes) for (probed in listOf(false, true)) {
            var t = 0L
            while (t <= 1_200 * s) {
                if (due(t, fix, probed)) {
                    assertTrue(oldDue(t, probed), "fix=$fix probed=$probed t=${t / s}s")
                }
                t += s
            }
        }
    }
}
