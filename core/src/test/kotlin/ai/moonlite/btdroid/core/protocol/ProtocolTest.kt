package ai.moonlite.btdroid.core.protocol

import ai.moonlite.btdroid.core.format.LogFormat
import ai.moonlite.btdroid.core.transport.PmtkTimeoutException
import ai.moonlite.btdroid.core.transport.SimulatedLoggerTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmeaTest {

    @Test
    fun `checksum matches the documented example`() {
        // $PMTK182,2,2*39 -- XOR of everything between '$' and '*'.
        assertEquals(0x39, Nmea.checksum("PMTK182,2,2"))
    }

    @Test
    fun `frame produces a complete sentence`() {
        assertEquals("\$PMTK182,2,2*39\r\n", Nmea.frame("PMTK182,2,2"))
    }

    @Test
    fun `frame and parse round-trip`() {
        val sentence = Nmea.parse(Nmea.frame("PMTK182,3,2,000A003F"))
        assertNotNull(sentence)
        assertEquals("PMTK182", sentence.type)
        assertEquals(listOf("PMTK182", "3", "2", "000A003F"), sentence.fields)
    }

    @Test
    fun `bad checksum is rejected rather than thrown`() {
        // Corrupt lines are a steady-state condition on a raw byte stream, so
        // they must be droppable without unwinding the caller.
        assertNull(Nmea.parse("\$PMTK182,2,2*FF"))
    }

    @Test
    fun `malformed lines are rejected`() {
        assertNull(Nmea.parse(""))
        assertNull(Nmea.parse("PMTK182,2,2*39"))   // no leading $
        assertNull(Nmea.parse("\$PMTK182,2,2"))    // no checksum
    }

    @Test
    fun `lowercase checksum hex is accepted`() {
        assertNotNull(Nmea.parse("\$PMTK182,2,2*39".replace("39", "39".lowercase())))
    }

    @Test
    fun `assembler reassembles sentences split across reads`() {
        val assembler = NmeaLineAssembler()
        assertEquals(emptyList(), assembler.feed("\$PMTK18".toByteArray()))
        assertEquals(emptyList(), assembler.feed("2,2,2*39".toByteArray()))
        assertEquals(listOf("\$PMTK182,2,2*39"), assembler.feed("\r\n".toByteArray()))
    }

    @Test
    fun `assembler splits multiple sentences in one read`() {
        val assembler = NmeaLineAssembler()
        val lines = assembler.feed("\$A*00\r\n\$B*00\r\n".toByteArray())
        assertEquals(2, lines.size)
    }

    @Test
    fun `assembler does not grow without bound on a stream with no terminators`() {
        val assembler = NmeaLineAssembler(maxLineLength = 64)
        repeat(100) { assembler.feed("0123456789".toByteArray()) }

        // 1000 characters went in with no line terminator. Whatever is still
        // buffered must be bounded by maxLineLength, not by what arrived.
        val flushed = assembler.feed("\n".toByteArray())
        assertEquals(1, flushed.size)
        assertTrue(
            flushed.single().length <= 64,
            "buffer grew past maxLineLength: ${flushed.single().length} chars",
        )
    }

    @Test
    fun `a single read carrying several sentences yields all of them`() {
        // Guards the bug where only the first sentence in a batch survived:
        // RFCOMM delivers whatever is in the buffer, so a block read's chunks
        // arrive several to a read.
        val assembler = NmeaLineAssembler()
        val batch = buildString {
            repeat(5) { append(Nmea.frame("PMTK182,8,0000000$it,AABB")) }
        }
        val lines = assembler.feed(batch.toByteArray())
        assertEquals(5, lines.size)
        assertTrue(lines.all { Nmea.parse(it) != null })
    }
}

class PmtkCommandTest {

    @Test
    fun `read length must be even`() {
        // Odd lengths are silently rejected by the device and present as an
        // unexplained timeout, so fail loudly at the call site instead.
        assertFailsWith<IllegalArgumentException> { Pmtk.readLog(0, 0x10001) }
    }

    @Test
    fun `read command formats address and length as 8 hex digits`() {
        assertEquals("PMTK182,7,00010000,00010000", Pmtk.readLog(0x10000, 0x10000))
    }

    @Test
    fun `ack parses success and failure flags`() {
        val ok = Pmtk.Ack.from(Nmea.parse(Nmea.frame("PMTK001,182,1,3"))!!)
        assertNotNull(ok)
        assertTrue(ok.isSuccess)

        val bad = Pmtk.Ack.from(Nmea.parse(Nmea.frame("PMTK001,182,1,2"))!!)
        assertNotNull(bad)
        assertTrue(!bad.isSuccess)
    }

    @Test
    fun `firmware response identifies the rollover-affected build`() {
        val fw = Pmtk.Firmware.from(
            Nmea.parse(Nmea.frame("PMTK705,AXN_1.30-B_1.3_C01,0008,,"))!!
        )
        assertNotNull(fw)
        assertEquals("AXN_1.30-B_1.3_C01", fw.release)
        assertEquals("0008", fw.modelId)
        assertTrue(fw.needsWeekRollover)
    }

    @Test
    fun `hex decoding rejects odd and invalid input`() {
        assertEquals(listOf(0xAA, 0xBB), PmtkClient.decodeHex("AABB")!!.map { it.toInt() and 0xFF })
        assertNull(PmtkClient.decodeHex("ABC"))
        assertNull(PmtkClient.decodeHex("ZZ"))
    }
}

class PmtkClientTest {

    private fun simulator(flash: ByteArray = ByteArray(0)) =
        SimulatedLoggerTransport(flash, chunkSize = 0x100)

    @Test
    fun `queries the firmware string`() = runBlocking {
        val transport = simulator().also { it.open() }
        val client = PmtkClient(transport)
        val fw = client.queryFirmware()
        assertEquals("AXN_1.30-B_1.3_C01", fw.release)
        assertEquals("0008", fw.modelId)
    }

    @Test
    fun `queries the log format register`() = runBlocking {
        val transport = simulator().also { it.open() }
        val client = PmtkClient(transport)
        assertEquals(LogFormat.OBSERVED.bits, client.queryLogFormat().bits)
    }

    @Test
    fun `queries the time interval in seconds`() = runBlocking {
        val transport = simulator().also { it.open() }
        val client = PmtkClient(transport)
        assertEquals(20.0, client.queryTimeIntervalSeconds(), 0.0)
    }

    @Test
    fun `a failing flash-size query times out rather than returning a wrong number`() =
        runBlocking {
            // The real device's flash-size query fails. It must surface as an
            // error, never as a plausible-looking size that would truncate a
            // download.
            val transport = simulator().also { it.open() }
            val client = PmtkClient(transport, defaultTimeoutMillis = 60, defaultRetries = 2)
            assertFailsWith<PmtkTimeoutException> {
                client.queryConfig(Pmtk.ConfigField.FLASH_SIZE)
            }
            Unit
        }

    @Test
    fun `unsupported PMTK704 times out rather than hanging forever`() = runBlocking {
        val transport = simulator().also { it.open() }
        val client = PmtkClient(transport, defaultTimeoutMillis = 60, defaultRetries = 1)
        assertFailsWith<PmtkTimeoutException> {
            client.exchange("PMTK704") { it.type == "PMTK704" }
        }
        Unit
    }

    @Test
    fun `reads a log block and places bytes at their reported addresses`() = runBlocking {
        val flash = ByteArray(0x800) { (it and 0xFF).toByte() }
        val transport = simulator(flash).also { it.open() }
        val client = PmtkClient(transport)

        val block = client.readLogBlock(0x100, 0x200)
        assertTrue(block.isComplete)
        assertEquals(0x100, block.address)
        for (i in 0 until 0x200) {
            assertEquals(flash[0x100 + i], block.bytes[i], "byte $i")
        }
    }

    @Test
    fun `writes are acknowledged`() = runBlocking {
        val transport = simulator().also { it.open() }
        val client = PmtkClient(transport)
        // Should not throw: the simulator acks with flag 3.
        client.writeLoggingEnabled(false)
        client.writeConfig(Pmtk.ConfigField.TIME_INTERVAL, "200")
        client.writeLoggingEnabled(true)
    }

    @Test
    fun `transcript records both directions`() = runBlocking {
        val transport = simulator().also { it.open() }
        val transcript = RingTranscript()
        val client = PmtkClient(transport, transcript)
        client.queryFirmware()

        val lines = transcript.snapshot()
        assertTrue(lines.any { it.startsWith(">>") && it.contains("PMTK605") },
            "expected a tx line, got $lines")
        assertTrue(lines.any { it.startsWith("<<") && it.contains("PMTK705") },
            "expected an rx line, got $lines")
    }

    @Test
    fun `ring transcript is bounded`() {
        val transcript = RingTranscript(capacity = 10)
        repeat(100) { transcript.note("line $it") }
        assertEquals(10, transcript.snapshot().size)
    }
}
