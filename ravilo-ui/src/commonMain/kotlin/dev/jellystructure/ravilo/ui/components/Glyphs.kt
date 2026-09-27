package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * R315 (FR-R315-1) — the UI's icons, drawn rather than typed.
 *
 * Ravilo used to draw these as text characters (✓ ✕ ★ ︿ ﹀ ▲ ▼ ◂ ▸ ▷ ○ ⚙ ✎ ✉ 🌐 ⇧ ▦ ↓). Android fills a
 * character its bundled fonts lack from the phone's system fonts, so they showed there; Compose on the
 * web has no system fonts and drew each as a replacement box. Drawn on a Canvas, the same icon shows on
 * every platform (the way PlayPauseGlyph and SkipGlyph already are). Each takes a tint and a size; the
 * size is the square the character used to occupy at that font size. [description] is set only where the
 * glyph is the whole content of a control (FR-R315-5).
 *
 * Symbols inside translated sentences stay text (translators write them); the web draws those from the
 * bundled fallback font (FR-R315-2).
 */

enum class GlyphDirection { UP, DOWN, LEFT, RIGHT }

@Composable
private fun GlyphCanvas(size: Dp, description: String?, modifier: Modifier, draw: DrawScope.() -> Unit) {
    val m = modifier.size(size).let { if (description != null) it.semantics { contentDescription = description } else it }
    Canvas(m) { draw() }
}

private fun DrawScope.px(f: Float) = size.minDimension * f
private fun DrawScope.at(x: Float, y: Float) = Offset(size.width * x, size.height * y)
private fun DrawScope.line(tint: Color, from: Offset, to: Offset, w: Float) =
    drawLine(tint, from, to, strokeWidth = w, cap = StrokeCap.Round)

private fun DrawScope.rotated(direction: GlyphDirection, block: DrawScope.() -> Unit) {
    val deg = when (direction) { GlyphDirection.DOWN -> 0f; GlyphDirection.LEFT -> 90f; GlyphDirection.UP -> 180f; GlyphDirection.RIGHT -> 270f }
    rotate(deg) { block() }
}

/** ✓ */
@Composable
fun CheckGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val p = Path().apply { moveTo(size.width * 0.18f, size.height * 0.53f); lineTo(size.width * 0.42f, size.height * 0.76f); lineTo(size.width * 0.84f, size.height * 0.27f) }
        drawPath(p, tint, style = Stroke(width = px(0.14f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

/** ✕ */
@Composable
fun CloseGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val w = px(0.12f)
        line(tint, at(0.24f, 0.24f), at(0.76f, 0.76f), w)
        line(tint, at(0.76f, 0.24f), at(0.24f, 0.76f), w)
    }

/** ★ */
@Composable
fun StarGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val c = center
        val outer = px(0.48f); val inner = outer * 0.4f
        val p = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 5
            val pt = Offset(c.x + (r * cos(a)).toFloat(), c.y + 0.04f * size.height + (r * sin(a)).toFloat())
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        p.close()
        drawPath(p, tint)
    }

/** ︿ ﹀ ‹ › — an open chevron pointing [direction]. */
@Composable
fun ChevronGlyph(direction: GlyphDirection, tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        rotated(direction) {
            val p = Path().apply { moveTo(size.width * 0.22f, size.height * 0.37f); lineTo(size.width * 0.5f, size.height * 0.65f); lineTo(size.width * 0.78f, size.height * 0.37f) }
            drawPath(p, tint, style = Stroke(width = px(0.12f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

/** ▲ ▼ ◂ ▸ — a solid triangle pointing [direction] (sort direction, a range stepper, the AirPlay mark). */
@Composable
fun TriangleGlyph(direction: GlyphDirection, tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        rotated(direction) {
            val p = Path().apply { moveTo(size.width * 0.2f, size.height * 0.3f); lineTo(size.width * 0.8f, size.height * 0.3f); lineTo(size.width * 0.5f, size.height * 0.74f); close() }
            drawPath(p, tint)
        }
    }

/** ▷ */
@Composable
fun PlayOutlineGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val p = Path().apply { moveTo(size.width * 0.26f, size.height * 0.18f); lineTo(size.width * 0.82f, size.height * 0.5f); lineTo(size.width * 0.26f, size.height * 0.82f); close() }
        drawPath(p, tint, style = Stroke(width = px(0.1f), join = StrokeJoin.Round))
    }

/** ○ */
@Composable
fun RingGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        drawCircle(tint, radius = px(0.34f), style = Stroke(width = px(0.1f)))
    }

/** ↓ */
@Composable
fun ArrowGlyph(direction: GlyphDirection, tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        rotated(direction) {
            val w = px(0.12f)
            line(tint, at(0.5f, 0.18f), at(0.5f, 0.8f), w)
            val p = Path().apply { moveTo(size.width * 0.26f, size.height * 0.56f); lineTo(size.width * 0.5f, size.height * 0.8f); lineTo(size.width * 0.74f, size.height * 0.56f) }
            drawPath(p, tint, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

/** ⚙ */
@Composable
fun GearGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val c = center
        drawCircle(tint, radius = px(0.26f), style = Stroke(width = px(0.14f)))
        for (i in 0 until 8) {
            val a = i * PI / 4
            val from = Offset(c.x + (px(0.3f) * cos(a)).toFloat(), c.y + (px(0.3f) * sin(a)).toFloat())
            val to = Offset(c.x + (px(0.45f) * cos(a)).toFloat(), c.y + (px(0.45f) * sin(a)).toFloat())
            drawLine(tint, from, to, strokeWidth = px(0.13f), cap = StrokeCap.Butt)
        }
    }

/** ✎ */
@Composable
fun PencilGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        drawLine(tint, at(0.36f, 0.64f), at(0.76f, 0.24f), strokeWidth = px(0.18f), cap = StrokeCap.Butt)
        val tip = Path().apply { moveTo(size.width * 0.29f, size.height * 0.58f); lineTo(size.width * 0.42f, size.height * 0.71f); lineTo(size.width * 0.18f, size.height * 0.82f); close() }
        drawPath(tip, tint)
    }

/** ✉ */
@Composable
fun EnvelopeGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val w = px(0.08f)
        drawRect(tint, topLeft = at(0.12f, 0.24f), size = Size(size.width * 0.76f, size.height * 0.52f), style = Stroke(width = w, join = StrokeJoin.Round))
        val v = Path().apply { moveTo(size.width * 0.14f, size.height * 0.27f); lineTo(size.width * 0.5f, size.height * 0.55f); lineTo(size.width * 0.86f, size.height * 0.27f) }
        drawPath(v, tint, style = Stroke(width = w, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }

/** 🌐 */
@Composable
fun GlobeGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val w = px(0.075f)
        drawCircle(tint, radius = px(0.4f), style = Stroke(width = w))
        drawOval(tint, topLeft = at(0.33f, 0.1f), size = Size(size.width * 0.34f, size.height * 0.8f), style = Stroke(width = w))
        line(tint, at(0.12f, 0.5f), at(0.88f, 0.5f), w)
    }

/** ⇧ as iOS draws its Share button: a box open at the top, an arrow out of it. */
@Composable
fun ShareGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val w = px(0.09f)
        val box = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.4f); lineTo(size.width * 0.2f, size.height * 0.4f)
            lineTo(size.width * 0.2f, size.height * 0.88f); lineTo(size.width * 0.8f, size.height * 0.88f)
            lineTo(size.width * 0.8f, size.height * 0.4f); lineTo(size.width * 0.66f, size.height * 0.4f)
        }
        drawPath(box, tint, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        line(tint, at(0.5f, 0.1f), at(0.5f, 0.6f), w)
        val head = Path().apply { moveTo(size.width * 0.35f, size.height * 0.25f); lineTo(size.width * 0.5f, size.height * 0.1f); lineTo(size.width * 0.65f, size.height * 0.25f) }
        drawPath(head, tint, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

/** ▦ */
@Composable
fun GridGlyph(tint: Color, glyphSize: Dp, modifier: Modifier = Modifier, description: String? = null) =
    GlyphCanvas(glyphSize, description, modifier) {
        val w = px(0.08f)
        drawRect(tint, topLeft = at(0.16f, 0.16f), size = Size(size.width * 0.68f, size.height * 0.68f), style = Stroke(width = w))
        for (f in listOf(0.387f, 0.613f)) {
            line(tint, at(f, 0.16f), at(f, 0.84f), w)
            line(tint, at(0.16f, f), at(0.84f, f), w)
        }
    }
