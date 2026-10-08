package thru.taxi.traxi.data

import thru.taxi.traxi.core.format.RecordingSince

/**
 * Where each logger's last write-pointer reading is kept, by device key.
 * [RecordingMarkStore] on the phone.
 */
interface MarkStore {
    fun last(deviceKey: String): RecordingSince.Mark?
    fun put(mark: RecordingSince.Mark)

    /**
     * The single mark versions before 2026-10-07 kept, keyed by model and
     * firmware -- removed as it is returned, so it is claimed at most once.
     */
    fun claimLegacy(): RecordingSince.Mark?
}

/** Where each logger's last flash-full reading is kept. [RecordMethodStore] on the phone. */
interface MethodStore {
    fun get(deviceKey: String): RecordMethodStore.Reading?
    fun put(deviceKey: String, reading: RecordMethodStore.Reading)

    /** A reading an older version stored under [legacyKey]; removed as it is returned. */
    fun claimLegacy(legacyKey: String): RecordMethodStore.Reading?

    /** Drop the reading for [deviceKey]: something may have changed the setting. */
    fun forget(deviceKey: String)
}
