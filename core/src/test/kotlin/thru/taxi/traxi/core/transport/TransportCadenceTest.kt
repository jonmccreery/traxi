package thru.taxi.traxi.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two link-cost constants, pinned to the values the record states.
 *
 * Both follow one design: **the default is the slow-link value and fast
 * transports opt down**, so a transport that has not been measured is treated
 * as fragile rather than free. `BluetoothSppTransport` overrides neither -- it
 * reads both from here, deliberately -- which makes this interface the place
 * Bluetooth's behaviour is actually decided.
 *
 * That is also how it went wrong. On 2026-09-08 `977a757`, titled *"Check
 * recording once a minute"*, lowered the probe default 600_000 -> 60_000. On
 * the only transport that reads the default, that was a tenfold increase in how
 * often the app talks to a device §15 measured as losing its link when talked
 * to. §15 item 3 has read "Bluetooth 10 min, USB 12 s" the whole time; the code
 * disagreed for seventeen days, and nothing noticed because nothing asserted it.
 *
 * These tests are cheap and they are the only thing standing between the record
 * and the code on a number that is invisible at every call site.
 */
class TransportCadenceTest {

    /** A transport that overrides nothing -- what Bluetooth effectively is here. */
    private object Unmeasured : Transport {
        override val description = "unmeasured"
        override val isOpen = true
        override suspend fun open() = Unit
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun read(dest: ByteArray, timeoutMillis: Long) = 0
        override fun close() = Unit
    }

    @Test
    fun `an unmeasured link probes every ten minutes, which is what Bluetooth gets`() {
        assertEquals(
            600_000L, Unmeasured.writePointerProbeIntervalMillis,
            "§15 item 3 says Bluetooth 10 min. BluetoothSppTransport does not override " +
                "this, so lowering the default silently raises the probe rate on the one " +
                "transport that cannot afford it -- see 977a757, and §15.",
        )
    }

    @Test
    fun `an unmeasured link waits ten seconds for a block, which is what Bluetooth gets`() {
        assertEquals(
            10_000L, Unmeasured.blockReadIdleTimeoutMillis,
            "10 s is the value Bluetooth download was verified byte-for-byte against " +
                "(161d60b, 0e0298d). A global drop to USB's 2.5 s cut every healthy " +
                "Bluetooth block short once already.",
        )
    }
}
