package thru.taxi.traxi.core.format

/**
 * What the logger wrote while nobody was watching.
 *
 * Every live recording signal this app has costs a Bluetooth query, works only
 * while connected, and expires the moment it disconnects -- and the ride is
 * exactly the part where the phone is in a pack with the link shut down. The
 * one indicator that survives all of that is the device's own write pointer,
 * because it is a number the logger keeps for itself: note it before setting
 * off, read it again afterwards, and the difference is the entire trip,
 * measured, for the cost of the query `openWith` already makes at connect.
 *
 * That is the assurance this object computes. Not "it was recording when I
 * looked", which is what a probe gives -- *"between 09:14 and 14:02 the logger
 * wrote 1,798 fixes"*, which is what the user actually wants to know.
 *
 * Nothing here talks to the device or to Android. It is arithmetic on two
 * samples, so every case below is a unit test rather than a field report.
 */
object RecordingSince {

    /**
     * One write-pointer reading, and enough context to know it is comparable.
     *
     * @param deviceKey identifies the logger the pointer came from. Comparing
     *   two pointers from different devices would produce a confident,
     *   meaningless number, which is worse than saying nothing.
     */
    data class Mark(
        val pointer: Long,
        val atMillis: Long,
        val deviceKey: String,
    )

    sealed interface Verdict {

        /** No earlier reading. The first connect of a device's life. */
        data object NothingToCompare : Verdict

        /**
         * The two readings cannot be subtracted.
         *
         * Kept distinct from [WroteNothing] because they look identical in the
         * arithmetic and could not be less alike to the user: a wrap in overlap
         * mode means the logger wrote *more* than the whole chip, and reporting
         * that as "nothing was recorded" would be the worst false alarm in the
         * app.
         */
        data class Unusable(val reason: String) : Verdict

        /**
         * The pointer did not move at all across the gap.
         *
         * The one unambiguous alarm in this file. It does not depend on a fix,
         * on the status word, or on the slide switch: whatever the logger was
         * doing, it put nothing in flash.
         */
        data class WroteNothing(val elapsedSeconds: Long) : Verdict

        /**
         * The pointer advanced, and by how much.
         *
         * @param recordedSeconds how long the logger would have to have been
         *   recording, at its configured interval, to write [fixes]. This is
         *   the number worth showing next to [elapsedSeconds]: it is the only
         *   way to tell "recorded the whole ride" from "recorded the first four
         *   minutes and then stopped", and both look like an advancing pointer.
         */
        data class Wrote(
            val bytes: Long,
            val fixes: Long,
            val recordedSeconds: Long,
            val elapsedSeconds: Long,
        ) : Verdict {

            /**
             * Recorded time over elapsed time.
             *
             * Deliberately allowed to exceed 1.0 rather than being clamped. A
             * logger writing on the distance criterion as well as the time one
             * genuinely outruns its interval, and a coverage of 1.4 is a fact
             * about the configuration; clamping it to "100%" would hide that
             * the estimate's assumption does not hold for this logger.
             */
            val coverage: Double
                get() = if (elapsedSeconds <= 0) 0.0
                else recordedSeconds.toDouble() / elapsedSeconds
        }
    }

    /**
     * The mark to store for a connect, or null if nothing should be stored.
     *
     * The two refusals here are the whole reason this is a function rather than
     * an `if` at the call site, because getting either wrong destroys the only
     * baseline the app has and does it silently:
     *
     *  - **a pointer the device did not answer** must leave the previous mark
     *    alone. Writing "unknown" would close the open interval without
     *    measuring it, so a link that misbehaves at the trailhead would erase
     *    the very reading the post-ride connect needs. Leaving it stale is
     *    right: the gap simply stays open and the next good reading spans all
     *    of it.
     *  - **a simulated session** must not store anything at all. Its pointer is
     *    an artefact of a file on the phone, and subtracting the hardware's
     *    from it would produce a confident number about a ride that did not
     *    happen.
     */
    fun markFor(pointer: Long?, deviceKey: String?, atMillis: Long): Mark? {
        if (pointer == null || pointer < 0) return null
        if (deviceKey.isNullOrEmpty()) return null
        return Mark(pointer = pointer, atMillis = atMillis, deviceKey = deviceKey)
    }

    /**
     * Compare a fresh reading against the last one.
     *
     * @param recordBytes on-flash size of one record, from
     *   [LogFormat.recordSizeWithChecksum].
     * @param intervalSeconds the logger's time criterion.
     */
    fun compare(
        previous: Mark?,
        current: Mark,
        recordBytes: Int,
        intervalSeconds: Double,
    ): Verdict {
        if (previous == null) return Verdict.NothingToCompare
        if (previous.deviceKey != current.deviceKey) {
            return Verdict.Unusable("the last reading came from a different logger")
        }
        val elapsedMillis = current.atMillis - previous.atMillis
        if (elapsedMillis < 0) {
            // The phone's clock moved backwards -- a timezone change, an NTP
            // correction, a manual set. The byte count is still sound but every
            // duration derived from it would be nonsense, and a duration is
            // most of what this verdict is for.
            return Verdict.Unusable("the phone's clock moved backwards since the last reading")
        }
        val elapsedSeconds = elapsedMillis / 1000

        if (current.pointer < previous.pointer) {
            // Either the log wrapped (overlap mode, and a great deal was
            // written) or the flash was erased (and nothing can be concluded).
            // Both are indistinguishable from here and neither is a fault, so
            // this says what it saw and stops.
            return Verdict.Unusable(
                "the write pointer went backwards, so the log has either wrapped " +
                    "or been erased since the last reading"
            )
        }
        if (current.pointer == previous.pointer) return Verdict.WroteNothing(elapsedSeconds)

        val raw = current.pointer - previous.pointer
        // Each sector boundary crossed costs a 512-byte header that is not
        // record payload. Under 1% of the total, and subtracted anyway because
        // the alternative is an estimate that is quietly always a little high.
        val headers = (current.pointer / SectorHeader.SECTOR_SIZE) -
            (previous.pointer / SectorHeader.SECTOR_SIZE)
        val payload = (raw - headers * SectorHeader.SIZE).coerceAtLeast(0)
        val fixes = if (recordBytes > 0) payload / recordBytes else 0
        val recordedSeconds = (fixes * intervalSeconds).toLong()

        return Verdict.Wrote(
            bytes = raw,
            fixes = fixes,
            recordedSeconds = recordedSeconds,
            elapsedSeconds = elapsedSeconds,
        )
    }
}
