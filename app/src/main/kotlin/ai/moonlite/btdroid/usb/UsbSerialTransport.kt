package ai.moonlite.btdroid.usb

import ai.moonlite.btdroid.core.transport.Transport
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * USB CDC-ACM transport.
 *
 * Written directly against Android's `UsbManager` rather than pulling in
 * `usb-serial-for-android`. That library is excellent and handles FTDI,
 * PL2303, CP210x and CH34x — none of which this device is. The BT-Q1000XT
 * enumerates as **native CDC-ACM** with no bridge chip at all (Linux binds it
 * as `usb-MTK_GPS_Receiver-if01` → `/dev/ttyACM0`), so the whole of the driver
 * we would use is the part written below. It is also only published on JitPack,
 * and adding a third-party repository to the build to obtain drivers we will
 * never execute is a poor trade.
 *
 * **This is worth 65x.** Measured on the same chip: USB moves the full 5.4 MB
 * flash in 83 s at 64 KB/s, where Bluetooth manages 493 B/s and takes about
 * three hours, because the logger bridges its Bluetooth module to the GPS chip
 * over an internal 9600-baud UART. USB talks to the chip directly.
 */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    /** Receives descriptor and setup detail, so a silent link can be diagnosed. */
    private val note: (String) -> Unit = {},
) : Transport {

    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var control: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null

    override val description: String
        get() = "usb ${device.productName ?: device.deviceName}"

    override val isOpen: Boolean
        get() = connection != null

    override suspend fun open() = withContext(Dispatchers.IO) {
        check(connection == null) { "transport already open" }
        if (!usbManager.hasPermission(device)) {
            throw IOException("No USB permission for ${device.deviceName}")
        }

        val conn = usbManager.openDevice(device)
            ?: throw IOException("Could not open ${device.deviceName}")

        // Report what the descriptors actually say. A CDC device that opens but
        // never speaks is almost always a wrong-interface or wrong-endpoint
        // problem, and guessing at it from a silent link wastes far more time
        // than logging it once.
        note("usb %04X:%04X, %d interface(s)"
            .format(device.vendorId, device.productId, device.interfaceCount))
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            note("  if%d class=0x%02X sub=0x%02X proto=0x%02X endpoints=%d".format(
                iface.id, iface.interfaceClass, iface.interfaceSubclass,
                iface.interfaceProtocol, iface.endpointCount))
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                note("    ep addr=0x%02X %s %s".format(
                    ep.address,
                    if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                    when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        else -> "iso"
                    }))
            }
        }

        try {
            // The data interface carries the bulk endpoints. Prefer the CDC
            // data class, but fall back to any interface offering one bulk IN
            // and one bulk OUT -- some firmware mislabels its descriptors and
            // the endpoint shape is the part that actually matters.
            val data = findInterface(UsbConstants.USB_CLASS_CDC_DATA)
                ?: findInterface(null)
                ?: throw IOException("No bulk data interface on ${device.deviceName}")

            // Claim the control interface too where present. Leaving it to the
            // kernel's own cdc-acm driver causes the claim below to fail on
            // some hosts.
            control = findInterface(UsbConstants.USB_CLASS_COMM)
            control?.let { conn.claimInterface(it, true) }

            if (!conn.claimInterface(data, true)) {
                throw IOException("Could not claim the data interface")
            }
            claimed = data

            for (i in 0 until data.endpointCount) {
                val ep = data.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) readEndpoint = ep
                else writeEndpoint = ep
            }
            if (readEndpoint == null || writeEndpoint == null) {
                throw IOException("Data interface lacks bulk endpoints")
            }

            connection = conn
            note("claimed if%d, read ep=0x%02X write ep=0x%02X".format(
                data.id, readEndpoint!!.address, writeEndpoint!!.address))
            configureLine(conn, control?.id ?: 0)
        } catch (e: Exception) {
            runCatching { conn.close() }
            connection = null
            claimed = null
            control = null
            throw e
        }
    }

    /**
     * Apply CDC-ACM line settings.
     *
     * Both requests are best-effort. A native CDC device on USB ignores the
     * baud rate entirely — the wire runs at USB speed, and 115200 is a fiction
     * inherited from the serial abstraction. What does matter is asserting DTR:
     * some firmware stays silent until the host says a terminal is present, and
     * a device that never speaks is indistinguishable from a broken cable.
     */
    private fun configureLine(conn: UsbDeviceConnection, commInterface: Int) {
        // SET_LINE_CODING: 115200 8N1
        val coding = byteArrayOf(
            0x00, 0xC2.toByte(), 0x01, 0x00,  // 115200, little endian
            0,                                 // 1 stop bit
            0,                                 // no parity
            8,                                 // 8 data bits
        )
        val codingResult = conn.controlTransfer(
            0x21, SET_LINE_CODING, 0, commInterface, coding, coding.size, CONTROL_TIMEOUT)
        // SET_CONTROL_LINE_STATE: DTR | RTS. Some firmware stays mute until a
        // host asserts DTR, so a failure here explains a silent link.
        val dtrResult = conn.controlTransfer(
            0x21, SET_CONTROL_LINE_STATE, 0x03, commInterface, null, 0, CONTROL_TIMEOUT)
        note("line coding -> $codingResult, DTR/RTS -> $dtrResult (negative means refused)")
    }

    private fun findInterface(usbClass: Int?): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (usbClass != null) {
                if (iface.interfaceClass == usbClass) {
                    if (usbClass == UsbConstants.USB_CLASS_COMM) return iface
                    if (hasBulkPair(iface)) return iface
                }
            } else if (hasBulkPair(iface)) {
                return iface
            }
        }
        return null
    }

    private fun hasBulkPair(iface: UsbInterface): Boolean {
        var input = false
        var output = false
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) input = true else output = true
        }
        return input && output
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IOException("transport not open")
        val ep = writeEndpoint ?: throw IOException("no write endpoint")
        var sent = 0
        while (sent < bytes.size) {
            val n = conn.bulkTransfer(ep, bytes, sent, bytes.size - sent, WRITE_TIMEOUT)
            if (n <= 0) throw IOException("USB write failed at byte $sent")
            sent += n
        }
    }

    /**
     * @return bytes read, or 0 on timeout.
     *
     * `bulkTransfer` reports a timeout and a genuine error identically, with a
     * negative return, so a short read cannot be distinguished from a dead
     * device here. Reporting silence rather than failure is the right default:
     * the protocol layer already decides when silence has gone on too long, and
     * treating an ordinary quiet moment as end-of-stream is what aborted a
     * Bluetooth download earlier in this project.
     */
    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
        withContext(Dispatchers.IO) {
            val conn = connection ?: return@withContext -1
            val ep = readEndpoint ?: return@withContext -1
            val n = conn.bulkTransfer(ep, dest, 0, dest.size, timeoutMillis.toInt().coerceAtLeast(1))
            if (n < 0) 0 else n
        }

    override fun close() {
        val conn = connection
        claimed?.let { runCatching { conn?.releaseInterface(it) } }
        control?.let { runCatching { conn?.releaseInterface(it) } }
        runCatching { conn?.close() }
        connection = null
        claimed = null
        control = null
        readEndpoint = null
        writeEndpoint = null
    }

    companion object {
        /** MediaTek. The BT-Q1000XT reports 0e8d:3329. */
        const val VENDOR_MEDIATEK = 0x0E8D
        const val PRODUCT_BT_Q1000XT = 0x3329

        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val CONTROL_TIMEOUT = 2_000
        private const val WRITE_TIMEOUT = 5_000

        /**
         * Attached devices that look like a logger.
         *
         * Matches the known MediaTek id first, then anything exposing a CDC
         * data interface. The broad case is deliberate: a differently-badged
         * MTK logger is far more likely than a user who wants to be told their
         * device does not exist.
         */
        fun candidates(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                (device.vendorId == VENDOR_MEDIATEK) || looksLikeCdc(device)
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
