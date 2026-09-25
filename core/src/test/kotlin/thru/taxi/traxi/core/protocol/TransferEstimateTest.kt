package thru.taxi.traxi.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The estimate, and the distinction it has twice failed to hold: time in which
 * nothing was transferred is not slow transfer.
 *
 * The numbers here are the real ones from the 2026-09-24 fetch (§16.16), so a
 * regression reads as the thing the user actually saw rather than as an
 * abstract arithmetic failure.
 */
class TransferEstimateTest {

    private val S = 1_000_000_000L
    private val BLOCK = 65_536

    // Measured: 65,536 bytes per block in ~160 s, about 405 B/s.
    private val TRUE_BPS = 405.0

    @Test
    fun `the wrap probe does not start the clock`() {
        // 165 s of probe, during which the downloader deliberately pins the
        // file position. Every callback must push the baseline forward.
        var b = TransferEstimate.rebase(null, 0L, 131_072)
        for (t in 1..165) b = TransferEstimate.rebase(b, t * S, 131_072)
        assertEquals(165 * S, b.atNanos, "the baseline must track the last pinned sample")
        assertEquals(131_072, b.bytes)
    }

    @Test
    fun `the first figure is right, instead of the last`() {
        // The regression this file exists for. Baseline armed at the probe's
        // first chunk and never moved gave 27.1 min against a true 13.5.
        var b = TransferEstimate.rebase(null, 0L, 131_072)
        for (t in 1..165) b = TransferEstimate.rebase(b, t * S, 131_072)

        // One block lands: 65,536 bytes over the 160 s it really took.
        val now = (165 + 160) * S
        val downloaded = 131_072 + BLOCK
        val target = 524_288

        val eta = TransferEstimate.secondsRemaining(
            baseline = TransferEstimate.rebase(b, now, downloaded),
            nowNanos = now,
            bytesDownloaded = downloaded,
            target = target,
            samples = 32,
            minSamples = 4,
        )
        assertNotNull(eta)
        val truth = (target - downloaded) / TRUE_BPS
        assertTrue(
            eta in (truth * 0.95).toInt()..(truth * 1.05).toInt(),
            "expected about ${truth.toInt()} s, got $eta",
        )
    }

    @Test
    fun `a mid-transfer stall is still charged to the rate`() {
        // The other side of the rule, and the reason rebase stops at the first
        // byte rather than whenever the link goes quiet. A minute of silence
        // really does put the finish a minute further away.
        val b = TransferEstimate.Baseline(0L, 0)
        val moving = TransferEstimate.secondsRemaining(
            b, 100 * S, 40_000, 100_000, samples = 32, minSamples = 4)
        val stalled = TransferEstimate.secondsRemaining(
            b, 160 * S, 40_000, 100_000, samples = 32, minSamples = 4)
        assertNotNull(moving); assertNotNull(stalled)
        assertTrue(stalled > moving, "a stall must lengthen the estimate, got $stalled vs $moving")

        // And the baseline must not have moved: bytes have advanced past it.
        assertEquals(b, TransferEstimate.rebase(b, 160 * S, 40_000))
    }

    @Test
    fun `a file position that goes backwards re-arms rather than going negative`() {
        // A restarted block or a resumed run. Subtracting would give a negative
        // advance and an estimate with the wrong sign.
        val b = TransferEstimate.Baseline(10 * S, 80_000)
        val rebased = TransferEstimate.rebase(b, 20 * S, 40_000)
        assertEquals(TransferEstimate.Baseline(20 * S, 40_000), rebased)
    }

    @Test
    fun `nothing is shown before enough arrivals`() {
        // One interval over a bursty link is not a throughput.
        assertNull(
            TransferEstimate.secondsRemaining(
                TransferEstimate.Baseline(0L, 0), 10 * S, 20_000, 100_000,
                samples = 3, minSamples = 4,
            )
        )
    }

    @Test
    fun `nothing is shown before any advance`() {
        assertNull(
            TransferEstimate.secondsRemaining(
                TransferEstimate.Baseline(0L, 131_072), 60 * S, 131_072, 524_288,
                samples = 32, minSamples = 4,
            )
        )
    }

    @Test
    fun `a read past the target says nothing rather than counting backwards`() {
        // The wrapped-log case: the run continues to the end of flash, and the
        // pointer-derived target is already behind it.
        assertNull(
            TransferEstimate.secondsRemaining(
                TransferEstimate.Baseline(0L, 0), 60 * S, 600_000, 524_288,
                samples = 32, minSamples = 4,
            )
        )
    }

    @Test
    fun `the estimate counts down to zero at the real finish`() {
        // §16.16: the target's +2 blocks are the same two blocks as
        // unwrittenSectorsToStop, so the target IS where the read stops. The
        // figure is therefore allowed to reach zero rather than stranding.
        val b = TransferEstimate.Baseline(0L, 131_072)
        val nearEnd = TransferEstimate.secondsRemaining(
            b, 970 * S, 524_288 - 2_048, 524_288, samples = 32, minSamples = 4)
        assertNotNull(nearEnd)
        assertTrue(nearEnd < 30, "expected a few seconds at the finish, got $nearEnd")
    }
}
