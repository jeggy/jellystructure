package dev.jellystructure.torrent

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.log.Logger

/**
 * Phase 178 §FR-178-3 — applies qBittorrent's own alternative speed limits while a TV is playing, using
 * the existing authenticated [QBittorrentClient] and its own built-in mechanism (deliberately not a
 * rewrite of the operator's configured rate numbers — see the phase's Root cause doc for why). [tick] is
 * called from the same 30s watchdog loop [dev.jellystructure.tv.PlaybackService.stopWatchdogTick] already
 * runs from (Main.kt) — no dedicated polling loop of its own.
 */
class PlaybackThrottleService(
    private val configStore: ConfigStore,
    private val qbClient: QBittorrentClient,
    private val store: PlaybackThrottleStore,
) {
    private var wasActive = false

    /** Completes an interrupted restore left over from a crash/restart mid-playback — otherwise a
     *  backend restart at the wrong moment leaves the household's downloads throttled forever with no
     *  visible cause (the phase's own stated failure mode). Call once at startup. */
    suspend fun recoverOnStartup() {
        val cfg = configStore.current.qbittorrent ?: return
        if (store.weChangedIt) restoreIfOurs(cfg)
    }

    suspend fun tick() {
        val cfg = configStore.current.qbittorrent
        if (cfg == null || !cfg.enabled || !cfg.throttleWhilePlaying) {
            // The operator turned the feature (or qBittorrent itself) off while we'd already thrown the
            // switch — still try to hand it back rather than leaving it silently throttled forever.
            if (store.weChangedIt && cfg != null) restoreIfOurs(cfg)
            wasActive = false
            return
        }
        val active = dev.jellystructure.tv.isPlaybackActive()
        if (active && !wasActive) onPlaybackStarted(cfg)
        else if (!active && wasActive) onPlaybackCleared(cfg)
        wasActive = active
    }

    private suspend fun onPlaybackStarted(cfg: QBittorrentConfig) {
        if (store.weChangedIt) return  // already throttled from an earlier, still-unresolved change
        runCatching {
            val sid = qbClient.login(cfg)
            val current = qbClient.getSpeedLimitsMode(cfg, sid)
            if (current == 1) return@runCatching  // already on alternative limits — nothing to remember
            qbClient.setSpeedLimitsMode(cfg, sid, mode = 1)
            store.markChanged(priorMode = current)
        }.onFailure { Logger.warn("Playback throttle: failed to enable qBittorrent alt speed limits: ${it.message}", "qbittorrent") }
    }

    private suspend fun onPlaybackCleared(cfg: QBittorrentConfig) = restoreIfOurs(cfg)

    /** "We only ever restore what we changed" (the phase's own invariant) — if the live mode no longer
     *  matches what we set it to, the operator changed it manually in the meantime; leave their change
     *  alone and just drop our own now-moot bookkeeping. */
    private suspend fun restoreIfOurs(cfg: QBittorrentConfig) {
        val prior = store.priorMode ?: return
        runCatching {
            val sid = qbClient.login(cfg)
            if (qbClient.getSpeedLimitsMode(cfg, sid) == 1) {
                qbClient.setSpeedLimitsMode(cfg, sid, mode = prior)
            }
            store.clear()
        }.onFailure { Logger.warn("Playback throttle: failed to restore qBittorrent speed limits mode: ${it.message}", "qbittorrent") }
    }
}
