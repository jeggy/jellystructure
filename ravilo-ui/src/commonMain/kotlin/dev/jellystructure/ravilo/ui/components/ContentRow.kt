package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/**
 * A titled horizontal rail: the caller supplies the item composables directly.
 *
 * Focus movement is native Compose traversal — items must not consume directional keys.
 * `Modifier.focusRestorer()` returns focus to the row's last-focused child (composing and
 * scrolling it into view) on re-entry, and the framework keeps the focused child visible, so
 * no manual scroll-to-focus is needed.
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
