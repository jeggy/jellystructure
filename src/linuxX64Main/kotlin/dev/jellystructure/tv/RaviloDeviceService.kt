package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
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
        )
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
            ),
            deviceToken,
        )
    }

    fun validateDeviceToken(token: String): DeviceData? {
        val now = nowMs()
        // Cache hit within TTL: skip the DB SELECT.
        tokenCache[token]?.let { entry ->
            if ((now - entry.cachedAt) < TOKEN_CACHE_TTL_MS) {
                // Debounce updateLastSeen: at most once per minute per token.
                if ((now - entry.lastSeenWritten) >= LAST_SEEN_DEBOUNCE_MS) {
                    db.raviloDeviceQueries.updateLastSeen(last_seen = now, device_token = token)
                    entry.lastSeenWritten = now
                }
                return entry.data
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
        )
        tokenCache[token] = TokenEntry(data, now, now)
        return data
    }

    fun unpair(deviceToken: String) {
        tokenCache.remove(deviceToken)
        db.raviloDeviceQueries.deleteByToken(deviceToken)
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
            .executeAsOneOrNull()?.let { tokenCache.remove(it.device_token) }
        db.raviloDeviceQueries.deleteByDeviceAndUser(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
    }

    /** Phase 143 — every device row across every user, for the Users & Devices admin overview. */
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
            )
        }

    /** Phase 143 — "sign out everywhere": every device row this Jellyfin user has ever signed into. */
    fun deleteAllForUser(jellyfinUserId: String) {
        // Security fix (2026-08-02 review, finding M3) — same tokenCache gap as removeSession above:
        // "sign out everywhere" reported success while every signed-out device token kept working for
        // up to 5 more minutes, served straight from the cache.
        db.raviloDeviceQueries.getByUser(jellyfin_user_id = jellyfinUserId).executeAsList()
            .forEach { tokenCache.remove(it.device_token) }
        db.raviloDeviceQueries.deleteByUser(jellyfin_user_id = jellyfinUserId)
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
            )
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
