package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalGridColumns
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.LocalPortraitGridColumns
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyGridEffect
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── R187 (FR-RV-BROWSE1) — the generic browse page ────────────────────────────

enum class BrowseFacetKey { GENRE, TYPE, MATURITY, YEAR, WATCHED, AUDIO, CHANNEL, QUALITY }
enum class BrowseSort { RECENT, TITLE_AZ, TITLE_ZA, YEAR, MATURITY, IMDB }

/** null bound = "Any" on that side (FR-RV-BROWSE1-6). Both null = filter off. */
data class MaturityRange(val from: Int? = null, val upTo: Int? = null) {
    val isActive: Boolean get() = from != null || upTo != null
    fun label(): String = when {
        from == null && upTo == null -> ""
        from == null -> "≤ $upTo"
        upTo == null -> "$from+"
        from == upTo -> "$from"
        else -> "$from–$upTo"
    }
}

sealed class SeededBrowseState {
    data object Loading : SeededBrowseState()
    data class Loaded(val items: List<BrowseCard>) : SeededBrowseState()
    data class Error(val message: String) : SeededBrowseState()
}

/**
 * R187 — holds the raw fetch (one call, the FULL seed-matching set — see [TvApiClient.browseSeeded]'s
 * doc comment) plus every facet's active selection. Filtering/counting/sorting is plain, cheap Kotlin
 * over that in-memory list (a few hundred items at most) recomputed reactively in the composable via
 * `remember` — no further network round trip as the viewer toggles facets, per FR-RV-BROWSE1-5.
 */
class SeededBrowseStore(
    private val apiClient: TvApiClient,
    val seedQuery: ConditionGroup?,
    val seedMediaKind: String?,
    val continueWatching: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SeededBrowseState>(SeededBrowseState.Loading)
    val state: StateFlow<SeededBrowseState> = _state.asStateFlow()
    val gridState = LazyGridState()
    var focusItemKey: String? = null
    private var loadJob: Job? = null

    // Active facet selections — multi-select OR within a facet (FR-RV-BROWSE1-4).
    var genre by mutableStateOf(setOf<String>())
    var type by mutableStateOf(setOf<String>())
    var maturity by mutableStateOf(MaturityRange())
    var year by mutableStateOf(setOf<String>())
    var watched by mutableStateOf(setOf<String>())
    var audio by mutableStateOf(setOf<String>())
    var channel by mutableStateOf(setOf<String>())
    var quality by mutableStateOf(setOf<String>())
    var sort by mutableStateOf(BrowseSort.RECENT)
    var openFacet by mutableStateOf<BrowseFacetKey?>(null)
    var sortOpen by mutableStateOf(false)
    /** Channel id -> display name, for the Channel facet's labels — set by the caller from whatever
     *  channel list is already loaded (Home's rail / the channel page's own config), since [BrowseCard]
     *  only carries ids. Empty (falls back to raw id) when unavailable. */
    var channelNames: Map<String, String> = emptyMap()

    val hasActiveFilters: Boolean
        get() = genre.isNotEmpty() || type.isNotEmpty() || maturity.isActive || year.isNotEmpty() ||
            watched.isNotEmpty() || audio.isNotEmpty() || channel.isNotEmpty() || quality.isNotEmpty()

    fun resetFilters() {
        genre = emptySet(); type = emptySet(); maturity = MaturityRange(); year = emptySet()
        watched = emptySet(); audio = emptySet(); channel = emptySet(); quality = emptySet()
    }

    fun load() {
        loadJob?.cancel()
        _state.value = SeededBrowseState.Loading
        loadJob = scope.launch {
            _state.value = runCatching {
                if (continueWatching) {
                    val items = apiClient.continueAll()
                    SeededBrowseState.Loaded(items.map { BrowseCard(card = it) })
                } else {
                    val resp = apiClient.browseSeeded(seedQuery, seedMediaKind)
                    SeededBrowseState.Loaded(resp.items)
                }
            }.getOrElse { SeededBrowseState.Error(it.message ?: "Unknown error") }
        }
    }
}

private fun yearDecade(year: Int?): String? = year?.let { "${(it / 10) * 10}s" }

/** Does [card] pass every active facet EXCEPT [excluding] — the "count against the seed with every
 *  other active facet applied" semantics of FR-RV-BROWSE1-5. */
private fun SeededBrowseStore.matches(card: BrowseCard, excluding: BrowseFacetKey?): Boolean {
    val c = card.card
    if (excluding != BrowseFacetKey.GENRE && genre.isNotEmpty() && card.genres.none { it in genre }) return false
    if (excluding != BrowseFacetKey.TYPE && type.isNotEmpty() && c.kind.name !in type) return false
    if (excluding != BrowseFacetKey.MATURITY && maturity.isActive) {
        val age = c.ageRating
        if (maturity.from != null && age < maturity.from!!) return false
        if (maturity.upTo != null && age > maturity.upTo!!) return false
    }
    if (excluding != BrowseFacetKey.YEAR && year.isNotEmpty() && yearDecade(c.year) !in year) return false
    if (excluding != BrowseFacetKey.WATCHED && watched.isNotEmpty()) {
        val w = if (c.watched) "watched" else "unwatched"
        if (w !in watched) return false
    }
    if (excluding != BrowseFacetKey.AUDIO && audio.isNotEmpty() && card.audioLanguages.none { it in audio }) return false
    if (excluding != BrowseFacetKey.CHANNEL && channel.isNotEmpty() && card.channels.none { it in channel }) return false
    if (excluding != BrowseFacetKey.QUALITY && quality.isNotEmpty() && card.quality !in quality) return false
    return true
}

private data class FacetValue(val value: String, val count: Int)

/** Value list for [key], counted against every OTHER active facet, ordered by count desc then by raw
 *  value (FR-RV-BROWSE1-5) — display labels are resolved separately, at render time
 *  ([facetValueLabel]), since [str] is `@Composable` and this runs inside a plain `remember` block. */
private fun SeededBrowseStore.valuesFor(all: List<BrowseCard>, key: BrowseFacetKey): List<FacetValue> {
    val narrowed = all.filter { matches(it, key) }
    val counts = mutableMapOf<String, Int>()
    fun bump(value: String) { counts[value] = (counts[value] ?: 0) + 1 }
    for (card in narrowed) {
        val c = card.card
        when (key) {
            BrowseFacetKey.GENRE -> card.genres.forEach { bump(it) }
            BrowseFacetKey.TYPE -> bump(c.kind.name)
            BrowseFacetKey.YEAR -> yearDecade(c.year)?.let { bump(it) }
            BrowseFacetKey.WATCHED -> bump(if (c.watched) "watched" else "unwatched")
            BrowseFacetKey.AUDIO -> card.audioLanguages.forEach { bump(it) }
            BrowseFacetKey.CHANNEL -> card.channels.forEach { bump(it) }
            BrowseFacetKey.QUALITY -> card.quality?.let { bump(it) }
            BrowseFacetKey.MATURITY -> {} // range picker, not a checklist
        }
    }
    return counts.entries
        .map { (v, n) -> FacetValue(v, n) }
        .sortedWith(compareByDescending<FacetValue> { it.count }.thenBy { it.value.lowercase() })
}

/** Display label for one facet VALUE (as opposed to [facetLabel], the facet's own name) — Composable
 *  because Type/Watched values need translation; everything else (genre names, decade labels, language
 *  codes, quality tiers) is already display-ready. [channelNames] resolves a channel id to its
 *  configured name (threaded in from the already-loaded Home/channel data by the caller). */
@Composable
private fun facetValueLabel(key: BrowseFacetKey, value: String, channelNames: Map<String, String>): String = when (key) {
    BrowseFacetKey.TYPE -> if (value == "MOVIE") str("browse.type.movie") else str("browse.type.series")
    BrowseFacetKey.WATCHED -> if (value == "watched") str("browse.watched.watched") else str("browse.watched.unwatched")
    BrowseFacetKey.CHANNEL -> channelNames[value] ?: value
    else -> value
}

private fun SeededBrowseStore.selectionFor(key: BrowseFacetKey): Set<String> = when (key) {
    BrowseFacetKey.GENRE -> genre; BrowseFacetKey.TYPE -> type; BrowseFacetKey.YEAR -> year
    BrowseFacetKey.WATCHED -> watched; BrowseFacetKey.AUDIO -> audio
    BrowseFacetKey.CHANNEL -> channel; BrowseFacetKey.QUALITY -> quality
    BrowseFacetKey.MATURITY -> emptySet()
}

private fun SeededBrowseStore.toggle(key: BrowseFacetKey, value: String) {
    val cur = selectionFor(key)
    val next = if (value in cur) cur - value else cur + value
    when (key) {
        BrowseFacetKey.GENRE -> genre = next; BrowseFacetKey.TYPE -> type = next
        BrowseFacetKey.YEAR -> year = next; BrowseFacetKey.WATCHED -> watched = next
        BrowseFacetKey.AUDIO -> audio = next; BrowseFacetKey.CHANNEL -> channel = next
        BrowseFacetKey.QUALITY -> quality = next; BrowseFacetKey.MATURITY -> {}
    }
}

private fun sortedFiltered(store: SeededBrowseStore, all: List<BrowseCard>): List<BrowseCard> {
    val filtered = all.filter { store.matches(it, null) }
    return when (store.sort) {
        BrowseSort.RECENT -> filtered // server order is already newest-first
        BrowseSort.TITLE_AZ -> filtered.sortedBy { it.card.title.lowercase() }
        BrowseSort.TITLE_ZA -> filtered.sortedByDescending { it.card.title.lowercase() }
        BrowseSort.YEAR -> filtered.sortedByDescending { it.card.year ?: 0 }
        BrowseSort.MATURITY -> filtered.sortedBy { it.card.ageRating }
        BrowseSort.IMDB -> filtered.sortedWith(compareByDescending<BrowseCard> { it.imdbRating?.aggregateRating ?: -1.0 })
    }
}

@Composable
fun SeededBrowseScreen(
    store: SeededBrowseStore,
    title: String,
    breadcrumb: String?,
    subtitle: String?,
    showTypeFacet: Boolean,
    showFacetBar: Boolean,
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(Unit) { store.load() }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val gridState = store.gridState
    val firstCellFR = remember { FocusRequester() }
    val backFR = remember { FocusRequester() }
    val firstFacetFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFR.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .backToTopOnBack(
                atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
                onBackToTop = {
                    scope.launch {
                        runCatching { gridState.animateScrollToItem(0) }
                        runCatching { firstCellFR.requestFocus() }
                    }
                },
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            // Header: breadcrumb + title + subtitle (FR-RV-BROWSE1-3) — no seed chip, Back leaves the page.
            Column(Modifier.padding(horizontal = raviloHPad)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.dpadFocusable(focusRequester = backFR, onSelect = onBack, onBack = onBack)
                            .padding(end = 10.dp),
                    ) { Text("◂", color = colors.textSecondary, fontSize = 18.sp) }
                    if (breadcrumb != null) {
                        Text(breadcrumb, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
                    }
                }
                Text(title, color = colors.text, fontSize = 28.sp, fontFamily = Sora, fontWeight = FontWeight.Bold)
                val count = (state as? SeededBrowseState.Loaded)?.let { sortedFiltered(store, it.items).size }
                val sub = listOfNotNull(subtitle, count?.let { n -> if (n == 1) str("browse.title_one") else str("browse.titles", mapOf("count" to n.toString())) })
                    .joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
            }
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is SeededBrowseState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is SeededBrowseState.Error -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
                }
                is SeededBrowseState.Loaded -> {
                    if (showFacetBar) {
                        FacetBar(
                            store = store, all = s.items, showTypeFacet = showTypeFacet,
                            firstFacetFR = firstFacetFR,
                            onBarUp = { runCatching { backFR.requestFocus() } },
                            onBarDown = { runCatching { firstCellFR.requestFocus() } },
                        )
                        val open = store.openFacet
                        if (open != null) {
                            FacetPopover(store = store, all = s.items, key = open, onClose = { store.openFacet = null })
                        } else if (store.sortOpen) {
                            SortPopover(store = store, onClose = { store.sortOpen = false })
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    val filtered = remember(s.items, store.genre, store.type, store.maturity, store.year, store.watched, store.audio, store.channel, store.quality, store.sort) {
                        sortedFiltered(store, s.items)
                    }
                    BrowseCardGrid(
                        items = filtered.map { it.card },
                        gridState = gridState,
                        firstCellFR = firstCellFR,
                        restoreItemKey = store.focusItemKey,
                        onItemSelect = { card -> store.focusItemKey = card.id; onItemSelect(card) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FacetBar(
    store: SeededBrowseStore,
    all: List<BrowseCard>,
    showTypeFacet: Boolean,
    firstFacetFR: FocusRequester,
    onBarUp: () -> Unit,
    onBarDown: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val facets = buildList {
        add(BrowseFacetKey.GENRE)
        if (showTypeFacet) add(BrowseFacetKey.TYPE)
        add(BrowseFacetKey.MATURITY); add(BrowseFacetKey.YEAR); add(BrowseFacetKey.WATCHED)
        add(BrowseFacetKey.AUDIO)
        if (all.any { it.channels.isNotEmpty() }) add(BrowseFacetKey.CHANNEL)
        if (all.any { it.quality != null }) add(BrowseFacetKey.QUALITY)
    }
    val chipShape = remember { RoundedCornerShape(18.dp) }
    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = raviloHPad),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(facets.size, key = { i -> facets[i].name }) { i ->
            val key = facets[i]
            val label = facetLabel(key)
            val active = when (key) {
                BrowseFacetKey.MATURITY -> store.maturity.isActive
                else -> store.selectionFor(key).isNotEmpty()
            }
            val badge = when (key) {
                BrowseFacetKey.MATURITY -> store.maturity.label().takeIf { it.isNotBlank() }
                else -> store.selectionFor(key).size.takeIf { it > 0 }?.toString()
            }
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .background(if (active) colors.accent else colors.surfaceVariant, chipShape)
                    .then(if (focused && !active) Modifier.border(2.dp, colors.focusRing, chipShape) else Modifier)
                    .dpadFocusable(
                        focusRequester = if (i == 0) firstFacetFR else null,
                        onFocused = { focused = true }, onBlurred = { focused = false },
                        onSelect = { store.openFacet = if (store.openFacet == key) null else key; store.sortOpen = false },
                        onUp = onBarUp, onDown = { if (store.openFacet == null) onBarDown() },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badge != null) "$label ($badge)" else label,
                    color = if (active) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 14.sp, fontFamily = Sora,
                    fontWeight = if (active || focused) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item(key = "reset") {
            if (store.hasActiveFilters) {
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .background(colors.surfaceVariant, chipShape)
                        .then(if (focused) Modifier.border(2.dp, colors.focusRing, chipShape) else Modifier)
                        .dpadFocusable(onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = { store.resetFilters() }, onUp = onBarUp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("✕ " + str("browse.reset"), color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora) }
            }
        }
        item(key = "sort") {
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .background(colors.surfaceVariant, chipShape)
                    .then(if (focused) Modifier.border(2.dp, colors.focusRing, chipShape) else Modifier)
                    .dpadFocusable(onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = { store.sortOpen = !store.sortOpen; store.openFacet = null }, onUp = onBarUp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(str("browse.sort") + ": " + sortLabel(store.sort), color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp, fontFamily = Sora) }
        }
    }
}

@Composable
private fun facetLabel(key: BrowseFacetKey): String = when (key) {
    BrowseFacetKey.GENRE -> str("browse.facet.genre"); BrowseFacetKey.TYPE -> str("browse.facet.type")
    BrowseFacetKey.MATURITY -> str("browse.facet.maturity"); BrowseFacetKey.YEAR -> str("browse.facet.year")
    BrowseFacetKey.WATCHED -> str("browse.facet.watched"); BrowseFacetKey.AUDIO -> str("browse.facet.audio")
    BrowseFacetKey.CHANNEL -> str("browse.facet.channel"); BrowseFacetKey.QUALITY -> str("browse.facet.quality")
}

@Composable
private fun sortLabel(sort: BrowseSort): String = when (sort) {
    BrowseSort.RECENT -> str("browse.sort.recent"); BrowseSort.TITLE_AZ -> str("browse.sort.az")
    BrowseSort.TITLE_ZA -> str("browse.sort.za"); BrowseSort.YEAR -> str("browse.sort.year")
    BrowseSort.MATURITY -> str("browse.sort.maturity"); BrowseSort.IMDB -> str("browse.sort.imdb")
}

/**
 * R187 (FR-RV-BROWSE1-8) — "the popover owns the remote while open": this is a Box with its own
 * [dpadFocusable] elements, requesting focus onto itself the moment it opens. Compose's own focus
 * system is exclusive (only the focused element receives key events), so this needs no manual global
 * key interception the way the player's picker does — that pattern belongs to PlayerScreen's own
 * single-focus-root architecture, not this grid's native traversal (see the spec's backend-review
 * addendum). onBack closes and returns focus to the facet bar via [onClose].
 */
@Composable
private fun FacetPopover(store: SeededBrowseStore, all: List<BrowseCard>, key: BrowseFacetKey, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val firstRowFR = remember(key) { FocusRequester() }
    LaunchedEffect(key) { runCatching { firstRowFR.requestFocus() } }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = raviloHPad)
            .dpadFocusable(onBack = onClose)
    ) {
        Column(
            Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            if (key == BrowseFacetKey.MATURITY) {
                MaturityRangePicker(store, firstRowFR, onClose)
            } else {
                val values = remember(all, store.genre, store.type, store.maturity, store.year, store.watched, store.audio, store.channel, store.quality) {
                    store.valuesFor(all, key)
                }
                LazyColumn(Modifier.height(280.dp)) {
                    items(values.size, key = { i -> values[i].value }) { i ->
                        val v = values[i]
                        val selected = v.value in store.selectionFor(key)
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (focused) colors.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                                .dpadFocusable(
                                    focusRequester = if (i == 0) firstRowFR else null,
                                    onFocused = { focused = true }, onBlurred = { focused = false },
                                    onSelect = { store.toggle(key, v.value) },
                                    onLeft = onClose, onRight = onClose, onBack = onClose,
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (selected) "✓ " else "• ", color = if (selected) colors.accent else colors.textSecondary, fontSize = 14.sp)
                            Text(facetValueLabel(key, v.value, store.channelNames), color = colors.text, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                            Text(v.count.toString(), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                        }
                    }
                }
            }
        }
    }
}

private const val MATURITY_MAX = 18

@Composable
private fun MaturityRangePicker(store: SeededBrowseStore, firstRowFR: FocusRequester, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    var row by remember { mutableStateOf(0) } // 0 = From, 1 = Up to
    val upToRowFR = remember { FocusRequester() }

    fun stepFrom(delta: Int) {
        val cur = store.maturity.from
        val next = (cur ?: -1) + delta
        val newFrom = if (next < 0) null else next.coerceAtMost(MATURITY_MAX)
        val upTo = store.maturity.upTo
        if (newFrom != null && upTo != null && newFrom > upTo) return
        store.maturity = store.maturity.copy(from = newFrom)
    }
    fun stepUpTo(delta: Int) {
        val cur = store.maturity.upTo
        val next = (cur ?: MATURITY_MAX + 1) + delta
        val newUpTo = if (next > MATURITY_MAX) null else next.coerceAtLeast(0)
        val from = store.maturity.from
        if (newUpTo != null && from != null && newUpTo < from) return
        store.maturity = store.maturity.copy(upTo = newUpTo)
    }

    // Ladder viz — highlights the selected span.
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (age in 0..MATURITY_MAX) {
            val inSpan = (store.maturity.from == null || age >= store.maturity.from!!) &&
                (store.maturity.upTo == null || age <= store.maturity.upTo!!)
            Box(
                Modifier.weight(1f).height(6.dp)
                    .background(if (inSpan) colors.accent else colors.surface, RoundedCornerShape(3.dp)),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    listOf(
        Triple(str("browse.maturity.from"), store.maturity.from?.toString() ?: str("browse.maturity.any"), 0),
        Triple(str("browse.maturity.upto"), store.maturity.upTo?.toString() ?: str("browse.maturity.any"), 1),
    ).forEach { (label, value, idx) ->
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) colors.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                .dpadFocusable(
                    focusRequester = if (idx == 0) firstRowFR else upToRowFR,
                    onFocused = { focused = true; row = idx }, onBlurred = { focused = false },
                    onLeft = { if (idx == 0) stepFrom(-1) else stepUpTo(-1) },
                    onRight = { if (idx == 0) stepFrom(1) else stepUpTo(1) },
                    // ▲▼ switches between From/Up-to (FR-RV-BROWSE1-6) — explicit bridge since the two
                    // rows are the only focusables in this popover, same idiom as the rest of the app's
                    // single/dual-target focus bridges (e.g. HomeScreen's heroFR/navBarFR).
                    onUp = if (idx == 1) ({ runCatching { firstRowFR.requestFocus() } }) else null,
                    onDown = if (idx == 0) ({ runCatching { upToRowFR.requestFocus() } }) else null,
                    onSelect = onClose, onBack = onClose,
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
            Text("◂", color = colors.textSecondary, fontSize = 14.sp)
            Text(value, color = colors.text, fontSize = 16.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("▸", color = colors.textSecondary, fontSize = 14.sp)
        }
    }
    Text(str("browse.maturity.hint"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SortPopover(store: SeededBrowseStore, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val firstFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFR.requestFocus() } }
    Box(Modifier.fillMaxWidth().padding(horizontal = raviloHPad).dpadFocusable(onBack = onClose)) {
        Column(Modifier.background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
            BrowseSort.entries.forEachIndexed { i, opt ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (focused) colors.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .dpadFocusable(
                            focusRequester = if (i == 0) firstFR else null,
                            onFocused = { focused = true }, onBlurred = { focused = false },
                            onSelect = { store.sort = opt; onClose() },
                            onLeft = onClose, onRight = onClose, onBack = onClose,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(if (store.sort == opt) "✓ " else "• ", color = if (store.sort == opt) colors.accent else colors.textSecondary, fontSize = 14.sp)
                    Text(sortLabel(opt), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
                }
            }
        }
    }
}

/** Up from the grid's first row lands on the facet bar via native spatial search (both are left-anchored
 *  at the same horizontal offset, unlike e.g. the full-width hero → left-anchored row case elsewhere in
 *  this app, which needed an explicit bridge) — matches the pre-existing `BrowseScreen.kt`'s own
 *  `BrowseGrid`, which relies on the same native traversal with no bridge. If that proves unreliable in
 *  practice (the same failure class as this app's documented hero/OnNow off-by-one bugs), add an
 *  explicit `onUp` bridge on the first tile then — `Tile` doesn't expose one today. */
@Composable
private fun BrowseCardGrid(
    items: List<MediaCard>,
    gridState: LazyGridState,
    firstCellFR: FocusRequester,
    restoreItemKey: String?,
    onItemSelect: (MediaCard) -> Unit,
) {
    val prefetchUrls = remember(items) { items.map { it.posterUrl.orEmpty() } }
    PrefetchLazyGridEffect(gridState = gridState, urls = prefetchUrls)
    val restoreFR = remember { FocusRequester() }
    var restoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (!restoredOnce && restoreItemKey != null && items.any { it.id == restoreItemKey }) {
            runCatching { restoreFR.requestFocus() }
            restoredOnce = true
        }
    }
    val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else LocalGridColumns.current
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(str("browse.empty"), color = RaviloTheme.colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        state = gridState,
        modifier = Modifier.focusRestorer(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(RaviloDimens.rowGap),
    ) {
        items(items.size, key = { i -> items[i].id }) { i ->
            val card = items[i]
            Tile(
                title = card.title,
                posterUrl = card.posterUrl,
                progressPct = card.progressPct ?: 0f,
                watched = card.watched,
                upcomingLabel = card.upcomingEpisode,
                focusRequester = if (card.id == restoreItemKey) restoreFR else if (i == 0) firstCellFR else null,
                onSelect = { onItemSelect(card) },
            )
        }
    }
}
