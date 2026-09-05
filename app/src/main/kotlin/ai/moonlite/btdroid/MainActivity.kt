package ai.moonlite.btdroid

import ai.moonlite.btdroid.ui.AppScreen
import ai.moonlite.btdroid.ui.theme.BtdroidTheme
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as BtdroidApplication).container }

    private var pendingAddress by mutableStateOf<String?>(null)
    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Notifications are nice to have; Bluetooth is not. Only the two
        // Bluetooth permissions gate the app being usable.
        permissionsGranted = results[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            results[Manifest.permission.BLUETOOTH_SCAN] == true
    }

    /** Result of the Companion Device Manager chooser. */
    private val chooseDevice = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data: Intent = result.data ?: return@registerForActivityResult

        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_ASSOCIATION,
                    android.companion.AssociationInfo::class.java,
                )?.deviceMacAddress?.toString()?.let { container.pairing.deviceFor(it) }
            } else {
                @Suppress("DEPRECATION")
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            }

        device?.address?.let { address ->
            pendingAddress = address
            container.session.connect(address)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            BtdroidTheme {
                AppScreen(
                    container = container,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = ::requestNeededPermissions,
                    onPairDevice = ::launchDeviceChooser,
                )
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun launchDeviceChooser() {
        container.pairing.requestAssociation(
            executor = mainExecutor,
            onReady = { sender: IntentSender ->
                chooseDevice.launch(IntentSenderRequest.Builder(sender).build())
            },
            onFailure = { error ->
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        error?.toString() ?: "Could not open the device chooser",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
}
