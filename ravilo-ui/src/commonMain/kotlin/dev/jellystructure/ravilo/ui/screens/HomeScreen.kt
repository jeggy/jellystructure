package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.ChannelCard
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.components.tileRequestedWidth
import dev.jellystructure.ravilo.ui.components.toTileVariant
import dev.jellystructure.ravilo.ui.seams.sizedProxyUrl
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind

@Composable
fun HomeScreen(
    store: HomeStore,
    activeNav: Int = 0,
    displayName: String = "",
    onNavSelect: (Int) -> Unit = {},
    onItemSelect: (MediaCard) -> Unit = {},
    onItemPlay: (MediaCard) -> Unit = {},
    onChannelSelect: (Channel) -> Unit = {},
    onSeeAll: (String?) -> Unit = {},
    onProfile: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val discoverAvailable by store.discoverAvailable.collectAsState()

    // R33: silently re-pull the home feed when this user's layout changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is HomeState.Loading -> HomeLoadingShell()
            is HomeState.Error   -> HomeErrorState(s.message) { store.refresh() }
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
) {
    val listState = store.listState   // R137
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

    // Native focus traversal handles movement between rows, within a row, and hero↔first row.
    // Only the app-bar overlay needs explicit bridges (it is not a spatial neighbour of the
    // content): heroFR receives down-from-app-bar, navBarFR receives up-from-hero. columnFR is
    // the entry point when there is no hero. All three are single, always-composed requesters —
    // never one-per-item across a lazy list (that was the source of the stuck/lag behaviour).
    val navBarFR = remember { FocusRequester() }
    val heroFR   = remember { FocusRequester() }
    val columnFR = remember { FocusRequester() }

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
        modifier = Modifier.fillMaxSize().focusRequester(columnFR),
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
                        // Down omitted → native focus search moves into the channel rail / first row.
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
                ) { _, ch, fr ->
                    ChannelCard(
                        name = ch.name,
                        logoUrl = ch.logoUrl,
                        brandColor = ch.brandColor,
                        logoPadding = if (ch.style == dev.jellystructure.shared.tv.ChannelStyle.LOGO) ch.paddingLogo else ch.paddingText,
                        focusRequester = fr,  // R139
                        onSelect = { store.focusRowKey = "channels"; store.focusItemKey = ch.id; onChannelSelect(ch) },  // R139
                    )
                }
            }
        }

        // Content rows
        items(feed.rows.size, key = { ri -> feed.rows[ri].id }) { ri ->
            val row: Row = feed.rows[ri]
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
                    focusRequester = fr,  // R139
                    onSelect = { store.focusRowKey = row.id; store.focusItemKey = card.id; onItemSelect(card) },  // R139
                )
            }
        }
    }
    }

    // AppBar overlay (transparent gradient over hero)
    val initials = remember(displayName) {
        displayName.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
    }
    val navItems = buildList {
        // R52: Search left the nav (now the right-cluster icon). Home·Movies·Series·Top 10·My List.
        add(str("nav.home")); add(str("nav.movies")); add(str("nav.series"))
        if (discoverAvailable) add("Top 10") // R49 — gated tab (index 3); My List shifts to 4
        add(str("nav.my_list"))
    }
    val appBarScrolled by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    } }
    AppBar(
        navItems = navItems,
        activeNav = activeNav,
        onNavSelect = onNavSelect,
        navFR = navBarFR,
        onDown = { runCatching { if (hasHero) heroFR.requestFocus() else columnFR.requestFocus() } },
        userInitials = initials,
        onProfile = onProfile,
        onSearch = onSearch,
        scrolled = appBarScrolled,
    )
    }
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
        Text(str("error.generic"), color = colors.text, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.textSecondary, fontSize = 14.sp)
    }
}
