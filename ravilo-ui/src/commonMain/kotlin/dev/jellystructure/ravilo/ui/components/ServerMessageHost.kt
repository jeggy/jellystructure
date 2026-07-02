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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        delay(RaviloMotion.ToastTransitionMs.toLong())
        onDismiss()
    }
    LaunchedEffect(toast.id) {
        delay(toast.durationMs)
        dismissed = true
    }

    val offsetX by animateDpAsState(
        targetValue = if (visible) 0.dp else 36.dp,
        animationSpec = tween(RaviloMotion.ToastTransitionMs),
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(RaviloMotion.ToastTransitionMs),
    )
    // Countdown bar: full while entering, then drains linearly over the toast's own display duration.
    val barProgress by animateFloatAsState(
        targetValue = if (visible && !dismissed) 0f else 1f,
        animationSpec = if (visible && !dismissed) tween(toast.durationMs.toInt(), easing = LinearEasing) else snap(),
    )
    val colors = RaviloTheme.colors

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
                    .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                contentAlignment = Alignment.Center,
            ) {
                Text("✉", color = Color.White, fontSize = 21.sp)
            }
            Text(toast.text, color = colors.text, fontSize = 19.sp, lineHeight = 26.sp)
        }
        // Left accent stripe (design's border-left: 4px solid --accent).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(4.dp)
                .background(colors.accent),
        )
        // Countdown bar: full width while entering, drains left-to-right over the display duration.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .graphicsLayer {
                    scaleX = 1f - barProgress
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
        )
    }
}
