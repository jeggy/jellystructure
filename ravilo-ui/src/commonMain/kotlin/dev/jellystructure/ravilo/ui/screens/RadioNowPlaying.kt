package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import dev.jellystructure.ravilo.ui.components.RadioGlyph
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.LiveTvChannel
import kotlinx.coroutines.delay
import kotlin.time.Clock

private val LIVE_RED = Color(0xFFE0263B)

/**
 * DEB-2658 (DEB-2628 FR-8) — the radio player's audio layout, the owner's pick A (*Now-playing*): ONE centred block — the logo
 * plate, number and name, LIVE with *Radio* beside it, the programme with its times and live progress, then *Next* — and a key
 * hint at the foot. With no guide it is the name, *Live radio* and LIVE. A stopped station shows no LIVE and says how to tune in
 * again. Drawn from `design/ravilo/radio/` (`rd-kit.js`, `radio.css`) at the design's 1920 × 1080 proportions, scaled to the
 * screen it is on.
 *
 * [orbit] and [dimmed] are the TV's burn-in protection (FR-8): the whole block moves by the orbit's offset, and the whole block —
 * the LIVE mark with it — is drawn at 40 % once [dimmed]. Both are passed in, so a computer's window, which has no OLED to
 * protect, draws it still.
 */
@Composable
internal fun RadioNowPlaying(
    channel: LiveTvChannel,
    stopped: Boolean,
    modifier: Modifier = Modifier,
    orbit: Boolean = false,
    dimmed: Boolean = false,
    reducedMotion: Boolean = false,
) {
    val colors = RaviloTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1A20), Color(0xFF05080B))))) {
        val k = (maxHeight.value / 1080f).coerceAtLeast(0.3f)   // the design's px → this screen's dp
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // FR-8 — one step every 7 minutes, a 600 ms glide or, under reduced motion, a cut. The loop counts from when the layout opened.
        var step by remember(orbit) { mutableStateOf(0) }
        if (orbit) LaunchedEffect(Unit) {
            val opened = kotlin.time.TimeSource.Monotonic.markNow()
            while (true) {
                val elapsed = opened.elapsedNow().inWholeMilliseconds
                delay(RadioBurnIn.STEP_MS - elapsed % RadioBurnIn.STEP_MS)
                step = RadioBurnIn.stepAt(opened.elapsedNow().inWholeMilliseconds)
            }
        }
        val (ox, oy) = RadioBurnIn.offsetPx(step, widthPx, heightPx)
        val glide = RadioBurnIn.glideMs(reducedMotion)
        val moved by animateIntOffsetAsState(IntOffset(ox, oy), tween(glide), label = "radioOrbit")

        Box(
            Modifier.fillMaxSize().offset { moved }.alpha(if (dimmed) RadioIdleDim.DIM_ALPHA else 1f),
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                StationPlate(channel, (312 * k).dp, k)
                Spacer(Modifier.height((38 * k).dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((16 * k).dp)) {
                    if (channel.number > 0) Text("${channel.number}", color = colors.textDim, fontSize = (28 * k).sp, fontFamily = FontFamily.Monospace)
                    Text(channel.name, color = colors.text, fontSize = (40 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height((16 * k).dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((14 * k).dp)) {
                    if (!stopped) LiveChip(k)
                    Text(str("radio.label").uppercase(), color = colors.textSecondary, fontSize = (17 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (2.4f * k).sp)
                }
                Spacer(Modifier.height((44 * k).dp))
                ProgrammeBlock(channel, k)
                if (stopped) {
                    Spacer(Modifier.height((32 * k).dp))
                    Column(
                        Modifier.background(Color(0xFF05080B), RoundedCornerShape((14 * k).dp)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape((14 * k).dp))
                            .padding(horizontal = (28 * k).dp, vertical = (14 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(str("radio.stopped"), color = colors.text, fontSize = (26 * k).sp, fontWeight = FontWeight.SemiBold)
                        Text(str("radio.tune_in_again"), color = colors.textSecondary, fontSize = (18 * k).sp)
                    }
                }
            }
            Text(
                str("radio.key_hint"), color = colors.textDim, fontSize = (18 * k).sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (42 * k).dp),
            )
        }
    }
}

/** The programme now with its times and live progress, then *Next*; or, with no guide, *Live radio* alone. */
@Composable
private fun ProgrammeBlock(channel: LiveTvChannel, k: Float) {
    val colors = RaviloTheme.colors
    val program = channel.currentProgram
    if (program == null) {
        Text(str("radio.live_radio"), color = colors.text, fontSize = (44 * k).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        return
    }
    // The live progress follows the clock, so a station left on for an hour keeps moving.
    var nowMs by remember(program) { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(program) { while (true) { delay(15_000L); nowMs = Clock.System.now().toEpochMilliseconds() } }
    val span = (program.endMs - program.startMs).coerceAtLeast(1L)
    val progress = ((nowMs - program.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    Text(program.name, color = colors.text, fontSize = (44 * k).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width((900 * k).dp))
    Spacer(Modifier.height((10 * k).dp))
    Text("${formatGuideTime(program.startMs)}–${formatGuideTime(program.endMs)}", color = colors.textSecondary, fontSize = (24 * k).sp)
    Spacer(Modifier.height((22 * k).dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((18 * k).dp)) {
        Text(formatGuideTime(program.startMs), color = colors.textDim, fontSize = (18 * k).sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.width((764 * k).dp).height((6 * k).dp).background(colors.progressBg, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LIVE_RED, RoundedCornerShape(3.dp)))
        }
        Text(formatGuideTime(program.endMs), color = colors.textDim, fontSize = (18 * k).sp, fontFamily = FontFamily.Monospace)
    }
    channel.nextProgram?.let { next ->
        Spacer(Modifier.height((22 * k).dp))
        Row(horizontalArrangement = Arrangement.spacedBy((8 * k).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str("livetv.next"), color = colors.textDim, fontSize = (22 * k).sp)
            Text(next.name, color = colors.textSecondary, fontSize = (22 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width((520 * k).dp))
            Text(formatGuideTime(next.startMs), color = colors.textDim, fontSize = (22 * k).sp)
        }
    }
}

@Composable
private fun LiveChip(k: Float) {
    Row(
        Modifier.background(LIVE_RED, RoundedCornerShape((8 * k).dp)).padding(horizontal = (16 * k).dp, vertical = (6 * k).dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * k).dp),
    ) {
        Box(Modifier.size((10 * k).dp).background(Color.White, CircleShape))
        Text(str("radio.live_badge"), color = Color.White, fontSize = (17 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (2f * k).sp)
    }
}

/** The station's logo, large, on a plate that carries the radio mark in its bottom-right corner (`.rd-plate .rd-pmark`). */
@Composable
private fun StationPlate(channel: LiveTvChannel, size: androidx.compose.ui.unit.Dp, k: Float) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape((32 * k).dp)
    val hue = remember(channel.name) { (channel.name.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
    Box(
        Modifier.size(size).background(Brush.linearGradient(listOf(Color.hsl(hue.toFloat(), 0.42f, 0.31f), Color.hsl(hue.toFloat(), 0.50f, 0.17f))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val logo = channel.logoUrl
        if (!logo.isNullOrBlank()) {
            // DEB-3051 (FR-8) — the logo is a server-relative `/api/tv/image/…` path: RemoteImage resolves it against the server base, as the Home tiles do; a bare AsyncImage cannot.
            RemoteImage(logo, channel.name, Modifier.fillMaxSize().padding((28 * k).dp), contentScale = ContentScale.Fit)
        } else {
            Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = (92 * k).sp, fontWeight = FontWeight.Bold)
        }
        RadioGlyph(Color.White.copy(alpha = 0.9f), (44 * k).dp, Modifier.align(Alignment.BottomEnd).padding(end = (22 * k).dp, bottom = (20 * k).dp), description = str("radio.label"))
    }
}
