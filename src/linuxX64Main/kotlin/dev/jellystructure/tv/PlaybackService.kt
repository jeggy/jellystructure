package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.SubTrack
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

private const val TICKET_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
private const val TICKS_PER_MS = 10_000L

class PlaybackService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    /**
     * Resolves a stream ticket for [jellyfinId]. Starts a Jellyfin playback session and
     * returns all the data the Ravilo player needs to stream directly from Jellyfin.
     *
     * TODO(R14): call Jellyfin PlaybackInfo with device profile for proper codec negotiation.
     *            For now we use a direct-play URL; the forked `:ravilo-player` engine handles
     *            most containers the Jellyfin library holds.
     */
    suspend fun startPlayback(
        device: DeviceData,
        jellyfinId: String,
        @Suppress("UNUSED_PARAMETER") capabilities: ClientCapabilities,
    ): StreamTicket? {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = device.jellyfinUserToken

        // Resolve resume position from Jellyfin user-data
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val startPositionTicks = itemDetail?.userData?.playbackPositionTicks ?: 0L
        val startPositionMs = startPositionTicks / TICKS_PER_MS

        // Start a Jellyfin playback session so the server tracks Now Playing + resume
        jellyfinClient.startPlaybackSession(jellyfinBase, token, jellyfinId, startPositionTicks, jellyfinId)

        // Build subtitle list from MediaStore (we know the tracks from mkvpropedit scanning)
        val subtitles = buildSubtracks(jellyfinId, jellyfinBase, token)

        // Direct-play stream URL — the player fetches bytes straight from Jellyfin
        val streamUrl = "$jellyfinBase/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&api_key=$token"

        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = token,
            itemId = jellyfinId,
            container = "mkv", // conservative; Jellyfin transcodes if needed
            directPlay = true,
            hlsUrl = streamUrl, // direct-play URL; clients use this regardless of hlsUrl vs directPlay
            startPositionMs = startPositionMs,
            subtitles = subtitles,
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
        )
    }

    suspend fun reportProgress(device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, device.jellyfinUserToken, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        jellyfinClient.stopPlaybackSession(
            jellyfinBase, device.jellyfinUserToken, jellyfinId,
            positionMs * TICKS_PER_MS, jellyfinId,
        )
    }

    suspend fun mark(device: DeviceData, jellyfinId: String, watched: Boolean) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        if (watched) {
            jellyfinClient.markPlayed(jellyfinBase, device.jellyfinUserToken, device.jellyfinUserId, jellyfinId)
        } else {
            jellyfinClient.markUnplayed(jellyfinBase, device.jellyfinUserToken, device.jellyfinUserId, jellyfinId)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSubtracks(jellyfinId: String, jellyfinBase: String, token: String): List<SubTrack> {
        val item = mediaStore.allItems().firstOrNull { it.jellyfinId == jellyfinId } ?: return emptyList()
        val tracks = if (item.episodes.isNotEmpty()) item.episodes.flatMap { it.tracks } else item.tracks
        return tracks
            .filter { it.kind == TrackKind.SUBTITLE }
            .mapIndexed { idx, t ->
                SubTrack(
                    index = t.streamIndex,
                    language = t.language,
                    label = t.title,
                    forced = t.forced,
                    isDefault = t.default,
                    url = "$jellyfinBase/Videos/$jellyfinId/$jellyfinId/Subtitles/${t.streamIndex}/0/Stream.ass?api_key=$token",
                )
            }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
