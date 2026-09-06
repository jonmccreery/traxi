package thru.taxi.traxi.bt

import thru.taxi.traxi.core.transport.Transport
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth RFCOMM transport.
 *
 * The device must already be **bonded** before a socket can open. Unlike BLE,
 * Android offers no unpaired-connect path for Classic, which is why pairing is
 * handled up front by [CompanionPairing] rather than lazily on first use.
 *
 * Blocking socket I/O is confined to [Dispatchers.IO]. `InputStream.read` on an
 * RFCOMM socket has no timeout of its own, so [read] implements one by polling
 * what [InputStream.available] reports. See [read] for why the obvious
 * threaded alternative was tried and reverted.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked by the caller
class BluetoothSppTransport(
    private val device: BluetoothDevice,
    private val serviceUuid: UUID = SPP_UUID,
) : Transport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val description: String
        get() = "bluetooth ${device.address}"

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override suspend fun open() = withContext(Dispatchers.IO) {
        check(socket == null) { "transport already open" }

        // Discovery and RFCOMM contend for the radio, and a scan in progress
        // makes connects slow or fail outright. Android documents cancelling
        // discovery before connecting; the companion chooser may well have left
        // one running.
        runCatching { adapter?.cancelDiscovery() }

        var lastFailure: IOException? = null
        for (strategy in STRATEGIES) {
            val s = try {
                strategy.create(device, serviceUuid)
            } catch (e: Exception) {
                lastFailure = IOException("${strategy.name}: ${e.message}", e)
                continue
            } ?: continue

            try {
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return@withContext
            } catch (e: IOException) {
                runCatching { s.close() }
                lastFailure = IOException("${strategy.name}: ${e.message}", e)
                // A half-open link from a previous session -- an app killed
                // without closing its socket, which this logger does not notice
                // because it serves one SPP client -- fails here. Give the
                // stack a moment to tear the old link down before the next
                // strategy tries.
                Thread.sleep(RETRY_PAUSE_MILLIS)
            }
        }

        throw IOException(
            "RFCOMM connect to ${device.address} failed: ${lastFailure?.message}",
            lastFailure,
        )
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("transport not open")
        out.write(bytes)
        out.flush()
    }

    /**
     * Poll for buffered bytes, sleeping briefly when the device is silent.
     *
     * A blocking reader thread was tried here and **reverted**. It is the
     * better-looking design, and it bought nothing: measured against this
     * device it delivered 493 B/s, identical to this loop, because the
     * bottleneck is not the read strategy. The BT-Q1000XT bridges its Bluetooth
     * module to the GPS chip over an internal UART running at 9600 baud --
     * 986 B/s of hex on the wire, which is exactly what both implementations
     * achieve. USB talks to the chip directly and manages 64 KB/s.
     *
     * The threaded version also introduced a failure mode this one does not
     * have: a dead reader thread reported end-of-stream and aborted the
     * download. Given no upside and a new way to fail, the simpler code wins.
     *
     * If throughput ever needs to improve, the lever is not here -- it is
     * downloading less (incremental fetch from the write pointer) or a faster
     * transport (USB).
     */
    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
        withContext(Dispatchers.IO) {
            val stream = input ?: return@withContext -1
            val deadline = System.currentTimeMillis() + timeoutMillis

            while (true) {
                val available = try {
                    stream.available()
                } catch (e: IOException) {
                    return@withContext -1
                }
                if (available > 0) {
                    return@withContext try {
                        stream.read(dest, 0, minOf(dest.size, available))
                    } catch (e: IOException) {
                        -1
                    }
                }
                if (System.currentTimeMillis() >= deadline) return@withContext 0
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") 0
        }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private val adapter
        get() = android.bluetooth.BluetoothAdapter.getDefaultAdapter()

    /** One way of obtaining an RFCOMM socket, tried in order. */
    private class Strategy(
        val name: String,
        val create: (BluetoothDevice, UUID) -> BluetoothSocket?,
    )

    companion object {
        /** Standard Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val POLL_INTERVAL_MILLIS = 8L
        private const val RETRY_PAUSE_MILLIS = 1_200L

        /**
         * Connect strategies, in order of preference.
         *
         * The first is the documented path. The other two exist because this
         * class of device fails the documented path in ways that are not
         * really its fault:
         *
         *  - **insecure**: the logger uses legacy pairing, and an encrypted
         *    channel is not always negotiable even once bonded.
         *  - **channel 1 by reflection**: the long-standing Android workaround
         *    for SDP lookups that fail or return stale records. Not a guess
         *    here -- `sdptool browse` confirms this device serves SPP on
         *    channel 1.
         *
         * Trying all three costs a few seconds on failure and turns a dead end
         * into a connection, which is the right trade at a trailhead.
         */
        @SuppressLint("MissingPermission")
        private val STRATEGIES = listOf(
            Strategy("secure rfcomm") { device, uuid ->
                device.createRfcommSocketToServiceRecord(uuid)
            },
            Strategy("insecure rfcomm") { device, uuid ->
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            },
            Strategy("channel 1 fallback") { device, _ ->
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                method.invoke(device, 1) as? BluetoothSocket
            },
        )
    }
}
