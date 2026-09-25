package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/**
 * R303 — what the player says top right while the chrome is up: the film's or the series' clearlogo,
 * a series' name in text when it has none (or the logo failed to load), and nothing for a film with
 * none — its title already sits in the bottom block. One decision, shared by the TV chrome, the phone
 * chrome and the web app (the same commonMain code), tested without a composition.
 */
sealed class PlayerIdent {
    /** FR-R303-2/4 — the logo; [plate] when its ink is `dark` (Phase 232): a light plate behind it, never a recolour. */
    data class Logo(val url: String, val plate: Boolean) : PlayerIdent()
    /** FR-R303-3 — a series with no logo shows its name in text. */
    data class Name(val text: String) : PlayerIdent()
    /** FR-R303-3 — a film with no logo shows nothing here. */
    data object None : PlayerIdent()
}

/**
 * [seriesName] is non-null only for an episode (the detail's series title, or the play push's
 * `series_name`); [logoFailed] is the image loader's word that [logoUrl] did not load, which falls back
 * exactly as no logo does (FR-R303-3). [hidden] is FR-R303-6: the slot gives way when something else
 * names a TV in the top row (an AirPlay chip); nothing else hides it.
 */
fun playerIdent(
    logoUrl: String?,
    logoInk: String?,
    seriesName: String?,
    logoFailed: Boolean = false,
    hidden: Boolean = false,
): PlayerIdent = when {
    hidden -> PlayerIdent.None
    !logoUrl.isNullOrBlank() && !logoFailed -> PlayerIdent.Logo(logoUrl, plate = logoInk == "dark")
    !seriesName.isNullOrBlank() -> PlayerIdent.Name(seriesName)
    else -> PlayerIdent.None
}

private val IdentHalo = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 14f)
private val PlateColor = Color(0xF4F6FA).copy(alpha = 0.92f)

/**
 * R303 (FR-R303-1) — the slot itself. Its own composable, deliberately outside `PlayerScreen`'s body
 * (dev review item 5: the release dex guard sits at 239 of 250 there, and both release-only
 * `VerifyError`s this project shipped came from inlining chrome into it). The failed-load state lives
 * here and nowhere else (item 6). Sizes are the caller's: TV 200 × 42 dp (400 × 84 px on the 1920 frame),
 * phone 112 × 36 dp.
 */
@Composable
fun PlayerIdentSlot(
    logoUrl: String?,
    logoInk: String?,
    seriesName: String?,
    maxWidth: Dp,
    maxHeight: Dp,
    nameFontSize: TextUnit,
    nameLineHeight: TextUnit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    when (val ident = playerIdent(logoUrl, logoInk, seriesName, logoFailed = failed, hidden = hidden)) {
        PlayerIdent.None -> Unit
        is PlayerIdent.Name -> Text(
            text = ident.text,
            color = Color.White,
            fontSize = nameFontSize,
            lineHeight = nameLineHeight,
            fontWeight = FontWeight.Bold,
            fontFamily = SpaceGrotesk,
            letterSpacing = (-0.4).sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = IdentHalo),
            modifier = modifier.widthIn(max = maxWidth),
        )
        is PlayerIdent.Logo -> {
            val baseUrl = LocalServerBaseUrl.current
            val resolved = if (ident.url.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl${ident.url}" else ident.url
            val ctx = LocalPlatformContext.current
            val request = remember(resolved) {
                ImageRequest.Builder(ctx).data(resolved).crossfade(RaviloMotion.IMAGE_CROSSFADE_MS).build()
            }
            // FR-R303-4 — a dark-ink logo sits on a light plate (the logo itself is ~3/4 of the slot then).
            val plateMod = if (ident.plate) Modifier.background(PlateColor, RoundedCornerShape(maxHeight / 4)).padding(horizontal = maxHeight / 4, vertical = maxHeight / 7) else Modifier
            val logoMax = if (ident.plate) maxHeight * 0.76f else maxHeight
            Box(modifier.then(plateMod), contentAlignment = Alignment.CenterEnd) {
                AsyncImage(
                    model = request,
                    contentDescription = seriesName,
                    modifier = Modifier.sizeIn(maxWidth = if (ident.plate) maxWidth * 0.9f else maxWidth, maxHeight = logoMax),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd,
                    onState = { st -> if (st is AsyncImagePainter.State.Error) failed = true },
                )
            }
        }
    }
}

/** The TV chrome's slot size (FR-R303-1: 400 × 84 px on the 1920 frame; the TV composes at 960 × 540 dp). */
val TV_IDENT_MAX_WIDTH: Dp = 200.dp
val TV_IDENT_MAX_HEIGHT: Dp = 42.dp
/** The phone chrome's slot size (FR-R303-1: 112 × 36 dp, immediately left of the cast glyph). */
val PHONE_IDENT_MAX_WIDTH: Dp = 112.dp
val PHONE_IDENT_MAX_HEIGHT: Dp = 36.dp
