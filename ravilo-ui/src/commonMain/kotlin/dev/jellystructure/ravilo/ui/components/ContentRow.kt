package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.jellystructure.ravilo.ui.seams.PrefetchLazyRowEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
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
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk

    // R42/R45: reveal off-screen tiles minimally; never move a fully-visible one; reveal a
    // start-clipped tile at the row's left inset (trackPadH) so item 0 keeps its padding.
    val insetPx = with(LocalDensity.current) { RaviloDimens.trackPadH.toPx() }
    @OptIn(ExperimentalFoundationApi::class)
    val bringIntoViewSpec = remember(insetPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val leading = offset
                val trailing = offset + size
                return when {
                    leading < 0f -> leading - insetPx                     // clipped at start → reveal at the left inset
                    trailing > containerSize -> trailing - containerSize  // clipped at end → reveal
                    else -> 0f                                            // fully visible → don't move
                }
            }
        }
    }

    val listState = rememberLazyListState()
    if (urlResolver != null) {
        // R99: key on urlResolver too — a row-kind/tile-shape change swaps the resolver (poster↔backdrop)
        // while `items` stays the same object, so remember(items) alone would prefetch stale URLs and
        // guarantee a cache miss when the tiles render the new ones.
        val prefetchUrls = remember(items, urlResolver) { items.map { urlResolver(it).orEmpty() } }
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
                    .padding(horizontal = RaviloDimens.sectionPadH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-0.3).sp,
                )
                if (seeAllLabel != null && onSeeAll != null) {
                    Text(
                        text = seeAllLabel,
                        color = colors.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
        }
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(RaviloDimens.itemSpacing),
                contentPadding = PaddingValues(
                    horizontal = RaviloDimens.trackPadH,
                    vertical = RaviloDimens.trackPadV,
                ),
            ) {
                items(items.size, key = if (itemKey != null) { i -> itemKey(items[i]) } else null) { i ->
                    itemContent(i, items[i])
                }
            }
        }
    }
}
