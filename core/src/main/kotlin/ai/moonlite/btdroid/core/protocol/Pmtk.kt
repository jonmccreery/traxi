package ai.moonlite.btdroid.core.protocol

/**
 * PMTK command payloads and response shapes.
 *
 * Payloads exclude the `$` prefix and `*<checksum>` suffix; [Nmea.frame] adds
 * those. Commands that mutate device state are grouped separately and named so
 * that a write can never be mistaken for a query at a call site.
 */
object Pmtk {

    // ---------------- queries: always safe ----------------

    /** Firmware release. Responds `PMTK705,<release>,<model>,...`. */
    const val QUERY_FIRMWARE = "PMTK605"

    fun queryConfig(field: ConfigField): String = "PMTK182,2,${field.id}"

    /**
     * Request [length] bytes of log starting at [address].
     *
     * **Length must be even** — odd lengths are rejected by the device
     * outright, which presents as an unexplained timeout.
     */
    fun readLog(address: Int, length: Int): String {
        require(length % 2 == 0) { "PMTK read length must be even, got $length" }
        require(length > 0) { "PMTK read length must be positive, got $length" }
        require(address >= 0) { "PMTK read address must be non-negative, got $address" }
        return "PMTK182,7,%08X,%08X".format(address, length)
    }

    // ---------------- writes: deliberate user action only ----------------

    /** Set a configuration field. Responds `PMTK001,182,1,3` on success. */
    fun writeConfig(field: ConfigField, value: String): String =
        "PMTK182,1,${field.id},$value"

    const val WRITE_ENABLE_LOGGING = "PMTK182,4"
    const val WRITE_DISABLE_LOGGING = "PMTK182,5"

    /**
     * Erase the entire log flash. **Irreversible, and the flash is the only
     * copy of the data.**
     *
     * An earlier revision left this command deliberately unimplemented. That
     * was wrong, and prep doc §0.2 records why: the device holds 21 days of
     * logging at the interval this user wants, against a thru-hike of four to
     * six months. *Dump, verify, erase, repeat* is the only workflow the
     * hardware supports, and it has to run from a trailhead with no computer.
     * Refusing to erase does not make the data safe, it makes the device
     * useless once full.
     *
     * The safety argument survives intact; it belongs in the gate rather than
     * in a refusal. Never call this without going through [EraseGate], and
     * prefer [FlashEraser], which owns the disable/erase/verify/restore
     * sequence.
     */
    const val WRITE_ERASE_FLASH = "PMTK182,6,1"

    /**
     * Configuration fields addressable by `PMTK182,1,<id>` (write) and
     * `PMTK182,2,<id>` (query).
     */
    enum class ConfigField(val id: Int) {
        /** 32-bit log format register, hex. */
        LOG_FORMAT(2),

        /** Time criterion, units of 0.1 s. 200 = 20.0 s. */
        TIME_INTERVAL(3),

        /** Distance criterion, units of 0.1 m. 0 disables. */
        DISTANCE_INTERVAL(4),

        /** Speed criterion, units of 0.1 km/h. 0 disables. */
        SPEED_LIMIT(5),

        /** Behaviour when flash fills: overlap (circular) or stop. */
        RECORD_METHOD(6),

        /** Logging enabled/disabled plus mode flags. */
        LOG_STATUS(7),

        /**
         * Next write address, in bytes.
         *
         * Despite mtkbabel presenting field 8 as a record count, the device
         * returns a byte offset: it answered `0051F3C2` (5,370,818) against a
         * flash whose written region measures 5.37 MB. The "247,133 records"
         * in the reference session was not this value.
         *
         * Useful as a **progress hint**, never as a bound. In OVERLAP mode
         * everything before a wrap sits after this pointer, so sizing a
         * download from it silently discards the oldest data.
         */
        WRITE_POINTER(8),

        /**
         * Flash identity. Returns a JEDEC RDID, not a byte count -- see
         * [FlashId]. Previously believed broken because nothing decoded it.
         */
        FLASH_SIZE(9),
    }

    /**
     * Decoded `PMTK182,3,7` log status.
     *
     * The device answers in **decimal**, and the meaning of the bits is not
     * guesswork: the reference dump carries 200 type-`0x07` markers with arg
     * `0x0100` and 200 with `0x0102`, which the internals doc identifies as
     * logging disabled and enabled respectively. The only bit that differs is
     * `0x02`, so that is the logging flag.
     *
     * Worth decoding rather than displaying raw, because "logging is off" is a
     * state the user must be able to *see*. A logger that is not recording
     * looks exactly like one that is, until the trip is over.
     */
    @JvmInline
    value class LogStatus(val bits: Int) {

        val isLoggingEnabled: Boolean get() = (bits and LOGGING_ENABLED) != 0

        fun describe(): String =
            if (isLoggingEnabled) "logging (0x%04X)".format(bits)
            else "NOT logging (0x%04X)".format(bits)

        companion object {
            /** Set when the device is recording. `0x0102` vs `0x0100`. */
            const val LOGGING_ENABLED = 0x0002

            /**
             * Parse the raw field, which the device sends in decimal.
             *
             * Hex is accepted as a fallback because nothing in the protocol
             * guarantees the radix and a `0x` form would otherwise silently
             * decode as zero -- which would read as "not logging" and send the
             * user chasing a problem that does not exist.
             */
            fun parse(raw: String): LogStatus? {
                val s = raw.trim()
                if (s.isEmpty()) return null
                val value = s.toIntOrNull()
                    ?: s.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    ?: return null
                return LogStatus(value)
            }
        }
    }

    /** A `PMTK001` acknowledgement. */
    data class Ack(val command: String, val subcommand: String?, val flag: Int) {
        val isSuccess: Boolean get() = flag == FLAG_SUCCESS

        companion object {
            const val FLAG_INVALID = 0
            const val FLAG_UNSUPPORTED = 1
            const val FLAG_FAILED = 2
            const val FLAG_SUCCESS = 3

            /** Parse `PMTK001,<cmd>[,<sub>],<flag>`, or null if not an ack. */
            fun from(sentence: NmeaSentence): Ack? {
                if (sentence.type != "PMTK001") return null
                val flag = sentence.fields.lastOrNull()?.toIntOrNull() ?: return null
                val command = sentence[1] ?: return null
                val subcommand = if (sentence.fields.size >= 4) sentence[2] else null
                return Ack(command, subcommand, flag)
            }
        }
    }

    /**
     * Decoded response to the flash-size query, `PMTK182,3,9`.
     *
     * This query was previously written off as broken: mtkbabel reported it as
     * failing, and the reference session had to force a size through an
     * environment override. It is not broken -- it returns a **JEDEC RDID**
     * (`0x9F`) flash identifier, which nothing in the toolchain was decoding.
     *
     * The observed value `1C70171C` decodes as manufacturer `0x1C` (EON Silicon
     * Solution), memory type `0x70` (EN25QH series), capacity `0x17`. The
     * capacity byte is a power of two by JEDEC convention, so `0x17` is
     * 2^23 = 8 MB.
     *
     * That reading is self-checking: the write pointer sits at 5.37 MB, which
     * 8 MB contains comfortably and 4 MB could not.
     */
    data class FlashId(val raw: String, val manufacturer: Int, val memoryType: Int, val capacityCode: Int) {

        /** Capacity in bytes, or null if the code is outside a believable range. */
        val bytes: Long?
            get() = if (capacityCode in 16..31) 1L shl capacityCode else null

        val manufacturerName: String
            get() = when (manufacturer) {
                0x1C -> "EON"
                0xC2 -> "Macronix"
                0xEF -> "Winbond"
                0x20 -> "Micron/ST"
                0x01 -> "Spansion"
                0xBF -> "SST"
                else -> "0x%02X".format(manufacturer)
            }

        fun describe(): String {
            val size = bytes
            return if (size != null) {
                "$manufacturerName ${size / 1024 / 1024} MB (JEDEC %02X %02X %02X)"
                    .format(manufacturer, memoryType, capacityCode)
            } else {
                "unrecognised flash id $raw"
            }
        }

        companion object {
            /**
             * Parse the hex payload of `PMTK182,3,9`.
             *
             * The device returns four bytes where JEDEC RDID defines three; the
             * trailing byte repeats the manufacturer. Only the first three are
             * interpreted.
             */
            fun parse(hex: String): FlashId? {
                val s = hex.trim()
                if (s.length < 6) return null
                val m = s.substring(0, 2).toIntOrNull(16) ?: return null
                val t = s.substring(2, 4).toIntOrNull(16) ?: return null
                val c = s.substring(4, 6).toIntOrNull(16) ?: return null
                return FlashId(s, m, t, c)
            }
        }
    }

    /** Parsed `PMTK705` firmware response. */
    data class Firmware(
        val release: String,
        val modelId: String,
        /**
         * Human-readable model, e.g. `BT-Q1000XT`. Present in field 3 of the
         * response and previously discarded -- it identifies the hardware far
         * more usefully than the numeric model id, which is `0008` on this
         * device and tells nobody anything.
         */
        val modelName: String,
        val raw: String,
    ) {
        /**
         * True for firmware predating the 2019-04-06 GPS week rollover, which
         * reports timestamps 1024 weeks early. `AXN_1.30-B` is such a build.
         */
        val needsWeekRollover: Boolean
            get() = release.startsWith("AXN_1.") || release.startsWith("AXN_2.0")

        companion object {
            fun from(sentence: NmeaSentence): Firmware? {
                if (sentence.type != "PMTK705") return null
                return Firmware(
                    release = sentence[1].orEmpty(),
                    modelId = sentence[2].orEmpty(),
                    modelName = sentence[3].orEmpty(),
                    raw = sentence.raw,
                )
            }
        }
    }
}
