package dev.velum.app.i18n

/** Localized chrome for the BLE device screen (pt-BR + en). */
object DeviceUiCopy {
    fun tagline(localeTag: String): String =
        if (isPt(localeTag)) "Seu gravador, seus modelos, suas notas."
        else "Your recorder, your models, your notes."

    fun wifiFastTitle(localeTag: String): String =
        if (isPt(localeTag)) "Transferência Wi‑Fi rápida"
        else "Wi‑Fi fast transfer"

    fun wifiFastBody(localeTag: String): String =
        if (isPt(localeTag)) "Wi‑Fi pronto — sessões grandes evitam a transferência lenta por Bluetooth."
        else "Wi‑Fi ready — large sessions skip slow Bluetooth transfer."

    fun sessionsOnDevice(localeTag: String): String =
        if (isPt(localeTag)) "Sessões no aparelho"
        else "Sessions on device"

    fun bleStubbed(localeTag: String): String =
        if (isPt(localeTag)) "Bluetooth neste ambiente é limitado. O pareamento funciona no Android."
        else "Bluetooth on this platform is limited. Pairing works on Android."

    fun readyToScan(localeTag: String): String =
        if (isPt(localeTag)) "Pronto para buscar um gravador compatível."
        else "Ready to scan for a compatible recorder."

    fun scanningRecorders(localeTag: String): String =
        if (isPt(localeTag)) "Buscando gravadores compatíveis…"
        else "Scanning for compatible recorders…"

    fun connectingTo(localeTag: String, deviceId: String): String =
        if (isPt(localeTag)) "Conectando a $deviceId…"
        else "Connecting to $deviceId…"

    fun connected(localeTag: String, label: String): String =
        if (isPt(localeTag)) "Conectado: $label"
        else "Connected: $label"

    fun noDevicesYet(localeTag: String): String =
        if (isPt(localeTag)) "Nenhum aparelho ainda. Ligue o gravador e toque em Buscar."
        else "No devices yet. Power on your recorder and tap Scan."

    fun desktopBleLater(localeTag: String): String =
        if (isPt(localeTag)) "Bluetooth indisponível neste ambiente. Use o app Android para o hardware."
        else "Bluetooth unavailable here. Use the Android app to talk to hardware."

    fun connectionFailed(localeTag: String): String =
        if (isPt(localeTag)) "Falha na conexão. Tente buscar e conectar de novo."
        else "Connection failed. Try scanning and connecting again."

    fun blePermissionBody(localeTag: String): String =
        if (isPt(localeTag)) {
            "O Velum precisa de Bluetooth para encontrar e conectar ao seu gravador."
        } else {
            "Velum needs Bluetooth to find and connect to your recorder."
        }

    fun wifiCapable(localeTag: String): String =
        if (isPt(localeTag)) "Com Wi‑Fi" else "Wi‑Fi capable"

    fun bleOnly(localeTag: String): String =
        if (isPt(localeTag)) "Só Bluetooth" else "BLE only"

    private fun isPt(localeTag: String): Boolean =
        localeTag.startsWith("pt", ignoreCase = true)
}
