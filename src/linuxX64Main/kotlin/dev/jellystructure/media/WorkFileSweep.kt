package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.log.Logger
import dev.jellystructure.torrent.libraryRoots
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Phase 311 (FR-311-3) — a crash or a container stop mid-remux can leave a work file behind. This removes work files
 * older than [WORK_FILE_MAX_AGE_SEC] that no running job holds, and the old-style `.jstmp_*` / `.jsreplace_*` files
 * beside the video (none exist today; for the transition). It never touches anything else.
 */
const val WORK_FILE_MAX_AGE_SEC = 24L * 3600L

/** One candidate the scan found: a path and its modification time (epoch seconds). */
data class WorkFileEntry(val path: String, val mtimeSec: Long)

/** The library file a work file (new or old style) belongs to, for the [MediaFileLock] check. */
fun workFileOwner(path: String): String? {
    WorkFiles.libraryPathOf(path)?.let { return it }
    val name = path.substringAfterLast('/')
    val prefix = WorkFiles.LEGACY_PREFIXES.firstOrNull { name.startsWith(it) && name.length > it.length } ?: return null
    return "${path.substringBeforeLast('/')}/${name.removePrefix(prefix)}"
}

/** FR-311-3 — the pure rule: a work file older than 24 h whose library file no job holds. */
fun workFilesToRemove(entries: List<WorkFileEntry>, nowSec: Long, held: (String) -> Boolean): List<String> =
    entries.filter { e ->
        val owner = workFileOwner(e.path) ?: return@filter false
        nowSec - e.mtimeSec > WORK_FILE_MAX_AGE_SEC && !held(owner)
    }.map { it.path }

object WorkFileSweep {
    private fun q(s: String) = "'${s.replace("'", "'\\''")}'"

    /** Every work file under [roots]: files inside a `.jellystructure` folder and old-style names, with mtimes. */
    private fun scan(roots: List<String>): List<WorkFileEntry> {
        if (roots.isEmpty()) return emptyList()
        val cmd = "find ${roots.joinToString(" ") { q(it) }} -xdev -type f \\( -path '*/${WorkFiles.DIR}/*' " +
            WorkFiles.LEGACY_PREFIXES.joinToString("") { "-o -name '$it*' " } + "\\) -printf '%T@ %p\\n' 2>/dev/null"
        val out = captureShell(cmd) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val sp = line.indexOf(' ').takeIf { it > 0 } ?: return@mapNotNull null
            val t = line.substring(0, sp).substringBefore('.').toLongOrNull() ?: return@mapNotNull null
            WorkFileEntry(line.substring(sp + 1), t)
        }.toList()
    }

    @OptIn(ExperimentalForeignApi::class)
    suspend fun run(config: AppConfig, nowSec: Long) {
        val entries = scan(libraryRoots(config))
        if (entries.isEmpty()) return
        val held = mutableSetOf<String>()
        for (e in entries) workFileOwner(e.path)?.let { if (MediaFileLock.isHeld(it)) held += it }
        for (path in workFilesToRemove(entries, nowSec) { it in held }) {
            if (platform.posix.remove(path) == 0) {
                Logger.info("work-file sweep: removed $path (older than 24 h, no job holds it) (311)", "jobs")
                workFileOwner(path)?.let { FfmpegRunner.removeWorkDirIfEmpty(it) }
            } else Logger.warn("work-file sweep: couldn't remove $path (311)", "jobs")
        }
    }
}
