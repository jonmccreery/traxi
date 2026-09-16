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
        /**
         * Bytes that never arrived and read as 0xFF in this file.
         *
         * Surfaced in the list because a dump with holes looks exactly like a
         * good one -- that is the entire hazard -- and the person deciding what
         * to export, extend or erase against has no other way to know.
         */
        val damagedBytes: Int = 0,
    ) {
        val name: String get() = file.name
        /**
         * Sectors the file spans -- its length over the sector size.
         *
         * Not a count of sectors holding data: a dump that stopped on two
         * unwritten sectors spans them too. The parser's `sectorsWithData` is
         * the figure that answers "how much of this is log", and on the same
         * file it is smaller. Named for the arithmetic it does so the two are
         * not mistaken for each other again.
         */
        val sectorSpan: Int get() = (sizeBytes / 0x10000).toInt()
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
                    damagedBytes = damagedRanges(file).sumOf { it.last - it.first + 1 },
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
    suspend fun save(
        file: File,
        bytes: ByteArray,
        partial: Boolean,
        /**
         * Byte ranges that never arrived and are therefore 0xFF in [bytes].
         *
         * Stored with the dump because **damage is a property of the image, not
         * of the transfer that produced it**. Held only in the transfer's
         * result, it evaporated the moment a resume produced a clean second
         * result: the marker was deleted, the holes stayed, and the erase gate
         * opened on a dump that was missing data. See §16.1.
         */
        damaged: List<IntRange> = emptyList(),
    ) = withContext(Dispatchers.IO) {
            val temp = File(file.path + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            val marker = File(file.path + PARTIAL_MARKER)
            // A dump with holes is never complete, whatever the caller thinks.
            if (partial || damaged.isNotEmpty()) {
                marker.writeText(
                    buildString {
                        append("resume=${bytes.size}\n")
                        if (damaged.isNotEmpty()) {
                            append("damaged=")
                            append(damaged.joinToString(",") {
                                "%08X-%08X".format(it.first, it.last)
                            })
                            append("\n")
                        }
                    }
                )
            } else {
                marker.delete()
            }
        }

    /**
     * Ranges of [file] that never arrived, as recorded when it was saved.
     *
     * Empty for a dump with no sidecar, which is also what a pre-§16.1 dump
     * looks like -- those were laundered clean and cannot be told from a good
     * one, which is why the fix had to be paired with re-reading rather than
     * with detection.
     */
    suspend fun damagedRanges(file: File): List<IntRange> = withContext(Dispatchers.IO) {
        val marker = File(file.path + PARTIAL_MARKER)
        if (!marker.exists()) return@withContext emptyList()
        val line = runCatching { marker.readLines() }.getOrNull()
            ?.firstOrNull { it.startsWith("damaged=") }
            ?: return@withContext emptyList()
        line.removePrefix("damaged=").split(",").mapNotNull { entry ->
            val halves = entry.trim().split("-")
            if (halves.size != 2) return@mapNotNull null
            val first = halves[0].toIntOrNull(16) ?: return@mapNotNull null
            val last = halves[1].toIntOrNull(16) ?: return@mapNotNull null
            if (last < first) null else first..last
        }
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
