package thru.taxi.traxi.core.format

/**
 * Human-readable byte counts that never round a real quantity down to nothing.
 *
 * This exists because `"${bytes / 1024} KB"` was written in four places, and
 * integer division turns every value under a kilobyte into `0 KB`. On the
 * Device tab that string sits beside the recording indicator, so a logger that
 * had genuinely recorded 960 bytes reported "Written 0 KB, 0% of flash used" --
 * which reads as *nothing is being recorded*, the one conclusion this app must
 * never invite the user to draw wrongly. See the engineering record's §12.
 *
 * The rule: a non-zero count never displays as zero. Below a kilobyte the exact
 * byte count is shown, because at that size the exact number is both short and
 * the only honest answer.
 */
object Bytes {

    /** e.g. `960 B`, `18 KB`, `5363 KB`. */
    fun describe(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

    fun describe(bytes: Int): String = describe(bytes.toLong())

    /**
     * A percentage that distinguishes "none" from "not much".
     *
     * `(fraction * 100).toInt()` renders 0.011% as `0%`, which is the same
     * misreading as `0 KB` in a different unit.
     */
    fun describePercent(fraction: Double): String {
        val percent = fraction * 100
        return when {
            percent <= 0.0 -> "0%"
            percent < 1.0 -> "<1%"
            else -> "${percent.toInt()}%"
        }
    }
}
