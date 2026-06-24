package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.OnScreenKeyboard
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SearchState {
    data object Loading : SearchState()
    data class Loaded(val results: SearchResults, val query: String) : SearchState()
    data class Error(val message: String) : SearchState()
}

class SearchStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SearchState>(SearchState.Loaded(
        SearchResults("", emptyList()), ""
    ))
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var debounceJob: kotlinx.coroutines.Job? = null

    fun onQuery(query: String) {
        debounceJob?.cancel()
        if (query.isBlank()) {
            scope.launch { loadSuggestions() }
            return
        }
        debounceJob = scope.launch {
            delay(250)
            _state.value = SearchState.Loading
            _state.value = runCatching {
                SearchState.Loaded(apiClient.search(query), query)
            }.getOrElse { SearchState.Error(it.message ?: "Error") }
        }
    }

    private suspend fun loadSuggestions() {
        _state.value = runCatching {
            SearchState.Loaded(apiClient.search(""), "")
        }.getOrElse { SearchState.Error(it.message ?: "Error") }
    }

    init { scope.launch { loadSuggestions() } }
}

private const val GRID_COLS_SEARCH = 5

@Composable
fun SearchScreen(
    store: SearchStore,
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val state by store.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var inGrid by remember { mutableStateOf(false) }

    val items = when (val s = state) {
        is SearchState.Loaded -> s.results.items
        else -> emptyList()
    }

    // Single entry requester for the grid; native traversal handles cell-to-cell movement.
    // focusedGridIdx is tracked (cheaply) only so the edge-exit handler knows when the user is
    // on the top row / first column and should drop back to the keyboard.
    val gridFR = remember { FocusRequester() }
    var focusedGridIdx by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 48.dp)
            // R55: Back drops out of the results grid to the keyboard (which auto-focuses) and scrolls the
            // grid back to the top; on the keyboard it falls through to RaviloApp's pop. The keyboard is
            // this page's "top", so being there counts as at-top.
            .backToTopOnBack(
                atTop = { !inGrid },
                onBackToTop = {
                    inGrid = false
                    scope.launch { runCatching { gridState.animateScrollToItem(0) } }
                },
            ),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RaviloDimens.screenPadH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                str("nav.search"),
                color = colors.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-0.5).sp,
            )
            Spacer(Modifier.weight(1f))
            if (query.isNotEmpty()) {
                Text(str("search.clear"), color = colors.accent, fontSize = 16.sp, fontFamily = sora)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Search bar — 76dp min height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RaviloDimens.screenPadH)
                .height(60.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(str("search.placeholder"), color = colors.textSecondary, fontSize = 16.sp, fontFamily = sora)
            } else {
                Text(query + "█", color = colors.text, fontSize = 16.sp, fontFamily = sora)
            }
        }
        Spacer(Modifier.height(24.dp))

        // On-screen keyboard
        if (!inGrid) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OnScreenKeyboard(
                    onChar = {
                        query += it
                        store.onQuery(query)
                    },
                    onDelete = {
                        if (query.isNotEmpty()) {
                            query = query.dropLast(1)
                            store.onQuery(query)
                        }
                    },
                    onDone = {
                        if (items.isNotEmpty()) {
                            inGrid = true
                            gridFR.requestFocus()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // Results label
        val label = when {
            query.isEmpty()                              -> str("search.suggestions_label")
            items.isEmpty() && state is SearchState.Loaded -> str("search.empty", mapOf("query" to query))
            state is SearchState.Loading                 -> str("loading")
            else                                         -> str("search.results", mapOf("count" to items.size.toString()))
        }
        Text(
            label,
            color = colors.textSecondary,
            fontSize = 16.sp,
            fontFamily = sora,
            modifier = Modifier.padding(horizontal = RaviloDimens.screenPadH),
        )
        Spacer(Modifier.height(12.dp))

        if (items.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLS_SEARCH),
                state = gridState,
                modifier = Modifier
                    .focusRequester(gridFR)
                    .focusRestorer()
                    // Native traversal moves between cells; intercept only the top-edge (Up) and
                    // left-edge (Left) cases to drop focus back to the keyboard, which re-appears
                    // and auto-focuses its first key.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionUp ->
                                if (focusedGridIdx < GRID_COLS_SEARCH) { inGrid = false; true } else false
                            Key.DirectionLeft ->
                                if (focusedGridIdx % GRID_COLS_SEARCH == 0) { inGrid = false; true } else false
                            else -> false
                        }
                    },
                contentPadding = PaddingValues(
                    horizontal = RaviloDimens.trackPadH,
                    vertical = RaviloDimens.trackPadV,
                ),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                verticalArrangement = Arrangement.spacedBy(RaviloDimens.rowGap),
            ) {
                items(items.size, key = { i -> items[i].id }) { i ->
                    val card = items[i]
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        progressPct = card.progressPct ?: 0f,
                        onFocused = { focusedGridIdx = i; inGrid = true },
                        onSelect = { onItemSelect(card) },
                    )
                }
            }
        }
    }
}
