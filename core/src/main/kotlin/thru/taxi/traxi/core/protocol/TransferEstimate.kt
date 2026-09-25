package thru.taxi.traxi.core.protocol

/**
 * How long a flash transfer has left.
 *
 * Pulled out of `SessionController` because this arithmetic has now been wrong
 * twice in the same way, and neither time was a test able to notice. The rule
 * both bugs broke is the same one: **the clock must not start before the work
 * does.**
 *
 *  - `d070013` fixed it inside a block. Timing the first chunk from the moment
 *    the transfer started charged it the request round trip, the device's own
 *    flash read and the read-lock wait, and produced a rate an order of
 *    magnitude low -- the first sector advertising three hours where the second
 *    showed twelve minutes.
 *  - §16.16 found it again around the whole transfer. An incremental fetch
 *    begins by re-reading one sector to check for a wrap, and during that probe
 *    the file position is deliberately pinned. The baseline was armed at the
 *    probe's first arriving chunk, so the transfer opened 165 seconds in debt
 *    with zero bytes against it, and spent the rest of the run paying that off:
 *    27.1 minutes shown against a true 13.5.
 *
 * The estimate is built on whole-run progress rather than the smoothed
 * instantaneous rate, which is deliberate (`932b1fd`) -- a burst at a sector
 * boundary must not be able to talk it down, and the between-block overhead has
 * to be counted rather than skipped. The price of averaging is that it carries
 * its history, so anything charged to it early is paid off slowly. That is
 * precisely why the baseline has to be right.
 */
object TransferEstimate {

    /** The point the whole-run average measures from. */
    data class Baseline(val atNanos: Long, val bytes: Int)

    /**
     * Move the baseline forward while nothing has actually been appended.
     *
     * A transfer that has added no bytes is not slow, it is **not started**, and
     * an average over that stretch describes something else entirely. Once one
     * byte has landed the baseline is frozen, so an ordinary mid-transfer stall
     * is still charged to the rate, exactly as it should be -- a link that goes
     * quiet for a minute really has made the finish a minute further away.
     *
     * @param previous the current baseline, or null before the first chunk.
     */
    fun rebase(previous: Baseline?, nowNanos: Long, bytesDownloaded: Int): Baseline {
        if (previous == null) return Baseline(nowNanos, bytesDownloaded)
        // `<=` rather than `==` so a file position that goes backwards -- a
        // restarted block, a resumed run -- re-arms rather than producing a
        // negative advance and a nonsense rate.
        if (bytesDownloaded <= previous.bytes) return Baseline(nowNanos, bytesDownloaded)
        return previous
    }

    /**
     * Seconds to [target], or null when there is nothing honest to say.
     *
     * Null rather than a guess in every unsettled case: before [minSamples]
     * arrivals, before any advance, and once the read has passed the target --
     * which is the wrapped-log case, where the run continues to the end of
     * flash and the figure would be counting down to a post already behind it.
     * A wrong number shown confidently is worse than none; it is the figure
     * someone decides whether to keep waiting on.
     */
    fun secondsRemaining(
        baseline: Baseline?,
        nowNanos: Long,
        bytesDownloaded: Int,
        target: Int?,
        samples: Int,
        minSamples: Int,
    ): Int? {
        if (baseline == null || target == null) return null
        if (samples < minSamples) return null
        if (bytesDownloaded >= target) return null
        val elapsed = (nowNanos - baseline.atNanos) / 1e9
        val advanced = bytesDownloaded - baseline.bytes
        if (elapsed <= 0 || advanced <= 0) return null
        val bytesPerSecond = advanced / elapsed
        if (bytesPerSecond <= 0) return null
        return ((target - bytesDownloaded) / bytesPerSecond).toInt()
    }
}
