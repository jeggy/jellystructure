package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.requestFocusAwaiting
import dev.jellystructure.ravilo.ui.focus.resolveReturn
import dev.jellystructure.ravilo.ui.focus.tryRequestFocus
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import dev.jellystructure.shared.tv.offersSeeAll
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import dev.jellystructure.ravilo.ui.components.LoadErrorState
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.SeeAllTile
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

private fun HomeFeed.deduped() = copy(rows = rows.map { r -> r.copy(items = r.items.distinctBy { it.id }) })

class ChannelStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()
    val listState = LazyListState()   // R137: retained scroll position survives navigate→back
    // R139: identity of the tile the user last navigated from, so Back re-focuses that exact tile.
    var focusRowKey: String? = null
    var focusItemKey: String? = null
    // R361 (FR-R361-5) — where they were at select time, for a return to a title (or a row) that is gone.
    var focusRowIndex: Int = 0
    /** R365 (FR-R365-10) — the hero slide showing, as on Home. */
    var heroId: String? = null
    var focusItemIndex: Int = 0

    /** R139 / R361 — a tile was opened: remember it, and where it was, for the Back-return. */
    fun rememberReturn(rowKey: String, itemKey: String, rowIndex: Int, itemIndex: Int) {
        focusRowKey = rowKey
        focusItemKey = itemKey
        focusRowIndex = rowIndex.coerceAtLeast(0)
        focusItemIndex = itemIndex.coerceAtLeast(0)
    }
    private var loadJob: Job? = null
    private var currentId: String? = null

    init {
        // R147: patch channel-row tiles in place when a watched-state change is broadcast.
        scope.launch {
            WatchedBus.patches.collect { patch ->
                val s = _state.value as? HomeState.Loaded ?: return@collect
                _state.value = HomeState.Loaded(s.feed.copy(
                    rows = s.feed.rows.map { r -> r.copy(items = r.items.map { it.applyWatchedPatch(patch) }.distinctBy { it.id }) }
                ))
            }
        }
    }

    fun load(channelId: String) {
        // R40: re-entry with the same channel keeps the cached feed and refreshes silently (no flash).
        // R248 (FR-R248-4) — unless the server's `home_changed` push already refreshed it while away.
        if (currentId == channelId && _state.value is HomeState.Loaded) { if (returnGate.consumeReturn()) refresh(silent = true); return }
        currentId = channelId
        loadJob?.cancel()
        _state.value = HomeState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { HomeState.Loaded(apiClient.getChannel(channelId).deduped()) }
                .getOrElse { HomeState.Error(it.message ?: "", loadErrorKindOf(it)) }
        }
    }

    // R248 (FR-R248-4) — a channel page gets the same treatment as Home: see HomeStore.onLeave/onHomeChanged.
    private val returnGate = ReturnRefreshGate()
    fun onLeave() = returnGate.onLeave()
    fun onHomeChanged() { returnGate.onEventRefresh(); refresh(silent = true) }

    /** R33 live refresh: re-pull the channel feed in place (no Loading flash). */
    fun refresh(silent: Boolean = false) {
        val id = currentId ?: return
        if (!silent) { load(id); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getChannel(id) }.getOrNull()?.let { _state.value = HomeState.Loaded(it.deduped()) }
        }
    }
}

@Composable
fun ChannelScreen(
    channel: Channel,
    store: ChannelStore,
    displayName: String,
    onBack: () -> Unit,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onSeeAll: (dev.jellystructure.shared.tv.Row) -> Unit = {},
) {
    val colors = RaviloTheme.colors

    LaunchedEffect(channel.id) { store.load(channel.id) }

    // R33: silently re-pull this channel when the user's layout changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    val storeState by store.state.collectAsState()

    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height

    val listState = store.listState   // R137
    val scope = rememberCoroutineScope()
    val channelBarFR = remember { FocusRequester() }
    val heroFR       = remember { FocusRequester() }
    val firstTileFR  = remember { FocusRequester() }
    val barScrolled by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    } }

    // R136: full nav bar (section tabs) on the channel page, matching the other screens.
    val navItems = raviloNavItems()

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
                        // R365 (FR-R365-6) — the hero if the collection has one, else the app bar. In Compose 1.9 an
                        // unattached requester returns false rather than throwing, so the old `.onFailure` never
                        // ran and focus stayed on the half-hidden tile of the row below.
                        focusHeroElseBar(heroFR, channelBarFR)
                    }
                },
            ),
    ) {
        when (val s = storeState) {
            is HomeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
            }
            is HomeState.Error -> LoadErrorState(
                s.kind,
                onRetry = { store.refresh() },
                onBack = onBack,
            )
            is HomeState.Loaded -> {
                val hasHero = s.feed.heroes.isNotEmpty()
                val nonEmpty = s.feed.rows.filter { it.items.isNotEmpty() }
                if (!hasHero && nonEmpty.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(str("browse.empty_channel"), color = colors.textSecondary, fontSize = 16.sp)
                    }
                } else {
                    // R159 — same portrait-override selection as HomeScreen.
                    val portraitHeroPct = s.feed.portraitHeroHeightPct
                    val heroPct = if (LocalPortrait.current && portraitHeroPct != null)
                        portraitHeroPct.coerceIn(20, 100) else s.feed.heroHeightPct.coerceIn(40, 100)
                    val tvHeroHeight = if (containerH > 0)
                        with(density) { containerH.toDp() } * (heroPct / 100f)
                    else 460.dp
                    // R337 — a computer's hero is the mockup's band (`.hero`: 380 of 760), not a TV's share of the screen.
                    val heroHeight = if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout && containerH > 0) maxOf(380.dp, with(density) { containerH.toDp() } * 0.5f) else tvHeroHeight

                    // R361 (FR-R361-1/5/6) — the Back-return is resolved once against the rows about to be laid out
                    // (the same tile, else its neighbour, else the row now in its place), and the store's keys are
                    // spent at once; the resolved row's own R139 effect scrolls and focuses the tile.
                    val rowOrder = nonEmpty.map { r -> r.id to r.items.map { it.id } }
                    var pendingRestore by remember {
                        val rk = store.focusRowKey
                        val ik = store.focusItemKey
                        mutableStateOf(if (rk == null || ik == null) null else resolveReturn(rowOrder, rk, ik, store.focusRowIndex, store.focusItemIndex))
                    }
                    LaunchedEffect(hasHero) {
                        store.focusRowKey = null
                        store.focusItemKey = null
                        val target = pendingRestore
                        if (target != null) {
                            // A resolved row the column has not composed is scrolled in first, so its restore can run.
                            withFrameNanos { }
                            if (listState.layoutInfo.visibleItemsInfo.none { it.key == target.rowKey }) {
                                val lazyIndex = (if (hasHero) 1 else 0) + rowOrder.indexOfFirst { it.first == target.rowKey }
                                runCatching { listState.scrollToItem(lazyIndex) }
                            }
                            kotlinx.coroutines.delay(1_000)
                            if (pendingRestore == target) pendingRestore = null
                            return@LaunchedEffect
                        }
                        // R137: else focus the bar when scrolled (fixed overlay; doesn't disturb scroll), hero at top.
                        val wasScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                        if (hasHero && !wasScrolled) heroFR.tryRequestFocus() else channelBarFR.tryRequestFocus()
                    }

                    @Suppress("OPT_IN_USAGE")
                    val edgeBringIntoViewSpec = rememberEdgeBringIntoViewSpec(
                        peekDp = 150.dp, topInsetDp = RaviloDimens.appBarHeight + 64.dp, centerLineFraction = 0.3f, // R140
                    )
                    @OptIn(ExperimentalFoundationApi::class)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().focusRequester(firstTileFR),
                            contentPadding = PaddingValues(
                                top = if (hasHero) 0.dp else 60.dp, // leave room for bar when no hero
                                bottom = 240.dp, // R140: last row lifts to the focus band
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
                                            onOpenDetail = { store.focusRowKey = null; store.focusItemKey = null; onItemSelect(it) },  // R139
                                            onUp = { runCatching { channelBarFR.requestFocus() } },
                                            // Bug fix: native focus search from the full-width hero into the row
                                            // below picked whichever tile sat nearest the hero's horizontal
                                            // center, not index 0 (same off-by-one confirmed live on Home's
                                            // "On Now" row) — bridge explicitly to the real first tile.
                                            onDown = { runCatching { firstTileFR.requestFocus() } },
                                            initialHeroId = store.heroId,   // R365 (FR-R365-10)
                                            onActiveChanged = { store.heroId = it },
                                        )
                                    }
                                }
                            }

                            // Content rows
                            items(nonEmpty.size, key = { ri -> nonEmpty[ri].id }) { ri ->
                                val row = nonEmpty[ri]
                                // R187 (FR-RV-BROWSE1-1) — see HomeScreen's ContentRowItem for the same check.
                                val canSeeAll = row.offersSeeAll()   // R187 / R318
                                val rowVariant = if (row.kind == RowKind.CONTINUE) TileVariant.LANDSCAPE
                                                  else s.feed.tileShape.toTileVariant()
                                Spacer(Modifier.height(raviloRowGap))
                                StaticContentRow(
                                    title = row.title,
                                    items = row.items,
                                    trailingItem = if (canSeeAll) ({
                                        SeeAllTile(count = row.seedTotalCount ?: row.items.size, variant = rowVariant, onSelect = { onSeeAll(row) })
                                    }) else null,
                                    itemKey = { card -> card.id },
                                    restoreItemKey = pendingRestore?.takeIf { it.rowKey == row.id }?.itemKey,  // R139 / R361
                                    // Bug fix: consume the restore once it fires — else scrolling this row
                                    // out of the LazyColumn's composed window and back in re-triggers it
                                    // and yanks focus back here.
                                    onRestored = { pendingRestore = null },
                                ) { idx, card, fr ->
                                    val variant = rowVariant
                                    Tile(
                                        title = card.title,
                                        subtitle = card.nextUpLabel,
                                        posterUrl = if (variant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                                        variant = variant,
                                        progressPct = card.progressPct ?: 0f,
                                        watched = card.watched,
                                        upcomingLabel = card.upcomingEpisode,
                                        qualityBadge = card.qualityBadge,   // R325
                                        focusRequester = fr ?: if (ri == 0 && idx == 0) firstTileFR else null,  // R139
                                        onSelect = { store.rememberReturn(row.id, card.id, ri, idx); onItemSelect(card) },  // R139 / R361
                                    )
                                }
                            }
                        }
                    }
                }

                // R136: full nav bar (section tabs) + the channel name as page context (replaces ChannelBar).
                AppBar(
                    navItems = navItems,
                    activeNav = -1,   // a channel isn't one of the section tabs → no tab highlighted
                    onNavSelect = onNavSelect,
                    navFR = channelBarFR,
                    onDown = {
                        // Bug fix: heroFR.requestFocus() used to be called directly here — if the list had
                        // been scrolled down, the hero (lazy item 0) was disposed and requestFocus() threw,
                        // silently swallowed, stranding focus in the nav bar (D-pad Down did nothing).
                        // Same root cause + fix as HomeScreen's identical AppBar.onDown bridge. Scroll to
                        // the top first (same idiom as this screen's own backToTopOnBack above) so the hero
                        // is back in composition before focusing it.
                        if (hasHero) {
                            scope.launch {
                                runCatching { listState.scrollToItem(0) }
                                runCatching { heroFR.requestFocus() }
                            }
                        } else {
                            runCatching { firstTileFR.requestFocus() }
                        }
                    },
                    userInitials = displayName.take(2).uppercase(),
                    onProfile = onProfile,
                    onSearch = onSearch,
                    scrolled = barScrolled,
                    title = channel.name,
                )
            }
        }

        // Nav bar during Loading / Error states — keep nav + back available.
        if (storeState !is HomeState.Loaded) {
            AppBar(
                navItems = navItems,
                activeNav = -1,
                onNavSelect = onNavSelect,
                navFR = channelBarFR,
                onDown = {},
                userInitials = displayName.take(2).uppercase(),
                onProfile = onProfile,
                onSearch = onSearch,
                scrolled = false,
                title = channel.name,
            )
        }
    }
}

/**
 * R365 (FR-R365-6) — Back-to-top's focus on a collection page: the hero (awaited for a few frames, since the scroll to
 * the top has just brought it back into composition), else the app bar.
 */
internal suspend fun focusHeroElseBar(heroFR: androidx.compose.ui.focus.FocusRequester, barFR: androidx.compose.ui.focus.FocusRequester) {
    if (!heroFR.requestFocusAwaiting(maxFrames = 5)) barFR.requestFocusAwaiting()
}
