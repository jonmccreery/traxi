package thru.taxi.traxi.session

import org.junit.Test
import thru.taxi.traxi.core.protocol.FlashDownloader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first tests in the app module.
 *
 * Until 2026-09-15 `app/` had no test source set, so everything here was
 * checked by reading it — including the message that told the user a ride they
 * had just downloaded did not exist.
 */
class FetchOutcomeTest {

    private val block = 0x10000

    /** Three blocks, real data up to [frontier], unwritten after it. */
    private fun image(frontier: Int): ByteArray =
        ByteArray(3 * block) { if (it < frontier) 0x41 else 0xFF.toByte() }

    private fun result(
        image: ByteArray,
        failure: String? = null,
        fullReread: Boolean = false,
        stoppedOnUnwritten: Boolean = true,
    ) = FlashDownloader.Result(
        image = image,
        sectorSpan = image.size / block,
        retries = 0,
        stoppedOnUnwritten = stoppedOnUnwritten,
        failure = failure,
        fullReread = fullReread,
    )

    @Test
    fun `a gain inside the frontier sector is reported, not denied`() {
        // The 2026-09-15 shape: identical lengths, frontier moved.
        val previous = image(0x20500)
        val updated = image(0x21000)
        assertEquals(previous.size, updated.size, "the test must reproduce the equal-length case")

        val message = FetchOutcome.message(result(updated), previous, "flash-new.bin", cancelled = false)

        assertTrue(message.startsWith("Added"), "got: $message")
        assertTrue(message.contains("2 KB"), "got: $message")
        assertTrue(
            !message.contains("Already up to date"),
            "this is the regression: a fetch that gained 2,816 bytes must not deny it",
        )
    }

    @Test
    fun `a fetch that gained nothing says so`() {
        val same = image(0x20500)
        assertEquals(
            "Already up to date — nothing new on the logger",
            FetchOutcome.message(result(same), same, "flash-new.bin", cancelled = false),
        )
    }

    @Test
    fun `a full re-read never claims an amount added`() {
        val previous = image(0x20500)
        val updated = image(0x21000)
        val message = FetchOutcome.message(
            result(updated, fullReread = true), previous, "flash-new.bin", cancelled = false,
        )
        assertTrue(message.startsWith("Re-read the whole log"), "got: $message")
        assertTrue(!message.contains("Added"), "a wrap gives no baseline to add against: $message")
    }

    @Test
    fun `how it stopped outranks what it gained`() {
        val previous = image(0x20500)
        val updated = image(0x21000)

        val failed = FetchOutcome.message(
            result(updated, failure = "link lost", stoppedOnUnwritten = false),
            previous, "flash-new.bin", cancelled = false,
        )
        assertTrue(failed.startsWith("Interrupted"), "got: $failed")

        val cancelled = FetchOutcome.message(
            result(updated, stoppedOnUnwritten = false), previous, "flash-new.bin", cancelled = true,
        )
        assertTrue(cancelled.startsWith("Cancelled"), "got: $cancelled")
    }

    @Test
    fun `the seed opens at the resume point, and agrees with itself`() {
        // Frontier in sector 2, so the extend resumes at the start of sector 2.
        val previous = image(2 * block + 0x500)
        val seed = FetchOutcome.extendSeed(previous)

        assertEquals(2 * block, seed.bytesDownloaded)
        assertEquals(2, seed.sectorPosition)
        assertEquals(2 * block, seed.resumedFrom)
        assertTrue(seed.running)
        // The bug this replaced: opening at previous.size, two sectors ahead of
        // the first figure the downloader reports.
        assertTrue(seed.bytesDownloaded < previous.size)
    }

    @Test
    fun `a dump that cannot be extended seeds at zero rather than guessing`() {
        val seed = FetchOutcome.extendSeed(ByteArray(3 * block) { 0xFF.toByte() })
        assertEquals(0, seed.bytesDownloaded)
        assertEquals(0, seed.sectorPosition)
        assertEquals(0, seed.resumedFrom)
    }
}
