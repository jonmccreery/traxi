package ai.moonlite.btdroid.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import java.util.concurrent.Executor

/**
 * Pairing via Companion Device Manager.
 *
 * CDM presents a system-owned device picker on the app's behalf. For a single
 * known companion this is strictly better than rolling our own discovery: it
 * needs no location permission, the association survives reboots, and the
 * chooser is a familiar system UI rather than a list we have to build and
 * explain.
 *
 * The remembered association gives the MAC address back on later launches, so
 * a return trip to the trailhead is one tap rather than a rescan.
 */
class CompanionPairing(private val context: Context) {

    private val deviceManager: CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true

    /** MAC addresses of devices already associated with this app. */
    @SuppressLint("MissingPermission")
    fun associatedAddresses(): List<String> {
        val dm = deviceManager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dm.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
        } else {
            @Suppress("DEPRECATION")
            dm.associations.map { it.uppercase() }
        }
    }

    /**
     * Bonded devices whose name looks like an MTK logger.
     *
     * A convenience for the common case, not a gate: the picker still shows
     * everything, because a logger with an unexpected name is far more likely
     * than a user who wants to be told their device does not exist.
     */
    @SuppressLint("MissingPermission")
    fun likelyLoggers(): List<BluetoothDevice> {
        val bonded = bluetoothAdapter?.bondedDevices ?: return emptyList()
        return bonded.filter { device ->
            val name = device.name ?: return@filter false
            LOGGER_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        }
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList().orEmpty()

    @SuppressLint("MissingPermission")
    fun deviceFor(address: String): BluetoothDevice? =
        runCatching { bluetoothAdapter?.getRemoteDevice(address.uppercase()) }.getOrNull()

    /**
     * Launch the system device picker.
     *
     * @param onReady receives an [IntentSender] the Activity starts to show the
     *   chooser. The selected device comes back in the Activity result.
     */
    fun requestAssociation(
        executor: Executor,
        onReady: (IntentSender) -> Unit,
        onFailure: (CharSequence?) -> Unit,
    ) {
        val dm = deviceManager
        if (dm == null) {
            onFailure("Companion Device Manager unavailable on this device")
            return
        }

        // A filter with no criteria matches every discovered device, including
        // ones whose name has not resolved yet.
        //
        // This deliberately does NOT set a name pattern. `.*` looks like
        // "match everything" but is stricter: a device is only tested against
        // the pattern once it *has* a name, so anything discovered before its
        // name resolves is filtered out. For a chooser whose whole job is to
        // show the user what is nearby, silently dropping devices is the worst
        // possible failure -- it is indistinguishable from the logger being
        // switched off.
        val filter = BluetoothDeviceFilter.Builder().build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()

        // associate() can throw synchronously -- notably IllegalStateException
        // when the companion_device_setup feature is not declared, and
        // SecurityException if the association quota is exhausted. Those never
        // reach the failure callback, so catching here is what keeps a pairing
        // problem from taking the whole app down. The bonded-device list
        // remains a usable path afterwards.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dm.associate(
                    request,
                    executor,
                    object : CompanionDeviceManager.Callback() {
                        override fun onAssociationPending(intentSender: IntentSender) =
                            onReady(intentSender)

                        override fun onAssociationCreated(
                            association: android.companion.AssociationInfo,
                        ) = Unit

                        override fun onFailure(error: CharSequence?) = onFailure(error)
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                dm.associate(
                    request,
                    object : CompanionDeviceManager.Callback() {
                        @Deprecated("Required below API 33")
                        override fun onDeviceFound(intentSender: IntentSender) =
                            onReady(intentSender)

                        override fun onFailure(error: CharSequence?) = onFailure(error)
                    },
                    null,
                )
            }
        } catch (e: Exception) {
            onFailure(
                "Device chooser unavailable (${e.javaClass.simpleName}). " +
                    "Pair the logger in Android's Bluetooth settings, then pick it " +
                    "from Known devices."
            )
        }
    }

    companion object {
        private val LOGGER_NAME_HINTS = listOf(
            "HOLUX", "QSTARZ", "iBlue", "i-Blue", "GPS", "BT-Q", "747", "M-241", "Transystem",
        )
    }
}
