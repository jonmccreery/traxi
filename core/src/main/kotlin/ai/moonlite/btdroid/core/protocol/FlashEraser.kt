package ai.moonlite.btdroid.core.protocol

/**
 * Runs the erase sequence: disable logging, erase, verify, restore logging.
 *
 * Separate from [PmtkClient] because the ordering *is* the safety property, and
 * it should be readable in one screen rather than inferred from a call site.
 * [EraseGate] decides whether this may run at all; this class assumes that
 * decision has already been made and concerns itself only with carrying it out
 * correctly and reporting honestly.
 *
 * Implements prep doc §0.2 clauses 5 and 6.
 */
class FlashEraser(
    private val client: PmtkClient,
    /** Bytes read back to confirm the erase. One whole block; see [verify]. */
    private val verifyLength: Int = VERIFY_LENGTH,
) {

    /** Progress, for a UI that must not look frozen for tens of seconds. */
    enum class Phase {
        DISABLING_LOGGING,
        ERASING,
        VERIFYING,
        RESTORING_LOGGING,
    }

    /**
     * @param verified sector 0 read back as 0xFF, per clause 6.
     * @param writePointerAfter the device's pointer once erased. Expected at or
     *   near zero; reported rather than enforced, because the pointer has lied
     *   before and the read-back is the stronger evidence.
     * @param loggingRestored whether logging was successfully switched back on.
     *   Tracked separately because failing to restore it is a real problem --
     *   the user walks away believing they are recording -- but it is not a
     *   reason to call the erase itself failed.
     */
    data class Result(
        val erased: Boolean,
        val verified: Boolean,
        val writePointerAfter: Long?,
        val loggingRestored: Boolean,
        val failure: String? = null,
    ) {
        /** Erased *and* proven erased. Anything less needs the user told. */
        val isClean: Boolean get() = erased && verified && failure == null
    }

    /**
     * Erase the device.
     *
     * Logging is restored in a `finally`, so an erase that fails verification
     * still leaves the logger recording rather than silently switched off. A
     * user who is told "erase failed" and then walks a day without logging
     * because of it has been failed twice.
     */
    suspend fun erase(onPhase: (Phase) -> Unit = {}): Result {
        var erased = false
        var verified = false
        var pointerAfter: Long? = null
        var failure: String? = null
        var restoreFailure: String? = null

        try {
            // Clause 5. Erasing while the logger is mid-record is exactly the
            // kind of concurrent state this device handles badly. The restore
            // is the client's guarantee, not this method's -- see
            // PmtkClient.withLoggingPaused.
            onPhase(Phase.DISABLING_LOGGING)
            client.withLoggingPaused(
                onRestoring = { onPhase(Phase.RESTORING_LOGGING) },
                onRestoreFailed = { restoreFailure = it },
            ) {
                onPhase(Phase.ERASING)
                client.writeEraseFlash()
                erased = true

                // Clause 6. An erase that silently failed leaves the user
                // believing they have free space, and they discover otherwise
                // when the chip fills a week early, in the field.
                onPhase(Phase.VERIFYING)
                verified = verify()
                pointerAfter = client.queryWritePointer()
                if (!verified) {
                    failure = "the logger acknowledged the erase but sector 0 still holds data"
                }
            }
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        }

        val restored = restoreFailure == null

        return Result(
            erased = erased,
            verified = verified,
            writePointerAfter = pointerAfter,
            loggingRestored = restored,
            failure = failure,
        )
    }

    /**
     * Read the first block back and confirm it is erased.
     *
     * A whole block, not a spot check: §6 of ENGINEERING-RECORD.md records
     * that this device ignores a 512-byte read request *and drops the link*, so
     * a small probe is not merely less thorough, it is actively harmful. Only
     * full-block reads have ever been reliable here.
     *
     * A short read counts as failure rather than success. Absent bytes are
     * filled with 0xFF, which is precisely the value being tested for, so
     * treating a truncated read as "all 0xFF" would turn a transport problem
     * into a false confirmation -- the same 0xFF ambiguity that
     * [FlashDownloader.Result.damagedRanges] exists to prevent.
     */
    private suspend fun verify(): Boolean {
        val block = client.readLogBlock(address = 0, length = verifyLength)
        if (!block.isComplete) {
            client.transcript.note(
                "erase verification read was short (${block.filled}/$verifyLength bytes); " +
                    "treating as unverified"
            )
            return false
        }
        val dirty = block.bytes.count { it != PmtkClient.UNWRITTEN }
        if (dirty > 0) {
            client.transcript.note("erase verification found $dirty non-0xFF bytes in sector 0")
        }
        return dirty == 0
    }

    companion object {
        /** One 64 KB block — the only read size this device is reliable at. */
        const val VERIFY_LENGTH = 0x10000
    }
}
