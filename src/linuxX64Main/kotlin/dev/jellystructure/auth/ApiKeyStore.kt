package dev.jellystructure.auth

import dev.jellystructure.db.JellystructureDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

data class ApiKeyData(val id: String, val name: String, val jellyfinUserId: String, val jellyfinUsername: String)

data class ApiKeyRow(
    val id: String,
    val name: String,
    val jellyfinUserId: String,
    val jellyfinUsername: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val revoked: Boolean,
)

/** Phase 111 — jellystructure-issued API keys for external tools, bound to a Jellyfin user and fenced
 *  to `/api/remote/**` by AuthPlugin. Only [sha256Hex] of the plaintext key is ever stored. */
class ApiKeyStore(private val db: JellystructureDb) {
    // O(1)-cached like device tokens (Phase 111 FR A.3) — most requests hit this, not the DB.
    private data class CacheEntry(val data: ApiKeyData, val cachedAt: Long)
    private val cache = HashMap<String, CacheEntry>()
    private val cacheTtlMs = 5 * 60_000L

    fun create(name: String, jellyfinUserId: String, jellyfinUsername: String): String {
        val plaintext = "jsk_" + generateSecureToken()
        val id = generateSecureToken()
        db.apiKeyQueries.insert(
            id = id,
            name = name,
            jellyfin_user_id = jellyfinUserId,
            jellyfin_username = jellyfinUsername,
            token_hash = sha256Hex(plaintext),
            created_at = nowMs() / 1000,
        )
        return plaintext
    }

    /** Validates [token] against the stored hash; touches last-used (debounced via the cache) and
     *  returns the bound identity, or null if unknown/revoked. */
    fun validate(token: String): ApiKeyData? {
        val now = nowMs()
        cache[token]?.let { entry ->
            if ((now - entry.cachedAt) < cacheTtlMs) return entry.data
        }
        val hash = sha256Hex(token)
        val row = db.apiKeyQueries.findByHash(hash).executeAsOneOrNull() ?: run { cache.remove(token); return null }
        db.apiKeyQueries.touchLastUsed(last_used_at = now / 1000, id = row.id)
        val data = ApiKeyData(row.id, row.name, row.jellyfin_user_id, row.jellyfin_username)
        cache[token] = CacheEntry(data, now)
        return data
    }

    fun list(): List<ApiKeyRow> = db.apiKeyQueries.listAll().executeAsList().map {
        ApiKeyRow(it.id, it.name, it.jellyfin_user_id, it.jellyfin_username, it.created_at, it.last_used_at, it.revoked == 1L)
    }

    fun revoke(id: String) {
        db.apiKeyQueries.revoke(id)
        cache.entries.removeAll { it.value.data.id == id }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
