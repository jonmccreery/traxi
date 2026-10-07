package thru.taxi.traxi.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The trailhead proof.
 *
 * The distinction this file exists to hold is between *nothing was written* and
 * *nothing could have been written*. They are the same arithmetic — the pointer
 * did not move — and one is the worst news the app can deliver while the other
 * is a logger sitting indoors behaving perfectly.
 */
class MarkProofTest {

    private val record = LogFormat.WITH_SATELLITE_QUALITY.recordSizeWithChecksum()

    // ---- can the press be told apart from the interval timer? ----
    //
    // Recorded on the hardware 2026-09-25: the card sat waiting on a human for
    // four minutes at a 10 s interval, 21 records landed, and it announced that
    // the beep "covered one real write". Twenty-one is what the timer writes on
    // its own across that window. The proof of recording was sound; the beep
    // claim was not, and nothing in the code could tell the two apart.

    @Test
    fun `a wait longer than the interval cannot isolate the press`() {
        val v = MarkProof.of(
            before = 0L,
            after = 21L * record,
            recordBytes = record,
            hadFix = true,
            elapsedSeconds = 240.0,
            intervalSeconds = 10.0,
        )
        assertIs<MarkProof.Result.Proven>(v)
        // Recording is still proven: the bytes are real and the count stands.
        assertEquals(21L, v.records)
        // The timer alone accounts for 24 ticks, plus one for phase.
        assertEquals(25L, v.explainedByInterval)
        assertFalse(v.markIsolated, "21 records is what the timer writes anyway")
    }

    @Test
    fun `more than the timer can explain does isolate the press`() {
        val v = MarkProof.of(
            before = 0L,
            after = 4L * record,
            recordBytes = record,
            hadFix = true,
            elapsedSeconds = 20.0,   // two ticks, plus one for phase
            intervalSeconds = 10.0,
        )
        assertIs<MarkProof.Result.Proven>(v)
        assertEquals(3L, v.explainedByInterval)
        assertTrue(v.markIsolated, "a fourth record the timer cannot account for")
    }

    @Test
    fun `the phase allowance is what stops a false isolation at the boundary`() {
        // 240 s at 10 s is 24 ticks, but a window out of phase at both ends
        // fits 25. Without the allowance a 25-record advance would be read as
        // "one more than the timer could write" and credited to the press --
        // the exact over-claim this arithmetic exists to prevent, arriving at
        // the one count where it is most believable.
        val v = MarkProof.of(
            before = 0L,
            after = 25L * record,
            recordBytes = record,
            hadFix = true,
            elapsedSeconds = 240.0,
            intervalSeconds = 10.0,
        )
        assertIs<MarkProof.Result.Proven>(v)
        assertEquals(25L, v.records)
        assertFalse(v.markIsolated, "25 records still fits a timer out of phase")

        // One more, and it no longer does.
        val next = MarkProof.of(
            before = 0L,
            after = 26L * record,
            recordBytes = record,
            hadFix = true,
            elapsedSeconds = 240.0,
            intervalSeconds = 10.0,
        )
        assertIs<MarkProof.Result.Proven>(next)
        assertTrue(next.markIsolated, "26 is past everything the timer explains")
    }

    @Test
    fun `an unknown interval never counts as a timer that wrote nothing`() {
        // The dangerous reading. Treating "unknown" as zero hands the whole
        // advance to the press and restores exactly the claim this prevents.
        for (pair in listOf(null to 10.0, 240.0 to null, 240.0 to 0.0, -1.0 to 10.0)) {
            val v = MarkProof.of(
                before = 0L,
                after = 21L * record,
                recordBytes = record,
                hadFix = true,
                elapsedSeconds = pair.first,
                intervalSeconds = pair.second,
            )
            assertIs<MarkProof.Result.Proven>(v)
            assertEquals(null, v.explainedByInterval, "for $pair")
            assertFalse(v.markIsolated, "unknown must not be read as isolated, for $pair")
        }
    }

    @Test
    fun `callers that know neither figure still get a verdict`() {
        // Every existing caller and test omits both arguments; the proof of
        // recording must not depend on them.
        val v = MarkProof.of(0L, record.toLong(), record, hadFix = true)
        assertIs<MarkProof.Result.Proven>(v)
        assertEquals(1L, v.records)
        assertEquals(null, v.explainedByInterval)
    }

    @Test
    fun `one record's advance proves the whole chain`() {
        val v = MarkProof.of(
            before = 0x50B90L,
            after = 0x50B90L + record,
            recordBytes = record,
            hadFix = true,
        )
        assertIs<MarkProof.Result.Proven>(v)
        assertEquals(1L, v.records)
        assertEquals(record.toLong(), v.bytes)
    }

    @Test
    fun `an interval record landing alongside the press still proves it`() {
        // The press is not the only thing that can write during the check, and
        // requiring exactly one record would fail a perfectly healthy logger
        // that ticked its interval at the same moment.
        val v = MarkProof.of(0x50B90L, 0x50B90L + 3L * record, record, hadFix = true)
        assertIs<MarkProof.Result.Proven>(v)
        assertEquals(3L, v.records)
    }

    @Test
    fun `a fix and a frozen pointer is the damning case`() {
        // The user pressed the button, the receiver had a position, and nothing
        // reached flash. Nothing else in the app establishes this so directly.
        assertEquals(
            MarkProof.Result.NothingWritten,
            MarkProof.of(0x50B90L, 0x50B90L, record, hadFix = true),
        )
    }

    @Test
    fun `no fix and a frozen pointer condemns nothing`() {
        // Indoors this logger holds no fix for hours and correctly writes
        // nothing. Reporting that as a failed proof would be the same false
        // alarm the recording indicator already refuses to make.
        val v = MarkProof.of(0x50B90L, 0x50B90L, record, hadFix = false)
        assertIs<MarkProof.Result.Inconclusive>(v)
        assertTrue(v.reason.contains("no position"), v.reason)
    }

    @Test
    fun `an unanswered query is inconclusive, not a failure`() {
        // Asking is what kills this link (§15), so an unanswered probe is an
        // expected outcome of the check itself -- not evidence about recording.
        assertIs<MarkProof.Result.Inconclusive>(
            MarkProof.of(null, 0x50B90L, record, hadFix = true)
        )
        assertIs<MarkProof.Result.Inconclusive>(
            MarkProof.of(0x50B90L, null, record, hadFix = true)
        )
    }

    @Test
    fun `a wrap mid-check is inconclusive rather than nothing written`() {
        val v = MarkProof.of(5_300_000L, 12_800L, record, hadFix = true)
        assertIs<MarkProof.Result.Inconclusive>(v)
        assertTrue(v.reason.contains("backwards"), v.reason)
    }
}
