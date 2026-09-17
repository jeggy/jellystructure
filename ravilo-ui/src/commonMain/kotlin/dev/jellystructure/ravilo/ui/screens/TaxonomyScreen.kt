package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalLiveConfig
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.FacetItem
import kotlinx.coroutines.launch

/** R243 — which list of the facets payload a taxonomy segment renders, and the seed facet its tile opens. */
internal fun DiscoverSegment.taxonomyKind(): String = when (this) {
    DiscoverSegment.STUDIOS -> "studios"
    DiscoverSegment.NETWORKS -> "networks"
    DiscoverSegment.GENRES -> "genres"
    else -> error("not a taxonomy segment: $this")
}

/** The workbench facet name the seeded browse page filters on for this segment (FR-R243-5). */
internal fun DiscoverSegment.seedFacet(): String = when (this) {
    DiscoverSegment.STUDIOS -> "studio"
    DiscoverSegment.NETWORKS -> "network"
    DiscoverSegment.GENRES -> "genre"
    else -> error("not a taxonomy segment: $this")
}

/**
 * R243 — one of the three library-taxonomy walls under Discover (Studios · Networks · Genres): the
 * viewer-side of Jellystructure's Metadata page. A wall of tiles, each with a count, each opening the
 * browse grid that already exists — no third kind of list.
 *
 * FR-R243-2: a tile with artwork shows the logo on a neutral card with the name and count beneath; a
 * tile without sets the NAME as the wordmark and captions only the count. Never a "no logo" badge.
 * FR-R243-4: the header states the scope, plus one line when Phase 216 answers `scoped: true`.
 * FR-R243-7: wall rows are ordinary D-pad rows; Up from the first row reaches the segment bar; nothing
 * auto-focuses a tile on load; no focus-detail behaviour applies here.
 */
@Composable
fun TaxonomyScreen(
    store: TaxonomyStore,
    segment: DiscoverSegment,
    displayName: String,
    segments: List<DiscoverSegment>,
    onSegment: (DiscoverSegment) -> Unit,
    focusSegmentOnEntry: Boolean,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    /** Select on a tile: the segment it came from, the value, and the crumb naming the path. */
    onTileSelect: (segment: DiscoverSegment, item: FacetItem, crumb: String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val navItems = raviloNavItems()

    val live = LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is TaxonomyState.Loading -> HomeLoadingShell()
            is TaxonomyState.Error -> Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(200.dp)); Text(str("nav.discover"), color = colors.text, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp)); Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is TaxonomyState.Loaded -> TaxonomyLoaded(
                store, s.facets, segment, displayName, navItems, segments, onSegment, focusSegmentOnEntry,
                onNavSelect, onProfile, onSearch, onTileSelect,
            )
        }
    }
}

@Composable
private fun TaxonomyLoaded(
    store: TaxonomyStore,
    facets: BrowseFacets,
    segment: DiscoverSegment,
    displayName: String,
    navItems: List<String>,
    segments: List<DiscoverSegment>,
    onSegment: (DiscoverSegment) -> Unit,
    focusSegmentOnEntry: Boolean,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onTileSelect: (DiscoverSegment, FacetItem, String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val handset = LocalHandset.current
    val kind = segment.taxonomyKind()
    // FR-R243-3 — rendered as handed over: the server's order, the server's names, the server's counts.
    val list = when (segment) {
        DiscoverSegment.STUDIOS -> facets.studios
        DiscoverSegment.NETWORKS -> facets.networks
        else -> facets.genres
    }
    val titles = facets.titles[kind] ?: 0
    // FR-R243-2 / FR-R243-10 — 4-up (5-up genres) on the TV, 2-up (3-up genres) on a phone.
    val perRow = when {
        handset -> if (segment == DiscoverSegment.GENRES) 3 else 2
        else -> if (segment == DiscoverSegment.GENRES) 5 else 4
    }
    val rows = remember(list, perRow) { list.chunked(perRow) }
    // FR-R243-5 — the crumb the seeded page shows: `Discover · Networks` (the page title is the value).
    val crumb = "${str("nav.discover")} · ${discoverSegmentLabel(segment)}"

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navBarFR = remember { FocusRequester() }
    val columnFR = remember { FocusRequester() }
    val restoreFR = remember { FocusRequester() }
    // remember{}: the key is consumed (cleared) by the restore below; a recomposition must not drop the
    // tile's requester before the restore has run.
    val restoreKey = remember { store.lastSelectedKey }
    val willRestore = remember { restoreKey != null && list.any { "$kind:${it.name}" == restoreKey } }

    // Fresh entry ⇒ focus the AppBar (the same rule every tab screen follows); a segment switch keeps
    // focus on the pressed chip (the bar does that itself); a Back-return re-focuses the opened tile.
    LaunchedEffect(Unit) {
        when {
            // R257 (FR-R257-2) — a pending tile restore wins over the chip: the destination on the stack
            // still carries focusSegment = true from the chip press that selected this tab, so checking
            // it first meant Back from a studio's grid never reached the restore below. The key is
            // consumed, so a later switch back to this wall focuses the chip, not an old tile.
            willRestore -> {
                store.lastSelectedKey = null
                val ri = rows.indexOfFirst { row -> row.any { "$kind:${it.name}" == restoreKey } }
                if (ri >= 0) listState.scrollToItem(ri + 2)  // +2: header + meta items
                repeat(10) { if (runCatching { restoreFR.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(16) }
            }
            focusSegmentOnEntry -> Unit
            else -> runCatching { navBarFR.requestFocus() }
        }
    }

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
        val edgeBringIntoViewSpec = rememberEdgeBringIntoViewSpec(peekDp = 120.dp, topInsetDp = RaviloDimens.appBarHeight + 64.dp, centerLineFraction = 0.3f)
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides edgeBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRequester(columnFR),
                contentPadding = PaddingValues(top = RaviloDimens.appBarHeight + 24.dp, bottom = 240.dp),
            ) {
                item(key = "taxo-head") {
                    Column(Modifier.padding(horizontal = raviloHPad, vertical = 8.dp)) {
                        Text(str("nav.discover"), color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                        Text(str("tx.sub_$kind"), color = colors.textSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        DiscoverSegmentBar(segments = segments, active = segment, onSelect = onSegment, focusActiveOnEntry = focusSegmentOnEntry && !willRestore)
                    }
                }
                item(key = "taxo-meta") {
                    // FR-R243-4 — "{n} networks · {n} titles", plus the scoped line when 216 says so.
                    Row(Modifier.padding(horizontal = raviloHPad).padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(str("tx.n_$kind", mapOf("n" to list.size.toString())), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                        Text("·", color = colors.textSecondary, fontSize = 15.sp)
                        Text(titleCountLabel(titles), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                        if (facets.scoped) {
                            Text("·", color = colors.textSecondary, fontSize = 15.sp)
                            Text(str("tx.scoped_note"), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                        }
                    }
                }
                if (rows.isEmpty()) {
                    // FR-R243-8 — one sentence, no grid furniture.
                    item(key = "taxo-empty") {
                        Text(str("tx.empty"), color = colors.textSecondary, fontSize = 16.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = raviloHPad, vertical = 26.dp))
                    }
                }
                items(rows.size, key = { ri -> "taxo-row-$ri" }) { ri ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = raviloHPad).padding(top = if (ri == 0) 12.dp else 0.dp, bottom = 26.dp),
                        horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                    ) {
                        rows[ri].forEach { item ->
                            val key = "$kind:${item.name}"
                            Box(Modifier.weight(1f)) {
                                TaxonomyTile(
                                    item = item,
                                    genre = segment == DiscoverSegment.GENRES,
                                    focusRequester = if (key == restoreKey) restoreFR else null,
                                    onSelect = {
                                        store.lastSelectedKey = key
                                        onTileSelect(segment, item, crumb)
                                    },
                                )
                            }
                        }
                        // keep the last row's cells the same width as a full row
                        repeat(perRow - rows[ri].size) { Spacer(Modifier.weight(1f)) }
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
                activeNav = DISCOVER_NAV_INDEX,
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

/** `1 title` / `{n} titles` — the singular form is its own string in all three languages (FR-R243-9). */
@Composable
internal fun titleCountLabel(n: Int): String =
    if (n == 1) str("tx.title_one") else str("tx.titles", mapOf("n" to n.toString()))

/**
 * FR-R243-2 — the two tile variants. A logo tile: the logo contained on a neutral card, name + count
 * beneath. A wordmark tile: the name fills the card, and the caption is the count alone — the name is
 * not repeated, and nothing ever says "no logo". Both carry exactly one caption line so the grid stays
 * regular. Not a [dev.jellystructure.ravilo.ui.components.Tile]: no poster, no progress, no badge.
 */
private val TAXO_LOGO_PLATE = androidx.compose.ui.graphics.Color(0xFFE8EAF0)

@Composable
private fun TaxonomyTile(
    item: FacetItem,
    genre: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val handset = LocalHandset.current
    var focused by remember { mutableStateOf(false) }
    val cardHeight = when {
        handset -> if (genre) 64.dp else 96.dp
        else -> if (genre) 104.dp else 158.dp
    }
    val wmSize = when {
        handset -> if (genre) 15.sp else 19.sp
        // R257 — 34 sp was the mockup's px on a 1920 canvas; a 4-up TV tile is ~200 dp wide and it had
        // never been seen there (R256: the TV drew the handset branch). "Entertainment" clips at 24 sp, fits at 20.
        else -> 20.sp
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .graphicsLayer { val s = if (focused) 1.045f else 1f; scaleX = s; scaleY = s }
                // R257 (FR-R257-3) — a captured logo is dark ink on transparency far more often than not (88
                // of 135 in production; TMDB draws them for a light page), so a logo tile gets a light
                // plate; a wordmark tile keeps the dark card and light ink.
                // R259 (FR-R259-5) — …except a logo the server has judged LIGHT ink (232: Channel 4's white mark),
                // which keeps the dark card it was drawn for. Unknown ink = the light plate.
                .background(if (item.logoUrl != null && item.logoInk != "light") TAXO_LOGO_PLATE else colors.surfaceVariant, shape)
                .then(if (focused) Modifier.border(2.dp, colors.focusRing, shape) else Modifier)
                .padding(horizontal = if (handset || item.logoUrl == null) 14.dp else 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            val logo = item.logoUrl
            if (logo != null) {
                RemoteImage(
                    url = logo,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth(0.74f).height(cardHeight * 0.56f),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    item.name,
                    color = colors.text,
                    fontSize = wmSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = wmSize * 1.05f,
                )
            }
        }
        // R257 — on a TV the name and the count stack: side by side they need ~215 dp and a 4-up tile has
        // ~200 ("Marvel Stu… 36 titles"). A handset's 2-up tile keeps R243's single line.
        val nameText: @Composable (Modifier) -> Unit = { m ->
            Text(
                item.name,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = if (handset) 14.sp else 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = m,
            )
        }
        val countText: @Composable () -> Unit = {
            Text(titleCountLabel(item.count), color = colors.textSecondary, fontSize = if (handset) 13.sp else 15.sp, fontFamily = Sora, maxLines = 1)
        }
        if (handset || genre) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.logoUrl != null) nameText(Modifier.weight(1f, fill = false))
                countText()
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
                if (item.logoUrl != null) nameText(Modifier)
                countText()
            }
        }
    }
}
