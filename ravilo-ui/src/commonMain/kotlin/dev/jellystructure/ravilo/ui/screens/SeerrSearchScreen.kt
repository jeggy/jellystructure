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
import dev.jellystructure.ravilo.ui.LocalGridColumns
import dev.jellystructure.ravilo.ui.LocalLiveAcquisition
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.LocalPortraitGridColumns
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.coroutines.launch

/**
 * R171 (FR-R171-3) — search scoped to the Seerr catalogue only, opened from the Request tab's search
 * pill. Structurally a trimmed parallel of [SearchScreen] (same native-IME `BasicTextField` + grid +
 * D-pad focus-restore pattern) rather than a shared/generalized screen: the two searches differ enough
 * in result type (request tiles with a live status badge vs. plain library `MediaCard`s) and intent
 * (browse-and-request vs. play) that forcing one screen to cover both seemed like the riskier change.
 * Back returns to the Request tab (handled by the caller's nav stack, same as any other pushed screen).
 */
@Composable
fun SeerrSearchScreen(
    store: SeerrSearchStore,
    onBack: () -> Unit,
    onEntrySelect: (mediaType: String, tmdbId: Int) -> Unit,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val state by store.state.collectAsState()

    val acq = LocalLiveAcquisition.current
    LaunchedEffect(acq) { acq?.collect { store.applyAcquisition(it) } }

    var query by remember { mutableStateOf("") }
    var inGrid by remember { mutableStateOf(false) }

    val items = when (val s = state) {
        is SeerrSearchState.Loaded -> s.results.items
        else -> emptyList()
    }

    val gridFR = remember { FocusRequester() }
    val textFieldFR = remember { FocusRequester() }
    var focusedGridIdx by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        textFieldFR.requestFocus()
        keyboardController?.show()
    }
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
                str("search_seerr"),
                color = colors.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-0.5).sp,
            )
        }
        Spacer(Modifier.height(12.dp))

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
            query.isEmpty()                                  -> str("request.search_hint")
            items.isEmpty() && state is SeerrSearchState.Loaded -> str("search.empty", mapOf("query" to query))
            state is SeerrSearchState.Loading                -> str("loading")
            else                                              -> str("search.results", mapOf("count" to items.size.toString()))
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
            // R174 — server-configured items per row; portrait viewports use the smaller portrait count.
            val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else LocalGridColumns.current
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                modifier = Modifier
                    .focusRequester(gridFR)
                    .focusRestorer()
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
                items(items.size, key = { i -> items[i].entry.tmdbId }) { i ->
                    val e = items[i]
                    RequestTile(
                        e = e,
                        onFocused = { focusedGridIdx = i; inGrid = true },
                        onSelect = { onEntrySelect(if (e.entry.mediaKind == MediaKind.SERIES) "tv" else "movie", e.entry.tmdbId) },
                    )
                }
            }
        }
    }
}
