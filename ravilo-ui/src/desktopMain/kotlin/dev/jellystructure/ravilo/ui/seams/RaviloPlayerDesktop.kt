package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.desktop.DesktopLog
import dev.jellystructure.ravilo.ui.desktop.DesktopEngine
import dev.jellystructure.ravilo.ui.desktop.DesktopEngines
import dev.jellystructure.ravilo.ui.desktop.MacNative
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack
import dev.jellystructure.shared.tv.VttCue
import dev.jellystructure.shared.tv.activeCueText
import dev.jellystructure.shared.tv.parseVtt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI

/**
 * R329 (FR-R329-2) — the Mac's film player: AVPlayer through [MacPlayer], the picture copied into Compose
 * ([PlayerVideoSurface]), text subtitles drawn by Compose from the ticket's WebVTT (FR-R329-5), audio picks as
 * rendition switches in AVPlayer's audible group (FR-R329-4). On Linux the engine is mpv (R335): it renders the
 * subtitles itself and lists the container's tracks, so the cue overlay and the ticket's lists step aside there.
 * Without an engine (a Mac without its library, a Linux without libmpv) every load fails at once, so the player
 * shows R237's card rather than waiting forever.
 */
actual class RaviloPlayer actual constructor() {
    private val engine: DesktopEngine = DesktopEngines.film()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var noEngineFailure = false
    private var streamUrl = ""
    private var textTracks: List<SubTrack> = emptyList()
    private var audioMeta: List<AudioTrack> = emptyList()
    private var subJob: Job? = null
    private var watchJob: Job? = null
    private var restoredAfterRecreate = 0
    /** When the last [load] happened — see [playbackFailed]. */
    private var loadedAtMs = 0L

    // Read by the surface every frame.
    internal var cues: List<VttCue> by mutableStateOf(emptyList())
        private set
    internal var captionScale: Float by mutableStateOf(1f)
        private set

    // FR-R329-10 — the Now Playing card while a film plays.
    private var npTitle = ""
    private var npKicker: String? = null
    private var npArtwork: String? = null
    private val nowPlaying = MacNowPlaying.Target { command, seconds ->
        when (command) {
            MacNowPlaying.Command.PLAY -> play()
            MacNowPlaying.Command.PAUSE -> pause()
            MacNowPlaying.Command.TOGGLE -> if (isPlaying) pause() else play()
            MacNowPlaying.Command.SEEK_TO -> seekTo((seconds * 1000).toLong())
            MacNowPlaying.Command.SKIP_FORWARD -> seekTo(positionMs + (seconds * 1000).toLong())
            MacNowPlaying.Command.SKIP_BACK -> seekTo(positionMs - (seconds * 1000).toLong())
            else -> Unit
        }
    }

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String?, artworkUrl: String?) {
        this.streamUrl = streamUrl
        // FR-R329-5 — the text tracks Compose draws; a burn-in candidate (`encode`) is the common player's R282 restream.
        textTracks = subtitles.filter { it.url != null && it.deliveryMethod != "encode" && it.deliveryMethod != "hls" }
        audioMeta = audio
        subJob?.cancel()
        cues = emptyList()
        loadedAtMs = System.currentTimeMillis()
        if (!engine.available) { noEngineFailure = true; return }
        noEngineFailure = false
        println("${DesktopLog.stamp()} [player] load ${streamUrl.substringBefore('?')} start=${startPositionMs}ms text subtitles=${textTracks.size} audio=${audio.size}")
        engine.load(streamUrl, startMs = startPositionMs)
        // R335 (FR-R335-5) — mpv draws the ticket's sidecar files itself; the embedded ones it already has.
        if (engine.rendersSubtitles) textTracks.forEach { t -> t.url?.let { engine.addSubtitle(absolute(it), t.label, t.language) } }
        npTitle = title; npKicker = subtitle; npArtwork = artworkUrl
        MacNowPlaying.claim(nowPlaying, MacNowPlaying.Mode.FILM)
        MacNowPlaying.artwork(nowPlaying, artworkUrl)
        startWatch()
    }

    /** Once a second while loaded: the Now Playing card and the display assertion follow the player (FR-R329-8/10). */
    private fun startWatch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var awake = false
            var ticks = 0
            while (isActive && engine.loaded) {
                val s = engine.state
                // Until the first frame (and whenever it fails or waits), AVFoundation's own account, once a second.
                if (!s.firstFrame || s.failed || (s.buffering && ticks % 5 == 0)) println("${DesktopLog.stamp()} [player] ${engine.debug()}")
                ticks++
                val playing = s.wantsPlay && !s.ended && !s.failed
                if (playing != awake) { awake = playing; MacNative.lib?.ravilo_display_keep_awake(if (playing) 1 else 0) }
                MacNowPlaying.update(nowPlaying, npTitle, npKicker, null, s.durationMs, s.positionMs, 1.0, playing && s.timeControl == 2, video = true)
                delay(1_000)
            }
            if (awake) MacNative.lib?.ravilo_display_keep_awake(0)
        }
    }

    actual fun play() = engine.play()
    actual fun pause() = engine.pause()
    actual fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    /** R354 (FR-R354-6) — the engine's own output level (AVPlayer / mpv). */
    actual fun setVolume(level: Float) = engine.setVolume(level.coerceIn(0f, 1f))

    /** FR-R329-4 — [index] is a position in [audioTracks], which are the ticket's; the library finds the rendition. */
    actual fun selectAudioTrack(index: Int) = engine.selectAudio(index)

    /** FR-R329-5 — [index] is a position in [subtitleTracks]; -1 = off. The cues are fetched and parsed here. */
    actual fun selectSubtitleTrack(index: Int) {
        subJob?.cancel()
        if (engine.rendersSubtitles) { engine.selectSubtitle(index); cues = emptyList(); return }
        val url = textTracks.getOrNull(index)?.url?.let(::absolute)
        if (url == null) { cues = emptyList(); return }
        subJob = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    DesktopApp.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (r.isSuccessful) parseVtt(r.body.string()) else null
                    }
                }.getOrNull()
            }
            if (isActive) cues = parsed ?: emptyList()
        }
    }

    /** A subtitle URL the server gave as a path is on the stream's own server. */
    private fun absolute(url: String): String {
        if (!url.startsWith("/")) return url
        val origin = runCatching { URI(streamUrl).let { "${it.scheme}://${it.rawAuthority}" } }.getOrNull() ?: return url
        return origin + url
    }

    actual fun release() {
        releaseEngine()
        MacNowPlaying.release(nowPlaying)
        noEngineFailure = false
    }

    actual fun releaseEngine() {
        subJob?.cancel()
        watchJob?.cancel(); watchJob = null
        MacNative.lib?.ravilo_display_keep_awake(0)
        engine.release()
    }

    actual fun recordRestoredAfterRecreate() { restoredAfterRecreate++ }
    actual fun setSessionActive(active: Boolean) { if (!active) MacNowPlaying.release(nowPlaying) }
    actual fun setChromeVisible(visible: Boolean) {}
    actual fun setSubtitleScale(scale: Float) { captionScale = scale; engine.setSubtitleScale(scale) }

    actual val positionMs: Long get() = engine.state.positionMs
    actual val durationMs: Long get() = engine.state.durationMs.coerceAtLeast(0L)
    actual val bufferedMs: Long get() = engine.state.bufferedMs
    actual val isPlaying: Boolean get() = engine.state.let { it.wantsPlay && !it.ended && !it.failed }
    actual val isEnded: Boolean get() = engine.state.ended
    /**
     * R306's latch arms only on a clear read after the stream is ready (it polls every 500 ms), because a flag left
     * from the stream before must never count. A failure this player knows at once (no library, a URL AVFoundation
     * refuses) is therefore shown from a second after the load, as an engine's asynchronous error would be.
     */
    actual val playbackFailed: Boolean
        get() = (noEngineFailure || engine.state.failed) && System.currentTimeMillis() - loadedAtMs >= FAILURE_VISIBLE_AFTER_MS
    actual val hasRenderedFirstFrame: Boolean get() = engine.state.firstFrame
    actual val isBuffering: Boolean get() = engine.state.let { it.buffering && !it.failed }
    actual val isSeeking: Boolean get() = engine.state.seeking

    /** The ticket's audio, once AVPlayer is ready (R181 resolves against tracks that exist) — or mpv's own list (R335). */
    actual val audioTracks: List<PlayerAudioTrack>
        get() = if (!engine.state.ready) emptyList() else engine.audioTracks()?.mapIndexed { i, t ->
            PlayerAudioTrack(
                index = i,
                label = t.title?.takeIf { it.isNotBlank() } ?: languageName(t.language) ?: t.language?.uppercase() ?: "Track ${i + 1}",
                language = t.language,
                channels = t.channels,
                isDefault = t.isDefault,
            )
        } ?: audioMeta.mapIndexed { i, a ->
            PlayerAudioTrack(
                index = i,
                label = a.label?.takeIf { it.isNotBlank() } ?: languageName(a.language) ?: a.language?.uppercase() ?: "Track ${i + 1}",
                language = a.language,
                channels = a.channels,
                isDefault = a.isDefault,
            )
        }

    actual val subtitleTracks: List<PlayerSubtitleTrack>
        get() = if (!engine.state.ready) emptyList() else engine.subtitleTracks()?.mapIndexed { i, t ->
            PlayerSubtitleTrack(
                index = i,
                label = t.title?.takeIf { it.isNotBlank() } ?: languageName(t.language) ?: t.language?.uppercase() ?: "Sub ${i + 1}",
                language = t.language,
                forced = t.forced,
                isDefault = t.isDefault,
                deliveryMethod = if (t.external) "external" else "embedded",
            )
        } ?: textTracks.mapIndexed { i, s ->
            PlayerSubtitleTrack(
                index = i,
                label = s.label?.takeIf { it.isNotBlank() } ?: languageName(s.language) ?: s.language?.uppercase() ?: "Sub ${i + 1}",
                language = s.language,
                forced = s.forced,
                isDefault = s.isDefault,
                deliveryMethod = "external",
            )
        }

    actual fun qoeSnapshot(): PlayerQoeSnapshot = engine.state.let { s ->
        PlayerQoeSnapshot(
            droppedFrames = s.droppedFrames.toInt(),
            rebufferCount = s.stalls.toInt(),
            bandwidthEstimateBps = s.observedBitrate.takeIf { it > 0 },
            videoDecoder = if (engine.available) engine.decoderName else null,
            restoredAfterRecreate = restoredAfterRecreate,
        )
    }

    /** R335 path (b): the engine wants a native window of its own; the surface gives it one and draws no frames. */
    internal val usesWindow: Boolean get() = engine.usesWindow
    internal fun attachWindow(id: Long) = engine.attachWindow(id)

    /** The newest decoded frame for the surface (FR-R329-1 path (b)); [surfaceW]/[surfaceH] in pixels, for a renderer that scales (R335). */
    internal fun takeFrame(surfaceW: Int = 0, surfaceH: Int = 0): ImageBitmap? {
        if (surfaceW > 0 && surfaceH > 0) engine.surfaceHint(surfaceW, surfaceH)
        return engine.takeFrame()?.toComposeImageBitmap()
    }
}

/**
 * FR-R329-1/5 — the picture, pulled once per display frame, and the text subtitle on top of it at R180's position
 * (bottom centre, above [subtitleBottomInset]) in R110's look: white with a soft dark outline, sized to the picture
 * and scaled by the Subtitle size row (S · M · L).
 */
@Composable
actual fun PlayerVideoSurface(
    player: RaviloPlayer,
    modifier: Modifier,
    onVideoOutputStuck: () -> Unit,
    onVideoOutputRecovering: (Boolean) -> Unit,
    fill: Boolean,
    subtitleBottomInset: Dp,
) {
    if (player.usesWindow) {
        // R335 (FR-R335-4) — a heavyweight AWT canvas is an X11 window; mpv draws into it (vo=gpu-next). The chrome
        // over it is Compose's, which needs `compose.interop.blending` (set by Main for `-Dravilo.video=gpu`).
        // The engine must let go of the window before AWT destroys it, or Xlib's BadWindow ends the process.
        androidx.compose.runtime.DisposableEffect(player) { onDispose { player.releaseEngine() } }
        Box(modifier.background(Color.Black)) {
            androidx.compose.ui.awt.SwingPanel(
                background = Color.Black,
                modifier = Modifier.fillMaxSize(),
                factory = {
                    java.awt.Canvas().apply {
                        background = java.awt.Color.BLACK
                        addHierarchyListener { e ->
                            if (e.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && isDisplayable) {
                                runCatching { com.sun.jna.Native.getComponentID(this) }.getOrNull()?.takeIf { it != 0L }?.let(player::attachWindow)
                            }
                        }
                    }
                },
            )
        }
        return
    }
    var frame by remember(player) { mutableStateOf<ImageBitmap?>(null) }
    var cueText by remember(player) { mutableStateOf<String?>(null) }
    var surfacePx by remember(player) { mutableStateOf(0 to 0) }
    LaunchedEffect(player) {
        while (isActive) {
            withFrameNanos { }
            player.takeFrame(surfacePx.first, surfacePx.second)?.let { frame = it }
            val text = activeCueText(player.cues, player.positionMs)
            if (text != cueText) cueText = text
        }
    }
    BoxWithConstraints(modifier.background(Color.Black)) {
        with(LocalDensity.current) { surfacePx = maxWidth.roundToPx() to maxHeight.roundToPx() }
        frame?.let {
            Image(it, contentDescription = null, contentScale = if (fill) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        val text = cueText ?: return@BoxWithConstraints
        // The picture's height inside the box, as Android sizes captions against the video, not the window (R300).
        val pictureHeight = frame?.let { f ->
            if (fill) maxHeight else minOf(maxHeight, maxWidth * (f.height.toFloat() / f.width.coerceAtLeast(1)))
        } ?: maxHeight
        val fontSize = with(LocalDensity.current) { (pictureHeight * 0.0533f * 0.9f * player.captionScale).toSp() }
        val base = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 48.dp, end = 48.dp, bottom = subtitleBottomInset)) {
            Text(text, style = base.copy(color = Color(0xCC000000), drawStyle = Stroke(width = 4f, join = StrokeJoin.Round)), modifier = Modifier.fillMaxWidth())
            Text(text, style = base.copy(color = Color.White), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A desktop window going to the Dock keeps its film, as the web's tab does: nothing to release. */
@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: () -> Unit,
) {}

private const val FAILURE_VISIBLE_AFTER_MS = 1_000L

