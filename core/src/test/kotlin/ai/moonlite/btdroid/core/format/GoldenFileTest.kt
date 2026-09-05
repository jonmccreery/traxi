package ai.moonlite.btdroid.core.format

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The correctness oracle for the whole parser.
 *
 * `cdt_v2.bin` is 18 months of real flash: 5,376,000 bytes, 82 written sectors,
 * 126,613 fixes, zero checksum failures. Every expected value below was
 * measured from the reference `mtkparse.py`, and several of them are values a
 * plausible-but-wrong parser would miss - most notably the fix count, which a
 * parser that trusts the sector header record counts would under-report by
 * 1,461.
 *
 * If this passes, the parser is correct. That is a far stronger claim than any
 * hand-written fixture supports.
 */
class GoldenFileTest {

    companion object {
        private const val EXPECTED_FIXES = 126_613
        private const val EXPECTED_SECTORS = 82
        private const val EXPECTED_MARKERS = 580
        private const val EXPECTED_DECLARED_COUNT = 125_152

        private lateinit var data: ByteArray
        private lateinit var result: MtkLogParser.Result

        /** Parse once; every test asserts against the same result. */
        @JvmStatic
        @BeforeClass
        fun parseOnce() {
            val file = goldenFile()
            assumeTrue(
                "golden dump not found at ${file.absolutePath}; set -Dbtdroid.dataDir",
                file.isFile,
            )
            data = file.readBytes()
            result = MtkLogParser.parse(data, GpsRollover.AXN_130B)
        }

        private fun goldenFile(): File {
            val dir = System.getProperty("btdroid.dataDir") ?: "../data"
            return File(dir, "cdt_v2.bin")
        }
    }

    @Test
    fun `dump is the expected size`() {
        assertEquals(5_376_000, data.size)
    }

    @Test
    fun `reproduces the golden fix count`() {
        assertEquals(EXPECTED_FIXES, result.fixes.size)
    }

    @Test
    fun `flash is entirely healthy`() {
        // Zero failures across 5.4 MB. Any failure here is a parser bug, not
        // bad flash - which is precisely why this assertion is exact.
        assertEquals(0, result.stats.checksumFailures)
        assertEquals(EXPECTED_FIXES, result.stats.records)
    }

    @Test
    fun `sector inventory matches`() {
        assertEquals(EXPECTED_SECTORS, result.stats.sectorsWithData)
        assertEquals(listOf(82), result.stats.unwrittenSectors)
        assertTrue(result.stats.badSectorHeaders.isEmpty(),
            "no sector header should fail to parse")
    }

    @Test
    fun `active sector count is unfinalized and must not be trusted`() {
        // Exactly one sector is mid-write and reads 0xFFFF.
        val unfinalized = result.stats.headers.count { !it.isCountFinalized }
        assertEquals(1, unfinalized)

        // The gap between declared and actual is the data a count-trusting
        // parser silently loses: the most recent 1,461 fixes.
        assertEquals(EXPECTED_DECLARED_COUNT, result.stats.declaredRecordCount)
        assertEquals(1_461, EXPECTED_FIXES - EXPECTED_DECLARED_COUNT)
    }

    @Test
    fun `format register is constant across every sector`() {
        val formats = result.stats.headers.map { it.format.bits }.toSet()
        assertEquals(setOf(LogFormat.OBSERVED.bits), formats)
        // Zero mid-log format changes: the 79 segments are power cycles.
        assertTrue(result.stats.formatChanges.isEmpty(),
            "expected no format-change markers, got ${result.stats.formatChanges}")
    }

    @Test
    fun `logging criteria are constant`() {
        val criteria = result.stats.headers
            .map { Triple(it.timeIntervalTenths, it.distanceIntervalTenths, it.speedLimitTenths) }
            .toSet()
        assertEquals(setOf(Triple(200, 0, 0)), criteria)
        assertEquals(20.0, result.stats.headers.first().timeIntervalSeconds, 0.0)
    }

    @Test
    fun `marker inventory matches`() {
        assertEquals(EXPECTED_MARKERS, result.stats.markers)
    }

    @Test
    fun `record size is 42 bytes`() {
        assertEquals(40, LogFormat.OBSERVED.recordSize())
        assertEquals(42, LogFormat.OBSERVED.recordSizeWithChecksum())
    }

    @Test
    fun `first and last fix match the reference`() {
        val first = result.fixes.first()
        assertEquals(37.18834, first.latitude!!, 1e-5)
        assertEquals(-121.54420, first.longitude!!, 1e-5)
        assertEquals(Instant.parse("2025-03-16T07:01:52Z"), first.instant)

        val last = result.fixes.last()
        assertEquals(41.88340, last.latitude!!, 1e-5)
        assertEquals(-87.80356, last.longitude!!, 1e-5)
        assertEquals(Instant.parse("2026-09-04T08:23:30Z"), last.instant)
    }

    @Test
    fun `rollover correction lands in the right era`() {
        // The verification case from the reference implementation: without the
        // 1024-week correction this fix reads as 2005, with 2048 it reads 2044.
        val chiefMountain = result.fixes.filter {
            it.latitude != null &&
                kotlin.math.abs(it.latitude!! - 48.993) < 0.01 &&
                kotlin.math.abs(it.longitude!! - -113.658) < 0.01
        }
        assertTrue(chiefMountain.isNotEmpty(), "expected the Chief Mountain fixes")
        assertTrue(
            chiefMountain.any { it.instant.toString().startsWith("2025-06-16") },
            "Chief Mountain should fall on 2025-06-16",
        )
    }

    @Test
    fun `timestamps are monotonic`() {
        // True only because this flash has not wrapped. If it ever does, this
        // assertion is the thing that will catch it.
        val backsteps = result.fixes.zipWithNext().count { (a, b) ->
            b.epochSeconds < a.epochSeconds
        }
        assertEquals(0, backsteps)
    }

    @Test
    fun `sample interval is 20 seconds`() {
        val deltas = result.fixes.zipWithNext()
            .map { (a, b) -> b.epochSeconds - a.epochSeconds }
            .filter { it in 1..300 }
            .sorted()
        assertEquals(20L, deltas[deltas.size / 2])
        assertEquals(20L, deltas[(deltas.size * 95) / 100])
    }

    @Test
    fun `VALID distribution matches and is dominated by DGPS`() {
        val byQuality = result.fixes.groupingBy { it.valid }.eachCount()
        assertEquals(124_259, byQuality[FixQuality.DGPS.code])
        assertEquals(2_333, byQuality[FixQuality.SPS.code])
        assertEquals(13, byQuality[FixQuality.NO_FIX.code])
        assertEquals(8, byQuality[FixQuality.ESTIMATED.code])

        // 98% differentially corrected. This is what makes the vertical channel
        // better than GPS-only norms without a barometer being involved.
        val dgpsShare = byQuality[FixQuality.DGPS.code]!!.toDouble() / result.fixes.size
        assertTrue(dgpsShare > 0.98, "DGPS share was $dgpsShare")
    }

    @Test
    fun `RCR identifies button-pressed waypoints`() {
        val byReason = result.fixes.groupingBy { it.rcr }.eachCount()
        assertEquals(125_194, byReason[RecordReason.TIME])
        assertEquals(1_419, byReason[RecordReason.BUTTON])
        assertEquals(1_419, result.fixes.count { it.isWaypoint })
    }

    @Test
    fun `there is exactly one sentinel record and it carries all three markers`() {
        val sentinels = result.fixes.filter { it.isSentinel }
        assertEquals(1, sentinels.size)

        val s = sentinels.single()
        // Latitude is 89.9999999999996, not 90.0 - an exact-equality mask on
        // latitude would silently match nothing. Longitude and height, by
        // contrast, are exact.
        assertEquals(89.9999999999996, s.latitude!!, 1e-12)
        assertTrue(s.latitude != 90.0, "sentinel latitude is not exactly 90.0")
        assertEquals(0.0, s.longitude!!, 0.0)
        assertEquals(150.0f, s.height!!, 0.0f)
        assertEquals(FixQuality.NO_FIX, s.quality)
        assertEquals(Instant.parse("2026-08-21T22:52:54Z"), s.instant)
    }

    @Test
    fun `height equals 150 exactly is not a usable sentinel test`() {
        // Only one record is genuinely 150.0, but 17 render as "150.0" at one
        // decimal place. Masking on formatted elevation would discard 16 good
        // fixes - the mistake this assertion exists to prevent.
        assertEquals(1, result.fixes.count { it.height == 150.0f })
        assertEquals(17, result.fixes.count { it.height != null && it.height!! >= 149.95f && it.height!! < 150.05f })
    }

    @Test
    fun `segments at a 300 second gap match the reference`() {
        assertEquals(79, Quality.segment(result.fixes, gapSeconds = 300).size)
    }

    @Test
    fun `speed is plausible but device distance is not`() {
        val speeds = result.fixes.mapNotNull { it.speed }
        assertEquals(result.fixes.size, speeds.size)
        assertTrue(speeds.max() in 120f..125f)

        // The odometer resets on power cycle and is not monotonic. This
        // assertion documents that it is broken so nobody wires it to a UI.
        val distances = result.fixes.mapNotNull { it.distance }
        val backsteps = distances.zipWithNext().count { (a, b) -> b < a }
        assertTrue(backsteps > 0,
            "device odometer should be non-monotonic, saw $backsteps resets")
    }

    @Test
    fun `quality filter removes the unusable fixes without touching the rest`() {
        val filtered = Quality.filter(result.fixes)
        // 13 NO_FIX (one of which is the sentinel) + 8 ESTIMATED.
        assertEquals(1, filtered.rejected[Quality.Rejection.SENTINEL])
        assertEquals(12, filtered.rejected[Quality.Rejection.NO_FIX])
        assertEquals(8, filtered.rejected[Quality.Rejection.ESTIMATED])
        assertTrue(filtered.kept.size > result.fixes.size - 100)
    }
}
