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
import thru.taxi.traxi.data.DumpRepository
import thru.taxi.traxi.session.harness.FakeLoggerLink
import thru.taxi.traxi.session.harness.FakeRadio
import thru.taxi.traxi.session.harness.MemoryMarks
import thru.taxi.traxi.session.harness.MemoryMethods
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.fail

/**
 * The dynamic counterpart to the 2026-10-07 audit.
 *
 * [LinkRaceTest] pins races someone already thought of. This looks for the
 * ones nobody did: each seed is a session of random user actions (connect,
 * download, fetch-new, Start logging, read a setting, cancel, disconnect) and
 * random logger faults (drops, silences, the link going down under a waiting
 * read, failed reconnects, the logger switched off), on random radio timing.
 *
 * Random times alone almost never hit a race: the windows are seconds wide and
 * a session is an hour long. The first version of this test, with events
 * spread evenly, passed 2,000 seeds with four of the audit's fixes reverted --
 * it could not see any of them. So events come in **bursts**, faults can be
 * **armed on a protocol command** (fire 0-6 s after the next block request,
 * status query, pointer check, enable or setting read), and a user action can
 * be **armed on a rebuild** (fire while it waits for the old link). With those,
 * each reverted fix is found -- that is the check this test is held to.
 *
 * Properties, checked every simulated second and at the end:
 *
 *  1. never two live links at once;
 *  2. no live link left behind once the session is over;
 *  3. no "Connected" over a radio link that is down, beyond a short grace;
 *  4. nothing stuck in Connecting;
 *  5. pointer-check attempts never closer than the schedule allows (a check
 *     and its retries, re-sent within 10 s, are one attempt);
 *  6. every saved dump matches the flash outside its recorded holes, and one
 *     not marked partial or damaged covers all the data;
 *  7. no transfer still running after the faults stop;
 *  8. the session never fails without a fault or a failed connect in the
 *     minute before;
 *  9. a single drop or silence during a transfer -- no second fault, the
 *     logger reachable for the next three minutes, nobody cancelling --
 *     leaves the session connected or the dump whole. (Back-to-back faults
 *     may legitimately end it: recovery stops when a rebuild gains no whole
 *     block, by design; a link already silent when the transfer starts
 *     counts as a fault at that moment.)
 *
 * Reproducible: a failure names its seed and prints the event log. Run more
 * with `-Dtraxi.explore.seeds=2000` (default 40, a few seconds).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkExplorationTest {

    private val address = "00:1C:88:22:15:98"
    private val block = 0x10000
    private val dataBlocks = 4

    private val flash: ByteArray by lazy {
        val golden = File(System.getProperty("traxi.dataDir") ?: "../data", "cdt_v2.bin")
        assumeTrue("golden dump not found at ${golden.absolutePath}", golden.isFile)
        val bytes = ByteArray(block * (dataBlocks + 2)) { 0xFF.toByte() }
        golden.readBytes().copyInto(bytes, 0, 0, block * dataBlocks)
        bytes
    }

    private var retried = 0
    private var attemptsSeen = 0

    private class Violation(val seed: Int, val atMillis: Long, val what: String, val log: List<String>)

    @Test
    fun `random sessions keep the link invariants`() {
        val seeds = (System.getProperty("traxi.explore.seeds") ?: "40").toInt()
        val first = (System.getProperty("traxi.explore.first") ?: "1").toInt()
        val failures = mutableListOf<Violation>()
        for (seed in first until first + seeds) {
            runTest(timeout = kotlin.time.Duration.parse("10m")) { explore(seed)?.let { failures += it } }
        }
        println("EXPLORE seeds=$seeds failed=${failures.size} pointer-check attempts=$attemptsSeen, re-sent=$retried")
        if (failures.isNotEmpty()) {
            fail(buildString {
                appendLine("${failures.size} of $seeds seeds broke a property:")
                for ((what, group) in failures.groupBy { it.what.substringBefore(" (") }) {
                    appendLine("  ${group.size} x $what  (seeds ${group.take(8).joinToString { "${it.seed}" }})")
                }
                for (v in failures.distinctBy { it.what.substringBefore(" (") }.take(4)) {
                    appendLine()
                    appendLine("seed ${v.seed} at ${v.atMillis / 1000.0} s: ${v.what}")
                    v.log.takeLast(25).forEach { appendLine("    $it") }
                }
                appendLine()
                append("Replay one with -Dtraxi.explore.first=<seed> -Dtraxi.explore.seeds=1")
            })
        }
    }

    private val triggers = listOf("PMTK182,7,", "PMTK182,2,7", "PMTK182,2,8", "PMTK182,4", "PMTK182,2,6", "PMTK182,2,2")

    private suspend fun TestScope.explore(seed: Int): Violation? {
        val rnd = Random(seed)
        val dir = Files.createTempDirectory("traxi-explore").toFile()
        val radio = FakeRadio(testScheduler, backgroundScope, flash, block * dataBlocks - 0x400)
        radio.teardownMillis = if (rnd.nextBoolean()) 0 else rnd.nextLong(15_000)
        radio.broadcastLagMillis = if (rnd.nextBoolean()) 0 else rnd.nextLong(8_000)
        val dumps = DumpRepository(dir, dir, StandardTestDispatcher(testScheduler))
        val session = SessionController(radio, dumps, MemoryMarks(), MemoryMethods(), backgroundScope)

        val log = mutableListOf("teardown=${radio.teardownMillis} ms, broadcast lag=${radio.broadcastLagMillis} ms")
        fun note(s: String) { log += "%7.1f s %s".format(radio.nowMillis / 1000.0, s) }

        fun state() = session.connection.value
        fun connected() = state() is ConnectionState.Connected
        fun downloading() = session.download.value.running
        fun live() = radio.links.filter { it.isOpen && !it.dropped }
        fun latestDump(complete: Boolean) = File(dir, "dumps").listFiles { f -> f.name.endsWith(".bin") }
            ?.filter { !complete || !File(it.path + ".partial").exists() }
            ?.maxByOrNull { it.lastModified() }

        var lastFaultAt = -1L
        var userEndedAt = -1L
        // Property 9: a fault during a transfer that ought to be recovered.
        var recoverableSince = -1L

        fun fault(kind: Int) {
            if (live().isEmpty()) return
            val prior = lastFaultAt
            lastFaultAt = radio.nowMillis
            // A fault within five minutes of another is back-to-back: not held
            // to property 9.
            if (downloading() && recoverableSince < 0 && (prior < 0 || radio.nowMillis - prior > 5 * 60_000)) {
                recoverableSince = radio.nowMillis
            }
            when (kind) {
                0 -> { radio.dropLink(); note("logger drops the link") }
                1 -> { radio.current.silence(); note("logger goes silent") }
                else -> { radio.current.silence(); radio.announceDown(); note("link goes down under a waiting read") }
            }
        }

        // Faults armed on a command: fire some seconds after the next match.
        var armed: Pair<String, Pair<Int, Long>>? = null
        val checks = mutableMapOf<FakeLoggerLink, MutableList<Long>>()
        radio.onNewLink = { link ->
            note("link ${radio.links.size} opened")
            link.onSend = { cmd ->
                if (cmd == "PMTK182,2,8") checks.getOrPut(link) { mutableListOf() } += radio.nowMillis
                val a = armed
                if (a != null && cmd.startsWith(a.first)) {
                    armed = null
                    val (kind, after) = a.second
                    note("armed fault fires in ${after / 1000.0} s after $cmd")
                    backgroundScope.launch { delay(after); fault(kind) }
                }
            }
        }

        // Bursty timing: half the events follow the last within 20 s.
        val horizon = 45 * 60_000L
        val events = mutableListOf<Pair<Long, Int>>()
        var at = rnd.nextLong(60_000)
        while (at < horizon && events.size < 40) {
            events += at to rnd.nextInt(100)
            at += if (rnd.nextBoolean()) rnd.nextLong(20_000) else rnd.nextLong(8 * 60_000)
        }
        var next = 0
        var offUntil = Long.MAX_VALUE

        var leakSince = -1L
        var zombieSince = -1L
        var connectingSince = -1L
        var wasFailed = false

        fun violation(what: String): Violation {
            if (System.getProperty("traxi.explore.seeds") == "1") {
                println("TRANSCRIPT seed $seed")
                session.transcript.snapshot().filterNot { "<< \$G" in it }.takeLast(60).forEach { println("T  $it") }
            }
            return Violation(seed, radio.nowMillis, what, log)
        }

        session.connect(address)
        note("connect")

        val end = horizon + 25 * 60_000L
        while (testScheduler.currentTime < end) {
            advanceTimeBy(1_000); runCurrent()
            val now = radio.nowMillis

            if (now >= offUntil) { radio.switchOn(); offUntil = Long.MAX_VALUE; note("logger back on") }

            while (next < events.size && events[next].first <= now) {
                val roll = events[next++].second
                when {
                    // ---- the user ----
                    roll < 8 && !connected() && state() !is ConnectionState.Connecting -> {
                        session.connect(address); note("connect")
                    }
                    roll < 16 && connected() && !downloading() -> {
                        val resume = latestDump(complete = false)?.takeIf { File(it.path + ".partial").exists() }
                        session.startDownload(resume); note("download${if (resume != null) " (resume)" else ""}")
                        // A silence lasts until a rebuild clears it, so a
                        // transfer started on a silent link meets it now.
                        if (live().any { it.silent }) lastFaultAt = now
                    }
                    roll < 21 && connected() && !downloading() && latestDump(complete = true) != null -> {
                        session.startIncrementalDownload(latestDump(complete = true)!!); note("fetch new")
                        if (live().any { it.silent }) lastFaultAt = now
                    }
                    roll < 27 && connected() && !downloading() -> { session.setLogging(true); note("start logging") }
                    roll < 31 && connected() && !downloading() -> { session.readRecordMethod(); note("read flash-full setting") }
                    roll < 35 && downloading() -> {
                        session.cancelDownload(); userEndedAt = now; note("cancel")
                    }
                    // Only what the UI offers: there is no Disconnect while Connecting.
                    roll < 40 && connected() -> {
                        session.disconnect(); userEndedAt = now; note("disconnect")
                    }
                    roll < 45 -> {
                        val action = rnd.nextInt(2)
                        val after = rnd.nextLong(20_000)
                        radio.onAwaitDown = onAwait@{
                            // A rebuild, not a connect: the UI offers nothing while Connecting.
                            if (!downloading()) return@onAwait
                            radio.onAwaitDown = null
                            note("rebuild waiting; ${if (action == 0) "disconnect" else "cancel"} in ${after / 1000.0} s")
                            backgroundScope.launch {
                                delay(after)
                                userEndedAt = radio.nowMillis
                                if (action == 0) session.disconnect() else session.cancelDownload()
                            }
                        }
                        note("armed a user action on the next rebuild")
                    }
                    // ---- the logger and the radio ----
                    roll < 60 -> {
                        val cmd = triggers[rnd.nextInt(triggers.size)]
                        armed = cmd to (rnd.nextInt(3) to rnd.nextLong(6_000))
                        note("armed a fault on the next $cmd")
                    }
                    roll < 68 -> fault(0)
                    roll < 74 -> fault(1)
                    roll < 78 -> fault(2)
                    roll < 88 -> {
                        radio.openFailures = 1 + rnd.nextInt(3)
                        lastFaultAt = now
                        note("next ${radio.openFailures} connects fail")
                    }
                    roll < 94 && radio.loggerOn -> {
                        radio.switchOff(); lastFaultAt = now
                        offUntil = now + rnd.nextLong(30_000, 10 * 60_000)
                        note("logger switched off until ${offUntil / 1000} s")
                    }
                    else -> Unit
                }
            }
            if (now >= horizon) {
                radio.openFailures = 0; armed = null; radio.onAwaitDown = null
                if (!radio.loggerOn) radio.switchOn()
            }

            // ---- invariants, every second ----
            val liveLinks = live()
            if (liveLinks.size > 1) return violation("${liveLinks.size} live links at once")

            val over = state() is ConnectionState.Disconnected || state() is ConnectionState.Failed
            leakSince = if (over && liveLinks.isNotEmpty()) (if (leakSince < 0) now else leakSince) else -1
            if (leakSince >= 0 && now - leakSince > 20_000) {
                return violation("a live link outlived the session (${state()::class.simpleName})")
            }

            zombieSince = if (connected() && !downloading() && !radio.aclUp) (if (zombieSince < 0) now else zombieSince) else -1
            if (zombieSince >= 0 && now - zombieSince > 60_000) {
                return violation("Connected for a minute over a radio link that is down")
            }

            connectingSince = if (state() is ConnectionState.Connecting) (if (connectingSince < 0) now else connectingSince) else -1
            if (connectingSince >= 0 && now - connectingSince > 120_000) {
                return violation("stuck in Connecting for two minutes")
            }

            val failed = state() is ConnectionState.Failed
            if (failed && !wasFailed) {
                val explained = (lastFaultAt >= 0 && now - lastFaultAt <= 60_000) ||
                    radio.failedOpens.any { now - it <= 60_000 }
                if (!explained) {
                    return violation("the session failed with no fault or failed connect in the minute before")
                }
            }
            wasFailed = failed

            if (recoverableSince >= 0) {
                val window = now - recoverableSince
                val spoiled = !radio.loggerOn || radio.openFailures > 0 ||
                    radio.failedOpens.any { it >= recoverableSince } ||
                    userEndedAt >= recoverableSince ||
                    lastFaultAt > recoverableSince
                if (spoiled) {
                    recoverableSince = -1
                } else if (window >= 180_000) {
                    // Recovered if the dump is whole: a drop after the
                    // transfer finished ends the session like an idle drop.
                    val whole = latestDump(complete = true)?.let { it.length() >= block * dataBlocks } == true
                    if (!connected() && !whole) {
                        return violation(
                            "a transfer fault the logger could recover from ended the session " +
                                "(${state()::class.simpleName})"
                        )
                    }
                    recoverableSince = -1
                }
            }
        }

        // ---- after the quiet period ----
        if (downloading()) return violation("a transfer was still running after 25 quiet minutes")

        for ((_, times) in checks) {
            val attempts = mutableListOf<MutableList<Long>>()
            for (t in times) {
                if (attempts.isNotEmpty() && t - attempts.last().last() < 10_000) attempts.last() += t
                else attempts += mutableListOf(t)
            }
            attempts.zipWithNext().firstOrNull { (a, b) -> b.first() - a.first() < 15_000 }?.let { (a, b) ->
                return violation(
                    "pointer-check attempts ${(b.first() - a.first()) / 1000.0} s apart on one link " +
                        "(at ${a.first() / 1000} s)"
                )
            }
            retried += attempts.count { it.size > 1 }
            attemptsSeen += attempts.size
        }

        File(dir, "dumps").listFiles { f -> f.name.endsWith(".bin") }?.forEach { file ->
            val bytes = file.readBytes()
            val holes = dumps.damagedRanges(file)
            for (i in bytes.indices) {
                if (holes.any { i in it }) continue
                if (bytes[i] != flash.getOrElse(i) { 0xFF.toByte() }) {
                    return violation("${file.name} differs from the flash at byte $i, outside any recorded hole")
                }
            }
            val partial = File(file.path + ".partial").exists()
            if (!partial && holes.isEmpty() && bytes.size < block * dataBlocks) {
                return violation(
                    "a dump not marked partial holds ${bytes.size} of ${block * dataBlocks} bytes (${file.name})"
                )
            }
        }
        return null
    }
}
