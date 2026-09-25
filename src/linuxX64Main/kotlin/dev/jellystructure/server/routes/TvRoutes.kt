package dev.jellystructure.server.routes

import dev.jellystructure.server.respondCachedBytes
import io.ktor.server.plugins.origin
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.ChannelLogoUpload
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.FavoriteRequest
import dev.jellystructure.shared.tv.MarkRequest
import dev.jellystructure.shared.tv.PlayedRequest
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvLoginRequest
import dev.jellystructure.shared.tv.PlaybackProgressRequest
import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.PlaybackRestreamRequest
import dev.jellystructure.shared.tv.PlaybackStartRequest
import dev.jellystructure.shared.tv.PlaybackStopRequest
import dev.jellystructure.shared.tv.SeededBrowseRequest
import dev.jellystructure.shared.tv.TvSession
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.RaviloArtworkService
import dev.jellystructure.tv.RaviloImageUrl
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.TvEventBus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase 143 — Users & Devices admin overview DTOs.
// Phase 256 (FR-256-5) — one tracker for the process: when each device was first seen ≥ 2 releases behind.
private val versionBehind = dev.jellystructure.tv.VersionBehindTracker()

@Serializable
private data class OverviewDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val connected: Boolean,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_kids") val isKids: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("now_playing") val nowPlaying: String? = null,
    // Phase 177 §FR-177-5 — this device's most recent playback-quality report, if any. Null when the
    // device has never posted one (an un-updated client, or simply no playback yet) — never badged in
    // that case, same as a clean (no-issue) report; see QoeSummary.hasIssue.
    @SerialName("recent_quality") val recentQuality: dev.jellystructure.tv.QoeSummary? = null,
    // Phase 185 (FR-185-8) — this device's persisted decode ceiling, for the "what this device can
    // take" second line. Both null ⇒ FR-185-2's "not measured yet" (no Ravilo session since the R216
    // build) — the admin card spells this out in plain words rather than a raw null.
    @SerialName("decode_max_bitrate_hevc") val decodeMaxBitrateHevc: Long? = null,
    @SerialName("decode_max_bitrate_h264") val decodeMaxBitrateH264: Long? = null,
    @SerialName("decode_measured_at") val decodeMeasuredAt: Long? = null,
    // Phase 224 (FR-224-5) — the build and platform this device last reported (R252). Both null ⇒ the
    // device has never said (an un-updated client); the row spells that out.
    @SerialName("app_version") val appVersion: String? = null,
    val platform: String? = null,
    // Phase 256 (FR-256-3) — connects in the last hour, present only while the device is above the
    // 12/h threshold (the server decides; the row renders the line or nothing).
    @SerialName("reconnects_last_hour") val reconnectsLastHour: Int? = null,
    // Phase 256 (FR-256-5) — how many releases behind this backend, present only when ≥ 2 for ≥ 7 days
    // (never for a dev build on either side), with when that was first seen.
    @SerialName("releases_behind") val releasesBehind: Int? = null,
    @SerialName("behind_since") val behindSince: Long? = null,
    // Phase 259 (FR-259-6) — when the current version was first seen (null for a seed row / no history) and
    // every version this device was seen on, newest first. The client computes durations and formatting only.
    @SerialName("version_since") val versionSince: Long? = null,
    val versions: List<OverviewVersion> = emptyList(),
)

/** Phase 259 (FR-259-6) — one row of a device's version history. `observed = false` is the migration's seed. */
@Serializable
private data class OverviewVersion(
    @SerialName("app_version") val appVersion: String,
    @SerialName("first_seen_at") val firstSeenAt: Long,
    val observed: Boolean,
)

/** Phase 259 (FR-259-9) — the page-bar chip: this backend's own version (the latest there is, 256 FR-256-5)
 *  and how many devices run an older release. [release] is false when the server itself runs a dev build,
 *  in which case there is no "latest" to compare against and the chip is hidden. */
@Serializable
private data class RaviloVersionSummary(
    val latest: String,
    val release: Boolean,
    val behind: Int,
    val devices: Int,
)

@Serializable
private data class OverviewSession(
    // Phase 143: NEVER the raw session token (that IS the js_session cookie value — returning it would
    // let any admin viewing this page hijack another admin's live session). A 12-char prefix of a
    // cryptographically random 64-char token leaks ~48 of 256 bits — not enough to reconstruct it —
    // and is resolved back to the full token server-side by the revoke route below.
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
private data class OverviewPolicy(
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("all_folders") val allFolders: Boolean,
    @SerialName("library_count") val libraryCount: Int? = null,  // null when allFolders (unrestricted)
    @SerialName("total_libraries") val totalLibraries: Int = 0,
    @SerialName("allowed_tags") val allowedTags: List<String> = emptyList(),
    @SerialName("blocked_tags") val blockedTags: List<String> = emptyList(),
    @SerialName("max_rating") val maxRating: Int? = null,
    /** Phase 258 (dev review item 2) — false when Jellyfin did not answer `/Users` or this user was not in
     *  the answer; the fields above are then fallbacks, and the page says *policy unknown* instead of
     *  "All libraries". */
    @SerialName("known") val known: Boolean = true,
    /** Phase 258 (FR-258-6) — true when any of this user's device rows still carries a policy that differs
     *  from Jellyfin's live one (a reconcile has not run yet, or has been failing). */
    @SerialName("stale") val stale: Boolean = false,
)

/** Phase 177 §FR-177-5 — one row for the Activity page's "Playback quality" card; device/title are
 *  resolved here (never raw ids) so the frontend renders server-pushed state only. */
@Serializable
private data class QoeActivityRow(
    @SerialName("device_name") val deviceName: String,
    val title: String,
    @SerialName("dropped_frames") val droppedFrames: Int,
    @SerialName("rebuffer_count") val rebufferCount: Int,
    @SerialName("rebuffer_ms") val rebufferMs: Long,
    @SerialName("direct_play") val directPlay: Boolean,
    @SerialName("link_kind") val linkKind: String,
    @SerialName("link_mbps") val linkMbps: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
private data class OverviewUser(
    @SerialName("user_id") val userId: String,
    val username: String,
    val policy: OverviewPolicy,
    val devices: List<OverviewDevice>,
    val sessions: List<OverviewSession>,
    // Phase 187 (FR-187-9) — read-only; the admin gets no ability to set/clear another user's photo.
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

// Phase 143 (design addendum) — "Recently watched" lazy history DTOs. Timestamps are epoch **seconds**
// (straight off Jellyfin's ISO DatePlayed via isoToEpochSeconds) — unlike every other Phase 143
// timestamp above, which is epoch millis (nowMs()-based); the admin frontend must not conflate the two.
@Serializable
private data class WatchHistoryEntry(
    val title: String,
    @SerialName("episode_label") val episodeLabel: String? = null,  // e.g. "S01 · E01–E08"; null for a movie
    @SerialName("episode_count") val episodeCount: Int = 1,
    @SerialName("first_played_at") val firstPlayedAt: Long,  // epoch seconds; oldest play in the group
    @SerialName("last_played_at") val lastPlayedAt: Long,    // epoch seconds; newest play in the group
    // Phase 143 follow-up — false only for the in-progress ("stopped at N%") items folded in on the
    // first page (see the /history route); true (and progressPct null) for every grouped finished row.
    val finished: Boolean = true,
    @SerialName("progress_pct") val progressPct: Int? = null,
)

@Serializable
private data class WatchHistoryPage(
    val entries: List<WatchHistoryEntry>,
    @SerialName("has_more") val hasMore: Boolean,
)

private const val HISTORY_PAGE_SIZE = 20

/** Phase 143 — collapses consecutive episodes of the same series **and season** (adjacent in the
 *  DatePlayed-sorted list) into one row, matching the design addendum's
 *  "Havets Hjarta · S01 · E01–E08 · ✓ 8 episodes" example. A movie, or an episode whose neighbours
 *  belong to a different series/season, is its own one-item row. */
private fun groupHistoryEntries(items: List<dev.jellystructure.auth.JellyfinPlayItem>): List<WatchHistoryEntry> {
    fun playedAt(item: dev.jellystructure.auth.JellyfinPlayItem): Long =
        item.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: 0L

    val result = mutableListOf<WatchHistoryEntry>()
    var i = 0
    while (i < items.size) {
        val head = items[i]
        if (head.seriesId == null) {
            result.add(WatchHistoryEntry(title = head.name, firstPlayedAt = playedAt(head), lastPlayedAt = playedAt(head)))
            i++
            continue
        }
        var j = i + 1
        while (j < items.size && items[j].seriesId == head.seriesId && items[j].seasonNumber == head.seasonNumber) j++
        val run = items.subList(i, j)
        val playedTimes = run.map { playedAt(it) }.filter { it > 0 }
        val episodeNums = run.mapNotNull { it.episodeNumber }
        val epLabel = buildString {
            append("S${(head.seasonNumber ?: 0).toString().padStart(2, '0')}")
            episodeNums.minOrNull()?.let { min ->
                append(" · E${min.toString().padStart(2, '0')}")
                val max = episodeNums.maxOrNull()!!
                if (max != min) append("–E${max.toString().padStart(2, '0')}")
            }
        }
        result.add(WatchHistoryEntry(
            title = head.seriesName ?: head.name,
            episodeLabel = epLabel,
            episodeCount = run.size,
            firstPlayedAt = playedTimes.minOrNull() ?: 0L,
            lastPlayedAt = playedTimes.maxOrNull() ?: 0L,
        ))
        i = j
    }
    return result
}

private const val SESSION_ID_PREFIX_LEN = 12

// Bug fix — a ScreenStatus re-broadcast to WS subscribers must carry every field, including ones sitting
// at their Kotlin default (position_ms=0, buffering=false, ...): the subscriber is a JS/TS client with no
// concept of a Kotlin default, so a field the bare Json.Default omits (encodeDefaults=false) reads back
// as `undefined`, not `0`/`false`. Same footgun HomeFeedService/PlaystateCache/AcquisitionService's own
// wire-JSON instances already guard against with encodeDefaults=true — this call site had been missed.
private val screenStatusJson = kotlinx.serialization.json.Json { encodeDefaults = true }

@Serializable
private data class AdminConfigEnvelope(
    val config: RaviloConfig,
    val hasOverride: Boolean,
    val isGlobal: Boolean,
)

// Phase 111 (FR D.1) — the Ravilo config editor's Pair-a-TV device list. Deliberately its own, smaller
// shape rather than the Phase 236 dev.jellystructure.shared.tv.RemoteDevice this route predates: this
// surface is cookie-gated admin UI, not the /api/remote/ API, and has no caller to compute "nearby"
// against or reason to carry a full ScreenStatus.
// (Line comments on purpose — see AuthPlugin.kt's RemoteCaller doc for why.)
@Serializable
private data class AdminDeviceSummary(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val connected: Boolean,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("now_playing") val nowPlaying: String? = null,
)

@Serializable
private data class TvDiscoverRequest(
    val mediaKind: String? = null,   // "movie" | "tv"
    val tmdbId: Int? = null,
    val title: String? = null,
    val language: String? = null,    // Phase 139 — explicit request-language intent id; null = resolve server-side
)

@Serializable
private data class ChangeRequestLanguageBody(val language: String)

fun Route.tvRoutes(
    deviceService: RaviloDeviceService,
    raviloConfigService: RaviloConfigService,
    homeFeedService: HomeFeedService,
    browseService: BrowseService,
    detailService: DetailService,
    playbackService: PlaybackService,
    sessionService: SessionService,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
    channelLogoStore: ChannelLogoStore,
    imageProxyService: RaviloArtworkService? = null,
    // Phase 216 (FR-216-5) — serves the studio/network logos `BrowseFacets.logoUrl` points at.
    logoDownloader: dev.jellystructure.media.LogoDownloader? = null,
    // Phase 218 (FR-218-9) — hand-off mint (phone) and redeem (receiver).
    castService: dev.jellystructure.tv.CastService? = null,
    tvEventBus: TvEventBus? = null,
    upcomingService: dev.jellystructure.tv.UpcomingService? = null,
    seerrDiscoverService: dev.jellystructure.seerr.SeerrDiscoverService? = null,
    mediaStore: dev.jellystructure.media.MediaStore? = null,
    // Security fix (2026-08-02 review, finding H4) — same unbounded-brute-force gap as /api/auth/login.
    loginRateLimiter: dev.jellystructure.auth.LoginRateLimiter,
    // Phase 177 §FR-177-5 — per-device recent-quality summary for the Users & devices overview.
    playbackQoeStore: dev.jellystructure.tv.PlaybackQoeStore,
    // Phase 236 (FR-236-2) — the receiver-shows-a-code pairing flow (screen/code, screen/claim).
    screenPairingService: dev.jellystructure.tv.ScreenPairingService? = null,
) {
    // Phase 141 — proxied username/password login, replacing the code+poll+admin-approve pairing flow.
    // No device token exists yet (OPEN_API_PATHS); jellystructure authenticates the credentials against
    // Jellyfin itself and mints a device token bound to the returned user (never the admin, silently).
    post("/tv/login") {
        val clientKey = dev.jellystructure.auth.LoginRateLimiter.clientKey(
            call.request.origin.remoteHost, call.request.headers["X-Forwarded-For"],
        )
        if (!loginRateLimiter.tryAcquire(clientKey)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many login attempts — try again in a minute"))
            return@post
        }
        val req = runCatching { call.receive<TvLoginRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            return@post
        }
        if (req.username.isBlank() || req.password.isBlank() || req.deviceId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username, password and deviceId are required"))
            return@post
        }
        val config = configStore.current
        if (config.apiKeys.jellyfinUrl.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
            return@post
        }
        // The identity presented for THIS auth call, before the Jellyfin userId is known — built from
        // deviceId + username (not forDevice(), which needs a DeviceData row that doesn't exist yet).
        // Folding the username in here is what keeps two different profiles on one shared TV from
        // colliding on a single Jellyfin DeviceId (see JellyfinDeviceIdentity.forDevice's KDoc) —
        // post-login calls use forDevice(device), which folds in the now-known jellyfinUserId instead.
        // Phase 224 (FR-224-2/3) — the build and platform this login comes from, R252's headers; null
        // when an older client is signing in, and then the Jellyfin header carries no Version.
        val appVersion = call.request.headers[dev.jellystructure.shared.RaviloHeaders.VERSION]?.trim()?.take(64)?.ifBlank { null }
        val platform = call.request.headers[dev.jellystructure.shared.RaviloHeaders.PLATFORM]?.trim()?.take(64)?.ifBlank { null }
        val loginIdentity = dev.jellystructure.auth.JellyfinDeviceIdentity(
            "ravilo-${req.deviceId}-${req.username}",
            req.deviceName?.ifBlank { null } ?: "Ravilo TV",
            appVersion,
        )
        val authAttempt = runCatching {
            jellyfinClient.authenticateByName(config.apiKeys.jellyfinUrl, req.username, req.password, loginIdentity)
        }
        val authResult = authAttempt.getOrElse { e ->
            val invalidCredentials = e is IllegalArgumentException
            // Security fix (H4) — there was no authentication audit trail at all; a compromise left no
            // trace. Username only, never the password.
            dev.jellystructure.log.Logger.warn("TV login failed for user '${req.username}' from device ${req.deviceId}", "auth")
            call.respond(
                if (invalidCredentials) HttpStatusCode.Unauthorized else HttpStatusCode.ServiceUnavailable,
                mapOf("error" to if (invalidCredentials) "Invalid username or password" else "Could not reach Jellyfin"),
            )
            return@post
        }
        dev.jellystructure.log.Logger.info("TV login succeeded for user '${authResult.user.name}' on device ${req.deviceId}", "auth")
        val (device, deviceToken) = deviceService.loginDevice(
            deviceId = req.deviceId,
            deviceName = req.deviceName,
            jellyfinUserId = authResult.user.id,
            jellyfinUsername = authResult.user.name,
            jellyfinUserToken = authResult.accessToken,
            isAdmin = authResult.user.policy.isAdministrator,
            isKids = authResult.user.policy.maxParentalRating != null,   // R18: parental cap ⇒ Kids profile
            // Phase 142 — AuthenticateByName already returns the full Policy inline, so this needs no
            // extra Jellyfin call. EnableAllFolders ⇒ unrestricted (null); else the enabled set (RAW —
            // loginDevice normalizes it).
            allowedLibraries = if (authResult.user.policy.enableAllFolders) null else authResult.user.policy.enabledFolders.toSet(),
            // Phase 142 follow-up — same policy response, its AllowedTags/BlockedTags (RAW; loginDevice
            // lowercases them).
            allowedTags = authResult.user.policy.allowedTags.toSet(),
            blockedTags = authResult.user.policy.blockedTags.toSet(),
            appVersion = appVersion,
            platform = platform,
        )
        call.respond(PairResult(
            session = TvSession(
                deviceId = device.deviceId,
                userId = device.jellyfinUserId,
                displayName = device.jellyfinUsername,
                isAdmin = device.isAdmin,
                isKids = device.isKids,
                // Phase 187 (FR-187-7) — free: AuthenticateByName's own User already carries PrimaryImageTag.
                avatarUrl = RaviloImageUrl.avatar(device.jellyfinUserId, authResult.user.primaryImageTag),
            ),
            deviceToken = deviceToken,
        ))
    }

    post("/tv/pair/unpair") {
        val device = runCatching { call.attributes[DeviceKey] }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No device session"))
                return@post
            }
        deviceService.unpair(device.deviceToken)
        call.respond(mapOf("status" to "unpaired"))
    }

    // ── Multi-user sessions ──────────────────────────────────────────────────
    get("/tv/sessions") {
        val device = call.attributes[DeviceKey]
        // Phase 187 (FR-187-7) — one Jellyfin call for the whole picker, not one per profile; the
        // stored device rows have no live PrimaryImageTag of their own (unlike the login response).
        val jf = configStore.current.apiKeys
        val tagByUser = if (jf.jellyfinUrl.isNotBlank() && jf.jellyfinToken.isNotBlank())
            jellyfinClient.getUsers(jf.jellyfinUrl, jf.jellyfinToken).associate { it.id to it.primaryImageTag }
        else emptyMap()
        val sessions = deviceService.listSessions(device.deviceId).map { d ->
            TvSession(
                deviceId = d.deviceId,
                userId = d.jellyfinUserId,
                displayName = d.jellyfinUsername,
                isAdmin = d.isAdmin,
                isKids = d.isKids,
                avatarUrl = RaviloImageUrl.avatar(d.jellyfinUserId, tagByUser[d.jellyfinUserId]),
            )
        }
        call.respond(sessions)
    }

    delete("/tv/sessions/{userId}") {
        val device = call.attributes[DeviceKey]
        val userId = call.parameters["userId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId required")); return@delete
        }
        deviceService.removeSession(device.deviceId, userId)
        call.respond(mapOf("status" to "removed"))
    }

    // ── Phase 187 — a viewer's own photo + password ──────────────────────────
    // FR-187-2: the Jellyfin user id is taken from the session (device.jellyfinUserId) — never from the
    // request body or a query parameter. There is no route shape here in which one viewer can name
    // another viewer's account, not "and check they match": no such parameter exists at all.
    post("/tv/account/photo") {
        val device = call.attributes[DeviceKey]
        val svc = imageProxyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        // FR-187-6 — reject an oversized body before decoding it. Base64 inflates ~4/3; 16MB of source
        // bytes (already generous for a phone photo the server is about to shrink to 320px) is ~21.5MB
        // of base64 text, comfortably under the global 64MB ceiling but a tighter, purpose-fit cap.
        val declared = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > 22 * 1024 * 1024L) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "photo too large")); return@post
        }
        val req = runCatching { call.receive<dev.jellystructure.shared.tv.AccountPhotoUpload>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid upload")); return@post
        }
        // FR-187-6 — content-type allowlist; the server decides what Jellyfin receives, not the client.
        val ct = req.contentType.substringBefore(';').trim().lowercase()
        if (ct !in setOf("image/jpeg", "image/png", "image/webp")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported image type")); return@post
        }
        val bytes = runCatching {
            @OptIn(ExperimentalEncodingApi::class)
            Base64.decode(req.dataBase64.substringAfterLast(","))
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid base64 data")); return@post
        }
        val tag = svc.setAvatar(device.jellyfinUserId, jellyfinClient, bytes)
            ?: return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin rejected the photo"))
        call.respond(dev.jellystructure.shared.tv.AccountPhotoResult(RaviloImageUrl.avatar(device.jellyfinUserId, tag)))
    }

    delete("/tv/account/photo") {
        val device = call.attributes[DeviceKey]
        val svc = imageProxyService ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable)
        val ok = svc.deleteAvatar(device.jellyfinUserId, jellyfinClient)
        if (!ok) return@delete call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin rejected the removal"))
        call.respond(dev.jellystructure.shared.tv.AccountPhotoResult(avatarUrl = null))
    }

    // FR-187-4 — the same brute-force exposure as /tv/login (a synchronous credential proxy on an
    // internet-exposable instance, Phase 167), keyed per session AND per source so one compromised
    // device can't exhaust the limit for every other device in the household.
    post("/tv/account/password") {
        val device = call.attributes[DeviceKey]
        val clientKey = dev.jellystructure.auth.LoginRateLimiter.clientKey(
            call.request.origin.remoteHost, call.request.headers["X-Forwarded-For"],
        )
        if (!loginRateLimiter.tryAcquire("${device.deviceId}:$clientKey")) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts — try again in a moment"))
            return@post
        }
        val req = runCatching { call.receive<dev.jellystructure.shared.tv.AccountPasswordChangeRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@post
        }
        val config = configStore.current
        if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured")); return@post
        }
        // FR-187-3 — Jellyfin validates the current password; jellystructure never does. The caller's
        // own device token IS a Jellyfin access token (Phase 141 login mints one per device/user), so it
        // is what's sent — never the shared admin token, and never re-authenticated as a side effect.
        val outcome = jellyfinClient.updateUserPassword(
            config.apiKeys.jellyfinUrl, device.jellyfinUserToken, device.jellyfinUserId,
            req.currentPassword, req.newPassword,
        )
        when (outcome) {
            dev.jellystructure.auth.PasswordChangeOutcome.WRONG_CURRENT ->
                call.respond(HttpStatusCode.OK, dev.jellystructure.shared.tv.AccountPasswordResult(ok = false, wrongCurrentPassword = true))
            dev.jellystructure.auth.PasswordChangeOutcome.FAILED ->
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin rejected the change"))
            dev.jellystructure.auth.PasswordChangeOutcome.OK -> {
                // FR-187-8 — measured, not assumed: re-validate the very token this call used.
                // Phase 194 — only an actual rejection means the password change killed the token. A
                // transient failure here is not evidence it died, and reporting it as death would tell
                // the viewer to sign in again on every device for no reason.
                val survived = jellyfinClient.checkToken(
                    config.apiKeys.jellyfinUrl, device.jellyfinUserToken, device.jellyfinUserId,
                ).outcome != dev.jellystructure.auth.TokenCheck.REJECTED
                call.respond(dev.jellystructure.shared.tv.AccountPasswordResult(ok = true, tokenSurvived = survived))
            }
        }
    }

    // ── Home feed ────────────────────────────────────────────────────────────
    get("/tv/home") {
        val device = call.attributes[DeviceKey]
        call.respond(homeFeedService.getHomeFeed(device))
    }

    // R187 fix — channel id->name list for the seeded-browse page's Channel facet.
    get("/tv/channels") {
        val device = call.attributes[DeviceKey]
        call.respond(homeFeedService.getChannels(device))
    }

    get("/tv/channel/{id}") {
        val device = call.attributes[DeviceKey]
        val channelId = call.parameters["id"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel id"))
            return@get
        }
        call.respond(homeFeedService.getChannelFeed(device, channelId))
    }

    // ── Detail ───────────────────────────────────────────────────────────────
    get("/tv/movie/{id}") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
        val detail = detailService.getMovieDetail(device, id)
        if (detail == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        else call.respond(detail)
    }

    get("/tv/series/{id}") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
        val detail = detailService.getSeriesDetail(device, id)
        if (detail == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Series not found"))
        else call.respond(detail)
    }

    // ── R83: bulk play-state ─────────────────────────────────────────────────
    get("/tv/playstate") {
        val device = call.attributes[DeviceKey]
        val raw = call.request.queryParameters["ids"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids required")); return@get
        }
        val ids = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val result: Map<String, CardPlayState> = detailService.getPlaystate(device, ids)
        call.respond(result)
    }

    // ── Browse, search, facets ───────────────────────────────────────────────
    get("/tv/browse") {
        val device = call.attributes[DeviceKey]
        val kind     = call.request.queryParameters["kind"]
        val sort     = call.request.queryParameters["sort"]
        val page     = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        // R118: absent pageSize ⇒ null ⇒ full filtered set (no 40-item default cap).
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
        val genres   = call.request.queryParameters.getAll("genre")   ?: emptyList()
        val studios  = call.request.queryParameters.getAll("studio")  ?: emptyList()
        val networks = call.request.queryParameters.getAll("network") ?: emptyList()
        val tags     = call.request.queryParameters.getAll("tag")     ?: emptyList()
        call.respond(browseService.browse(device, kind, genres, studios, networks, tags, sort, page, pageSize))
    }

    // R187 (§G-4) — Continue Watching's own "→ See all": not a ConditionGroup seed (see
    // HomeFeedService.continueWatchingAll's doc comment), so it's a dedicated endpoint, not a
    // /tv/browse/seeded call. Plain MediaCards, not BrowseCard — Continue Watching's own See-all page
    // doesn't offer the catalog facet bar (genre/quality/etc.), just the viewer's full in-progress list.
    // R219 (FR-R219-6) — optional ?channel= mirrors the originating row's scope; see
    // HomeFeedService.continueWatchingAll's doc comment for the exact rule.
    get("/tv/continue/all") {
        val device = call.attributes[DeviceKey]
        val channelId = call.request.queryParameters["channel"]
        call.respond(homeFeedService.continueWatchingAll(device, channelId))
    }

    // R187 — the "→ See all" browse page's seed resolver: POST (not GET) because the seed is a
    // ConditionGroup tree, not flat query params. Returns the FULL matching set — see
    // BrowseService.browseByQuery's doc comment for why no pagination/narrowed-facets round trip.
    post("/tv/browse/seeded") {
        val device = call.attributes[DeviceKey]
        val req = runCatching { call.receive<SeededBrowseRequest>() }.getOrDefault(SeededBrowseRequest())
        call.respond(browseService.browseByQuery(device, req.query, req.mediaKind))
    }

    // R190 §C — the person-browse page's Seerr overflow row: requestable titles featuring this person
    // that the library doesn't already hold. Empty (not an error) when Seerr is off/unconfigured.
    get("/tv/browse/person/{tmdbId}/seerr-overflow") {
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(seerrDiscoverService?.getPersonOverflow(tmdbId) ?: emptyList())
    }

    get("/tv/search") {
        val device = call.attributes[DeviceKey]
        val query = call.request.queryParameters["q"] ?: ""
        call.respond(browseService.search(device, query))
    }

    get("/tv/facets") {
        val device = call.attributes[DeviceKey]
        val kind = call.request.queryParameters["kind"]
        call.respond(browseService.facets(device, kind))
    }

    // ── Phase 218 (FR-218-9): Chromecast hand-off ────────────────────────────
    // The phone mints a short-lived, single-use code under its own session and puts it in the Cast
    // LOAD; the receiver redeems it for ITS OWN device token + ravilo_device row and is a Ravilo device
    // from then on. Nothing about the session depends on the phone staying alive.
    post("/tv/cast/handoff") {
        val device = call.attributes[DeviceKey]
        val svc = castService ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "casting is not available"))
        if (svc.capability() == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "casting is not enabled"))
        call.respond(svc.mint(device))
    }
    // Pre-auth (AuthPlugin OPEN_API_PATHS) — same rate limiter as /tv/login, since a code is guessable
    // in principle and this is the only unauthenticated path that mints a device token.
    post("/tv/cast/redeem") {
        val svc = castService ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "casting is not available"))
        val clientKey = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
            ?: call.request.local.remoteHost
        if (!loginRateLimiter.tryAcquire(clientKey)) {
            call.response.headers.append(HttpHeaders.RetryAfter, "60")
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts — try again in a minute"))
        }
        val req = runCatching { call.receive<dev.jellystructure.shared.tv.CastRedeemRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        // Phase 224 (FR-224-2) — the receiver's own build, from its headers (R252 sends them on castRedeem).
        val redeemed = svc.redeem(
            req.code, req.deviceName, req.receiverId,
            appVersion = call.request.headers[dev.jellystructure.shared.RaviloHeaders.VERSION]?.trim()?.take(64)?.ifBlank { null },
            platform = call.request.headers[dev.jellystructure.shared.RaviloHeaders.PLATFORM]?.trim()?.take(64)?.ifBlank { null },
        )
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "That code is not valid any more — cast again from your phone"))
        val (device, deviceToken) = redeemed
        call.respond(PairResult(
            session = TvSession(
                deviceId = device.deviceId,
                userId = device.jellyfinUserId,
                displayName = device.displayName,
                isAdmin = device.isAdmin,
                isKids = device.isKids,
            ),
            deviceToken = deviceToken,
        ))
    }

    // ── Phase 236 (FR-236-2): the receiver-shows-a-code pairing flow ───────────
    // An unpaired receiver has no origin to reach a server through (it's a sideloaded .wgt, not a
    // browser tab) and no credential yet — it mints a code, shows it, and polls for the phone to have
    // typed it in. Both routes are pre-auth (AuthPlugin OPEN_API_PATHS) and rate-limited exactly like
    // /tv/login and /tv/cast/redeem above — a code is guessable in principle, and screen/claim is the
    // second unauthenticated path (after cast/redeem) that hands back a device token.
    post("/tv/screen/code") {
        val svc = screenPairingService ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "screens are not available"))
        val clientKey = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
            ?: call.request.local.remoteHost
        if (!loginRateLimiter.tryAcquire(clientKey)) {
            call.response.headers.append(HttpHeaders.RetryAfter, "60")
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts — try again in a minute"))
        }
        val req = runCatching { call.receive<dev.jellystructure.shared.tv.ScreenCodeRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        if (req.deviceId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "device_id is required"))
        val (code, expiresAt, claimSecret) = svc.mintCode(req.deviceId, req.deviceName, req.platform)
        call.respond(dev.jellystructure.shared.tv.ScreenCodeResponse(
            code = code, expiresAt = expiresAt, claimSecret = claimSecret,
        ))
    }
    post("/tv/screen/claim") {
        val svc = screenPairingService ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "screens are not available"))
        val clientKey = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
            ?: call.request.local.remoteHost
        if (!loginRateLimiter.tryAcquire(clientKey)) {
            call.response.headers.append(HttpHeaders.RetryAfter, "60")
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts — try again in a minute"))
        }
        val req = runCatching { call.receive<dev.jellystructure.shared.tv.ScreenClaimRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        when (val result = svc.poll(req.code, req.claimSecret)) {
            null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "That code is not valid any more"))
            is dev.jellystructure.tv.ScreenPairingService.PollResult.Waiting -> call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
            is dev.jellystructure.tv.ScreenPairingService.PollResult.Claimed -> call.respond(result.result)
        }
    }

    // ── Phase 236 (FR-236-5): status reported by a screen, fanned out to subscribers ───────────────
    post("/tv/playback/status") {
        val device = call.attributes[DeviceKey]
        val status = runCatching { call.receive<dev.jellystructure.shared.tv.ScreenStatus>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid status"))
        }
        // Phase 236 (FR-236-6) — the other of the two moments a device's address is refreshed.
        val statusAddress = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
            ?: call.request.local.remoteHost
        deviceService.recordAddress(device.deviceId, device.jellyfinUserId, statusAddress)
        dev.jellystructure.tv.screenStatusTracker.update(device.deviceId, status)
        tvEventBus?.notifyDeviceStatus(
            device.deviceId,
            screenStatusJson.encodeToString(dev.jellystructure.shared.tv.ScreenStatus.serializer(), status),
        )
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    // ── Playback ─────────────────────────────────────────────────────────────
    post("/tv/playback/start") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackStartRequest>()
        call.respond(playbackService.startPlayback(device, req.itemId, req.capabilities, req.startPositionMs, req.audioLanguage, req.audioVariant))   // R292 / R291
    }

    post("/tv/playback/progress") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackProgressRequest>()
        playbackService.reportProgress(device, req.itemId, req.positionMs, req.isPaused)
        call.respond(mapOf("status" to "ok"))
    }

    post("/tv/playback/stop") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackStopRequest>()
        playbackService.stopPlayback(device, req.itemId, req.positionMs, req.startupMs)
        call.respond(mapOf("status" to "ok"))
        // Bug fix: the stop used to be forwarded to Jellyfin and nothing else — the cached home feed
        // (5 min) kept serving the pre-stop Continue row, so a correct stop could stay invisible on
        // Home for minutes. Runs after responding so the client's stop ack isn't delayed by it.
        // R248 (FR-R248-2) — with the 219 writer in place the stop has only been *queued* at this
        // point, so invalidating here would rebuild the row from Jellyfin's pre-stop state; the writer
        // runs the same invalidation (and the `home_changed` push) the moment the stop lands.
        if (!playbackService.queuesStops) homeFeedService.invalidatePlaystate(device)
    }

    // Phase 177 §FR-177-5 / R216 §FR-R216-4 — fire-and-forget playback-quality report; see
    // PlaybackService.recordQoe's doc for why deviceId/playSessionId are server-resolved, not
    // client-supplied. Always 200s (this is diagnostics — never a reason to surface an error to a player).
    post("/tv/playback/qoe") {
        val device = call.attributes[DeviceKey]
        val req = runCatching { call.receive<PlaybackQoeReport>() }.getOrNull()
        if (req != null) playbackService.recordQoe(device, req)
        call.respond(mapOf("status" to "ok"))
    }

    // R56: restream with a subtitle burned in (PGS encode path)
    post("/tv/playback/restream") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackRestreamRequest>()
        call.respond(playbackService.restream(device, req.itemId, req.subtitleStreamIndex, req.positionMs, req.capabilities, req.audioStreamIndex))
    }

    post("/tv/mark") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<MarkRequest>()
        playbackService.mark(device, req.itemId, req.watched)
        call.respond(mapOf("status" to "ok"))
        homeFeedService.invalidatePlaystate(device)  // see /tv/playback/stop
    }

    // R142 — played/unplayed write-through (movie / episode / season / series). Returns the authoritative
    // per-id play-state for the item + affected episodes so the client renders from the server result.
    put("/tv/played") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlayedRequest>()
        call.respond(playbackService.setPlayed(device, req.itemId, req.played, req.episodeIds))
        // A finished episode must leave (and its successor enter) the Continue row now, not in 5 minutes.
        homeFeedService.invalidatePlaystate(device)  // see /tv/playback/stop
    }

    // Bug fix — "My List" write-through: the detail screens' "+ My List" button had no backend call
    // behind it at all (only a read path existed, for the My List browse grid's own filter).
    put("/tv/favorite") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<FavoriteRequest>()
        val state = playbackService.setFavorite(device, req.itemId, req.favorite)
        if (state != null) call.respond(state) else call.respond(HttpStatusCode.NotFound)
    }

    // ── Per-user config ──────────────────────────────────────────────────────
    get("/tv/config") {
        val device = call.attributes[DeviceKey]
        call.respond(raviloConfigService.getConfig(device.jellyfinUserId))
    }

    // R141: degrade-to-poll fallback — client polls this when the WS is down (or as a safety net).
    // Returns the monotonic config-change rev so the client can detect a missed event and self-heal.
    get("/tv/config/rev") {
        val rev = tvEventBus?.currentRev() ?: 0L
        call.respond(mapOf("rev" to rev))
    }

    // R171 — the TV's Request tab: Seerr-backed discover feeds (Phase 137 row config), replacing the
    // chart engine Phase 136 retired. `seerrDiscoverService` is null only if Main.kt didn't wire it
    // (shouldn't happen outside tests) — falls back to unavailable rather than 500ing.
    get("/tv/discover") {
        val device = call.attributes[DeviceKey]
        val resp = seerrDiscoverService?.getRequestFeeds(device.jellyfinUserId, device.isAdmin, device.isKids)
            ?: DiscoverResponse(available = false, canRequest = false)
        call.respond(resp)
    }

    // Phase 139 §D.2 — the viewer's own not-yet-available requests (the Request tab's "In progress"
    // rail), so a strict-waiting choice made days ago is easy to find again and change.
    get("/tv/discover/requests/mine") {
        val device = call.attributes[DeviceKey]
        call.respond(seerrDiscoverService?.getMyRequests(device.jellyfinUserId) ?: emptyList())
    }

    // R160 — server-cached with a short TTL (UpcomingService) so opening the tab never fans out a live
    // Sonarr/Radarr round-trip; R188 — the cached feed is then filtered per device (library/kids
    // restrictions) before it's returned, same as every other device-facing catalog read.
    get("/tv/upcoming") {
        val device = call.attributes[DeviceKey]
        call.respond(upcomingService?.getUpcoming(device) ?: dev.jellystructure.shared.tv.UpcomingFeed(enabled = false))
    }

    // R167 — not-held-item detail (Discover-detail parity: live TMDB genres/runtime/cast). Best-effort:
    // a lookup miss still returns 404 so the client falls back to the plain feed item it already has,
    // never a blank screen. R188 — also 404s for an item the device isn't allowed to see.
    get("/tv/upcoming/item/{id}") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val detail = upcomingService?.getDetail(device, id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(detail)
    }

    // R171 — request detail: {mediaType}=movie|tv, {tmdbId}=TMDB id (addressing changed from the
    // retired chart flow's listId+rank, since Request rows have no rank concept).
    get("/tv/discover/item/{mediaType}/{tmdbId}") {
        val device = call.attributes[DeviceKey]
        val mediaType = call.parameters["mediaType"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val detail = seerrDiscoverService?.getEntry(mediaType, tmdbId, device.jellyfinUserId, device.isKids) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(detail)
    }

    post("/tv/discover/request") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<TvDiscoverRequest>()
        val tmdbId = req.tmdbId ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no tmdbId"))
        val service = seerrDiscoverService
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "discover request unavailable"))
        call.respond(service.request(device.jellyfinUserId, device.isAdmin, req.mediaKind ?: "movie", tmdbId, req.title.orEmpty(), req.language, device.isKids))
    }

    // Phase 139 §E — switch a still-waiting request to a different language: re-profiles + re-searches
    // the *arr item and updates the persisted intent. 404 covers "nothing requested at that id" and
    // "request-language feature not configured" alike — the client shows the same generic failure either way.
    post("/tv/discover/request/{mediaType}/{tmdbId}/language") {
        val device = call.attributes[DeviceKey]
        val mediaType = call.parameters["mediaType"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = runCatching { call.receive<ChangeRequestLanguageBody>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "language is required"))
        }
        val ok = seerrDiscoverService?.changeLanguage(device.jellyfinUserId, mediaType, tmdbId, body.language) ?: false
        if (ok) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.NotFound, mapOf("error" to "couldn't change language"))
    }

    // R171 — search scoped to the Seerr catalogue only (never the local library — that stays on the
    // AppBar search icon / GET /tv/search). Results are request tiles, same live-status derivation as
    // the discover feed rows.
    get("/tv/search/seerr") {
        call.attributes[DeviceKey]
        val q = call.request.queryParameters["q"].orEmpty()
        val items = seerrDiscoverService?.search(q) ?: emptyList()
        call.respond(dev.jellystructure.shared.tv.SeerrSearchResults(query = q, items = items))
    }

    // Admin config endpoints — authenticated by session cookie (jellystructure admin login)
    // R51: ?scope=global → global layout; ?userId=<id> → per-user override; default = session user.
    get("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val qScope = call.request.queryParameters["scope"]
        val userId = when {
            qScope == "global" -> dev.jellystructure.tv.GLOBAL_USER_ID
            else -> call.request.queryParameters["userId"] ?: session.jellyfinUserId
        }
        val config = if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) raviloConfigService.getGlobalConfig()
                     else raviloConfigService.getConfig(userId)
        val hasOverride = raviloConfigService.hasCustomConfig(userId)
        call.respond(AdminConfigEnvelope(config, hasOverride, userId == dev.jellystructure.tv.GLOBAL_USER_ID))
    }

    // Phase 111 (FR D.1) — paired-devices list for the Ravilo config editor's Pair-a-TV area (name,
    // last seen, connected, and eventually Phase 110's "re-pair" chip). Same shape as /api/remote/devices
    // minus the API-key fence — cookie-gated like the rest of Settings.
    get("/tv/admin/devices") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val userId = call.request.queryParameters["userId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        val devices = deviceService.listByUser(userId).map { d ->
            AdminDeviceSummary(
                deviceId = d.deviceId,
                name = d.displayName,
                connected = tvEventBus?.isConnected(d.deviceId) ?: false,
                lastSeen = d.lastSeen,
                // Bug fix: this used to be the raw Jellyfin item id (a hex UUID) — resolve to a title.
                nowPlaying = dev.jellystructure.tv.nowPlayingItem(d.deviceId)?.let { id -> mediaStore?.titleForJellyfinId(id) ?: id },
            )
        }
        call.respond(devices)
    }

    delete("/tv/admin/devices/{deviceId}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val deviceId = call.parameters["deviceId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val userId = call.request.queryParameters["userId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        deviceService.removeSession(deviceId, userId)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 — every Jellyfin user → their Ravilo devices + admin web sessions + policy summary, one
    // fetch. Supersedes the per-user get("/tv/admin/devices") above for the new "Users & devices" tab
    // (that route stays for any other caller). Now-playing is sourced from the same local
    // PlaybackService.activePlayback map /tv/admin/devices already reads — no extra Jellyfin call.
    get("/tv/admin/overview") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val config = configStore.current
        val allDevices = deviceService.allDevices()
        val allSessions = sessionService.list()
        // Every admin's full Policy, in one call — already fetched for admin login elsewhere; reused
        // here rather than a per-user round-trip. Skipped gracefully if Jellyfin isn't configured yet.
        // Phase 258 (dev review item 2) — a failed fetch is known to have failed, not an empty household.
        val jfUsers = if (config.apiKeys.jellyfinUrl.isNotBlank() && config.apiKeys.jellyfinToken.isNotBlank()) {
            jellyfinClient.getUsersOrNull(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken).orEmpty()
        } else emptyList()
        val jfPolicies = jfUsers.associate { it.id to it.policy }
        // Phase 187 (FR-187-9) — read-only photo per user row, same PrimaryImageTag cache-busting
        // shape as the client-facing routes above; one call, reused, not a per-row round trip.
        val jfAvatarTags = jfUsers.associate { it.id to it.primaryImageTag }
        val totalLibraries = config.libraries.size

        val userIds = (allDevices.map { it.jellyfinUserId } + allSessions.map { it.jellyfinUserId }).distinct()
        val users = userIds.map { uid ->
            val userDevices = allDevices.filter { it.jellyfinUserId == uid }
            val userSessions = allSessions.filter { it.jellyfinUserId == uid }
            val username = userDevices.firstOrNull()?.jellyfinUsername ?: userSessions.firstOrNull()?.jellyfinUsername ?: uid
            val policy = jfPolicies[uid]
            OverviewUser(
                userId = uid,
                username = username,
                policy = OverviewPolicy(
                    // Sessions exist only for admins (AuthRoutes 403s a non-admin login); fall back to
                    // that signal if the live Policy lookup missed (Jellyfin unreachable/unconfigured).
                    isAdmin = policy?.isAdministrator ?: (userDevices.any { it.isAdmin } || userSessions.isNotEmpty()),
                    allFolders = policy?.enableAllFolders ?: true,
                    libraryCount = policy?.takeUnless { it.enableAllFolders }?.enabledFolders?.size,
                    totalLibraries = totalLibraries,
                    allowedTags = policy?.allowedTags ?: emptyList(),
                    blockedTags = policy?.blockedTags ?: emptyList(),
                    maxRating = policy?.maxParentalRating,
                    known = policy != null,
                    // FR-258-6 — the live policy is what this line shows; name it when a device's own copy
                    // (the one that decides what plays) is behind it. Same comparison the reconciler makes.
                    stale = policy?.let { live -> val p = dev.jellystructure.tv.DevicePolicy.of(live); userDevices.any { dev.jellystructure.tv.policyChanges(it, p).isNotEmpty() } } ?: false,
                ),
                avatarUrl = RaviloImageUrl.avatar(uid, jfAvatarTags[uid]),
                devices = userDevices.map { d ->
                    val decode = deviceService.decodeCapabilities(d.deviceId, d.jellyfinUserId)
                    val history = deviceService.versionHistory(d.deviceId)   // Phase 259 (FR-259-6)
                    val behind = versionBehind.observe(d.deviceId, d.appVersion, dev.jellystructure.ServerVersion.current, dev.jellystructure.nowEpochSec() * 1000L)
                    OverviewDevice(
                        deviceId = d.deviceId,
                        name = d.displayName,
                        connected = tvEventBus?.isConnected(d.deviceId) ?: false,
                        isAdmin = d.isAdmin,
                        isKids = d.isKids,
                        createdAt = d.createdAt,
                        lastSeen = d.lastSeen,
                        // Bug fix: this used to be the raw Jellyfin item id (a hex UUID) — resolve to a title.
                        nowPlaying = dev.jellystructure.tv.nowPlayingItem(d.deviceId)?.let { id -> mediaStore?.titleForJellyfinId(id) ?: id },
                        recentQuality = playbackQoeStore.recentForDevice(d.deviceId, limit = 1).firstOrNull(),
                        decodeMaxBitrateHevc = decode?.hevcMaxBitrate,
                        decodeMaxBitrateH264 = decode?.h264MaxBitrate,
                        decodeMeasuredAt = decode?.measuredAt,
                        appVersion = d.appVersion,
                        platform = d.platform,
                        reconnectsLastHour = tvEventBus?.unstable(d.deviceId)?.connectsLastHour,   // Phase 256 (FR-256-3)
                        releasesBehind = behind?.releases,   // Phase 256 (FR-256-5)
                        behindSince = behind?.sinceMs,
                        versionSince = history.firstOrNull()?.takeIf { it.observed }?.firstSeenAt,
                        versions = history.map { OverviewVersion(it.appVersion, it.firstSeenAt, it.observed) },
                    )
                },
                sessions = userSessions.map { s ->
                    OverviewSession(
                        id = s.token.take(SESSION_ID_PREFIX_LEN),
                        createdAt = s.createdAt,
                        lastUsedAt = s.lastUsedAt,
                        expiresAt = s.expiresAt,
                        isCurrent = s.token == session.token,
                    )
                },
            )
        }
        call.respond(users)
    }

    // Phase 259 (FR-259-9) — the Users & devices page-bar chip. "Latest" is this backend's own version (the
    // only version the server knows — dev review item 1); a device counts as behind by arithmetic on
    // MAJOR.MINOR, one count per physical device (its most recently seen viewer row), dev builds never.
    get("/tv/admin/ravilo-version") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val latest = dev.jellystructure.ServerVersion.current
        val perDevice = deviceService.allDevices().groupBy { it.deviceId }.values
            .map { rows -> rows.maxBy { it.lastSeen }.appVersion }
        val behind = perDevice.count { v -> (dev.jellystructure.shared.tv.raviloReleasesBehind(v, latest) ?: 0) > 0 }
        call.respond(RaviloVersionSummary(
            latest = latest,
            release = dev.jellystructure.shared.tv.parseRaviloRelease(latest) != null,
            behind = behind,
            devices = perDevice.size,
        ))
    }

    // Phase 177 §FR-177-5 — the Activity page's "Playback quality" card: recent sessions across every
    // device, device/title already resolved server-side (constitution: frontend renders server-pushed
    // state only, never derives it from raw ids).
    get("/tv/admin/playback-qoe/recent") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val devicesById = deviceService.allDevices().associateBy { it.deviceId }
        val rows = playbackQoeStore.recent(30).map { q ->
            QoeActivityRow(
                deviceName = devicesById[q.deviceId]?.displayName?.ifBlank { q.deviceId } ?: q.deviceId,
                title = mediaStore?.titleForJellyfinId(q.jellyfinId) ?: q.jellyfinId,
                droppedFrames = q.droppedFrames,
                rebufferCount = q.rebufferCount,
                rebufferMs = q.rebufferMs,
                directPlay = q.directPlay,
                linkKind = q.linkKind,
                linkMbps = q.linkMbps,
                updatedAt = q.updatedAt,
            )
        }
        call.respond(rows)
    }

    // Phase 143 — revoke one admin web session by its wire-safe id prefix (never the raw token; see
    // OverviewSession). Resolving the prefix back to a full token is an in-memory scan over `list()` —
    // the session count is always small (tens at most), so this is cheap.
    delete("/tv/admin/sessions/{id}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val match = sessionService.list().firstOrNull { it.token.startsWith(id) }
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        sessionService.revoke(match.token)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 — "sign out everywhere": revokes every device token AND every admin web session this
    // Jellyfin user holds, in one action.
    post("/tv/admin/users/{userId}/signout-all") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val userId = call.parameters["userId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        deviceService.deleteAllForUser(userId)
        sessionService.revokeAllForUser(userId)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 (design addendum) — "Recently watched": the one section of the overview that must read
    // Jellyfin live (no local play-history store). Strictly lazy, one user at a time, behind the FE's
    // "Show more" expander — never fanned out across all users on the base overview.
    get("/tv/admin/users/{userId}/history") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val config = configStore.current
        if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
            call.respond(WatchHistoryPage(emptyList(), false)); return@get
        }
        val raw = jellyfinClient.getRecentlyPlayed(
            config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, userId,
            limit = HISTORY_PAGE_SIZE, startIndex = offset,
        )
        // Caveat (documented, not a bug): a binge run can straddle a page boundary, splitting one
        // logical group across two "Show more" pages — acceptable for a history view, not a spec violation.
        val finishedEntries = groupHistoryEntries(raw)
        // Phase 143 follow-up — fold in "stopped at N%" (in-progress) items, first page only: there are
        // only ever a handful at once (Continue Watching, not a growing history), so no pagination is
        // needed for these; re-fetching them on every "Show more" click would be wasted work. Reuses
        // getResumeItems (built for the device's own paired token) with the ADMIN token instead — the
        // same "admin token, arbitrary userId" pattern getRecentlyPlayed/getUsers already rely on.
        val entries = if (offset == 0) {
            val resumable = jellyfinClient.getResumeItems(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, userId, limit = 10)
            val inProgress = resumable.mapNotNull { item ->
                val userData = item.userData ?: return@mapNotNull null
                val playedAt = userData.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: return@mapNotNull null
                WatchHistoryEntry(
                    title = item.seriesName ?: item.name,
                    episodeLabel = if (item.seriesId != null) {
                        "S${(item.seasonNumber ?: 0).toString().padStart(2, '0')} · E${(item.episodeNumber ?: 0).toString().padStart(2, '0')}"
                    } else null,
                    firstPlayedAt = playedAt,
                    lastPlayedAt = playedAt,
                    finished = false,
                    progressPct = userData.playedPercentage?.toInt(),
                )
            }
            (finishedEntries + inProgress).sortedByDescending { it.lastPlayedAt }
        } else finishedEntries
        call.respond(WatchHistoryPage(entries = entries, hasMore = raw.size == HISTORY_PAGE_SIZE))
    }

    put("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@put }
        val qScope = call.request.queryParameters["scope"]
        val userId = when {
            qScope == "global" -> dev.jellystructure.tv.GLOBAL_USER_ID
            else -> call.request.queryParameters["userId"] ?: session.jellyfinUserId
        }
        val config = runCatching { call.receive<RaviloConfig>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config: ${it.message}")); return@put
        }
        raviloConfigService.validate(config)?.let { err ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to err)); return@put
        }
        raviloConfigService.save(userId, config)
        call.respond(mapOf("status" to "ok"))
    }

    delete("/tv/admin/config") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val userId = call.request.queryParameters["userId"]
            ?: run { call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId required")); return@delete }
        if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot delete the global config")); return@delete
        }
        raviloConfigService.removeCustomConfig(userId)
        call.respond(mapOf("status" to "ok"))
    }

    // R162: the field-level behaviour & preferences overlay — independent of /tv/admin/config above
    // (the R51 layout override). Never gated by "has a custom layout"; editable in both scopes.
    get("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val userId = call.request.queryParameters["userId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    // PUT body: same shape as ViewerSettingsRequest — only the one field being changed is non-null.
    // Global scope (?scope=global) edits the global defaults directly (via the layout config save path,
    // unchanged); this route only ever writes a per-user overlay entry, so scope=global is rejected.
    put("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@put }
        val userId = call.request.queryParameters["userId"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Set the global default via /tv/admin/config instead")); return@put
        }
        val req = runCatching { call.receive<ViewerSettingsRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${it.message}")); return@put
        }
        req.skin?.let { raviloConfigService.setAdminSkin(userId, it) }
        req.tileShape?.let { raviloConfigService.setAdminTileShape(userId, it) }
        req.showContinueProgress?.let { raviloConfigService.setAdminShowContinueProgress(userId, it) }
        req.autoplayNext?.let { raviloConfigService.setAdminAutoplayNext(userId, it) }
        req.uiLanguage?.let { raviloConfigService.setAdminUiLanguage(userId, it) }
        req.requestLanguage?.let { raviloConfigService.setAdminRequestLanguage(userId, it) }
        req.skipIntro?.let { raviloConfigService.setAdminSkipIntro(userId, it) }
        req.skipCredits?.let { raviloConfigService.setAdminSkipCredits(userId, it) }
        req.skipSecs?.let { raviloConfigService.setAdminSkipSecs(userId, it) }
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    delete("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val userId = call.request.queryParameters["userId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        val field = call.request.queryParameters["field"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "field is required"))
        raviloConfigService.resetBehaviourField(userId, field)
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    // ── Channel-logo asset library (R36 §F) ──────────────────────────────────
    // R133: public artwork — serves jellystructure's OWN on-disk poster/backdrop/logo (resized + cached),
    // no Jellyfin call (AuthPlugin OPEN_API_PATHS; Coil can't attach a token, images aren't sensitive).
    // R291 (FR-R291-2) — every audio track as an HLS rendition; public, the id is the capability (see AuthPlugin).
    get("/tv/stream/{id}/master.m3u8") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val text = playbackService.audioRenditions.master(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(text, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    get("/tv/image/{itemId}/{type}") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val type   = call.parameters["type"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc    = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width  = call.request.queryParameters["w"]?.toIntOrNull()  // R93: optional width
        val result = svc.serve(itemId, type, width) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
    // Phase 216 (FR-216-5) — a captured studio/network logo, addressed the way `RaviloImageUrl.taxonomyLogo`
    // builds it. Public like every other /tv/image/ path (brand logos are not sensitive, Coil can't
    // attach a token). 404 when nothing was captured — the client never asks unless `logoUrl` was set.
    get("/tv/image/logo/{kind}/{name}") {
        val kind = call.parameters["kind"]?.takeIf { it == "studios" || it == "networks" } ?: return@get call.respond(HttpStatusCode.BadRequest)
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val bytes = logoDownloader?.serveLogo(kind, name) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(bytes, ContentType.Image.PNG)
    }
    // R133: episode still — addressed by series id + episode filename. Phase 149: ?ep= disambiguates
    // when several episodes share that filename (a multi-episode file).
    get("/tv/image/{itemId}/still/{epFilename}") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val epFilename = call.parameters["epFilename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width = call.request.queryParameters["w"]?.toIntOrNull()
        val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
        val result = svc.serveStill(itemId, epFilename, epNum, width) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
    // R194: a season's own poster — for the player's OS media-session artwork (R193). 404s (no auth
    // fallback needed) when the season has no poster on disk; the client falls back to the series poster.
    get("/tv/image/{itemId}/season/{season}/poster") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val season = call.parameters["season"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width = call.request.queryParameters["w"]?.toIntOrNull()
        val result = svc.serveSeasonPoster(itemId, season, width) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
    // R133: user avatar — the one remaining (cached) Jellyfin fetch; reused by the admin pairing UI.
    get("/tv/image/user/{userId}/avatar") {
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        // Phase 187 (FR-187-7) — the cache-busting tag, when the caller has one (RaviloImageUrl.avatar).
        val tag = call.request.queryParameters["v"]
        val result = svc.serveAvatar(userId, tag) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }

    // Admin (cookie) uploads + lists; the serve route is public (AuthPlugin OPEN_API_PATHS) so the TV
    // can load a channel's logoUrl without a device token — brand logos are not sensitive.
    get("/tv/admin/channel-logos") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(channelLogoStore.list())
    }
    post("/tv/admin/channel-logos") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = runCatching { call.receive<ChannelLogoUpload>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid upload")); return@post
        }
        val data = runCatching {
            @OptIn(ExperimentalEncodingApi::class)
            Base64.decode(req.dataBase64.substringAfterLast(",")) // tolerate a data: URL prefix
        }.getOrNull()
        if (data == null || data.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid base64 data")); return@post
        }
        val logo = channelLogoStore.save(req.filename, data)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported file — use PNG/SVG/JPG/WebP up to 2 MB"))
        call.respond(logo)
    }
    // Public serve — see AuthPlugin OPEN_API_PATHS.
    get("/tv/channel-logos/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val data = channelLogoStore.read(name) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(data, ContentType.parse(channelLogoStore.contentType(name)))
    }

    put("/tv/settings") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<ViewerSettingsRequest>()
        raviloConfigService.applyViewerSettings(
            userId = device.jellyfinUserId,
            skin = req.skin,
            showContinueProgress = req.showContinueProgress,
            autoplayNext = req.autoplayNext,
            tileShape = req.tileShape,
            uiLanguage = req.uiLanguage,
        )
        call.respond(mapOf("status" to "ok"))
    }
}
