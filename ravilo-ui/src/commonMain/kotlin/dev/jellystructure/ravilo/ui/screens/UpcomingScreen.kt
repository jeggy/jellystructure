package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalLiveConfig
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

sealed class UpcomingState {
    data object Loading : UpcomingState()
    data class Loaded(val feed: UpcomingFeed) : UpcomingState()
    data class Error(val message: String) : UpcomingState()
}

/** R160 — the calendar is the same for every viewer; `getUpcoming()` is itself server-cached
 *  (UpcomingService), so re-fetching on every silent refresh is cheap. */
class UpcomingStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<UpcomingState>(UpcomingState.Loading)
    val state: StateFlow<UpcomingState> = _state.asStateFlow()
    val listState = LazyListState()

    init { load() }

    fun load() {
        _state.value = UpcomingState.Loading
        scope.launch {
            _state.value = runCatching { UpcomingState.Loaded(apiClient.getUpcoming()) }
                .getOrElse { UpcomingState.Error(it.message ?: "Unknown error") }
        }
    }

    fun refresh(silent: Boolean = true) {
        if (!silent) { load(); return }
        scope.launch {
            runCatching { apiClient.getUpcoming() }.getOrNull()?.let { _state.value = UpcomingState.Loaded(it) }
        }
    }
}

private enum class UpcomingFilter { ALL, SERIES, MOVIES }

@Composable
fun UpcomingScreen(
    store: UpcomingStore,
    displayName: String,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onItemSelect: (UpcomingItem) -> Unit,
    // R170 — set when Request (Seerr feeds) is *also* available, so this segment needs a way over to
    // it; null when Coming Soon is the only Discover segment (no switcher shown).
    onSwitchToRequest: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    // Being on this screen implies the Discover tab itself is active, regardless of which segment.
    val navItems = raviloNavItems(discoverAvailable = true)

    val live = LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is UpcomingState.Loading -> HomeLoadingShell()
            is UpcomingState.Error -> Column(
                Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(200.dp))
                Text(str("nav.upcoming"), color = colors.text, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is UpcomingState.Loaded -> UpcomingLoaded(
                store = store,
                feed = s.feed,
                displayName = displayName,
                navItems = navItems,
                onNavSelect = onNavSelect,
                onProfile = onProfile,
                onSearch = onSearch,
                onItemSelect = onItemSelect,
                onSwitchToRequest = onSwitchToRequest,
            )
        }
    }
}

@Composable
private fun UpcomingLoaded(
    store: UpcomingStore,
    feed: UpcomingFeed,
    displayName: String,
    navItems: List<String>,
    onNavSelect: (Int) -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onItemSelect: (UpcomingItem) -> Unit,
    onSwitchToRequest: (() -> Unit)?,
) {
    val colors = RaviloTheme.colors
    var filter by remember { mutableStateOf(UpcomingFilter.ALL) }
    val filtered = remember(feed, filter) {
        when (filter) {
            UpcomingFilter.ALL -> feed.items
            UpcomingFilter.SERIES -> feed.items.filter { it.kind == MediaKind.SERIES }
            UpcomingFilter.MOVIES -> feed.items.filter { it.kind == MediaKind.MOVIE }
        }
    }
    // `feed.items` is already server-sorted by date — LinkedHashMap preserves that chronological order.
    val grouped = remember(filtered) {
        val map = LinkedHashMap<String, MutableList<UpcomingItem>>()
        filtered.forEach { map.getOrPut(it.date) { mutableListOf() }.add(it) }
        map
    }
    val days = remember(grouped) { grouped.keys.toList() }
    // Item 0 = header. Then either one "empty" placeholder or one item per day. Used to compute
    // the missing-jump-pill's scroll target correctly in both cases.
    val missingHeaderIndex = 1 + (if (filtered.isEmpty()) 1 else days.size)

    val listState = store.listState
    val scope = rememberCoroutineScope()
    val navBarFR = remember { FocusRequester() }
    val columnFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }

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
        // Index accounting for animateScrollToItem from the date rail: item 0 = header block
        // (title/subtitle/filter chips/jump pill/date rail), then one item per day section.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(columnFR),
            contentPadding = PaddingValues(top = RaviloDimens.appBarHeight + 24.dp, bottom = 120.dp),
        ) {
            item(key = "up-head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad, vertical = 8.dp)) {
                    Text(str("nav.upcoming"), color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    Text(str("up.subtitle"), color = colors.textSecondary, fontSize = 13.sp)
                    if (onSwitchToRequest != null) {
                        Spacer(Modifier.height(12.dp))
                        DiscoverSegmentPill(other = str("seg.request"), onSelect = onSwitchToRequest)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChips(filter, onFilterChange = { filter = it })
                        if (feed.missing.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            MissingJumpPill(feed.missing.size) {
                                scope.launch { listState.animateScrollToItem(missingHeaderIndex) }
                            }
                        }
                    }
                    if (days.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        DateRail(days, grouped) { day ->
                            scope.launch { listState.animateScrollToItem(1 + days.indexOf(day)) }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "up-empty") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = raviloHPad, vertical = 60.dp)) {
                        Text(str("up.nothing"), color = colors.textSecondary, fontSize = 15.sp)
                    }
                }
            } else {
                days.forEach { day ->
                    item(key = "up-day:$day") {
                        StaticContentRow(
                            title = dayLabel(day),
                            items = grouped[day].orEmpty(),
                            itemKey = { it.id },
                        ) { _, item, fr ->
                            UpcomingCard(item, fr, onSelect = { onItemSelect(item) })
                        }
                    }
                }
            }

            if (feed.missing.isNotEmpty()) {
                item(key = "up-missing-head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad).padding(top = 28.dp, bottom = 8.dp)) {
                        Text(str("up.missing_title"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(str("up.missing_sub"), color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
                item(key = "up-missing-row") {
                    StaticContentRow(
                        title = null,
                        items = feed.missing,
                        itemKey = { "missing:${it.id}" },
                    ) { _, item, fr ->
                        UpcomingCard(item, fr, onSelect = { onItemSelect(item) })
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
                activeNav = 3,
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
private fun FilterChips(active: UpcomingFilter, onFilterChange: (UpcomingFilter) -> Unit) {
    val colors = RaviloTheme.colors
    val chips = listOf(UpcomingFilter.ALL to "up.all", UpcomingFilter.SERIES to "up.series", UpcomingFilter.MOVIES to "up.movies")
    val frs = remember { chips.map { FocusRequester() } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEachIndexed { i, (kind, labelKey) ->
            val isActive = active == kind
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .background(if (isActive) colors.accent else colors.surfaceVariant, RoundedCornerShape(20.dp))
                    .then(if (focused && !isActive) Modifier.background(colors.surfaceVariant, RoundedCornerShape(20.dp)) else Modifier)
                    .dpadFocusable(
                        focusRequester = frs[i],
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onLeft = { if (i > 0) frs[i - 1].requestFocus() },
                        onRight = { if (i < chips.lastIndex) frs[i + 1].requestFocus() },
                        onSelect = { onFilterChange(kind) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    str(labelKey),
                    color = if (isActive) colors.onAccent else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MissingJumpPill(count: Int, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(Color(0xFFE0393A).copy(alpha = if (focused) 0.9f else 0.75f), RoundedCornerShape(20.dp))
            .dpadFocusable(focusRequester = fr, onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(str("up.missing_jump", mapOf("count" to count.toString())), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DateRail(days: List<String>, grouped: Map<String, List<UpcomingItem>>, onDaySelect: (String) -> Unit) {
    val colors = RaviloTheme.colors
    val frs = remember(days) { days.map { FocusRequester() } }
    LazyRow(state = rememberLazyListState(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days.size, key = { i -> "rail:${days[i]}" }) { i ->
            val day = days[i]
            val date = remember(day) { runCatching { LocalDate.parse(day) }.getOrNull() }
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
                    .then(if (focused) Modifier.background(colors.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp)) else Modifier)
                    .dpadFocusable(
                        focusRequester = frs[i],
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onLeft = { if (i > 0) frs[i - 1].requestFocus() },
                        onRight = { if (i < days.lastIndex) frs[i + 1].requestFocus() },
                        onSelect = { onDaySelect(day) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date?.let { weekdayAbbrev(it.dayOfWeek) } ?: "",
                    color = colors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    date?.day?.toString() ?: "?",
                    color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    date?.let { monthAbbrev(it.month) } ?: "",
                    color = colors.textSecondary, fontSize = 10.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${grouped[day]?.size ?: 0}",
                    color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun UpcomingCard(item: UpcomingItem, focusRequester: FocusRequester?, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val gradient = remember(item.title) { gradientFor(item.title) }
    // Same draw-only focus treatment as Tile.kt (R42/R43): scale + shadow in graphicsLayer, ring in
    // drawWithCache — no animated value is read at composition, so the card never recomposes per frame.
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec = remember { RaviloMotion.focusSpring<Dp>() }
    val scale by animateFloatAsState(if (focused) RaviloMotion.TILE_FOCUS_SCALE else 1f, focusSpec, label = "upCardScale")
    val ringWidth by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "upCardRing")
    val glowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "upCardShadow")
    val cardShape = remember { RoundedCornerShape(10.dp) }

    Column(modifier = Modifier.width(220.dp)) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(124.dp)
                .dpadFocusable(focusRequester = focusRequester, onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = glowElevation.toPx()
                    shape = cardShape
                    clip = true
                    ambientShadowColor = colors.focusGlow
                    spotShadowColor = colors.focusGlow
                }
                .drawWithCache {
                    val radius = CornerRadius(10.dp.toPx())
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
            // Cross-module `val` properties (item.posterUrl is declared in :shared) aren't
            // smart-cast — bind to a local val first. R167: not-held items have no on-disk poster —
            // fall back to the client-direct-CDN remote poster before the gradient placeholder.
            val posterUrl = item.posterUrl ?: item.posterRemoteUrl
            if (posterUrl != null) {
                RemoteImage(posterUrl, item.title, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(gradient))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))

            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (item.kind == MediaKind.MOVIE) str("up.movie") else str("up.episode"),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                statusPillText(item)?.let { (label, color) ->
                    Row(
                        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (item.status == UpcomingStatus.DOWNLOADING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(8.dp),
                                color = Color.White,
                                strokeWidth = 1.5.dp,
                            )
                        }
                        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(
                    item.time ?: releaseTypeLabel(item.releaseType) ?: "",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = if (item.kind == MediaKind.SERIES && item.season != null && item.episode != null) {
            "S${item.season}·E${item.episode}" + (item.episodeTitle?.let { " · $it" } ?: "")
        } else releaseTypeLabel(item.releaseType) ?: ""
        Text(sub, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val footer = listOfNotNull(item.network, item.year?.toString()).joinToString("  ·  ")
        if (footer.isNotBlank()) Text(footer, color = colors.textSecondary.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.status == UpcomingStatus.MISSING) {
            Text(str("up.was_due", mapOf("date" to item.date)), color = Color(0xFFE0393A), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun statusPillText(item: UpcomingItem): Pair<String, Color>? = when (item.status) {
    UpcomingStatus.DOWNLOADING -> (item.progress?.let { "$it%" } ?: "…") to Color(0xFF3B82F6)
    UpcomingStatus.AVAILABLE -> str("up.available") to Color(0xFF22A559)
    UpcomingStatus.MISSING -> str("up.missing") to Color(0xFFE0393A)
    UpcomingStatus.MONITORED -> null
}

@Composable
private fun releaseTypeLabel(releaseType: String?): String? = when (releaseType) {
    "digital" -> str("up.release_digital")
    "physical" -> str("up.release_physical")
    "cinema" -> str("up.release_cinema")
    else -> null
}

@Composable
private fun dayLabel(dateStr: String): String {
    val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
    val today = todayUtc()
    return when (today.daysUntil(date)) {
        0 -> str("up.day_today")
        1 -> str("up.day_tomorrow")
        -1 -> str("up.day_yesterday")
        else -> "${weekdayAbbrev(date.dayOfWeek)} ${date.day} ${monthAbbrev(date.month)}"
    }
}

private fun todayUtc(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date

private fun weekdayAbbrev(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "MON"; DayOfWeek.TUESDAY -> "TUE"; DayOfWeek.WEDNESDAY -> "WED"
    DayOfWeek.THURSDAY -> "THU"; DayOfWeek.FRIDAY -> "FRI"; DayOfWeek.SATURDAY -> "SAT"
    DayOfWeek.SUNDAY -> "SUN"
}

private fun monthAbbrev(m: Month): String = when (m) {
    Month.JANUARY -> "JAN"; Month.FEBRUARY -> "FEB"; Month.MARCH -> "MAR"; Month.APRIL -> "APR"
    Month.MAY -> "MAY"; Month.JUNE -> "JUN"; Month.JULY -> "JUL"; Month.AUGUST -> "AUG"
    Month.SEPTEMBER -> "SEP"; Month.OCTOBER -> "OCT"; Month.NOVEMBER -> "NOV"; Month.DECEMBER -> "DEC"
}

// internal, not private: UpcomingDetailScreen.kt (a different file, same package) reuses this too.
internal val UPCOMING_GRADIENTS = listOf(
    Brush.linearGradient(listOf(Color(0xFF6D28D9), Color(0xFF1E3A8A))),
    Brush.linearGradient(listOf(Color(0xFFBE185D), Color(0xFF7C2D92))),
    Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF1E3A8A))),
    Brush.linearGradient(listOf(Color(0xFF9A3412), Color(0xFF7C2D12))),
    Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF0F172A))),
)

internal fun gradientFor(title: String): Brush =
    UPCOMING_GRADIENTS[(title.hashCode().let { if (it < 0) -it else it }) % UPCOMING_GRADIENTS.size]
