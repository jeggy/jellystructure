package dev.jellystructure.tv

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.CastCapability
import dev.jellystructure.shared.tv.CastHandoffResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Phase 218 (FR-218-8) — a cast start refused because `max_sessions` receivers are already playing.
 *  Mapped to phase 182's 503 + `Retry-After` in Server.kt; the receiver renders it as its busy state. */
class CastCeilingException(val retryAfterSeconds: Int, message: String) : Exception(message)

@Serializable
data class ReceiverCheck(val reachable: Boolean, val https: Boolean, val detail: String)

@Serializable
data class CastDeviceSummary(
    val name: String,
    @SerialName("last_seen") val lastSeen: Long,
)

/** Phase 218 (FR-218-4/7) — what the admin card's status list renders. `verified` is true only when a
 *  real cast has enrolled a receiver, because jellystructure cannot check an application id with Google. */
@Serializable
data class ChromecastStatus(
    val enabled: Boolean,
    @SerialName("app_id_set") val appIdSet: Boolean,
    @SerialName("receiver_url") val receiverUrl: String?,
    val verified: Boolean,
    val devices: List<CastDeviceSummary>,
    @SerialName("last_cast_at") val lastCastAt: Long?,
    @SerialName("max_sessions") val maxSessions: Int,
    @SerialName("active_sessions") val activeSessions: Int,
)

/**
 * Phase 218 — the server half of casting. Everything here is what makes a Chromecast receiver *its own
 * Ravilo device* (decision 2): it enrols by a hand-off code (FR-218-9) and from then on is a
 * `ravilo_device` like any TV — heartbeats, `/api/tv/playback/start`, progress, stop, QoE — so phase
 * 177 negotiation, the per-user ACL, `requireVisible()`, R183 pacing, phase 180 teardown and R216 QoE
 * all apply unchanged (FR-218-12). The receiver never holds a Jellyfin token; its row holds the
 * enrolling user's, server-side, exactly like a TV's row does.
 */
class CastService(
    private val db: JellystructureDb,
    private val configStore: ConfigStore,
    private val deviceService: RaviloDeviceService,
) {
    companion object {
        /** FR-218-10 — the receiver's `ravilo_device` display name starts with this, which is what
         *  `JellyfinDeviceIdentity.forDevice` shows the Jellyfin dashboard. Also how a device is
         *  recognised as a cast receiver for the session ceiling. */
        const val DEVICE_PREFIX = "Chromecast via Ravilo"
        /** FR-218-9 — "expires in minutes": long enough for a slow stick to boot the receiver page, short
         *  enough that a stale LOAD cannot enrol a device later (open question 4 — 5 min, stated). */
        const val HANDOFF_TTL_MS = 5 * 60_000L
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
        fun isCastDevice(device: DeviceData): Boolean = device.displayName.startsWith(DEVICE_PREFIX)
    }

    /** FR-218-3/11 — null unless enabled AND an 8-hex application id is set. Nothing derived from the
     *  network, a Cast route or a cached value ever enters this. */
    fun capability(cfg: AppConfig = configStore.current): CastCapability? {
        val cc = cfg.chromecast ?: return null
        if (!cc.enabled || !cc.hasAppId()) return null
        return CastCapability(appId = cc.appId.trim().uppercase(), receiverUrl = cc.receiverUrl())
    }

    fun maxSessions(): Int = configStore.current.chromecast?.effectiveMaxSessions() ?: 2

    // ── FR-218-9: hand-off ────────────────────────────────────────────────────

    fun mint(phone: DeviceData): CastHandoffResponse {
        val now = nowMs()
        db.castHandoffQueries.sweep(now)
        var code: String
        do {
            code = generateSecureToken().filter { it in CODE_ALPHABET.lowercase() || it in CODE_ALPHABET }.uppercase().take(6)
            if (code.length < 6) code = (code + "X".repeat(6)).take(6)
        } while (db.castHandoffQueries.getByCode(code).executeAsOneOrNull() != null)
        val expires = now + HANDOFF_TTL_MS
        db.castHandoffQueries.insert(code, phone.deviceId, phone.jellyfinUserId, now, expires)
        return CastHandoffResponse(code = code, expiresAt = expires, ttlSeconds = (HANDOFF_TTL_MS / 1000).toInt())
    }

    /**
     * Redeems [code] for the receiver's own device row + token. Returns null when the code is unknown,
     * expired, already used, or the minting phone's session is gone. [receiverId] reuses an existing
     * receiver row (storage survived between casts); absent ⇒ a fresh `cast-…` device id.
     */
    fun redeem(code: String, deviceName: String?, receiverId: String?): Pair<DeviceData, String>? {
        val now = nowMs()
        db.castHandoffQueries.sweep(now)
        val row = db.castHandoffQueries.getByCode(code.trim().uppercase()).executeAsOneOrNull() ?: return null
        if (row.redeemed_at != null || row.expires_at < now) return null
        val phone = deviceService.listSessions(row.device_id).firstOrNull { it.jellyfinUserId == row.jellyfin_user_id } ?: return null
        db.castHandoffQueries.markRedeemed(now, row.code)
        val id = receiverId?.trim()?.takeIf { it.startsWith("cast-") && it.length in 10..64 } ?: "cast-${generateSecureToken().take(12)}"
        val name = "$DEVICE_PREFIX · ${deviceName?.trim()?.take(40)?.ifBlank { null } ?: "Chromecast"}"
        println("[INFO] Cast receiver enrolled as $id for user '${phone.jellyfinUsername}' via phone ${phone.deviceId}")
        return deviceService.loginDevice(
            deviceId = id,
            deviceName = name,
            jellyfinUserId = phone.jellyfinUserId,
            jellyfinUsername = phone.jellyfinUsername,
            jellyfinUserToken = phone.jellyfinUserToken,
            isAdmin = phone.isAdmin,
            isKids = phone.isKids,
            allowedLibraries = phone.allowedLibraries,
            allowedTags = phone.allowedTags,
            blockedTags = phone.blockedTags,
        )
    }

    // ── FR-218-8: the ceiling ─────────────────────────────────────────────────

    /** Throws [CastCeilingException] when [device] is a receiver and `max_sessions` OTHER receivers are
     *  already playing. A receiver starting its next episode never counts against itself. */
    fun checkCeiling(device: DeviceData, activeDeviceNames: List<String>) {
        if (!isCastDevice(device)) return
        val others = activeDeviceNames.count { it.startsWith(DEVICE_PREFIX) && it != device.displayName }
        val max = maxSessions()
        if (others >= max) {
            throw CastCeilingException(30, "All $max cast sessions are in use — try again in a moment")
        }
    }

    // ── FR-218-5: reachability, checked by the BACKEND fetching its own /cast/ ──

    /** Two outcomes only: reachable over https, or not. The fetch must see this server's own receiver
     *  (the `X-Ravilo-Cast` header the `/cast/` route sets), so a captive portal's 200 does not pass. */
    suspend fun checkReceiver(publicUrl: String): ReceiverCheck {
        val base = publicUrl.trim().trimEnd('/')
        if (base.isBlank()) return ReceiverCheck(false, false, "Enter the public address first")
        val https = base.startsWith("https://", ignoreCase = true)
        if (!https) return ReceiverCheck(false, false, "not reachable — a Chromecast needs a public https address")
        val url = "$base/cast/"
        val result = runCatching {
            withTimeoutOrNull(8_000L) {
                OutboundHttp.withPermit {
                    val r = OutboundHttp.client.get(url) { header("Accept", "text/html") }
                    r.status.value to r.headers["X-Ravilo-Cast"]
                }
            }
        }.getOrNull()
        return when {
            result == null -> ReceiverCheck(false, true, "not reachable — a Chromecast needs a public https address")
            result.first !in 200..299 -> ReceiverCheck(false, true, "not reachable — $url answered ${result.first}")
            result.second == null -> ReceiverCheck(false, true, "not reachable — something else answers at $url")
            else -> ReceiverCheck(true, true, "reachable over https")
        }
    }

    // ── FR-218-7: honest status ───────────────────────────────────────────────

    fun status(activeDeviceNames: List<String>): ChromecastStatus {
        val cc = configStore.current.chromecast
        val receivers = deviceService.allDevices().filter { it.displayName.startsWith(DEVICE_PREFIX) }
        return ChromecastStatus(
            enabled = cc?.enabled == true,
            appIdSet = cc?.hasAppId() == true,
            receiverUrl = cc?.receiverUrl(),
            verified = receivers.isNotEmpty(),
            devices = receivers.sortedByDescending { it.lastSeen }.map { CastDeviceSummary(it.displayName.removePrefix("$DEVICE_PREFIX · "), it.lastSeen) },
            lastCastAt = receivers.maxOfOrNull { it.lastSeen },
            maxSessions = cc?.effectiveMaxSessions() ?: 2,
            activeSessions = activeDeviceNames.count { it.startsWith(DEVICE_PREFIX) },
        )
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMs(): Long = platform.posix.time(null) * 1000L
