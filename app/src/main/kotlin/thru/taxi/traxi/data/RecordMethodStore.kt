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
class RecordMethodStore(context: Context) : MethodStore {

    data class Reading(val method: Pmtk.RecordMethod, val atMillis: Long)

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Null if never read, or if what is stored cannot be parsed. */
    override fun get(deviceKey: String): Reading? = read("$KEYED$deviceKey")

    override fun put(deviceKey: String, reading: Reading) {
        prefs.edit()
            .putString("$KEYED$deviceKey/method", reading.method.name)
            .putLong("$KEYED$deviceKey/atMillis", reading.atMillis)
            .apply()
    }

    // Before 2026-10-07 entries were keyed by model and firmware with no
    // prefix, which a second identical unit would share. New entries carry
    // [KEYED], so an old one can be claimed once and never confused with them.
    override fun forget(deviceKey: String) {
        prefs.edit().remove("$KEYED$deviceKey/method").remove("$KEYED$deviceKey/atMillis").apply()
    }

    override fun claimLegacy(legacyKey: String): Reading? {
        val reading = read(legacyKey)
        prefs.edit().remove("$legacyKey/method").remove("$legacyKey/atMillis").apply()
        return reading
    }

    private fun read(prefix: String): Reading? {
        val name = prefs.getString("$prefix/method", null) ?: return null
        val at = prefs.getLong("$prefix/atMillis", -1L)
        val method = Pmtk.RecordMethod.entries.firstOrNull { it.name == name } ?: return null
        return if (at > 0) Reading(method, at) else null
    }

    private companion object {
        const val NAME = "record-methods"
        const val KEYED = "v2/"
    }
}
