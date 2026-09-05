package ai.moonlite.btdroid.core.format

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
    ) {
        val rejectedCount: Int get() = rejected.values.sum()
    }

    /**
     * Apply sentinel masking, quality masking, and spike rejection.
     *
     * Note what is *not* here: masking on `height == 150.0`. That sentinel is
     * real but redundant - the single record carrying it also carries latitude
     * 90.0 - and testing height alone would reject 16 genuine elevations that
     * merely round to 150.0 when formatted to one decimal.
     */
    fun filter(fixes: List<Fix>, config: SpikeConfig = SpikeConfig()): Filtered {
        val rejected = mutableMapOf<Rejection, Int>()
        fun reject(r: Rejection) { rejected[r] = (rejected[r] ?: 0) + 1 }

        val surviving = fixes.filter { fix ->
            when {
                !fix.hasPosition -> { reject(Rejection.NO_POSITION); false }
                fix.isSentinel -> { reject(Rejection.SENTINEL); false }
                fix.quality == FixQuality.NO_FIX -> { reject(Rejection.NO_FIX); false }
                fix.quality == FixQuality.ESTIMATED -> { reject(Rejection.ESTIMATED); false }
                else -> true
            }
        }

        val spikes = findElevationSpikes(surviving, config)
        if (spikes.isEmpty()) return Filtered(surviving, rejected)

        rejected[Rejection.ELEVATION_SPIKE] = spikes.size
        val kept = surviving.filterIndexed { i, _ -> i !in spikes }
        return Filtered(kept, rejected)
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
