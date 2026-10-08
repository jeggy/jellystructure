package dev.jellystructure.filefix

import dev.jellystructure.torrent.linkInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test

/**
 * Phase 314 — the dry run's rules over the real library, read-only, gated: runs only with `FILEFIX_LIVE_LIST` set to a
 * file of absolute video paths (one per line). It probes each file (ffprobe headers only, background priority) and
 * prints counts per kind, split by seeded (a hard link outside the library counts as seeded, as 315's guard says) — no
 * titles, no writes. Used to validate 314 against the household's files on 2026-10-08.
 */
class FileFixLiveDryRunTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test fun liveDryRun() = runBlocking {
        val listFile = getenv("FILEFIX_LIVE_LIST")?.toKString() ?: return@runBlocking
        val shell = PosixFileFixShell
        val paths = shell.run("cat ${FileFixCommands.q(listFile)}").output.lines().map { it.trim() }.filter { it.startsWith("/") }
        val counts = HashMap<String, Int>()
        fun bump(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
        var bytes = HashMap<String, Long>()
        for (p in paths) {
            val out = shell.run("nice -n 19 ionice -c3 " + FileFixCommands.probe(p))
            val probe = (if (out.ok) parseProbe(p, out.output) else null) ?: run { bump("unreadable"); null } ?: continue
            val seeded = (linkInfo(p)?.links ?: 1L) > 1L
            val film = "/movies/" in p
            for (kind in listOf(FixKind.STEREO, FixKind.SURROUND)) {
                val v = planAudio(kind, probe.facts)
                val key = "${kind.id}.${if (film) "film" else "episode"}.${v.action}" + if (v.action == "add") (if (seeded) ".seeded" else ".unseeded") else ""
                bump(key)
                if (v.action == "add") bytes[kind.id] = (bytes[kind.id] ?: 0L) + v.estBytes
                if (v.action == "skip" && v.reason?.startsWith("MP4") == true && planAudio(kind, probe.facts.copy(container = "matroska")).action == "add") bump("${kind.id}.${if (film) "film" else "episode"}.skipped-mp4")
            }
            if (probe.facts.video?.dvProfile == 7) {
                val folder = shell.listDir(p.substringBeforeLast('/')).filter { it.lowercase().endsWith(".mkv") || it.lowercase().endsWith(".mp4") }
                val v = planDolbyVision(probe.facts, film, folder)
                bump("c.${v.action}" + (v.reason?.let { if (v.action == "skip") ": " + it.substringBefore(',') else "" } ?: "") + if (probe.facts.video?.dvElPresent == true) " (EL)" else "")
                // Phase 314c — the dry run's needs_rename rule (FileFixService.planOne): a rename after the folder would
                // make it eligible; and what the rename would take with it (dev.jellystructure.media.planRenameToFolder).
                val renamed = renamedToFolder(p)
                if (v.action == "skip" && renamed != null && !namedAfterFolder(p) &&
                    planDolbyVision(probe.facts.copy(path = renamed), film, folder.map { if (it == p) renamed else it }).action == "add"
                ) {
                    val dir = p.substringBeforeLast('/')
                    val names = shell.run("ls -1A ${FileFixCommands.q(dir)}").output.lines().filter { it.isNotBlank() }
                    val plan = dev.jellystructure.media.planRenameToFolder(p.substringAfterLast('/'), names, dir.substringAfterLast('/'))
                    val clash = plan.any { f -> f.target != f.name && f.target in names }
                    val links = linkInfo(p)?.links ?: 1L
                    bump("c.needs_rename" + (if (links > 1) " (hard-linked: seeding elsewhere goes on)" else " (no other link: ask qBittorrent)") + if (clash) " CLASH" else "")
                    bump("c.needs_rename files renamed with it = ${plan.size - 1}")
                }
            }
        }
        println("FILEFIX LIVE DRY RUN over ${paths.size} files")
        counts.entries.sortedBy { it.key }.forEach { (k, n) -> println("  $k = $n") }
        bytes.forEach { (k, b) -> println("  bytes $k = ${b / 1_000_000_000} GB") }
    }
}
