package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * That logging always comes back on.
 *
 * This is the regression test for a bug that shipped: the config writes
 * disabled logging, did their work, and re-enabled it — with no `finally`. A
 * write that threw in the middle left the device powered on and not recording,
 * while the user was told only that their setting change had failed. A logger
 * that is not recording looks exactly like one that is.
 */
class LoggingPausedTest {

    private fun client(): Pair<PmtkClient, SimulatedLoggerTransport> {
        val transport = SimulatedLoggerTransport(ByteArray(0x1000), chunkSize = 0x400)
        return PmtkClient(transport, defaultTimeoutMillis = 1_000) to transport
    }

    @Test
    fun `logging is disabled around the body and restored after it`() = runBlocking {
        val (c, transport) = client()
        transport.open()

        var ran = false
        c.withLoggingPaused { ran = true }

        assertTrue(ran)
        assertEquals(
            listOf(Pmtk.WRITE_DISABLE_LOGGING, Pmtk.WRITE_ENABLE_LOGGING),
            transport.received.filter { it == Pmtk.WRITE_DISABLE_LOGGING || it == Pmtk.WRITE_ENABLE_LOGGING },
        )
    }

    @Test
    fun `logging is restored even when the body throws`() = runBlocking {
        val (c, transport) = client()
        transport.open()

        assertFailsWith<IllegalStateException> {
            c.withLoggingPaused { error("the write failed") }
        }

        // The whole point. Before the fix, the enable never ran.
        assertTrue(
            transport.received.contains(Pmtk.WRITE_ENABLE_LOGGING),
            "logging was left disabled after a failing body: ${transport.received}",
        )
    }

    @Test
    fun `the body's exception is what propagates, not anything from the restore`() = runBlocking {
        val (c, transport) = client()
        transport.open()

        val e = assertFailsWith<IllegalStateException> {
            c.withLoggingPaused { error("the original problem") }
        }
        assertEquals("the original problem", e.message)
    }

    @Test
    fun `the restore happens after the body, not before`() = runBlocking {
        val (c, transport) = client()
        transport.open()

        c.withLoggingPaused { c.queryLogStatus() }

        val order = transport.received.filter {
            it == Pmtk.WRITE_DISABLE_LOGGING ||
                it == Pmtk.WRITE_ENABLE_LOGGING ||
                it.startsWith("PMTK182,2,7")
        }
        assertEquals(
            listOf(Pmtk.WRITE_DISABLE_LOGGING, "PMTK182,2,7", Pmtk.WRITE_ENABLE_LOGGING),
            order,
        )
    }

    @Test
    fun `onRestoring fires before logging comes back on`() = runBlocking {
        val (c, transport) = client()
        transport.open()

        var restoringSeenAt = -1
        c.withLoggingPaused(
            onRestoring = { restoringSeenAt = transport.received.size },
        ) { }

        val enableAt = transport.received.indexOf(Pmtk.WRITE_ENABLE_LOGGING)
        assertTrue(
            restoringSeenAt in 0..enableAt,
            "onRestoring fired at $restoringSeenAt, enable was at $enableAt",
        )
    }

    // ---------------- cancellation ----------------

    /**
     * A transport that suspends for real on every call.
     *
     * [SimulatedLoggerTransport] does not: its `write` returns without ever
     * reaching a suspension point, so a cancelled coroutine sails straight
     * through it. Both real transports wrap their I/O in
     * `withContext(Dispatchers.IO)`, which checks for cancellation -- so the
     * simulator cannot reproduce the bug the test below exists for. It passed
     * against the simulator while the device stayed switched off.
     */
    private class SuspendingTransport(val inner: SimulatedLoggerTransport) : Transport {
        override val description get() = inner.description
        override val isOpen get() = inner.isOpen
        override suspend fun open() = withContext(Dispatchers.IO) { inner.open() }
        override suspend fun write(bytes: ByteArray) =
            withContext(Dispatchers.IO) { inner.write(bytes) }
        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
            withContext(Dispatchers.IO) { inner.read(dest, timeoutMillis) }
        override fun close() = inner.close()
    }

    @Test
    fun `logging is restored when the coroutine is cancelled mid-body`() = runBlocking {
        // The regression. A `finally` runs on cancellation, but a suspending
        // call inside it aborts at its first suspension point -- so without
        // NonCancellable the enable is never sent and the logger is left off
        // permanently. This test fails on the pre-fix code.
        val sim = SimulatedLoggerTransport(ByteArray(0x1000), chunkSize = 0x400)
        val transport = SuspendingTransport(sim)
        transport.open()
        val c = PmtkClient(transport, defaultTimeoutMillis = 1_000)

        val job = launch(Dispatchers.Default) {
            c.withLoggingPaused { delay(10_000) }
        }
        delay(300)
        job.cancelAndJoin()

        assertTrue(
            sim.received.contains(Pmtk.WRITE_ENABLE_LOGGING),
            "logging was left disabled after cancellation: ${sim.received}",
        )
    }

    @Test
    fun `a cancelled body still propagates cancellation`() = runBlocking {
        // NonCancellable must cover only the restore. The cancellation itself
        // has to keep travelling, or structured concurrency breaks.
        val sim = SimulatedLoggerTransport(ByteArray(0x1000), chunkSize = 0x400)
        val transport = SuspendingTransport(sim)
        transport.open()
        val c = PmtkClient(transport, defaultTimeoutMillis = 1_000)

        val job = launch(Dispatchers.Default) { c.withLoggingPaused { delay(10_000) } }
        delay(300)
        job.cancelAndJoin()
        assertTrue(job.isCancelled, "the job should still end cancelled")
    }
}
