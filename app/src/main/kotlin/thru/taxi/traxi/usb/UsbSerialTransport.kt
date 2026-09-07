package thru.taxi.traxi.usb

import thru.taxi.traxi.core.transport.Transport
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * USB CDC-ACM transport, on `usb-serial-for-android`.
 *
 * This was originally hand-written against `UsbManager`, on the reasoning that
 * the device is plain CDC-ACM and the library's FTDI, PL2303, CP210x and CH34x
 * drivers would never run. The reasoning was correct and the decision was still
 * wrong: the hand-rolled version lost data, and four attempts at fixing it --
 * flooring the read timeout, a reader thread, a longer transfer timeout,
 * smaller requests -- each removed some symptoms without curing it.
 *
 * The losses were whole 2 KB chunks and corrupted sentence boundaries, which is
 * what happens when the host fails to keep a transfer posted while the device
 * is emitting. Getting that right across devices and Android versions is
 * exactly the decade of accumulated fixes this library carries, and none of it
 * was interesting to reproduce.
 *
 * What is worth keeping from the exercise is the shape of this device, which is
 * unusual and cost real time to establish:
 *
 *  - its interfaces are **reversed** from the normal CDC layout. `if0` is the
 *    data interface (class 0x0A, bulk 0x81 IN / 0x01 OUT) and `if1` is
 *    communications (class 0x02), so control requests belong to `if1`.
 *  - it stays mute until DTR is asserted.
 *  - it answers `PMTK182,7` in 2 KB chunks, an exact multiple of the 64-byte
 *    packet size, so a large read has no short packet to terminate it.
 */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val note: (String) -> Unit = {},
) : Transport, SerialInputOutputManager.Listener {

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null

    private val incoming = LinkedBlockingQueue<ByteArray>(4096)
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    /** Delivery-size histogram; see [recordDelivery]. */
    private val sizes = HashMap<Int, Long>()
    private var deliveries = 0L

    @Volatile private var failure: String? = null

    override val description: String
        get() = "usb ${device.productName ?: device.deviceName}"

    // USB delivers a 64 KB block in about a second (~33 ms between chunks), so
    // 2.5 s cannot end a healthy read but a failed block costs seconds instead
    // of the slow-link default. See [Transport.blockReadIdleTimeoutMillis].
    override val blockReadIdleTimeoutMillis: Long get() = 2_500

    override val isOpen: Boolean
        get() = port != null

    override suspend fun open() = withContext(Dispatchers.IO) {
        check(port == null) { "transport already open" }
        if (!usbManager.hasPermission(device)) {
            throw IOException("No USB permission for ${device.deviceName}")
        }

        // The default prober does not know 0e8d:3329, so name it explicitly and
        // fall back to the built-in table for anything else.
        val table = ProbeTable().apply {
            addProduct(VENDOR_MEDIATEK, PRODUCT_BT_Q1000XT, CdcAcmSerialDriver::class.java)
        }
        val driver = UsbSerialProber(table).probeDevice(device)
            ?: UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IOException("No serial driver matches ${device.deviceName}")

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Could not open ${device.deviceName}")

        val p = driver.ports.firstOrNull()
            ?: throw IOException("Driver exposes no ports")

        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Without DTR this device never transmits.
            runCatching { p.dtr = true }
            runCatching { p.rts = true }
        } catch (e: Exception) {
            runCatching { p.close() }
            runCatching { connection.close() }
            throw IOException("Could not configure the serial port: ${e.message}", e)
        }

        port = p
        failure = null
        incoming.clear()
        pending = null
        pendingOffset = 0
        synchronized(sizes) { sizes.clear(); deliveries = 0L }

        // Keeps a read posted continuously and delivers through onNewData.
        // This is the part the hand-rolled transport never got right.
        io = SerialInputOutputManager(p, this@UsbSerialTransport).apply {
            // Zero is not "no timeout" in the casual sense -- it selects an
            // entirely different code path in CommonUsbSerialPort.read. See
            // READ_TIMEOUT below.
            readTimeout = READ_TIMEOUT
            readBufferSize = READ_BUFFER
            start()
        }

        note("usb ${driver.javaClass.simpleName} on " +
            "${device.productName ?: device.deviceName}, $BAUD 8N1, DTR asserted")
    }

    // ---------------- SerialInputOutputManager.Listener ----------------

    override fun onNewData(data: ByteArray) {
        recordDelivery(data.size)
        if (!incoming.offer(data)) note("usb read queue full; dropped ${data.size} bytes")
    }

    /**
     * Delivery sizes are the evidence for whether a transfer ends on a short
     * packet or on a full buffer, which decides whether [READ_BUFFER] can go
     * higher. A response sentence is 4121 bytes on the wire -- 64 whole packets
     * and a 25-byte remainder -- so a stream of sizes at the buffer ceiling
     * with a small tail means transfers are terminating per sentence, and the
     * ceiling can be raised. Sizes pinned at exactly [READ_BUFFER] with no tail
     * would mean the opposite.
     */
    private fun recordDelivery(size: Int) {
        val total = synchronized(sizes) {
            sizes.merge(size, 1L, Long::plus)
            ++deliveries
        }
        if (total % DELIVERY_REPORT_EVERY == 0L) reportDeliveries()
    }

    private fun reportDeliveries() {
        val snapshot = synchronized(sizes) { sizes.toSortedMap() to deliveries }
        val (histogram, total) = snapshot
        if (total == 0L) return
        val bytes = histogram.entries.sumOf { it.key.toLong() * it.value }
        val top = histogram.entries.sortedByDescending { it.value }.take(6)
            .joinToString(" ") { "${it.key}x${it.value}" }
        note("usb reads=$total bytes=$bytes avg=${bytes / total} sizes: $top")
    }

    override fun onRunError(e: Exception) {
        failure = e.message ?: e.toString()
        note("usb read error: $failure")
    }

    // ---------------- Transport ----------------

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val p = port ?: throw IOException("transport not open")
        p.write(bytes, WRITE_TIMEOUT)
    }

    /**
     * @return bytes read, 0 on silence, or -1 once the port is gone.
     *
     * Silence is not failure. The protocol layer decides when it has gone on
     * too long, and treating a quiet moment as end-of-stream is what aborted a
     * download earlier in this project.
     */
    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
        withContext(Dispatchers.IO) {
            if (port == null) return@withContext -1

            var chunk = pending
            if (chunk == null) {
                chunk = incoming.poll(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    ?: return@withContext if (failure != null && incoming.isEmpty()) -1 else 0
                pendingOffset = 0
            }

            var written = copyFrom(chunk, dest, 0)
            while (written < dest.size) {
                val next = incoming.poll() ?: break
                pending = next
                pendingOffset = 0
                written += copyFrom(next, dest, written)
            }
            written
        }

    private fun copyFrom(chunk: ByteArray, dest: ByteArray, at: Int): Int {
        val n = minOf(dest.size - at, chunk.size - pendingOffset)
        chunk.copyInto(dest, at, pendingOffset, pendingOffset + n)
        pendingOffset += n
        pending = if (pendingOffset >= chunk.size) null else chunk
        return n
    }

    override fun close() {
        reportDeliveries()
        runCatching { io?.listener = null }
        runCatching { io?.stop() }
        runCatching { port?.close() }
        io = null
        port = null
        incoming.clear()
        pending = null
        pendingOffset = 0
    }

    companion object {
        /** MediaTek. The BT-Q1000XT reports 0e8d:3329. */
        const val VENDOR_MEDIATEK = 0x0E8D
        const val PRODUCT_BT_Q1000XT = 0x3329

        /** Nominal; a native CDC device on USB ignores it. */
        private const val BAUD = 115_200
        private const val WRITE_TIMEOUT = 5_000

        /**
         * Zero, and it must stay zero.
         *
         * `CommonUsbSerialPort.read` branches on this value:
         *
         * ```java
         * if (timeout != 0) nread = mConnection.bulkTransfer(ep, dest, len, timeout);
         * else { mUsbRequest.queue(ByteBuffer.wrap(dest, 0, len), len);
         *        mConnection.requestWait(); nread = buf.position(); }
         * ```
         *
         * `bulkTransfer` returns -1 on timeout and the bytes already received
         * into that URB are **thrown away** -- the caller cannot even find out
         * how many there were. The `UsbRequest` path has no timeout to expire
         * and so has nothing to discard. That is the whole difference.
         *
         * This also explains the earlier result where raising the transfer
         * timeout to 10 s made corruption worse: a bigger buffer waiting longer
         * simply had more accumulated data to lose when the timeout finally
         * fired.
         *
         * A blocked `requestWait` is not a leak. `stop()` only sets a flag, but
         * `close()` below calls `port.close()`, which cancels the outstanding
         * request and unblocks the manager thread.
         */
        private const val READ_TIMEOUT = 0

        /**
         * The library defaults this to the endpoint's max packet size, 64 bytes
         * here, which is a thousand round trips a second at 61 KB/s -- and
         * after each one the manager thread copies, dispatches to `onNewData`
         * and takes the write-buffer lock before posting the next read, all
         * with nothing posted on the endpoint.
         *
         * 4096 is comfortably more than enough and there is no point raising
         * it. The measured delivery histogram never exceeds 537 bytes:
         *
         * ```
         * usb reads=42000 avg=268 sizes: 1x19472 511x19465 537x2288 25x245
         * ```
         *
         * The device emits in ~512-byte units split as 1 + 511 -- the counts
         * are exactly paired from the first sample on -- and every one of those
         * ends in a short packet, which terminates the transfer regardless of
         * how much buffer is left. So transfers are bounded by the firmware's
         * write granularity, not by this number.
         *
         * That also retires the old worry that a 2048-byte payload is an exact
         * multiple of the packet size and so leaves nothing to terminate a
         * large read. Short packets arrive constantly; that was never the
         * constraint.
         *
         * Honest limitation: this landed in the same run as [READ_TIMEOUT], so
         * the two are confounded. `readTimeout` is the one with a proven
         * mechanism; this one is defensible but unmeasured on its own.
         */
        private const val READ_BUFFER = 4096

        /**
         * One line per ~2 MB at the sizes we expect, so a full read leaves a
         * handful of samples in the transcript rather than burying it.
         */
        private const val DELIVERY_REPORT_EVERY = 500L

        /** Attached devices that look like a logger. */
        fun candidates(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                device.vendorId == VENDOR_MEDIATEK || looksLikeCdc(device)
            }

        private fun looksLikeCdc(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val c = device.getInterface(i).interfaceClass
                if (c == UsbConstants.USB_CLASS_CDC_DATA || c == UsbConstants.USB_CLASS_COMM) {
                    return true
                }
            }
            return false
        }
    }
}
