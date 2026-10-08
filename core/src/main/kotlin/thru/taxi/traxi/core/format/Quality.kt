package thru.taxi.traxi.core.format

/**
 * Post-parse filtering of fixes that are structurally valid but positionally
 * untrustworthy.
 *
 * Kept strictly separate from [MtkLogParser]: parsing is about reproducing what
 * the flash says, filtering is about deciding what to believe. Only the parser
 * output is treated as ground truth, so a change of opinion here never requires
 * re-reading the device.
 */
object Quality {

    /**
     * Rolling-median elevation spike rejection.
     *
     * @param windowRadius samples either side of the point under test. 2 gives
     *   the 5-sample window that was validated against a closed loop on the
     *   San Juan trip.
     * @param thresholdMetres rejection distance from the local median.
     * @param nonDgpsThresholdMetres tighter threshold applied to fixes without
     *   a differential correction. On the reference dump 40 of 42 spikes are
     *   `VALID == SPS`, a class holding only 1.8% of records - roughly a 90x
     *   enrichment. Quality is a strong prior, but not a filter on its own:
     *   rejecting all SPS fixes would discard 2,333 points to catch 40.
     */
    data class SpikeConfig(
        val windowRadius: Int = 2,
        val thresholdMetres: Double = 25.0,
        val nonDgpsThresholdMetres: Double = 12.0,
    )

    /** Reason a fix was excluded, for surfacing counts in the UI. */
    enum class Rejection { SENTINEL, NO_FIX, ESTIMATED, NO_POSITION, ELEVATION_SPIKE }

    data class Filtered(
        val kept: List<Fix>,
        val rejected: Map<Rejection, Int>,
        /** Button presses flagged NO_FIX but kept by [MarkRescue]; already in [kept]. */
        val rescuedMarks: Int = 0,
    ) {
        val rejectedCount: Int get() = rejected.values.sum()
    }

    /**
     * When a button press flagged NO_FIX is still believed.
     *
     * A press is the only data in the log a person chose to make, and the chip
     * often has no solution at the moment of it -- 7 of the reference dump's
     * 1,419 marks are NO_FIX. So a NO_FIX press is kept when a believed fix
     * within [maxSeconds], before or after, puts the walker within [maxMetres]
     * of it.
     *
     * Either side, because the first press of a morning comes before the
     * first fix: the previous one is last night's. Not the previous fix
     * alone, because those morning presses carry the chip's stored position,
     * which matches last night to the metre and is no witness at all -- three
     * on the reference dump sit 1.0-1.5 km from the first real fix 20-211 s
     * later. Of the seven, three pass (23-52 m out); the stale three and the
     * 90N sentinel do not.
     */
    data class MarkRescue(
        val maxMetres: Double = 100.0,
        val maxSeconds: Long = 300,
    )

    /**
     * Apply sentinel masking, quality masking, and spike rejection.
     *
     * Note what is *not* here: masking on `height == 150.0`. That sentinel is
     * real but redundant - the single record carrying it also carries latitude
     * 90.0 - and testing height alone would reject 16 genuine elevations that
     * merely round to 150.0 when formatted to one decimal.
     */
    fun filter(
        fixes: List<Fix>,
        config: SpikeConfig = SpikeConfig(),
        rescue: MarkRescue = MarkRescue(),
    ): Filtered {
        val rejected = mutableMapOf<Rejection, Int>()
        fun reject(r: Rejection) { rejected[r] = (rejected[r] ?: 0) + 1 }

        fun believed(fix: Fix) = fix.hasPosition && !fix.isSentinel &&
            fix.quality != FixQuality.NO_FIX && fix.quality != FixQuality.ESTIMATED

        val surviving = mutableListOf<Fix>()
        var rescued = 0
        for ((i, fix) in fixes.withIndex()) {
            when {
                !fix.hasPosition -> reject(Rejection.NO_POSITION)
                fix.isSentinel -> reject(Rejection.SENTINEL)
                fix.quality == FixQuality.NO_FIX ->
                    if (fix.isWaypoint && witnessed(fixes, i, ::believed, rescue)) {
                        surviving += fix; rescued++
                    } else {
                        reject(Rejection.NO_FIX)
                    }
                fix.quality == FixQuality.ESTIMATED -> reject(Rejection.ESTIMATED)
                else -> surviving += fix
            }
        }

        val spikes = findElevationSpikes(surviving, config)
        if (spikes.isEmpty()) return Filtered(surviving, rejected, rescued)

        rejected[Rejection.ELEVATION_SPIKE] = spikes.size
        val kept = surviving.filterIndexed { i, _ -> i !in spikes }
        return Filtered(kept, rejected, rescued - spikes.count { surviving[it].quality == FixQuality.NO_FIX })
    }

    /** Whether the nearest believed fix on either side vouches for [fixes]`[i]`. */
    private fun witnessed(
        fixes: List<Fix>,
        i: Int,
        believed: (Fix) -> Boolean,
        rescue: MarkRescue,
    ): Boolean {
        val mark = fixes[i]
        val before = (i - 1 downTo 0).firstOrNull { believed(fixes[it]) }?.let { fixes[it] }
        val after = (i + 1 until fixes.size).firstOrNull { believed(fixes[it]) }?.let { fixes[it] }
        return listOfNotNull(before, after).any {
            kotlin.math.abs(it.epochSeconds - mark.epochSeconds) <= rescue.maxSeconds &&
                thru.taxi.traxi.core.analysis.DayCarver.metres(it, mark) <= rescue.maxMetres
        }
    }

    /** Indices in [fixes] whose elevation departs too far from the local median. */
    fun findElevationSpikes(fixes: List<Fix>, config: SpikeConfig): Set<Int> {
        val r = config.windowRadius
        if (fixes.size < 2 * r + 1) return emptySet()

        val spikes = mutableSetOf<Int>()
        val window = DoubleArray(2 * r + 1)

        for (i in r until fixes.size - r) {
            val height = fixes[i].height?.toDouble() ?: continue
            var n = 0
            var complete = true
            for (j in i - r..i + r) {
                val h = fixes[j].height?.toDouble()
                if (h == null) { complete = false; break }
                window[n++] = h
            }
            if (!complete) continue

            val median = medianOf(window, n)
            val threshold = if (fixes[i].quality == FixQuality.DGPS) {
                config.thresholdMetres
            } else {
                config.nonDgpsThresholdMetres
            }
            if (kotlin.math.abs(height - median) > threshold) spikes += i
        }
        return spikes
    }

    private fun medianOf(values: DoubleArray, n: Int): Double {
        val copy = values.copyOf(n)
        copy.sort()
        return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
    }

    /** Split into track segments on time gaps, the way the reference export does. */
    fun segment(fixes: List<Fix>, gapSeconds: Long = 300): List<List<Fix>> {
        if (fixes.isEmpty()) return emptyList()
        val segments = mutableListOf<List<Fix>>()
        var current = mutableListOf<Fix>()
        var previous: Long? = null

        for (fix in fixes) {
            if (previous != null && fix.epochSeconds - previous > gapSeconds) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            }
            current += fix
            previous = fix.epochSeconds
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }
}
