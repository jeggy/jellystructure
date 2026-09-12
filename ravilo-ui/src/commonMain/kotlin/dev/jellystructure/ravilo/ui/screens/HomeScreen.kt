package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.ChannelCard
import dev.jellystructure.ravilo.ui.components.FOCUS_DETAIL_LINE_HEIGHT
import dev.jellystructure.ravilo.ui.components.FocusDetailLine
import dev.jellystructure.ravilo.ui.components.FocusDetailPanel
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.SeeAllTile
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.focusDetailRowOpenScrollDelta
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.requestFocusRetryingOrMoveNative
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.components.tileRequestedWidth
import dev.jellystructure.ravilo.ui.components.toTileVariant
import dev.jellystructure.ravilo.ui.seams.sizedProxyUrl
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.TvApiClient

@Composable
fun HomeScreen(
    store: HomeStore,
    apiClient: TvApiClient,
    activeNav: Int = 0,
    displayName: String = "",
    onNavSelect: (Int) -> Unit = {},
    onItemSelect: (MediaCard) -> Unit = {},
    onItemPlay: (MediaCard) -> Unit = {},
    onChannelSelect: (Channel) -> Unit = {},
    onSeeAll: (dev.jellystructure.shared.tv.Row) -> Unit = {},
    onProfile: () -> Unit = {},
    onSearch: () -> Unit = {},
    // Fires when the user signs out from the error state (see HomeErrorState) — a device whose
    // locally-cached token no longer matches any server-side record can never recover via Retry.
    onSignOut: () -> Unit = {},
    // Phase R177 — selecting an "On now" tile tunes straight into the live player (never a top-nav tab).
    onLiveTvChannelSelect: (LiveTvChannel) -> Unit = {},
    onOpenLiveTvGuide: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val chartsAvailable by store.discoverAvailable.collectAsState()
    val upcomingAvailable by store.upcomingAvailable.collectAsState()
    // R170 — one merged Discover tab covers both segments; the tab itself shows if either is available.
    val discoverAvailable = upcomingAvailable || chartsAvailable

    // R33: silently re-pull the home feed when this user's layout changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is HomeState.Loading -> HomeLoadingShell()
            is HomeState.Error   -> HomeErrorState(
                message = s.message,
                apiClient = apiClient,
                onRetry = { store.refresh() },
                onSignOut = onSignOut,
            )
            is HomeState.Loaded  -> HomeLoaded(
                feed = s.feed,
                store = store,   // R137 retained scroll + R139 focus-key
                activeNav = activeNav,
                displayName = displayName,
                discoverAvailable = discoverAvailable,
                onNavSelect = onNavSelect,
                onItemSelect = onItemSelect,
                onItemPlay = onItemPlay,
                onChannelSelect = onChannelSelect,
                onSeeAll = onSeeAll,
                onProfile = onProfile,
                onSearch = onSearch,
                onLiveTvChannelSelect = onLiveTvChannelSelect,
                onOpenLiveTvGuide = onOpenLiveTvGuide,
            )
        }
    }
}

@Composable
private fun HomeLoaded(
    feed: dev.jellystructure.shared.tv.HomeFeed,
    store: HomeStore,
    activeNav: Int,
    displayName: String,
    discoverAvailable: Boolean,
    onNavSelect: (Int) -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onItemPlay: (MediaCard) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onSeeAll: (dev.jellystructure.shared.tv.Row) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onLiveTvChannelSelect: (LiveTvChannel) -> Unit,
    onOpenLiveTvGuide: () -> Unit,
) {
    val listState = store.listState   // R137
    val liveTvChannels by store.liveTvChannels.collectAsState()
    // R212 — true while showing a cached/stale snapshot and the background refresh keeps failing.
    val showingStale by store.showingStaleContent.collectAsState()
    val onNowRowIndex = feed.liveTvHome?.onNowRowPosition?.coerceAtLeast(0) ?: 0
    val scope = rememberCoroutineScope()
    // R236 (FR-R236-2) — the hero/app-bar Down bridges fall back to this when firstRowFR genuinely
    // can't be reached, so the key press is never a dead end.
    val focusManager = LocalFocusManager.current

    // Hero height as a % of the screen, per the user's config (R27); auto-advance interval too.
    // R159 — portrait override, when set, replaces the landscape value; both are already on the feed
    // (no refetch), the client only selects by its own viewport (presentation selection, not derived state).
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    val portraitHeroPct = feed.portraitHeroHeightPct
    val heroPct = if (LocalPortrait.current && portraitHeroPct != null)
        portraitHeroPct.coerceIn(20, 100) else feed.heroHeightPct.coerceIn(40, 100)
    val heroHeight = if (containerH > 0)
        with(density) { containerH.toDp() } * (heroPct / 100f)
    else 460.dp

    val hasHero     = feed.heroes.isNotEmpty()
    val hasChannels = feed.channels.isNotEmpty()
    // Bug fix: DOWN from the hero landed on the 2nd tile of the row below (e.g. "On Now"'s KVF1
    // instead of DR1) — confirmed live on soveværelse TV. The old comment below assumed native
    // focus search handles hero↔first-row correctly, but the hero's focus box spans the full
    // width while the row's tiles are left-anchored, so Compose's nearest-candidate search picks
    // whichever tile sits closest to the hero's horizontal center — not index 0. Explicit bridge,
    // same idiom as heroFR/navBarFR below.
    val onNowIsFirstRow = !hasChannels && onNowRowIndex.coerceIn(0, feed.rows.size) == 0 && liveTvChannels.isNotEmpty()
    val firstRowFR = remember { FocusRequester() }

    // Native focus traversal handles movement between rows and within a row. Only the app-bar
    // overlay and the hero→first-row jump need explicit bridges (neither is a reliable spatial
    // neighbour of what's below/above it): heroFR receives down-from-app-bar, navBarFR receives
    // up-from-hero, firstRowFR receives down-from-hero AND down-from-app-bar-when-hero-less — it is
    // passed as the `rowFocusRequester` of whichever row is currently first (channels / a content row
    // / On Now), attached to that ROW, never to one of its items (see StaticContentRow's own doc for
    // why: R236 found firstRowFR wired to lazy item index 0 here, which died the instant that item
    // scrolled out of its LazyRow's composed window — R139's own Back-return restore does exactly
    // that — permanently breaking Down from the hero after visiting any tile and returning).
    //
    // Bug fix: down-from-app-bar used to request focus on a `columnFR` attached to the LazyColumn
    // container itself — per Compose docs, requesting focus on a container with no focusable of its
    // own is unreliable (whether a descendant's focus target silently claims the delegated request
    // is unspecified). Reported live: D-pad Down from the nav bar sometimes did nothing, stranding
    // focus in the top bar. Now uses the same firstRowFR bridge the hero uses, which is guaranteed to
    // be attached to a real focusable tile whenever the feed has any content.
    val navBarFR = remember { FocusRequester() }
    val heroFR   = remember { FocusRequester() }

    // Phase R240 — this Home content-row phase is the only thing FocusDetailController tracks; every
    // OTHER focusable Home surface (hero, channel rail, nav bar) must say so by clearing it the moment
    // IT takes focus, or L/J would keep stating a title that's no longer focused (FR-R240-1's reverse).
    val fdUi by store.focusDetail.current.collectAsState()
    val lineActive = feed.focusDetail == "line"
    // FR-R240-13 — a config change re-runs the reveal rule for whichever tile is focused right now,
    // through the same path a real focus move takes (not merely "the next natural refetch").
    LaunchedEffect(feed.focusDetail, feed.focusDetailDelayMs) {
        store.focusDetail.reapply(feed.focusDetail, feed.focusDetailDelayMs)
    }

    // Land focus somewhere sensible on entry. With a hero, focus it; otherwise focus the app bar
    // (always composed + focusable) so a hero-less feed never opens with nothing focused — Down
    // then enters the content. Requesting focus on the LazyColumn container itself is unreliable.
    LaunchedEffect(Unit) {
        // R139: on a Back-return from a tile/channel (a key was saved on select), the originating row's own
        // effect scrolls to + focuses that exact tile — skip the default entry focus so we don't fight it.
        // R137: otherwise the retained scroll is preserved; focus the hero on a fresh/at-top entry, else the
        // app bar (a fixed overlay — focusing it doesn't disturb the scroll).
        if (store.focusItemKey != null) return@LaunchedEffect
        val wasScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        runCatching {
            if (!wasScrolled && hasHero) heroFR.requestFocus() else navBarFR.requestFocus()
        }
    }

    // R55: Back scrolls a scrolled feed to the top (refocusing the hero / app bar so bring-into-view
    // doesn't yank it back) before falling through to RaviloApp's pop/exit — harder to close by accident.
    Box(
        modifier = Modifier.fillMaxSize().backToTopOnBack(
            atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 },
            onBackToTop = {
                // Scroll to the top first, THEN focus the hero — when scrolled down the hero (item 0) is
                // disposed, so requesting its focus only works once it's back in view. The app bar
                // (an always-composed overlay) is the fallback when there is no hero.
                scope.launch {
                    runCatching { listState.animateScrollToItem(0) }
                    runCatching { (if (hasHero) heroFR else navBarFR).requestFocus() }
                }
            },
        ),
    ) {
    @Suppress("OPT_IN_USAGE")
    // R140: comfortable vertical framing for content rows.
    //  - topInsetDp clears the 60dp AppBar + the full ~64dp row-title band (was 34, too small → the title
    //    slid under the bar when navigating UP). Now the focused row's title is always clearly visible.
    //  - centerLineDp (DOWN) is a focus band: the focused row's top snaps to ~1/3 down, so the row you're
    //    looking at sits toward the middle (not the bottom), with the next row peeking below.
    val edgeBringIntoViewSpec = rememberEdgeBringIntoViewSpec(
        peekDp = 150.dp, topInsetDp = RaviloDimens.appBarHeight + 64.dp, centerLineFraction = 0.3f,
    )
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
    LazyColumn(
        state = listState,
        // R140: generous bottom padding so the LAST row can still scroll up to the same comfortable height
        // as the others (never stranded at the very bottom of the screen).
        // Phase R240 (FR-R240-2) — L is not an overlay: this padding grows by the strip's own height
        // for as long as the household's resolved direction is "line", so the last row can always
        // clear it. Kept for as long as L is CONFIGURED, not merely while something is focused, or the
        // page would shift the moment the very first tile ever takes focus.
        contentPadding = PaddingValues(bottom = 240.dp + (if (lineActive) FOCUS_DETAIL_LINE_HEIGHT else 0.dp)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Hero carousel
        if (hasHero) {
            item(key = "hero") {
                // R45: whenever the hero holds focus — on launch and when focus returns up from a
                // content row — snap the list to the top so the full hero re-frames (the buttons sit
                // low in the hero, so a bare bring-into-view would otherwise strand it mid-scroll).
                Box(
                    modifier = Modifier.onFocusChanged {
                        // Phase R240 — the hero is outside Home content rows (spec non-goal list); it
                        // must say nothing on the status line/row-open panel.
                        if (it.hasFocus) { scope.launch { listState.scrollToItem(0) }; store.focusDetail.clear() }
                    }
                ) {
                    HeroCarousel(
                        items = feed.heroes,
                        focusRequester = heroFR,
                        heightDp = heroHeight,
                        autoAdvanceSeconds = feed.autoAdvanceSeconds,
                        // R53: button-less — the whole hero opens detail (movie + series alike); the detail
                        // screen owns Play/resume. Left/Right pages the carousel inside HeroCarousel.
                        // R139: opened from the hero (not a tile) → clear the saved key so Back returns to the hero.
                        onOpenDetail = { store.focusRowKey = null; store.focusItemKey = null; onItemSelect(it) },
                        onUp = { navBarFR.requestFocus() },
                        // R101: freeze the Ken Burns drift while the list is actively scrolling so the
                        // full-width hero stops its per-frame scaled redraw during the gesture.
                        driftEnabled = { !listState.isScrollInProgress },
                        onDown = { requestFocusRetryingOrMoveNative(scope, firstRowFR, focusManager, FocusDirection.Down) },
                    )
                }
            }
        }

        // Channel rail
        if (hasChannels) {
            item(key = "channels") {
                Spacer(Modifier.height(24.dp))
                // Phase R240 — the channel rail is outside Home content rows (spec non-goal list).
                Box(modifier = Modifier.onFocusChanged { if (it.hasFocus) store.focusDetail.clear() }) {
                StaticContentRow(
                    title = str("section.channels"),
                    items = feed.channels,
                    itemKey = { ch -> ch.id },
                    urlResolver = { ch -> ch.logoUrl },
                    bringRowHeaderIntoView = false,  // R108: spec topInset already shows the title
                    restoreItemKey = if (store.focusRowKey == "channels") store.focusItemKey else null,  // R139
                    // Bug fix: consume the restore once it fires — else scrolling this row out of the
                    // LazyColumn's composed window and back in re-triggers it and yanks focus back here.
                    onRestored = { store.focusRowKey = null; store.focusItemKey = null },
                    // R236 — channels is always the first row whenever it exists; the bridge targets the
                    // row itself now, never a specific tile (see StaticContentRow's rowFocusRequester doc).
                    rowFocusRequester = firstRowFR,
                ) { _, ch, fr ->
                    ChannelCard(
                        name = ch.name,
                        logoUrl = ch.logoUrl,
                        brandColor = ch.brandColor,
                        logoPadding = if (ch.style == dev.jellystructure.shared.tv.ChannelStyle.LOGO) ch.paddingLogo else ch.paddingText,
                        focusRequester = fr,  // R139 restore target only
                        onSelect = { store.focusRowKey = "channels"; store.focusItemKey = ch.id; onChannelSelect(ch) },  // R139
                    )
                }
                }
            }
        }

        // Content rows, with the Phase R177 "On now" row interleaved at feed.liveTvHome's configured
        // position (never a top-nav tab — it only ever lives among the Home rows).
        val clampedOnNowIndex = onNowRowIndex.coerceIn(0, feed.rows.size)
        items(clampedOnNowIndex, key = { ri -> feed.rows[ri].id }) { ri ->
            ContentRowItem(feed.rows[ri], feed, store, listState, onItemSelect, onSeeAll, rowFocusRequester = if (!hasChannels && ri == 0) firstRowFR else null)
        }
        if (liveTvChannels.isNotEmpty()) {
            item(key = "on_now") {
                Spacer(Modifier.height(RaviloDimens.rowGap))
                // Phase R240 — On Now isn't a Home content row either (spec non-goal list).
                Box(modifier = Modifier.onFocusChanged { if (it.hasFocus) store.focusDetail.clear() }) {
                    OnNowRow(liveTvChannels, store, onLiveTvChannelSelect, onOpenLiveTvGuide, rowFocusRequester = if (onNowIsFirstRow) firstRowFR else null)
                }
            }
        }
        items(feed.rows.size - clampedOnNowIndex, key = { i -> feed.rows[clampedOnNowIndex + i].id }) { i ->
            val isVeryFirstRow = !hasChannels && clampedOnNowIndex == 0 && liveTvChannels.isEmpty() && i == 0
            ContentRowItem(feed.rows[clampedOnNowIndex + i], feed, store, listState, onItemSelect, onSeeAll, rowFocusRequester = if (isVeryFirstRow) firstRowFR else null)
        }
    }
    }

    // AppBar overlay (transparent gradient over hero)
    val initials = remember(displayName) {
        displayName.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
    }
    val navItems = raviloNavItems(discoverAvailable)
    val appBarScrolled by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    } }
    AppBar(
        navItems = navItems,
        activeNav = activeNav,
        onNavSelect = onNavSelect,
        navFR = navBarFR,
        onDown = {
            if (hasHero) {
                // Bug fix: heroFR.requestFocus() used to be called directly here — if the list had been
                // scrolled down, the hero (lazy item 0) was disposed and requestFocus() threw, silently
                // swallowed, stranding focus in the nav bar. Scrolling to the top first (same idiom as
                // backToTopOnBack above) forces the hero back into composition before focusing it.
                scope.launch {
                    runCatching { listState.scrollToItem(0) }
                    runCatching { heroFR.requestFocus() }
                }
            } else {
                // R236 (FR-R236-3) — same guarantee as the hero's own Down bridge above.
                requestFocusRetryingOrMoveNative(scope, firstRowFR, focusManager, FocusDirection.Down)
            }
        },
        userInitials = initials,
        onProfile = onProfile,
        onSearch = onSearch,
        scrolled = appBarScrolled,
        // Phase R240 — the nav bar is outside Home content rows too.
        modifier = Modifier.onFocusChanged { if (it.hasFocus) store.focusDetail.clear() },
    )
    // R212 — small, non-blocking: a cached snapshot is on screen and the background refresh keeps
    // failing. Never takes over the screen (no Loading/Error state change) and disappears the
    // instant any refresh succeeds (HomeStore clears the flag on every successful getHome()).
    if (showingStale) {
        StaleContentBanner(modifier = Modifier.align(Alignment.TopCenter).padding(top = RaviloDimens.appBarHeight + 12.dp))
    }
    // Phase R240 (FR-R240-2) — L's foot strip. Reserved space comes from the LazyColumn's own bottom
    // padding above; this is an overlay ONLY in the sense that it paints on top of that reserved,
    // never-scrolled-under band — it never covers a tile.
    FocusDetailLine(ui = fdUi, active = lineActive, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StaleContentBanner(modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Row(
        modifier = modifier
            .background(colors.surface.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(colors.textSecondary, CircleShape))
        Text(str("home.showing_saved"), color = colors.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun ContentRowItem(
    row: Row,
    feed: dev.jellystructure.shared.tv.HomeFeed,
    store: HomeStore,
    listState: LazyListState,
    onItemSelect: (MediaCard) -> Unit,
    onSeeAll: (Row) -> Unit = {},
    rowFocusRequester: FocusRequester? = null,
) {
    // Compute variant here so urlResolver and Tile use the same value.
    val rowVariant = if (row.kind == RowKind.CONTINUE) TileVariant.LANDSCAPE else feed.tileShape.toTileVariant()
    Spacer(Modifier.height(RaviloDimens.rowGap))
    // R187 (FR-RV-BROWSE1-1) — a "→ See all" TILE (not a header link — see SeeAllTile's doc comment)
    // only when there's more than a screen's worth AND the row has something to resolve into: CONTINUE
    // has its own dedicated seed-less path (still navigable, HomeFeedService.continueWatchingAll),
    // everything else needs Row.seedQuery.
    val canSeeAll = row.items.size > 8 && (row.kind == RowKind.CONTINUE || row.seedQuery != null || row.seedMediaKind != null)

    // Phase R240 — this row's own slice of the shared FocusDetailController state.
    val fd by store.focusDetail.current.collectAsState()
    val rowHasOpen = fd?.rowId == row.id && fd?.mode == "rowOpen"
    // FR-R240-7 — the panel item stays anchored at [panelKey] through its whole exit tween (not
    // immediately reassigned to wherever focus lands next), so leaving the row entirely gets a real
    // narrow-out instead of the node just vanishing. Known simplification vs the full spec: hopping
    // laterally from one already-open tile straight to another within the SAME row re-anchors
    // immediately with no exit tween for the old panel — see FR-R240-10's doc comment on
    // FocusDetailPanel.kt for why a true two-slot crossfade wasn't built out here.
    var panelKey by remember(row.id) { mutableStateOf<String?>(null) }
    var panelVisible by remember(row.id) { mutableStateOf(false) }
    // Kept alive through the exit tween below — [fd] itself may already be null (or about a
    // different row) by the time the panel is merely narrowing out, and the panel must keep showing
    // the title it was open on, not vanish, until FR-R240-7's close animation actually finishes.
    var lastUi by remember(row.id) { mutableStateOf<dev.jellystructure.ravilo.ui.focus.FocusDetailUi?>(null) }
    if (rowHasOpen) lastUi = fd
    LaunchedEffect(rowHasOpen, fd?.itemKey) {
        if (rowHasOpen) {
            panelKey = fd?.itemKey
            panelVisible = true
        } else if (panelKey != null) {
            panelVisible = false
            kotlinx.coroutines.delay(RaviloMotion.ROW_OPEN_TWEEN_MS.toLong() + 60L)
            if (!rowHasOpen) panelKey = null
        }
    }

    // FR-R240-9 — the row's own top/foot in window space, refreshed on every layout pass (incl. every
    // frame of the open/collapse width-and-height tween). The effect below only ever READS these once
    // the tween has settled, so a mid-animation value never leaks into the scroll target.
    var rowTopPx by remember(row.id) { mutableStateOf(0f) }
    var rowFootPx by remember(row.id) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val scope = rememberCoroutineScope()
    LaunchedEffect(rowHasOpen) {
        if (rowHasOpen) {
            // Sequenced after the growth (R232's hazard): wait out the same tween Tile/FocusDetailPanel
            // animate on, so rowFootPx reflects the GROWN row, not the row mid-tween.
            kotlinx.coroutines.delay(RaviloMotion.ROW_OPEN_TWEEN_MS.toLong())
            val peekPx = with(density) { 150.dp.toPx() }
            val footMarginPx = with(density) { 40.dp.toPx() }
            val delta = focusDetailRowOpenScrollDelta(rowTopPx, rowFootPx, screenHeightPx, peekPx, footMarginPx)
            // FR-R240-9's own "never scrolls back up": a row that already fits (delta <= 0) is left alone.
            if (delta > 0f) scope.launch { runCatching { listState.animateScrollBy(delta) } }
        }
    }

    StaticContentRow(
        title = row.title,
        items = row.items,
        modifier = Modifier.onGloballyPositioned { coords ->
            rowTopPx = coords.positionInWindow().y
            rowFootPx = rowTopPx + coords.size.height
        },
        trailingItem = if (canSeeAll) ({
            // Phase R240 — a "→ See all" tile carries no MediaCard/facts; focusing it must say nothing.
            Box(modifier = Modifier.onFocusChanged { if (it.hasFocus) store.focusDetail.clear() }) {
                SeeAllTile(count = row.seedTotalCount ?: row.items.size, variant = rowVariant, onSelect = { onSeeAll(row) })
            }
        }) else null,
        itemKey = { card -> card.id },
        urlResolver = { card ->
            val u = if (rowVariant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl
            // R96 fix: prefetch the SAME ?w= URL the Tile will request (else prefetch warms the
            // full-size image and the tile cache-misses → double download).
            u?.let { sizedProxyUrl(it, tileRequestedWidth(rowVariant)) }
        },
        bringRowHeaderIntoView = false,  // R108: spec topInset already shows the title
        restoreItemKey = if (store.focusRowKey == row.id) store.focusItemKey else null,  // R139
        // Bug fix: consume the restore once it fires — else scrolling this row out of the LazyColumn's
        // composed window and back in re-triggers it and yanks focus back here.
        onRestored = { store.focusRowKey = null; store.focusItemKey = null },
        rowFocusRequester = rowFocusRequester,  // R236
        // Phase R240 (FR-R240-3) — J's panel, spliced in right after [panelKey]'s tile. Deliberately
        // NOT given a FocusRequester anywhere inside it (FR-R240-5) — see FocusDetailPanel's own doc.
        openAfterKey = panelKey,
        openPanel = if (panelKey != null) ({
            lastUi?.let { FocusDetailPanel(ui = it, visible = panelVisible) }
        }) else null,
    ) { _, card, fr ->
        // R113: in Continue Watching, show the season/episode as a small on-image badge for TV
        // shows and leave just the series title below (was "S1E3 · Episode" as the subtitle).
        val isContinue = row.kind == RowKind.CONTINUE
        val episodeBadge = if (isContinue && card.seasonNumber != null && card.episodeNumber != null)
            "S${card.seasonNumber}:E${card.episodeNumber}" else null
        Tile(
            title = card.title,
            subtitle = if (isContinue) null else card.nextUpLabel,
            episodeBadge = episodeBadge,
            posterUrl = if (rowVariant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl,
            variant = rowVariant,
            progressPct = card.progressPct ?: 0f,
            watched = card.watched,
            upcomingLabel = card.upcomingEpisode,
            focusRequester = fr,  // R139 restore target only — the hero-down bridge is row-level now (R236)
            // Phase R240 — the tile that's actually open right now grows in place (FR-R240-7); every
            // other tile in every other row is untouched.
            open = rowHasOpen && fd?.itemKey == card.id,
            onFocused = { store.focusDetail.onFocus(row.id, card.id, card, feed.focusDetail, feed.focusDetailDelayMs) },
            onSelect = { store.focusRowKey = row.id; store.focusItemKey = card.id; onItemSelect(card) },  // R139
        )
    }
}

/** Phase R177 — the Home "On now" row: each tile is a channel's currently-airing program with its
 *  live elapsed-time progress bar (reuses [Tile]'s existing progressPct rendering). Selecting a tile
 *  tunes that channel directly (opens the live player) — there is no intermediate detail screen.
 *  User request: real channel logos are square, so tiles are SQUARE (not LANDSCAPE) here — with Fit
 *  content scale that now matches the tile's own aspect, a square logo fills it edge to edge with no
 *  letterboxing at all. The "Open TV Guide" entry point used to be a text link in the row header
 *  (dead space-wise and easy to miss); it's now its own leading tile in the track, matching the design
 *  mockup's `lt-guidetile` treatment (`design/ravilo/ravilo-livetv.js` `onNowRow()`). */
@Composable
private fun OnNowRow(
    channels: List<LiveTvChannel>,
    store: HomeStore,
    onLiveTvChannelSelect: (LiveTvChannel) -> Unit,
    onOpenLiveTvGuide: () -> Unit,
    rowFocusRequester: FocusRequester? = null,
) {
    StaticContentRow(
        title = str("livetv.on_now"),
        items = channels,
        itemKey = { it.channelId },
        urlResolver = { it.logoUrl },
        bringRowHeaderIntoView = false,
        restoreItemKey = if (store.focusRowKey == "on_now") store.focusItemKey else null,
        // Bug fix: consume the restore once it fires — else scrolling this row out of the LazyColumn's
        // composed window and back in re-triggers it and yanks focus back here.
        onRestored = { store.focusRowKey = null; store.focusItemKey = null },
        rowFocusRequester = rowFocusRequester,  // R236 — lands on the leading guide tile by default (first in composition order) via focusRestorer
        // The guide tile is the row's first item, reached via the row-level bridge above.
        leadingItem = { LiveTvGuideTile(onClick = onOpenLiveTvGuide) },
        // Design inspiration (design/ravilo/ravilo-livetv.js onNowRow()'s lt-onnow-head): a live-dot
        // beside the title and a "N channels" info line where the old "TV Guide" link used to sit —
        // now that the guide has its own tile, that slot is free for this instead. User request:
        // Ravilo doesn't mention Jellyfin by name anywhere in its own UI (a branding/white-label
        // choice — Jellyfin is jellystructure's own backend detail, not something the viewer-facing
        // app surfaces), so this drops the design mockup's own "· From Jellyfin" suffix.
        titleAccessory = { LiveDot() },
        trailingInfo = str("livetv.channels_from_jellyfin", mapOf("count" to channels.size.toString())),
    ) { _, ch, fr ->
        val program = ch.currentProgram
        val nowMs = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
        val progress = if (program != null && program.endMs > program.startMs)
            ((nowMs - program.startMs).toFloat() / (program.endMs - program.startMs).toFloat()).coerceIn(0f, 1f)
        else 0f
        Tile(
            title = ch.name,
            subtitle = program?.name ?: str("livetv.no_programs"),
            // User request ("show timestamps on the channels home screen") — the current program's
            // time range, matching the design's `.ptime` line (design/ravilo/ravilo-livetv.css).
            caption = program?.let { "${formatGuideTime(it.startMs)}–${formatGuideTime(it.endMs)}" },
            episodeBadge = if (ch.number > 0) ch.number.toString() else null,
            posterUrl = ch.logoUrl,
            variant = TileVariant.SQUARE,
            // Bug fix: Crop (Tile's default, right for photographic posters/backdrops) cut the top off
            // channel logos and let a white logo canvas bleed through the progress track's ~20%-alpha
            // background. Fit shows the whole mark on a neutral card instead — same treatment the TV
            // Guide's channel column and program-details overlay already use for the same logos.
            contentScale = ContentScale.Fit,
            progressPct = progress,
            focusRequester = fr,
            onSelect = { store.focusRowKey = "on_now"; store.focusItemKey = ch.channelId; onLiveTvChannelSelect(ch) },
        )
    }
}

/** A small solid red dot beside the "On Now" title — the same red as the in-player LIVE badge
 *  (LiveTvPlayerScreen.kt), reused here per the design mockup's `.live-dot` (ravilo-livetv.css). */
@Composable
private fun LiveDot() {
    Box(modifier = Modifier.size(8.dp).background(Color(0xFFE0263B), CircleShape))
}

/** The row's leading "Open TV Guide" entry point (see [OnNowRow]'s doc comment) — an icon + kicker +
 *  title + subtitle card, sized to match the SQUARE channel tiles beside it. */
@Composable
private fun LiveTvGuideTile(onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) RaviloMotion.TILE_FOCUS_SCALE else 1f, label = "guideTileScale")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }
    Column(
        modifier = Modifier
            .width(180.dp).height(180.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(colors.surfaceVariant, tileShape)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, tileShape) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(colors.accentGradient, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("▦", color = Color.White, fontSize = 18.sp) }
        Spacer(Modifier.height(10.dp))
        Text(
            str("livetv.guide_kicker"), color = colors.textDim, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        )
        Text(
            str("livetv.open_guide"), color = colors.text, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            str("livetv.guide_subtitle"), color = colors.textSecondary, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

// HomeLoadingShell is imported from Shimmer.kt

/**
 * Bug fix: this used to render only text — [onRetry] was accepted but never wired to anything, and
 * there was no way back to the login screen. A device whose locally-cached token no longer matches
 * any server-side record (re-pair, DB reset, "old local storage") could never recover from here
 * short of clearing app data outside the app. Retry re-runs the load; Sign out clears the local
 * session and best-effort revokes it server-side (see [unpairAllSessions]) so the device lands back
 * on the login screen instead of being permanently stuck.
 */
@Composable
private fun HomeErrorState(
    message: String,
    apiClient: TvApiClient,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val retryFR = remember { FocusRequester() }
    val signOutFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFR.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(200.dp))
        Text(str("error.generic"), color = colors.text, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        var retryFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .then(if (retryFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                .dpadFocusable(
                    focusRequester = retryFR,
                    onFocused = { retryFocused = true },
                    onBlurred = { retryFocused = false },
                    onDown = { runCatching { signOutFR.requestFocus() } },
                    onSelect = onRetry,
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) { Text(str("action.retry"), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        var signOutFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .then(if (signOutFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                .dpadFocusable(
                    focusRequester = signOutFR,
                    onFocused = { signOutFocused = true },
                    onBlurred = { signOutFocused = false },
                    onUp = { runCatching { retryFR.requestFocus() } },
                    onSelect = { scope.launch { unpairAllSessions(apiClient); onSignOut() } },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) { Text(str("profile.sign_out"), color = colors.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}
