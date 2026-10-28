package dev.velum.ble.publicapi

import dev.velum.ble.BleTransport
import dev.velum.ble.GattProfiles
import dev.velum.ble.PlaudBleTransport
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.protocol.Bytes
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.Framing
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Compiles against the **public** `:ble` surface from a sibling package.
 *
 * Documents that [BleTransport.write] has no safety/opt-out parameter — the
 * compiler enforces this for other modules; sealed writes are only reachable
 * through [PlaudBleTransport.writeSealedAfterPlaintextGuard] after a plaintext
 * check (`BleSealedWriteSink` is `internal` to `:ble` and is not a member of
 * [BleTransport]).
 */
class PublicBleTransportGuardTest {
    @Test
    fun publicWriteHasNoSafetyOptOutParameter() {
        val writeMethods = BleTransport::class.java.methods.filter { it.name == "write" }
        assertTrue(writeMethods.isNotEmpty(), "expected public write on BleTransport")
        writeMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers), "write must stay public")
            val paramSimpleNames = method.parameterTypes.map { it.simpleName }
            assertFalse(
                paramSimpleNames.any { it.contains("BleWriteSafety") },
                "BleWriteSafety must not appear on public write: $paramSimpleNames",
            )
            assertFalse(
                method.parameters.any { it.name.equals("safety", ignoreCase = true) },
                "public write must not expose a safety parameter",
            )
        }
        assertTrue(
            BleTransport::class.java.methods.none { it.name == "writeSealedAlreadyGuarded" },
            "sealed sink must not be a member of public BleTransport",
        )
        // BleWriteSafety was removed; Class.forName must fail for that public opt-out enum.
        assertFailsWith<ClassNotFoundException> {
            Class.forName("dev.velum.ble.BleWriteSafety")
        }
    }

    @Test
    fun publicWrite_alwaysRejectsForbiddenPlaintextOn2bb1() = runTest {
        val fake = FakeBleTransport()
        val ble: BleTransport = fake
        ble.connect("fake-note-pro", GattProfiles.Plaud)

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("public write opcode $op") {
                ble.write(GattProfiles.PLAUD_WRITE, rawAppCommand(op))
            }
        }
        assertTrue(fake.recordedWrites.isEmpty())
    }

    @Test
    fun writeSealedAfterPlaintextGuard_isPublicEntryPoint() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val plaud = PlaudBleTransport(ble)

        // (c) sealed path works for safe plaintext + forbidden-looking ciphertext
        val sealedLookalike = rawAppCommand(ForbiddenOpcodes.CLEAR_ALL)
        plaud.writeSealedAfterPlaintextGuard(
            plaintext = rawAppCommand(9),
            sealed = sealedLookalike,
        )
        assertEquals(1, ble.recordedWrites.size)
        assertTrue(ble.recordedWrites.single().second.contentEquals(sealedLookalike))

        ble.clearRecordedWrites()
        // Forbidden plaintext still rejected before any sealed write
        assertFailsWith<IllegalArgumentException> {
            plaud.writeSealedAfterPlaintextGuard(
                plaintext = rawAppCommand(ForbiddenOpcodes.OTA),
                sealed = byteArrayOf(1, 2, 3),
            )
        }
        assertTrue(ble.recordedWrites.isEmpty())
    }

    private fun rawAppCommand(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val raw = ByteArray(1 + 2 + params.size)
        raw[0] = Framing.APP_PREFIX
        Bytes.u16Le(cmd).copyInto(raw, 1)
        params.copyInto(raw, 3)
        return raw
    }
}
