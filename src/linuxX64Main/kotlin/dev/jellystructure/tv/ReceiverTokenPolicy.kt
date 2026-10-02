package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData

/**
 * Phase 300 — which Jellyfin token a device that borrows one (a Cast receiver, a Tizen/web screen) should hold.
 *
 * A borrowing device never signs in to Jellyfin itself: phase 218/236 copy the token of the device that minted its
 * code. Jellyfin replaces a token when its own DeviceId signs in again, so the copy has its sender's lifetime. These
 * rules keep the copy pointing at a live sign-in of the same user. Pure: no database, no network, no clock.
 */
object ReceiverTokenPolicy {
    /** Kinds whose Jellyfin token is a copy of another device's (phase 236's `kind`). */
    val BORROWING_KINDS = setOf("cast", "screen")

    /** How many donor tokens FR-300-4 checks at most — each costs a round trip on an already-failing path. */
    const val MAX_DONORS = 3

    fun borrowsToken(kind: String?): Boolean = kind in BORROWING_KINDS

    /**
     * FR-300-2 — the token a redemption stores on the receiver's row for the sender's user. The sender's, unless the
     * sender's is known dead and the receiver's own (for this same user) is non-blank and not known dead.
     * [receiverToken] is null when the receiver has no row for this user yet.
     */
    fun tokenOnHandoff(senderToken: String, senderKnownDead: Boolean, receiverToken: String?, receiverKnownDead: Boolean): String =
        if ((senderToken.isBlank() || senderKnownDead) && !receiverToken.isNullOrBlank() && !receiverKnownDead) receiverToken
        else senderToken

    /**
     * FR-300-4 — the tokens to try, in order, for [receiver] whose token was rejected: the same user's own-sign-in
     * rows, most recently seen first, distinct tokens only, skipping blanks, the rejected token itself and any token
     * [knownDead] says is negative-cached. At most [MAX_DONORS].
     */
    fun donors(receiver: DeviceData, rows: List<DeviceData>, knownDead: (String) -> Boolean = { false }): List<DeviceData> =
        rows.asSequence()
            .filter { it.jellyfinUserId == receiver.jellyfinUserId }
            .filter { !borrowsToken(it.kind) && it.deviceId != receiver.deviceId }
            .filter { it.jellyfinUserToken.isNotBlank() && it.jellyfinUserToken != receiver.jellyfinUserToken }
            .filter { !knownDead(it.jellyfinUserToken) }
            .sortedByDescending { it.lastSeen }
            .distinctBy { it.jellyfinUserToken }
            .take(MAX_DONORS)
            .toList()

    /**
     * FR-300-3 — the rows that follow a re-sign-in: [signedIn] (a non-borrowing device of [userId]) replaced
     * [oldToken] with a new one; every borrowing row of the same user that still holds [oldToken] follows it.
     */
    fun followers(signedInKind: String, userId: String, oldToken: String?, newToken: String, rows: List<DeviceData>): List<DeviceData> {
        if (borrowsToken(signedInKind) || oldToken.isNullOrBlank() || oldToken == newToken || newToken.isBlank()) return emptyList()
        return rows.filter { it.jellyfinUserId == userId && borrowsToken(it.kind) && it.jellyfinUserToken == oldToken }
    }
}
