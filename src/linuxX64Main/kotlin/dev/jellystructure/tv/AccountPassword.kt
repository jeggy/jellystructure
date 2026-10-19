package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.PasswordChangeOutcome
import dev.jellystructure.shared.tv.AccountPasswordResult

/**
 * `POST /api/tv/account/password`, minus the HTTP — the three calls it makes are parameters so a test can hand it a
 * Jellyfin that fails the test if it is asked for a password change.
 */
object AccountPassword {
    /**
     * DEB-1854 (FR-8). [readUser] is the account read live (`GET /Users/{id}`); `null` means the read failed, and the
     * route then behaves exactly as it did before this phase. An account managed elsewhere answers
     * `{ok:false, managed_elsewhere:true}` and [relay] is never called, so the password never leaves for Jellyfin.
     * [onProvider] hears what the live read said, so the viewer's config follows without waiting for a reconciler pass.
     *
     * Returns `null` when Jellyfin rejected the change (the route answers 502).
     */
    suspend fun change(
        readUser: suspend () -> JellyfinUser?,
        onProvider: (managedElsewhere: Boolean) -> Unit,
        relay: suspend () -> PasswordChangeOutcome,
        tokenSurvived: suspend () -> Boolean,
        // DEB-1932 (FR-187-8 rules 1-5) — after Jellyfin's 204: re-signs the account's other devices; true when one was signed out.
        otherDevices: suspend () -> Boolean = { false },
    ): AccountPasswordResult? {
        readUser()?.let { user ->
            val managed = user.policy.passwordManagedElsewhere
            onProvider(managed)
            if (managed) return AccountPasswordResult(ok = false, managedElsewhere = true)
        }
        return when (relay()) {
            PasswordChangeOutcome.WRONG_CURRENT -> AccountPasswordResult(ok = false, wrongCurrentPassword = true)
            PasswordChangeOutcome.FAILED -> null
            PasswordChangeOutcome.OK -> {
                val survived = tokenSurvived()
                AccountPasswordResult(ok = true, tokenSurvived = survived, otherDevicesSignedOut = otherDevices())
            }
        }
    }
}
