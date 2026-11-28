package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/** The two sizes of the one busy mark: beside other content, or alone. */
enum class WaitSize(val dp: Dp, val stroke: Dp) {
    Inline(14.dp, 2.dp),
    Alone(36.dp, 3.dp),
}

/** One busy mark for a wait that has no page shape (DEB-5837, FR-4). One colour, two sizes. */
@Composable
fun WaitIndicator(size: WaitSize = WaitSize.Inline, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        color = RaviloTheme.colors.textSecondary,
        strokeWidth = size.stroke,
    )
}
