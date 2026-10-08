package dev.jellystructure.filefix

import dev.jellystructure.arr.ArrParseResult
import dev.jellystructure.config.AppConfig
import dev.jellystructure.db.File_fix
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaFileLock
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.WorkFiles
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.model.FileFixKindSummary
import dev.jellystructure.model.FileFixOverview
import dev.jellystructure.model.FileFixPlanStatus
import dev.jellystructure.model.FileFixRow
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.torrent.SeedingCheckResult
import kotlinx.coroutines.isActive

/** What one job ended as; the job queue maps it onto its own outcome. */
sealed class FixOutcome {
    object Done : FixOutcome()
    data class Failed(val reason: String) : FixOutcome()
    /** Stopped for playback (or the job was cancelled): the row goes back to pending, nothing was changed. */
    data class Stopped(val reason: String) : FixOutcome()
    /** Nothing to do any more (already done, no longer eligible): the row says why. */
    data class Skipped(val reason: String) : FixOutcome()
}

/** One file the dry run looks at: a film or one episode. */
data class FixTarget(val item: MediaItem, val path: String, val isFilm: Boolean, val label: String)

/**
 * Phase 314 — a file every device can play directly: the dry run (FR-314-2), Apply, the queue tick that hands one file at
 * a time to the media lane within the nightly read cap (FR-314-10), and the job body per kind (FR-314-3/-6, kind C).
 *
 * Seeded files are never modified (owner, 2026-10-08): 315's guard with the hard-link check decides, and a seeded file
 * gets its added audio as a `.mka` beside it. Kind C never changes the original at all: the profile-8.1 copy is a
 * Jellyfin version file beside it, written only after Radarr has been asked how it would read the copy's name.
 */
class FileFixService(
    private val db: JellystructureDb,
    private val store: MediaStore,
    private val config: () -> AppConfig,
    private val history: MediaHistory,
    /** 315's guard, asked as an in-place writer (a hard link counts as seeded). */
    private val seeding: suspend (String) -> SeedingCheckResult,
    /** Radarr's reading of a release name; null when Radarr isn't set up (then the check is skipped). */
    private val radarrParse: (suspend (String) -> ArrParseResult?)?,
    /** After a write: tell Jellyfin to re-read the title, and nudge Radarr/Sonarr (284's post-write sync). */
    private val afterWrite: suspend (MediaItem) -> Unit,
    /** Is anything playing that this job must yield to (the job queue's own rule)? */
    private val playbackActive: () -> Boolean,
    private val shell: FileFixShell = PosixFileFixShell,
    private val doviTool: String = DEFAULT_DOVI_TOOL,
    private val now: () -> Long,
    private val today: () -> String,
    /** Phase 314c — renames a Dolby Vision 7 original after its folder (316's mover, in place); null where not wired. */
    private val renameOriginal: (suspend (String) -> dev.jellystructure.model.FileFixRenameResult)? = null,
) {
    private val q get() = db.fileFixQueries

    /** The dry run's write: a new row, or an update that leaves a pending or running row's state alone. */
    private fun upsertPlanned(path: String, kind: String, mediaId: String, label: String, state: String, detail: String, est: Long, size: Long, mtime: Long, at: Long) {
        q.transaction {
            q.insertPlanned(path, kind, mediaId, label, state, detail, est, size, mtime, at)
            q.updatePlanned(mediaId, label, state, detail, est, size, mtime, at, path, kind)
        }
    }

    private fun upsertSetting(kind: String, enabled: Long, autoNew: Long, appliedAt: Long?) {
        q.transaction {
            q.insertSetting(kind)
            q.updateSetting(enabled, autoNew, appliedAt, kind)
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────────────────────────────────────────

    fun enabled(kind: FixKind): Boolean = q.settingFor(kind.id).executeAsOneOrNull()?.enabled == 1L
    private fun autoNew(kind: FixKind): Boolean = q.settingFor(kind.id).executeAsOneOrNull()?.auto_new == 1L

    suspend fun setSetting(kind: FixKind, enabled: Boolean, autoNew: Boolean) {
        upsertSetting(kind.id, if (enabled) 1L else 0L, if (autoNew) 1L else 0L, null)
        if (!enabled) q.revertPendingForKind(now(), kind.id)   // FR-314-8 — off stops new jobs; nothing added is removed
        Logger.info("file fix: ${kind.label} ${if (enabled) "on" else "off"}${if (autoNew) ", new files automatically" else ""} (314)", "jobs")
    }

    /** FR-314-2 — Apply queues the listed files of that kind, and only those. Off ⇒ refused. */
    suspend fun apply(kind: FixKind): Int? {
        if (!enabled(kind)) return null
        val before = countsOf(kind)
        q.markPendingForKind(now(), kind.id)
        upsertSetting(kind.id, 1L, if (autoNew(kind)) 1L else 0L, now())
        val n = (before["would_add"] ?: 0) + (before["sidecar"] ?: 0)
        Logger.info("file fix: Apply for ${kind.label} — $n file(s) queued (314)", "jobs")
        return n
    }

    /** FR-314-7 — one title, whatever the switches say: its rows of [kind] that would add something go pending (a row
     *  the owner removed too — phase 314c: the title's own Add is the one way back). */
    fun applyTitle(kind: FixKind, mediaId: String): Int {
        val rows = q.listForKind(kind.id, null, 10_000, 0).executeAsList().filter { it.media_id == mediaId && it.state in setOf("would_add", "sidecar", "failed", "removed") }
        for (r in rows) q.setState("pending", r.detail, r.read_bytes, now(), r.path, r.kind)
        return rows.size
    }

    /** Phase 314c — one title's rows for the admin's Tracks tab (add, remove, or why not). */
    fun title(mediaId: String): dev.jellystructure.model.FileFixTitle =
        dev.jellystructure.model.FileFixTitle(mediaId, q.listForMedia(mediaId).executeAsList().mapNotNull { r ->
            val k = FixKind.of(r.kind) ?: return@mapNotNull null
            dev.jellystructure.model.FileFixTitleRow(
                kind = k.id, kindLabel = k.label, path = r.path, label = r.label, state = r.state, detail = r.detail,
                canAdd = r.state in setOf("would_add", "sidecar", "failed", "needs_rename", "removed"),
                canRemove = r.state == "done",
            )
        })

    /**
     * Phase 314c (Remove) — what [kind] added to [mediaId]'s files is taken away again, as a job on the media lane (a copy in
     * the file is a remux): only rows that are `done`. Seeded files only ever got a `.mka` beside them, which the job deletes;
     * a Dolby Vision version file of ours is deleted; the original is never touched beyond dropping our own tracks.
     */
    suspend fun removeTitle(kind: FixKind, mediaId: String): Int {
        val rows = q.listForMedia(mediaId).executeAsList().filter { it.kind == kind.id && it.state == "done" }
        for (r in rows) q.setState("remove_pending", "removing what jellystructure added", r.read_bytes, now(), r.path, r.kind)
        if (rows.isNotEmpty()) Logger.info("file fix: Remove ${kind.label} for $mediaId — ${rows.size} file(s) queued (314)", "jobs")
        return rows.size
    }

    /**
     * Phase 314c (owner, 2026-10-08: rename, asked per film) — the ticked Dolby Vision 7 originals are renamed after their
     * folder (316's mover in place: hard links and seeding untouched, Radarr refreshed, Jellyfin told, watch data and our
     * own rows carried over), then planned again under the new name and their version queued. A torrent seeding the
     * library file itself is never renamed under (the mover's caller refuses it). Only `needs_rename` rows qualify.
     */
    suspend fun renameAndQueue(paths: List<String>): List<dev.jellystructure.model.FileFixRenameResult> {
        val renamer = renameOriginal
        return paths.map { path ->
            val row = q.find(path, FixKind.DOLBY_VISION.id).executeAsOneOrNull()
            when {
                renamer == null -> dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = "renaming isn't available here")
                row == null || row.state != "needs_rename" -> dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = "not waiting for a rename")
                else -> {
                    val r = runCatching { renamer(path) }.getOrElse { dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = it.message) }
                    val newPath = r.newPath
                    if (newPath != null && r.state != "failed") {
                        q.deleteForPath(path)
                        probeCache.remove(path)
                        val item = store.resolve(row.media_id)
                        if (item != null) {
                            runCatching { planOne(FixTarget(item, newPath, true, row.label)) }
                            val queued = applyTitle(FixKind.DOLBY_VISION, row.media_id)
                            history.record(item.id, "file_fix", "Renamed to ${newPath.substringAfterLast('/')} for its Dolby Vision version" +
                                if (queued > 0) "; the version is queued" else "")
                        }
                        Logger.info("file fix: renamed $path → $newPath for its Dolby Vision version (314)", "jobs")
                    }
                    r
                }
            }
        }
    }

    // ── The dry run ───────────────────────────────────────────────────────────────────────────────────────────────

    private var planStatus = FileFixPlanStatus()
    fun planStatus(): FileFixPlanStatus = planStatus

    /** Every film and episode (MKV or not — an MP4 is listed as skipped where it matters). Music videos are left alone. */
    suspend fun targets(): List<FixTarget> = store.allItems().flatMap { item ->
        when (item.kind) {
            MediaKind.MOVIE -> listOf(FixTarget(item, item.path, true, item.title + (item.year?.let { " ($it)" } ?: "")))
            MediaKind.TV_SHOW -> dev.jellystructure.media.DuplicateEpisodes.deduped(item.episodes).map { e ->
                val s = e.seasonNumber; val n = e.episodeNumber
                FixTarget(item, e.path, false, "${item.title} · " + (if (s != null && n != null) "S${s.toString().padStart(2, '0')}E${n.toString().padStart(2, '0')}" else e.filename))
            }
            else -> emptyList()
        }
    }.filter { it.path.startsWith("/") }

    private val probeCache = HashMap<String, Triple<Long, Long, ProbeResult>>()

    /** One probe of [path], cached by size + mtime (a dry run of 9 000 files probes each once). */
    suspend fun probe(path: String, withUids: Boolean = false): ProbeResult? {
        val st = shell.stat(path) ?: return null
        if (!withUids) probeCache[path]?.let { (size, mtime, r) -> if (size == st.first && mtime == st.second) return r }
        val out = shell.run(FileFixCommands.probe(path)).takeIf { it.ok }?.output ?: return null
        val uids = if (withUids) parseTrackUids(shell.run(FileFixCommands.identify(path)).output) else emptyMap()
        val r = parseProbe(path, out, uids) ?: return null
        if (!withUids) probeCache[path] = Triple(st.first, st.second, r)
        return r
    }

    /** Is the sidecar for every planned track already beside the video (a seeded file done before)? */
    private fun sidecarsPresent(path: String, tracks: List<PlannedTrack>): Boolean =
        tracks.isNotEmpty() && tracks.all { shell.exists(sidecarPath(path, it)) }

    private fun videosInFolder(path: String): List<String> =
        shell.listDir(path.substringBeforeLast('/')).filter { f -> VIDEO_EXT.any { f.lowercase().endsWith(it) } }

    /**
     * FR-314-2 — the dry run: every film and episode, each kind's verdict, and whether the file is seeded (A/B then get
     * a sidecar). Writes rows only; never a file. A row already pending or running keeps its state.
     */
    suspend fun buildPlan() {
        if (planStatus.running) return
        val started = now()
        val all = targets()
        planStatus = FileFixPlanStatus(running = true, done = 0, total = all.size, startedAt = started)
        try {
            for ((i, t) in all.withIndex()) {
                runCatching { planOne(t) }.onFailure { Logger.warn("file fix: dry run failed for ${t.path}: ${it.message} (314)", "jobs") }
                if (i % 50 == 0) planStatus = planStatus.copy(done = i)
            }
            for (k in FixKind.entries) q.deleteMissing(k.id, started)
            planStatus = planStatus.copy(running = false, done = all.size, finishedAt = now())
            Logger.info("file fix: dry run done — ${all.size} files (314)", "jobs")
        } catch (e: Exception) {
            planStatus = planStatus.copy(running = false, finishedAt = now(), error = e.message)
            throw e
        }
    }

    /** One file's rows for all three kinds. */
    suspend fun planOne(t: FixTarget) {
        val st = shell.stat(t.path) ?: return
        val probe = probe(t.path) ?: return
        val facts = probe.facts
        var seeded: Boolean? = null
        suspend fun seededNow(): Boolean? {
            if (seeded == null) seeded = when (seeding(t.path)) {
                is SeedingCheckResult.Blocked -> true
                is SeedingCheckResult.Unreachable -> null
                else -> false
            }
            return seeded
        }
        for (kind in listOf(FixKind.STEREO, FixKind.SURROUND)) {
            val v = planAudio(kind, facts)
            val (state, detail) = when (v.action) {
                "add" -> {
                    val lines = v.tracks.joinToString(" · ") { it.describe() }
                    when (seededNow()) {
                        true -> if (sidecarsPresent(t.path, v.tracks)) "done" to "seeded · beside it as a .mka" else "sidecar" to "seeded, a .mka beside it · $lines"
                        null -> "waiting" to "qBittorrent unreachable · $lines"
                        false -> "would_add" to lines
                    }
                }
                "done" -> "done" to "added by jellystructure"
                else -> if (v.reason?.startsWith("MP4") == true && planAudio(kind, facts.copy(container = "matroska")).action == "add") "skipped" to "skipped (MP4)" else continue
            }
            val joined = state == "would_add" && autoNew(kind) && enabled(kind)
            upsertPlanned(t.path, kind.id, t.item.id, t.label, if (joined) "pending" else state, detail, v.estBytes, st.first, st.second, now())
        }
        val folderVideos = if (facts.video?.dvProfile == 7) videosInFolder(t.path) else emptyList()
        val dv = planDolbyVision(facts, t.isFilm, folderVideos)
        // Phase 314c (owner, 2026-10-08: rename, asked per film) — a film that would get its version once its file is
        // named after its folder is listed as `needs_rename`: the owner ticks it, the rename runs, then the version.
        val renamedTo = renamedToFolder(t.path)
        if (dv.action == "skip" && renamedTo != null && !namedAfterFolder(t.path) &&
            planDolbyVision(facts.copy(path = renamedTo), t.isFilm, folderVideos.map { if (it == t.path) renamedTo else it }).action == "add"
        ) {
            upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "needs_rename",
                "rename to ${renamedTo.substringAfterLast('/')} first, then + a Dolby Vision 8.1 version beside it · ~${st.first / 1_000_000_000} GB",
                st.first, st.first, st.second, now())
            return
        }
        when (dv.action) {
            "add" -> upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "would_add",
                "+ a Dolby Vision 8.1 version beside it (${dv.reason}) · ~${st.first / 1_000_000_000} GB", st.first, st.first, st.second, now())
            "done" -> upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "done", "the Dolby Vision 8.1 version is beside it", 0, st.first, st.second, now())
            else -> if (facts.video?.dvProfile == 7) upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "skipped", dv.reason ?: "skipped", 0, st.first, st.second, now())
        }
    }

    // ── The card ──────────────────────────────────────────────────────────────────────────────────────────────────

    private fun countsOf(kind: FixKind): Map<String, Int> = q.countsForKind(kind.id).executeAsList().associate { it.state to it.n.toInt() }

    fun overview(): FileFixOverview {
        val kinds = FixKind.entries.map { k ->
            val rows = q.countsForKind(k.id).executeAsList()
            val setting = q.settingFor(k.id).executeAsOneOrNull()
            val doviMissing = k == FixKind.DOLBY_VISION && !shell.exists(doviTool)
            FileFixKindSummary(
                kind = k.id, label = k.label, sentence = SENTENCES.getValue(k),
                enabled = setting?.enabled == 1L, autoNew = setting?.auto_new == 1L,
                available = !doviMissing, unavailableReason = if (doviMissing) "dovi_tool is not installed in this image" else null,
                counts = rows.associate { it.state to it.n.toInt() },
                eligibleBytes = rows.filter { it.state == "would_add" || it.state == "sidecar" }.sumOf { it.bytes ?: 0L },
            )
        }
        return FileFixOverview(kinds, planStatus, readCapBytes(), q.readBytesSince(now() - 86_400).executeAsOne())
    }

    fun rows(kind: FixKind, state: String?, limit: Int, offset: Int): List<FileFixRow> =
        q.listForKind(kind.id, state, limit.toLong(), offset.toLong()).executeAsList().map { FileFixRow(it.path, it.media_id, it.label, it.state, it.detail, it.est_bytes) }

    /** FR-314-11 — an information row per kind that is off and has files waiting, a warning for failures, nothing at zero. */
    fun dashboardRows(): List<DashboardRow> {
        val out = ArrayList<DashboardRow>()
        for (k in FixKind.entries) {
            if (enabled(k)) continue
            val c = countsOf(k)
            val n = (c["would_add"] ?: 0) + (c["sidecar"] ?: 0)
            if (n == 0) continue
            out += DashboardRow(
                id = "file_fix_${k.id}", domain = "lib", severity = "info",
                label = when (k) {
                    FixKind.STEREO -> "Files a Chromecast or a browser can't play without converting the audio"
                    FixKind.SURROUND -> "Files with only lossless surround audio"
                    FixKind.DOLBY_VISION -> "Dolby Vision films the TVs can't play directly"
                },
                sentence = "${k.label}: adding to them is switched off. Nothing is changed until you switch it on and press Apply.",
                count = n, unit = "file", fix = "elsewhere", where = "Settings", path = "Libraries › Make files play directly",
            )
        }
        val failed = q.failedRows(5).executeAsList()
        if (failed.isNotEmpty()) {
            val total = FixKind.entries.sumOf { countsOf(it)["failed"] ?: 0 }
            out += DashboardRow(
                id = "file_fix_failed", domain = "lib", severity = "warning",
                label = "Files that couldn't be made to play directly",
                sentence = failed.joinToString(" · ") { "${it.label}: ${it.detail}" } + if (total > failed.size) " · and ${total - failed.size} more" else "",
                count = total, unit = "file", fix = "elsewhere", where = "Settings", path = "Libraries › Make files play directly",
            )
        }
        return out
    }

    // ── The queue tick (FR-314-10) ────────────────────────────────────────────────────────────────────────────────

    fun readCapBytes(): Long = DEFAULT_NIGHTLY_READ_CAP

    /** What the next job should be, or null: one at a time, within the 24 h read cap, its kind switched on. */
    fun nextToQueue(jobActive: Boolean): File_fix? {
        if (jobActive || q.countRunning().executeAsOne() > 0L) return null
        val next = q.nextPending().executeAsOneOrNull() ?: return null
        FixKind.of(next.kind) ?: return null
        val read = q.readBytesSince(now() - 86_400).executeAsOne()
        if (read + next.size > readCapBytes()) return null
        return next
    }

    // ── The job ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Runs one file. [cancelled] is the job queue's cancel flag. */
    suspend fun runJob(path: String, kindId: String, cancelled: () -> Boolean, progress: (Double) -> Unit = {}): FixOutcome {
        val kind = FixKind.of(kindId) ?: return FixOutcome.Failed("unknown kind '$kindId'")
        val row = q.find(path, kind.id).executeAsOneOrNull()
        val item = row?.let { store.resolve(it.media_id) } ?: store.allItems().firstOrNull { it.path == path || it.episodes.any { e -> e.path == path } }
            ?: return finish(path, kind, "failed", FixOutcome.Failed("the title is no longer in the library"))
        val stop = { cancelled() || playbackActive() }
        val removing = row?.state == "remove_pending"   // phase 314c — a Remove, not an add
        val waitState = if (removing) "remove_pending" else "pending"
        if (stop()) return finish(path, kind, waitState, FixOutcome.Stopped("waiting for playback to end"), keepDetail = removing)
        q.setState("running", if (removing) "removing what jellystructure added" else row?.detail ?: "", row?.read_bytes ?: 0, now(), path, kind.id)
        val outcome = try {
            MediaFileLock.withLock(path) {
                when {
                    removing -> runRemove(item, path, kind, stop)
                    kind == FixKind.DOLBY_VISION -> runDolbyVision(item, path, stop, progress)
                    else -> runAudio(item, path, kind, stop, progress)
                }
            }
        } catch (e: Exception) {
            // 310's rule: only this job's own cancellation ends it; anything else (a foreign cancellation included) is a
            // failure of this file, recorded and logged.
            if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            FixOutcome.Failed(e.message ?: "unexpected error")
        } finally {
            cleanupWork(path)
        }
        return when (outcome) {
            is FixOutcome.Done -> outcome
            is FixOutcome.Stopped -> finish(path, kind, waitState, outcome, keepDetail = removing)
            // A Remove that found nothing, or failed, leaves the row as it was: what we added is still there (or wasn't).
            is FixOutcome.Skipped -> finish(path, kind, if (removing) "done" else "skipped", outcome)
            is FixOutcome.Failed -> finish(path, kind, if (removing) "done" else "failed", outcome)
        }
    }

    private suspend fun finish(path: String, kind: FixKind, state: String, outcome: FixOutcome, keepDetail: Boolean = false): FixOutcome {
        val reason = when (outcome) {
            is FixOutcome.Failed -> outcome.reason; is FixOutcome.Stopped -> outcome.reason; is FixOutcome.Skipped -> outcome.reason; else -> ""
        }
        val row = q.find(path, kind.id).executeAsOneOrNull()
        if (row != null) q.setState(state, if (keepDetail) "removing what jellystructure added" else reason, row.read_bytes, now(), path, kind.id)
        if (outcome is FixOutcome.Failed) Logger.warn("file fix: ${kind.label} failed for $path: $reason (314)", "jobs")
        return outcome
    }

    private fun cleanupWork(path: String) {
        val kinds = listOf(WorkFiles.Kind.FILE_FIX, WorkFiles.Kind.SIDECAR, WorkFiles.Kind.DV_HEVC, WorkFiles.Kind.DV_TIMESTAMPS,
            WorkFiles.Kind.DV_TAGS, WorkFiles.Kind.DV_VERSION)
        for (k in kinds) shell.remove(WorkFiles.pathFor(path, k))
        for (i in 0 until 8) { shell.remove(WorkFiles.pathFor(path, WorkFiles.Kind.ADDED_AUDIO, i)); shell.remove(WorkFiles.pathFor(path, WorkFiles.Kind.SIDECAR, i)) }
        dev.jellystructure.media.FfmpegRunner.removeWorkDirIfEmpty(path)
    }

    private suspend fun freeBytes(path: String): Long? = shell.run(FileFixCommands.freeBytes(path.substringBeforeLast('/'))).output.trim().lines().lastOrNull()?.trim()?.toLongOrNull()

    /**
     * Phase 314c (Remove) — takes away what [kind] added: kind A/B's `.mka` beside the file (deleted) and its copies in the
     * file (a remux without our tracks, verified, swapped in one rename; never on a seeded file); kind C's version file
     * (deleted, only when it is ours by its `JELLYSTRUCTURE_DV` tag). Then the file is planned again.
     */
    private suspend fun runRemove(item: MediaItem, path: String, kind: FixKind, stop: () -> Boolean): FixOutcome {
        val did = ArrayList<String>()
        if (kind == FixKind.DOLBY_VISION) {
            val version = dvVersionPath(path)
            if (!shell.exists(version)) return FixOutcome.Skipped("no Dolby Vision version beside it")
            val p = shell.run(FileFixCommands.probe(version)).takeIf { it.ok }?.output ?: return FixOutcome.Failed("the version file can't be read; left alone")
            if (!hasOurDvTag(p)) return FixOutcome.Failed("the file beside it isn't jellystructure's version; left alone")
            shell.remove(version)
            if (shell.exists(version)) return FixOutcome.Failed("the version file couldn't be deleted")
            did += "deleted ${version.substringAfterLast('/')}"
        } else {
            val token = if (kind == FixKind.STEREO) "Stereo" else "Surround"
            val dir = path.substringBeforeLast('/')
            val base = path.substringAfterLast('/').substringBeforeLast('.')
            // listDir gives full paths (`find -maxdepth 1`).
            for (f in shell.listDir(dir).map { it.substringAfterLast('/') }.filter { it.startsWith("$base.") && it.endsWith(".$token.mka") }) {
                shell.remove("$dir/$f")
                if (shell.exists("$dir/$f")) return FixOutcome.Failed("$f couldn't be deleted")
                did += "deleted $f"
            }
            val ids = copyTrackIds(shell.run(FileFixCommands.identify(path)).output, kind)
            if (ids.isNotEmpty()) {
                if (seeding(path) is SeedingCheckResult.Blocked) return FixOutcome.Failed("the file is seeded now, so its added track can't be taken out of it")
                val before = probe(path) ?: return FixOutcome.Failed("ffprobe could not read the file")
                shell.run(WorkFiles.prepareCommand(path)).takeIf { it.ok } ?: return FixOutcome.Failed("could not make the work folder")
                val work = WorkFiles.pathFor(path, WorkFiles.Kind.FILE_FIX)
                val mux = shell.runStoppable(FileFixCommands.removeTracks(path, ids, work), work, stop) ?: return FixOutcome.Stopped("stopped for playback")
                if (mux.exit > 1) return FixOutcome.Failed("mkvmerge failed: ${mux.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${mux.exit}"}")
                val after = probe(work) ?: return FixOutcome.Failed("the new file can't be read")
                // mkvmerge's track ids are the file's track order, which is ffprobe's stream order (attachments come after).
                verifyRemoved(before.streams, ids.toSet(), after.streams)?.let { return FixOutcome.Failed("verification: $it") }
                if (kotlin.math.abs(after.facts.durationMs - before.facts.durationMs) > 60) return FixOutcome.Failed("verification: the duration changed")
                if (seeding(path) is SeedingCheckResult.Blocked) return FixOutcome.Stopped("the file became seeded during the job")
                if (!shell.run(FileFixCommands.moveIntoPlace(work, path, path)).ok) return FixOutcome.Failed("the swap failed; the file is untouched")
                did += "${ids.size} added ${if (ids.size == 1) "track" else "tracks"} taken out of the file"
            }
        }
        if (did.isEmpty()) return FixOutcome.Skipped("nothing jellystructure added is there")
        history.record(item.id, "file_fix", "${kind.label} removed: ${did.joinToString("; ")} · ${path.substringAfterLast('/')}")
        q.setState("removed", did.joinToString("; "), 0, now(), path, kind.id)
        probeCache.remove(path)
        runCatching { planOne(FixTarget(item, path, item.kind == MediaKind.MOVIE, item.title)) }
        runCatching { afterWrite(item) }
        Logger.info("file fix: ${kind.label} removed for $path (${did.joinToString("; ")}) (314)", "jobs")
        return FixOutcome.Done
    }

    /** Kinds A and B: encode each planned track, then either append them (unseeded) or place them as sidecars (seeded). */
    private suspend fun runAudio(item: MediaItem, path: String, kind: FixKind, stop: () -> Boolean, progress: (Double) -> Unit): FixOutcome {
        // FR-314-3 step 1 — the guard again: a file can become seeded after the dry run. Unreachable ⇒ wait.
        val seeded = when (val g = seeding(path)) {
            is SeedingCheckResult.Blocked -> true
            is SeedingCheckResult.Unreachable -> return FixOutcome.Stopped("qBittorrent unreachable: ${g.reason}")
            else -> false
        }
        val before = probe(path, withUids = true) ?: return FixOutcome.Failed("ffprobe could not read the file")
        val verdict = planAudio(kind, before.facts)
        if (verdict.action != "add") return FixOutcome.Skipped(if (verdict.action == "done") "already added" else verdict.reason ?: "nothing to add")
        if (seeded && sidecarsPresent(path, verdict.tracks)) return FixOutcome.Skipped("already beside it as a .mka")
        val size = shell.stat(path)?.first ?: 0L
        val need = (if (seeded) 0L else (size * 11) / 10) + verdict.estBytes * 2
        val free = freeBytes(path)
        if (free != null && free < need) return FixOutcome.Failed("not enough free space (${need / 1_000_000_000} GB needed, ${free / 1_000_000_000} GB free)")
        shell.run(WorkFiles.prepareCommand(path)).takeIf { it.ok } ?: return FixOutcome.Failed("could not make the work folder")
        val encoded = ArrayList<String>()
        for ((i, t) in verdict.tracks.withIndex()) {
            val out = FileFixCommands.addedTrackPath(path, i)
            val uid = before.facts.audio.firstOrNull { it.order == t.sourceOrder }?.uid
            val r = shell.runStoppable(FileFixCommands.encodeTrack(path, t, uid, today(), out), out, stop)
                ?: return FixOutcome.Stopped("stopped for playback")
            if (!r.ok) return FixOutcome.Failed("encoding the ${t.name} track failed: ${r.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.exit}"}")
            if (!shell.run(FileFixCommands.decodeCheck(out)).ok) return FixOutcome.Failed("the encoded ${t.name} track doesn't decode")
            encoded += out
            progress((i + 1).toDouble() / (verdict.tracks.size + 1) * 100.0)
        }
        val readBytes = size * (verdict.tracks.size + if (seeded) 0 else 1)
        if (seeded) {
            // FR-314-6 — beside the video, never in it: the torrent's bytes stay as they are.
            for ((i, t) in verdict.tracks.withIndex()) {
                val target = sidecarPath(path, t)
                if (!shell.run(FileFixCommands.moveIntoPlace(encoded[i], target, path)).ok) return FixOutcome.Failed("could not place ${target.substringAfterLast('/')}")
            }
            history.record(item.id, "file_fix", "${kind.label}: ${verdict.tracks.joinToString(", ") { it.describe() }} — beside the file as a .mka (seeded) · ${path.substringAfterLast('/')}")
            q.setState("done", "seeded · beside it as a .mka · " + verdict.tracks.joinToString(" · ") { it.describe() }, readBytes, now(), path, kind.id)
        } else {
            val work = WorkFiles.pathFor(path, WorkFiles.Kind.FILE_FIX)
            val mux = shell.runStoppable(FileFixCommands.appendTracks(path, encoded, work), work, stop) ?: return FixOutcome.Stopped("stopped for playback")
            // mkvmerge exits 1 for warnings only; the verification below decides.
            if (mux.exit > 1) return FixOutcome.Failed("mkvmerge failed: ${mux.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${mux.exit}"}")
            val after = probe(work) ?: return FixOutcome.Failed("the new file can't be read")
            verifyAppended(before.streams, before.facts.durationMs, after.streams, after.facts.durationMs, verdict.tracks)?.let { return FixOutcome.Failed("verification: $it") }
            // FR-314-3 step 1 again, at the last moment: a file seeded since the start is left alone.
            if (seeding(path) is SeedingCheckResult.Blocked) return FixOutcome.Stopped("the file became seeded during the job; it gets a .mka beside it next time")
            if (!shell.run(FileFixCommands.moveIntoPlace(work, path, path)).ok) return FixOutcome.Failed("the swap failed; the original is untouched")
            history.record(item.id, "file_fix", "${kind.label}: ${verdict.tracks.joinToString(", ") { it.describe() }} · ${path.substringAfterLast('/')}")
            q.setState("done", verdict.tracks.joinToString(" · ") { it.describe() }, readBytes, now(), path, kind.id)
        }
        probeCache.remove(path)
        runCatching { afterWrite(item) }
        Logger.info("file fix: ${kind.label} done for $path (${if (seeded) "sidecar" else "in the file"}) (314)", "jobs")
        return FixOutcome.Done
    }

    /** Kind C: a profile-8.1 version beside the original, which is never changed. */
    private suspend fun runDolbyVision(item: MediaItem, path: String, stop: () -> Boolean, progress: (Double) -> Unit): FixOutcome {
        if (!shell.exists(doviTool)) return FixOutcome.Failed("dovi_tool is not installed in this image")
        val before = probe(path) ?: return FixOutcome.Failed("ffprobe could not read the file")
        val verdict = planDolbyVision(before.facts, item.kind == MediaKind.MOVIE, videosInFolder(path))
        if (verdict.action != "add") return FixOutcome.Skipped(if (verdict.action == "done") "the version is already beside it" else verdict.reason ?: "not eligible")
        if (before.streams.firstOrNull()?.type != "video") return FixOutcome.Skipped("the picture isn't the file's first track")
        val target = dvVersionPath(path)
        // Owner, 2026-10-08 (review item 7) — Radarr must never take the copy for an upgrade and delete the original.
        radarrParse?.let { parse ->
            val copy = parse(target.substringAfterLast('/').substringBeforeLast('.'))
                ?: return FixOutcome.Stopped("Radarr didn't answer; tried again later")
            val original = parse(path.substringAfterLast('/').substringBeforeLast('.'))
            radarrUnsafe(original, copy)?.let { return FixOutcome.Skipped(it) }
        }
        val size = shell.stat(path)?.first ?: 0L
        val free = freeBytes(path)
        if (free != null && free < (size * 22) / 10) return FixOutcome.Failed("not enough free space (${(size * 22) / 10 / 1_000_000_000} GB needed, ${free / 1_000_000_000} GB free)")
        shell.run(WorkFiles.prepareCommand(path)).takeIf { it.ok } ?: return FixOutcome.Failed("could not make the work folder")
        val hevc = WorkFiles.pathFor(path, WorkFiles.Kind.DV_HEVC)
        val ts = WorkFiles.pathFor(path, WorkFiles.Kind.DV_TIMESTAMPS)
        val tags = WorkFiles.pathFor(path, WorkFiles.Kind.DV_TAGS)
        val work = WorkFiles.pathFor(path, WorkFiles.Kind.DV_VERSION)
        val rc = "$hevc.rc"
        val conv = shell.runStoppable(FileFixCommands.dvConvert(path, doviTool, hevc, rc), hevc, stop)
        shell.remove(rc)
        if (conv == null) return FixOutcome.Stopped("stopped for playback")
        if (!conv.ok) return FixOutcome.Failed("dovi_tool convert failed: ${conv.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${conv.exit}"}")
        progress(40.0)
        if (!shell.run(FileFixCommands.dvTimestamps(path, ts)).ok) return FixOutcome.Failed("mkvextract couldn't read the timestamps")
        if (!shell.writeText(tags, FileFixCommands.dvTagsXml(today(), before.facts.video?.dvElPresent == true))) return FixOutcome.Failed("could not write the tags file")
        val videoLang = before.streams.firstOrNull()?.language
        val mux = shell.runStoppable(FileFixCommands.dvMux(hevc, ts, path, videoLang, tags, work), work, stop) ?: return FixOutcome.Stopped("stopped for playback")
        if (mux.exit > 1) return FixOutcome.Failed("mkvmerge failed: ${mux.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${mux.exit}"}")
        shell.remove(hevc)
        progress(70.0)
        val after = probe(work) ?: return FixOutcome.Failed("the version can't be read")
        val framesBefore = shell.runStoppable(FileFixCommands.frameCount(path), path, stop)?.output?.trim()?.trimEnd(',')?.toLongOrNull()
        val framesAfter = shell.run(FileFixCommands.frameCount(work)).output.trim().trimEnd(',').toLongOrNull()
        verifyDvVersion(before.streams, framesBefore, after.streams, framesAfter, after.facts.video)?.let { return FixOutcome.Failed("verification: $it") }
        if (shell.exists(target)) return FixOutcome.Skipped("a file named ${target.substringAfterLast('/')} is already there")
        if (!shell.run(FileFixCommands.moveIntoPlace(work, target, path)).ok) return FixOutcome.Failed("could not place the version")
        history.record(item.id, "file_fix", "Dolby Vision the TVs play: a profile 8.1 version beside the original · ${target.substringAfterLast('/')}")
        q.setState("done", "the Dolby Vision 8.1 version is beside it", size * 3, now(), path, FixKind.DOLBY_VISION.id)
        runCatching { afterWrite(item) }
        Logger.info("file fix: Dolby Vision 8.1 version written for $path (314)", "jobs")
        return FixOutcome.Done
    }

    companion object {
        /** The one instance (Main.kt), for the routes and the Dashboard, as `SuggestionService.current`. */
        var current: FileFixService? = null
        const val DEFAULT_DOVI_TOOL = "/usr/local/bin/dovi_tool"
        /** FR-314-10 — 2 TB read per 24 h, so a backlog spreads over nights instead of saturating the disks. */
        const val DEFAULT_NIGHTLY_READ_CAP = 2_000_000_000_000L
        private val VIDEO_EXT = listOf(".mkv", ".mp4", ".m4v", ".avi", ".ts", ".m2ts", ".mov", ".wmv")
        val SENTENCES: Map<FixKind, String> = mapOf(
            FixKind.STEREO to "Adds a stereo AAC track where a file has none a Chromecast, a browser or a phone without Dolby can play. The original tracks stay.",
            FixKind.SURROUND to "Adds a 5.1 E-AC-3 track where a language has only TrueHD or DTS, for the Mac, an iPhone and receivers. The original tracks stay.",
            FixKind.DOLBY_VISION to "Writes a Dolby Vision 8.1 version beside a profile 7 film, so the TVs play it directly. The original stays exactly as it is.",
        )
    }
}

/**
 * Owner, 2026-10-08 — Radarr reads the version's name before it is written: a copy it would score as a better release
 * could replace the original (its recycle bin is off here, so the original would be deleted). Safe only when the copy's
 * name parses to no quality (or *Unknown*) and a custom-format score no higher than the original's. Null = safe.
 */
fun radarrUnsafe(original: ArrParseResult?, copy: ArrParseResult): String? {
    if (copy.quality != null && !copy.quality.equals("Unknown", ignoreCase = true))
        return "Radarr would read the copy as ${copy.quality}; it could replace the original"
    if (original != null && copy.customFormatScore > original.customFormatScore)
        return "Radarr would score the copy ${copy.customFormatScore} over the original's ${original.customFormatScore}; it could replace the original"
    return null
}
