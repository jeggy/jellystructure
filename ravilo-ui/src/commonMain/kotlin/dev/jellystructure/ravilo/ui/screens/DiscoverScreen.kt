package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.UpcomingItem
import kotlinx.coroutines.launch

/**
 * R262 — one Discover frame, not three screens. One [AppBar], one page header (title + subtitle, plus
 * the Seerr pill only for Request) and one [DiscoverSegmentBar], composed **once** for as long as
 * `Dest.Discover` is on top — a chip press changes only the content region below them (FR-R262-1).
 * Loading/Error are content-region states, never page states (FR-R262-2): the frame always stands.
 *
 * The three stores are constructed by the caller (`RaviloApp.kt`'s `is Dest.Discover ->` branch) via
 * `keptStore(...)`, one call per available segment, so entering Discover warms every tab regardless of
 * which one is shown first (FR-R262-7); [upcomingStore]/[requestStore] are `null` exactly when their
 * segment isn't in [segments] (Sonarr/Radarr or Seerr not configured) and are never rendered in that
 * case. [taxonomyStore] is never null — the three library-taxonomy segments are never gated.
 *
 * `navBarFR` and one [LazyListState] + one content-column [FocusRequester] per segment KIND are lifted
 * here (not inside each content composable) so Back-to-top (FR-R262-5) and "AppBar Down → content"
 * (unchanged) can always reach whichever one is active; Studios/Networks/Genres share one, matching
 * their shared store. This also means a Request/Taxonomy scroll position now survives a round trip to
 * a different segment and back within the same visit — more than FR-R262-4 requires, not less.
 */
@Composable
fun DiscoverScreen(
    segment: DiscoverSegment,
    segments: List<DiscoverSegment>,
    onSegment: (DiscoverSegment) -> Unit,
    // R262 (dev review item 2) — lives on the destination (R243's focusSegment), so it survives a
    // drill-in/Back round trip; must be consumed (below) the instant the chip takes focus, or a return
    // from a seeded grid re-steals focus from R257's tile restore.
    focusSegmentOnEntry: Boolean,
    onFocusSegmentConsumed: () -> Unit,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    upcomingStore: UpcomingStore?,
    onUpcomingItemSelect: (UpcomingItem) -> Unit,
    requestStore: DiscoverStore?,
    onEntrySelect: (mediaType: String, tmdbId: Int) -> Unit,
    onSearchSeerr: () -> Unit,
    taxonomyStore: TaxonomyStore,
    onTileSelect: (segment: DiscoverSegment, item: FacetItem, crumb: String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val navItems = raviloNavItems()
    val scope = rememberCoroutineScope()

    val navBarFR = remember { FocusRequester() }
    val upcomingColumnFR = remember { FocusRequester() }
    val requestColumnFR = remember { FocusRequester() }
    val taxonomyColumnFR = remember { FocusRequester() }
    // Request/Taxonomy have no store-held scroll state (unlike UpcomingStore.listState) — lifted here,
    // outside the `when` below, so a segment switch and back doesn't reset them either.
    val requestListState = remember { LazyListState() }
    val taxonomyListState = remember { LazyListState() }

    val activeListState = when (segment) {
        DiscoverSegment.COMING_SOON -> upcomingStore?.listState ?: requestListState
        DiscoverSegment.REQUEST -> requestListState
        else -> taxonomyListState
    }
    val activeColumnFR = when (segment) {
        DiscoverSegment.COMING_SOON -> upcomingColumnFR
        DiscoverSegment.REQUEST -> requestColumnFR
        else -> taxonomyColumnFR
    }
    // R257 (FR-R257-2), moved here from TaxonomyScreen's own willRestore check (dev review item 2): a
    // pending restore on the active segment's own store wins over the chip taking focus. Checking "any
    // pending key at all" rather than confirming the exact tile is still present trades a hair of
    // precision (a stale key with a since-vanished tile would leave focus on neither) for not needing
    // this frame to know each content composable's row/tile shape.
    val activeHasPendingRestore = when (segment) {
        DiscoverSegment.COMING_SOON -> upcomingStore?.lastSelectedItemKey != null
        DiscoverSegment.REQUEST -> requestStore?.lastSelectedItemKey != null
        else -> taxonomyStore.lastSelectedKey != null
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(
            // FR-R262-5/6 — "at the top" is the content region's own scroll state, never which element
            // has focus (the bug this phase fixes: the nav bar having focus isn't the same as being
            // scrolled to the top, and cost a whole extra Back press). Gated to the TV inside the
            // modifier itself (FR-R262-6).
            modifier = Modifier.fillMaxSize().backToTopOnBack(
                atTop = { activeListState.firstVisibleItemIndex == 0 && activeListState.firstVisibleItemScrollOffset == 0 },
                onBackToTop = {
                    runCatching { navBarFR.requestFocus() }
                    scope.launch { activeListState.scrollToItem(0) }
                },
            ),
        ) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(RaviloDimens.appBarHeight + 24.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad).padding(bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                discoverHeaderTitle(segment), color = colors.text,
                                fontSize = if (segment == DiscoverSegment.COMING_SOON) 26.sp else 22.sp,
                                fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk,
                            )
                            Text(discoverHeaderSubtitle(segment), color = colors.textSecondary, fontSize = 13.sp)
                        }
                        if (segment == DiscoverSegment.REQUEST) SeerrSearchPill(onSelect = onSearchSeerr)
                    }
                    Spacer(Modifier.height(12.dp))
                    DiscoverSegmentBar(
                        segments = segments,
                        active = segment,
                        onSelect = onSegment,
                        focusActiveOnEntry = focusSegmentOnEntry && !activeHasPendingRestore,
                        onFocusConsumed = onFocusSegmentConsumed,
                    )
                }
                // FR-R262-2 — loading/error are content-region states; the frame above never changes.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (segment) {
                        DiscoverSegment.COMING_SOON -> {
                            val store = upcomingStore
                            if (store == null) DiscoverLoading() else {
                                val upcomingState by store.state.collectAsState()
                                when (val s = upcomingState) {
                                    is UpcomingState.Loading -> DiscoverLoading()
                                    is UpcomingState.Error -> DiscoverError(s.message)
                                    is UpcomingState.Loaded -> UpcomingContent(
                                        store = store,
                                        feed = s.feed,
                                        listState = activeListState,
                                        navBarFR = navBarFR,
                                        columnFR = activeColumnFR,
                                        focusSegmentOnEntry = focusSegmentOnEntry,
                                        onItemSelect = onUpcomingItemSelect,
                                    )
                                }
                            }
                        }
                        DiscoverSegment.REQUEST -> {
                            val store = requestStore
                            if (store == null) DiscoverLoading() else {
                                val requestState by store.state.collectAsState()
                                when (val s = requestState) {
                                    is DiscoverState.Loading -> DiscoverLoading()
                                    is DiscoverState.Error -> DiscoverError(s.message)
                                    is DiscoverState.Loaded -> RequestContent(
                                        store = store,
                                        data = s.data,
                                        listState = activeListState,
                                        navBarFR = navBarFR,
                                        columnFR = activeColumnFR,
                                        focusSegmentOnEntry = focusSegmentOnEntry,
                                        onEntrySelect = onEntrySelect,
                                    )
                                }
                            }
                        }
                        DiscoverSegment.STUDIOS, DiscoverSegment.NETWORKS, DiscoverSegment.GENRES -> {
                            val taxState by taxonomyStore.state.collectAsState()
                            when (val s = taxState) {
                                is TaxonomyState.Loading -> DiscoverLoading()
                                is TaxonomyState.Error -> DiscoverError(s.message)
                                is TaxonomyState.Loaded -> TaxonomyContent(
                                    store = taxonomyStore,
                                    facets = s.facets,
                                    segment = segment,
                                    listState = activeListState,
                                    navBarFR = navBarFR,
                                    columnFR = activeColumnFR,
                                    focusSegmentOnEntry = focusSegmentOnEntry,
                                    onTileSelect = onTileSelect,
                                )
                            }
                        }
                    }
                }
            }

            val initials = remember(displayName) {
                displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
            }
            val appBarScrolled by remember(activeListState) { derivedStateOf {
                activeListState.firstVisibleItemIndex > 0 || activeListState.firstVisibleItemScrollOffset > 0
            } }
            AppBar(
                navItems = navItems,
                activeNav = DISCOVER_NAV_INDEX,
                onNavSelect = onNavSelect,
                navFR = navBarFR,
                onDown = { runCatching { activeColumnFR.requestFocus() } },
                userInitials = initials,
                onProfile = onProfile,
                onSearch = onSearch,
                scrolled = appBarScrolled,
            )
        }
    }
}

@Composable
private fun discoverHeaderTitle(segment: DiscoverSegment): String = when (segment) {
    DiscoverSegment.COMING_SOON -> str("nav.upcoming")
    DiscoverSegment.REQUEST -> str("seg.request")
    DiscoverSegment.STUDIOS, DiscoverSegment.NETWORKS, DiscoverSegment.GENRES -> str("nav.discover")
}

@Composable
private fun discoverHeaderSubtitle(segment: DiscoverSegment): String = when (segment) {
    DiscoverSegment.COMING_SOON -> str("up.subtitle")
    DiscoverSegment.REQUEST -> str("request.sub")
    DiscoverSegment.STUDIOS, DiscoverSegment.NETWORKS, DiscoverSegment.GENRES -> str("tx.sub_${segment.taxonomyKind()}")
}

// FR-R262-2 — Browse's own one-line treatment (open question 3: acceptable per the FR text; the
// per-segment shimmer it also allows is deliberately left as later polish, zero design work needed).
@Composable
private fun DiscoverLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(str("loading"), color = RaviloTheme.colors.textSecondary, fontSize = 16.sp)
    }
}

@Composable
private fun DiscoverError(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = RaviloTheme.colors.textSecondary, fontSize = 14.sp)
    }
}
