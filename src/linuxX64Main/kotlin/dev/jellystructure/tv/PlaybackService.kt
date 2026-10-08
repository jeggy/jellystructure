package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.fileDurationMs
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
import dev.jellystructure.shared.tv.playbackFinished
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.SubTrack
import dev.jellystructure.auth.withJellyfinToken
import dev.jellystructure.auth.withChannelLimit
import dev.jellystructure.auth.channelLimit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
// R381 (FR-R381-7, dev review item 8) — a prepared next item is valid for at most five minutes.
private const val PREPARED_TTL_MS = 5 * 60 * 1000L
// 309 (FR-309-6) — a prewarm's stream id lives as long as a ticket (its job idles out after 60 s with no request).
private const val PREWARM_TTL_MS = 4 * 60 * 60 * 1000L

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

/** 308 — query keys a logged stream URL never shows (credentials and Jellyfin's cache tag), compared by name. */
private val LOG_REDACTED_QUERY_KEYS = setOf("api_key", "apikey", "tag")

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
internal data class StartResult(
    val stopAlreadyArrived: Boolean,
    val superseded: TrackedPlayback?,
    /** Phase 312 — the position the viewer's own early stop carried (when [stopAlreadyArrived]), so the abandoned
     *  start re-sends THAT stop, never one at its own start position (which is 0 for a play from the beginning). */
    val stoppedAtMs: Long? = null,
)

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
    /** 286 (FR-286-8) — the ticket was a direct play: for a cast receiver, not a session against the ceiling. */
    val directPlay: Boolean = false,
) {
    /** 306 (FR-306-1) — a progress report moves the position and the heartbeat time and nothing else, so every
     *  field the start recorded (and any field added later — add it here too) survives the first report. */
    fun withProgress(positionMs: Long, heartbeatMs: Long) =
        TrackedPlayback(device, jellyfinId, positionMs, heartbeatMs, jellyfinPlaySessionId, directPlay)
}

private val repairJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val STOP_WATCHDOG_MS = 90_000L

// Phase 312 (FR-312-3) — how long after our stop's user-data write the backend reads it back, and how far the place
// Jellyfin holds may differ from ours before it writes it again.
private const val READ_BACK_DELAY_MS = 3_000L
private const val READ_BACK_TOLERANCE_MS = 2_000L

// R343 (dev review item 3) — how long a stop waits for a running Start over clear before it queues its own write.
private const val START_OVER_CLEAR_WAIT_MS = 15_000L

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
    private val pendingStopPositions = HashMap<PlaybackKey, Long>()

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
        directPlay: Boolean = false,
    ): StartResult {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            stoppedUntilMs.remove(key)  // an explicit new start ends the post-stop grace window
            val stopAlreadyArrived = pendingStops.remove(key) != null
            val stoppedAt = pendingStopPositions.remove(key)
            val superseded = active[key]
            active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock(), jellyfinPlaySessionId, directPlay)
            lastSeen[key] = device to clock()
            publish()
            StartResult(stopAlreadyArrived, superseded, stoppedAt.takeIf { stopAlreadyArrived })
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
                // 306 — keep what the start recorded (directPlay, the play-session id); no entry (restart, or a
                // report ahead of its start) stays conservative: directPlay = false counts against the ceiling.
                active[key] = active[key]?.withProgress(positionMs, clock())
                    ?: TrackedPlayback(device, jellyfinId, positionMs, clock())
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
    suspend fun stopped(device: DeviceData, jellyfinId: String, positionMs: Long? = null): String? {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        return mutex.withLock {
            val existing = active.remove(key)
            stoppedUntilMs[key] = clock() + STOP_GRACE_MS
            lastSeen[key] = device to clock()  // Phase 178 — keeps this stop visible to anyActive()'s grace window
            if (existing == null) { pendingStops[key] = clock(); positionMs?.let { pendingStopPositions[key] = it } }
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
        pendingStopPositions.keys.retainAll(pendingStops.keys)
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

    /** 286 (FR-286-8) — the devices whose every active playback is a direct play (nothing converting for them). */
    fun activeDirectDeviceIds(): Set<String> {
        val byDevice = snapshot.values.groupBy { it.device.deviceId }
        return byDevice.filterValues { list -> list.all { it.directPlay } }.keys
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
    // Phase 310 (owner decision 2) — queued STOPs kept in SQLite until Jellyfin has them; null in tests.
    playbackOutbox: PlaybackOutbox? = null,
    // 309 (FR-309-1) — what each device has shown it can take; null in tests that never look at it.
    private val streamRecords: StreamRecordStore? = null,
) {
    private val writer: PlaybackWriter? = writerScope?.let { PlaybackWriter(it, JellyfinSink(), outbox = playbackOutbox) }

    /** R343 — where Start over's clear and R347's tick run without holding a request (null in tests: inline). */
    private val backgroundScope: kotlinx.coroutines.CoroutineScope? = writerScope

    /** R347 (FR-R347-2) — the detail payloads' own intro/credits lookup ([DetailService.segmentsFor]), so the
     *  stop judges "finished" by the same credits marker the player's next-up card fires at. Null in tests. */
    var segmentsFor: ((itemId: String, episodeKey: String, episodeNumber: Int, legacyStinger: dev.jellystructure.model.Stinger?) -> dev.jellystructure.shared.tv.TvSegmentMarkers)? = null

    /** R343 (FR-R343-4) — after Start over's clear: the same invalidation `PUT /tv/played` runs (the Continue
     *  list rebuilt, `home_changed` and `playstate_changed` pushed). Main wires it to `invalidatePlaystate`. */
    var onSeriesCleared: (suspend (DeviceData, String) -> Unit)? = null

    /** R375 (FR-R375-6) — (user, series Jellyfin id) → that series' last finished episode's date as the last Continue
     *  build saw it. Main wires it to `HomeFeedService.anchorDate`; null in tests (no anchor ⇒ Jellyfin's date stays). */
    var anchorDateFor: ((String, String) -> String?)? = null

    // R343 / R347 — per-session plans (see SessionPlan), the Start over latch, and a running clear per session.
    private val plansMutex = Mutex()
    private val plans = HashMap<PlaybackKey, SessionPlan>()
    private val clearLatched = HashSet<PlaybackKey>()
    private val clearJobs = HashMap<PlaybackKey, kotlinx.coroutines.Job>()

    /** R291 (FR-R291-2) — the composed masters behind `/api/tv/stream/{id}/master.m3u8`, this server's own
     *  audio renditions beside them, and the jobs phase 180's teardown stops with the playback. */
    val audioRenditions = AudioRenditions(jellyfinClient::fetchPlaylist)

    /** Phase 313 — our own encoder (every quality from one ffmpeg per play); Jellyfin's transcode is the fallback. */
    val encoder = Encoder({ configStore.current.encoder }, fetchText = jellyfinClient::fetchPlaylist)

    /** Phase 180 + R291 — releases the encode AND this server's audio rendition jobs for it. */
    private suspend fun releaseEncodes(jellyfinBase: String, token: String, identity: JellyfinDeviceIdentity, jellyfinPlaySessionId: String) {
        // Phase 313 (dev review item 13) — a play our encoder serves has no Jellyfin job: stop ours. Jellyfin's stop is
        // still sent (it is a no-op for a play session with no job) so a fallback play is always released.
        encoder.stopFor(jellyfinPlaySessionId)
        jellyfinClient.stopActiveEncoding(jellyfinBase, token, identity, jellyfinPlaySessionId)
        // 308 (FR-308-1) — every ladder variant is a job under its own play session: each is stopped by its own id.
        audioRenditions.stopFor(jellyfinPlaySessionId).forEach { jellyfinClient.stopActiveEncoding(jellyfinBase, token, identity, it) }
    }

    /**
     * 308 (FR-308-3/-4) — what this device has measured its path to carry (median of its latest HLS bandwidth
     * estimates, R216), or null when it has measured nothing yet. A measurement, never a location.
     */
    private fun measuredThroughputOf(device: DeviceData): Long? = qoeMeasurementOf(device)?.first

    /** 308/309 (FR-309-13) — the QoE rows' real measurement and when its newest sample was taken (seconds), or null. */
    private fun qoeMeasurementOf(device: DeviceData): Pair<Long, Long>? = qoeMeasurementOf(device.deviceId)

    private fun qoeMeasurementOf(deviceId: String): Pair<Long, Long>? = runCatching {
        val samples = playbackQoeStore.recentForDevice(deviceId, 20).map { ThroughputSample(it.bandwidthEstimateBps, it.directPlay, it.updatedAt, it.bandwidthSamples) }
        val now = nowMs() / 1000
        val bps = measuredThroughput(samples, now) ?: return@runCatching null
        val at = samples.filter { isRealMeasurement(it) && now - it.updatedAtSec <= RECORD_MAX_AGE_SEC }.maxOfOrNull { it.updatedAtSec } ?: now
        bps to at
    }.getOrNull()

    /** 309 (FR-309-1) — this device's record (null without the store, or with no row yet). */
    private fun recordOf(device: DeviceData): StreamRecord? = runCatching { streamRecords?.get(device.deviceId) }.getOrNull()

    /**
     * 309 (FR-309-1) — what [device] can take now (its proof, its newest measurement × 0.7, a recent stall's cap), in the
     * master's own units; null = it has shown nothing yet. One decision for the negotiation's cap, the start rung and
     * the seed, so they never disagree.
     */
    internal fun takeOf(device: DeviceData): Long? = takeOf(device.deviceId)

    /** 309 (FR-309-11) — the same, by device id (the admin's device row: *holds 8 Mbps*). */
    fun takeOf(deviceId: String): Long? {
        val q = qoeMeasurementOf(deviceId)
        return canTake(runCatching { streamRecords?.get(deviceId) }.getOrNull(), nowMs() / 1000, q?.first, q?.second)
    }

    /** 309 (FR-309-3) — true when [device] should measure its path again (nothing fresh within 24 h). */
    fun probeNeeded(device: DeviceData): Boolean = probeNeeded(recordOf(device), nowMs() / 1000, qoeMeasurementOf(device)?.second)

    /**
     * 309 (FR-309-3) — the client timed [bytes] from `GET /api/tv/probe` in [ms]: that is a measurement of its path (the
     * same Caddy route the segments take). Too small or too fast to mean anything (< 1 MB or < 50 ms) is ignored.
     */
    suspend fun recordProbe(device: DeviceData, bytes: Long, ms: Long): Long? {
        val store = streamRecords ?: return null
        if (bytes < 1_000_000L || ms < 50L) return null
        val bps = bytes * 8_000L / ms
        val now = nowMs() / 1000
        val rec = (store.get(device.deviceId) ?: StreamRecord()).copy(measuredBps = bps, measuredAt = now)
        store.put(device.deviceId, rec, now)
        Logger.info("probe: device=${device.deviceId} ${bytes / 1000} kB in $ms ms → ${bps / 1000}k (309)", "tv")
        return bps
    }

    /** 309 (FR-309-1) — the hold between two QoE posts of one play (in memory: a restart only delays a proof). */
    private val holds = kotlin.concurrent.AtomicReference<Map<String, HoldState>>(emptyMap())

    /** 313 (FR-313-13) — who served each (device, item)'s current play: `ours`, `jellyfin` or `direct`. */
    private val servedBy = kotlin.concurrent.AtomicReference<Map<PlaybackKey, ServedNow>>(emptyMap())

    private fun noteServed(device: DeviceData, jellyfinId: String, how: String, detail: String? = null) {
        while (true) {
            val old = servedBy.value
            val next = (old + (PlaybackKey(device.deviceId, jellyfinId) to ServedNow(how, detail))).let { m -> if (m.size > 400) m.entries.drop(m.size - 400).associate { it.toPair() } else m }
            if (servedBy.compareAndSet(old, next)) return
        }
    }

    /** 313 (FR-313-13) — who serves [itemId] on [deviceId] now (null: unknown, e.g. after a restart). */
    fun servedByOf(deviceId: String, itemId: String): String? = servedBy.value[PlaybackKey(deviceId, itemId)]?.how

    /** 313 (FR-313-13) — who serves it and, for ours, *4 qualities · HEVC HDR* (the admin's *Playing now*). */
    fun servedOf(deviceId: String, itemId: String): ServedNow? = servedBy.value[PlaybackKey(deviceId, itemId)]

    /** 309 (FR-309-1) — a direct play's stream is its file: the video bitrate our scan measured, plus a little audio. */
    private suspend fun fileStreamBpsOf(jellyfinId: String): Long? =
        localFileOf(jellyfinId)?.tracks?.firstOrNull { it.kind == dev.jellystructure.model.TrackKind.VIDEO }?.videoBitrate?.toLong()
            ?.takeIf { it > 0 }?.let { (it * 1.05).toLong() + 256_000L }

    /** 309 (FR-309-1) — [report] folded into its device's record (only an R381 per-item report is read). */
    private suspend fun foldIntoRecord(device: DeviceData, report: PlaybackQoeReport) {
        val store = streamRecords ?: return
        if (!report.perItem) return
        val variant = report.variantBandwidthBps?.takeIf { it > 0 } ?: if (report.directPlay) fileStreamBpsOf(report.itemId) else null
        val holdKey = "${device.deviceId}|${report.itemId}"
        val now = nowMs() / 1000
        val before = store.get(device.deviceId) ?: StreamRecord()
        val (after, hold) = foldRecord(before, holds.value[holdKey], RecordSample(variant, report.stalls, true), now)
        while (true) {
            val old = holds.value
            val next = (if (hold != null) old + (holdKey to hold) else old - holdKey).let { m -> if (m.size > 200) m.entries.drop(m.size - 200).associate { it.toPair() } else m }
            if (holds.compareAndSet(old, next)) break
        }
        if (after != before) {
            store.put(device.deviceId, after, now)
            if (after.stalledAt != before.stalledAt && after.stalledBps != null)
                Logger.info("record: device=${device.deviceId} stalled on ${after.stalledBps / 1000}k — holds ×0.8 for 24 h (309)", "tv")
            if (after.provenBps != before.provenBps && after.provenBps != null)
                Logger.info("record: device=${device.deviceId} proved ${after.provenBps / 1000}k (309)", "tv")
        }
    }

    /** 309 (FR-309-11) — the admin's device line: what a device holds and when it last stalled (null: no record). */
    fun recordSummary(deviceId: String): StreamRecord? = runCatching { streamRecords?.get(deviceId) }.getOrNull()

    /** 308 (FR-308-5) — the variant each (device, item) last reported playing, for the admin's *Playing now*. */
    private val variantsNow = kotlin.concurrent.AtomicReference<Map<PlaybackKey, VariantNow>>(emptyMap())

    /** 308 (FR-308-5) — the variant [deviceId] last reported for [itemId] (null: one stream, or nothing reported). */
    fun variantOf(deviceId: String, itemId: String): VariantNow? = variantsNow.value[PlaybackKey(deviceId, itemId)]

    /**
     * R291 (FR-R291-2) — for a player that switches HLS audio renditions in place (it said so), a
     * transcode's ticket points at the composed master instead of Jellyfin's own: every audio track, the
     * carried one muxed. Untouched for direct play (the container already has every track), for one audio
     * track, for a player that did not ask, and for a start the viewer has already abandoned (FR-180-3).
     */
    private suspend fun withRenditions(
        ticket: StreamTicket, capabilities: ClientCapabilities?, jellyfinBase: String, jellyfinId: String,
        token: String, identity: JellyfinDeviceIdentity, jellyfinPlaySessionId: String?, abandoned: Boolean,
        // 308 — the source's own video bitrate (from PlaybackInfo) and this device's measured throughput.
        sourceVideoBps: Long? = null,
        measuredBps: Long? = null,
        sourceVideoCodec: String? = null,
        sourceVideoRange: String? = null,
        /** Phase 313 — the device (`cast` gets MPEG-TS segments until fMP4 is verified on the receiver). */
        device: DeviceData? = null,
        /** 309 (FR-309-1) — what the device can take (null: no record). */
        take: Long? = null,
        /** 313d — the picked image subtitle's place among the file's subtitle streams (null: none/unmapped). */
        burnSubtitleOrder: Int? = null,
        burnRequested: Boolean = false,
    ): StreamTicket {
        val deviceKind = device?.kind ?: "tv"
        val measured = ticket.copy(measuredBandwidthBps = measuredBps,
            encoder = if (ticket.directPlay) "direct" else "jellyfin")
        device?.let { noteServed(it, jellyfinId, measured.encoder ?: "jellyfin") }
        if (ticket.directPlay || capabilities == null || jellyfinPlaySessionId == null || abandoned) return measured
        val master = ticket.hlsUrl ?: return measured
        // Phase 313 (FR-313-10/-12) — our own encoder serves the transcode when it can: one job, every rung, no
        // Jellyfin transcode job (Jellyfin's URL is never handed out, so it never starts one). Otherwise, Jellyfin's.
        if (reencodesVideo(master, sourceVideoCodec, sourceVideoRange)) {
            val file = localFileOf(jellyfinId)
            val (plan, why) = if (file == null) null to "file not on this server's disk"
            else encoder.planFor(capabilities, deviceKind, file.first, file.second, file.tracks, ticket.audio, ticket.audioStreamIndex,
                takeBps = take, noRecord = take == null, sourceVideoRange = sourceVideoRange,
                burnSubtitleOrder = burnSubtitleOrder, burnRequested = burnRequested || (ticket.burnedSubtitleIndex != null && burnSubtitleOrder == null),
                subtitles = encoderSubtitlesOf(ticket, jellyfinBase, jellyfinId, token), platform = device?.platform)
            if (plan != null) {
                val startSegment = (ticket.startPositionMs / ENCODER_SEGMENT_MS).toInt()
                // 309 (FR-309-6, owner) — the early encode on the detail page becomes this play's own job when it matches.
                val adopted = device?.let { encoder.adoptPrewarm(it.deviceId, jellyfinId, plan, startSegment, jellyfinPlaySessionId, ticket.expiresAt) }
                val id = adopted ?: encoder.register(plan, jellyfinPlaySessionId, ticket.expiresAt, device?.deviceId ?: "", jellyfinId)
                val audioPeak = plan.audio.maxOfOrNull { audioBitrate(it.codec, it.channels) } ?: 0L
                val startStream = rungBandwidth(plan.rungs[plan.startRung], audioPeak)
                Logger.info("playback: item=$jellyfinId encoder=ours ${plan.codec}${if (plan.keepsHdr) " HDR" else ""} " +
                    "rungs=${plan.rungs.joinToString("/") { "${it.height}p@${it.videoBps / 1000}k" }} start=${plan.startRung} " +
                    "audio=${plan.audio.size} subs=${plan.subtitles.size}${if (plan.burnSubtitleOrder != null) " burn-in" else ""} ${plan.mux} card=${plan.cudaDevice}" +
                    "${if (adopted != null) " prewarmed" else ""} (313/309)", "tv")
                device?.let { noteServed(it, jellyfinId, "ours", encoderWords(plan)) }
                return measured.copy(hlsUrl = "/api/tv/stream/$id/master.m3u8",
                    audioRenditions = capabilities.hlsAudioRenditions && plan.audio.size >= 2, adaptive = plan.rungs.size > 1,
                    encoder = "ours", startVariantBps = startStream.takeIf { plan.rungs.size > 1 },
                    // 309 (FR-309-2) — the player's estimate seeded so its own rule (× 0.7) lands on the start rung.
                    measuredBandwidthBps = if (plan.rungs.size > 1) (startStream / THROUGHPUT_HEADROOM).toLong() else measuredBps)
            }
            if (configStore.current.encoder.enabled) {
                encoder.recordFallback(why)
                Logger.info("encoder: fallback to Jellyfin — $why item=$jellyfinId (313)", "tv")
            }
        }
        // 308 (FR-308-1) — a ladder for a player that adapts, on a transcode that re-encodes the picture.
        val ceiling = decodeCeiling(capabilities, master)
        // 309 (FR-309-2) — Jellyfin's own ladder starts where the record says too: what the device can take, else the
        // 4 Mbps rung (never the top first).
        val startBudget = take ?: NO_RECORD_START_STREAM_BPS
        val ladder = if (capabilities.hlsAdaptive && reencodesVideo(master, sourceVideoCodec, sourceVideoRange)) {
            topVideoBps(queryParam(master, "VideoBitrate")?.toLongOrNull(), sourceVideoBps, ceiling)
                ?.let { AudioRenditions.LadderPlan(master, jellyfinPlaySessionId, it, ceiling, startBudget) }
        } else null
        // 308 — when a transcode gets no ladder, say why (found 2026-10-06: a Chromecast's cast streamed at the
        // source's 80.9 Mbps with no ladder and nothing in the log to tell which condition said no).
        if (ladder == null) Logger.info("playback: item=$jellyfinId no ladder: adaptive=${capabilities.hlsAdaptive} " +
            "reencodes=${reencodesVideo(master, sourceVideoCodec, sourceVideoRange)} reasons=${queryParam(master, "TranscodeReasons")} codec=${queryParam(master, "VideoCodec")}/$sourceVideoCodec range=$sourceVideoRange " +
            "negotiated=${queryParam(master, "VideoBitrate")} source=$sourceVideoBps ceiling=$ceiling " +
            "url=${master.substringAfter('?').split('&').filterNot { it.substringBefore('=').lowercase() in LOG_REDACTED_QUERY_KEYS }.joinToString("&")} (308)", "tv")
        // The renditions are made from the file on THIS server's disk (see AudioRenditions): none without it.
        val file = if (capabilities.hlsAudioRenditions) localFileOf(jellyfinId) else null
        if (file == null && ladder == null) return measured
        val id = audioRenditions.register(
            jellyfinPlaySessionId = jellyfinPlaySessionId, jellyfinMasterUrl = master,
            audio = ticket.audio, carriedIndex = ticket.audioStreamIndex, expiresAt = ticket.expiresAt,
            filePath = file?.first ?: "", durationMs = file?.second ?: 0L,
            withRenditions = file != null, ladder = ladder, fileTracks = file?.tracks,
        ) ?: return measured
        val renditions = file != null && ticket.audio.size >= 2 && ticket.audioStreamIndex != null && (file.second ?: 0L) > 0L &&
            ticket.audio.any { it.index == ticket.audioStreamIndex }
        if (renditions) Logger.info("playback: item=$jellyfinId audio renditions=${ticket.audio.size} (R291)", "tv")
        if (ladder != null) Logger.info(
            "playback: item=$jellyfinId ladder top=${ladder.topBps / 1000}k ceiling=${ceiling?.let { "${it / 1000}k" } ?: "-"} " +
                "measured=${measuredBps?.let { "${it / 1000}k" } ?: "none"} (308)", "tv",
        )
        return measured.copy(hlsUrl = "/api/tv/stream/$id/master.m3u8", audioRenditions = renditions, adaptive = ladder != null,
            startVariantBps = if (ladder != null) startBudget else null,
            measuredBandwidthBps = if (ladder != null) (startBudget / THROUGHPUT_HEADROOM).toLong() else measuredBps)
    }

    /**
     * 313d (FR-313-6) — the text subtitles a player that reads them from the manifest gets as WebVTT renditions: the
     * ticket's `hls` subtitles in their order, each from Jellyfin's own VTT conversion (fetched server-side once; the
     * tokened URL never reaches the player).
     */
    private fun encoderSubtitlesOf(ticket: StreamTicket, jellyfinBase: String, jellyfinId: String, token: String): List<EncoderSubtitle> =
        ticket.subtitles.filter { it.deliveryMethod == "hls" }.map { t ->
            EncoderSubtitle(
                name = t.label ?: t.language ?: "Subtitles", language = t.language, forced = t.forced, default = t.isDefault,
                sourceUrl = withJellyfinToken("$jellyfinBase/Videos/$jellyfinId/$jellyfinId/Subtitles/${t.index}/0/Stream.vtt", token),
            )
        }

    /** 308 — the decode ceiling (bits/s) for the codec [transcodingUrl] encodes to, or null when the device gave none. */
    private fun decodeCeiling(c: ClientCapabilities, transcodingUrl: String): Long? {
        val codec = queryParam(transcodingUrl, "VideoCodec")?.substringBefore(',')?.lowercase()
        val perCodec = if (codec == "hevc" || codec == "h265") c.maxHevcBitrate else c.maxH264Bitrate
        return listOf(perCodec, c.maxVideoBitrate).filter { it > 0 }.minOrNull()?.toLong()
    }

    /** R291 — the file on this server's disk behind a Jellyfin id (a movie, or one series' episode), and its
     *  length; the same top-level-or-episode lookup [requireVisible] does. */
    /** The file on this server's disk for [jellyfinId]: its path, length and our scan's tracks (R382 maps by them). */
    private data class LocalFile(val first: String, val second: Long?, val tracks: List<dev.jellystructure.model.Track>)

    private suspend fun localFileOf(jellyfinId: String): LocalFile? {
        mediaStore.resolveByJellyfinId(jellyfinId)?.takeIf { it.episodes.isEmpty() }?.let { return LocalFile(it.path, it.tracks.fileDurationMs(), it.tracks) }
        val ep = mediaStore.allItems().firstNotNullOfOrNull { series -> series.episodes.firstOrNull { it.jellyfinId == jellyfinId } } ?: return null
        return LocalFile(ep.path, ep.tracks.fileDurationMs(), ep.tracks)
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
    /** R368 — the playback sessions above the tracker (null in tests that never look at sessions). */
    var sessions: PlaybackSessions? = null

    /**
     * R368 (FR-R368-4, dev review item 12 b/c) — after a restart: every session that wasn't ended comes back
     * *reconnecting*, and the tracker entry and R343's plan of its current item are rebuilt from the row — with
     * Jellyfin's own play-session id, so phase 180 can still release a transcode started before the restart, and a
     * shuffle or a Start over still reports the right resume point at its stop.
     */
    suspend fun restoreSessions(deviceOf: (deviceId: String, userId: String) -> DeviceData?) {
        val restored = sessions?.restore() ?: return
        for (s in restored) {
            val device = deviceOf(s.targetId, s.ownerUserId) ?: continue
            if (s.itemId.isBlank()) continue
            playbackTracker.started(device, s.itemId, s.positionMs, s.jellyfinPlaySessionId, directPlay = s.options.directPlay)
            s.options.plan()?.let { plan -> plansMutex.withLock { plans[PlaybackKey(device.deviceId, s.itemId)] = plan } }
        }
    }

    var onStopLanded: (suspend (DeviceData, String) -> Unit)? = null  // Phase 230 — (device, stopped Jellyfin id)

    /** Phase 219 — what one queued write does: the same token + identity + ids the inline path used. */
    private inner class JellyfinSink : PlaybackSink {
        override suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean {
            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = jellyfinClient.tvToken(jellyfinBase, w.device, configStore.current.apiKeys.jellyfinToken)
            return jellyfinClient.postPlaybackProgress(
                jellyfinBase, token, w.jellyfinId, w.positionMs * TICKS_PER_MS, w.isPaused, w.jellyfinId,
                JellyfinDeviceIdentity.forDevice(w.device), playSessionIdFor(w.device, w.jellyfinId),
                volumePercent = w.volumePercent, muted = w.muted,   // R357 (FR-R357-2)
            )
        }

        /** Phase 310 (FR-310-2) — the stop itself and nothing else; what it starts is [afterStop], off the writer's line. */
        override suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean {
            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = jellyfinClient.tvToken(jellyfinBase, w.device, configStore.current.apiKeys.jellyfinToken)
            return jellyfinClient.postPlaybackStopped(
                jellyfinBase, token, w.jellyfinId, w.positionMs * TICKS_PER_MS, w.jellyfinId,
                JellyfinDeviceIdentity.forDevice(w.device), playSessionIdFor(w.device, w.jellyfinId),
            )
        }

        /**
         * Phase 310 (FR-310-2) — what a landed stop starts, in its order, under the writer's follow-up deadline:
         * phase 180's encode release; the one user-data write (dev review item 7, folding R347's tick, R343's unwatched
         * write-back and R375's date restore) and phase 312's read-back of it; then the refresh the page needs.
         */
        override suspend fun afterStop(w: PlaybackWriter.PendingWrite) {
            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = jellyfinClient.tvToken(jellyfinBase, w.device, configStore.current.apiKeys.jellyfinToken)
            val identity = JellyfinDeviceIdentity.forDevice(w.device)
            // Phase 180 — release the encode once the stop has landed (idempotent; a release for a
            // session that never transcoded or already ended is a success, not an error).
            if (w.jellyfinPlaySessionId != null) releaseEncodes(jellyfinBase, token, identity, w.jellyfinPlaySessionId)
            val written = w.userData?.let { writeStopUserData(jellyfinBase, token, w.device, w.jellyfinId, it, "stop") } ?: false
            // R343 (FR-R343-11) — a cleared Start over: exactly what the played route refreshes (the clear's own hook),
            // in place of the stop's ordinary refresh below.
            if (w.startOverUnplayed) {
                if (written) patchUnwatched(w.device, w.jellyfinId, w.userData!!.positionMs, factsOf(w.jellyfinId)?.durationMs ?: 0L)
                afterStartOverWriteBack(w.device, w.jellyfinId, written)
            } else {
                // R248 — Jellyfin has the stop: now (and only now) the Home feed can be rebuilt to show it.
                onStopLanded?.let { hook -> runCatching { hook(w.device, w.jellyfinId) }.onFailure { Logger.warn("Home refresh after stop failed for ${w.jellyfinId}: ${it.message}", "tv") } }
            }
            // Phase 312 (FR-312-3) — read it back once Jellyfin has settled: a stray stop landing just after ours (a stop
            // at 0 resets the place) must not have the last word. Skipped when the item is playing again anywhere.
            val expected = w.userData ?: return
            kotlinx.coroutines.delay(READ_BACK_DELAY_MS)
            if (playbackTracker.tracked().any { it.jellyfinId == w.jellyfinId }) return
            val now = jellyfinClient.getItemDetail(jellyfinBase, token, w.device.jellyfinUserId, w.jellyfinId)?.userData ?: return
            val placeNow = now.playbackPositionTicks / TICKS_PER_MS
            val drifted = (expected.played != null && now.played != expected.played) || kotlin.math.abs(placeNow - expected.positionMs) > READ_BACK_TOLERANCE_MS
            if (!drifted) return
            Logger.warn("playback stop: item=${w.jellyfinId} read back played=${now.played} at ${placeNow}ms, expected played=${expected.played} at ${expected.positionMs}ms — writing it again (312)", "tv")
            if (writeStopUserData(jellyfinBase, token, w.device, w.jellyfinId, expected, "read-back")) onStopLanded?.let { hook -> runCatching { hook(w.device, w.jellyfinId) } }
        }
    }

    /** Phase 310 (dev review item 7) — the one user-data write after a stop. A failure only logs. */
    private suspend fun writeStopUserData(jellyfinBase: String, token: String, device: DeviceData, jellyfinId: String, u: StopUserData, why: String): Boolean =
        runCatching { jellyfinClient.setUserData(jellyfinBase, token, device.jellyfinUserId, jellyfinId, played = u.played, positionTicks = u.positionMs * TICKS_PER_MS, lastPlayedDate = u.lastPlayedDate) }
            .onSuccess { Logger.info("playback stop: item=$jellyfinId user data played=${u.played ?: "kept"} at ${u.positionMs}ms${u.lastPlayedDate?.let { " last played $it" } ?: ""} ($why, 310)", "tv") }
            .onFailure { Logger.warn("playback stop: user-data write failed for item=$jellyfinId ($why): ${it.message}", "tv") }
            .isSuccess

    /** Phase 310 (FR-310-7) / 312 (FR-312-5) — the candidates file next to the database (Main sets it); null: no repair. */
    var repairCandidatesFile: String? = null
    private val repairMutex = Mutex()
    private var repairCache: Pair<Long, List<RepairAction>>? = null

    /** The dry run: what *Put them back* would change, computed against Jellyfin's state now (cached 5 min). */
    suspend fun repairPlan(): List<RepairAction> = repairMutex.withLock {
        repairCache?.takeIf { nowMs() - it.first < 5 * 60_000L }?.let { return@withLock it.second }
        val path = repairCandidatesFile ?: return@withLock emptyList()
        val text = runCatching { dev.jellystructure.io.FileIo.readText(kotlinx.io.files.Path(path)) }.getOrNull() ?: return@withLock emptyList()
        val candidates = runCatching { repairJson.decodeFromString<List<RepairCandidate>>(text) }
            .onFailure { Logger.warn("playback repair: could not read $path: ${it.message}", "tv") }.getOrNull() ?: return@withLock emptyList()
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = configStore.current.apiKeys.jellyfinToken
        val plan = planRepair(candidates) { c ->
            val facts = factsOf(c.jellyfinId) ?: return@planRepair null
            val detail = jellyfinClient.getItemDetail(base, token, c.userId, c.jellyfinId) ?: return@planRepair null
            val u = detail.userData
            RepairItemNow(detail.name.ifBlank { c.jellyfinId }, facts.durationMs, facts.creditsStartMs, u?.played == true,
                (u?.playbackPositionTicks ?: 0L) / TICKS_PER_MS, u?.lastPlayedDate)
        }
        repairCache = nowMs() to plan
        plan
    }

    /** The owner's *Put them back*: writes each planned place, then retires the candidates file. Returns how many landed. */
    suspend fun applyRepair(): Int {
        val plan = repairMutex.withLock { repairCache = null }.let { repairPlan() }
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = configStore.current.apiKeys.jellyfinToken
        var ok = 0
        for (a in plan) {
            val landed = runCatching { jellyfinClient.setUserData(base, token, a.userId, a.jellyfinId, played = false, positionTicks = a.positionMs * TICKS_PER_MS, lastPlayedDate = a.lastPlayed) }
                .onFailure { Logger.warn("playback repair: ${a.jellyfinId} failed: ${it.message}", "tv") }.isSuccess
            if (landed) { ok++; Logger.info("playback repair: item=${a.jellyfinId} put back at ${a.positionMs}ms, unwatched (${a.source})", "tv") }
        }
        repairCandidatesFile?.let { p -> runCatching { platform.posix.rename(p, "$p.applied") } }
        repairMutex.withLock { repairCache = null }
        Logger.info("playback repair: $ok of ${plan.size} places put back (310/312)", "tv")
        return ok
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
        // R343 (FR-R343-4/5) — this start is a finished series' Start over / one entry of a shuffle.
        startOver: Boolean = false,
        shuffle: Boolean = false,
    ): StreamTicket {
        requireVisible(device, jellyfinId)
        rememberCapabilities(device, capabilities)   // 309 (FR-309-6) — a phone can prewarm for this device later
        // Phase 218 (FR-218-8) — a receiver past `max_sessions` gets phase 182's 503 + Retry-After
        // (CastCeilingException → Server.kt StatusPages), never a spinner forever. A TV is never gated.
        castService?.checkCeiling(device, playbackTracker.activeDeviceObjects(), playbackTracker.activeDirectDeviceIds())
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
        // R343 (FR-R343-8) — a screen's own start for the id a carried shuffle / Start over named counts as one.
        val screenCtx = if (!startOver && !shuffle) screenShuffles.forStart(device.deviceId, jellyfinId) else null
        val asShuffle = shuffle || screenCtx?.shuffled == true
        val asStartOver = startOver || screenCtx?.startOver == true
        // R343 — a shuffled entry and Start over start at 0:00 on any client (a return from the background still
        // carries its own position).
        val startPositionTicks = resolveStartPositionTicks(startPositionMsOverride ?: (if (asShuffle || asStartOver) 0L else null), saved)
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
        // Phase 314 (FR-314-5) — a track jellystructure added is the same audio as its source: a device that can't decode
        // the stream that would play gets the added copy of the same language (a cast becomes a copy, not an audio
        // encode). Nothing changes for a file without one, nor for a device that decodes the original.
        val copyIndex = dev.jellystructure.filefix.copyForDevice(
            itemDetail?.mediaStreams.orEmpty().filter { it.type.equals("Audio", ignoreCase = true) }
                .map { dev.jellystructure.filefix.JellyfinAudio(it.index, it.codec, it.language, it.title, it.isDefault) },
            wantedAudioIndex, capabilities.audioCodecs,
        )?.also { Logger.info("playback start: device=${device.deviceId} item=$jellyfinId audio → stream $it, the added copy this device plays (314)", "tv") }
        // 308 (FR-308-4) — direct play only when the file fits what this device has measured its path to carry: the
        // measured budget joins Phase 177's MaxStreamingBitrate, so a file above it is transcoded (and gets the ladder).
        val measuredBps = measuredThroughputOf(device)
        // 309 (FR-309-1/-8, owner decisions 3/4) — what this device has shown it can take caps the negotiation: a file
        // above it is transcoded (and gets the ladder; an audio-only transcode's picture is re-encoded), one under it
        // direct-plays. No record: a player that climbs takes files up to 8 Mbps directly; one that can't is not capped.
        val take = takeOf(device)
        val cap = negotiationCap(capabilities.hlsAdaptive, take)
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities, identity = identity, audioStreamIndex = copyIndex ?: wantedAudioIndex, throughputCapBps = cap)
        val source = playbackInfo?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        Logger.info("playback start: device=${device.deviceId} item=$jellyfinId takes ${take?.let { "${it / 1000}k" } ?: "no record"}" +
            "${measuredBps?.let { " (measured ${it / 1000}k)" } ?: ""} → cap ${cap?.let { "${it / 1000}k" } ?: "none"} (309)", "tv")
        // Phase 180 (FR-180-2) — Jellyfin's OWN play-session id, distinct from playSessionIdFor()'s
        // bookkeeping id below; this is the one stopActiveEncoding needs. Null when PlaybackInfo itself
        // was unavailable (source == null, plain direct-play-URL fallback) — nothing to ever release.
        val jellyfinPlaySessionId = playbackInfo?.playSessionId
        Logger.info(
            "PlaybackInfo: item=$jellyfinId directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode" +
                (channelLimit(capabilities)?.let { " maxAudioChannels=$it" } ?: ""),
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
        val streamUrl = streamUrlFor(jellyfinBase, jellyfinId, token, identity, source?.transcodingUrl?.takeIf { needsTranscode }, capabilities)

        // R343 / R347 — what the stop and the 5 % trigger need about this item, kept for the session. The
        // position before a shuffle is Jellyfin's as it stood (a Played item has no resume point: 0, R306).
        val facts = factsOf(jellyfinId)
        val plan = SessionPlan(
            startOverSeriesId = facts?.seriesJellyfinId?.takeIf { asStartOver },
            durationMs = facts?.durationMs?.takeIf { it > 0 } ?: ((itemDetail?.runTimeTicks ?: 0L) / TICKS_PER_MS),
            creditsStartMs = facts?.creditsStartMs,
            shuffle = asShuffle,
            priorPositionMs = if (saved?.played == true) 0L else (saved?.playbackPositionTicks ?: 0L) / TICKS_PER_MS,
            // R375 (FR-R375-6) — an episode's date and watched flag before this play, and a shuffle's series anchor.
            priorLastPlayed = saved?.lastPlayedDate?.takeIf { facts?.seriesJellyfinId != null },
            watchedAtStart = saved?.played == true && facts?.seriesJellyfinId != null,
            playedAtStart = saved?.played,   // 310 (dev review item 7) — null when Jellyfin's user data was unavailable
            anchorLastPlayed = facts?.seriesJellyfinId?.takeIf { asShuffle }?.let { sid -> anchorDateFor?.invoke(device.jellyfinUserId, sid) },
        )
        val key = PlaybackKey(device.deviceId, jellyfinId)
        plansMutex.withLock { plans[key] = plan; clearLatched.remove(key); clearJobs.remove(key) }
        if (asStartOver || asShuffle) Logger.info("playback start: device=${device.deviceId} item=$jellyfinId startOver=$asStartOver shuffle=$asShuffle prior=${plan.priorPositionMs}ms (R343)", "tv")

        val startResult = playbackTracker.started(device, jellyfinId, startPositionMs, jellyfinPlaySessionId)

        // Phase 180 (FR-180-1) — a still-active earlier session for this exact (device, item) key is
        // being replaced without an intervening stop; release it too, or its encode leaks forever (this
        // is the "new session superseding an older one" convergence path). NonCancellable: this request's
        // own connection is alive and its response doesn't depend on this, but it must still complete
        // even if something upstream tears the request coroutine down before we return.
        startResult.superseded?.let { old ->
            withContext(NonCancellable) {
                // Phase 312 — the same item keeps playing here: free the old encode, send Jellyfin no stop.
                if (old.jellyfinPlaySessionId != jellyfinPlaySessionId) releaseEncodesOnly(device, jellyfinId, old.jellyfinPlaySessionId, "supersede")
            }
        }

        // R368 (FR-R368-2) — the session: joins the one on this (device, lane) or starts one; its id rides the ticket.
        val sessionId = if (startResult.stopAlreadyArrived) null else runCatching {
            sessions?.onStart(device, jellyfinId, startPositionMs, jellyfinPlaySessionId, plan = plan, directPlay = !needsTranscode)
        }.onFailure { Logger.warn("Playback sessions: start failed: ${it.message}", "tv") }.getOrNull()

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
                releaseAbandoned(device, jellyfinId, jellyfinPlaySessionId, startResult.stoppedAtMs, "abandoned")
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
            sessionId = sessionId,   // R368 (dev review item 8)
        ), capabilities, jellyfinBase, jellyfinId, token, identity, jellyfinPlaySessionId, abandoned = startResult.stopAlreadyArrived, device = device,
            sourceVideoBps = source?.videoBitrate(), measuredBps = measuredBps, take = take,
            sourceVideoCodec = source?.mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }?.codec,
            sourceVideoRange = source?.mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }?.videoRangeType)
    }

    /**
     * R381 (FR-R381-7) — the next item's stream, prepared ahead of time (the player prefetches its first seconds at the
     * credits) with **none** of a start's side effects: nothing is reported to Jellyfin (no `/Sessions/Playing`, so its
     * *now playing* never switches while the current item still plays, and no stop can follow from it), no R368 session
     * moves, no tracker or heartbeat, no plan, no encode, no composed master. The real start, with all of them, is the
     * ordinary [startPlayback] when the item actually begins.
     *
     * The same gate as a start ([requireVisible]: library ACL and kids rules), the same negotiation (PlaybackInfo with the
     * device's capabilities and 308's measured budget), so a direct play here is a direct play at the start. A transcode
     * is answered `directPlay = false`, no URL: a transcoded next item is not preloaded until 309's early encode or 313's
     * encoder exists (owner, 2026-10-08).
     */
    suspend fun preparePlayback(device: DeviceData, jellyfinId: String, capabilities: ClientCapabilities): dev.jellystructure.shared.tv.PreparedStream {
        requireVisible(device, jellyfinId)
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
        val startPositionMs = resolveStartPositionTicks(null, itemDetail?.userData) / TICKS_PER_MS
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities,
            identity = identity, throughputCapBps = negotiationCap(capabilities.hlsAdaptive, takeOf(device)))   // 309 — the start's own cap
        val source = playbackInfo?.mediaSources?.firstOrNull()
        val direct = source != null && (source.supportsDirectPlay || source.transcodingUrl == null)
        Logger.info("playback prepare: device=${device.deviceId} item=$jellyfinId directPlay=$direct from ${startPositionMs}ms (R381, nothing reported)", "tv")
        return dev.jellystructure.shared.tv.PreparedStream(
            itemId = jellyfinId,
            directPlay = direct,
            url = if (direct) streamUrlFor(jellyfinBase, jellyfinId, token, identity, null, capabilities) else null,
            startPositionMs = startPositionMs,
            expiresAt = nowMs() + PREPARED_TTL_MS,
        )
    }

    /** 309 (FR-309-6) — each device's capabilities from its last start, so a phone can prewarm for its Cast receiver. */
    private val lastCapabilities = kotlin.concurrent.AtomicReference<Map<String, ClientCapabilities>>(emptyMap())

    private fun rememberCapabilities(device: DeviceData, caps: ClientCapabilities) {
        while (true) {
            val old = lastCapabilities.value
            val next = (old + (device.deviceId to caps)).let { m -> if (m.size > 100) m.entries.drop(m.size - 100).associate { it.toPair() } else m }
            if (lastCapabilities.compareAndSet(old, next)) return
        }
    }

    /**
     * 309 (FR-309-6, owner 2026-10-07/08) — the viewer has been on [jellyfinId]'s detail page for more than 2 s: start
     * the encode Play would start, so a transcode's first frame is there at once. Only our own encoder warms (one job,
     * every rung, adopted by Play when it matches; Jellyfin's per-rung jobs never do). Nothing is reported to Jellyfin
     * (no `/Sessions/Playing`, no transcode job), no R368 session, no tracker, no R291 rendition job. A direct play has
     * nothing to warm. With [castDeviceId] the encode is made for that Cast device's receiver (its own capabilities,
     * its own record), the one that will play it.
     */
    suspend fun prewarmPlayback(
        device: DeviceData, jellyfinId: String, capabilities: ClientCapabilities,
        audioLanguage: String? = null, audioVariant: String? = null, castDeviceId: String? = null,
    ): dev.jellystructure.shared.tv.PrewarmResult {
        fun none(why: String) = dev.jellystructure.shared.tv.PrewarmResult("none", why)
        requireVisible(device, jellyfinId)
        if (!configStore.current.encoder.enabled) return none("encoder off")
        // The device that will play: this one, or the receiver the phone casts to.
        val target = if (castDeviceId == null) device else {
            val receiverId = sessions?.receiversByCastDevice()?.get(castDeviceId) ?: return none("no receiver known for that Cast device yet")
            raviloDeviceService.listSessions(receiverId).firstOrNull { it.jellyfinUserId == device.jellyfinUserId } ?: return none("receiver not signed in")
        }
        val caps = if (castDeviceId == null) capabilities else lastCapabilities.value[target.deviceId] ?: return none("receiver capabilities not known yet")
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, target) ?: return none("sign-in expired")
        val identity = JellyfinDeviceIdentity.forDevice(target)
        val itemDetail = jellyfinClient.getItemDetail(jellyfinBase, token, target.jellyfinUserId, jellyfinId)
        val startPositionMs = resolveStartPositionTicks(null, itemDetail?.userData) / TICKS_PER_MS
        val audio = buildAudioTracks(itemDetail)
        val wantedAudioIndex = audioLanguage?.let { lang ->
            dev.jellystructure.shared.tv.resolveAudioChoice(lang, audioVariant, audio.map { dev.jellystructure.shared.tv.VersionInput(it.language, it.label, forced = false, isDefault = it.isDefault) })
                ?.let { audio[it].index }
        }
        val take = takeOf(target)
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, target.jellyfinUserId, jellyfinId, capabilities = caps, identity = identity,
            audioStreamIndex = wantedAudioIndex, throughputCapBps = negotiationCap(caps.hlsAdaptive, take))
        val source = playbackInfo?.mediaSources?.firstOrNull() ?: return none("no PlaybackInfo")
        if (source.supportsDirectPlay || source.transcodingUrl == null) return dev.jellystructure.shared.tv.PrewarmResult("direct")
        val master = source.transcodingUrl
        val video = source.mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
        if (!reencodesVideo(master, video?.codec, video?.videoRangeType)) return none("the picture is copied (no encode to warm)")
        val file = localFileOf(jellyfinId) ?: return none("file not on this server's disk")
        val subs = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token, embedContainerSubs = false, hlsSubtitles = caps.hlsOnly && caps.hlsSubtitles)
        val ticketLike = StreamTicket(jellyfinBaseUrl = jellyfinBase, accessToken = "", itemId = jellyfinId, container = "mkv", directPlay = false,
            hlsUrl = master, startPositionMs = startPositionMs, subtitles = subs, audio = audio, trickplayUrl = null, expiresAt = nowMs() + TICKET_TTL_MS)
        val (plan, why) = encoder.planFor(caps, target.kind, file.first, file.second, file.tracks, audio, carriedAudioIndex(master, wantedAudioIndex),
            takeBps = take, noRecord = take == null, sourceVideoRange = video?.videoRangeType,
            subtitles = encoderSubtitlesOf(ticketLike, jellyfinBase, jellyfinId, token), platform = target.platform)
        plan ?: return none(why)
        encoder.prewarm(plan, target.deviceId, jellyfinId, (startPositionMs / ENCODER_SEGMENT_MS).toInt(), nowMs() + PREWARM_TTL_MS)
        return dev.jellystructure.shared.tv.PrewarmResult("warm")
    }

    /** 309 — the viewer left the detail page without pressing Play: the warm encode stops at once (owner). */
    suspend fun cancelPrewarm(device: DeviceData, jellyfinId: String, castDeviceId: String? = null) {
        val targetId = if (castDeviceId == null) device.deviceId else sessions?.receiversByCastDevice()?.get(castDeviceId) ?: return
        val n = encoder.cancelPrewarm(targetId, jellyfinId)
        if (n > 0) Logger.info("encoder: prewarm cancelled device=$targetId item=$jellyfinId — the viewer left the page (309)", "tv")
    }

    /**
     * Phase 279 (FR-279-6) — one song. The same session machinery as a film (Jellyfin's Now Playing, progress, stop,
     * `PlayCount` / `LastPlayedDate`, phase 180's teardown), negotiated with [audioDeviceProfile]: direct play for what
     * the phone declared, else an HLS/AAC stream (WMA). The caller has already checked the viewer may see the song
     * (275's library rule — [requireVisible] only knows films). Not gated by 218's ceiling: that is cast-only by
     * construction (dev review 3). A song always starts at 0 unless the phone says where (Jellyfin keeps no music
     * position; a resumed queue carries its own).
     */
    suspend fun startMusicPlayback(device: DeviceData, trackId: String, capabilities: ClientCapabilities, startPositionMs: Long? = null,
                                   /** R368 (dev review item 4) — the book this part belongs to: the session's kind is `audiobook`. */
                                   bookId: String? = null): StreamTicket {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvTokenForClient(jellyfinBase, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — sign in again to continue listening.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val playSessionId = playSessionIdFor(device, trackId)
        val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)
        jellyfinClient.startPlaybackSession(jellyfinBase, token, trackId, startMs * TICKS_PER_MS, trackId, identity, playSessionId)
        val info = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, trackId, capabilities = capabilities, identity = identity, audio = true)
        val source = info?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        val jellyfinPlaySessionId = info?.playSessionId
        Logger.info("PlaybackInfo (music): item=$trackId directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode", "tv")
        // 286 (FR-286-8) — a speaker counts against the cast ceiling only while it converts (a WMA); a direct-played
        // MP3 is a file download and never does.
        if (needsTranscode) castService?.checkCeiling(device, playbackTracker.activeDeviceObjects(), playbackTracker.activeDirectDeviceIds())
        val url = if (needsTranscode) source?.transcodingUrl!!.let { withChannelLimit(if (it.startsWith("http")) it else "$jellyfinBase$it", capabilities) }
            else withJellyfinToken("$jellyfinBase/Audio/$trackId/stream?Static=true&MediaSourceId=$trackId&DeviceId=${identity.deviceId}", token)
        val startResult = playbackTracker.started(device, trackId, startMs, jellyfinPlaySessionId, directPlay = !needsTranscode)
        startResult.superseded?.let { old -> withContext(NonCancellable) {
            if (old.jellyfinPlaySessionId != jellyfinPlaySessionId) releaseEncodesOnly(device, trackId, old.jellyfinPlaySessionId, "music-supersede")
        } }
        if (startResult.stopAlreadyArrived) withContext(NonCancellable) {
            playbackTracker.stopped(device, trackId)
            releaseAbandoned(device, trackId, jellyfinPlaySessionId, startResult.stoppedAtMs, "music-abandoned")
        }
        // R368 (FR-R368-2) — one session per queue: a song boundary joins the session the last song left held.
        val sessionId = if (startResult.stopAlreadyArrived) null else runCatching {
            sessions?.onStart(device, trackId, startMs, jellyfinPlaySessionId, bookId = bookId, directPlay = !needsTranscode,
                kindHint = if (bookId != null) SessionKind.AUDIOBOOK else SessionKind.MUSIC)
        }.onFailure { Logger.warn("Playback sessions: start failed: ${it.message}", "tv") }.getOrNull()
        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = "",   // R271 — present and empty, never the token
            itemId = trackId,
            container = source?.container ?: "audio",
            directPlay = !needsTranscode,
            hlsUrl = url,
            startPositionMs = startMs,
            expiresAt = nowMs() + TICKET_TTL_MS,
            sessionId = sessionId,
        )
    }

    /** [volumePercent]/[muted] — R357 (FR-R357-2): the player's volume as its report carried it, forwarded to
     *  Jellyfin's session (`PlayState.VolumeLevel`/`IsMuted`); null leaves Jellyfin's body as it always was. */
    suspend fun reportProgress(device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean,
                               volumePercent: Int? = null, muted: Boolean? = null,
                               /** R368 (dev review item 8) — the ticket's session; absent ⇒ matched by (device, item). */
                               sessionId: String? = null) {
        // No requireVisible() here deliberately — this is a heartbeat for a session startPlayback
        // already gated; failing a heartbeat because a policy/library edit drifted mid-playback would
        // only strand a phantom "Now Playing" in Jellyfin, the exact bug class the watchdog above
        // exists to prevent. A straggling tick for an item that already reported a stop must not be
        // forwarded either — see PlaybackTracker.heartbeat.
        if (!playbackTracker.heartbeat(device, jellyfinId, positionMs)) {
            Logger.info("Ignoring progress for already-stopped playback item=$jellyfinId device=${device.deviceId}", "tv")
            return
        }
        // R368 (FR-R368-3) — the session stores the position; only a change is pushed (review item 9).
        runCatching { sessions?.onProgress(device, jellyfinId, positionMs, isPaused, sessionId, volumePercent?.coerceIn(0, 100), muted) }
            .onFailure { Logger.warn("Playback sessions: progress failed: ${it.message}", "tv") }
        // R343 (FR-R343-4) — a Start over past 5 %: clear the series in the background (never awaited here).
        maybeStartOverClear(device, jellyfinId, positionMs)
        StartOverHolds.move(device.jellyfinUserId, jellyfinId, positionMs)   // FR-R343-13 — only if a hold stands
        // Phase 219 (FR-219-2) — queued and retried by the writer; the route answers at once.
        val w = writer
        val volume = volumePercent?.coerceIn(0, 100)   // FR-R357-2 — clamped server-side
        if (w != null) { w.enqueueProgress(device, jellyfinId, positionMs, isPaused, volume, muted); return }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId,
            positionMs * TICKS_PER_MS, isPaused, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
            volumePercent = volume, muted = muted,
        )
    }

    suspend fun stopPlayback(device: DeviceData, jellyfinId: String, positionMs: Long, startupMs: Long? = null,
                             /** R368 (dev review item 8) — the ticket's session; absent ⇒ matched by (device, item). */
                             sessionId: String? = null,
                             /** Phase 312 (FR-312-1) — who asked for this stop: `user` (the app's own stop) or `watchdog`. */
                             reason: String = "user") {
        // No requireVisible() here deliberately — same reasoning as reportProgress above: this is
        // cleanup for a session startPlayback already gated, and it's also called from the stop
        // watchdog for stale/disconnected devices. Blocking it would risk leaving a phantom "Now
        // Playing" in Jellyfin forever, which is worse than the (already access-gated-at-start) cost
        // of letting an in-flight stop go through.
        val key = PlaybackKey(device.deviceId, jellyfinId)
        // R343 (FR-R343-4) — a stop past 5 % that no heartbeat reported yet still clears; and the stop's own
        // write always lands after a running clear (bounded), so the stop's position is the last word.
        maybeStartOverClear(device, jellyfinId, positionMs)
        val clear = plansMutex.withLock { clearJobs.remove(key) }
        if (clear != null) dev.jellystructure.ops.boundedOrNull(START_OVER_CLEAR_WAIT_MS, "Start over clear") { clear.join() }
            ?: run { if (clear.isActive) Logger.warn("Start over: clear still running after ${START_OVER_CLEAR_WAIT_MS}ms; stopping item=$jellyfinId anyway", "tv") }
        // R347 / R343 — what the stop reports: the playhead, the end of the file for an item finished at its
        // credits (and a tick through mark), or a shuffled entry's position from before the shuffle.
        var cleared = false
        val plan = plansMutex.withLock { plans.remove(key).also { cleared = clearLatched.remove(key) } } ?: factsOf(jellyfinId)?.let { SessionPlan(durationMs = it.durationMs, creditsStartMs = it.creditsStartMs) }
        val decision = resolveStop(positionMs, plan)
        val jellyfinPlaySessionId = playbackTracker.stopped(device, jellyfinId, positionMs)
        // R368 (review item 6) — the session is held 15 s for the next song, part or episode.
        runCatching { sessions?.onStop(device, jellyfinId, positionMs, sessionId) }
            .onFailure { Logger.warn("Playback sessions: stop failed: ${it.message}", "tv") }
        Logger.info("playback stop: device=${device.deviceId} item=$jellyfinId at ${positionMs}ms" +
            (if (decision.reportMs != positionMs) " (reported ${decision.reportMs}ms: ${if (decision.markPlayed) "finished at its credits, R347" else "shuffled, R343"})" else ""), "tv")   // R292 — the number the resume record carries
        // R343 — a cleared Start over episode that did not finish stays unwatched at its position (see the sink).
        val startOverUnplayed = cleared && !playbackFinished(decision.reportMs, plan?.durationMs ?: 0L, plan?.creditsStartMs)
        // R343 (FR-R343-11) — a page read the moment the player closes sees the episode as it is about to be, not the
        // watched flag Jellyfin's own stop is about to write back.
        if (startOverUnplayed) {
            patchUnwatched(device, jellyfinId, decision.reportMs, plan?.durationMs ?: 0L)
            plan?.startOverSeriesId?.let { StartOverHolds.hold(device.jellyfinUserId, it, jellyfinId, decision.reportMs, plan.durationMs) }
        } else {
            // FR-R343-13 — a Start over that finished its episode: Jellyfin's own word (watched) stands from now.
            StartOverHolds.release(device.jellyfinUserId, jellyfinId)
        }
        // R375 (FR-R375-6) — a shuffled play, or a replay that did not finish, puts the episode's date back.
        val restoreLastPlayed = if (startOverUnplayed) null
            else lastPlayedRestore(plan, playbackFinished(positionMs, plan?.durationMs ?: 0L, plan?.creditsStartMs))
        // Phase 310 (dev review item 7) — the ONE user-data write the landed stop is followed by (and phase 312 reads
        // back): it folds R347's tick (previously a separate `mark` that sent its own stop at 0 and raced this one),
        // R343's unwatched write-back and R375's date. None for a song or an id outside the library (no plan).
        val userData = plan?.let { stopUserData(positionMs, it, startOverUnplayed) }
        releaseSession(device, jellyfinId, decision.reportMs, jellyfinPlaySessionId, startOverUnplayed = startOverUnplayed, restoreLastPlayed = restoreLastPlayed,
            reason = reason, userData = userData)
        // Phase 185 (FR-185-4) — session genuinely completed (this IS the stop path, not a mid-session
        // heartbeat) and the client reported a real startup duration: record one sample. The watchdog's
        // own forced stop (stopWatchdogTick) never supplies startupMs, so a device that vanished
        // mid-session correctly contributes nothing here.
        if (startupMs != null) recordStartSample(device, jellyfinId, startupMs)
    }

    /** R347 / R343 — what a stop needs about one playable id: the file's length, its trusted-or-not credits
     *  marker (the player's own lookup), and the series it belongs to (null for a film). Null when the id is
     *  not in this library (music, a stale id). A multi-episode file's credits are its LAST part's, as the
     *  player's episode entry takes them (`g.last().segments`). */
    private data class ItemFacts(val durationMs: Long, val creditsStartMs: Long?, val seriesJellyfinId: String?)

    private suspend fun factsOf(jellyfinId: String): ItemFacts? {
        mediaStore.resolveByJellyfinId(jellyfinId)?.takeIf { it.episodes.isEmpty() }?.let { film ->
            val credits = runCatching { segmentsFor?.invoke(film.id, "", 0, film.segments.stinger)?.creditsStartMs }.getOrNull()
            return ItemFacts(film.tracks.fileDurationMs() ?: 0L, credits, null)
        }
        for (series in mediaStore.allItems()) {
            val ep = series.episodes.firstOrNull { it.jellyfinId == jellyfinId } ?: continue
            val last = series.episodes.filter { it.jellyfinId == jellyfinId && it.path == ep.path }.maxByOrNull { it.episodeNumber ?: 0 } ?: ep
            val credits = runCatching { segmentsFor?.invoke(series.id, last.filename, last.episodeNumber ?: 0, last.segments.stinger)?.creditsStartMs }.getOrNull()
            return ItemFacts(ep.tracks.fileDurationMs() ?: 0L, credits, series.jellyfinId)
        }
        return null
    }

    /**
     * R343 (FR-R343-4, dev review item 3) — once a Start over session reaches 5 % of its file, latch (once per
     * session) and clear: every episode of the series unwatched for this viewer through [setPlayed] (the
     * `PUT /tv/played` fan-out — specials included, a multi-episode file's parts deduped), then the playing
     * episode's live position written back at once (the clear unmarked it too, which is intended: it becomes an
     * ordinary in-progress episode), then the same invalidation `/tv/played` runs. Never awaited by a heartbeat;
     * a failure only logs, and the page reads playstate again on return, so a partial clear shows as it is.
     */
    private suspend fun maybeStartOverClear(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        val scope = backgroundScope
        var seriesId = ""
        var durationMs = 0L
        val work: suspend () -> Unit = {
            val clearedOk = runCatching { setPlayed(device, seriesId, played = false) }
                .onFailure { Logger.warn("Start over: clear failed for series=$seriesId: ${it.message}", "tv") }
                .isSuccess
            // The live position, not the trigger's: a heartbeat may have moved on while the fan-out ran.
            val live = playbackTracker.tracked().firstOrNull { it.device.deviceId == device.deviceId && it.jellyfinId == jellyfinId }?.positionMs ?: positionMs
            // R343 (FR-R343-13) — from here until the stop's write-back has been refreshed, the server says this
            // episode is unwatched at its position, whatever Jellyfin's own session writes back meanwhile.
            if (clearedOk) StartOverHolds.hold(device.jellyfinUserId, seriesId, jellyfinId, live, durationMs)
            runCatching { writeProgressNow(device, jellyfinId, live) }
                .onFailure { Logger.warn("Start over: position write-back failed for item=$jellyfinId: ${it.message}", "tv") }
            runCatching { onSeriesCleared?.invoke(device, seriesId) }
                .onFailure { Logger.warn("Start over: refresh after the clear failed: ${it.message}", "tv") }
        }
        // Latched and registered under one lock, so a stop arriving at the same moment always finds the job.
        val job = plansMutex.withLock {
            val plan = plans[key] ?: return
            val sid = plan.startOverSeriesId ?: return
            if (key in clearLatched || positionMs < startOverThresholdMs(plan.durationMs)) return
            clearLatched.add(key)
            seriesId = sid
            durationMs = plan.durationMs
            scope?.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { work() }?.also { clearJobs[key] = it }
        }
        Logger.info("Start over: device=${device.deviceId} item=$jellyfinId at ${positionMs}ms — clearing series=$seriesId for this viewer (R343)", "tv")
        if (job == null) work() else job.start()
    }

    /** The playing episode's position, written straight away (not at the next 10 s heartbeat). */
    private suspend fun writeProgressNow(device: DeviceData, jellyfinId: String, positionMs: Long) {
        val w = writer
        if (w != null) { w.enqueueProgress(device, jellyfinId, positionMs, false); return }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.reportPlaybackProgress(
            jellyfinBase, token, jellyfinId, positionMs * TICKS_PER_MS, false, jellyfinId,
            JellyfinDeviceIdentity.forDevice(device), playSessionIdFor(device, jellyfinId),
        )
    }

    /**
     * R343 (FR-R343-4) — after the stop of a *Start over* session that cleared its series: the playing episode
     * back to unwatched at the stop's position. Jellyfin's session holds the user data it read at start (the
     * episode was watched) and its stop writes that back over the clear; this write comes after it. A failure
     * only logs — the episode then shows as watched, which a viewer can untick.
     */
    private suspend fun writeStartOverUnplayed(jellyfinBase: String, token: String, device: DeviceData, jellyfinId: String, positionMs: Long): Boolean =
        runCatching { jellyfinClient.setUserData(jellyfinBase, token, device.jellyfinUserId, jellyfinId, played = false, positionTicks = positionMs * TICKS_PER_MS) }
            .onSuccess { Logger.info("Start over: item=$jellyfinId left unwatched at ${positionMs}ms after its stop (R343)", "tv") }
            .onFailure { Logger.warn("Start over: unwatched write-back failed for item=$jellyfinId: ${it.message}", "tv") }
            .onSuccess { patchUnwatched(device, jellyfinId, positionMs, factsOf(jellyfinId)?.durationMs ?: 0L) }
            .isSuccess

    /** R375 (FR-R375-6) — the episode's `LastPlayedDate` alone (nothing else moves), after its stop has landed. A
     *  failure only logs: the episode then reads as the last one played until the next play. */
    private suspend fun writeLastPlayedBack(jellyfinBase: String, token: String, device: DeviceData, jellyfinId: String, lastPlayed: String) {
        runCatching { jellyfinClient.setUserData(jellyfinBase, token, device.jellyfinUserId, jellyfinId, played = null, positionTicks = null, lastPlayedDate = lastPlayed) }
            .onSuccess { Logger.info("playback stop: item=$jellyfinId last played put back to $lastPlayed (R375)", "tv") }
            .onFailure { Logger.warn("playback stop: last-played write-back failed for item=$jellyfinId: ${it.message}", "tv") }
    }

    /**
     * R343 (FR-R343-11) — after the write-back, the clear's own invalidation (the series re-read, the Continue list
     * rebuilt, `playstate_changed` + `home_changed` pushed): what the played route runs. On a failed write-back it still
     * runs, so the page shows Jellyfin's honest state.
     */
    private suspend fun afterStartOverWriteBack(device: DeviceData, jellyfinId: String, written: Boolean = true) {
        // R343 (FR-R343-13) — the hold ends once Jellyfin says the same (after the refresh, so the rebuilt Continue
        // list and the pushes already carry it); a failed write-back ends it first, so the refresh shows Jellyfin's
        // honest state.
        if (!written) StartOverHolds.release(device.jellyfinUserId, jellyfinId)
        val hook = onSeriesCleared ?: onStopLanded
        if (hook != null) runCatching { hook(device, jellyfinId) }
            .onFailure { Logger.warn("Start over: refresh after the write-back failed for item=$jellyfinId: ${it.message}", "tv") }
        StartOverHolds.release(device.jellyfinUserId, jellyfinId)
    }

    /** R343 (FR-R343-11) — the cache entry for an episode left unwatched at [positionMs] (its favourite flag kept). */
    private fun patchUnwatched(device: DeviceData, jellyfinId: String, positionMs: Long, durationMs: Long) {
        val old = PlaystateCache.get(device.jellyfinUserId)[jellyfinId]
        val pct = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        PlaystateCache.patch(device.jellyfinUserId, mapOf(jellyfinId to (old ?: dev.jellystructure.shared.tv.CardPlayState()).copy(played = false, resumeMs = positionMs, playedPct = pct)))
    }

    /** Work a stop starts but never waits for (R347's tick): on the service's scope, inline in tests. */
    private suspend fun runInBackground(what: String, work: suspend () -> Unit) {
        val scope = backgroundScope
        val guarded: suspend () -> Unit = { runCatching { work() }.onFailure { Logger.warn("$what failed: ${it.message}", "tv") } }
        if (scope == null) guarded() else scope.launch { guarded() }
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
        startOverUnplayed: Boolean = false,
        restoreLastPlayed: String? = null,
        reason: String = "user",
        userData: StopUserData? = null,
    ) {
        // Phase 219 (FR-219-2) — the stop is the write that must land: queued, retried past the
        // client's disconnect, and the encode released once it has (JellyfinSink.stop).
        val w = writer
        if (w != null) { w.enqueueStop(device, jellyfinId, positionMs, jellyfinPlaySessionId, startOverUnplayed, restoreLastPlayed, reason = reason, userData = userData); return }
        Logger.info("stop write: item=$jellyfinId device=${device.deviceId} at=${positionMs}ms reason=$reason inline (312)", "tv")
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
        if (userData != null) {
            // Phase 310 (dev review item 7) — the same single write the writer's sink makes.
            val written = writeStopUserData(jellyfinBase, token, device, jellyfinId, userData, "stop")
            if (startOverUnplayed) afterStartOverWriteBack(device, jellyfinId, written)
            return
        }
        if (restoreLastPlayed != null) writeLastPlayedBack(jellyfinBase, token, device, jellyfinId, restoreLastPlayed)
        if (startOverUnplayed) {
            val written = writeStartOverUnplayed(jellyfinBase, token, device, jellyfinId, positionMs)
            afterStartOverWriteBack(device, jellyfinId, written)
        }
    }

    /**
     * Phase 312 (dev review item 4) — free an encode without telling Jellyfin the play stopped. Used where the same play
     * goes on (a start or a restream superseding the tracker's entry for this exact device and item) and where the
     * viewer's own stop has already been queued (an abandoned start re-sends THAT stop's position, never its own start
     * position). Before this phase each of these queued a STOP: a superseded entry's at its old position (ending
     * Jellyfin's session while the play went on), an abandoned start's at its start position — 0 for a play from the
     * beginning, which reset the place after the real stop (24 cases in four days).
     */
    private suspend fun releaseEncodesOnly(device: DeviceData, jellyfinId: String, jellyfinPlaySessionId: String?, why: String) {
        Logger.info("stop write: item=$jellyfinId device=${device.deviceId} reason=$why — encodes only, no stop (312)", "tv")
        jellyfinPlaySessionId ?: return
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        runCatching { releaseEncodes(jellyfinBase, token, JellyfinDeviceIdentity.forDevice(device), jellyfinPlaySessionId) }
            .onFailure { Logger.warn("releasing encodes failed for item=$jellyfinId ($why): ${it.message}", "tv") }
    }

    /** Phase 312 — an abandoned start: free what it minted, and re-send the viewer's own stop (the position their stop
     *  carried, which the tracker kept) so Jellyfin's session that this start re-opened is closed at the right place. */
    private suspend fun releaseAbandoned(device: DeviceData, jellyfinId: String, jellyfinPlaySessionId: String?, stoppedAtMs: Long?, why: String) {
        releaseEncodesOnly(device, jellyfinId, jellyfinPlaySessionId, why)
        if (stoppedAtMs != null) releaseSession(device, jellyfinId, stoppedAtMs, null, reason = "$why-resend")
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
        playbackQoeStore.record(device.deviceId, playSessionIdFor(device, report.itemId), report, encoder = servedByOf(device.deviceId, report.itemId))
        // 309 (FR-309-1) — the device's record learns from every post (a stall, a hold); never awaited by the route.
        val scope = backgroundScope
        if (scope != null) scope.launch { runCatching { foldIntoRecord(device, report) }.onFailure { Logger.warn("record: fold failed: ${it.message}", "tv") } }
        else kotlinx.coroutines.runBlocking { runCatching { foldIntoRecord(device, report) } }
        // 308 (FR-308-5) — the variant it is on now, for the admin's *Playing now*.
        val key = PlaybackKey(device.deviceId, report.itemId)
        val bps = report.variantBandwidthBps
        val now = bps?.takeIf { it > 0 }?.let { VariantNow(it, report.variantHeight, report.variantSwitchesDown, report.variantSwitchesUp,
            startBps = report.startVariantBps ?: variantsNow.value[key]?.startBps) }
        while (true) {
            val old = variantsNow.value
            if (old[key] == now) return
            val next = (if (now != null) old + (key to now) else old - key).let { m -> if (m.size > 200) m.entries.drop(m.size - 200).associate { it.toPair() } else m }
            if (variantsNow.compareAndSet(old, next)) break
        }
        onVariantReported?.invoke()
    }

    /** 308 (FR-308-5) — a QoE report changed what *Playing now* shows (Main re-publishes the admin list). */
    var onVariantReported: (() -> Unit)? = null

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
                // R368 (review item 12 a) — a session still reconnecting after a restart is judged by heartbeat alone:
                // its progress may arrive before its events socket comes back.
                val reconnecting = sessions?.isReconnecting(p.device.deviceId) == true
                val stale = playbackTracker.isHeartbeatStale(p)
                val needsSocket = PlaybackTracker.needsEventsSocket(p.device)
                if (shouldForceStop(stale, needsSocket, !needsSocket || isDeviceConnected(p.device.deviceId), reconnecting)) add(p)
            }
        }
        for (p in stale) {
            Logger.info("Stop watchdog: force-stopping stale playback item=${p.jellyfinId} device=${p.device.deviceId}", "tv")
            runCatching { stopPlayback(p.device, p.jellyfinId, p.positionMs, reason = "watchdog") }
                .onFailure { Logger.warn("Stop watchdog: force-stop failed: ${it.message}", "tv") }
            // R372 (FR-R372-4, amends FR-R368-2) — the session is left paused and offline for 24 h, not ended.
            runCatching { sessions?.onReaped(p.device, p.jellyfinId) }
            // Phase 236 (FR-236-8) — a screen's status is display state, not tied to the Jellyfin stop
            // call's own success; clear it regardless of whether the stop above landed.
            runCatching { onDeviceReaped?.invoke(p.device.deviceId) }
                .onFailure { Logger.warn("Stop watchdog: onDeviceReaped failed: ${it.message}", "tv") }
            screenShuffles.clear(p.device.deviceId)   // R343 (FR-R343-8) — a reaped screen's carried shuffle ends
        }
    }

    /** 312 / R185 — a watched item's resume position set to 0 by a user-data write (`setUserData`, position only), never by a
     *  `/Sessions/Playing/Stopped` at 0 ms. A failure only logs: the played mark already landed. */
    private suspend fun zeroPosition(base: String, token: String, userId: String, jellyfinId: String) {
        runCatching { jellyfinClient.setUserData(base, token, userId, jellyfinId, played = null, positionTicks = 0L) }
            .onFailure { e -> if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                Logger.warn("mark watched: position zeroing failed for item=$jellyfinId: ${e.message}", "tv") }
    }

    suspend fun mark(device: DeviceData, jellyfinId: String, watched: Boolean) {
        requireVisible(device, jellyfinId)
        StartOverHolds.release(device.jellyfinUserId, jellyfinId)   // R343 (FR-R343-13) — the viewer's word wins
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        if (watched) {
            // R185 — markPlayed alone never touches PlaybackPositionTicks, so a stale/leaked resume
            // position can outlive the played flag and keep this item showing as "in progress" in
            // Continue Watching. Zero it explicitly at the same choke point every "mark watched" path
            // goes through, rather than relying on whichever caller happens to also report a stop.
            // 312 (found live 2026-10-08) — the zeroing used to be a `/Sessions/Playing/Stopped` at 0 ms, a stop Jellyfin
            // logs and treats as a playback stop; it is now a user-data write, after the played mark, so no stop at 0 is
            // ever sent for a watched item.
            jellyfinClient.markPlayed(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
            zeroPosition(jellyfinBase, token, device.jellyfinUserId, jellyfinId)
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
        // R343 (FR-R343-13) — a viewer's own tick or untick ends any Start over hold on these episodes (the clear's
        // own call comes before its hold is set).
        targets.forEach { StartOverHolds.release(uid, it) }

        coroutineScope {
            targets.map { id ->
                async {
                    playedGate.withPermit {
                        if (played) {
                            // R185 — same gap as mark() above: the manual watched-toggle had NO position
                            // handling at all, so a partially-watched item flipped to "watched" here kept
                            // its stale nonzero PlaybackPositionTicks forever. Zero it alongside markPlayed.
                            Logger.info("stop write: item=$id device=${device.deviceId} at=0ms reason=set-played direct (312)", "tv")
                            jellyfinClient.markPlayed(base, token, uid, id)
                            zeroPosition(base, token, uid, id)   // 312 — a user-data write, never a stop at 0
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
            // Phase 273 (FR-273-17) — a sidecar judged not to fit this video is not offered, and so never chosen by
            // default or remembered either (the client picks only from this list). Matched by file name: Jellyfin
            // sees the media under its own root.
            .filter { !it.isExternal || dev.jellystructure.subtitles.SubtitleVerdicts.isOffered(it.path) }
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
        // R351 (FR-R351-11) — the burn-in still negotiates SDR-only as before, but with the client's own limits: the
        // channel count, the H.264 size and level, the bitrate ceiling and the audio it plays. Without them a Nest Hub
        // that picked a picture subtitle was sent 1080p and six channels again.
        val limits = burnInLimits(capabilities)
        val measuredBps = measuredThroughputOf(device)   // 308 (FR-308-4)
        val take = takeOf(device)   // 309 (FR-309-1)
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = limits, subtitleStreamIndex = subtitleStreamIndex, identity = identity, audioStreamIndex = audioStreamIndex, throughputCapBps = negotiationCap(capabilities?.hlsAdaptive == true, take))
        val negotiated = playbackInfo?.mediaSources?.firstOrNull()?.transcodingUrl
            ?.let { withChannelLimit(if (it.startsWith("http")) it else "$jellyfinBase$it", limits) }
        Logger.info("PlaybackInfo(restream, burn-in): item=$jellyfinId sub=$subtitleStreamIndex audio=${audioStreamIndex ?: "default"} negotiated=${negotiated != null}", "tv")
        // FR-239-2/-5 — `negotiated` is Jellyfin's own URL and is passed through untouched (see the
        // note at [startPlayback]'s streamUrl). Only the hand-built FALLBACK, used when PlaybackInfo is
        // unavailable, is ours to spell — and it is the branch that runs least often, which is exactly
        // why the pre-239 grep would have looked clean while every real transcoded play still carried a
        // Jellyfin-spelled token. `withJellyfinToken` owns the separator too: this concatenation used
        // to start its last fragment with a hand-written `&`.
        val transcodingUrl = negotiated ?: withJellyfinToken(
            withChannelLimit(
                "$jellyfinBase/Videos/$jellyfinId/master.m3u8" +
                    "?DeviceId=${identity.deviceId}" +
                    "&MediaSourceId=$jellyfinId" +
                    "&VideoCodec=h264" +
                    "&AudioCodec=aac" +
                    "&MaxWidth=${limits.maxH264Width.takeIf { it > 0 } ?: 1920}&MaxHeight=${limits.maxH264Height.takeIf { it > 0 } ?: 1080}" +
                    "&SubtitleMethod=Encode" +
                    "&SubtitleStreamIndex=$subtitleStreamIndex",
                limits,
            ),
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
                // Phase 312 — a restream replaces the stream of a play that goes on: no stop for Jellyfin.
                if (old.jellyfinPlaySessionId != playbackInfo?.playSessionId) releaseEncodesOnly(device, jellyfinId, old.jellyfinPlaySessionId, "restream-supersede")
            }
        }
        if (startResult.stopAlreadyArrived) {
            withContext(NonCancellable) {
                playbackTracker.stopped(device, jellyfinId)
                releaseAbandoned(device, jellyfinId, playbackInfo?.playSessionId, startResult.stoppedAtMs, "restream-abandoned")
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
        ).let { withRenditions(it, capabilities, jellyfinBase, jellyfinId, token, identity, playbackInfo?.playSessionId, abandoned = startResult.stopAlreadyArrived, device = device,
            sourceVideoBps = playbackInfo?.mediaSources?.firstOrNull()?.videoBitrate(), measuredBps = measuredBps, take = take,
            // 313d (FR-313-6) — the picked image subtitle's place among the file's own subtitle streams.
            burnSubtitleOrder = embeddedSubtitleOrder(itemDetail?.mediaStreams.orEmpty(), subtitleStreamIndex), burnRequested = true) }
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
        val measuredBps = measuredThroughputOf(device)   // 308 (FR-308-4)
        val take = takeOf(device)   // 309 (FR-309-1)
        val playbackInfo = jellyfinClient.getPlaybackInfo(jellyfinBase, token, device.jellyfinUserId, jellyfinId, capabilities = capabilities, identity = identity, audioStreamIndex = audioStreamIndex, throughputCapBps = negotiationCap(capabilities.hlsAdaptive, take))
        val source = playbackInfo?.mediaSources?.firstOrNull()
        val needsTranscode = source != null && !source.supportsDirectPlay && source.transcodingUrl != null
        // 2026-09-24 — this path serves an un-burn AND a plain audio switch (R284), and cannot tell them
        // apart; it used to log every one of them as "un-burn", which misread the soveværelse-TV sweep.
        Logger.info("PlaybackInfo(restream, no burn-in): item=$jellyfinId audio=${audioStreamIndex ?: "default"} directPlay=${source?.supportsDirectPlay} transcode=$needsTranscode", "tv")
        val subtitles = buildSubtracks(itemDetail, jellyfinId, jellyfinBase, token, embedContainerSubs = !needsTranscode && capabilities.supportsEmbeddedTextSubs, hlsSubtitles = capabilities.hlsOnly && capabilities.hlsSubtitles)

        val startResult = playbackTracker.started(device, jellyfinId, positionMs, playbackInfo?.playSessionId)
        startResult.superseded?.let { old ->
            withContext(NonCancellable) {
                if (old.jellyfinPlaySessionId != playbackInfo?.playSessionId) releaseEncodesOnly(device, jellyfinId, old.jellyfinPlaySessionId, "unburn-supersede")
            }
        }
        if (startResult.stopAlreadyArrived) {
            withContext(NonCancellable) {
                playbackTracker.stopped(device, jellyfinId)
                releaseAbandoned(device, jellyfinId, playbackInfo?.playSessionId, startResult.stoppedAtMs, "unburn-abandoned")
            }
        }

        return StreamTicket(
            jellyfinBaseUrl = jellyfinBase,
            accessToken = "", // R271 — see StreamTicket.accessToken
            itemId = jellyfinId,
            container = "mkv",
            directPlay = !needsTranscode,
            hlsUrl = streamUrlFor(jellyfinBase, jellyfinId, token, identity, source?.transcodingUrl?.takeIf { needsTranscode }, capabilities),
            startPositionMs = positionMs,
            subtitles = subtitles,
            audio = buildAudioTracks(itemDetail),
            trickplayUrl = null,
            expiresAt = nowMs() + TICKET_TTL_MS,
            audioStreamIndex = if (needsTranscode) carriedAudioIndex(source?.transcodingUrl, audioStreamIndex) else null,
        ).let { withRenditions(it, capabilities, jellyfinBase, jellyfinId, token, identity, playbackInfo?.playSessionId, abandoned = startResult.stopAlreadyArrived, device = device,
            sourceVideoBps = source?.videoBitrate(), measuredBps = measuredBps, take = take,
            sourceVideoCodec = source?.mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }?.codec,
            sourceVideoRange = source?.mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }?.videoRangeType) }
    }

    /**
     * The stream URL for a negotiated session: Jellyfin's own `TranscodingUrl` verbatim when it is
     * transcoding (FR-239-5 — its credential spelling is Jellyfin's to get right, never rewritten
     * here), else our static direct-play URL (FR-239-2 — handed to a player, which cannot attach a
     * header). One spelling, shared by [startPlayback] and [restreamWithoutBurnIn].
     */
    private fun streamUrlFor(jellyfinBase: String, jellyfinId: String, token: String, identity: JellyfinDeviceIdentity, transcodingUrl: String?, capabilities: ClientCapabilities? = null): String =
        if (transcodingUrl != null) {
            // R351 (FR-R351-10) — the client's channel limit rides the conversion URL even if Jellyfin left it out.
            withChannelLimit(if (transcodingUrl.startsWith("http")) transcodingUrl else "$jellyfinBase$transcodingUrl", capabilities)
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
/**
 * R351 (FR-R351-11) — what a burn-in restream negotiates with: today's conservative default (SDR only, no direct-play
 * list of its own) plus the client's hard limits, so a converted stream never exceeds what the device said it plays.
 * Null (an old client that sends no capabilities) keeps the plain default.
 */
internal fun burnInLimits(capabilities: ClientCapabilities?): ClientCapabilities =
    capabilities?.let {
        ClientCapabilities(
            audioCodecs = it.audioCodecs,
            maxAudioChannels = it.maxAudioChannels,
            hlsOnly = it.hlsOnly,
            maxH264Width = it.maxH264Width,
            maxH264Height = it.maxH264Height,
            maxH264Level = it.maxH264Level,
            maxVideoBitrate = it.maxVideoBitrate,
            maxH264Bitrate = it.maxH264Bitrate,
        )
    } ?: ClientCapabilities()

internal fun carriedAudioIndex(transcodingUrl: String?, requested: Int?): Int? =
    transcodingUrl?.let { Regex("[?&]AudioStreamIndex=(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: requested

/**
 * Phase 179 — moved out of [PlaybackService] (was `private`) so [dev.jellystructure.media.PipelineStepOps]
 * can reuse the exact same text-subtitle predicate [buildSubtracks] uses, rather than a second,
 * possibly-diverging copy. `internal`: visible module-wide, not exported past this Gradle target.
 */
/**
 * 313d (FR-313-6) — a Jellyfin subtitle stream's place among the FILE's own subtitle streams (`-map 0:s:<n>`): its
 * order among the non-external subtitle streams (Jellyfin lists sidecars first and renumbers, R382). Null when it is
 * a sidecar (not in the file) or not found.
 */
/** 313 (FR-313-13) — who served a play: `ours` / `jellyfin` / `direct`, and for ours the words *Playing now* adds. */
data class ServedNow(val how: String, val detail: String? = null)

/** 313 (FR-313-13) — *4 qualities · HEVC HDR* (or *1 quality · H.264*, *… · burned-in subtitles*). */
internal fun encoderWords(plan: EncoderPlan): String = listOfNotNull(
    "${plan.rungs.size} ${if (plan.rungs.size == 1) "quality" else "qualities"}",
    (if (plan.codec == EncoderCodec.HEVC) "HEVC" else "H.264") + if (plan.keepsHdr) " HDR" else "",
    "burned-in subtitles".takeIf { plan.burnSubtitleOrder != null },
).joinToString(" · ")

internal fun embeddedSubtitleOrder(streams: List<dev.jellystructure.auth.JellyfinMediaStream>, jellyfinIndex: Int): Int? {
    val embedded = streams.filter { it.type.equals("Subtitle", ignoreCase = true) && !it.isExternal }.sortedBy { it.index }
    return embedded.indexOfFirst { it.index == jellyfinIndex }.takeIf { it >= 0 }
}

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
private suspend fun JellyfinClient.cachedTokenCheck(baseUrl: String, device: DeviceData): TokenCheck {
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
    var result = dev.jellystructure.ops.boundedOrNull(TOKEN_CHECK_TIMEOUT_MS, "token check") { checkToken(baseUrl, userToken, device.jellyfinUserId) }
        ?: TokenCheckResult(TokenCheck.UNKNOWN, error = "timed out after ${TOKEN_CHECK_TIMEOUT_MS}ms")
    // FR-194-5 — believing a rejection costs the whole household ten minutes of playback, so it does
    // not get to rest on a single round trip. Re-probe once; only a second consecutive REJECTED is
    // believed. This runs only on the already-rare rejection path, never on the cache-served hot one.
    if (result.outcome == TokenCheck.REJECTED && userToken.isNotBlank()) {
        delay(500)
        result = dev.jellystructure.ops.boundedOrNull(TOKEN_CHECK_TIMEOUT_MS, "token check") { checkToken(baseUrl, userToken, device.jellyfinUserId) }
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

/**
 * Phase 300 (FR-300-4) — where a borrowing device's rejected token can be replaced from. [RaviloDeviceService] in
 * production (installed by Main.kt); a test installs its own. Null ⇒ no healing, the phase-110 behaviour.
 */
interface BorrowedTokenStore {
    /** The token stored on [device]'s row now; null when the row is gone. */
    fun storedJellyfinToken(deviceId: String, jellyfinUserId: String): String?
    /** Every row of [jellyfinUserId] (any kind; [ReceiverTokenPolicy.donors] filters). */
    fun listByUser(jellyfinUserId: String): List<DeviceData>
    /** Stores [token] (held by [fromDeviceId]) on [device]'s row. */
    fun adoptJellyfinToken(device: DeviceData, token: String, fromDeviceId: String): Boolean
}

private val borrowedTokenStoreLock = Mutex()
private var borrowedTokenStore: BorrowedTokenStore? = null

/** Phase 300 — Main.kt installs the device service; tests install a store and remove it (null) when done. */
suspend fun installBorrowedTokenStore(store: BorrowedTokenStore?) = borrowedTokenStoreLock.withLock { borrowedTokenStore = store }

/** Phase 300 (FR-300-2) — is [token] negative-cached right now (Jellyfin rejected it twice within ten minutes)? */
internal suspend fun isTokenKnownDead(token: String): Boolean =
    token.isNotBlank() && tokenCacheMutex.withLock { (tokenInvalidUntil[token] ?: 0L) > nowMs() }

/** Phase 300 (FR-300-5) — [token] was replaced on its device: check it again before it is trusted. The negative entry stays. */
suspend fun forgetTokenValidity(token: String) = tokenCacheMutex.withLock { tokenValidUntil.remove(token); Unit }

/**
 * Phase 300 (FR-300-4) — [device] borrows its token (a Cast receiver, a screen) and Jellyfin rejected it. First the
 * token now on its row (a hand-off or a re-sign-in may already have replaced the copy this request carried), then the
 * same user's own sign-ins, most recently seen first. The first one Jellyfin accepts is stored and returned; null when
 * none is. Only ever the same Jellyfin user's tokens. Never logs a token.
 */
private suspend fun JellyfinClient.healBorrowedToken(baseUrl: String, device: DeviceData): String? {
    val store = borrowedTokenStoreLock.withLock { borrowedTokenStore } ?: return null
    val stored = store.storedJellyfinToken(device.deviceId, device.jellyfinUserId) ?: return null
    if (stored.isNotBlank() && stored != device.jellyfinUserToken &&
        cachedTokenCheck(baseUrl, device.copy(jellyfinUserToken = stored)) == TokenCheck.VALID
    ) return stored
    val rows = store.listByUser(device.jellyfinUserId)
    val dead = rows.map { it.jellyfinUserToken }.filter { isTokenKnownDead(it) }.toSet()
    val receiver = device.copy(jellyfinUserToken = stored)
    for (donor in ReceiverTokenPolicy.donors(receiver, rows) { it in dead || it == device.jellyfinUserToken }) {
        if (cachedTokenCheck(baseUrl, donor) != TokenCheck.VALID) continue
        if (!store.adoptJellyfinToken(receiver, donor.jellyfinUserToken, donor.deviceId)) continue
        // The donor's identity was remembered for this token by its own check; this request goes out as the receiver.
        dev.jellystructure.auth.DeviceIdentityRegistry.remember(device.copy(jellyfinUserToken = donor.jellyfinUserToken))
        return donor.jellyfinUserToken
    }
    return null
}

/** Phase 300 — the paired-token check plus FR-300-4's heal: the outcome and the token to use with it. */
private suspend fun JellyfinClient.pairedToken(baseUrl: String, device: DeviceData): Pair<TokenCheck, String> {
    val check = cachedTokenCheck(baseUrl, device)
    if (check != TokenCheck.REJECTED || !ReceiverTokenPolicy.borrowsToken(device.kind)) return check to device.jellyfinUserToken
    val healed = healBorrowedToken(baseUrl, device) ?: return check to device.jellyfinUserToken
    return TokenCheck.VALID to healed
}

internal suspend fun JellyfinClient.tvToken(baseUrl: String, device: DeviceData, serverToken: String): String =
    pairedToken(baseUrl, device).let { (check, token) -> if (check == TokenCheck.VALID) token else serverToken }

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
    pairedToken(baseUrl, device).let { (check, token) -> if (check != TokenCheck.REJECTED) token else null }

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
