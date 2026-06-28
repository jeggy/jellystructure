package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * R131: our version of Jellyfin's thumbnail extractor — generate a placeholder episode still by grabbing a
 * frame from the episode's own video file. The lowest-priority artwork source (see [ArtworkDownloader]); a
 * real TMDB still or a manual pick supersedes it.
 *
 * ffmpeg is heavier than a network download, so concurrency is held to a small gate.
 */
class Screengrabber {
    private val gate = Semaphore(2)

    /**
     * Extract a frame (~20% into the runtime, to skip intros/black) from [episode]'s file into [destPath].
     * Returns false (no-op) when the file is missing or ffmpeg fails — the caller leaves the still empty.
     */
    suspend fun grabEpisodeStill(episode: Episode, destPath: String): Boolean = gate.withPermit {
        val src = episode.path
        if (src.isBlank() || !SystemFileSystem.exists(Path(src))) return@withPermit false
        val dur = FfprobeRunner.duration(src)
        val at = if (dur != null && dur > 1.0) (dur * 0.2).toInt().coerceAtLeast(10) else 60
        val ok = FfmpegRunner.extractFrame(src, destPath, at)
        if (ok) Logger.info("Screengrab still: $destPath @ ${at}s", "artwork")
        else Logger.warn("Screengrab failed for '$src'", "artwork")
        ok
    }
}
