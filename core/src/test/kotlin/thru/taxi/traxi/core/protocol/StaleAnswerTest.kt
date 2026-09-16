package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.transport.PmtkTimeoutException
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §16.3: an answer must never satisfy a request that had not been made yet.
 *
 * `PmtkClient.queued` holds sentences seen while waiting for something else,
 * and `awaitSentence` consults it *before* the wire. Nothing aged it out, so a
 * sentence parked there was indistinguishable from a reply to whatever was
 * asked next. Two supplies fed it:
 *
 *  1. **the device acks every query** -- `PMTK001,182,2,3`, seventy-six of them
 *     in `data/usb_clean_2026-09-06.log` -- and nothing in the query path
 *     consumes those acks;
 *  2. a retried request is answered twice, and only the first copy is matched.
 *
 * Against a write predicate that tested the ack's *command* and never its
 * subcommand, supply 1 alone was enough: a connect parked six success-acks and
 * the next `writeLoggingEnabled` took one, reporting a logger switched back on
 * without waiting to see whether it was. That is the §12 failure the
 * `withLoggingPaused` guarantee exists to prevent, reached from underneath.
 *
 * The cure is three-part: acks match on subcommand, `exchange` drops the queue
 * and drains the wire before it sends, and `pump` -- which by definition has
 * nothing waiting -- no longer queues at all.
 *
 * **Each part has a test that fails when only that part is reverted.** The
 * first three tests below check the behaviour a user would see; on their own
 * they passed with any *one* of the three fixes in place, because the three
 * mask each other, which means none of them was actually verified. The three
 * that follow isolate one mechanism each. A fix nothing can fail is a fix
 * nobody has checked.
 */
class StaleAnswerTest {

    /**
     * A logger that answers, but not before the client has given up and asked
     * again -- and then answers both copies.
     *
     * Time is counted in reads rather than wall-clock so the retry lands in the
     * same place every run; [PmtkClient] takes its clock as a parameter, which
     * is what makes that possible.
     */
    private class SlowDevice(
        /** Reads that must pass before a reply is delivered. */
        private val holdReads: Int = 0,
        /**
         * Reads during which the device delivers nothing at all, after which
         * everything it owes arrives **in one batch**.
         *
         * This is the §15 shape in miniature: the command path wedges, the
         * client times out and re-sends, and when the logger comes back it
         * flushes both answers together. A single `awaitSentence` then ingests
         * two answers, matches one, and parks the other -- with no `pump`
         * involved anywhere.
         */
        private val stallUntilRead: Int = 0,
        private val respond: (List<String>) -> String?,
    ) : Transport {

        private class Pending(val dueAtRead: Int, val bytes: ByteArray)

        private val pending = mutableListOf<Pending>()
        private var out = ByteArray(0)
        private var offset = 0
        private var reads = 0

        /** Fake clock, advanced by the cost of a read. */
        var nowMillis = 0L
            private set

        override val description: String get() = "slow device"
        override val isOpen: Boolean get() = true
        override suspend fun open() = Unit
        override fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            val line = String(bytes, Charsets.US_ASCII).trim()
            val sentence = Nmea.parse(line) ?: return
            val reply = respond(sentence.fields) ?: return
            pending += Pending(
                maxOf(reads + holdReads, stallUntilRead),
                Nmea.frame(reply).toByteArray(Charsets.US_ASCII),
            )
        }

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            nowMillis += READ_COST_MILLIS
            reads++

            val due = pending.filter { it.dueAtRead <= reads }
            if (due.isNotEmpty()) {
                pending.removeAll(due)
                val tail = out.copyOfRange(offset, out.size)
                out = tail + due.fold(ByteArray(0)) { acc, p -> acc + p.bytes }
                offset = 0
            }

            if (offset >= out.size) return 0
            val n = minOf(dest.size, out.size - offset)
            out.copyInto(dest, 0, offset, offset + n)
            offset += n
            return n
        }

        /** Answers still inside the device, not yet on the wire. */
        val undelivered: Int get() = pending.size

        companion object {
            /** Each read costs this much of the fake clock. */
            const val READ_COST_MILLIS = 250L
        }
    }

    @Test
    fun `a duplicate answer left by a retry is not served to the next query`() = runBlocking {
        var devicePointer = 0x0051F3C2L
        val device = SlowDevice(holdReads = 20) { f ->
            if (f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "2" && f.getOrNull(2) == "8") {
                "PMTK182,3,8,%08X".format(devicePointer)
            } else null
        }
        val transcript = RingTranscript()
        val client = PmtkClient(device, transcript, clock = { device.nowMillis })

        // First probe: times out once, is retried, and is answered -- twice,
        // because the device answered the first copy as well as the retry.
        val first = client.queryWritePointer()
        assertEquals(devicePointer, first, "the first probe reads the live value")
        assertTrue(
            transcript.snapshot().any { it.contains("retry") },
            "the duplicate has to come from a real retry, not from the fixture",
        )

        // The logger keeps recording.
        devicePointer += 2_048

        // The telemetry loop's pump() is what used to pick the late duplicate
        // up and park it. It now passes it to the observer and drops it.
        repeat(30) { client.pump(timeoutMillis = 10) }
        assertEquals(0, device.undelivered, "the duplicate really did reach the client")

        assertEquals(
            devicePointer, client.queryWritePointer(),
            "the second probe must read the device, not the leftovers of the first",
        )
    }

    @Test
    fun `an ack for a query is not accepted as an ack for a write`() = runBlocking {
        // The real shape, and the one that needed no retry at all: the device
        // acks each query, the ack is never consumed, and the next write used
        // to match it out of the queue.
        val sent = mutableListOf<String>()
        val device = SlowDevice(holdReads = 1) { f ->
            sent += f.joinToString(",")
            when {
                // Every query is acknowledged, exactly as the capture shows.
                f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "2" -> "PMTK001,182,2,3"
                // ...and the command path is wedged: PMTK182,4 is heard and
                // ignored, which is §15 in one line.
                else -> null
            }
        }
        val client = PmtkClient(device, RingTranscript(), clock = { device.nowMillis })

        // A query, whose ack nothing consumes. `queryConfig` times out here
        // because this fixture never sends the *value* -- which is fine, and is
        // itself the point: the ack alone is not an answer.
        runCatching { client.queryLogStatus() }
        assertTrue(sent.any { it.startsWith("PMTK182,2") }, "the query went out")

        assertFailsWith<PmtkTimeoutException>(
            "a logger that ignores PMTK182,4 must be reported, not papered over " +
                "with somebody else's acknowledgement",
        ) {
            client.writeLoggingEnabled(true)
        }

        assertTrue(
            sent.any { it.startsWith("PMTK182,4") },
            "the enable really was sent -- the failure is that it was never answered",
        )
    }

    @Test
    fun `withLoggingPaused reports a restore the device never acknowledged`() = runBlocking {
        // End to end through the guarantee itself, on a device that acks its
        // queries and then stops answering commands part-way through.
        var acceptCommands = true
        val device = SlowDevice(holdReads = 1) { f ->
            when {
                f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "2" -> "PMTK001,182,2,3"
                f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "5" -> "PMTK001,182,5,3"
                f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "4" ->
                    if (acceptCommands) "PMTK001,182,4,3" else null
                else -> null
            }
        }
        val client = PmtkClient(device, RingTranscript(), clock = { device.nowMillis })

        var restoreFailure: String? = null
        acceptCommands = false
        client.withLoggingPaused(onRestoreFailed = { restoreFailure = it }) { }

        assertTrue(
            restoreFailure != null,
            "the logger did not acknowledge PMTK182,4, so the user must be told " +
                "it is not recording -- this is the §12 guarantee",
        )
    }

    // ---------------- one test per mechanism ----------------
    //
    // Each of these fails if, and only if, its own part of the fix is removed.

    /**
     * Isolates **ack subcommand matching**.
     *
     * The queue clear cannot help here: the foreign ack has not arrived when
     * the write is sent. It lands *during* the wait, which is a window no
     * amount of clearing covers -- only knowing what the ack is for does.
     */
    @Test
    fun `an ack that arrives mid-wait is still judged on its subcommand`(): Unit = runBlocking {
        // The query's ack is delayed long enough to arrive while the *write*
        // is waiting for its own. The device never acks PMTK182,4 at all.
        val device = SlowDevice(holdReads = 45) { f ->
            if (f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "2") "PMTK001,182,2,3" else null
        }
        val client = PmtkClient(device, RingTranscript(), clock = { device.nowMillis })

        runCatching { client.queryLogStatus() }

        assertFailsWith<PmtkTimeoutException>(
            "an ack for PMTK182,2 is not an ack for PMTK182,4, whenever it turns up",
        ) {
            client.writeLoggingEnabled(true)
        }
    }

    /**
     * Isolates **the queue clear in `exchange`**.
     *
     * Same command, same subcommand, same everything: two answers to the same
     * question, so no predicate can tell them apart. Only discarding what
     * predates the request can. No `pump` runs in this test.
     */
    @Test
    fun `a duplicate parked during a stall is not served to the next query`() = runBlocking {
        var devicePointer = 0x0051F3C2L
        // Silent for 15 reads, then both the original answer and the retry's
        // answer arrive together -- one `awaitSentence`, two answers, one match.
        val device = SlowDevice(stallUntilRead = 15) { f ->
            if (f.getOrNull(0) == "PMTK182" && f.getOrNull(1) == "2" && f.getOrNull(2) == "8") {
                "PMTK182,3,8,%08X".format(devicePointer)
            } else null
        }
        val transcript = RingTranscript()
        val client = PmtkClient(device, transcript, clock = { device.nowMillis })

        assertEquals(devicePointer, client.queryWritePointer())
        assertTrue(
            transcript.snapshot().any { it.contains("retry") },
            "the duplicate must come from a real retry",
        )
        assertEquals(0, device.undelivered, "both answers were delivered in the stall flush")

        devicePointer += 4_096
        assertEquals(
            devicePointer, client.queryWritePointer(),
            "the parked duplicate is indistinguishable from a fresh answer, so it " +
                "must not survive into the next request",
        )
    }

    /**
     * Isolates **`pump` not queuing**.
     *
     * Stated as the property itself, through `awaitSentence` rather than
     * through `exchange`, because `exchange` clears the queue anyway and would
     * hide it. This is the layer the block reader waits on.
     */
    @Test
    fun `a sentence seen by pump is not waiting in the queue afterwards`(): Unit = runBlocking {
        val device = EmitsOnce("PMTK182,3,8,0051F3C2")
        val client = PmtkClient(device, RingTranscript(), clock = { device.nowMillis })

        var observed = 0
        client.onSentence = { observed++ }

        assertTrue(client.pump(timeoutMillis = 10) > 0, "the sentence was read")
        assertEquals(1, observed, "and delivered to the observer, which is pump's job")

        assertFailsWith<PmtkTimeoutException>(
            "having been seen is not having been asked for",
        ) {
            client.awaitSentence(timeoutMillis = 1_000) { it.matches("PMTK182", "3", "8") }
        }
    }

    /** Emits one sentence, then stays quiet rather than closing. */
    private class EmitsOnce(sentence: String) : Transport {
        private val bytes = Nmea.frame(sentence).toByteArray(Charsets.US_ASCII)
        private var sent = false

        var nowMillis = 0L
            private set

        override val description: String get() = "emits once"
        override val isOpen: Boolean get() = true
        override suspend fun open() = Unit
        override fun close() = Unit
        override suspend fun write(bytes: ByteArray) = Unit

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            nowMillis += 250
            if (sent) return 0
            sent = true
            bytes.copyInto(dest)
            return bytes.size
        }
    }

    @Test
    fun `the simulator acks its queries, as the device does`() = runBlocking {
        // Guards the fixture itself. If this ever goes back to silence, the
        // whole class of bug above becomes invisible to the suite again.
        val transport = SimulatedLoggerTransport(ByteArray(0x20000) { 0xFF.toByte() })
        transport.open()
        val client = PmtkClient(transport, RingTranscript())

        client.queryWritePointer()

        assertTrue(
            transport.received.any { it.startsWith("PMTK182,2,8") },
            "the query was sent",
        )
        // And a write immediately afterwards must still have to wait for its own
        // acknowledgement rather than finding one lying around.
        client.writeLoggingEnabled(false)
        assertEquals(
            "0100",
            "%04X".format(Pmtk.LogStatus.parse(client.queryLogStatus())!!.bits),
            "the device really was told to stop",
        )
    }
}
