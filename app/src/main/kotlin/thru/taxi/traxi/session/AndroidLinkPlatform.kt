package thru.taxi.traxi.session

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import thru.taxi.traxi.bt.AclLink
import thru.taxi.traxi.bt.Bonding
import thru.taxi.traxi.bt.BluetoothSppTransport
import thru.taxi.traxi.bt.CompanionPairing
import thru.taxi.traxi.core.transport.Transport
import thru.taxi.traxi.service.LinkService
import thru.taxi.traxi.usb.UsbSerialTransport

/**
 * The phone's [LinkPlatform]: the calls [SessionController] used to make
 * directly, moved here unchanged so the session can also run against a fake.
 */
class AndroidLinkPlatform(
    private val context: Context,
    private val pairing: CompanionPairing,
) : LinkPlatform {

    private var watcher: BroadcastReceiver? = null

    private fun device(address: String): BluetoothDevice =
        pairing.deviceFor(address)
            ?: throw IllegalStateException("No Bluetooth device at $address")

    override fun nanoTime(): Long = System.nanoTime()

    override suspend fun ensureBonded(address: String): Bonding.Result =
        Bonding.ensureBonded(context, device(address))

    override fun looksStale(address: String): Boolean =
        runCatching { Bonding.looksStale(device(address)) }.getOrDefault(false)

    override fun removeBond(address: String): Boolean = Bonding.removeBond(device(address))

    override fun deviceName(address: String): String? =
        runCatching { device(address).name }.getOrNull()

    override suspend fun awaitLinkDown(address: String, note: (String) -> Unit) {
        AclLink.awaitDown(context, device(address), note = note)
    }

    override fun isLinkUp(address: String): Boolean? =
        runCatching { AclLink.isConnected(device(address)) }.getOrNull()

    override suspend fun openBluetooth(address: String): Transport =
        BluetoothSppTransport(device(address)).also { it.open() }

    override fun watchLink(address: String, onLost: () -> Unit) {
        stopWatching()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val which: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (!address.equals(which?.address, ignoreCase = true)) return
                onLost()
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        watcher = receiver
    }

    override fun watchUsbDetach(onLost: () -> Unit) {
        stopWatching()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val gone: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (gone != null && gone.vendorId != UsbSerialTransport.VENDOR_MEDIATEK) return
                onLost()
            }
        }
        context.registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        watcher = receiver
    }

    override fun stopWatching() {
        watcher?.let { runCatching { context.unregisterReceiver(it) } }
        watcher = null
    }

    override fun holdForeground(): Result<Unit> = runCatching { LinkService.start(context) }

    override fun releaseForeground() {
        runCatching { LinkService.stop(context) }
    }
}
