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
 * FR-314-15 — what grouping a kind C version did, in the words the title's history uses. [merged] false with a
 * [detail] is the honest outcome when Jellyfin never resolved the path into an item: the file is written and good,
 * the grouping is not done, and the next dry run tries again rather than anyone assuming it worked.
 */
data class VersionGrouping(val merged: Boolean, val detail: String)

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
    /**
     * After a write: tell Jellyfin to re-read the title, and — only when `nudgeArr` — nudge Radarr/Sonarr (284's
     * post-write sync). FR-314-12: a kind C version is ours and it is not a release, so Radarr is never asked to
     * rescan a folder holding one; its scan would take the version for the film's file (DEB-868).
     */
    private val afterWrite: suspend (item: MediaItem, nudgeArr: Boolean) -> Unit,
    /** FR-314-12 — what Radarr has the film's file as, read around a kind C write; null where Radarr isn't wired. */
    private val radarrLink: RadarrLink? = null,
    /**
     * FR-314-13 — after a kind C version is written or removed, whatever of the film's images and NFO is not in its
     * folder is written back on the spot. Radarr deletes the extras it had adopted when a `moviefile` row changes
     * under it (DEB-869), and the hourly pass is up to an hour away. Returns the filenames it put back; the default
     * is the no-op for tests and anywhere it isn't wired.
     */
    private val reassertAssets: suspend (MediaItem) -> List<String> = { emptyList() },
    /** Is anything playing that this job must yield to (the job queue's own rule)? */
    private val playbackActive: () -> Boolean,
    private val shell: FileFixShell = PosixFileFixShell,
    private val doviTool: String = DEFAULT_DOVI_TOOL,
    private val now: () -> Long,
    private val today: () -> String,
    /** Phase 314c — renames a Dolby Vision 7 original after its folder (316's mover, in place); null where not wired. */
    private val renameOriginal: (suspend (String) -> dev.jellystructure.model.FileFixRenameResult)? = null,
    /**
     * FR-314-15 (DEB-875) — makes Jellyfin group a version that lives in the versions library with its film: report
     * the one created path, resolve the item it becomes, merge it onto the film. **Asserted, never remembered** — it
     * is called after a write and again from the dry run while the file is there, and a merge that already holds is a
     * no-op. Null where it isn't wired (tests, and an installation with no versions root).
     */
    private val groupVersion: (suspend (item: MediaItem, versionPath: String) -> VersionGrouping)? = null,
    /**
     * FR-314-15 — tells Jellyfin our version is gone, so it drops the alternate item and leaves the film its own file
     * and its watch data (measured, DEB-875). Never `DELETE /Videos/{id}/AlternateSources`: that drops a film's
     * *every* alternate, not ours.
     */
    private val ungroupVersion: (suspend (versionPath: String) -> Unit)? = null,
    /** FR-314-15 — Radarr's and Sonarr's root folders, for the overlap refusal. Empty where the arrs aren't wired. */
    private val arrRootFolders: (suspend () -> List<String>)? = null,
    /**
     * FR-314-15 — keeps the versions library off every viewer's home screen (their own `MyMediaExcludes` and
     * `LatestItemsExcludes`, the only thing we write on a viewer's account) and reports any restricted account that
     * cannot see it. Called after a version is grouped, which is both when a write lands and on every later pass, so
     * a viewer who appears later is covered; the wiring rate-limits it.
     */
    private val assertViewerVisibility: (suspend () -> Unit)? = null,
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
        // FR-314-18 — the precondition is re-derived here, not read off the row. `needs_rename` is written by one
        // branch guarded by `root == null`, and a row's state is only ever recomputed by another dry run, so with a
        // root configured the rows still in it are verdicts from before it — and this is the one write path whose
        // gate was the stored state. Acting on them renames library files the live configuration needs no rename
        // for: a `moviefile` row deleted and re-created under Radarr per film (FR-314-13, FR-314-14, DEB-869).
        val root = versionsRoot()
        return paths.map { path ->
            val row = q.find(path, FixKind.DOLBY_VISION.id).executeAsOneOrNull()
            when {
                renamer == null -> dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = "renaming isn't available here")
                row == null || row.state != "needs_rename" -> dev.jellystructure.model.FileFixRenameResult(path, state = "failed", detail = "not waiting for a rename")
                root != null -> {
                    // The row is corrected in the same call, so the refusal both leaves the file alone and takes the
                    // button away: the dry run's own rules put this film where it belongs (`would_add`).
                    store.resolve(row.media_id)?.let { runCatching { planOne(FixTarget(it, path, true, row.label)) } }
                    dev.jellystructure.model.FileFixRenameResult(path, state = "failed",
                        detail = "no rename is needed any more: the versions library groups the film whatever its file is called. " +
                            "Nothing was renamed, and this film is now listed as one to add.")
                }
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
        // FR-314-15 — with a versions root the version does not live in the film's folder, so the target is stat'ed
        // rather than looked for among the folder's videos.
        val root = versionsRoot()
        val versionPath = dvVersionPath(t.path, root)
        val versionThere = root != null && facts.video?.dvProfile == 7 && shell.exists(versionPath)
        val dv = planDolbyVision(facts, t.isFilm, folderVideos, root, versionThere)
        val where = if (root != null) "in the versions library" else "beside it"
        // Phase 314c (owner, 2026-10-08: rename, asked per film) — a film that would get its version once its file is
        // named after its folder is listed as `needs_rename`: the owner ticks it, the rename runs, then the version.
        // FR-314-15 — only for the beside-the-original placement: a merged version groups whatever the original is
        // called, so with a root configured no film waits on a rename and `needs_rename` leaves this kind.
        val renamedTo = renamedToFolder(t.path)
        if (root == null && dv.action == "skip" && renamedTo != null && !namedAfterFolder(t.path) &&
            planDolbyVision(facts.copy(path = renamedTo), t.isFilm, folderVideos.map { if (it == t.path) renamedTo else it }).action == "add"
        ) {
            upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "needs_rename",
                "rename to ${renamedTo.substringAfterLast('/')} first, then + a Dolby Vision 8.1 version beside it · ~${st.first / 1_000_000_000} GB",
                st.first, st.first, st.second, now())
            return
        }
        when (dv.action) {
            "add" -> upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "would_add",
                "+ a Dolby Vision 8.1 version $where (${dv.reason}) · ~${st.first / 1_000_000_000} GB", st.first, st.first, st.second, now())
            "done" -> {
                upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "done", "the Dolby Vision 8.1 version is $where", 0, st.first, st.second, now())
                // FR-314-15 — the merge is asserted, not remembered: a version that is on disk and not grouped (a
                // library rescan that re-created the item, a merge that never landed) is merged again here, and a
                // merge that already holds costs one read.
                if (versionThere) groupAndKeepHidden(t.item, versionPath)
            }
            else -> if (facts.video?.dvProfile == 7) upsertPlanned(t.path, FixKind.DOLBY_VISION.id, t.item.id, t.label, "skipped", dv.reason ?: "skipped", 0, st.first, st.second, now())
        }
    }

    /** FR-314-15 — the versions library's root, or null when the owner has not configured one. */
    private fun versionsRoot(): String? = config().fileFix.versionsRoot()

    /**
     * FR-314-15 — group the version with its film and, once it is grouped, keep that library out of every viewer's
     * way. Null when grouping isn't wired. Never throws: the file is written and good whatever Jellyfin answers.
     */
    private suspend fun groupAndKeepHidden(item: MediaItem, versionPath: String): VersionGrouping? {
        val grouped = runCatching { groupVersion?.invoke(item, versionPath) }.getOrNull() ?: return null
        if (grouped.merged) runCatching { assertViewerVisibility?.invoke() }
        return grouped
    }

    // ── The card ──────────────────────────────────────────────────────────────────────────────────────────────────

    private fun countsOf(kind: FixKind): Map<String, Int> = q.countsForKind(kind.id).executeAsList().associate { it.state to it.n.toInt() }

    /**
     * FR-314-18 — one sentence when [kind]'s stored verdicts were computed under a different `dv_versions_root`, else
     * null. A row's state is written by the dry run and recomputed only by another one, and an installation with
     * `scan_interval_hours = 0` never plans on its own, so nothing corrects these rows with time.
     *
     * What it keys on is a verdict the live rules **cannot produce**, not a timestamp: with a root configured no film
     * waits on a rename and no film is skipped for its folder's naming (FR-314-15), so a row still in one of those
     * states was decided when there was no root. The reverse — a root cleared, leaving verdicts computed under one —
     * leaves no impossible state behind and is not detected here; it is also not a direction this installation takes.
     */
    private fun staleNote(kind: FixKind, root: String?, counts: Map<String, Int>): String? {
        if (kind != FixKind.DOLBY_VISION || root == null) return null
        val n = q.countPreRootDvVerdicts(kind.id, DV_SKIP_FILE_NOT_NAMED_AFTER_FOLDER, DV_SKIP_OTHER_VIDEO_NOT_NAMED).executeAsOne().toInt()
        if (n == 0) return null
        val others = counts.values.sum() - n
        return "These counts were worked out before the versions library was configured, when a version had to go beside the film and " +
            "a rename came first. $n of the ${n + others} films were decided under that rule and would be decided differently now. " +
            "Press Find files to work them out again — Apply waits for that, and finding only reads."
    }

    fun overview(): FileFixOverview {
        val root = versionsRoot()
        val kinds = FixKind.entries.map { k ->
            val rows = q.countsForKind(k.id).executeAsList()
            val setting = q.settingFor(k.id).executeAsOneOrNull()
            val doviMissing = k == FixKind.DOLBY_VISION && !shell.exists(doviTool)
            val counts = rows.associate { it.state to it.n.toInt() }
            FileFixKindSummary(
                kind = k.id, label = k.label, sentence = sentenceFor(k, root),
                enabled = setting?.enabled == 1L, autoNew = setting?.auto_new == 1L,
                available = !doviMissing, unavailableReason = if (doviMissing) "dovi_tool is not installed in this image" else null,
                counts = counts,
                eligibleBytes = rows.filter { it.state == "would_add" || it.state == "sidecar" }.sumOf { it.bytes ?: 0L },
                staleNote = staleNote(k, root, counts),
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
            // FR-314-15 — wherever the version was placed, including a version written under the old
            // beside-the-original placement on an installation that has since configured a root.
            val root = versionsRoot()
            val version = listOfNotNull(dvVersionPath(path, root), dvVersionPath(path).takeIf { root != null })
                .firstOrNull { shell.exists(it) }
                ?: return FixOutcome.Skipped("no Dolby Vision version to take away")
            val p = shell.run(FileFixCommands.probe(version)).takeIf { it.ok }?.output ?: return FixOutcome.Failed("the version file can't be read; left alone")
            if (!hasOurDvTag(p)) return FixOutcome.Failed("that file isn't jellystructure's version; left alone")
            shell.remove(version)
            if (shell.exists(version)) return FixOutcome.Failed("the version file couldn't be deleted")
            did += "deleted ${version.substringAfterLast('/')}"
            // FR-314-15 — Jellyfin drops the alternate item from the deletion of the path; the film keeps its own
            // file and its watch data (measured, DEB-875). Never `AlternateSources`: it unmerges every alternate.
            runCatching { ungroupVersion?.invoke(version) }
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
        // FR-314-12 — a deleted version is no business of Radarr's either: the half of DEB-868's damage that left the
        // film at `movieFileId 0` happened on this path, when our nudge sent Radarr to look at a folder it had just
        // adopted a file from.
        runCatching { afterWrite(item, kind != FixKind.DOLBY_VISION) }
        // FR-314-13 — a deleted version is a `moviefile` row changing under Radarr, which takes the film's adopted
        // extras with it. Kind C only: A and B leave the film's own files and Radarr's row where they were.
        if (kind == FixKind.DOLBY_VISION) runCatching { reassertAssets(item) }
        if (kind == FixKind.DOLBY_VISION) reportRadarrLink(item, path)
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
        runCatching { afterWrite(item, true) }   // FR-314-12 — kinds A and B keep their nudge: the file's identity doesn't change
        Logger.info("file fix: ${kind.label} done for $path (${if (seeded) "sidecar" else "in the file"}) (314)", "jobs")
        return FixOutcome.Done
    }

    /** Kind C: a profile-8.1 version beside the original, which is never changed. */
    private suspend fun runDolbyVision(item: MediaItem, path: String, stop: () -> Boolean, progress: (Double) -> Unit): FixOutcome {
        if (!shell.exists(doviTool)) return FixOutcome.Failed("dovi_tool is not installed in this image")
        val before = probe(path) ?: return FixOutcome.Failed("ffprobe could not read the file")
        // FR-314-15 — where the version goes, and the refusal that keeps the placement meaningful: inside a Radarr
        // root folder, Radarr's own scan would adopt it as the movie's file and no API of ours could put the link
        // back (DEB-868). Checked per job, because a root folder can be added to Radarr after the root is set.
        val root = versionsRoot()
        if (root != null) {
            // FR-314-16 (DEB-898) — the root is the backend's path and the arrs name theirs in Jellyfin's view, so
            // the overlap is compared on the translation. No mapping, no translation: refused here, naming the
            // library row to fill in, rather than two minutes later as "the item never appeared".
            val jfRoot = jellyfinViewOrNull(root, config().libraries)
                ?: return FixOutcome.Failed("no library mapping covers the versions root $root — fill in that library's local_path on Settings ▸ Libraries so Jellyfin's name for the folder is known; nothing was written")
            val roots = runCatching { arrRootFolders?.invoke() }.getOrNull()
            if (roots != null) versionsRootOverlapsArrRoot(jfRoot, roots)?.let {
                return FixOutcome.Failed("the versions root $root is $jfRoot to Jellyfin, which the media root $it holds — Radarr would take the version for the film's file (DEB-868); point it somewhere Radarr does not scan")
            }
        }
        val target = dvVersionPath(path, root)
        val verdict = planDolbyVision(before.facts, item.kind == MediaKind.MOVIE, videosInFolder(path), root, shell.exists(target))
        if (verdict.action != "add") return FixOutcome.Skipped(if (verdict.action == "done") "the version is already there" else verdict.reason ?: "not eligible")
        if (before.streams.firstOrNull()?.type != "video") return FixOutcome.Skipped("the picture isn't the file's first track")
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
        // FR-314-15 — the versions root holds a folder per film, and this film's does not exist the first time.
        if (root != null && !shell.run(FileFixCommands.makeVersionFolder(target, path.substringBeforeLast('/'))).ok) {
            return FixOutcome.Failed("could not make the version's folder under $root")
        }
        if (!shell.run(FileFixCommands.moveIntoPlace(work, target, path)).ok) return FixOutcome.Failed("could not place the version")
        val where = if (root != null) "in the versions library" else "beside the original"
        history.record(item.id, "file_fix", "Dolby Vision the TVs play: a profile 8.1 version $where · ${target.substringAfterLast('/')}")
        q.setState("done", "the Dolby Vision 8.1 version is ${if (root != null) "in the versions library" else "beside it"}", size * 3, now(), path, FixKind.DOLBY_VISION.id)
        // FR-314-12 — Jellyfin is told, Radarr is not: its rescan takes the version for the film's file, and nothing
        // jellystructure can call puts that back (DEB-868).
        runCatching { afterWrite(item, false) }
        runCatching { reassertAssets(item) }   // FR-314-13 — after the Jellyfin refresh, not instead of it
        // FR-314-15 — the version is a film of its own to Jellyfin until it is merged onto this one. Grouping is
        // reported either way: the file is written and good even when Jellyfin has not resolved it yet, and the next
        // dry run asserts the merge rather than anyone assuming it happened.
        if (root != null) {
            val grouped = groupAndKeepHidden(item, target)
            if (grouped != null && !grouped.merged) {
                history.record(item.id, "file_fix", "The version is written, but Jellyfin has not grouped it with the film yet: ${grouped.detail}. The next pass tries again.")
                Logger.warn("file fix: version written but not grouped for $path — ${grouped.detail} (314, FR-314-15)", "jobs")
            }
        }
        reportRadarrLink(item, path)
        Logger.info("file fix: Dolby Vision 8.1 version written for $path (314)", "jobs")
        return FixOutcome.Done
    }

    /**
     * FR-314-12 — after a kind C version is written or removed, read what Radarr has the film's file as and record
     * anything that is not the original: the link moved to our version, or it went to 0 with the film's own
     * `moviefile` row still naming the original (DEB-868's two halves).
     *
     * It reports rather than repairs because Radarr's API cannot re-point a movie at a file at all — see
     * [RadarrLink]. Best-effort throughout: an unreachable Radarr never fails a job that has already succeeded.
     */
    private suspend fun reportRadarrLink(item: MediaItem, path: String) {
        // FR-314-15 — with a versions root Radarr has no file of ours in its view at all, so this read costs nothing
        // and becomes the canary: a link that still moves means the root is somewhere Radarr scans.
        val drift = runCatching { radarrLinkDrift(radarrLink, item, path, dvVersionPath(path, versionsRoot())) }.getOrNull() ?: return
        history.record(item.id, "file_fix", "$drift. Jellystructure can't put that back — it takes Radarr's own UI (DEB-868).")
        Logger.warn("file fix: $drift — for $path (314, FR-314-12)", "jobs")
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

        /**
         * FR-314-18 — the card's headline sentence takes the same `root != null` branch each row's detail already
         * takes. *Beside a profile 7 film* describes the superseded placement, and it is the shape DEB-868 came out
         * of, so with a versions root configured it advertises at the top of the card the hazard the root exists to
         * avoid. Kinds A and B place nothing a root can move and read their sentence unchanged.
         */
        fun sentenceFor(kind: FixKind, versionsRoot: String?): String =
            if (kind == FixKind.DOLBY_VISION && versionsRoot != null)
                "Writes a Dolby Vision 8.1 version of a profile 7 film into the versions library, which Jellyfin shows as another " +
                    "version of the same film, so the TVs play it directly. The original stays exactly as it is."
            else SENTENCES.getValue(kind)
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
