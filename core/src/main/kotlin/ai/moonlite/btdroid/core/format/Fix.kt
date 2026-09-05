package ai.moonlite.btdroid.core.format

import java.time.Instant

/**
 * Fix quality, from the `VALID` field.
 *
 * On the reference dump 98.1% of fixes are [DGPS]. That single fact answers a
 * question the GPX export could not: the vertical channel is SBAS-corrected
 * GPS, not barometric, which is sufficient to explain a vertical/horizontal
 * noise ratio of 0.72 against the GPS-only norm of 1.5-2x.
 */
enum class FixQuality(val code: Int) {
    NO_FIX(0x0001),
    SPS(0x0002),
    DGPS(0x0004),
    PPS(0x0008),
    RTK(0x0010),
    FRTK(0x0020),
    ESTIMATED(0x0040),
    MANUAL(0x0080),
    SIMULATOR(0x0100);

    /** True for fixes that carry no usable position. */
    val isUnusable: Boolean
        get() = this == NO_FIX || this == ESTIMATED || this == SIMULATOR

    companion object {
        fun from(code: Int): FixQuality? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Why the device recorded this point, from the `RCR` field.
 *
 * [BUTTON] marks a user-pressed waypoint. The reference dump holds 1,419 of
 * them; they are the reason mtkbabel emits a separate `_wpt.gpx`. Preserve the
 * distinction rather than flattening it into the track.
 */
object RecordReason {
    const val TIME = 0x01
    const val SPEED = 0x02
    const val DISTANCE = 0x04
    const val BUTTON = 0x08

    fun describe(rcr: Int): String = buildList {
        if (rcr and TIME != 0) add("TIME")
        if (rcr and SPEED != 0) add("SPEED")
        if (rcr and DISTANCE != 0) add("DISTANCE")
        if (rcr and BUTTON != 0) add("BUTTON")
    }.joinToString("|").ifEmpty { "0x%02X".format(rcr) }
}

/**
 * One decoded log record.
 *
 * Every field beyond [epochSeconds] is nullable because presence is dictated by
 * the sector's format register, not by the schema.
 */
data class Fix(
    /** Raw on-flash seconds, before rollover correction. */
    val utcRaw: Int,
    /** Corrected UTC, after applying [GpsRollover]. */
    val epochSeconds: Long,
    val valid: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val height: Float? = null,
    val speed: Float? = null,
    val heading: Float? = null,
    val dsta: Int? = null,
    val dage: Int? = null,
    val pdop: Int? = null,
    val hdop: Int? = null,
    val vdop: Int? = null,
    val satellitesInView: Int? = null,
    val satellitesInUse: Int? = null,
    val rcr: Int? = null,
    val millisecond: Int? = null,
    /**
     * The device's own odometer. **Not usable as a trip total** - it is not
     * monotonic, resets on power cycle, and on the reference dump its positive
     * deltas sum to 19,082 mi against an actual 1,042 mi. Carried through for
     * fidelity; never surfaced as a distance.
     */
    val distance: Double? = null,
) {
    val instant: Instant get() = Instant.ofEpochSecond(epochSeconds)

    val quality: FixQuality? get() = valid?.let { FixQuality.from(it) }

    val isWaypoint: Boolean get() = (rcr ?: 0) and RecordReason.BUTTON != 0

    val hasPosition: Boolean get() = latitude != null && longitude != null

    /**
     * The device's no-fix sentinel: the north pole with zero longitude and a
     * height of exactly 150.0 m, all on a single record.
     *
     * **Neither magic number survives exact comparison**, which is why this is
     * a tolerance test:
     *  - latitude is `89.9999999999996` (bits `40567FFFFFFFFFE4`), *not* 90.0,
     *    so `lat == 90.0` matches nothing at all;
     *  - `height == 150.0` matches this record but the value is shared with
     *    16 genuine elevations once rounded to one decimal, so it over-matches
     *    on any formatted comparison.
     *
     * The reliable signal is [FixQuality.NO_FIX] from `VALID`; this test exists
     * to catch the same record on dumps where `VALID` is not logged.
     */
    val isSentinel: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return kotlin.math.abs(lat - 90.0) < 1e-6 && lon == 0.0
        }
}

/**
 * GPS week-rollover correction.
 *
 * Firmware `AXN_1.30-B` predates the April 2019 rollover and never received a
 * fix, so its epoch seconds land 1024 weeks (~19.6 years) in the past.
 *
 * This is verified rather than assumed: 1024 weeks reproduces a known Chief
 * Mountain fix at 48.993/-113.658 on 2025-06-16, while 2048 lands in 2044.
 *
 * It is deliberately a value rather than a constant. It is a per-firmware
 * property, it will be wrong for other devices, and it will need revisiting
 * after the next rollover epoch - so it must stay configurable and visible.
 */
@JvmInline
value class GpsRollover(val weeks: Int) {
    val seconds: Long get() = weeks.toLong() * SECONDS_PER_WEEK

    fun apply(rawUtc: Int): Long = java.lang.Integer.toUnsignedLong(rawUtc) + seconds

    companion object {
        const val SECONDS_PER_WEEK = 7L * 24 * 3600

        /** Correct for firmware `AXN_1.30-B_1.3_C01`. */
        val AXN_130B = GpsRollover(1024)

        val NONE = GpsRollover(0)
    }
}
