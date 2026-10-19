package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.boundedOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * DEB-1932 (FR-187-8, 2026-10-19 amendment, rules 1, 3 and 4) — what the password route does for the account's *other*
 * Ravilo devices once Jellyfin has answered `204`. Jellyfin 12.1 revokes every token of the user but the caller's, so
 * each other device that holds its own sign-in is signed back in with the new password, and one that cannot be is
 * signed out at once.
 *
 * The password never passes through here (rule 2): [signIn] is the route's closure over the request's own body, and
 * returns only a token. Nothing in this file can log it.
 */
object OtherDevicesResign {
    /** Rule 1 — each sign-in is bounded like a token check (Phase 205), so one slow Jellyfin call cannot hold the request. */
    const val SIGN_IN_TIMEOUT_MS = TOKEN_CHECK_TIMEOUT_MS

    /** Rule 4 — the event pushed to a device whose sign-in the change ended. Additive: a client that does not know it ignores it. */
    const val SIGNED_OUT_EVENT = """{"type":"signed_out"}"""

    /** The rows rule 1 reaches: [userId]'s own-sign-in rows other than [caller]'s. Borrowing kinds and rows with no token are not. */
    fun targets(caller: DeviceData, userId: String, rows: List<DeviceData>): List<DeviceData> =
        rows.filter {
            it.jellyfinUserId == userId && it.deviceId != caller.deviceId &&
                !ReceiverTokenPolicy.borrowsToken(it.kind) && it.jellyfinUserToken.isNotBlank()
        }

    /**
     * The Jellyfin DeviceId a device's own sign-in used: `ravilo-<deviceId>-<username>`, the shape `POST /tv/login` builds.
     * Not [JellyfinDeviceIdentity.forDevice], which folds in the user id and is only what later calls present — signing
     * in under that one would add a second Jellyfin device entry instead of replacing the device's own.
     * [username] is the account's name as Jellyfin has it now; the name typed at that device's login is not stored.
     */
    fun signInIdentity(row: DeviceData, username: String): JellyfinDeviceIdentity =
        JellyfinDeviceIdentity("ravilo-${row.deviceId}-$username", row.displayName.ifBlank { "Ravilo TV" }, row.appVersion)

    /**
     * Returns `true` when at least one device could not be re-signed and was signed out (rule 5's `other_devices_signed_out`).
     *
     * [signIn] calls `AuthenticateByName` and returns the new token, or `null`/throws when it did not come back with one.
     * [replaceToken] stores a new token on a row and moves its followers; [signOut] ends a device's sign-in the way Sign
     * out does; [pushSignedOut] sends it the event; [forget] is `forgetTokenValidity`. Every call is awaited before this returns.
     */
    suspend fun run(
        caller: DeviceData,
        rows: List<DeviceData>,
        liveUsername: String?,
        signIn: suspend (username: String, identity: JellyfinDeviceIdentity) -> String?,
        replaceToken: (row: DeviceData, newToken: String) -> Unit,
        signOut: (row: DeviceData) -> Unit,
        pushSignedOut: (row: DeviceData) -> Unit,
        forget: suspend (token: String) -> Unit,
    ): Boolean {
        val others = targets(caller, caller.jellyfinUserId, rows)
        if (others.isEmpty()) return false
        val outcomes = coroutineScope {
            others.map { row ->
                async {
                    val username = liveUsername?.takeIf { it.isNotBlank() } ?: row.jellyfinUsername
                    val token = try {
                        boundedOrNull(SIGN_IN_TIMEOUT_MS, "re-sign after a password change") { signIn(username, signInIdentity(row, username)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // The class only: a message could carry anything the transport had in hand.
                        Logger.warn("Password change: could not sign device ${row.deviceId} back in (${e::class.simpleName})", "auth")
                        null
                    }
                    row to token?.takeIf { it.isNotBlank() }
                }
            }.awaitAll()
        }
        var signedOut = false
        for ((row, token) in outcomes) {
            if (token != null) {
                replaceToken(row, token)
            } else {
                signedOut = true
                Logger.info("Password change: device ${row.deviceId} could not be signed back in — signed out", "auth")
                pushSignedOut(row)
                signOut(row)
            }
        }
        // Rule 3 — every token the change revoked, so a play start cannot hand out a URL Jellyfin refuses.
        others.map { it.jellyfinUserToken }.distinct().forEach { forget(it) }
        return signedOut
    }
}
