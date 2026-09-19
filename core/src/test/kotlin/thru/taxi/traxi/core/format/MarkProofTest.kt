package thru.taxi.traxi.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
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
