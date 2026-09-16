package thru.taxi.traxi.session

import thru.taxi.traxi.core.protocol.FlashDownloader
import thru.taxi.traxi.core.protocol.PmtkClient
import thru.taxi.traxi.core.protocol.RingTranscript
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16.2 and §16.4: how a fetch-new stopped, and what it re-read, must survive
 * into what the user is told.
 *
 * `stoppedUnanswered` is deliberately **not** a `failure` -- that distinction is
 * what lets `downloadWithLinkRecovery` tell a recoverable wedge from a dead
 * transport. [FetchOutcome.message] had not learned it. Its first branch tests
 * `failure != null`, which is null here, and the transfer then fell past
 * `cancelled` and `fullReread` into the byte comparison.
 *
 * The comparison is against a truncated image: the extend restarts at the block
 * holding the previous dump's frontier, so a wedge on that very first block
 * leaves an image *shorter* than the dump it was extending. `newDataBytes` goes
 * negative, `gained > 0` is false, and the last branch -- the one written for a
 * logger that genuinely had nothing to add -- claimed exactly that.
 *
 * That is the §12 shape the [FetchOutcome] file was created to stamp out: a
 * confident negative claim about capture, made after the device stopped
 * answering, over a ride still sitting in the flash. "Already up to date" is
 * now reachable only from a transfer that completed.
 */
class WedgedFetchTest {

    private val block = FlashDownloader.DEFAULT_BLOCK_SIZE

    /**
     * Serves normally for [blocksBeforeMute] log reads and then goes silent,
     * which is what this logger's command path does under sustained reading.
     * Silence, not end-of-stream: the socket stays up and NMEA keeps flowing.
     */
    private class MuteAfterBlocks(
        private val inner: Transport,
        private val blocksBeforeMute: Int,
    ) : Transport {
        private var logReads = 0
        private val muted: Boolean get() = logReads > blocksBeforeMute

        override val description: String get() = inner.description
        // Wall-clock, unlike the fake-clock fixtures in StaleAnswerTest, so it
        // needs headroom: at 50 ms a garbage collection pause mid-block would
        // truncate a healthy read and fail this test for a reason that has
        // nothing to do with what it checks.
        override val blockReadIdleTimeoutMillis: Long get() = 250
        override val isOpen: Boolean get() = inner.isOpen
        override suspend fun open() = inner.open()
        override fun close() = inner.close()

        override suspend fun write(bytes: ByteArray) {
            if (String(bytes, Charsets.US_ASCII).contains("PMTK182,7")) logReads++
            if (muted) return
            inner.write(bytes)
        }

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
            if (muted) 0 else inner.read(dest, timeoutMillis)
    }

    /** Three blocks of flash with the write frontier 0x500 into the third. */
    private fun flash(): ByteArray {
        val frontier = 2 * block + 0x500
        return ByteArray(3 * block) {
            if (it < frontier) ((it % 251) + 1).toByte() else 0xFF.toByte()
        }
    }

    @Test
    fun `a wedge during fetch-new is reported as a wedge`() = runBlocking {
        val previous = flash()

        // The device holds what the previous dump holds, plus whatever has been
        // ridden since -- irrelevant here, because it never gets that far.
        val transport = MuteAfterBlocks(
            SimulatedLoggerTransport(previous, chunkSize = 0x800),
            // One block: the wrap probe is answered, the extend is not.
            blocksBeforeMute = 1,
        )
        transport.open()
        val downloader = FlashDownloader(PmtkClient(transport, RingTranscript()))

        val result = downloader.downloadIncremental(previous)

        // The transfer stopped because the logger stopped answering...
        assertTrue(result.stoppedUnanswered, "the wedge must be reported as unanswered")
        assertNull(result.failure, "a wedge is not a failure -- that is the whole point of it")
        assertFalse(result.isComplete)
        // ...leaving an image shorter than the dump it was extending.
        assertTrue(
            result.image.size < previous.size,
            "the extend restarts at the frontier block, so a wedge truncates: " +
                "${result.image.size} < ${previous.size}",
        )
        assertTrue(
            FlashDownloader.newDataBytes(previous, result.image) < 0,
            "the gain measurement is negative, which no branch of the message handles",
        )

        val message = FetchOutcome.message(
            result = result,
            previous = previous,
            targetName = "flash-new.bin",
            cancelled = false,
        )

        assertEquals(
            "The logger stopped answering — 128 KB saved and resumable", message,
            "a transfer the device cut short may not make any claim about content",
        )
    }

    @Test
    fun `only a completed transfer may say the logger has nothing new`() = runBlocking {
        // The control, and the general form of the rule. Same device, same dump,
        // but the transfer runs to the end of the flash: now the negative claim
        // is a measurement rather than the absence of one.
        val previous = flash()
        val transport = SimulatedLoggerTransport(previous, chunkSize = 0x800)
        transport.open()

        val result = FlashDownloader(PmtkClient(transport, RingTranscript()))
            .downloadIncremental(previous)

        assertTrue(result.isComplete, "this one reached the end of the flash")
        assertEquals(
            "Already up to date — nothing new on the logger",
            FetchOutcome.message(result, previous, "flash-new.bin", cancelled = false),
        )
    }

    /**
     * §16.4: `downloadWithLinkRecovery` dropped `fullReread`.
     *
     * The merge is now [FlashDownloader.Result.continuedBy], extracted so this
     * can call the real rule instead of a copy of it. It previously read,
     * inline in that method:
     *
     * ```kotlin
     * result = continued.copy(
     *     retries = result.retries + continued.retries,
     *     damagedRanges = result.damagedRanges + continued.damagedRanges,
     * )
     * ```
     *
     * `continued` comes from a plain `download()`, which has no idea a wrap
     * probe ever ran, so `fullReread` reverted to its default of false and
     * `sectorsFetched` counted only the last segment. Every link cycle -- the
     * §15 recovery, sized for up to sixty of them per transfer -- erased the
     * one fact that stops the app claiming an amount added after a wrap.
     *
     * Both are now carried forward explicitly, which is what this asserts.
     */
    @Test
    fun `a link cycle carries the full-re-read flag and the work done`() {
        val previous = ByteArray(3 * block) {
            if (it < 2 * block + 0x500) 0x41 else 0xFF.toByte()
        }
        // What the first pass returned: the log had wrapped, so fetch-new
        // re-read the whole chip, and then the link wedged.
        val firstPass = FlashDownloader.Result(
            image = ByteArray(6 * block) { if (it < 5 * block) 0x42 else 0xFF.toByte() },
            sectorSpan = 6,
            retries = 4,
            stoppedOnUnwritten = false,
            fullReread = true,
            stoppedUnanswered = true,
            sectorsFetched = 6,
        )
        // What the resume after the link cycle returned.
        val continued = FlashDownloader.Result(
            image = ByteArray(8 * block) { if (it < 7 * block) 0x42 else 0xFF.toByte() },
            sectorSpan = 8,
            retries = 1,
            stoppedOnUnwritten = true,
            sectorsFetched = 2,
        )

        // The production rule itself, not a copy of it. Transcribing the merge
        // into the test made the test pass no matter what SessionController
        // did, which is no test at all; `continuedBy` exists so this line can
        // call the thing it is checking.
        val merged = firstPass.continuedBy(continued)

        assertTrue(merged.fullReread, "the flag the first pass set must survive the cycle")
        assertEquals(8, merged.sectorsFetched, "and so must the work the first pass did")
        assertEquals(5, merged.retries)

        val message = FetchOutcome.message(merged, previous, "flash-new.bin", cancelled = false)

        assertTrue(
            message.startsWith("Re-read the whole log"),
            "after a wrap there is no baseline to add against: $message",
        )
        assertFalse(message.startsWith("Added"), "no amount may be claimed: $message")
    }
}
