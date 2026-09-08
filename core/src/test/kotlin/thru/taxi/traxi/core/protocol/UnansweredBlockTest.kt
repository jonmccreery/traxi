package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device that stops answering must be distinguishable from one that finished.
 *
 * This is the signature the Bluetooth link-recovery leans on: over RFCOMM this
 * logger's command handling wedges under sustained reading, and the cure is a
 * new radio link and a resume from the frontier. That recovery is only
 * defensible because the condition triggering it is *observed* rather than
 * guessed -- consecutive block attempts each returning zero bytes -- so the
 * distinction is worth a test of its own.
 *
 * The failure this guards against is silent and expensive in both directions:
 * mistaking silence for end-of-flash truncates a dump and calls it complete,
 * and mistaking end-of-flash for silence would rebuild the link forever.
 */
class UnansweredBlockTest {

    /** Serves normally, then goes mute — the wedge, without the hardware. */
    private class MuteAfterBlocks(
        private val inner: Transport,
        private val blocksBeforeMute: Int,
    ) : Transport {
        private var logReads = 0

        override val description: String get() = inner.description

        // Short, so three doomed attempts cost milliseconds rather than the
        // ten seconds a real Bluetooth link is given.
        override val blockReadIdleTimeoutMillis: Long get() = 50

        override val isOpen: Boolean get() = inner.isOpen
        override suspend fun open() = inner.open()

        override suspend fun write(bytes: ByteArray) {
            if (String(bytes, Charsets.US_ASCII).contains("PMTK182,7")) logReads++
            if (muted) return          // the request is swallowed, as if unheard
            inner.write(bytes)
        }

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
            if (muted) 0 else inner.read(dest, timeoutMillis)   // silence, not EOF

        override fun close() = inner.close()

        private val muted: Boolean get() = logReads > blocksBeforeMute
    }

    private fun twoBlocksOfData(): ByteArray {
        val block = FlashDownloader.DEFAULT_BLOCK_SIZE
        // Non-0xFF, so the downloader cannot mistake it for erased flash and
        // stop for the legitimate reason instead of the one under test.
        return ByteArray(block * 2) { (it % 251).toByte() }
    }

    @Test
    fun `a device that goes silent mid-transfer is reported as unanswered`() = runBlocking {
        val block = FlashDownloader.DEFAULT_BLOCK_SIZE
        val transport = MuteAfterBlocks(
            SimulatedLoggerTransport(twoBlocksOfData(), chunkSize = 0x800),
            blocksBeforeMute = 1,
        )
        transport.open()
        val downloader = FlashDownloader(PmtkClient(transport, RingTranscript()))

        val result = downloader.download()

        assertTrue(
            result.stoppedUnanswered,
            "silence after every attempt must be reported as unanswered, " +
                "not as the end of the flash",
        )
        assertFalse(
            result.stoppedOnUnwritten,
            "silence is not erased flash — calling it so truncates a dump and " +
                "presents it as complete",
        )
        // The bytes that did arrive are kept, and land on a block boundary so
        // the recovery can resume from exactly here.
        assertEquals(block, result.image.size)
        assertEquals(0, result.image.size % block)
        assertFalse(result.isComplete)
    }

    @Test
    fun `a transfer that reaches the end of flash is not reported as unanswered`() = runBlocking {
        // The control. Erased flash still answers, with 0xFF, and must stop the
        // download without ever looking like a wedged device.
        val block = FlashDownloader.DEFAULT_BLOCK_SIZE
        val image = ByteArray(block * 3) { if (it < block) 0x41 else 0xFF.toByte() }
        val transport = SimulatedLoggerTransport(image, chunkSize = 0x800)
        transport.open()
        val downloader = FlashDownloader(PmtkClient(transport, RingTranscript()))

        val result = downloader.download()

        assertTrue(result.stoppedOnUnwritten, "should stop on the unwritten run")
        assertFalse(
            result.stoppedUnanswered,
            "reaching the end of flash must never trigger link recovery",
        )
        assertTrue(result.isComplete)
    }
}
