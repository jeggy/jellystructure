package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.DesktopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.swing.SwingUtilities

/**
 * R329 (FR-R329-10) — the Mac's Now Playing card and its media keys, owned by one player at a time: a film, the
 * music engine's song or book, or (R330 FR-R330-7) a speaker being cast to. A new owner takes the card; only the
 * owner may change or clear it, so a film that stops cannot wipe the card a song has since taken.
 */
internal object MacNowPlaying {
    enum class Mode(val code: Int) { FILM(0), MUSIC(1), BOOK(2) }

    /** What a media key asks, as NowPlaying.swift numbers it. */
    enum class Command { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK_TO, SKIP_FORWARD, SKIP_BACK, STOP }

    fun interface Target { fun onCommand(command: Command, seconds: Double) }

    private val lib get() = MacNative.lib
    private var owner: Target? = null
    private var handlerInstalled = false
    private var artworkUrl: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // An object, not a lambda: JNA finds the callback method by reflection, and keeps only a weak reference —
    // this field is what keeps it alive.
    private val callback = object : MacNative.RemoteCallback {
        override fun invoke(command: Int, value: Double) {
            val c = Command.entries.getOrNull(command - 1) ?: return
            SwingUtilities.invokeLater { owner?.onCommand(c, value) }
        }
    }

    fun claim(target: Target, mode: Mode) {
        val l = lib ?: return
        if (!handlerInstalled) { l.ravilo_nowplaying_set_handler(callback); handlerInstalled = true }
        if (owner !== target) artworkUrl = null
        owner = target
        l.ravilo_nowplaying_set_mode(mode.code)
    }

    fun owns(target: Target): Boolean = owner === target

    fun update(target: Target, title: String, artist: String?, album: String?, durationMs: Long, positionMs: Long,
               rate: Double, playing: Boolean, video: Boolean) {
        if (owner !== target) return
        lib?.ravilo_nowplaying_update(title, artist.orEmpty(), album.orEmpty(), durationMs, positionMs.coerceAtLeast(0L),
            rate, if (playing) 1 else 0, if (video) 1 else 0)
    }

    /** The cover by URL; fetched once per URL, applied only if the same owner still holds the card. */
    fun artwork(target: Target, url: String?) {
        val l = lib ?: return
        if (owner !== target || url == artworkUrl) return
        artworkUrl = url
        if (url == null) { l.ravilo_nowplaying_artwork(null, 0); return }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    DesktopApp.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
                }.getOrNull()
            }
            if (bytes != null && owner === target && artworkUrl == url) l.ravilo_nowplaying_artwork(bytes, bytes.size.toLong())
        }
    }

    fun release(target: Target) {
        if (owner !== target) return
        owner = null
        artworkUrl = null
        lib?.ravilo_nowplaying_clear()
    }
}
