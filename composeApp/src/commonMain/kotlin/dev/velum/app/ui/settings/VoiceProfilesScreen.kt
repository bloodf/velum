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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    var profiles by remember { mutableStateOf(container.voiceProfileStore.listProfiles()) }
    var selected by remember { mutableStateOf<VoiceProfile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf("") }
    val threshold = container.settingsRepository.settings.value.speakerMatchThreshold

    fun refresh() {
        profiles = container.voiceProfileStore.listProfiles()
        selected = selected?.id?.let { id -> profiles.firstOrNull { it.id == id } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar("Voice profiles", onBack = onBack)
        Column(Modifier.padding(20.dp)) {
            Text(
                "Encrypted on-device speaker embeddings (3D-Speaker / ECAPA). " +
                    "Auto-label uses cosine ≥ ${"%.2f".format(threshold)}. Never uploaded.",
                color = VelumInkSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (profiles.isEmpty()) {
                Text("No profiles yet — name a speaker in a recording to enroll.", color = VelumInkSecondary)
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
                                append("${p.sampleCount} sample(s)")
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
                        container.voiceProfileStore.rename(p.id, name)
                        container.voiceProfileStore.updateMeta(
                            p.id,
                            role = role,
                            company = company,
                            email = email,
                            notes = notes,
                        )
                        refresh()
                        status = "Saved ${name}"
                    },
                    onDelete = {
                        container.voiceProfileStore.delete(p.id)
                        selected = null
                        refresh()
                        status = "Deleted"
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlineButton(
                "Export encrypted",
                {
                    exportText = container.voiceProfileStore.exportEncrypted()
                    status = "Export ready — copy below (stays on device unless you share it)"
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            exportText?.let {
                Text(it.take(400) + if (it.length > 400) "…" else "", color = VelumInkSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }
            Text("Import encrypted JSON", fontWeight = FontWeight.SemiBold, color = VelumInk)
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
                "Import",
                {
                    runCatching {
                        val n = container.voiceProfileStore.importEncrypted(importDraft)
                        refresh()
                        status = "Imported $n profile(s)"
                        importDraft = ""
                    }.onFailure { status = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                "Wipe all profiles",
                {
                    container.voiceProfileStore.wipeAll()
                    selected = null
                    refresh()
                    status = "All profiles wiped"
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Match threshold",
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
                                status = "Threshold $t"
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
                strings.ux.let { "Enroll: name a speaker in Fontes, or record and promote a cluster." },
                color = VelumInkSecondary,
                fontSize = 12.sp,
            )
        }
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
