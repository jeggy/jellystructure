package dev.jellystructure.auth

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
 * Plain map, no lock — the same discipline as RaviloDeviceService's tokenCache, and entries are
 * idempotent (a token maps to one identity for its whole life; a re-login mints a new token).
 */
object DeviceIdentityRegistry {
    private val byToken = HashMap<String, JellyfinDeviceIdentity>()

    fun remember(device: DeviceData) {
        if (device.jellyfinUserToken.isBlank()) return
        byToken[device.jellyfinUserToken] = JellyfinDeviceIdentity.forDevice(device)
    }

    fun forget(jellyfinUserToken: String) { byToken.remove(jellyfinUserToken) }

    fun identityFor(jellyfinUserToken: String): JellyfinDeviceIdentity? = byToken[jellyfinUserToken]

    /** Tests only. */
    internal fun clear() = byToken.clear()
}
