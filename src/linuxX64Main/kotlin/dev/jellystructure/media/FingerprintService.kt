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

    // Phase 159 (FR-159-7) — cache-bust on window size: a fingerprint computed at the old
    // (pre-Phase-159) 600s window is not equivalent to one computed at 900s (it's missing frames a
    // wider-window match could need), so a stale on-disk cache must never look like a hit for the new
    // window. Suffixing the window size into the key makes any old-window cache file simply orphaned
    // (harmlessly unreferenced) rather than silently served as if it were current.
    private fun cacheKey(seriesId: String, ep: Episode): String {
        val epSuffix = ep.episodeNumber?.let { "-e$it" } ?: ""
        return "$seriesId-${ep.filename.hashCode().toUInt()}$epSuffix-w${FfmpegRunner.FINGERPRINT_WINDOW_SEC}"
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

    /** Phase 159 (FR-159-3) — a tail/outro fingerprint plus the absolute file offset (ms) its frame 0
     *  corresponds to, since [SegmentDetection.findIntroMatch] reports match bounds relative to frame 0
     *  of whatever it's given and has no idea this array came from the END of the file, not the start. */
    data class TailFingerprint(val frames: List<Int>, val windowStartMs: Long)

    /** Outro counterpart to [getOrCompute] — same cache-then-compute shape, distinct cache key so it
     *  never collides with the head/intro fingerprint for the same episode. [durationSec] (already
     *  fetched by every caller for other reasons) determines [TailFingerprint.windowStartMs]; a file
     *  shorter than the analysis window is clamped to 0 (ffmpeg's own `-sseof` clamps identically). */
    suspend fun getOrComputeOutro(seriesId: String, ep: Episode, durationSec: Double): TailFingerprint? {
        val windowStartMs = ((durationSec - FfmpegRunner.OUTRO_FINGERPRINT_WINDOW_SEC).coerceAtLeast(0.0) * 1000).toLong()
        val cachePath = "$cacheDir/${cacheKey(seriesId, ep)}-outro-w${FfmpegRunner.OUTRO_FINGERPRINT_WINDOW_SEC}.json"
        if (SystemFileSystem.exists(Path(cachePath))) {
            runCatching { json.decodeFromString(listSerializer, FileIo.readText(Path(cachePath))) }
                .getOrNull()?.let { return TailFingerprint(it, windowStartMs) }
        }
        val computed = FfmpegRunner.computeOutroFingerprint(ep.path) ?: return null
        runCatching {
            val tmp = "$cachePath.tmp"
            FileIo.writeText(Path(tmp), json.encodeToString(listSerializer, computed))
            platform.posix.rename(tmp, cachePath)
        }.onFailure { Logger.warn("Failed to persist outro fingerprint cache for ${ep.filename}: ${it.message}", "segments") }
        return TailFingerprint(computed, windowStartMs)
    }
}
