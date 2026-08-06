package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.auth.JellyfinItemDetail
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
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
import kotlin.concurrent.Volatile
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
//
// Bug fix: this used to be keyed by deviceId alone ("one active playback per device"), but the Jellyfin
// play-session id is per ITEM (see playSessionIdFor), so a device can legitimately hold more than one
// Jellyfin session at a time — which is exactly what a leaking client produced. Starting episode 2 then
// overwrote episode 1's entry, leaving episode 1's Jellyfin session untracked and therefore unreapable
// by the watchdog (a phantom "Now Playing" forever), and a late stop for episode 1 wiped the tracking
// for the episode actually playing. Keyed by (deviceId, jellyfinId) the watchdog reaps every stale
// session, not just the last one.
internal data class PlaybackKey(val deviceId: String, val jellyfinId: String)

internal class TrackedPlayback(
    val device: DeviceData,
    val jellyfinId: String,
    val positionMs: Long,
    val heartbeatMs: Long,
)

private const val STOP_WATCHDOG_MS = 90_000L

// A stopped playback stays "stopped" for this long so a progress tick that was already in flight when
// the stop landed can't resurrect the session. Comfortably longer than the client's 10s tick.
private const val STOP_GRACE_MS = 60_000L

/**
 * The in-memory register of what is playing where, backing the Phase 110 (FR B.2) stop watchdog and the
 * Phase 111 (FR B.1) "now playing" device list. Extracted from loose top-level HashMaps so the exact
 * bugs below are unit-testable (see PlaybackTrackerTest); [clock] is injectable for the same reason.
 *
 * Bug fix: the maps used to be mutated from concurrent request coroutines with no synchronisation at
 * all, unlike tokenCacheMutex right above — a concurrent HashMap rehash is a data race and a crash risk
 * on Kotlin/Native. Every mutation now happens under [mutex].
 */
internal class PlaybackTracker(private val clock: () -> Long = ::nowMs) {
    private val mutex = Mutex()
    private val active = HashMap<PlaybackKey, TrackedPlayback>()
    private val stoppedUntilMs = HashMap<PlaybackKey, Long>()

    // Non-suspend readers (nowPlaying, called from route handlers) can't take the mutex, so they read an
    // immutable snapshot republished on every mutation instead of iterating the live map.
    @Volatile
    private var snapshot: Map<PlaybackKey, TrackedPlayback> = emptyMap()

    suspend fun started(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        mutex.withLock {
            stoppedUntilMs.remove(key)  // an explicit new start ends the post-stop grace window
            active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock())
            publish()
        }
    }

    /**
     * Records a heartbeat. Returns false when this is a late tick for a playback that already reported a
     * stop — bug fix: such a tick used to re-register the playback AND get pushed to Jellyfin,
     * resurrecting a finished session and overwriting the final resume position we had just written.
     */
    suspend fun heartbeat(device: DeviceData, jellyfinId: String, positionMs: Long): Boolean {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            if ((stoppedUntilMs[key] ?: 0L) > clock()) {
                false
            } else {
                active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock())
                publish()
                true
            }
        }
    }

    /**
     * Bug fix: the entry used to be removed by deviceId regardless of which item stopped, so a late stop
     * for episode 1 dropped the tracking for the episode actually playing.
     */
    suspend fun stopped(device: DeviceData, jellyfinId: String) {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        mutex.withLock {
            active.remove(key)
            stoppedUntilMs[key] = clock() + STOP_GRACE_MS
            publish()
        }
    }

    /** Everything currently tracked, snapshotted so callers can suspend while walking it. Also prunes
     *  expired post-stop grace entries — the watchdog tick is the natural janitor. */
    suspend fun tracked(): List<TrackedPlayback> = mutex.withLock {
        val now = clock()
        stoppedUntilMs.entries.removeAll { it.value <= now }
        active.values.toList()
    }

    /** Has [playback] gone [STOP_WATCHDOG_MS] without a heartbeat (app kill / network drop / HDMI-off)? */
    fun isHeartbeatStale(playback: TrackedPlayback): Boolean =
        clock() - playback.heartbeatMs > STOP_WATCHDOG_MS

    /** With per-item keying a device can briefly hold several entries (an overlapping stop/start), so
     *  report the most recently heartbeated one — that is the one actually on screen. */
    fun nowPlaying(deviceId: String): String? =
        snapshot.values
            .filter { it.device.deviceId == deviceId }
            .maxByOrNull { it.heartbeatMs }
            ?.jellyfinId

    /** Must be called while holding [mutex]. */
    private fun publish() {
        snapshot = active.toMap()
    }
}

internal val playbackTracker = PlaybackTracker()

/** Phase 111 (FR B.1) — the Jellyfin item id this device is actively playing, or null. Used by the
 *  remote-control device list; reads the same tracking the stop watchdog does, no separate state. */
fun nowPlayingItem(deviceId: String): String? = playbackTracker.nowPlaying(deviceId)

private fun playSessionIdFor(device: DeviceData, jellyfinId: String): String = "${device.deviceId}-$jellyfinId"

/** Security fix (2026-08-02 review, finding H2) — thrown by [PlaybackService.startPlayback] /
 *  [PlaybackService.restream] instead of falling back to (and leaking) the server admin token when a
 *  device's paired Jellyfin token has gone stale. Routes catch this and respond with a clear
 *  re-authentication error instead of a raw 500. */
class JellyfinReauthRequiredException(message: String) : Exception(message)

/** Security fix (2026-08-02 review, finding M4) — thrown when a device tries to act on an item its
 *  own library-allowlist / AllowedTags-BlockedTags policy hides. Routes catch this and respond 403. */
class PlaybackForbiddenException(message: String) : Exception(message)

class PlaybackService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    /**
     * Security fix (2026-08-02 review, finding M4) — Detail/Browse/Home all gate on
     * `MediaItem.visibleTo(device)` (library allow-list + Jellyfin AllowedTags/BlockedTags), but
     * playback never did: every function below took the caller-supplied `jellyfinId` straight to
     * Jellyfin. Normally the device's own Jellyfin user token is the backstop, but [tvToken] can fall
     * back to the server admin token when that token is stale — so a Kids/library-restricted device
     * could, in that window, start/stop/mark-played *any* item in the library. Fail closed: an id
     * outside jellystructure's own catalog (nothing to check a policy against) is rejected too.
     */
    private suspend fun requireVisible(device: DeviceData, jellyfinId: String) {
        // resolveByJellyfinId only indexes top-level (movie/series) ids — playing an EPISODE is the
        // common case and needs the same series-episode scan MediaStore.resolvePlayTarget already
        // uses, or every episode play would be wrongly rejected as "not found".
        val item = mediaStore.resolveByJellyfinId(jellyfinId)
            ?: mediaStore.allItems().firstOrNull { series -> series.episodes.any { it.jellyfinId == jellyfinId } }
        if (item == null || !item.visibleTo(device)) {
            throw PlaybackForbiddenException("Item not visible to this device")
        }
    }

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
        capabilities: ClientCapabilities,
    ): StreamTicket {
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
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
        // Bug fix: [capabilities] used to be discarded here — an HDR10/HLG source always direct-played
        // regardless of what the device could actually display correctly (see deviceProfile()'s doc).
        val source = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities, identity = identity)
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

        playbackTracker.started(device, jellyfinId, startPositionMs)

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
        // No requireVisible() here deliberately — this is a heartbeat for a session startPlayback
        // already gated; failing a heartbeat because a policy/library edit drifted mid-playback would
        // only strand a phantom "Now Playing" in Jellyfin, the exact bug class the watchdog above
        // exists to prevent. A straggling tick for an item that already reported a stop must not be
        // forwarded either — see PlaybackTracker.heartbeat.
        if (!playbackTracker.heartbeat(device, jellyfinId, positionMs)) {
            Logger.info("Ignoring progress for already-stopped playback item=$jellyfinId device=${device.deviceId}", "tv")
            return
        }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long) {
        // No requireVisible() here deliberately — same reasoning as reportProgress above: this is
        // cleanup for a session startPlayback already gated, and it's also called from the stop
        // watchdog for stale/disconnected devices. Blocking it would risk leaving a phantom "Now
        // Playing" in Jellyfin forever, which is worse than the (already access-gated-at-start) cost
        // of letting an in-flight stop go through.
        playbackTracker.stopped(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
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
        // Snapshot first: isDeviceConnected suspends, and stopPlayback below takes the tracker's own
        // (non-reentrant) mutex.
        val tracked = playbackTracker.tracked()
        // buildList's lambda is inline, so the suspend isDeviceConnected() call is allowed here —
        // a plain `.filter { }` lambda is not inline and can't call a suspend function.
        val stale = buildList {
            for (p in tracked) {
                if (playbackTracker.isHeartbeatStale(p) || !isDeviceConnected(p.device.deviceId)) add(p)
            }
        }
        for (p in stale) {
            Logger.info("Stop watchdog: force-stopping stale playback item=${p.jellyfinId} device=${p.device.deviceId}", "tv")
            runCatching { stopPlayback(p.device, p.jellyfinId, p.positionMs) }
                .onFailure { Logger.warn("Stop watchdog: force-stop failed: ${it.message}", "tv") }
        }
    }

    suspend fun mark(device: DeviceData, jellyfinId: String, watched: Boolean) {
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        if (watched) {
            // R185 — markPlayed alone never touches PlaybackPositionTicks, so a stale/leaked resume
            // position can outlive the played flag and keep this item showing as "in progress" in
            // Continue Watching. Zero it explicitly at the same choke point every "mark watched" path
            // goes through, rather than relying on whichever caller happens to also report a stop.
            jellyfinClient.stopPlaybackSession(
                jellyfinBase, token, jellyfinId, 0L, jellyfinId,
                JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
            )
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
        requireVisible(device, itemId)
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        val uid = device.jellyfinUserId

        // Leaf ids to write: an explicit season set, else a series → all its episodes, else the item itself.
        val targets: List<String> = episodeIds.ifEmpty {
            val mi = mediaStore.resolveByJellyfinId(itemId)
            if (mi != null && mi.episodes.isNotEmpty()) mi.episodes.mapNotNull { it.jellyfinId } else listOf(itemId)
        }.filterNot { it.startsWith('/') }.distinct()

        coroutineScope {
            targets.map { id ->
                async {
                    playedGate.withPermit {
                        if (played) {
                            // R185 — same gap as mark() above: the manual watched-toggle had NO position
                            // handling at all, so a partially-watched item flipped to "watched" here kept
                            // its stale nonzero PlaybackPositionTicks forever. Zero it alongside markPlayed.
                            jellyfinClient.stopPlaybackSession(
                                base, token, id, 0L, id,
                                JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, id),
                            )
                            jellyfinClient.markPlayed(base, token, uid, id)
                        } else {
                            jellyfinClient.markUnplayed(base, token, uid, id)
                        }
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
                        // Skip a vacuous series ✓ (empty Jellyfin child rollup → Played=true of 0). See PlaystateHydrator.
                        played    = ud.played && !(jf.type == "Series" && jf.recursiveItemCount == 0),
                        playedPct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f,
                        favorite  = ud.isFavorite,
                    )
                }
            }
        }
        return out
    }

    /**
     * Bug fix — Ravilo's "My List": mirrors [mark] exactly (single item, write then done — no episode
     * fan-out needed since My List/Favorite is a per-title toggle, unlike watched state). Returns the
     * re-read authoritative CardPlayState so the client renders from the server result, matching every
     * other write-through in this file.
     */
    suspend fun setFavorite(device: DeviceData, jellyfinId: String, favorite: Boolean): CardPlayState? {
        requireVisible(device, jellyfinId)
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        val uid = device.jellyfinUserId
        if (favorite) jellyfinClient.markFavorite(base, token, uid, jellyfinId)
        else jellyfinClient.unmarkFavorite(base, token, uid, jellyfinId)
        val jf = jellyfinClient.getUserDataBulk(base, token, uid, listOf(jellyfinId)).firstOrNull() ?: return null
        val ud = jf.userData ?: return null
        return CardPlayState(
            resumeMs  = ud.playbackPositionTicks / TICKS_PER_MS,
            played    = ud.played && !(jf.type == "Series" && jf.recursiveItemCount == 0),
            playedPct = (ud.playedPercentage?.toFloat() ?: 0f) / 100f,
            favorite  = ud.isFavorite,
        )
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
    ): StreamTicket {
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token)
        val audio = buildAudioTracks(itemDetail)
        // R56: ask Jellyfin (PlaybackInfo + DeviceProfile, with the sub index for Encode burn-in) for the
        // real TranscodingUrl; fall back to a hand-built HLS burn-in URL if PlaybackInfo is unavailable.
        // Bug fix: this used to pass subtitleStreamIndex positionally into what is now the new
        // `capabilities` parameter slot — named args here since burn-in restream doesn't have the
        // original session's capabilities on hand; ClientCapabilities()'s conservative SDR-only default
        // is fine since this path already forces a transcode for the subtitle burn-in regardless.
        val negotiated = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, subtitleStreamIndex = subtitleStreamIndex, identity = identity)
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
private suspend fun JellyfinClient.isPairedTokenValid(baseUrl: String, device: DeviceData): Boolean {
    val userToken = device.jellyfinUserToken
    val now = nowMs()
    tokenCacheMutex.withLock {
        // Fast path: token was recently validated — skip the Jellyfin round-trip.
        tokenValidUntil[userToken]?.let { if (it > now) return true }
        // Phase 110: negative-cached — known-dead as of a recent check, skip the round-trip too.
        tokenInvalidUntil[userToken]?.let { if (it > now) return false }
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
                "— negative-cached ${TOKEN_NEGATIVE_TTL_MS / 60_000}min",
            "tv",
        )
    }
    return valid
}

internal suspend fun JellyfinClient.tvToken(baseUrl: String, device: DeviceData, serverToken: String): String =
    if (isPairedTokenValid(baseUrl, device)) device.jellyfinUserToken else serverToken

/**
 * Security fix (2026-08-02 review, finding H2) — [tvToken] falls back to the long-lived **server**
 * token when the device's paired token has gone stale, which is fine for calls the server makes on
 * the device's behalf without ever showing the token to it. It is NOT fine for [startPlayback]/
 * [restream]: those embed the token directly into a stream URL and return it to the client as
 * `StreamTicket.accessToken` — so any signed-in device (including a Kids/library-restricted profile)
 * would receive full Jellyfin **admin** credentials the moment its own token went stale, which the
 * surrounding negative-cache logic treats as routine. This variant never falls back — it returns
 * null so the caller can surface a clear "re-pair this device" error instead of silently handing out
 * server-admin access.
 */
internal suspend fun JellyfinClient.tvTokenForClient(baseUrl: String, device: DeviceData): String? =
    if (isPairedTokenValid(baseUrl, device)) device.jellyfinUserToken else null

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
