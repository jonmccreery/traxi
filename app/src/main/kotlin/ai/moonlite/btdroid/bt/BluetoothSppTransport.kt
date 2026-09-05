package ai.moonlite.btdroid.bt

import ai.moonlite.btdroid.core.transport.Transport
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
 * RFCOMM socket has no timeout of its own, so [read] implements one by only
 * reading what [InputStream.available] reports, and sleeping briefly when the
 * device is silent. That keeps a stalled device from wedging the download
 * coroutine forever, which matters when the alternative is a hung app on a
 * phone with no other computer to fall back on.
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
        val s = device.createRfcommSocketToServiceRecord(serviceUuid)
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            throw IOException("RFCOMM connect to ${device.address} failed: ${e.message}", e)
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("transport not open")
        out.write(bytes)
        out.flush()
    }

    override suspend fun read(dest: ByteArray, timeoutMillis: Long): Int =
        withContext(Dispatchers.IO) {
            val stream = input ?: return@withContext -1
            val deadline = System.currentTimeMillis() + timeoutMillis

            while (true) {
                val available = stream.available()
                if (available > 0) {
                    return@withContext stream.read(dest, 0, minOf(dest.size, available))
                }
                if (System.currentTimeMillis() >= deadline) return@withContext 0
                // Short enough to stay responsive, long enough not to spin a
                // core for 25 minutes during a full dump.
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

    companion object {
        /** Standard Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val POLL_INTERVAL_MILLIS = 8L
    }
}
