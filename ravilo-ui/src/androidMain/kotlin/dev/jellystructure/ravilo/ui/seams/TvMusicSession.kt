package dev.jellystructure.ravilo.ui.seams

import androidx.media3.session.MediaSession
import dev.jellystructure.ravilo.ui.music.MusicEngine

/**
 * R380 (dev review item 4) — the music engine's own Media3 session (`RaviloMusicService`'s), for Cast Connect: a music
 * cast hands THIS token to the receiver's `MediaManager` (the phone's standard play/pause/seek and the system UI then
 * drive the music), where a film cast hands the video player's ([TvPlayerSessionHooks]). One session per engine.
 */
object TvMusicSession {
    fun session(): MediaSession? = runCatching { MusicEngine.sessionOrBuild() }.getOrNull()
}
