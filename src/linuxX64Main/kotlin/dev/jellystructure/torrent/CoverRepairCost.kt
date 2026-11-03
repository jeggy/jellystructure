package dev.jellystructure.torrent

import kotlinx.serialization.Serializable

/**
 * 315 FR-315-7 — what the cover-art repair costs in disk. A repair writes a new library file; a file with a name outside
 * the library keeps its old bytes under that name, so the repair adds a second copy while the torrent seeds. [sharedFiles]
 * counts the files the button repairs that have such a name, [sharedBytes] sums their sizes and [sharedGb] is that sum
 * in GB (10^9 bytes), one decimal. The admin renders these and derives nothing.
 */
@Serializable
data class CoverRepairCost(val sharedFiles: Int = 0, val sharedBytes: Long = 0L, val sharedGb: Double = 0.0)

/**
 * [files] is each file the repair touches as path and size (null: not recorded yet, [sizeOf] is asked). A path listed
 * twice (episodes that share one file) counts once. [verdict] is `SeedingGuard.linkVerdict`.
 */
suspend fun coverRepairCost(
    files: List<Pair<String, Long?>>,
    verdict: suspend (String) -> LinkVerdict,
    sizeOf: (String) -> Long?,
): CoverRepairCost {
    var count = 0
    var bytes = 0L
    for ((path, size) in files.distinctBy { it.first }) {
        if (verdict(path) != LinkVerdict.OUTSIDE) continue
        count++
        bytes += size ?: sizeOf(path) ?: 0L
    }
    return CoverRepairCost(count, bytes, kotlin.math.round(bytes / 1e8) / 10.0)
}
