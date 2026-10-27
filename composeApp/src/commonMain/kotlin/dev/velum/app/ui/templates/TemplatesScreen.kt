package dev.velum.app.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.rememberJsonFileImporter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.DisplayTitle
import dev.velum.app.ui.components.GradientTitle
import dev.velum.app.ui.components.SearchSheet
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.db.Template
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.templates.TemplateJsonImporter
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenTemplate: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val templates by container.repository.observeTemplates().collectAsState(emptyList())
    var tab by remember { mutableStateOf(1) } // 0 my space, 1 discover
    var showCreate by remember { mutableStateOf(false) }
    var showTypeCreate by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var filtered by remember { mutableStateOf<List<Template>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftPrompt by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val languageTag = strings.localeTag
    val importJson = rememberJsonFileImporter { jsonText ->
        scope.launch {
            runCatching {
                val specs = TemplateJsonImporter.parse(jsonText)
                specs.forEachIndexed { index, spec ->
                    container.repository.upsertTemplate(
                        id = TemplateJsonImporter.stableId(spec, index),
                        name = spec.name,
                        category = (spec.categoryId ?: spec.category).lowercase(),
                        description = spec.description,
                        author = spec.author,
                        systemPrompt = spec.resolvedPrompt(),
                        builtin = false,
                    )
                }
                importStatus = "Imported ${specs.size} template(s)"
            }.onFailure {
                importStatus = it.message ?: "Import failed"
            }
        }
    }

    LaunchedEffect(templates, searchQuery) {
        val match = FtsQuery.fromUserInput(searchQuery)
        val ftsIds = if (match != null) {
            try {
                container.repository.searchTemplateIdsFts(match)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        filtered = RecordingSearch.templates(templates, searchQuery, ftsIds)
    }

    val scoped = remember(filtered, tab) {
        when (tab) {
            0 -> filtered.filter { it.builtin == 0L }
            else -> filtered.filter { it.builtin != 0L }
        }
    }
    val categoryTabs = remember(scoped) {
        BuiltinTemplateCatalog.CategoryOrder.filter { key -> scoped.any { it.category == key } } +
            scoped.map { it.category }
                .filter { it !in BuiltinTemplateCatalog.CategoryOrder && it != "mine" }
                .distinct()
    }
    LaunchedEffect(categoryTabs, selectedCategory) {
        if (selectedCategory != null && selectedCategory !in categoryTabs) {
            selectedCategory = null
        }
    }
    val shown = remember(scoped, selectedCategory) {
        val base = if (selectedCategory == null) scoped
        else scoped.filter { it.category == selectedCategory }
        base.sortedByDescending { it.uses }
    }
    val ranking = remember(scoped) { scoped.sortedByDescending { it.uses }.take(5) }

    Column(Modifier.fillMaxSize().background(VelumBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(8.dp),
                tint = VelumInk,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.Search,
                strings.search,
                tint = VelumInk,
                modifier = Modifier.size(22.dp).clickable { showSearch = true },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "JSON",
                color = VelumInk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = importJson).padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(VelumBlack)
                    .clickable { showCreate = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.create, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        importStatus?.let { status ->
            Text(
                status,
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.padding(horizontal = 20.dp)) {
            Tab(strings.mySpace, tab == 0) { tab = 0 }
            Spacer(Modifier.width(24.dp))
            Tab(strings.discover, tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))

        if (tab == 1 && categoryTabs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryChip(
                    label = if (languageTag.lowercase().startsWith("en")) "All" else "Todos",
                    selected = selectedCategory == null,
                ) { selectedCategory = null }
                categoryTabs.forEach { cat ->
                    CategoryChip(
                        label = BuiltinTemplateCatalog.categoryLabel(cat, languageTag),
                        selected = selectedCategory == cat,
                    ) { selectedCategory = cat }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 1 && ranking.isNotEmpty() && searchQuery.isBlank() && selectedCategory == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        DisplayTitle(strings.weeklyRanking)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ranking.forEachIndexed { index, tpl ->
                                RankingCard(tpl, index + 1, languageTag) { onOpenTemplate(tpl.id) }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        DisplayTitle(strings.categories)
                    }
                }
            }
            items(shown, key = { it.id }) { tpl ->
                TemplateGridCard(
                    name = templateDisplayName(tpl, languageTag),
                    description = tpl.description,
                    uses = tpl.uses,
                    modifier = Modifier.fillMaxWidth(),
                ) { onOpenTemplate(tpl.id) }
            }
        }
    }

    if (showSearch) {
        SearchSheet(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearch = false },
            resultCount = shown.size,
        )
    }

    if (showCreate) {
        ModalBottomSheet(
            onDismissRequest = { showCreate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.createTemplate, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Icon(Icons.Outlined.Close, null, modifier = Modifier.clickable { showCreate = false })
                }
                Spacer(Modifier.height(16.dp))
                CreateOption(
                    title = { GradientTitle(strings.photoToTemplate) },
                    icon = { Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFF8B5CF6)) },
                    subtitle = strings.ux.photoToTemplateHint,
                ) { showCreate = false }
                Spacer(Modifier.height(8.dp))
                CreateOption(
                    title = { Text(strings.typeToTemplate, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
                    icon = { Icon(Icons.Outlined.Edit, null, tint = VelumInk) },
                    subtitle = strings.ux.typeToTemplateHint,
                ) {
                    showCreate = false
                    showTypeCreate = true
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showTypeCreate) {
        ModalBottomSheet(
            onDismissRequest = { showTypeCreate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(strings.createTemplate, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (draftName.isEmpty()) {
                            Text(strings.templateName, color = VelumInkSecondary)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = draftDesc,
                    onValueChange = { draftDesc = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (draftDesc.isEmpty()) {
                            Text(strings.categories, color = VelumInkSecondary)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = draftPrompt,
                    onValueChange = { draftPrompt = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (draftPrompt.isEmpty()) {
                            Text(strings.templatePrompt, color = VelumInkSecondary)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(12.dp))
                BlackButton(
                    strings.save,
                    {
                        scope.launch {
                            val id = "tpl-user-${Random.nextInt(100000)}"
                            container.repository.upsertTemplate(
                                id = id,
                                name = draftName.trim(),
                                category = "mine",
                                description = draftDesc.trim().ifBlank { draftName.trim() },
                                systemPrompt = draftPrompt.trim(),
                            )
                            draftName = ""
                            draftDesc = ""
                            draftPrompt = ""
                            showTypeCreate = false
                            onOpenTemplate(id)
                        }
                    },
                    enabled = draftName.isNotBlank() && draftPrompt.isNotBlank(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else VelumInk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) VelumBlack else VelumSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Text(
            label,
            color = if (selected) VelumInk else VelumInkSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(72.dp)
                .height(2.dp)
                .background(if (selected) VelumBlack else Color.Transparent),
        )
    }
}

private fun templateDisplayName(tpl: Template, languageTag: String): String {
    val catalog = BuiltinTemplateCatalog.get(tpl.id)
    return catalog?.displayName(languageTag) ?: tpl.name
}

/** In-app credit: Velum PR contributor only — never Plaud author/official. */
private fun templateUiCredit(tpl: Template): String? =
    BuiltinTemplateCatalog.get(tpl.id)?.uiContributorCredit()

@Composable
private fun RankingCard(tpl: Template, rank: Int, languageTag: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(180.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VelumSurface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Outlined.BarChart, null, tint = VelumInk, modifier = Modifier.size(28.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFD6C4A8))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("No. $rank", fontSize = 11.sp, color = VelumInk)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            templateDisplayName(tpl, languageTag),
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(tpl.description, color = VelumInkSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text("${tpl.uses}", color = VelumInkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TemplateGridCard(
    name: String,
    description: String,
    uses: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VelumSurface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = VelumInk, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(description, color = VelumInkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("$uses", color = VelumInkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CreateOption(
    title: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            title()
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = VelumInkSecondary, fontSize = 13.sp)
        }
        Text("›", color = VelumInkSecondary, fontSize = 20.sp)
    }
}

@Composable
fun TemplateDetailScreen(
    container: AppContainer,
    templateId: String,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val templates by container.repository.observeTemplates().collectAsState(emptyList())
    val tpl = templates.firstOrNull { it.id == templateId }
    val contributorCredit = tpl?.let { templateUiCredit(it) }

    Column(Modifier.fillMaxSize().background(VelumBg).verticalScroll(rememberScrollState())) {
        Icon(
            Icons.Outlined.Close,
            null,
            modifier = Modifier.padding(16.dp).size(28.dp).clickable(onClick = onBack),
            tint = VelumInk,
        )
        Box(
            Modifier
                .size(56.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(28.dp))
                .background(VelumSurface)
                .border(1.dp, VelumDivider, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("@", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            tpl?.let { templateDisplayName(it, strings.localeTag) } ?: "—",
            fontWeight = FontWeight.Thin,
            fontSize = 32.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 24.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                tpl?.category?.let {
                    append(BuiltinTemplateCatalog.categoryLabel(it, strings.localeTag))
                    append(" · ")
                }
                append(tpl?.uses ?: 0)
            },
            color = VelumInkSecondary,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (!contributorCredit.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                contributorCredit,
                color = VelumInkSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("Descrição", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            tpl?.description.orEmpty(),
            color = VelumInk,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("RESUMO", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(6.dp))
        var editing by remember(tpl?.id) { mutableStateOf(false) }
        var promptDraft by remember(tpl?.id) { mutableStateOf(tpl?.system_prompt.orEmpty()) }
        var nameDraft by remember(tpl?.id) { mutableStateOf(tpl?.name.orEmpty()) }
        val scope = rememberCoroutineScope()
        if (editing && tpl != null && tpl.builtin == 0L) {
            BasicTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                textStyle = TextStyle(color = VelumInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                textStyle = TextStyle(color = VelumInk, fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(160.dp)
                    .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
            Spacer(Modifier.height(12.dp))
            BlackButton(
                strings.save,
                {
                    scope.launch {
                        container.repository.upsertTemplate(
                            id = tpl.id,
                            name = nameDraft,
                            category = tpl.category,
                            description = tpl.description,
                            systemPrompt = promptDraft,
                            author = tpl.author,
                            builtin = false,
                            uses = tpl.uses,
                        )
                        editing = false
                    }
                },
                Modifier.padding(horizontal = 24.dp),
            )
        } else {
            Text(
                tpl?.system_prompt.orEmpty(),
                color = VelumInk,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                    .clickable {
                        if (tpl?.builtin == 0L) editing = !editing
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = VelumInk)
            }
            BlackButton(strings.setForLater, onBack, Modifier.weight(1f))
        }
    }
}
