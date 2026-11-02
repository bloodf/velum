package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Issues #93 / #97: simulated-peripheral coverage only (synthetic identity, in-process fake).
 * None of this proves a real Note Pro behaves the same; that stays in #5 / #9.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedPeripheralScenariosTest {
    private val cipher = createSessionCipher()
    private val decryptor = createFileDecryptor() as JcaFileDecryptor
    private val pems = rsaKeyPairToPem()
    private val sig = ByteArray(256) { (it % 251).toByte() }
    private val keys = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = sig,
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )
    private val fixture = "synthetic-ogg-fixture".repeat(20).encodeToByteArray()
    private val encrypted = decryptor.encryptFile(pems.first, fixture)

    // ---- recording ------------------------------------------------------

    /** Records every app->device write, split into one segment per connect(). */
    private class RecordingBle(private val inner: FakeBleTransport) : BleTransport by inner {
        val segments = mutableListOf<MutableList<ByteArray>>()
        var swallowWrites = false

        override suspend fun connect(deviceId: String) {
            segments += mutableListOf<ByteArray>()
            inner.connect(deviceId)
        }

        override suspend fun write(payload: ByteArray, withoutResponse: Boolean) {
            segments.last() += payload.copyOf()
            if (!swallowWrites) inner.write(payload, withoutResponse)
        }
    }

    private sealed class Sent {
        data class Raw(val opcode: Int) : Sent()
        data class App(val cmd: Int, val params: ByteArray) : Sent()
    }

    /**
     * Decode one connection's writes: unlock frames are raw (only FE10 / FE12 are legal app->device
     * raw opcodes), everything else must open as an AEAD-sealed app packet with seq 2,3,...
     * A frame that is neither fails the test instead of being guessed at.
     */
    private fun decode(segment: List<ByteArray>, secret: Protocol20SessionSecret?): List<Sent> {
        var seq = 2L
        return segment.map { w ->
            val opened = secret?.let { cipher.openPacket(it, seq, w) }
            if (opened == null) {
                val op = Bytes.readU16Le(w, 0)
                assertTrue(
                    op == Commands.UNLOCK_SN_SIGNATURE || op == Commands.UNLOCK_SESSION_SECRET,
                    "unopenable app->device frame with raw opcode 0x${op.toString(16)}",
                )
                Sent.Raw(op)
            } else {
                seq = opened.first + 1
                val p = opened.second
                assertEquals(Framing.APP_PREFIX, p[0])
                Sent.App(
                    if (p.size >= 3) Bytes.readU16Le(p, 1) else p[1].toInt() and 0xFF,
                    if (p.size > 3) p.copyOfRange(3, p.size) else ByteArray(0),
                )
            }
        }
    }

    private fun assertSafe(sent: List<Sent>) {
        for (s in sent) when (s) {
            is Sent.Raw -> assertFalse(s.opcode in ForbiddenOpcodes.ALL, "raw ${s.opcode}")
            is Sent.App -> {
                assertFalse(s.cmd in ForbiddenOpcodes.ALL, "cmd ${s.cmd}")
                if (s.cmd == 8 && s.params.size > 1) {
                    assertFalse((s.params[0].toInt() and 0xff) in ForbiddenSettings.ALL)
                }
            }
        }
    }

    /** Full stack trace (message + causes) must carry no token, PEM or signature material. */
    private fun assertNoIdentityLeak(t: Throwable, vararg signatures: ByteArray) {
        val trace = t.stackTraceToString()
        assertFalse(trace.contains(keys.bindToken), "bind token leaked")
        assertFalse(trace.contains("-----"), "PEM marker leaked")
        assertFalse(trace.contains("PRIVATE KEY") || trace.contains("PUBLIC KEY"))
        assertFalse(trace.contains(pems.first.lines()[1]) || trace.contains(pems.second.lines()[1]), "PEM body leaked")
        for (sg in signatures) {
            assertFalse(trace.contains(java.util.Base64.getEncoder().encodeToString(sg)), "signature base64 leaked")
            assertFalse(trace.contains(sg.joinToString("") { "%02x".format(it) }), "signature hex leaked")
            assertFalse(trace.contains(sg.joinToString(", ")), "signature byte list leaked")
        }
    }

    private class Rig(
        val ble: RecordingBle,
        val peripheral: FakePlaudPeripheral,
        val deviceFrames: MutableList<ByteArray>,
    )

    private fun TestScope.rig(
        e2ee: Boolean,
        battery: Int = 0x5d,
        peripheralKeys: DeviceIdentityKeys = keys,
    ): Rig {
        val p = FakePlaudPeripheral(
            cipher, peripheralKeys, encrypted,
            batteryPercent = battery, requirePublicKey = e2ee,
        )
        val frames = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            p.notifications.collect { frames += it }
        }
        return Rig(RecordingBle(FakeBleTransport(p)), p, frames)
    }

    private fun Rig.deviceRawOpcodes(): List<Int> = deviceFrames.map { Bytes.readU16Le(it, 0) }

    // ---- #93 advertising --------------------------------------------------

    @Test
    fun advertising_fixture_parses_portVersion_and_project_881() {
        val adv = PlaudAdvertisement.parseManufacturerData(FakeBleTransport.noteProManufacturerData())!!
        assertEquals(881, adv.modelCode)
        assertEquals(DeviceModel.NotePro, adv.model)
        assertEquals(20, adv.protocolVersion)
        assertEquals("881FAKE1", adv.serial)
        val v30 = PlaudAdvertisement.parseManufacturerData(FakeBleTransport.noteProManufacturerData(protocolVersion = 30))!!
        assertEquals(30, v30.protocolVersion)
    }

    @Test
    fun advertising_shorter_than_20_bytes_is_not_discovered() {
        assertNull(PlaudAdvertisement.parseManufacturerData(ByteArray(0)))
        for (n in 1..19) assertNull(PlaudAdvertisement.parseManufacturerData(ByteArray(n) { 0x71 }), "len=$n")
    }

    /** Garbage 20+ byte blobs are not Plaud adverts: null from the parser and not listed. */
    @Test
    fun advertising_20_byte_garbage_is_rejected_and_not_listed() {
        for (blob in listOf(ByteArray(20), ByteArray(24) { 0xFF.toByte() })) {
            assertNull(PlaudAdvertisement.parseManufacturerData(blob))
            assertNull(PlaudAdvertisement.parse(0x005D, blob))
            assertFalse(PlaudAdvertisement.shouldList(false, blob, null))
            assertFalse(PlaudAdvertisement.shouldList(false, blob, "JBL Flip 6"))
        }
    }

    // ---- #93 unlock -------------------------------------------------------

    @Test
    fun unlock_succeeds_with_matching_credentials_and_traffic_is_safe() = runTest {
        val r = rig(e2ee = true)
        r.ble.connect("fake-note-pro")
        val c = PlaudDeviceClient(r.ble, cipher, keys)
        c.unlock()
        assertNotNull(c.sessionSecret)
        c.handshake(); c.listSessions(); c.readBattery()
        val sent = decode(r.ble.segments.single(), c.sessionSecret)
        assertSafe(sent)
        c.close()
    }

    @Test
    fun rejected_signature_does_not_establish_a_session() = runTest {
        val r = rig(e2ee = true)
        r.ble.connect("fake-note-pro")
        val wrong = keys.copy(serialSignature = ByteArray(256) { 7 })
        val c = PlaudDeviceClient(r.ble, cipher, wrong)
        val e = assertFailsWith<IllegalStateException> { c.unlock() }
        assertNoIdentityLeak(e, wrong.serialSignature, sig)
        assertNull(c.sessionSecret)
        // Only FE10 chunks went out: no public key, no app commands.
        val sent = decode(r.ble.segments.single(), null)
        assertTrue(sent.all { it == Sent.Raw(Commands.UNLOCK_SN_SIGNATURE) })
        // The device never offered a session secret.
        assertFalse(r.deviceRawOpcodes().contains(Commands.UNLOCK_SESSION_SECRET))
        c.close()
    }

    @Test
    fun missing_or_short_signature_cannot_be_imported() {
        for (bad in listOf(ByteArray(0), ByteArray(255))) {
            val e = assertFailsWith<IllegalArgumentException> { keys.copy(serialSignature = bad) }
            assertTrue(e.message!!.contains("serialSignature"))
        }
    }

    // ---- #93 reconnect + battery -----------------------------------------

    @Test
    fun reconnect_gets_a_fresh_working_session_and_old_secret_is_not_reused() = runTest {
        val r = rig(e2ee = true)
        r.ble.connect("fake-note-pro")
        val c1 = PlaudDeviceClient(r.ble, cipher, keys)
        c1.unlock(); c1.handshake()
        val s1 = c1.sessionSecret!!
        assertEquals(1, c1.listSessions().total)
        c1.close()
        r.ble.disconnect()

        r.ble.connect("fake-note-pro")
        val c2 = PlaudDeviceClient(r.ble, cipher, keys)
        c2.unlock(); c2.handshake()
        val s2 = c2.sessionSecret!!
        assertFalse(s1.key.contentEquals(s2.key), "new connection negotiates a new key")
        assertEquals(1, c2.listSessions().total)
        assertEquals(0x5d, c2.readBattery().percent)
        assertEquals(2, r.ble.segments.size)
        r.ble.segments.zip(listOf(s1, s2)).forEach { (seg, s) -> assertSafe(decode(seg, s)) }
        c2.close()
    }

    /** Polled (cmd 9 request/response). The fake never pushes unsolicited battery notifications. */
    @Test
    fun battery_polled_reading_follows_the_peripheral_level() = runTest {
        for (level in listOf(0, 17, 100)) {
            val r = rig(e2ee = true, battery = level)
            r.ble.connect("fake-note-pro")
            val c = PlaudDeviceClient(r.ble, cipher, keys)
            c.unlock(); c.handshake()
            assertEquals(level, c.readBattery().percent)
            assertSafe(decode(r.ble.segments.single(), c.sessionSecret))
            c.close()
        }
    }

    // ---- #97 FE10-only ----------------------------------------------------

    @Test
    fun fe10_only_session_lists_downloads_and_reconnects_without_fe11_or_fe12() = runTest {
        val r = rig(e2ee = false)
        val secrets = mutableListOf<Protocol20SessionSecret>()
        repeat(2) { round ->
            r.ble.connect("fake-note-pro")
            val c = PlaudDeviceClient(r.ble, cipher, keys)
            c.unlock(); c.handshake()
            secrets += c.sessionSecret!!
            val list = c.listSessions()
            assertEquals(1, list.total)
            val bytes = c.downloadSession(list.entries[0].sessionId, list.entries[0].sizeBytes)
            assertContentEquals(encrypted, bytes, "round $round")
            c.close()
            r.ble.disconnect()
        }
        r.ble.segments.forEachIndexed { i, seg ->
            val sent = decode(seg, secrets[i])
            assertSafe(sent)
            val raw = sent.filterIsInstance<Sent.Raw>().map { it.opcode }.toSet()
            assertEquals(setOf(Commands.UNLOCK_SN_SIGNATURE), raw, "app only sends FE10 before the secret")
        }
        val fromDevice = r.deviceRawOpcodes().toSet()
        assertFalse(Commands.UNLOCK_NEED_PUBLIC_KEY in fromDevice, "device never asked for FE11 key")
        assertTrue(Commands.UNLOCK_SESSION_SECRET in fromDevice)
    }

    @Test
    fun e2ee_enabled_handshake_still_uses_fe10_then_public_key_then_secret() = runTest {
        val r = rig(e2ee = true)
        r.ble.connect("fake-note-pro")
        val c = PlaudDeviceClient(r.ble, cipher, keys)
        c.unlock(); c.handshake()
        val raw = decode(r.ble.segments.single(), c.sessionSecret).filterIsInstance<Sent.Raw>().map { it.opcode }
        assertEquals(Commands.UNLOCK_SN_SIGNATURE, raw.first())
        assertTrue(Commands.UNLOCK_SESSION_SECRET in raw, "public key chunks sent as FE12")
        assertEquals(
            listOf(Commands.UNLOCK_NEED_PUBLIC_KEY, Commands.UNLOCK_SESSION_SECRET),
            r.deviceRawOpcodes().filter { it in 0xFE10..0xFE12 }.distinct(),
        )
        c.close()
    }

    @Test
    fun fe10_rejection_by_peripheral_surfaces_as_error_and_does_not_fall_back_to_fe11() = runTest {
        val r = rig(e2ee = false)
        r.ble.connect("fake-note-pro")
        val c = PlaudDeviceClient(r.ble, cipher, keys.copy(serialSignature = ByteArray(256) { 9 }))
        // The error comes from the fake failing the write; the client adds no FE10-specific error.
        val e = assertFailsWith<IllegalStateException> { c.unlock() }
        assertNoIdentityLeak(e, ByteArray(256) { 9 }, sig)
        assertEquals(setOf(Sent.Raw(Commands.UNLOCK_SN_SIGNATURE)), decode(r.ble.segments.single(), null).toSet())
        assertTrue(r.deviceFrames.isEmpty(), "no FE11/FE12 offered after rejection")
        c.close()
    }

    @Test
    fun silent_peripheral_on_fe10_times_out_without_sending_fe11_or_fe12() = runTest {
        val r = rig(e2ee = false)
        r.ble.connect("fake-note-pro")
        r.ble.swallowWrites = true
        val c = PlaudDeviceClient(r.ble, cipher, keys, responseTimeout = 200.milliseconds)
        val e = assertFailsWith<TimeoutCancellationException> { c.unlock() }
        assertNoIdentityLeak(e, sig)
        assertNull(c.sessionSecret)
        assertEquals(setOf(Sent.Raw(Commands.UNLOCK_SN_SIGNATURE)), decode(r.ble.segments.single(), null).toSet())
        c.close()
    }

    @Test
    fun list_and_download_failures_surface_as_timeouts_without_fe11() = runTest {
        // list: device goes silent after unlock
        run {
            val r = rig(e2ee = false)
            r.ble.connect("fake-note-pro")
            val c = PlaudDeviceClient(r.ble, cipher, keys, responseTimeout = 200.milliseconds)
            c.unlock(); c.handshake()
            r.ble.swallowWrites = true
            assertNoIdentityLeak(assertFailsWith<TimeoutCancellationException> { c.listSessions() }, sig)
            assertFalse(Commands.UNLOCK_NEED_PUBLIC_KEY in r.deviceRawOpcodes())
            assertSafe(decode(r.ble.segments.single(), c.sessionSecret))
            c.close()
        }
        // download: device never answers sync start
        run {
            val r = rig(e2ee = false)
            r.ble.connect("fake-note-pro")
            val c = PlaudDeviceClient(r.ble, cipher, keys, responseTimeout = 200.milliseconds)
            c.unlock(); c.handshake(); c.listSessions()
            r.ble.swallowWrites = true
            assertNoIdentityLeak(assertFailsWith<TimeoutCancellationException> { c.downloadSession(1_700_000_000L, encrypted.size.toLong()) }, sig)
            assertFalse(Commands.UNLOCK_NEED_PUBLIC_KEY in r.deviceRawOpcodes())
            assertSafe(decode(r.ble.segments.single(), c.sessionSecret))
            c.close()
        }
    }
}
