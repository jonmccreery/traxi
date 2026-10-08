package thru.taxi.traxi.data

import thru.taxi.traxi.core.format.RecordingSince

/** Where the last write-pointer reading is kept. [RecordingMarkStore] on the phone. */
interface MarkStore {
    fun last(): RecordingSince.Mark?
    fun put(mark: RecordingSince.Mark)
}

/** Where each logger's last flash-full reading is kept. [RecordMethodStore] on the phone. */
interface MethodStore {
    fun get(deviceKey: String): RecordMethodStore.Reading?
    fun put(deviceKey: String, reading: RecordMethodStore.Reading)
}
