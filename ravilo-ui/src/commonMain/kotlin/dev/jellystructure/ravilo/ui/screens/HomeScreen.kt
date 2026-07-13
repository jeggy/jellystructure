package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.ChannelCard
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.components.tileRequestedWidth
import dev.jellystructure.ravilo.ui.components.toTileVariant
import dev.jellystructure.ravilo.ui.seams.sizedProxyUrl
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
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
    onSeeAll: (String?) -> Unit = {},
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
    onSeeAll: (String?) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onLiveTvChannelSelect: (LiveTvChannel) -> Unit,
    onOpenLiveTvGuide: () -> Unit,
) {
    val listState = store.listState   // R137
    val liveTvChannels by store.liveTvChannels.collectAsState()
    val onNowRowIndex = feed.liveTvHome?.onNowRowPosition?.coerceAtLeast(0) ?: 0
    val scope = rememberCoroutineScope()

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
    // up-from-hero, firstRowFR receives down-from-hero AND down-from-app-bar-when-hero-less (it is
    // always attached to whichever composable is currently the first focusable row/tile — see the
    // firstItemFR wiring below). All are single, always-composed requesters — never one-per-item
    // across a lazy list (that was the source of the stuck/lag behaviour).
    //
    // Bug fix: down-from-app-bar used to request focus on a `columnFR` attached to the LazyColumn
    // container itself — per Compose docs, requesting focus on a container with no focusable of its
    // own is unreliable (whether a descendant's focus target silently claims the delegated request
    // is unspecified). Reported live: D-pad Down from the nav bar sometimes did nothing, stranding
    // focus in the top bar. Now uses the same firstRowFR bridge the hero uses, which is guaranteed to
    // be attached to a real focusable tile whenever the feed has any content.
    val navBarFR = remember { FocusRequester() }
    val heroFR   = remember { FocusRequester() }

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
        contentPadding = PaddingValues(bottom = 240.dp),
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
                        if (it.hasFocus) scope.launch { listState.scrollToItem(0) }
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
                        onDown = { requestFocusRetrying(scope, firstRowFR) },
                    )
                }
            }
        }

        // Channel rail
        if (hasChannels) {
            item(key = "channels") {
                Spacer(Modifier.height(24.dp))
                StaticContentRow(
                    title = str("section.channels"),
                    items = feed.channels,
                    itemKey = { ch -> ch.id },
                    urlResolver = { ch -> ch.logoUrl },
                    bringRowHeaderIntoView = false,  // R108: spec topInset already shows the title
                    restoreItemKey = if (store.focusRowKey == "channels") store.focusItemKey else null,  // R139
                ) { i, ch, fr ->
                    ChannelCard(
                        name = ch.name,
                        logoUrl = ch.logoUrl,
                        brandColor = ch.brandColor,
                        logoPadding = if (ch.style == dev.jellystructure.shared.tv.ChannelStyle.LOGO) ch.paddingLogo else ch.paddingText,
                        focusRequester = fr ?: if (i == 0) firstRowFR else null,  // R139 / hero-down bridge
                        onSelect = { store.focusRowKey = "channels"; store.focusItemKey = ch.id; onChannelSelect(ch) },  // R139
                    )
                }
            }
        }

        // Content rows, with the Phase R177 "On now" row interleaved at feed.liveTvHome's configured
        // position (never a top-nav tab — it only ever lives among the Home rows).
        val clampedOnNowIndex = onNowRowIndex.coerceIn(0, feed.rows.size)
        items(clampedOnNowIndex, key = { ri -> feed.rows[ri].id }) { ri ->
            ContentRowItem(feed.rows[ri], feed, store, onItemSelect, firstItemFR = if (!hasChannels && ri == 0) firstRowFR else null)
        }
        if (liveTvChannels.isNotEmpty()) {
            item(key = "on_now") {
                Spacer(Modifier.height(RaviloDimens.rowGap))
                OnNowRow(liveTvChannels, store, onLiveTvChannelSelect, onOpenLiveTvGuide, firstItemFR = if (onNowIsFirstRow) firstRowFR else null)
            }
        }
        items(feed.rows.size - clampedOnNowIndex, key = { i -> feed.rows[clampedOnNowIndex + i].id }) { i ->
            val isVeryFirstRow = !hasChannels && clampedOnNowIndex == 0 && liveTvChannels.isEmpty() && i == 0
            ContentRowItem(feed.rows[clampedOnNowIndex + i], feed, store, onItemSelect, firstItemFR = if (isVeryFirstRow) firstRowFR else null)
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
                requestFocusRetrying(scope, firstRowFR)
            }
        },
        userInitials = initials,
        onProfile = onProfile,
        onSearch = onSearch,
        scrolled = appBarScrolled,
    )
    }
}

@Composable
private fun ContentRowItem(
    row: Row,
    feed: dev.jellystructure.shared.tv.HomeFeed,
    store: HomeStore,
    onItemSelect: (MediaCard) -> Unit,
    firstItemFR: FocusRequester? = null,
) {
    // Compute variant here so urlResolver and Tile use the same value.
    val rowVariant = if (row.kind == RowKind.CONTINUE) TileVariant.LANDSCAPE else feed.tileShape.toTileVariant()
    Spacer(Modifier.height(RaviloDimens.rowGap))
    StaticContentRow(
        title = row.title,
        items = row.items,
        itemKey = { card -> card.id },
        urlResolver = { card ->
            val u = if (rowVariant == TileVariant.LANDSCAPE) card.backdropUrl ?: card.posterUrl else card.posterUrl
            // R96 fix: prefetch the SAME ?w= URL the Tile will request (else prefetch warms the
            // full-size image and the tile cache-misses → double download).
            u?.let { sizedProxyUrl(it, tileRequestedWidth(rowVariant)) }
        },
        bringRowHeaderIntoView = false,  // R108: spec topInset already shows the title
        restoreItemKey = if (store.focusRowKey == row.id) store.focusItemKey else null,  // R139
    ) { i, card, fr ->
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
            focusRequester = fr ?: if (i == 0) firstItemFR else null,  // R139 / hero-down bridge
            onSelect = { store.focusRowKey = row.id; store.focusItemKey = card.id; onItemSelect(card) },  // R139
        )
    }
}

/** Phase R177 — the Home "On now" row: each tile is a channel's currently-airing program with its
 *  live elapsed-time progress bar (reuses [Tile]'s existing progressPct rendering). Selecting a tile
 *  tunes that channel directly (opens the live player) — there is no intermediate detail screen. */
@Composable
private fun OnNowRow(
    channels: List<LiveTvChannel>,
    store: HomeStore,
    onLiveTvChannelSelect: (LiveTvChannel) -> Unit,
    onOpenLiveTvGuide: () -> Unit,
    firstItemFR: FocusRequester? = null,
) {
    StaticContentRow(
        title = str("livetv.on_now"),
        items = channels,
        seeAllLabel = str("livetv.guide"),
        onSeeAll = onOpenLiveTvGuide,
        itemKey = { it.channelId },
        urlResolver = { it.logoUrl },
        bringRowHeaderIntoView = false,
        restoreItemKey = if (store.focusRowKey == "on_now") store.focusItemKey else null,
    ) { i, ch, fr ->
        val program = ch.currentProgram
        val nowMs = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
        val progress = if (program != null && program.endMs > program.startMs)
            ((nowMs - program.startMs).toFloat() / (program.endMs - program.startMs).toFloat()).coerceIn(0f, 1f)
        else 0f
        Tile(
            title = ch.name,
            subtitle = program?.name ?: str("livetv.no_programs"),
            episodeBadge = if (ch.number > 0) ch.number.toString() else null,
            posterUrl = ch.logoUrl,
            variant = TileVariant.LANDSCAPE,
            // Bug fix: Crop (Tile's default, right for photographic posters/backdrops) cut the top off
            // channel logos and let a white logo canvas bleed through the progress track's ~20%-alpha
            // background. Fit shows the whole mark on a neutral card instead — same treatment the TV
            // Guide's channel column and program-details overlay already use for the same logos.
            contentScale = ContentScale.Fit,
            progressPct = progress,
            focusRequester = fr ?: if (i == 0) firstItemFR else null,   // hero-down bridge
            onSelect = { store.focusRowKey = "on_now"; store.focusItemKey = ch.channelId; onLiveTvChannelSelect(ch) },
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
