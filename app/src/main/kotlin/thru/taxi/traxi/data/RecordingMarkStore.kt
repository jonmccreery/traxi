package thru.taxi.traxi.data

import android.content.Context
import thru.taxi.traxi.core.format.RecordingSince

/**
 * The last write-pointer reading of each logger, kept across app restarts.
 *
 * The whole value of [RecordingSince] depends on the earlier reading still
 * existing when the user comes back, and "when the user comes back" means after
 * the phone has been in a pack for nine hours, very possibly after Android has
 * killed the process. In-memory state cannot span that gap -- which is the same
 * lesson [TranscriptFile] exists for, and the same one §12 was.
 *
 * One mark **per logger**, not a history. The question is "what has this logger
 * done since I last looked", and a second entry would only invite showing a
 * stale answer. It was one mark in all until 2026-10-07, when a second
 * BT-Q1000XT was bought: with one slot, connecting either unit would have
 * overwritten the other's baseline, every time they were swapped.
 */
class RecordingMarkStore(context: Context) : MarkStore {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * The stored mark for [deviceKey], or null if there is none or it cannot be
     * read.
     *
     * Any unreadable value is treated as absent rather than repaired. A mark
     * this class cannot parse is a mark it cannot vouch for, and the cost of
     * discarding one is a single session that says "no earlier reading" --
     * against a wrong duration presented as fact.
     */
    override fun last(deviceKey: String): RecordingSince.Mark? {
        val pointer = prefs.getLong("$KEYED$deviceKey/pointer", -1L)
        val at = prefs.getLong("$KEYED$deviceKey/atMillis", -1L)
        if (pointer < 0 || at <= 0) return null
        return RecordingSince.Mark(pointer = pointer, atMillis = at, deviceKey = deviceKey)
    }

    override fun put(mark: RecordingSince.Mark) {
        prefs.edit()
            .putLong("$KEYED${mark.deviceKey}/pointer", mark.pointer)
            .putLong("$KEYED${mark.deviceKey}/atMillis", mark.atMillis)
            .apply()
    }

    override fun claimLegacy(): RecordingSince.Mark? {
        val pointer = prefs.getLong(LEGACY_POINTER, -1L)
        val at = prefs.getLong(LEGACY_AT_MILLIS, -1L)
        val device = prefs.getString(LEGACY_DEVICE, null)
        if (pointer < 0 && at < 0 && device == null) return null
        prefs.edit().remove(LEGACY_POINTER).remove(LEGACY_AT_MILLIS).remove(LEGACY_DEVICE).apply()
        if (pointer < 0 || at <= 0 || device.isNullOrEmpty()) return null
        return RecordingSince.Mark(pointer = pointer, atMillis = at, deviceKey = device)
    }

    /** Forget every mark, so the next connect reports no earlier reading. */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val NAME = "recording-marks"
        const val KEYED = "mark/"
        // The single slot used before 2026-10-07.
        const val LEGACY_POINTER = "pointer"
        const val LEGACY_AT_MILLIS = "atMillis"
        const val LEGACY_DEVICE = "deviceKey"
    }
}
