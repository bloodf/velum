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
import dev.velum.ble.BleConnectionState
import dev.velum.core.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
        assertEquals(listOf(GattProfiles.OMI_AUDIO_CODEC), GattProfiles.Omi.reads)
        assertEquals("19b10002-e8f2-537e-4f6c-d104768a1214", GattProfiles.OMI_AUDIO_CODEC.characteristic)
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
        for (char in omiChars + GattProfiles.OMI_AUDIO_CODEC + GattProfiles.PLAUD_WRITE + GattChar("1234", "5678")) {
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
                GattProfiles.OMI_AUDIO_DATA to listOf(audio(1, 0, 1, 2), audio(2, 1, 3), audio(3, 0, 4), audio(4, 0, 5)),
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
    }

    /** Implements only the Plaud-style single stream, so the interface default for notifications(char) is what runs. */
    private class SingleStreamTransport : BleTransport {
        override val isAvailable = true
        override val linkGeneration = 0L
        override val connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
        override val discoveredDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
        override fun advertisements(): Flow<ConnectedDevice> = emptyFlow()
        override suspend fun startScan() {}
        override suspend fun stopScan() {}
        override suspend fun connect(deviceId: String) {}
        override suspend fun disconnect() {}
        override suspend fun writeRaw(write: GattWrite) {}
        override fun notifications(): Flow<ByteArray> = flowOf(byteArrayOf(7))
    }

    @Test
    fun defaultPerCharacteristicNotifications_onlyKnowThePlaudStream() = runTest {
        val t = SingleStreamTransport()
        assertEquals(listOf(listOf<Byte>(7)), t.notifications(GattProfiles.PLAUD_NOTIFY).toList().map { it.toList() })
        for (char in omiChars) assertEquals(0, t.notifications(char).toList().size)
    }

    /** Uses the interface's own two-arg connect (plain delegation would forward it to the inner stub). */
    private class RecordingConnect(private val stub: StubBleTransport = StubBleTransport()) : BleTransport by stub {
        val connected = mutableListOf<String>()

        override suspend fun connect(deviceId: String) {
            connected += deviceId
            stub.connect(deviceId)
        }

        override suspend fun connect(deviceId: String, profile: GattProfile) =
            super<BleTransport>.connect(deviceId, profile)
    }

    @Test
    fun plaudOnlyTransports_refuseOtherProfilesBeforeConnecting() = runTest {
        val ble = RecordingConnect()
        for (profile in listOf(GattProfiles.Omi, GattProfiles.Pocket, GattProfiles.Limitless)) {
            assertFailsWith<IllegalArgumentException>(profile.id) { ble.connect("synthetic-device", profile) }
        }
        assertTrue(ble.connected.isEmpty(), "a refused profile must not start a connection")
        assertEquals(BleConnectionState.Idle, ble.connectionState.value)
    }

    @Test
    fun plaudProfile_connectsTheGivenDevice() = runTest {
        val ble = RecordingConnect()
        ble.connect("synthetic-device", GattProfiles.Plaud)
        assertEquals(listOf("synthetic-device"), ble.connected)
    }
}
