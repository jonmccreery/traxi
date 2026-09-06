package thru.taxi.traxi.core.format

import java.time.Duration
import java.time.Instant

/**
 * Whether a dump shows the logger ever silently stopped recording.
 *
 * Silent loss of capture is the worst outcome this project has: a logger that
 * is not recording is indistinguishable from one that is, and the mistake is
 * only discovered when the trip is over and the data does not exist. The app's
 * transcript is in memory and dies with the process, so it cannot answer the
 * question after the fact. The flash can — the device writes a marker every
 * time logging starts or stops, inline between timestamped fixes.
 *
 * This turns those markers into the two things a user actually needs to know:
 * did recording stop and never resume, and did anything stop it that should
 * not have.
 */
object RecordingAudit {

    /**
     * @param stops total number of times logging stopped.
     * @param unansweredStops stops that nothing re-enabled before the next
     *   stop. **Any value above zero is a fault, not a usage pattern** — see
     *   [Report.explanation].
     * @param endsRecording whether the last thing the log records is a start.
     * @param lastStopAt time of the final stop when [endsRecording] is false,
     *   as a lower bound: the last fix written before the marker.
     * @param longestGap the longest interval between a stop and the following
     *   start, which is the largest stretch this log is known to have missed.
     */
    data class Report(
        val stops: Int,
        val unansweredStops: Int,
        val endsRecording: Boolean,
        val lastStopAt: Instant?,
        val longestGap: Duration?,
    ) {
        /**
         * True when this dump carries evidence of the fault rather than of
         * ordinary use.
         *
         * Deliberately **not** keyed on [endsRecording]. A dump captured while
         * the device is powered down ends with a stop and that is completely
         * normal — the matching start is written at the next power-on. Treating
         * that as a fault was an early mistake here and it would cry wolf on
         * every dump.
         */
        val hasFault: Boolean get() = unansweredStops > 0

        /**
         * What to tell the user, or null when there is nothing to say.
         *
         * Null on every healthy dump, deliberately. A clean parse gets **no**
         * recording commentary at all -- not a reassurance, not a "looks fine",
         * not an explanation of why a benign condition is benign. This alarm
         * only ever fires for real data loss, so silence is what carries the
         * good news, and a reader who sees anything here knows immediately that
         * it matters.
         *
         * That rules out speaking about [endsRecording] here. A dump ending
         * with a stop is the ordinary result of switching the logger off, and a
         * paragraph explaining that on every such parse is precisely the noise
         * that teaches people to skim. The live device state on the Device tab
         * is where a stopped logger is both authoritative and actionable.
         */
        fun explanation(): String? =
            if (unansweredStops > 0) {
                "$unansweredStops time(s) the logger was told to stop recording and " +
                    "nothing started it again. This is what an interrupted settings " +
                    "write used to leave behind, and any stretch of trip after it was " +
                    "not recorded."
            } else {
                null
            }
    }

    fun of(stats: MtkLogParser.Stats, fixes: List<Fix>): Report {
        val changes = stats.statusChanges
        if (changes.isEmpty()) {
            return Report(0, 0, endsRecording = true, lastStopAt = null, longestGap = null)
        }

        // A stop immediately followed by another stop was never answered.
        val unanswered = (0 until changes.size - 1).count {
            !changes[it].enabled && !changes[it + 1].enabled
        }

        // Widest known hole: from the fix before a stop to the fix after the
        // start that ended it.
        var longest: Duration? = null
        for (i in changes.indices) {
            if (changes[i].enabled) continue
            val resumed = changes.drop(i + 1).firstOrNull { it.enabled } ?: continue
            val from = changes[i].after ?: continue
            val to = fixes.firstOrNull { it.instant > from }?.instant ?: continue
            if (to <= from) continue
            val gap = Duration.between(from, to)
            if (longest == null || gap > longest) longest = gap
            // `resumed` is only needed to know the stop was answered at all.
            check(resumed.enabled)
        }

        val last = changes.last()
        return Report(
            stops = changes.count { !it.enabled },
            unansweredStops = unanswered,
            endsRecording = last.enabled,
            lastStopAt = if (last.enabled) null else last.after,
            longestGap = longest,
        )
    }
}
