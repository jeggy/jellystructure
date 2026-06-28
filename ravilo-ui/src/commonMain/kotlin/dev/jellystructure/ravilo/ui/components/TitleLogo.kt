package dev.jellystructure.ravilo.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

// R130: a dark blurred halo (symmetric — small offset, large blur) so the fallback title stays legible
// over ANY backdrop, bright or busy. The hero/detail equivalent of the R110 subtitle outline. The hero
// and detail already darken the lower third with a scrim gradient; this covers anything bleeding through.
private val TitleHalo = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 1f), blurRadius = 16f)

/**
 * R130: render a title's clearlogo when it loads, otherwise fall back to the title as text.
 *
 * The fallback fires both when [logoUrl] is null AND when the logo image fails to load — the common
 * case, because the server always supplies a logo *proxy* URL which 404s for titles that have no
 * clearlogo. The text carries a dark halo so it reads on top of any backdrop. Used by the home hero
 * carousel and the Movie/Series detail heroes so a missing logo never leaves a blank space.
 */
@Composable
fun TitleLogoOrText(
    logoUrl: String?,
    title: String,
    logoModifier: Modifier,
    fontSize: TextUnit = 34.sp,
    lineHeight: TextUnit = 40.sp,
    maxLines: Int = 2,
    logoAlignment: Alignment = Alignment.BottomStart,
) {
    // Reset the failure flag when the logo target changes (e.g. a hero slide advance reuses this slot).
    var failed by remember(logoUrl) { mutableStateOf(false) }

    if (logoUrl == null || failed) {
        Text(
            text = title,
            color = RaviloTheme.colors.text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Bold,
            fontFamily = SpaceGrotesk,
            letterSpacing = (-0.5).sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = TitleHalo),
        )
        return
    }

    val baseUrl = LocalServerBaseUrl.current
    val resolved = if (logoUrl.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$logoUrl" else logoUrl
    val ctx = LocalPlatformContext.current
    val request = remember(resolved) {
        ImageRequest.Builder(ctx).data(resolved).crossfade(RaviloMotion.ImageCrossfadeMs).build()
    }
    AsyncImage(
        model = request,
        contentDescription = title,
        modifier = logoModifier,
        contentScale = ContentScale.Fit,
        alignment = logoAlignment,
        onState = { st -> if (st is AsyncImagePainter.State.Error) failed = true },
    )
}
