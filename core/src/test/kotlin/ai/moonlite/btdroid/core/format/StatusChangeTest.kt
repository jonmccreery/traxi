package ai.moonlite.btdroid.core.format

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log-status audit trail recovered from a dump.
 *
 * This exists because a real silent failure was diagnosed with it and could not
 * have been diagnosed without it: the app's transcript is in memory and dies
 * with the process, but the device writes every logging start and stop into the
 * flash, between timestamped fixes. A dump is therefore a self-contained record
 * of its own recording gaps.
 */
class StatusChangeTest {

    companion object {
        private lateinit var flash: ByteArray

        @JvmStatic
        @BeforeClass
        fun load() {
            val dir = System.getProperty("btdroid.dataDir") ?: "../data"
            val file = File(dir, "cdt_v2.bin")
            assumeTrue("golden dump not found at ${file.absolutePath}", file.isFile)
            flash = file.readBytes()
        }
    }

    @Test
    fun `status changes are recovered and dated`() {
        val stats = MtkLogParser.parse(flash, GpsRollover.AXN_130B).stats
        assertTrue(stats.statusChanges.isNotEmpty(), "no status changes found")

        // Every change is a real marker, so the count cannot exceed the total.
        assertTrue(stats.statusChanges.size <= stats.markers)

        // Offsets ascend, and all but a leading change carry a timestamp to
        // date them against.
        val offsets = stats.statusChanges.map { it.offset }
        assertEquals(offsets.sorted(), offsets)
        assertTrue(stats.statusChanges.drop(1).all { it.after != null })
    }

    @Test
    fun `enabled is decoded from the same bit the PMTK query reports`() {
        val stats = MtkLogParser.parse(flash, GpsRollover.AXN_130B).stats
        // The reference dump pairs 0x0100 and 0x0102 in near-equal numbers.
        val enabled = stats.statusChanges.count { it.enabled }
        val disabled = stats.statusChanges.count { !it.enabled }
        assertTrue(enabled > 0 && disabled > 0, "expected both states present")
        assertTrue(
            kotlin.math.abs(enabled - disabled) < stats.statusChanges.size / 4,
            "expected roughly paired start/stop events, got $enabled on / $disabled off",
        )
    }

    @Test
    fun `a healthy history contains no unanswered stop`() {
        // The property that caught the bug, and it is specifically *this* one.
        //
        // A trailing DISABLE is NOT a fault: powering the device off writes one,
        // and the matching ENABLE arrives at the next power-on. This reference
        // dump ends disabled for exactly that reason, which is why the first
        // version of this test was wrong.
        //
        // What never happens on a healthy device is a DISABLE immediately
        // followed by another DISABLE -- a stop that nothing answered before
        // the next stop. Seventeen months of this device's history contain
        // zero. Three appeared on 2026-09-05, the day the app began writing
        // configuration to it, and they are the signature of a config write
        // that failed between disabling logging and re-enabling it.
        val changes = MtkLogParser.parse(flash, GpsRollover.AXN_130B).stats.statusChanges
        val unanswered = (0 until changes.size - 1).count {
            !changes[it].enabled && !changes[it + 1].enabled
        }
        assertEquals(0, unanswered, "found $unanswered stops that nothing re-enabled")
    }
}
