package thru.taxi.traxi.service

import thru.taxi.traxi.TraxiApplication
import thru.taxi.traxi.MainActivity
import thru.taxi.traxi.R
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import thru.taxi.traxi.session.ConnectionState

/**
 * Holds the process up for as long as a logger is connected.
 *
 * [DownloadService] already did this for transfers, on the reasoning that a
 * download is the long-running thing. A ride disproved that: an idle-connected
 * session is just as long-running and had no protection at all. Between user
 * actions the app was an ordinary background process, and Samsung's cached-app
 * freezer suspends those within roughly ten minutes of leaving the foreground
 * -- which is exactly how long the link survived, every time.
 *
 * What that does to a Bluetooth session is quiet and total. The read loop stops
 * being scheduled, the socket's receive buffer fills, RFCOMM flow control tells
 * the logger to stop sending, and the link stays *up* the whole time. Nothing
 * disconnects, so nothing is reported; the UI simply stops moving. The device
 * kept recording perfectly throughout, which is what made the failure look like
 * a mystery rather than a suspended process.
 *
 * The service owns nothing. [thru.taxi.traxi.session.SessionController] holds
 * the session in the Application scope; this only tells Android the work is
 * user-visible and ongoing.
 */
class LinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = container().session
        startForegroundCompat(buildNotification("Connected"))

        // The notification carries the recording state, not just the fact of a
        // connection, because on the trail it is the only part of this app the
        // user can see without stopping, unlocking and navigating. "Connected
        // to BT-Q1000XT" is the reassuring half of the sentence and the half
        // that has never been in doubt.
        //
        // What it reports is deliberately *weaker* than the Device tab's
        // verdict: only whether a pointer probe has ever confirmed a write, and
        // how long ago. The tab's four-way reading depends on fix recency and
        // on whether the window was long enough to judge, and re-deriving that
        // here would be the same logic in two places, free to drift apart.
        // Strictly weaker cannot contradict it.
        scope.launch {
            combine(session.connection, session.recording) { state, recording ->
                state to recording
            }.collect { (state, recording) ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val name = state.info.displayModel
                        val text = if (recording.confirmedEver) {
                            val agoSeconds =
                                (System.nanoTime() - recording.lastAdvanceAtNanos) / 1_000_000_000L
                            "Recording confirmed ${describeAgo(agoSeconds)} · $name"
                        } else {
                            "Recording not yet confirmed · $name"
                        }
                        NotificationManagerCompat.from(this@LinkService)
                            .notify(NOTIFICATION_ID, buildNotification(text))
                    }
                    // The session is over: stop holding the process up. Doing
                    // this from the connection flow rather than from each
                    // caller means every way a session can end -- a tap, a lost
                    // link, a failure -- retires the notification.
                    else -> stopSelf()
                }
            }
        }

        // START_STICKY: if Android kills the process anyway, restarting the
        // service reattaches this notification to a session that may still be
        // alive in the Application scope.
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancel the collector before clearing the notification, so a late
        // emission cannot re-post it as an orphan the user cannot swipe away.
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun container() = (applicationContext as TraxiApplication).container

    /**
     * Age of the last confirmed write, for a line read at a glance.
     *
     * Coarse on purpose: the number is only ever as fresh as the probe interval,
     * which over Bluetooth is a minute, so second-level precision would imply a
     * liveness the reading does not have.
     */
    private fun describeAgo(seconds: Long): String = when {
        seconds < 90 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} h ago"
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TraxiApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.link_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
