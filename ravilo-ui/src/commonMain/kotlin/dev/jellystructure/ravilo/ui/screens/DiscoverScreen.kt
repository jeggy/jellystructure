package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalLiveAcquisition
import dev.jellystructure.ravilo.ui.LocalLiveConfig
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.focus.EdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.ChartEntry
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.Trend
import kotlinx.coroutines.launch

/** Index of the Top 10 nav item in the app bar (after home/movies/series/my_list; Search is the
 *  right-cluster icon now, R52). */
const val DISCOVER_NAV_INDEX = 4

@Composable
fun DiscoverScreen(
    store: DiscoverStore,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onEntrySelect: (listId: String, rank: Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val navItems = listOf(
        str("nav.home"), str("nav.movies"), str("nav.series"), str("nav.my_list"), "Top 10",
    )

    // R33 live config refresh + payload-bearing acquisition patching (Phase 56).
    val live = LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }
    val acq = LocalLiveAcquisition.current
    LaunchedEffect(acq) { acq?.collect { store.applyAcquisition(it) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is DiscoverState.Loading -> HomeLoadingShell()
            is DiscoverState.Error -> Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(200.dp)); Text("Top 10 unavailable", color = colors.text, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp)); Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is DiscoverState.Loaded -> DiscoverLoaded(s.data, displayName, DISCOVER_NAV_INDEX, navItems, onNavSelect, onEntrySelect, onProfile, onSearch)
        }
    }
}

@Composable
private fun DiscoverLoaded(
    data: DiscoverResponse,
    displayName: String,
    activeNav: Int,
    navItems: List<String>,
    onNavSelect: (Int) -> Unit,
    onEntrySelect: (String, Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navBarFR = remember { FocusRequester() }
    val columnFR = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }

    // R55: Back scrolls a scrolled chart to the top (refocusing the app bar so bring-into-view doesn't
    // yank it back) before falling through to RaviloApp's pop/exit.
    Box(
        modifier = Modifier.fillMaxSize().backToTopOnBack(
            atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 },
            onBackToTop = {
                runCatching { navBarFR.requestFocus() }
                scope.launch { listState.animateScrollToItem(0) }
            },
        ),
    ) {
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(columnFR),
            contentPadding = PaddingValues(top = 84.dp, bottom = 48.dp),
        ) {
            item(key = "discover-head") {
                Column(Modifier.padding(horizontal = RaviloDimens.sectionPadH, vertical = 8.dp)) {
                    Text("Top 10", color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    Text(
                        "Trending now · ${data.region}  ·  ${data.source.replaceFirstChar { it.uppercase() }} via Tudum",
                        color = colors.textSecondary, fontSize = 14.sp,
                    )
                }
            }
            items(data.rows.size, key = { ri -> data.rows[ri].spec.id }) { ri ->
                val row = data.rows[ri]
                Spacer(Modifier.height(RaviloDimens.rowGap))
                StaticContentRow(
                    title = row.spec.title,
                    items = row.entries,
                    itemKey = { e -> "${row.spec.id}:${e.entry.rank}" },
                ) { _, e ->
                    RankTile(e) { onEntrySelect(row.spec.id, e.entry.rank) }
                }
            }
        }
    }

    val initials = remember(displayName) {
        displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    }
    AppBar(
        navItems = navItems,
        activeNav = activeNav,
        onNavSelect = onNavSelect,
        navFR = navBarFR,
        onDown = { runCatching { columnFR.requestFocus() } },
        userInitials = initials,
        onProfile = onProfile,
        onSearch = onSearch,
    )
    }
}

/** A ranked Discover tile: large rank numeral + landscape art + live status pill + a trend/views sub-line. */
@Composable
private fun RankTile(e: DiscoverEntry, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${e.entry.rank}",
            color = colors.textSecondary.copy(alpha = 0.5f),
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 92.sp,
            modifier = Modifier.widthIn(min = 64.dp).padding(end = 2.dp),
        )
        Box {
            Tile(
                title = e.entry.title,
                posterUrl = tmdbImg(e.entry.backdropPath),
                variant = TileVariant.LANDSCAPE,
                subtitle = chartSubline(e.entry),
                onSelect = onSelect,
            )
            discoverStatusLabel(e.acquisition)?.let { label ->
                StatusPill(label, discoverStatusColor(e.acquisition.status, colors.accent), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(color.copy(alpha = 0.92f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ── shared helpers (also used by DiscoverDetailScreen) ──────────────────────────

internal fun tmdbImg(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" }

internal fun chartSubline(e: ChartEntry): String {
    val trend = when (e.trend) { Trend.UP -> " ▲"; Trend.DOWN -> " ▼"; Trend.NEW -> " · NEW"; Trend.SAME -> "" }
    val base = when {
        e.views != null -> "${e.views} views"
        e.weeksOnChart > 0 -> "${e.weeksOnChart} wk on chart"
        else -> ""
    }
    return (base + trend).trim()
}

internal fun discoverStatusLabel(a: AcquisitionRecord): String? = when (a.status) {
    AcquisitionStatus.AVAILABLE -> "✓ In Library"
    AcquisitionStatus.REQUESTED -> "Requested"
    AcquisitionStatus.QUEUED -> a.queuePosition?.let { "In queue · #$it" } ?: "In queue"
    AcquisitionStatus.DOWNLOADING -> {
        val amt = if (a.episodesTotal > 0) "${a.episodesDone}/${a.episodesTotal}" else "${a.progress}%"
        val flag = when { a.flags.stalled -> " · stalled"; a.flags.metadata -> " · starting"; else -> "" }
        "Fetching · $amt$flag"
    }
    AcquisitionStatus.IMPORTING -> "Importing…"
    AcquisitionStatus.FAILED -> "Failed"
    AcquisitionStatus.NOT_REQUESTED -> null
}

internal fun discoverStatusColor(status: AcquisitionStatus, accent: Color): Color = when (status) {
    AcquisitionStatus.AVAILABLE -> Color(0xFF38C172)
    AcquisitionStatus.FAILED -> Color(0xFFE3554E)
    else -> accent
}
