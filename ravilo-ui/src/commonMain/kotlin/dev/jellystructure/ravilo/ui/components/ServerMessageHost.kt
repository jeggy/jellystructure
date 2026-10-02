package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.jellystructure.ravilo.ui.LocalServerMessages
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.delay

private data class ToastItem(val id: Long, val header: String?, val text: String, val durationMs: Long)

/**
 * R152 — Jellyfin dashboard "send message" toasts, top-right, stacking. Mounted once at the app root
 * (above every screen incl. the player) so it floats over navigation. Reads [LocalServerMessages];
 * a no-op if that local isn't provided (e.g. a preview/test host).
 *
 * Not part of the D-pad focus order — timeout is the primary dismissal on TV (FR-R152-4); pointer
 * click dismisses immediately where available.
 */
@Composable
fun ServerMessageHost(modifier: Modifier = Modifier) {
    val messages = LocalServerMessages.current ?: return
    val toasts = remember { mutableStateListOf<ToastItem>() }
    var nextId by remember { mutableStateOf(0L) }

    LaunchedEffect(messages) {
        messages.collect { envelope ->
            // R354 (FR-R354-9b/c) — the header on its own line, the sender's timeout (3–60 s) or R152's length rule.
            val notice = dev.jellystructure.shared.tv.serverNoticeOf(envelope) ?: return@collect
            toasts.add(ToastItem(nextId, notice.header, notice.text, notice.durationMs))
            nextId += 1
        }
    }

    if (toasts.isEmpty()) return
    // R354 (FR-R354-9d) — on a phone the message is a card at the top, under the status bar, the screen's width less
    // 16 dp a side: R152's top-right TV toast sat 100 dp down at a TV's size.
    if (dev.jellystructure.ravilo.ui.theme.LocalHandset.current) {
        Box(modifier = modifier.fillMaxSize().safeAreaPadding(includeIme = false), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp).widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (toast in toasts.asReversed()) {
                    key(toast.id) { ServerMessageToast(toast = toast, phone = true, onDismiss = { toasts.remove(toast) }) }
                }
            }
        }
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = 40.dp)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // Newest first — a new message appears nearest the top-right anchor (FR-R152-2).
            for (toast in toasts.asReversed()) {
                key(toast.id) {
                    ServerMessageToast(toast = toast, phone = false, onDismiss = { toasts.remove(toast) })
                }
            }
        }
    }
}

@Composable
private fun ServerMessageToast(toast: ToastItem, phone: Boolean, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(toast.id) {
        visible = true
    }
    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        visible = false
        delay(RaviloMotion.TOAST_TRANSITION_MS.toLong())
        onDismiss()
    }
    LaunchedEffect(toast.id) {
        delay(toast.durationMs)
        dismissed = true
    }

    val offsetX by animateDpAsState(
        targetValue = if (visible || phone) 0.dp else 36.dp,
        animationSpec = tween(RaviloMotion.TOAST_TRANSITION_MS),
    )
    // A phone's card drops in from the top; the TV's toast slides in from the right.
    val offsetY by animateDpAsState(
        targetValue = if (visible || !phone) 0.dp else (-24).dp,
        animationSpec = tween(RaviloMotion.TOAST_TRANSITION_MS),
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(RaviloMotion.TOAST_TRANSITION_MS),
    )
    // Countdown bar: full while entering, then drains linearly over the toast's own display duration.
    val barProgress by animateFloatAsState(
        targetValue = if (visible && !dismissed) 0f else 1f,
        animationSpec = if (visible && !dismissed) tween(toast.durationMs.toInt(), easing = LinearEasing) else snap(),
    )
    val colors = RaviloTheme.colors
    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout || (dev.jellystructure.ravilo.ui.isDesktopPlatform)

    // Both of the design's decorations — `border-left: 4px solid var(--accent)` and the absolutely
    // positioned `.rv-msg-bar` — are PAINTED, not laid out. That is the whole fix.
    //
    // They used to be child `Box`es with `fillMaxHeight()` and `fillMaxWidth()`, and a child that
    // fills takes the incoming maximum, which here is the whole screen: the card then sized itself to
    // its tallest child and rendered as a screen-high mostly-empty panel with a full-height stripe
    // down its side. The width one is the same mistake in the other axis — `fillMaxWidth()` pinned
    // every toast to the 460 dp maximum, so a short message could never hug its text the way
    // `min-width: 300px` in the design intends.
    //
    // In CSS neither decoration affects the box: a border is drawn on the box's own resolved size,
    // and `.rv-msg-bar` is `position: absolute`. `drawBehind` is the Compose equivalent — it reads the
    // card's resolved size and contributes nothing to measurement. It also makes the countdown a pure
    // redraw rather than a relayout.
    // Captured outside the draw scope: DrawScope has no composition access.
    val accentColor = colors.accent
    val stripeBrush = remember(colors.accent, colors.accentSecondary) {
        Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))
    }
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = offsetX.toPx()
                translationY = offsetY.toPx()
                this.alpha = alpha
            }
            // R337 — in a computer's window the card is a notification's size, not a TV's.
            .then(if (phone) Modifier.fillMaxWidth() else Modifier.widthIn(min = if (desk) 240.dp else 300.dp, max = if (desk) 360.dp else 460.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(colors.surface.copy(alpha = 0.93f))
            .border(1.dp, colors.textDim.copy(alpha = 0.25f), RoundedCornerShape(15.dp))
            .drawBehind {
                // design: `border-left: 4px solid var(--accent)` — the card's own height, always.
                drawRect(color = accentColor, size = Size(4.dp.toPx(), size.height))
                // design: `.rv-msg-bar` — 3px, bottom, drains left-to-right over the display duration.
                val barH = 3.dp.toPx()
                drawRect(
                    brush = stripeBrush,
                    topLeft = Offset(0f, size.height - barH),
                    size = Size(size.width * (1f - barProgress), barH),
                    alpha = 0.9f,
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!dismissed) dismissed = true },
    ) {
        // Three sizes: a computer's notification, a phone's card, a TV's toast.
        val small = desk || phone
        val font = if (desk) dev.jellystructure.ravilo.ui.theme.SystemUiFont else null
        val size = if (desk) 13.sp else if (phone) 15.sp else 19.sp
        val line = if (desk) 18.sp else if (phone) 20.sp else 26.sp
        Row(
            modifier = when {
                desk -> Modifier.padding(start = 15.dp, top = 11.dp, end = 14.dp, bottom = 13.dp)
                phone -> Modifier.padding(start = 16.dp, top = 12.dp, end = 14.dp, bottom = 14.dp)
                else -> Modifier.padding(start = 19.dp, top = 16.dp, end = 19.dp, bottom = 18.dp)
            },
            horizontalArrangement = Arrangement.spacedBy(if (small) 12.dp else 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (desk) 28.dp else if (phone) 34.dp else 40.dp)
                    .clip(RoundedCornerShape(if (small) 9.dp else 12.dp))
                    .background(stripeBrush),
                contentAlignment = Alignment.Center,
            ) {
                EnvelopeGlyph(Color.White, if (desk) 16.dp else if (phone) 20.dp else 24.dp)   // R315 — drawn, not typed
            }
            // R354 (FR-R354-9b) — the sender's header on its own line, bold, above the text.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                toast.header?.let { h ->
                    Text(h, color = colors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = size, lineHeight = line, fontFamily = font)
                }
                Text(toast.text, color = if (toast.header != null) colors.textSecondary else colors.text,
                    fontSize = size, lineHeight = line, fontFamily = font)
            }
        }
    }
}
