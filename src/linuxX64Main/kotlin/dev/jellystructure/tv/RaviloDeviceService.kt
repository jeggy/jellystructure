package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.generateSecureToken
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

private const val PAIRING_TTL_MS = 5L * 60 * 1000
private val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

data class PairingStartResult(val code: String, val pollToken: String, val expiresAt: Long)

class RaviloDeviceService(private val db: JellystructureDb) {

    init {
        db.raviloPairingQueries.deleteExpired(nowMs())
    }

    fun startPairing(): PairingStartResult {
        val code = generateCode()
        val pollToken = generateSecureToken()
        val pairId = generateSecureToken()
        val expiresAt = nowMs() + PAIRING_TTL_MS
        db.raviloPairingQueries.insertPairing(
            pair_id = pairId,
            code = code,
            poll_token = pollToken,
            expires_at = expiresAt,
        )
        return PairingStartResult(code = code, pollToken = pollToken, expiresAt = expiresAt)
    }

    fun approvePairing(
        code: String,
        jellyfinUserId: String,
        jellyfinUsername: String,
        jellyfinUserToken: String,
        isAdmin: Boolean,
    ): Boolean {
        val now = nowMs()
        db.raviloPairingQueries.approve(
            jellyfin_user_id = jellyfinUserId,
            jellyfin_username = jellyfinUsername,
            jellyfin_user_token = jellyfinUserToken,
            is_admin = if (isAdmin) 1L else 0L,
            code = code,
            now = now,
        )
        return db.raviloPairingQueries.getByCode(code, now).executeAsOneOrNull()?.approved == 1L
    }

    /** Returns (DeviceData, deviceToken) once the challenge is approved, null while pending.
     *  [existingDeviceId] lets an already-paired device add a second user without changing its id. */
    fun pollPairing(pollToken: String, existingDeviceId: String? = null): Pair<DeviceData, String>? {
        val row = db.raviloPairingQueries.getByPollToken(pollToken, nowMs()).executeAsOneOrNull()
            ?: return null
        if (row.approved == 0L) return null
        val userId = row.jellyfin_user_id ?: return null
        val username = row.jellyfin_username ?: return null
        val userToken = row.jellyfin_user_token ?: return null

        val deviceId = existingDeviceId ?: generateSecureToken()
        val deviceToken = generateSecureToken()
        val now = nowMs()
        db.raviloDeviceQueries.insertDevice(
            device_id = deviceId,
            jellyfin_user_id = userId,
            jellyfin_username = username,
            jellyfin_user_token = userToken,
            is_admin = row.is_admin,
            device_token = deviceToken,
            display_name = "",
            created_at = now,
            last_seen = now,
        )
        db.raviloPairingQueries.consume(pollToken)

        return Pair(
            DeviceData(
                deviceId = deviceId,
                deviceToken = deviceToken,
                jellyfinUserId = userId,
                jellyfinUsername = username,
                jellyfinUserToken = userToken,
                isAdmin = row.is_admin == 1L,
            ),
            deviceToken,
        )
    }

    fun validateDeviceToken(token: String): DeviceData? {
        val row = db.raviloDeviceQueries.getByToken(token).executeAsOneOrNull() ?: return null
        db.raviloDeviceQueries.updateLastSeen(last_seen = nowMs(), device_token = token)
        return DeviceData(
            deviceId = row.device_id,
            deviceToken = token,
            jellyfinUserId = row.jellyfin_user_id,
            jellyfinUsername = row.jellyfin_username,
            jellyfinUserToken = row.jellyfin_user_token,
            isAdmin = row.is_admin == 1L,
        )
    }

    fun unpair(deviceToken: String) {
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
            )
        }

    /** Removes a specific user's session from [deviceId] without affecting others. */
    fun removeSession(deviceId: String, jellyfinUserId: String) {
        db.raviloDeviceQueries.deleteByDeviceAndUser(device_id = deviceId, jellyfin_user_id = jellyfinUserId)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun generateCode(): String {
        val bytes = ByteArray(6)
        bytes.usePinned { pinned ->
            val fd = open("/dev/urandom", O_RDONLY)
            read(fd, pinned.addressOf(0), 6.convert())
            close(fd)
        }
        // 32 chars in alphabet = 256 / 32 = 8 → no modulo bias
        return bytes.map { CODE_ALPHABET[(it.toInt() and 0xFF) % CODE_ALPHABET.size] }.joinToString("")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
