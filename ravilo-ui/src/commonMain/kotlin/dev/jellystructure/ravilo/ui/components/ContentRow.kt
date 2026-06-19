package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/**
 * A titled, horizontally-scrolling content row.
 * Auto-scrolls to keep the focused item visible.
 *
 * @param title row label, null = no header
 * @param focusedIndex the currently focused child index (drives auto-scroll)
 * @param content the tiles / cards rendered inside the lazy row
 */
@Composable
fun ContentRow(
    title: String?,
    focusedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable (requestFocusAt: (Int) -> FocusRequester) -> Unit,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        listState.scrollToItem(focusedIndex.coerceAtLeast(0))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        // The row delegates item rendering to the caller via a simple factory approach
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 40.dp),
            ) {
                // content slot rendered inside caller — we provide an empty scope here
                // callers use the StaticContentRow variant or provide the items directly
            }
        }
    }
}

/**
 * Simpler variant: caller provides a list of composable items directly.
 * The row handles lazy display + auto-scroll by focusedIndex.
 */
@Composable
fun <T> StaticContentRow(
    title: String?,
    items: List<T>,
    focusedIndex: Int,
    modifier: Modifier = Modifier,
    itemKey: ((T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val colors = RaviloTheme.colors
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (items.isNotEmpty()) listState.scrollToItem(focusedIndex.coerceIn(0, items.lastIndex))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 40.dp),
        ) {
            items(items.size, key = if (itemKey != null) { i -> itemKey(items[i]) } else null) { i ->
                itemContent(i, items[i])
            }
        }
    }
}
