package ai.moonlite.btdroid.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Ensures a device is bonded before anyone tries to open an RFCOMM socket.
 *
 * This step is easy to miss and fails confusingly when it is. **Companion
 * Device Manager association is not bonding.** Associating grants the app a
 * remembered relationship with the device; it does not exchange link keys. A
 * Classic RFCOMM socket needs the link key, and Android offers no
 * unpaired-connect path for Classic the way it does for BLE.
 *
 * Connecting to an unbonded device therefore does not report "not paired". It
 * fails deep inside the socket with `read failed, socket might closed or
 * timeout, read ret: -1`, which reads like a dead device and sends you looking
 * in entirely the wrong place.
 */
object Bonding {

    sealed interface Result {
        data object AlreadyBonded : Result
        data object Bonded : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Bond with [device] if it is not bonded already, waiting for the system
     * to finish.
     *
     * Bonding may show a system PIN prompt, so the timeout is generous: the
     * user has to physically respond, sometimes by pressing a button on the
     * logger itself.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT held by the caller
    suspend fun ensureBonded(
        context: Context,
        device: BluetoothDevice,
        timeoutMillis: Long = 60_000,
    ): Result {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.AlreadyBonded

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                            val changed: BluetoothDevice? =
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            if (changed?.address != device.address) return

                            when (intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.ERROR,
                            )) {
                                BluetoothDevice.BOND_BONDED -> {
                                    runCatching { context.unregisterReceiver(this) }
                                    if (continuation.isActive) continuation.resume(Result.Bonded)
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    runCatching { context.unregisterReceiver(this) }
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            Result.Failed("Pairing was refused or failed")
                                        )
                                    }
                                }
                                // BOND_BONDING: still in progress, keep waiting.
                                else -> Unit
                            }
                        }
                    }

                    context.registerReceiver(
                        receiver,
                        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    )
                    continuation.invokeOnCancellation {
                        runCatching { context.unregisterReceiver(receiver) }
                    }

                    if (!device.createBond()) {
                        runCatching { context.unregisterReceiver(receiver) }
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.Failed(
                                    "Android refused to start pairing. The logger may be " +
                                        "switched off or out of range."
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.Failed(
                "Pairing timed out. Some loggers need a button held to become " +
                    "discoverable, and some ask for a PIN of 0000."
            )
        }
    }
}
