package dev.velum.ble

actual fun createBleTransport(): BleTransport =
    StubBleTransport(
        isAvailable = false,
        unavailableReason = "Desktop BLE is not wired yet (planned via SimpleBLE). Use Android/iOS for device sync.",
    )
