package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
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
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RequestEntry
import kotlinx.coroutines.launch

/** R170 — the Discover tab is always index 3: with the nav collapsed to one optional slot (this
 *  screen and [UpcomingScreen] are now the two segments of the same tab, not separate tabs), there's
 *  no more "shifts to 4 when Upcoming is also present" case to account for. */
const val DISCOVER_NAV_INDEX = 3

@Composable
fun DiscoverScreen(
    store: DiscoverStore,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onEntrySelect: (mediaType: String, tmdbId: Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onSearchSeerr: () -> Unit,
    // R243 (FR-R243-1) — the Discover segment bar replaces R170's single "↔ Coming Soon" pill; see
    // UpcomingScreen for the same three parameters.
    segments: List<DiscoverSegment> = listOf(DiscoverSegment.REQUEST),
    onSegment: (DiscoverSegment) -> Unit = {},
    focusSegmentOnEntry: Boolean = false,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val myRequests by store.myRequests.collectAsState()
    val navItems = raviloNavItems()

    // R33 live config refresh + payload-bearing acquisition patching (Phase 56).
    val live = LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }
    val acq = LocalLiveAcquisition.current
    LaunchedEffect(acq) { acq?.collect { store.applyAcquisition(it) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is DiscoverState.Loading -> HomeLoadingShell()
            is DiscoverState.Error -> Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(200.dp)); Text("Request unavailable", color = colors.text, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp)); Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is DiscoverState.Loaded -> DiscoverLoaded(store, s.data, myRequests, displayName, DISCOVER_NAV_INDEX, navItems, onNavSelect, onEntrySelect, onProfile, onSearch, onSearchSeerr, segments, onSegment, focusSegmentOnEntry)
        }
    }
}

@Composable
private fun DiscoverLoaded(
    store: DiscoverStore,
    data: DiscoverResponse,
    myRequests: List<DiscoverEntry>,
    displayName: String,
    activeNav: Int,
    navItems: List<String>,
    onNavSelect: (Int) -> Unit,
    onEntrySelect: (String, Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onSearchSeerr: () -> Unit,
    segments: List<DiscoverSegment>,
    onSegment: (DiscoverSegment) -> Unit,
    focusSegmentOnEntry: Boolean,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navBarFR = remember { FocusRequester() }
    val columnFR = remember { FocusRequester() }

    // Restore scroll to last-selected row on Back-return from detail. Bug fix: only default focus to
    // the AppBar on a genuinely fresh entry — when returning from a detail/search screen we just
    // opened a tile from, restoreItemKey (passed to StaticContentRow below) re-focuses that exact tile
    // instead, matching BrowseScreen's R139 pattern.
    LaunchedEffect(Unit) {
        val idx = store.lastSelectedRowIndex
        if (idx >= 0) {
            listState.scrollToItem((idx + 1).coerceAtLeast(0)) // +1 for header item
        }
        if (store.lastSelectedItemKey == null && !focusSegmentOnEntry) runCatching { navBarFR.requestFocus() }
    }

    // Two-stage Back: first Back focuses AppBar (and scrolls to top); second Back (from AppBar) pops.
    var navBarFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().backToTopOnBack(
            atTop = { navBarFocused },
            onBackToTop = {
                runCatching { navBarFR.requestFocus() }
                scope.launch { listState.scrollToItem(0) }
            },
        ),
    ) {
    // R140: match Home — bigger peek (next-row title peeks below), top inset clears the bar on UP.
    val edgeBringIntoViewSpec = rememberEdgeBringIntoViewSpec(peekDp = 150.dp, topInsetDp = RaviloDimens.appBarHeight + 64.dp, centerLineFraction = 0.3f)
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(columnFR),
            contentPadding = PaddingValues(top = RaviloDimens.appBarHeight + 24.dp, bottom = 240.dp), // R140 bottom lift
        ) {
            item(key = "discover-head") {
                Column(Modifier.padding(horizontal = raviloHPad, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(str("seg.request"), color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                            Text(str("request.sub"), color = colors.textSecondary, fontSize = 13.sp)
                        }
                        SeerrSearchPill(onSelect = onSearchSeerr)
                    }
                    Spacer(Modifier.height(12.dp))
                    DiscoverSegmentBar(segments = segments, active = DiscoverSegment.REQUEST, onSelect = onSegment, focusActiveOnEntry = focusSegmentOnEntry)
                }
            }
            // Phase 139 §D.2 — "In progress": the viewer's own not-yet-available requests, so a
            // strict-waiting pick from days ago is easy to find again (and switch, from its detail page).
            if (myRequests.isNotEmpty()) {
                item(key = "discover-in-progress") {
                    Spacer(Modifier.height(RaviloDimens.rowGap))
                    StaticContentRow(
                        title = str("request.in_progress"),
                        items = myRequests,
                        itemKey = { e -> "in-progress:${e.entry.tmdbId}" },
                    ) { _, e, fr ->
                        RequestTile(e, focusRequester = fr) {
                            onEntrySelect(if (e.entry.mediaKind == MediaKind.SERIES) "tv" else "movie", e.entry.tmdbId)
                        }
                    }
                }
            }
            if (data.rows.isEmpty()) {
                item(key = "discover-empty") {
                    Column(Modifier.padding(horizontal = raviloHPad, vertical = 32.dp)) {
                        Text(
                            "Nothing to request yet — add feeds in the Ravilo config editor.",
                            color = colors.textSecondary, fontSize = 14.sp,
                        )
                    }
                }
            }
            items(data.rows.size, key = { ri -> data.rows[ri].feedId }) { ri ->
                val row = data.rows[ri]
                Spacer(Modifier.height(RaviloDimens.rowGap))
                StaticContentRow(
                    title = row.feedName,
                    items = row.entries,
                    itemKey = { e -> "${row.feedId}:${e.entry.tmdbId}" },
                    // Bug fix: only the row we last opened a tile from carries a restore target.
                    restoreItemKey = if (ri == store.lastSelectedRowIndex) store.lastSelectedItemKey else null,
                ) { _, e, fr ->
                    RequestTile(e, focusRequester = fr) {
                        store.lastSelectedRowIndex = ri
                        store.lastSelectedItemKey = "${row.feedId}:${e.entry.tmdbId}"
                        onEntrySelect(if (e.entry.mediaKind == MediaKind.SERIES) "tv" else "movie", e.entry.tmdbId)
                    }
                }
            }
        }
    }

    val initials = remember(displayName) {
        displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    }
    val appBarScrolled by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    } }
    Box(Modifier.onFocusChanged { navBarFocused = it.hasFocus }) {
        AppBar(
            navItems = navItems,
            activeNav = activeNav,
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            onDown = { runCatching { columnFR.requestFocus() } },
            userInitials = initials,
            onProfile = onProfile,
            onSearch = onSearch,
            scrolled = appBarScrolled,
        )
    }
    }
}

@Composable
private fun SeerrSearchPill(onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Text(
        text = str("search_seerr"),
        color = if (focused) colors.background else colors.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = Sora,
        modifier = Modifier
            .background(if (focused) colors.text else colors.surfaceVariant, RoundedCornerShape(20.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(20.dp)) else Modifier)
            .dpadFocusable(onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * R171 — a plain Request tile (poster + status badge + title + year·genre·rating), replacing the
 * retired chart `RankTile` (no rank numeral, weeks-on-chart, or trend arrow — Seerr's discover feeds
 * carry no chart framing). Reuses the shared [Tile] component like every other row in the app.
 * Non-private: also used by [SeerrSearchScreen]'s result grid.
 */
@Composable
internal fun RequestTile(e: DiscoverEntry, focusRequester: FocusRequester? = null, onFocused: () -> Unit = {}, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    Tile(
        title = e.entry.title,
        posterUrl = tmdbPoster(e.entry.posterPath) ?: tmdbImg(e.entry.backdropPath),
        subtitle = requestSubline(e.entry),
        episodeBadge = discoverStatusLabel(e.acquisition),
        episodeBadgeColor = discoverStatusColor(e.acquisition.status, colors.accent).copy(alpha = 0.92f),
        focusRequester = focusRequester,
        onFocused = onFocused,
        onSelect = onSelect,
    )
}

// ── shared helpers (also used by DiscoverDetailScreen) ──────────────────────────

internal fun tmdbImg(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" }

/** Portrait poster URL for Request tiles (w500 fits the ~150dp poster well). */
internal fun tmdbPoster(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

internal fun requestSubline(e: RequestEntry): String =
    listOfNotNull(e.year?.toString(), e.genre, e.rating?.let { "★$it" }).joinToString("  ·  ")

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
    // Bug fix (2026-07-05): every not-yet-requested tile showed a "Request" badge — every title in a
    // feed always has this state until acted on, so it added noise rather than information. No badge
    // at all is the tile's neutral/default look; a real status only appears once one exists.
    AcquisitionStatus.NOT_REQUESTED -> null
}

internal fun discoverStatusColor(status: AcquisitionStatus, accent: Color): Color = when (status) {
    AcquisitionStatus.AVAILABLE -> Color(0xFF38C172)
    AcquisitionStatus.FAILED -> Color(0xFFE3554E)
    AcquisitionStatus.NOT_REQUESTED -> Color.Black.copy(alpha = 0.6f)
    else -> accent
}
