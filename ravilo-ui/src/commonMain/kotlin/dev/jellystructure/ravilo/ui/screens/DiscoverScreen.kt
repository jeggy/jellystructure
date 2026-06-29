package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalLiveAcquisition
import dev.jellystructure.ravilo.ui.LocalLiveConfig
import dev.jellystructure.ravilo.ui.LocalTileScale
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.focus.rememberEdgeBringIntoViewSpec
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
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
const val DISCOVER_NAV_INDEX = 3

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
        str("nav.home"), str("nav.movies"), str("nav.series"), "Top 10", str("nav.my_list"),
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
            is DiscoverState.Loaded -> DiscoverLoaded(store, s.data, displayName, DISCOVER_NAV_INDEX, navItems, onNavSelect, onEntrySelect, onProfile, onSearch)
        }
    }
}

@Composable
private fun DiscoverLoaded(
    store: DiscoverStore,
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

    // Restore scroll to last-selected row on Back-return from detail.
    LaunchedEffect(Unit) {
        val idx = store.lastSelectedRowIndex
        if (idx >= 0) {
            listState.scrollToItem((idx + 1).coerceAtLeast(0)) // +1 for header item
        }
        runCatching { navBarFR.requestFocus() }
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
                Column(Modifier.padding(horizontal = RaviloDimens.sectionPadH, vertical = 8.dp)) {
                    Text("Top 10", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    Text(
                        "Trending now · ${data.region}  ·  ${data.source.replaceFirstChar { it.uppercase() }} via Tudum",
                        color = colors.textSecondary, fontSize = 13.sp,
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
                ) { _, e, _ ->   // R139: StaticContentRow signature gained a FocusRequester slot (Discover keeps its row-level restore)
                    RankTile(e) {
                        store.lastSelectedRowIndex = ri
                        onEntrySelect(row.spec.id, e.entry.rank)
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

// Ranked Top 10 tile geometry — a portrait poster with the giant numeral tucked behind it (R49 mockup
// `.rtile`: poster 180px / numeral 168px). Sized down for TV and scaled by the operator density.
private val RANK_POSTER_W = 150.dp
private val RANK_POSTER_H = 225.dp

/**
 * A ranked Discover tile (R49 mockup `.rtile`): a giant **outlined** rank numeral with the portrait
 * poster tucked over its right edge (Netflix Top-10 style), a live status pill on the art, and a
 * compact title + trend/views sub-line. The numeral stroke turns accent on focus; the poster keeps the
 * R42/R43 draw-only focus scale + ring (no viewport jump). Smaller text + portrait art make the row far
 * more compact than the old landscape tile with a numeral in its own column.
 */
@Composable
private fun RankTile(e: DiscoverEntry, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    val tileScale = LocalTileScale.current
    val w = RANK_POSTER_W * tileScale
    val h = RANK_POSTER_H * tileScale
    val overlap = 12.dp * tileScale   // how far the poster tucks over the numeral's right edge

    var focused by remember { mutableStateOf(false) }
    // Snappier focus feel, mirrors Tile (R43): draw-only scale/ring so the lazy list never chases it.
    val focusSpec = remember { spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium) }
    val scale         by animateFloatAsState(if (focused) 1.10f else 1f, focusSpec, label = "rankScale")
    val ringWidth     by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "rankRing")
    val glowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "rankShadow")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }
    val numColor  = if (focused) colors.accent else colors.textDim
    val numSize   = (140f * tileScale).sp
    val strokePx  = with(LocalDensity.current) { (2.5.dp * tileScale).toPx() }
    val labelW    = w + 48.dp

    Column(
        modifier = Modifier.dpadFocusable(
            onFocused = { focused = true },
            onBlurred = { focused = false },
            onSelect = onSelect,
        ),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Giant outlined numeral. Trim.Both hugs the glyph box so the bottom-aligned numeral sits
            // flush with the poster's lower edge; the poster (drawn after) overlaps its right edge.
            Text(
                text = "${e.entry.rank}",
                color = numColor,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Bold,
                fontSize = numSize,
                lineHeight = numSize,
                letterSpacing = (-4).sp,
                // Nudge down ~one font-descent so the bottom-aligned digits hug the poster's lower
                // edge (the mockup's `line-height: .74` crops into the descent for the same effect).
                modifier = Modifier.offset(y = (numSize.value * 0.16f).dp),
                style = TextStyle(
                    drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Bottom,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
            Box(modifier = Modifier.offset(x = -overlap)) {
                Box(
                    modifier = Modifier
                        .width(w)
                        .height(h)
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            this.shadowElevation = glowElevation.toPx()
                            shape = tileShape
                            clip = true
                            ambientShadowColor = colors.focusGlow
                            spotShadowColor = colors.focusGlow
                        }
                        .drawWithCache {
                            val radius = CornerRadius(colors.tileRadius.toPx())
                            onDrawWithContent {
                                drawContent()
                                val bw = ringWidth.toPx()
                                if (bw > 0f) drawRoundRect(
                                    color = colors.focusRing,
                                    cornerRadius = radius,
                                    style = Stroke(width = bw),
                                    topLeft = Offset(bw / 2f, bw / 2f),
                                    size = Size(size.width - bw, size.height - bw),
                                )
                            }
                        },
                ) {
                    val posterUrl = tmdbPoster(e.entry.posterPath) ?: tmdbImg(e.entry.backdropPath)
                    if (posterUrl != null) {
                        RemoteImage(posterUrl, e.entry.title, Modifier.matchParentSize())
                    } else {
                        val hue = remember(e.entry.title) { (e.entry.title.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
                        val grad = remember(hue, colors.surface) {
                            Brush.verticalGradient(listOf(Color.hsl(hue.toFloat(), 0.38f, 0.22f), colors.surface))
                        }
                        Box(Modifier.matchParentSize().background(grad), contentAlignment = Alignment.Center) {
                            Text(
                                text = e.entry.title.take(2).uppercase(),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = Sora,
                            )
                        }
                    }
                }
                discoverStatusLabel(e.acquisition)?.let { label ->
                    StatusPill(label, discoverStatusColor(e.acquisition.status, colors.accent), Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = e.entry.title,
            color = if (focused) colors.text else colors.textSecondary,
            fontFamily = Sora,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelW),
        )
        val sub = chartSubline(e.entry)
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                color = colors.textDim,
                fontFamily = Sora,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(labelW),
            )
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

/** Portrait poster URL for the ranked Top 10 tiles (w500 fits the ~150dp poster well). */
internal fun tmdbPoster(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

internal fun chartSubline(e: ChartEntry): String {
    val trend = when (e.trend) { Trend.UP -> " ▲"; Trend.DOWN -> " ▼"; Trend.NEW -> " · NEW"; Trend.SAME -> "" }
    val base = when {
        e.views != null -> "${e.views} views (Netflix)"
        e.weeksOnChart > 0 -> "${e.weeksOnChart} ${if (e.weeksOnChart == 1) "week" else "weeks"} on chart"
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
