package dev.jellystructure.auth

import dev.jellystructure.db.JellystructureDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.CLOCK_REALTIME
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.clock_gettime
import platform.posix.open
import platform.posix.read
import platform.posix.timespec

private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000
// Phase 143: mirrors RaviloDeviceService.validateDeviceToken's inline debounce — validate() is called
// on every cookie-authed admin request, so writing last_used_at unconditionally would be a SQLite
// UPDATE per request.
private const val LAST_USED_DEBOUNCE_MS = 60_000L

class SessionService(private val db: JellystructureDb) {

    // token -> last time last_used_at was actually written (debounce state; not a data cache like
    // RaviloDeviceService's, since a session's own row rarely changes underneath it).
    private val lastUsedWritten = HashMap<String, Long>()

    init {
        // Remove expired sessions on startup
        db.sessionQueries.deleteExpired(nowMs())
    }

    fun create(
        jellyfinUserId: String,
        jellyfinUsername: String,
        jellyfinUserToken: String,
    ): String {
        val token = generateSecureToken()
        val now = nowMs()
        db.sessionQueries.upsert(
            token = token,
            jellyfin_user_id = jellyfinUserId,
            jellyfin_username = jellyfinUsername,
            jellyfin_user_token = jellyfinUserToken,
            expires_at = now + SESSION_TTL_MS,
            created_at = now,
            last_used_at = now,
        )
        return token
    }

    fun validate(token: String): SessionData? {
        val row = db.sessionQueries.getByToken(token).executeAsOneOrNull() ?: return null
        if (row.expires_at < nowMs()) return null
        val now = nowMs()
        if ((now - (lastUsedWritten[token] ?: 0L)) >= LAST_USED_DEBOUNCE_MS) {
            db.sessionQueries.updateLastUsed(last_used_at = now, token = token)
            lastUsedWritten[token] = now
        }
        return SessionData(
            token = row.token,
            jellyfinUserId = row.jellyfin_user_id,
            jellyfinUsername = row.jellyfin_username,
            jellyfinUserToken = row.jellyfin_user_token,
            expiresAt = row.expires_at,
            createdAt = row.created_at,
            lastUsedAt = row.last_used_at,
        )
    }

    fun revoke(token: String) {
        lastUsedWritten.remove(token)
        db.sessionQueries.deleteByToken(token)
    }

    /** Phase 143 — "sign out everywhere": every admin web session this Jellyfin user holds. */
    fun revokeAllForUser(jellyfinUserId: String) {
        db.sessionQueries.deleteByUser(jellyfinUserId)
    }

    /** Phase 143 — every live admin web session, for the Users & Devices overview. */
    fun list(): List<SessionData> = db.sessionQueries.getAll().executeAsList().map { row ->
        SessionData(
            token = row.token,
            jellyfinUserId = row.jellyfin_user_id,
            jellyfinUsername = row.jellyfin_username,
            jellyfinUserToken = row.jellyfin_user_token,
            expiresAt = row.expires_at,
            createdAt = row.created_at,
            lastUsedAt = row.last_used_at,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
fun generateSecureToken(): String {
    val bytes = ByteArray(32)
    bytes.usePinned { pinned ->
        val fd = open("/dev/urandom", O_RDONLY)
        read(fd, pinned.addressOf(0), 32.convert())
        close(fd)
    }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
