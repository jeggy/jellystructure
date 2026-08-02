package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.TrailerEmbed
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.TvTrailer

/**
 * R163 — fullscreen embedded YouTube/Vimeo trailer, chrome-matched to the real media player.
 * Opened from the Movie/Series detail hero's Trailer button (gated on [TvTrailer] being present,
 * Phase 130); the provider embed is a bounded exception to Media3/ExoPlayer (no direct byte stream).
 *
 * The surface owns input while open: Back **or** Select-on-Close exits and restores focus to the
 * Trailer button (the caller's [onClose]); D-pad up/down/left/right are swallowed by having exactly
 * one focusable target, matching the design's "single control" model.
 */
@Composable
fun TrailerOverlay(trailer: TvTrailer, title: String, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val closeFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFR.requestFocus() } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        TrailerEmbed(site = trailer.site, key = trailer.key, modifier = Modifier.fillMaxSize())

        val topGradient = remember(colors.background) {
            Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.92f),
                1f to colors.background.copy(alpha = 0f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topGradient)
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                str("action.trailer").uppercase(),
                color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, fontFamily = sora,
                maxLines = 1, softWrap = false,
            )
            Box(Modifier.weight(1f, fill = false).padding(start = 12.dp)) {
                val name = trailer.name?.takeIf { it.isNotBlank() }
                Text(
                    if (name != null) "$title · $name" else title,
                    color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = spaceGrotesk,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (trailer.site.equals("vimeo", ignoreCase = true)) "Vimeo" else "YouTube",
                    color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    fontFamily = sora,
                    maxLines = 1, softWrap = false,
                )
            }
            Box(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .dpadFocusable(focusRequester = closeFR, onSelect = onClose, onBack = onClose)
                    .background(colors.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "✕ ${str("action.close")}",
                    color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = sora,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
