package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.log.Logger

sealed class SeedingCheckResult {
    object Unconfigured : SeedingCheckResult()
    object Allowed : SeedingCheckResult()
    data class Blocked(val torrentName: String) : SeedingCheckResult()
    data class Unreachable(val reason: String) : SeedingCheckResult()
}

private val SEEDING_STATES = setOf("uploading", "stalledUP", "forcedUP", "queuedUP", "pausedUP")

class SeedingGuard(private val client: QBittorrentClient) {

    suspend fun check(localFilePath: String, config: AppConfig): SeedingCheckResult {
        val qbConfig = config.qbittorrent ?: return SeedingCheckResult.Unconfigured
        if (!qbConfig.enabled) return SeedingCheckResult.Unconfigured

        val translatedPath = run {
            val best = qbConfig.pathMappings
                .filter { localFilePath.startsWith(it.local) }
                .maxByOrNull { it.local.length }
            if (best != null) localFilePath.replaceFirst(best.local, best.remote) else localFilePath
        }

        val torrents = runCatching {
            val sid = client.login(qbConfig)
            client.getTorrents(qbConfig, sid)
        }.getOrElse { e ->
            Logger.warn("SeedingGuard: qBittorrent unreachable — ${e.message}")
            return SeedingCheckResult.Unreachable(e.message ?: "unknown error")
        }

        for (torrent in torrents) {
            val inTorrent = translatedPath == torrent.contentPath ||
                translatedPath.startsWith(torrent.contentPath + "/")
            if (inTorrent && torrent.state in SEEDING_STATES) {
                return SeedingCheckResult.Blocked(torrent.name)
            }
        }
        return SeedingCheckResult.Allowed
    }
}
