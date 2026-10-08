package thru.taxi.traxi.session.harness

import kotlinx.coroutines.delay
import thru.taxi.traxi.core.protocol.Nmea
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import java.io.IOException

/**
 * One Bluetooth connection to a scripted BT-Q1000XT.
 *
 * The protocol is [SimulatedLoggerTransport]'s; this adds what the real link
 * does that the simulator does not, because those are the things the races
 * are made of:
 *
 *  - **time.** Bytes arrive at a wire rate ([wireBytesPerSecond]), so a 64 KB
 *    block takes about two minutes of virtual time, and a read that finds
 *    nothing waits rather than spinning.
 *  - **a GPS stream**, one fix a second, so the read loop sees a live,
 *    fixed logger -- which is what arms its pointer checks.
 *  - **the two failures.** [silence]: commands go unanswered while NMEA keeps
 *    flowing, the §15 wedge. [drop]: the radio link is gone and reads fail --
 *    but [isOpen] stays true, as `BluetoothSocket.isConnected` does after a
 *    remote drop, because code that trusted it was one of the bugs.
 *
 * [onSend] sees every command the session sends, in order, so a test can
 * script "drop 4.6 s after the second block request".
 */
class FakeLoggerLink(
    address: String,
    flash: ByteArray,
    writePointer: Int,
    private val nowMillis: () -> Long,
    private val onClosed: (FakeLoggerLink) -> Unit,
    private val wireBytesPerSecond: Int = 1_000,
) : Transport {

    private val logger = SimulatedLoggerTransport(
        flash = flash,
        writePointer = writePointer,
        logFormatHex = "000A1C3F",
        chunkSize = 0x800,
    )

    override val description: String = "bluetooth $address"

    // The real Bluetooth cadence, not the simulator's lively one.
    override val writePointerProbeIntervalMillis: Long get() = 600_000

    private var open = false
    var dropped = false
        private set
    var silent = false
        private set
    var closedBySession = false
        private set

    /** Every command sent, as `PMTK182,2,8`, in order -- including unanswered ones. */
    val sent = mutableListOf<String>()

    var onSend: ((String) -> Unit)? = null

    private var nextNmeaAt = 0L
    private var pendingNmea = ByteArray(0)

    override val isOpen: Boolean get() = open

    override suspend fun open() {
        logger.open()
        open = true
        nextNmeaAt = nowMillis()
    }

    override fun close() {
        if (!open) return
        open = false
        closedBySession = true
        logger.close()
        onClosed(this)
    }

    /** The radio link is gone. Reads fail from now on; [isOpen] does not notice. */
    fun drop() { dropped = true }

    /** Commands go unanswered from now on; the GPS stream carries on. */
    fun silence() { silent = true }

    override suspend fun write(bytes: ByteArray) {
        if (!open || dropped) throw IOException("Broken pipe")
        val line = String(bytes, Charsets.US_ASCII).trim()
        val payload = line.removePrefix("$").substringBefore('*')
        sent += payload
        if (!silent) logger.write(bytes)
        onSend?.invoke(payload)
    }

    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int {
        if (!open || dropped) return -1
        // Command replies first, paced at the wire rate.
        val reply = if (silent) 0 else readReply(dest)
        if (reply > 0) {
            delay(maxOf(1L, reply * 1000L / wireBytesPerSecond))
            if (dropped) return -1
            return reply
        }
        // Then the GPS stream, once a second.
        if (pendingNmea.isEmpty() && nowMillis() >= nextNmeaAt) {
            pendingNmea = fixSentences()
            nextNmeaAt += 1_000
        }
        if (pendingNmea.isNotEmpty()) {
            val n = minOf(dest.size, pendingNmea.size)
            pendingNmea.copyInto(dest, 0, 0, n)
            pendingNmea = pendingNmea.copyOfRange(n, pendingNmea.size)
            return n
        }
        // Nothing to say: wait, the way a socket read with a timeout does.
        delay(minOf(timeoutMillis.coerceAtLeast(1), maxOf(1L, nextNmeaAt - nowMillis())))
        return if (dropped || !open) -1 else 0
    }

    private suspend fun readReply(dest: ByteArray): Int {
        val buf = ByteArray(minOf(dest.size, 2048))
        val n = logger.read(buf, 0)
        if (n > 0) buf.copyInto(dest, 0, 0, n)
        return n
    }

    private fun fixSentences(): ByteArray = (
        Nmea.frame("GPGGA,005639.000,4153.0178,N,08748.2120,W,2,8,1.13,196.1,M,-34.0,M,0000,0000") +
            Nmea.frame("GPRMC,005639.000,A,4153.0178,N,08748.2120,W,0.03,177.06,220207,,,D")
        ).toByteArray(Charsets.US_ASCII)
}
