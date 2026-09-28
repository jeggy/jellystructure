package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.MusicConvertPlan
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicFormats
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.reencodesOnPhone
import dev.jellystructure.nowEpochSec
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 278 (FR-278-7, H1's lean) — *Convert…*: a one-time repair that turns the songs a phone can only play by
 * re-encoding (WMA) into AAC 192 kbps `.m4a` files beside them. The files are already lossy, so a little more is
 * lost — the confirmation says so. The originals are **moved** to the holding folder (254's `.js-quarantine`,
 * outside anything Jellyfin scans), never deleted; a file qBittorrent is seeding is skipped (phase 26's guard).
 *
 * Runs as a `convert_audio` job on the media lane. A converted song is a new file, so Jellyfin gives it a new item
 * id: the album is refreshed in Jellyfin, and the next `scan_music` picks it up (its MusicBrainz ids come back on
 * the next match refresh from the album's own ids; its `.lrc` keeps working because the base name is unchanged).
 */
class MusicConvert(
    private val store: MusicStore,
    private val configStore: ConfigStore,
    private val seedingGuard: SeedingGuard,
    private val jellyfin: JellyfinClient,
    private val history: MediaHistory?,
) {
    /** The songs a request covers: only live files that re-encode on a phone. */
    fun targets(req: MusicConvertRequest): List<MusicTrack> {
        val snap = store.snapshot()
        val ids = req.trackIds?.toSet()
        return snap.tracks.values.filter { t ->
            t.missingSince == null && t.path != null && t.reencodesOnPhone() &&
                (req.albumId == null || t.albumId == req.albumId) && (ids == null || t.id in ids)
        }.sortedWith(compareBy({ it.albumId }, { it.disc ?: 1 }, { it.position ?: 0 }))
    }

    suspend fun plan(req: MusicConvertRequest): MusicConvertPlan {
        val ts = targets(req)
        val cfg = configStore.current
        val seeding = ts.count { seedingGuard.check(it.path!!, cfg) is SeedingCheckResult.Blocked }
        return MusicConvertPlan(
            songs = ts.size - seeding, seeding = seeding,
            formats = ts.map { MusicFormats.label(it.container, it.codec, it.bitrate) }.distinct(),
        )
    }

    /**
     * The job's body: convert [paths] one at a time. Returns null when every file was converted or skipped for a
     * reason the admin can act on, else one sentence naming what failed.
     */
    suspend fun run(ownerId: String, paths: List<String>, onFile: suspend (Int) -> Unit, cancelled: () -> Boolean): String? {
        val cfg = configStore.current
        var converted = 0; var seeding = 0
        val failed = mutableListOf<String>()
        val albums = LinkedHashSet<String>()
        for ((i, path) in paths.withIndex()) {
            if (cancelled()) break
            when (val r = convertOne(path, cfg)) {
                null -> { converted++; store.snapshot().tracks.values.firstOrNull { it.path == path }?.albumId?.let { albums += it } }
                SEEDING -> seeding++
                else -> failed += "${path.substringAfterLast('/')}: $r"
            }
            onFile(i + 1)
        }
        // Jellyfin finds the new files (and loses the old ones) when it re-reads each album folder.
        if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank())
            for (a in albums) jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, a, full = false, recursive = true)
        val sentence = buildString {
            append("Converted $converted song${if (converted == 1) "" else "s"} to AAC 192 kbps; originals moved to the holding folder")
            if (seeding > 0) append(" · $seeding skipped (seeding in qBittorrent)")
            if (failed.isNotEmpty()) append(" · ${failed.size} failed")
        }
        (albums.ifEmpty { setOf(ownerId) }).forEach { runCatching { history?.record(it, "music_convert", sentence) } }
        Logger.info("convert_audio: $sentence${if (failed.isNotEmpty()) " — " + failed.joinToString("; ") else ""}", "music")
        return if (failed.isEmpty()) null else failed.joinToString(" · ")
    }

    private suspend fun convertOne(path: String, cfg: AppConfig): String? {
        if (!SystemFileSystem.exists(Path(path))) return "the file is gone"
        when (val g = seedingGuard.check(path, cfg)) {
            is SeedingCheckResult.Blocked -> return SEEDING
            is SeedingCheckResult.Unreachable -> return "qBittorrent unreachable (${g.reason}) — skipped to be safe"
            else -> Unit
        }
        val out = path.substringBeforeLast('.') + ".m4a"
        if (out == path || SystemFileSystem.exists(Path(out))) return "${out.substringAfterLast('/')} already exists"
        val tmp = path.substringBeforeLast('.') + ".js-convert.m4a"
        if (!FfmpegRunner.convertAudioToAac(path, tmp)) {
            platform.posix.remove(tmp)
            return "ffmpeg could not convert it"
        }
        sh("chown --reference=${q(path)} ${q(tmp)} 2>/dev/null; chmod --reference=${q(path)} ${q(tmp)} 2>/dev/null")
        val hold = holdingPathFor(path, cfg)
        sh("mkdir -p ${q(hold.substringBeforeLast('/'))}")
        if (platform.posix.rename(path, hold) != 0 && sh("mv -n ${q(path)} ${q(hold)}") != 0) {
            platform.posix.remove(tmp)
            return "could not move the original to the holding folder"
        }
        if (platform.posix.rename(tmp, out) != 0) {
            // Put the original back: a song must never simply vanish.
            if (platform.posix.rename(hold, path) != 0) sh("mv -n ${q(hold)} ${q(path)}")
            platform.posix.remove(tmp)
            return "could not put the new file in place"
        }
        return null
    }

    companion object {
        private const val SEEDING = "\u0000seeding"
        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        private fun sh(cmd: String): Int = platform.posix.system(cmd)

        /** `<parent of the library root>/.js-quarantine/<root name>/<path under the root>` — 254's rule; a second
         *  conversion of the same path gets a timestamp suffix, never an overwrite. */
        fun holdingPathFor(path: String, cfg: AppConfig): String {
            val root = cfg.libraries.map { it.localPath.trimEnd('/') }
                .filter { it.isNotEmpty() && path.startsWith("$it/") }.maxByOrNull { it.length }
                ?: path.substringBeforeLast('/')
            val base = root.substringBeforeLast('/') + "/.js-quarantine/" + root.substringAfterLast('/') + path.removePrefix(root)
            return if (!SystemFileSystem.exists(Path(base))) base else "$base.${nowEpochSec()}"
        }
    }
}
