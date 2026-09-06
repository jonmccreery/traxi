package ai.moonlite.btdroid.core.protocol

import ai.moonlite.btdroid.core.transport.SimulatedLoggerTransport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The erase sequence against a simulated logger.
 *
 * The case that earns its place here is [an erase the device acknowledges and
 * does not perform][`an erase that is acknowledged but does nothing is caught`].
 * Prep doc §0.2 clause 6 exists for exactly that, and without a simulator that
 * can produce it the verification step would be untested code guarding a
 * condition nobody has ever reproduced.
 */
class FlashEraserTest {

    /** A small image that is emphatically not all-0xFF. */
    private fun image(size: Int = 0x20000) = ByteArray(size) { (it % 251).toByte() }

    private fun clientFor(transport: SimulatedLoggerTransport): PmtkClient =
        PmtkClient(transport, defaultTimeoutMillis = 2_000)

    @Test
    fun `a successful erase reports clean, and sector 0 reads back as 0xFF`() = runBlocking {
        val transport = SimulatedLoggerTransport(image(), chunkSize = 0x400)
        transport.open()
        val client = clientFor(transport)

        val phases = mutableListOf<FlashEraser.Phase>()
        val result = FlashEraser(client).erase { phases += it }

        assertTrue(result.erased)
        assertTrue(result.verified)
        assertTrue(result.isClean)
        assertEquals(null, result.failure)
        assertTrue(result.loggingRestored)
        assertEquals(0L, result.writePointerAfter)

        assertEquals(
            listOf(
                FlashEraser.Phase.DISABLING_LOGGING,
                FlashEraser.Phase.ERASING,
                FlashEraser.Phase.VERIFYING,
                FlashEraser.Phase.RESTORING_LOGGING,
            ),
            phases,
        )
    }

    @Test
    fun `an erase that is acknowledged but does nothing is caught`() = runBlocking {
        val transport = SimulatedLoggerTransport(
            image(),
            chunkSize = 0x400,
            eraseSilentlyFails = true,
        )
        transport.open()

        val result = FlashEraser(clientFor(transport)).erase()

        // The device said yes, so we did send it and it did ack.
        assertTrue(result.erased)
        // But the read-back is what decides.
        assertFalse(result.verified)
        assertFalse(result.isClean)
        assertContains(result.failure ?: "", "still holds data")
    }

    @Test
    fun `logging is restored even when verification fails`() = runBlocking {
        val transport = SimulatedLoggerTransport(
            image(),
            chunkSize = 0x400,
            eraseSilentlyFails = true,
        )
        transport.open()

        val result = FlashEraser(clientFor(transport)).erase()

        assertFalse(result.isClean)
        assertTrue(
            result.loggingRestored,
            "a failed erase must not leave the logger silently switched off",
        )
    }

    @Test
    fun `the disable comes before the erase and the restore after it`() = runBlocking {
        val transport = SimulatedLoggerTransport(image(), chunkSize = 0x400)
        transport.open()

        FlashEraser(clientFor(transport)).erase()

        // Read the command order off the wire rather than trusting the phase
        // callback, which is only a report of what we believe we did.
        val sent = transport.received

        val disable = sent.indexOf(Pmtk.WRITE_DISABLE_LOGGING)
        val erase = sent.indexOf(Pmtk.WRITE_ERASE_FLASH)
        val enable = sent.indexOf(Pmtk.WRITE_ENABLE_LOGGING)

        assertTrue(disable >= 0, "logging was never disabled: $sent")
        assertTrue(erase > disable, "erase must follow the disable: $sent")
        assertTrue(enable > erase, "logging must be restored after the erase: $sent")
    }

    @Test
    fun `verification reads a whole block, never a sub-block probe`() = runBlocking {
        // The device ignores a 512-byte read request *and drops the link*, so a
        // cheap spot check is actively harmful here.
        val transport = SimulatedLoggerTransport(image(), chunkSize = 0x400)
        transport.open()

        FlashEraser(clientFor(transport)).erase()

        val reads = transport.received
            .filter { it.startsWith("PMTK182,7,") }
            .map { it.split(",")[3].toLong(16) }

        assertTrue(reads.isNotEmpty(), "verification never read anything back")
        assertTrue(
            reads.all { it == FlashEraser.VERIFY_LENGTH.toLong() },
            "every verification read must be a full block, got $reads",
        )
    }
}
