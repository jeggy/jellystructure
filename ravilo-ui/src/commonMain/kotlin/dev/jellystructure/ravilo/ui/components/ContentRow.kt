package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.theme.raviloItemSpacing
import dev.jellystructure.ravilo.ui.theme.raviloRowHeadPadB
import dev.jellystructure.ravilo.ui.theme.raviloTrackPadV
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyRowEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.fallbackIndex
import dev.jellystructure.ravilo.ui.focus.requestFocusAwaiting
import dev.jellystructure.ravilo.ui.focus.focusDetailPanelAvailableWidthPx
import dev.jellystructure.ravilo.ui.focus.focusDetailPanelClampedWidthPx
import dev.jellystructure.ravilo.ui.focus.focusDetailRowOpenTargetScrollDelta
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.launch

/**
 * A titled horizontal rail: the caller supplies the item composables directly.
 *
 * Focus movement is native Compose traversal — items must not consume directional keys.
 * `Modifier.focusRestorer()` returns focus to the row's last-focused child (composing and
 * scrolling it into view) on re-entry. The bring-into-view spec keeps the focused child visible
 * without re-centring already-visible tiles (R42); a tile that is **clipped at the start** is
 * revealed at the row's `trackPadH` left inset rather than flush to the edge, so returning to the
 * first item restores the original left padding (R45).
 */
@Composable
fun <T> StaticContentRow(
    title: String?,
    items: List<T>,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    onSeeAll: (() -> Unit)? = null,
    itemKey: ((T) -> Any)? = null,
    /** R88: supply a URL per item to warm Coil's cache ahead of the scroll position. */
    urlResolver: ((T) -> String?)? = null,
    /**
     * R108: bring the whole row (title + tiles) into view on focus. Pass `false` on screens whose
     * vertical bring-into-view spec already reserves a top inset for the title (Home, R65): there this
     * is a redundant *second* scroll that competes with the focused tile's native bring-into-view and
     * janks fast up/down navigation. Screens without a top-inset spec (Discover/Channel) keep it true.
     */
    bringRowHeaderIntoView: Boolean = true,
    /** R139: on a Back-return, scroll this row to the item with this key and focus it (the originating tile).
     *  R361: the caller passes the key already resolved (`resolveReturn`); the restore fires whenever it changes
     *  to a non-null key, so a caller can also aim it at a row that is already composed. */
    restoreItemKey: Any? = null,
    /** R361 (FR-R361-1) — where [restoreItemKey] sat when it was selected; used only if the key is no longer in
     *  [items] by the time the restore runs ([fallbackIndex]'s tile instead). */
    restoreItemIndex: Int? = null,
    /** Bug fix: called once the [restoreItemKey] restore actually fires, so the caller can consume it
     *  (null out whatever store field produced it) — without this, a row that's scrolled out of the
     *  LazyColumn's composition window and back in gets a BRAND NEW `restoredOnce` (it's plain local
     *  `remember` state on a disposed-and-recreated composable), so as long as the store still points
     *  at this row/item — which stays true well after the original Back-return, since nothing else ever
     *  clears it — every subsequent scroll-past-and-back-into-view fires the restore again, yanking
     *  focus back into this row out of nowhere. Reported live as focus "getting stuck"/jumping while
     *  navigating. Callers should clear their `focusRowKey`/`focusItemKey` here. */
    onRestored: () -> Unit = {},
    /** An extra, non-[T] tile rendered before [items] (e.g. Home's "Open TV Guide" entry point ahead of
     *  the On Now channel tiles) — its own focus target, unrelated to [itemKey]/[restoreItemKey]. */
    leadingItem: (@Composable () -> Unit)? = null,
    /** R187 — the mirror of [leadingItem], rendered AFTER [items]: a real tile in the row's own focus
     *  track (e.g. a "→ See all" card), not a header-level action link. Use this instead of
     *  [seeAllLabel]/[onSeeAll] when the destination should read as one more thing in the row, not a
     *  page-level action — [seeAllLabel] stays for genuinely link-shaped affordances elsewhere. */
    trailingItem: (@Composable () -> Unit)? = null,
    /** A small non-focusable accessory before [title] (e.g. Home On Now's live-dot). */
    titleAccessory: (@Composable () -> Unit)? = null,
    /** A plain, non-interactive label at the header's end — shown only when [seeAllLabel]/[onSeeAll]
     *  aren't (they're mutually exclusive with this: a row has an action link OR an info caption,
     *  never a visual double-up). e.g. Home On Now's "5 channels". */
    trailingInfo: String? = null,
    /** R361 (FR-R361-3) — the focused tile left this row in a refresh and nothing is left to focus here (the row
     *  is now empty): the caller resolves "the row now in its place". */
    onEmptiedWhileFocused: () -> Unit = {},
    /** R361 — told the index of the tile that takes focus (the caller keeps it, for "the row now in its place"). */
    onItemFocused: (index: Int) -> Unit = {},
    /**
     * R236 — an optional cross-screen bridge target (e.g. Home's hero-down / app-bar-down jump into
     * "whichever row is first"), attached to the LazyRow ITSELF via [focusRequester] + [focusRestorer],
     * never to a specific item inside it. The previous shape — a plain [FocusRequester] attached to
     * item index 0 — died the moment that item scrolled out of the LazyRow's composed window (R139's
     * own Back-return restore does exactly this by scrolling to whichever tile the viewer opened), so
     * Down from the hero went permanently dead as soon as a viewer had gone right into any row and
     * come back. A row-level requester's target is the row container, which stays composed for as
     * long as the row itself is on screen — [focusRestorer] then lands on whichever child was last
     * focused (or the first, on a fresh entry with no history), including a currently-disposed one.
     */
    rowFocusRequester: FocusRequester? = null,
    /** Phase R240 (FR-R240-3) — J's inert panel is inserted as one more row item, immediately after
     *  the tile whose [itemKey] equals [openAfterKey]. Null ⇒ no panel anywhere in this row (the
     *  household direction isn't "rowOpen", or nothing here is focused/settled yet). */
    openAfterKey: Any? = null,
    /** R250 (FR-R250-4) — receives the widest the panel may be given where the opening tile actually
     *  landed (≤ [openPanelWidth]); the caller clamps its panel to it. */
    openPanel: (@Composable (maxWidth: Dp) -> Unit)? = null,
    /** The width [openPanel] will lay out at. Supplied rather than measured: the panel is spliced in
     *  to the RIGHT of a tile that is often already at the viewport's edge, so its slot lands
     *  off-screen — and a `LazyRow` never *places* an off-screen item, so `onGloballyPositioned` never
     *  fires for it. Measuring it in order to know how far to scroll it into view is therefore
     *  circular. The caller derives this from the row's own tile variant (`focusDetailPanelWidthFor`),
     *  so this row and the panel it scrolls always agree on one number. */
    openPanelWidth: Dp = 0.dp,
    /** FR-R240-10 — a SECOND, independent panel slot for the tile that just stopped being open, so a
     *  lateral hop from one open tile straight to another in the same row gets a real two-panel
     *  crossfade (the old one shrinking out on its own exit tween, the new one expanding in) instead of
     *  the old panel node just being reassigned mid-tween with no exit animation. Null ⇒ nothing is
     *  currently closing. Ignored if it equals [openAfterKey] (never render the same tile's panel twice). */
    closingAfterKey: Any? = null,
    closingPanel: (@Composable () -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T, focusRequester: FocusRequester?) -> Unit,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk

    // R42/R45: reveal off-screen tiles minimally; never move a fully-visible one; reveal a
    // start-clipped tile at the row's left inset (trackPadH) so item 0 keeps its padding.
    val insetPx = with(LocalDensity.current) { raviloHPad.toPx() }
    @OptIn(ExperimentalFoundationApi::class)
    val bringIntoViewSpec = remember(insetPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailing = offset + size
                return when {
                    offset < 0f -> offset - insetPx                       // clipped at start → reveal at the left inset
                    trailing > containerSize -> trailing - containerSize  // clipped at end → reveal
                    else -> 0f                                            // fully visible → don't move
                }
            }
        }
    }

    val listState = rememberLazyListState()

    // R248 (FR-R248-3) — focus follows the item, not the index. A silent refresh may reorder this row
    // (Continue Watching after a stop) or drop the focused title from it. Keyed items already keep the
    // focused tile focused when it merely moves; this scrolls it back into view if the move took it out.
    // `rowFocused` is read *during* the composition that applied the new items — before the removed
    // tile's detach clears focus — so the effect knows whether this row owned focus at the swap.
    //
    // R361 (FR-R361-3, amends FR-R248-3) — when the title is gone, focus goes to the tile now at its
    // position (`fallbackIndex`), not to index 0, through a requester on THAT item (never a fixed lazy-item-0
    // requester, which is not composed when the row is scrolled right — the R200/R236 pattern), scrolled into
    // view first, then requested with the bounded retry. An emptied row hands off to the caller.
    var rowFocused by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<Any?>(null) }
    val targetFR = remember { FocusRequester() }
    var targetKey by remember { mutableStateOf<Any?>(null) }
    val prevItems = remember { mutableStateOf(items) }
    val hadFocusAtSwap = rowFocused
    val lead = if (leadingItem != null) 1 else 0
    LaunchedEffect(items) {
        val prev = prevItems.value
        prevItems.value = items
        val key = focusedKey ?: return@LaunchedEffect
        if (itemKey == null || !hadFocusAtSwap || prev === items) return@LaunchedEffect
        val idx = items.indexOfFirst { itemKey(it) == key }
        if (idx < 0) {
            focusedKey = null
            val prevIdx = prev.indexOfFirst { itemKey(it) == key }
            val to = fallbackIndex(prevIdx.coerceAtLeast(0), items.size)
            if (to == null) { onEmptiedWhileFocused(); return@LaunchedEffect }
            val toKey = itemKey(items[to])
            targetKey = toKey
            withFrameNanos { }
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == toKey }) {
                runCatching { listState.scrollToItem(to + lead) }
            }
            targetFR.requestFocusAwaiting()
            targetKey = null
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
            runCatching { listState.scrollToItem(idx + lead) }
        }
    }

    // R139: a Back-return into a screen that retained its scroll re-composes this row; if it's the row the
    // user navigated from, scroll it to the originating tile and request focus there. The matching item's
    // content receives `restoreFR` below.
    // R361 (dev review item 6) — keyed on the key (the caller clears it through [onRestored] once it fired, so a
    // row scrolled out of the column and back in does not fire it again); the row scrolls only if the tile is
    // not already on screen (FR-R361-2: the row moves only as far as needed), and the request awaits the layout
    // with the bounded retry rather than racing the scroll.
    val restoreFR = remember { FocusRequester() }
    var restoreKeyNow by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(restoreItemKey) {
        if (restoreItemKey == null || itemKey == null) return@LaunchedEffect
        var idx = items.indexOfFirst { itemKey(it) == restoreItemKey }
        if (idx < 0 && restoreItemIndex != null) idx = fallbackIndex(restoreItemIndex, items.size) ?: -1
        if (idx >= 0) {
            val key = itemKey(items[idx])
            restoreKeyNow = key
            withFrameNanos { }
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                runCatching { listState.scrollToItem(idx + lead) }
            }
            restoreFR.requestFocusAwaiting()
        }
        // R200 — onRestored() must fire whether or not the target was found: it's what clears the caller's
        // pending restore, and a not-found item used to leave that pointer dangling forever.
        onRestored()
    }

    if (urlResolver != null) {
        // R99: key on urlResolver too — a row-kind/tile-shape change swaps the resolver (poster↔backdrop)
        // while `items` stays the same object, so remember(items) alone would prefetch stale URLs and
        // guarantee a cache miss when the tiles render the new ones.
        // A leadingItem occupies real LazyRow position 0, shifting every items[] tile's actual position
        // by one — prepend a blank placeholder (PrefetchEffect already skips blank URLs) so the index
        // lastVisibleIndex reports lines back up with this list instead of prefetching one item early.
        val prefetchUrls = remember(items, urlResolver, leadingItem != null) {
            val base = items.map { urlResolver(it).orEmpty() }
            if (leadingItem != null) listOf("") + base else base
        }
        PrefetchLazyRowEffect(listState = listState, urls = prefetchUrls)
    }

    // When any descendant gains focus, bring the *whole row* (title + tiles) into view so the
    // vertical LazyColumn scrolls to show the row header, not just the focused tile. R108: skipped
    // when [bringRowHeaderIntoView] is false (the spec's top inset already shows the title there).
    @OptIn(ExperimentalFoundationApi::class)
    val rowBIVR = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val headerInViewModifier = if (bringRowHeaderIntoView) {
        Modifier.bringIntoViewRequester(rowBIVR)
            .onFocusChanged { if (it.hasFocus) scope.launch { rowBIVR.bringIntoView() } }
    } else Modifier

    Column(modifier = modifier.fillMaxWidth().onFocusChanged { rowFocused = it.hasFocus }.then(headerInViewModifier)) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = raviloHPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleAccessory != null) {
                        titleAccessory()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        // R250 (FR-R250-2) — the heading's own local backing: a halo in the page's own
                        // background colour. Invisible on plain `--bg` (same colour), a dark ground
                        // behind the letters while J's backdrop is lit under this row.
                        style = TextStyle(shadow = Shadow(color = colors.background.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 12f)),
                        fontFamily = spaceGrotesk,
                        letterSpacing = (-0.3).sp,
                    )
                }
                if (seeAllLabel != null && onSeeAll != null) {
                    // Bug fix: this Text had no clickable/dpadFocusable modifier at all — onSeeAll was
                    // captured but never invoked, so "See All" / "TV Guide" links were dead on every
                    // platform (confirmed live: tapping "TV Guide" on the Home screen did nothing).
                    // dpadFocusable's onSelect covers both D-pad Enter and a pointer tap (see its doc).
                    var seeAllFocused by rememberFocusVisual()
                    val deskMore = dev.jellystructure.ravilo.ui.theme.isDesktopLayout   // R337 — `.rh .more`
                    Text(
                        text = seeAllLabel,
                        color = if (deskMore) colors.textDim else colors.accent,
                        fontSize = if (deskMore) 12.5.sp else 15.sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (seeAllFocused) TextDecoration.Underline else TextDecoration.None,
                        modifier = Modifier.dpadFocusable(
                            onFocused = { seeAllFocused = true },
                            onBlurred = { seeAllFocused = false },
                            onSelect = onSeeAll,
                        ),
                    )
                } else if (trailingInfo != null) {
                    Text(text = trailingInfo, color = colors.textDim, fontSize = 13.sp, fontFamily = spaceGrotesk)
                }
            }
            Spacer(Modifier.height(raviloRowHeadPadB))
        }
        // Phase R240 (FR-R240-8) — while J holds an open panel in this row, the row's measured height
        // may only ever grow, never shrink back, until focus leaves the row entirely (openAfterKey
        // returns to null clears it below). Without this, closing the old tile's panel a frame before
        // the newly-focused tile's panel opens would let the row's natural (intrinsic) height sag back
        // to its resting size and immediately grow again — exactly the "pump" the spec calls out.
        var heldHeightPx by remember { mutableStateOf(0) }
        if (openAfterKey == null && heldHeightPx != 0) heldHeightPx = 0
        val heldHeightDp = with(LocalDensity.current) { heldHeightPx.toDp() }

        // J's panel needs room to its right, and the native per-tile bring-into-view above can't
        // provide it: that spec only ever knows about the FOCUSED TILE's own bounds, while the panel
        // is a separate `LazyRow` item spliced in after it (FR-R240-3). Without this, a tile focused
        // near the right edge opened a panel that rendered past the viewport entirely — invisible.
        //
        // Two earlier attempts, both wrong, both kept here because the reasons matter:
        //   1. Wait out the panel's own tween, THEN jump the scroll in one animated correction. Reads
        //      as two separate motions (grow, pause, slide) and, running on a clock independent of the
        //      tile's width tween, could momentarily clip the tile's poster while the two raced.
        //   2. Track the panel's measured right edge every frame and scroll incrementally. Smooth in
        //      principle, but it can only react to a panel that has actually been laid out — and the
        //      panel very often isn't. Device logging proved the real failure: after a fast sweep the
        //      dwell fires, `HomeScreen.kt`'s state machine transitions, and this composable genuinely
        //      composes the `FocusDetailPanel` node (all confirmed in logcat) — yet
        //      `onGloballyPositioned` fires exactly once with a pre-layout `0f` and never again, and
        //      the panel is never presented, while the app's own clock keeps ticking (so: not a freeze,
        //      not a data bug, not a dwell race — every one of those was tested and ruled out). A
        //      `LazyRow` never PLACES an item that falls outside its viewport, and this panel lands
        //      outside precisely when it most needs scrolling in. Measure-then-scroll is circular.
        //
        // So: compute the target from KNOWN geometry instead — the tile's current offset/width from
        // `layoutInfo`, its growth factor (FR-R240-7's own constant), and the panel's declared width
        // (the caller-supplied [openPanelWidth], from `focusDetailPanelWidthFor`) — and scroll on the
        // SAME tween the tile and panel animate on, so all three read as one motion. Nothing here
        // depends on the panel having been laid out first.
        val density = LocalDensity.current
        val panelWidthPx = with(density) { openPanelWidth.toPx() }
        val itemSpacingPx = with(density) { raviloItemSpacing.toPx() }
        // R250 (FR-R250-4) — 0 = no clamp (the declared width stands); set from the tile's measured
        // landing once the scroll has settled, and reset the moment another tile opens.
        var panelMaxWidthPx by remember { mutableStateOf(0f) }
        val panelMaxWidthDp = if (panelMaxWidthPx > 0f) with(density) { panelMaxWidthPx.toDp() } else openPanelWidth
        LaunchedEffect(openAfterKey) {
            panelMaxWidthPx = 0f
            val key = openAfterKey ?: return@LaunchedEffect
            val info = listState.layoutInfo
            val tile = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
            // R250 (FR-R250-5) — `viewportEndOffset` is the row's far edge net of its START padding only,
            // i.e. the screen edge in content space, not the end gutter. Targeting it let the panel end at
            // x = 1920 and left the opening tile short of the content start by exactly the end gutter
            // (the 66 px the stue TV measured, less the tile's own inset). The end gutter is the target.
            val endGutterPx = (info.viewportEndOffset - info.afterContentPadding).toFloat()
            val delta = focusDetailRowOpenTargetScrollDelta(
                openTileOffsetPx = tile.offset.toFloat(),
                openTileWidthPx = tile.size.toFloat(),
                widthScale = RaviloMotion.ROW_OPEN_WIDTH_SCALE,
                itemSpacingPx = itemSpacingPx,
                panelWidthPx = panelWidthPx,
                viewportEndPx = endGutterPx,
            )
            if (delta > 0f) {
                listState.animateScrollBy(delta, tween(RaviloMotion.ROW_OPEN_TWEEN_MS))
            }
            // FR-R250-4 — size the panel from where the tile ACTUALLY landed. Measuring here is not the
            // circularity R240 warns about (that was scrolling on a panel that is never placed until it
            // is scrolled to): the tile is always placed, and a clamp only ever narrows.
            val after = listState.layoutInfo
            val landed = after.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
            val grownPx = maxOf(landed.size.toFloat(), tile.size * RaviloMotion.ROW_OPEN_WIDTH_SCALE)
            val available = focusDetailPanelAvailableWidthPx(
                landedTileOffsetPx = landed.offset.toFloat(),
                grownTileWidthPx = grownPx,
                itemSpacingPx = itemSpacingPx,
                endGutterPx = (after.viewportEndOffset - after.afterContentPadding).toFloat(),
            )
            val clamped = focusDetailPanelClampedWidthPx(panelWidthPx, available, with(density) { 280.dp.toPx() })
            panelMaxWidthPx = if (clamped < panelWidthPx) clamped else 0f
        }

        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            ArrowRow(
                state = listState,
                modifier = Modifier
                    .then(if (rowFocusRequester != null) Modifier.focusRequester(rowFocusRequester) else Modifier)
                    .focusRestorer()
                    .heightIn(min = heldHeightDp)
                    .then(if (openAfterKey != null) Modifier.onSizeChanged { if (it.height > heldHeightPx) heldHeightPx = it.height } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(raviloItemSpacing),
                contentPadding = PaddingValues(
                    horizontal = raviloHPad,
                    vertical = raviloTrackPadV,
                ),
            ) {
                if (leadingItem != null) {
                    item(key = "__leading") { leadingItem() }
                }
                // Manual per-index items (rather than the items() builder) so the panel can be spliced
                // in right after whichever item's key matches openAfterKey — items() has no hook for
                // "one more item conditionally after index i" without this same unrolling underneath.
                for (i in items.indices) {
                    val key = itemKey?.invoke(items[i])
                    item(key = key) {
                        val fr = if (itemKey != null && key != null && key == (restoreKeyNow ?: restoreItemKey)) restoreFR else null
                        // R248 (FR-R248-3) — see `focusedKey` above; the wrapper adds no size of its own.
                        // R361 (FR-R361-3) — the refresh's target carries its own requester, keyed to its item.
                        Box(
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) { focusedKey = key; onItemFocused(i) } }
                                .then(if (key != null && key == targetKey) Modifier.focusRequester(targetFR) else Modifier),
                        ) { itemContent(i, items[i], fr) }
                    }
                    if (openPanel != null && key != null && key == openAfterKey) {
                        item(key = "__openpanel__$key") { openPanel(panelMaxWidthDp) }
                    }
                    // FR-R240-10: the closing slot renders at its own tile's position, distinct from
                    // the open slot above — the guard against `closingAfterKey == openAfterKey` stops a
                    // tile from ever getting two panel items on itself (e.g. the instant a hop lands on
                    // what was already the closing key from an even earlier hop).
                    if (closingPanel != null && key != null && key == closingAfterKey && closingAfterKey != openAfterKey) {
                        item(key = "__closingpanel__$key") { closingPanel() }
                    }
                }
                if (trailingItem != null) {
                    item(key = "__trailing") { trailingItem() }
                }
            }
        }
    }
}
