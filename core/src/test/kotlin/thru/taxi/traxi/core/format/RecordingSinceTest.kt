package thru.taxi.traxi.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Two write-pointer readings, and what can honestly be said between them.
 *
 * The cases that matter here are the ones where the arithmetic is fine and the
 * conclusion would be wrong: a wrap looks exactly like a small advance, and an
 * erase looks exactly like a wrap. Both are [Verdict.Unusable] rather than a
 * confident number.
 */
class RecordingSinceTest {

    private val key = "BT-Q1000XT/AXN_1.30-B"
    // The format this logger is actually running -- PMTK182,3,2,000A1C3F in
    // every recent capture -- rather than the historical default.
    private val record = LogFormat.WITH_SATELLITE_QUALITY.recordSizeWithChecksum()

    private fun mark(pointer: Long, atMillis: Long, deviceKey: String = key) =
        RecordingSince.Mark(pointer, atMillis, deviceKey)

    private fun compare(
        previous: RecordingSince.Mark?,
        current: RecordingSince.Mark,
        intervalSeconds: Double = 10.0,
    ) = RecordingSince.compare(previous, current, record, intervalSeconds)

    @Test
    fun `a ride the logger recorded end to end`() {
        // 4h48m of wall clock. At 10 s and this record size that is 1,728
        // records; give it the bytes for a shade over that.
        val elapsed = 4 * 3600 + 48 * 60
        val fixes = 1_798L
        val start = 191_904L
        // Real flash interleaves a 512-byte header at each sector boundary, so
        // a run of 1,798 records moves the pointer further than 1,798 records'
        // worth. Two boundaries are crossed here. Writing the input the way the
        // device would write it is the whole point: an earlier version of this
        // test omitted the headers and "caught" the code correctly subtracting
        // them.
        val crossings = 2L
        val v = compare(
            mark(start, 0L),
            mark(start + fixes * record + crossings * SectorHeader.SIZE, elapsed * 1000L),
        )
        assertIs<RecordingSince.Verdict.Wrote>(v)
        assertEquals(fixes, v.fixes)
        assertEquals(elapsed.toLong(), v.elapsedSeconds)
        assertEquals(
            crossings,
            (start + fixes * record + crossings * SectorHeader.SIZE) /
                SectorHeader.SECTOR_SIZE - start / SectorHeader.SECTOR_SIZE,
            "the fixture must cross the boundaries it claims to",
        )
        // 1,798 fixes at 10 s is 17,980 s against 17,280 s elapsed.
        assertTrue(v.coverage > 1.0, "coverage was ${v.coverage}")
    }

    @Test
    fun `a pointer that never moved is the one unambiguous alarm`() {
        val v = compare(mark(191_904, 0L), mark(191_904, 3 * 3600 * 1000L))
        assertIs<RecordingSince.Verdict.WroteNothing>(v)
        assertEquals(3 * 3600L, v.elapsedSeconds)
    }

    @Test
    fun `a logger that stopped partway reads as partial coverage, not as healthy`() {
        // Five hours out; twenty minutes of fixes in the flash. The pointer
        // advanced, so every probe-based signal in the app would have said
        // RECORDING at some point and been right. Only the ratio shows it.
        val elapsed = 5 * 3600
        val fixes = 120L // 20 minutes at 10 s
        val v = compare(
            mark(1_000_000, 0L),
            mark(1_000_000 + fixes * record, elapsed * 1000L),
        )
        assertIs<RecordingSince.Verdict.Wrote>(v)
        assertEquals(1_200L, v.recordedSeconds)
        assertTrue(v.coverage < 0.1, "coverage was ${v.coverage}")
    }

    @Test
    fun `a wrap is never reported as nothing recorded`() {
        // Overlap mode: the pointer wrapped past the end of the chip and came
        // back low. The logger wrote *more* than the whole flash. Subtracting
        // would give a large negative; reporting WroteNothing would be the
        // worst false alarm the app could make.
        val v = compare(mark(5_300_000, 0L), mark(12_800, 6 * 3600 * 1000L))
        assertIs<RecordingSince.Verdict.Unusable>(v)
        assertTrue(v.reason.contains("wrapped"), v.reason)
    }

    @Test
    fun `an erase between readings is not a data-loss alarm either`() {
        // Indistinguishable from a wrap at this layer, and deliberately so:
        // both are "the pointer went backwards", neither is a fault, and
        // guessing which would mean inventing the distinction.
        val v = compare(mark(4_000_000, 0L), mark(0, 60_000L))
        assertIs<RecordingSince.Verdict.Unusable>(v)
    }

    @Test
    fun `a reading from another logger is not compared`() {
        val v = compare(
            mark(500_000, 0L, deviceKey = "some other unit"),
            mark(12_000, 60_000L),
        )
        assertIs<RecordingSince.Verdict.Unusable>(v)
        assertTrue(v.reason.contains("different logger"), v.reason)
    }

    @Test
    fun `the first connect of a device's life says nothing rather than guessing`() {
        assertIs<RecordingSince.Verdict.NothingToCompare>(compare(null, mark(1_000, 0L)))
    }

    @Test
    fun `a clock that moved backwards invalidates the durations`() {
        val v = compare(mark(1_000, 60_000L), mark(2_000, 0L))
        assertIs<RecordingSince.Verdict.Unusable>(v)
        assertTrue(v.reason.contains("clock"), v.reason)
    }

    @Test
    fun `sector headers are not counted as fixes`() {
        // Exactly one sector boundary is crossed, so 512 of these bytes are
        // header and cannot be records. Counting them would inflate every
        // long-gap estimate, always in the reassuring direction.
        val start = SectorHeader.SECTOR_SIZE - 1_000L
        val v = compare(mark(start, 0L), mark(start + 10_000, 3_600_000L))
        assertIs<RecordingSince.Verdict.Wrote>(v)
        assertEquals((10_000L - SectorHeader.SIZE) / record, v.fixes)
        // The byte count itself stays honest -- it is what the pointer did.
        assertEquals(10_000L, v.bytes)
    }

    // ---------------- what may be stored ----------------

    @Test
    fun `a pointer the device did not answer must not overwrite the baseline`() {
        // The destructive case. If this returns a mark, the caller stores it,
        // the open interval is closed without ever being measured, and the
        // post-ride connect has nothing to subtract from -- exactly at the
        // trailhead, which is where this link misbehaves most.
        assertEquals(null, RecordingSince.markFor(null, key, 1_000L))
    }

    @Test
    fun `a simulated session stores nothing`() {
        // Its pointer comes from a file on the phone. Subtracting the
        // hardware's from it would describe a ride that never happened.
        assertEquals(null, RecordingSince.markFor(12_345L, null, 1_000L))
        assertEquals(null, RecordingSince.markFor(12_345L, "", 1_000L))
    }

    @Test
    fun `a real reading is stored`() {
        assertEquals(
            RecordingSince.Mark(12_345L, 1_000L, key),
            RecordingSince.markFor(12_345L, key, 1_000L),
        )
    }

    @Test
    fun `coverage of a zero-length gap does not divide by zero`() {
        val v = compare(mark(1_000, 5_000L), mark(1_000 + 10L * record, 5_000L))
        assertIs<RecordingSince.Verdict.Wrote>(v)
        assertEquals(0L, v.elapsedSeconds)
        assertEquals(0.0, v.coverage)
    }
}
