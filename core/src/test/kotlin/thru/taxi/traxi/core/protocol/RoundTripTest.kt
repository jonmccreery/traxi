package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.export.GpxWriter
import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.MtkLogParser
import thru.taxi.traxi.core.format.Quality
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
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
            val dir = System.getProperty("traxi.dataDir") ?: "../data"
            val file = File(dir, "cdt_v2.bin")
            assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
            flash = file.readBytes()
        }
    }

    private fun client(
        transcript: PmtkTranscript = PmtkTranscript.NONE,
        image: ByteArray = flash,
    ): PmtkClient {
        val transport = SimulatedLoggerTransport(image, chunkSize = 0x1000)
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
        // Count completed blocks by their boundary progress (blockBytes == 0),
        // not by shouldContinue calls: shouldContinue is now polled per chunk so
        // a cancel is honoured mid-block, which a call-counting limiter mistakes
        // for many blocks.
        var blocks = 0
        val partial = FlashDownloader(client()).download(
            onProgress = { if (it.blockBytes == 0) blocks++ },
            shouldContinue = { blocks < 3 },
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
        val result = downloader.download(
            onProgress = { if (it.blockBytes == 0) blocks++ },
            shouldContinue = { blocks < 2 },
        )

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
    ) : thru.taxi.traxi.core.transport.Transport by inner {
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
        val transport = object : thru.taxi.traxi.core.transport.Transport by inner {
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

    /** A clock the transport advances by hand: fast on data, slow on a stall. */
    private class SteppedClock {
        var now = 0L
            private set
        fun advance(ms: Long) { now += ms }
        fun read(): Long = now
    }

    /**
     * A link that delivers chunks with a real gap between them, the way RFCOMM
     * does and USB never does: every [stallEvery]-th read is a beat of silence
     * lasting [stallMillis] -- longer than a USB-sized idle timeout, shorter than
     * a Bluetooth-sized one. [blockReadIdleTimeoutMillis] is the single knob the
     * download path is supposed to take from the transport rather than hard-code.
     */
    private class GappyTransport(
        private val inner: SimulatedLoggerTransport,
        override val blockReadIdleTimeoutMillis: Long,
        private val clock: SteppedClock,
        private val stallEvery: Int = 5,
        private val stallMillis: Long = 4_000,
    ) : thru.taxi.traxi.core.transport.Transport by inner {
        private var reads = 0
        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            reads++
            if (reads % stallEvery == 0) { clock.advance(stallMillis); return 0 }
            clock.advance(50)
            return inner.read(dest, timeoutMillis)
        }
    }

    @Test
    fun `the block idle timeout comes from the transport, not a fixed constant`() = runBlocking {
        // The regression: FlashDownloader hard-coded a 2.5 s idle timeout that
        // was right for USB and cut every Bluetooth block short. A 4 s gap
        // between chunks -- unremarkable over RFCOMM -- must fail under a
        // USB-sized 2.5 s timeout and pass under a Bluetooth-sized 10 s one,
        // with nothing changing but the value the transport advertises.
        fun run(idleTimeout: Long): FlashDownloader.Result = runBlocking {
            val clock = SteppedClock()
            val inner = SimulatedLoggerTransport(flash, chunkSize = 0x1000)
            inner.open()
            val client = PmtkClient(
                GappyTransport(inner, idleTimeout, clock),
                defaultTimeoutMillis = 20_000,
                clock = clock::read,
            )
            var blocks = 0
            FlashDownloader(client).download(
                onProgress = { if (it.blockBytes == 0) blocks++ },
                shouldContinue = { blocks < 2 },
            )
        }

        val overBluetooth = run(10_000)
        assertTrue(overBluetooth.damagedRanges.isEmpty(), "a 4 s gap is normal over RFCOMM")
        assertEquals(null, overBluetooth.failure)
        assertEquals(2 * FlashDownloader.DEFAULT_BLOCK_SIZE, overBluetooth.image.size)
        for (i in overBluetooth.image.indices) {
            if (flash[i] != overBluetooth.image[i]) {
                throw AssertionError("byte mismatch at 0x%08X".format(i))
            }
        }

        val overUsbTimeout = run(2_500)
        assertTrue(
            overUsbTimeout.isDamaged,
            "a USB-sized 2.5 s idle timeout must choke on a 4 s gap, not pass silently",
        )
    }

    @Test
    fun `a cancel is honoured mid-block, not at the end of it`() = runBlocking {
        // Over Bluetooth a block is minutes long, so a cancel that waits for the
        // in-flight block to finish is a wait worth removing. Cancel three chunks
        // into the first block: the download must return promptly with only the
        // whole blocks it holds (here none), discarding the partial block.
        val transport = SimulatedLoggerTransport(flash, chunkSize = 0x1000)
        transport.open()
        val client = PmtkClient(transport, defaultTimeoutMillis = 5_000)

        var chunks = 0
        val result = FlashDownloader(client).download(
            onProgress = { if (it.blockBytes > 0) chunks++ },
            shouldContinue = { chunks < 3 },
        )

        // Block 0 is 32 chunks; cancelled at 3, so no whole block was written.
        assertEquals(0, result.image.size, "a mid-block cancel must not keep the partial block")
        assertTrue(!result.isComplete)
    }

    @Test
    fun `a cancel during an idle wait ends within a read slice, not the idle budget`() = runBlocking {
        // The Bluetooth case the display cleanup targets: no chunk is arriving,
        // the read is parked waiting for the next one. A cancel must end that
        // wait within a read slice, not at the end of the 10 s idle budget --
        // otherwise "stop" still hangs for seconds between chunks.
        val clock = SteppedClock()
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x1000)
        inner.open()
        var cancelled = false
        // A link that never yields the awaited chunk: every read costs time and
        // returns silence, exactly like an inter-chunk gap over RFCOMM.
        val silent = object : thru.taxi.traxi.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                clock.advance(400)
                if (clock.now >= 2_000) cancelled = true
                return 0
            }
        }
        val client = PmtkClient(silent, clock = clock::read)

        val block = client.readLogBlock(
            0, FlashDownloader.DEFAULT_BLOCK_SIZE,
            idleTimeoutMillis = 10_000,
            shouldContinue = { !cancelled },
        )

        assertEquals(0, block.filled, "no chunk ever arrived")
        assertTrue(
            clock.now < 5_000,
            "cancel should end the wait near 2 s, not at the 10 s idle budget; was ${clock.now}",
        )
    }

    @Test
    fun `a request abandoned mid-transfer does not contaminate the next one`() = runBlocking {
        // Exactly the observed failure. A read is cut short while the device is
        // still sending; its late chunks are then sitting in the transport when
        // the next read is issued. Without resetStream they are parsed as the
        // new read's data, land outside its window, and it returns nothing --
        // which the downloader used to report as end of flash.
        //
        // Deliberately small so the stall lands inside a single read.
        val inner = SimulatedLoggerTransport(flash, chunkSize = 0x200)
        val size = 8 * 1024

        // After a few chunks the link goes quiet and stays quiet, with time
        // passing. A single clock jump is not enough: progress immediately
        // after it resets the idle timer and absorbs the stall.
        var dataReads = 0
        var now = 0L
        var stalled = false
        val transport = object : thru.taxi.traxi.core.transport.Transport by inner {
            override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
                if (stalled) { now += 1_000; return 0 }
                val window = ByteArray(minOf(512, dest.size))
                val n = inner.read(window, timeoutMillis)
                if (n > 0) {
                    window.copyInto(dest, 0, 0, n)
                    if (++dataReads >= 3) stalled = true
                }
                return n
            }
        }
        transport.open()
        val client = PmtkClient(transport, clock = { now })

        val first = client.readLogBlock(0, size, idleTimeoutMillis = 10_000)
        assertTrue(!first.isComplete, "the read should have been cut short by the stall")
        assertTrue(first.filled > 0, "it should still have partial data")

        // The device recovers; the next read must not inherit the mess.
        stalled = false
        dataReads = -1_000_000
        val second = client.readLogBlock(size, size, idleTimeoutMillis = 10_000)
        assertTrue(
            second.filled > 0,
            "the next read came back empty -- stale chunks were absorbed",
        )
        assertEquals(flash[size], second.bytes[0], "the next read starts at the wrong data")
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
    fun `the wrap probe reports sub-sector progress, like any other block`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val previous = flash.copyOf(40 * size)

        // Over Bluetooth the probe is the first two minutes of fetch-new. It
        // must fill the per-sector bar as chunks arrive, not sit dead — that
        // was the regression: download() gained onChunk, the probe did not.
        val bars = mutableListOf<Int>()
        val plan = FlashDownloader(client()).planIncremental(previous, onProgress = {
            assertEquals(size, it.blockSizeBytes, "the probe bar needs its denominator")
            bars += it.blockBytes
        })

        assertTrue(plan is FlashDownloader.Plan.Extend, "got $plan")
        assertTrue(bars.count { it > 0 } >= 2, "the bar should move as chunks accumulate: $bars")
        assertTrue(
            bars.zipWithNext().all { (a, b) -> b >= a || b == 0 },
            "the bar must only grow within the block: $bars",
        )
        assertEquals(size, bars.max(), "the probe reads a whole block")
        assertEquals(0, bars.last(), "the bar resets at the block boundary")
    }

    @Test
    fun `a cancel during the wrap probe fetches nothing and touches nothing`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val previous = flash.copyOf(40 * size)

        // Cancel as soon as the probe shows life. Before the fix this waited
        // the whole block out and then fell into the full-download branch.
        var cancelled = false
        val result = FlashDownloader(client()).downloadIncremental(
            previous,
            onProgress = { cancelled = true },
            shouldContinue = { !cancelled },
        )

        assertTrue(cancelled, "the probe never reported progress to cancel from")
        assertTrue(result.image.isEmpty(), "a cancelled probe must not fetch anything")
        assertTrue(!result.stoppedOnUnwritten, "a cancel is not end-of-flash")
    }

    @Test
    fun `extending produces the same image as a full download`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val previous = flash.copyOf(40 * size)

        val extended = FlashDownloader(client()).downloadIncremental(previous)
        val full = FlashDownloader(client()).download()

        assertTrue(!extended.fullReread, "this extended; it must not claim a full re-read")
        assertTrue(FlashDownloader.newDataBytes(previous, extended.image) > 0)

        assertEquals(full.image.size, extended.image.size)
        assertTrue(full.image.contentEquals(extended.image), "extended image differs")
        assertEquals(EXPECTED_FIXES, MtkLogParser.parse(extended.image, GpsRollover.AXN_130B).fixes.size)
    }

    @Test
    fun `growth inside a young first block is extension, not a wrap`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        // A dump of a post-erase log minutes old: block 0 real up to 0x73C0,
        // unwritten from there, plus the two 0xFF blocks that ended the read.
        // The device has since kept recording INTO block 0 — the shape that was
        // misdiagnosed as "wrapped or erased" on hardware on 2026-09-06.
        val frontier = 0x73C0
        val young = ByteArray(3 * size) { 0xFF.toByte() }
        flash.copyInto(young, destinationOffset = 0, startIndex = 0, endIndex = frontier)

        val plan = FlashDownloader(client()).planIncremental(young)
        assertTrue(plan is FlashDownloader.Plan.Extend, "growth is not a wrap; got $plan")
        // The frontier sits inside block 0, so that is where the re-read starts:
        // re-reading only the final block would copy the stale block 0 forward
        // and silently drop every record written since the dump.
        assertEquals(0, (plan as FlashDownloader.Plan.Extend).fromOffset)

        val result = FlashDownloader(client()).downloadIncremental(young)
        assertTrue(result.isComplete)
        for (i in 0 until FLASH_BYTES) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("young-log extend lost data at 0x%08X".format(i))
            }
        }
        assertEquals(EXPECTED_FIXES, MtkLogParser.parse(result.image, GpsRollover.AXN_130B).fixes.size)
    }

    @Test
    fun `a dump whose data ends mid-file extends from its frontier`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        // Real data through block 34 and a bit of 35, unwritten to the end:
        // a complete dump of an unfull chip. New records land at the frontier,
        // not past the end of the file.
        val cut = 35 * size + 1234
        val previous = flash.copyOf(40 * size)
        for (i in cut until previous.size) previous[i] = 0xFF.toByte()

        val plan = FlashDownloader(client()).planIncremental(previous)
        assertTrue(plan is FlashDownloader.Plan.Extend, "got $plan")
        assertEquals(35 * size, (plan as FlashDownloader.Plan.Extend).fromOffset)

        val result = FlashDownloader(client()).downloadIncremental(previous)
        for (i in 0 until FLASH_BYTES) {
            if (flash[i] != result.image[i]) {
                throw AssertionError("frontier extend lost data at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `fetch-new progress never runs backwards`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        // A complete dump of an unfull chip: data through block 35, unwritten
        // to the end. This is the shape every real fetch-new starts from, and
        // the shape that made the readout jump.
        val cut = 35 * size + 1234
        val previous = flash.copyOf(40 * size)
        for (i in cut until previous.size) previous[i] = 0xFF.toByte()

        val bytes = mutableListOf<Int>()
        val sectors = mutableListOf<Int>()
        FlashDownloader(client()).downloadIncremental(previous, onProgress = {
            bytes += it.bytesDownloaded
            sectors += it.sectorPosition
        })

        assertTrue(bytes.isNotEmpty(), "the transfer reported no progress at all")
        // The probe reports from where the extend will resume, not from the
        // previous dump's full length. Before the fix this opened at 40 blocks
        // and then fell to 35 the moment the extend began.
        assertEquals(35 * size, bytes.first())
        assertEquals(35, sectors.first())

        for (i in 1 until bytes.size) {
            if (bytes[i] < bytes[i - 1]) {
                throw AssertionError(
                    "bytes went backwards at sample $i: ${bytes[i - 1]} -> ${bytes[i]}"
                )
            }
            if (sectors[i] < sectors[i - 1]) {
                throw AssertionError(
                    "sectors went backwards at sample $i: ${sectors[i - 1]} -> ${sectors[i]}"
                )
            }
        }
    }

    @Test
    fun `sectors fetched counts work done, not ground spanned`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val cut = 35 * size + 1234
        val previous = flash.copyOf(40 * size)
        for (i in cut until previous.size) previous[i] = 0xFF.toByte()

        val extend = FlashDownloader(client()).downloadIncremental(previous)
        // Spanned: the whole image, including 35 sectors never asked for.
        // Fetched: the wrap probe, plus every block from the frontier on.
        val blocksFromFrontier = extend.sectorSpan - 35
        assertEquals(blocksFromFrontier + 1, extend.sectorsFetched)
        assertTrue(
            extend.sectorsFetched < extend.sectorSpan,
            "a fetch-new must do less work than it spans",
        )

        // A full download asks for everything it spans, so the two agree.
        val full = FlashDownloader(client()).download()
        assertEquals(full.sectorSpan, full.sectorsFetched)
    }

    @Test
    fun `sectors fetched never decreases, and the probe is counted from the start`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val cut = 35 * size + 1234
        val previous = flash.copyOf(40 * size)
        for (i in cut until previous.size) previous[i] = 0xFF.toByte()

        val fetched = mutableListOf<Int>()
        val result = FlashDownloader(client()).downloadIncremental(previous, onProgress = {
            fetched += it.sectorsFetched
        })
        for (i in 1 until fetched.size) {
            if (fetched[i] < fetched[i - 1]) {
                throw AssertionError(
                    "sectors fetched went backwards at $i: ${fetched[i - 1]} -> ${fetched[i]}"
                )
            }
        }
        // The probe is already counted while the first extend block is in
        // flight, so the counter never sits at zero once work has been done.
        assertEquals(1, fetched.first { it > 0 })
        assertEquals(result.sectorsFetched, fetched.last())
    }

    @Test
    fun `a wrap fallback resets progress once, deliberately, and then climbs`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val stale = flash.copyOf(40 * size)
        for (i in 0 until 64) {
            stale[FlashDownloader.PROBE_OFFSET + i] = (stale[FlashDownloader.PROBE_OFFSET + i] + 1).toByte()
        }

        val bytes = mutableListOf<Int>()
        val result = FlashDownloader(client()).downloadIncremental(stale, onProgress = {
            bytes += it.bytesDownloaded
        })

        // A full re-read really does start from nothing, so one drop to zero is
        // correct -- but exactly one, and everything after it moves forward.
        val zero = bytes.indexOf(0)
        assertTrue(zero >= 0, "the fallback never announced its restart")
        for (i in zero + 1 until bytes.size) {
            if (bytes[i] < bytes[i - 1]) {
                throw AssertionError(
                    "bytes went backwards after the restart at $i: ${bytes[i - 1]} -> ${bytes[i]}"
                )
            }
        }
        // The read runs block-aligned past the end of the served image and
        // stops on two unwritten sectors, so the final figure is the image the
        // transfer actually produced -- not the source file's length.
        assertEquals(result.image.size, bytes.last())
    }

    @Test
    fun `an erased flash is detected by the probe, never appended to`() = runBlocking {
        val size = FlashDownloader.DEFAULT_BLOCK_SIZE
        val previous = flash.copyOf(40 * size)
        // The device after a real erase: nothing but unwritten flash.
        val erased = ByteArray(4 * size) { 0xFF.toByte() }

        val plan = FlashDownloader(client(image = erased)).planIncremental(previous)
        assertTrue(
            plan is FlashDownloader.Plan.FullRequired,
            "real bytes turned 0xFF are an erase, not growth; got $plan",
        )
        assertTrue(
            (plan as FlashDownloader.Plan.FullRequired).reason.contains("erased"),
            "the reason should name the erase: ${plan.reason}",
        )
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
        // The caller must be able to tell this from an extend. After a wrap the
        // previous dump's frontier is not a baseline, so no "added N" claim can
        // honestly be made from comparing the two.
        assertTrue(result.fullReread, "a wrap fallback must report itself as a full re-read")
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

        val transport = object : thru.taxi.traxi.core.transport.Transport by inner {
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
        val lossy = object : thru.taxi.traxi.core.transport.Transport by inner {
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

        val sent = transcript.snapshot().filter { it.contains(">>") }
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
    fun `erase is spelled exactly once and exactly correctly`() {
        // This test previously asserted that no erase payload existed at all.
        // Prep doc §0.2 reversed that: the device holds three weeks against a
        // four-month trip, so refusing to erase does not keep the data safe, it
        // makes the logger useless once full. The safety argument moved into
        // EraseGate, which is tested separately and far more heavily.
        //
        // What is still worth pinning here is the payload itself. `PMTK182,6`
        // takes a subcommand, this project only ever means `,1`, and a typo in
        // a constant nothing else validates would be discovered on hardware, in
        // the field, once.
        val payloads = Pmtk::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(Pmtk) as? String }
        val eraseLike = payloads.filter { it.startsWith("PMTK182,6") }

        assertEquals(
            listOf("PMTK182,6,1"),
            eraseLike,
            "expected exactly one erase payload, spelled PMTK182,6,1",
        )
        assertEquals("PMTK182,6,1", Pmtk.WRITE_ERASE_FLASH)
    }
}

class GpxWriterTest {

    companion object {
        private lateinit var flash: ByteArray

        @JvmStatic
        @BeforeClass
        fun load() {
            val dir = System.getProperty("traxi.dataDir") ?: "../data"
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
