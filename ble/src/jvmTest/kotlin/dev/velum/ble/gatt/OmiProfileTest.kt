package dev.velum.ble.gatt

import dev.velum.ble.BleTransport
import dev.velum.ble.StubBleTransport
import dev.velum.protocol.Bytes
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.Framing
import dev.velum.recorder.omi.OmiAudioSession
import dev.velum.recorder.omi.OmiBattery
import dev.velum.recorder.omi.OmiCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** Fake multi-characteristic peripheral: one scripted notification stream per characteristic; records writes. */
private class MultiCharTransport(
    private val streams: Map<GattChar, List<ByteArray>>,
    private val stub: StubBleTransport = StubBleTransport(),
) : BleTransport by stub {
    val writes = mutableListOf<GattWrite>()

    override suspend fun writeRaw(write: GattWrite) {
        writes += write
    }

    override fun notifications(char: GattChar): Flow<ByteArray> = flowOf(*(streams[char].orEmpty()).toTypedArray())
}

class OmiProfileTest {
    private val omiChars = listOf(GattProfiles.OMI_AUDIO_DATA, GattProfiles.OMI_BATTERY_LEVEL)

    @Test
    fun omiProfile_declaresNoWritableCharacteristic_andIsAddressedByOmiUuids() {
        assertTrue(GattProfiles.Omi.writes.isEmpty())
        assertEquals(omiChars, GattProfiles.Omi.notify)
        assertEquals("19b10001-e8f2-537e-4f6c-d104768a1214", GattProfiles.OMI_AUDIO_DATA.characteristic)
    }

    @Test
    fun omiLink_rejectsEveryWrite_includingPlaudWriteCharAndForbiddenOpcodes_andNothingReachesTheRadio() = runTest {
        val t = MultiCharTransport(emptyMap())
        val link = GuardedLink(t, GattProfiles.Omi)
        val payloads = listOf(
            byteArrayOf(0x01),
            byteArrayOf(Framing.APP_PREFIX) + Bytes.u16Le(ForbiddenOpcodes.ALL.first()),
        )
        for (char in omiChars + GattProfiles.PLAUD_WRITE + GattChar("1234", "5678")) {
            for (p in payloads) {
                assertFailsWith<IllegalArgumentException>("$char") { link.write(char, p, withoutResponse = false) }
            }
        }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun plaudLink_rejectsWritesToOmiCharacteristics() = runTest {
        val t = MultiCharTransport(emptyMap())
        val link = GuardedLink(t, GattProfiles.Plaud)
        for (char in omiChars) {
            assertFailsWith<IllegalArgumentException> { link.write(char, byteArrayOf(1), withoutResponse = true) }
        }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun plaudProfile_stillRejectsForbiddenOpcodes() = runTest {
        val t = MultiCharTransport(emptyMap())
        val link = GuardedLink(t, GattProfiles.Plaud)
        for (op in ForbiddenOpcodes.ALL) {
            assertFailsWith<IllegalArgumentException> {
                link.write(GattProfiles.PLAUD_WRITE, byteArrayOf(Framing.APP_PREFIX) + Bytes.u16Le(op), withoutResponse = false)
            }
        }
        assertEquals(0, t.writes.size)
    }

    private fun audio(packet: Int, index: Int, vararg payload: Int) = byteArrayOf(
        (packet and 0xff).toByte(), ((packet shr 8) and 0xff).toByte(), index.toByte(),
    ) + ByteArray(payload.size) { payload[it].toByte() }

    @Test
    fun fakePeripheral_streamsAudioAndBatteryThroughTheSessionWithoutAnyWrite() = runTest {
        val t = MultiCharTransport(
            mapOf(
                GattProfiles.OMI_AUDIO_DATA to listOf(audio(1, 0, 1, 2), audio(1, 1, 3), audio(2, 0, 4), audio(3, 0, 5)),
                GattProfiles.OMI_BATTERY_LEVEL to listOf(byteArrayOf(64), byteArrayOf(63)),
            ),
        )
        val session = OmiAudioSession(OmiCodec.Opus16)
        val frames = t.notifications(GattProfiles.OMI_AUDIO_DATA).toList().mapNotNull { session.accept(it) } +
            listOfNotNull(session.finish())
        assertEquals(3, frames.size)
        assertContentEquals(byteArrayOf(1, 2, 3), frames[0])
        assertContentEquals(byteArrayOf(4), frames[1])
        assertContentEquals(byteArrayOf(5), frames[2])
        assertEquals(listOf(64, 63), t.notifications(GattProfiles.OMI_BATTERY_LEVEL).toList().map { OmiBattery.parse(it) })
        assertEquals(0, t.writes.size)
        // Plaud's single stream is unaffected by the per-characteristic API.
        assertEquals(0, t.notifications(GattProfiles.PLAUD_NOTIFY).toList().size)
    }
}
