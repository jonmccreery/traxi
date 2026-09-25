package thru.taxi.traxi.core.transport

/**
 * A raw, bidirectional byte stream to the logger.
 *
 * Deliberately minimal and free of Android types so that the entire protocol
 * stack above it — [thru.taxi.traxi.core.protocol.PmtkClient],
 * [thru.taxi.traxi.core.protocol.FlashDownloader] — is testable on the JVM
 * against [FileReplayTransport] with no device present.
 *
 * Implementations: Bluetooth RFCOMM (v1), USB serial (v2), file replay.
 */
interface Transport : AutoCloseable {

    /** Human-readable identification for the transcript, e.g. a MAC or path. */
    val description: String

    /**
     * How long the block reader should wait on silence before treating a block
     * as stalled and retrying it.
     *
     * This is a property of the link, not the protocol, which is why it lives
     * here rather than as a constant in [thru.taxi.traxi.core.protocol.FlashDownloader].
     * USB delivers a 64 KB block as ~32 chunks in about a second, so a couple
     * of seconds is untouchably generous. Over Bluetooth the same block is tens
     * of seconds of hex-encoded bytes on the wire, arriving interleaved with the
     * live 1 Hz NMEA stream, so the gap between two log chunks routinely exceeds
     * a USB-sized timeout and every healthy read is cut short.
     *
     * The default is deliberately the slow-link value: a transport that does not
     * override it is treated as slow, so a new or unmeasured link is safe rather
     * than fast-and-broken. Fast transports opt down. 10 s is the value against
     * which Bluetooth download was verified byte-for-byte (commits 161d60b,
     * 0e0298d), before it was globally overridden to a USB-only 2.5 s.
     */
    val blockReadIdleTimeoutMillis: Long get() = 10_000

    /**
     * How often the session may re-read the write pointer to confirm recording.
     *
     * A property of the link for the same reason as the timeout above, but for
     * a harsher reason: over Bluetooth this query is not free, and not always
     * survivable. Measured on the BT-Q1000XT, 17 probes over three minutes drew
     * 14 answers, and **every unanswered probe was followed within a second by
     * the link going down** -- once with the logger itself terminating the
     * connection. Idle NMEA streams for hours untouched; it is talking to this
     * device that destabilises it, which is why a frozen app on the 7 September
     * ride held a link for 39 minutes while a healthy one loses it every two.
     *
     * So the default is rare, and the fast transports opt down, exactly as with
     * the idle timeout: an unmeasured link is assumed fragile rather than
     * assumed free. USB has no such problem -- it talks to the chip directly,
     * with none of the Bluetooth module's UART bridge in the way. Measured
     * 2026-09-08 straight at the chip over CDC-ACM: 24 of 25 probes at the
     * Bluetooth-killing 12 s cadence, 10-16 ms each, no degradation across five
     * minutes. The command is not the problem; the radio path is.
     *
     * **Back to ten minutes, 2026-09-25.** `977a757` lowered this to 60 s on
     * 2026-09-08 and ended its own message with *"if it destabilises Bluetooth,
     * this is the one number to move."* It did, and this is that move.
     *
     * Two things made the reduction look safe and neither held.
     *
     * It was licensed on *"a wedge is survivable rather than fatal: the link
     * rebuilds itself and a transfer resumes from the frontier."* §11 records
     * that `downloadWithLinkRecovery` **had never run against hardware** --
     * nothing had ever wedged while it was watching. It first ran on
     * 2026-09-25 and failed at the rebuild, so the premise was untested when it
     * was spent.
     *
     * And it was applied to the *default*, which is the only place Bluetooth
     * reads it from: `BluetoothSppTransport` has never overridden this, by
     * design -- the default is the slow-link value and fast transports opt
     * down, exactly as `blockReadIdleTimeoutMillis` above still does. So a
     * change described as "check recording once a minute" was, on the one
     * transport that matters, a **tenfold increase in how often the app talks
     * to a device that §15 measured as losing its link when talked to.** §15
     * item 3 has said "Bluetooth 10 min, USB 12 s" throughout; the code has
     * disagreed since the day after it was written.
     *
     * The early probe is unaffected -- `startTelemetry` takes
     * `min(firstProbe, steady)` and still confirms recording about twenty
     * seconds in. This only changes the steady cadence, which is what the
     * record always described.
     *
     * If usefulness needs raising again: raise it on a transport that has been
     * measured to tolerate it, not on the default that Bluetooth inherits.
     */
    val writePointerProbeIntervalMillis: Long get() = 600_000

    val isOpen: Boolean

    suspend fun open()

    suspend fun write(bytes: ByteArray)

    /**
     * Read up to `dest.size` bytes.
     *
     * @return bytes read, 0 on timeout, or -1 at end of stream.
     *   A timeout is *not* an error: the device is often simply silent between
     *   responses, and the caller decides when silence has gone on too long.
     */
    suspend fun read(dest: ByteArray, timeoutMillis: Long): Int
}

/** Raised when the device does not answer within the allotted time. */
class PmtkTimeoutException(message: String) : Exception(message)

/** Raised when the device answers, but with a failure or unparseable payload. */
class PmtkProtocolException(message: String) : Exception(message)
