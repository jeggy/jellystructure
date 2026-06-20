package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.ChannelCard
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.focus.FocusRow
import dev.jellystructure.ravilo.ui.focus.saveFocusAt
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import kotlinx.coroutines.launch

private const val FOCUS_KEY = "home"

enum class NavDestination { HOME, MOVIES, SERIES, MY_LIST, SEARCH }

@Composable
fun HomeScreen(
    store: HomeStore,
    activeNav: Int = 0,
    onNavSelect: (Int) -> Unit = {},
    onItemSelect: (MediaCard) -> Unit = {},
    onChannelSelect: (Channel) -> Unit = {},
    onSeeAll: (String?) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is HomeState.Loading -> HomeLoadingShell()
            is HomeState.Error   -> HomeErrorState(s.message) { store.refresh() }
            is HomeState.Loaded  -> HomeLoaded(
                feed = s.feed,
                activeNav = activeNav,
                onNavSelect = onNavSelect,
                onItemSelect = onItemSelect,
                onChannelSelect = onChannelSelect,
                onSeeAll = onSeeAll,
            )
        }
    }
}

@Composable
private fun HomeLoaded(
    feed: dev.jellystructure.shared.tv.HomeFeed,
    activeNav: Int,
    onNavSelect: (Int) -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onSeeAll: (String?) -> Unit,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Focus section index: 0 = hero, 1 = channel rail, 2+ = content rows
    var focusSection by remember { mutableIntStateOf(0) }

    // AppBar entry + hero focus
    val navBarFR = remember { FocusRequester() }
    val heroFR   = remember { FocusRequester() }

    // Channel rail
    val channelRow = remember(feed.channels.size) { FocusRow(maxOf(feed.channels.size, 1)) }

    // Content row focus columns (one FocusRow per row)
    val rowFocusStates = remember(feed.rows.size) {
        feed.rows.map { row -> FocusRow(maxOf(row.items.size, 1)) }
    }

    // LazyColumn item indices:
    //   0         → hero (if present)
    //   hasHero   → channel rail (if present)
    //   headerCnt + ri → content row ri
    val hasHero     = feed.heroes.isNotEmpty()
    val hasChannels = feed.channels.isNotEmpty()
    val headerCount = (if (hasHero) 1 else 0) + (if (hasChannels) 1 else 0)

    // Scroll-then-focus helpers — scroll brings the target into the composition,
    // then the FocusRequester is guaranteed to be attached.
    fun focusRowAfterScroll(ri: Int) = scope.launch {
        listState.scrollToItem((headerCount + ri).coerceAtLeast(0))
        rowFocusStates[ri].requestFocus()
    }

    // Restore focus on entry
    LaunchedEffect(Unit) {
        if (focusSection == 0) runCatching { heroFR.requestFocus() }
    }

    LaunchedEffect(focusSection) {
        saveFocusAt(FOCUS_KEY, focusSection, 0)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        // Hero carousel
        if (hasHero) {
            item(key = "hero") {
                HeroCarousel(
                    items = feed.heroes,
                    focusRequester = heroFR,
                    onSelect = { onItemSelect(it) },
                    onUp = { navBarFR.requestFocus() },
                    onDown = {
                        if (hasChannels) {
                            focusSection = 1
                            channelRow.requestFocus()
                        } else if (rowFocusStates.isNotEmpty()) {
                            focusSection = 2
                            focusRowAfterScroll(0)
                        }
                    },
                )
            }
        }

        // Channel rail
        if (hasChannels) {
            item(key = "channels") {
                Spacer(Modifier.height(24.dp))
                StaticContentRow(
                    title = "Channels",
                    items = feed.channels,
                    focusedIndex = channelRow.focused,
                    itemKey = { ch -> ch.id },
                ) { i, ch ->
                    ChannelCard(
                        name = ch.name,
                        logoUrl = ch.logoUrl,
                        brandColor = ch.brandColor,
                        focusRequester = channelRow.requesters[i],
                        onFocused = { channelRow.focused = i; focusSection = 1 },
                        onLeft   = { channelRow.moveLeft() },
                        onRight  = { channelRow.moveRight() },
                        onUp     = { focusSection = 0; runCatching { heroFR.requestFocus() } },
                        onDown   = {
                            if (rowFocusStates.isNotEmpty()) {
                                focusSection = 2
                                focusRowAfterScroll(0)
                            }
                        },
                        onSelect = { onChannelSelect(ch) },
                    )
                }
            }
        }

        // Content rows
        items(feed.rows.size, key = { ri -> feed.rows[ri].id }) { ri ->
            val row: Row = feed.rows[ri]
            val rowFocus = rowFocusStates[ri]

            Spacer(Modifier.height(RaviloDimens.rowGap))
            StaticContentRow(
                title = row.title,
                items = row.items,
                focusedIndex = rowFocus.focused,
                itemKey = { card -> card.id },
            ) { ci, card ->
                val isLandscape = row.kind == RowKind.CONTINUE
                Tile(
                    title = card.title,
                    posterUrl = if (isLandscape) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                    focusRequester = rowFocus.requesters[ci],
                    variant = if (isLandscape) TileVariant.LANDSCAPE else TileVariant.POSTER,
                    progressPct = card.progressPct ?: 0f,
                    onFocused = { rowFocus.focused = ci; focusSection = ri + 2 },
                    onLeft  = { rowFocus.moveLeft() },
                    onRight = { rowFocus.moveRight() },
                    onUp = {
                        if (ri == 0) {
                            focusSection = if (hasChannels) 1 else 0
                            if (hasChannels) channelRow.requestFocus()
                            else runCatching { heroFR.requestFocus() }
                        } else {
                            focusSection = ri + 1
                            focusRowAfterScroll(ri - 1)
                        }
                    },
                    onDown = {
                        if (ri < rowFocusStates.lastIndex) {
                            focusSection = ri + 3
                            focusRowAfterScroll(ri + 1)
                        }
                    },
                    onSelect = { onItemSelect(card) },
                )
            }
        }
    }

    // AppBar overlay (transparent gradient over hero)
    AppBar(
        activeNav = activeNav,
        onNavSelect = onNavSelect,
        navFR = navBarFR,
        onDown = { runCatching { heroFR.requestFocus() } },
    )
}

// HomeLoadingShell is imported from Shimmer.kt

@Composable
private fun HomeErrorState(message: String, onRetry: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(200.dp))
        Text("Couldn't load home feed", color = colors.text, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.textSecondary, fontSize = 14.sp)
    }
}
