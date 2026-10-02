package dev.jellystructure.ravilo.ui.music

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.jellystructure.ravilo.ui.RaviloAppContext

/**
 * R322 (FR-R322-1) — keeps a song playing when the app leaves the screen: it hosts [MusicEngine]'s session, and
 * Media3 makes it a foreground service with the notification while the player plays (FR-R322-13). Declared in
 * `:ravilo-android`'s manifest by this class name (dev review 1: `foregroundServiceType="mediaPlayback"`).
 *
 * R292's video lifecycle is untouched: the film player still lets go of everything on `ON_STOP`; this service exists
 * precisely so music does not.
 */
class RaviloMusicService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        RaviloAppContext.init(this)   // dev review 2 — safe from a service
        addSession(MusicEngine.sessionOrBuild())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = MusicEngine.sessionOrBuild()

    /**
     * Acceptance 1 — removing the app from recents stops the music. R356 (FR-R356-5) — while the session mirrors a cast
     * the phone plays nothing: the service goes, the device plays on (FR-R245-5), and the phone's own queue is kept.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MusicEngine.castingRemote) MusicEngine.clear()
        stopSelf()
    }

    override fun onDestroy() {
        MusicEngine.onServiceDestroyed()
        super.onDestroy()
    }
}
