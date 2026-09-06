package ai.moonlite.btdroid.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * One-time USB device permission.
 *
 * Markedly simpler than the Bluetooth path, and that difference is the reason
 * USB is worth having. There is no runtime permission to request, no bonding,
 * no PIN, and no link key to go stale across a power cycle — just a single
 * system dialog per device, which Android can be told to remember.
 */
class UsbPermission(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(UsbManager::class.java)

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) == true

    /**
     * Ask for permission, calling [onResult] when the user answers.
     *
     * The receiver is registered per request and torn down on the way out, so a
     * declined dialog cannot leave a listener behind.
     */
    fun request(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val manager = usbManager
        if (manager == null) {
            onResult(false)
            return
        }
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED,
        )

        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(device, pending)
    }

    fun candidates(): List<UsbDevice> =
        usbManager?.let { UsbSerialTransport.candidates(it) }.orEmpty()

    fun manager(): UsbManager? = usbManager

    companion object {
        private const val ACTION_USB_PERMISSION = "ai.moonlite.btdroid.USB_PERMISSION"
    }
}
