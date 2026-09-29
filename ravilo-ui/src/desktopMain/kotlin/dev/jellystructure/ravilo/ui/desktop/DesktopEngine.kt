package dev.jellystructure.ravilo.ui.desktop

import org.jetbrains.skia.Image

/**
 * R335 (FR-R335-2) — the one surface both desktop players ([RaviloPlayerDesktop][dev.jellystructure.ravilo.ui.seams.RaviloPlayer]
 * and the desktop `MusicEngine`) drive: AVPlayer through the Swift library on a Mac ([MacEngine] around R329's
 * [MacPlayer]), mpv through libmpv on Linux ([MpvPlayer]). The state is R329's [MacPlayerState] on both.
 */
internal interface DesktopEngine {
    val available: Boolean
    val loaded: Boolean
    fun load(url: String, mime: String = "", startMs: Long = 0L)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setRate(rate: Float)
    fun setVolume(volume: Float)
    /** [index] is a position in the audio list the player shows — the ticket's on a Mac, [audioTracks] on Linux. */
    fun selectAudio(index: Int)
    val state: MacPlayerState
    fun error(): String?
    fun debug(): String
    fun audioOptions(): String?
    /** The newest frame as a Skia image, or null when there is none new. Must be called on Compose's thread. */
    fun takeFrame(): Image?
    fun release()
    /** What this engine calls itself in a QoE snapshot. */
    val decoderName: String

    // ── Linux (FR-R335-5/6): the engine that renders subtitles and knows the container's tracks ──
    /** The surface's size in pixels, so a renderer that scales can stop at what is shown. */
    fun surfaceHint(width: Int, height: Int) {}
    val rendersSubtitles: Boolean get() = false
    /** The audio tracks as the engine sees them, or null to use the ticket's list. */
    fun audioTracks(): List<EngineTrack>? = null
    fun subtitleTracks(): List<EngineTrack>? = null
    fun addSubtitle(url: String, title: String?, language: String?) {}
    /** [index] is a position in [subtitleTracks]; -1 = off. */
    fun selectSubtitle(index: Int) {}
    fun setSubtitleScale(scale: Float) {}
    /** Path (b), FR-R335-4: the engine draws into a native window of its own instead of the ring. */
    val usesWindow: Boolean get() = false
    fun attachWindow(id: Long) {}
}

/** One track of the loaded file, as the engine lists it (mpv's `track-list`). */
internal data class EngineTrack(
    val id: Int,
    val title: String?,
    val language: String?,
    val channels: Int?,
    val isDefault: Boolean,
    val forced: Boolean,
    val external: Boolean,
    val codec: String?,
)

/** R329's AVPlayer engine behind the shared surface — a thin wrapper, so [MacPlayer] itself is unchanged. */
internal class MacEngine(private val p: MacPlayer) : DesktopEngine {
    override val available: Boolean get() = p.available
    override val loaded: Boolean get() = p.loaded
    override fun load(url: String, mime: String, startMs: Long) = p.load(url, mime, startMs)
    override fun play() = p.play()
    override fun pause() = p.pause()
    override fun seekTo(ms: Long) = p.seekTo(ms)
    override fun setRate(rate: Float) = p.setRate(rate)
    override fun setVolume(volume: Float) = p.setVolume(volume)
    override fun selectAudio(index: Int) = p.selectAudio(index)
    override val state: MacPlayerState get() = p.state
    override fun error(): String? = p.error()
    override fun debug(): String = p.debug()
    override fun audioOptions(): String? = p.audioOptions()
    override fun takeFrame(): Image? = p.takeFrame()
    override fun release() = p.release()
    override val decoderName: String get() = "AVFoundation"
}

/** Which engine this desktop runs: the Mac's, or mpv where libmpv is there (FR-R335-7's honest absence otherwise). */
internal object DesktopEngines {
    val isMpv: Boolean get() = !DesktopPaths.isMac && Mpv.lib != null

    fun film(): DesktopEngine = if (DesktopPaths.isMac) MacEngine(MacPlayer(audioOnly = false)) else MpvPlayer(audioOnly = false)
    fun music(): DesktopEngine = if (DesktopPaths.isMac) MacEngine(MacPlayer(audioOnly = true)) else MpvPlayer(audioOnly = true)
}
