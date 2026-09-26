package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.auth.JellyfinItemDetail
import dev.jellystructure.auth.TokenCheck
import dev.jellystructure.auth.TokenCheckResult
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.SubTrack
import dev.jellystructure.auth.withJellyfinToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
// Phase 205 (FR-205-4) — checkToken's own client is configured requestTimeoutMillis = 120_000; on a
// cache miss (every 5 min per token) that is not a timeout, it is the absence of one. A wedged Jellyfin
// could otherwise hold a TV request for two minutes with nothing in between to stop it.
private const val TOKEN_CHECK_TIMEOUT_MS = 5_000L
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

/** See [PlaybackTracker.started]'s doc. [superseded] is the full still-active earlier entry for the
 *  same key, if any, so the caller can run it through the same complete teardown (stop report + encode
 *  release) as any other exit path, not just release its encode. */
internal data class StartResult(val stopAlreadyArrived: Boolean, val superseded: TrackedPlayback?)

internal class TrackedPlayback(
    val device: DeviceData,
    val jellyfinId: String,
    val positionMs: Long,
    val heartbeatMs: Long,
    // Phase 180 — Jellyfin's OWN play-session id (JellyfinPlaybackInfoResponse.playSessionId), the one
    // its transcode manager actually keys an active encode on. Distinct from playSessionIdFor()'s
    // deterministic bookkeeping id used for /Sessions/Playing* — see stopActiveEncoding's doc. Null when
    // PlaybackInfo was unavailable and startPlayback fell back to a plain direct-play URL (no encode to
    // ever release).
    val jellyfinPlaySessionId: String? = null,
)

private const val STOP_WATCHDOG_MS = 90_000L

// A stopped playback stays "stopped" for this long so a progress tick that was already in flight when
// the stop landed can't resurrect the session. Comfortably longer than the client's 10s tick.
private const val STOP_GRACE_MS = 60_000L

// Phase 180 (FR-180-3) — a stop that arrives for a key startPlayback hasn't reached started() for yet is
// held this long, not discarded as "unknown". Bounded well past the ~15s worst-case client retry backoff
// (PlayerStore.startSession: 1+2+4+8s between 5 attempts) so a late-succeeding retry still finds its
// abandonment recorded, but well short of STOP_WATCHDOG_MS — this is bridging one in-flight request pair,
// not covering a dead client.
private const val PENDING_STOP_TTL_MS = 30_000L

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

    // Phase 180 (FR-180-3) — key -> when the stop was requested. Present only while a stop has arrived
    // for a key that startPlayback hasn't called started() for yet (the abandon-during-negotiation race
    // R218 introduces real traffic for). Pruned by TTL alongside stoppedUntilMs in tracked().
    private val pendingStops = HashMap<PlaybackKey, Long>()

    // Non-suspend readers (nowPlaying, called from route handlers) can't take the mutex, so they read an
    // immutable snapshot republished on every mutation instead of iterating the live map.
    @Volatile
    private var snapshot: Map<PlaybackKey, TrackedPlayback> = emptyMap()

    // Phase 178 §FR-178-1 — when each key was last seen playing (started/heartbeated) OR stopped, kept
    // (not removed) for PLAYBACK_DEFER_GRACE_MS so [anyActive] bridges a between-episode gap or a brief
    // pause without immediately unleashing a deferred background job the next episode then has to fight.
    // A distinct grace window from stoppedUntilMs above — that one guards a race (a late in-flight
    // progress tick resurrecting an already-stopped session) and is tuned for that, not for this.
    private val lastSeen = HashMap<PlaybackKey, Pair<DeviceData, Long>>()
    @Volatile
    private var lastSeenSnapshot: Map<PlaybackKey, Pair<DeviceData, Long>> = emptyMap()

    /**
     * Phase 180 — [jellyfinPlaySessionId] is Jellyfin's own play-session id for this start (carried
     * through to [stopped] so a later teardown can release the right encode). Returns a [StartResult]:
     * [StartResult.stopAlreadyArrived] is true when a stop for this exact key was already recorded by
     * [stopped] before this start finished negotiating (FR-180-3) — the caller must tear the session it
     * just minted down immediately, since the viewer already left. [StartResult.supersededPlaySessionId]
     * carries a still-active EARLIER session's own play-session id when one existed for this exact key
     * (FR-180-1's "a new session for the same device superseding an older one") — the caller must
     * release that one too, since a second start for the same key without an intervening stop otherwise
     * leaks the first session's encode forever.
     */
    suspend fun started(
        device: DeviceData,
        jellyfinId: String,
        positionMs: Long,
        jellyfinPlaySessionId: String? = null,
    ): StartResult {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            stoppedUntilMs.remove(key)  // an explicit new start ends the post-stop grace window
            val stopAlreadyArrived = pendingStops.remove(key) != null
            val superseded = active[key]
            active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock(), jellyfinPlaySessionId)
            lastSeen[key] = device to clock()
            publish()
            StartResult(stopAlreadyArrived, superseded)
        }
    }

    /**
     * Records a heartbeat. Returns false when this is a late tick for a playback that already reported a
     * stop — bug fix: such a tick used to re-register the playback AND get pushed to Jellyfin,
     * resurrecting a finished session and overwriting the final resume position we had just written.
     */
    /**
     * Phase 180 bug fix — found live 2026-08-29: this used to construct a fresh [TrackedPlayback] with
     * [TrackedPlayback.jellyfinPlaySessionId] defaulting to null, silently wiping out whatever [started]
     * had recorded. Every real session's first progress heartbeat (~10s in, see PlayerStore's poll
     * interval) erased the id [stopped] needs to release the encode — confirmed live: a real NVENC
     * transcode survived 40+ seconds after an explicit stop because this was the only path in the whole
     * request lifecycle that touched the tracked entry between [started] and [stopped], and it dropped
     * the one field FR-180-2 exists for. A trivial test that stops immediately after starting (no
     * heartbeat in between) never hit this — which is exactly why it looked like it worked at first.
     */
    suspend fun heartbeat(device: DeviceData, jellyfinId: String, positionMs: Long): Boolean {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            if ((stoppedUntilMs[key] ?: 0L) > clock()) {
                false
            } else {
                val existingPlaySessionId = active[key]?.jellyfinPlaySessionId
                active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock(), existingPlaySessionId)
                lastSeen[key] = device to clock()
                publish()
                true
            }
        }
    }

    /**
     * Bug fix: the entry used to be removed by deviceId regardless of which item stopped, so a late stop
     * for episode 1 dropped the tracking for the episode actually playing.
     *
     * Phase 180 — returns the removed entry's `jellyfinPlaySessionId` (null if there was nothing active
     * for this key, or the active entry never got one) so the caller can release the right encode. When
     * nothing was active, records a [pendingStops] entry instead (FR-180-3) — a stop for a key that
     * hasn't reached [started] yet is a real race ([R218]'s abandon-during-negotiation case), not proof
     * there was nothing to stop.
     */
    suspend fun stopped(device: DeviceData, jellyfinId: String): String? {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            val existing = active.remove(key)
            stoppedUntilMs[key] = clock() + STOP_GRACE_MS
            lastSeen[key] = device to clock()  // Phase 178 — keeps this stop visible to anyActive()'s grace window
            if (existing == null) pendingStops[key] = clock()
            publish()
            existing?.jellyfinPlaySessionId
        }
    }

    /** Everything currently tracked, snapshotted so callers can suspend while walking it. Also prunes
     *  expired post-stop grace entries — the watchdog tick is the natural janitor. */
    suspend fun tracked(): List<TrackedPlayback> = mutex.withLock {
        val now = clock()
        stoppedUntilMs.entries.removeAll { it.value <= now }
        pendingStops.entries.removeAll { (_, requestedAt) -> now - requestedAt > PENDING_STOP_TTL_MS }
        lastSeen.entries.removeAll { (_, v) -> now - v.second > PLAYBACK_DEFER_GRACE_MS }
        active.values.toList()
    }

    /** Phase 178 §FR-178-1 — true while any device is actively playing, or was within the last
     *  [PLAYBACK_DEFER_GRACE_MS] (bridges a between-episode auto-advance gap or a brief pause so a
     *  deferred background job doesn't immediately unleash a burst the next episode then has to fight).
     *  Non-suspend — reads the same published snapshots [nowPlaying] does, so it's cheap to poll from
     *  anywhere (the pipeline step loop, the segments-lane worker, the `GET /api/playback/active` route). */
    fun anyActive(): Boolean {
        if (snapshot.isNotEmpty()) return true
        val now = clock()
        return lastSeenSnapshot.values.any { (_, ts) -> now - ts <= PLAYBACK_DEFER_GRACE_MS }
    }

    /** The display names of every device [anyActive] currently covers (active + within grace) — for a
     *  "Paused — TV is watching {name}" UI (FR-178-4) without a second device-store lookup. */
    fun activeDevices(): List<String> = activeDeviceObjects().map { it.displayName.ifBlank { it.deviceId } }

    /** Phase 236 (FR-236-9) — the ceiling and the cast status card need to filter by [DeviceData.kind],
     *  which a display-name string throws away; everything else keeps calling [activeDevices]. */
    fun activeDeviceObjects(): List<DeviceData> {
        val now = clock()
        val active = snapshot.values.map { it.device }
        val grace = lastSeenSnapshot.values.filter { (_, ts) -> now - ts <= PLAYBACK_DEFER_GRACE_MS }.map { it.first }
        return (active + grace).distinctBy { it.deviceId }
    }

    /** Has [playback] gone [STOP_WATCHDOG_MS] without a heartbeat (app kill / network drop / HDMI-off)? */
    fun isHeartbeatStale(playback: TrackedPlayback): Boolean =
        clock() - playback.heartbeatMs > STOP_WATCHDOG_MS

    companion object {
        /** 218 amendment (FR-218-11), extended by Phase 236 (FR-236-8) — which devices the watchdog may
         *  judge by their TV-events socket. A Ravilo TV or phone holds that socket for as long as the app
         *  is open, so "socket gone" means "app gone". A Chromecast receiver never opens one — it is a
         *  receiver page, driven by the phone over the Cast channel — so for it the socket test read as
         *  "disconnected" 11 s into every cast, the playback was force-stopped, Jellyfin was told so (an
         *  empty Now Playing card while the TV kept playing), and every later progress report was
         *  ignored. FR-236-8 extends the same reasoning to a Tizen/webOS screen: it is driven entirely by
         *  the backend's own push, not by holding this socket open, so the events socket closing must not
         *  end its play either. Both kinds are judged by heartbeat alone (reported every 10 s, well
         *  inside [STOP_WATCHDOG_MS]) — checked via [DeviceData.kind], not `platform` (kind is the field
         *  47.sqm's migration and every enrolment path since keep authoritative; a receiver's own
         *  X-Ravilo-Platform report was never guaranteed to be this exact string). */
        fun needsEventsSocket(device: DeviceData): Boolean = device.kind !in NO_SOCKET_KINDS
        private val NO_SOCKET_KINDS = setOf("cast", "screen")
    }

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
        lastSeenSnapshot = lastSeen.toMap()
    }
}

// Phase 178 §FR-178-1 — 120s: long enough to bridge a between-episode auto-advance gap or a brief pause,
// short enough that an evening of viewing doesn't starve deferred background work indefinitely.
private const val PLAYBACK_DEFER_GRACE_MS = 120_000L

internal val playbackTracker = PlaybackTracker()

/** Phase 111 (FR B.1) — the Jellyfin item id this device is actively playing, or null. Used by the
 *  remote-control device list; reads the same tracking the stop watchdog does, no separate state. */
fun nowPlayingItem(deviceId: String): String? = playbackTracker.nowPlaying(deviceId)

/** Phase 178 §FR-178-1 — is any device actively playing right now (or within its grace window)? The one
 *  signal every deferral check (pipeline steps, the segments-lane worker, `GET /api/playback/active`)
 *  consults — see [PlaybackTracker.anyActive]'s doc. */
fun isPlaybackActive(): Boolean = playbackTracker.anyActive()

/** The display names behind [isPlaybackActive] — for naming the device in a "Paused — TV is watching"
 *  UI state (FR-178-4). */
fun activePlaybackDeviceNames(): List<String> = playbackTracker.activeDevices()

/** Phase 236 (FR-236-9) — same live set as [activePlaybackDeviceNames], as full [DeviceData] so a
 *  [kind]-based filter (the cast ceiling, the cast status card) doesn't have to guess from a name. */
fun activePlaybackDevices(): List<DeviceData> = playbackTracker.activeDeviceObjects()

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
    private val playbackQoeStore: PlaybackQoeStore,
    // Phase 185 (FR-185-4) — written from stopPlayback only, on a real client-reported startupMs.
    private val playbackStartSampleStore: PlaybackStartSampleStore,
    // Phase 185 (FR-185-1) — persists the decode ceiling reported on every negotiation.
    private val raviloDeviceService: RaviloDeviceService,
    // Phase 218 (FR-218-8) — the concurrent-cast ceiling; null in tests that never cast.
    private val castService: CastService? = null,
    // Phase 219 (FR-219-2) — when given a scope, progress/stop writes are queued on a PlaybackWriter
    // running there and retried until Jellyfin acks; null (tests) keeps the direct inline path.
    writerScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    private val writer: PlaybackWriter? = writerScope?.let { PlaybackWriter(it, JellyfinSink()) }

    /** R291 (FR-R291-2) — the composed masters behind `/api/tv/stream/{id}/master.m3u8`, and the audio
     *  rendition sessions phase 180's teardown must stop one by one. */
    val audioRenditions = AudioRenditions(jellyfinClient::fetchPlaylist)

    /** Phase 180 + R291 — releases the encode AND every audio rendition job minted for it: one
     *  `DELETE /Videos/ActiveEncodings` stops one job (measured), so each rendition by its own session. */
    private suspend fun releaseEncodes(jellyfinBase: String, token: String, identity: JellyfinDeviceIdentity, jellyfinPlaySessionId: String) {
        jellyfinClient.stopActiveEncoding(jellyfinBase, token, identity, jellyfinPlaySessionId)
        for (s in audioRenditions.sessionsFor(jellyfinPlaySessionId)) jellyfinClient.stopActiveEncoding(jellyfinBase, token, identity, s)
    }

    /**
     * R291 (FR-R291-2) — for a player that switches HLS audio renditions in place (it said so), a
     * transcode's ticket points at the composed master instead of Jellyfin's own: every audio track, the
     * carried one muxed. Untouched for direct play (the container already has every track), for one audio
     * track, for a player that did not ask, and for a start the viewer has already abandoned (FR-180-3).
     */
    private suspend fun withRenditions(
        ticket: StreamTicket, capabilities: ClientCapabilities?, jellyfinBase: String, jellyfinId: String,
        token: String, identity: JellyfinDeviceIdentity, jellyfinPlaySessionId: String?, abandoned: Boolean,
    ): StreamTicket {
        if (ticket.directPlay || capabilities?.hlsAudioRenditions != true || jellyfinPlaySessionId == null || abandoned) return ticket
        val master = ticket.hlsUrl ?: return ticket
        val id = audioRenditions.register(
            jellyfinBase, jellyfinId, mediaSourceId = jellyfinId, token = token, identity = identity,
            jellyfinPlaySessionId = jellyfinPlaySessionId, jellyfinMasterUrl = master,
            audio = ticket.audio, carriedIndex = ticket.audioStreamIndex, expiresAt = ticket.expiresAt,
        ) ?: return ticket
        Logger.info("playback: item=$jellyfinId audio renditions=${ticket.audio.size} (R291)", "tv")
        return ticket.copy(hlsUrl = "/api/tv/stream/$id/master.m3u8", audioRenditions = true)
    }

    /** R248 — true when stops are queued on the [PlaybackWriter] (production): the Home-feed
     *  invalidation then runs from [onStopLanded] once Jellyfin has acknowledged the stop, not from the
     *  route right after it responds — before this the route's own invalidation raced the queued write
     *  and could rebuild Continue Watching from Jellyfin's *pre-stop* state. */
    val queuesStops: Boolean get() = writer != null

    /** Phase 236 (FR-236-8) — called with a device id every time [stopWatchdogTick] reaps a stale
     *  playback, so a screen's last-known status doesn't linger as "playing" once the backend itself
     *  has decided otherwise. Main wires this to clear [screenStatusTracker] and fan out the resulting
     *  `loaded=false` status to subscribers. */
    var onDeviceReaped: (suspend (String) -> Unit)? = null

    /** R248 (FR-R248-2) — called with the device once a queued STOP has landed in Jellyfin (Main wires
     *  it to `HomeFeedService.invalidatePlaystate`, which ends with the `home_changed` push). Never
     *  called for a stop that was abandoned; a throw here never turns the landed stop into a retry. */
    var onStopLanded: (suspend (DeviceData, String) -> Unit)? = null  // Phase 230 — (device, stopped Jellyfin id)

    /** Phase 219 — what one queued write does: the same token + identity + ids the inline path used. */
    private inner class JellyfinSink : PlaybackSink {
        override suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean {
            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = jellyfinClient.tvToken(jellyfinBase, w.device, configStore.current.apiKeys.jellyfinToken)
            return jellyfinClient.postPlaybackProgress(
                jellyfinBase, token, w.jellyfinId, w.positionMs * TICKS_PER_MS, w.isPaused, w.jellyfinId,
                JellyfinDeviceIdentity.forDevice(w.device), playSessionIdFor(w.device, w.jellyfinId),
            )
        }
        override suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean {
            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = jellyfinClient.tvToken(jellyfinBase, w.device, configStore.current.apiKeys.jellyfinToken)
            val identity = JellyfinDeviceIdentity.forDevice(w.device)
            val ok = jellyfinClient.postPlaybackStopped(
                jellyfinBase, token, w.jellyfinId, w.positionMs * TICKS_PER_MS, w.jellyfinId, identity, playSessionIdFor(w.device, w.jellyfinId),
            )
            // Phase 180 — release the encode once the stop has landed (idempotent; a release for a
            // session that never transcoded or already ended is a success, not an error).
            if (ok && w.jellyfinPlaySessionId != null) releaseEncodes(jellyfinBase, token, identity, w.jellyfinPlaySessionId)
            // R248 — Jellyfin has the stop: now (and only now) the Home feed can be rebuilt to show it.
            if (ok) onStopLanded?.let { hook -> runCatching { hook(w.device, w.jellyfinId) }.onFailure { Logger.warn("Home refresh after stop failed for ${w.jellyfinId}: ${it.message}", "tv") } }
            return ok
        }
    }

    /** Phase 219 (FR-219-4) — for `/api/health`. */
    suspend fun writerStats(): PlaybackWriter.Stats? = writer?.stats()

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
        // R292 (FR-R292-2, dev review item 2) — the client's own position wins over Jellyfin's user data
        // when present: a return from the background must start where the engine stopped, not where a
        // stop that has not landed yet says. Null = an ordinary start.
        startPositionMsOverride: Long? = null,
        // R291 (FR-R291-1) — the viewer's remembered audio choice, resolved below against this file's own
        // track list (the one the ticket carries) and asked of Jellyfin on the FIRST negotiation.
        audioLanguage: String? = null,
        audioVariant: String? = null,
    ): StreamTicket {
        requireVisible(device, jellyfinId)
        // Phase 218 (FR-218-8) — a receiver past `max_sessions` gets phase 182's 503 + Retry-After
        // (CastCeilingException → Server.kt StatusPages), never a spinner forever. A TV is never gated.
        castService?.checkCeiling(device, playbackTracker.activeDeviceObjects())
        // Phase 185 (FR-185-1) — every negotiation that reports at least one decode ceiling persists it,
        // regardless of what this particular file needs (ClientCapabilities always reports both
        // hevc/h264 ceilings together, not just the one this session happens to select).
        raviloDeviceService.recordDecodeCapabilities(
            device.deviceId, device.jellyfinUserId,
            capabilities.maxHevcBitrate.takeIf { it > 0 }?.toLong(),
            capabilities.maxH264Bitrate.takeIf { it > 0 }?.toLong(),
        )
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
        // R306 (FR-R306-2) — a Played item starts at 0: the detail page says *Play Again* for it, and R185
        // already rules that Played wins over whatever position Jellyfin left on it. The client's own
        // position (R292's return from the background) still wins over both.
        val saved = itemDetail?.userData
        val startPositionTicks = resolveStartPositionTicks(startPositionMsOverride, saved)
        val startPositionMs = startPositionTicks / TICKS_PER_MS
        val ignoredPositionTicks = saved?.takeIf { it.played }?.playbackPositionTicks?.takeIf { it > 0 }
        if (startPositionMsOverride == null && ignoredPositionTicks != null)
            Logger.info("playback start: device=${device.deviceId} item=$jellyfinId from 0 — played, so Jellyfin's ${ignoredPositionTicks / TICKS_PER_MS}ms is not a resume point (R306)", "tv")
        // R292 — one line per return, so a return that landed at the wrong place is readable from the log.
        if (startPositionMsOverride != null) Logger.info("playback start: device=${device.deviceId} item=$jellyfinId cut at ${startPositionMs}ms (the client's own position; Jellyfin held ${(itemDetail?.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS}ms)", "tv")

        // Start a Jellyfin playback session so the server tracks Now Playing + resume
        jellyfinClient.startPlaybackSession(jellyfinBase, token, jellyfinId, startPositionTicks, jellyfinId, identity, playSessionId)

        // R56: negotiate delivery via PlaybackInfo + DeviceProfile. Jellyfin tells us whether the item
        // can direct-play; if not, it hands back a TranscodingUrl. Fall back to a direct-play URL.
        // Bug fix: [capabilities] used to be discarded here — an HDR10/HLG source always direct-played
        // regardless of what the device could actually display correctly (see deviceProfile()'s doc).
        // Phase 161: moved before buildSubtracks() below — it now needs to know `needsTranscode`.
        // R291 (FR-R291-1) — one resolver, the picker's own (TrackVariants.kt in :shared), on the list the
        // ticket will carry; a match becomes the negotiation's AudioStreamIndex exactly as a restream's does.
        val wantedAudioIndex = audioLanguage?.let { lang ->
            val tracks = buildAudioTracks(itemDetail)
            dev.jellystructure.shared.tv.resolveAudioChoice(lang, audioVariant, tracks.map { dev.jellystructure.shared.tv.VersionInput(it.language, it.label, forced = false, isDefault = it.isDefault) })
                ?.let { tracks[it].index }
                ?.also { Logger.info("playback start: device=${device.deviceId} item=$jellyfinId audio $lang/${audioVariant ?: "-"} → stream $it (R291)", "tv") }
        }
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities, identity = identity, audioStreamIndex = wantedAudioIndex)
        val source = playbackInfo?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        // Phase 180 (FR-180-2) — Jellyfin's OWN play-session id, distinct from playSessionIdFor()'s
        // bookkeeping id below; this is the one stopActiveEncoding needs. Null when PlaybackInfo itself
        // was unavailable (source == null, plain direct-play-URL fallback) — nothing to ever release.
        val jellyfinPlaySessionId = playbackInfo?.playSessionId
        Logger.info(
            "PlaybackInfo: item=$jellyfinId directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode",
            "tv",
        )

        // Build the subtitle list from Jellyfin's MediaStreams for THIS playable item. Phase 161 / R209:
        // only sideload/burn a text-or-PGS subtitle when the file is actually transcoding OR the client
        // hasn't confirmed it can render embedded container subs — on a direct-played file, a client
        // that CAN (embedContainerSubs=true) already gets that exact stream natively from the container
        // (MatroskaExtractor), so sideloading/burning it too used to double-deliver it (see
        // buildSubtracks' own doc).
        val embedContainerSubs = !needsTranscode && capabilities.supportsEmbeddedTextSubs
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token, embedContainerSubs, hlsSubtitles = capabilities.hlsOnly && capabilities.hlsSubtitles)

        // Audio-track metadata (R46): the player labels embedded audio from the container, which often
        // lacks a track title — so carry Jellyfin's rich DisplayTitle (e.g. "Synstolkning") through the
        // ticket. Order matches the container's audio-stream order so the player can map by index.
        val audio = buildAudioTracks(itemDetail)

        // Phase 239 (FR-239-5) — on a transcode this is **Jellyfin's own** URL, verbatim from
        // PlaybackInfo's `TranscodingUrl`. Measured from a real PlaybackInfo on 12.1.0 (2026-09-20):
        // Jellyfin templates `ApiKey=`, the same parameter `withJellyfinToken` emits (matched
        // case-insensitively, no underscore) — so the server is not handing out URLs it will refuse to
        // authenticate. See [streamUrlFor].
        val streamUrl = streamUrlFor(jellyfinBase, jellyfinId, token, identity, source?.transcodingUrl?.takeIf { needsTranscode })

        val startResult = playbackTracker.started(device, jellyfinId, startPositionMs, jellyfinPlaySessionId)

        // Phase 180 (FR-180-1) — a still-active earlier session for this exact (device, item) key is
        // being replaced without an intervening stop; release it too, or its encode leaks forever (this
        // is the "new session superseding an older one" convergence path). NonCancellable: this request's
        // own connection is alive and its response doesn't depend on this, but it must still complete
        // even if something upstream tears the request coroutine down before we return.
        startResult.superseded?.let { old ->
            withContext(NonCancellable) {
                releaseSession(device, jellyfinId, old.positionMs, old.jellyfinPlaySessionId)
            }
        }

        if (startResult.stopAlreadyArrived) {
            // Phase 180 (FR-180-3) — a stop for this exact key already arrived while we were still
            // negotiating (R218's abandon-during-negotiation case): the viewer already left. Tear down
            // what we just minted immediately rather than leaving it live for however long it takes
            // something else to notice. NonCancellable because the client that would have awaited this
            // response is, by definition, already gone — its connection may already be closing.
            //
            // FR-180-4 — started() above already wrote this session into `active` (that write is what
            // lets a LATER started() call detect a genuine supersede); undo it via the same stopped()
            // every other exit path uses, or the abandoned session would briefly read as live to
            // anyActive()/nowPlaying() despite already being known-abandoned.
            withContext(NonCancellable) {
                playbackTracker.stopped(device, jellyfinId)
                releaseSession(device, jellyfinId, startPositionMs, jellyfinPlaySessionId)
            }
        }

        return withRenditions(StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            // R271 — empty, never the token. See StreamTicket.accessToken's own doc for why the field
            // still exists at all.
            accessToken = "",
            itemId = jellyfinId,
            container = "mkv", // conservative; Jellyfin transcodes if needed
            directPlay = !needsTranscode,
            hlsUrl = streamUrl, // direct-play URL or a Jellyfin TranscodingUrl per PlaybackInfo
            startPositionMs = startPositionMs,
            subtitles = subtitles,
            audio = audio,
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
            // Phase 253 (FR-253-2) — which audio a single-audio (transcoded) stream carries.
            audioStreamIndex = if (needsTranscode) carriedAudioIndex(source?.transcodingUrl, null) else null,
        ), capabilities, jellyfinBase, jellyfinId, token, identity, jellyfinPlaySessionId, abandoned = startResult.stopAlreadyArrived)
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
        // Phase 219 (FR-219-2) — queued and retried by the writer; the route answers at once.
        val w = writer
        if (w != null) { w.enqueueProgress(device, jellyfinId, positionMs, isPaused); return }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long, startupMs: Long? = null) {
        // No requireVisible() here deliberately — same reasoning as reportProgress above: this is
        // cleanup for a session startPlayback already gated, and it's also called from the stop
        // watchdog for stale/disconnected devices. Blocking it would risk leaving a phantom "Now
        // Playing" in Jellyfin forever, which is worse than the (already access-gated-at-start) cost
        // of letting an in-flight stop go through.
        val jellyfinPlaySessionId = playbackTracker.stopped(device, jellyfinId)
        Logger.info("playback stop: device=${device.deviceId} item=$jellyfinId at ${positionMs}ms", "tv")   // R292 — the number the resume record carries
        releaseSession(device, jellyfinId, positionMs, jellyfinPlaySessionId)
        // Phase 185 (FR-185-4) — session genuinely completed (this IS the stop path, not a mid-session
        // heartbeat) and the client reported a real startup duration: record one sample. The watchdog's
        // own forced stop (stopWatchdogTick) never supplies startupMs, so a device that vanished
        // mid-session correctly contributes nothing here.
        if (startupMs != null) recordStartSample(device, jellyfinId, startupMs)
    }

    private suspend fun recordStartSample(device: DeviceData, jellyfinId: String, startupMs: Long) {
        // Same top-level-or-episode lookup requireVisible() already does — a jellyfinId is either a
        // movie/series' own id or one of a series' episode ids; FR-185-9 keys by the FILE (Phase 149's
        // own multi-episode grouping key, R179), never the item id, so a combined S01E01-E03 file's
        // three episodes share one history.
        val fileId = mediaStore.resolveByJellyfinId(jellyfinId)?.path
            ?: mediaStore.allItems().firstOrNull { series -> series.episodes.any { it.jellyfinId == jellyfinId } }
                ?.episodes?.firstOrNull { it.jellyfinId == jellyfinId }?.path
            ?: return
        val seconds = ((startupMs + 500) / 1000L).toInt().coerceAtLeast(0)
        playbackStartSampleStore.record(device.deviceId, jellyfinId, fileId, seconds, mediaStore.nowMs())
    }

    /**
     * Phase 180 (FR-180-1/FR-180-2) — the one teardown routine every exit path converges on: the
     * existing Jellyfin stop report, now followed by releasing the encode (idempotent and
     * failure-tolerant per [dev.jellystructure.auth.JellyfinClient.stopActiveEncoding]'s own doc — a
     * release for a session that was never transcoding, or already ended, is a success, not an error).
     * [jellyfinPlaySessionId] is Jellyfin's own id (from [getPlaybackInfo]'s response), not
     * [playSessionIdFor]'s bookkeeping id — null skips the release outright (nothing was ever minted to
     * release, e.g. PlaybackInfo was unavailable at start time).
     */
    private suspend fun releaseSession(
        device: DeviceData,
        jellyfinId: String,
        positionMs: Long,
        jellyfinPlaySessionId: String?,
    ) {
        // Phase 219 (FR-219-2) — the stop is the write that must land: queued, retried past the
        // client's disconnect, and the encode released once it has (JellyfinSink.stop).
        val w = writer
        if (w != null) { w.enqueueStop(device, jellyfinId, positionMs, jellyfinPlaySessionId); return }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        val identity = JellyfinDeviceIdentity.forDevice(device)
        jellyfinClient.stopPlaybackSession(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, jellyfinId, identity, playSessionIdFor(device, jellyfinId),
        )
        if (jellyfinPlaySessionId != null) {
            releaseEncodes(jellyfinBase, token, identity, jellyfinPlaySessionId)
        }
    }

    /**
     * Phase 177 §FR-177-5 / R216 §FR-R216-4 — record one playback-quality report. `deviceId` and
     * `playSessionId` are both server-resolved (never taken from the request body) — [device] comes from
     * the caller's own Bearer token and [playSessionIdFor] is the exact same derivation every other
     * playback call uses, so a report can never claim to be a different device or session.
     *
     * No [requireVisible] check here deliberately, same reasoning as [reportProgress]/[stopPlayback]:
     * this is diagnostics for a session `startPlayback` already gated, and per the phase's own invariant
     * a QoE report must never affect playback — failing it closed would only risk losing the one signal
     * that explains a stutter, for no real security benefit (the row is keyed to the caller's own
     * authenticated device id regardless).
     */
    fun recordQoe(device: DeviceData, report: PlaybackQoeReport) {
        playbackQoeStore.record(device.deviceId, playSessionIdFor(device, report.itemId), report)
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
                if (playbackTracker.isHeartbeatStale(p) || (PlaybackTracker.needsEventsSocket(p.device) && !isDeviceConnected(p.device.deviceId))) add(p)
            }
        }
        for (p in stale) {
            Logger.info("Stop watchdog: force-stopping stale playback item=${p.jellyfinId} device=${p.device.deviceId}", "tv")
            runCatching { stopPlayback(p.device, p.jellyfinId, p.positionMs) }
                .onFailure { Logger.warn("Stop watchdog: force-stop failed: ${it.message}", "tv") }
            // Phase 236 (FR-236-8) — a screen's status is display state, not tied to the Jellyfin stop
            // call's own success; clear it regardless of whether the stop above landed.
            runCatching { onDeviceReaped?.invoke(p.device.deviceId) }
                .onFailure { Logger.warn("Stop watchdog: onDeviceReaped failed: ${it.message}", "tv") }
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

    /**
     * Phase 161 / R209: [embedContainerSubs] (renamed from `embedTextSubs` by R209) — when true, a
     * subtitle stream the client can decode straight from the container (text via `MatroskaExtractor`'s
     * SRT/ASS/SSA parsing, *and* PGS via the same extractor's PGS parsing — R209) is declared `"embed"`
     * (`url = null`) instead of sideloaded/burned, because the caller has already confirmed BOTH that
     * the file is direct-playing (so the container the client receives genuinely still carries this
     * exact stream) AND that the client renders it natively from there (`ClientCapabilities.
     * supportsEmbeddedTextSubs` — one flag covers both codec families since Android's "yes" answer
     * comes from `MatroskaExtractor`, which decodes both the same way). Bug fix (R55 origin, text):
     * R55 originally sideloaded every text subtitle unconditionally — correct when the player had no
     * in-container text-track rendering at all, but once a client's `MatroskaExtractor` also parses the
     * same embedded stream, sideloading it too double-delivers it (visible in R195's picker as e.g. two
     * identical "English" rows; R183 also attributed a multi-minute Jellyfin ffmpeg VTT-extraction stall
     * to this on large titles). Bug fix (R209, PGS): the PGS branch below used to route to `"encode"`
     * (burn-in) unconditionally regardless of native in-container support, so a PGS track a client could
     * already render natively showed up TWICE (once native, once as a burn-in candidate) — picking the
     * duplicate forced a real transcode that permanently baked that subtitle into the video for the rest
     * of the session. Every branch below also now excludes `s.isExternal` streams (R209): an external
     * (sidecar-file) subtitle was never actually muxed into the container, so `MatroskaExtractor` can
     * never substitute for it — marking it "embed" silently dropped it (`RaviloPlayerAndroid.load()`'s
     * `subConfigs` drops any `SubTrack` with a `null` url), which is exactly how a live report ("Pinocchio")
     * lost its external English/Danish subtitles entirely while Italian/German (embedded PGS) still
     * showed up twice.
     */
    private fun buildSubtracks(
        itemDetail: JellyfinItemDetail?,
        jellyfinId: String,
        jellyfinBase: String,
        token: String,
        embedContainerSubs: Boolean,
        // R265 (FR-R265-8) — text subtitles ride the HLS manifest (the client asked for them there, see
        // ClientCapabilities.hlsSubtitles); the same condition deviceProfile() uses to ask Jellyfin for it.
        hlsSubtitles: Boolean = false,
    ): List<SubTrack> {
        val streams = itemDetail?.mediaStreams ?: return emptyList()
        return streams
            .filter { it.type.equals("Subtitle", ignoreCase = true) }
            .mapNotNull { s ->
                val codec = s.codec?.lowercase()
                when {
                    // Phase 161 / R209: this exact stream is already natively available in-container —
                    // never ALSO sideload it (see this function's own doc). Never true for an external
                    // (sidecar-file) stream — R209 — since it was never muxed in to begin with.
                    (s.isTextSubtitleStream || isTextSubCodec(s.codec)) && embedContainerSubs && !s.isExternal -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = null,
                        deliveryMethod = "embed",
                    )
                    // R265 (FR-R265-8) — in the manifest, not sideloaded: no URL, the client selects the
                    // rendition itself. Same stream order as the manifest's renditions.
                    (s.isTextSubtitleStream || isTextSubCodec(s.codec)) && hlsSubtitles -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = null,
                        deliveryMethod = "hls",
                    )
                    // R55: text subs (SRT/ASS/SSA/VTT/muxed, or external — R209) — sideloaded via
                    // Jellyfin's VTT extractor.
                    s.isTextSubtitleStream || isTextSubCodec(s.codec) -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        // FR-239-2 — sideloaded by the client's own player.
                        url = withJellyfinToken("$jellyfinBase/Videos/$jellyfinId/$jellyfinId/Subtitles/${s.index}/0/Stream.vtt", token),
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
                    // R209: PGS a direct-playing, capability-confirmed, non-external client already
                    // decodes natively via MatroskaExtractor — same treatment as VobSub/DVDSub above,
                    // no burn-in candidate needed (was unconditional "encode" before this phase, causing
                    // the native track to be listed a second time as a redundant burn-in duplicate).
                    codec != null && isPgsSubCodec(codec) && embedContainerSubs && !s.isExternal -> SubTrack(
                        index = s.index,
                        language = s.language,
                        label = s.displayTitle ?: s.title,
                        forced = s.isForced,
                        isDefault = s.isDefault,
                        url = null,
                        deliveryMethod = "embed",
                    )
                    // R56: PGS — burn-in via Jellyfin HLS transcode (encode path). Reached whenever the
                    // client hasn't confirmed native in-container PGS support, the file is transcoding,
                    // or the stream is external.
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

    /**
     * R56 — Re-stream the item with a PGS subtitle burned in via Jellyfin HLS transcode.
     *
     * Phase 252 — a NEGATIVE [subtitleStreamIndex] is the inverse: the same item at [positionMs] with
     * no burn-in at all, negotiated the way [startPlayback] negotiates (see [restreamWithoutBurnIn]).
     * Until 252 this function could only ever add a burn-in, so once a subtitle was in the picture
     * neither "Off" nor another subtitle could take it out again for the rest of the session.
     */
    suspend fun restream(
        device: DeviceData,
        jellyfinId: String,
        subtitleStreamIndex: Int,
        positionMs: Long,
        capabilities: ClientCapabilities? = null,
        audioStreamIndex: Int? = null,
    ): StreamTicket {
        if (subtitleStreamIndex < 0) return restreamWithoutBurnIn(device, jellyfinId, positionMs, capabilities ?: ClientCapabilities(), audioStreamIndex)
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        // Phase 161 / R209: always false here — restream() forces a transcode (directPlay = false
        // below), and a transcoded output doesn't carry the source's original embedded subtitle
        // streams (text or PGS), so there's nothing to double by sideloading/burning; this path's subs
        // were never affected by either bug.
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token, embedContainerSubs = false, hlsSubtitles = capabilities?.let { it.hlsOnly && it.hlsSubtitles } ?: false)
        val audio = buildAudioTracks(itemDetail)
        // R56: ask Jellyfin (PlaybackInfo + DeviceProfile, with the sub index for Encode burn-in) for the
        // real TranscodingUrl; fall back to a hand-built HLS burn-in URL if PlaybackInfo is unavailable.
        // Bug fix: this used to pass subtitleStreamIndex positionally into what is now the new
        // `capabilities` parameter slot — named args here since burn-in restream doesn't have the
        // original session's capabilities on hand; ClientCapabilities()'s conservative SDR-only default
        // is fine since this path already forces a transcode for the subtitle burn-in regardless.
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, subtitleStreamIndex = subtitleStreamIndex, identity = identity, audioStreamIndex = audioStreamIndex)
        val negotiated = playbackInfo?.mediaSources?.firstOrNull()?.transcodingUrl
            ?.let { if (it.startsWith("http")) it else "$jellyfinBase$it" }
        Logger.info("PlaybackInfo(restream, burn-in): item=$jellyfinId sub=$subtitleStreamIndex audio=${audioStreamIndex ?: "default"} negotiated=${negotiated != null}", "tv")
        // FR-239-2/-5 — `negotiated` is Jellyfin's own URL and is passed through untouched (see the
        // note at [startPlayback]'s streamUrl). Only the hand-built FALLBACK, used when PlaybackInfo is
        // unavailable, is ours to spell — and it is the branch that runs least often, which is exactly
        // why the pre-239 grep would have looked clean while every real transcoded play still carried a
        // Jellyfin-spelled token. `withJellyfinToken` owns the separator too: this concatenation used
        // to start its last fragment with a hand-written `&`.
        val transcodingUrl = negotiated ?: withJellyfinToken(
            "$jellyfinBase/Videos/$jellyfinId/master.m3u8" +
                "?DeviceId=${identity.deviceId}" +
                "&MediaSourceId=$jellyfinId" +
                "&VideoCodec=h264" +
                "&AudioCodec=aac" +
                "&MaxWidth=1920&MaxHeight=1080" +
                "&SubtitleMethod=Encode" +
                "&SubtitleStreamIndex=$subtitleStreamIndex",
            token,
        )

        // Phase 180 — found live 2026-08-29: this path forces a transcode on EVERY call (burn-in always
        // transcodes) yet never registered with playbackTracker at all, so its own real Jellyfin
        // playSessionId (playbackInfo?.playSessionId — same distinct-from-playSessionIdFor() namespace
        // as startPlayback's, see stopActiveEncoding's doc) was silently discarded and stopPlayback()
        // could never release it: confirmed with a real NVENC HDR tonemap transcode left running for
        // minutes after an explicit stop. restream() always supersedes whatever startPlayback() already
        // registered for this exact key (same device, same item — R56 restream is mid-session, not a
        // new item), so this is the FR-180-1 supersede path, not a fresh started() call conceptually;
        // reusing started() here is still correct — it releases the entry being replaced (the original
        // direct-play/transcode session's own encode, if it had one) exactly the same way a genuine new
        // session would.
        val startResult = playbackTracker.started(device, jellyfinId, positionMs, playbackInfo?.playSessionId)
        startResult.superseded?.let { old ->
            withContext(NonCancellable) {
                releaseSession(device, jellyfinId, old.positionMs, old.jellyfinPlaySessionId)
            }
        }
        if (startResult.stopAlreadyArrived) {
            withContext(NonCancellable) {
                playbackTracker.stopped(device, jellyfinId)
                releaseSession(device, jellyfinId, positionMs, playbackInfo?.playSessionId)
            }
        }

        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            // R271 — empty, never the token. See StreamTicket.accessToken's own doc for why the field
            // still exists at all.
            accessToken = "",
            itemId = jellyfinId,
            container = "mkv",
            directPlay = false,
            hlsUrl = transcodingUrl,
            startPositionMs = positionMs,
            subtitles = subtitles,
            audio = audio,
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
            // Phase 252 (FR-252-1) — the one fact the client cannot get anywhere else: this subtitle
            // is already in the pixels, so no text track may render beside it.
            burnedSubtitleIndex = subtitleStreamIndex,
            audioStreamIndex = carriedAudioIndex(transcodingUrl, audioStreamIndex),
        ).let { withRenditions(it, capabilities, jellyfinBase, jellyfinId, token, identity, playbackInfo?.playSessionId, abandoned = startResult.stopAlreadyArrived) }
    }

    /**
     * Phase 252 (FR-252-2/-3/-4) — [restream]'s un-burn branch: a fresh ticket for the same item at
     * [positionMs], negotiated exactly as [startPlayback] does (so it may direct-play, and then
     * 161/R209's in-container rule applies to its subtitle list), minus everything that belongs to a
     * session's FIRST start: no resume-position read, no `Sessions/Playing` report — the session is
     * already running and its heartbeat continues. Goes through the same [PlaybackTracker.started]
     * supersede handling as the burn-in branch, which is what releases the burn-in encode it replaces.
     */
    private suspend fun restreamWithoutBurnIn(
        device: DeviceData,
        jellyfinId: String,
        positionMs: Long,
        capabilities: ClientCapabilities,
        audioStreamIndex: Int?,
    ): StreamTicket {
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities, identity = identity, audioStreamIndex = audioStreamIndex)
        val source = playbackInfo?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        // 2026-09-24 — this path serves an un-burn AND a plain audio switch (R284), and cannot tell them
        // apart; it used to log every one of them as "un-burn", which misread the soveværelse-TV sweep.
        Logger.info("PlaybackInfo(restream, no burn-in): item=$jellyfinId audio=${audioStreamIndex ?: "default"} directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode", "tv")
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token, embedContainerSubs = !needsTranscode && capabilities.supportsEmbeddedTextSubs, hlsSubtitles = capabilities.hlsOnly && capabilities.hlsSubtitles)

        val startResult = playbackTracker.started(device, jellyfinId, positionMs, playbackInfo?.playSessionId)
        startResult.superseded?.let { old ->
            withContext(NonCancellable) { releaseSession(device, jellyfinId, old.positionMs, old.jellyfinPlaySessionId) }
        }
        if (startResult.stopAlreadyArrived) {
            withContext(NonCancellable) {
                playbackTracker.stopped(device, jellyfinId)
                releaseSession(device, jellyfinId, positionMs, playbackInfo?.playSessionId)
            }
        }

        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = "", // R271 — see StreamTicket.accessToken
            itemId = jellyfinId,
            container = "mkv",
            directPlay = !needsTranscode,
            hlsUrl = streamUrlFor(jellyfinBase, jellyfinId, token, identity, source?.transcodingUrl?.takeIf { needsTranscode }),
            startPositionMs = positionMs,
            subtitles = subtitles,
            audio = buildAudioTracks(itemDetail),
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
            audioStreamIndex = if (needsTranscode) carriedAudioIndex(source?.transcodingUrl, audioStreamIndex) else null,
        ).let { withRenditions(it, capabilities, jellyfinBase, jellyfinId, token, identity, playbackInfo?.playSessionId, abandoned = startResult.stopAlreadyArrived) }
    }

    /**
     * The stream URL for a negotiated session: Jellyfin's own `TranscodingUrl` verbatim when it is
     * transcoding (FR-239-5 — its credential spelling is Jellyfin's to get right, never rewritten
     * here), else our static direct-play URL (FR-239-2 — handed to a player, which cannot attach a
     * header). One spelling, shared by [startPlayback] and [restreamWithoutBurnIn].
     */
    private fun streamUrlFor(jellyfinBase: String, jellyfinId: String, token: String, identity: JellyfinDeviceIdentity, transcodingUrl: String?): String =
        if (transcodingUrl != null) {
            if (transcodingUrl.startsWith("http")) transcodingUrl else "$jellyfinBase$transcodingUrl"
        } else {
            withJellyfinToken("$jellyfinBase/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&DeviceId=${identity.deviceId}", token)
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

    private fun isEmbedImageSubCodec(c: String): Boolean =
        c in setOf("dvd_subtitle", "dvdsub", "vobsub", "dvbsub", "dvb_subtitle")

    private fun isPgsSubCodec(c: String): Boolean =
        c in setOf("hdmv_pgs_subtitle", "pgssub", "pgs")
}

/**
 * Phase 253 (FR-253-2) — the audio track a transcoded stream really carries: the `AudioStreamIndex`
 * Jellyfin wrote into its own `TranscodingUrl` (measured on 12.1.0: present whether or not one was
 * requested, and equal to the request when one was), else what was [requested], else unknown. What
 * Jellyfin DID outranks what it was asked — a ticket states facts.
 */
internal fun carriedAudioIndex(transcodingUrl: String?, requested: Int?): Int? =
    transcodingUrl?.let { Regex("[?&]AudioStreamIndex=(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: requested

/**
 * Phase 179 — moved out of [PlaybackService] (was `private`) so [dev.jellystructure.media.PipelineStepOps]
 * can reuse the exact same text-subtitle predicate [buildSubtracks] uses, rather than a second,
 * possibly-diverging copy. `internal`: visible module-wide, not exported past this Gradle target.
 */
internal fun isTextSubCodec(c: String?): Boolean =
    (c?.lowercase()) in setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text")

/**
 * The token the TV should use for Jellyfin reads/streaming: the paired user token when Jellyfin still
 * accepts it, else the long-lived **server** token. A stale paired token (captured at pairing, later
 * invalidated by Jellyfin) otherwise 401s every call — empty home feed, episodes falling back to a
 * file-path id, malformed stream URLs, black screen. The user's `userId` stays in each request URL,
 * so per-user data (resume/watched/next-up) is still correct under the server token.
 *
 * Validity is cached for [TOKEN_VALID_TTL_MS] so we don't pay a round-trip to Jellyfin on every
 * route handler. On a cache hit this returns instantly; on a miss we fall through to checkToken().
 *
 * Phase 194 — this returns the three-state [TokenCheck], not a `Boolean`. An unreachable Jellyfin and
 * a rejected credential are different facts, and only one of them is a reason to stop trusting the
 * credential. See FR-194-2 for what may be cached, and FR-194-3 for why the two callers below
 * diverge on [TokenCheck.UNKNOWN].
 */
private suspend fun JellyfinClient.pairedTokenCheck(baseUrl: String, device: DeviceData): TokenCheck {
    // Phase 224 (FR-224-4) — before this device's token goes anywhere (checkToken below, tvToken's
    // callers after it), the registry knows whose it is, so no call can send it under Device="Server".
    dev.jellystructure.auth.DeviceIdentityRegistry.remember(device)
    val userToken = device.jellyfinUserToken
    val now = nowMs()
    tokenCacheMutex.withLock {
        // Fast path: token was recently validated — skip the Jellyfin round-trip.
        tokenValidUntil[userToken]?.let { if (it > now) return TokenCheck.VALID }
        // Phase 110: negative-cached — known-dead as of a recent check, skip the round-trip too.
        tokenInvalidUntil[userToken]?.let { if (it > now) return TokenCheck.REJECTED }
    }
    // Slow path: check with Jellyfin. Phase 205 (FR-205-4) — bounded well under the client's own 120s
    // default; a timeout here is UNKNOWN (Jellyfin didn't answer), never REJECTED (that would escalate
    // "could not tell" into a ten-minute household-wide credential rejection).
    var result = withTimeoutOrNull(TOKEN_CHECK_TIMEOUT_MS) { checkToken(baseUrl, userToken, device.jellyfinUserId) }
        ?: TokenCheckResult(TokenCheck.UNKNOWN, error = "timed out after ${TOKEN_CHECK_TIMEOUT_MS}ms")
    // FR-194-5 — believing a rejection costs the whole household ten minutes of playback, so it does
    // not get to rest on a single round trip. Re-probe once; only a second consecutive REJECTED is
    // believed. This runs only on the already-rare rejection path, never on the cache-served hot one.
    if (result.outcome == TokenCheck.REJECTED && userToken.isNotBlank()) {
        delay(500)
        result = withTimeoutOrNull(TOKEN_CHECK_TIMEOUT_MS) { checkToken(baseUrl, userToken, device.jellyfinUserId) }
            ?: TokenCheckResult(TokenCheck.UNKNOWN, error = "timed out after ${TOKEN_CHECK_TIMEOUT_MS}ms")
    }
    when (result.outcome) {
        TokenCheck.VALID -> tokenCacheMutex.withLock {
            tokenValidUntil[userToken] = nowMs() + TOKEN_VALID_TTL_MS
            tokenInvalidUntil.remove(userToken)
            tokenRejectionLogged.remove(userToken)
        }
        TokenCheck.REJECTED -> tokenCacheMutex.withLock {
            tokenValidUntil.remove(userToken)
            tokenInvalidUntil[userToken] = nowMs() + TOKEN_NEGATIVE_TTL_MS
        }
        // FR-194-2 — cache nothing, clear nothing. The next call re-probes. Crucially this must not
        // drop an existing positive entry either: the old `else` branch did, so one blip also threw
        // away a validity we had already established and paid a round trip for.
        TokenCheck.UNKNOWN -> Unit
    }
    // FR-194-4 — say what actually happened. The rejection line is still logged once per transition
    // (Phase 110 FR E), but the UNKNOWN lines are deliberately NOT suppressed by tokenRejectionLogged:
    // a repeat means Jellyfin is flapping, which is exactly what an operator needs to see.
    when (result.outcome) {
        TokenCheck.REJECTED -> if (tokenRejectionLogged.add(userToken)) {
            Logger.warn(
                "TV: Jellyfin rejected this device's paired token " +
                    "(${result.httpStatus?.let { "HTTP $it" } ?: result.error}) for user ${device.jellyfinUserId} " +
                    "— negative-cached ${TOKEN_NEGATIVE_TTL_MS / 60_000}min; re-pair the device",
                "tv",
            )
        }
        TokenCheck.UNKNOWN -> Logger.warn(
            "TV: could not verify paired token for user ${device.jellyfinUserId} — " +
                (result.error?.let { "$it" } ?: "Jellyfin returned HTTP ${result.httpStatus}") +
                "; not cached, will retry",
            "tv",
        )
        TokenCheck.VALID -> Unit
    }
    return result.outcome
}

internal suspend fun JellyfinClient.tvToken(baseUrl: String, device: DeviceData, serverToken: String): String =
    if (pairedTokenCheck(baseUrl, device) == TokenCheck.VALID) device.jellyfinUserToken else serverToken

/**
 * Security fix (2026-08-02 review, finding H2) — [tvToken] falls back to the long-lived **server**
 * token when the device's paired token has gone stale, which is fine for calls the server makes on
 * the device's behalf without ever showing the token to it. It is NOT fine for [startPlayback]/
 * [restream]: those embed the token directly into the stream URL the client is handed (see
 * `withJellyfinToken`) — so any signed-in device (including a Kids/library-restricted profile) would
 * receive full Jellyfin **admin** credentials the moment its own token went stale, which the
 * surrounding negative-cache logic treats as routine. (R271 stopped populating `StreamTicket.accessToken`,
 * which used to be a second copy of the same credential — the field itself survives, always empty,
 * for wire compatibility; the URL is still the mechanism, so this fix is unchanged either way.) This variant never falls back — it returns
 * null so the caller can surface a clear "re-pair this device" error instead of silently handing out
 * server-admin access.
 *
 * Phase 194 (FR-194-3) — that fix is about never *escalating* a stale device token to server-admin
 * credentials, and it is untouched here: [TokenCheck.REJECTED] still returns null. But on
 * [TokenCheck.UNKNOWN] we hand the device back *its own* token, the one it already holds. No
 * privilege is escalated and no token crosses a boundary it had not already crossed. If the token
 * really is dead the downstream Jellyfin call fails on its own and the client sees a real error —
 * one request later, instead of a ten-minute deterministic outage for every device of that user.
 */
internal suspend fun JellyfinClient.tvTokenForClient(baseUrl: String, device: DeviceData): String? =
    if (pairedTokenCheck(baseUrl, device) != TokenCheck.REJECTED) device.jellyfinUserToken else null

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

/**
 * R306 (FR-R306-2) — where a start begins: the client's own position when it sends one (R292's return from
 * the background), else Jellyfin's saved position — except on a Played item, which starts at 0 (R185:
 * Played wins over a leaked position; the page says *Play Again*).
 */
internal fun resolveStartPositionTicks(overrideMs: Long?, saved: dev.jellystructure.auth.JellyfinUserData?): Long =
    overrideMs?.coerceAtLeast(0L)?.let { it * TICKS_PER_MS }
        ?: if (saved?.played == true) 0L else (saved?.playbackPositionTicks ?: 0L)
