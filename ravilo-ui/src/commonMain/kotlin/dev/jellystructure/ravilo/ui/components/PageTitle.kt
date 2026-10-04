package dev.jellystructure.ravilo.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/**
 * R364 (FR-R364-4, dev review item 7) — a page's own title in the app's display face: Space Grotesk bold 28 (Search's,
 * which already matched the design's headings), 30 on a computer. Used by *My List*, *Settings* and every seeded
 * browse page (*Movies*, *Series*, a row's *See all*, a person, a genre, a Discover wall), so they read as one family.
 * Discover's own header keeps its 22 (it sits over the segment strip and is not a page title of this kind).
 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
    Text(
        text,
        color = RaviloTheme.colors.text,
        fontSize = if (desk) 30.sp else 28.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = SpaceGrotesk,
        letterSpacing = (-0.5).sp,
        modifier = modifier,
    )
}
