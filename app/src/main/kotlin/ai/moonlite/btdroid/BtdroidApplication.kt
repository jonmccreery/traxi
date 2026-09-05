package ai.moonlite.btdroid

import ai.moonlite.btdroid.bt.CompanionPairing
import ai.moonlite.btdroid.data.DumpRepository
import ai.moonlite.btdroid.session.SessionController
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class BtdroidApplication : Application() {

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
    val session = SessionController(application, pairing, dumpRepository, applicationScope)
}
