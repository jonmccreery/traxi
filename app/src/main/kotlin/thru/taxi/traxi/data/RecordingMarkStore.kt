package thru.taxi.traxi.data

import android.content.Context
import thru.taxi.traxi.core.format.RecordingSince

/**
 * The last write-pointer reading, kept across app restarts.
 *
 * The whole value of [RecordingSince] depends on the earlier reading still
 * existing when the user comes back, and "when the user comes back" means after
 * the phone has been in a pack for nine hours, very possibly after Android has
 * killed the process. In-memory state cannot span that gap -- which is the same
 * lesson [TranscriptFile] exists for, and the same one §12 was.
 *
 * Deliberately one mark, not a history. The question is "what has it done since
 * I last looked", and a second entry would only invite showing a stale answer.
 */
class RecordingMarkStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * The stored mark, or null if there is none or it cannot be read.
     *
     * Any unreadable value is treated as absent rather than repaired. A mark
     * this class cannot parse is a mark it cannot vouch for, and the cost of
     * discarding one is a single session that says "no earlier reading" --
     * against a wrong duration presented as fact.
     */
    fun last(): RecordingSince.Mark? {
        val pointer = prefs.getLong(KEY_POINTER, -1L)
        val at = prefs.getLong(KEY_AT_MILLIS, -1L)
        val device = prefs.getString(KEY_DEVICE, null)
        if (pointer < 0 || at <= 0 || device.isNullOrEmpty()) return null
        return RecordingSince.Mark(pointer = pointer, atMillis = at, deviceKey = device)
    }

    fun put(mark: RecordingSince.Mark) {
        prefs.edit()
            .putLong(KEY_POINTER, mark.pointer)
            .putLong(KEY_AT_MILLIS, mark.atMillis)
            .putString(KEY_DEVICE, mark.deviceKey)
            .apply()
    }

    /** Forget the mark, so the next connect reports no earlier reading. */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val NAME = "recording-marks"
        const val KEY_POINTER = "pointer"
        const val KEY_AT_MILLIS = "atMillis"
        const val KEY_DEVICE = "deviceKey"
    }
}
