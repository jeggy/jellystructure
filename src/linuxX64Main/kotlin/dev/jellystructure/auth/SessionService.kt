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

class SessionService(private val db: JellystructureDb) {

    init {
        // Remove expired sessions on startup
        db.sessionQueries.deleteExpired(nowMs())
    }

    suspend fun create(
        jellyfinUserId: String,
        jellyfinUsername: String,
        jellyfinUserToken: String,
    ): String {
        val token = generateSecureToken()
        db.sessionQueries.upsert(
            token = token,
            jellyfin_user_id = jellyfinUserId,
            jellyfin_username = jellyfinUsername,
            jellyfin_user_token = jellyfinUserToken,
            expires_at = nowMs() + SESSION_TTL_MS,
        )
        return token
    }

    fun validate(token: String): SessionData? {
        val row = db.sessionQueries.getByToken(token).executeAsOneOrNull() ?: return null
        if (row.expires_at < nowMs()) return null
        return SessionData(
            token = row.token,
            jellyfinUserId = row.jellyfin_user_id,
            jellyfinUsername = row.jellyfin_username,
            jellyfinUserToken = row.jellyfin_user_token,
            expiresAt = row.expires_at,
        )
    }

    suspend fun revoke(token: String) {
        db.sessionQueries.deleteByToken(token)
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
