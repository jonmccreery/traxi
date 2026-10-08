package thru.taxi.traxi.data

import android.content.Context
import thru.taxi.traxi.core.protocol.Pmtk

/**
 * The last flash-full setting read from each logger, kept across app restarts.
 *
 * The setting only changes when this app changes it, and reading it costs a
 * query this logger's link pays for (§15). So it is read on request and then
 * remembered: the Config card can say "OVERLAP, read Oct 7" on every later
 * connect without asking again.
 */
class RecordMethodStore(context: Context) {

    data class Reading(val method: Pmtk.RecordMethod, val atMillis: Long)

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Null if never read, or if what is stored cannot be parsed. */
    fun get(deviceKey: String): Reading? {
        val name = prefs.getString("$deviceKey/method", null) ?: return null
        val at = prefs.getLong("$deviceKey/atMillis", -1L)
        val method = Pmtk.RecordMethod.entries.firstOrNull { it.name == name } ?: return null
        return if (at > 0) Reading(method, at) else null
    }

    fun put(deviceKey: String, reading: Reading) {
        prefs.edit()
            .putString("$deviceKey/method", reading.method.name)
            .putLong("$deviceKey/atMillis", reading.atMillis)
            .apply()
    }

    private companion object {
        const val NAME = "record-methods"
    }
}
