package thru.taxi.traxi

import thru.taxi.traxi.bt.CompanionPairing
import thru.taxi.traxi.data.DumpRepository
import thru.taxi.traxi.data.RecordingMarkStore
import thru.taxi.traxi.data.TranscriptFile
import thru.taxi.traxi.usb.UsbPermission
import thru.taxi.traxi.session.SessionController
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class TraxiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download"
    }
}

/**
 * Manual dependency container.
 *
 * [SessionController] is held here, not in a ViewModel, so a download survives
 * Activity recreation. Losing 25 minutes of RFCOMM transfer to a screen
 * rotation is not an acceptable failure mode when the phone is the only
 * computer available.
 */
class AppContainer(application: Application) {

    /** Outlives any Activity; deliberately never cancelled. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dumpRepository = DumpRepository(application)
    val pairing = CompanionPairing(application)
    val usb = UsbPermission(application)
    val recordingMarks = RecordingMarkStore(application)
    val session =
        SessionController(application, pairing, dumpRepository, recordingMarks, applicationScope)

    /**
     * The on-disk mirror of the transcript.
     *
     * Attached here, at construction, so that everything the session ever
     * records is on disk -- including whatever it says on its way down. A
     * diagnostic that starts later than the thing it diagnoses is no use.
     */
    val transcriptFile = TranscriptFile(java.io.File(application.filesDir, "logs")).apply {
        attachTo(session.transcript)
        noteSessionStart("traxi ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
    }
}
