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

private fun parseBrandColor(hex: String?): Color? {
    if (hex == null) return null
    return try {
        val stripped = hex.trimStart('#')
        val argb = when (stripped.length) {
            6 -> (0xFF000000L or stripped.toLong(16)).toULong().toLong()
            8 -> stripped.toLong(16)
            else -> return null
        }
        Color(argb)
    } catch (_: NumberFormatException) {
        null
    }
}

@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    brandColor: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
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
    val brandColorParsed = remember(brandColor) { parseBrandColor(brandColor) }
    val accentColor = brandColorParsed ?: colors.accent
    val glowColor   = remember(accentColor) { accentColor.copy(alpha = 0.55f) }

    // Diagonal branded gradient using Float.POSITIVE_INFINITY for density-independent diagonal
    val cardGradient = remember(accentColor, colors.card) {
        Brush.linearGradient(
            colors = listOf(accentColor.copy(alpha = 0.28f), colors.card),
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
            .size(268.dp, 150.dp)
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
                onFocused = { focused = true; onFocused() },
                onBlurred = { focused = false },
                onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Sheen
        Box(modifier = Modifier.fillMaxSize().background(sheenGradient))

        // Watermark text (bottom-start, behind logo)
        Text(
            text = name.take(8).uppercase(),
            color = Color.White.copy(alpha = 0.06f),
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = spaceGrotesk,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 8.dp),
        )

        if (logoUrl != null) {
            RemoteImage(
                url = logoUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize().padding(24.dp),
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
