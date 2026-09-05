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
