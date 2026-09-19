package thru.taxi.traxi.core.format

/**
 * Proof that the logger is recording, obtained by making it record something.
 *
 * Every other recording signal in this project is *observational*: wait a log
 * interval, read the write pointer, and see whether it moved. That works, and
 * it is what the Device tab's indicator does, but it is slow (it cannot answer
 * faster than one interval), it is passive, and at a trailhead the answer the
 * user wants is immediate and causal — *I did a thing; did it land?*
 *
 * The logger has a physical **mark button**, and pressing it writes one record
 * there and then rather than at the next interval tick; the reference dump
 * holds 1,419 of them, carrying `RCR = BUTTON` (see [RecordReason.BUTTON]).
 * Read the pointer, press the button, read the pointer again, and a single
 * record's worth of advance proves the entire chain at once:
 *
 *  - the slide switch is on LOG, not NAV or OFF (§15.2 — no software can tell
 *    you this, and NAV reports `0x0102` over a frozen pointer),
 *  - logging is enabled in firmware (§12, §16.11),
 *  - the receiver has a position to write,
 *  - and bytes are genuinely reaching flash.
 *
 * Nothing else here establishes all four, and nothing else establishes any of
 * them in two seconds. **Two queries, both user-paced** — this must never be
 * built into a poll, because per-request exhaustion in the logger's Bluetooth
 * firmware is what kills the link (§15), and the one place that is affordable
 * is standing still before setting off.
 */
object MarkProof {

    sealed interface Result {

        /** A record landed. The chain is intact, end to end. */
        data class Proven(val bytes: Long, val records: Long) : Result

        /**
         * The pointer did not move, and the receiver had a position to write.
         *
         * The only damning outcome here, and it is worth the strong wording it
         * gets: the user physically asked the device to record a point, it had
         * a fix, and nothing reached flash.
         */
        data object NothingWritten : Result

        /**
         * Something prevented an answer rather than answering "no".
         *
         * Kept strictly apart from [NothingWritten]. A logger with no fix
         * writes nothing *correctly*, and reporting that as a failure would
         * condemn a healthy device for being indoors — the standing rule for
         * every indicator in this app.
         */
        data class Inconclusive(val reason: String) : Result
    }

    /**
     * @param before pointer read before the press, or null if unanswered.
     * @param after pointer read after the press, or null if unanswered.
     * @param hadFix whether the receiver held a position across the attempt.
     */
    fun of(before: Long?, after: Long?, recordBytes: Int, hadFix: Boolean): Result {
        if (before == null || after == null) {
            return Result.Inconclusive(
                "the logger did not answer the write-pointer query, so nothing was measured"
            )
        }
        if (after < before) {
            // A wrap in overlap mode, or an erase. Either way the subtraction
            // is meaningless and the pointer cannot be used as evidence here.
            return Result.Inconclusive(
                "the write pointer went backwards — the log wrapped or was erased mid-check"
            )
        }
        if (after == before) {
            return if (hadFix) Result.NothingWritten
            else Result.Inconclusive(
                "the receiver had no position to write, so nothing was expected to land"
            )
        }
        val bytes = after - before
        return Result.Proven(
            bytes = bytes,
            records = if (recordBytes > 0) bytes / recordBytes else 0,
        )
    }
}
