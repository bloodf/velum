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
import dev.velum.core.data.UserContentIds
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.rememberJsonFileImporter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.DisplayTitle
import dev.velum.app.ui.components.EmptyState
import dev.velum.app.ui.components.SearchSheet
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.db.Template
import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.templates.DiscoverFacets
import dev.velum.core.templates.DiscoverFilter
import dev.velum.core.templates.DiscoverTier
import dev.velum.core.templates.TemplateJsonImporter
import dev.velum.core.templates.TemplateTagVocabulary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    val templateChanges = remember(container) { container.repository.observeTemplateChanges() }
    val templateTick by templateChanges.collectAsState(0L)
    var tab by remember { mutableStateOf(1) } // 0 my space, 1 discover
    /** Discover tier: true = Featured builtins, false = All builtins. */
    var discoverFeaturedOnly by remember { mutableStateOf(true) }
    var showTypeCreate by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftPrompt by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var fullImportRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val languageTag = strings.localeTag

    LaunchedEffect(tab, discoverFeaturedOnly) {
        if (tab != 1 || discoverFeaturedOnly) return@LaunchedEffect
        fullImportRunning = true
        val result = runCatching { container.repository.ensureFullTemplatesImported() }
        fullImportRunning = false
        result.onFailure {
            importStatus = strings.templatesImportFailed
        }
    }
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
                importStatus = strings.templatesImportOkFormat.replace("%d", specs.size.toString())
            }.onFailure {
                importStatus = strings.templatesImportFailed
            }
        }
    }

    val tier = when {
        tab == 0 -> DiscoverTier.Mine
        discoverFeaturedOnly -> DiscoverTier.Featured
        else -> DiscoverTier.All
    }
    // Facet values come from catalog metadata for the current tier + search (+ category for tags/langs).
    // `facetsLoaded` stays false until a load succeeds; a failed load keeps the previous facets and never prunes.
    var facets by remember { mutableStateOf(DiscoverFacets(emptyList(), emptyList(), emptyList())) }
    var facetsLoaded by remember { mutableStateOf(false) }
    // Search the current facets were loaded for: only a blank-search load may prune selections (#102).
    var facetsSearch by remember { mutableStateOf<String?>(null) }
    // Debounced so each keystroke does not re-run the facet and page queries.
    var debouncedQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        // Clearing the search applies at once; only typing is debounced.
        if (searchQuery.isNotEmpty() && searchQuery != debouncedQuery) delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = searchQuery
    }
    LaunchedEffect(tier, debouncedQuery, selectedCategory, templateTick) {
        try {
            facets = container.repository.discoverFacets(DiscoverFilter(tier, debouncedQuery, selectedCategory))
            facetsLoaded = true
            facetsSearch = debouncedQuery
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the previous facets (and the user's selections) on failure.
        }
    }
    val canPrune = facetsLoaded && facetsSearch?.isBlank() == true
    // The selected category stays listed even when the current search has no hit in it (#102).
    val categoryTabs = remember(facets, selectedCategory) {
        val categories = facets.categories.let { c -> if (selectedCategory != null && selectedCategory !in c) c + selectedCategory!! else c }
        BuiltinTemplateCatalog.CategoryOrder.filter { it in categories } +
            categories.filter { it !in BuiltinTemplateCatalog.CategoryOrder && it != "mine" }
    }
    val availableTags = facets.tags
    val availableLangs = facets.langs
    LaunchedEffect(facets, selectedCategory, canPrune) {
        val kept = pruneSelection(selectedCategory, facets.categories, canPrune)
        if (kept != selectedCategory) selectedCategory = kept
    }
    LaunchedEffect(availableTags, selectedTags, canPrune) {
        val kept = pruneSelection(selectedTags, availableTags, canPrune)
        if (kept != selectedTags) selectedTags = kept
    }
    LaunchedEffect(availableLangs, selectedLang, canPrune) {
        val kept = pruneSelection(selectedLang, availableLangs, canPrune)
        if (kept != selectedLang) selectedLang = kept
    }
    val filter = DiscoverFilter(
        tier = tier,
        search = debouncedQuery,
        category = selectedCategory.takeIf { tab == 1 },
        tags = if (tab == 1) selectedTags else emptySet(),
        lang = selectedLang.takeIf { tab == 1 },
    )
    // Any filter/tier/search/catalog change resets paging to the first page.
    var pages by remember(filter, templateTick) { mutableStateOf(1) }
    // null = nothing loaded yet for this filter (no empty-state flash, no stale rows).
    var shown by remember { mutableStateOf<List<Template>?>(null) }
    var shownFilter by remember { mutableStateOf<DiscoverFilter?>(null) }
    // Catalog tick the rows were loaded at; the paging sentinel stays off while they are older than the catalog.
    var shownTick by remember { mutableStateOf(-1L) }
    var totalMatches by remember { mutableStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(filter, pages, templateTick) {
        // Snapshot before the first suspension: the tick may move while the queries run.
        val loadTick = templateTick
        // A different filter must not keep the previous filter's rows (or its paging sentinel) on screen.
        if (shownFilter != filter) {
            shown = null
        }
        try {
            val current = shown.orEmpty()
            val offset = if (pages == 1) 0 else current.size
            val rows = container.repository.queryDiscoverTemplates(filter, limit = PAGE_SIZE, offset = offset)
            val total = container.repository.countDiscoverTemplates(filter)
            shown = if (pages == 1) rows else appendPage(current, rows)
            shownFilter = filter
            shownTick = loadTick
            totalMatches = total
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed later page keeps the rows already loaded for this filter; anything else must not
            // keep the previous filter's rows on screen.
            if (pages == 1 || shownFilter != filter) {
                shown = emptyList()
                shownFilter = filter
                shownTick = loadTick
                totalMatches = 0
            }
            loadFailed = true
        }
    }
    // Rows from another filter are never visible, even for the frame before the effect clears them.
    val visible = shown.takeIf { shownFilter == filter }
    val rows = visible.orEmpty()
    // Weekly ranking uses Featured builtins only.
    var ranking by remember { mutableStateOf<List<Template>>(emptyList()) }
    LaunchedEffect(templateTick) {
        ranking = try {
            container.repository.queryDiscoverTemplates(DiscoverFilter(DiscoverTier.Featured), limit = 5)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun clearFilters() {
        selectedCategory = null
        selectedTags = emptySet()
        selectedLang = null
    }

    Column(Modifier.fillMaxSize().background(VelumBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = strings.ux.back,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = strings.ux.back }
                    .clickable(onClick = onBack)
                    .padding(8.dp),
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
                strings.templatesImportJson,
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
                    .clickable { showTypeCreate = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = VelumOnBlack, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.create, color = VelumOnBlack, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        val statusLine = when {
            fullImportRunning -> strings.templatesImportingFull
            loadFailed -> strings.ux.genericError
            else -> importStatus
        }
        statusLine?.let { status ->
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("templates:grid"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Filter rows scroll with the results so short screens / the keyboard never squeeze the grid to 0 height.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (tab == 1) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CategoryChip(
                                label = strings.templatesFeatured,
                                selected = discoverFeaturedOnly,
                                testTag = "tier:featured",
                            ) { discoverFeaturedOnly = true }
                            CategoryChip(
                                label = strings.templatesAll,
                                selected = !discoverFeaturedOnly,
                                testTag = "tier:all",
                            ) { discoverFeaturedOnly = false }
                        }
                    }

                    if (tab == 1 && categoryTabs.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CategoryChip(
                                label = strings.templatesAll,
                                selected = selectedCategory == null,
                                testTag = "category:all",
                            ) { selectedCategory = null }
                            categoryTabs.forEach { cat ->
                                CategoryChip(
                                    label = BuiltinTemplateCatalog.categoryLabel(cat, languageTag),
                                    selected = selectedCategory == cat,
                                    testTag = "category:$cat",
                                ) { selectedCategory = cat }
                            }
                        }
                    }

                    if (tab == 1 && availableTags.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strings.templateTags,
                                color = VelumInkSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            availableTags.take(24).forEach { tag ->
                                CategoryChip(
                                    label = TemplateTagVocabulary.label(tag, languageTag),
                                    selected = tag in selectedTags,
                                    testTag = "tag:$tag",
                                ) {
                                    selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                                }
                            }
                        }
                    }

                    if (tab == 1 && availableLangs.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strings.templateLangFilter,
                                color = VelumInkSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            CategoryChip(
                                label = strings.templatesAll,
                                selected = selectedLang == null,
                                testTag = "lang:all",
                            ) { selectedLang = null }
                            availableLangs.forEach { lang ->
                                CategoryChip(
                                    label = templateLangLabel(lang, languageTag),
                                    selected = selectedLang == lang,
                                    testTag = "lang:$lang",
                                ) { selectedLang = lang }
                            }
                        }
                    }

                    if (tab == 1 && filter.hasFacetSelection) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strings.templatesActiveFilters,
                                color = VelumInkSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val removeFormat = strings.templatesRemoveFilterFormat
                            selectedCategory?.let { cat ->
                                val label = BuiltinTemplateCatalog.categoryLabel(cat, languageTag)
                                ActiveFilterChip(label, removeFormat.replace("%s", label), "active:category") { selectedCategory = null }
                            }
                            selectedTags.sorted().forEach { tag ->
                                val label = TemplateTagVocabulary.label(tag, languageTag)
                                ActiveFilterChip(label, removeFormat.replace("%s", label), "active:tag:$tag") { selectedTags = selectedTags - tag }
                            }
                            selectedLang?.let { lang ->
                                val label = templateLangLabel(lang, languageTag)
                                ActiveFilterChip(label, removeFormat.replace("%s", label), "active:lang") { selectedLang = null }
                            }
                            CategoryChip(label = strings.templatesClearFilters, selected = false, testTag = "filters:clear") { clearFilters() }
                        }
                    }

                }
            }
            if (tab == 1 && discoverFeaturedOnly && ranking.isNotEmpty() && searchQuery.isBlank() &&
                selectedCategory == null && selectedTags.isEmpty() && selectedLang == null
            ) {
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
            if (showEmptyState(visible, fullImportRunning)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("templates:empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (filter.hasFacetSelection) {
                            EmptyState(
                                title = strings.templatesEmptyTitle,
                                subtitle = strings.templatesNoMatchHint,
                                actionLabel = strings.templatesClearFilters,
                                onAction = { clearFilters() },
                            )
                        } else {
                            EmptyState(
                                title = strings.templatesEmptyTitle,
                                subtitle = strings.templatesEmptyHint,
                            )
                        }
                    }
                }
            }
            items(rows, key = { it.id }) { tpl ->
                TemplateGridCard(
                    name = templateDisplayName(tpl, languageTag),
                    description = tpl.description,
                    uses = tpl.uses,
                    tagLabels = TemplateTagVocabulary.decode(tpl.tags)
                        .take(3)
                        .map { TemplateTagVocabulary.label(it, languageTag) },
                    modifier = Modifier.fillMaxWidth(),
                ) { onOpenTemplate(tpl.id) }
            }
            if (shownTick == templateTick && rows.size >= PAGE_SIZE * pages && rows.size < totalMatches) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // Composed only when scrolled to the end: load the next page.
                    LaunchedEffect(rows.size) { pages += 1 }
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }

    if (showSearch) {
        SearchSheet(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearch = false },
            resultCount = totalMatches.toInt(),
        )
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
                            val id = UserContentIds.template()
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

private const val PAGE_SIZE = 60

@Composable
private fun CategoryChip(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    val selectedState = LocalStrings.current.templatesFilterSelected
    Text(
        label,
        color = if (selected) VelumOnBlack else VelumInk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) VelumBlack else VelumSurface)
            .testTag(testTag)
            .semantics {
                this.selected = selected
                role = Role.Button
                if (selected) stateDescription = selectedState
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActiveFilterChip(label: String, removeDescription: String, testTag: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VelumBlack)
            .testTag(testTag)
            .semantics { contentDescription = removeDescription; role = Role.Button }
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = VelumOnBlack, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.Close, null, tint = VelumOnBlack, modifier = Modifier.size(14.dp))
    }
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
    val strings = LocalStrings.current
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
                Text(
                    strings.rankingBadgeFormat.replace("%d", rank.toString()),
                    fontSize = 11.sp,
                    color = VelumInk,
                )
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
    tagLabels: List<String>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(138.dp)
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
        if (tagLabels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                tagLabels.joinToString(" · "),
                color = VelumInkSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("$uses", color = VelumInkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun templateLangLabel(lang: String, languageTag: String): String {
    val en = languageTag.lowercase().startsWith("en")
    return when (lang.lowercase()) {
        "en" -> if (en) "English" else "Inglês"
        "pt" -> if (en) "Portuguese" else "Português"
        "es" -> if (en) "Spanish" else "Espanhol"
        "de" -> if (en) "German" else "Alemão"
        "fr" -> if (en) "French" else "Francês"
        "zh" -> if (en) "Chinese" else "Chinês"
        "ja" -> if (en) "Japanese" else "Japonês"
        "ko" -> if (en) "Korean" else "Coreano"
        "ru" -> if (en) "Russian" else "Russo"
        "ar" -> if (en) "Arabic" else "Árabe"
        else -> lang
    }
}

@Composable
fun TemplateDetailScreen(
    container: AppContainer,
    templateId: String,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val templateFlow = remember(container, templateId) { container.repository.observeTemplate(templateId) }
    val tpl = templateFlow.collectAsState(null).value
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
        val detailTags = TemplateTagVocabulary.decode(tpl?.tags)
        if (detailTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                detailTags.take(6).joinToString(" · ") {
                    TemplateTagVocabulary.label(it, strings.localeTag)
                },
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp).align(Alignment.CenterHorizontally),
            )
        }
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
        Text(
            strings.ux.templateDescription,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp),
            color = VelumInk,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tpl?.description.orEmpty(),
            color = VelumInk,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            strings.ux.templatePromptSection.uppercase(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp),
            color = VelumInk,
        )
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
            BlackButton(strings.close, onBack, Modifier.weight(1f))
        }
    }
}
