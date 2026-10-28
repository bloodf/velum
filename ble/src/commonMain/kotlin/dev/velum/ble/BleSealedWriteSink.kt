package dev.velum.ble

/**
 * Module-internal sealed-write contract.
 *
 * Concrete transports implement this via [AbstractBleTransport] with an
 * **explicit** `internal override` of [writeSealedAlreadyGuarded]. A bare
 * (public) override on a public class does **not** inherit internal visibility.
 */
internal interface BleSealedWriteSink {
    /**
     * Write already-sealed bytes without opcode parsing.
     *
     * Callers must have already run [PlaudWriteGuard.requireAllowedPlaintext].
     */
    suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}

/**
 * Public transport base that carries the sealed-write entry as an
 * **`internal abstract`** member so subclasses can use `internal override`.
 *
 * (Kotlin forbids `internal override` of a public interface member on a public
 * class; the abstract member here is itself `internal`, which allows it.)
 */
abstract class AbstractBleTransport : BleTransport {
    /**
     * Sealed write — visible only inside the `:ble` module.
     *
     * This is the [BleSealedWriteSink.writeSealedAlreadyGuarded] entry point on
     * concrete transports ([FakeBleTransport], [StubBleTransport],
     * [KableBleTransport]).
     */
    internal abstract suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}
