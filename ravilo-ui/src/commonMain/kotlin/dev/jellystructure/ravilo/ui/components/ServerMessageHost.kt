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
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.delay

private data class ToastItem(val id: Long, val text: String, val durationMs: Long)

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
            val header = envelope.header
            val text = buildString {
                if (!header.isNullOrBlank()) { append(header.trim()); append(" — ") }
                append(envelope.text)
            }.trim()
            if (text.isEmpty()) return@collect
            val duration = envelope.timeoutMs?.takeIf { it > 0 } ?: calculateToastDurationMs(text)
            toasts.add(ToastItem(nextId, text, duration))
            nextId += 1
        }
    }

    if (toasts.isEmpty()) return
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
                    ServerMessageToast(toast = toast, onDismiss = { toasts.remove(toast) })
                }
            }
        }
    }
}

@Composable
private fun ServerMessageToast(toast: ToastItem, onDismiss: () -> Unit) {
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
        targetValue = if (visible) 0.dp else 36.dp,
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
                this.alpha = alpha
            }
            .widthIn(min = 300.dp, max = 460.dp)
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
        Row(
            modifier = Modifier.padding(start = 19.dp, top = 16.dp, end = 19.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(stripeBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text("✉", color = Color.White, fontSize = 21.sp)
            }
            Text(toast.text, color = colors.text, fontSize = 19.sp, lineHeight = 26.sp)
        }
    }
}
