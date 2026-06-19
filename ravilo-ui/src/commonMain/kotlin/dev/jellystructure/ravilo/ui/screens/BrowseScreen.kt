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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
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
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors

    LaunchedEffect(kind) { if (store.activeKind != kind) store.load(kind) }

    val state by store.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp)) {
            Text("‹ Back", color = colors.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val title = when (kind) {
                BrowseKind.ALL -> "All"
                BrowseKind.MOVIES -> "Movies"
                BrowseKind.SERIES -> "Series"
                BrowseKind.MY_LIST -> "My List"
            }
            Text(title, color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }

        when (val s = state) {
            is BrowseState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Loading…", color = colors.textSecondary, fontSize = 16.sp)
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
                    "${s.results.items.size} titles",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
                Spacer(Modifier.height(12.dp))
                BrowseGrid(items = s.results.items, onItemSelect = onItemSelect)
            }
        }
    }
}

@Composable
private fun GenreChips(
    genres: List<FacetItem>,
    activeGenre: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = RaviloTheme.colors
    val chips = listOf(null) + genres.map { it.name } // null = All
    val chipFRs = remember(chips.size) { List(chips.size) { FocusRequester() } }
    val chipShape = remember { RoundedCornerShape(20.dp) }
    var focusedChip by remember { mutableIntStateOf(0) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips.size, key = { i -> chips[i] ?: "all" }) { i ->
            val label = chips[i] ?: "All"
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
                        focusRequester = chipFRs[i],
                        onFocused = { focused = true; focusedChip = i },
                        onBlurred = { focused = false },
                        onLeft  = { if (i > 0) chipFRs[i - 1].requestFocus() },
                        onRight = { if (i < chips.lastIndex) chipFRs[i + 1].requestFocus() },
                        onSelect = { onSelect(chips[i]) },
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isActive) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 13.sp,
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
private fun BrowseGrid(items: List<MediaCard>, onItemSelect: (MediaCard) -> Unit) {
    val colors = RaviloTheme.colors
    val gridState = rememberLazyGridState()

    // FocusRequesters: one per item
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var focusedIdx by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusedIdx) {
        gridState.scrollToItem(focusedIdx.coerceAtLeast(0))
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLS),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items.size, key = { i -> items[i].id }) { i ->
            val card = items[i]
            val row = i / GRID_COLS
            val col = i % GRID_COLS

            Tile(
                title = card.title,
                posterUrl = card.posterUrl,
                focusRequester = focusRequesters[i],
                progressPct = card.progressPct ?: 0f,
                onFocused = { focusedIdx = i },
                onLeft  = { if (col > 0) focusRequesters[i - 1].requestFocus() },
                onRight = { if (col < GRID_COLS - 1 && i < items.lastIndex) focusRequesters[i + 1].requestFocus() },
                onUp    = { if (i >= GRID_COLS) focusRequesters[i - GRID_COLS].requestFocus() },
                onDown  = { if (i + GRID_COLS <= items.lastIndex) focusRequesters[i + GRID_COLS].requestFocus() },
                onSelect = { onItemSelect(card) },
            )
        }
    }
}
