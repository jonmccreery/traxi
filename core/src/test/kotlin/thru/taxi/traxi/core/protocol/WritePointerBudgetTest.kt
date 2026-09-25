package thru.taxi.traxi.core.protocol

import kotlinx.coroutines.runBlocking
import thru.taxi.traxi.core.transport.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an unanswered write-pointer query costs the caller.
 *
 * §15: this is the request that wedges this logger's radio, and the wedge is
 * usually discovered by this very query going unanswered. What happens next is
 * therefore not a detail. On 2026-09-24 the connect-time call took the default
 * three attempts at three seconds and held `openWith` for twelve seconds while
 * the link died underneath it — across which the app received six valid DGPS
 * fixes and discarded them, because nothing was listening yet.
 *
 * The retry budget is now the caller's to choose, and these tests pin that the
 * choice is actually honoured — a default that silently ignored the argument
 * would restore the twelve seconds with nothing to show for it.
 */
class WritePointerBudgetTest {

    /** Accepts everything, answers nothing. The wedge, in its simplest form. */
    private class DeafTransport : Transport {
        val sent = mutableListOf<String>()
        override val description = "deaf"
        override var isOpen = true
        override suspend fun open() { isOpen = true }
        override suspend fun write(bytes: ByteArray) {
            sent += String(bytes).trim()
        }
        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int = 0
        override fun close() { isOpen = false }
    }

    @Test
    fun `one attempt means one request on the wire`() {
        val t = DeafTransport()
        val client = PmtkClient(t, RingTranscript())
        val answer = runBlocking {
            client.queryWritePointer(timeoutMillis = 50, retries = 1)
        }
        assertNull(answer, "a deaf device must yield null, not a guess")
        assertEquals(
            1, t.sent.count { it.contains("PMTK182,2,8") },
            "retries = 1 must put exactly one query on the wire; sent ${t.sent}",
        )
    }

    @Test
    fun `the default budget still retries, for the caller that can afford it`() {
        // The telemetry loop keeps this: it only probes a link already carrying
        // bytes, and a retry there costs a row rather than a connect.
        val t = DeafTransport()
        val client = PmtkClient(t, RingTranscript(), defaultTimeoutMillis = 50)
        runBlocking { client.queryWritePointer() }
        assertTrue(
            t.sent.count { it.contains("PMTK182,2,8") } > 1,
            "the default must still retry; sent ${t.sent}",
        )
    }

    @Test
    fun `a wedged connect cannot be held for the full default budget`() {
        // The property that actually mattered on the night: bounded, and bounded
        // well below 3 x 3 s. Timing is asserted loosely -- this is a ceiling,
        // not a benchmark -- because the point is that it returns, quickly,
        // rather than blocking a connect behind a device that will never reply.
        val t = DeafTransport()
        val client = PmtkClient(t, RingTranscript())
        val elapsed = runBlocking {
            val start = System.nanoTime()
            client.queryWritePointer(timeoutMillis = 200, retries = 1)
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue(elapsed < 2_000, "took ${elapsed}ms; a single short attempt must not stall")
    }
}
