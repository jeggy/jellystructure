package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/**
 * Simpler variant: caller provides a list of composable items directly.
 * The row handles lazy display + smooth auto-scroll by focusedIndex.
 *
 * A `rowHasFocus` guard prevents the initial-composition scroll from firing
 * before the user has entered the row, which avoids a spurious jump to idx=0.
 */
@Composable
fun <T> StaticContentRow(
    title: String?,
    items: List<T>,
    focusedIndex: Int,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    onSeeAll: (() -> Unit)? = null,
    itemKey: ((T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk
    val listState = rememberLazyListState()
    var rowHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(focusedIndex) {
        if (!rowHasFocus || items.isEmpty()) return@LaunchedEffect
        val idx = focusedIndex.coerceIn(0, items.lastIndex)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == idx }
        when {
            // Not composed yet — snap into view
            item == null -> listState.scrollToItem(idx)
            // Right edge cut off — scroll forward just enough
            item.offset + item.size > info.viewportEndOffset ->
                listState.animateScrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
            // Left edge cut off — scroll backward just enough
            item.offset < info.viewportStartOffset ->
                listState.animateScrollBy((item.offset - info.viewportStartOffset).toFloat())
            // Fully visible — no scroll needed
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) rowHasFocus = true },
    ) {
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-0.3).sp,
                )
                if (seeAllLabel != null && onSeeAll != null) {
                    Text(
                        text = seeAllLabel,
                        color = colors.accent,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(RaviloDimens.rowHeadPadB))
        }
        LazyRow(
            state = listState,
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
