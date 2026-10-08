package thru.taxi.traxi.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import thru.taxi.traxi.core.format.RecordingSince
import thru.taxi.traxi.data.DumpRepository
import thru.taxi.traxi.session.harness.FakeLoggerLink
import thru.taxi.traxi.session.harness.FakeRadio
import thru.taxi.traxi.session.harness.MemoryMarks
import thru.taxi.traxi.session.harness.MemoryMethods
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The link races, as tests.
 *
 * Every hard bug in this project's link handling was two mechanisms reacting
 * to one event, and each needed Android's Bluetooth stack to happen, so none
 * could be tested and each was found in the field or by reading. This runs
 * the real [SessionController] -- download, recovery, watcher, read loop --
 * against a scripted logger ([FakeLoggerLink]) and radio ([FakeRadio]) on
 * virtual time. Each test names the event it reproduces; the ones from the
 * 2026-10-07 audit say which finding, and fail with that fix reverted.
 *
 * The flash is the first six blocks of the real CDT dump, so the parse at the
 * end of every download is a real parse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkRaceTest {

    private val address = "00:1C:88:22:15:98"
    private val block = 0x10000
    private val dataBlocks = 6

    private val flash: ByteArray by lazy {
        val golden = File(System.getProperty("traxi.dataDir") ?: "../data", "cdt_v2.bin")
        assumeTrue("golden dump not found at ${golden.absolutePath}", golden.isFile)
        val bytes = ByteArray(block * (dataBlocks + 2)) { 0xFF.toByte() }
        golden.readBytes().copyInto(bytes, 0, 0, block * dataBlocks)
        bytes
    }

    /** A session wired to the fakes, connected and streaming fixes. */
    private inner class Rig(
        val test: TestScope,
        marks: MemoryMarks = MemoryMarks(),
        methods: MemoryMethods = MemoryMethods(),
        val address: String = this@LinkRaceTest.address,
    ) {
        val dir: File = Files.createTempDirectory("traxi-race").toFile()
        val radio = FakeRadio(test.testScheduler, test.backgroundScope, flash, block * dataBlocks - 0x400)
        val session = SessionController(
            radio,
            DumpRepository(dir, dir, StandardTestDispatcher(test.testScheduler)),
            marks,
            methods,
            test.backgroundScope,
        )

        /** Every command sent, on every link, with when it was sent. */
        val sends = mutableListOf<Pair<Long, String>>()

        init {
            radio.onNewLink = { link ->
                link.onSend = { cmd ->
                    sends += radio.nowMillis to cmd
                    onSend?.invoke(link, cmd)
                }
            }
        }

        /** Write-pointer checks sent in [from, to). */
        fun pointerChecks(from: Long, to: Long): Int =
            sends.count { (t, cmd) -> cmd == "PMTK182,2,8" && t >= from && t < to }

        var onSend: ((FakeLoggerLink, String) -> Unit)? = null

        val notes: List<String> get() = session.transcript.snapshot()

        fun connect() {
            session.connect(this.address)
            advanceUntil(60_000) { session.connection.value is ConnectionState.Connected }
            assertIs<ConnectionState.Connected>(session.connection.value, "connect")
        }

        /** Start a download and run until it ends, or until [limitMillis]. */
        fun download(limitMillis: Long = 60 * 60_000L): Long {
            session.startDownload()
            advanceUntil(5_000) { session.download.value.running }
            advanceUntil(limitMillis) { !session.download.value.running }
            assertFalse(session.download.value.running, "download did not finish")
            return radio.nowMillis
        }

        fun advanceUntil(limitMillis: Long, condition: () -> Boolean) {
            val end = test.testScheduler.currentTime + limitMillis
            while (!condition() && test.testScheduler.currentTime < end) {
                test.advanceTimeBy(250)
                test.runCurrent()
            }
        }

        fun advance(millis: Long) { test.advanceTimeBy(millis); test.runCurrent() }

        fun dump(): File = File(dir, "dumps").listFiles { f -> f.name.endsWith(".bin") }!!.single()

        fun partial(): Boolean = File(dump().path + ".partial").exists()

        /** In the background: [action] [afterMillis] after the [nth] block request. */
        fun onBlockRequest(nth: Int, afterMillis: Long = 0, action: (FakeLoggerLink) -> Unit) {
            var seen = 0
            onSend = { link, cmd ->
                if (cmd.startsWith("PMTK182,7,") && ++seen == nth) {
                    test.backgroundScope.launch { delay(afterMillis); action(link) }
                }
            }
        }

        fun assertCompleteDump() {
            assertFalse(partial(), "dump still marked partial")
            val bytes = dump().readBytes()
            assertTrue(bytes.size >= block * dataBlocks, "dump is ${bytes.size} bytes")
            assertContentEquals(flash.copyOf(bytes.size), bytes, "dump differs from the flash")
        }
    }

    // ---------------- the field failures ----------------

    @Test
    fun `a drop mid-download is resumed and the dump completes`() = runTest {
        // 2026-10-07 20:28: the logger dropped its radio 4.6 s after a block request.
        val rig = Rig(this)
        rig.connect()
        rig.onBlockRequest(nth = 3, afterMillis = 4_600) { rig.radio.dropLink() }

        rig.download()

        rig.assertCompleteDump()
        assertEquals(1, rig.session.download.value.linkResets)
        assertEquals(2, rig.radio.links.size)
        assertIs<ConnectionState.Connected>(rig.session.connection.value)
        assertTrue(rig.notes.any { "link lost during the transfer" in it })
        assertTrue(rig.radio.leakedLinks().size == 1, "only the rebuilt link is open")
    }

    @Test
    fun `a silence mid-download is rebuilt and resumed`() = runTest {
        // §15.1: a block that returns nothing on all three attempts. The rebuild's
        // own close raises the disconnect broadcast, which once tore the session
        // down mid-rebuild.
        val rig = Rig(this)
        rig.connect()
        rig.onBlockRequest(nth = 2) { it.silence() }

        rig.download()

        rig.assertCompleteDump()
        assertEquals(1, rig.session.download.value.linkResets)
        assertIs<ConnectionState.Connected>(rig.session.connection.value)
    }

    @Test
    fun `a drop while idle ends the session, as it always has`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.advance(30_000)

        rig.radio.dropLink()
        rig.advance(1_000)

        assertIs<ConnectionState.Failed>(rig.session.connection.value)
        assertTrue(rig.notes.any { it.contains("-- link lost: ") })
        assertEquals(1, rig.radio.links.size, "nothing reconnects outside a transfer")
    }

    // ---------------- the 2026-10-07 audit ----------------

    @Test
    fun `audit 1 -- a late broadcast from the rebuild does not end the session`() = runTest {
        // The rebuild sees the old link down by polling; the broadcast saying so
        // lands after the new link is up and the flag was reset for it.
        val rig = Rig(this)
        rig.radio.broadcastLagMillis = 5_000
        rig.connect()
        rig.onBlockRequest(nth = 2) { it.silence() }

        rig.download()
        rig.advance(30_000)

        rig.assertCompleteDump()
        assertIs<ConnectionState.Connected>(
            rig.session.connection.value,
            "a successful transfer must not end its own session",
        )
    }

    @Test
    fun `audit 2 -- disconnecting during a rebuild leaves no link open`() = runTest {
        val rig = Rig(this)
        rig.radio.teardownMillis = 20_000          // the old link takes a while to drop
        rig.connect()
        rig.onBlockRequest(nth = 2) { it.silence() }
        rig.radio.onAwaitDown = {
            rig.radio.onAwaitDown = null
            backgroundScope.launch { delay(5_000); rig.session.disconnect() }
        }

        rig.download()
        rig.advance(60_000)

        assertEquals(ConnectionState.Disconnected, rig.session.connection.value)
        assertEquals(emptyList(), rig.radio.leakedLinks(), "a link was left open after Disconnect")
    }

    @Test
    fun `audit 3 -- no pointer check fires as a long transfer ends`() = runTest {
        // On 2026-10-07 at 21:03:32 the overdue check went out 0.3 s after a
        // 19-minute read finished -- when §15.1 found the link least able to answer.
        val rig = Rig(this)
        rig.connect()

        val ended = rig.download()
        assertTrue(ended > 10 * 60_000, "the transfer must outlast the ten-minute schedule")
        // From just before `ended`: the overdue check goes out in the same
        // instant the transfer releases the link, inside the step that saw it end.
        val from = ended - 5_000

        rig.advance(9 * 60_000)
        assertEquals(
            0, rig.pointerChecks(from, ended + 9 * 60_000),
            "a pointer check went out within nine minutes of the transfer",
        )

        rig.advance(2 * 60_000)
        assertEquals(
            1, rig.pointerChecks(from, ended + 11 * 60_000),
            "the steady check resumes ten minutes after the transfer",
        )
    }

    @Test
    fun `audit 4 -- a drop during a config refresh does not restore Connected`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.advance(5_000)
        // After the enable, the refresh reads format, interval, status, method.
        // As the status is asked the link goes: the reply never comes, and the
        // disconnect broadcast lands while the query is still waiting for it.
        // The last two reads then fail quietly, and the refresh carries on.
        rig.onSend = { link, cmd ->
            if (cmd == "PMTK182,2,7") { link.silence(); rig.radio.announceDown() }
        }

        rig.session.setLogging(true)
        rig.advance(60_000)

        assertIs<ConnectionState.Failed>(
            rig.session.connection.value,
            "the refresh put Connected back over a dead link",
        )
    }

    @Test
    fun `audit 5 -- a rebuild keeps the session's memory of the logger`() = runTest {
        val earlier = RecordingSince.Mark(
            pointer = 0x10000, atMillis = 1L, deviceKey = "BT-Q1000XT/AXN_1.30-B_1.3_C01",
        )
        val rig = Rig(this, MemoryMarks(legacy = earlier))
        rig.connect()
        assertNotNull(rig.session.sinceLastSeen.value, "connect should compare against the mark")
        rig.onBlockRequest(nth = 2) { it.silence() }

        rig.download()

        assertEquals(1, rig.session.download.value.linkResets)
        assertNotNull(rig.session.sinceLastSeen.value, "the rebuild forgot the session")
    }

    // ---------------- two loggers ----------------
    //
    // 2026-10-07: a second, identical BT-Q1000XT was bought as a backup. Both
    // report the same model and firmware, which was the key per-logger state
    // was filed under -- and the store held one mark in all.

    private val legacyKey = "BT-Q1000XT/AXN_1.30-B_1.3_C01"

    @Test
    fun `two loggers with the same firmware keep separate baselines`() = runTest {
        val marks = MemoryMarks()
        val methods = MemoryMethods()
        val first = Rig(this, marks, methods, address = "00:1C:88:22:15:98")
        first.connect()
        first.session.disconnect(); first.advance(30_000)

        // The backup: never seen before, whatever the first one wrote.
        val backup = Rig(this, marks, methods, address = "00:1C:88:AA:BB:CC")
        backup.connect()
        assertEquals(RecordingSince.Verdict.NothingToCompare, backup.session.sinceLastSeen.value)
        backup.session.disconnect(); backup.advance(30_000)

        // And the first still has its own baseline after the backup was used.
        val again = Rig(this, marks, methods, address = "00:1C:88:22:15:98")
        again.connect()
        val verdict = again.session.sinceLastSeen.value
        assertTrue(
            verdict is RecordingSince.Verdict.Wrote || verdict is RecordingSince.Verdict.WroteNothing,
            "the first logger lost its baseline to the backup: $verdict",
        )
    }

    @Test
    fun `the mark from before the update is claimed once, by the first Bluetooth connect`() = runTest {
        val old = RecordingSince.Mark(pointer = 0x10000, atMillis = 1L, deviceKey = legacyKey)
        val marks = MemoryMarks(legacy = old)
        val rig = Rig(this, marks)
        rig.connect()

        assertIs<RecordingSince.Verdict.Wrote>(rig.session.sinceLastSeen.value)
        assertEquals(null, marks.legacy, "claimed, so the backup cannot inherit it too")
        assertNotNull(marks.last("bt:$address"))
    }

    @Test
    fun `the remembered flash-full setting follows the logger, not the model`() = runTest {
        val reading = thru.taxi.traxi.data.RecordMethodStore.Reading(
            thru.taxi.traxi.core.protocol.Pmtk.RecordMethod.OVERLAP, 1L,
        )
        val methods = MemoryMethods(legacy = mutableMapOf(legacyKey to reading))
        val first = Rig(this, methods = methods)
        first.connect()
        assertEquals(reading, first.session.rememberedRecordMethod.value)
        first.session.disconnect(); first.advance(30_000)

        val backup = Rig(this, methods = methods, address = "00:1C:88:AA:BB:CC")
        backup.connect()
        assertEquals(null, backup.session.rememberedRecordMethod.value)
    }

    // ---------------- the edges of recovery ----------------

    @Test
    fun `cancelling during the wait after a drop does not reconnect`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.onBlockRequest(nth = 3, afterMillis = 4_600) { link ->
            rig.radio.dropLink()
            // Inside the 15 s settle.
            backgroundScope.launch { delay(8_000); rig.session.cancelDownload() }
        }

        rig.download()

        assertEquals(1, rig.radio.links.size, "reconnected after a cancel")
        assertIs<ConnectionState.Failed>(rig.session.connection.value)
        assertTrue(rig.partial(), "the bytes so far are kept, marked partial")
    }

    @Test
    fun `a rebuild that cannot reconnect ends the session and keeps the bytes`() = runTest {
        val rig = Rig(this)
        rig.connect()
        rig.onBlockRequest(nth = 3, afterMillis = 4_600) { rig.radio.openFailures = 1; rig.radio.dropLink() }

        rig.download()

        assertIs<ConnectionState.Failed>(rig.session.connection.value)
        assertTrue(rig.partial())
        assertEquals(emptyList(), rig.radio.leakedLinks())
    }
}
