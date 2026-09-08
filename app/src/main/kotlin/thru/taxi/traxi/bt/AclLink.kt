package thru.taxi.traxi.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The radio link underneath an RFCOMM socket, and why it has to be gone before
 * reconnecting.
 *
 * Closing a Bluetooth socket does not drop the ACL link. Android keeps it up
 * for some seconds afterwards and **reuses it** for the next connection, which
 * is normally a courtesy -- it makes a reconnect fast -- and against this logger
 * is a trap.
 *
 * The reason is §15: this device's command handling wedges under sustained
 * traffic, and the wedge lives in the *session*, not in the device. A genuinely
 * new ACL clears it -- proven on 2026-09-07, when a fresh link answered in
 * 146 ms after twelve minutes of silence on the old one. Reconnecting inside
 * the reuse window inherits the same wedged session instead, so the app opens a
 * socket, watches NMEA stream normally, and gets no answer to a single command.
 *
 * That is exactly what happened at 23:58 that night: a reconnect four seconds
 * after the disconnect landed on the old ACL, which did not actually go down
 * until ten seconds later. The app read the silence as a stale link key and
 * deleted a working bond.
 *
 * So: wait for the old link to die before building a new one. It costs seconds
 * and it is the difference between a reliable reset and a coin flip.
 */
object AclLink {

    /**
     * Whether Android currently holds a radio link to [device].
     *
     * `isConnected` is a hidden API, so a failure to reach it is reported as
     * "unknown" rather than guessed at -- the caller falls back to a settle
     * delay instead of concluding the link is down.
     */
    @SuppressLint("PrivateApi")
    fun isConnected(device: BluetoothDevice): Boolean? = runCatching {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as Boolean
    }.getOrNull()

    /**
     * Suspend until the radio link to [device] is down, or [timeoutMillis] passes.
     *
     * Returns true if the link is known to be down. A false return is not a
     * failure to act on -- the caller should carry on and connect anyway, having
     * said so -- because refusing to connect over a link that merely refused to
     * drop would turn a slow reconnect into no reconnect at all.
     */
    suspend fun awaitDown(
        context: Context,
        device: BluetoothDevice,
        timeoutMillis: Long = 12_000,
        note: (String) -> Unit = {},
    ): Boolean {
        when (isConnected(device)) {
            false -> return true
            null -> {
                // Cannot see the link state on this platform. Give the stack a
                // moment rather than racing it blind.
                note("cannot read ACL state; pausing ${SETTLE_MILLIS}ms before reconnecting")
                kotlinx.coroutines.delay(SETTLE_MILLIS)
                return false
            }
            true -> Unit
        }

        note("waiting for the old radio link to drop before reconnecting")
        val down = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val which: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device.address.equals(which?.address, ignoreCase = true)) {
                    down.complete(Unit)
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
        )
        return try {
            // Poll alongside the broadcast: the link can go down between the
            // check above and the receiver being registered, and that race
            // would otherwise cost the whole timeout.
            val result = withTimeoutOrNull(timeoutMillis) {
                while (isConnected(device) == true && !down.isCompleted) {
                    kotlinx.coroutines.delay(POLL_MILLIS)
                }
                true
            }
            if (result == true) {
                note("the old radio link is down")
                true
            } else {
                note(
                    "the old radio link did not drop within ${timeoutMillis}ms; " +
                        "connecting anyway, but it may still be wedged"
                )
                false
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Pause used when the link state cannot be read at all. */
    private const val SETTLE_MILLIS = 2_500L
    private const val POLL_MILLIS = 250L
}
