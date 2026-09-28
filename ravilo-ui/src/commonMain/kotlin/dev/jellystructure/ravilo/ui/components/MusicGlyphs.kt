package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * R321/R322 — music mode's icons, drawn on the design's own 24-unit grid (`design/ravilo/mobile/ravilo-music.js`'s
 * `ICONS`, `stroke-width: 2`, round caps and joins), like [RaviloBottomNav]'s page marks. Never text: R315's rule,
 * and the web build has no system font for ♪ or ♡ (dev review 3 — `scripts/check-web-glyphs.sh`).
 */
enum class MusicIcon {
    LISTEN, BROWSE, PLAYING, QUEUE, NOTE, SHUFFLE, REPEAT, PREVIOUS, NEXT, PLAY, PAUSE,
    HEART, HEART_FILLED, MORE, LYRICS, DRAG, CHEVRON_DOWN, FILM, SEARCH,
}

@Composable
fun MusicGlyph(icon: MusicIcon, tint: Color, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val m = modifier.size(size).let { if (description != null) it.semantics { contentDescription = description } else it }
    Canvas(m) { drawMusicIcon(icon, tint) }
}

private fun DrawScope.drawMusicIcon(icon: MusicIcon, tint: Color) {
    val u = size.minDimension / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f
    fun at(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, at(x1, y1), at(x2, y2), 2f * u, StrokeCap.Round)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(tint, r * u, at(x, y)) else drawCircle(tint, r * u, at(x, y), style = stroke)
    fun path(fill: Boolean = false, build: Path.(p: (Float, Float) -> Offset) -> Unit) {
        val p = Path().apply { build(::at) }
        if (fill) drawPath(p, tint) else drawPath(p, tint, style = stroke)
    }
    fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = false) {
        if (fill) drawRoundRect(tint, at(x, y), Size(w * u, h * u), CornerRadius(r * u, r * u))
        else drawRoundRect(tint, at(x, y), Size(w * u, h * u), CornerRadius(r * u, r * u), style = stroke)
    }
    fun Path.m(p: Offset) = moveTo(p.x, p.y)
    fun Path.l(p: Offset) = lineTo(p.x, p.y)
    fun Path.c(a: Offset, b: Offset, e: Offset) = cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
    fun Path.qd(a: Offset, e: Offset) = quadraticTo(a.x, a.y, e.x, e.y)

    when (icon) {
        MusicIcon.LISTEN -> {
            circle(7.5f, 17.5f, 2.8f); circle(16.7f, 15.5f, 2.8f)
            path { m(it(10.3f, 17.5f)); l(it(10.3f, 5.5f)); l(it(19.5f, 3.5f)); l(it(19.5f, 15.5f)) }
        }
        MusicIcon.BROWSE -> { rrect(4f, 4f, 7f, 7f, 1.5f); rrect(13f, 4f, 7f, 7f, 1.5f); rrect(4f, 13f, 7f, 7f, 1.5f); rrect(13f, 13f, 7f, 7f, 1.5f) }
        MusicIcon.PLAYING -> {
            circle(12f, 12f, 9f)
            line(8.5f, 15.5f, 8.5f, 12.5f); line(11f, 15.5f, 11f, 9f); line(13.5f, 15.5f, 13.5f, 10.5f); line(16f, 15.5f, 16f, 13.5f)
        }
        MusicIcon.QUEUE -> {
            line(4f, 6f, 16f, 6f); line(4f, 11f, 16f, 11f); line(4f, 16f, 11f, 16f)
            path(fill = true) { m(it(16f, 14f)); l(it(21f, 17.2f)); l(it(16f, 20.4f)); close() }
        }
        MusicIcon.NOTE -> {
            circle(8f, 17f, 3f); circle(17f, 15f, 3f)
            path { m(it(11f, 17f)); l(it(11f, 5f)); l(it(20f, 3f)); l(it(20f, 15f)) }
        }
        MusicIcon.SHUFFLE -> {
            path { m(it(3f, 7f)); l(it(6.5f, 7f)); c(it(11.5f, 7f), it(12.5f, 17f), it(17.5f, 17f)); l(it(21f, 17f)) }
            path { m(it(3f, 17f)); l(it(6.5f, 17f)); c(it(8.5f, 17f), it(9.7f, 15.4f), it(10.7f, 13.5f)) }
            path { m(it(14f, 8.5f)); c(it(15f, 7.5f), it(16f, 7f), it(17.5f, 7f)); l(it(21f, 7f)) }
            path { m(it(18.5f, 4.5f)); l(it(21f, 7f)); l(it(18.5f, 9.5f)) }
            path { m(it(18.5f, 14.5f)); l(it(21f, 17f)); l(it(18.5f, 19.5f)) }
        }
        MusicIcon.REPEAT -> {
            path { m(it(4f, 11f)); l(it(4f, 9f)); qd(it(4f, 5f), it(8f, 5f)); l(it(20f, 5f)) }
            path { m(it(17f, 2f)); l(it(20f, 5f)); l(it(17f, 8f)) }
            path { m(it(20f, 13f)); l(it(20f, 15f)); qd(it(20f, 19f), it(16f, 19f)); l(it(4f, 19f)) }
            path { m(it(7f, 22f)); l(it(4f, 19f)); l(it(7f, 16f)) }
        }
        MusicIcon.PREVIOUS -> {
            line(5f, 5f, 5f, 19f)
            path(fill = true) { m(it(19f, 5f)); l(it(8.5f, 12f)); l(it(19f, 19f)); close() }
        }
        MusicIcon.NEXT -> {
            line(19f, 5f, 19f, 19f)
            path(fill = true) { m(it(5f, 5f)); l(it(15.5f, 12f)); l(it(5f, 19f)); close() }
        }
        MusicIcon.PLAY -> path(fill = true) { m(it(7.5f, 4.5f)); l(it(19.5f, 12f)); l(it(7.5f, 19.5f)); close() }
        MusicIcon.PAUSE -> { rrect(6.5f, 4.5f, 4f, 15f, 1.2f, fill = true); rrect(13.5f, 4.5f, 4f, 15f, 1.2f, fill = true) }
        MusicIcon.HEART, MusicIcon.HEART_FILLED -> path(fill = icon == MusicIcon.HEART_FILLED) {
            m(it(12f, 20.5f))
            c(it(5f, 16f), it(2.5f, 12.5f), it(2.5f, 9f))
            c(it(2.5f, 6.2f), it(4.6f, 4.2f), it(7.2f, 4.2f))
            c(it(9.2f, 4.2f), it(11f, 5.3f), it(12f, 7f))
            c(it(13f, 5.3f), it(14.8f, 4.2f), it(16.8f, 4.2f))
            c(it(19.4f, 4.2f), it(21.5f, 6.2f), it(21.5f, 9f))
            c(it(21.5f, 12.5f), it(19f, 16f), it(12f, 20.5f))
            close()
        }
        MusicIcon.MORE -> { circle(5f, 12f, 1.9f, fill = true); circle(12f, 12f, 1.9f, fill = true); circle(19f, 12f, 1.9f, fill = true) }
        MusicIcon.LYRICS -> {
            line(4f, 6f, 16f, 6f); line(4f, 11f, 13f, 11f); line(4f, 16f, 10f, 16f)
            circle(17.5f, 16.5f, 2.5f)
            path { m(it(20f, 16.5f)); l(it(20f, 8f)); l(it(18f, 9f)) }
        }
        MusicIcon.DRAG -> { line(5f, 9f, 19f, 9f); line(5f, 15f, 19f, 15f) }
        MusicIcon.CHEVRON_DOWN -> path { m(it(6f, 9f)); l(it(12f, 15f)); l(it(18f, 9f)) }
        MusicIcon.FILM -> {
            rrect(3f, 5f, 18f, 14f, 2f)
            path(fill = true) { m(it(10f, 9.5f)); l(it(15f, 12f)); l(it(10f, 14.5f)); close() }
        }
        MusicIcon.SEARCH -> { circle(11f, 11f, 7f); line(16.3f, 16.3f, 20.5f, 20.5f) }
    }
}

/**
 * FR-R321-7 — the number of the song that is playing becomes three bars: moving while it plays, still while paused.
 */
@Composable
fun PlayingBars(animated: Boolean, tint: Color, glyphSize: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "playingBars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.size(glyphSize)) {
        val w = size.width / 7f
        val base = size.height
        val shapes = floatArrayOf(0.55f, 0.95f, 0.7f)
        for (i in 0..2) {
            val f = if (animated) {
                val x = (phase + i * 0.33f) % 1f
                0.3f + 0.7f * (if (x < 0.5f) x * 2f else (1f - x) * 2f)
            } else shapes[i]
            val h = base * f
            drawRoundRect(tint, Offset(w * (1 + i * 2), base - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}
