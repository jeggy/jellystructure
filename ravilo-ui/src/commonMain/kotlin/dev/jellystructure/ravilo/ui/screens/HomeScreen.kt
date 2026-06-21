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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.ChannelCard
import dev.jellystructure.ravilo.ui.components.HeroCarousel
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind

enum class NavDestination { HOME, MOVIES, SERIES, MY_LIST, SEARCH }

@Composable
fun HomeScreen(
    store: HomeStore,
    activeNav: Int = 0,
    displayName: String = "",
    onNavSelect: (Int) -> Unit = {},
    onItemSelect: (MediaCard) -> Unit = {},
    onChannelSelect: (Channel) -> Unit = {},
    onSeeAll: (String?) -> Unit = {},
    onProfile: () -> Unit = {},
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
                displayName = displayName,
                onNavSelect = onNavSelect,
                onItemSelect = onItemSelect,
                onChannelSelect = onChannelSelect,
                onSeeAll = onSeeAll,
                onProfile = onProfile,
            )
        }
    }
}

@Composable
private fun HomeLoaded(
    feed: dev.jellystructure.shared.tv.HomeFeed,
    activeNav: Int,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onSeeAll: (String?) -> Unit,
    onProfile: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()

    // Hero height as a % of the screen, per the user's config (R27); auto-advance interval too.
    val density = LocalDensity.current
    val containerH = LocalWindowInfo.current.containerSize.height
    val heroHeight = if (containerH > 0)
        with(density) { containerH.toDp() } * (feed.heroHeightPct.coerceIn(20, 80) / 100f)
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
        runCatching { if (hasHero) heroFR.requestFocus() else navBarFR.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().focusRequester(columnFR),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        // Hero carousel
        if (hasHero) {
            item(key = "hero") {
                HeroCarousel(
                    items = feed.heroes,
                    focusRequester = heroFR,
                    heightDp = heroHeight,
                    autoAdvanceSeconds = feed.autoAdvanceSeconds,
                    onSelect = { onItemSelect(it) },
                    onUp = { navBarFR.requestFocus() },
                    // onDown omitted → native focus search moves down into the channel rail / first row.
                )
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
                ) { _, ch ->
                    ChannelCard(
                        name = ch.name,
                        logoUrl = ch.logoUrl,
                        brandColor = ch.brandColor,
                        onSelect = { onChannelSelect(ch) },
                    )
                }
            }
        }

        // Content rows
        items(feed.rows.size, key = { ri -> feed.rows[ri].id }) { ri ->
            val row: Row = feed.rows[ri]

            Spacer(Modifier.height(RaviloDimens.rowGap))
            StaticContentRow(
                title = row.title,
                items = row.items,
                itemKey = { card -> card.id },
            ) { _, card ->
                val isLandscape = row.kind == RowKind.CONTINUE
                Tile(
                    title = card.title,
                    posterUrl = if (isLandscape) card.backdropUrl ?: card.posterUrl else card.posterUrl,
                    variant = if (isLandscape) TileVariant.LANDSCAPE else TileVariant.POSTER,
                    progressPct = card.progressPct ?: 0f,
                    onSelect = { onItemSelect(card) },
                )
            }
        }
    }

    // AppBar overlay (transparent gradient over hero)
    val initials = remember(displayName) {
        displayName.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
    }
    AppBar(
        activeNav = activeNav,
        onNavSelect = onNavSelect,
        navFR = navBarFR,
        onDown = { runCatching { if (hasHero) heroFR.requestFocus() else columnFR.requestFocus() } },
        userInitials = initials,
        onProfile = onProfile,
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
        Text(str("error.generic"), color = colors.text, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.textSecondary, fontSize = 14.sp)
    }
}
