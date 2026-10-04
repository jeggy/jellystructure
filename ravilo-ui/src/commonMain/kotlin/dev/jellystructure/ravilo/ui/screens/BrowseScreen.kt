package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.PageTitle
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.focus.requestFocusRetryingOrMoveNative
import dev.jellystructure.ravilo.ui.focus.requestFocusAwaiting
import androidx.compose.ui.focus.FocusDirection
import dev.jellystructure.ravilo.ui.focus.gridColumnKeys
import androidx.compose.ui.platform.LocalFocusManager
import dev.jellystructure.ravilo.ui.focus.rememberGridFocus
import dev.jellystructure.ravilo.ui.focus.FollowGridRefresh
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import dev.jellystructure.ravilo.ui.focus.tryRequestFocus
import dev.jellystructure.ravilo.ui.theme.raviloItemSpacing
import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import dev.jellystructure.ravilo.ui.theme.raviloTrackPadV
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.components.LoadErrorState
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalGridColumns
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.LocalPortraitGridColumns
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyGridEffect
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.MUSIC_KIND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class BrowseKind(val apiKey: String?) {
    // R267 (FR-R267-5c) — MUSIC is new. Music videos are a real library type (phase 172) and the
    // phone's Library dropdown offers them; before this, any kind other than movie/series fell
    // through to `all` server-side and would have rendered the whole library under a Music heading.
    ALL(null), MOVIES("movie"), SERIES("series"), MUSIC(MUSIC_KIND), MY_LIST("mylist")
}

sealed class BrowseState {
    data object Loading : BrowseState()
    data class Loaded(val results: SearchResults, val facets: BrowseFacets) : BrowseState()
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : BrowseState()
}

// Bug fix (R118 follow-up): items fetched per page. The grid pages in as the user scrolls near its
// current end instead of fetching the whole filtered set in one response — that "full set" response
// used to grow with the whole library on every single Browse "All" open, even though the lazy grid
// only ever composes/loads images for the handful of visible cells. Appending pages only ever adds
// tiles past what's already rendered (never reorders/resizes/removes an existing one), so this
// doesn't violate the no-flicker/no-row-jump rule — that rule is about content popping in among
// already-visible rows, not about more of the same grid becoming available further down as you keep
// scrolling, which is standard behavior for any scrollable list.
private const val BROWSE_PAGE_SIZE = 60
private const val BROWSE_LOAD_MORE_LOOKAHEAD = 2 * 6  // ~2 rows at a typical 6-column width

class BrowseStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state.asStateFlow()
    val gridState = LazyGridState()   // R137: retained grid scroll survives navigate→back
    var focusItemKey: String? = null  // R139: the grid cell the user last navigated from
    var focusItemIndex: Int = 0       // R361 (FR-R361-4/5): where it sat in the list when opened
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    var activeKind: BrowseKind = BrowseKind.ALL
        private set
    var activeGenre: String? = null
        private set

    private var currentPage = 1
    private var endReached = false
    private var loadingMore = false

    init {
        // R147: patch grid tiles in place when a watched-state change is broadcast (instant, no re-fetch).
        scope.launch {
            WatchedBus.patches.collect { patch ->
                val s = _state.value as? BrowseState.Loaded ?: return@collect
                _state.value = BrowseState.Loaded(
                    s.results.copy(items = s.results.items.map { it.applyWatchedPatch(patch) }), s.facets,
                )
            }
        }
    }

    fun load(kind: BrowseKind = activeKind, genre: String? = null) {
        loadJob?.cancel(); loadMoreJob?.cancel()
        activeKind = kind; activeGenre = genre
        currentPage = 1; endReached = false; loadingMore = false
        _state.value = BrowseState.Loading
        loadJob = scope.launch {
            _state.value = runCatching {
                val results = apiClient.browse(kind = kind.apiKey, genre = genre, page = 1, pageSize = BROWSE_PAGE_SIZE)
                endReached = results.items.size < BROWSE_PAGE_SIZE
                val facets = apiClient.getFacets(kind = kind.apiKey)
                BrowseState.Loaded(results, facets)
            }.getOrElse { BrowseState.Error(it.message ?: "", loadErrorKindOf(it)) }
        }
    }

    /**
     * R364 (FR-R364-1) — what every arrival does: a silent re-fetch of page 1. A `Loaded` list stays on screen until
     * the new one lands (no *Loading…* flash, R212's rule) and a failed refresh leaves it as it is; `Loading` is shown
     * only from nothing (a first visit — which also covers `ALL`, whose first visit used to never load at all, review
     * item 2) or on a kind change. Paging starts again at page 1.
     */
    fun refresh(kind: BrowseKind = activeKind) {
        if (kind != activeKind || _state.value !is BrowseState.Loaded) { load(kind); return }
        loadJob?.cancel(); loadMoreJob?.cancel()
        // Paging is held while page 1 is re-fetched: a load-more against the old list would append to it a page the
        // refresh is about to replace (duplicate keys in the grid).
        loadingMore = true
        val genre = activeGenre
        loadJob = scope.launch {
            try {
                val next = runCatching {
                    val results = apiClient.browse(kind = kind.apiKey, genre = genre, page = 1, pageSize = BROWSE_PAGE_SIZE)
                    BrowseState.Loaded(results, apiClient.getFacets(kind = kind.apiKey))
                }.getOrNull() ?: return@launch
                currentPage = 1
                endReached = next.results.items.size < BROWSE_PAGE_SIZE
                _state.value = next
            } finally {
                loadingMore = false
            }
        }
    }

    /** Filter by genre in place — keeps the facets/genre chips mounted (no Loading flicker),
     *  refetches only the results grid so focus never escapes to "Home" (R67). */
    fun filterByGenre(genre: String?) {
        loadJob?.cancel(); loadMoreJob?.cancel()
        activeGenre = genre
        currentPage = 1; endReached = false; loadingMore = false
        val currentFacets = (_state.value as? BrowseState.Loaded)?.facets
        if (currentFacets != null) {
            // Keep the current state visible (chips stay mounted); swap results in place.
            val currentResults = (_state.value as BrowseState.Loaded).results
            _state.value = BrowseState.Loaded(currentResults, currentFacets) // optimistic (clear results)
        }
        loadJob = scope.launch {
            val kind = activeKind
            _state.value = runCatching {
                val results = apiClient.browse(kind = kind.apiKey, genre = genre, page = 1, pageSize = BROWSE_PAGE_SIZE)
                endReached = results.items.size < BROWSE_PAGE_SIZE
                val facets = currentFacets ?: apiClient.getFacets(kind = kind.apiKey)
                BrowseState.Loaded(results, facets)
            }.getOrElse { cur ->
                currentFacets?.let { BrowseState.Loaded((_state.value as? BrowseState.Loaded)?.results ?: emptyResults, it) }
                    ?: BrowseState.Error(cur.message ?: "", loadErrorKindOf(cur))
            }
        }
    }

    /** Appends the next page to the grid — call only once the user has scrolled near the currently
     *  loaded end (see LoadMoreEffect in BrowseScreen). No-ops past the last page or while already
     *  fetching, so a fast scroll can't fire overlapping/duplicate page requests. */
    fun loadMore() {
        if (endReached || loadingMore) return
        if (_state.value !is BrowseState.Loaded) return
        loadingMore = true
        val kind = activeKind
        val genre = activeGenre
        val nextPage = currentPage + 1
        loadMoreJob = scope.launch {
            val more = runCatching {
                apiClient.browse(kind = kind.apiKey, genre = genre, page = nextPage, pageSize = BROWSE_PAGE_SIZE)
            }.getOrNull()
            if (more != null) {
                currentPage = nextPage
                endReached = more.items.size < BROWSE_PAGE_SIZE
                val cur = _state.value as? BrowseState.Loaded
                if (cur != null) {
                    _state.value = BrowseState.Loaded(cur.results.copy(items = cur.results.items + more.items), cur.facets)
                }
            }
            loadingMore = false
        }
    }
}

private val emptyResults = SearchResults(query = "", items = emptyList())

@Composable
fun BrowseScreen(
    kind: BrowseKind,
    store: BrowseStore,
    displayName: String,
    onBack: () -> Unit,
    onNavSelect: (Int) -> Unit = {},
    onItemSelect: (MediaCard) -> Unit,
    onProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors

    // R364 (FR-R364-1) — every arrival refreshes (the kept store used to load only when its kind changed, so My List
    // never showed a title added since the app started). FR-R364-2's client-side patch is not built (the dev review
    // drops it: inserting a card is derived catalog state, constitution invariant 4); this arrival refresh covers it.
    LaunchedEffect(store, kind) { store.refresh(kind) }

    val state by store.state.collectAsState()

    // R55: Back scrolls a scrolled grid to the top before falling through to RaviloApp's pop.
    val gridState = store.gridState   // R137: retained scroll position survives navigate→back
    val scope = rememberCoroutineScope()
    val screenFocusManager = LocalFocusManager.current
    val firstCellFR = remember { FocusRequester() }
    // R117: the genre-chip row's first ("All") chip — the DOWN target from the AppBar so the chips
    // aren't skipped on the way into the grid.
    val firstChipFR = remember { FocusRequester() }

    // R60: NavBar focus — ensures Back fires through Compose (not Android finish()) and AppBar is visible
    val navBarFR = remember { FocusRequester() }
    // R139: on a Back-return from a grid cell, the grid re-focuses that cell; skip the default nav-bar focus.
    val returning = remember { store.focusItemKey != null }
    // R364 (FR-R364-3) — My List is opened from the profile menu: arrival focus goes back to the avatar, not to Home
    // (one stray OK used to leave the page).
    val avatarFR = remember { FocusRequester() }
    val arrivalFR = if (kind == BrowseKind.MY_LIST && isTvPlatform) avatarFR else navBarFR
    LaunchedEffect(Unit) { if (!returning) requestFocusRetrying(scope, arrivalFR) }

    val navItems = raviloNavItems()
    // R170 — My List moved out of the section-tab row into the avatar's ProfileMenu, so it no longer
    // has a nav index to highlight; -1 never matches any tab's index, leaving all of them unselected.
    val activeNav = when (kind) {
        BrowseKind.MOVIES -> 1
        BrowseKind.SERIES -> 2
        BrowseKind.MY_LIST -> -1
        BrowseKind.ALL -> 0
        // R267 — a phone-only Library type; it has no TV nav tab to highlight, and this screen is
        // only ever reached with MUSIC on a handset, where the selection lives in the bottom bar.
        BrowseKind.MUSIC -> -1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .backToTopOnBack(
                atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
                onBackToTop = {
                    scope.launch {
                        runCatching { gridState.animateScrollToItem(0) }
                        firstCellFR.requestFocusAwaiting()
                    }
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = RaviloDimens.appBarHeight + 24.dp)) {
            when (val s = state) {
                is BrowseState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is BrowseState.Error -> Box(Modifier.weight(1f)) {
                    LoadErrorState(s.kind, onRetry = { store.load(kind) }, onBack = onBack)
                }
                is BrowseState.Loaded -> {
                    // Genre chips (skip for My List)
                    if (kind != BrowseKind.MY_LIST && s.facets.genres.isNotEmpty()) {
                        GenreChips(
                            genres = s.facets.genres,
                            activeGenre = store.activeGenre,
                            onSelect = { store.filterByGenre(it) },
                            firstChipFR = firstChipFR,
                            onChipUp = { runCatching { navBarFR.requestFocus() } },
                            // R362 (FR-R362-3) — item 0's requester is not composed once the grid has scrolled: retry,
                            // then the native move, so the key is never dead.
                            onChipDown = { requestFocusRetryingOrMoveNative(scope, firstCellFR, screenFocusManager, FocusDirection.Down) },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    // R204 — Movies/Series/All get their page identity from the still-visible nav-bar
                    // tab label (AppBar renders every tab's text regardless of active state); My List
                    // has no nav-bar tab at all since R170 moved it into the avatar's ProfileMenu, so
                    // it needs its own heading here or the page says nothing about what it is.
                    if (kind == BrowseKind.MY_LIST) {
                        // R364 (FR-R364-4) — in the app's display face, like every other page title.
                        PageTitle(str("nav.my_list"), modifier = Modifier.padding(horizontal = raviloHPad))
                        Spacer(Modifier.height(8.dp))
                    }
                    // Count + grid
                    // Bug fix: "1 titles" read wrong — found during general mobile exploration testing.
                    // Bug fix: reads the true total (s.results.total), not items.size — the grid now
                    // pages in (R118 follow-up), so items.size is only how much has loaded so far.
                    Text(
                        if (s.results.total == 1) str("browse.title_one")
                        else str("browse.titles", mapOf("count" to s.results.total.toString())),
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = raviloHPad),
                    )
                    // R364 (FR-R364-3) — an empty My List says how a title gets there, naming the button by its own label.
                    if (kind == BrowseKind.MY_LIST && s.results.total == 0) {
                        Text(
                            str("browse.mylist_empty_hint", mapOf("button" to "+ ${str("nav.my_list")}")),
                            color = colors.textSecondary,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = raviloHPad, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    BrowseGrid(
                        items = s.results.items,
                        gridState = gridState,
                        firstCellFR = firstCellFR,
                        restoreItemKey = store.focusItemKey,   // R139
                        restoreItemIndex = store.focusItemIndex,   // R361 (FR-R361-4)
                        onRestoreResolved = { store.focusItemKey = null },
                        onEmpty = { requestFocusRetrying(scope, arrivalFR) },
                        onItemSelect = { card ->   // R139: save on select
                            store.focusItemKey = card.id
                            store.focusItemIndex = s.results.items.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
                            onItemSelect(card)
                        },
                        onLoadMore = { store.loadMore() },
                    )
                }
            }
        }

        AppBar(
            navItems = navItems,
            activeNav = activeNav,
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            // R117: DOWN from the nav bar lands on the genre chips (the "All" chip) when they're shown,
            // not straight on the grid; My List (no chips) drops to the grid as before.
            onDown = {
                val chipsShown = (state as? BrowseState.Loaded)
                    ?.let { kind != BrowseKind.MY_LIST && it.facets.genres.isNotEmpty() } ?: false
                requestFocusRetryingOrMoveNative(scope, if (chipsShown) firstChipFR else firstCellFR, screenFocusManager, FocusDirection.Down)
            },
            userInitials = displayName.take(2).uppercase(),
            onProfile = onProfile,
            onSearch = onSearch,
            avatarFocusRequester = avatarFR,
        )
    }
}

@Composable
private fun GenreChips(
    genres: List<FacetItem>,
    activeGenre: String?,
    onSelect: (String?) -> Unit,
    // R117: the AppBar's DOWN target (first chip), and explicit vertical exits so the chip row sits
    // cleanly between the nav bar (UP) and the grid (DOWN) instead of relying on native focus search.
    firstChipFR: FocusRequester,
    onChipUp: () -> Unit,
    onChipDown: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val chips = listOf(null) + genres.map { it.name }
    val chipShape = remember { RoundedCornerShape(18.dp) }

    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = raviloHPad),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(chips.size, key = { i -> chips[i] ?: "all" }) { i ->
            val label = chips[i] ?: str("browse.all")
            val isActive = chips[i] == activeGenre
            var focused by rememberFocusVisual()

            Box(
                modifier = Modifier
                    .background(
                        if (isActive) colors.accent else colors.surfaceVariant,
                        chipShape,
                    )
                    .then(
                        if (focused && !isActive) Modifier.border(2.dp, colors.focusRing, chipShape)
                        else Modifier
                    )
                    .dpadFocusable(
                        focusRequester = if (i == 0) firstChipFR else null,
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onSelect = { onSelect(chips[i]) },
                        onUp = onChipUp,
                        onDown = onChipDown,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isActive) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 14.sp,
                    fontFamily = sora,
                    fontWeight = if (isActive || focused) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BrowseGrid(
    items: List<MediaCard>,
    gridState: LazyGridState,
    firstCellFR: FocusRequester,
    restoreItemKey: String?,   // R139: the cell to re-focus on Back (its scroll is already retained)
    onItemSelect: (MediaCard) -> Unit,
    restoreItemIndex: Int = 0,          // R361: the cell now at this index when the opened one is gone
    onRestoreResolved: () -> Unit = {}, // R361: the caller clears its key once the restore resolved
    onEmpty: () -> Unit = {},           // R361: nothing left to focus in the grid
    onLoadMore: () -> Unit,   // R118 follow-up: fetch the next page once scrolled near the loaded end
) {
    // R88: warm the next 4 poster images ahead of the scroll position.
    val prefetchUrls = remember(items) { items.map { it.posterUrl.orEmpty() } }
    PrefetchLazyGridEffect(gridState = gridState, urls = prefetchUrls)

    // Bug fix (R118 follow-up): trigger the next page a couple of rows before the user actually hits
    // the loaded end, same lookahead spirit as the image prefetch above — onLoadMore() itself is a
    // no-op once the store has no more pages or a fetch is already in flight, so this can fire on
    // every scroll tick harmlessly.
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= 0 && lastVisible >= items.size - BROWSE_LOAD_MORE_LOOKAHEAD) onLoadMore()
            }
    }

    // R139: on a Back-return, re-focus the cell the user navigated from. The grid's scroll is retained
    // (R137) so the cell is already in view → request focus directly (no scroll disturbance).
    // R361 (FR-R361-4) — the cell if it is still there, else the cell now at its index; the key is spent either way.
    val gridFocus = rememberGridFocus(gridState)
    val keys = remember(items) { items.map { it.id } }
    val gridScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var restoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (restoredOnce || restoreItemKey == null) return@LaunchedEffect
        restoredOnce = true
        onRestoreResolved()
        if (!gridFocus.restore(keys, restoreItemKey, restoreItemIndex)) onEmpty()
    }
    // R361 (FR-R361-3 for grids, R364 review item 4) — a refresh that drops the focused cell: the cell now there.
    FollowGridRefresh(gridFocus, keys, onEmpty = onEmpty)

    // Native 2-D focus traversal across the grid: the framework composes off-screen rows in the
    // search direction and scrolls them into view; focusRestorer() returns focus to the last cell
    // on re-entry. No per-item FocusRequester (except the single first-cell back-to-top target, R55),
    // no scroll-to-focused effect.
    // R174 — items per row is server-configured; portrait viewports use the smaller portrait count.
    val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else LocalGridColumns.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        state = gridState,
        modifier = Modifier.focusRestorer().onFocusChanged { gridFocus.gridFocused = it.hasFocus }
            // R362 (FR-R362-5) — Down and Up keep the column (the same handler as the Movies/Series grid).
            .gridColumnKeys(gridFocus, { keys }, cols, gridScope, focusManager),
        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = raviloTrackPadV),
        horizontalArrangement = Arrangement.spacedBy(raviloItemSpacing),
        verticalArrangement = Arrangement.spacedBy(raviloRowGap),
    ) {
        items(items.size, key = { i -> items[i].id }) { i ->
            val card = items[i]
            // R361 — the wrapper carries the restore/refresh target's requester and tracks focus; it adds no size.
            Box(gridFocus.itemModifier(card.id, i)) {
                Tile(
                    title = card.title,
                    posterUrl = card.posterUrl,
                    progressPct = card.progressPct ?: 0f,
                    watched = card.watched,
                    upcomingLabel = card.upcomingEpisode,
                    qualityBadge = card.qualityBadge,   // R325
                    // R55: the first cell is the back-to-top landing target (always: R362 review item 2).
                    focusRequester = if (i == 0) firstCellFR else null,
                    onSelect = { onItemSelect(card) },
                )
            }
        }
    }
}
