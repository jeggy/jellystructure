package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.tween
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
import androidx.compose.ui.focus.FocusRequester
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyRowEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
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
    /** R139: on a Back-return, scroll this row to the item with this key and focus it (the originating tile). */
    restoreItemKey: Any? = null,
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
    openPanel: (@Composable () -> Unit)? = null,
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

    // R139: a Back-return into a screen that retained its scroll re-composes this row; if it's the row the
    // user navigated from, scroll it to the originating tile and request focus there (once per entry). The
    // matching item's content receives `restoreFR` below.
    val restoreFR = remember { FocusRequester() }
    var restoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!restoredOnce && restoreItemKey != null && itemKey != null) {
            val idx = items.indexOfFirst { itemKey(it) == restoreItemKey }
            if (idx >= 0) {
                runCatching { listState.scrollToItem(idx) }
                runCatching { restoreFR.requestFocus() }
            }
            // R200 — onRestored() must fire whether or not the target was found: it's the only thing
            // that clears the caller's focusRowKey/focusItemKey, and a not-found item (this row's own
            // composable freshly recreated after scrolling out of the LazyColumn's window and back in,
            // most likely) used to leave that pointer dangling forever, permanently re-arming this row's
            // restoreItemKey on every future recomposition with nothing to resolve it.
            onRestored()
            restoredOnce = true
        }
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

    Column(modifier = modifier.fillMaxWidth().then(headerInViewModifier)) {
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
                        fontFamily = spaceGrotesk,
                        letterSpacing = (-0.3).sp,
                    )
                }
                if (seeAllLabel != null && onSeeAll != null) {
                    // Bug fix: this Text had no clickable/dpadFocusable modifier at all — onSeeAll was
                    // captured but never invoked, so "See All" / "TV Guide" links were dead on every
                    // platform (confirmed live: tapping "TV Guide" on the Home screen did nothing).
                    // dpadFocusable's onSelect covers both D-pad Enter and a pointer tap (see its doc).
                    var seeAllFocused by remember { mutableStateOf(false) }
                    Text(
                        text = seeAllLabel,
                        color = colors.accent,
                        fontSize = 15.sp,
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
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
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
        val itemSpacingPx = with(density) { RaviloDimens.itemSpacing.toPx() }
        LaunchedEffect(openAfterKey) {
            val key = openAfterKey ?: return@LaunchedEffect
            val info = listState.layoutInfo
            val tile = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
            val delta = focusDetailRowOpenTargetScrollDelta(
                openTileOffsetPx = tile.offset.toFloat(),
                openTileWidthPx = tile.size.toFloat(),
                widthScale = RaviloMotion.ROW_OPEN_WIDTH_SCALE,
                itemSpacingPx = itemSpacingPx,
                panelWidthPx = panelWidthPx,
                viewportEndPx = info.viewportEndOffset.toFloat(),
            )
            if (delta > 0f) {
                listState.animateScrollBy(delta, tween(RaviloMotion.ROW_OPEN_TWEEN_MS))
            }
        }

        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .then(if (rowFocusRequester != null) Modifier.focusRequester(rowFocusRequester) else Modifier)
                    .focusRestorer()
                    .heightIn(min = heldHeightDp)
                    .then(if (openAfterKey != null) Modifier.onSizeChanged { if (it.height > heldHeightPx) heldHeightPx = it.height } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                contentPadding = PaddingValues(
                    horizontal = raviloHPad,
                    vertical = RaviloDimens.trackPadV,
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
                        val fr = if (restoreItemKey != null && itemKey != null && key == restoreItemKey) restoreFR else null
                        itemContent(i, items[i], fr)
                    }
                    if (openPanel != null && key != null && key == openAfterKey) {
                        item(key = "__openpanel__$key") { openPanel() }
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
