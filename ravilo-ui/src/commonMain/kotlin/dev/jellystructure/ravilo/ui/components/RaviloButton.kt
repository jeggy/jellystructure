package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

enum class ButtonStyle { PRIMARY, GHOST }

@Composable
fun RaviloButton(
    label: String,
    focusRequester: FocusRequester? = null,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val scale           by animateFloatAsState(if (focused) 1.06f else 1f, focusSpec, label = "buttonScale")
    val shadowElevation by animateDpAsState(if (focused) 16.dp else 0.dp, dpSpec, label = "buttonShadow")
    // Lift: 4dp upward on focus (converted to px for graphicsLayer)
    val liftPx by animateFloatAsState(
        targetValue = if (focused) with(density) { -3.dp.toPx() } else 0f,
        animationSpec = focusSpec,
        label = "buttonLift",
    )
    val shape = remember { RoundedCornerShape(10.dp) }
    val ghostBorderColor = remember(colors.textSecondary) { colors.textSecondary.copy(alpha = 0.4f) }

    Box(
        modifier = Modifier
            .scale(scale)
            .graphicsLayer { translationY = liftPx }
            .shadow(shadowElevation, shape, clip = false, ambientColor = colors.focusGlow, spotColor = colors.focusGlow)
            .clip(shape)
            .then(
                when {
                    focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accent)
                    !focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accentDim)
                    focused ->
                        Modifier.border(2.dp, colors.accent, shape)
                    else ->
                        Modifier.border(1.dp, ghostBorderColor, shape)
                }
            )
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true; onFocused() },
                onBlurred = { focused = false },
                onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                style == ButtonStyle.PRIMARY -> colors.onAccent
                focused -> colors.text
                else -> colors.textSecondary
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = sora,
        )
    }
}
