package thru.taxi.traxi.core.analysis

import thru.taxi.traxi.core.format.Fix
import thru.taxi.traxi.core.format.Quality
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Carves a dump into hiking days, and each day into segments between gaps.
 *
 * **A day ends at the overnight gap.** On the CDT dump (`cdt_v2.bin`, 70 local
 * days) the logger was off for more than four hours across 66 of 69 nights, so
 * the gap already *is* the day boundary -- and using it needs no time zone, on
 * a trail that crosses them. The three nights it logged straight through are
 * split at the middle of the night's stop instead, and only if no stop is long
 * enough to be a night does local midnight decide -- solar midnight, read from
 * each fix's own longitude, so that still needs no time zone.
 *
 * **Nothing is ever measured across a gap.** On the 2026-09-25 dump, most of
 * the naive distance on three ride days was single straight-line steps across
 * the logger being off -- one of them 17.5 km. A gap is reported, with its
 * length, and never bridged.
 *
 * **A ride is its own track.** A sustained run at vehicle speed, by the
 * logger's own speed field, becomes a [Mode.VEHICLE] segment with its own
 * figures; the day's walking figures never include it. A single fast step --
 * a spike -- is not a ride, and stays in the walk where cleaning skips it.
 *
 * Raw and cleaned figures are both kept, because they answer different
 * questions and the gap between them is itself information: raw is the track
 * exactly as logged, cleaned removes camp jitter and impossible jumps.
 */
object DayCarver {

    data class Config(
        /** A gap at least this long ends the day. */
        val overnightGapSeconds: Long = 4 * 3600,
        /** A gap longer than this splits a segment within the day. Matches [thru.taxi.traxi.core.export.GpxWriter]. */
        val segmentGapSeconds: Long = 300,
        /** A day longer than this, with no night-length stop in it, splits at midnight. */
        val maxDaySeconds: Long = 20 * 3600,
        /**
         * A logged stop at least this long is a night. Longer than the
         * overnight gap on purpose: a logger left on through a town stop or a
         * storm sits still for hours by day, and the CDT's logged-through
         * nights were 12-14 h of under 6 m of drift.
         */
        val minNightStopSeconds: Long = 6 * 3600,
        /**
         * A night stop only ends a day with at least this much logged on both
         * sides of it. A stop at the edge of a stretch is camp before
         * switching off, or after switching on (CDT 2025-07-29, 2025-03-16) --
         * not a night between two days.
         */
        val minActiveEitherSideSeconds: Long = 3600,
        /** Positions within this of where a stop began are still that stop. */
        val stopRadiusMetres: Double = 50.0,
        /** Cleaned distance only counts movement beyond this from the last counted point. */
        val jitterMetres: Double = 10.0,
        /**
         * Time since the last counted point is moving time only up to this.
         * Past it, the walker had stopped, and only the latest step counts --
         * otherwise the first stride out of camp books the whole night.
         */
        val movingWindowSeconds: Long = 60,
        /**
         * A step faster than this is not walking: a spike, or a vehicle.
         * `mtkparse.py`'s threshold, but applied to every step in a segment
         * rather than only across short intervals -- a ride into town at
         * 31 km/h slipped past the short-interval gate and booked 44 km of
         * "walking" in 1.4 h (CDT 2025-08-20). Raw keeps it.
         */
        val maxGroundSpeedMps: Double = 8.0,
        /** Cleaned elevation only counts a climb or descent once it exceeds this. */
        val elevationHysteresisMetres: Double = 5.0,
        /**
         * The logger's own speed, in km/h, at or above which a fix is riding.
         * Nobody walks at 20 km/h for a minute; the slowest ride on the CDT
         * dump, into town on 2025-08-20, ran at 31.
         */
        val rideMinKmh: Double = 20.0,
        /** A ride must last this long, and hold this many fast fixes, to be one. */
        val rideMinSeconds: Long = 60,
        val rideMinFixes: Int = 3,
        /** Rides this close together are one ride: a stoplight, a junction. */
        val rideBridgeSeconds: Long = 180,
    )

    data class Stats(
        val rawMetres: Double,
        val cleanedMetres: Double,
        /** Time spent between cleaned moves -- what was walked, not waited. */
        val movingSeconds: Long,
        val rawGainMetres: Double,
        val rawLossMetres: Double,
        val cleanedGainMetres: Double,
        val cleanedLossMetres: Double,
    ) {
        operator fun plus(o: Stats) = Stats(
            rawMetres + o.rawMetres,
            cleanedMetres + o.cleanedMetres,
            movingSeconds + o.movingSeconds,
            rawGainMetres + o.rawGainMetres,
            rawLossMetres + o.rawLossMetres,
            cleanedGainMetres + o.cleanedGainMetres,
            cleanedLossMetres + o.cleanedLossMetres,
        )

        companion object {
            val ZERO = Stats(0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    enum class Mode { FOOT, VEHICLE }

    data class Segment(val fixes: List<Fix>, val stats: Stats, val mode: Mode = Mode.FOOT) {
        val start: Instant get() = fixes.first().instant
        val end: Instant get() = fixes.last().instant
    }

    /** Time inside a day with nothing logged, between two segments. */
    data class Gap(val from: Instant, val to: Instant) {
        val seconds: Long get() = to.epochSecond - from.epochSecond
    }

    data class Day(
        /** 1-based, in time order across the dump. */
        val number: Int,
        val segments: List<Segment>,
        val gaps: List<Gap>,
        /** Button-pressed marks, kept apart as the GPX writer does. */
        val waypoints: List<Fix>,
        /** On foot only. */
        val stats: Stats,
        /** The day's [Mode.VEHICLE] segments, kept out of [stats]. */
        val rideStats: Stats = Stats.ZERO,
    ) {
        val rides: List<Segment> get() = segments.filter { it.mode == Mode.VEHICLE }
        val start: Instant get() = segments.first().start
        val end: Instant get() = segments.last().end
        val fixes: List<Fix> get() = segments.flatMap { it.fixes }
        val fixCount: Int get() = segments.sumOf { it.fixes.size }
    }

    data class Carve(
        val days: List<Day>,
        /** What [Quality.filter] dropped before carving, by reason. */
        val rejected: Map<Quality.Rejection, Int>,
    )

    fun carve(fixes: List<Fix>, config: Config = Config()): Carve {
        val filtered = Quality.filter(fixes)
        // Flash is a ring: after a wrap, parse order is not time order.
        val sorted = filtered.kept.sortedBy { it.epochSeconds }

        val chunks = splitAt(sorted) { a, b ->
            b.epochSeconds - a.epochSeconds >= config.overnightGapSeconds
        }.flatMap { splitAtNightsOrMidnight(it, config) }

        val days = chunks.mapIndexed { i, chunk -> buildDay(i + 1, chunk, config) }
        return Carve(days, filtered.rejected)
    }

    private fun buildDay(number: Int, fixes: List<Fix>, config: Config): Day {
        val pieces = splitAt(fixes) { a, b ->
            b.epochSeconds - a.epochSeconds > config.segmentGapSeconds
        }
        // A change between walking and riding is not a gap: nothing is missing.
        val gaps = pieces.zipWithNext { a, b -> Gap(a.last().instant, b.first().instant) }
        val segments = pieces.flatMap { splitRides(it, config) }
            .map { (part, mode) -> Segment(part, measure(part, config, mode), mode) }
        fun total(mode: Mode) = segments.filter { it.mode == mode }
            .fold(Stats.ZERO) { acc, s -> acc + s.stats }
        return Day(
            number = number,
            segments = segments,
            gaps = gaps,
            waypoints = fixes.filter { it.isWaypoint },
            stats = total(Mode.FOOT),
            rideStats = total(Mode.VEHICLE),
        )
    }

    /** Cut a gap-free stretch into alternating walks and rides. */
    private fun splitRides(fixes: List<Fix>, config: Config): List<Pair<List<Fix>, Mode>> {
        val rides = rideRanges(fixes, config)
        if (rides.isEmpty()) return listOf(fixes to Mode.FOOT)
        val out = mutableListOf<Pair<List<Fix>, Mode>>()
        var from = 0
        for (ride in rides) {
            if (ride.first > from) out += fixes.subList(from, ride.first) to Mode.FOOT
            out += fixes.subList(ride.first, ride.last + 1) to Mode.VEHICLE
            from = ride.last + 1
        }
        if (from < fixes.size) out += fixes.subList(from, fixes.size) to Mode.FOOT
        return out
    }

    /**
     * Index ranges of sustained vehicle speed. The logger's speed field is
     * the witness; without one, the step from the previous fix stands in.
     */
    internal fun rideRanges(fixes: List<Fix>, config: Config): List<IntRange> {
        fun fast(i: Int): Boolean {
            fixes[i].speed?.let { return it >= config.rideMinKmh }
            if (i == 0) return false
            val dt = (fixes[i].epochSeconds - fixes[i - 1].epochSeconds).coerceAtLeast(1)
            return metres(fixes[i - 1], fixes[i]) / dt * 3.6 >= config.rideMinKmh
        }
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < fixes.size) {
            if (!fast(i)) { i++; continue }
            var j = i
            while (j + 1 < fixes.size && fast(j + 1)) j++
            val last = runs.lastOrNull()
            runs += if (last != null &&
                fixes[i].epochSeconds - fixes[last.last].epochSeconds <= config.rideBridgeSeconds
            ) {
                runs.removeAt(runs.lastIndex).first..j
            } else {
                i..j
            }
            i = j + 1
        }
        return runs.filter {
            it.last - it.first + 1 >= config.rideMinFixes &&
                fixes[it.last].epochSeconds - fixes[it.first].epochSeconds >= config.rideMinSeconds
        }
    }

    /**
     * A night the logger recorded through: split at the middle of every stop
     * long enough to be one. All of them in one pass -- cutting one, then
     * re-measuring the halves, left half a night inside the evening and cut
     * it again (CDT, 2025-07-17, a 4.8 h sliver of camp as its own day).
     */
    private fun splitAtNightsOrMidnight(fixes: List<Fix>, config: Config): List<List<Fix>> {
        val cuts = nightStopMiddles(fixes, config)
        // Midnight only where no night was found. A day cut at a night
        // carries half of that night's camp, and measuring that as "too long"
        // cut it again at UTC midnight -- 18:00 on the trail (CDT 2025-07-18).
        if (cuts.isEmpty()) return splitAtMidnight(fixes, config)
        return (listOf(0) + cuts + fixes.size).zipWithNext { a, b -> fixes.subList(a, b) }
            .filter { it.isNotEmpty() }
    }

    /** Indices of the first fix past the middle of each night-length stop. */
    private fun nightStopMiddles(fixes: List<Fix>, config: Config): List<Int> {
        val cuts = mutableListOf<Int>()
        var i = 0
        while (i < fixes.size) {
            var j = i + 1
            while (j < fixes.size && metres(fixes[i], fixes[j]) <= config.stopRadiusMetres) j++
            val from = fixes[i].epochSeconds
            val to = fixes[j - 1].epochSeconds
            val between = from - fixes.first().epochSeconds >= config.minActiveEitherSideSeconds &&
                fixes.last().epochSeconds - to >= config.minActiveEitherSideSeconds
            if (to - from >= config.minNightStopSeconds && between) {
                val middle = (from + to) / 2
                val cut = (i..j - 1).first { fixes[it].epochSeconds > middle }
                if (cut > 0) cuts += cut
            }
            i = j
        }
        return cuts
    }

    /** The last resort, for a stretch too long to be one day with no night in it. */
    private fun splitAtMidnight(fixes: List<Fix>, config: Config): List<List<Fix>> {
        if (fixes.last().epochSeconds - fixes.first().epochSeconds <= config.maxDaySeconds) {
            return listOf(fixes)
        }
        val firstDate = solarDate(fixes.first())
        val cut = fixes.indexOfFirst { solarDate(it) != firstDate }
        if (cut <= 0) return listOf(fixes)
        return listOf(fixes.subList(0, cut)) + splitAtMidnight(fixes.subList(cut, fixes.size), config)
    }

    /**
     * The date at the fix's own longitude: UTC plus four minutes per degree
     * east. UTC midnight is 18:00 in Colorado, mid-evening on the trail; solar
     * midnight is within about an hour of the clock anywhere in the lower 48,
     * and needs no zone table for a trail that crosses zones.
     */
    private fun solarDate(fix: Fix): LocalDate =
        Instant.ofEpochSecond(fix.epochSeconds + (fix.longitude!! * 240).roundToLong())
            .atOffset(ZoneOffset.UTC).toLocalDate()

    private fun measure(fixes: List<Fix>, config: Config, mode: Mode = Mode.FOOT): Stats {
        var raw = 0.0
        var cleaned = 0.0
        var moving = 0L
        var anchor = fixes.first()
        for ((a, b) in fixes.zipWithNext()) {
            raw += metres(a, b)

            val step = metres(anchor, b)
            val dt = b.epochSeconds - anchor.epochSeconds
            // A ride is measured as driven; only a walk has a speed limit.
            val tooFast = mode == Mode.FOOT && metres(a, b) / (b.epochSeconds - a.epochSeconds).coerceAtLeast(1) >
                config.maxGroundSpeedMps
            when {
                // Not counted, and the anchor follows: a spike's return leg is
                // just as fast and is excluded too, and a ride is excluded end
                // to end, resuming from wherever it set the walker down.
                tooFast -> anchor = b
                step >= config.jitterMetres -> {
                    cleaned += step
                    moving += if (dt <= config.movingWindowSeconds) dt else b.epochSeconds - a.epochSeconds
                    anchor = b
                }
            }
        }

        val heights = fixes.mapNotNull { it.height?.toDouble() }
        var rawGain = 0.0
        var rawLoss = 0.0
        for ((a, b) in heights.zipWithNext()) {
            if (b > a) rawGain += b - a else rawLoss += a - b
        }
        var cleanGain = 0.0
        var cleanLoss = 0.0
        heights.firstOrNull()?.let { first ->
            var ref = first
            for (h in heights) {
                if (h - ref >= config.elevationHysteresisMetres) {
                    cleanGain += h - ref; ref = h
                } else if (ref - h >= config.elevationHysteresisMetres) {
                    cleanLoss += ref - h; ref = h
                }
            }
        }
        return Stats(raw, cleaned, moving, rawGain, rawLoss, cleanGain, cleanLoss)
    }

    private inline fun splitAt(
        fixes: List<Fix>,
        breaks: (Fix, Fix) -> Boolean,
    ): List<List<Fix>> {
        if (fixes.isEmpty()) return emptyList()
        val out = mutableListOf<List<Fix>>()
        var from = 0
        for (i in 1 until fixes.size) {
            if (breaks(fixes[i - 1], fixes[i])) {
                out += fixes.subList(from, i)
                from = i
            }
        }
        out += fixes.subList(from, fixes.size)
        return out
    }

    /** Great-circle distance; positions are guaranteed by [Quality.filter]. */
    internal fun metres(a: Fix, b: Fix): Double {
        val lat1 = Math.toRadians(a.latitude!!)
        val lat2 = Math.toRadians(b.latitude!!)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude!! - a.longitude!!)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(h))
    }

    /** The same mean radius as `mtkparse.py`, so distances agree to the metre. */
    private const val EARTH_RADIUS_METRES = 6_371_000.0
}
