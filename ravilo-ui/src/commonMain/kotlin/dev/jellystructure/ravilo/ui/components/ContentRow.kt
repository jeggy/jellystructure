package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/**
 * Edge-based bring-into-view (R42): scroll only enough to reveal a tile that is partially off-screen;
 * never re-centre a tile that is already fully visible. The default spec re-centres on focus, which
 * made the whole row jump while the focus scale animated. Returns 0 when the item is fully in view.
 */
@OptIn(ExperimentalFoundationApi::class)
private val EdgeBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset
        val trailing = offset + size
        return when {
            leading < 0f -> leading                          // partially off the start → reveal
            trailing > containerSize -> trailing - containerSize  // partially off the end → reveal
            else -> 0f                                        // fully visible → don't move
        }
    }
}

/**
 * A titled horizontal rail: the caller supplies the item composables directly.
 *
 * Focus movement is native Compose traversal — items must not consume directional keys.
 * `Modifier.focusRestorer()` returns focus to the row's last-focused child (composing and
 * scrolling it into view) on re-entry. The [EdgeBringIntoViewSpec] keeps the focused child visible
 * without re-centring already-visible tiles (R42), so navigating between visible tiles doesn't jump.
 */
@Composable
fun <T> StaticContentRow(
    title: String?,
    items: List<T>,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    onSeeAll: (() -> Unit)? = null,
    itemKey: ((T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk

    Column(modifier = modifier.fillMaxWidth()) {
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
        CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeBringIntoViewSpec) {
            LazyRow(
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
