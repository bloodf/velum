package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.rememberJsonFileImporter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.core.security.PlaudExportImporter
import dev.velum.core.security.PlaudExportParseResult
import kotlinx.coroutines.launch

/**
 * Import from Plaud: paste identity fields and/or paste/import a version-1 JSON export
 * (see docs/PLAUD_IMPORT.md). Stored in Android Keystore via [SecureStore].
 * sn-sign cannot be generated locally (blocked-on-owner).
 */
@Composable
fun ImportDeviceKeysScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var serial by remember { mutableStateOf("") }
    var snSignature by remember { mutableStateOf("") }
    var bindToken by remember { mutableStateOf("") }
    var publicPem by remember { mutableStateOf("") }
    var privatePem by remember { mutableStateOf("") }
    var jsonDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasKeys by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasKeys = container.deviceKeyImporter.hasKeys()
    }

    fun applyJson(raw: String) {
        scope.launch {
            error = null
            status = null
            try {
                when (
                    val parsed = PlaudExportImporter.importInto(
                        rawJson = raw,
                        keys = container.deviceKeyImporter,
                        upsertTemplate = { tpl ->
                            container.repository.upsertTemplate(
                                id = tpl.localId,
                                name = tpl.name,
                                category = tpl.category,
                                description = tpl.description,
                                systemPrompt = tpl.systemPrompt,
                                builtin = false,
                            )
                        },
                    )
                ) {
                    is PlaudExportParseResult.Success -> {
                        serial = parsed.device.serial
                        snSignature = parsed.device.snSignatureBase64
                        bindToken = parsed.device.bindToken
                        publicPem = parsed.device.publicKeyPem.orEmpty()
                        privatePem = parsed.device.privateKeyPem.orEmpty()
                        hasKeys = true
                        jsonDraft = raw
                        status = strings.importFromPlaudJsonOk
                    }
                    is PlaudExportParseResult.Failure -> {
                        error = parsed.message
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            }
        }
    }

    val pickJsonFile = rememberJsonFileImporter { text -> applyJson(text) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.importDeviceKeys, onBack = onBack)
        Text(
            strings.importDeviceKeysHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            strings.importFromPlaudJsonHint,
            color = VelumInkSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlineButton(
            strings.importFromPlaudJson,
            { pickJsonFile() },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = jsonDraft,
            onValueChange = { jsonDraft = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.importFromPlaudJsonPaste) },
            shape = RoundedCornerShape(10.dp),
            minLines = 4,
        )
        OutlineButton(
            strings.importFromPlaudJsonApply,
            { if (jsonDraft.isNotBlank()) applyJson(jsonDraft) },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            enabled = jsonDraft.isNotBlank(),
        )
        if (hasKeys) {
            Text(
                strings.importDeviceKeysPresent,
                color = VelumInk,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.deviceSerial) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )
        OutlinedTextField(
            value = snSignature,
            onValueChange = { snSignature = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.snSignatureB64) },
            shape = RoundedCornerShape(10.dp),
            minLines = 2,
        )
        OutlinedTextField(
            value = bindToken,
            onValueChange = { bindToken = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.bindToken) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )
        OutlinedTextField(
            value = publicPem,
            onValueChange = { publicPem = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.rsaPublicPemOptional) },
            shape = RoundedCornerShape(10.dp),
            minLines = 3,
        )
        OutlinedTextField(
            value = privatePem,
            onValueChange = { privatePem = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            label = { Text(strings.rsaPrivatePemOptional) },
            shape = RoundedCornerShape(10.dp),
            minLines = 3,
        )
        Spacer(Modifier.height(12.dp))
        BlackButton(
            strings.save,
            {
                scope.launch {
                    error = null
                    status = null
                    try {
                        container.deviceKeyImporter.importKeys(
                            serialNumber = serial,
                            serialSignatureBase64 = snSignature,
                            bindToken = bindToken,
                            publicKeyPem = publicPem,
                            privateKeyPem = privatePem,
                        )
                        hasKeys = true
                        status = strings.importDeviceKeysSaved
                    } catch (t: Throwable) {
                        error = t.message ?: t.toString()
                    }
                }
            },
            Modifier.padding(horizontal = 20.dp),
            enabled = serial.isNotBlank() && snSignature.isNotBlank() && bindToken.isNotBlank(),
        )
        Spacer(Modifier.height(8.dp))
        OutlineButton(
            strings.clearDeviceKeys,
            {
                scope.launch {
                    container.deviceKeyImporter.clearKeys()
                    hasKeys = false
                    status = strings.importDeviceKeysCleared
                }
            },
            Modifier.padding(horizontal = 20.dp),
        )
        status?.let {
            Text(it, color = VelumInk, modifier = Modifier.padding(20.dp, 8.dp))
        }
        error?.let {
            Text(it, color = Color(0xFFFF3B30), modifier = Modifier.padding(20.dp, 8.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}
