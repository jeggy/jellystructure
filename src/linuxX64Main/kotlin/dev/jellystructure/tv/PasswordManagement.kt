package dev.jellystructure.tv

import dev.jellystructure.ops.LockedMap

/**
 * DEB-1854 (FR-3) — per Jellyfin user, whether the account's password is kept by a provider other than Jellyfin's
 * own. Written at sign-in from `AuthenticateByName`'s `User.Policy` and by every `DevicePolicyReconciler` pass from
 * the `/Users` answer it already fetched; read by the viewer's config and nowhere else.
 */
class PasswordManagement {
    private val elsewhere = LockedMap<String, Boolean>()

    /** A user nobody has recorded yet reads as local — today's behaviour. */
    fun isManagedElsewhere(userId: String): Boolean = elsewhere[userId] ?: false

    /** Stores [managedElsewhere] for [userId]; true when the answer a viewer would read changed. */
    fun record(userId: String, managedElsewhere: Boolean): Boolean {
        val was = isManagedElsewhere(userId)
        elsewhere[userId] = managedElsewhere
        return was != managedElsewhere
    }
}
