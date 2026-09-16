package thru.taxi.traxi.core.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.MtkLogParser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much did a fetch-new actually gain? Measured against the run that proved
 * the old answer wrong.
 *
 * On 2026-09-15 a fetch-new brought back a 30.5-minute ride — 161 fixes,
 * 18:03–18:33Z — and the app reported **"Already up to date — nothing new on
 * the logger."** The two dumps are the evidence, pulled from the phone:
 *
 * ```
 * files/dumps/flash-20260908-231255.bin  ->  data/bt_fetch_base_2026-09-08.bin
 * files/dumps/flash-20260915-133103.bin  ->  data/bt_fetch_new_2026-09-15.bin
 * ```
 *
 * Both are exactly 327,680 bytes. That is the whole bug: `gained` was
 * `result.image.size - previous.size`, the logger writes new records at the
 * frontier — which sat mid-sector, at `0x00026090`, with 39 KB of room left in
 * sector 2 — so the file gained 8,432 bytes of riding without gaining a single
 * byte of length. A size delta of 0 sent the message straight to the "nothing
 * new" branch.
 *
 * This is the §12 failure shape rather than a cosmetic one: a confident
 * negative claim about capture that is false, and one that could talk the user
 * out of a download they need.
 */
class IncrementalGainTest {

    private fun dump(name: String): File =
        File(System.getProperty("traxi.dataDir") ?: "../data", name)

    private fun pair(): Pair<ByteArray, ByteArray>? {
        val base = dump("bt_fetch_base_2026-09-08.bin")
        val new = dump("bt_fetch_new_2026-09-15.bin")
        assumeTrue("fetch-new evidence not found at ${base.parentFile.absolutePath}",
            base.isFile && new.isFile)
        return base.readBytes() to new.readBytes()
    }

    @Test
    fun `the two real dumps are the same length, which is what defeated the old check`() {
        val (base, new) = pair() ?: return
        assertEquals(327_680, base.size)
        assertEquals(327_680, new.size)
        // The old computation, preserved so the regression cannot come back
        // quietly: it reports nothing gained.
        assertEquals(0, new.size - base.size)
    }

    @Test
    fun `the frontier moved, and that is where the ride is`() {
        val (base, new) = pair() ?: return
        assertEquals(0x00026090, FlashDownloader.frontierOffset(base))
        assertEquals(0x00028180, FlashDownloader.frontierOffset(new))
        assertEquals(8_432, FlashDownloader.newDataBytes(base, new))
        assertTrue(FlashDownloader.newDataBytes(base, new) > 0)
    }

    @Test
    fun `the gain is a clean append — everything the base held is untouched`() {
        val (base, new) = pair() ?: return
        // If this fails, the frontier delta is not new data and the whole
        // measure is wrong, wrap probe or no wrap probe.
        val frontier = FlashDownloader.frontierOffset(base)
        for (i in 0 until frontier) {
            if (base[i] != new[i]) {
                throw AssertionError("base data changed at 0x%08X; this was not an append".format(i))
            }
        }
    }

    @Test
    fun `those bytes really are the ride, not padding`() {
        val (base, new) = pair() ?: return
        val before = MtkLogParser.parse(base, GpsRollover.AXN_130B).fixes
        val after = MtkLogParser.parse(new, GpsRollover.AXN_130B).fixes
        assertEquals(3_141, before.size)
        assertEquals(3_311, after.size)
        // 170 fixes the user would have been told they did not have.
        assertEquals(170, after.size - before.size)
        assertTrue(after.last().epochSeconds > before.last().epochSeconds)
    }

    @Test
    fun `replaying the run reports the four sectors the transcript recorded`() = runBlocking {
        val (base, updated) = pair() ?: return@runBlocking
        // Serve the post-ride flash to a fetch-new that starts from the dump
        // taken before it: the 2026-09-15 transfer, replayed.
        val transport = SimulatedLoggerTransport(updated, chunkSize = 0x1000)
        transport.open()
        val client = PmtkClient(transport, PmtkTranscript.NONE, defaultTimeoutMillis = 5_000)

        val result = FlashDownloader(client).downloadIncremental(base)

        // The transcript logged exactly four block reads that day:
        //   0x00000000 (wrap probe), 0x00020000, 0x00030000, 0x00040000
        // 4 x 64 KB = 262,144 bytes on the wire, for 8,432 bytes of new riding.
        assertEquals(4, result.sectorsFetched)
        // While the image spans five sectors: two of them were never asked for.
        assertEquals(5, result.sectorSpan)
        assertTrue(result.image.contentEquals(updated), "the replay did not reproduce the dump")
        assertEquals(8_432, FlashDownloader.newDataBytes(base, result.image))
    }

    @Test
    fun `the resume point matches what the app logged on the day`() {
        val (base, _) = pair() ?: return
        // The transcript of the 2026-09-15 run reads:
        //   wrap probe matches; frontier at 0x00026090, extending from 0x00020000
        // so the shared helper the UI now seeds its readout from has to agree
        // with the downloader's own recorded decision.
        assertEquals(0x00020000, FlashDownloader.extendOffset(base))
    }

    @Test
    fun `a dump too small to extend has no resume point`() {
        assertEquals(null, FlashDownloader.extendOffset(ByteArray(1024) { 1 }))
        // Big enough, but holding nothing: still nothing to extend, and the
        // answer costs no wire time.
        assertEquals(null, FlashDownloader.extendOffset(ByteArray(4 * 0x10000) { 0xFF.toByte() }))
    }

    @Test
    fun `an unwritten image has no frontier and nothing is claimed for it`() {
        val empty = ByteArray(4096) { 0xFF.toByte() }
        assertEquals(0, FlashDownloader.frontierOffset(empty))
        assertEquals(0, FlashDownloader.newDataBytes(empty, empty))
    }

    @Test
    fun `the frontier ignores trailing unwritten flash but not interior bytes`() {
        val image = ByteArray(4096) { 0xFF.toByte() }
        image[100] = 0x41
        assertEquals(101, FlashDownloader.frontierOffset(image))
        // A limit shorter than the data bounds the answer, as the wrap probe needs.
        assertEquals(0, FlashDownloader.frontierOffset(image, limit = 100))
    }
}
