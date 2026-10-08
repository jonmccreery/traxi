package thru.taxi.traxi.session

import thru.taxi.traxi.bt.Bonding
import thru.taxi.traxi.core.transport.Transport

/**
 * Everything [SessionController] asks of the phone's radio and operating
 * system, behind one seam.
 *
 * It exists for the tests. Every hard bug in this project's link handling has
 * been two mechanisms reacting to the same event -- the ACL watcher and the
 * download recovery, the read loop and a transfer, a disconnect and a rebuild
 * -- and none of them could be reached by a unit test, because each needed
 * Android's real Bluetooth stack to happen at all. With this interface the
 * session runs on the JVM against a scripted logger and a scripted radio, on
 * virtual time, so "the logger drops 4.6 s after a block request" or "the
 * disconnect broadcast lands after the rebuild" is a test that runs on every
 * build instead of a field failure weeks later. See `LinkRaceTest`.
 *
 * [AndroidLinkPlatform] is the real one, and holds exactly the calls the
 * session used to make directly. Adding behaviour here rather than there is
 * the thing to avoid: the point is that the fake and the phone differ only in
 * where the events come from.
 */
interface LinkPlatform {

    /** Monotonic time, for every interval the session measures. */
    fun nanoTime(): Long

    /** Bond with [address] if needed. Throws if there is no such device. */
    suspend fun ensureBonded(address: String): Bonding.Result

    /** Whether a failed connect to a bonded [address] looks like a stale key. */
    fun looksStale(address: String): Boolean

    /** Clear the pairing with [address]. Only ever at the user's request. */
    fun removeBond(address: String): Boolean

    fun deviceName(address: String): String?

    /**
     * Wait until the radio link to [address] is down, or give up after a
     * timeout. A socket opened while the old link lingers inherits it (§15.1).
     */
    suspend fun awaitLinkDown(address: String, note: (String) -> Unit)

    /** Whether the radio link to [address] is up; null if it cannot be told. */
    fun isLinkUp(address: String): Boolean?

    /**
     * Open an RFCOMM transport to [address]; throws if it cannot. [note] hears
     * each connect strategy as it is tried, and how it failed.
     */
    suspend fun openBluetooth(address: String, note: (String) -> Unit = {}): Transport

    /** Call [onLost] whenever the radio link to [address] drops. */
    fun watchLink(address: String, onLost: () -> Unit)

    /** Call [onLost] if the logger's USB cable is pulled. */
    fun watchUsbDetach(onLost: () -> Unit)

    /** Stop whichever watch is active. */
    fun stopWatching()

    /**
     * Keep the process alive while a session is up (the foreground service).
     * A failure is returned, not thrown: it is never fatal, but it is noted.
     */
    fun holdForeground(): Result<Unit>

    fun releaseForeground()
}
