package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val PX_PER_MINUTE = 4.4f
private const val CHANNEL_COL_WIDTH_DP = 140
private const val CHANNEL_COL_SPACER_DP = 2
// Bug fix: D-pad LEFT/RIGHT paces the shared viewport by this fixed amount (see PAGE_MINUTES call
// sites' doc comment for why this replaced per-program-cell focus).
private const val PAGE_MINUTES = 30f

/**
 * Computes which item (index 0 = the very first rendered cell, whatever it is — a gap spacer or a
 * real program/tick) and intra-item pixel offset corresponds to [targetMinutes] (minutes since a
 * shared origin), given every cell's actual RENDERED width in minutes in order, starting at the
 * origin. Shared by [GuideChannelRow] (program cells + gap spacers) and [GuideTimeRuler] (a leading
 * gap + uniform 60min ticks) so both position identically for the same target.
 */
private fun scrollTarget(itemWidthsMin: List<Float>, targetMinutes: Float): Pair<Int, Int> {
    if (itemWidthsMin.isEmpty()) return 0 to 0
    var cursor = 0f
    for ((i, w) in itemWidthsMin.withIndex()) {
        if (targetMinutes < cursor + w || i == itemWidthsMin.lastIndex) {
            val px = ((targetMinutes - cursor).coerceAtLeast(0f) * PX_PER_MINUTE).toInt()
            return i to px
        }
        cursor += w
    }
    return itemWidthsMin.lastIndex to 0
}

/** A guide row's timeline as a flat, gap-aware cell list — real EPG data can have scheduling gaps
 *  between two programs on the same channel (channel off-air / no data), and simply butting the
 *  next program up against the previous one (the old behaviour) silently "compresses away" that gap,
 *  which desyncs the cumulative layout position from real clock time for every cell after it. Each
 *  gap becomes its own spacer cell so the rendered layout and [scrollTarget]'s position math always
 *  agree, and both are anchored at [originMs] (so a leading gap before the very first item falls out
 *  of the same loop, no special-cased "index 0 is the lead spacer" branch needed elsewhere). */
private sealed class GuideCell {
    data class Prog(val program: LiveTvGuideProgram) : GuideCell()
    data class Gap(val minutes: Float) : GuideCell()
}

private fun buildGuideCells(programs: List<LiveTvGuideProgram>, originMs: Long): List<GuideCell> = buildList {
    var cursor = originMs
    for (p in programs) {
        if (p.startMs > cursor) add(GuideCell.Gap((p.startMs - cursor) / 60_000f))
        add(GuideCell.Prog(p))
        cursor = maxOf(cursor, p.endMs)
    }
}

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

                // Bug fix history — three rounds, each surfaced by the next: each channel's program row
                // used to scroll independently (dragging one row left every other row showing whatever
                // time slice it happened to be at, not one shared timeline). Fixed by anchoring every
                // row to the same guideOriginMs (the earliest program start across the whole grid) and
                // fanning drag deltas out to every row's LazyListState via scrollBy — which then
                // resurfaced as a subtler desync once the guide's range grew to 4h back/2 days forward:
                // rows with many short programs (DRTV's ~15min TVA segments, each stretched to a 90dp
                // floor) accumulated MORE rendered pixels per real hour than rows with long programs
                // (DR1), so equal scrollBy deltas covered different amounts of real time per row. Fixed
                // by replacing per-row relative scrollBy with one canonical `viewportStartMinutes`
                // (minutes since guideOriginMs, the single source of truth for "what time is the left
                // edge showing") — each row (GuideChannelRow) computes, from its OWN programs list,
                // exactly which item + pixel-offset represents that real clock time and calls
                // `scrollToItem` to jump there directly, never an accumulated-and-clamped delta. That in
                // turn surfaced the root cause of the pixel-vs-time mismatch itself: real EPG scheduling
                // gaps between two programs on the same channel weren't rendered as space at all (see
                // buildGuideCells), and the 90dp stretch floor made a "minute" mean a different number of
                // pixels in different rows. Both are fixed now: every gap is its own spacer cell, and no
                // cell is ever wider than duration*PX_PER_MINUTE — so PX_PER_MINUTE means the exact same
                // thing in every row, and the hour ruler below lines up with all of them unconditionally.
                val guideOriginMs = remember(s.programs) { s.programs.minOfOrNull { it.startMs } ?: nowMs }
                val maxEndMs = remember(s.programs) { s.programs.maxOfOrNull { it.endMs } ?: (guideOriginMs + 3_600_000L) }
                val maxViewportMinutes = remember(guideOriginMs, maxEndMs) { ((maxEndMs - guideOriginMs) / 60_000f).coerceAtLeast(0f) }
                var viewportStartMinutes by remember { mutableFloatStateOf(0f) }
                val sharedScrollableState = rememberScrollableState { delta ->
                    viewportStartMinutes = (viewportStartMinutes - delta / PX_PER_MINUTE).coerceIn(0f, maxViewportMinutes)
                    delta
                }
                // Bug fix: D-pad LEFT/RIGHT used to rely on Compose's native "scroll the focused item
                // into view" behaviour on a per-program-cell focus target — that only scrolls the ONE
                // focused row's own LazyListState, never touches viewportStartMinutes, so every other
                // row froze in place (confirmed: worked correctly on mobile, where dragging a row
                // instead drives sharedScrollableState above, which every row already reacts to via its
                // own LaunchedEffect/scrollToItem — see the extensive fix history in the Loaded branch
                // below). Routes D-pad paging through that exact same canonical-state mechanism instead,
                // by a fixed amount so — per this file's own established lesson — a page means the same
                // real time regardless of which channel's cell widths happen to be focused.
                fun pageViewport(deltaMinutes: Float) {
                    viewportStartMinutes = (viewportStartMinutes + deltaMinutes).coerceIn(0f, maxViewportMinutes)
                }

                // User request: open on the start of the OLDEST currently-active program (across every
                // channel), with a 10min lead-in so its start isn't flush against the left edge — "now"
                // then sits close to (but not necessarily at) the left, depending on which channel's
                // current program started earliest. Capped at 1h before "now": a long-running program
                // (e.g. a 3h movie) shouldn't drag the whole guide back that far just because it's still
                // playing. Falls back to "now" itself if nothing is currently active in the fetched
                // window (shouldn't normally happen since the guide always spans "now").
                val oldestActiveStartMs = remember(s.programs, nowMs) {
                    val oldest = s.programs.filter { nowMs in it.startMs until it.endMs }.minOfOrNull { it.startMs } ?: nowMs
                    maxOf(oldest, nowMs - 3_600_000L)
                }
                LaunchedEffect(guideOriginMs, oldestActiveStartMs) {
                    viewportStartMinutes = ((oldestActiveStartMs - guideOriginMs) / 60_000f - 10f).coerceAtLeast(0f)
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
                    // User request: hour markers above the first channel row, scrolling in lockstep with
                    // every program row (same viewportStartMinutes/scrollableState every row reacts to).
                    GuideTimeRuler(
                        guideOriginMs = guideOriginMs,
                        maxEndMs = maxEndMs,
                        viewportStartMinutes = { viewportStartMinutes },
                        scrollableState = sharedScrollableState,
                    )
                    Spacer(Modifier.height(8.dp))
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
                                viewportStartMinutes = { viewportStartMinutes },
                                scrollableState = sharedScrollableState,
                                onTune = { onTuneChannel(ch) },
                                onPage = ::pageViewport,
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

/**
 * Hour-marker ruler shown once, above the channel list — user request, so the guide's time columns
 * have a clear "top of the hour" reference. Ticks start at the first ROUND hour at/after
 * [guideOriginMs] (there's nothing to show for the partial hour before that — the guide can't scroll
 * earlier than its own origin) and run every 60min through [maxEndMs]. Shares [scrollTarget] with
 * [GuideChannelRow] so both always land on the exact same clock position for a given
 * [viewportStartMinutes] — ticks are uniformly 60min wide (well over the 90dp min-width floor, so no
 * stretching/rounding mismatch versus program cells to account for).
 */
@Composable
private fun GuideTimeRuler(
    guideOriginMs: Long,
    maxEndMs: Long,
    viewportStartMinutes: () -> Float,
    scrollableState: ScrollableState,
) {
    val colors = RaviloTheme.colors
    val firstTickMs = remember(guideOriginMs) {
        val hourMs = (guideOriginMs / 3_600_000L) * 3_600_000L
        if (hourMs == guideOriginMs) hourMs else hourMs + 3_600_000L
    }
    val tickCount = remember(firstTickMs, maxEndMs) {
        (((maxEndMs - firstTickMs).coerceAtLeast(0L)) / 3_600_000L).toInt() + 1
    }
    val leadingGapMin = remember(firstTickMs, guideOriginMs) { ((firstTickMs - guideOriginMs) / 60_000L).coerceAtLeast(0L) }
    val itemWidthsMin = remember(tickCount, leadingGapMin) {
        buildList { if (leadingGapMin > 0) add(leadingGapMin.toFloat()); repeat(tickCount) { add(60f) } }
    }
    val listState = rememberLazyListState()
    // Bug fix: scrollTarget's offset is computed in the same dp-per-minute units as PX_PER_MINUTE
    // (used everywhere else via Modifier.width(X.dp), which Compose auto-converts to real device
    // pixels at layout time) — but LazyListState.scrollToItem's scrollOffset param wants real device
    // PIXELS directly, not dp. Confirmed live: rows needing a large intra-item offset (DR2, ~30min
    // into "Seneste nyt fra TVA") rendered ~30min later than the ruler/other rows on a ~2x-density
    // screen, while offset-0 cases (an item's own start, e.g. DR1's Wimbledon) were unaffected — this
    // only shows up once the offset itself is large enough to notice, exactly the density-vs-dp gap.
    val density = LocalDensity.current.density

    LaunchedEffect(listState, itemWidthsMin, density) {
        snapshotFlow(viewportStartMinutes).collectLatest { minutes ->
            val (idx, px) = scrollTarget(itemWidthsMin, minutes)
            listState.scrollToItem(idx, (px * density).toInt())
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width((CHANNEL_COL_WIDTH_DP + CHANNEL_COL_SPACER_DP).dp).fillMaxHeight())
        LazyRow(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().scrollable(scrollableState, Orientation.Horizontal),
        ) {
            if (leadingGapMin > 0) {
                item(key = "__lead") { Spacer(Modifier.width((leadingGapMin * PX_PER_MINUTE).dp).fillMaxHeight()) }
            }
            items(tickCount, key = { it }) { i ->
                Box(Modifier.width((60 * PX_PER_MINUTE).dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        formatGuideTime(firstTickMs + i * 3_600_000L),
                        color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideChannelRow(
    channel: LiveTvChannel,
    programs: List<LiveTvGuideProgram>,
    nowMs: Long,
    guideOriginMs: Long,
    viewportStartMinutes: () -> Float,
    scrollableState: ScrollableState,
    onTune: () -> Unit,
    onPage: (Float) -> Unit,
    channelFocusRequester: FocusRequester? = null,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()
    Row(modifier = Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
        // Sticky-ish channel column (not a true pinned-column grid — see the R177 status note on scope).
        // Bug fix: this is now the ONLY focusable target in the row (program cells below lost their own
        // dpadFocusable — see that comment) specifically so D-pad LEFT/RIGHT paging never has to decide
        // which program cell to move focus onto next: the channel box is never lazily virtualized away,
        // so it's a stable, always-focusable anchor regardless of how far the shared viewport pages.
        // Selecting any program cell always tuned this same channel anyway (never the specific program
        // — "you watch live, not the future slot"), so this loses no functionality.
        var chFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.width(CHANNEL_COL_WIDTH_DP.dp).fillMaxSize()
                .background(if (chFocused) colors.accentDim else colors.surface)
                .dpadFocusable(
                    focusRequester = channelFocusRequester,
                    onFocused = { chFocused = true }, onBlurred = { chFocused = false }, onSelect = onTune,
                    onLeft = { onPage(-PAGE_MINUTES) },
                    onRight = { onPage(PAGE_MINUTES) },
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // User request: channel logos alongside the name.
            val logoUrl = channel.logoUrl
            if (logoUrl != null) {
                RemoteImage(
                    url = logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(verticalArrangement = Arrangement.Center) {
                Text("${channel.number}", color = colors.textSecondary, fontSize = 11.sp)
                Text(channel.name, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
        Spacer(Modifier.width(CHANNEL_COL_SPACER_DP.dp))
        if (programs.isEmpty()) {
            Box(Modifier.fillMaxSize().background(colors.surfaceVariant), contentAlignment = Alignment.CenterStart) {
                Text(str("livetv.no_programs"), color = colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        } else {
            // Bug fix: real EPG data can have scheduling gaps between two programs on the same
            // channel — the old code only ever accounted for the gap BEFORE the first program (a
            // leading spacer), silently butting every subsequent program up against the previous one
            // regardless of any real gap between them. Confirmed live: DR2's row didn't line up with
            // the new hour ruler (its 14:00 program rendered well to the right of the ruler's "14:00"
            // mark) because of an unaccounted-for gap earlier in its schedule. buildGuideCells turns
            // every gap (including the leading one) into its own spacer cell, so the rendered layout
            // and scrollTarget's position math are always the same list, in the same order.
            val cells = remember(programs, guideOriginMs) { buildGuideCells(programs, guideOriginMs) }
            // Second bug fix: cells used to have a widthIn(min = 90.dp) floor so very short programs
            // stayed legible/tappable — but stretching a cell wider than duration*PX_PER_MINUTE means
            // this row now needs MORE pixels per real minute than every other row, permanently
            // shifting everything after that cell out of alignment with the ruler and other channels
            // (confirmed live: DRTV/DR Ramasjang's many short segments drifted them out of sync over a
            // long scroll — the very first version of this bug, before the gap fix above). Every cell
            // must render at exactly duration*PX_PER_MINUTE, no floor, so PX_PER_MINUTE means the same
            // thing in every row — a short program just renders as a narrow, possibly textless sliver,
            // same as a real EPG grid.
            val itemWidthsMin = remember(cells) {
                cells.map { cell ->
                    when (cell) {
                        is GuideCell.Gap -> cell.minutes
                        is GuideCell.Prog -> ((cell.program.endMs - cell.program.startMs) / 60_000L).coerceAtLeast(1L).toFloat()
                    }
                }
            }

            // Third bug fix: even with gap-aware cells and no width stretch, rows needing a large
            // intra-item scroll offset (DR2, ~100min into "Seneste nyt fra TVA" to reach 14:00) still
            // rendered later than the ruler on a real device (confirmed live, ~30min off on a ~2x
            // density screen) — offset-zero cases (an item's own start, e.g. DR1's Wimbledon) were
            // unaffected, exactly the signature of a dp-vs-pixel unit mismatch: scrollTarget's offset
            // is computed in the same dp-per-minute units as PX_PER_MINUTE (used everywhere else via
            // Modifier.width(X.dp), which Compose auto-converts to real device pixels at layout time),
            // but LazyListState.scrollToItem's scrollOffset param wants real device pixels directly.
            val density = LocalDensity.current.density

            LaunchedEffect(listState, itemWidthsMin, density) {
                snapshotFlow(viewportStartMinutes).collectLatest { minutes ->
                    val (idx, px) = scrollTarget(itemWidthsMin, minutes)
                    listState.scrollToItem(idx, (px * density).toInt())
                }
            }

            LazyRow(
                state = listState,
                // The shared scrollableState drives scrolling — a row's own drag gesture would fight
                // the canonical viewportStartMinutes and desync from the others. No inter-item spacing
                // here (unlike the old spacedBy(2.dp)) — any visible gap must be its own Gap cell so it
                // counts towards scrollTarget's position math; an unaccounted-for 2dp per boundary would
                // silently drift over a row with hundreds of cells, the same class of bug as the gap fix.
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().scrollable(scrollableState, Orientation.Horizontal),
            ) {
                itemsIndexed(cells, key = { i, cell ->
                    when (cell) { is GuideCell.Prog -> "${cell.program.channelId}-${cell.program.startMs}"; is GuideCell.Gap -> "gap-$i" }
                }) { _, cell ->
                    if (cell is GuideCell.Gap) {
                        Spacer(Modifier.width((cell.minutes * PX_PER_MINUTE).dp).fillMaxHeight())
                        return@itemsIndexed
                    }
                    val p = (cell as GuideCell.Prog).program
                    val isNow = nowMs in p.startMs until p.endMs
                    val minutes = ((p.endMs - p.startMs) / 60_000L).coerceAtLeast(1L).toInt()
                    // Bug fix: no longer its own dpadFocusable target — see the channel-box comment
                    // above. Selecting any cell always tuned this same channel regardless, so a program
                    // cell never needed independent focus; only the "now" background highlight (unrelated
                    // to focus) is real content here.
                    Box(
                        modifier = Modifier
                            .width((minutes * PX_PER_MINUTE).dp).fillMaxSize()
                            .background(if (isNow) colors.accentDim else colors.surfaceVariant, RoundedCornerShape(6.dp))
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
