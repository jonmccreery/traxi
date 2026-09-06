package thru.taxi.traxi.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device storage for raw flash images.
 *
 * The governing rule from the prep doc: **persist the raw bytes before parsing
 * anything.** The flash is the only copy of the data, so every parse runs
 * against a file on disk and never against a live stream. A bad parser release
 * then cannot destroy anything, and a half-finished dump stays re-parseable
 * without the logger present.
 *
 * Dumps live in app-internal storage, so no storage permission is involved and
 * scoped-storage rules — which broke the vendor app — do not apply. Export to a
 * user-visible location is a separate, explicit action.
 */
class DumpRepository(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "dumps").apply { mkdirs() }

    data class Dump(
        val file: File,
        val sizeBytes: Long,
        val modifiedAt: Long,
        /**
         * True if the download did not run to a clean end-of-flash. Partial
         * dumps are kept, listed, and resumable rather than discarded.
         */
        val isPartial: Boolean,
    ) {
        val name: String get() = file.name
        val sectorsComplete: Int get() = (sizeBytes / 0x10000).toInt()
    }

    suspend fun list(): List<Dump> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.isFile && f.name.endsWith(BIN_SUFFIX) }
            .orEmpty()
            .map { file ->
                Dump(
                    file = file,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    isPartial = File(file.path + PARTIAL_MARKER).exists(),
                )
            }
            .sortedByDescending { it.modifiedAt }
    }

    /** Create a new dump file named for the moment the download started. */
    fun newDumpFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "flash-$stamp$BIN_SUFFIX")
    }

    suspend fun read(file: File): ByteArray = withContext(Dispatchers.IO) {
        file.readBytes()
    }

    /**
     * Write [bytes] and mark the dump partial or complete.
     *
     * Writes to a temporary file and renames, so an interrupted save can never
     * leave a truncated file wearing a complete dump's name.
     */
    suspend fun save(file: File, bytes: ByteArray, partial: Boolean) =
        withContext(Dispatchers.IO) {
            val temp = File(file.path + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            val marker = File(file.path + PARTIAL_MARKER)
            if (partial) marker.writeText("resume=${bytes.size}") else marker.delete()
        }

    /** Byte offset a partial dump should resume from, or 0 if not resumable. */
    suspend fun resumeOffset(file: File): Int = withContext(Dispatchers.IO) {
        if (!File(file.path + PARTIAL_MARKER).exists()) return@withContext 0
        // Resume must land on a block boundary; truncate to the last complete
        // block rather than trusting a byte count that may straddle one.
        val whole = (file.length() / BLOCK_SIZE) * BLOCK_SIZE
        whole.toInt()
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        file.delete()
        File(file.path + PARTIAL_MARKER).delete()
    }

    /** Write text (a GPX export or a transcript) to the app's cache for sharing. */
    suspend fun writeShareable(name: String, content: String): File =
        withContext(Dispatchers.IO) {
            val exports = File(context.cacheDir, "exports").apply { mkdirs() }
            File(exports, name).apply { writeText(content) }
        }

    companion object {
        private const val BIN_SUFFIX = ".bin"
        private const val PARTIAL_MARKER = ".partial"
        private const val BLOCK_SIZE = 0x10000L
    }
}
