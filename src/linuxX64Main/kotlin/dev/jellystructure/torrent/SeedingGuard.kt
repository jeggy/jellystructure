package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig

sealed class SeedingCheckResult {
    object Unconfigured : SeedingCheckResult()
    object Allowed : SeedingCheckResult()
    data class Blocked(val torrentName: String) : SeedingCheckResult()
    data class Unreachable(val reason: String) : SeedingCheckResult()
}

class SeedingGuard(private val snapshot: SeedingSnapshot) {
    suspend fun check(localFilePath: String, config: AppConfig): SeedingCheckResult =
        snapshot.checkPath(localFilePath, config)
}
