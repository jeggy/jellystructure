package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.jellystructure.ravilo.ui.LocalGridColumns
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.LocalPortraitGridColumns
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.components.AppBar
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
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : SearchState()
}

/** R295 (FR-R295-1) — which result was opened on which visit of Search; read once, on the way back. */
internal class SearchReturnTarget {
    private var target: Pair<Long, String>? = null
    fun remember(visit: Long, itemId: String) { target = visit to itemId }
    /** The id to land on when [visit] is the visit it was opened from, else null. Forgets either way. */
    fun take(visit: Long): String? = target?.takeIf { it.first == visit }?.second.also { target = null }
}

class SearchStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SearchState>(SearchState.Loaded(
        SearchResults("", emptyList()), ""
    ))
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var debounceJob: kotlinx.coroutines.Job? = null

    // R295 (FR-R295-1) — the result the viewer opened, and on which visit of the page. The store
    // outlives the composable (R259); the composable's own focus and scroll do not, so without this
    // Back from a detail page landed on the text field with the keyboard up (and, on a phone, on a
    // grid scrolled back to the top) instead of on the tile that was pressed.
    internal val returnTarget = SearchReturnTarget()

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
            }.getOrElse { SearchState.Error(it.message ?: "", loadErrorKindOf(it)) }
        }
    }

    private suspend fun loadSuggestions() {
        _state.value = runCatching {
            SearchState.Loaded(apiClient.search(""), "")
        }.getOrElse { SearchState.Error(it.message ?: "", loadErrorKindOf(it)) }
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

@Composable
fun SearchScreen(
    store: SearchStore,
    visit: Long = 0L,
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    // R277 (FR-R277-2) — set by a tap on the bottom bar's Search item while Search is already showing,
    // and consumed immediately, so the next tap sets a fresh one. A flag left true would raise the
    // keyboard once and then be indistinguishable from false for the rest of the page's life.
    focusInputOnEntry: Boolean = false,
    onFocusInputConsumed: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val state by store.state.collectAsState()

    // R259 — the store outlives this composable (it survives a push to a detail page), the text field
    // did not: Back from a result showed the previous results under an EMPTY field, relabelled
    // "Suggestions" (stue TV, 2026-09-17). The field starts from the query the results belong to.
    var query by remember { mutableStateOf((store.state.value as? SearchState.Loaded)?.query.orEmpty()) }

    val items = when (val s = state) {
        is SearchState.Loaded -> s.results.items
        else -> emptyList()
    }
    // R174 — server-configured items per row; portrait viewports use the smaller portrait count.
    val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else LocalGridColumns.current

    // R295 (FR-R295-1) — Back from a result: the index of the tile that was opened, if it is still in
    // the list. Resolved once, when the page is composed again.
    val returnIndex = remember {
        store.returnTarget.take(visit)?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    }
    var inGrid by remember { mutableStateOf(returnIndex != null) }

    val gridFR = remember { FocusRequester() }
    val textFieldFR = remember { FocusRequester() }
    val returnFR = remember { FocusRequester() }
    var focusedGridIdx by remember { mutableIntStateOf(returnIndex ?: 0) }
    // Opened scrolled to the returning tile's row, so it is on screen (and composed) from the first frame.
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = returnIndex?.let { it - it % cols.coerceAtLeast(1) } ?: 0)
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // R277 (FR-R277-1) — on a TV this screen IS the keyboard: the viewer arrives with a D-pad and no
    // other way in, so the field takes focus on entry and the IME comes with it. On a phone the same
    // two effects are the bug — the keyboard is half the screen and it arrives before anyone has said
    // they want to type, covering the suggestions the page exists to show. A phone viewer who wants to
    // type taps the field.
    val handset = LocalHandset.current
    val focusInput: () -> Unit = {
        textFieldFR.requestFocus()
        // Called even when the field already holds focus: Back dismisses the IME without moving focus,
        // so requestFocus() is a no-op there and show() is the half that does the work (FR-R277-2).
        keyboardController?.show()
    }

    // Auto-focus and open IME on screen entry — unless this is Back from a result, which lands on that
    // result (R295 FR-R295-1). A phone takes no focus either way; it only keeps its place in the grid.
    LaunchedEffect(Unit) {
        when {
            handset -> Unit
            returnIndex != null -> requestFocusRetrying(scope, returnFR)
            else -> focusInput()
        }
    }

    // Return focus to the text field and re-open IME when leaving the results grid
    LaunchedEffect(inGrid) {
        if (!inGrid && !handset) focusInput()
    }

    // FR-R277-2 — the bottom bar's own Search item, tapped while already here.
    // R267 (FR-R267-9) — and the results scroll back to their top; the query is kept. The scroll runs in
    // the screen's own scope: consuming the flag restarts this effect, which would cancel it mid-scroll
    // (seen on the Pixel 9 — the keyboard came up and the grid stayed where it was).
    LaunchedEffect(focusInputOnEntry) {
        if (focusInputOnEntry) {
            scope.launch { runCatching { gridState.animateScrollToItem(0) } }
            focusInput()
            onFocusInputConsumed()
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The gap is for the app bar this screen is drawn under. R277 (FR-R277-4) cut it to 16 dp on
            // a handset while Search had no top row; R267's open item closed 2026-09-25 by giving it
            // the same brand · cast row as every other page (FR-R267-2, and the mockup's persistent
            // row), so the gap is the ordinary one again on every platform.
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
                // R277 (FR-R277-3) — this was a plain Text: an accent-coloured affordance that had
                // never been clickable. It clears and nothing else; raising the keyboard is the
                // field's job and the bar's, and clearing mid-typing keeps the IME up on its own
                // because focus never moves.
                //
                // Handset only, and NOT because a TV has no pointer: Modifier.clickable is focusable,
                // so applying it unconditionally would insert a new node into the TV's D-pad graph on
                // this screen — reachable by Up from the field, ahead of whatever the viewer expects.
                // The TV's search flow is this phase's own non-goal; a focusable header control there
                // is a change someone should make deliberately, with a TV in front of them.
                Text(
                    str("search.clear"),
                    color = colors.accent,
                    fontSize = 16.sp,
                    fontFamily = sora,
                    modifier = if (!handset) Modifier else Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { query = ""; store.onQuery("") }
                        // Past the 46 dp touch floor without moving the label off the title's baseline.
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                )
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
            // Bug fix: "1 results" read wrong — found during general mobile exploration testing.
            items.size == 1                              -> str("search.result_one")
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
                columns = GridCells.Fixed(cols),
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
                                if (focusedGridIdx < cols) { inGrid = false; true } else false
                            Key.DirectionLeft ->
                                if (focusedGridIdx % cols == 0) { inGrid = false; true } else false
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
                        upcomingLabel = card.upcomingEpisode,
                        focusRequester = if (i == returnIndex) returnFR else null,
                        onFocused = { focusedGridIdx = i; inGrid = true },
                        onSelect = { store.returnTarget.remember(visit, card.id); onItemSelect(card) },
                    )
                }
            }
        }
    }
    // R267 (FR-R267-2) — the handset's top row: brand · cast, as on every other page. The TV keeps no
    // bar here (unchanged), and the phone's search and profile live in the bottom bar.
    if (handset) AppBar()
    }
}
