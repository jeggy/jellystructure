package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvSession

/**
 * Phase 236 (FR-236-2, dev review item 1) — the receiver-shows-a-code pairing flow. 218's hand-off code
 * runs phone → receiver (an authenticated phone mints, an unpaired receiver redeems); this is the
 * opposite direction, and is the flow the device-code pairing phase 141 retired
 * (`/api/tv/pair/{start,poll,approve}`, removed in `9c6325e3`) coming back for one device kind:
 *
 *  1. `mintCode` — the unpaired receiver (no credential) shows a code and holds a secret.
 *  2. `claim` — a signed-in phone types the code in; its OWN session (Jellyfin token, ACL, tags, kids)
 *     is copied onto the receiver's device id, exactly as [CastService.redeem] copies the minting
 *     phone's today. Refused for an API-key caller (FR-236-2) — an API key has no Jellyfin user token.
 *  3. `poll` — the receiver polls with its secret until the code has been claimed, then collects its own
 *     device token and the row is deleted (single use).
 */
class ScreenPairingService(
    private val db: JellystructureDb,
    private val deviceService: RaviloDeviceService,
) {
    companion object {
        /** Long enough for someone to walk to their phone and type six characters off a TV across the
         *  room — a slower flow than 218's phone-initiated hand-off, so a longer TTL than its 5 minutes. */
        const val CODE_TTL_MS = 10 * 60_000L
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
    }

    private fun freshCode(now: Long): String {
        db.screenPairingQueries.sweep(now)
        var code: String
        do {
            // generateSecureToken() is lowercase hex; matches CastService.mint's own filter shape.
            code = generateSecureToken().filter { it in CODE_ALPHABET.lowercase() || it in CODE_ALPHABET }.uppercase().take(6)
            if (code.length < 6) code = (code + "X".repeat(6)).take(6)
        } while (db.screenPairingQueries.getByCode(code).executeAsOneOrNull() != null)
        return code
    }

    /** FR-236-2 — `POST /api/tv/screen/code` (open path, rate-limited). Returns the code, its expiry,
     *  and the claim secret the SAME caller must present back to [poll] — never shown on screen with
     *  the code itself. */
    fun mintCode(deviceId: String, deviceName: String?, platform: String?): Triple<String, Long, String> {
        val now = nowMs()
        val code = freshCode(now)
        val claimSecret = generateSecureToken()
        val expires = now + CODE_TTL_MS
        db.screenPairingQueries.insert(
            code, deviceId, deviceName?.trim()?.take(80)?.ifBlank { null }, platform?.trim()?.take(64)?.ifBlank { null },
            sha256Hex(claimSecret), now, expires,
        )
        return Triple(code, expires, claimSecret)
    }

    /** FR-236-2 — `POST /api/remote/pair`, device-token callers only (checked by the route, per
     *  [dev.jellystructure.auth.RemoteCaller.viaApiKey]). Copies [phone]'s session onto the pairing
     *  row's device id — the receiver keeps one identity across every user who has claimed it, same as
     *  [CastService.redeem]'s `receiverId` reuse. Returns the receiver's own (name, deviceId) for the
     *  phone's confirmation, or null when the code is unknown, expired, or already claimed. */
    fun claim(code: String, phone: DeviceData): Pair<String, String>? {
        val now = nowMs()
        db.screenPairingQueries.sweep(now)
        val row = db.screenPairingQueries.getByCode(code.trim().uppercase()).executeAsOneOrNull() ?: return null
        if (row.expires_at < now || row.claimed_by_user != null) return null
        val (_, deviceToken) = deviceService.loginDevice(
            deviceId = row.device_id,
            deviceName = row.device_name,
            jellyfinUserId = phone.jellyfinUserId,
            jellyfinUsername = phone.jellyfinUsername,
            jellyfinUserToken = phone.jellyfinUserToken,
            isAdmin = phone.isAdmin,
            isKids = phone.isKids,
            allowedLibraries = phone.allowedLibraries,
            allowedTags = phone.allowedTags,
            blockedTags = phone.blockedTags,
            platform = row.platform,
            kind = "screen",
        )
        db.screenPairingQueries.claim(claimed_by_user = phone.jellyfinUserId, claimed_token = deviceToken, code = row.code)
        return (row.device_name ?: "Ravilo TV ${row.device_id.take(6)}") to row.device_id
    }

    /** FR-236-2 — `POST /api/tv/screen/claim` (open path, rate-limited). `null` while the code doesn't
     *  exist or has expired (the receiver shows the one error sentence); [PollResult.WAITING] while it
     *  hasn't been claimed yet (202); [PollResult.Claimed] once, after which the row is gone — a second
     *  poll for the same code sees "unknown", matching every other single-use code in this codebase. */
    sealed interface PollResult {
        data object Waiting : PollResult
        data class Claimed(val result: PairResult) : PollResult
    }

    fun poll(code: String, claimSecret: String): PollResult? {
        val now = nowMs()
        val row = db.screenPairingQueries.getByCode(code.trim().uppercase()).executeAsOneOrNull() ?: return null
        if (row.expires_at < now) return null
        if (sha256Hex(claimSecret) != row.claim_secret_hash) return null
        val claimedBy = row.claimed_by_user ?: return PollResult.Waiting
        val token = row.claimed_token ?: return PollResult.Waiting
        // Single-use: the receiver has its token now, so the row can't be claimed or polled again.
        db.screenPairingQueries.deleteByCode(row.code)
        val device = deviceService.listSessions(row.device_id).firstOrNull { it.jellyfinUserId == claimedBy }
            ?: return null // the claiming session was removed between claim() and this poll
        return PollResult.Claimed(
            PairResult(
                session = TvSession(
                    deviceId = device.deviceId,
                    userId = device.jellyfinUserId,
                    displayName = device.displayName,
                    isAdmin = device.isAdmin,
                    isKids = device.isKids,
                ),
                deviceToken = token,
            )
        )
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMs(): Long = platform.posix.time(null) * 1000L
