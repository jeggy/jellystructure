package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

private class BrandFill(val colors: List<Color>)

private fun parseSolidColor(s: String): Color? {
    val stripped = s.trim().removePrefix("#")
    val hex = when (stripped.length) {
        3 -> buildString { stripped.forEach { append(it).append(it) } } // #abc -> aabbcc
        6, 8 -> stripped
        else -> return null
    }
    return try {
        val argb = when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toULong().toLong()
            8 -> hex.toLong(16)
            else -> return null
        }
        Color(argb)
    } catch (_: NumberFormatException) {
        null
    }
}

/**
 * Parse a channel `brandColor` CSS fill into ordered stop colors. Accepts a solid hex
 * (`#rgb`/`#rrggbb`/`#rrggbbaa`) or a single `linear-gradient(<deg>, <c1>, <c2>)`; the angle token is
 * ignored (the card uses a fixed diagonal). Returns null for anything unparseable so the caller falls
 * back to the theme accent.
 */
private fun parseBrandFill(brandColor: String?): BrandFill? {
    val t = brandColor?.trim() ?: return null
    if (t.startsWith("linear-gradient", ignoreCase = true)) {
        val inner = t.substringAfter('(').substringBeforeLast(')')
        val colors = inner.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("deg") && !it.startsWith("to ") }
            .mapNotNull { parseSolidColor(it) }
        return if (colors.isNotEmpty()) BrandFill(colors) else null
    }
    return parseSolidColor(t)?.let { BrandFill(listOf(it)) }
}

@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    brandColor: String?,
    focusRequester: FocusRequester? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val scale          by animateFloatAsState(if (focused) 1.08f else 1f, focusSpec, label = "channelScale")
    val borderWidth    by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "channelBorder")
    val shadowElevation by animateDpAsState(if (focused) 22.dp else 0.dp, dpSpec, label = "channelShadow")

    val cardShape = remember { RoundedCornerShape(18.dp) }
    val brandFill   = remember(brandColor) { parseBrandFill(brandColor) }
    val accentColor = brandFill?.colors?.firstOrNull() ?: colors.accent
    val glowColor   = remember(accentColor) { accentColor.copy(alpha = 0.55f) }

    // Channel-button background. A gradient brandColor (e.g. "linear-gradient(135deg,#3b2a78,#15102e)")
    // renders its own stops; a solid keeps the subtle accent→card wash. Diagonal via POSITIVE_INFINITY
    // (density-independent); the authored angle is approximated to the card diagonal.
    val cardGradient = remember(brandFill, accentColor, colors.card) {
        val stops = if (brandFill != null && brandFill.colors.size >= 2) brandFill.colors
                    else listOf(accentColor.copy(alpha = 0.28f), colors.card)
        Brush.linearGradient(
            colors = stops,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }
    // Subtle sheen overlay
    val sheenGradient = remember {
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }

    Box(
        modifier = Modifier
            .size(186.dp, 104.dp)
            .scale(scale)
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                clip = false,
                ambientColor = glowColor,
                spotColor = glowColor,
            )
            .clip(cardShape)
            .background(cardGradient)
            .border(borderWidth, accentColor.copy(alpha = 0.7f), cardShape)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Sheen
        Box(modifier = Modifier.fillMaxSize().background(sheenGradient))

        // Watermark text (bottom-start, behind logo)
        Text(
            text = name.take(8).uppercase(),
            color = Color.White.copy(alpha = 0.06f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = spaceGrotesk,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 8.dp),
        )

        if (logoUrl != null) {
            // R39: the logo fills the whole button (ContentScale.Crop), clipped by the card's rounded
            // corners — no padding/letterboxing. A transparent logo shows the brand fill behind it.
            RemoteImage(
                url = logoUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
