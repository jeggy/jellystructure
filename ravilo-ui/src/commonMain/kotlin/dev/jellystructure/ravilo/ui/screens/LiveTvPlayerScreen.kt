package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.PlayerVideoSurface
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.LiveTvChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)
private const val CHROME_HIDE_MS = 5_000L
private const val NUMBER_ENTRY_COMMIT_MS = 2_500L
private const val ZAP_BANNER_MS = 3_000L

/**
 * Phase R177 §D — full-screen live playback: a LIVE indicator + auto-hiding channel bar (logo ·
 * number · name · category · current program · times · live progress), zap (←/→) with a channel
 * banner, 0–9 number-entry tuning, and ↑ for a Now/Next mini-guide overlay. Reuses the shared
 * [RaviloPlayer]/[PlayerVideoSurface] byte-streaming engine — only the control plane
 * ([LiveTvPlayerStore]) is new (see its doc comment for why this isn't a branch of [PlayerScreen]).
 */
@Composable
fun LiveTvPlayerScreen(
    channelId: String,
    store: LiveTvPlayerStore,
    onBack: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val player = remember { RaviloPlayer() }
    // R194 — same relative-URL resolution PlayerScreen uses for the OS media session's artwork.
    val serverBaseUrl = LocalServerBaseUrl.current
    fun resolveImageUrl(url: String?): String? =
        url?.let { if (it.startsWith("/") && serverBaseUrl.isNotBlank()) "$serverBaseUrl$it" else it }
    val scope = rememberCoroutineScope()
    val rootFR = remember { FocusRequester() }

    var chromeVisible by remember { mutableStateOf(true) }
    var chromeRevision by remember { mutableStateOf(0) }
    var zapBanner by remember { mutableStateOf<LiveTvChannel?>(null) }
    var numberEntry by remember { mutableStateOf("") }
    var guideOpen by remember { mutableStateOf(false) }

    fun wake() { chromeVisible = true; chromeRevision++ }

    LaunchedEffect(channelId) { store.tune(channelId) }

    LaunchedEffect(state) {
        val s = state
        if (s is LiveTvPlayerState.Ready) {
            // R192 — feed the channel/current-program into the OS media session (TV-only; see RaviloPlayer.load doc).
            player.load(
                s.ticket.hlsUrl, startPositionMs = 0L, subtitles = emptyList(), audio = emptyList(),
                title = s.channel.name, subtitle = s.channel.currentProgram?.name, artworkUrl = resolveImageUrl(s.channel.logoUrl),
            )
            player.play()
            wake()
        }
    }

    // Auto-hide the chrome after a quiet period — never while the number-entry OSD or the
    // Now/Next overlay is open.
    LaunchedEffect(chromeRevision, chromeVisible, numberEntry, guideOpen) {
        if (!chromeVisible || numberEntry.isNotEmpty() || guideOpen) return@LaunchedEffect
        delay(CHROME_HIDE_MS)
        chromeVisible = false
    }
    // Number-entry auto-commits a short pause after the last digit (no explicit Enter needed).
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_ENTRY_COMMIT_MS)
        val n = numberEntry.toIntOrNull()
        if (n != null && !store.tuneByNumber(n)) wake() // unknown number — just drop back to normal chrome
        numberEntry = ""
    }
    // The zap banner clears itself a moment after the last zap.
    LaunchedEffect(zapBanner) {
        if (zapBanner == null) return@LaunchedEffect
        delay(ZAP_BANNER_MS)
        zapBanner = null
    }

    LaunchedEffect(Unit) { runCatching { rootFR.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose {
            store.stop()
            player.release()
        }
    }

    fun zap(direction: Int) {
        wake()
        val list = store.channelList
        val cur = (state as? LiveTvPlayerState.Ready)?.channel
        if (list.isNotEmpty() && cur != null) {
            val idx = list.indexOfFirst { it.channelId == cur.channelId }
            if (idx >= 0) zapBanner = list[((idx + direction) % list.size + list.size) % list.size]
        }
        store.zap(direction)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .dpadFocusable(
                focusRequester = rootFR,
                onLeft = { if (!guideOpen) zap(-1) },
                onRight = { if (!guideOpen) zap(1) },
                onUp = { if (!guideOpen) { guideOpen = true; chromeVisible = true } },
                onSelect = { if (!guideOpen) { if (chromeVisible) chromeVisible = false else wake() } },
                onBack = {
                    when {
                        guideOpen -> guideOpen = false
                        numberEntry.isNotEmpty() -> numberEntry = ""
                        else -> onBack()
                    }
                },
            )
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || guideOpen) return@onKeyEvent false
                val digit = DIGIT_KEYS[ev.key] ?: return@onKeyEvent false
                wake()
                numberEntry = (numberEntry + digit).takeLast(4)
                true
            },
    ) {
        PlayerVideoSurface(player, Modifier.fillMaxSize())

        if (state is LiveTvPlayerState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(str("livetv.tuning"), color = colors.text, fontSize = 14.sp)
                }
            }
        }
        if (state is LiveTvPlayerState.Error) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(str("livetv.tune_failed"), color = colors.text, fontSize = 16.sp)
            }
        }

        val ready = state as? LiveTvPlayerState.Ready
        AnimatedVisibility(
            visible = chromeVisible && ready != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (ready != null) ChannelBar(ready.channel)
        }
        AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Text(
                str("livetv.live_badge"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(20.dp)
                    .background(Color(0xFFE0263B), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (numberEntry.isNotEmpty()) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(numberEntry, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(str("livetv.enter_channel"), color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
        zapBanner?.let { ch ->
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).background(colors.surface, RoundedCornerShape(10.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(ch, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${ch.number} · ${ch.name}", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        ch.currentProgram?.name?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (guideOpen) {
            NowNextOverlay(
                channels = store.channelList,
                onSelect = { ch -> guideOpen = false; scope.launch { store.tune(ch.channelId) } },
                onDismiss = { guideOpen = false },
            )
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveTvChannel, size: androidx.compose.ui.unit.Dp) {
    val colors = RaviloTheme.colors
    val logo = channel.logoUrl
    if (!logo.isNullOrBlank()) {
        // Bug fix: a fixed SQUARE slot forced every logo's aspect ratio to 1:1 — fine for a
        // roughly-square brand mark, but a wide horizontal wordmark (e.g. a 3.2:1 lockup) got
        // shrunk down to an illegibly thin sliver to fit. Height stays fixed (matches the
        // surrounding row's other `size` usages); width now follows the image's own aspect
        // ratio, capped so an extreme logo can't blow out the row.
        Box(
            modifier = Modifier.height(size).widthIn(max = size * 3)
                .background(colors.surfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
        }
    } else {
        Box(
            modifier = Modifier.size(size).background(colors.surfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(channel.name.take(3).uppercase(), color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelBar(channel: LiveTvChannel) {
    val colors = RaviloTheme.colors
    val program = channel.currentProgram
    val next = channel.nextProgram
    val progress = if (program != null && program.endMs > program.startMs)
        ((kotlin.time.Clock.System.now().toEpochMilliseconds() - program.startMs).toFloat() / (program.endMs - program.startMs).toFloat()).coerceIn(0f, 1f)
    else 0f
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 28.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}", color = colors.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(channel.name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (channel.category.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(channel.category, color = colors.textDim, fontSize = 12.sp)
                    }
                }
                Text(program?.name ?: str("livetv.no_programs"), color = colors.textSecondary, fontSize = 14.sp)
                if (next != null) Text("${str("livetv.next")}: ${next.name}", color = colors.textDim, fontSize = 12.sp)
            }
        }
        if (program != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(colors.progressBg, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(colors.progressFill, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun NowNextOverlay(
    channels: List<LiveTvChannel>,
    onSelect: (LiveTvChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .dpadFocusable(onBack = onDismiss),
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).width(420.dp).fillMaxSize()
                .background(colors.surface).padding(20.dp),
        ) {
            Text(str("livetv.guide"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(channels, key = { it.channelId }) { ch ->
                    val fr = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (focused) colors.accentDim else Color.Transparent, RoundedCornerShape(8.dp))
                            .dpadFocusable(
                                focusRequester = fr,
                                onFocused = { focused = true },
                                onBlurred = { focused = false },
                                onSelect = { onSelect(ch) },
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelLogo(ch, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${ch.number} · ${ch.name}", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(ch.currentProgram?.name ?: str("livetv.no_programs"), color = colors.textSecondary, fontSize = 12.sp)
                            ch.nextProgram?.let { Text("${str("livetv.next")}: ${it.name}", color = colors.textDim, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}
