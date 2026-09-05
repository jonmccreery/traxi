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

    // Deliberately absent: `PMTK182,6,1` (erase flash). The flash is the only
    // copy of the data and the device is unreliable about its own state, so
    // this project does not implement erase at all. See prep doc section 8.

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
         * Flash size. **Unreliable on this firmware** — the reference session
         * had to force it via an environment override. Never size a download
         * from this.
         */
        FLASH_SIZE(9),
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

    /** Parsed `PMTK705` firmware response. */
    data class Firmware(val release: String, val modelId: String, val raw: String) {
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
                    raw = sentence.raw,
                )
            }
        }
    }
}
