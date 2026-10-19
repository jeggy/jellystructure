package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.TvApiClient

/**
 * DEB-1933 (DEB-1926, FR-R234-7) — what a `signed_out` event asks of this device. The server sends it, on the socket of
 * the profile whose sign-in a password change could not keep (187 FR-187-8), so the profile it names is the one the
 * socket belongs to: [socketUserId]. Where the viewer is — Home, a page, the player — does not matter.
 */
enum class SignedOutRoute { LOGIN, PICKER }

/**
 * Applies the event and says where the screen goes, or `null` when it stays.
 *
 * - **The active profile** does what *Sign in again* does ([signOutActive] is [signOutActiveSession]): signed out, then
 *   Login, or the profile picker while other profiles remain.
 * - **A profile that is stored but not active** (the socket outlived a profile switch) is forgotten
 *   ([forgetProfile]); the active one is not interrupted, so nothing is routed.
 * - **A profile this device no longer holds** is nothing to do.
 */
suspend fun applySignedOut(
    socketUserId: String?,
    activeUserId: String?,
    storedUserIds: List<String>,
    signOutActive: suspend () -> Boolean,
    forgetProfile: suspend (String) -> Unit,
): SignedOutRoute? {
    if (socketUserId == null) return null
    if (socketUserId == activeUserId) {
        val othersRemain = signOutActive()
        return if (othersRemain) SignedOutRoute.PICKER else SignedOutRoute.LOGIN
    }
    if (socketUserId in storedUserIds) forgetProfile(socketUserId)
    return null
}

/**
 * Forgets one stored profile that is not the active one: its token and the local memory that does not outlive it, as
 * [signOutActiveSession] does, without a revoke — the server has already ended that sign-in. Nothing else on the device
 * is touched, and the listening mode (which belongs to whoever is watching) is left alone.
 */
fun forgetStoredProfile(userId: String) {
    MultiTokenStore.remove(userId)
    PlaybackPrefsStore.clearProfile(userId)
    HomeSnapshotCache.clear(userId)
}

/** The real [applySignedOut]: the device's own store and [apiClient]'s sign-out. */
suspend fun applySignedOutToDevice(apiClient: TvApiClient, socketUserId: String?): SignedOutRoute? =
    applySignedOut(
        socketUserId = socketUserId,
        activeUserId = MultiTokenStore.getActive()?.userId,
        storedUserIds = MultiTokenStore.getAll().map { it.userId },
        signOutActive = { signOutActiveSession(apiClient) },
        forgetProfile = { forgetStoredProfile(it) },
    )

/**
 * FR-R234-7 — the one line a password change's result adds on the changing device: only when the server says a device
 * could not be signed back in. An older server omits the field, which reads false.
 */
fun passwordChangedOthersLineKey(otherDevicesSignedOut: Boolean): String? =
    if (otherDevicesSignedOut) "account.pw_ok_others" else null
