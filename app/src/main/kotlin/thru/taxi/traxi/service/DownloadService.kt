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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps a full-flash download alive in the foreground.
 *
 * A 5.4 MB dump is 10.8 MB of hex on the wire and takes 20-25 minutes at
 * 115200 baud. Android will not let that run in the background without a
 * declared foreground service of type `connectedDevice`, and the phone will
 * spend most of those minutes in a pocket with the screen off.
 *
 * The service does not own the download. [thru.taxi.traxi.session.SessionController]
 * does, in the Application scope; this only holds the process up and shows
 * progress. That split means an Activity restart mid-transfer changes nothing.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                container().session.cancelDownload()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat(buildNotification("Starting…", 0, indeterminate = true))

        val resumePath = intent?.getStringExtra(EXTRA_RESUME_PATH)
        val extendPath = intent?.getStringExtra(EXTRA_EXTEND_PATH)
        val session = container().session

        scope.launch {
            launch {
                session.download.collectLatest { state ->
                    if (!state.running) return@collectLatest
                    val kb = state.bytesDownloaded / 1024
                    val text = buildString {
                        append("$kb KB · ${state.sectorsFetched} sectors fetched")
                        if (state.retries > 0) append(" · ${state.retries} retries")
                    }
                    // No trustworthy total exists to compute a percentage from,
                    // so progress is reported as work done, not as a fraction.
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NOTIFICATION_ID, buildNotification(text, kb, indeterminate = true))
                }
            }

            if (extendPath != null) {
                session.startIncrementalDownload(File(extendPath)) { stopSelf() }
            } else {
                session.startDownload(
                    resumeFile = resumePath?.let(::File),
                    onFinished = { stopSelf() },
                )
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Order matters. The progress collector posts with notify(), and an
        // emission dispatched after stopSelf() would re-post the notification
        // as an orphan no longer owned by the service -- ongoing, unswipeable,
        // and cleared by nothing short of killing the app. Cancel the collector
        // first so no post-mortem notify can run (both are on Main, so there is
        // no interleaving), then remove the notification it may already have
        // re-posted.
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun container() =
        (applicationContext as TraxiApplication).container

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

    private fun buildNotification(
        text: String,
        progressKb: Int,
        indeterminate: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, TraxiApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(0, progressKb, indeterminate)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "thru.taxi.traxi.CANCEL_DOWNLOAD"
        private const val EXTRA_RESUME_PATH = "resume_path"
        private const val EXTRA_EXTEND_PATH = "extend_path"

        fun start(context: Context, resumeFile: File? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                resumeFile?.let { putExtra(EXTRA_RESUME_PATH, it.path) }
            }
            context.startForegroundService(intent)
        }

        /** Fetch only what the logger has added since [source] was taken. */
        fun startIncremental(context: Context, source: File) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .putExtra(EXTRA_EXTEND_PATH, source.path)
            )
        }
    }
}
