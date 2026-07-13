package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Phase 150 (FR-SEG1-4) — per-episode Chromaprint fingerprint cache for cross-episode intro matching
 * (`SegmentDetection.findIntroMatch`). A single episode's raw fingerprint is tens of KB (see
 * `FfmpegRunner.computeFingerprint`) — far too large to embed in the `MediaItem` JSON blob (Phase 78's
 * own history: full-library blob decode was already a real perf cost at ~61KB/item before that phase's
 * trimming) — so it's cached on disk instead, keyed the same way `RaviloArtworkService` keys its own
 * per-episode still cache (series id + hashed filename + episode-number suffix, so a multi-episode-file
 * group's members never collide on one cache entry).
 */
class FingerprintService(dataDir: String) {
    private val cacheDir = "$dataDir/fingerprints"
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Int.serializer())

    init {
        runCatching { SystemFileSystem.createDirectories(Path(cacheDir)) }
    }

    private fun cacheKey(seriesId: String, ep: Episode): String {
        val epSuffix = ep.episodeNumber?.let { "-e$it" } ?: ""
        return "$seriesId-${ep.filename.hashCode().toUInt()}$epSuffix"
    }

    /**
     * Returns the cached fingerprint for [ep], computing (and persisting) it on a cache miss. Null on
     * any failure (file gone, fpcalc error) — the caller treats that as "no fingerprint yet", the same
     * graceful-fallback shape every other segment-detection tier already uses.
     */
    suspend fun getOrCompute(seriesId: String, ep: Episode): List<Int>? {
        val cachePath = "$cacheDir/${cacheKey(seriesId, ep)}.json"
        if (SystemFileSystem.exists(Path(cachePath))) {
            runCatching { json.decodeFromString(listSerializer, FileIo.readText(Path(cachePath))) }
                .getOrNull()?.let { return it }
        }
        val computed = FfmpegRunner.computeFingerprint(ep.path) ?: return null
        runCatching {
            val tmp = "$cachePath.tmp"
            FileIo.writeText(Path(tmp), json.encodeToString(listSerializer, computed))
            platform.posix.rename(tmp, cachePath)
        }.onFailure { Logger.warn("Failed to persist fingerprint cache for ${ep.filename}: ${it.message}", "segments") }
        return computed
    }
}
