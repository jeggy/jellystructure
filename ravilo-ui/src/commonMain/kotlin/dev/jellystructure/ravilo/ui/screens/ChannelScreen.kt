package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.components.ChannelBar
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.components.toTileVariant
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

class ChannelStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null

    fun load(channelId: String) {
        // R40: re-entry with the same channel keeps the cached feed and refreshes silently (no flash).
        if (currentId == channelId && _state.value is HomeState.Loaded) { refresh(silent = true); return }
        currentId = channelId
        loadJob?.cancel()
        _state.value = HomeState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { HomeState.Loaded(apiClient.getChannel(channelId)) }
                .getOrElse { HomeState.Error(it.message ?: "Unknown error") }
        }
    }

    /** R33 live refresh: re-pull the channel feed in place (no Loading flash). */
    fun refresh(silent: Boolean = false) {
        val id = currentId ?: return
        if (!silent) { load(id); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getChannel(id) }.getOrNull()?.let { _state.value = HomeState.Loaded(it) }
        }
    }
}

@Composable
fun ChannelScreen(
    channel: Channel,
    store: ChannelStore,
    onBack: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
) {
    val colors = RaviloTheme.colors

    LaunchedEffect(channel.id) { store.load(channel.id) }

    // R33: silently re-pull this channel when the user's layout changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    val storeState by store.state.collectAsState()

    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val channelBarFR = remember { FocusRequester() }
    val heroFR       = remember { FocusRequester() }
    val firstTileFR  = remember { FocusRequester() }
    val barScrolled by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    } }

    // Give the bar initial focus so Back works even during the loading state;
    // LaunchedEffect(hasHero) in the Loaded branch will re-route to hero/content.
    LaunchedEffect(Unit) { runCatching { channelBarFR.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .backToTopOnBack(
                atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 },
                onBackToTop = {
                    scope.launch {
                        runCatching { listState.animateScrollToItem(0) }
                        // heroFR throws if not attached (no hero configured) — fall back to bar
                        runCatching { heroFR.requestFocus() }
                            .onFailure { runCatching { channelBarFR.requestFocus() } }
                    }
                },
            ),
    ) {
        when (val s = storeState) {
            is HomeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
            }
            is HomeState.Error -> Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is HomeState.Loaded -> {
                val hasHero = s.feed.heroes.isNotEmpty()
                val nonEmpty = s.feed.rows.filter { it.items.isNotEmpty() }
                if (!hasHero && nonEmpty.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(str("browse.empty_channel"), color = colors.textSecondary, fontSize = 16.sp)
                    }
                } else {
                    val heroHeight = if (containerH > 0)
                        with(density) { containerH.toDp() } * (s.feed.heroHeightPct.coerceIn(40, 100) / 100f)
                    else 460.dp

                    LaunchedEffect(hasHero) {
                        runCatching { if (hasHero) heroFR.requestFocus() else channelBarFR.requestFocus() }
                    }

                    @Suppress("OPT_IN_USAGE")
                    val edgeBringIntoViewSpec = rememberEdgeBringIntoViewSpec(
                        peekDp = 80.dp, topInsetDp = RaviloDimens.appBarHeight + 34.dp, // R65
                    )
                    @OptIn(ExperimentalFoundationApi::class)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().focusRequester(firstTileFR),
                            contentPadding = PaddingValues(
                                top = if (hasHero) 0.dp else 60.dp, // leave room for bar when no hero
                                bottom = 40.dp,
                            ),
                        ) {
                            // Hero carousel (R52: per-channel page hero)
                            if (hasHero) {
                                item(key = "hero") {
                                    Box(modifier = Modifier.onFocusChanged {
                                        if (it.hasFocus) scope.launch { listState.scrollToItem(0) }
                                    }) {
                                        HeroCarousel(
                                            items = s.feed.heroes,
                                            focusRequester = heroFR,
                                            heightDp = heroHeight,
                                            autoAdvanceSeconds = s.feed.autoAdvanceSeconds,
                                            onOpenDetail = { onItemSelect(it) },
                                            onUp = { runCatching { channelBarFR.requestFocus() } },
                                        )
                                    }
                                }
                            }

                            // Content rows
                            items(nonEmpty.size, key = { ri -> nonEmpty[ri].id }) { ri ->
                                val row = nonEmpty[ri]
                                Spacer(Modifier.height(RaviloDimens.rowGap))
                                StaticContentRow(
                                    title = row.title,
                                    items = row.items,
                                    itemKey = { card -> card.id },
                                ) { idx, card ->
                                    val variant = if (row.kind == RowKind.CONTINUE) TileVariant.LANDSCAPE
                                                  else s.feed.tileShape.toTileVariant()
                                    Tile(
                                        title = card.title,
                                        subtitle = card.nextUpLabel,
                                        posterUrl = if (variant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                                        variant = variant,
                                        progressPct = card.progressPct ?: 0f,
                                        watched = card.watched,
                                        focusRequester = if (!hasHero && ri == 0 && idx == 0) firstTileFR else null,
                                        onSelect = { onItemSelect(card) },
                                    )
                                }
                            }
                        }
                    }
                }

                // ChannelBar — always-composed overlay (even during Loading/Error so back works)
                ChannelBar(
                    channelName = channel.name,
                    navFR = channelBarFR,
                    onBack = onBack,
                    onDown = {
                        runCatching { if (hasHero) heroFR.requestFocus() else firstTileFR.requestFocus() }
                    },
                    scrolled = barScrolled,
                )
            }
        }

        // ChannelBar during Loading / Error states — allow back navigation
        if (storeState !is HomeState.Loaded) {
            ChannelBar(
                channelName = channel.name,
                navFR = channelBarFR,
                onBack = onBack,
                onDown = {},
                scrolled = false,
            )
        }
    }
}
