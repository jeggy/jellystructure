package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.PosixFileFixShell
import dev.jellystructure.media.WorkFiles
import dev.jellystructure.media.parseSeasonEpisodes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * DEB-2476 (DEB-2474, FR-6, FR-16) — a run. Reads the listing through the site's lane, maps and diffs it against what is on
 * disk, downloads what is missing (newest first, at most [LaneRules.MAX_PER_RUN]), checks each file before it is placed,
 * places it atomically and never over anything, tells Jellyfin by path, and finally applies retention under FR-16's guards.
 * Idempotent by construction: the diff is recomputed every run.
 */
enum class RunTrigger(val id: String) { MANUAL("manual"), SCHEDULE("schedule"), ALL("run-all") }

data class RunResult(val status: String, val summary: String, val lines: List<RunLine>, val placed: Int = 0, val deleted: Int = 0)

class AutofetchEngine(
    private val store: AutofetchStore,
    private val lanes: SiteLanes,
    private val tools: MediaTools,
    private val world: AutofetchWorld,
    private val shell: FileFixShell = PosixFileFixShell,
) {
    // Cancellation-safe: a plain lock, so the release in `finally` can never be skipped by a cancelled coroutine.
    private val running = dev.jellystructure.ops.LockedMap<String, Boolean>()

    fun isRunning(id: String): Boolean = running[id] == true

    /** Null when the fetcher is gone, the engine is off, or the run was deferred for viewers (nothing recorded). */
    suspend fun run(id: String, trigger: RunTrigger, stop: () -> Boolean = { false }): RunResult? {
        val f = store.fetcher(id) ?: return null
        if (!world.enabled()) return null
        if (trigger != RunTrigger.MANUAL && (!f.enabled || world.waitForViewers())) return null
        var acquired = false
        running.getOrPut(id) { acquired = true; true }
        if (!acquired) return null
        val now = world.nowSec()
        val runId = store.startRun(id, trigger.id, now)
        val lines = ArrayList<RunLine>()
        val result = try { runInner(f, trigger, lines, stop) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) {
                store.finishRun(runId, world.nowSec(), "cancelled", "stopped", lines)
                throw e
            }
            lines += RunLine("error", e.message ?: e::class.simpleName.orEmpty())
            RunResult("failed", "failed · ${e.message ?: "unexpected error"}", lines)
        } finally {
            running.remove(id)
        }
        store.finishRun(runId, world.nowSec(), result.status, result.summary, result.lines)
        if (result.status != "deferred") store.setRan(id, world.nowSec())
        world.activity(if (result.status == "failed") "warn" else "info", "Auto-fetch · ${f.name} · ${result.summary}", "autofetch-$runId")
        return result
    }

    private suspend fun runInner(f: Fetcher, trigger: RunTrigger, lines: MutableList<RunLine>, stop: () -> Boolean): RunResult {
        val recipe = f.recipe ?: return RunResult("failed", "failed · the stored recipe cannot be read", lines)
        if (f.state == "needs_reinvestigation") return RunResult("needs_reinvestigation", "Needs re-investigation · ${f.stateReason}", lines)
        world.sonarrReason(f.tmdbId, f.seriesPath)?.let { reason ->
            store.setState(f.id, "refused", reason)
            return RunResult("refused", reason, lines.also { it += RunLine("refused", reason) })
        }
        val lane = lanes.forSite(f.site)
        lane.state().let { if (it.stopped) return RunResult("stopped", "Site stopped · ${it.stoppedReason}", lines) }
        val stoppedBefore = lane.state().stopped

        val read = ListingFetcher.read(recipe, lane)
        if (read is ListingRead.Blocked) return blockedResult(f, lane, read.outcome, stoppedBefore, lines)
        read as ListingRead.Read
        val now = world.nowSec()
        if (read.items.isEmpty()) {
            store.setState(f.id, "needs_reinvestigation", "0 videos found")
            return RunResult("needs_reinvestigation", "Needs re-investigation · 0 videos found", lines.also { it += RunLine("changed", "the listing yields 0 videos") })
        }
        store.setListOk(f.id, now)

        // ---- the listing, numbered and stored ------------------------------------------------------------------------
        val proofHelper = RecipeProof(tools, world)
        val listed = LinkedHashMap<String, Pair<ListedItem, SeasonEpisode?>>()
        for (it in read.items) {
            val itemId = it.id?.takeIf { v -> v.isNotBlank() } ?: it.link ?: continue
            if (itemId in listed) continue
            val se = proofHelper.numberOf(recipe, it)
            listed[itemId] = it to se
            store.upsertListed(f.id, itemId, it, se, Numbering.isoDate(it.published), now)
        }
        val stored = store.items(f.id)
        // Missing marks: only a full read is evidence (FR-16.5).
        if (read.full) for (s in stored) if (s.placedPath != null && s.itemId !in listed && s.missingSince == null) store.markMissing(f.id, s.itemId, now)

        // ---- the diff ---------------------------------------------------------------------------------------------------
        val folderCodes = existingCodes(f.seriesPath)
        var rows = rowsOf(f.id, listed.keys)
        val candidates = RetentionPlanner.downloadable(rows, f.retention)
        val tmdbEpisodes: Set<Pair<Int, Int>>? = if (!f.pageOnly && !f.proven && f.tmdbId != null) world.tmdbEpisodes(f.tmdbId)?.map { it.season to it.episode }?.toSet() ?: emptySet() else null

        val takenThisRun = HashSet<SeasonEpisode>()
        val placedLines = ArrayList<String>()
        var placed = 0
        var attempted = 0
        var cutShort = false
        var skippedForCap = 0
        for (row in candidates) {
            val item = store.item(f.id, row.itemId) ?: continue
            val (listedItem, se0) = listed[row.itemId] ?: continue
            val se = se0 ?: run { lines += RunLine("skipped", "${label(item)}: no season and episode"); null } ?: continue
            if (item.state == "failed") continue
            if (item.state == "refused") continue
            if (tmdbEpisodes != null && (se.season to se.episode) !in tmdbEpisodes) {
                lines += RunLine("waiting", "${Numbering.code(se)}: waiting for the admin's number, the pattern is not proven against TMDB"); continue
            }
            if (se in takenThisRun || se in folderCodes) {
                lines += RunLine("skipped", if (recipe.numbering.kind == "date") "${label(item)}: shares ${Numbering.isoDate(item.published) ?: Numbering.code(se)} with an episode already placed"
                else "${label(item)}: ${Numbering.code(se)} already has a file")
                continue
            }
            if (attempted >= LaneRules.MAX_PER_RUN) { skippedForCap++; continue }
            if (stop()) { cutShort = true; break }
            if (trigger != RunTrigger.MANUAL && world.waitForViewers()) { cutShort = true; lines += RunLine("waiting", "someone started watching; the rest waits for the next run"); break }
            if (lane.state().stopped) { cutShort = true; break }
            attempted++
            when (val r = fetchOne(f, recipe, lane, listedItem, item, se, stop)) {
                is OneResult.Placed -> {
                    placed++; takenThisRun += se; placedLines += r.text; lines += RunLine("placed", r.text)
                }
                is OneResult.Skipped -> lines += RunLine("skipped", "${label(item)}: ${r.reason}")
                is OneResult.Failed -> lines += RunLine("failed", "${label(item)}: ${r.reason}")
                is OneResult.SiteDown -> {
                    cutShort = true; lines += RunLine("stopped", r.reason)
                    if (lane.state().stopped && !stoppedBefore) world.notifySiteStopped(f.site, lane.state().stoppedReason.orEmpty())
                }
            }
            if (cutShort) break
        }
        if (skippedForCap > 0) lines += RunLine("info", "$placed of ${placed + skippedForCap} fetched · the rest on the next runs")

        // ---- retention (FR-16) --------------------------------------------------------------------------------------------
        rows = rowsOf(f.id, listed.keys)
        val evidence = ListingEvidence(fullRead = read.full && !cutShort && !stop(), itemCount = listed.size)
        val plan = RetentionPlanner.deletions(rows, f.retention, evidence, world.nowSec())
        for (r in plan.released) store.release(f.id, r.itemId)
        var deleted = 0
        for (d in plan.deletes) if (deleteOne(f, d, lines)) deleted++
        plan.note?.let { lines += RunLine("retention", it) }

        val summary = buildString {
            if (placed == 0) append("no new episodes") else append("+$placed · ").append(placedLines.joinToString(" · "))
            if (deleted > 0) append(" · −$deleted by retention")
            if (cutShort) append(" · stopped early")
        }
        val status = if (cutShort && placed == 0) "stopped" else "ok"
        return RunResult(status, summary, lines, placed, deleted)
    }

    private fun label(item: FetchItem) = item.title ?: item.link

    private suspend fun blockedResult(f: Fetcher, lane: SiteLane, o: LaneOutcome, stoppedBefore: Boolean, lines: MutableList<RunLine>): RunResult = when (o) {
        is LaneOutcome.Stopped -> {
            if (!stoppedBefore) world.notifySiteStopped(f.site, o.reason)
            lines += RunLine("stopped", o.reason)
            RunResult("stopped", "Site stopped · ${o.reason}", lines)
        }
        is LaneOutcome.BackOff -> { lines += RunLine("waiting", o.reason); RunResult("waiting", "waiting · ${o.reason}", lines) }
        is LaneOutcome.Forbidden -> { lines += RunLine("failed", o.reason); RunResult("failed", "failed · ${o.reason}", lines) }
        is LaneOutcome.Failed -> { lines += RunLine("failed", o.reason); RunResult("failed", "failed · ${o.reason}", lines) }
        is LaneOutcome.Ok -> RunResult("ok", "", lines)
    }

    private fun rowsOf(fetcherId: String, onPage: Set<String>): List<RetentionRow> = store.items(fetcherId).map {
        val path = it.placedPath
        RetentionRow(it.itemId, it.published, it.order, path, path != null && shell.exists(path), it.missingSince, it.itemId in onPage)
    }

    /** SxxEyy of every video already in the series folder, whoever put it there (FR-6.1). */
    private suspend fun existingCodes(seriesPath: String): Set<SeasonEpisode> {
        if (!shell.exists(seriesPath)) return emptySet()
        val out = shell.run("find ${q(seriesPath)} -type f 2>/dev/null").output
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && "/.jellystructure/" !in it && it.substringAfterLast('.').lowercase() in VIDEO_EXT }
            .mapNotNull { p -> val (s, eps) = parseSeasonEpisodes(p); if (s != null) eps.map { SeasonEpisode(s, it) } else null }.flatten().toSet()
    }

    private sealed class OneResult {
        data class Placed(val text: String) : OneResult()
        data class Skipped(val reason: String) : OneResult()
        data class Failed(val reason: String) : OneResult()
        data class SiteDown(val reason: String) : OneResult()
    }

    private suspend fun fetchOne(f: Fetcher, recipe: Recipe, lane: SiteLane, listed: ListedItem, item: FetchItem, se: SeasonEpisode, stop: () -> Boolean): OneResult {
        // The media id on the item's own page: read once, stored (FR-4.1).
        val resolved = ManifestResolver.resolveWithPage(recipe, listed, lane, item.mediaValue) { v, _ -> store.setMediaValue(f.id, item.itemId, v) }
        val manifestUrl = when (resolved) {
            is ManifestResolver.Skipped -> {
                if (lane.state().stopped) return OneResult.SiteDown("Site stopped · ${lane.state().stoppedReason}")
                if (resolved.reason.startsWith("the item's page gives no media id") || resolved.reason.startsWith("the item id fails")) {
                    store.setItemState(f.id, item.itemId, "skipped", resolved.reason, item.failures)
                    return OneResult.Skipped(resolved.reason)
                }
                return OneResult.Failed(resolved.reason)
            }
            is ManifestResolver.Resolved -> resolved.url
        }
        val manifest = lane.page(manifestUrl, Expect.MANIFEST, player = true)
        if (manifest !is LaneOutcome.Ok) return when (manifest) {
            is LaneOutcome.Stopped -> OneResult.SiteDown("Site stopped · ${manifest.reason}")
            is LaneOutcome.BackOff -> OneResult.SiteDown("waiting · ${manifest.reason}")
            is LaneOutcome.Forbidden -> OneResult.Failed(manifest.reason)
            is LaneOutcome.Failed -> OneResult.Failed(manifest.reason)
            is LaneOutcome.Ok -> OneResult.Failed("")
        }
        if (Manifests.hasDrm(manifest.reply.body)) {
            store.setItemState(f.id, item.itemId, "refused", RecipeRefusal.DRM, item.failures)
            return OneResult.Skipped(RecipeRefusal.DRM)
        }

        val isoDate = if (recipe.numbering.kind == "date") Numbering.isoDate(item.published) else null
        val name = (item.title ?: "").let { t -> if (t.isNotBlank()) t else null }
        val fileName = Numbering.fileName(f.name, se, isoDate, name)
        val seasonDir = "${f.seriesPath}/${Numbering.seasonFolder(se.season)}"
        val finalPath = "$seasonDir/$fileName"
        if (shell.exists(finalPath)) return OneResult.Skipped("${Numbering.code(se)} already has a file")
        val work = WorkFiles.pathFor(finalPath, WorkFiles.Kind.FETCH)
        shell.run(WorkFiles.prepareCommand(finalPath)).let { if (!it.ok) return OneResult.Failed("could not make the work folder") }

        return lane.download {
            try {
                val dl = tools.download(DownloadSpec(manifestUrl, manifest.reply.body, recipe.media.kind, work, recipe.language.audio, recipe.language.subtitle), stop)
                if (!dl.ok) return@download failItem(f, item, "the download failed: ${dl.message}")
                val facts = tools.inspect(work) ?: return@download failItem(f, item, "ffprobe could not read the file")
                if (recipe.language.audio.isNotBlank() && facts.audioLanguages.none { it.equals(recipe.language.audio, true) })
                    return@download failItem(f, item, "the audio is not tagged ${recipe.language.audio}")
                val expected = item.duration
                if (expected != null && expected > 0 && facts.durationSec != null && kotlin.math.abs(facts.durationSec - expected) > expected * 0.10)
                    return@download failItem(f, item, "the duration is ${facts.durationSec.toInt()} s, the listing says $expected s")
                if (facts.durationSec == null || facts.durationSec <= 0.0) return@download failItem(f, item, "the file has no duration")
                val first = tools.firstTopLevel(work)
                if (first != "Tracks") return@download failItem(f, item, "Tracks is not before the first Cluster ($first)")
                if (!moveNoOverwrite(work, finalPath)) {
                    shell.remove(work)
                    return@download OneResult.Skipped("something is already at ${fileName}; the download was discarded and nothing was replaced")
                }
                store.setPlaced(f.id, item.itemId, finalPath, world.nowSec())
                if (f.pageOnly) {
                    runCatching { tools.thumbnail(finalPath, facts.durationSec, finalPath.substringBeforeLast('.') + "-thumb.jpg") }
                    runCatching { placePoster(f, lane) }
                }
                world.announce(finalPath, "Created")
                world.history(finalPath, "Auto-fetch placed ${Numbering.code(se)} · $fileName")
                val res = facts.height?.let { "${it}p" }
                val size = facts.sizeBytes?.let { gb(it) }
                OneResult.Placed("${Numbering.code(se)}${if (res != null || size != null) " (${listOfNotNull(res, size).joinToString(", ")})" else ""}")
            } finally {
                shell.remove(work)
                cleanEmpty(seasonDir, f.seriesPath)
            }
        }
    }

    private fun failItem(f: Fetcher, item: FetchItem, why: String): OneResult {
        val n = item.failures + 1
        // After three failures the item is marked failed until the admin re-investigates (FR-6.5).
        store.setItemState(f.id, item.itemId, if (n >= 3) "failed" else "retry", why, n)
        return OneResult.Failed(why + if (n >= 3) " · marked failed" else " · retried next run")
    }

    private fun gb(bytes: Long): String = if (bytes >= 1_000_000_000L) "${(bytes / 100_000_000L) / 10.0} GB" else "${bytes / 1_000_000L} MB"

    private suspend fun placePoster(f: Fetcher, lane: SiteLane) {
        val url = f.seriesImage ?: return
        val dest = "${f.seriesPath}/poster.jpg"
        if (shell.exists(dest)) return
        world.fetchImage(url, dest, lane)
    }

    /** Removes the work folder, the season folder and the series folder, each only if empty. A failed first download leaves nothing. */
    private suspend fun cleanEmpty(seasonDir: String, seriesDir: String) {
        shell.run("rmdir ${q("$seasonDir/${WorkFiles.DIR}")} 2>/dev/null; rmdir ${q(seasonDir)} 2>/dev/null; rmdir ${q(seriesDir)} 2>/dev/null; true")
    }

    /** `link(2)` then `unlink(2)`: atomic, and it fails with EEXIST rather than replacing whatever is at [to] (FR-6.6). */
    @OptIn(ExperimentalForeignApi::class)
    private fun moveNoOverwrite(from: String, to: String): Boolean {
        if (platform.posix.link(from, to) != 0) return false
        platform.posix.unlink(from)
        return true
    }

    // ---- retention's deletion (FR-16.6) ----------------------------------------------------------------------------------

    private suspend fun deleteOne(f: Fetcher, d: PlannedDelete, lines: MutableList<RunLine>): Boolean {
        val path = d.row.placedPath ?: return false
        val item = store.item(f.id, d.row.itemId)
        val se = item?.let { if (it.season != null && it.episode != null) SeasonEpisode(it.season, it.episode) else null }
        val code = se?.let { Numbering.code(it) } ?: path.substringAfterLast('/')
        val tail = "${d.row.published?.let { " ($it)" } ?: ""} · ${if (d.reason == DeleteReason.NEWEST_KEPT) "newest ${f.retention.keep} kept" else "left the page"}"
        if (world.episodePlaying(path)) {
            lines += RunLine("retention", "held · $code$tail · playing now, it waits for the next run")
            return false
        }
        val base = path.substringBeforeLast('.')
        // Exactly the file and the two sidecars jellystructure wrote for it, named in full — never a prefix match.
        for (p in listOf(path, "$base.nfo", "$base-thumb.jpg")) shell.remove(p)
        if (shell.exists(path)) {
            lines += RunLine("failed", "−0 · $code$tail · the file could not be deleted; retried at the next run")
            return false
        }
        val seasonDir = path.substringBeforeLast('/')
        removeSeasonIfEmpty(seasonDir)
        store.setDeleted(f.id, d.row.itemId, if (d.reason == DeleteReason.NEWEST_KEPT) "deleted by retention" else "deleted: left the page")
        world.forgetEpisode(path)
        world.announce(path, "Deleted")
        world.history(path, "Auto-fetch deleted $code$tail")
        lines += RunLine("deleted", "−1 · $code$tail")
        world.activity("info", "Auto-fetch · ${f.name} · −1 · $code$tail")
        return true
    }

    /** FR-16.6 — the season folder goes only when nothing is left in it except jellystructure's own empty work folder. */
    private suspend fun removeSeasonIfEmpty(seasonDir: String) {
        val left = shell.run("find ${q(seasonDir)} -mindepth 1 -maxdepth 1 2>/dev/null").output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (left.all { it.endsWith("/${WorkFiles.DIR}") }) shell.run("rmdir ${q("$seasonDir/${WorkFiles.DIR}")} 2>/dev/null; rmdir ${q(seasonDir)} 2>/dev/null; true")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** What a change to a fetcher's retention would delete at its next run, counted by the server (FR-12). */
    fun previewRetention(f: Fetcher, retention: RetentionSpec): Int {
        val rows = store.items(f.id).map {
            val path = it.placedPath
            RetentionRow(it.itemId, it.published, it.order, path, path != null && shell.exists(path), it.missingSince, true)
        }
        // The preview counts what the newest-N rule alone would remove: *leaves the page* needs a listing read the preview does not make.
        return RetentionPlanner.deletions(rows, retention.copy(leavesPage = false), ListingEvidence(true, rows.size), world.nowSec()).deletes.size
    }

    companion object {
        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")
    }
}
