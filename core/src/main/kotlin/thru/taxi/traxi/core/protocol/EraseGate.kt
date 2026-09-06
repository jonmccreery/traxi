package thru.taxi.traxi.core.protocol

/**
 * The conditions under which erasing the logger is permissible.
 *
 * Kept as pure functions over plain data, deliberately: this is the one place
 * in the project where a mistake destroys a user's data irrecoverably, and it
 * should be readable end to end and testable without a device, a file system or
 * an Android runtime. Nothing here performs I/O.
 *
 * Prep doc §0.2 sets out the gate. Each rule below cites the clause it
 * implements, so a reviewer can check the two against each other.
 */
object EraseGate {

    /**
     * What the app knows about the dump it proposes to erase against.
     *
     * This describes **one specific file that was downloaded and then parsed in
     * this session**. It is not a summary of "a download happened": clause 1
     * requires the image to be on disk, to have been read back, and to have
     * been understood.
     */
    data class Evidence(
        val fileName: String,

        /** Bytes of flash the dump covers, counted from offset 0. */
        val coveredBytes: Int,

        /**
         * The downloader's own verdict: it reached the end of flash, hit no
         * error, and recorded no damaged ranges.
         */
        val downloadComplete: Boolean,

        /**
         * Bytes the downloader knows never arrived. Non-zero means the image
         * has 0xFF holes that are indistinguishable from erased flash.
         */
        val damagedBytes: Int,

        /** Fixes recovered by the parser from this exact file. */
        val fixes: Int,

        /** Records the parser rejected on checksum. */
        val checksumFailures: Int,
    )

    /**
     * A reason erasing is refused, phrased for the user rather than the log.
     *
     * [remedy] is the part that matters. A gate that only says no teaches the
     * user to look for a way around it; one that says what would open it keeps
     * them inside the safe workflow.
     */
    data class Blocker(val reason: String, val remedy: String)

    /**
     * Every reason the device may not be erased, or an empty list if it may.
     *
     * All blockers are returned rather than just the first. A user who fixes
     * one obstacle only to be shown another has been misled about how far away
     * they were.
     *
     * @param evidence the verified dump, or null if this session has none.
     * @param writePointer the device's write pointer **read now**, not the one
     *   cached at connect. See clause 2 below for why the freshness matters.
     */
    fun blockers(evidence: Evidence?, writePointer: Long?): List<Blocker> {
        // Clause 1: a verified dump in the current session.
        if (evidence == null) {
            return listOf(
                Blocker(
                    "No verified dump in this session.",
                    "Download the flash and let it parse. Erase stays disabled " +
                        "until the app has read back and understood what it would destroy.",
                )
            )
        }

        val out = mutableListOf<Blocker>()

        // Clause 1, continued: the dump must be whole and must parse cleanly.
        if (!evidence.downloadComplete) {
            out += Blocker(
                "${evidence.fileName} is a partial dump.",
                "Resume or re-run the download until it reports a complete read.",
            )
        }
        if (evidence.damagedBytes > 0) {
            out += Blocker(
                "${evidence.fileName} is missing ${evidence.damagedBytes} bytes that never arrived.",
                "Those gaps are 0xFF, which is byte-identical to erased flash — the copy " +
                    "looks complete and is not. Download again.",
            )
        }
        if (evidence.checksumFailures > 0) {
            out += Blocker(
                "${evidence.fileName} has ${evidence.checksumFailures} checksum failures.",
                "A dump that does not parse cleanly is not a verified copy. Download again.",
            )
        }
        if (evidence.fixes <= 0) {
            out += Blocker(
                "${evidence.fileName} contains no fixes.",
                "There is nothing to verify against. Download again, or check the " +
                    "logger actually holds data.",
            )
        }

        // Clause 2: coverage. The dump must reach past everything the device
        // has written.
        //
        // The pointer is read fresh at erase time and not taken from connect,
        // which closes a hole the prep doc does not mention: the logger keeps
        // recording after a download finishes. Dump at 10:00, erase at 10:40,
        // and forty minutes of new tracking is destroyed by an erase the gate
        // would otherwise have called safe. Comparing against a current pointer
        // catches both failure modes -- a download that stopped short, and a
        // device that has moved on since.
        if (writePointer == null) {
            out += Blocker(
                "The logger did not report its write pointer.",
                "Without it there is no way to confirm the dump covers everything " +
                    "on the device. Reconnect and try again.",
            )
        } else if (writePointer > evidence.coveredBytes) {
            val extra = writePointer - evidence.coveredBytes
            out += Blocker(
                "The logger has written past the end of ${evidence.fileName} " +
                    "(pointer 0x%08X, dump covers %d bytes).".format(writePointer, evidence.coveredBytes),
                "$extra bytes on the device are not in the dump — either the download " +
                    "stopped short, or the logger has recorded more since. Download again.",
            )
        }

        return out
    }

    /**
     * The word the user must type to confirm, per clause 3.
     *
     * The fix count rather than a generic "ERASE", because the point is not to
     * slow the user down, it is to prove they have read the summary of what
     * they are about to destroy. A number they cannot produce from memory is a
     * number they have not looked at.
     */
    fun confirmationWord(evidence: Evidence): String = evidence.fixes.toString()

    /** Whether [typed] matches the confirmation for [evidence]. */
    fun isConfirmed(typed: String, evidence: Evidence): Boolean =
        typed.trim() == confirmationWord(evidence)

    /**
     * Whether the erase may proceed: gates open **and** confirmation typed.
     *
     * Call sites should use this rather than the two halves, so that neither
     * can be checked without the other.
     */
    fun permits(evidence: Evidence?, writePointer: Long?, typed: String): Boolean =
        evidence != null &&
            blockers(evidence, writePointer).isEmpty() &&
            isConfirmed(typed, evidence)
}
