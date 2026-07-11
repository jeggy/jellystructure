package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val PX_PER_MINUTE = 4.4f

// Bug fix: program cells showed only the title, no start/end time at all — reported as "missing
// timestamps" after the guide was made reachable (the "See All"/"TV Guide" link fix). Matches
// AppBar's ClockDisplay formatting (24h HH:mm, device-local time zone).
private fun formatGuideTime(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/**
 * Phase R177 §C — the full-schedule EPG guide: one horizontally-scrollable row of programs per
 * channel (sticky channel column), category filter chips (channel-level — addendum B, Jellyfin has
 * no per-program category data), a "now" line per row (the current program is highlighted with its
 * live elapsed progress). Selecting a program tunes its channel LIVE, not the future slot — the
 * spec's own framing (§C1: "you watch live, not the future slot").
 */
@Composable
fun LiveTvGuideScreen(
    store: LiveTvGuideStore,
    displayName: String,
    discoverAvailable: Boolean,
    onBack: () -> Unit,
    onTuneChannel: (LiveTvChannel) -> Unit,
    onNavSelect: (Int) -> Unit = {},
    onProfile: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    LaunchedEffect(Unit) { store.load() }

    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val navItems = raviloNavItems(discoverAvailable)
    val navBarFR = remember { FocusRequester() }

    // Give the bar initial focus so Back works even during Loading/Error (matches ChannelScreen);
    // the Loaded branch below re-routes focus onto the first channel row once data is in.
    LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background).dpadFocusable(onBack = onBack)) {
        when (val s = state) {
            is LiveTvGuideState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            is LiveTvGuideState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
            }
            is LiveTvGuideState.Loaded -> {
                val categories = remember(s.channels) { s.channels.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
                val visibleChannels = remember(s.channels, selectedCategory) {
                    if (selectedCategory == null) s.channels else s.channels.filter { it.category == selectedCategory }
                }
                val nowMs = remember { Clock.System.now().toEpochMilliseconds() }

                // Bug fix: each channel's program row scrolled independently — dragging one channel's
                // row left/right left every other row showing whatever time slice it happened to be at,
                // so the grid didn't read as one shared timeline the way a real TV guide does. Two parts:
                // (1) guideOriginMs anchors every row to the SAME absolute clock position (the earliest
                // program start across the whole grid), via a per-row leading spacer in GuideChannelRow —
                // without this, scrolling every row by the same pixel amount wouldn't line them up, since
                // each channel's own program list can start at a different real time. (2) sharedScrollableState
                // fans every drag/fling delta out to every row's own LazyListState via scrollBy, so once
                // aligned they move together. (D-pad focus-driven auto-scroll within a single row is
                // unaffected by this — this covers the touch/drag scroll gesture, which is what "scrolling
                // a channel" means on the phone/web targets this was reported on.)
                val guideOriginMs = remember(s.programs) { s.programs.minOfOrNull { it.startMs } ?: nowMs }
                val rowListStates = remember(visibleChannels) {
                    visibleChannels.associate { it.channelId to LazyListState() }
                }
                val scope = rememberCoroutineScope()
                val sharedScrollableState = rememberScrollableState { delta ->
                    scope.launch { rowListStates.values.forEach { it.scrollBy(-delta) } }
                    delta
                }

                // This screen had no initial-focus target — every other screen requests focus onto
                // a nav bar / first cell on load, but this one never did, so a D-pad landing here had
                // nothing to move focus away from the (non-directional) root box: LEFT/RIGHT/DOWN were
                // all silently swallowed. Land on the first channel's row like BrowseScreen's firstCellFR.
                val firstChannelFR = remember { FocusRequester() }
                LaunchedEffect(visibleChannels.isNotEmpty()) {
                    if (visibleChannels.isNotEmpty()) runCatching { firstChannelFR.requestFocus() }
                }

                Column(modifier = Modifier.fillMaxSize().padding(top = 88.dp)) {
                    Text(
                        str("livetv.guide"),
                        color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (categories.isNotEmpty()) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "__all") {
                                CategoryChip(str("browse.all").ifBlank { "All" }, selectedCategory == null) { selectedCategory = null }
                            }
                            items(categories, key = { it }) { cat ->
                                CategoryChip(cat, selectedCategory == cat) { selectedCategory = cat }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(visibleChannels, key = { _, ch -> ch.channelId }) { index, ch ->
                            GuideChannelRow(
                                channel = ch,
                                programs = s.programs.filter { it.channelId == ch.channelId }.sortedBy { it.startMs },
                                nowMs = nowMs,
                                guideOriginMs = guideOriginMs,
                                listState = rowListStates.getValue(ch.channelId),
                                scrollableState = sharedScrollableState,
                                onTune = { onTuneChannel(ch) },
                                channelFocusRequester = if (index == 0) firstChannelFR else null,
                            )
                        }
                    }
                }
            }
        }
        AppBar(
            navItems = navItems,
            activeNav = -1,   // the guide isn't one of the section tabs → no tab highlighted
            onNavSelect = onNavSelect,
            navFR = navBarFR,
            userInitials = displayName.take(2).uppercase(),
            onProfile = onProfile,
            onSearch = onSearch,
            scrolled = true,
        )
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(if (selected) colors.accent else colors.surfaceVariant, RoundedCornerShape(20.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(20.dp)) else Modifier)
            .dpadFocusable(onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) colors.onAccent else colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GuideChannelRow(
    channel: LiveTvChannel,
    programs: List<LiveTvGuideProgram>,
    nowMs: Long,
    guideOriginMs: Long,
    listState: LazyListState,
    scrollableState: ScrollableState,
    onTune: () -> Unit,
    channelFocusRequester: FocusRequester? = null,
) {
    val colors = RaviloTheme.colors
    Row(modifier = Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
        // Sticky-ish channel column (not a true pinned-column grid — see the R177 status note on scope).
        var chFocused by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.width(140.dp).fillMaxSize()
                .background(if (chFocused) colors.accentDim else colors.surface)
                .dpadFocusable(
                    focusRequester = channelFocusRequester,
                    onFocused = { chFocused = true }, onBlurred = { chFocused = false }, onSelect = onTune,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${channel.number}", color = colors.textSecondary, fontSize = 11.sp)
            Text(channel.name, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
        Spacer(Modifier.width(2.dp))
        if (programs.isEmpty()) {
            Box(Modifier.fillMaxSize().background(colors.surfaceVariant), contentAlignment = Alignment.CenterStart) {
                Text(str("livetv.no_programs"), color = colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        } else {
            // Leading spacer aligns this row's first program to guideOriginMs (the earliest program
            // start across ALL channels) — without it, two channels whose own schedules start at
            // different real times would render item 0 at the same x position despite representing
            // different clock times, so scrolling them by equal pixel amounts (below) wouldn't keep
            // them showing the same time slice.
            val leadingGapMin = ((programs.first().startMs - guideOriginMs) / 60_000L).coerceAtLeast(0L)
            LazyRow(
                state = listState,
                // The shared scrollableState (fanned out to every row's listState) drives scrolling
                // instead — a row's own drag gesture would fight it and desync from the others.
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().scrollable(scrollableState, Orientation.Horizontal),
            ) {
                if (leadingGapMin > 0) {
                    item(key = "__lead") { Spacer(Modifier.width((leadingGapMin * PX_PER_MINUTE).dp).fillMaxHeight()) }
                }
                items(programs, key = { "${it.channelId}-${it.startMs}" }) { p ->
                    val isNow = nowMs in p.startMs until p.endMs
                    val minutes = ((p.endMs - p.startMs) / 60_000L).coerceAtLeast(1L).toInt()
                    var pFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .widthIn(min = 90.dp).width((minutes * PX_PER_MINUTE).dp).fillMaxSize()
                            .background(if (isNow) colors.accentDim else colors.surfaceVariant, RoundedCornerShape(6.dp))
                            .then(if (pFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(6.dp)) else Modifier)
                            .dpadFocusable(onFocused = { pFocused = true }, onBlurred = { pFocused = false }, onSelect = onTune)
                            .padding(8.dp),
                    ) {
                        Column {
                            Text(
                                "${formatGuideTime(p.startMs)}–${formatGuideTime(p.endMs)}",
                                color = colors.textDim, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            )
                            Text(p.name, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            if (isNow) {
                                val progress = ((nowMs - p.startMs).toFloat() / (p.endMs - p.startMs).toFloat()).coerceIn(0f, 1f)
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(3.dp).background(colors.progressBg, RoundedCornerShape(2.dp))) {
                                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(colors.progressFill, RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
