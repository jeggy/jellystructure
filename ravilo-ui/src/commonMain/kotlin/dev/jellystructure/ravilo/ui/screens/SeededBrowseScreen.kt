package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalGridColumns
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.LocalPortraitGridColumns
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.LANG_CC
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyGridEffect
import dev.jellystructure.ravilo.ui.seams.languageName
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
import org.jetbrains.compose.resources.painterResource

// ─── R187 (FR-RV-BROWSE1) — the generic browse page ────────────────────────────

enum class BrowseFacetKey { GENRE, TYPE, MATURITY, YEAR, WATCHED, AUDIO, CHANNEL, QUALITY }
enum class SortField { RECENT, TITLE, YEAR, MATURITY, IMDB }
enum class SortDir { ASC, DESC }

/** R253 (FR-R253-2) — 225's served `sort_by`/`sort_descending` onto the page's existing (field, direction)
 *  pair. Absent or unknown ⇒ R187's default (`RECENT · DESC`), never an empty chip. Zero new strings. */
fun initialBrowseSort(sortBy: String?, descending: Boolean?): Pair<SortField, SortDir> {
    val field = when (sortBy) { "title" -> SortField.TITLE; "year" -> SortField.YEAR; "added" -> SortField.RECENT; else -> return SortField.RECENT to SortDir.DESC }
    val dir = when (descending) { true -> SortDir.DESC; false -> SortDir.ASC; null -> if (field == SortField.TITLE) SortDir.ASC else SortDir.DESC }
    return field to dir
}

/** Each field's "makes sense first" direction when newly selected (FR-RV-BROWSE1-fix: bidirectional
 *  sort) — e.g. Year defaults to newest-first (DESC), Title to A-Z (ASC). Re-selecting the already-
 *  active field flips [SortDir] instead of resetting to this default. */
private fun defaultDirFor(field: SortField): SortDir = when (field) {
    SortField.RECENT -> SortDir.DESC
    SortField.TITLE -> SortDir.ASC
    SortField.YEAR -> SortDir.DESC
    SortField.MATURITY -> SortDir.ASC
    SortField.IMDB -> SortDir.DESC
}

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
    // R190 §C — set only for a person seed; drives the Seerr overflow row's own fetch, independent of
    // the facet-filtered grid (the row is person-scoped, not filter-scoped — FR-RV-PPL1-4).
    val personTmdbId: Int? = null,
    // R219 (FR-R219-6) — set only alongside [continueWatching] == true, when this page was reached from
    // a channel's own Continue row; forwarded as-is to continueAll(), which is the sole authority on
    // whether it ends up filtering anything (see HomeFeedService.continueWatchingAll's doc comment).
    val channelId: String? = null,
    // R253 (FR-R253-2) — the (field, direction) the page OPENS in; the viewer's Sort control is untouched.
    initialSort: Pair<SortField, SortDir> = SortField.RECENT to defaultDirFor(SortField.RECENT),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SeededBrowseState>(SeededBrowseState.Loading)
    val state: StateFlow<SeededBrowseState> = _state.asStateFlow()
    private val _seerrOverflow = MutableStateFlow<List<dev.jellystructure.shared.tv.DiscoverEntry>>(emptyList())
    val seerrOverflow: StateFlow<List<dev.jellystructure.shared.tv.DiscoverEntry>> = _seerrOverflow.asStateFlow()
    val gridState = LazyGridState()
    var focusItemKey: String? = null
    private var loadJob: Job? = null
    /** Channel id -> display name, for the Channel facet's labels — [BrowseCard] only carries ids.
     *  R187 fix: fetched by the store itself (cheap, config-only) in [load] instead of being threaded
     *  in by whichever screen happened to have a [dev.jellystructure.shared.tv.HomeFeed] already loaded
     *  — that left it silently empty (raw ids shown) whenever the seeded page was reached any other way,
     *  e.g. the Movies/Series tabs. Empty until the fetch resolves. */
    var channelNames by mutableStateOf(emptyMap<String, String>())
        private set

    // Active facet selections — multi-select OR within a facet (FR-RV-BROWSE1-4).
    var genre by mutableStateOf(setOf<String>())
    var type by mutableStateOf(setOf<String>())
    var maturity by mutableStateOf(MaturityRange())
    var year by mutableStateOf(setOf<String>())
    var watched by mutableStateOf(setOf<String>())
    var audio by mutableStateOf(setOf<String>())
    var channel by mutableStateOf(setOf<String>())
    var quality by mutableStateOf(setOf<String>())
    var sortField by mutableStateOf(initialSort.first)
    var sortDir by mutableStateOf(initialSort.second)
    var openFacet by mutableStateOf<BrowseFacetKey?>(null)
    var sortOpen by mutableStateOf(false)

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
        // R187 fix — fetch the Channel facet's id->name map here (cheap, config-only) instead of
        // relying on a caller that may not have one loaded; best-effort, never blocks/fails the main
        // items load.
        if (!continueWatching) scope.launch {
            runCatching { apiClient.getChannels() }.getOrNull()?.let { list ->
                channelNames = list.associate { it.id to it.name }
            }
        }
        // R190 §C — independent of the main items load: empty (Seerr off, or no matches) is a normal,
        // silent outcome, never surfaced as an error.
        personTmdbId?.let { pid ->
            scope.launch {
                _seerrOverflow.value = runCatching { apiClient.getPersonOverflow(pid) }.getOrElse { emptyList() }
            }
        }
        loadJob = scope.launch {
            _state.value = runCatching {
                if (continueWatching) {
                    val items = apiClient.continueAll(channelId)
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

/** Three-state Watched facet (FR-RV-BROWSE1-9 addendum): "in progress" is the same signal Continue
 *  Watching already uses (unwatched but with playback progress), surfaced here as its own value rather
 *  than folded into "unwatched". */
private fun watchedState(c: MediaCard): String = when {
    c.watched -> "watched"
    (c.progressPct ?: 0f) > 0f -> "in_progress"
    else -> "unwatched"
}

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
    if (excluding != BrowseFacetKey.WATCHED && watched.isNotEmpty() && watchedState(c) !in watched) return false
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
            BrowseFacetKey.WATCHED -> bump(watchedState(c))
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
    BrowseFacetKey.TYPE -> when (value) {
        "MOVIE" -> str("browse.type.movie")
        "MUSIC_VIDEO" -> str("browse.type.musicvideo")
        else -> str("browse.type.series")
    }
    BrowseFacetKey.WATCHED -> when (value) {
        "watched" -> str("browse.watched.watched")
        "in_progress" -> str("browse.watched.in_progress")
        else -> str("browse.watched.unwatched")
    }
    BrowseFacetKey.CHANNEL -> channelNames[value] ?: value
    BrowseFacetKey.AUDIO -> languageName(value) ?: value
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
    // ASC ordering per field; RECENT's "ascending" base is the server's own newest-first order, so
    // ASC there reads as oldest-first and DESC (the default) as newest-first — both real orders, not
    // one arbitrary order plus its reverse-for-the-sake-of-it.
    val ascending = when (store.sortField) {
        SortField.RECENT -> filtered.asReversed()
        // R253 (FR-R253-3) — the SAME key the server lines the row up by (Jellyfin's SortName, else the
        // title), so the row and its page cannot file *The Bear* in two different places.
        SortField.TITLE -> filtered.sortedBy { (it.sortName?.takeIf { n -> n.isNotBlank() } ?: it.card.title).lowercase() }
        SortField.YEAR -> filtered.sortedBy { it.card.year ?: 0 }
        SortField.MATURITY -> filtered.sortedBy { it.card.ageRating }
        SortField.IMDB -> filtered.sortedBy { it.imdbRating?.aggregateRating ?: -1.0 }
    }
    return if (store.sortDir == SortDir.DESC) ascending.asReversed() else ascending
}

/**
 * R187 — the shared browse page (FR-RV-BROWSE1). Movies/Series (predefined [SeededBrowseStore.seedMediaKind]
 * filters, no [breadcrumb]) and a row's "→ See all" drill-in (arbitrary seed + [breadcrumb] naming the
 * row it came from) both render through this one composable — matching every other top-level/drill-in
 * screen in the app (Home, Channel), it always carries the full nav [AppBar], not a page-local back
 * affordance; system/D-pad Back is handled globally (see [onBack]'s doc on the other screens).
 */
@Composable
fun SeededBrowseScreen(
    store: SeededBrowseStore,
    title: String,
    breadcrumb: String?,
    subtitle: String?,
    showTypeFacet: Boolean,
    showFacetBar: Boolean,
    displayName: String,
    /** Which nav tab (if any) to highlight — pass Movies/Series' own index when this IS that tab;
     *  -1 (default) for a seeded drill-in that isn't itself one of the section tabs. */
    activeNav: Int = -1,
    onBack: () -> Unit,
    onNavSelect: (Int) -> Unit = {},
    onProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onItemSelect: (MediaCard) -> Unit,
    // R190 §B — the role/department meta line under the title, for a person seed only (FR-RV-PPL1-3).
    personRoleLine: String? = null,
    // R190 §C — opens the existing Seerr request flow for an overflow-row tile; null when this page
    // isn't a person seed (the row itself is hidden then too, see [SeededBrowseStore.seerrOverflow]).
    onRequestSelect: ((dev.jellystructure.shared.tv.DiscoverEntry) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    LaunchedEffect(Unit) { store.load() }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val gridState = store.gridState
    val firstCellFR = remember { FocusRequester() }
    val navBarFR = remember { FocusRequester() }
    // R187 fix — one FocusRequester per facet chip (plus sort), so a closed popover can restore focus
    // to the exact chip that opened it. Without this, Compose's default focus-loss recovery on the
    // popover's removal was landing focus on the AppBar's Home tab instead — a stray next D-pad press
    // (a normal "move to the next facet" RIGHT+OK) silently navigated the viewer clean out of the page.
    val facetChipFRs = remember { BrowseFacetKey.entries.associateWith { FocusRequester() } }
    val sortChipFR = remember { FocusRequester() }
    val firstFacetFR = facetChipFRs.getValue(BrowseFacetKey.GENRE)
    LaunchedEffect(Unit) { if (store.focusItemKey == null) runCatching { navBarFR.requestFocus() } }
    // R257 (FR-R257-1) — a seeded page is pushed from a tile / See all / a cast face: the viewer's
    // attention is on the content, not the app bar (whose first item, with activeNav = -1, is *Home* —
    // one stray OK left the page). The bar only HOLDS focus until the first results compose (R60:
    // something must, or Back bypasses Compose); then the first cell takes it — unless the viewer has
    // already moved off the bar themselves.
    val freshEntry = remember { store.focusItemKey == null }
    var movedOffBar by remember { mutableStateOf(false) }
    val barScrolled by remember { derivedStateOf {
        gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
    } }
    val navItems = raviloNavItems()

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .backToTopOnBack(
                atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
                onBackToTop = {
                    scope.launch {
                        runCatching { gridState.animateScrollToItem(0) }
                        runCatching { navBarFR.requestFocus() }
                    }
                },
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(top = RaviloDimens.appBarHeight + 24.dp)) {
            // Header: breadcrumb + title + subtitle (FR-RV-BROWSE1-3).
            Column(Modifier.padding(horizontal = raviloHPad)) {
                if (breadcrumb != null) {
                    Text(breadcrumb, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
                }
                Text(title, color = colors.text, fontSize = 28.sp, fontFamily = Sora, fontWeight = FontWeight.Bold)
                if (personRoleLine != null) {
                    Text(personRoleLine, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
                }
                val count = (state as? SeededBrowseState.Loaded)?.let { sortedFiltered(store, it.items).size }
                val sub = listOfNotNull(subtitle, count?.let { n -> if (n == 1) str("browse.title_one") else str("browse.titles", mapOf("count" to n.toString())) })
                    .joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
            }
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is SeededBrowseState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is SeededBrowseState.Error -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
                }
                is SeededBrowseState.Loaded -> {
                    if (showFacetBar) {
                        FacetBar(
                            store = store, all = s.items, showTypeFacet = showTypeFacet,
                            facetChipFRs = facetChipFRs, sortChipFR = sortChipFR,
                            onBarUp = { runCatching { navBarFR.requestFocus() } },
                            onBarDown = { runCatching { firstCellFR.requestFocus() } },
                        )
                        // R187 fix — small, restrained open/close motion (fade + vertical expand, ~150ms)
                        // instead of the popover just snapping in; matches AppBar's own tween(180) idiom.
                        AnimatedVisibility(
                            visible = store.openFacet != null || store.sortOpen,
                            enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                        ) {
                            val open = store.openFacet
                            if (open != null) {
                                FacetPopover(
                                    store = store, all = s.items, key = open,
                                    onClose = { store.openFacet = null; runCatching { facetChipFRs.getValue(open).requestFocus() } },
                                )
                            } else if (store.sortOpen) {
                                SortPopover(store = store, onClose = { store.sortOpen = false; runCatching { sortChipFR.requestFocus() } })
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    val filtered = remember(s.items, store.genre, store.type, store.maturity, store.year, store.watched, store.audio, store.channel, store.quality, store.sortField, store.sortDir) {
                        sortedFiltered(store, s.items)
                    }
                    // Bug fix — a sort change kept whatever grid position/focus the viewer had before,
                    // so "A–Z" could open still scrolled halfway down showing unrelated titles with the
                    // old focused tile silently re-highlighted. A new sort is a new list: drop the stale
                    // focus target and jump back to the top so the first (now differently-ordered) items
                    // are what's actually on screen — focus itself stays on the sort chip (unchanged).
                    LaunchedEffect(store.sortField, store.sortDir) {
                        store.focusItemKey = null
                        runCatching { gridState.scrollToItem(0) }
                    }
                    val seerrOverflow by store.seerrOverflow.collectAsState()
                    BrowseCardGrid(
                        items = filtered.map { it.card },
                        gridState = gridState,
                        firstCellFR = firstCellFR,
                        focusFirstOnLoad = freshEntry && !movedOffBar,
                        restoreItemKey = store.focusItemKey,
                        onItemSelect = { card -> store.focusItemKey = card.id; onItemSelect(card) },
                        seerrOverflow = if (onRequestSelect != null) seerrOverflow else emptyList(),
                        seerrRowLabel = str("browse.seerr_more", mapOf("name" to title)),
                        onRequestSelect = onRequestSelect,
                    )
                }
            }
        }

        AppBar(
            navItems = navItems,
            activeNav = activeNav,
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            onDown = {
                movedOffBar = true
                runCatching { (if (showFacetBar) firstFacetFR else firstCellFR).requestFocus() }
            },
            userInitials = displayName.take(2).uppercase(),
            onProfile = onProfile,
            onSearch = onSearch,
            scrolled = barScrolled,
        )
    }
}

@Composable
private fun FacetBar(
    store: SeededBrowseStore,
    all: List<BrowseCard>,
    showTypeFacet: Boolean,
    facetChipFRs: Map<BrowseFacetKey, FocusRequester>,
    sortChipFR: FocusRequester,
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
            val badge = facetChipSummary(store, key)
            var focused by remember { mutableStateOf(false) }
            // R187 fix — was an instant color snap; a short tween reads as one more small, restrained
            // bit of polish rather than a jarring toggle (same idiom as AppBar's own scrolled-bg tween).
            val chipBg by animateColorAsState(if (active) colors.accent else colors.surfaceVariant, tween(150), label = "facetChipBg")
            Box(
                Modifier
                    .background(chipBg, chipShape)
                    .then(if (focused && !active) Modifier.border(2.dp, colors.focusRing, chipShape) else Modifier)
                    .dpadFocusable(
                        focusRequester = facetChipFRs[key],
                        onFocused = { focused = true }, onBlurred = { focused = false },
                        onSelect = { store.openFacet = if (store.openFacet == key) null else key; store.sortOpen = false },
                        onUp = onBarUp, onDown = { if (store.openFacet == null) onBarDown() },
                        // Bug fix (live-tested on stue TV): this row sits directly under the AppBar, close
                        // enough that Right past the LAST chip fell through to native focus search and
                        // landed on the profile avatar instead of doing nothing -- an unrelated,
                        // disorienting jump. Left on the FIRST chip has the same risk. No-op both ends
                        // explicitly; every chip in between is untouched (still pure native search).
                        onLeft = { }.takeIf { i == 0 },
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
                    .dpadFocusable(
                        focusRequester = sortChipFR,
                        onFocused = { focused = true }, onBlurred = { focused = false },
                        onSelect = { store.sortOpen = !store.sortOpen; store.openFacet = null },
                        onUp = onBarUp,
                        // Bug fix -- see the matching comment on the facet chips above: this is always the
                        // row's true last item, so Right here must no-op rather than escape to the avatar.
                        onRight = { },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val dirArrow = if (store.sortDir == SortDir.DESC) "▼" else "▲"
                Text(
                    str("browse.sort") + ": " + sortLabel(store.sortField) + " " + dirArrow,
                    color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp, fontFamily = Sora,
                )
            }
        }
    }
}

/** R187 fix — the active-selection summary shown in a facet chip: the actual value name(s) for 1-2
 *  selections (e.g. "Action, Comedy"), then "+N" once there are more, instead of a bare count. */
@Composable
private fun facetChipSummary(store: SeededBrowseStore, key: BrowseFacetKey): String? {
    if (key == BrowseFacetKey.MATURITY) return store.maturity.label().takeIf { it.isNotBlank() }
    val sel = store.selectionFor(key)
    if (sel.isEmpty()) return null
    val names = sel.map { facetValueLabel(key, it, store.channelNames) }
    return if (names.size <= 2) names.joinToString(", ") else names.take(2).joinToString(", ") + " +${names.size - 2}"
}

@Composable
private fun facetLabel(key: BrowseFacetKey): String = when (key) {
    BrowseFacetKey.GENRE -> str("browse.facet.genre"); BrowseFacetKey.TYPE -> str("browse.facet.type")
    BrowseFacetKey.MATURITY -> str("browse.facet.maturity"); BrowseFacetKey.YEAR -> str("browse.facet.year")
    BrowseFacetKey.WATCHED -> str("browse.facet.watched"); BrowseFacetKey.AUDIO -> str("browse.facet.audio")
    BrowseFacetKey.CHANNEL -> str("browse.facet.channel"); BrowseFacetKey.QUALITY -> str("browse.facet.quality")
}

@Composable
private fun sortLabel(field: SortField): String = when (field) {
    SortField.RECENT -> str("browse.sort.recent"); SortField.TITLE -> str("browse.sort.title")
    SortField.YEAR -> str("browse.sort.year")
    SortField.MATURITY -> str("browse.sort.maturity"); SortField.IMDB -> str("browse.sort.imdb")
}

/** Bidirectional-sort fix — a short, direction-aware sub-label under the field name in the popover
 *  (e.g. "Newest first" / "Oldest first", "A–Z" / "Z–A") so the arrow isn't the only cue. */
@Composable
private fun sortDirLabel(field: SortField, dir: SortDir): String = when (field) {
    SortField.TITLE -> if (dir == SortDir.ASC) str("browse.sort.az") else str("browse.sort.za")
    SortField.RECENT -> if (dir == SortDir.DESC) str("browse.sort.newest") else str("browse.sort.oldest")
    SortField.YEAR -> if (dir == SortDir.DESC) str("browse.sort.newest") else str("browse.sort.oldest")
    SortField.MATURITY -> if (dir == SortDir.ASC) str("browse.sort.low_first") else str("browse.sort.high_first")
    SortField.IMDB -> if (dir == SortDir.DESC) str("browse.sort.high_first") else str("browse.sort.low_first")
}

/**
 * R187 (FR-RV-BROWSE1-8) — "the popover owns the remote while open": this is a Box with its own
 * [dpadFocusable] elements, requesting focus onto itself the moment it opens. Compose's own focus
 * system is exclusive (only the focused element receives key events), so this needs no manual global
 * key interception the way the player's picker does — that pattern belongs to PlayerScreen's own
 * single-focus-root architecture, not this grid's native traversal (see the spec's backend-review
 * addendum). onBack closes and returns focus to the facet bar via [onClose].
 */
/** R187 fix — the popover's width tracks its actual content instead of always spanning the full facet
 *  bar: a rough char-count heuristic over the (already display-ready, per [facetValueLabel]'s doc
 *  comment) longest value, clamped to a sane range. Audio gets extra room for its flag glyph. */
@Composable
private fun facetPopoverWidth(store: SeededBrowseStore, values: List<FacetValue>, key: BrowseFacetKey): androidx.compose.ui.unit.Dp {
    val maxLen = remember(values, store.channelNames) {
        values.maxOfOrNull { v ->
            when (key) {
                BrowseFacetKey.CHANNEL -> store.channelNames[v.value]?.length ?: v.value.length
                BrowseFacetKey.AUDIO -> (languageName(v.value) ?: v.value).length
                else -> v.value.length
            }
        } ?: 8
    }
    val flagAllowance = if (key == BrowseFacetKey.AUDIO) 28 else 0
    return (maxLen * 8 + 110 + flagAllowance).coerceIn(220, 420).dp
}

@Composable
private fun FacetPopover(store: SeededBrowseStore, all: List<BrowseCard>, key: BrowseFacetKey, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val firstRowFR = remember(key) { FocusRequester() }
    LaunchedEffect(key) { runCatching { firstRowFR.requestFocus() } }
    Box(
        Modifier.padding(horizontal = raviloHPad)
            .dpadFocusable(onBack = onClose)
    ) {
        if (key == BrowseFacetKey.MATURITY) {
            Column(
                Modifier.width(320.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                MaturityRangePicker(store, firstRowFR, onClose)
            }
        } else {
            val values = remember(all, store.genre, store.type, store.maturity, store.year, store.watched, store.audio, store.channel, store.quality) {
                store.valuesFor(all, key)
            }
            Column(
                Modifier.width(facetPopoverWidth(store, values, key)).background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
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
                            // R187 fix — no unselected-row bullet; a fixed-width slot keeps the checkmark
                            // from shifting the label when a row becomes (un)selected.
                            Box(Modifier.width(18.dp)) {
                                if (selected) Text("✓", color = colors.accent, fontSize = 14.sp)
                            }
                            if (key == BrowseFacetKey.AUDIO) {
                                val flag = LANG_CC[v.value.lowercase()]
                                if (flag != null) {
                                    Image(
                                        painterResource(flag), contentDescription = null,
                                        modifier = Modifier.width(20.dp).height(14.dp).clip(RoundedCornerShape(2.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
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
    Box(Modifier.padding(horizontal = raviloHPad).dpadFocusable(onBack = onClose)) {
        Column(Modifier.width(260.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
            SortField.entries.forEachIndexed { i, opt ->
                var focused by remember { mutableStateOf(false) }
                val active = store.sortField == opt
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (focused) colors.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .dpadFocusable(
                            focusRequester = if (i == 0) firstFR else null,
                            onFocused = { focused = true }, onBlurred = { focused = false },
                            // Bug fix (bidirectional sort): picking an already-active field used to be a
                            // no-op — there was no way to flip newest-first ↔ oldest-first etc. without
                            // this popover exposing a direction at all. Re-selecting the active field now
                            // flips it; selecting a different field switches to its sensible default dir.
                            onSelect = {
                                if (active) store.sortDir = if (store.sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
                                else { store.sortField = opt; store.sortDir = defaultDirFor(opt) }
                                onClose()
                            },
                            onLeft = onClose, onRight = onClose, onBack = onClose,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // R187 fix — same no-dot-when-unselected treatment as FacetPopover's checklist.
                    Box(Modifier.width(18.dp)) {
                        if (active) Text("✓", color = colors.accent, fontSize = 14.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(sortLabel(opt), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
                        Text(
                            sortDirLabel(opt, if (active) store.sortDir else defaultDirFor(opt)),
                            color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora,
                        )
                    }
                    if (active) {
                        Text(
                            if (store.sortDir == SortDir.DESC) "▼" else "▲",
                            color = colors.accent, fontSize = 14.sp, fontFamily = Sora,
                        )
                    }
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
    // R257 (FR-R257-1) — hand focus to the first cell once, when the first non-empty result composes.
    focusFirstOnLoad: Boolean = false,
    // R190 §C — a person-scoped Seerr row, appended after every grid item as one full-width span (not
    // a nested scrollable — TV D-pad traversal handles a second scrollable inside a grid poorly, and
    // this keeps it in the same natural Down-navigation flow as the grid itself).
    seerrOverflow: List<dev.jellystructure.shared.tv.DiscoverEntry> = emptyList(),
    seerrRowLabel: String = "",
    onRequestSelect: ((dev.jellystructure.shared.tv.DiscoverEntry) -> Unit)? = null,
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
    var firstFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (focusFirstOnLoad && !firstFocusDone && items.isNotEmpty()) {
            firstFocusDone = true
            // The cell's requester attaches on the grid's first layout pass, one frame after this effect.
            repeat(10) { if (runCatching { firstCellFR.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(16) }
        }
    }
    val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else LocalGridColumns.current
    if (items.isEmpty() && seerrOverflow.isEmpty()) {
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
        if (seerrOverflow.isNotEmpty() && onRequestSelect != null) {
            item(key = "seerr-overflow", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = RaviloDimens.rowGap)) {
                    Text(
                        seerrRowLabel, color = RaviloTheme.colors.text, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                    )
                    Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        items(seerrOverflow.size, key = { i -> "overflow:${seerrOverflow[i].entry.tmdbId}" }) { i ->
                            val e = seerrOverflow[i]
                            RequestTile(e) { onRequestSelect(e) }
                        }
                    }
                }
            }
        }
    }
}
