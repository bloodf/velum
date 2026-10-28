package dev.velum.app.ui.autoflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ThinDivider
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.autoflow.AutoFlowTemplate
import dev.velum.core.autoflow.AutoFlowTemplateCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoFlowTemplateLibraryScreen(
    onBack: () -> Unit,
    onUseTemplate: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val locale = strings.localeTag
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<AutoFlowTemplate?>(null) }

    val categories = remember { AutoFlowTemplateCatalog.categoriesPresent() }
    val filtered = remember(query, category, locale) {
        AutoFlowTemplateCatalog.search(query, category, locale)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                tint = VelumInk,
                modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(8.dp),
            )
            Text(
                strings.ux.autoFlowTemplates,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = VelumInk,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            strings.ux.autoFlowTemplatesSubtitle,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(VelumSurface)
                .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, strings.search, tint = VelumInkSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                cursorBrush = SolidColor(VelumInk),
                textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(strings.ux.searchAutoFlowTemplates, color = VelumInkSecondary, fontSize = 15.sp)
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close,
                    strings.cancel,
                    tint = VelumInkSecondary,
                    modifier = Modifier.size(20.dp).clickable { query = "" },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip(
                label = strings.everything,
                selected = category == null,
                onClick = { category = null },
            )
            categories.forEach { key ->
                CategoryChip(
                    label = autoFlowCategoryLabel(key, strings),
                    selected = category == key,
                    onClick = { category = key },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ThinDivider(0)
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { template ->
                TemplateRow(
                    template = template,
                    strings = strings,
                    onClick = { preview = template },
                )
                ThinDivider(20)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    preview?.let { template ->
        ModalBottomSheet(
            onDismissRequest = { preview = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        autoFlowTemplateIcon(template.icon),
                        null,
                        tint = VelumInk,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            template.name.forLocale(locale),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = VelumInk,
                        )
                        Text(
                            autoFlowCategoryLabel(template.category, strings),
                            color = VelumInkSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    Icon(
                        Icons.Outlined.Close,
                        strings.cancel,
                        modifier = Modifier.clickable { preview = null },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    template.description.forLocale(locale),
                    color = VelumInkSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    strings.ux.autoFlowTemplatePreview,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = VelumInk,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    template.preview.forLocale(locale),
                    color = VelumInk,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                template.author?.let { author ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        strings.ux.autoFlowTemplateAuthorFormat.replace("%s", author.creditLine()),
                        color = VelumInkSecondary,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                BlackButton(
                    strings.ux.useAutoFlowTemplate,
                    {
                        val id = template.id
                        preview = null
                        onUseTemplate(id)
                    },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TemplateRow(
    template: AutoFlowTemplate,
    strings: Strings,
    onClick: () -> Unit,
) {
    val locale = strings.localeTag
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(VelumSurface)
                .border(1.dp, VelumDivider, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(autoFlowTemplateIcon(template.icon), null, tint = VelumInk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                template.name.forLocale(locale),
                color = VelumInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                template.description.forLocale(locale),
                color = VelumInkSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            template.author?.let { author ->
                Spacer(Modifier.height(4.dp))
                Text(
                    strings.ux.autoFlowTemplateAuthorFormat.replace("%s", author.creditLine()),
                    color = VelumInkSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) VelumBlack else VelumSurface)
            .border(1.dp, if (selected) VelumBlack else VelumDivider, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) VelumOnBlack else VelumInk,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

internal fun autoFlowTemplateIcon(icon: String): ImageVector = when (icon) {
    "meeting" -> Icons.Outlined.AccountTree
    "sales" -> Icons.Outlined.Business
    "interview" -> Icons.Outlined.People
    "medical" -> Icons.Outlined.LocalHospital
    "legal" -> Icons.Outlined.Gavel
    "education" -> Icons.Outlined.School
    "journal" -> Icons.Outlined.Book
    "productivity" -> Icons.Outlined.Checklist
    "research" -> Icons.Outlined.Science
    "content" -> Icons.Outlined.Create
    "estate" -> Icons.Outlined.Home
    "finance" -> Icons.Outlined.AccountBalance
    "code" -> Icons.Outlined.Code
    "device" -> Icons.Outlined.Devices
    else -> Icons.Outlined.AutoAwesome
}

internal fun autoFlowCategoryLabel(category: String, strings: Strings): String = when (category) {
    "meetings" -> strings.ux.autoFlowCatMeetings
    "sales" -> strings.ux.autoFlowCatSales
    "interviews" -> strings.ux.autoFlowCatInterviews
    "medical" -> strings.ux.autoFlowCatMedical
    "legal" -> strings.ux.autoFlowCatLegal
    "education" -> strings.ux.autoFlowCatEducation
    "personal" -> strings.ux.autoFlowCatPersonal
    "productivity" -> strings.ux.autoFlowCatProductivity
    "research" -> strings.ux.autoFlowCatResearch
    "content" -> strings.ux.autoFlowCatContent
    "real_estate" -> strings.ux.autoFlowCatRealEstate
    "finance" -> strings.ux.autoFlowCatFinance
    "it" -> strings.ux.autoFlowCatIt
    "devices" -> strings.ux.autoFlowCatDevices
    else -> category
}
