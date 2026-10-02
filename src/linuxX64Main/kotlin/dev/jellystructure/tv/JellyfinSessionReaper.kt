package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger

/** Phase 298 (FR-298-6) — a session idle this long with nothing playing and no socket is ended: phase 256's grace. */
const val STALE_SESSION_GRACE_SEC = 90L
/** Phase 298 (FR-298-6) — how often the sweep runs. */
const val SESSION_SWEEP_EVERY_MS = 60_000L

/**
 * Phase 298 (FR-298-6) — ends the Jellyfin sessions of Ravilo devices that no socket holds. Jellyfin ends a session only
 * when its last WebSocket closes; one made by REST reports alone (a Cast receiver before 298, a phone in the background,
 * a stop report that landed after the bridge closed) stays in `/Sessions` until Jellyfin restarts. The decision is
 * [staleRaviloSessions]; ending one is [JellyfinSessionBridge.endJellyfinSession].
 */
class JellyfinSessionReaper(
    private val configStore: ConfigStore,
    private val devices: () -> List<DeviceData>,
    private val bridge: JellyfinSessionBridge,
    private val jellyfin: JellyfinClient = JellyfinClient(),
    private val nowEpochSec: () -> Long = { dev.jellystructure.nowEpochSec() },
) {
    /** One pass. Returns the device ids whose session it ended. */
    suspend fun sweep(): List<String> {
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        val token = cfg.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) return emptyList()
        val body = jellyfin.getSessionsBody(base, token) ?: return emptyList()
        val sessions = parseJellyfinSessions(body) ?: return emptyList()
        val stale = staleRaviloSessions(sessions, devices(), bridge::isBridged, nowEpochSec(), STALE_SESSION_GRACE_SEC)
        val ended = mutableListOf<String>()
        for (device in stale) {
            if (bridge.endJellyfinSession(device)) {
                ended += device.deviceId
                Logger.info("Jellyfin session ended: device=${device.deviceId} (${device.kind}) had no socket and nothing playing", "tv")
            }
        }
        return ended
    }
}
