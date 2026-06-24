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
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
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
    private var loadJob: Job? = null

    var activeKind: BrowseKind = BrowseKind.ALL
        private set
    var activeGenre: String? = null
        private set

    fun load(kind: BrowseKind = activeKind, genre: String? = activeGenre) {
        loadJob?.cancel()
        activeKind = kind; activeGenre = genre
        _state.value = BrowseState.Loading
        loadJob = scope.launch {
            _state.value = runCatching {
                val results = apiClient.browse(kind = kind.apiKey, page = 1)
                val facets = apiClient.getFacets(kind = kind.apiKey)
                BrowseState.Loaded(results, facets)
            }.getOrElse { BrowseState.Error(it.message ?: "Unknown error") }
        }
    }

    fun filterByGenre(genre: String?) = load(activeKind, genre)
}

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
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val firstCellFR = remember { FocusRequester() }

    // R60: NavBar focus — ensures Back fires through Compose (not Android finish()) and AppBar is visible
    val navBarFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }

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
        Column(modifier = Modifier.fillMaxSize().padding(top = 84.dp)) {
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
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    // Count + grid
                    Text(
                        str("browse.titles", mapOf("count" to s.results.items.size.toString())),
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = RaviloDimens.screenPadH),
                    )
                    Spacer(Modifier.height(12.dp))
                    BrowseGrid(
                        items = s.results.items,
                        gridState = gridState,
                        firstCellFR = firstCellFR,
                        onItemSelect = onItemSelect,
                    )
                }
            }
        }

        AppBar(
            navItems = navItems,
            activeNav = activeNav,
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            onDown = { runCatching { firstCellFR.requestFocus() } },
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
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val chips = listOf(null) + genres.map { it.name }
    val chipShape = remember { RoundedCornerShape(18.dp) }

    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH),
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
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onSelect = { onSelect(chips[i]) },
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
    onItemSelect: (MediaCard) -> Unit,
) {
    // Native 2-D focus traversal across the grid: the framework composes off-screen rows in the
    // search direction and scrolls them into view; focusRestorer() returns focus to the last cell
    // on re-entry. No per-item FocusRequester (except the single first-cell back-to-top target, R55),
    // no scroll-to-focused effect.
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLS),
        state = gridState,
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = RaviloDimens.trackPadV),
        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(RaviloDimens.rowGap),
    ) {
        items(items.size, key = { i -> items[i].id }) { i ->
            val card = items[i]
            Tile(
                title = card.title,
                posterUrl = card.posterUrl,
                progressPct = card.progressPct ?: 0f,
                // R55: the first cell is the back-to-top focus landing target.
                focusRequester = if (i == 0) firstCellFR else null,
                onSelect = { onItemSelect(card) },
            )
        }
    }
}
