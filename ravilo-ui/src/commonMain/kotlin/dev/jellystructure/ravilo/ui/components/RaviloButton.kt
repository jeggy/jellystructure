package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

enum class ButtonStyle { PRIMARY, GHOST }

@Composable
fun RaviloButton(
    label: String,
    focusRequester: FocusRequester? = null,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    modifier: Modifier = Modifier,
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
    val focusSpec = remember { RaviloMotion.softSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.softSpring<Dp>() }
    val scale           by animateFloatAsState(if (focused) RaviloMotion.BUTTON_FOCUS_SCALE else 1f, focusSpec, label = "buttonScale")
    val glowElevation   by animateDpAsState(if (focused) 16.dp else 0.dp, dpSpec, label = "buttonShadow")
    // Lift: 4dp upward on focus (converted to px for graphicsLayer)
    val liftPx by animateFloatAsState(
        targetValue = if (focused) with(density) { -3.dp.toPx() } else 0f,
        animationSpec = focusSpec,
        label = "buttonLift",
    )
    val buttonShape = remember { RoundedCornerShape(10.dp) }
    val ghostBorderColor = remember(colors.textSecondary) { colors.textSecondary.copy(alpha = 0.4f) }

    // Focusable outer keeps a constant layout size; the scale, lift and glow run draw-only on the inner
    // layer so the actions row never chases the focus animation → no viewport jump (R42/R43, now on the
    // detail/hero buttons too).
    Box(
        modifier = modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { focused = true; onFocused() },
            onBlurred = { focused = false },
            onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
        ),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationY = liftPx
                shadowElevation = glowElevation.toPx()
                shape = buttonShape
                clip = true
                ambientShadowColor = colors.focusGlow
                spotShadowColor = colors.focusGlow
            }
            .then(
                when {
                    focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accent)
                    !focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accentDim)
                    focused ->
                        Modifier.border(2.dp, colors.accent, buttonShape)
                    else ->
                        Modifier.border(1.dp, ghostBorderColor, buttonShape)
                }
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
}
