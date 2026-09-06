package thru.taxi.traxi.core.protocol

import thru.taxi.traxi.core.transport.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That live telemetry reaches the observer without a second reader.
 *
 * The design constraint this protects: navigation sentences must be picked up
 * by whatever is *already* reading the stream. A second reader would compete
 * for bytes with a block read in progress, and losing bytes out of a block read
 * is the failure this project exists to prevent.
 */
class TelemetryTapTest {

    /** Replays a fixed script of bytes, one read per entry. */
    private class ScriptedTransport(private val script: List<String>) : Transport {
        private var index = 0
        override val description = "scripted"
        override var isOpen = true
            private set

        val written = mutableListOf<String>()

        override suspend fun open() { isOpen = true }
        override fun close() { isOpen = false }
        override suspend fun write(bytes: ByteArray) {
            written += String(bytes, Charsets.US_ASCII).trim()
        }

        override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
            if (index >= script.size) return 0
            val bytes = script[index++].toByteArray(Charsets.US_ASCII)
            bytes.copyInto(dest)
            return bytes.size
        }
    }

    private val gga = "\$GPGGA,155939.000,4153.0220,N,08748.2074,W,1,7,1.16,234.8,M,-34.0,M,,*6B\r\n"
    private val gsa = "\$GPGSA,A,3,23,32,24,10,27,15,18,,,,,,1.44,1.16,0.86*04\r\n"

    @Test
    fun `pump delivers navigation sentences without sending anything`() = runBlocking {
        val transport = ScriptedTransport(listOf(gga, gsa))
        val client = PmtkClient(transport, defaultTimeoutMillis = 200)

        val seen = mutableListOf<NmeaSentence>()
        client.onSentence = { seen += it }

        client.pump(50)
        client.pump(50)

        assertEquals(listOf("GPGGA", "GPGSA"), seen.map { it.type })
        // pump is a read. If it ever starts writing, the "connecting never
        // mutates configuration" rail in PmtkClient's documentation is gone.
        assertTrue(transport.written.isEmpty())
    }

    @Test
    fun `telemetry arrives while waiting for a PMTK reply`() = runBlocking {
        // The important case: a navigation sentence interleaved with the reply
        // the caller actually asked for. This is what makes a three-hour
        // download feed the Live tab for free.
        val ack = Nmea.frame("PMTK001,182,3")
        val transport = ScriptedTransport(listOf(gga, ack))
        val client = PmtkClient(transport, defaultTimeoutMillis = 500)

        val seen = mutableListOf<NmeaSentence>()
        client.onSentence = { seen += it }

        val reply = client.awaitSentence(500) { it.matches("PMTK001") }

        assertEquals("PMTK001", reply.type)
        assertTrue(seen.any { it.type == "GPGGA" }, "navigation sentence was not observed")
    }

    @Test
    fun `bulk log chunks never reach the observer`() = runBlocking {
        // A full download is roughly 2,700 chunks of several kilobytes each.
        // Feeding those through the telemetry observer would burn cycles on the
        // download's own hot path for sentences that can never be navigation.
        val chunk = Nmea.frame("PMTK182,8,00000000," + "AB".repeat(64))
        val transport = ScriptedTransport(listOf(chunk + gga))
        val client = PmtkClient(transport, defaultTimeoutMillis = 200)

        val seen = mutableListOf<NmeaSentence>()
        client.onSentence = { seen += it }

        client.pump(50)

        assertEquals(listOf("GPGGA"), seen.map { it.type })
        assertEquals(1, client.bulkChunks)
    }

    @Test
    fun `an observer that throws cannot break the read path`() = runBlocking {
        // The observer runs inside a download. A bug in the telemetry decoder
        // must cost telemetry, never the transfer.
        val ack = Nmea.frame("PMTK001,182,3")
        val transport = ScriptedTransport(listOf(gga, ack))
        val client = PmtkClient(transport, defaultTimeoutMillis = 500)

        client.onSentence = { error("observer is broken") }

        val reply = client.awaitSentence(500) { it.matches("PMTK001") }
        assertEquals("PMTK001", reply.type)
    }

    @Test
    fun `the assembler folds a live stream into usable telemetry`() = runBlocking {
        // End to end at the seam the app actually wires up.
        val transport = ScriptedTransport(listOf(gga + gsa))
        val client = PmtkClient(transport, defaultTimeoutMillis = 200)

        val assembler = TelemetryAssembler()
        client.onSentence = { assembler.accept(it) }

        client.pump(50)

        assertEquals(FixType.THREE_D, assembler.current.fixType)
        assertEquals(7, assembler.current.satellitesUsed)
        assertTrue(assembler.current.hasPosition)
    }
}
