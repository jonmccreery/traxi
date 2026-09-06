package ai.moonlite.btdroid.core.protocol

import ai.moonlite.btdroid.core.export.GpxWriter
import ai.moonlite.btdroid.core.format.GpsRollover
import ai.moonlite.btdroid.core.format.MtkLogParser
import ai.moonlite.btdroid.core.format.Quality
import ai.moonlite.btdroid.core.transport.SimulatedLoggerTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: a simulated logger serves the real 5.4 MB flash image over PMTK,
 * the downloader pulls it back, and the parser reproduces the golden fix count.
 *
 * This exercises every layer the device path will use — framing, checksums, hex
 * transport, chunk reassembly, block retry, empirical end-of-flash detection —
 * without hardware. If this passes, the only thing left untested against the
 * real logger is the Bluetooth socket itself.
 */
class RoundTripTest {

    companion object {
        private const val EXPECTED_FIXES = 126_613
        private const val FLASH_BYTES = 5_376_000

        private lateinit var flash: ByteArray

        @JvmStatic
        @BeforeClass
        fun load() {
            val dir = System.getProperty("btdroid.dataDir") ?: "../data"
            val file = File(dir, "cdt_v2.bin")
            assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
            flash = file.readBytes()
        }
    }

    private fun client(transcript: PmtkTranscript = PmtkTranscript.NONE): PmtkClient {
        val transport = SimulatedLoggerTransport(flash, chunkSize = 0x1000)
        runBlocking { transport.open() }
        return PmtkClient(transport, transcript, defaultTimeoutMillis = 5_000)
    }

    @Test
    fun `full download reproduces the flash and the golden fix count`() = runBlocking {
        val transcript = RingTranscript()
        val downloader = FlashDownloader(client(transcript))

        var lastProgress = 0
        val result = downloader.download(onProgress = { lastProgress = it.bytesDownloaded })

        // Sized empirically -- nothing asked the device how big its flash is,
        // because this device answers that question wrongly or not at all.
        assertTrue(result.stoppedOnUnwritten, "should stop on unwritten sectors")
        assertTrue(
            result.image.size >= FLASH_BYTES,
            "downloaded ${result.image.size}, expected at least $FLASH_BYTES",
        )
        assertEquals(result.image.size, lastProgress)

        // Every byte of real flash came back intact.
        for (i in 0 until FLASH_BYTES) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("byte mismatch at 0x%08X".format(i))
            }
        }

        // And it parses to the same answer as reading the file directly.
        val parsed = MtkLogParser.parse(result.image, GpsRollover.AXN_130B)
        assertEquals(EXPECTED_FIXES, parsed.fixes.size)
        assertEquals(0, parsed.stats.checksumFailures)
    }

    @Test
    fun `resume from a partial download yields an identical image`() = runBlocking {
        val blockSize = FlashDownloader.DEFAULT_BLOCK_SIZE

        // Interrupt after three blocks, as a phone going into a pocket would.
        var blocks = 0
        val partial = FlashDownloader(client()).download(
            shouldContinue = { blocks++ < 3 },
        )
        assertEquals(3 * blockSize, partial.image.size)

        // Resume: hand back the bytes already held, and continue from there.
        val resumed = FlashDownloader(client()).download(
            existing = partial.image,
            resumeFrom = partial.image.size,
        )

        val full = FlashDownloader(client()).download()
        assertEquals(full.image.size, resumed.image.size)
        assertTrue(full.image.contentEquals(resumed.image), "resumed image differs")

        val parsed = MtkLogParser.parse(resumed.image, GpsRollover.AXN_130B)
        assertEquals(EXPECTED_FIXES, parsed.fixes.size)
    }

    @Test
    fun `download is correct when many small chunks arrive per read`() = runBlocking {
        // A 256-byte chunk puts ~15 sentences in every 8 KB transport read,
        // which is what the real device does and what the 4 KB chunks used
        // elsewhere in this class do not exercise.
        val transport = SimulatedLoggerTransport(flash, chunkSize = 0x100)
        transport.open()
        val downloader = FlashDownloader(PmtkClient(transport, defaultTimeoutMillis = 5_000))

        var blocks = 0
        val result = downloader.download(shouldContinue = { blocks++ < 2 })

        assertEquals(2 * FlashDownloader.DEFAULT_BLOCK_SIZE, result.image.size)
        assertEquals(0, result.retries, "small chunks should not require retries")
        for (i in result.image.indices) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("byte mismatch at 0x%08X".format(i))
            }
        }
    }

    /**
     * Delivers at most [bytesPerRead] bytes per read, with a stall every
     * [stallEvery] reads.
     *
     * Models the property that broke the first real Bluetooth download and that
     * USB hid completely: a 64 KB block is sub-second over CDC-ACM and tens of
     * seconds over RFCOMM, so any fixed per-block timeout that passes on one
     * transport fails on the other.
     */
    private class ThrottledTransport(
        private val inner: SimulatedLoggerTransport,
        private val bytesPerRead: Int = 512,
        private val stallEvery: Int = 12,
    ) : ai.moonlite.btdroid.core.transport.Transport by inner {
        private var reads = 0
        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            reads++
            if (reads % stallEvery == 0) return 0        // a beat of silence
            val window = ByteArray(minOf(bytesPerRead, dest.size))
            val n = inner.read(window, timeoutMillis)
            if (n > 0) window.copyInto(dest, 0, 0, n)
            return n
        }
    }

    /** A clock that advances only when the transport is read. */
    private class TickingClock(private val msPerTick: Long = 400) {
        var now = 0L
            private set
        fun tick() { now += msPerTick }
        fun read(): Long = now
    }

    @Test
    fun `a slow link completes because the timeout is idle-based, not total`() = runBlocking {
        // The real failure: a 64 KB block is sub-second over USB and tens of
        // seconds over RFCOMM, so a fixed per-block budget that passes on one
        // transport fails on the other.
        //
        // The clock advances 400 ms per transport read, so a block needing ~130
        // reads takes ~52 s of simulated time -- far beyond the old fixed
        // 10 s budget. It must still succeed, because every read makes
        // progress and progress is what resets the idle timer.
        val clock = TickingClock(msPerTick = 400)
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x200)
        val transport = object : ai.moonlite.btdroid.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                clock.tick()
                val window = ByteArray(minOf(512, dest.size))
                val n = inner.read(window, timeoutMillis)
                if (n > 0) window.copyInto(dest, 0, 0, n)
                return n
            }
        }
        transport.open()
        val client = PmtkClient(transport, clock = clock::read)

        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val block = client.readLogBlock(size, size, idleTimeoutMillis = 10_000)

        assertTrue(
            clock.now > 30_000,
            "test did not actually simulate a slow link (only ${clock.now} ms elapsed)",
        )
        assertTrue(block.isComplete, "got ${block.filled}/$size over a slow link")
        for (i in 0 until size) {
            if (flash[size + i] != block.bytes[i]) {
                throw AssertionError("byte mismatch at 0x%08X".format(size + i))
            }
        }
    }

    @Test
    fun `a block abandoned mid-transfer does not contaminate the next one`() = runBlocking {
        // Exactly the observed failure. Block 0 is cut short while the device
        // is still sending; its late chunks are then sitting in the transport
        // when block 1 is requested. Without resetStream they are parsed as
        // block 1's data, land outside its window, and block 1 returns zero
        // bytes -- which the downloader used to report as end of flash.
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x200)
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE

        // Count reads that actually carried data, so the stall lands inside the
        // transfer rather than in the pre-request drain, and jump the clock far
        // enough to trip the idle timeout while the device is still sending.
        var dataReads = 0
        var now = 0L
        val transport = object : ai.moonlite.btdroid.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                val window = ByteArray(minOf(512, dest.size))
                val n = inner.read(window, timeoutMillis)
                if (n > 0) {
                    window.copyInto(dest, 0, 0, n)
                    dataReads++
                    if (dataReads == 40) now += 60_000
                }
                return n
            }
        }
        transport.open()
        val client = PmtkClient(transport, clock = { now })

        val first = client.readLogBlock(0, size, idleTimeoutMillis = 10_000)
        assertTrue(!first.isComplete, "block 0 should have been cut short by the stall")
        assertTrue(first.filled > 0, "block 0 should have partial data")

        // Now the next block, with the device's leftovers still queued.
        val second = client.readLogBlock(size, size, idleTimeoutMillis = 10_000)
        assertTrue(
            second.filled > 0,
            "block 1 came back empty -- stale chunks from block 0 were absorbed",
        )
        assertEquals(flash[size], second.bytes[0], "block 1 starts at the wrong data")
    }

    @Test
    fun `an unwrapped log is extended, not re-read`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        // Stand in for a dump taken before the last few days of tracking.
        val previous = flash.copyOf(40 * size)

        val downloader = FlashDownloader(client())
        val plan = downloader.planIncremental(previous)

        assertTrue(plan is FlashDownloader.Plan.Extend, "expected an extend plan, got $plan")
        // Re-reads only the final block, whose sector was mid-write last time.
        assertEquals(39 * size, (plan as FlashDownloader.Plan.Extend).fromOffset)
    }

    @Test
    fun `extending produces the same image as a full download`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val previous = flash.copyOf(40 * size)

        val extended = FlashDownloader(client()).downloadIncremental(previous)
        val full = FlashDownloader(client()).download()

        assertEquals(full.image.size, extended.image.size)
        assertTrue(full.image.contentEquals(extended.image), "extended image differs")
        assertEquals(EXPECTED_FIXES, MtkLogParser.parse(extended.image, GpsRollover.AXN_130B).fixes.size)
    }

    @Test
    fun `a wrapped log forces a full re-read instead of a corrupt append`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        // A previous dump whose oldest records no longer match the chip: exactly
        // what a wrap looks like, since OVERLAP mode overwrites the start first.
        val stale = flash.copyOf(40 * size)
        for (i in 0 until 64) {
            stale[FlashDownloader.PROBE_OFFSET + i] = (stale[FlashDownloader.PROBE_OFFSET + i] + 1).toByte()
        }
        // Sanity: the corruption must sit inside the region the probe compares.
        assertTrue(FlashDownloader.PROBE_OFFSET < FlashDownloader.DEFAULT_BLOCK_SIZE)

        val downloader = FlashDownloader(client())
        val plan = downloader.planIncremental(stale)

        assertTrue(
            plan is FlashDownloader.Plan.FullRequired,
            "a wrapped log must not be appended to; got $plan",
        )

        // And the fallback yields a correct image rather than a spliced one.
        val result = FlashDownloader(client()).downloadIncremental(stale)
        for (i in 0 until FLASH_BYTES) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("fallback image is corrupt at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `a dump too small to extend falls back to a full read`() = runBlocking {
        assertTrue(
            FlashDownloader(client()).planIncremental(ByteArray(100))
                is FlashDownloader.Plan.FullRequired,
        )
    }

    @Test
    fun `a misaligned dump is trimmed to whole blocks, not rejected`() = runBlocking {
        // The reference mtkbabel image is exactly this shape: 82 blocks plus
        // 2 KB. Refusing it would cost a three-hour re-read to recover 2 KB.
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val misaligned = flash.copyOf(40 * size + 2048)

        val plan = FlashDownloader(client()).planIncremental(misaligned)
        assertTrue(plan is FlashDownloader.Plan.Extend, "got $plan")
        assertEquals(39 * size, (plan as FlashDownloader.Plan.Extend).fromOffset)

        // And the result is still byte-correct, with the ragged tail re-read.
        val result = FlashDownloader(client()).downloadIncremental(misaligned)
        for (i in 0 until FLASH_BYTES) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("misaligned extend corrupt at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `a transport failure keeps the bytes already read`() = runBlocking {
        // The regression that cost a real transfer: an exception mid-download
        // propagated out, and the bytes already in hand went with it. On a link
        // that manages 493 B/s, discarding a mostly-finished dump is the most
        // expensive failure available.
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x800)
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        var blocksServed = 0

        val transport = object : ai.moonlite.btdroid.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                val n = inner.read(dest, timeoutMillis)
                // Die abruptly once two blocks are through.
                if (n > 0 && ++blocksServed > 140) throw java.io.IOException("link dropped")
                return n
            }
        }
        transport.open()
        val downloader = FlashDownloader(PmtkClient(transport, defaultTimeoutMillis = 2_000))

        val result = downloader.download()

        assertTrue(result.failure != null, "failure should be reported, not swallowed")
        assertTrue(!result.isComplete, "an interrupted download is not complete")
        assertTrue(
            result.image.isNotEmpty(),
            "bytes read before the failure must survive it",
        )
        // And what survived is real data, resumable from a block boundary.
        assertEquals(0, result.image.size % size)
        for (i in result.image.indices) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("kept bytes are corrupt at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `a block with a lost chunk is reported damaged, not silently accepted`() = runBlocking {
        // The failure found on real hardware: chunks go missing, readLogBlock
        // leaves those bytes as 0xFF, and 0xFF is exactly what erased flash
        // looks like. The block then passes as "valid but short" and the
        // corruption is invisible -- an image with holes still parses cleanly.
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x800)
        var dropped = 0
        val lossy = object : ai.moonlite.btdroid.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                val n = inner.read(dest, timeoutMillis)
                // Swallow a couple of responses outright, as a flaky link does.
                if (n > 0 && dropped < 2 && kotlin.random.Random(dropped).nextBoolean()) {
                    dropped++
                    return 0
                }
                return n
            }
        }
        lossy.open()
        val client = PmtkClient(lossy, defaultTimeoutMillis = 1_000)
        val block = client.readLogBlock(0, FlashDownloader.DEFAULT_BLOCK_SIZE,
            idleTimeoutMillis = 1_000)

        // Whatever the outcome, coverage must be self-describing: bytes that
        // did not arrive are enumerated rather than left to look like flash.
        val missing = block.gaps.sumOf { it.last - it.first + 1 }
        assertEquals(
            FlashDownloader.DEFAULT_BLOCK_SIZE - block.filled, missing,
            "every unfilled byte must appear in a reported gap",
        )
        if (!block.isComplete) {
            assertTrue(block.gaps.isNotEmpty(), "an incomplete block must report gaps")
        }
        assertTrue(block.elapsedMillis >= 0)
    }

    @Test
    fun `a complete block reports no gaps`() = runBlocking {
        val block = FlashDownloader(client()).let {
            PmtkClient(SimulatedLoggerTransport(flash, chunkSize = 0x1000)
                .also { t -> t.open() }, defaultTimeoutMillis = 5_000)
        }.readLogBlock(0, FlashDownloader.DEFAULT_BLOCK_SIZE)
        assertTrue(block.isComplete)
        assertTrue(block.gaps.isEmpty(), "a full block must report no gaps")
        assertTrue(block.chunks > 0, "chunk count should be instrumented")
    }

    @Test
    fun `resume offset must land on a block boundary`() {
        val downloader = FlashDownloader(client())
        try {
            runBlocking { downloader.download(ByteArray(100), resumeFrom = 100) }
            throw AssertionError("expected rejection of a misaligned resume offset")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("block size"))
        }
    }

    @Test
    fun `read-only session never sends a write command`() = runBlocking {
        // The safety rail from prep doc section 8, enforced by test: connecting
        // and interrogating the device must not mutate it.
        val transport = SimulatedLoggerTransport(flash, chunkSize = 0x1000)
        transport.open()
        val transcript = RingTranscript()
        val client = PmtkClient(transport, transcript)

        client.queryFirmware()
        client.queryLogFormat()
        client.queryTimeIntervalSeconds()
        client.queryLogStatus()
        client.readLogBlock(0, 0x200)

        val sent = transcript.snapshot().filter { it.startsWith(">>") }
        assertTrue(sent.isNotEmpty())
        for (line in sent) {
            assertTrue(
                !line.contains("PMTK182,1,") &&   // config write
                    !line.contains("PMTK182,4") && // enable logging
                    !line.contains("PMTK182,5") && // disable logging
                    !line.contains("PMTK182,6"),   // erase
                "read-only session sent a mutating command: $line",
            )
        }
    }

    @Test
    fun `erase is not reachable from the command surface`() {
        // Erase is out of scope entirely. Assert the payload is absent from the
        // Pmtk object rather than merely unused, so adding it back is a
        // deliberate act that breaks this test.
        val payloads = Pmtk::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(Pmtk) as? String }
        assertTrue(
            payloads.none { it.startsWith("PMTK182,6") },
            "an erase payload has appeared in Pmtk: $payloads",
        )
    }
}

class GpxWriterTest {

    companion object {
        private lateinit var flash: ByteArray

        @JvmStatic
        @BeforeClass
        fun load() {
            val dir = System.getProperty("btdroid.dataDir") ?: "../data"
            val file = File(dir, "cdt_v2.bin")
            assumeTrue("golden dump not found", file.isFile)
            flash = file.readBytes()
        }
    }

    @Test
    fun `waypoints are separated from the track and elevations keep full precision`() {
        val fixes = MtkLogParser.parse(flash, GpsRollover.AXN_130B).fixes
        val kept = Quality.filter(fixes).kept

        val out = StringWriter()
        GpxWriter().write(kept.take(5000), out)
        val gpx = out.toString()

        assertTrue(gpx.contains("<wpt "), "button presses should emit waypoints")
        assertTrue(gpx.contains("<trkpt "), "should emit track points")
        assertTrue(gpx.contains("<fix>dgps</fix>"), "fix quality should survive export")

        // The defect this guards against: the previous exporter rounded
        // elevations to one decimal, manufacturing 16 phantom '150.0' values
        // that were later mistaken for device sentinels.
        val rounded = Regex("<ele>-?\\d+\\.\\d</ele>").findAll(gpx).count()
        val total = Regex("<ele>").findAll(gpx).count()
        assertTrue(total > 1000, "expected elevations in the output")
        assertTrue(
            rounded < total / 2,
            "elevations look rounded to one decimal: $rounded of $total",
        )
    }

    @Test
    fun `filtered export excludes the sentinel record`() {
        val fixes = MtkLogParser.parse(flash, GpsRollover.AXN_130B).fixes
        val kept = Quality.filter(fixes).kept
        assertTrue(kept.none { it.isSentinel })
        assertEquals(1, fixes.count { it.isSentinel })
    }
}
