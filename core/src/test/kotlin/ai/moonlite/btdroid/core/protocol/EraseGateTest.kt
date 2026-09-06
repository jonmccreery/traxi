package ai.moonlite.btdroid.core.protocol

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate is the only thing standing between a user and the irreversible loss
 * of a season of tracking, so it is tested for what it *refuses* at least as
 * carefully as for what it permits.
 */
class EraseGateTest {

    private fun goodEvidence(
        coveredBytes: Int = 5_636_096,
        downloadComplete: Boolean = true,
        damagedBytes: Int = 0,
        fixes: Int = 129_186,
        checksumFailures: Int = 0,
    ) = EraseGate.Evidence(
        fileName = "flash-20260906-110014.bin",
        coveredBytes = coveredBytes,
        downloadComplete = downloadComplete,
        damagedBytes = damagedBytes,
        fixes = fixes,
        checksumFailures = checksumFailures,
    )

    private val pointerInsideDump = 0x0053A68AL   // 5,481,610

    // ---------------- the permitting case ----------------

    @Test
    fun `a complete verified dump covering the write pointer opens the gate`() {
        val evidence = goodEvidence()
        assertEquals(emptyList(), EraseGate.blockers(evidence, pointerInsideDump))
        assertTrue(EraseGate.permits(evidence, pointerInsideDump, "129186"))
    }

    @Test
    fun `confirmation is the fix count, so it cannot be guessed from the prompt`() {
        val evidence = goodEvidence(fixes = 129_186)
        assertEquals("129186", EraseGate.confirmationWord(evidence))
        assertFalse(EraseGate.isConfirmed("ERASE", evidence))
        assertFalse(EraseGate.isConfirmed("erase", evidence))
        assertFalse(EraseGate.isConfirmed("", evidence))
        assertTrue(EraseGate.isConfirmed("129186", evidence))
    }

    @Test
    fun `surrounding whitespace in the confirmation is forgiven`() {
        val evidence = goodEvidence()
        assertTrue(EraseGate.isConfirmed("  129186 ", evidence))
    }

    // ---------------- clause 1: a verified dump this session ----------------

    @Test
    fun `no dump at all blocks, and says what would unblock it`() {
        val blockers = EraseGate.blockers(null, pointerInsideDump)
        assertEquals(1, blockers.size)
        assertContains(blockers[0].reason, "No verified dump")
        assertContains(blockers[0].remedy, "Download")
        assertFalse(EraseGate.permits(null, pointerInsideDump, "129186"))
    }

    @Test
    fun `a partial dump blocks`() {
        val blockers = EraseGate.blockers(goodEvidence(downloadComplete = false), pointerInsideDump)
        assertTrue(blockers.any { it.reason.contains("partial") })
    }

    @Test
    fun `a dump with holes blocks, and the reason names the 0xFF ambiguity`() {
        val blockers = EraseGate.blockers(goodEvidence(damagedBytes = 2048), pointerInsideDump)
        val holes = blockers.single { it.reason.contains("2048 bytes") }
        assertContains(holes.remedy, "0xFF")
    }

    @Test
    fun `checksum failures block`() {
        val blockers = EraseGate.blockers(goodEvidence(checksumFailures = 1), pointerInsideDump)
        assertTrue(blockers.any { it.reason.contains("checksum") })
    }

    @Test
    fun `a dump with no fixes blocks, so an empty image cannot license an erase`() {
        val blockers = EraseGate.blockers(goodEvidence(fixes = 0), pointerInsideDump)
        assertTrue(blockers.any { it.reason.contains("no fixes") })
    }

    // ---------------- clause 2: coverage ----------------

    @Test
    fun `a dump that stops short of the write pointer blocks`() {
        val evidence = goodEvidence(coveredBytes = 1_048_576)
        val blockers = EraseGate.blockers(evidence, pointerInsideDump)
        assertTrue(blockers.any { it.reason.contains("written past the end") })
    }

    @Test
    fun `data logged after the dump blocks the erase`() {
        // The hole the prep doc does not mention: dump completes, the logger
        // keeps recording, and erasing now destroys everything since.
        val evidence = goodEvidence(coveredBytes = 5_636_096)
        val advanced = 5_636_096L + 4_096
        val blockers = EraseGate.blockers(evidence, advanced)
        val coverage = blockers.single { it.reason.contains("written past the end") }
        assertContains(coverage.remedy, "4096 bytes")
        assertFalse(EraseGate.permits(evidence, advanced, "129186"))
    }

    @Test
    fun `a pointer exactly at the end of the dump is covered`() {
        val evidence = goodEvidence(coveredBytes = 5_636_096)
        assertEquals(emptyList(), EraseGate.blockers(evidence, 5_636_096L))
    }

    @Test
    fun `an unreadable write pointer blocks rather than being assumed safe`() {
        val blockers = EraseGate.blockers(goodEvidence(), null)
        assertTrue(blockers.any { it.reason.contains("did not report its write pointer") })
    }

    // ---------------- reporting ----------------

    @Test
    fun `every blocker is reported at once, not one at a time`() {
        val evidence = goodEvidence(
            coveredBytes = 1024,
            downloadComplete = false,
            damagedBytes = 512,
            checksumFailures = 3,
        )
        val blockers = EraseGate.blockers(evidence, pointerInsideDump)
        assertEquals(4, blockers.size, "expected partial, holes, checksums and coverage")
        assertTrue(blockers.all { it.remedy.isNotBlank() })
    }

    @Test
    fun `confirmation alone cannot open a gate that evidence has closed`() {
        val evidence = goodEvidence(damagedBytes = 2048)
        assertTrue(EraseGate.isConfirmed("129186", evidence))
        assertFalse(EraseGate.permits(evidence, pointerInsideDump, "129186"))
    }
}
