package dev.jellystructure.ravilo.ui.focus

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * DEB-2204 (FR-1) — the one focus ring. It replaces the `.then(if (focused) Modifier.border(…) else Modifier)`
 * and `drawBehind`-behind-an-`if` rings that appeared in a single frame.
 *
 * - **The stroke is permanent.** The modifier is always in the chain; focus moves a 0..1 fraction, never the
 *   stroke's width, offset or shape and never the modifier itself (R338: a ring modifier that comes and goes
 *   breaks focus).
 * - **Timing:** [RaviloMotion.FOCUS_RING_MS] on [RaviloMotion.EASE], in both directions.
 * - **Draw-only (FR-3):** the fraction lives in a state read only inside [draw], so a fade invalidates the draw
 *   layer and never recomposes. The caller recomposes once when `focused` changes, as it did before.
 * - **Reduced motion (FR-4):** under [LocalReducedMotion] the fraction jumps to its end value in the same frame.
 * - **A phone** passes the `focused` of [rememberFocusVisual], which is false there, so nothing is drawn (R298).
 *
 * @param focused the target; read through [rememberFocusVisual] at the call site.
 * @param color the ring at full focus.
 * @param resting the stroke colour at rest — [Color.Transparent] (nothing drawn) for a plain ring.
 * @param restingWidth the resting stroke's width when it differs from [width] (a ghost button's 1 dp border
 *   under its 2 dp ring): the resting stroke is then always drawn and the focused one is drawn over it, only
 *   its alpha moving.
 * @param outset how far the stroke's OUTER edge sits outside the bounds; 0 = inside, as `Modifier.border`.
 * @param fillFocused / [fillResting] a fill drawn behind the content that eases between the two (a chip or
 *   primary button whose background changes on focus); unspecified = none.
 * @param over true draws the ring over the content (what `Modifier.border` does); false draws it behind.
 */
fun Modifier.focusRing(
    focused: Boolean,
    shape: Shape,
    color: Color,
    width: Dp = 2.dp,
    resting: Color = Color.Transparent,
    restingWidth: Dp = width,
    outset: Dp = 0.dp,
    fillFocused: Color = Color.Unspecified,
    fillResting: Color = Color.Unspecified,
    over: Boolean = true,
): Modifier = this then FocusRingElement(focused, shape, color, width, resting, restingWidth, outset, fillFocused, fillResting, over)

private data class FocusRingElement(
    val focused: Boolean,
    val shape: Shape,
    val color: Color,
    val width: Dp,
    val resting: Color,
    val restingWidth: Dp,
    val outset: Dp,
    val fillFocused: Color,
    val fillResting: Color,
    val over: Boolean,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(this)
    override fun update(node: FocusRingNode) = node.update(this)
    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
        properties["focused"] = focused
    }
}

private class FocusRingNode(private var spec: FocusRingElement) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    // 0 = resting, 1 = focused. Written by the tween's frames and read only in draw().
    private var fraction by mutableFloatStateOf(if (spec.focused) 1f else 0f)
    private var running: Job? = null

    override fun onAttach() {
        // A node that attaches already focused starts drawn; only a CHANGE eases.
        running?.cancel()
        fraction = if (spec.focused) 1f else 0f
    }

    fun update(next: FocusRingElement) {
        val moved = next.focused != spec.focused
        spec = next
        if (moved) retarget(if (next.focused) 1f else 0f)
        invalidateDraw()
    }

    private fun retarget(to: Float) {
        running?.cancel()
        if (currentValueOf(LocalReducedMotion)) {
            fraction = to
            return
        }
        val from = fraction
        running = coroutineScope.launch {
            animate(from, to, animationSpec = tween(RaviloMotion.FOCUS_RING_MS, easing = RaviloMotion.EASE)) { v, _ -> fraction = v }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = spec
        val f = fraction
        if (s.fillFocused.isSpecified && s.fillResting.isSpecified) {
            drawOutline(s.shape.createOutline(size, layoutDirection, this), lerp(s.fillResting, s.fillFocused, f), style = Fill)
        }
        if (!s.over) drawRing(s, f)
        drawContent()
        if (s.over) drawRing(s, f)
    }

    private fun ContentDrawScope.drawRing(s: FocusRingElement, f: Float) {
        val sameWidth = s.restingWidth == s.width
        if (s.resting.alpha > 0f) {
            val w = s.restingWidth.toPx()
            val c = if (sameWidth) lerp(s.resting, s.color, f) else s.resting
            stroke(s, c, w)
            if (sameWidth) return
        }
        if (f > 0f) stroke(s, s.color.copy(alpha = s.color.alpha * f), s.width.toPx())
    }

    private fun ContentDrawScope.stroke(s: FocusRingElement, c: Color, w: Float) {
        if (c.alpha <= 0f || w <= 0f) return
        // The stroke's centre line sits `d` inside the bounds: half the stroke in, less the outset, so
        // outset = 0 is exactly `Modifier.border` and outset = gap + w is the button's outer ring.
        val d = w / 2f - s.outset.toPx()
        when (val o = s.shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> drawRect(c, o.rect.topLeft + Offset(d, d), Size(o.rect.width - 2 * d, o.rect.height - 2 * d), style = Stroke(w))
            is Outline.Rounded -> {
                val r = o.roundRect
                drawRoundRect(
                    c,
                    topLeft = Offset(r.left + d, r.top + d),
                    size = Size(r.width - 2 * d, r.height - 2 * d),
                    cornerRadius = CornerRadius((r.topLeftCornerRadius.x - d).coerceAtLeast(0f), (r.topLeftCornerRadius.y - d).coerceAtLeast(0f)),
                    style = Stroke(w),
                )
            }
            is Outline.Generic -> drawPath(o.path, c, style = Stroke(w))
        }
    }
}

/**
 * DEB-2204 (FR-2) — scale and glow on [RaviloMotion.softSpring], drawn in a `graphicsLayer` the way `RaviloButton` does
 * it: the layer lambda is the only reader of the two animated values, so a spring never recomposes and never touches
 * layout (the old `.scale(if (focused) …)` jumped in one frame, and the old `.shadow` came and went with an `if`).
 * Put it where `.scale(…)` was: outermost, ahead of the size modifiers, so the layer is the whole control.
 * Under [LocalReducedMotion] both values are their end values in the first frame (FR-4).
 *
 * @param scale the focused scale; 1f at rest.
 * @param glow the focused shadow elevation; 0 at rest (0.dp = scale only).
 */
@Composable
fun Modifier.focusScaleGlow(focused: Boolean, scale: Float, glow: Dp, shape: Shape, glowColor: Color): Modifier {
    val reduced = LocalReducedMotion.current
    val toScale = if (focused) scale else 1f
    val toGlow = if (focused) glow else 0.dp
    val scaleSpec = remember { RaviloMotion.softSpring<Float>() }
    val glowSpec = remember { RaviloMotion.softSpring<Dp>() }
    val s: State<Float> = if (reduced) rememberUpdatedState(toScale) else animateFloatAsState(toScale, scaleSpec, label = "focusScale")
    val g: State<Dp> = if (reduced) rememberUpdatedState(toGlow) else animateDpAsState(toGlow, glowSpec, label = "focusGlow")
    return this.graphicsLayer {
        scaleX = s.value; scaleY = s.value
        shadowElevation = g.value.toPx()
        this.shape = shape
        // Not clipped: the ring can sit outside the control; the fills carry the shape themselves.
        clip = false
        ambientShadowColor = glowColor
        spotShadowColor = glowColor
    }
}
