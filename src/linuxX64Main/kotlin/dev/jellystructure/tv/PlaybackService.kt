package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
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

// Phase 110 (FR E) — a rejected token used to be dropped from the cache entirely, so the very next TV
// request re-triggered the slow isTokenValid() round-trip unconditionally — every request, forever,
// for every device sharing that dead token. Negative-cache it instead, and log the rejection once per
// transition (not once per request).
private val tokenInvalidUntil = HashMap<String, Long>()
private val tokenRejectionLogged = HashSet<String>()
private const val TOKEN_NEGATIVE_TTL_MS = 10 * 60_000L // ~10 minutes, per spec FR E.1
private const val TICKS_PER_MS = 10_000L

// R142: bound the played write-through fan-out (a series mark-all can be dozens of episode calls).
// Kotlin/Native CIO select() crashes on FD ≥ 1024, so every fan-out MUST be Semaphore-capped.
private val playedGate = Semaphore(4)

// Phase 110 (FR B.2) — stop watchdog: a playback the client hasn't heartbeated (progress report, ~every
// 10s) in STOP_WATCHDOG_MS is force-stopped server-side, so an app kill / network drop / HDMI-off
// doesn't leave "Now Playing" lingering in the Jellyfin dashboard until its own 5-minute timeout.
// Key = deviceId (one active playback per device); cleared on an explicit stop.
private val lastHeartbeatMs = HashMap<String, Long>()
private val activePlayback = HashMap<String, Triple<DeviceData, String, Long>>() // deviceId -> (device, jellyfinId, lastKnownPositionMs)

/** Phase 111 (FR B.1) — the Jellyfin item id this device is actively playing, or null. Used by the
 *  remote-control device list; reads the same map the stop watchdog does, no separate tracking. */
fun nowPlayingItem(deviceId: String): String? = activePlayback[deviceId]?.second
private const val STOP_WATCHDOG_MS = 90_000L

private fun playSessionIdFor(device: DeviceData, jellyfinId: String): String = "${device.deviceId}-$jellyfinId"

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
        // Phase 110: this device's own Jellyfin identity — every call below presents it, so the
        // dashboard shows one correctly-named session per TV instead of one shared "Jellystructure"
        // session for all playback everywhere.
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val playSessionId = playSessionIdFor(device, jellyfinId)

        // Resolve resume position from Jellyfin user-data
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val startPositionTicks = itemDetail?.userData?.playbackPositionTicks ?: 0L
        val startPositionMs = startPositionTicks / TICKS_PER_MS

        // Start a Jellyfin playback session so the server tracks Now Playing + resume
        jellyfinClient.startPlaybackSession(jellyfinBase, token, jellyfinId, startPositionTicks, jellyfinId, identity, playSessionId)

        // Build the external-subtitle list from Jellyfin's MediaStreams for THIS playable item
        // (works for movies + episodes; embedded subs are discovered in-container by the player).
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token)

        // Audio-track metadata (R46): the player labels embedded audio from the container, which often
        // lacks a track title — so carry Jellyfin's rich DisplayTitle (e.g. "Synstolkning") through the
        // ticket. Order matches the container's audio-stream order so the player can map by index.
        val audio = buildAudioTracks(itemDetail)

        // R56: negotiate delivery via PlaybackInfo + DeviceProfile. Jellyfin tells us whether the item
        // can direct-play; if not, it hands back a TranscodingUrl. Fall back to a direct-play URL.
        val source = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, identity = identity)
            ?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        Logger.info(
            "PlaybackInfo: item=$jellyfinId directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode",
            "tv",
        )
        val streamUrl = if (needsTranscode) {
            val tu = source.transcodingUrl
            if (tu.startsWith("http")) tu else "$jellyfinBase$tu"
        } else {
            "$jellyfinBase/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&DeviceId=${identity.deviceId}&api_key=$token"
        }

        lastHeartbeatMs[device.deviceId] = nowMs()
        activePlayback[device.deviceId] = Triple(device, jellyfinId, startPositionMs)

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
        lastHeartbeatMs[device.deviceId] = nowMs()
        activePlayback[device.deviceId] = Triple(device, jellyfinId, positionMs)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        lastHeartbeatMs.remove(device.deviceId)
        activePlayback.remove(device.deviceId)
        jellyfinClient.stopPlaybackSession(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
        )
    }

    /** Phase 110 (FR B.2) — force-stops any playback whose last heartbeat is older than
     *  [STOP_WATCHDOG_MS] (an app kill / dropped network / HDMI-off never sent an explicit stop), and
     *  anything for a device whose TV-events socket has disconnected (checked via [isDeviceConnected]).
     *  Called on a periodic tick from Main.kt; also callable immediately on a TV disconnect. */
    suspend fun stopWatchdogTick(isDeviceConnected: suspend (String) -> Boolean) {
        val now = nowMs()
        // buildList's lambda is inline, so the suspend isDeviceConnected() call is allowed here —
        // a plain `.filter { }` lambda is not inline and can't call a suspend function.
        val stale = buildList {
            for ((deviceId, value) in activePlayback.entries) {
                val heartbeatStale = now - (lastHeartbeatMs[deviceId] ?: 0L) > STOP_WATCHDOG_MS
                if (heartbeatStale || !isDeviceConnected(deviceId)) add(value)
            }
        }
        for ((device, jellyfinId, positionMs) in stale) {
            Logger.info("Stop watchdog: force-stopping stale playback item=$jellyfinId device=${device.deviceId}", "tv")
            runCatching { stopPlayback(device, jellyfinId, positionMs) }
                .onFailure { Logger.warn("Stop watchdog: force-stop failed: ${it.message}", "tv") }
        }
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
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token)
        val audio = buildAudioTracks(itemDetail)
        // R56: ask Jellyfin (PlaybackInfo + DeviceProfile, with the sub index for Encode burn-in) for the
        // real TranscodingUrl; fall back to a hand-built HLS burn-in URL if PlaybackInfo is unavailable.
        val negotiated = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, subtitleStreamIndex, identity)
            ?.mediaSources?.firstOrNull()?.transcodingUrl
            ?.let { if (it.startsWith("http")) it else "$jellyfinBase$it" }
        Logger.info("PlaybackInfo(burn-in): item=$jellyfinId sub=$subtitleStreamIndex negotiated=${negotiated != null}", "tv")
        val transcodingUrl = negotiated ?: ("$jellyfinBase/Videos/$jellyfinId/master.m3u8" +
            "?DeviceId=${identity.deviceId}" +
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
    tokenCacheMutex.withLock {
        // Fast path: token was recently validated — skip the Jellyfin round-trip.
        tokenValidUntil[userToken]?.let { if (it > now) return userToken }
        // Phase 110: negative-cached — known-dead as of a recent check, skip the round-trip too.
        tokenInvalidUntil[userToken]?.let { if (it > now) return serverToken }
    }
    // Slow path: check with Jellyfin.
    val valid = isTokenValid(baseUrl, userToken, device.jellyfinUserId)
    tokenCacheMutex.withLock {
        if (valid) {
            tokenValidUntil[userToken] = nowMs() + TOKEN_VALID_TTL_MS
            tokenInvalidUntil.remove(userToken)
            tokenRejectionLogged.remove(userToken)
        } else {
            tokenValidUntil.remove(userToken)
            tokenInvalidUntil[userToken] = nowMs() + TOKEN_NEGATIVE_TTL_MS
        }
    }
    if (!valid && tokenRejectionLogged.add(userToken)) {
        Logger.warn(
            "TV: paired user token rejected by Jellyfin (401) for user ${device.jellyfinUserId} " +
                "— negative-cached ${TOKEN_NEGATIVE_TTL_MS / 60_000}min, using server token meanwhile",
            "tv",
        )
    }
    return if (valid) userToken else serverToken
}

/** Phase 110 (FR E.2) — is this device's paired token currently known-dead? Surfaced by the device
 *  list / health panel so "re-pair this user" is visible instead of a silent server-token fallback. */
fun isTokenNegativeCached(device: DeviceData): Boolean {
    val until = tokenInvalidUntil[device.jellyfinUserToken] ?: return false
    return until > nowMs()
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
