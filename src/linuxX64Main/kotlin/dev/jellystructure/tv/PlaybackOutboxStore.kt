package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.JellystructureDb

/**
 * Phase 310 (owner decision 2) — [PlaybackOutbox] in SQLite (`playback_outbox`, migration 71). [deviceOf] resolves a
 * saved stop's device again after a restart (the same lookup the session restore uses); a stop whose device is gone
 * is dropped, since nothing could authenticate it any more.
 */
class SqlPlaybackOutbox(
    private val db: JellystructureDb,
    private val deviceOf: (deviceId: String, jellyfinUserId: String) -> DeviceData?,
    private val clock: () -> Long = ::nowWallMs,
) : PlaybackOutbox {
    private val q get() = db.playbackOutboxQueries

    override suspend fun save(w: PlaybackWriter.PendingWrite) {
        val u = w.userData
        q.upsert(
            w.device.deviceId, w.device.jellyfinUserId, w.jellyfinId, w.positionMs, w.jellyfinPlaySessionId,
            if (w.startOverUnplayed) 1L else 0L, w.restoreLastPlayed, w.reason,
            u?.played?.let { if (it) 1L else 0L }, u?.positionMs, u?.lastPlayedDate, if (u != null) 1L else 0L, clock(),
        )
    }

    override suspend fun remove(deviceId: String, jellyfinId: String) {
        q.remove(deviceId, jellyfinId)
    }

    override suspend fun loadAll(): List<PlaybackWriter.PendingWrite> = q.all().executeAsList().mapNotNull { r ->
        val device = deviceOf(r.device_id, r.jellyfin_user_id)
        if (device == null) { q.remove(r.device_id, r.jellyfin_id); return@mapNotNull null }
        PlaybackWriter.PendingWrite(
            PlaybackWriter.Kind.STOP, device, r.jellyfin_id, r.position_ms, false, r.jellyfin_play_session_id, 0L, 0L,
            startOverUnplayed = r.start_over_unplayed == 1L, restoreLastPlayed = r.restore_last_played, reason = r.reason,
            userData = if (r.has_user_data == 1L) StopUserData(r.user_played?.let { it == 1L }, r.user_position_ms ?: r.position_ms, r.user_last_played) else null,
        )
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowWallMs(): Long = platform.posix.time(null) * 1000L
