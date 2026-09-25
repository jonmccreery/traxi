package thru.taxi.traxi.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * What a connect is allowed to ask the logger.
 *
 * §15 is the most expensive thing this project has learned: **listening to this
 * logger is free and talking to it is not.** Something is consumed per request
 * in its Bluetooth firmware and not fully released; probe latency climbs
 * 132 ms → 1142 ms → no reply → link down. Every unexplained disconnection in
 * this project has been a consequence of that.
 *
 * `openWith` runs on every single connection, including the one at a trailhead
 * before a ride, so a request added here is a request paid for over and over by
 * the person least able to afford a dropped link.
 *
 * On 2026-09-24 a seventh query was added to that sequence to read a
 * configuration field that only ever changes when this app changes it. The
 * session it shipped in lost its link twenty-five seconds after connecting.
 * One sample proves nothing about that specific drop — but the request was not
 * worth making, and nothing in the build objected, because the existing
 * safety-rail test hand-rolls its own query list and never reads this function.
 *
 * So this test reads the real one. It is deliberately a source-level check
 * rather than a behavioural one: `openWith` needs an Android `Context` and this
 * module has no Robolectric, and a guard that cannot run is not a guard. What
 * matters is that adding a request to the connect path now *fails the build and
 * says why*, instead of depending on whoever is editing remembering §15.
 *
 * **To add a query here:** have a reason it must be answered on every connect
 * rather than on demand, write that reason next to it, and update this list.
 * The failure message is the review.
 */
class ConnectBudgetTest {

    /**
     * Every request `openWith` is permitted to make, and why each one earns it.
     *
     *  - `queryFirmware` — identifies the model and the week-rollover fix.
     *  - `queryLogFormat` — sets the record size; parsing is wrong without it.
     *  - `queryTimeIntervalSeconds` — the interval the recording indicator judges against.
     *  - `queryLogStatus` — the status word, plus need_format / memory_full.
     *  - `queryFlashId` — flash identity, for the capacity hint.
     *  - `queryWritePointer` — the baseline for [RecordingSince] and the probe.
     *
     * Not here, on purpose: the record method. It only changes when this app
     * changes it, and it is read on demand from the Config tab instead.
     */
    private val allowed = setOf(
        "queryFirmware",
        "queryLogFormat",
        "queryTimeIntervalSeconds",
        "queryLogStatus",
        "queryFlashId",
        "queryWritePointer",
    )

    @Test
    fun `a connect asks the logger for exactly these things and no more`() {
        val body = openWithBody()
        val asked = Regex("""\bc\.(query[A-Za-z]+)\s*\(""")
            .findAll(body).map { it.groupValues[1] }.toSet()

        val added = asked - allowed
        if (added.isNotEmpty()) {
            fail(
                "openWith now asks the logger for ${added.sorted()}, which §15 says is " +
                    "spent from a budget this device runs out of. Every connection pays " +
                    "it, including the one before a ride. If it genuinely must be " +
                    "answered at connect rather than on demand, say why beside the call " +
                    "and add it to `allowed` here. If it does not, read it from the " +
                    "screen that shows it."
            )
        }
        val removed = allowed - asked
        if (removed.isNotEmpty()) {
            fail(
                "openWith no longer asks for ${removed.sorted()}. That may be right — " +
                    "fewer requests is the direction of travel — but something downstream " +
                    "reads each of these, so update `allowed` deliberately rather than " +
                    "letting this pass silently."
            )
        }
        assertEquals(allowed, asked)
    }

    @Test
    fun `a connect still writes nothing`() {
        // The older rail from prep doc §8, applied to the real function this
        // time rather than to a hand-written sequence. A write reaching this
        // path is §12: the device left switched off by an operation nobody
        // asked for.
        val body = openWithBody()
        for (forbidden in listOf("writeConfig", "writeLoggingEnabled", "writeEraseFlash")) {
            if (body.contains(forbidden)) {
                fail("openWith calls $forbidden — connecting must never write. See §12.")
            }
        }
    }

    private fun openWithBody(): String {
        val source = findSource("app/src/main/kotlin/thru/taxi/traxi/session/SessionController.kt")
        val text = source.readText()
        val start = text.indexOf("private suspend fun openWith(")
        if (start < 0) fail("could not find openWith in ${source.path}")
        // Up to the point the connected state is published: everything after
        // that is post-connect setup, not the interrogation sequence.
        val end = text.indexOf("recordingBaseline = pointer", start)
        if (end < 0) fail("could not find the end of openWith's query sequence")
        return text.substring(start, end)
    }

    private fun findSource(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
    }
}
