package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.AdvertisementFixtures
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.PlaudOutboundGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * #93 regression: advertising, FE10 unlock, reconnect, and battery against a
 * simulated Note Pro peripheral built from protocol fixtures. Failure paths
 * (wrong unlock, timeouts, disconnect, malformed battery) included.
 *
 * Real Note Pro hardware remains unconfirmed (#5).
 */
class SimulatedPeripheralRegressionTest {
    @Test
    fun scan_parses881PortVersion_fromFixtures_skipsMalformed() = runTest {
        val fixtures = listOf(
            AdvertisementFixtures.notePro881(portVersion = 20, serial = "881FAKE1"),
            AdvertisementFixtures.tooShort(),
            AdvertisementFixtures.empty(),
            AdvertisementFixtures.allZeros(),
            AdvertisementFixtures.truncatedBeforePortVersion(),
            AdvertisementFixtures.otherModel(),
            AdvertisementFixtures.notePro881WrongPort(0),
            AdvertisementFixtures.notePro883(portVersion = 20, serial = "883FAKE1"),
        )
        val ble = FakeBleTransport(
            advertisementFixtures = fixtures,
            deviceId = "fake-note-pro",
        )
        ble.startScan()
        val devices = ble.discoveredDevices.value
        assertEquals(2, devices.size, "only Note Pro family discoveries must surface")
        assertEquals(DeviceModel.NotePro.code, devices[0].modelCode)
        assertEquals(20, devices[0].protocolVersion)
        assertEquals("881FAKE1", devices[0].serial)
        assertEquals(DeviceModel.NotePro883.code, devices[1].modelCode)
        assertEquals(20, devices[1].protocolVersion)

        val adv = PlaudAdvertisement.parseNoteProFamilyDiscovery(fixtures[0])
        assertNotNull(adv)
        assertEquals(20, adv.portVersion)
        assertEquals(881, adv.modelCode)
    }

    @Test
    fun unlock_succeedsWithMatchingSyntheticCredentials() = runTest {
        val env = unlockedEnv(requirePublicKey = true)
        assertTrue(env.client.isUnlocked)
        assertNotNull(env.client.sessionSecret)
        assertTrue(env.peripheral.isUnlocked)
        assertEquals(20, env.client.handshake().protocolVersion)
        assertNoForbiddenPlaintext(env.ble.recordedWrites)
        env.client.close()
    }

    @Test
    fun unlock_rejectedCredentials_doNotEstablishSession() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val matching = syntheticKeys(publicPem, privatePem, signatureFill = 1)
        val wrong = syntheticKeys(publicPem, privatePem, signatureFill = 2)
        val cipher = createSessionCipher()
        val file = tinyEncryptedFile(publicPem)
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = matching,
            sessionFile = file,
            requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, wrong, responseTimeout = 500.milliseconds)
        assertFailsWith<IllegalStateException> {
            client.unlock()
        }
        assertFalse(client.isUnlocked)
        assertNull(client.sessionSecret)
        assertFalse(peripheral.isUnlocked)
        client.close()
    }

    @Test
    fun unlock_emptyFe10_rejectedWithoutSession() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = syntheticKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = tinyEncryptedFile(publicPem),
            requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        // Crafted FE10 with empty payload (cannot come from Unlock.encodeSnSignatureChunks).
        val emptyFe10 = Bytes.u16Le(Commands.UNLOCK_SN_SIGNATURE) + byteArrayOf(1, 0)
        assertFailsWith<IllegalStateException> {
            ble.write(GattProfiles.PLAUD_WRITE, emptyFe10)
        }
        assertFalse(peripheral.isUnlocked)
    }

    @Test
    fun unlock_timeoutWhenPeripheralMutes_leavesSessionLocked() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = syntheticKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = tinyEncryptedFile(publicPem),
            requirePublicKey = false,
        )
        peripheral.muteResponses = true
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 200.milliseconds)
        assertFailsWith<TimeoutCancellationException> {
            client.unlock()
        }
        assertFalse(client.isUnlocked)
        assertNull(client.sessionSecret)
        client.close()
    }

    @Test
    fun unlock_disconnectMidHandshake_doesNotLeaveUnlockedSession() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = syntheticKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = tinyEncryptedFile(publicPem),
            requirePublicKey = true,
        )
        peripheral.muteResponses = true
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 400.milliseconds)
        val unlockJob = async {
            runCatching { client.unlock() }
        }
        // Disconnect while the client is waiting for FE11 / FE12.
        ble.disconnect()
        val result = unlockJob.await()
        assertTrue(result.isFailure)
        assertFalse(client.isUnlocked)
        assertNull(client.sessionSecret)
        client.close()
    }

    @Test
    fun reconnect_requiresFe10_andSessionRemainsUsable() = runTest {
        val env = unlockedEnv(requirePublicKey = false)
        env.client.handshake()
        val batteryBefore = env.client.readBattery()
        assertEquals(0x5d, batteryBefore.percent)
        assertNoForbiddenPlaintext(env.ble.recordedWrites)
        env.client.close()

        env.ble.disconnect()
        assertFalse(env.peripheral.isUnlocked)

        env.ble.connect("fake-note-pro")
        val client2 = PlaudDeviceClient(
            env.ble,
            env.cipher,
            env.keys,
            responseTimeout = 5_000.milliseconds,
        )
        client2.unlock()
        assertTrue(client2.isUnlocked)
        assertEquals(20, client2.handshake().protocolVersion)
        val batteryAfter = client2.readBattery()
        assertEquals(0x5d, batteryAfter.percent)
        assertNoForbiddenPlaintext(env.ble.recordedWrites)
        client2.close()
    }

    @Test
    fun battery_readAndUnsolicitedNotification_updateLevel() = runTest {
        val env = unlockedEnv(requirePublicKey = false)
        env.client.handshake()

        val polled = env.client.readBattery()
        assertEquals(93, polled.percent)

        env.peripheral.batteryPercent = 76
        val notifyJob = async {
            env.client.awaitBatteryNotification(timeout = 2_000.milliseconds)
        }
        env.peripheral.emitUnsolicitedBattery(76)
        val notified = notifyJob.await()
        assertEquals(76, notified.percent)

        // Application path poll after notify still returns the updated level.
        val again = env.client.readBattery()
        assertEquals(76, again.percent)
        assertNoForbiddenPlaintext(env.ble.recordedWrites)
        env.client.close()
    }

    @Test
    fun battery_malformedFrame_failsWithoutCorruptReading() = runTest {
        val env = unlockedEnv(requirePublicKey = false)
        env.client.handshake()
        env.peripheral.batteryPayloadOverride = ByteArray(0)
        assertFailsWith<IllegalArgumentException> {
            env.client.readBattery()
        }
        env.peripheral.batteryPayloadOverride = byteArrayOf(0x00, 0xFF.toByte())
        assertFailsWith<IllegalArgumentException> {
            env.client.readBattery()
        }
        // Valid frame still works after malformed attempts.
        env.peripheral.batteryPercent = 55
        assertEquals(55, env.client.readBattery().percent)
        env.client.close()
    }

    @Test
    fun unlockAndBatteryPath_cannotBypassForbiddenOpcodeGuards() = runTest {
        val env = unlockedEnv(requirePublicKey = false)
        env.client.handshake()
        env.ble.clearRecordedWrites()

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("opcode $op") {
                env.client.writeCommandForTest(op)
            }
            assertTrue(
                env.ble.recordedWrites.isEmpty(),
                "forbidden opcode must not reach recorded transport writes",
            )
            assertFailsWith<IllegalArgumentException>("transport opcode $op") {
                env.ble.write(
                    GattProfiles.PLAUD_WRITE,
                    ForbiddenOpcodeLayersTest.rawAppCommand(op),
                )
            }
            assertTrue(env.ble.recordedWrites.isEmpty())
        }
        listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC).forEach { setting ->
            assertFailsWith<IllegalArgumentException> {
                env.client.writeCommandForTest(8, byteArrayOf(setting.toByte(), 1))
            }
            assertFailsWith<IllegalArgumentException> {
                env.ble.write(
                    GattProfiles.PLAUD_WRITE,
                    ForbiddenOpcodeLayersTest.rawAppCommand(8, byteArrayOf(setting.toByte(), 1)),
                )
            }
            assertTrue(env.ble.recordedWrites.isEmpty())
        }

        // Safe battery request still allowed through both layers.
        PlaudOutboundGuard.requireAllowed(Framing.encodeCommand(Commands.BATTERY_LIKELY))
        assertEquals(93, env.client.readBattery().percent)
        assertNoForbiddenPlaintext(env.ble.recordedWrites)
        env.client.close()
    }

    /**
     * After simulated unlock, sealed writes go through
     * [PlaudBleTransport.writeSealedAfterPlaintextGuard]: allowed plaintext
     * seals and records a write; forbidden opcodes / settings 4 & 26 are
     * rejected on plaintext before any sealed bytes are recorded.
     *
     * Assertions use write counts only — never inspect ciphertext.
     */
    @Test
    fun encryptedSession_sealedWrite_guardsPlaintextBeforeSeal() = runTest {
        val env = unlockedEnv(requirePublicKey = false)
        env.client.handshake()
        assertTrue(env.client.isUnlocked)
        env.ble.clearRecordedWrites()

        // Allowed plaintext → sealed path succeeds (real seal via client).
        assertEquals(93, env.client.readBattery().percent)
        assertTrue(
            env.ble.recordedWrites.isNotEmpty(),
            "allowed sealed battery write must reach transport after unlock",
        )
        env.ble.clearRecordedWrites()

        val plaud = PlaudBleTransport(env.ble)
        // Dummy sealed payload is never inspected and must not be recorded
        // when plaintext is rejected.
        val dummySealed = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

        // Explicit forbidden set: 104, 5, 0xFE20 (LE [0x20,0xFE]), 128, 143.
        val forbiddenOps = listOf(
            ForbiddenOpcodes.CLEAR_ALL,
            ForbiddenOpcodes.DEPAIR,
            ForbiddenOpcodes.FE20,
            ForbiddenOpcodes.RESET_FINDMY,
            ForbiddenOpcodes.OTA,
        )
        assertEquals(setOf(104, 5, 0xFE20, 128, 143), forbiddenOps.toSet())
        assertEquals(
            byteArrayOf(0x20, 0xFE.toByte()).toList(),
            Bytes.u16Le(ForbiddenOpcodes.FE20).toList(),
        )

        forbiddenOps.forEach { op ->
            assertFailsWith<IllegalArgumentException>("sealed-path plaintext opcode $op") {
                plaud.writeSealedAfterPlaintextGuard(
                    plaintext = ForbiddenOpcodeLayersTest.rawAppCommand(op),
                    sealed = dummySealed,
                )
            }
            assertTrue(
                env.ble.recordedWrites.isEmpty(),
                "forbidden plaintext $op must not reach sealed write",
            )
            assertFailsWith<IllegalArgumentException>("client sealed-path opcode $op") {
                env.client.writeCommandForTest(op)
            }
            assertTrue(env.ble.recordedWrites.isEmpty())
        }

        listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC).forEach { setting ->
            assertTrue(setting == 4 || setting == 26, "expected settings 4 or 26, got $setting")
            assertFailsWith<IllegalArgumentException>("sealed-path setting $setting") {
                plaud.writeSealedAfterPlaintextGuard(
                    plaintext = ForbiddenOpcodeLayersTest.rawAppCommand(
                        8,
                        byteArrayOf(setting.toByte(), 1),
                    ),
                    sealed = dummySealed,
                )
            }
            assertTrue(env.ble.recordedWrites.isEmpty())
            assertFailsWith<IllegalArgumentException>("client sealed-path setting $setting") {
                env.client.writeCommandForTest(8, byteArrayOf(setting.toByte(), 1))
            }
            assertTrue(env.ble.recordedWrites.isEmpty())
        }

        // Allowed sealed write still works after rejection attempts.
        assertEquals(93, env.client.readBattery().percent)
        assertTrue(env.ble.recordedWrites.isNotEmpty())
        env.client.close()
    }

    private data class Env(
        val keys: DeviceIdentityKeys,
        val cipher: dev.velum.protocol.SessionCipher,
        val peripheral: FakePlaudPeripheral,
        val ble: FakeBleTransport,
        val client: PlaudDeviceClient,
    )

    private suspend fun unlockedEnv(requirePublicKey: Boolean): Env {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = syntheticKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = tinyEncryptedFile(publicPem),
            requirePublicKey = requirePublicKey,
            batteryPercent = 0x5d,
        )
        val ble = FakeBleTransport(
            peripheral = peripheral,
            advertisementFixtures = listOf(
                AdvertisementFixtures.notePro881(portVersion = 20, serial = keys.serialNumber),
            ),
        )
        ble.startScan()
        ble.connect(ble.discoveredDevices.value.first().id)
        val client = PlaudDeviceClient(
            ble,
            cipher,
            keys,
            responseTimeout = 5_000.milliseconds,
        )
        client.unlock()
        return Env(keys, cipher, peripheral, ble, client)
    }

    private fun syntheticKeys(
        publicPem: String,
        privatePem: String,
        signatureFill: Int = 7,
    ) = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = ByteArray(256) { ((it + signatureFill) % 251).toByte() },
        publicKeyPem = publicPem,
        privateKeyPem = privatePem,
        bindToken = "synth-tok",
    )

    private fun tinyEncryptedFile(publicPem: String): ByteArray =
        (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(1, 2, 3, 4),
        )

    /**
     * Inspect recorded Plaud writes that are still plaintext (unlock / pre-link).
     * Sealed link frames are opaque; forbidden opcodes are blocked before seal
     * and again at the transport guard for plaintext APP frames.
     */
    private fun assertNoForbiddenPlaintext(writes: List<Pair<String, ByteArray>>) {
        for ((characteristic, payload) in writes) {
            if (characteristic != GattProfiles.PLAUD_WRITE) continue
            if (payload.size < 2) continue
            val looksPlainApp =
                payload[0] == Framing.APP_PREFIX && payload.size >= 3
            val looksUnlock =
                !looksPlainApp && payload.size >= 4 &&
                    Bytes.readU16Le(payload, 0) in setOf(
                        Commands.UNLOCK_SN_SIGNATURE,
                        Commands.UNLOCK_NEED_PUBLIC_KEY,
                        Commands.UNLOCK_SESSION_SECRET,
                    )
            if (!looksPlainApp && !looksUnlock) continue
            PlaudOutboundGuard.requireAllowed(payload)
            val opcode = if (looksPlainApp) {
                Bytes.readU16Le(payload, 1)
            } else {
                Bytes.readU16Le(payload, 0)
            }
            assertFalse(
                ForbiddenOpcodes.isForbidden(opcode),
                "recorded plaintext opcode $opcode must not be forbidden",
            )
            if (looksPlainApp && opcode == 8 && payload.size > 4) {
                val setting = payload[3].toInt() and 0xFF
                assertFalse(
                    ForbiddenSettings.isForbidden(setting),
                    "recorded settings write $setting must not be forbidden",
                )
            }
        }
    }
}
