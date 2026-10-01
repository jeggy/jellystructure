package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
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
import androidx.compose.ui.draw.drawBehind
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
    var focused by rememberFocusVisual()
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
    // R337 — a computer's button is the mockup's `.bt` / `.bt.pri`: 38 dp, ink on the page's colour for the primary
    // one and a faint plate for the rest; it does not grow or lift — a ring marks it for the keyboard.
    if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) {
        val primary = style == ButtonStyle.PRIMARY
        Box(
            modifier.dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true; onFocused() },
                onBlurred = { focused = false },
                onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
            ),
            contentAlignment = Alignment.Center,
            propagateMinConstraints = true,   // a caller's minimum width is the pill's (R295's rule)
        ) {
            Box(
                Modifier.height(38.dp).clip(buttonShape).background(if (primary) colors.text else colors.fg.copy(alpha = 0.10f))
                    .then(if (focused) Modifier.border(2.dp, colors.focusRing, buttonShape) else Modifier).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (primary) colors.background else colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = sora,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, softWrap = false)
            }
        }
        return
    }

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
        // R295 (FR-R295-4) — a caller's min width (the detail page's Play/Resume is widthIn(min = 200.dp))
        // reaches the VISIBLE pill. It used to stop at this invisible outer box, so a short label ("Play",
        // "Resume · S2E19") drew a narrow pill centred in a 200 dp slot: indented from the page's left
        // edge, with a gap before the next button, on the TV and the phone alike.
        propagateMinConstraints = true,
    ) {
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationY = liftPx
                shadowElevation = glowElevation.toPx()
                shape = buttonShape
                // R350 (FR-R350-8) — not clipped, so the focus ring can sit outside the pill; the fills below carry
                // the shape themselves.
                clip = false
                ambientShadowColor = colors.focusGlow
                spotShadowColor = colors.focusGlow
            }
            // R350 (FR-R350-8) — the design's `.btn.focused` ring, drawn OUTSIDE the button (a 2 dp ring, 3 dp off
            // it). The primary button's lit fill alone did not read as focus: there was no unfocused primary on screen
            // to compare it with, so the series page's Play looked the same before and after the first key.
            .then(if (focused) Modifier.drawBehind {
                val gap = 3.dp.toPx(); val w = 2.dp.toPx()
                drawRoundRect(
                    color = colors.focusRing,
                    topLeft = androidx.compose.ui.geometry.Offset(-gap - w / 2, -gap - w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width + 2 * gap + w, size.height + 2 * gap + w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx() + gap + w / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
                )
            } else Modifier)
            .then(
                when {
                    focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accent, buttonShape)
                    !focused && style == ButtonStyle.PRIMARY ->
                        Modifier.background(colors.accentDim, buttonShape)
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
            // Bug fix: an unbounded label inside a Row that runs short on remaining width (e.g. a
            // sibling with a very long, uncapped title) collapsed into a one-character-per-line,
            // near-unreadable sliver instead of overflowing gracefully — a button label must never
            // wrap; degrade to an ellipsis, never a vertical stack of letters.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
    }
}
