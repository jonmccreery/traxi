package ai.moonlite.btdroid.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detection of silent capture loss.
 *
 * Built from a real incident: an interrupted settings write left the logger
 * switched off for an hour and forty minutes, and nothing in the app could say
 * so afterwards because the transcript lives in memory and had been destroyed
 * by a reinstall. The device's own markers survived, and this is what reads
 * them.
 */
class RecordingAuditTest {

    private var clock = 1_000_000L

    private fun change(enabled: Boolean, at: Long? = null) =
        MtkLogParser.StatusChange(
            offset = (clock++ % 100000).toInt(),
            enabled = enabled,
            after = java.time.Instant.ofEpochSecond(at ?: clock),
        )

    private fun stats(vararg changes: MtkLogParser.StatusChange) =
        MtkLogParser.Stats().apply { statusChanges += changes }

    @Test
    fun `an empty log reports no fault`() {
        val r = RecordingAudit.of(MtkLogParser.Stats(), emptyList())
        assertFalse(r.hasFault)
        assertTrue(r.endsRecording)
        assertNull(r.explanation())
    }

    @Test
    fun `a stop answered by a start is normal use`() {
        val r = RecordingAudit.of(stats(change(false), change(true)), emptyList())
        assertEquals(0, r.unansweredStops)
        assertFalse(r.hasFault)
        assertTrue(r.endsRecording)
    }

    @Test
    fun `two stops in a row is the fault signature`() {
        // The fingerprint of a settings write that failed between disabling
        // logging and re-enabling it.
        val r = RecordingAudit.of(stats(change(false), change(false), change(true)), emptyList())
        assertEquals(1, r.unansweredStops)
        assertTrue(r.hasFault)
        assertTrue(r.explanation()!!.contains("nothing started it again"))
    }

    @Test
    fun `ending on a stop is NOT a fault, and says nothing at all`() {
        // Powering the device off writes a stop; the matching start arrives at
        // the next power-on. The reference dump from before any of this ends
        // exactly that way, and treating it as a fault would cry wolf on every
        // dump taken from a switched-off logger.
        //
        // It does not warrant a reassuring note either. Explaining on every
        // ordinary parse why a benign thing is benign is the noise that teaches
        // people to skim past the line that matters.
        val r = RecordingAudit.of(stats(change(true), change(false)), emptyList())
        assertFalse(r.hasFault)
        assertFalse(r.endsRecording)
        assertNull(r.explanation())
    }

    @Test
    fun `a healthy log says nothing`() {
        val r = RecordingAudit.of(stats(change(false), change(true)), emptyList())
        assertFalse(r.hasFault)
        assertNull(r.explanation(), "a clean parse must not comment on recording")
    }

    @Test
    fun `ending on a stop still reports when it happened`() {
        val at = java.time.Instant.parse("2026-09-06T16:16:10Z")
        val r = RecordingAudit.of(
            stats(change(true), MtkLogParser.StatusChange(0x53CBCA, false, at)),
            emptyList(),
        )
        assertEquals(at, r.lastStopAt)
    }

    @Test
    fun `a fault outranks a plain trailing stop in the explanation`() {
        // Both conditions can hold at once. The one that means lost data wins,
        // because that is the one the user has to act on.
        val r = RecordingAudit.of(
            stats(change(false), change(false), change(true), change(false)),
            emptyList(),
        )
        assertTrue(r.hasFault)
        assertFalse(r.endsRecording)
        assertTrue(r.explanation()!!.contains("nothing started it again"))
    }

    @Test
    fun `stops are counted, not just faults`() {
        val r = RecordingAudit.of(
            stats(change(false), change(true), change(false), change(true)),
            emptyList(),
        )
        assertEquals(2, r.stops)
        assertEquals(0, r.unansweredStops)
    }
}
