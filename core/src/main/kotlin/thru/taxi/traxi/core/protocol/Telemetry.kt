package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.format.GpsRollover
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * One satellite as reported by `GSV`.
 *
 * [snrDb] is null when the field is empty, which the receiver uses to mean "in
 * view but not being tracked". That distinction is the whole point of showing
 * satellites at all: twelve in view with four tracked is a different situation
 * from four in view, and only the first one gets better by waiting.
 */
data class SatelliteView(
    val prn: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDb: Int?,
) {
    val tracked: Boolean get() = snrDb != null
}

/** `GSA` field 2. */
enum class FixType(val label: String) {
    NONE("no fix"),
    TWO_D("2D"),
    THREE_D("3D");

    companion object {
        fun of(code: String?): FixType? = when (code) {
            "1" -> NONE
            "2" -> TWO_D
            "3" -> THREE_D
            else -> null
        }
    }
}

/**
 * A live view of what the receiver is doing right now.
 *
 * Assembled from the navigation sentences the logger emits continuously
 * alongside its PMTK replies. Every field is nullable because it is built from
 * four independent sentence types that arrive at different rates -- a snapshot
 * taken between a `GGA` and its matching `GSA` is legitimately half-populated,
 * and pretending otherwise would mean inventing values.
 *
 * **This says nothing about whether the logger is recording.** Navigation
 * sentences flow whether logging is enabled or not, so a perfect 3D fix on
 * twelve satellites is entirely compatible with the silent capture failure in
 * the engineering record's section 12. Lock status and recording status are
 * different questions with different answers.
 */
data class Telemetry(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val geoidSeparationMeters: Double? = null,
    /** `GGA` field 6: 0 no fix, 1 GPS, 2 DGPS. */
    val fixQuality: Int? = null,
    val fixType: FixType? = null,
    /** `RMC` field 2: A valid, V warning. */
    val valid: Boolean? = null,
    val satellitesUsed: Int? = null,
    /** PRNs actually in the position solution, from `GSA`. */
    val usedPrns: Set<Int> = emptySet(),
    /** Everything in view, from `GSV`, tracked or not. */
    val satellites: List<SatelliteView> = emptyList(),
    val pdop: Double? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,
    val speedKnots: Double? = null,
    val courseDeg: Double? = null,
    /** Rollover-corrected UTC, or null before a dated sentence has arrived. */
    val utcMillis: Long? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** True once anything at all has been decoded. */
    val hasAnything: Boolean
        get() = hasPosition || fixType != null || satellites.isNotEmpty() || fixQuality != null

    val trackedCount: Int get() = satellites.count { it.tracked }

    val speedKmh: Double? get() = speedKnots?.let { it * 1.852 }
}

/**
 * Folds navigation sentences into a running [Telemetry].
 *
 * Deliberately tolerant. This is fed from the same byte stream that carries
 * bulk log chunks, so it must treat every field as optional and every sentence
 * as possibly the only one it will ever see. A field it cannot parse is left at
 * its previous value rather than cleared, because a single garbled `GGA` should
 * not blank a position that was correct a second ago.
 *
 * @param rollover applied to decoded UTC. The AXN_1.30-B firmware predates the
 *   2019 rollover, so its live sentences are dated 1024 weeks in the past
 *   exactly as its logged fixes are -- an `RMC` captured on 2026-09-06 reads
 *   `210107`. Same defect, same correction, verified against a real capture.
 */
class TelemetryAssembler(
    private val rollover: GpsRollover = GpsRollover.NONE,
) {
    var current: Telemetry = Telemetry()
        private set

    /** GSV arrives split across several sentences; collected until the last. */
    private val gsvPending = LinkedHashMap<Int, SatelliteView>()
    private var gsvExpected = 0

    /** Last date seen, so a `GGA` between `RMC`s still yields a full timestamp. */
    private var lastDate: LocalDate? = null

    /**
     * Fold one sentence in.
     *
     * @return true if this was a navigation sentence and [current] may have
     *   changed. PMTK replies return false and are left entirely alone.
     */
    fun accept(sentence: NmeaSentence): Boolean {
        // Match on the last three characters so any talker works: the logger
        // sends GP, but GN/GL/GA are the same sentences from a receiver that
        // also sees GLONASS or Galileo.
        val kind = sentence.type.takeLast(3)
        if (sentence.type.length < 5 || !sentence.type.startsWith("G")) return false

        return when (kind) {
            "GGA" -> { gga(sentence); true }
            "RMC" -> { rmc(sentence); true }
            "GSA" -> { gsa(sentence); true }
            "GSV" -> { gsv(sentence); true }
            else -> false
        }
    }

    fun reset() {
        current = Telemetry()
        gsvPending.clear()
        gsvExpected = 0
        lastDate = null
    }

    // $GPGGA,155939.000,4153.0220,N,08748.2074,W,1,7,1.16,234.8,M,-34.0,M,,*6B
    private fun gga(s: NmeaSentence) {
        val lat = coordinate(s[2], s[3])
        val lon = coordinate(s[4], s[5])
        current = current.copy(
            latitude = lat ?: current.latitude,
            longitude = lon ?: current.longitude,
            fixQuality = s[6]?.toIntOrNull() ?: current.fixQuality,
            satellitesUsed = s[7]?.toIntOrNull() ?: current.satellitesUsed,
            hdop = s[8]?.toDoubleOrNull() ?: current.hdop,
            altitudeMeters = s[9]?.toDoubleOrNull() ?: current.altitudeMeters,
            geoidSeparationMeters = s[11]?.toDoubleOrNull() ?: current.geoidSeparationMeters,
            utcMillis = timestamp(lastDate, s[1]) ?: current.utcMillis,
        )
    }

    // $GPRMC,155938.000,A,4153.0220,N,08748.2074,W,0.18,0.00,210107,,,A*72
    private fun rmc(s: NmeaSentence) {
        val date = s[9]?.takeIf { it.length == 6 }?.let {
            runCatching {
                LocalDate.of(2000 + it.substring(4, 6).toInt(), it.substring(2, 4).toInt(), it.substring(0, 2).toInt())
            }.getOrNull()
        }
        if (date != null) lastDate = date

        val lat = coordinate(s[3], s[4])
        val lon = coordinate(s[5], s[6])
        current = current.copy(
            valid = when (s[2]) { "A" -> true; "V" -> false; else -> current.valid },
            latitude = lat ?: current.latitude,
            longitude = lon ?: current.longitude,
            speedKnots = s[7]?.toDoubleOrNull() ?: current.speedKnots,
            courseDeg = s[8]?.toDoubleOrNull() ?: current.courseDeg,
            utcMillis = timestamp(lastDate, s[1]) ?: current.utcMillis,
        )
    }

    // $GPGSA,A,3,23,32,24,10,27,15,18,,,,,,1.44,1.16,0.86*04
    private fun gsa(s: NmeaSentence) {
        // Fields 3..14 are the twelve PRN slots, blank where unused.
        val prns = (3..14).mapNotNull { s[it]?.toIntOrNull() }.toSet()
        current = current.copy(
            fixType = FixType.of(s[2]) ?: current.fixType,
            usedPrns = if (prns.isNotEmpty()) prns else current.usedPrns,
            pdop = s[15]?.toDoubleOrNull() ?: current.pdop,
            hdop = s[16]?.toDoubleOrNull() ?: current.hdop,
            vdop = s[17]?.toDoubleOrNull() ?: current.vdop,
        )
    }

    // $GPGSV,3,1,12,23,71,053,35,10,60,320,42,24,44,068,32,32,40,246,27*76
    private fun gsv(s: NmeaSentence) {
        val total = s[1]?.toIntOrNull() ?: return
        val index = s[2]?.toIntOrNull() ?: return

        // A fresh sequence starts at message 1. Starting mid-sequence -- which
        // happens whenever a connection opens partway through -- is dropped
        // rather than published half-complete.
        if (index == 1) {
            gsvPending.clear()
            gsvExpected = total
        } else if (gsvExpected == 0) {
            return
        }

        // Satellites are four fields each from index 4; the last sentence in a
        // sequence is usually short, so this stops at whatever is present.
        var i = 4
        while (i + 3 < s.fields.size) {
            val prn = s[i]?.toIntOrNull()
            if (prn != null) {
                gsvPending[prn] = SatelliteView(
                    prn = prn,
                    elevationDeg = s[i + 1]?.toIntOrNull(),
                    azimuthDeg = s[i + 2]?.toIntOrNull(),
                    snrDb = s[i + 3]?.takeIf { it.isNotBlank() }?.toIntOrNull(),
                )
            }
            i += 4
        }

        if (index == total) {
            current = current.copy(satellites = gsvPending.values.sortedByDescending { it.snrDb ?: -1 })
            gsvExpected = 0
        }
    }

    /**
     * `ddmm.mmmm` plus a hemisphere into signed decimal degrees.
     *
     * The degree field is two digits for latitude and three for longitude, so
     * the split is located from the decimal point rather than assumed: minutes
     * are always the two digits immediately before it.
     */
    private fun coordinate(value: String?, hemisphere: String?): Double? {
        if (value.isNullOrBlank() || hemisphere.isNullOrBlank()) return null
        val dot = value.indexOf('.')
        val split = (if (dot >= 0) dot else value.length) - 2
        if (split <= 0) return null
        val degrees = value.substring(0, split).toDoubleOrNull() ?: return null
        val minutes = value.substring(split).toDoubleOrNull() ?: return null
        val magnitude = degrees + minutes / 60.0
        return when (hemisphere.uppercase()) {
            "N", "E" -> magnitude
            "S", "W" -> -magnitude
            else -> null
        }
    }

    /** `hhmmss.sss` on a known date, corrected for the firmware's rollover. */
    private fun timestamp(date: LocalDate?, time: String?): Long? {
        if (date == null || time.isNullOrBlank() || time.length < 6) return null
        val t = runCatching {
            LocalTime.of(
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                time.substring(4, 6).toInt(),
            )
        }.getOrNull() ?: return null
        val raw = LocalDateTime.of(date, t).toEpochSecond(ZoneOffset.UTC)
        return (raw + rollover.seconds) * 1000
    }
}
