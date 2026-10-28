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

    fun noSessionsOnDevice(localeTag: String): String =
        if (isPt(localeTag)) {
            "Nenhuma sessão no aparelho. Grave no gravador e toque em Buscar de novo."
        } else {
            "No sessions on the device. Record on the recorder, then scan again."
        }

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

    fun sessionNotFound(localeTag: String): String =
        if (isPt(localeTag)) "Sessão não encontrada no aparelho."
        else "Session not found on device."

    fun notConnected(localeTag: String): String =
        if (isPt(localeTag)) "Não conectado. Conecte e desbloqueie primeiro."
        else "Not connected. Connect and unlock first."

    fun importKeysToDownload(localeTag: String): String =
        if (isPt(localeTag)) "Importe as chaves de identidade do aparelho para baixar."
        else "Import device identity keys to download."

    fun downloadingSession(localeTag: String, sessionId: Long): String =
        if (isPt(localeTag)) "Baixando sessão #$sessionId…"
        else "Downloading session #$sessionId…"

    fun downloadedSession(localeTag: String, sessionId: Long): String =
        if (isPt(localeTag)) "Sessão #$sessionId baixada"
        else "Downloaded session #$sessionId"

    fun downloadFailed(localeTag: String): String =
        if (isPt(localeTag)) "Falha no download. Verifique a conexão e tente de novo."
        else "Download failed. Check the connection and try again."

    fun downloadCancelled(localeTag: String): String =
        if (isPt(localeTag)) "Download cancelado."
        else "Download cancelled."

    fun downloadNotificationTitle(localeTag: String): String =
        if (isPt(localeTag)) "Baixando sessão do aparelho"
        else "Downloading device session"

    fun modelDownloadNotificationTitle(localeTag: String, name: String): String =
        if (isPt(localeTag)) "Baixando $name"
        else "Downloading $name"

    fun unsupportedRecorder(localeTag: String): String =
        if (isPt(localeTag)) "Este modelo de gravador não é suportado nesta versão."
        else "This recorder model is not supported in this version."

    fun connectedImportKeys(localeTag: String): String =
        if (isPt(localeTag)) "Conectado. Importe as chaves de identidade para sincronizar."
        else "Connected. Import device identity keys to sync."

    fun connectedKeysIncomplete(localeTag: String): String =
        if (isPt(localeTag)) "Conectado. Chaves de identidade incompletas."
        else "Connected. Identity keys incomplete."

    fun unlocking(localeTag: String): String =
        if (isPt(localeTag)) "Desbloqueando…"
        else "Unlocking…"

    fun unlockFailed(localeTag: String): String =
        if (isPt(localeTag)) "Não foi possível desbloquear o aparelho. Verifique as chaves e tente de novo."
        else "Could not unlock the device. Check keys and try again."

    fun readyStatus(
        localeTag: String,
        displayName: String,
        protocolVersion: Int,
        wifiPreferred: Boolean,
        wifiCapable: Boolean,
    ): String {
        val base = if (isPt(localeTag)) {
            "Pronto · $displayName · protocolo $protocolVersion"
        } else {
            "Ready · $displayName · protocol $protocolVersion"
        }
        val transfer = when {
            wifiPreferred -> if (isPt(localeTag)) {
                " · Wi‑Fi preferido (download BLE ativo)"
            } else {
                " · Wi‑Fi preferred (BLE download active)"
            }
            wifiCapable -> if (isPt(localeTag)) {
                " · Transferência BLE (Wi‑Fi preferido desligado)"
            } else {
                " · BLE transfer (Wi‑Fi preferred off)"
            }
            else -> if (isPt(localeTag)) " · Transferência BLE" else " · BLE transfer"
        }
        return base + transfer
    }

    fun downloadPercent(localeTag: String, percent: Int): String =
        if (isPt(localeTag)) "$percent%"
        else "$percent%"

    fun cancel(localeTag: String): String =
        if (isPt(localeTag)) "Cancelar" else "Cancel"

    private fun isPt(localeTag: String): Boolean =
        localeTag.startsWith("pt", ignoreCase = true)
}
