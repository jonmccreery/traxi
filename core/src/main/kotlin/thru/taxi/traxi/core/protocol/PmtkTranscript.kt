package thru.taxi.traxi.core.protocol

/**
 * A record of every byte exchanged with the device.
 *
 * Not a debugging nicety. The phone is the only computer available in the
 * field, so when the logger misbehaves at a trailhead this transcript is the
 * only diagnostic that exists. It must therefore be cheap enough to leave on
 * permanently and readable from inside the app.
 *
 * Bulk log-read payloads are elided rather than recorded — a full dump is
 * ~10.8 MB of hex on the wire and would bury the exchanges that matter.
 */
interface PmtkTranscript {

    /** A sentence written to the device. */
    fun tx(line: String)

    /** A sentence received from the device. */
    fun rx(line: String)

    /** Free-text annotation: retries, timeouts, state transitions. */
    fun note(message: String)

    companion object {
        val NONE: PmtkTranscript = object : PmtkTranscript {
            override fun tx(line: String) = Unit
            override fun rx(line: String) = Unit
            override fun note(message: String) = Unit
        }
    }
}

/**
 * An in-memory ring buffer of transcript lines.
 *
 * Bounded so a 25-minute download cannot exhaust memory. The Android layer
 * mirrors this to a rolling file.
 */
class RingTranscript(private val capacity: Int = 2000) : PmtkTranscript {

    private val lines = ArrayDeque<String>(capacity)

    // Local wall-clock, millisecond precision. Wall-clock because the reader
    // correlates lines with what they were doing in the field ("it dropped
    // when the phone went in my pocket"); milliseconds because the questions
    // this transcript answers are about pacing -- chunk gaps, retry spacing,
    // link stalls -- which live below the second.
    @Synchronized
    private fun add(prefix: String, text: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast("${java.time.LocalTime.now().format(STAMP)} $prefix $text")
    }

    override fun tx(line: String) = add(">>", line)
    override fun rx(line: String) = add("<<", line)
    override fun note(message: String) = add("--", message)

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    private companion object {
        val STAMP: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
