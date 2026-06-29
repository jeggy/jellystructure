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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
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

    init {
        scope.launch { loadSuggestions() }
        // R147: patch result tiles in place when a watched-state change is broadcast (instant, no re-fetch).
        scope.launch {
            WatchedBus.patches.collect { patch ->
                val s = _state.value as? SearchState.Loaded ?: return@collect
                _state.value = SearchState.Loaded(
                    s.results.copy(items = s.results.items.map { it.applyWatchedPatch(patch) }), s.query,
                )
            }
        }
    }
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

    val gridFR = remember { FocusRequester() }
    val textFieldFR = remember { FocusRequester() }
    var focusedGridIdx by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus and open IME on screen entry
    LaunchedEffect(Unit) {
        textFieldFR.requestFocus()
        keyboardController?.show()
    }

    // Return focus to the text field and re-open IME when leaving the results grid
    LaunchedEffect(inGrid) {
        if (!inGrid) {
            textFieldFR.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = RaviloDimens.appBarHeight + 24.dp)
            // Back from results grid → text field + IME; Back from text field → pops screen.
            .backToTopOnBack(
                atTop = { !inGrid },
                onBackToTop = {
                    inGrid = false
                    scope.launch { runCatching { gridState.animateScrollToItem(0) } }
                },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = raviloHPad),
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

        // Native IME text field — the OS keyboard appears automatically on focus
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = raviloHPad)
                .height(60.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it; store.onQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFR)
                    // D-pad Down moves focus into the results grid and hides the IME
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (ev.key == Key.DirectionDown && items.isNotEmpty()) {
                            inGrid = true
                            scope.launch { runCatching { gridFR.requestFocus() } }
                            keyboardController?.hide()
                            true
                        } else false
                    },
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp, fontFamily = sora),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (items.isNotEmpty()) {
                        inGrid = true
                        scope.launch { runCatching { gridFR.requestFocus() } }
                        keyboardController?.hide()
                    }
                }),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(str("search.placeholder"), color = colors.textSecondary, fontSize = 16.sp, fontFamily = sora)
                    }
                    innerTextField()
                },
            )
        }
        Spacer(Modifier.height(24.dp))

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
            modifier = Modifier.padding(horizontal = raviloHPad),
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
                    // left-edge (Left) exits to return focus to the text field, re-showing the IME.
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
                    horizontal = raviloHPad,
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
                        watched = card.watched,
                        onFocused = { focusedGridIdx = i; inGrid = true },
                        onSelect = { onItemSelect(card) },
                    )
                }
            }
        }
    }
}
