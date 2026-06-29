package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.auth.JellyfinItemDetail
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.SubTrack
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

private const val TICKET_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours

// Cache token validity so we don't make a live Jellyfin round-trip on every API request.
// Key = jellyfinUserToken; value = expiry timestamp (ms). On cache hit tvToken() returns instantly.
private val tokenValidUntil = HashMap<String, Long>()
private val tokenCacheMutex = Mutex()
private const val TOKEN_VALID_TTL_MS = 5 * 60_000L // 5 minutes
private const val TICKS_PER_MS = 10_000L

// R142: bound the played write-through fan-out (a series mark-all can be dozens of episode calls).
// Kotlin/Native CIO select() crashes on FD ≥ 1024, so every fan-out MUST be Semaphore-capped.
private val playedGate = Semaphore(4)

class PlaybackService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    /**
     * Resolves a stream ticket for [jellyfinId]. Starts a Jellyfin playback session and
     * returns all the data the Ravilo player needs to stream directly from Jellyfin.
     *
     * R56: delivery is negotiated via Jellyfin PlaybackInfo with a DeviceProfile — Jellyfin decides
     * direct-play vs a server TranscodingUrl (e.g. for a burned-in image subtitle). Falls back to a
     * direct-play URL if PlaybackInfo is unavailable.
     */
    suspend fun startPlayback(
        device: DeviceData,
        jellyfinId: String,
        @Suppress("UNUSED_PARAMETER") capabilities: ClientCapabilities,
    ): StreamTicket? {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)

        // Resolve resume position from Jellyfin user-data
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val startPositionTicks = itemDetail?.userData?.playbackPositionTicks ?: 0L
        val startPositionMs = startPositionTicks / TICKS_PER_MS

        // Start a Jellyfin playback session so the server tracks Now Playing + resume
        jellyfinClient.startPlaybackSession(jellyfinBase, token, jellyfinId, startPositionTicks, jellyfinId)

        // Build the external-subtitle list from Jellyfin's MediaStreams for THIS playable item
        // (works for movies + episodes; embedded subs are discovered in-container by the player).
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token)

        // Audio-track metadata (R46): the player labels embedded audio from the container, which often
        // lacks a track title — so carry Jellyfin's rich DisplayTitle (e.g. "Synstolkning") through the
        // ticket. Order matches the container's audio-stream order so the player can map by index.
        val audio = buildAudioTracks(itemDetail)

        // R56: negotiate delivery via PlaybackInfo + DeviceProfile. Jellyfin tells us whether the item
        // can direct-play; if not, it hands back a TranscodingUrl. Fall back to a direct-play URL.
        val source = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
            ?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        Logger.info(
            "PlaybackInfo: item=$jellyfinId directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode",
            "tv",
        )
        val streamUrl = if (needsTranscode) {
            val tu = source!!.transcodingUrl!!
            if (tu.startsWith("http")) tu else "$jellyfinBase$tu"
        } else {
            "$jellyfinBase/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&api_key=$token"
        }

        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = token,
            itemId = jellyfinId,
            container = "mkv", // conservative; Jellyfin transcodes if needed
            directPlay = !needsTranscode,
            hlsUrl = streamUrl, // direct-play URL or a Jellyfin TranscodingUrl per PlaybackInfo
            startPositionMs = startPositionMs,
            subtitles = subtitles,
            audio = audio,
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
        )
    }

    suspend fun reportProgress(device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.stopPlaybackSession(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, jellyfinId,
        )
    }

    suspend fun mark(device: DeviceData, jellyfinId: String, watched: Boolean) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        if (watched) {
            jellyfinClient.markPlayed(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        } else {
            jellyfinClient.markUnplayed(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        }
    }

    /**
     * R142 — played/unplayed write-through. Writes Jellyfin user-data only (never library metadata).
     * For a series [itemId] (or an explicit [episodeIds] season set) the flag fans out to every child
     * episode (bounded by [playedGate] — FD_SETSIZE-safe). Returns the authoritative play-state for the
     * item + all affected episodes, re-read from Jellyfin, so the client renders from the server result.
     */
    suspend fun setPlayed(
        device: DeviceData,
        itemId: String,
        played: Boolean,
        episodeIds: List<String> = emptyList(),
    ): Map<String, CardPlayState> {
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        val uid = device.jellyfinUserId

        // Leaf ids to write: an explicit season set, else a series → all its episodes, else the item itself.
        val targets: List<String> = (if (episodeIds.isNotEmpty()) {
            episodeIds
        } else {
            val mi = mediaStore.resolveByJellyfinId(itemId)
            if (mi != null && mi.episodes.isNotEmpty()) mi.episodes.mapNotNull { it.jellyfinId } else listOf(itemId)
        }).filterNot { it.startsWith('/') }.distinct()

        coroutineScope {
            targets.map { id ->
                async {
                    playedGate.withPermit {
                        if (played) jellyfinClient.markPlayed(base, token, uid, id)
                        else        jellyfinClient.markUnplayed(base, token, uid, id)
                    }
                }
            }.awaitAll()
        }

        // Re-read authoritative state for the parent item + every affected episode.
        val readIds = (listOf(itemId) + targets).filterNot { it.startsWith('/') }.distinct()
        val out = mutableMapOf<String, CardPlayState>()
        for (chunk in readIds.chunked(100)) {
            playedGate.withPermit {
                jellyfinClient.getUserDataBulk(base, token, uid, chunk).forEach { jf ->
                    val ud = jf.userData ?: return@forEach
                    out[jf.id] = CardPlayState(
                        resumeMs  = ud.playbackPositionTicks / TICKS_PER_MS,
                        played    = ud.played,
                        playedPct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f,
                    )
                }
            }
        }
        return out
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSubtracks(
        itemDetail: JellyfinItemDetail?,
        jellyfinId: String,
        jellyfinBase: String,
        token: String,
    ): List<SubTrack> {
        val streams = itemDetail?.mediaStreams ?: return emptyList()
        return streams
            .filter { it.type.equals("Subtitle", ignoreCase = true) }
            .mapNotNull { s ->
                val codec = s.codec?.lowercase()
                when {
                    // R55: text subs (SRT/ASS/SSA/VTT/muxed) — sideloaded via Jellyfin's VTT extractor.
                    s.isTextSubtitleStream || isTextSubCodec(s.codec) -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = "$jellyfinBase/Videos/$jellyfinId/$jellyfinId/Subtitles/${s.index}/0/Stream.vtt?api_key=$token",
                        deliveryMethod = "external",
                    )
                    // R56: VobSub/DVDSub — native in-container rendering via MatroskaExtractor.
                    codec != null && isEmbedImageSubCodec(codec) -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = null,
                        deliveryMethod = "embed",
                    )
                    // R56: PGS — burn-in via Jellyfin HLS transcode (encode path).
                    codec != null && isPgsSubCodec(codec) -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = null,
                        deliveryMethod = "encode",
                    )
                    else -> null // unknown image sub type — skip
                }
            }
    }

    /** R56 — Re-stream the item with a PGS subtitle burned in via Jellyfin HLS transcode. */
    suspend fun restream(
        device: DeviceData,
        jellyfinId: String,
        subtitleStreamIndex: Int,
        positionMs: Long,
    ): StreamTicket? {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token)
        val audio = buildAudioTracks(itemDetail)
        // R56: ask Jellyfin (PlaybackInfo + DeviceProfile, with the sub index for Encode burn-in) for the
        // real TranscodingUrl; fall back to a hand-built HLS burn-in URL if PlaybackInfo is unavailable.
        val negotiated = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, subtitleStreamIndex)
            ?.mediaSources?.firstOrNull()?.transcodingUrl
            ?.let { if (it.startsWith("http")) it else "$jellyfinBase$it" }
        Logger.info("PlaybackInfo(burn-in): item=$jellyfinId sub=$subtitleStreamIndex negotiated=${negotiated != null}", "tv")
        val transcodingUrl = negotiated ?: ("$jellyfinBase/Videos/$jellyfinId/master.m3u8" +
            "?DeviceId=jellystructure-ravilo" +
            "&MediaSourceId=$jellyfinId" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&MaxWidth=1920&MaxHeight=1080" +
            "&SubtitleMethod=Encode" +
            "&SubtitleStreamIndex=$subtitleStreamIndex" +
            "&api_key=$token")
        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = token,
            itemId = jellyfinId,
            container = "mkv",
            directPlay = false,
            hlsUrl = transcodingUrl,
            startPositionMs = positionMs,
            subtitles = subtitles,
            audio = audio,
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
        )
    }

    private fun buildAudioTracks(itemDetail: JellyfinItemDetail?): List<AudioTrack> {
        val streams = itemDetail?.mediaStreams ?: return emptyList()
        return streams
            .filter { it.type.equals("Audio", ignoreCase = true) }
            .map { s ->
                AudioTrack(
                    index = s.index,
                    language = s.language,
                    // DisplayTitle is the fullest human string Jellyfin composes (lang + title +
                    // codec + layout, e.g. "Dansk - Synstolkning - Dolby Digital - 5.1"); fall back
                    // to the raw Title, then to a composed language+codec, then language alone.
                    label = s.displayTitle?.takeIf { it.isNotBlank() }
                        ?: s.title?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(s.language, s.codec?.uppercase()).joinToString(" · ").ifBlank { null },
                    codec = s.codec,
                    channels = s.channels,
                    isDefault = s.isDefault,
                )
            }
    }

    private fun isTextSubCodec(c: String?): Boolean =
        (c?.lowercase()) in setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text")

    private fun isEmbedImageSubCodec(c: String): Boolean =
        c in setOf("dvd_subtitle", "dvdsub", "vobsub", "dvbsub", "dvb_subtitle")

    private fun isPgsSubCodec(c: String): Boolean =
        c in setOf("hdmv_pgs_subtitle", "pgssub", "pgs")
}

/**
 * The token the TV should use for Jellyfin reads/streaming: the paired user token when Jellyfin still
 * accepts it, else the long-lived **server** token. A stale paired token (captured at pairing, later
 * invalidated by Jellyfin) otherwise 401s every call — empty home feed, episodes falling back to a
 * file-path id, malformed stream URLs, black screen. The user's `userId` stays in each request URL,
 * so per-user data (resume/watched/next-up) is still correct under the server token.
 *
 * Validity is cached for [TOKEN_VALID_TTL_MS] so we don't pay a round-trip to Jellyfin on every
 * route handler. On a cache hit this returns instantly; on a miss we fall through to isTokenValid().
 */
internal suspend fun JellyfinClient.tvToken(baseUrl: String, device: DeviceData, serverToken: String): String {
    val userToken = device.jellyfinUserToken
    val now = nowMs()
    // Fast path: token was recently validated — skip the Jellyfin round-trip.
    if (tokenCacheMutex.withLock { tokenValidUntil[userToken]?.let { it > now } == true }) {
        return userToken
    }
    // Slow path: check with Jellyfin.
    val valid = isTokenValid(baseUrl, userToken, device.jellyfinUserId)
    tokenCacheMutex.withLock {
        if (valid) tokenValidUntil[userToken] = nowMs() + TOKEN_VALID_TTL_MS
        else tokenValidUntil.remove(userToken)
    }
    if (!valid) Logger.warn(
        "TV: paired user token rejected by Jellyfin (401) for user ${device.jellyfinUserId}; using server token"
    )
    return if (valid) userToken else serverToken
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
