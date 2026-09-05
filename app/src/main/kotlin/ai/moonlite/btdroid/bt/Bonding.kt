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
 * in entirely the wrong place. Confirmed against the hardware: an RFCOMM
 * connect from a desktop without a bond returns `Permission denied`, and the
 * identical connect after bonding succeeds in 2.0 s.
 *
 * **This logger uses legacy PIN pairing** (Bluetooth 2.0, `LegacyPairing: yes`)
 * rather than Secure Simple Pairing, so Android shows a PIN entry dialog rather
 * than a yes/no confirmation. See [KNOWN_PIN].
 */
object Bonding {

    /**
     * PIN for the Qstarz BT-Q1000XT, verified against the device.
     *
     * Recorded because a wrong guess is expensive: the device answers a bad PIN
     * with a bare `AuthenticationFailed`, indistinguishable from being out of
     * range or switched off. `1111` is a common guess for this class of
     * hardware and is **wrong** for this one.
     */
    const val KNOWN_PIN = "0000"

    sealed interface Result {
        data object AlreadyBonded : Result
        data object Bonded : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Drop a bond so the next connect re-pairs from scratch.
     *
     * Necessary because **a legacy-pairing device routinely forgets its link
     * key across a power cycle while Android keeps hers.** The phone then holds
     * a `BOND_TYPE_PERSISTENT` key the logger no longer honours, and the
     * observable state is contradictory:
     *
     * ```
     * bredr_linkkey_known:T   bredr_authenticated:F   bredr_encrypted:F
     * ```
     *
     * Because `bondState` still reads `BOND_BONDED`, [ensureBonded] short
     * circuits and no PIN is ever requested, so the connect fails with no
     * prompt and no explanation. Clearing the stale bond is what restores the
     * PIN exchange.
     *
     * `removeBond` is not public API, so this uses reflection and reports
     * failure rather than throwing; the caller falls back to asking the user to
     * forget the device in system settings.
     */
    @SuppressLint("MissingPermission")
    fun removeBond(device: BluetoothDevice): Boolean = runCatching {
        val method = device.javaClass.getMethod("removeBond")
        method.invoke(device) as? Boolean ?: false
    }.getOrDefault(false)

    /**
     * True when the phone holds a bond that the device is evidently not
     * honouring — bonded, but with no authenticated or encrypted link.
     */
    @SuppressLint("MissingPermission")
    fun looksStale(device: BluetoothDevice): Boolean =
        device.bondState == BluetoothDevice.BOND_BONDED

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
                                            Result.Failed(
                                                "Pairing failed. If a PIN was requested, " +
                                                    "this logger's is $KNOWN_PIN."
                                            )
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
                "Pairing timed out. Check the logger is powered on — it only " +
                    "advertises with power — and enter $KNOWN_PIN if asked for a PIN."
            )
        }
    }
}
