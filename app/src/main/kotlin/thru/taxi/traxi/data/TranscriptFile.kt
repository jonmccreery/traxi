package thru.taxi.traxi.data

import thru.taxi.traxi.core.protocol.RingTranscript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The transcript, mirrored to disk so it outlives the process.
 *
 * The ring buffer alone was not enough, and an investigation proved it: after a
 * ride where the UI froze on a live Bluetooth link, the only record of what the
 * link had been doing died with the process before it could be read. The
 * evidence that would have explained the failure was destroyed by the ordinary
 * act of installing the next build. An in-memory diagnostic cannot survive the
 * events it exists to diagnose -- a freeze, a kill, a reboot, an upgrade.
 *
 * Attached as [RingTranscript.sink], so it sees exactly the lines the in-app
 * view shows, in the same order and formatting. Lines go through a channel to a
 * single writer coroutine, so the caller -- often the socket read path, which
 * must not stall -- never waits on file I/O. The writer appends as it drains,
 * so an abrupt death loses at most a few milliseconds of lines.
 *
 * Two generations are kept, capped, because a diagnostic that fills the phone
 * is its own outage.
 */
class TranscriptFile(
    directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Unbounded: dropping lines under load would silently lose the densest part
    // of an incident, which is the part worth having.
    private val pending = Channel<String>(Channel.UNLIMITED)

    val current = File(directory, CURRENT_NAME)
    private val previous = File(directory, PREVIOUS_NAME)

    init {
        directory.mkdirs()
        scope.launch {
            for (line in pending) {
                runCatching {
                    rotateIfNeeded()
                    current.appendText(line + "\n")
                }
            }
        }
    }

    /** Mirror [ring] to this file from now on. */
    fun attachTo(ring: RingTranscript) {
        ring.sink = { line -> pending.trySend(line) }
    }

    /**
     * Mark a new run in the file.
     *
     * The date lives here rather than on every line: the per-line stamps are
     * times of day, so without this a reader cannot tell which day -- or which
     * run -- a line belongs to. A process death leaves no marker of its own, so
     * two banners with no orderly shutdown between them are themselves the
     * evidence that the app was killed.
     */
    fun noteSessionStart(detail: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        pending.trySend("")
        pending.trySend("=== app started $stamp — $detail ===")
    }

    private fun rotateIfNeeded() {
        if (current.length() < maxBytes) return
        runCatching {
            previous.delete()
            current.renameTo(previous)
        }
    }

    /** Both generations, oldest first, for sharing. */
    fun readAll(): String = buildString {
        runCatching { if (previous.exists()) append(previous.readText()) }
        runCatching { if (current.exists()) append(current.readText()) }
    }

    fun sizeBytes(): Long = current.length() + previous.length()

    fun clear() {
        scope.launch { runCatching { current.delete(); previous.delete() } }
    }

    private companion object {
        const val CURRENT_NAME = "transcript.log"
        const val PREVIOUS_NAME = "transcript.1.log"
    }
}
