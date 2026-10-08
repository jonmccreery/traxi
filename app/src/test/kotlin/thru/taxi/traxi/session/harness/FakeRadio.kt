package thru.taxi.traxi.session.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import thru.taxi.traxi.bt.Bonding
import thru.taxi.traxi.core.format.RecordingSince
import thru.taxi.traxi.core.transport.Transport
import thru.taxi.traxi.data.MarkStore
import thru.taxi.traxi.data.MethodStore
import thru.taxi.traxi.data.RecordMethodStore
import thru.taxi.traxi.session.LinkPlatform
import java.io.IOException

/**
 * The phone's side of the radio, scripted: an ACL that comes up and goes down,
 * the `ACL_DISCONNECTED` broadcast, and the timing between the two.
 *
 * The timing is the point. On the phone, the link can be *seen* down (by
 * polling, as `AclLink.awaitDown` does) before the broadcast saying so is
 * delivered, and a deliberate close takes 10-14 s to tear down. Both gaps are
 * where the races live, so both are knobs here:
 *
 *  - [teardownMillis]: how long the ACL stays up after the session closes a
 *    link, before it reads as down.
 *  - [broadcastLagMillis]: how long after it reads as down the broadcast lands.
 *
 * [dropLink] is the logger dropping its radio on its own.
 */
class FakeRadio(
    private val scheduler: TestCoroutineScheduler,
    /** Where broadcasts are delivered from, like the phone's main thread. */
    private val events: CoroutineScope,
    private val flash: ByteArray,
    private val writePointer: Int,
) : LinkPlatform {

    var teardownMillis = 0L
    var broadcastLagMillis = 0L

    /** The next this-many opens fail, as a connect to an unreachable logger does. */
    var openFailures = 0

    /** When each failed open happened. */
    val failedOpens = mutableListOf<Long>()

    /** Off: every open fails until it is switched back on. See [switchOff]. */
    var loggerOn = true
        private set

    /** The logger is switched off: its link drops, and nothing connects. */
    fun switchOff() {
        loggerOn = false
        if (links.isNotEmpty() && current.isOpen && !current.dropped) dropLink()
    }

    fun switchOn() { loggerOn = true }

    /** Runs as a rebuild starts waiting for the old link to drop. */
    var onAwaitDown: (() -> Unit)? = null

    /** Applied to every new link, so a script can follow the session across rebuilds. */
    var onNewLink: ((FakeLoggerLink) -> Unit)? = null

    val links = mutableListOf<FakeLoggerLink>()
    val current: FakeLoggerLink get() = links.last()

    var aclUp = false
        private set
    private var onLost: (() -> Unit)? = null
    var foregroundHeld = false
        private set

    val nowMillis: Long get() = scheduler.currentTime

    /** The logger drops its radio link: reads fail, and the broadcast follows. */
    fun dropLink() {
        current.drop()
        linkWentDown()
    }

    /**
     * The radio link goes down and the broadcast follows, but the socket has
     * not noticed yet: a read in progress just waits. This is the window in
     * which the broadcast and a query still in flight race on the phone.
     */
    fun announceDown() = linkWentDown()

    /** Links the session opened and never closed, other than ones the logger dropped. */
    fun leakedLinks(): List<FakeLoggerLink> = links.filter { it.isOpen && !it.dropped }

    private fun linkWentDown() {
        if (!aclUp) return
        aclUp = false
        events.launch {
            delay(broadcastLagMillis)
            onLost?.invoke()
        }
    }

    private fun closed(link: FakeLoggerLink) {
        if (link !== links.lastOrNull() || !aclUp) return
        events.launch {
            delay(teardownMillis)
            // A newer link may have come up on the same ACL in the meantime.
            if (link === links.lastOrNull()) linkWentDown()
        }
    }

    // ---------------- LinkPlatform ----------------

    override fun nanoTime(): Long = scheduler.currentTime * 1_000_000

    override suspend fun ensureBonded(address: String): Bonding.Result = Bonding.Result.AlreadyBonded

    override fun looksStale(address: String): Boolean = false

    override fun removeBond(address: String): Boolean = true

    override fun deviceName(address: String): String = "BT-Q1000XT"

    override suspend fun awaitLinkDown(address: String, note: (String) -> Unit) {
        onAwaitDown?.invoke()
        // As AclLink.awaitDown: poll the link state, give up after 30 s.
        val until = nowMillis + 30_000
        while (aclUp && nowMillis < until) delay(100)
    }

    override fun isLinkUp(address: String): Boolean = aclUp

    override suspend fun openBluetooth(address: String, note: (String) -> Unit): Transport {
        delay(1_500)   // an RFCOMM connect is not instant, and the gap matters
        if (!loggerOn) {
            failedOpens += nowMillis
            throw IOException("read failed, socket might closed or timeout, read ret: -1")
        }
        if (openFailures > 0) {
            failedOpens += nowMillis
            openFailures--
            throw IOException("read failed, socket might closed or timeout, read ret: -1")
        }
        val link = FakeLoggerLink(address, flash, writePointer, { nowMillis }, ::closed)
        links += link
        onNewLink?.invoke(link)
        link.open()
        aclUp = true
        return link
    }

    override fun watchLink(address: String, onLost: () -> Unit) { this.onLost = onLost }

    override fun watchUsbDetach(onLost: () -> Unit) { this.onLost = onLost }

    override fun stopWatching() { onLost = null }

    override fun holdForeground(): Result<Unit> { foregroundHeld = true; return Result.success(Unit) }

    override fun releaseForeground() { foregroundHeld = false }
}

/** Per-logger marks, plus the single [legacy] mark older versions kept. */
class MemoryMarks(var legacy: RecordingSince.Mark? = null) : MarkStore {
    val marks = mutableMapOf<String, RecordingSince.Mark>()
    override fun last(deviceKey: String) = marks[deviceKey]
    override fun put(mark: RecordingSince.Mark) { marks[mark.deviceKey] = mark }
    override fun claimLegacy() = legacy.also { legacy = null }
}

class MemoryMethods(val legacy: MutableMap<String, RecordMethodStore.Reading> = mutableMapOf()) : MethodStore {
    val readings = mutableMapOf<String, RecordMethodStore.Reading>()
    override fun get(deviceKey: String) = readings[deviceKey]
    override fun put(deviceKey: String, reading: RecordMethodStore.Reading) {
        readings[deviceKey] = reading
    }
    override fun claimLegacy(legacyKey: String) = legacy.remove(legacyKey)
    override fun forget(deviceKey: String) { readings.remove(deviceKey) }
}
