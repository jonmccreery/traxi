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
