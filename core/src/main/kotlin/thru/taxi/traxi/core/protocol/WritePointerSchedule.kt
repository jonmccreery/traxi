package thru.taxi.traxi.core.protocol

/**
 * When the telemetry loop may next spend a write-pointer query.
 *
 * Every query costs the link (§15), so the session gets one early "pre-flight"
 * probe to resolve the recording indicator, then settles to the transport's
 * steady rate. This decides when that pre-flight fires.
 *
 * It used to be timed from connect. The logger writes nothing until it has a
 * fix, so a connect made before the sky is acquired spent its one early probe
 * on a pointer that could not have moved: on 2026-10-02 it fired at 20 s, the
 * first fix came at 2m13s, and the indicator then waited out the full
 * ten-minute steady interval. It is now timed from the first fix -- which the
 * app learns by listening, at no cost on the wire.
 *
 * The invariant the tests hold it to: **never earlier, and never more often,
 * than the connect-timed schedule it replaces.** Without a fix the pre-flight
 * is simply skipped and the steady rate applies, because a frozen pointer with
 * no position to write is evidence of nothing.
 *
 * **One exception, approved 2026-10-07:** after the user presses Start logging
 * and the logger acknowledges it, one confirming check at [confirmAtNanos].
 * Starting the logger has been the hardest thing in this project to be sure
 * of, and the ten-minute wait left the answer out of reach. It costs one query
 * per press, the caller clears it once spent, and the steady schedule restarts
 * from it -- so it brings the next reading forward rather than adding a stream
 * of them.
 */
object WritePointerSchedule {

    /**
     * @param nowNanos the current monotonic time.
     * @param lastProbeNanos the last probe, or the start of the loop if none yet.
     * @param firstFixNanos the first fix seen this session, or null if none yet.
     * @param probedYet whether this session has spent its pre-flight.
     * @param preflightNanos how long after the fix the pre-flight waits.
     * @param steadyNanos the transport's steady interval.
     */
    fun isDue(
        nowNanos: Long,
        lastProbeNanos: Long,
        firstFixNanos: Long?,
        probedYet: Boolean,
        preflightNanos: Long,
        steadyNanos: Long,
        confirmAtNanos: Long? = null,
    ): Boolean {
        if (confirmAtNanos != null && nowNanos >= confirmAtNanos) return true
        val steadyDue = nowNanos - lastProbeNanos >= steadyNanos
        if (probedYet || firstFixNanos == null) return steadyDue
        // A fix already present at connect counts from connect, never before
        // it: that is what keeps this from firing earlier than it used to.
        val armedAt = maxOf(firstFixNanos, lastProbeNanos)
        return steadyDue || nowNanos - armedAt >= minOf(preflightNanos, steadyNanos)
    }
}
