package dev.jellystructure.tv

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.JellyfinLiveTvChannel
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import dev.jellystructure.shared.tv.LiveTvOverview
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import dev.jellystructure.util.isoToEpochSeconds
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
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

    private var guideCache: List<LiveTvGuideProgram> = emptyList()
    private var guideCacheFetchedAt: Long = 0L

    // Live-stream watchdog — separate from PlaybackService's VOD tracking (dev-review addendum D: a
    // live tune is a sibling flow, not a branch of startPlayback). Key = deviceId.
    private val activeStreams = HashMap<String, Triple<DeviceData, String, String>>() // -> (device, channelId, liveStreamId)
    private val lastHeartbeatMs = HashMap<String, Long>()

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
        lastLiveChannels = live
        val liveIds = live.associateBy { it.id }
        val existing = store.overridesByChannelId()
        var nextOrder = (existing.values.maxOfOrNull { it.order } ?: -1) + 1

        val merged = buildList {
            for ((id, ov) in existing) {
                val liveCh = liveIds[id]
                add(if (liveCh != null) ov.copy(unavailable = false) else ov.copy(unavailable = true))
            }
            for (ch in live) {
                if (ch.id in existing) continue
                add(
                    LiveTvChannelOverride(
                        channelId = ch.id, shown = false, number = nextOrder + 1, order = nextOrder,
                        category = "", unavailable = false, isNew = true,
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
        val liveById = lastLiveChannels.associateBy { it.id }
        return store.overridesByChannelId().values.sortedBy { it.order }.map { ov ->
            val live = liveById[ov.channelId]
            val name = live?.name?.takeIf { it.isNotBlank() } ?: ov.channelId
            // Public path (like R133's channel-logos/image-proxy) — the TV client can't attach a device
            // token to an <img>/Coil image request, and logos aren't sensitive.
            val logo = ov.logoOverrideUrl
                ?: live?.imageTags?.primary?.let { "/api/tv/livetv/logo/${ov.channelId}" }
            val current = live?.currentProgram?.let { p ->
                val start = p.startDate?.let(::isoToEpochMs) ?: return@let null
                val end = p.endDate?.let(::isoToEpochMs) ?: start
                LiveTvProgramInfo(p.name, start, end, p.isSeries)
            }
            LiveTvChannel(
                channelId = ov.channelId,
                name = name,
                number = ov.number,
                order = ov.order,
                shown = ov.shown,
                logoUrl = logo,
                category = ov.category,
                hasGuide = live?.currentProgram != null,
                isNew = ov.isNew,
                unavailable = ov.unavailable,
                currentProgram = current,
                nextProgram = null,
            )
        }
    }

    /** Only the shown+available channels, for the TV-facing Home "On now" row / player channel bar
     *  (R177 §B/D1) — those need no separate guide fetch (addendum C, embedded CurrentProgram). */
    fun homeChannels(): List<LiveTvChannel> = lineup().filter { it.shown && !it.unavailable }

    fun updateChannel(
        channelId: String,
        shown: Boolean? = null,
        number: Int? = null,
        category: String? = null,
        logoOverrideUrl: String? = null,
    ): Boolean {
        if (store.override(channelId) == null) return false
        store.upsertOverride(channelId) { ov ->
            ov.copy(
                shown = shown ?: ov.shown,
                number = number ?: ov.number,
                category = category ?: ov.category,
                logoOverrideUrl = logoOverrideUrl ?: ov.logoOverrideUrl,
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
     */
    suspend fun guide(days: Int = 7): List<LiveTvGuideProgram> {
        val cadenceMs = store.settings().epgCadenceMinutes.coerceAtLeast(1) * 60_000L
        val now = nowMs()
        if (guideCache.isEmpty() || now - guideCacheFetchedAt > cadenceMs) {
            val base = jellyfinBase(); val token = jellyfinToken()
            val channelIds = store.overridesByChannelId().values.filter { it.shown && !it.unavailable }.map { it.channelId }
            if (base.isNotBlank() && token.isNotBlank() && channelIds.isNotEmpty()) {
                val programs = jellyfinClient.getLiveTvPrograms(base, token, channelIds, utcIsoNow(), utcIsoPlusDays(days))
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

    suspend fun tune(device: DeviceData, channelId: String, capabilities: ClientCapabilities): LiveTvStreamTicket? {
        val base = jellyfinBase(); val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        val identity = JellyfinDeviceIdentity.forDevice(device)
        val info = jellyfinClient.getPlaybackInfo(base, token, device.jellyfinUserId, channelId, capabilities = capabilities, identity = identity)
        val source = info?.mediaSources?.firstOrNull { !it.openToken.isNullOrBlank() } ?: info?.mediaSources?.firstOrNull()
        val openToken = source?.openToken
        if (openToken.isNullOrBlank()) {
            Logger.warn("LiveTv: no OpenToken for channel=$channelId — cannot tune", "livetv")
            return null
        }
        val opened = jellyfinClient.openLiveStream(base, token, device.jellyfinUserId, openToken, capabilities, identity) ?: return null
        val mediaSource = opened.mediaSource ?: return null
        val streamUrl = mediaSource.path ?: return null
        val liveStreamId = mediaSource.liveStreamId ?: opened.id ?: return null
        lastHeartbeatMs[device.deviceId] = nowMs()
        activeStreams[device.deviceId] = Triple(device, channelId, liveStreamId)
        return LiveTvStreamTicket(
            jellyfinBaseUrl = base,
            accessToken = token,
            channelId = channelId,
            liveStreamId = liveStreamId,
            hlsUrl = if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl",
            expiresAt = nowMs() + TICKET_TTL_MS,
        )
    }

    suspend fun heartbeat(device: DeviceData) {
        if (activeStreams.containsKey(device.deviceId)) lastHeartbeatMs[device.deviceId] = nowMs()
    }

    suspend fun stopTune(device: DeviceData, liveStreamId: String) {
        val base = jellyfinBase()
        val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
        lastHeartbeatMs.remove(device.deviceId)
        activeStreams.remove(device.deviceId)
        jellyfinClient.closeLiveStream(base, token, liveStreamId, JellyfinDeviceIdentity.forDevice(device))
    }

    /** Mirrors PlaybackService.stopWatchdogTick for the live-stream lifecycle — a disconnect or stale
     *  heartbeat force-closes the tuner/provider stream instead of leaking it open indefinitely. */
    suspend fun stopWatchdogTick(isDeviceConnected: suspend (String) -> Boolean) {
        val now = nowMs()
        val stale = buildList {
            for ((deviceId, value) in activeStreams.entries) {
                val heartbeatStale = now - (lastHeartbeatMs[deviceId] ?: 0L) > STOP_WATCHDOG_MS
                if (heartbeatStale || !isDeviceConnected(deviceId)) add(value)
            }
        }
        for ((device, _, liveStreamId) in stale) {
            Logger.info("LiveTv stop watchdog: force-closing stale stream device=${device.deviceId}", "livetv")
            runCatching { stopTune(device, liveStreamId) }
        }
    }

    // ── Channel logo proxy (Jellyfin ImageTags.Primary — R133 doesn't cover Live TV; addendum E) ──────

    suspend fun serveLogo(channelId: String): Pair<ByteArray, String>? {
        val cachePath = "$logoDir/$channelId"
        val ctPath = "$cachePath.ct"
        readCached(cachePath, ctPath)?.let { return it }
        return OutboundHttp.withPermit {
            readCached(cachePath, ctPath)?.let { return@withPermit it }
            val base = jellyfinBase(); val token = jellyfinToken()
            if (base.isBlank() || token.isBlank()) return@withPermit null
            val resp = runCatching { http.get("$base/Items/$channelId/Images/Primary?api_key=$token&quality=90") }
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

private fun isoToEpochMs(iso: String): Long? = isoToEpochSeconds(iso)?.times(1000L)

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
private fun utcIsoNow(): String = memScoped {
    val t = alloc<time_tVar>(); time(t.ptr)
    val tmv = alloc<tm>(); gmtime_r(t.ptr, tmv.ptr)
    val buf = allocArray<ByteVar>(32)
    strftime(buf, 32.convert(), "%Y-%m-%dT%H:%M:%S.000Z", tmv.ptr)
    buf.toKString()
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
