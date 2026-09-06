package ai.moonlite.btdroid.usb

import ai.moonlite.btdroid.core.transport.Transport
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

    @Volatile private var failure: String? = null

    override val description: String
        get() = "usb ${device.productName ?: device.deviceName}"

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

        // Keeps a read posted continuously and delivers through onNewData.
        // This is the part the hand-rolled transport never got right.
        io = SerialInputOutputManager(p, this@UsbSerialTransport).apply {
            readTimeout = READ_TIMEOUT
            start()
        }

        note("usb ${driver.javaClass.simpleName} on " +
            "${device.productName ?: device.deviceName}, $BAUD 8N1, DTR asserted")
    }

    // ---------------- SerialInputOutputManager.Listener ----------------

    override fun onNewData(data: ByteArray) {
        if (!incoming.offer(data)) note("usb read queue full; dropped ${data.size} bytes")
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
        private const val READ_TIMEOUT = 200

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
