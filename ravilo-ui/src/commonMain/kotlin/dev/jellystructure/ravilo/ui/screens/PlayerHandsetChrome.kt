package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.EpisodeTriptych
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// ─── R244 — the player on a phone ────────────────────────────────────────────
//
// Direction 2 · "Thumb rail" (owner decision 2026-09-16): transport centred on the picture, three
// secondary controls (Subtitles · Next/Episodes · Lock) on a right-edge column in landscape that
// folds into a row above the seek bar in portrait. Every target ≥ 46 dp, every readable label ≥ 13 sp,
// skip amounts as labels BENEATH the arrow — never a numeral inside a 46 dp circle.
//
// Its own file and its own @Composables from the first commit, per the release-only VerifyError
// precedent (R237): PlayerScreen's body is already ~3.5k lines and the minified verifier rejects a
// Compose-generated method that grows past it. Nothing in here reads or writes PlayerScreen's state
// directly — it is a stateless presentational layer plus a gesture layer that reports what happened.

internal const val HANDSET_CHROME_HIDE_MS = 3_000L      // FR-R244-3 — not the TV's 3 600
private const val DOUBLE_TAP_WINDOW_MS = 900L           // FR-R244-4 — repeats accumulate while the ripple is up
private const val LOCK_HINT_MS = 1_200L                 // FR-R244-8
private const val FIT_TOAST_MS = 900L                   // FR-R244-6
private const val SWIPE_PILL_LINGER_MS = 600L

internal val HANDSET_TARGET: Dp = 46.dp
private val CARD = Color(0xFF0E1119)

internal enum class HandsetRailItem { SUBTITLES, NEXT, EPISODES, LOCK, GUIDE }

/** The rail for a title: Subtitles first, then Next (or Episodes when there is no next but a season), then Lock. */
internal fun handsetRailFor(hasNextEp: Boolean, hasSeason: Boolean): List<HandsetRailItem> = buildList {
    add(HandsetRailItem.SUBTITLES)
    if (hasNextEp) add(HandsetRailItem.NEXT) else if (hasSeason) add(HandsetRailItem.EPISODES)
    add(HandsetRailItem.LOCK)
}

internal fun hmsLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

// ─── The chrome ────────────────────────────────────────────────────────────────

@Composable
internal fun HandsetPlayerChrome(
    colors: RaviloColors,
    itemTitle: String,
    itemKicker: String?,
    // R303 (FR-R303-1/2) — the identity slot between the title and the cast glyph.
    logoUrl: String? = null,
    logoInk: String? = null,
    seriesName: String? = null,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    isPlaying: Boolean,
    railItems: List<HandsetRailItem>,
    stallActive: Boolean,
    seekMomentActive: Boolean,
    /** FR-R244-7 — only while the SYSTEM has rotation locked; a permanent rotate button is out of scope. */
    showRotate: Boolean,
    landscapeForced: Boolean,
    /** FR-R244-15 — the Live TV player: no seek bar, a Now/Next line in its place, no skip buttons. */
    showSeek: Boolean = true,
    showSkips: Boolean = true,
    liveLine: String? = null,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    /** Set when a season exists: tapping the kicker + title opens the season sheet (FR-R244-11). */
    onTitleTap: (() -> Unit)? = null,
    /** R245 (FR-R245-1/4) — the cast button; casting from inside the player hands the position over. */
    castSlot: (@Composable () -> Unit)? = null,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipFwd: () -> Unit,
    onRail: (HandsetRailItem) -> Unit,
    onSeekStart: (Long) -> Unit,
    onSeekDrag: (Long) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val portrait = LocalPortrait.current
    val topScrim = remember { Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)) }
    val botScrim = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(topScrim))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(botScrim))

        // FR-R244-13 — everything below is inset from the notch / punch-hole / gesture bar.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // ── Top bar: back · kicker + title · rotate-when-locked ──
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HandsetIconButton(size = HANDSET_TARGET, onClick = onBack) { tint ->
                    Canvas(Modifier.size(20.dp)) {
                        val p = Path().apply {
                            moveTo(size.width * 0.62f, size.height * 0.12f)
                            lineTo(size.width * 0.28f, size.height * 0.5f)
                            lineTo(size.width * 0.62f, size.height * 0.88f)
                        }
                        drawPath(p, tint, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                Column(
                    Modifier.weight(1f).padding(horizontal = 6.dp)
                        .then(if (onTitleTap != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTitleTap) else Modifier),
                ) {
                    itemKicker?.let {
                        Text(it.uppercase(), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(itemTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // R303 (FR-R303-1) — immediately left of the cast glyph; the title column's weight(1f) is what
                // yields to it. FR-R303-6's AirPlay chip does not exist in this build (no iOS target), so
                // nothing hides it here yet.
                PlayerIdentSlot(
                    logoUrl = logoUrl, logoInk = logoInk, seriesName = seriesName,
                    maxWidth = PHONE_IDENT_MAX_WIDTH, maxHeight = PHONE_IDENT_MAX_HEIGHT,
                    nameFontSize = 14.sp, nameLineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                castSlot?.invoke()
                if (showRotate) {
                    HandsetIconButton(size = HANDSET_TARGET, onClick = onRotate, label = str("pl.rotate"), showLabel = false) { tint ->
                        Canvas(Modifier.size(20.dp)) {
                            val r = if (landscapeForced) Size(size.width * 0.6f, size.height * 0.9f) else Size(size.width * 0.9f, size.height * 0.6f)
                            drawRoundRect(tint, Offset((size.width - r.width) / 2, (size.height - r.height) / 2), r, CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }

            // ── Centred transport ──
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (portrait) 26.dp else 40.dp),
            ) {
                if (showSkips) HandsetSkipButton(label = "10 s", back = true, onClick = onSkipBack)
                HandsetPlayButton(isPlaying = isPlaying, buffering = stallActive, colors = colors, onClick = onPlayPause)
                if (showSkips) HandsetSkipButton(label = "30 s", back = false, onClick = onSkipFwd)
            }

            // ── Landscape rail: a right-edge column, vertically centred ──
            if (!portrait) {
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    railItems.forEach { HandsetRailButton(it, onClick = { onRail(it) }) }
                }
            }

            // ── Bottom: (portrait rail row) · seek bar + times, or the live line ──
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (portrait) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    ) {
                        railItems.forEach { HandsetRailButton(it, onClick = { onRail(it) }) }
                    }
                }
                if (showSeek) {
                    HandsetSeekBar(
                        colors = colors, positionMs = positionMs, durationMs = durationMs, bufferedMs = bufferedMs,
                        scrubbing = scrubbing, scrubPos = scrubPos, isSeeking = seekMomentActive,
                        onSeekStart = onSeekStart, onSeekDrag = onSeekDrag, onSeekEnd = onSeekEnd,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(hmsLabel(if (scrubbing) scrubPos else positionMs), color = Color.White.copy(0.9f), fontSize = 13.sp, fontFamily = Sora)
                        Text(hmsLabel(durationMs), color = Color.White.copy(0.6f), fontSize = 13.sp, fontFamily = Sora)
                    }
                } else if (liveLine != null) {
                    Text(liveLine, color = Color.White.copy(0.85f), fontSize = 13.sp, fontFamily = Sora, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─── Buttons ──────────────────────────────────────────────────────────────────

@Composable
private fun HandsetIconButton(
    size: Dp,
    onClick: () -> Unit,
    label: String? = null,
    showLabel: Boolean = true,
    glyph: @Composable (tint: Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { glyph(Color.White) }
        if (label != null && showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(label, color = Color.White.copy(0.85f), fontSize = 13.sp, fontFamily = Sora, maxLines = 1)
        }
    }
}

/** FR-R244-1 — the amount is a label under the arrow ("10 s" / "30 s"), never a numeral inside the glyph. */
@Composable
private fun HandsetSkipButton(label: String, back: Boolean, onClick: () -> Unit) {
    HandsetIconButton(size = 50.dp, onClick = onClick, label = label) { tint -> SkipGlyph(back, tint) }
}

/**
 * The −10 s / +30 s arrow: a three-quarter arc opening at the top, arrowhead at the open end.
 * R257 (FR-R257-6) — back turns COUNTER-clockwise with its head at the upper left pointing left;
 * forward turns clockwise with its head at the upper right pointing right. These were swapped (the
 * back glyph was every platform's "forward"). R301 — one drawing, shared with the cast remote, which
 * had kept its own copy of the swapped version.
 */
@Composable
internal fun SkipGlyph(back: Boolean, tint: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        val r = this.size.minDimension * 0.36f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val arc = skipArc(back)
        drawArc(tint, arc.startAngle, arc.sweepAngle, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = stroke)
        val d = arc.tipSide.toFloat()
        val tipX = c.x + d * r * 0.5f
        val tipY = c.y - r
        val head = Path().apply {
            moveTo(tipX - d * 5.dp.toPx(), tipY - 3.dp.toPx())
            lineTo(tipX, tipY)
            lineTo(tipX - d * 5.dp.toPx(), tipY + 4.dp.toPx())
        }
        drawPath(head, tint, style = stroke)
    }
}

@Composable
private fun HandsetPlayButton(isPlaying: Boolean, buffering: Boolean, colors: RaviloColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            // R218 moment C at phone scale: the spinner stands in for the play button; no text.
            CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        } else {
            Canvas(Modifier.size(28.dp)) {
                val ink = Color(0xFF0A0C13)
                if (isPlaying) {
                    val bw = size.width * 0.26f; val gap = size.width * 0.16f
                    drawRoundRect(ink, Offset(size.width / 2 - gap / 2 - bw, 0f), Size(bw, size.height), CornerRadius(2.dp.toPx()))
                    drawRoundRect(ink, Offset(size.width / 2 + gap / 2, 0f), Size(bw, size.height), CornerRadius(2.dp.toPx()))
                } else {
                    val p = Path().apply {
                        moveTo(size.width * 0.2f, 0f); lineTo(size.width * 1.02f, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
                    }
                    drawPath(p, ink)
                }
            }
        }
    }
}

@Composable
private fun HandsetRailButton(item: HandsetRailItem, onClick: () -> Unit) {
    val label = when (item) {
        HandsetRailItem.SUBTITLES -> str("pl.subtitles")
        HandsetRailItem.NEXT -> str("pl.next")
        HandsetRailItem.EPISODES -> str("pl.episodes")
        HandsetRailItem.LOCK -> str("pl.lock")
        HandsetRailItem.GUIDE -> str("pl.guide")
    }
    HandsetIconButton(size = HANDSET_TARGET, onClick = onClick, label = label) { tint ->
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            when (item) {
                HandsetRailItem.SUBTITLES -> {
                    drawRoundRect(tint, Offset(0f, size.height * 0.18f), Size(size.width, size.height * 0.64f), CornerRadius(3.dp.toPx()), style = stroke)
                    drawLine(tint, Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.55f, size.height * 0.5f), stroke.width, StrokeCap.Round)
                    drawLine(tint, Offset(size.width * 0.2f, size.height * 0.66f), Offset(size.width * 0.8f, size.height * 0.66f), stroke.width, StrokeCap.Round)
                }
                HandsetRailItem.NEXT -> {
                    val p = Path().apply { moveTo(size.width * 0.1f, size.height * 0.1f); lineTo(size.width * 0.7f, size.height * 0.5f); lineTo(size.width * 0.1f, size.height * 0.9f); close() }
                    drawPath(p, tint)
                    drawLine(tint, Offset(size.width * 0.86f, size.height * 0.1f), Offset(size.width * 0.86f, size.height * 0.9f), 2.6.dp.toPx(), StrokeCap.Round)
                }
                HandsetRailItem.EPISODES -> {
                    for (i in 0..2) {
                        val y = size.height * (0.2f + i * 0.3f)
                        drawLine(tint, Offset(0f, y), Offset(size.width * 0.28f, y), 3.dp.toPx(), StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.42f, y), Offset(size.width, y), stroke.width, StrokeCap.Round)
                    }
                }
                HandsetRailItem.LOCK -> {
                    drawRoundRect(tint, Offset(size.width * 0.12f, size.height * 0.45f), Size(size.width * 0.76f, size.height * 0.5f), CornerRadius(3.dp.toPx()), style = stroke)
                    drawArc(tint, 180f, 180f, false, Offset(size.width * 0.28f, size.height * 0.08f), Size(size.width * 0.44f, size.height * 0.6f), style = stroke)
                }
                HandsetRailItem.GUIDE -> {
                    for (i in 0..1) for (j in 0..1) {
                        drawRoundRect(tint, Offset(size.width * (0.05f + i * 0.5f), size.height * (0.05f + j * 0.5f)), Size(size.width * 0.4f, size.height * 0.4f), CornerRadius(2.dp.toPx()), style = stroke)
                    }
                }
            }
        }
    }
}

// ─── Seek bar (FR-R244-9) ─────────────────────────────────────────────────────

@Composable
private fun HandsetSeekBar(
    colors: RaviloColors,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    isSeeking: Boolean,
    onSeekStart: (Long) -> Unit,
    onSeekDrag: (Long) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val scrubFrac = if (durationMs > 0 && scrubbing) (scrubPos.toFloat() / durationMs).coerceIn(0f, 1f) else played
    // The ghost mark: where the drag began, kept for the drag's whole life.
    var ghostFrac by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(scrubbing) { if (scrubbing && ghostFrac < 0f) ghostFrac = played; if (!scrubbing) ghostFrac = -1f }
    val accent = colors.accent; val accentS = colors.accentSecondary

    fun posAt(x: Float, widthPx: Int): Long {
        if (durationMs <= 0 || widthPx <= 0) return 0L
        return ((x / widthPx) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = maxWidth
        // Time bubble above the thumb while dragging (no thumbnail — trickplayUrl is always null).
        if (scrubbing) {
            val bubbleW = 64.dp
            val x = (widthDp * scrubFrac - bubbleW / 2).coerceIn(0.dp, widthDp - bubbleW)
            Box(
                Modifier.offset(x = x, y = (-30).dp).width(bubbleW).background(Color.White, RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(hmsLabel(scrubPos), color = Color(0xFF0A0C13), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDSET_TARGET)
                .pointerInput(durationMs) {
                    detectTapGestures(onTap = { offset -> onSeekStart(posAt(offset.x, size.width)); onSeekEnd() })
                }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { offset -> ghostFrac = played; onSeekStart(posAt(offset.x, size.width)) },
                        onDrag = { change, _ -> change.consume(); onSeekDrag(posAt(change.position.x, size.width)) },
                        onDragEnd = { onSeekEnd() },
                        onDragCancel = { onSeekEnd() },
                    )
                },
        ) {
            val barH = (if (scrubbing) 6.dp else 4.dp).toPx()
            val y = center.y; val w = size.width
            val corner = CornerRadius(barH / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y - barH / 2), Size(w, barH), corner)
            if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.30f), Offset(0f, y - barH / 2), Size(w * buffered, barH), corner)
            if (scrubFrac > 0f) {
                val grad = Brush.linearGradient(listOf(accent, accentS), start = Offset.Zero, end = Offset(w, 0f))
                drawRoundRect(grad, Offset(0f, y - barH / 2), Size(w * scrubFrac, barH), corner)
            }
            if (scrubbing && ghostFrac >= 0f) {
                drawRect(Color.White.copy(alpha = 0.7f), Offset(w * ghostFrac - 1.dp.toPx(), y - 9.dp.toPx()), Size(2.dp.toPx(), 18.dp.toPx()))
            }
            val thumbR = (if (scrubbing) 13.dp else 7.dp).toPx()   // 26 px thumb while dragging
            drawCircle(Color.White, thumbR, Offset(w * scrubFrac, y))
            if (isSeeking) drawCircle(Color.White.copy(alpha = 0.35f), thumbR + 6.dp.toPx(), Offset(w * scrubFrac, y))
        }
    }
}

// ─── Lock (FR-R244-8) ─────────────────────────────────────────────────────────

@Composable
internal fun HandsetLockOverlay(onUnlock: () -> Unit) {
    var hintUntil by remember { mutableLongStateOf(0L) }
    var hintRev by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hintRev) {
        if (hintRev == 0) return@LaunchedEffect
        hintVisible = true
        delay(LOCK_HINT_MS)
        hintVisible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            // Every gesture is inert while locked: this layer takes them all. A tap shows the hint, a
            // long-press unlocks. The system back is NOT intercepted here (it reaches the root).
            .pointerInput(Unit) {
                detectTapGestures(onTap = { hintRev++ }, onLongPress = { onUnlock() })
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(visible = hintVisible, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
                Text(
                    str("pl.locked_hint"),
                    color = Color.White, fontSize = 13.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Box(
                Modifier.size(HANDSET_TARGET).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawRoundRect(Color.White, Offset(size.width * 0.12f, size.height * 0.45f), Size(size.width * 0.76f, size.height * 0.5f), CornerRadius(3.dp.toPx()), style = stroke)
                    drawArc(Color.White, 180f, 180f, false, Offset(size.width * 0.28f, size.height * 0.08f), Size(size.width * 0.44f, size.height * 0.6f), style = stroke)
                }
            }
        }
    }
}

// ─── Gestures (FR-R244-4/5/6) ─────────────────────────────────────────────────

internal enum class SwipeSide { LEFT, RIGHT }

/**
 * The handset's gesture surface, drawn UNDER the chrome so the controls take their own taps first:
 * single tap toggles the chrome; double-tap in the outer thirds seeks (accumulating while the ripple
 * is up, never raising the chrome); a vertical drag on the left half is brightness, on the right half
 * volume (each only when the platform offers a setter); a pinch toggles fit / fill. Everything here
 * is reported upward — the layer owns only its own transient visuals.
 */
@Composable
internal fun HandsetGestureLayer(
    enabled: Boolean,
    brightness: Float?,
    setBrightness: ((Float) -> Unit)?,
    volume: Float?,
    setVolume: ((Float) -> Unit)?,
    fill: Boolean,
    onSingleTap: () -> Unit,
    /** Called with the increment (−10 000 / +30 000) on every double-tap; the ripple shows the running total. */
    onDoubleTapSeek: (Long) -> Unit,
    onFillChange: (Boolean) -> Unit,
) {
    val portrait = LocalPortrait.current
    // Double-tap ripple + accumulator
    var rippleSide by remember { mutableStateOf<SwipeSide?>(null) }
    var rippleTotalMs by remember { mutableLongStateOf(0L) }
    var rippleRev by remember { mutableIntStateOf(0) }
    LaunchedEffect(rippleRev) {
        if (rippleRev == 0) return@LaunchedEffect
        delay(DOUBLE_TAP_WINDOW_MS)
        rippleSide = null; rippleTotalMs = 0L
    }
    // Swipe pill
    var swipeSide by remember { mutableStateOf<SwipeSide?>(null) }
    var swipeLevel by remember { mutableFloatStateOf(0f) }
    var swipeRev by remember { mutableIntStateOf(0) }
    var swipeActive by remember { mutableStateOf(false) }
    LaunchedEffect(swipeRev, swipeActive) {
        if (swipeActive || swipeRev == 0) return@LaunchedEffect
        delay(SWIPE_PILL_LINGER_MS)
        swipeSide = null
    }
    // Fit / fill toast
    var toastRev by remember { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }
    var toastFill by remember { mutableStateOf(fill) }
    LaunchedEffect(toastRev) {
        if (toastRev == 0) return@LaunchedEffect
        toastVisible = true
        delay(FIT_TOAST_MS)
        toastVisible = false
    }
    val currentFill by rememberUpdatedState(fill)
    val currentBrightness by rememberUpdatedState(brightness)
    val currentVolume by rememberUpdatedState(volume)
    val currentEnabled by rememberUpdatedState(enabled)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (currentEnabled) onSingleTap() },
                    onDoubleTap = { offset ->
                        if (!currentEnabled) return@detectTapGestures
                        val third = size.width / 3f
                        val side = when {
                            offset.x < third -> SwipeSide.LEFT
                            offset.x > third * 2 -> SwipeSide.RIGHT
                            else -> return@detectTapGestures      // the centre third is never a seek
                        }
                        val inc = if (side == SwipeSide.LEFT) -10_000L else 30_000L
                        rippleTotalMs = if (rippleSide == side) rippleTotalMs + inc else inc
                        rippleSide = side
                        rippleRev++
                        onDoubleTapSeek(inc)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    var mode = 0                         // 0 undecided · 1 swipe · 2 pinch
                    val side = if (down.position.x < size.width / 2f) SwipeSide.LEFT else SwipeSide.RIGHT
                    val setter = if (side == SwipeSide.LEFT) setBrightness else setVolume
                    var startLevel = 0f
                    var zoomAcc = 1f
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 2
                            zoomAcc *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            if (zoomAcc > 1.25f && !currentFill) { onFillChange(true); toastFill = true; toastRev++; zoomAcc = 1f }
                            else if (zoomAcc < 0.8f && currentFill) { onFillChange(false); toastFill = false; toastRev++; zoomAcc = 1f }
                            continue
                        }
                        if (mode == 2) continue
                        val ch = pressed.first()
                        val dy = ch.position.y - down.position.y
                        val dx = ch.position.x - down.position.x
                        if (mode == 0 && abs(dy) > slop && abs(dy) > abs(dx) * 1.5f && setter != null) {
                            mode = 1
                            startLevel = (if (side == SwipeSide.LEFT) currentBrightness else currentVolume) ?: 0.5f
                            swipeSide = side; swipeActive = true
                        }
                        if (mode == 1) {
                            ch.consume()
                            val level = (startLevel - dy / size.height * 1.4f).coerceIn(0f, 1f)
                            swipeLevel = level
                            setter?.invoke(level)
                        }
                    }
                    if (mode == 1) { swipeActive = false; swipeRev++ }
                }
            },
    ) {
        // Double-tap ripple, carrying the accumulated amount (FR-R244-4)
        val side = rippleSide
        if (side != null) {
            Box(
                Modifier
                    .align(if (side == SwipeSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.33f)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                val secs = abs(rippleTotalMs) / 1000
                Text(
                    (if (rippleTotalMs < 0) "−" else "+") + "$secs s",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        // Brightness / volume pill at the edge it belongs to (FR-R244-5)
        val sw = swipeSide
        if (sw != null) {
            Box(
                Modifier
                    .align(if (sw == SwipeSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = if (portrait) 18.dp else 70.dp)
                    .width(30.dp).height(150.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(swipeLevel.coerceIn(0.02f, 1f)).background(Color.White, RoundedCornerShape(11.dp)))
                Canvas(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(12.dp)) {
                    if (sw == SwipeSide.LEFT) drawCircle(Color.White.copy(0.9f), size.minDimension / 3, center, style = Stroke(1.6.dp.toPx()))
                    else {
                        val p = Path().apply { moveTo(0f, size.height * 0.35f); lineTo(size.width * 0.3f, size.height * 0.35f); lineTo(size.width * 0.65f, 0f); lineTo(size.width * 0.65f, size.height); lineTo(size.width * 0.3f, size.height * 0.65f); lineTo(0f, size.height * 0.65f); close() }
                        drawPath(p, Color.White.copy(0.9f))
                    }
                }
            }
        }
        // Fit / fill toast (FR-R244-6): one word, ~900 ms
        AnimatedVisibility(visible = toastVisible, enter = fadeIn(tween(120)), exit = fadeOut(tween(220)), modifier = Modifier.align(Alignment.Center)) {
            Text(
                str(if (toastFill) "pl.fill" else "pl.fit"),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ─── Bottom sheet (FR-R244-10/11/12) ──────────────────────────────────────────

/** The handset's one sheet container: scrim (tap-away dismisses), rounded top, inset from the home
 *  indicator, at most ~72 % of the screen. Content decides the rest. */
@Composable
internal fun HandsetSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight * 0.72f
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(tween(180)),
            exit = slideOutVertically { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(CARD.copy(alpha = 0.97f))
                    // a sheet takes its own taps so they never reach the scrim or the video
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).background(Color.White.copy(0.25f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/** FR-R244-10 — the Subtitle size row inside the picker sheet: S · M · L, applied live; phone-local.
 *  [note] is the one line naming where the choice applies ("Applies on this phone only." here; R245's
 *  remote passes "Applies on {device}"), which is the only difference between the two destinations. */
@Composable
internal fun SubtitleSizeRow(colors: RaviloColors, current: Char, note: String, onPick: (Char) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(str("pl.sub_size"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf('S' to "pl.size_s", 'M' to "pl.size_m", 'L' to "pl.size_l").forEach { (c, key) ->
                    val on = c == current
                    Text(
                        str(key), color = if (on) Color(0xFF0A0C13) else Color.White.copy(0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                        modifier = Modifier
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onPick(c) })
                            .heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text(note, color = Color.White.copy(0.5f), fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(top = 6.dp))
    }
}

/** FR-R244-11 — the TV's horizontal episode rail as a vertical season list on a handset. */
@Composable
internal fun SeasonSheet(
    colors: RaviloColors,
    episodes: List<PlayerEpisodeEntry>,
    currentEpIndex: Int,
    onPick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem((currentEpIndex - 1).coerceAtLeast(0)) }
    val seasonLabel = episodes.firstOrNull()?.kicker?.substringBefore("·")?.trim() ?: ""
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(str("pl.episodes") + (if (seasonLabel.isNotBlank()) " · $seasonLabel" else ""), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                val current = i == currentEpIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onPick(i) })
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)).background(Color(0xFF1A1D28))
                            .border(if (current) 2.dp else 0.dp, if (current) colors.accent else Color.Transparent, RoundedCornerShape(7.dp)),
                    ) {
                        if (ep.stillUrls.any { it != null }) EpisodeTriptych(stillUrls = ep.stillUrls, modifier = Modifier.fillMaxSize())
                        if (ep.progressPct > 0f) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(0.25f))) {
                                Box(Modifier.fillMaxWidth(ep.progressPct.coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(ep.numberLabel, ep.title).joinToString(" · "),
                            color = Color.White, fontSize = 14.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold, fontFamily = Sora,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(ep.durationLabel.takeIf { it.isNotBlank() }, if (current) str("player.now_playing").lowercase().replaceFirstChar { it.uppercase() } else null, if (ep.watched) str("action.watched") else null).joinToString(" · "),
                            color = Color.White.copy(0.55f), fontSize = 13.sp, fontFamily = Sora, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
