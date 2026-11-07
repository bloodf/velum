package dev.velum.ble.gatt

import dev.velum.protocol.Framing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** #19 QA at f3fd513: the opcode guard cannot be weakened by the caller's profile, a seal lambda, or an odd frame shape. */
class GuardHardeningTest {
    private val char = GattProfiles.PLAUD_WRITE
    private val fe20 = byteArrayOf(Framing.APP_PREFIX, 0x20, 0xFE.toByte())

    @Test fun a_custom_seal_is_not_reachable_from_outside_the_module() {
        // The overload that takes a seal lambda is internal, so it is not a public JVM method. A public one would let any
        // caller put chosen bytes on the wire after an innocent plaintext passed (QA probe: 0120fe after a cmd 9).
        val publicWithLambda = GuardedLink::class.java.methods.filter { m ->
            m.name == "write" && m.parameterTypes.any { it.name == "kotlin.jvm.functions.Function1" }
        }
        assertEquals(emptyList(), publicWithLambda.map { it.toString() })
    }

    @Test fun the_transport_check_rejects_a_plain_forbidden_write_to_2BB1_and_nothing_else() {
        assertFailsWith<IllegalArgumentException> { requireTransportAllowed(char, fe20, sealed = false) }
        requireTransportAllowed(char, byteArrayOf(Framing.APP_PREFIX, 0x09), sealed = false)
        // Sealed bytes are never parsed; other characteristics belong to other guards.
        requireTransportAllowed(char, fe20, sealed = true)
        requireTransportAllowed(GattChar("other", "other"), fe20, sealed = false)
    }
}
