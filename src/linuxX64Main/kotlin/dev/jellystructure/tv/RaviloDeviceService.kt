package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.db.JellystructureDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

// Phase R86-B: avoid a SQLite SELECT + UPDATE on every TV API call by caching validated tokens.
private const val TOKEN_CACHE_TTL_MS  = 5 * 60_000L  // serve cached DeviceData for 5 min
private const val LAST_SEEN_DEBOUNCE_MS = 60_000L      // write updateLastSeen at most once/min

// Phase 142 — GUIDs from Jellyfin's Policy and from /Library/VirtualFolders can differ in dashing/case
// across server versions; normalize once at every boundary (here, and in MediaStore.visibleTo) so a
// formatting difference never makes a restricted user's catalog wrongly empty. `internal` so MediaStore
// (a different package, same module) reuses this exact definition instead of a second copy that could drift.
internal fun normalizeGuid(id: String): String = id.replace("-", "").lowercase()
private fun encodeAllowedLibraries(ids: Set<String>?): String? = ids?.joinToString(",")
private fun decodeAllowedLibraries(raw: String?): Set<String>? =
    raw?.split(",")?.filter { it.isNotBlank() }?.toSet()

// Phase 142 follow-up — tag-based restriction (Jellyfin Policy AllowedTags/BlockedTags), stored the
// same comma-joined way as allowed_libraries; lowercased so a casing difference never lets a blocked
// tag through or hides an allowed one.
private fun encodeTags(tags: Set<String>): String? = tags.takeIf { it.isNotEmpty() }?.joinToString(",")
private fun decodeTags(raw: String?): Set<String> =
    raw?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

/** Phase 259 (FR-259-6) — one version a device was seen on. [observed] is false for the migration's seed row. */
data class DeviceVersionSeen(val appVersion: String, val platform: String?, val firstSeenAt: Long, val observed: Boolean)

/** Phase 185 — a device's persisted decode ceiling (bps), per codec, plus when it was last measured
 *  (epoch millis). Every field null means "not measured yet" (FR-185-2). */
data class DeviceDecodeCapabilities(
    val hevcMaxBitrate: Long?,
    val h264MaxBitrate: Long?,
    val measuredAt: Long?,
)

/**
 * Phase 258 — one Jellyfin user's policy in the exact shape `ravilo_device` stores it: library GUIDs
 * normalised, tags lowercased, `EnableAllFolders` ⇒ `null` (unrestricted), `MaxParentalRating != null` ⇒
 * kids. Built from a live `/Users` policy by [of] and from a stored row by [of], so the two compare equal
 * whenever they mean the same thing and a reconciler pass never "changes" a row into what it already was.
 */
data class DevicePolicy(
    val allowedLibraries: Set<String>?,
    val allowedTags: Set<String>,
    val blockedTags: Set<String>,
    val isAdmin: Boolean,
    val isKids: Boolean,
) {
    companion object {
        /** The four rules of `TvRoutes`' login, copied rather than referenced (dev review item 4). */
        fun of(policy: dev.jellystructure.auth.JellyfinPolicy): DevicePolicy = DevicePolicy(
            allowedLibraries = if (policy.enableAllFolders) null else policy.enabledFolders.map { normalizeGuid(it) }.toSet(),
            allowedTags = policy.allowedTags.map { it.lowercase() }.toSet(),
            blockedTags = policy.blockedTags.map { it.lowercase() }.toSet(),
            isAdmin = policy.isAdministrator,
            isKids = policy.maxParentalRating != null,
        )

        fun of(row: DeviceData): DevicePolicy =
            DevicePolicy(row.allowedLibraries, row.allowedTags, row.blockedTags, row.isAdmin, row.isKids)
    }
}

/** Phase 258 (FR-258-7) — one (row, field) that differed from Jellyfin, in the words the log line uses. */
data class PolicyChange(val deviceId: String, val deviceName: String, val field: String, val from: String, val to: String)

/** Phase 258 — which of [row]'s five policy fields disagree with [live]; empty when the row is current. */
fun policyChanges(row: DeviceData, live: DevicePolicy): List<PolicyChange> {
    val stored = DevicePolicy.of(row)
    fun libs(s: Set<String>?) = s?.let { if (it.isEmpty()) "none" else it.sorted().joinToString(",") { id -> id.take(8) } } ?: "all"
    fun tags(s: Set<String>) = if (s.isEmpty()) "none" else s.sorted().joinToString(",")
    return buildList {
        if (stored.allowedLibraries != live.allowedLibraries) add(PolicyChange(row.deviceId, row.displayName, "libraries", libs(stored.allowedLibraries), libs(live.allowedLibraries)))
        if (stored.allowedTags != live.allowedTags) add(PolicyChange(row.deviceId, row.displayName, "allowed tags", tags(stored.allowedTags), tags(live.allowedTags)))
        if (stored.blockedTags != live.blockedTags) add(PolicyChange(row.deviceId, row.displayName, "blocked tags", tags(stored.blockedTags), tags(live.blockedTags)))
        if (stored.isAdmin != live.isAdmin) add(PolicyChange(row.deviceId, row.displayName, "admin", stored.isAdmin.toString(), live.isAdmin.toString()))
        if (stored.isKids != live.isKids) add(PolicyChange(row.deviceId, row.displayName, "kids", stored.isKids.toString(), live.isKids.toString()))
    }
}

class RaviloDeviceService(private val db: JellystructureDb) {

    // token → (DeviceData, cachedAtMs, lastSeenWrittenMs)
    private data class TokenEntry(val data: DeviceData, val cachedAt: Long, var lastSeenWritten: Long)
    private val tokenCache = HashMap<String, TokenEntry>()

    /**
     * Phase 141 — direct username/password sign-in (`POST /api/tv/login`): upserts the `(deviceId,
     * jellyfinUserId)` row directly, bypassing the retired `ravilo_pairing` challenge table entirely.
     * Reuses an existing row's `device_token`/`created_at` (via [existingDeviceId]'s counterpart lookup)
     * so a re-login doesn't churn the client's stored token; mints a fresh one for a first-time sign-in.
     */
    fun loginDevice(
        deviceId: String,
        deviceName: String?,
        jellyfinUserId: String,
        jellyfinUsername: String,
        jellyfinUserToken: String,
        isAdmin: Boolean,
        isKids: Boolean,
        // Phase 142 — this user's allowed library set (null = unrestricted), resolved from the Jellyfin
        // policy returned inline by AuthenticateByName. Pass RAW ids — normalized once here, at the
        // boundary where they enter the system. Refreshed on every login so a Jellyfin-side access
        // change catches up the next time the viewer signs in.
        allowedLibraries: Set<String>? = null,
        // Phase 142 follow-up — same policy response, its AllowedTags/BlockedTags. Pass RAW (any case);
        // lowercased once here.
        allowedTags: Set<String> = emptySet(),
        blockedTags: Set<String> = emptySet(),
        // Phase 224 (FR-224-2) — from the login request's X-Ravilo-* headers. Null keeps what the row
        // already knew (an older client signing in again must not blank a newer client's report).
        appVersion: String? = null,
        platform: String? = null,
        // Phase 236 (FR-236-1) — tv | phone | web | cast | screen. Kept on a re-login the same way
        // appVersion/platform are (an older caller passing the historical default must not downgrade an
        // already-known kind).
        kind: String? = null,
    ): Pair<DeviceData, String> {
        val normalizedAllowed = allowedLibraries?.map { normalizeGuid(it) }?.toSet()
        val normalizedAllowedTags = allowedTags.map { it.lowercase() }.toSet()
        val normalizedBlockedTags = blockedTags.map { it.lowercase() }.toSet()
        val now = nowMs()
        val existing = db.raviloDeviceQueries.getByDeviceAndUser(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
            .executeAsOneOrNull()
        val deviceToken = existing?.device_token ?: generateSecureToken()
        val createdAt = existing?.created_at ?: now
        val displayName = deviceName?.take(80)?.ifBlank { null }
            ?: existing?.display_name?.takeIf { it.isNotBlank() }
            ?: "Ravilo TV ${deviceId.take(6)}"
        val resolvedKind = kind ?: existing?.kind ?: "tv"
        val seenVersion = appVersion?.trim()?.take(64)?.ifBlank { null }
        val seenPlatform = platform?.trim()?.take(64)?.ifBlank { null }
        // Phase 259 (FR-259-2, dev review item 2) — the row and its history row land together or not at all.
        db.transaction {
        db.raviloDeviceQueries.insertDevice(
            device_id = deviceId,
            jellyfin_user_id = jellyfinUserId,
            jellyfin_username = jellyfinUsername,
            jellyfin_user_token = jellyfinUserToken,
            is_admin = if (isAdmin) 1L else 0L,
            is_kids = if (isKids) 1L else 0L,
            device_token = deviceToken,
            display_name = displayName,
            created_at = createdAt,
            last_seen = now,
            allowed_libraries = encodeAllowedLibraries(normalizedAllowed),
            allowed_tags = encodeTags(normalizedAllowedTags),
            blocked_tags = encodeTags(normalizedBlockedTags),
            app_version = appVersion ?: existing?.app_version,
            platform = platform ?: existing?.platform,
            kind = resolvedKind,
            policy_refreshed_at = now,   // Phase 258 (FR-258-5/6) — the AuthenticateByName policy is the freshest copy there is
        )
        if (seenVersion != null) recordVersionSeen(deviceId, seenVersion, seenPlatform, now)
        }
        // Force a fresh DB read on the next validateDeviceToken call — the token/policy may have
        // changed even though the device_token itself was reused (re-login as the same user).
        tokenCache.remove(deviceToken)
        return Pair(
            DeviceData(
                deviceId = deviceId,
                deviceToken = deviceToken,
                jellyfinUserId = jellyfinUserId,
                jellyfinUsername = jellyfinUsername,
                jellyfinUserToken = jellyfinUserToken,
                isAdmin = isAdmin,
                isKids = isKids,
                displayName = displayName,
                lastSeen = now,
                createdAt = createdAt,
                allowedLibraries = normalizedAllowed,
                allowedTags = normalizedAllowedTags,
                blockedTags = normalizedBlockedTags,
                appVersion = appVersion ?: existing?.app_version,
                platform = platform ?: existing?.platform,
                kind = resolvedKind,
                lastPublicAddress = existing?.last_public_address,
            ).also { DeviceIdentityRegistry.remember(it) },
            deviceToken,
        )
    }

    /**
     * Phase 224 (FR-224-2) — [appVersion]/[platform] are the request's X-Ravilo-* headers (null when the
     * client sent none). Compared against the cached row and written only when the pair differs: one
     * write per change, never per request, next to the once-a-minute last_seen write.
     */
    fun validateDeviceToken(token: String, appVersion: String? = null, platform: String? = null): DeviceData? {
        val now = nowMs()
        // Cache hit within TTL: skip the DB SELECT.
        tokenCache[token]?.let { entry ->
            if ((now - entry.cachedAt) < TOKEN_CACHE_TTL_MS) {
                // Debounce updateLastSeen: at most once per minute per token.
                if ((now - entry.lastSeenWritten) >= LAST_SEEN_DEBOUNCE_MS) {
                    db.raviloDeviceQueries.updateLastSeen(last_seen = now, device_token = token)
                    entry.lastSeenWritten = now
                }
                val data = recordAppInfo(entry.data, appVersion, platform)
                if (data !== entry.data) tokenCache[token] = TokenEntry(data, entry.cachedAt, entry.lastSeenWritten)
                DeviceIdentityRegistry.remember(data)
                return data
            }
        }
        // Cache miss or expired: hit DB.
        val row = db.raviloDeviceQueries.getByToken(token).executeAsOneOrNull() ?: run {
            tokenCache.remove(token)
            return null
        }
        db.raviloDeviceQueries.updateLastSeen(last_seen = now, device_token = token)
        val data = DeviceData(
            deviceId = row.device_id,
            deviceToken = token,
            jellyfinUserId = row.jellyfin_user_id,
            jellyfinUsername = row.jellyfin_username,
            jellyfinUserToken = row.jellyfin_user_token,
            isAdmin = row.is_admin == 1L,
            isKids = row.is_kids == 1L,
            displayName = row.display_name.ifBlank { "Ravilo TV ${row.device_id.take(6)}" },
            lastSeen = row.last_seen,
            createdAt = row.created_at,
            allowedLibraries = decodeAllowedLibraries(row.allowed_libraries),
            allowedTags = decodeTags(row.allowed_tags),
            blockedTags = decodeTags(row.blocked_tags),
            appVersion = row.app_version,
            platform = row.platform,
            kind = row.kind,
            lastPublicAddress = row.last_public_address,
        ).let { recordAppInfo(it, appVersion, platform) }
        tokenCache[token] = TokenEntry(data, now, now)
        DeviceIdentityRegistry.remember(data)
        return data
    }

    /** Phase 224 (FR-224-2) — the change-only write. A request that says nothing changes nothing; a
     *  request whose pair matches the row is a no-op; only a genuinely different pair reaches the DB. */
    private fun recordAppInfo(data: DeviceData, appVersion: String?, platform: String?): DeviceData {
        val v = appVersion?.trim()?.take(64)?.ifBlank { null } ?: return data
        val p = platform?.trim()?.take(64)?.ifBlank { null }
        if (v == data.appVersion && p == data.platform) return data
        // Phase 259 (FR-259-2, dev review item 2) — one transaction: a crash between the two statements
        // could otherwise leave a version without its history row.
        db.transaction {
            db.raviloDeviceQueries.updateAppInfo(app_version = v, platform = p, device_token = data.deviceToken)
            recordVersionSeen(data.deviceId, v, p, nowMs())
        }
        return data.copy(appVersion = v, platform = p)
    }

    /**
     * Phase 259 (FR-259-2/3) — one history row per CHANGE of the device's version, dated when this request
     * carried it (never the install time). Keyed by device, not viewer: a second viewer's row catching up to
     * a version the device already reported writes nothing. Callers run this inside their own transaction.
     */
    private fun recordVersionSeen(deviceId: String, version: String, platform: String?, now: Long) {
        val latest = db.raviloDeviceVersionQueries.latestForDevice(deviceId).executeAsOneOrNull()
        if (latest?.app_version == version) return
        db.raviloDeviceVersionQueries.insertVersion(device_id = deviceId, app_version = version, platform = platform, first_seen_at = now, observed = 1L)
    }

    /** Phase 259 (FR-259-5, dev review item 4) — after any of the three revoke paths: a device with no
     *  `ravilo_device` row left has nothing to show a history on, so the history goes with the last row. */
    private fun dropHistoryIfGone(deviceId: String) {
        if (db.raviloDeviceQueries.getByDevice(deviceId).executeAsList().isEmpty()) {
            db.raviloDeviceVersionQueries.deleteForDevice(deviceId)
        }
    }

    /** Phase 259 (FR-259-6) — every version this device has been seen on, newest first. */
    fun versionHistory(deviceId: String): List<DeviceVersionSeen> =
        db.raviloDeviceVersionQueries.historyForDevice(deviceId).executeAsList().map {
            DeviceVersionSeen(appVersion = it.app_version, platform = it.platform, firstSeenAt = it.first_seen_at, observed = it.observed == 1L)
        }

    /** Phase 236 (FR-236-6) — stamped on an events-socket open and a playback/status post, never on
     *  every request (a screen doesn't move networks mid-session, and this would otherwise be a DB
     *  write on the hottest path in the app). Best-effort: an empty/blank address is a no-op, not a
     *  clear — a proxy that occasionally fails to set the header must not un-group an already-placed TV. */
    fun recordAddress(deviceId: String, jellyfinUserId: String, address: String?) {
        val a = address?.trim()?.ifBlank { null } ?: return
        db.raviloDeviceQueries.updatePublicAddress(last_public_address = a, device_id = deviceId, jellyfin_user_id = jellyfinUserId)
    }

    fun unpair(deviceToken: String) {
        tokenCache.remove(deviceToken)?.let { DeviceIdentityRegistry.forget(it.data.jellyfinUserToken) }
        val deviceId = db.raviloDeviceQueries.getByToken(deviceToken).executeAsOneOrNull()?.device_id
        db.raviloDeviceQueries.deleteByToken(deviceToken)
        deviceId?.let { dropHistoryIfGone(it) }   // Phase 259 (FR-259-5)
    }

    /**
     * Phase 258 (FR-258-1/2/4) — make every row of [jellyfinUserId] carry [live], Jellyfin's policy for that
     * user as it is now. Returns one [PolicyChange] per (row, field) that actually differed — empty when every
     * row already agreed, in which case only `policy_refreshed_at` is stamped (FR-258-6) and nothing is logged
     * by the caller (FR-258-7). A change rewrites all of the user's rows in one statement (the phone, the
     * screens and cast receivers it minted, the TVs — dev review item 3) and evicts every one of their tokens
     * from [tokenCache] (dev review item 1): `validateDeviceToken` serves `DeviceData` from that cache for
     * five minutes, and every feed cache keys on the `DeviceData` it is handed, so without the eviction a
     * rewritten row is invisible for exactly the interval this phase exists to remove. `remove` only — the
     * Jellyfin user token has not changed, so `DeviceIdentityRegistry` keeps its entry.
     *
     * [live] must already be normalised the way `loginDevice` stores its fields ([DevicePolicy.of] does
     * that) — comparing raw Jellyfin values against stored ones would see a difference on every row every
     * pass (dev review item 4).
     */
    fun refreshPolicy(jellyfinUserId: String, live: DevicePolicy, now: Long = nowMs()): List<PolicyChange> {
        val rows = listByUser(jellyfinUserId)
        if (rows.isEmpty()) return emptyList()
        val changes = rows.flatMap { policyChanges(it, live) }
        if (changes.isEmpty()) {
            db.raviloDeviceQueries.stampPolicyRefreshed(policy_refreshed_at = now, jellyfin_user_id = jellyfinUserId)
            return changes
        }
        db.raviloDeviceQueries.updatePolicy(
            allowed_libraries = encodeAllowedLibraries(live.allowedLibraries),
            allowed_tags = encodeTags(live.allowedTags),
            blocked_tags = encodeTags(live.blockedTags),
            is_admin = if (live.isAdmin) 1L else 0L,
            is_kids = if (live.isKids) 1L else 0L,
            policy_refreshed_at = now,
            jellyfin_user_id = jellyfinUserId,
        )
        rows.forEach { tokenCache.remove(it.deviceToken) }
        return changes
    }

    /** Lists all users currently signed in on [deviceId]. */
    fun listSessions(deviceId: String): List<DeviceData> =
        db.raviloDeviceQueries.getByDevice(deviceId).executeAsList().map { row ->
            DeviceData(
                deviceId = row.device_id,
                deviceToken = row.device_token,
                jellyfinUserId = row.jellyfin_user_id,
                jellyfinUsername = row.jellyfin_username,
                jellyfinUserToken = row.jellyfin_user_token,
                isAdmin = row.is_admin == 1L,
                isKids = row.is_kids == 1L,
                displayName = row.display_name.ifBlank { "Ravilo TV ${row.device_id.take(6)}" },
                lastSeen = row.last_seen,
                createdAt = row.created_at,
                allowedLibraries = decodeAllowedLibraries(row.allowed_libraries),
                allowedTags = decodeTags(row.allowed_tags),
                blockedTags = decodeTags(row.blocked_tags),
                appVersion = row.app_version,
                platform = row.platform,
                kind = row.kind,
                lastPublicAddress = row.last_public_address,
            )
        }

    /** Removes a specific user's session from [deviceId] without affecting others. */
    fun removeSession(deviceId: String, jellyfinUserId: String) {
        // Security fix (2026-08-02 review, finding M3) — this deleted the DB row but never touched
        // tokenCache, so a revoked device token kept passing validateDeviceToken() (served from cache)
        // for up to TOKEN_CACHE_TTL_MS (5 minutes) after the operator removed it. unpair() already got
        // this right; this one and deleteAllForUser below didn't. Look the token up before the DB row
        // is gone so the cache entry can be dropped too.
        db.raviloDeviceQueries.getByDeviceAndUser(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
            .executeAsOneOrNull()?.let { tokenCache.remove(it.device_token); DeviceIdentityRegistry.forget(it.jellyfin_user_token) }
        db.raviloDeviceQueries.deleteByDeviceAndUser(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
        dropHistoryIfGone(deviceId)   // Phase 259 (FR-259-5) — one viewer of two keeps it; the last takes it
    }

    /** Phase 143 — every device row across every user, for the Users & Devices admin overview.
     *  Phase 224 (FR-224-4): every row resolved here is registered too, so a token a caller then hands to
     *  Jellyfin travels under its own device's identity. */
    fun allDevices(): List<DeviceData> =
        db.raviloDeviceQueries.getAllDevices().executeAsList().map { row ->
            DeviceData(
                deviceId = row.device_id,
                deviceToken = row.device_token,
                jellyfinUserId = row.jellyfin_user_id,
                jellyfinUsername = row.jellyfin_username,
                jellyfinUserToken = row.jellyfin_user_token,
                isAdmin = row.is_admin == 1L,
                isKids = row.is_kids == 1L,
                displayName = row.display_name.ifBlank { "Ravilo TV ${row.device_id.take(6)}" },
                lastSeen = row.last_seen,
                createdAt = row.created_at,
                allowedLibraries = decodeAllowedLibraries(row.allowed_libraries),
                allowedTags = decodeTags(row.allowed_tags),
                blockedTags = decodeTags(row.blocked_tags),
                appVersion = row.app_version,
                platform = row.platform,
                kind = row.kind,
                lastPublicAddress = row.last_public_address,
            ).also { DeviceIdentityRegistry.remember(it) }
        }

    /** Phase 143 — "sign out everywhere": every device row this Jellyfin user has ever signed into. */
    fun deleteAllForUser(jellyfinUserId: String) {
        // Security fix (2026-08-02 review, finding M3) — same tokenCache gap as removeSession above:
        // "sign out everywhere" reported success while every signed-out device token kept working for
        // up to 5 more minutes, served straight from the cache.
        val rows = db.raviloDeviceQueries.getByUser(jellyfin_user_id = jellyfinUserId).executeAsList()
        rows.forEach { tokenCache.remove(it.device_token); DeviceIdentityRegistry.forget(it.jellyfin_user_token) }
        db.raviloDeviceQueries.deleteByUser(jellyfin_user_id = jellyfinUserId)
        rows.map { it.device_id }.distinct().forEach { dropHistoryIfGone(it) }   // Phase 259 (FR-259-5)
    }

    /**
     * Phase 185 (FR-185-1) — persists the R216 decode ceiling this device just reported, keyed per
     * codec (not one shared value — see the migration's own doc for why a single slot is wrong on a TV
     * that always transcodes to AVC). Called on every playback negotiation; a no-op when the client
     * reported neither ceiling (an unmeasured client, or one that hasn't started a session on this
     * build yet — FR-185-2's two permanent unknown states, left untouched rather than zeroed).
     */
    fun recordDecodeCapabilities(deviceId: String, jellyfinUserId: String, hevcMaxBitrate: Long?, h264MaxBitrate: Long?) {
        if (hevcMaxBitrate == null && h264MaxBitrate == null) return
        db.raviloDeviceQueries.updateDecodeCapabilities(
            decode_max_bitrate_hevc = hevcMaxBitrate,
            decode_max_bitrate_h264 = h264MaxBitrate,
            decode_measured_at = nowMs(),
            device_id = deviceId,
            jellyfin_user_id = jellyfinUserId,
        )
    }

    /** Phase 185 (FR-185-5/FR-185-8) — this device's persisted decode ceiling. Both fields null means
     *  FR-185-2's "not measured yet" (or "not measured", for a client that structurally never can) —
     *  the two are indistinguishable from stored state alone; callers needing to tell them apart use
     *  [DeviceData] context (e.g. a known-web session) the way the admin row already does. */
    fun decodeCapabilities(deviceId: String, jellyfinUserId: String): DeviceDecodeCapabilities? =
        db.raviloDeviceQueries.getDecodeCapabilities(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
            .executeAsOneOrNull()?.let {
                DeviceDecodeCapabilities(
                    hevcMaxBitrate = it.decode_max_bitrate_hevc,
                    h264MaxBitrate = it.decode_max_bitrate_h264,
                    measuredAt = it.decode_measured_at,
                )
            }

    /** Phase 111 — every device paired to [jellyfinUserId] (remote-control device list / D.1's admin
     *  Ravilo config editor device list). */
    fun listByUser(jellyfinUserId: String): List<DeviceData> =
        db.raviloDeviceQueries.getByUser(jellyfinUserId).executeAsList().map { row ->
            DeviceData(
                deviceId = row.device_id,
                deviceToken = row.device_token,
                jellyfinUserId = row.jellyfin_user_id,
                jellyfinUsername = row.jellyfin_username,
                jellyfinUserToken = row.jellyfin_user_token,
                isAdmin = row.is_admin == 1L,
                isKids = row.is_kids == 1L,
                displayName = row.display_name.ifBlank { "Ravilo TV ${row.device_id.take(6)}" },
                lastSeen = row.last_seen,
                createdAt = row.created_at,
                allowedLibraries = decodeAllowedLibraries(row.allowed_libraries),
                allowedTags = decodeTags(row.allowed_tags),
                blockedTags = decodeTags(row.blocked_tags),
                appVersion = row.app_version,
                platform = row.platform,
                kind = row.kind,
                lastPublicAddress = row.last_public_address,
            )
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
