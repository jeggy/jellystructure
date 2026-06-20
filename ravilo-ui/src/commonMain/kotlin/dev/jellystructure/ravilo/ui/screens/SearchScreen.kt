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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.OnScreenKeyboard
import dev.jellystructure.ravilo.ui.components.Tile
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

    val gridFRs = remember(items.size) { List(maxOf(items.size, 1)) { FocusRequester() } }
    var focusedGridIdx by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 48.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RaviloDimens.screenPadH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Search",
                color = colors.text,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.weight(1f))
            if (query.isNotEmpty()) {
                Text("Clear", color = colors.accent, fontSize = 16.sp, fontFamily = sora)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Search bar — 76dp min height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RaviloDimens.screenPadH)
                .height(76.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text("Start typing…", color = colors.textSecondary, fontSize = 20.sp, fontFamily = sora)
            } else {
                Text(query + "█", color = colors.text, fontSize = 20.sp, fontFamily = sora)
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
                            gridFRs[0].requestFocus()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // Results label
        val label = when {
            query.isEmpty()                              -> "Suggestions"
            items.isEmpty() && state is SearchState.Loaded -> "No results for \"$query\""
            state is SearchState.Loading                 -> "Searching…"
            else                                         -> "${items.size} results"
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
                contentPadding = PaddingValues(
                    horizontal = RaviloDimens.trackPadH,
                    vertical = RaviloDimens.trackPadV,
                ),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                verticalArrangement = Arrangement.spacedBy(RaviloDimens.rowGap),
            ) {
                items(items.size, key = { i -> items[i].id }) { i ->
                    val card = items[i]
                    val col = i % GRID_COLS_SEARCH
                    Tile(
                        title = card.title,
                        posterUrl = card.posterUrl,
                        focusRequester = gridFRs[i],
                        progressPct = card.progressPct ?: 0f,
                        onFocused = { focusedGridIdx = i; inGrid = true },
                        onLeft  = { if (col > 0) gridFRs[i - 1].requestFocus() else { inGrid = false } },
                        onRight = { if (col < GRID_COLS_SEARCH - 1 && i < items.lastIndex) gridFRs[i + 1].requestFocus() },
                        onUp    = {
                            if (i >= GRID_COLS_SEARCH) gridFRs[i - GRID_COLS_SEARCH].requestFocus()
                            else { inGrid = false }
                        },
                        onDown  = { if (i + GRID_COLS_SEARCH <= items.lastIndex) gridFRs[i + GRID_COLS_SEARCH].requestFocus() },
                        onSelect = { onItemSelect(card) },
                    )
                }
            }
        }
    }
}
