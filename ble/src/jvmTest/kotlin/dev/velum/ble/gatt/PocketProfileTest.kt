package dev.velum.ble.gatt

import dev.velum.ble.BleTransport
import dev.velum.ble.StubBleTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class CountingTransport(
    private val stub: StubBleTransport = StubBleTransport(),
) : BleTransport by stub {
    var writes = 0
    override suspend fun writeRaw(write: GattWrite) {
        writes++
    }
}

class PocketProfileTest {
    private val pocketChars = listOf(GattProfiles.POCKET_AUDIO_DATA, GattProfiles.POCKET_REPLIES, GattProfiles.POCKET_BATTERY_LEVEL)
    private val commandChars = listOf(
        GattChar("001120a0-2233-4455-6677-889912345678", "001120a2-2233-4455-6677-889912345678"), // vendor app writes here
        GattProfiles.POCKET_REPLIES, // pocket-libre also writes commands here
    )

    @Test
    fun pocketProfile_isNotifyOnly_andAddressedByThePublishedUuids() {
        assertTrue(GattProfiles.Pocket.writes.isEmpty())
        assertEquals(pocketChars, GattProfiles.Pocket.notify)
        assertEquals("001120a1-2233-4455-6677-889912345678", GattProfiles.POCKET_AUDIO_DATA.characteristic)
        assertEquals("001120a3-2233-4455-6677-889912345678", GattProfiles.POCKET_REPLIES.characteristic)
    }

    @Test
    fun pocketLink_rejectsEveryWrite_includingTheCommandChannelAndTheSessionKeyPairing_andPlaudsWriteChar() = runTest {
        val t = CountingTransport()
        val link = GuardedLink(t, GattProfiles.Pocket)
        val payloads = listOf("APP&SK&0123456789abcdef".encodeToByteArray(), "APP&BAT".encodeToByteArray(), byteArrayOf(1))
        for (char in pocketChars + commandChars + GattProfiles.PLAUD_WRITE) {
            for (p in payloads) {
                assertFailsWith<IllegalArgumentException>("$char") { link.write(char, p, withoutResponse = false) }
            }
        }
        assertEquals(0, t.writes)
    }

    @Test
    fun otherProfiles_cannotWriteToPocketCharacteristics() = runTest {
        val t = CountingTransport()
        for (profile in listOf(GattProfiles.Plaud, GattProfiles.Omi)) {
            val link = GuardedLink(t, profile)
            for (char in pocketChars + commandChars) {
                assertFailsWith<IllegalArgumentException> { link.write(char, byteArrayOf(1), withoutResponse = true) }
            }
        }
        assertEquals(0, t.writes)
    }
}
