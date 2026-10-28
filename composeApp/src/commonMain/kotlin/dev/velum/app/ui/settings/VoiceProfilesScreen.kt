package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.local.VoiceKeyPrimingState
import dev.velum.ai.local.VoiceProfile
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import kotlinx.coroutines.launch

/**
 * On-device voice profiles: list / rename / edit meta / delete / export / import / wipe.
 * Embeddings stay encrypted; never leave the device unless the user exports.
 */
@Composable
fun VoiceProfilesScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val priming by container.voiceProfileStore.primingState.collectAsState()
    var profiles by remember { mutableStateOf<List<VoiceProfile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VoiceProfile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    suspend fun refresh() {
        profiles = container.voiceProfileStore.listProfiles()
        selected = selected?.id?.let { id -> profiles.firstOrNull { it.id == id } }
        loaded = true
    }

    LaunchedEffect(Unit) {
        runCatching { refresh() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.voiceProfiles, onBack = onBack)
        Column(Modifier.padding(20.dp)) {
            when (val state = priming) {
                is VoiceKeyPrimingState.Failed -> {
                    Text(
                        strings.ux.voiceProfilesKeyError,
                        color = VelumInkSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    BlackButton(
                        strings.ux.voiceProfilesKeyRetry,
                        {
                            scope.launch {
                                runCatching { container.voiceProfileStore.ensureKey() }
                                runCatching { refresh() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Keep class name only — never key material.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.errorClassName,
                        color = VelumInkSecondary,
                        fontSize = 12.sp,
                    )
                }
                else -> {
                    Text(
                        strings.ux.voiceProfilesHint,
                        color = VelumInkSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!loaded) {
                        Text(strings.ux.voiceProfilesEmpty, color = VelumInkSecondary)
                    } else if (profiles.isEmpty()) {
                        Text(strings.ux.voiceProfilesEmpty, color = VelumInkSecondary)
                    } else {
                        profiles.forEach { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = p }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(p.displayName, fontWeight = FontWeight.SemiBold, color = VelumInk)
                                Text(
                                    buildString {
                                        append(
                                            strings.ux.voiceProfilesSamplesFormat.replace(
                                                "%d",
                                                p.sampleCount.toString(),
                                            ),
                                        )
                                        if (p.role.isNotBlank()) append(" · ${p.role}")
                                        if (p.company.isNotBlank()) append(" · ${p.company}")
                                        if (p.email.isNotBlank()) append(" · ${p.email}")
                                    },
                                    color = VelumInkSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    selected?.let { p ->
                        ProfileEditor(
                            profile = p,
                            onSave = { name, role, company, email, notes ->
                                scope.launch {
                                    runCatching {
                                        container.voiceProfileStore.rename(p.id, name)
                                        container.voiceProfileStore.updateMeta(
                                            p.id,
                                            role = role,
                                            company = company,
                                            email = email,
                                            notes = notes,
                                        )
                                        refresh()
                                        status = strings.ux.voiceProfilesSaved
                                    }.onFailure { status = strings.ux.genericError }
                                }
                            },
                            onDelete = { showDeleteConfirm = true },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlineButton(
                        strings.ux.voiceProfilesExport,
                        {
                            scope.launch {
                                runCatching {
                                    exportText = container.voiceProfileStore.exportEncrypted()
                                    status = strings.ux.voiceProfilesExportReady
                                }.onFailure { status = strings.ux.genericError }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    exportText?.let {
                        Text(
                            it.take(400) + if (it.length > 400) "…" else "",
                            color = VelumInkSecondary,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        strings.ux.voiceProfilesImportTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = VelumInk,
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicTextField(
                        value = importDraft,
                        onValueChange = { importDraft = it },
                        textStyle = TextStyle(color = VelumInk, fontSize = 13.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(VelumBg)
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(
                        strings.ux.voiceProfilesImport,
                        {
                            scope.launch {
                                runCatching {
                                    val n = container.voiceProfileStore.importEncrypted(importDraft)
                                    refresh()
                                    status = strings.ux.voiceProfilesImportedFormat
                                        .replace("%d", n.toString())
                                    importDraft = ""
                                }.onFailure { status = strings.ux.genericError }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(
                        strings.ux.voiceProfilesWipe,
                        { showWipeConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strings.ux.voiceProfilesThreshold,
                        fontWeight = FontWeight.SemiBold,
                        color = VelumInk,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        listOf(0.45f, 0.55f, 0.65f, 0.75f).forEach { t ->
                            OutlineButton(
                                "%.2f".format(t),
                                {
                                    scope.launch {
                                        container.settingsRepository.setSpeakerMatchThreshold(t)
                                        status = strings.ux.voiceProfilesThresholdSet
                                    }
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            )
                        }
                    }
                    status?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = VelumInkSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strings.ux.voiceProfilesEnrollHint,
                        color = VelumInkSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val p = selected
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmDeleteProfileBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        if (p != null) {
                            container.voiceProfileStore.delete(p.id)
                            selected = null
                            scope.launch {
                                runCatching { refresh() }
                                status = strings.ux.voiceProfilesDeleted
                            }
                        }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) }
            },
        )
    }
    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmWipeProfilesBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        container.voiceProfileStore.wipeAll()
                        selected = null
                        scope.launch {
                            runCatching { refresh() }
                            status = strings.ux.voiceProfilesWiped
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun ProfileEditor(
    profile: VoiceProfile,
    onSave: (name: String, role: String, company: String, email: String, notes: String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.displayName) }
    var role by remember(profile.id) { mutableStateOf(profile.role) }
    var company by remember(profile.id) { mutableStateOf(profile.company) }
    var email by remember(profile.id) { mutableStateOf(profile.email) }
    var notes by remember(profile.id) { mutableStateOf(profile.notes) }
    Column {
        val strings = LocalStrings.current
        Field(strings.name, name) { name = it }
        Field(strings.ux.role, role) { role = it }
        Field(strings.ux.company, company) { company = it }
        Field(strings.ux.email, email) { email = it }
        Field(strings.notes, notes) { notes = it }
        Spacer(Modifier.height(8.dp))
        BlackButton(strings.save, { onSave(name, role, company, email, notes) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlineButton(strings.ux.deleteProfile, onDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = VelumInkSecondary, fontSize = 12.sp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}
