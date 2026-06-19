package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.delay

private const val CHROME_HIDE_DELAY_MS = 3_000L
private const val NEAR_END_THRESHOLD_MS = 60_000L

@Composable
fun PlayerScreen(
    itemId: String,
    itemTitle: String,
    nextEpisodeId: String? = null,
    nextEpisodeLabel: String? = null,
    store: PlayerStore,
    onBack: () -> Unit,
    onNextEpisode: ((String) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sessionState by store.state.collectAsState()

    val player = remember { RaviloPlayer() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeHideRevision by remember { mutableLongStateOf(0L) }

    // Start session and load stream
    LaunchedEffect(itemId) {
        store.startSession(itemId, positionProvider = { positionMs }, isPausedProvider = { !isPlaying })
    }

    // Load player when ticket is ready
    LaunchedEffect(sessionState) {
        val s = sessionState
        if (s is PlayerSessionState.Ready) {
            player.load(s.ticket.hlsUrl ?: "", s.ticket.startPositionMs)
            player.play()
            isPlaying = true
        }
    }

    // Poll position
    LaunchedEffect(isPlaying) {
        while (true) {
            delay(500)
            positionMs = player.positionMs
            isPlaying = player.isPlaying
        }
    }

    // Auto-hide chrome after a period of inactivity
    LaunchedEffect(chromeHideRevision) {
        if (chromeHideRevision > 0) {
            delay(CHROME_HIDE_DELAY_MS)
            chromeVisible = false
        }
    }

    // Stop on exit
    DisposableEffect(Unit) {
        onDispose {
            store.stopSession(positionMs)
            player.release()
        }
    }

    val playerFR = remember { FocusRequester() }
    val nextEpFR = remember { FocusRequester() }

    LaunchedEffect(Unit) { playerFR.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .dpadFocusable(
                focusRequester = playerFR,
                onFocused = {},
                onSelect = {
                    if (isPlaying) { player.pause(); isPlaying = false }
                    else { player.play(); isPlaying = true }
                    chromeVisible = true; chromeHideRevision++
                },
                onLeft  = { player.seekTo((positionMs - 10_000L).coerceAtLeast(0)); chromeVisible = true; chromeHideRevision++ },
                onRight = { player.seekTo(positionMs + 10_000L); chromeVisible = true; chromeHideRevision++ },
                onUp    = { chromeVisible = true; chromeHideRevision++ },
                onBack  = { store.stopSession(positionMs); player.release(); onBack() },
            ),
    ) {
        // Player surface placeholder — actual platforms render below this layer
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))

        // Loading overlay
        if (sessionState is PlayerSessionState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = Color.White, fontSize = 18.sp)
            }
        }

        // Player chrome
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerChrome(
                title = itemTitle,
                positionMs = positionMs,
                durationMs = (sessionState as? PlayerSessionState.Ready)?.ticket?.run {
                    // estimate from position; Jellyfin PlaybackInfo would give this
                    0L
                } ?: 0L,
                isPlaying = isPlaying,
                nearEnd = nextEpisodeId != null && positionMs > 0,
                nextEpLabel = nextEpisodeLabel,
                nextEpFR = nextEpFR,
                onPlayPause = {
                    if (isPlaying) { player.pause(); isPlaying = false }
                    else { player.play(); isPlaying = true }
                },
                onSkipBack  = { player.seekTo((positionMs - 10_000L).coerceAtLeast(0)) },
                onSkipFwd   = { player.seekTo(positionMs + 10_000L) },
                onNextEp    = { nextEpisodeId?.let { onNextEpisode?.invoke(it) } },
                onBack      = { store.stopSession(positionMs); player.release(); onBack() },
            )
        }
    }
}

@Composable
private fun PlayerChrome(
    title: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    nearEnd: Boolean,
    nextEpLabel: String?,
    nextEpFR: FocusRequester,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipFwd: () -> Unit,
    onNextEp: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = RaviloTheme.colors

    // Bottom gradient + controls
    Box(modifier = Modifier.fillMaxSize()) {
        // Bottom gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(40.dp, 24.dp),
        ) {
            // Title
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // Seek bar
            val pct = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                if (pct > 0f) Box(modifier = Modifier.fillMaxWidth(pct).height(4.dp).background(colors.accent, RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.height(4.dp))
            // Time
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(positionMs.toTimeStr(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                if (durationMs > 0) Text(durationMs.toTimeStr(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            // Controls
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("−10s", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 28.sp)
                Spacer(Modifier.width(8.dp))
                Text("+10s", color = Color.White, fontSize = 14.sp)
                if (nearEnd && nextEpLabel != null) {
                    Spacer(Modifier.width(24.dp))
                    val fr = remember { FocusRequester() }
                    RaviloButton(
                        label = "Next · $nextEpLabel",
                        focusRequester = fr,
                        onSelect = onNextEp,
                    )
                }
            }
        }
    }
}

private fun Long.toTimeStr(): String {
    val s = this / 1000
    val m = s / 60
    val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format2(h, m % 60, s % 60)
    else "%d:%02d".format2(m, s % 60)
}

// Simple multiplatform string format for time display
private fun String.format2(vararg args: Long): String {
    var result = this
    args.forEach { n ->
        val ph = result.indexOf('%')
        if (ph < 0) return result
        val fmt = result.substring(ph, ph + 4)
        val padded = if (fmt.contains("02")) n.toString().padStart(2, '0') else n.toString()
        result = result.substring(0, ph) + padded + result.substring(ph + fmt.length)
    }
    return result
}
