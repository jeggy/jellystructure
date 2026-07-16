package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.ChannelButtonPadding
import dev.jellystructure.shared.tv.ChannelButtonSpec

private class BrandFill(val colors: List<Color>, val stops: List<Float>?, val angleDeg: Float)

/**
 * A CSS `linear-gradient(<angle>deg, ...)`-accurate brush — unlike a plain
 * `Brush.linearGradient(start = Offset(0,0), end = Offset(Float.POSITIVE_INFINITY, ...))` (which
 * always draws corner-to-corner regardless of the authored angle), this computes the true gradient
 * line for the box's actual size at draw time, so e.g. 135deg on a wide 224x94dp tile still reads as
 * a diagonal, not a near-horizontal blend. CSS convention: 0deg points up, angles increase clockwise.
 * [stops] are explicit 0f..1f positions (e.g. from "60%") when the source authored any, else null for
 * even spacing.
 */
private class AngledGradientBrush(
    private val colors: List<Color>,
    private val stops: List<Float>?,
    private val angleDeg: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val theta = angleDeg.toDouble() * kotlin.math.PI / 180.0
        val dx = kotlin.math.sin(theta).toFloat()
        val dy = -kotlin.math.cos(theta).toFloat()
        val length = kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = length / 2f
        return LinearGradientShader(
            from = Offset(cx - dx * half, cy - dy * half),
            to = Offset(cx + dx * half, cy + dy * half),
            colors = colors,
            colorStops = stops,
            tileMode = TileMode.Clamp,
        )
    }
}

// Bug fix: a color-stop token can carry a trailing CSS position (e.g. "#7FD6F2 0%") — the old
// hex-only parser didn't strip that, so any 3+-stop gradient with explicit positions (the exact
// shape a real CSS gradient-picker UI produces) silently failed to parse entirely, falling back to
// the generic theme-accent wash instead of the configured colors.
private fun parseColorStop(token: String): Pair<Color, Float?>? {
    val parts = token.trim().split(Regex("\\s+"))
    val stripped = parts[0].removePrefix("#")
    val hex = when (stripped.length) {
        3 -> buildString { stripped.forEach { append(it).append(it) } } // #abc -> aabbcc
        6, 8 -> stripped
        else -> return null
    }
    val color = try {
        val argb = when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toULong().toLong()
            8 -> hex.toLong(16)
            else -> return null
        }
        Color(argb)
    } catch (_: NumberFormatException) {
        return null
    }
    val pct = parts.getOrNull(1)?.takeIf { it.endsWith("%") }
        ?.removeSuffix("%")?.trim()?.toFloatOrNull()?.div(100f)
    return color to pct
}

private fun parseSolidColor(s: String): Color? = parseColorStop(s)?.first

/**
 * Parse a channel `brandColor` CSS fill into ordered stop colors (with optional explicit positions)
 * plus its angle. Accepts a solid hex (`#rgb`/`#rrggbb`/`#rrggbbaa`) or a single
 * `linear-gradient(<deg>, <c1> [pos%], <c2> [pos%], ...)` — 2 or more stops. Bug fix: the angle token
 * used to be discarded entirely (the card always drew a fixed corner-to-corner diagonal) — on a wide
 * tile that made e.g. a configured 135deg render as a near-horizontal blend, which for two
 * similarly-hued colors (differing mainly in lightness) looked like a flat solid instead of a
 * gradient. Now parsed and honored by `AngledGradientBrush`. CSS default with no angle token is
 * 180deg ("to bottom"). Returns null for anything unparseable so the caller falls back to the theme
 * accent.
 */
private fun parseBrandFill(brandColor: String?): BrandFill? {
    val t = brandColor?.trim() ?: return null
    if (t.startsWith("linear-gradient", ignoreCase = true)) {
        val inner = t.substringAfter('(').substringBeforeLast(')')
        val tokens = inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val angle = tokens.firstOrNull { it.endsWith("deg") }
            ?.removeSuffix("deg")?.trim()?.toFloatOrNull() ?: 180f
        val stopPairs = tokens
            .filter { !it.endsWith("deg") && !it.startsWith("to ", ignoreCase = true) }
            .mapNotNull { parseColorStop(it) }
        val colors = stopPairs.map { it.first }
        val stops = if (stopPairs.isNotEmpty() && stopPairs.all { it.second != null }) stopPairs.map { it.second!! } else null
        return if (colors.isNotEmpty()) BrandFill(colors, stops, angle) else null
    }
    return parseSolidColor(t)?.let { BrandFill(listOf(it), null, 180f) }
}

@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    brandColor: String?,
    logoPadding: ChannelButtonPadding? = null,
    focusRequester: FocusRequester? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val spaceGrotesk = SpaceGrotesk
    var focused by remember { mutableStateOf(false) }
    // Snappier focus feel (R43).
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.focusSpring<Dp>() }
    val scale         by animateFloatAsState(if (focused) ChannelButtonSpec.FOCUS_SCALE else 1f, focusSpec, label = "channelScale")
    val ringWidth     by animateDpAsState(if (focused) ChannelButtonSpec.RING_WIDTH_DP.dp else 0.dp, dpSpec, label = "channelBorder")
    val glowElevation by animateDpAsState(if (focused) ChannelButtonSpec.GLOW_ELEV_DP.dp else 0.dp, dpSpec, label = "channelShadow")

    val cardShape = remember { RoundedCornerShape(ChannelButtonSpec.CORNER_DP.dp) }
    val brandFill   = remember(brandColor) { parseBrandFill(brandColor) }
    val accentColor = brandFill?.colors?.firstOrNull() ?: colors.accent
    val glowColor   = remember(accentColor) { accentColor.copy(alpha = 0.55f) }

    // Channel-button background. A gradient brandColor (e.g. "linear-gradient(135deg,#3b2a78,#15102e)")
    // renders its own stops at its own authored angle (AngledGradientBrush — see its doc for the bug
    // this replaced: a fixed corner-to-corner diagonal made a real angle look near-horizontal on a
    // wide tile). A solid brandColor renders as an actual solid fill.
    //
    // Bug fix: a solid brandColor used to be discarded as "the background" and instead blended at
    // SOLID_WASH_ALPHA (28%) into the near-black theme `colors.card` — reported live: a channel with a
    // black logo + solid WHITE brandColor rendered as an almost entirely near-black tile (a faint white
    // corner glow, nothing more), making the black logo invisible instead of showing a white tile with a
    // legible black logo. The wash-to-card treatment is now reserved for the true "no brandColor set"
    // case (brandFill == null) — a chosen solid color, however light or dark, always renders as itself.
    val cardGradient = remember(brandFill, accentColor, colors.card) {
        when {
            brandFill != null && brandFill.colors.size >= 2 -> AngledGradientBrush(brandFill.colors, brandFill.stops, brandFill.angleDeg)
            brandFill != null -> Brush.linearGradient(
                colors = brandFill.colors.let { listOf(it[0], it[0]) },
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
            else -> Brush.linearGradient(
                colors = listOf(accentColor.copy(alpha = ChannelButtonSpec.SOLID_WASH_ALPHA), colors.card),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
        }
    }
    // Subtle sheen overlay
    val sheenGradient = remember {
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = ChannelButtonSpec.SHEEN_ALPHA), Color.Transparent),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }

    // Focusable at a FIXED layout size; the focus scale is a draw-only graphicsLayer on the inner box,
    // so the lazy list's focused-bounds tracking never chases the scale animation → no viewport jump (R42).
    Box(
        modifier = Modifier
            .size(ChannelButtonSpec.WIDTH_DP.dp, ChannelButtonSpec.HEIGHT_DP.dp)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // R43: focus animation runs entirely in the draw phase (scale + shadow in graphicsLayer,
                // ring in drawWithCache) — no per-frame recomposition.
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    this.shadowElevation = glowElevation.toPx()
                    shape = cardShape
                    clip = true
                    ambientShadowColor = glowColor
                    spotShadowColor = glowColor
                }
                .background(cardGradient)
                .drawWithCache {
                    val radius = CornerRadius(ChannelButtonSpec.CORNER_DP.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        val bw = ringWidth.toPx()
                        // Bug fix: this used to be accentColor (the brand color's own first stop, at
                        // partial alpha) — for a light/white brandColor (e.g. Apple TV+, see the
                        // background fix above) that made the focus ring nearly invisible against a
                        // same-toned background. The focus ring is chrome, not brand identity: it now
                        // always uses the theme's own focusRing token (same one every other focusable
                        // surface in the app uses), at full opacity, so it stays legible regardless of
                        // the tile's own colors.
                        if (bw > 0f) drawRoundRect(
                            color = colors.focusRing,
                            cornerRadius = radius,
                            style = Stroke(width = bw),
                            topLeft = Offset(bw / 2f, bw / 2f),
                            size = Size(size.width - bw, size.height - bw),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
        // Sheen
        Box(modifier = Modifier.fillMaxSize().background(sheenGradient))

        // Watermark text (bottom-start, behind logo)
        Text(
            text = name.take(ChannelButtonSpec.WATERMARK_TAKE_CHARS).uppercase(),
            color = Color.White.copy(alpha = ChannelButtonSpec.WATERMARK_ALPHA),
            fontSize = ChannelButtonSpec.WATERMARK_SIZE_SP.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = spaceGrotesk,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = ChannelButtonSpec.WATERMARK_PAD_START_DP.dp,
                    bottom = ChannelButtonSpec.WATERMARK_PAD_BOT_DP.dp,
                ),
        )

        if (logoUrl != null) {
            RemoteImage(
                url = logoUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = if (logoPadding != null)
                    Modifier.fillMaxSize().padding(
                        start = logoPadding.left.dp,
                        top = logoPadding.top.dp,
                        end = logoPadding.right.dp,
                        bottom = logoPadding.bottom.dp,
                    )
                else
                    Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = ChannelButtonSpec.TEXT_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(ChannelButtonSpec.TEXT_PAD_DP.dp),
            )
        }
        }
    }
}
