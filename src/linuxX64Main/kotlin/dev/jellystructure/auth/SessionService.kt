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

// Security fix (2026-08-02 review, finding H6) — every secret in this system (admin session tokens,
// Ravilo device tokens, API keys, the *arr webhook secret) is minted here. The old code never checked
// open()/read()'s return values: on EMFILE — which this process can legitimately hit, since it runs
// against a hard 1024-FD ceiling with a documented history of FD-exhaustion incidents — open() returns
// -1, read(-1, ...) also returns -1, and the ByteArray stays zero-initialized. The old code silently
// returned the constant 64-zero-character string with no exception, no log: a predictable "secret"
// minted at exactly the moment load (and likely login attempts) is highest. Fail loudly instead — a
// thrown exception here is far better than a live security hole with no signal.
@OptIn(ExperimentalForeignApi::class)
fun generateSecureToken(): String {
    val bytes = ByteArray(32)
    val bytesRead = bytes.usePinned { pinned ->
        val fd = open("/dev/urandom", O_RDONLY)
        check(fd >= 0) { "generateSecureToken: failed to open /dev/urandom (fd=$fd)" }
        try {
            read(fd, pinned.addressOf(0), 32.convert())
        } finally {
            close(fd)
        }
    }
    check(bytesRead == 32L) {
        "generateSecureToken: short/failed read from /dev/urandom ($bytesRead of 32 bytes) — refusing to mint a weak token"
    }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
