package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleEffect
import dev.jellystructure.ravilo.ui.seams.PlayerVideoSurface
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.SubTrack
import kotlinx.coroutines.delay
import kotlin.math.PI

// ─── Constants ────────────────────────────────────────────────────────────────

private const val CHROME_HIDE_MS = 3_600L
private const val NEXTUP_AT_MS   = 34_000L    // show next-up card when this many ms remain
private const val COUNTDOWN_SECS = 8
private const val SKIP_BACK_MS   = 10_000L
private const val SKIP_FWD_MS    = 30_000L
private const val POLL_MS        = 500L

// ─── Focus model ──────────────────────────────────────────────────────────────

private enum class PlFocus { SEEK_BAR, SKIP_BACK, PLAY, SKIP_FWD, TRACKS, NEXT_EP, BACK }
private enum class NuFocus { PLAY, STAY }

private fun transportOrder(hasNextEp: Boolean): List<PlFocus> =
    buildList {
        add(PlFocus.SEEK_BAR); add(PlFocus.SKIP_BACK); add(PlFocus.PLAY)
        add(PlFocus.SKIP_FWD); add(PlFocus.TRACKS)
        if (hasNextEp) add(PlFocus.NEXT_EP)
        add(PlFocus.BACK)
    }

// ─── Top-level composable ─────────────────────────────────────────────────────

@Composable
fun PlayerScreen(
    itemId: String,
    itemTitle: String,
    itemKicker: String? = null,
    nextEpisodeId: String? = null,
    nextEpisodeLabel: String? = null,
    episodes: List<PlayerEpisodeEntry>? = null,
    currentEpIndex: Int = 0,
    store: PlayerStore,
    onBack: () -> Unit,
    onNavigateToEpisode: ((String) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sessionState by store.state.collectAsState()

    val player = remember { RaviloPlayer() }

    // Playback state — polled every 500 ms from the player
    var positionMs   by remember { mutableLongStateOf(0L) }
    var durationMs   by remember { mutableLongStateOf(0L) }
    var bufferedMs   by remember { mutableLongStateOf(0L) }
    var isPlaying    by remember { mutableStateOf(false) }
    var audioTracks  by remember { mutableStateOf<List<PlayerAudioTrack>>(emptyList()) }

    // Chrome visibility — bumping chromeRevision restarts the auto-hide timer
    var chromeVisible  by remember { mutableStateOf(true) }
    var chromeRevision by remember { mutableLongStateOf(0L) }

    // Focus
    var focus    by remember { mutableStateOf(PlFocus.PLAY) }

    // Scrubbing
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos  by remember { mutableLongStateOf(0L) }

    // Track picker
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerTab  by remember { mutableIntStateOf(0) }   // 0=audio, 1=subs
    var pickerIdx  by remember { mutableIntStateOf(0) }
    var selectedAudio by remember { mutableIntStateOf(0) }
    var selectedSub   by remember { mutableIntStateOf(-1) } // -1 = off

    // Next-up card
    var nextUpVisible by remember { mutableStateOf(false) }
    var countdown     by remember { mutableIntStateOf(COUNTDOWN_SECS) }
    var nuFocus       by remember { mutableStateOf(NuFocus.PLAY) }

    // Episode rail
    var epRailOpen  by remember { mutableStateOf(false) }
    var focusedEpIdx by remember { mutableIntStateOf(currentEpIndex) }

    // Pause flash
    var pauseFlash by remember { mutableStateOf(false) }
    var pauseFlashIsPlay by remember { mutableStateOf(true) }

    // Subtitle options derived from the stream ticket
    val subtitleTracks: List<SubTrack?> = remember(sessionState) {
        val subs = (sessionState as? PlayerSessionState.Ready)?.ticket?.subtitles ?: emptyList()
        listOf(null) + subs   // null = "Off"
    }

    // ─── Helper functions ───────────────────────────────────────────────────

    fun wake() { chromeVisible = true; chromeRevision++ }
    fun scheduleHide() { chromeRevision++ }

    fun skip(ms: Long) {
        val newPos = (positionMs + ms).coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(newPos)
        positionMs = newPos
        wake()
    }

    fun commitScrub() {
        player.seekTo(scrubPos)
        positionMs = scrubPos
        scrubbing = false
        wake()
    }

    fun togglePlay() {
        if (isPlaying) {
            player.pause(); isPlaying = false
            pauseFlashIsPlay = false
        } else {
            player.play(); isPlaying = true
            pauseFlashIsPlay = true
        }
        pauseFlash = true
        wake()
    }

    fun advanceNext() {
        nextUpVisible = false
        nextEpisodeId?.let { onNavigateToEpisode?.invoke(it) }
    }

    fun stayThrough() { nextUpVisible = false; chromeVisible = true; scheduleHide() }

    fun chooseEpisode() {
        if (focusedEpIdx == currentEpIndex) { epRailOpen = false; return }
        episodes?.getOrNull(focusedEpIdx)?.id?.let { onNavigateToEpisode?.invoke(it) }
    }

    fun choosePick() {
        if (pickerTab == 0) {
            selectedAudio = pickerIdx
            player.selectAudioTrack(pickerIdx)
        } else {
            val sub = subtitleTracks.getOrNull(pickerIdx)
            selectedSub = if (sub == null) -1 else pickerIdx - 1
            player.selectSubtitleTrack(sub?.url)
        }
        pickerOpen = false
        wake()
    }

    fun scrubStep() = SKIP_BACK_MS.coerceAtMost(maxOf(5_000L, (durationMs * 0.012).toLong()))

    // ─── Effects ────────────────────────────────────────────────────────────

    // Start the playback session
    LaunchedEffect(itemId) {
        store.startSession(itemId, positionProvider = { positionMs }, isPausedProvider = { !isPlaying })
    }

    // Load player when the StreamTicket is ready
    LaunchedEffect(sessionState) {
        val s = sessionState as? PlayerSessionState.Ready ?: return@LaunchedEffect
        val streamUrl = s.ticket.hlsUrl
            ?: "${s.ticket.jellyfinBaseUrl}/Videos/${s.ticket.itemId}/stream.${s.ticket.container}?api_key=${s.ticket.accessToken}"
        player.load(streamUrl, s.ticket.startPositionMs, s.ticket.subtitles)
        player.play()
        isPlaying = true
        wake()
    }

    // Poll player state
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_MS)
            positionMs  = player.positionMs
            durationMs  = player.durationMs
            bufferedMs  = player.bufferedMs
            isPlaying   = player.isPlaying
            audioTracks = player.audioTracks

            // Near-end → show next-up card
            if (nextEpisodeId != null && durationMs > 0 && !nextUpVisible && !player.isEnded) {
                if ((durationMs - positionMs) in 1..NEXTUP_AT_MS) {
                    nextUpVisible = true
                    nuFocus = NuFocus.PLAY
                }
            }

            // Natural end with no next episode → exit
            if (player.isEnded && nextEpisodeId == null) {
                onBack(); break
            }
            if (player.isEnded && !nextUpVisible) {
                nextUpVisible = true; nuFocus = NuFocus.PLAY
            }
        }
    }

    // Auto-hide chrome timer (restarted every time chromeRevision bumps)
    LaunchedEffect(chromeRevision) {
        if (chromeRevision == 0L) return@LaunchedEffect
        delay(CHROME_HIDE_MS)
        if (!pickerOpen && !nextUpVisible && !epRailOpen) chromeVisible = false
    }

    // Next-up countdown
    LaunchedEffect(nextUpVisible) {
        if (!nextUpVisible) return@LaunchedEffect
        countdown = COUNTDOWN_SECS
        repeat(COUNTDOWN_SECS) {
            delay(1_000)
            countdown--
        }
        if (nextUpVisible) advanceNext()
    }

    // Pause-flash auto-dismiss
    LaunchedEffect(pauseFlash) {
        if (!pauseFlash) return@LaunchedEffect
        delay(550)
        pauseFlash = false
    }

    // Pause/resume when activity goes to background (Home button) and returns
    PlayerLifecycleEffect(player, wasPlaying = { isPlaying })

    // Cleanup on exit — stop the Jellyfin playback session and release the player engine
    DisposableEffect(Unit) {
        onDispose {
            store.stopSession(positionMs)
            player.release()
        }
    }

    // ─── Key handling ────────────────────────────────────────────────────────

    val playerFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { playerFR.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .dpadFocusable(
                focusRequester = playerFR,
                onFocused = {},
                onLeft = {
                    wake()
                    when {
                        nextUpVisible -> nuFocus = NuFocus.PLAY
                        epRailOpen -> focusedEpIdx = (focusedEpIdx - 1).coerceAtLeast(0)
                        pickerOpen -> { pickerTab = 0; pickerIdx = selectedAudio }
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos - scrubStep()).coerceAtLeast(0L)
                        }
                        else -> {
                            val order = transportOrder(nextEpisodeId != null)
                            val idx = order.indexOf(focus)
                            if (idx > 0) focus = order[idx - 1]
                        }
                    }
                },
                onRight = {
                    wake()
                    when {
                        nextUpVisible -> nuFocus = NuFocus.STAY
                        epRailOpen -> episodes?.let { focusedEpIdx = (focusedEpIdx + 1).coerceAtMost(it.size - 1) }
                        pickerOpen -> {
                            pickerTab = 1
                            pickerIdx = if (selectedSub < 0) 0 else (selectedSub + 1).coerceAtMost(subtitleTracks.lastIndex)
                        }
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos + scrubStep()).coerceAtMost(durationMs)
                        }
                        else -> {
                            val order = transportOrder(nextEpisodeId != null)
                            val idx = order.indexOf(focus)
                            if (idx < order.lastIndex) focus = order[idx + 1]
                        }
                    }
                },
                onUp = {
                    wake()
                    when {
                        epRailOpen -> { epRailOpen = false; scheduleHide() }
                        pickerOpen -> if (pickerIdx > 0) pickerIdx--
                        nextUpVisible -> {}
                        focus != PlFocus.SEEK_BAR -> focus = PlFocus.SEEK_BAR
                        else -> {}
                    }
                },
                onDown = {
                    wake()
                    when {
                        nextUpVisible -> {}
                        epRailOpen -> {}
                        pickerOpen -> {
                            val size = if (pickerTab == 0) audioTracks.size.coerceAtLeast(1) else subtitleTracks.size
                            if (pickerIdx < size - 1) pickerIdx++
                        }
                        focus == PlFocus.SEEK_BAR -> {
                            if (scrubbing) commitScrub()
                            focus = PlFocus.PLAY
                        }
                        episodes != null -> { epRailOpen = true; chromeVisible = true }
                        else -> {}
                    }
                },
                onSelect = {
                    wake()
                    when {
                        nextUpVisible -> { if (nuFocus == NuFocus.PLAY) advanceNext() else stayThrough() }
                        epRailOpen -> chooseEpisode()
                        pickerOpen -> choosePick()
                        focus == PlFocus.SEEK_BAR -> {
                            if (scrubbing) commitScrub() else { scrubbing = true; scrubPos = positionMs }
                        }
                        focus == PlFocus.PLAY     -> togglePlay()
                        focus == PlFocus.SKIP_BACK -> skip(-SKIP_BACK_MS)
                        focus == PlFocus.SKIP_FWD  -> skip(SKIP_FWD_MS)
                        focus == PlFocus.TRACKS    -> {
                            pickerOpen = true
                            pickerIdx = if (pickerTab == 0) selectedAudio
                                        else (selectedSub + 1).coerceIn(0, subtitleTracks.lastIndex)
                        }
                        focus == PlFocus.NEXT_EP   -> advanceNext()
                        focus == PlFocus.BACK      -> onBack()
                        else -> {}
                    }
                },
                onBack = {
                    when {
                        pickerOpen    -> { pickerOpen = false; wake() }
                        epRailOpen    -> { epRailOpen = false; wake() }
                        nextUpVisible -> stayThrough()
                        scrubbing     -> { scrubbing = false; wake() }
                        else          -> onBack()
                    }
                },
            )
    ) {
        // ── Platform video surface (TextureView on Android, <video> element on WASM) ───
        PlayerVideoSurface(player, Modifier.fillMaxSize())

        // ── Dim scrim (deepens when chrome is up or paused) ──────────────────
        val dimAlpha = when {
            chromeVisible && !isPlaying -> 0.50f
            chromeVisible               -> 0.34f
            else                        -> 0f
        }
        if (dimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
        }

        // ── Loading overlay ───────────────────────────────────────────────────
        if (sessionState is PlayerSessionState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BufferingSpinner(colors)
                Spacer(Modifier.height(48.dp))
                Text(str("loading"), color = Color.White.copy(0.7f), fontSize = 18.sp)
            }
        }

        // ── Buffering spinner (while playing but stalled) ────────────────────
        // (In a real integration the engine signals buffering; we skip this for now)

        // ── Center pause flash ────────────────────────────────────────────────
        AnimatedVisibility(pauseFlash, enter = fadeIn(tween(80)), exit = fadeOut(tween(450))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.42f))
                        .border(2.dp, Color.White.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (pauseFlashIsPlay) "▶" else "⏸",
                        color = Color.White,
                        fontSize = 36.sp,
                    )
                }
            }
        }

        // ── Player chrome (auto-hiding transport + metadata) ──────────────────
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
        ) {
            PlayerChrome(
                colors          = colors,
                itemTitle       = itemTitle,
                itemKicker      = itemKicker,
                positionMs      = positionMs,
                durationMs      = durationMs,
                bufferedMs      = bufferedMs,
                scrubbing       = scrubbing,
                scrubPos        = scrubPos,
                isPlaying       = isPlaying,
                focus           = focus,
                hasNextEp       = nextEpisodeId != null,
                isSeries        = episodes != null,
                epRailOpen      = epRailOpen,
                pickerOpen      = pickerOpen,
                nextUpVisible   = nextUpVisible,
                directPlay      = (sessionState as? PlayerSessionState.Ready)?.ticket?.directPlay ?: true,
                container       = (sessionState as? PlayerSessionState.Ready)?.ticket?.container ?: "",
            )
        }

        // ── Track picker popup (Audio / Subtitles) ────────────────────────────
        AnimatedVisibility(
            visible = pickerOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            TrackPicker(
                colors        = colors,
                pickerTab     = pickerTab,
                pickerIdx     = pickerIdx,
                audioTracks   = audioTracks,
                subtitleTracks = subtitleTracks,
                selectedAudio = selectedAudio,
                selectedSub   = selectedSub,
            )
        }

        // ── Next-up card ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = nextUpVisible,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            NextUpCard(
                colors         = colors,
                nextEpLabel    = nextEpisodeLabel,
                countdown      = countdown,
                nuFocus        = nuFocus,
            )
        }

        // ── Episode rail (series only) ────────────────────────────────────────
        val epList = episodes
        if (epList != null) {
            AnimatedVisibility(
                visible = epRailOpen,
                enter = slideInVertically { it } + fadeIn(tween(300)),
                exit = slideOutVertically { it } + fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                EpisodeRail(
                    colors          = colors,
                    episodes        = epList,
                    currentEpIndex  = currentEpIndex,
                    focusedEpIdx    = focusedEpIdx,
                )
            }
        }
    }
}

// ─── Player chrome overlay ────────────────────────────────────────────────────

@Composable
private fun PlayerChrome(
    colors: RaviloColors,
    itemTitle: String,
    itemKicker: String?,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    isPlaying: Boolean,
    focus: PlFocus,
    hasNextEp: Boolean,
    isSeries: Boolean,
    epRailOpen: Boolean,
    pickerOpen: Boolean,
    nextUpVisible: Boolean,
    directPlay: Boolean,
    container: String,
) {
    val topScrim = remember {
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))
    }
    val botScrim = remember {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f)))
    }

    Box(Modifier.fillMaxSize()) {
        // Top gradient
        Box(Modifier.fillMaxWidth().height(230.dp).align(Alignment.TopCenter).background(topScrim))
        // Bottom gradient
        Box(Modifier.fillMaxWidth().height(420.dp).align(Alignment.BottomCenter).background(botScrim))

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(focused = focus == PlFocus.BACK)
            Spacer(Modifier.weight(1f))
            StreamPill(colors = colors, directPlay = directPlay, container = container)
        }

        // Bottom transport
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 36.dp),
        ) {
            // Metadata
            itemKicker?.let {
                Text(
                    text = it.uppercase(),
                    color = colors.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = itemTitle,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SpaceGrotesk,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(14.dp))

            // Seek row
            SeekRow(
                colors     = colors,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                scrubbing  = scrubbing,
                scrubPos   = scrubPos,
                barFocused = focus == PlFocus.SEEK_BAR,
            )

            Spacer(Modifier.height(12.dp))

            // Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SkipButton(label = "−10s", focused = focus == PlFocus.SKIP_BACK)
                PlayPauseButton(isPlaying = isPlaying, focused = focus == PlFocus.PLAY)
                SkipButton(label = "+30s", focused = focus == PlFocus.SKIP_FWD)
                Spacer(Modifier.weight(1f))
                TrackButton(label = "Audio & Subs", focused = focus == PlFocus.TRACKS)
                if (hasNextEp) TrackButton(label = "▶▶ Next", focused = focus == PlFocus.NEXT_EP)
            }

            // Episode chip (series, only when rail/picker/nextup are closed)
            if (isSeries && !epRailOpen && !pickerOpen && !nextUpVisible) {
                Spacer(Modifier.height(20.dp))
                EpisodeChip(colors)
            }
        }
    }
}

// ─── Seek row ─────────────────────────────────────────────────────────────────

@Composable
private fun SeekRow(
    colors: RaviloColors,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    barFocused: Boolean,
) {
    val timeColor = Color.White.copy(alpha = 0.8f)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = (if (scrubbing) scrubPos else positionMs).toTimestamp(),
            color = timeColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.weight(1f)) {
            SeekBar(
                colors      = colors,
                positionMs  = positionMs,
                durationMs  = durationMs,
                bufferedMs  = bufferedMs,
                scrubbing   = scrubbing,
                scrubPos    = scrubPos,
                focused     = barFocused,
            )
        }
        if (durationMs > 0) {
            Text(
                text = durationMs.toTimestamp(),
                color = timeColor.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ─── Seek bar (Canvas) ────────────────────────────────────────────────────────

@Composable
private fun SeekBar(
    colors: RaviloColors,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    focused: Boolean,
) {
    val played   = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val scrubFrac = if (durationMs > 0 && scrubbing) (scrubPos.toFloat() / durationMs).coerceIn(0f, 1f) else played

    val barH by animateDpAsState(if (focused) 12.dp else 8.dp, label = "barH")
    val accent   = colors.accent
    val accentS  = colors.accentSecondary
    val ringColor = colors.focusRing

    Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val barHPx = barH.toPx()
        val y = center.y
        val w = size.width
        val corner = CornerRadius(barHPx / 2)
        val barTop = y - barHPx / 2

        // Background track
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, barTop), Size(w, barHPx), corner)

        // Buffered
        if (buffered > 0f)
            drawRoundRect(Color.White.copy(alpha = 0.30f), Offset(0f, barTop), Size(w * buffered, barHPx), corner)

        // Played (accent gradient)
        if (played > 0f) {
            val grad = Brush.linearGradient(listOf(accent, accentS), start = Offset.Zero, end = Offset(w, 0f))
            drawRoundRect(grad, Offset(0f, barTop), Size(w * played, barHPx), corner)
        }

        // Scrub ghost bar
        if (scrubbing) {
            val ghostX = w * scrubFrac
            drawRect(Color.White, Offset(ghostX - 2.dp.toPx(), y - 13.dp.toPx()), Size(4.dp.toPx(), 26.dp.toPx()))
        }

        // Handle
        val handleR = if (focused) 13.dp.toPx() else 10.dp.toPx()
        val handleX = w * played
        if (focused) {
            drawCircle(ringColor.copy(alpha = 0.45f), handleR + 6.dp.toPx(), Offset(handleX, y))
        }
        drawCircle(Color.White, handleR, Offset(handleX, y))
    }
}

// ─── Control buttons ──────────────────────────────────────────────────────────

@Composable
private fun PlayPauseButton(isPlaying: Boolean, focused: Boolean) {
    val colors = RaviloTheme.colors
    val size by animateDpAsState(if (focused) 60.dp else 54.dp, label = "ppScale")
    Box(
        modifier = Modifier
            .size(size)
            .then(if (focused) Modifier.shadow(16.dp, CircleShape, spotColor = colors.focusGlow) else Modifier)
            .clip(CircleShape)
            .background(Color.White)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) colors.focusRing else Color.Transparent,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isPlaying) "⏸" else "▶",
            color = Color.Black,
            fontSize = if (isPlaying) 19.sp else 20.sp,
        )
    }
}

@Composable
private fun SkipButton(label: String, focused: Boolean) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .scale(if (focused) 1.06f else 1f)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .then(if (focused) Modifier.shadow(14.dp, RoundedCornerShape(12.dp), spotColor = colors.focusGlow) else Modifier)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TrackButton(label: String, focused: Boolean) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(11.dp))
            .then(if (focused) Modifier.shadow(14.dp, RoundedCornerShape(11.dp), spotColor = colors.focusGlow) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BackButton(focused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (focused) Color.White else Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = if (focused) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = str("action.back"),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StreamPill(colors: RaviloColors, directPlay: Boolean, container: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillBox {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (directPlay) Color(0xFF2DD49A) else colors.accentSecondary)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (directPlay) "DIRECT PLAY" else "HLS",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
        if (container.isNotEmpty()) {
            PillBox {
                Text(
                    text = container.uppercase(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun PillBox(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun EpisodeChip(colors: RaviloColors) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(30.dp))
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(colors.accentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("↓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(str("detail.episodes"), color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Track picker popup ───────────────────────────────────────────────────────

@Composable
private fun TrackPicker(
    colors: RaviloColors,
    pickerTab: Int,
    pickerIdx: Int,
    audioTracks: List<PlayerAudioTrack>,
    subtitleTracks: List<SubTrack?>,
    selectedAudio: Int,
    selectedSub: Int,
) {
    Box(
        modifier = Modifier
            .padding(end = 48.dp, bottom = 36.dp)
            .width(520.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1119).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Column {
            // Tabs
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerTab("Audio", pickerTab == 0)
                PickerTab("Subtitles", pickerTab == 1)
            }
            Spacer(Modifier.height(14.dp))

            // Options
            val effectiveAudio = if (audioTracks.isEmpty()) listOf(PlayerAudioTrack(0, "Default", null)) else audioTracks
            val items: List<Triple<String, String?, String?>> = if (pickerTab == 0) {
                effectiveAudio.map { Triple(it.label, it.language, null) }
            } else {
                subtitleTracks.mapIndexed { i, sub ->
                    if (sub == null) Triple("Off", null, null)
                    else Triple(sub.label ?: sub.language ?: "Track $i", sub.language, if (sub.forced) "FORCED" else if (sub.isDefault) "DEFAULT" else null)
                }
            }
            val selectedInTab = if (pickerTab == 0) selectedAudio else selectedSub + 1

            items.forEachIndexed { i, (label, lang, flag) ->
                PickerOption(
                    label   = label,
                    lang    = lang,
                    flag    = flag,
                    selected = i == selectedInTab,
                    focused  = i == pickerIdx,
                    colors  = colors,
                )
                if (i < items.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PickerTab(label: String, active: Boolean) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Transparent)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) colors.accent else Color.White.copy(0.18f),
                shape = RoundedCornerShape(30.dp),
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) colors.text else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PickerOption(
    label: String,
    lang: String?,
    flag: String?,
    selected: Boolean,
    focused: Boolean,
    colors: RaviloColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) colors.focusRing.copy(0.7f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Radio tick
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(2.dp, if (selected) colors.accent else Color.White.copy(0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (lang != null) Text(lang, color = colors.textSecondary, fontSize = 12.sp)
        }
        if (flag != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, Color.White.copy(0.22f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(flag, color = colors.accentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
    }
}

// ─── Next-up card ─────────────────────────────────────────────────────────────

@Composable
private fun NextUpCard(
    colors: RaviloColors,
    nextEpLabel: String?,
    countdown: Int,
    nuFocus: NuFocus,
) {
    Box(
        modifier = Modifier
            .padding(end = 48.dp, bottom = 36.dp)
            .width(560.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1119).copy(alpha = 0.95f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Column {
            Text(
                text = "UP NEXT",
                color = colors.accentSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Thumbnail placeholder + countdown ring
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1A1D28)),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Box(modifier = Modifier.padding(8.dp)) {
                        CountdownRing(colors, countdown)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    nextEpLabel?.let {
                        Text(it, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = str("detail.episode"),
                        color = colors.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGrotesk,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NuButton(
                            label = "▶  Play  in ${countdown}s",
                            focused = nuFocus == NuFocus.PLAY,
                            isPrimary = true,
                            colors = colors,
                        )
                        NuButton(
                            label = "Watch credits",
                            focused = nuFocus == NuFocus.STAY,
                            isPrimary = false,
                            colors = colors,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownRing(colors: RaviloColors, countdown: Int) {
    Canvas(Modifier.size(42.dp)) {
        val r = 17.dp.toPx()
        val stroke = 4.dp.toPx()
        val circumference = (2 * PI * r).toFloat()
        drawCircle(Color.White.copy(0.25f), r, style = Stroke(stroke))
        if (countdown > 0) {
            drawArc(
                color = colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * countdown.toFloat() / COUNTDOWN_SECS,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun NuButton(label: String, focused: Boolean, isPrimary: Boolean, colors: RaviloColors) {
    Box(
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused && isPrimary -> Color.White
                    isPrimary -> Color.White.copy(alpha = 0.15f)
                    focused -> Color.White.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.focusRing else Color.White.copy(0.15f),
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (focused) Modifier.shadow(14.dp, RoundedCornerShape(10.dp), spotColor = colors.focusGlow) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused && isPrimary) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─── Episode rail panel ───────────────────────────────────────────────────────

@Composable
private fun EpisodeRail(
    colors: RaviloColors,
    episodes: List<PlayerEpisodeEntry>,
    currentEpIndex: Int,
    focusedEpIdx: Int,
) {
    val gradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.28f to Color.Black.copy(alpha = 0.72f),
                1f to Color.Black.copy(alpha = 0.97f),
            )
        )
    }
    val listState = rememberLazyListState()
    LaunchedEffect(focusedEpIdx) {
        listState.animateScrollToItem(focusedEpIdx.coerceAtLeast(0))
    }

    Box(modifier = Modifier.fillMaxWidth().background(gradient)) {
        Column(modifier = Modifier.padding(top = 32.dp, bottom = 40.dp)) {
            // Header
            val seasonLabel = episodes.firstOrNull()?.kicker?.substringBefore("·")?.trim() ?: ""
            Row(
                modifier = Modifier.padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(seasonLabel, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                Text(
                    "← → switch  ·  ↵ play  ·  ↑ back",
                    color = Color.White.copy(0.45f),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(14.dp))

            // Episode cards
            LazyRow(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(episodes) { idx, ep ->
                    val isCurrent = idx == currentEpIndex
                    val isFocused = idx == focusedEpIdx
                    EpisodeRailCard(ep, isCurrent, isFocused, colors)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRailCard(
    ep: PlayerEpisodeEntry,
    isCurrent: Boolean,
    isFocused: Boolean,
    colors: RaviloColors,
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .scale(if (isFocused) 1.05f else 1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF1A1D28))
                .border(
                    width = if (isFocused) 2.dp else if (isCurrent) 2.dp else 0.dp,
                    color = if (isFocused) colors.focusRing else if (isCurrent) colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                ),
        ) {
            // Episode number
            Text(
                text = ep.n.toString(),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SpaceGrotesk,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
            // NOW PLAYING badge
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.accentGradient)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("NOW PLAYING", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
            // Progress bar
            if (ep.progressPct > 0f || ep.watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(0.30f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (ep.watched) 1f else ep.progressPct)
                            .fillMaxHeight()
                            .background(if (ep.watched) Color(0xFF2DD49A) else colors.accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${ep.n}. ${ep.title}",
            color = if (isCurrent || isFocused) Color.White else Color.White.copy(0.65f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (ep.durationLabel.isNotEmpty()) {
            Text(ep.durationLabel, color = Color.White.copy(0.40f), fontSize = 11.sp)
        }
    }
}

// ─── Buffering spinner ────────────────────────────────────────────────────────

@Composable
private fun BufferingSpinner(colors: RaviloColors) {
    val rotation by rememberInfiniteTransition(label = "buf")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rot")
    Canvas(Modifier.size(64.dp)) {
        drawCircle(Color.White.copy(0.16f), radius = 32.dp.toPx() - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
        drawArc(
            color      = colors.accent,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter  = false,
            style      = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

private fun Long.toTimestamp(): String {
    val totalSecs = (this / 1000).coerceAtLeast(0)
    val h = (totalSecs / 3600).toInt()
    val m = ((totalSecs % 3600) / 60).toInt()
    val s = (totalSecs % 60).toInt()
    return if (h > 0) "$h:${m.pad2()}:${s.pad2()}" else "$m:${s.pad2()}"
}

private fun Int.pad2() = toString().padStart(2, '0')
