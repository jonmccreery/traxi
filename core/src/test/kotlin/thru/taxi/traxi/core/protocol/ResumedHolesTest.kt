package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.MtkLogParser
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §16.1: a resume must not launder a holed dump into a verified one.
 *
 * `Result.damagedRanges` described only the blocks *that call* read. The resume
 * path hands the previous image in as `existing` and starts past it, so holes
 * already in that prefix were copied forward, counted by nobody, and the second
 * result reported `isComplete = true` with `damagedBytes = 0`.
 *
 * Everything downstream believed it: `DumpRepository.save(partial = !isComplete)`
 * deleted the `.partial` marker, `SessionController.pendingEvidence` recorded
 * `damagedBytes = 0`, and `EraseGate` opened -- against an image whose 0xFF
 * holes are, by the downloader's own documentation, "indistinguishable from
 * erased flash".
 *
 * Nothing else could have caught it. A hole is 0xFF, and inside a sector
 * `MtkLogParser` reads 0xFF as end-of-data fill and skips to the next sector:
 * the records after it vanish with no checksum failure and no entry in the
 * stats. That is asserted below, because it is the reason the reporting has to
 * be right -- there is no second line of defence.
 *
 * The fix is `priorDamage`: damage is stored with the dump, the damaged blocks
 * are re-read when the dump is resumed, and whatever is still missing stays on
 * the record.
 */
class ResumedHolesTest {

    companion object {
        private val BLOCK = FlashDownloader.DEFAULT_BLOCK_SIZE

        /**
         * The chunk the "link" loses, and keeps losing, in block 0.
         *
         * 0x4000 is not special to the downloader -- any chunk can be lost.
         * It is chosen because at this alignment the resulting hole is
         * *completely* silent to the parser: no checksum failure, no bad
         * header, nothing. Of the 31 chunk positions in this sector, two are
         * silent like this and the rest raise exactly one checksum failure,
         * which the erase gate happens to catch for the wrong reason.
         */
        private const val LOST_CHUNK_ADDRESS = 0x4000
        private const val LOST_CHUNK_BYTES = 0x800

        private lateinit var flash: ByteArray

        @JvmStatic
        @BeforeClass
        fun load() {
            // Two real sectors from the golden dump, so the parse below is a
            // parse of genuine records rather than of invented bytes.
            val dir = System.getProperty("traxi.dataDir") ?: "../data"
            val file = File(dir, "cdt_v2.bin")
            assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
            flash = file.readBytes().copyOf(2 * BLOCK)
        }
    }

    /**
     * A link that persistently loses one chunk, as a marginal RFCOMM link does.
     *
     * Drops whole `PMTK182,8` sentences at [LOST_CHUNK_ADDRESS] and passes
     * everything else through untouched, so the loss looks to the client
     * exactly like a chunk that never arrived.
     */
    private class LosesOneChunk(
        private val inner: Transport,
        private val lossy: () -> Boolean,
    ) : Transport {
        private val assembler = NmeaLineAssembler()
        private val scratch = ByteArray(16 * 1024)
        private var buffered = ByteArray(0)
        private var offset = 0

        override val description: String get() = inner.description
        override val blockReadIdleTimeoutMillis: Long get() = 50
        override val isOpen: Boolean get() = inner.isOpen
        override suspend fun open() = inner.open()
        override suspend fun write(bytes: ByteArray) = inner.write(bytes)
        override fun close() = inner.close()

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            if (offset >= buffered.size) {
                val n = inner.read(scratch, timeoutMillis)
                if (n <= 0) return n
                val out = ByteArrayOutputStream()
                for (line in assembler.feed(scratch, n)) {
                    val sentence = Nmea.parse(line) ?: continue
                    val address = sentence[2]?.toLongOrNull(16)?.toInt()
                    val isLostChunk = lossy() &&
                        sentence.matches("PMTK182", "8") &&
                        address == LOST_CHUNK_ADDRESS
                    if (isLostChunk) continue
                    out.write((line + Nmea.CRLF).toByteArray(Charsets.US_ASCII))
                }
                buffered = out.toByteArray()
                offset = 0
                if (buffered.isEmpty()) return 0
            }
            val n = minOf(dest.size, buffered.size - offset)
            buffered.copyInto(dest, 0, offset, offset + n)
            offset += n
            return n
        }
    }

    private fun downloader(transport: Transport): FlashDownloader {
        runBlocking { transport.open() }
        return FlashDownloader(PmtkClient(transport, RingTranscript()))
    }

    @Test
    fun `a resume repairs the holes it is told about`() = runBlocking {
        // ---- first transfer: one chunk of block 0 never arrives ----
        val damaged = downloader(
            LosesOneChunk(SimulatedLoggerTransport(flash, chunkSize = 0x800)) { true }
        ).download()

        assertFalse(damaged.isComplete, "a dump with holes is not complete")
        assertEquals(LOST_CHUNK_BYTES, damaged.damagedBytes, "the lost chunk must be reported")
        assertTrue(
            damaged.image.copyOfRange(
                LOST_CHUNK_ADDRESS, LOST_CHUNK_ADDRESS + LOST_CHUNK_BYTES
            ).all { it == PmtkClient.UNWRITTEN },
            "the hole reads as erased flash",
        )
        // This is what DumpRepository.save() would write to disk, and what
        // resumeOffset() would hand back: whole blocks, marked partial.
        assertEquals(0, damaged.image.size % BLOCK)

        // ---- the user taps Resume, on a link that is now healthy ----
        // DumpRepository persists these ranges beside the dump; this is what it
        // hands back.
        val resumed = downloader(SimulatedLoggerTransport(flash, chunkSize = 0x800)).download(
            existing = damaged.image,
            resumeFrom = damaged.image.size,
            priorDamage = damaged.damagedRanges,
        )

        // The hole was re-read and filled from the device.
        assertTrue(
            resumed.image.copyOfRange(
                LOST_CHUNK_ADDRESS, LOST_CHUNK_ADDRESS + LOST_CHUNK_BYTES
            ).contentEquals(
                flash.copyOfRange(LOST_CHUNK_ADDRESS, LOST_CHUNK_ADDRESS + LOST_CHUNK_BYTES)
            ),
            "the damaged block is re-read before the extend, so the bytes come back",
        )
        assertTrue(resumed.damagedRanges.isEmpty(), "nothing is missing any more")
        assertEquals(0, resumed.damagedBytes)
        assertTrue(resumed.isComplete, "and only now may it be called complete")

        // The whole image matches the device, not just the repaired window.
        for (i in 0 until minOf(flash.size, resumed.image.size)) {
            if (flash[i] != resumed.image[i]) {
                throw AssertionError("byte mismatch at 0x%08X".format(i))
            }
        }
    }

    @Test
    fun `a resume that cannot repair keeps the dump marked damaged`() = runBlocking {
        val damaged = downloader(
            LosesOneChunk(SimulatedLoggerTransport(flash, chunkSize = 0x800)) { true }
        ).download()

        // The link is still losing the same chunk when the user retries.
        val resumed = downloader(
            LosesOneChunk(SimulatedLoggerTransport(flash, chunkSize = 0x800)) { true }
        ).download(
            existing = damaged.image,
            resumeFrom = damaged.image.size,
            priorDamage = damaged.damagedRanges,
        )

        assertEquals(
            LOST_CHUNK_BYTES, resumed.damagedBytes,
            "a repair that did not work must not be reported as one that did",
        )
        assertEquals(
            listOf(LOST_CHUNK_ADDRESS..(LOST_CHUNK_ADDRESS + LOST_CHUNK_BYTES - 1)),
            resumed.damagedRanges,
            "and the range is carried forward exactly, so the next resume can retry it",
        )
        assertFalse(resumed.isComplete, "so DumpRepository keeps the .partial marker")
    }

    @Test
    fun `the parser cannot see the hole either, and silently loses the sector`() {
        val whole = MtkLogParser.parse(flash, GpsRollover.AXN_130B)

        val holed = flash.copyOf().also {
            java.util.Arrays.fill(
                it, LOST_CHUNK_ADDRESS, LOST_CHUNK_ADDRESS + LOST_CHUNK_BYTES,
                PmtkClient.UNWRITTEN,
            )
        }
        val parsed = MtkLogParser.parse(holed, GpsRollover.AXN_130B)

        // 0xFF inside a sector is read as fill, so the parser jumps to the next
        // sector: every record after the hole in sector 0 is gone.
        assertTrue(
            parsed.fixes.size < whole.fixes.size,
            "expected fixes to be lost; whole=${whole.fixes.size} holed=${parsed.fixes.size}",
        )
        // And nothing anywhere says so.
        assertEquals(
            0, parsed.stats.checksumFailures,
            "the loss raises no checksum failure -- it is completely silent",
        )
        assertTrue(parsed.stats.badSectorHeaders.isEmpty())
        assertTrue(parsed.fixes.isNotEmpty(), "still parses cleanly, which is the danger")

        println(
            "hole cost ${whole.fixes.size - parsed.fixes.size} fixes " +
                "(${whole.fixes.size} -> ${parsed.fixes.size}), 0 checksum failures"
        )
    }

    @Test
    fun `the erase gate refuses a dump that is still missing bytes`() = runBlocking {
        // Build the evidence exactly as SessionController does after a resume:
        // coveredBytes = image size, downloadComplete = result.isComplete,
        // damagedBytes = result.damagedBytes, fixes/checksums from the parse.
        val damaged = downloader(
            LosesOneChunk(SimulatedLoggerTransport(flash, chunkSize = 0x800)) { true }
        ).download()
        // The link is still bad, so the repair cannot succeed.
        val resumed = downloader(
            LosesOneChunk(SimulatedLoggerTransport(flash, chunkSize = 0x800)) { true }
        ).download(
            existing = damaged.image,
            resumeFrom = damaged.image.size,
            priorDamage = damaged.damagedRanges,
        )
        val parsed = MtkLogParser.parse(resumed.image, GpsRollover.AXN_130B)

        val evidence = EraseGate.Evidence(
            fileName = "flash-resumed.bin",
            coveredBytes = resumed.image.size,
            downloadComplete = resumed.isComplete,
            damagedBytes = resumed.damagedBytes,
            fixes = parsed.fixes.size,
            checksumFailures = parsed.stats.checksumFailures,
        )
        // The device's pointer sits inside the image, as it does after a dump
        // that reached the end of the written flash.
        val writePointer = FlashDownloader.frontierOffset(flash).toLong()

        val blockers = EraseGate.blockers(evidence, writePointer)
        assertTrue(
            blockers.any { it.reason.contains("missing") },
            "the gate must name the missing bytes; got ${blockers.map { it.reason }}",
        )
        assertFalse(
            EraseGate.permits(evidence, writePointer, EraseGate.confirmationWord(evidence)),
            "an erase against a dump with holes destroys data that was never copied",
        )
    }
}
