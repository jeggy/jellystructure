package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    brandColor: Long?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, focusSpec, label = "channelScale")
    val borderWidth by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "channelBorder")
    val shadowElevation by animateDpAsState(if (focused) 18.dp else 0.dp, dpSpec, label = "channelShadow")
    val cardShape = remember { RoundedCornerShape(10.dp) }
    val accentColor = remember(brandColor, colors.accent) {
        if (brandColor != null) androidx.compose.ui.graphics.Color(brandColor) else colors.accent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(scale),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp, 70.dp)
                .shadow(shadowElevation, cardShape, clip = false, ambientColor = accentColor, spotColor = accentColor)
                .clip(cardShape)
                .background(colors.surfaceVariant)
                .border(borderWidth, accentColor, cardShape)
                .dpadFocusable(
                    focusRequester = focusRequester,
                    onFocused = { focused = true; onFocused() },
                    onBlurred = { focused = false },
                    onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (logoUrl != null) {
                RemoteImage(url = logoUrl, contentDescription = name, modifier = Modifier.padding(10.dp))
            } else {
                Text(
                    text = name,
                    color = if (focused) colors.text else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (logoUrl != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
