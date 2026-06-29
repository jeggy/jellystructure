package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BrowseKind(val apiKey: String?) {
    ALL(null), MOVIES("movie"), SERIES("series"), MY_LIST("mylist")
}

sealed class BrowseState {
    data object Loading : BrowseState()
    data class Loaded(val results: SearchResults, val facets: BrowseFacets) : BrowseState()
    data class Error(val message: String) : BrowseState()
}

class BrowseStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state.asStateFlow()
    val gridState = LazyGridState()   // R137: retained grid scroll survives navigate→back
    var focusItemKey: String? = null  // R139: the grid cell the user last navigated from
    private var loadJob: Job? = null

    var activeKind: BrowseKind = BrowseKind.ALL
        private set
    var activeGenre: String? = null
        private set

    fun load(kind: BrowseKind = activeKind, genre: String? = null) {
        loadJob?.cancel()
        activeKind = kind; activeGenre = genre
        _state.value = BrowseState.Loading
        loadJob = scope.launch {
            _state.value = runCatching {
                val results = apiClient.browse(kind = kind.apiKey, genre = genre)  // R118: full set (no pageSize cap)
                val facets = apiClient.getFacets(kind = kind.apiKey)
                BrowseState.Loaded(results, facets)
            }.getOrElse { BrowseState.Error(it.message ?: "Unknown error") }
        }
    }

    /** Filter by genre in place — keeps the facets/genre chips mounted (no Loading flicker),
     *  refetches only the results grid so focus never escapes to "Home" (R67). */
    fun filterByGenre(genre: String?) {
        loadJob?.cancel()
        activeGenre = genre
        val currentFacets = (_state.value as? BrowseState.Loaded)?.facets
        if (currentFacets != null) {
            // Keep the current state visible (chips stay mounted); swap results in place.
            val currentResults = (_state.value as BrowseState.Loaded).results
            _state.value = BrowseState.Loaded(currentResults, currentFacets) // optimistic (clear results)
        }
        loadJob = scope.launch {
            val kind = activeKind
            _state.value = runCatching {
                val results = apiClient.browse(kind = kind.apiKey, genre = genre)  // R118: full set (no pageSize cap)
                val facets = currentFacets ?: apiClient.getFacets(kind = kind.apiKey)
                BrowseState.Loaded(results, facets)
            }.getOrElse { cur ->
                currentFacets?.let { BrowseState.Loaded((_state.value as? BrowseState.Loaded)?.results ?: emptyResults, it) }
                    ?: BrowseState.Error(cur.message ?: "Unknown error")
            }
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
    discoverAvailable: Boolean = false,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk

    LaunchedEffect(kind) { if (store.activeKind != kind) store.load(kind) }

    val state by store.state.collectAsState()

    // R55: Back scrolls a scrolled grid to the top before falling through to RaviloApp's pop.
    val gridState = store.gridState   // R137: retained scroll position survives navigate→back
    val scope = rememberCoroutineScope()
    val firstCellFR = remember { FocusRequester() }
    // R117: the genre-chip row's first ("All") chip — the DOWN target from the AppBar so the chips
    // aren't skipped on the way into the grid.
    val firstChipFR = remember { FocusRequester() }

    // R60: NavBar focus — ensures Back fires through Compose (not Android finish()) and AppBar is visible
    val navBarFR = remember { FocusRequester() }
    // R139: on a Back-return from a grid cell, the grid re-focuses that cell; skip the default nav-bar focus.
    LaunchedEffect(Unit) { if (store.focusItemKey == null) runCatching { navBarFR.requestFocus() } }

    val navItems = buildList {
        add(str("nav.home")); add(str("nav.movies")); add(str("nav.series"))
        if (discoverAvailable) add("Top 10")
        add(str("nav.my_list"))
    }
    val activeNav = when (kind) {
        BrowseKind.MOVIES -> 1
        BrowseKind.SERIES -> 2
        BrowseKind.MY_LIST -> if (discoverAvailable) 4 else 3
        BrowseKind.ALL -> 0
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
                        runCatching { firstCellFR.requestFocus() }
                    }
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = RaviloDimens.appBarHeight + 24.dp)) {
            when (val s = state) {
                is BrowseState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is BrowseState.Error -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
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
                            onChipDown = { runCatching { firstCellFR.requestFocus() } },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    // Count + grid
                    Text(
                        str("browse.titles", mapOf("count" to s.results.items.size.toString())),
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = raviloHPad),
                    )
                    Spacer(Modifier.height(12.dp))
                    BrowseGrid(
                        items = s.results.items,
                        gridState = gridState,
                        firstCellFR = firstCellFR,
                        restoreItemKey = store.focusItemKey,   // R139
                        onItemSelect = { card -> store.focusItemKey = card.id; onItemSelect(card) },  // R139: save on select
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
                runCatching { (if (chipsShown) firstChipFR else firstCellFR).requestFocus() }
            },
            userInitials = displayName.take(2).uppercase(),
            onProfile = onProfile,
            onSearch = onSearch,
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
            var focused by remember { mutableStateOf(false) }

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

private const val GRID_COLS = 6

@Composable
private fun BrowseGrid(
    items: List<MediaCard>,
    gridState: LazyGridState,
    firstCellFR: FocusRequester,
    restoreItemKey: String?,   // R139: the cell to re-focus on Back (its scroll is already retained)
    onItemSelect: (MediaCard) -> Unit,
) {
    // R88: warm the next 4 poster images ahead of the scroll position.
    val prefetchUrls = remember(items) { items.map { it.posterUrl.orEmpty() } }
    PrefetchLazyGridEffect(gridState = gridState, urls = prefetchUrls)

    // R139: on a Back-return, re-focus the cell the user navigated from. The grid's scroll is retained
    // (R137) so the cell is already in view → request focus directly (no scroll disturbance).
    val restoreFR = remember { FocusRequester() }
    var restoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!restoredOnce && restoreItemKey != null && items.any { it.id == restoreItemKey }) {
            runCatching { restoreFR.requestFocus() }
            restoredOnce = true
        }
    }

    // Native 2-D focus traversal across the grid: the framework composes off-screen rows in the
    // search direction and scrolls them into view; focusRestorer() returns focus to the last cell
    // on re-entry. No per-item FocusRequester (except the single first-cell back-to-top target, R55),
    // no scroll-to-focused effect.
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLS),
        state = gridState,
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = raviloHPad, vertical = RaviloDimens.trackPadV),
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
                // R139 restore target takes precedence; R55: first cell is the back-to-top landing target.
                focusRequester = if (card.id == restoreItemKey) restoreFR else if (i == 0) firstCellFR else null,
                onSelect = { onItemSelect(card) },
            )
        }
    }
}
