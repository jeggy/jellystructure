package dev.jellystructure.tv

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.JellyfinLiveTvChannel
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.boundedOrNull
import dev.jellystructure.ops.LockedMap
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.model.AdminSessionRow
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import dev.jellystructure.shared.tv.LiveTvOverview
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import dev.jellystructure.util.isoToEpochSeconds
import dev.jellystructure.auth.constantTimeEquals
import dev.jellystructure.auth.jellyfinAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.gmtime_r
import platform.posix.strftime
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.timespec
import platform.posix.tm

private const val TICKET_TTL_MS = 4 * 60 * 60 * 1000L

/** DEB-2627 FR-1 — the backend's look at a source's master: at most 3 redirects, 3 s in all, 64 KiB, cached an hour. */
private const val SOURCE_MAX_REDIRECTS = 3
private const val SOURCE_FETCH_TIMEOUT_MS = 3_000L
private const val SOURCE_AUDIO_DRAIN_MS = 1_000L
private const val SOURCE_AUDIO_DRAIN_BYTES = 4L * 1024
private const val SOURCE_FETCH_MAX_BYTES = 64 * 1024
private const val SOURCE_CLASS_TTL_MS = 60 * 60 * 1000L
/** A failed look is remembered for a minute only: an hour of `hls` would hide a ladder after one slow answer, and a dead source would cost every tune 3 s. */
private const val SOURCE_CLASS_FAILED_TTL_MS = 60 * 1000L
private const val SOURCE_CLASS_MAX_ENTRIES = 256

/** DEB-2627 FR-4 — the one value of [dev.jellystructure.shared.tv.LiveTvTuneRequest.via] that means something; any other reads as the source. */
private const val VIA_JELLYFIN = "jellyfin"

/** DEB-2627 FR-5 — where the stream on screen comes from: the broadcaster's own master, or Jellyfin's remux. The heartbeat's `delivery`. */
private const val DELIVERY_SOURCE = "source"
private const val DELIVERY_JELLYFIN = "jellyfin"

/** DEB-2627 FR-5 — the `kind` of an admin session row that is a live channel; never in a viewer's list. */
internal const val SESSION_KIND_LIVE = "live"

/** DEB-2520 — how long a viewer's channel list from Jellyfin is believed; a policy change drops it sooner (the hash). */
private const val VIEWER_CHANNELS_TTL_MS = 2 * 60 * 1000L

/**
 * Phase 147 — surfaces Jellyfin's Live TV into Ravilo: reads the channel list, diffs it against
 * jellystructure's stored [LiveTvChannelOverride]s (new → hidden, gone → unavailable), serves the
 * merged lineup + a cached guide, proxies channel logos, and tunes/stops a live stream. Read-only
 * against Jellyfin's tuner/guide — see the spec's non-goals.
 */
class LiveTvService(
    dataDir: String,
    private val store: LiveTvStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val eventBus: TvEventBus? = null,
) {
    private val http = OutboundHttp.client
    private val logoDir = "$dataDir/artwork/livetv-logos"
    private val syncMutex = Mutex()
    private var lastLiveChannels: List<JellyfinLiveTvChannel> = emptyList()

    // DEB-3066 — now/next follows the clock. `hasGuide` per channel (a programme on now or starting within [GUIDE_REACH_MS]),
    // when the snapshot above was read, and when a read last tried to refresh it. `nowNextMutex` makes concurrent readers share one re-read.
    private var guideFlags: Map<String, Boolean> = emptyMap()
    private var snapshotAt: Long = 0L
    private var lastNowNextAttempt: Long = 0L
    private val nowNextMutex = Mutex()

    // DEB-2520 — the channel ids Jellyfin lists for one viewer (`/LiveTv/Channels?userId=`), per user. HomeFeedService's
    // shape: the entry also carries the hash of the device's library and tag lists, so a policy change misses it.
    private data class ViewerChannels(val ids: Set<String>, val policyHash: Int, val fetchedAt: Long)
    private val viewerChannels = LockedMap<String, ViewerChannels>()

    // DEB-2627 FR-1 — what a source's master was last read as, per (channel id, Path). `until` is the epoch ms it stops being believed.
    private data class SourceClass(val look: SourceLook, val until: Long)
    private val sourceClasses = LockedMap<String, SourceClass>()
    private val sourceHttp by lazy { http.config { followRedirects = false } }

    private var guideCache: List<LiveTvGuideProgram> = emptyList()
    private var guideCacheFetchedAt: Long = 0L

    /** DEB-2627 FR-5 — one device's open live stream, as reported: [delivery] is `source` or `jellyfin`, the rung is what the player last sent (null until it does). */
    internal data class ActiveLive(
        val device: DeviceData,
        val channelId: String,
        val liveStreamId: String,
        val delivery: String,
        val startedMs: Long,
        val heartbeatMs: Long,
        val variantBps: Long? = null,
        val height: Int? = null,
        /** DEB-2656 (DEB-2628 FR-5) — `tv` or `radio`, from the channel's stored kind at the time of the tune. */
        val channelKind: String = "tv",
        /** DEB-2656 (DEB-2628 FR-4) — the radio relay this tune minted; null for a TV channel, an as-is station or a stream re-registered after a restart. */
        val relay: RelayState? = null,
        /** DEB-2656 (DEB-2628 FR-5) — the admin row's *MP3 · 192 kb/s*; null when the source stated neither. */
        val format: String? = null,
    )

    // Live-stream watchdog — separate from PlaybackService's VOD tracking (dev-review addendum D: a
    // live tune is a sibling flow, not a branch of startPlayback). Key = deviceId. DEB-2627 FR-5: also the admin's live
    // rows and `isPlaybackActive()`'s live half, so it is read from request threads and the deferral checks alike.
    private val activeStreams = LockedMap<String, ActiveLive>()

    /** DEB-2656 — every time this service reads (a tune, a heartbeat, the watchdog, a relay closing); a test moves it instead of waiting 60 s. */
    internal var clock: () -> Long = { nowMs() }

    // DEB-2656 (FR-4) — open relay connections across the server, against RADIO_RELAY_MAX_TOTAL.
    private val relayTotal = AtomicInt(0)

    /** DEB-2627 FR-5 — told when the set of live rows, or what one shows, changes (the admin's `/ws` list is re-sent). */
    var onLiveChanged: (() -> Unit)? = null

    /** DEB-2627 FR-5 — is any device holding a live stream open? Non-suspend, like [PlaybackTracker.anyActive]. */
    fun anyLiveActive(): Boolean = activeStreams.size > 0

    /** DEB-2627 FR-5 — the display names of the devices holding a live stream, for "Paused — TV is watching". */
    fun liveDeviceNames(): List<String> = activeStreams.snapshot().values.map { it.device.displayName.ifBlank { it.device.deviceId } }

    init {
        runCatching { SystemFileSystem.createDirectories(Path(logoDir)) }
    }

    private fun jellyfinBase(): String = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
    private fun jellyfinToken(): String = configStore.current.apiKeys.jellyfinToken

    /** Re-pull channels from Jellyfin, diff against the store (new → hidden+badged, gone → unavailable,
     *  reappeared → un-flagged), persist, and return the fresh status summary. Cheap (one Jellyfin GET) —
     *  safe to call on every admin page load, not just the explicit "Refresh from Jellyfin" action. */
    suspend fun sync(): LiveTvOverview = syncMutex.withLock {
        val base = jellyfinBase(); val token = jellyfinToken()
        val reachable = base.isNotBlank() && token.isNotBlank()
        val live = if (reachable) jellyfinClient.getLiveTvChannels(base, token) else emptyList()
        // A transient Jellyfin outage must NOT wipe the lineup to "everything unavailable" — only a
        // real (non-empty-due-to-error) reachable response participates in the diff.
        if (reachable && live.isEmpty() && jellyfinClient.getLiveTvInfo(base, token) == null) {
            return@withLock buildOverview(reachable = false)
        }
        applySnapshot(live, base, token)
        val liveIds = live.associateBy { it.id }
        val existing = store.overridesByChannelId()
        var nextOrder = (existing.values.maxOfOrNull { it.order } ?: -1) + 1

        val merged = buildList {
            for ((id, ov) in existing) {
                val liveCh = liveIds[id]
                // DEB-2655 (FR-1) — the kind follows Jellyfin on every sync a channel is still listed in, until the operator
                // has set it (DEB-3039: Jellyfin 12.1.0 never says Radio); a gone channel keeps its last.
                add(
                    if (liveCh == null) ov.copy(unavailable = true)
                    else ov.copy(unavailable = false, kind = if (ov.kindSet) ov.kind else kindOf(liveCh)),
                )
            }
            for (ch in live) {
                if (ch.id in existing) continue
                add(
                    LiveTvChannelOverride(
                        channelId = ch.id, shown = false, number = nextOrder + 1, order = nextOrder,
                        category = "", unavailable = false, isNew = true, kind = kindOf(ch),
                    ),
                )
                nextOrder++
            }
        }
        store.replaceAll(merged)
        store.updateSettings { it.copy(lastSyncedAt = nowMs()) }
        eventBus?.notifyGlobalConfigChanged()
        buildOverview(reachable)
    }

    private fun buildOverview(reachable: Boolean): LiveTvOverview {
        val settings = store.settings()
        val overrides = store.overridesByChannelId().values
        return LiveTvOverview(
            enabled = settings.enabled,
            reachable = reachable,
            channelsDiscovered = overrides.count { !it.unavailable },
            channelsShown = overrides.count { it.shown && !it.unavailable },
            newCount = overrides.count { it.isNew },
            unavailableCount = overrides.count { it.unavailable },
            epgCadenceMinutes = settings.epgCadenceMinutes,
            lastSyncedAt = settings.lastSyncedAt,
        )
    }

    fun overview(): LiveTvOverview {
        val reachable = jellyfinBase().isNotBlank() && jellyfinToken().isNotBlank()
        return buildOverview(reachable)
    }

    /** The merged admin lineup table — Jellyfin facts (name/logo/current program) from the last [sync]
     *  joined with the stored override, ordered per the operator's chosen order. */
    fun lineup(): List<LiveTvChannel> {
        val now = clock()
        val liveById = lastLiveChannels.associateBy { it.id }
        return store.overridesByChannelId().values.sortedBy { it.order }.map { ov ->
            val live = liveById[ov.channelId]
            // Bug fix: this used to fall back to the raw Jellyfin channel id (a hex UUID) whenever the
            // in-memory `lastLiveChannels` cache hadn't been populated yet (e.g. right after a backend
            // restart, before the next sync() cadence) — every screen (Home rail, TV Guide, in-player
            // channel switcher, badge initials) renders `name` verbatim, so the id leaked to the TV.
            // Never show it; fall back to a plain "Channel N" placeholder instead.
            // Admin rename wins over Jellyfin's own name; blank/absent falls through to it, then to
            // the id-avoiding placeholder above.
            val name = ov.displayName?.takeIf { it.isNotBlank() }
                ?: live?.name?.takeIf { it.isNotBlank() }
                ?: "Channel ${ov.number}"
            // Public path (like R133's channel-logos/image-proxy) — the TV client can't attach a device
            // token to an <img>/Coil image request, and logos aren't sensitive.
            val logo = ov.logoOverrideUrl
                ?: live?.imageTags?.primary?.let { "/api/tv/livetv/logo/${ov.channelId}" }
            // DEB-3066 — a programme whose end has passed is never the current one, whatever the snapshot holds.
            val current = live?.currentProgram?.let { p ->
                val start = p.startDate?.let(::isoToEpochMs) ?: return@let null
                val end = p.endDate?.let(::isoToEpochMs) ?: start
                LiveTvProgramInfo(p.name, start, end, p.isSeries)
            }?.takeIf { it.endMs > now }
            LiveTvChannel(
                channelId = ov.channelId,
                name = name,
                number = ov.number,
                order = ov.order,
                shown = ov.shown,
                logoUrl = logo,
                category = ov.category,
                hasGuide = guideFlags[ov.channelId] ?: (current != null),
                isNew = ov.isNew,
                unavailable = ov.unavailable,
                currentProgram = current,
                nextProgram = null,
                kind = ov.kind,
            )
        }
    }

    /**
     * DEB-3066 (Phase 147 D1, amended) — [lineup] after a re-read of now/next when one is due: the snapshot holds a programme
     * that has ended or no channels at all, or a channel with a guide is in a gap and the snapshot is over [GAP_REREAD_MS] old.
     * Happens on read, never on a timer; at most one re-read per [REREAD_MIN_GAP_MS], shared by concurrent readers; it replaces
     * the snapshot and nothing else (no store write, no `lastSyncedAt`, no push). A Jellyfin that does not answer changes nothing.
     */
    suspend fun freshLineup(): List<LiveTvChannel> {
        if (nowNextDue(clock())) refreshNowNext()
        return lineup()
    }

    private fun nowNextDue(now: Long): Boolean {
        val snapshot = lastLiveChannels
        if (snapshot.isEmpty()) return true
        for (ch in snapshot) {
            val end = ch.currentProgram?.let { p -> p.endDate?.let(::isoToEpochMs) ?: p.startDate?.let(::isoToEpochMs) }
            if (end != null && end <= now) return true
            if (end == null && guideFlags[ch.id] == true && now - snapshotAt > GAP_REREAD_MS) return true
        }
        return false
    }

    private suspend fun refreshNowNext() = nowNextMutex.withLock {
        val now = clock()
        // A reader that waited on the lock finds the first one's answer here and does not ask again.
        if (now - lastNowNextAttempt < REREAD_MIN_GAP_MS || !nowNextDue(now)) return@withLock
        lastNowNextAttempt = now
        val base = jellyfinBase(); val token = jellyfinToken()
        if (base.isBlank() || token.isBlank()) return@withLock
        val live = jellyfinClient.getLiveTvChannelsOrNull(base, token, null) ?: return@withLock
        if (live.isEmpty() && lastLiveChannels.isNotEmpty()) return@withLock
        applySnapshotLocked(live, base, token)
    }

    private suspend fun applySnapshot(live: List<JellyfinLiveTvChannel>, base: String, token: String) =
        nowNextMutex.withLock { applySnapshotLocked(live, base, token) }

    /**
     * Takes [live] as the snapshot and works out `has_guide` for each channel: true with a programme on now, otherwise true
     * when Jellyfin lists one starting within [GUIDE_REACH_MS] (a gap, or a station off air overnight). A Programs call that
     * is not answered leaves the channel's flag as it was.
     */
    private suspend fun applySnapshotLocked(live: List<JellyfinLiveTvChannel>, base: String, token: String) {
        val now = clock()
        val flags = HashMap<String, Boolean>()
        val bare = ArrayList<String>()
        for (ch in live) {
            val end = ch.currentProgram?.let { p -> p.endDate?.let(::isoToEpochMs) ?: p.startDate?.let(::isoToEpochMs) }
            if (end != null && end > now) flags[ch.id] = true else bare += ch.id
        }
        if (bare.isNotEmpty()) {
            val listed = jellyfinClient.getLiveTvProgramsOrNull(base, token, bare, utcIsoMinusHours(0), utcIsoPlusDays(1))
            for (id in bare) {
                flags[id] = if (listed == null) guideFlags[id] ?: false
                else listed.any {
                    val start = it.startDate?.let(::isoToEpochMs) ?: return@any false
                    it.channelId == id && (it.endDate?.let(::isoToEpochMs) ?: start) > now && start < now + GUIDE_REACH_MS
                }
            }
        }
        lastLiveChannels = live
        guideFlags = flags
        snapshotAt = now
    }

    private fun kindOf(ch: JellyfinLiveTvChannel): String = if (ch.channelType.equals("Radio", ignoreCase = true)) "radio" else "tv"

    /** Only the shown+available channels [device]'s viewer may watch, for the TV-facing Home "On now" row / player
     *  channel bar (R177 §B/D1) — those need no separate guide fetch (addendum C, embedded CurrentProgram). */
    suspend fun homeChannels(device: DeviceData, radio: Boolean = false, kind: String? = null): List<LiveTvChannel> {
        val visible = visibleChannelIds(device)
        // DEB-2655 (FR-2) — a station is sent only to a client that asked (`radio=1`); `kind=` narrows what was asked for.
        return freshLineup().filter {
            it.shown && !it.unavailable && it.channelId in visible && (radio || it.kind != "radio") && (kind == null || it.kind == kind)
        }
    }

    /**
     * DEB-2520 (restores R177: *restricted viewers see only permitted channels*) — the channel ids Jellyfin lists for
     * [device]'s account, asked with the server key and `userId=` so Jellyfin's own policy (tags, folders, channels)
     * decides. Cached per user for [VIEWER_CHANNELS_TTL_MS] and dropped when the device's lists change. Fails closed:
     * when Jellyfin cannot be asked, the last answer under the same policy stands, and with none the viewer has no
     * channels. A failed answer is never cached.
     */
    suspend fun visibleChannelIds(device: DeviceData): Set<String> {
        val userId = device.jellyfinUserId
        if (userId.isBlank()) return emptySet()
        val policyHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()
        val cached = viewerChannels[userId]?.takeIf { it.policyHash == policyHash }
        if (cached != null && now - cached.fetchedAt < VIEWER_CHANNELS_TTL_MS) return cached.ids
        val base = jellyfinBase(); val token = jellyfinToken()
        val listed = if (base.isBlank() || token.isBlank()) null else jellyfinClient.getLiveTvChannelsOrNull(base, token, userId)
        if (listed == null) return cached?.ids ?: emptySet()
        val ids = listed.mapTo(HashSet()) { it.id }
        viewerChannels[userId] = ViewerChannels(ids, policyHash, now)
        return ids
    }

    fun updateChannel(
        channelId: String,
        shown: Boolean? = null,
        number: Int? = null,
        category: String? = null,
        logoOverrideUrl: String? = null,
        displayName: String? = null,
        kind: String? = null,
    ): Boolean {
        if (store.override(channelId) == null) return false
        require(kind == null || kind in CHANNEL_KINDS) { "unknown kind" }
        store.upsertOverride(channelId) { ov ->
            ov.copy(
                shown = shown ?: ov.shown,
                number = number ?: ov.number,
                category = category ?: ov.category,
                logoOverrideUrl = logoOverrideUrl ?: ov.logoOverrideUrl,
                // "" (the admin clearing the input) is intentionally stored as-is, not skipped — see
                // lineup()'s isNotBlank() fallback, which is what actually makes it "revert to Jellyfin".
                displayName = displayName ?: ov.displayName,
                kind = kind ?: ov.kind,
                kindSet = ov.kindSet || kind != null, // DEB-3039 — the operator's choice is kept across syncs
                isNew = false, // any explicit edit acknowledges the "new" badge
            )
        }
        eventBus?.notifyGlobalConfigChanged()
        return true
    }

    fun reorder(orderedChannelIds: List<String>) {
        val cur = store.overridesByChannelId()
        val next = orderedChannelIds.mapIndexedNotNull { i, id -> cur[id]?.copy(order = i, number = i + 1) }
        // Preserve any entries the caller's list omitted (defensive — shouldn't happen from the admin UI).
        val missing = cur.values.filter { it.channelId !in orderedChannelIds }
        store.replaceAll(next + missing)
        eventBus?.notifyGlobalConfigChanged()
    }

    /** §C2 "Show new" bulk action — clears the new badge and shows every currently-new channel. */
    fun bulkShowNew() {
        val next = store.overridesByChannelId().values.map { if (it.isNew) it.copy(shown = true, isNew = false) else it }
        store.replaceAll(next)
        eventBus?.notifyGlobalConfigChanged()
    }

    /** §C2 "Remove missing" bulk action — drops every unavailable override from the layout entirely. */
    fun bulkRemoveMissing() {
        val next = store.overridesByChannelId().values.filterNot { it.unavailable }
        store.replaceAll(next)
        eventBus?.notifyGlobalConfigChanged()
    }

    fun updateSettings(enabled: Boolean? = null, epgCadenceMinutes: Int? = null) {
        store.updateSettings {
            it.copy(
                enabled = enabled ?: it.enabled,
                epgCadenceMinutes = (epgCadenceMinutes ?: it.epgCadenceMinutes).coerceIn(15, 1440),
            )
        }
        eventBus?.notifyGlobalConfigChanged()
    }

    /**
     * Full-schedule guide grid (R177 §C) for [days] ahead, cached at the configured cadence (addendum F
     * — dropped the custom-XMLTV source; cadence-only against Jellyfin's own guide). "On now"/next never
     * calls this — see [homeChannels]'s embedded CurrentProgram (addendum C).
     *
     * [hoursBack] (user request): the guide used to only ever query from "now" forward, so scrolling left
     * never revealed anything — the floor is now `now - hoursBack`. Whether that actually returns anything
     * depends on the Jellyfin server's own EPG source retaining recently-elapsed programs; if it doesn't,
     * this floor is harmless (Jellyfin just returns nothing before its own retention edge).
     */
    suspend fun guide(device: DeviceData, days: Int = 7, hoursBack: Int = 0, radio: Boolean = false): List<LiveTvGuideProgram> {
        // DEB-2520 — the programme data is one shared fetch; what this viewer is handed is cut to their channels.
        val visible = visibleChannelIds(device)
        // DEB-2655 (FR-2) — without `radio=1` a station's programmes are not there either.
        val stations = if (radio) emptySet() else store.overridesByChannelId().values.filter { it.kind == "radio" }.mapTo(HashSet()) { it.channelId }
        return sharedGuide(days, hoursBack).filter { it.channelId in visible && it.channelId !in stations }
    }

    private suspend fun sharedGuide(days: Int, hoursBack: Int): List<LiveTvGuideProgram> {
        val cadenceMs = store.settings().epgCadenceMinutes.coerceAtLeast(1) * 60_000L
        val now = nowMs()
        if (guideCache.isEmpty() || now - guideCacheFetchedAt > cadenceMs) {
            val base = jellyfinBase(); val token = jellyfinToken()
            val channelIds = store.overridesByChannelId().values.filter { it.shown && !it.unavailable }.map { it.channelId }
            if (base.isNotBlank() && token.isNotBlank() && channelIds.isNotEmpty()) {
                val programs = jellyfinClient.getLiveTvPrograms(base, token, channelIds, utcIsoMinusHours(hoursBack), utcIsoPlusDays(days))
                guideCache = programs.mapNotNull { p ->
                    val chId = p.channelId ?: return@mapNotNull null
                    val start = p.startDate?.let(::isoToEpochMs) ?: return@mapNotNull null
                    val end = p.endDate?.let(::isoToEpochMs) ?: start
                    LiveTvGuideProgram(chId, p.name, start, end, p.isSeries)
                }
                guideCacheFetchedAt = now
            }
        }
        return guideCache
    }

    // ── Tune / stop — a sibling to PlaybackService.startPlayback, not a branch of it (addendum D):
    // live channels have no resume position and require Jellyfin's explicit Open/Close handshake. ──

    /**
     * DEB-2520 — null for a channel Jellyfin does not list for this viewer (the route answers 404; the check is here
     * too so no other caller can tune past it). The token that goes into the ticket is the device's own, from
     * [tvTokenForClient]: a rejected one throws [JellyfinReauthRequiredException] (the 409 *re-pair* playback gives),
     * and the server key is never the fallback — it is not a thing a device is handed (2026-08-02 review, H2).
     */
    suspend fun tune(device: DeviceData, channelId: String, capabilities: ClientCapabilities, via: String? = null): LiveTvStreamTicket? {
        if (channelId !in visibleChannelIds(device)) return null
        val base = jellyfinBase()
        val token = jellyfinClient.tvTokenForClient(base, device)
            ?: throw JellyfinReauthRequiredException("This device's Jellyfin sign-in has expired — re-pair it to continue watching.")
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val channelKind = kindOfChannel(channelId)
        val radio = channelKind == "radio"
        // R379 — a channel's AC-3 on a device without a platform decoder is converted, never copied (the track's codec
        // isn't known before tuning, so only the list narrows).
        val liveCaps = forPlatformAudio(capabilities, null)
        val info = jellyfinClient.getPlaybackInfo(base, token, device.jellyfinUserId, channelId, capabilities = liveCaps, identity = identity, mediaSourceId = null)
        val source = info?.mediaSources?.firstOrNull { !it.openToken.isNullOrBlank() } ?: info?.mediaSources?.firstOrNull()
        val openToken = source?.openToken
        if (openToken.isNullOrBlank()) {
            Logger.warn("LiveTv: no OpenToken for channel=$channelId — cannot tune", "livetv")
            return null
        }
        val opened = jellyfinClient.openLiveStream(base, token, device.jellyfinUserId, openToken, channelId, info?.playSessionId, liveCaps, identity) ?: return null
        val mediaSource = opened.mediaSource ?: return null
        val streamUrl = mediaSource.path ?: return null
        val liveStreamId = mediaSource.liveStreamId ?: opened.id ?: return null
        if (via == VIA_JELLYFIN) {
            // DEB-2627 FR-4 — the source failed for the client: hand back what Jellyfin remuxes, verbatim (FR-239-5: its
            // credential spelling is Jellyfin's; the open ran under the device's own token, so that is the token in it).
            val transcoding = mediaSource.transcodingUrl?.takeIf { it.isNotBlank() }
            if (transcoding == null) {
                Logger.warn("LiveTv: Jellyfin offered no TranscodingUrl for channel=$channelId — no fallback to hand over", "livetv")
                jellyfinClient.closeLiveStream(base, token, liveStreamId, identity)
                return null
            }
            register(device, channelId, liveStreamId, DELIVERY_JELLYFIN, channelKind = channelKind)
            return LiveTvStreamTicket(
                jellyfinBaseUrl = base,
                accessToken = token,
                channelId = channelId,
                liveStreamId = liveStreamId,
                hlsUrl = if (transcoding.startsWith("http")) transcoding else "$base$transcoding",
                expiresAt = nowMs() + TICKET_TTL_MS,
                streamKind = STREAM_KIND_HLS,
                channelKind = channelKind,
            )
        }
        val absolute = if (streamUrl.startsWith("http", ignoreCase = true)) streamUrl else "$base$streamUrl"
        // A station's Jellyfin-local stream is probed too (with the device's own token, to Jellyfin only): the relay needs its type.
        val look = classifySource(channelId, if (radio) absolute else streamUrl, bearer = token.takeIf { absolute.startsWith(base, ignoreCase = true) })
        val sourceClass = look.classification
        // Only an audio answer is a type the relay can carry; a playlist or a page leaves it null, and the relay then answers 502.
        val audioType = look.contentType?.takeIf { isRelayableAudio(it) }
        if (radio && !(sourceClass.kind != STREAM_KIND_OTHER && absolute.startsWith("https://", ignoreCase = true))) {
            // DEB-2628 FR-4 — anything but an HLS station on https plays through our relay: one credential of its own, minted per tune.
            val relay = RelayState(
                id = secureHexId(), upstreamUrl = absolute, jellyfinBase = base,
                bearer = token.takeIf { absolute.startsWith(base, ignoreCase = true) }, contentType = audioType,
            )
            register(device, channelId, liveStreamId, DELIVERY_SOURCE, channelKind = channelKind, relay = relay, format = radioFormatLabel(audioType, look.bitrateKbps))
            return LiveTvStreamTicket(
                jellyfinBaseUrl = base,
                accessToken = token,
                channelId = channelId,
                liveStreamId = liveStreamId,
                hlsUrl = "$RADIO_RELAY_PATH${relay.id}",
                expiresAt = clock() + TICKET_TTL_MS,
                streamKind = STREAM_KIND_AUDIO,
                contentType = audioType,
                channelKind = channelKind,
            )
        }
        // A relative Path is Jellyfin's own stream; an absolute one is the broadcaster's (FR-1) — what the client then plays.
        register(device, channelId, liveStreamId, if (streamUrl.startsWith("http")) DELIVERY_SOURCE else DELIVERY_JELLYFIN, channelKind = channelKind)
        return LiveTvStreamTicket(
            jellyfinBaseUrl = base,
            accessToken = token,
            channelId = channelId,
            liveStreamId = liveStreamId,
            hlsUrl = if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl",
            expiresAt = nowMs() + TICKET_TTL_MS,
            streamKind = sourceClass.kind,
            ladder = sourceClass.ladder,
            channelKind = channelKind,
        )
    }

    /** DEB-2628 FR-1 — the channel's stored kind (`tv` or `radio`); a channel the store does not know is a TV channel. */
    private fun kindOfChannel(channelId: String): String = store.overridesByChannelId()[channelId]?.kind ?: "tv"

    /**
     * DEB-2627 FR-1 — what [path] (the open's `MediaSource.Path`) is. A relative path is Jellyfin's own stream: `other`,
     * nothing fetched. An absolute one is looked at once an hour per channel; [hlsUrl][LiveTvStreamTicket.hlsUrl] stays
     * [path] whatever this finds, because the client fetches the master itself.
     */
    private suspend fun classifySource(channelId: String, path: String, bearer: String? = null): SourceLook {
        if (!path.startsWith("http://", ignoreCase = true) && !path.startsWith("https://", ignoreCase = true)) return SourceLook(SourceClassification(STREAM_KIND_OTHER))
        val key = "$channelId $path"
        val now = nowMs()
        sourceClasses[key]?.takeIf { it.until > now }?.let { return it.look }
        val head = fetchPlaylistHead(path, bearer)
        val look = when {
            head == null -> SourceLook(classifyUnfetched(path))
            // An audio answer is never an HLS playlist, and its body is a stream: it was not read (DEB-2656), so it is `other`.
            head.text == null -> SourceLook(SourceClassification(STREAM_KIND_OTHER), head.contentType, head.bitrateKbps)
            else -> SourceLook(classifyHlsPlaylist(head.text) ?: SourceClassification(STREAM_KIND_OTHER), head.contentType, head.bitrateKbps)
        }
        sourceClasses.removeIf { _, v -> v.until <= now }
        if (sourceClasses.size >= SOURCE_CLASS_MAX_ENTRIES) sourceClasses.clear()
        sourceClasses[key] = SourceClass(look, now + if (head == null) SOURCE_CLASS_FAILED_TTL_MS else SOURCE_CLASS_TTL_MS)
        return look
    }

    /** What [classifySource] found: the DEB-2627 classification, and (DEB-2656) the answer's `Content-Type` and `icy-br` when it gave them. */
    private data class SourceLook(val classification: SourceClassification, val contentType: String? = null, val bitrateKbps: Int? = null)

    /** A source's first answer: [text] is the playlist head, null when the answer is audio (its body is not read). */
    private data class SourceHead(val text: String?, val contentType: String?, val bitrateKbps: Int?)

    private sealed interface PlaylistStep {
        data class Redirect(val url: String) : PlaylistStep
        data class Body(val head: SourceHead) : PlaylistStep
        data object Failed : PlaylistStep
    }

    /** The first [SOURCE_FETCH_MAX_BYTES] of [url] after at most [SOURCE_MAX_REDIRECTS] redirects, all inside [SOURCE_FETCH_TIMEOUT_MS]; null on any failure. */
    private suspend fun fetchPlaylistHead(url: String, bearer: String? = null): SourceHead? = boundedOrNull(SOURCE_FETCH_TIMEOUT_MS, "live-tv source master fetch") {
        try {
            OutboundHttp.withPermit {
                var current = url
                for (hop in 0..SOURCE_MAX_REDIRECTS) {
                    val at = current
                    val step = sourceHttp.prepareGet(at) {
                        header(HttpHeaders.Accept, "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
                        if (bearer != null && at.startsWith(jellyfinBase(), ignoreCase = true)) jellyfinAuth(bearer)
                    }.execute { resp ->
                        when {
                            resp.status.value in 300..399 ->
                                resp.headers[HttpHeaders.Location]
                                    ?.let { URLBuilder(current).takeFrom(it).buildString() }
                                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                                    ?.let { PlaylistStep.Redirect(it) } ?: PlaylistStep.Failed
                            resp.status.isSuccess() && isRelayableAudio(resp.headers[HttpHeaders.ContentType]) -> {
                                // DEB-2656 — a station's stream never ends: its headers are the whole look. DEB-3051 (FR-4) — leaving the
                                // lambda with the body unread hangs the call inside a handler until the 3 s bound, and the look was then lost
                                // (a null `content_type` on the ticket): take the look first, then drain a little, itself bounded to 1 s.
                                val look = SourceHead(null, resp.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim(), resp.headers["icy-br"]?.substringBefore(',')?.trim()?.toIntOrNull())
                                boundedOrNull(SOURCE_AUDIO_DRAIN_MS, "live-tv source audio drain") { runCatching { resp.bodyAsChannel().discard(SOURCE_AUDIO_DRAIN_BYTES) } }
                                PlaylistStep.Body(look)
                            }
                            resp.status.isSuccess() -> {
                                val contentType = resp.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                                val bytes = resp.bodyAsChannel().readRemaining(SOURCE_FETCH_MAX_BYTES.toLong()).readByteArray()
                                val text = bytes.decodeToString()
                                // A cut-off body ends mid-line: drop that line rather than read half an attribute.
                                PlaylistStep.Body(SourceHead(if (bytes.size >= SOURCE_FETCH_MAX_BYTES) text.substringBeforeLast('\n') else text, contentType, null))
                            }
                            else -> PlaylistStep.Failed
                        }
                    }
                    when (step) {
                        is PlaylistStep.Body -> return@withPermit step.head
                        is PlaylistStep.Redirect -> current = step.url
                        PlaylistStep.Failed -> return@withPermit null
                    }
                }
                null // redirected more than SOURCE_MAX_REDIRECTS times
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.info("LiveTv: could not read a source's master (${e.message}) — classified from its URL", "livetv")
            null
        }
    }

    /** Puts [device]'s stream in the table (a tune replaces whatever it held) and tells the admin. */
    private fun register(
        device: DeviceData, channelId: String, liveStreamId: String, delivery: String, variantBps: Long? = null, height: Int? = null,
        channelKind: String = "tv", relay: RelayState? = null, format: String? = null,
    ) {
        val now = clock()
        activeStreams[device.deviceId] = ActiveLive(device, channelId, liveStreamId, delivery, startedMs = now, heartbeatMs = now, variantBps = variantBps, height = height, channelKind = channelKind, relay = relay, format = format)
        onLiveChanged?.invoke()
    }

    /**
     * DEB-2627 FR-5 — a heartbeat. Body-less (an installed client) it only keeps the stream alive. With [report] it also
     * records the rung on screen and the delivery. A device the backend holds no stream for, whose [report] names a
     * channel and a live stream (the backend restarted under a viewer), is registered again so the row survives and the
     * watchdog does not close it — but only for a channel Jellyfin lists for that viewer, so a heartbeat can never put a
     * stream in the table that a tune could not have opened.
     */
    suspend fun heartbeat(device: DeviceData, report: LiveTvHeartbeatRequest? = null) {
        val held = activeStreams[device.deviceId]
        if (held == null) {
            val channelId = report?.channelId?.takeIf { it.isNotBlank() } ?: return
            val liveStreamId = report.liveStreamId?.takeIf { it.isNotBlank() } ?: return
            if (channelId !in visibleChannelIds(device)) return
            register(device, channelId, liveStreamId, deliveryOf(report.delivery), report.variantBandwidthBps?.takeIf { it > 0 }, report.height?.takeIf { it > 0 }, channelKind = kindOfChannel(channelId))
            return
        }
        // A heartbeat that names another stream than the one held belongs to a tune that was since replaced: it keeps nothing alive.
        if (report?.liveStreamId?.isNotBlank() == true && report.liveStreamId != held.liveStreamId) return
        var changed = false
        activeStreams.update(device.deviceId) { cur ->
            cur?.let {
                val next = if (report == null) it else it.copy(
                    delivery = report.delivery?.let(::deliveryOf) ?: it.delivery,
                    variantBps = report.variantBandwidthBps?.takeIf { b -> b > 0 } ?: it.variantBps,
                    height = report.height?.takeIf { h -> h > 0 } ?: it.height,
                )
                changed = next.delivery != it.delivery || next.variantBps != it.variantBps || next.height != it.height
                next.copy(heartbeatMs = clock())
            }
        }
        if (changed) onLiveChanged?.invoke()
    }

    private fun deliveryOf(raw: String?): String = if (raw == DELIVERY_JELLYFIN) DELIVERY_JELLYFIN else DELIVERY_SOURCE

    /**
     * DEB-2627 FR-5 — each open live stream as a row of the admin's playback sessions list (`kind = "live"`): the viewer,
     * the channel and the programme on now, the device, and the delivery and rung in the fields the list already draws
     * for a film's variant. Position, duration and the timeline are empty — a channel has none.
     */
    fun adminRows(index: Map<String, String> = emptyMap()): List<AdminSessionRow> {
        val now = clock()
        val channels = lineup().associateBy { it.channelId }
        return activeStreams.snapshot().values.sortedBy { it.startedMs }.map { a ->
            val ch = channels[a.channelId]
            val program = ch?.currentProgram?.takeIf { it.startMs <= now && now < it.endMs }?.name
            AdminSessionRow(
                id = "live:${a.device.deviceId}", revision = a.heartbeatMs, ownerId = a.device.jellyfinUserId,
                ownerName = a.device.jellyfinUsername.ifBlank { a.device.jellyfinUserId }, kind = SESSION_KIND_LIVE,
                title = ch?.name ?: "Live channel", subtitle = program,
                targetKind = "device", targetId = a.device.deviceId,
                targetName = index[a.device.deviceId] ?: a.device.displayName.ifBlank { a.device.deviceId },
                targetIcon = targetIcon(a.device.platform, a.device.kind),
                state = "playing", createdAt = a.startedMs, updatedAt = a.heartbeatMs,
                variantBps = a.variantBps, variantHeight = a.height,
                // DEB-2628 FR-5 — a station through our relay reads *relayed*; a speaker's own tune is its own row, named by the device.
                encoder = if (a.relay != null) "relayed" else a.delivery,
                channelKind = a.channelKind, format = a.format,
            )
        }
    }

    suspend fun stopTune(device: DeviceData, liveStreamId: String) {
        // A stop of a stream the device no longer holds (it already tuned another) leaves the new row where it is. The row
        // goes before the token is asked for: a token that cannot be had must not keep a closed stream on the admin's list.
        val held = activeStreams[device.deviceId]
        if (held == null || held.liveStreamId == liveStreamId) {
            activeStreams.remove(device.deviceId)
            onLiveChanged?.invoke()
        }
        val base = jellyfinBase()
        val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        jellyfinClient.closeLiveStream(base, token, liveStreamId, JellyfinDeviceIdentity.forDevice(device))
    }

    /** Mirrors PlaybackService.stopWatchdogTick for the live-stream lifecycle — a disconnect or stale
     *  heartbeat force-closes the tuner/provider stream instead of leaking it open indefinitely. */
    suspend fun stopWatchdogTick(isDeviceConnected: suspend (String) -> Boolean) {
        val now = clock()
        val stale = buildList {
            for ((deviceId, a) in activeStreams.snapshot()) {
                val relay = a.relay
                // DEB-2628 FR-5 — a tune with a relay connection open is being listened to, whoever last spoke for it: a phone
                // that sleeps while a speaker plays must not cut the speaker off.
                if (relay != null && relay.open > 0) continue
                // A relayed tune whose last connection ended closes RADIO_RELAY_IDLE_STOP_MS after that, or after its last heartbeat.
                val heartbeatStale = if (relay != null) now - maxOf(a.heartbeatMs, relay.endedMs) > RADIO_RELAY_IDLE_STOP_MS else now - a.heartbeatMs > STOP_WATCHDOG_MS
                if (heartbeatStale || !isDeviceConnected(deviceId)) add(a)
            }
        }
        for (a in stale) {
            Logger.info("LiveTv stop watchdog: force-closing stale stream device=${a.device.deviceId}", "livetv")
            runCatching { stopTune(a.device, a.liveStreamId) }
        }
    }

    // ── Radio relay (DEB-2628 FR-4, FR-5): the credential, the limits and the lifetime. The wire is RadioRelay.kt. ──

    /**
     * Admits one connection on [relayId]: [RelayOpen.NotFound] for an id no active tune holds (stopped, re-tuned, closed by the
     * watchdog, or never minted), [RelayOpen.Busy] over [RADIO_RELAY_MAX_PER_ID] on it or [RADIO_RELAY_MAX_TOTAL] on the server.
     * Every active tune's id is compared, none stopping at the first match, so the time taken does not say how much of an id was right.
     */
    internal fun relayOpen(relayId: String): RelayOpen {
        var match: ActiveLive? = null
        for (a in activeStreams.snapshot().values) {
            val r = a.relay ?: continue
            if (constantTimeEquals(r.id, relayId)) match = a
        }
        val found = match ?: return RelayOpen.NotFound
        var result: RelayOpen = RelayOpen.NotFound
        activeStreams.update(found.device.deviceId) { cur ->
            val r = cur?.relay
            when {
                cur == null || r == null || cur.liveStreamId != found.liveStreamId || !constantTimeEquals(r.id, relayId) -> cur
                r.open >= RADIO_RELAY_MAX_PER_ID || !reserveRelaySlot() -> { result = RelayOpen.Busy; cur }
                else -> {
                    result = RelayOpen.Granted(RelayLease(cur.device.deviceId, cur.liveStreamId, r.id, r.upstreamUrl, r.jellyfinBase, r.bearer))
                    cur.copy(relay = r.copy(open = r.open + 1))
                }
            }
        }
        return result
    }

    private fun reserveRelaySlot(): Boolean {
        while (true) {
            val n = relayTotal.value
            if (n >= RADIO_RELAY_MAX_TOTAL) return false
            if (relayTotal.compareAndSet(n, n + 1)) return true
        }
    }

    /** Gives back a [relayOpen] grant, however its connection ended; the watchdog's 60 s start from here. */
    internal fun relayRelease(lease: RelayLease) {
        relayTotal.decrementAndGet()
        activeStreams.update(lease.deviceId) { cur ->
            val r = cur?.relay
            if (cur != null && r != null && cur.liveStreamId == lease.liveStreamId && r.id == lease.relayId) cur.copy(relay = r.copy(open = (r.open - 1).coerceAtLeast(0), endedMs = clock())) else cur
        }
    }

    /** How many connections are open on [relayId] now; 0 for an id no tune holds. */
    internal fun relayOpenCount(relayId: String): Int = activeStreams.snapshot().values.firstOrNull { it.relay?.id == relayId }?.relay?.open ?: 0

    /** Is [lease]'s tune still the one its device holds? A stop, a re-tune or the watchdog make it false, and the connection ends. */
    internal fun relayStillLive(lease: RelayLease): Boolean {
        val cur = activeStreams[lease.deviceId] ?: return false
        return cur.liveStreamId == lease.liveStreamId && cur.relay?.id == lease.relayId
    }

    // ── Channel logo proxy (Jellyfin ImageTags.Primary — R133 doesn't cover Live TV; addendum E) ──────

    suspend fun serveLogo(channelId: String): Pair<ByteArray, String>? {
        // Security fix (2026-08-02 review, finding C2) — same auth-exempt-route + pre-network-call
        // cache-read shape as RaviloArtworkService.serveAvatar; without this guard a channelId of
        // "..%2F..%2Fconfig.toml" traverses out of the cache directory with zero authentication.
        // Same guard ChannelLogoStore.read already uses correctly.
        if (".." in channelId || "/" in channelId || "\\" in channelId) return null
        val cachePath = "$logoDir/$channelId"
        val ctPath = "$cachePath.ct"
        readCached(cachePath, ctPath)?.let { return it }
        return OutboundHttp.withPermit {
            readCached(cachePath, ctPath)?.let { return@withPermit it }
            val base = jellyfinBase(); val token = jellyfinToken()
            if (base.isBlank() || token.isBlank()) return@withPermit null
            // Phase 239 (FR-239-1) — header, not query parameter. Same reasoning as the avatar fetch
            // in RaviloArtworkService: this is jellystructure fetching bytes for itself.
            val resp = runCatching { http.get("$base/Items/$channelId/Images/Primary?quality=90") { jellyfinAuth(token) } }
                .getOrElse { Logger.warn("LiveTv: logo fetch failed $channelId — ${it.message}", "livetv"); return@withPermit null }
            val bytes = runCatching { resp.readRawBytes() }.getOrNull() ?: return@withPermit null
            val ct = resp.contentType()?.toString() ?: "image/png"
            if (resp.status.value !in 200..299 || bytes.isEmpty() || !ct.startsWith("image/")) return@withPermit null
            atomicWrite(cachePath, bytes)
            atomicWrite(ctPath, ct.encodeToByteArray())
            Pair(bytes, ct)
        }
    }

    private fun readCached(cachePath: String, ctPath: String): Pair<ByteArray, String>? {
        if (!SystemFileSystem.exists(Path(cachePath))) return null
        val bytes = runCatching { FileIo.readBytes(Path(cachePath)) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val ct = runCatching { FileIo.readBytes(Path(ctPath)).decodeToString() }.getOrDefault("image/png")
        return Pair(bytes, ct)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun atomicWrite(destPath: String, bytes: ByteArray) {
        val tmp = "$destPath.tmp"
        val result = runCatching {
            SystemFileSystem.sink(Path(tmp)).buffered().use { sink -> sink.write(bytes, 0, bytes.size) }
            platform.posix.rename(tmp, destPath)
        }
        if (result.isFailure) Logger.warn("LiveTv: logo cache write failed $destPath — ${result.exceptionOrNull()?.message}", "livetv")
    }
}

private const val STOP_WATCHDOG_MS = 90_000L
/** DEB-3066 — how far ahead a programme still counts as the channel having a guide. */
private const val GUIDE_REACH_MS = 24 * 3_600_000L
/** DEB-3066 — a channel in a gap is looked at again once the snapshot is this old. */
private const val GAP_REREAD_MS = 60_000L
/** DEB-3066 — never more often than this, whatever triggered it. */
private const val REREAD_MIN_GAP_MS = 5_000L
/** DEB-2628 FR-5 — a relayed tune with no connection open and no heartbeat closes this long after the last of either. */
internal const val RADIO_RELAY_IDLE_STOP_MS = 60_000L

private fun isoToEpochMs(iso: String): Long? = isoToEpochSeconds(iso)?.times(1000L)

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
private fun utcIsoPlusDays(days: Int): String = memScoped {
    val t = alloc<time_tVar>(); time(t.ptr)
    t.value = (t.value.convert<Long>() + days.toLong() * 86_400L).convert()
    val tmv = alloc<tm>(); gmtime_r(t.ptr, tmv.ptr)
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32.convert(), "%Y-%m-%dT%H:%M:%S.000Z", tmv.ptr)
    buf.toKString()
}

/** User request: the guide should scroll 4h into the past as well as forward — [hours] is subtracted
 *  from now to become the query's MinStartDate floor (was always "now", so there was no past window). */
@OptIn(ExperimentalForeignApi::class)
private fun utcIsoMinusHours(hours: Int): String = memScoped {
    val t = alloc<time_tVar>(); time(t.ptr)
    t.value = (t.value.convert<Long>() - hours.toLong() * 3_600L).convert()
    val tmv = alloc<tm>(); gmtime_r(t.ptr, tmv.ptr)
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32.convert(), "%Y-%m-%dT%H:%M:%S.000Z", tmv.ptr)
    buf.toKString()
}
