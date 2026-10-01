package dev.jellystructure.auth

import dev.jellystructure.ops.LockedMap

/**
 * Phase 224 (FR-224-4) — which Ravilo identity a Jellyfin user token belongs to, so that no call can
 * send a device's token under the server's own `Device="Server"` header.
 *
 * The bug this exists for: `tvToken()` hands a device's Jellyfin token to 56 of the 59 `jellyfinAuth`
 * call sites, none of which know the device — so they attached the server identity, and Jellyfin
 * (10.11.11 `AuthorizationContext.cs:158`) renamed the device to "Server" on every such request, then
 * back on the next playback call. Keyed by the token because that is the one thing every call site
 * has; filled wherever a [DeviceData] is resolved (token validation, login, the paired-token check).
 *
 * Phase 294 (FR-294-1) — a [LockedMap], not a plain `HashMap`. This was written on every authenticated TV request
 * from many threads at once with no lock; a TV sign-in's insert met another thread's insert mid-rehash and the login
 * route threw (*"…grow-only hash array. Have object hashCodes changed?"*) after the device row was already written,
 * so the TV said "Couldn't reach the server". Entries are still idempotent (a token maps to one identity for its
 * whole life; a re-login mints a new token).
 */
object DeviceIdentityRegistry {
    private val byToken = LockedMap<String, JellyfinDeviceIdentity>()

    fun remember(device: DeviceData) {
        if (device.jellyfinUserToken.isBlank()) return
        val identity = JellyfinDeviceIdentity.forDevice(device)
        // Every request remembers its device again; only a new or changed identity needs the write.
        if (byToken[device.jellyfinUserToken] != identity) byToken[device.jellyfinUserToken] = identity
    }

    fun forget(jellyfinUserToken: String) { byToken.remove(jellyfinUserToken) }

    fun identityFor(jellyfinUserToken: String): JellyfinDeviceIdentity? = byToken[jellyfinUserToken]

    /** Tests only. */
    internal fun clear() = byToken.clear()

    /** Tests only. */
    internal val size: Int get() = byToken.size
}
