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
import androidx.compose.ui.text.style.TextOverflow
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
// Bug fix: below this rendered cell width (~25min at PX_PER_MINUTE), the program cell drops its time
// range label entirely rather than wrapping/truncating it into unreadable fragments — see the call
// site's doc comment.
private const val TIME_LABEL_MIN_WIDTH_DP = 110f
// User request ("too many '...' shown"): below this width (~13min at PX_PER_MINUTE) even a single
// ellipsized word is a near-useless 2-3 letter fragment — e.g. a real DR Ramasjang block of ~10min
// segments rendered "Ma…", "Gal…" back to back. Below it the cell shows no text at all (just its
// colored/isNow fill), still focusable/selectable — the details overlay always has the full title.
private const val TEXT_MIN_WIDTH_DP = 56f

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
 * live elapsed progress). Selecting a program cell opens a details overlay for that specific
 * program (title/time/channel) with an explicit "Watch Live" action — selecting the channel column
 * itself still tunes immediately, matching the spec's own framing (§C1: "you watch live, not the
 * future slot") for that entry point.
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
    // Hoisted out of the Loaded branch (unlike most of its other state) so the details overlay below
    // — which must render on top of the AppBar, i.e. as a sibling declared after it — can both set it
    // (from inside Loaded) and read it (from outside).
    val firstChannelFR = remember { FocusRequester() }
    var selectedProgram by remember { mutableStateOf<Pair<LiveTvChannel, LiveTvGuideProgram>?>(null) }

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
                // below). Rather than intercepting LEFT/RIGHT (which would still need native focus
                // search to actually move between program cells), every cell reports its own start time
                // whenever it gains focus — native search still drives which cell focuses next, this
                // just keeps the shared canonical state (and therefore every other row) truthful about
                // where the newly-focused cell actually is.
                fun onCellFocused(startMinutes: Float) {
                    viewportStartMinutes = startMinutes.coerceIn(0f, maxViewportMinutes)
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
                                onCellFocused = ::onCellFocused,
                                onProgramSelect = { p -> selectedProgram = ch to p },
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
        selectedProgram?.let { (channel, program) ->
            ProgramDetailsOverlay(
                channel = channel,
                program = program,
                onWatchLive = {
                    selectedProgram = null
                    onTuneChannel(channel)
                },
                onDismiss = {
                    selectedProgram = null
                    // No per-cell FocusRequester exists to return to precisely (deliberately, to avoid
                    // one-per-lazy-item — see the channel-box comment on the same tradeoff); the first
                    // channel row is a stable, always-present fallback anchor.
                    runCatching { firstChannelFR.requestFocus() }
                },
            )
        }
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
    onCellFocused: (Float) -> Unit,
    onProgramSelect: (LiveTvGuideProgram) -> Unit,
    channelFocusRequester: FocusRequester? = null,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()
    Row(modifier = Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
        // Sticky-ish channel column (not a true pinned-column grid — see the R177 status note on scope).
        // Its own focus target, independent of the program cells below — selecting it tunes this
        // channel immediately (no details popup), a quick "just watch this channel" path alongside
        // the cells' richer per-program flow.
        var chFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.width(CHANNEL_COL_WIDTH_DP.dp).fillMaxSize()
                .background(if (chFocused) colors.accentDim else colors.surface)
                .dpadFocusable(
                    focusRequester = channelFocusRequester,
                    onFocused = { chFocused = true }, onBlurred = { chFocused = false }, onSelect = onTune,
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
                    val cellWidthDp = minutes * PX_PER_MINUTE
                    // Bug fix: short programs (a real, common case — e.g. DR Ramasjang's ~15min
                    // segments) render as narrow cells by design (no width floor — see the comment
                    // above buildGuideCells' caller on why one cell must never be wider than its own
                    // duration*PX_PER_MINUTE). Neither Text had line/overflow limits, so on a narrow
                    // cell both the time range and the title wrapped onto several lines, breaking mid-
                    // word ("Gala"/"ktisk", "08:40"/"-08:5"/"0") instead of the "narrow, possibly
                    // textless sliver" already described (but not actually implemented) in the comment
                    // two screens up. Below TIME_LABEL_MIN_WIDTH_DP the time range (the less useful of
                    // the two — the ruler above already conveys roughly when a cell starts) is dropped
                    // entirely rather than wrapped or truncated into unreadable fragments; both texts
                    // are always capped to a single rendered line each with an ellipsis, never a wrap.
                    val showTimeLabel = cellWidthDp >= TIME_LABEL_MIN_WIDTH_DP
                    val showText = cellWidthDp >= TEXT_MIN_WIDTH_DP
                    // User request: each cell is focusable/selectable again (selecting opens the
                    // details overlay for this specific program — see onProgramSelect). LEFT/RIGHT is
                    // deliberately left to Compose's native focus search (moves to the adjacent cell
                    // and scrolls just this row into view, same as before the sync bug fix) —
                    // onCellFocused below is what keeps every OTHER row's viewport truthful about
                    // wherever native search lands, without needing to hand-roll the cell-to-cell
                    // traversal ourselves.
                    var pFocused by remember { mutableStateOf(false) }
                    val startMinutes = remember(p.startMs, guideOriginMs) { (p.startMs - guideOriginMs) / 60_000f }
                    Box(
                        modifier = Modifier
                            .width(cellWidthDp.dp).fillMaxSize()
                            .background(if (isNow) colors.accentDim else colors.surfaceVariant, RoundedCornerShape(6.dp))
                            .then(if (pFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(6.dp)) else Modifier)
                            .dpadFocusable(
                                onFocused = { pFocused = true; onCellFocused(startMinutes) },
                                onBlurred = { pFocused = false },
                                onSelect = { onProgramSelect(p) },
                            )
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        Column {
                            if (showText) {
                                if (showTimeLabel) {
                                    Text(
                                        "${formatGuideTime(p.startMs)}–${formatGuideTime(p.endMs)}",
                                        color = colors.textDim, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    p.name, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    maxLines = if (showTimeLabel) 2 else 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
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

/**
 * User request — selecting a program cell opens this instead of tuning directly: title/time/channel
 * plus an explicit "Watch Live" action, so browsing the guide with the D-pad doesn't blow past a
 * program you meant to look at and immediately jump the channel. Back dismisses without tuning.
 */
@Composable
private fun ProgramDetailsOverlay(
    channel: LiveTvChannel,
    program: LiveTvGuideProgram,
    onWatchLive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val watchLiveFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { watchLiveFR.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .dpadFocusable(onBack = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logoUrl = channel.logoUrl
                if (logoUrl != null) {
                    RemoteImage(
                        url = logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("${channel.number}  ${channel.name}", color = colors.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(program.name, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatGuideTime(program.startMs)}–${formatGuideTime(program.endMs)}",
                color = colors.textDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))
            var wlFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .background(if (wlFocused) colors.accent else colors.accentDim, RoundedCornerShape(10.dp))
                    .dpadFocusable(
                        focusRequester = watchLiveFR,
                        onFocused = { wlFocused = true }, onBlurred = { wlFocused = false },
                        onSelect = onWatchLive,
                    )
                    .padding(horizontal = 22.dp, vertical = 13.dp),
            ) {
                Text(
                    str("livetv.watch_live"),
                    color = if (wlFocused) colors.onAccent else colors.text,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
