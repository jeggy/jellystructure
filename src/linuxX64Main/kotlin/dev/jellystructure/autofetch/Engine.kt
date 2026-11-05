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

/** FR-12 — what saving a retention would do at the next run. */
data class RetentionPreview(val deletes: Int, val keeps: Int, val onPage: Int)

data class RunResult(val status: String, val summary: String, val lines: List<RunLine>, val placed: Int = 0, val deleted: Int = 0)

class AutofetchEngine(
    private val store: AutofetchStore,
    private val lanes: SiteLanes,
    private val tools: MediaTools,
    private val world: AutofetchWorld,
    private val shell: FileFixShell = PosixFileFixShell,
) {
    /** *Not on TMDB yet* (FR-9): filed from a run, cleared by the daily check. */
    val queue = NotOnTmdbQueue(store, tools, world, shell)

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
        val activityRun = "autofetch-$runId"
        world.activityStart(activityRun, trigger.id)
        val lines = ArrayList<RunLine>()
        val result = try { runInner(f, trigger, lines, stop) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) {
                store.finishRun(runId, world.nowSec(), "cancelled", "stopped", lines)
                world.activityEnd(activityRun)
                throw e
            }
            lines += RunLine("error", e.message ?: e::class.simpleName.orEmpty())
            RunResult("failed", "failed · ${e.message ?: "unexpected error"}", lines)
        } finally {
            running.remove(id)
        }
        store.finishRun(runId, world.nowSec(), result.status, result.summary, result.lines)
        if (result.status != "deferred") store.setRan(id, world.nowSec())
        // FR-11 — one entry on Activity ▸ History, with the run's lines under it (a deletion is one of them, FR-16.6).
        world.activity(if (result.status == "failed") "warn" else "info", "Auto-fetch · ${f.name} · ${result.summary}", activityRun)
        for (l in result.lines) world.activity(if (l.kind == "failed" || l.kind == "stopped") "warn" else "info", l.text, activityRun)
        world.activityEnd(activityRun)
        return result
    }

    private suspend fun runInner(f: Fetcher, trigger: RunTrigger, lines: MutableList<RunLine>, stop: () -> Boolean): RunResult {
        val recipe = f.recipe ?: return RunResult("failed", "failed · the stored recipe cannot be read", lines)
        if (f.state == "needs_reinvestigation") return RunResult("needs_reinvestigation", "Needs re-investigation · ${f.stateReason}", lines)
        world.sonarrReason(f.tmdbId, f.seriesPath)?.let { reason ->
            store.setState(f.id, "refused", reason)
            return RunResult("refused", reason, lines.also { it += RunLine("refused", reason) })
        }
        val lane = lanes.forSite(f.site, f.ignoreRobots, signedIn = true)
        lane.state().let { if (it.stopped) return RunResult("stopped", "Site stopped · ${it.stoppedReason}", lines) }
        val stoppedBefore = lane.state().stopped

        val read = ListingFetcher.read(recipe, lane, tools)
        if (read is ListingRead.Blocked) return blockedResult(f, lane, read.outcome, stoppedBefore, lines)
        read as ListingRead.Read
        val now = world.nowSec()
        if (read.items.isEmpty()) {
            store.setState(f.id, "needs_reinvestigation", "0 videos found")
            return RunResult("needs_reinvestigation", "Needs re-investigation · 0 videos found", lines.also { it += RunLine("changed", "the listing yields 0 videos") })
        }
        store.setListOk(f.id, now)

        // ---- items a flat yt-dlp entry gives no date are dated from their own answer: once, stored, at most 20 a run (FR-4.1, DEB-3407) -----
        val dateReads = HashMap<String, String?>()
        var undatedLeft = 0
        val items: List<ListedItem> = if (recipe.list.kind != "ytdlp") read.items else {
            var asked = 0
            var halted = false
            val out = read.items.map { it ->
                if (!ItemDates.undated(it)) return@map it
                val itemId = it.id?.takeIf { v -> v.isNotBlank() } ?: it.link ?: return@map it
                if (itemId in dateReads) return@map dateReads[itemId]?.let { d -> it.copy(published = d) } ?: it
                val prior = store.item(f.id, itemId)
                if (prior?.ownDateRead == true) return@map prior.ownDate?.let { d -> it.copy(published = d) } ?: it
                if (halted || asked >= ItemDates.PER_RUN || stop()) { undatedLeft++; return@map it }
                when (val r = ItemDates.read(recipe, it, lane, tools)) {
                    is DateRead.NotAsked -> it
                    is DateRead.Unread -> {
                        // A refusal by the lane is no request; a yt-dlp failure is one, and is asked again on the next run.
                        if (r.outcome !is LaneOutcome.Forbidden) { if (r.outcome is LaneOutcome.Failed) asked++ else halted = true; undatedLeft++ }
                        it
                    }
                    is DateRead.Answered -> { asked++; dateReads[itemId] = r.date; if (r.date != null) it.copy(published = r.date) else it }
                }
            }
            if (lane.state().stopped && !stoppedBefore) world.notifySiteStopped(f.site, lane.state().stoppedReason.orEmpty())
            out
        }

        // ---- the listing, numbered and stored ------------------------------------------------------------------------
        val proofHelper = RecipeProof(tools, world)
        val listed = LinkedHashMap<String, Pair<ListedItem, SeasonEpisode?>>()
        for (it in items) {
            val itemId = it.id?.takeIf { v -> v.isNotBlank() } ?: it.link ?: continue
            if (itemId in listed) continue
            // FR-4.3 — the admin's approved number outranks whatever the recipe works out, and `mapped` works out none.
            val se = store.item(f.id, itemId)?.approved ?: proofHelper.numberOf(recipe, it)
            listed[itemId] = it to se
            store.upsertListed(f.id, itemId, it, se, PageDates.dateOf(recipe, it), now)
            if (itemId in dateReads) store.setOwnDate(f.id, itemId, dateReads[itemId])
        }
        val stored = store.items(f.id)
        // Missing marks: only a full read is evidence (FR-16.5).
        if (read.full) for (s in stored) if (s.placedPath != null && s.itemId !in listed && s.missingSince == null) store.markMissing(f.id, s.itemId, now)

        // ---- a placed file follows its item's date (FR-4.5, DEB-3695) — before the diff, so the numbers it frees are free in it -------------
        val evidenceNow = ListingEvidence(fullRead = read.full && !stop(), itemCount = listed.size)
        val followed = followDates(f, recipe, listed, evidenceNow, now, lines)

        // ---- the diff ---------------------------------------------------------------------------------------------------
        val folderFiles = existingFiles(f.seriesPath)
        val folderCodes = folderFiles.flatMap { it.second }.toSet()
        var rows = rowsOf(f.id, listed.keys)
        // FR-4.5 *A re-upload* — an item whose number is held by one placed episode that has left the page (FR-16.5's test) replaces it.
        val departed = RetentionPlanner.leftThePage(rows, evidenceNow, now).associateBy { it.placedPath }
        val replacing = HashMap<String, RetentionRow>()
        for ((itemId, entry) in listed) {
            val se = entry.second ?: continue
            if (store.item(f.id, itemId)?.placedPath != null) continue
            val held = folderFiles.filter { se in it.second }
            if (held.size == 1) departed[held[0].first]?.takeIf { it.itemId != itemId }?.let { replacing[itemId] = it }
        }
        val candidates = RetentionPlanner.downloadable(rows.filter { r -> replacing.values.none { it.itemId == r.itemId } }, f.retention)
        val tmdbEpisodes: Set<Pair<Int, Int>>? = if (!f.pageOnly && !f.proven && f.tmdbId != null) world.tmdbEpisodes(f.tmdbId)?.map { it.season to it.episode }?.toSet() ?: emptySet() else null

        // FR-9.1 — for a proven fetcher TMDB is asked once, after the first episode is placed, which of them it lacks.
        var onTmdb: Set<Pair<Int, Int>>? = tmdbEpisodes
        var onTmdbAsked = tmdbEpisodes != null
        suspend fun tmdbHas(se: SeasonEpisode): Boolean? {
            if (!onTmdbAsked) { onTmdbAsked = true; onTmdb = f.tmdbId?.let { world.tmdbEpisodes(it) }?.map { it.season to it.episode }?.toSet() }
            return onTmdb?.let { (se.season to se.episode) in it }
        }

        val takenThisRun = HashMap<SeasonEpisode, String>()
        val placedLines = ArrayList<String>()
        var placed = 0
        var attempted = 0
        var cutShort = false
        var skippedForCap = 0
        for (row in candidates) {
            val item = store.item(f.id, row.itemId) ?: continue
            val (listedItem, se0) = listed[row.itemId] ?: continue
            if (item.state == "failed") continue
            if (item.state == "refused") continue
            val se = se0 ?: run {
                // FR-4.1 — under `date` numbering an undated item has no number: one yt-dlp answered with no date is skipped, one not asked yet waits quietly.
                if (recipe.list.kind == "ytdlp" && recipe.numbering.kind == "date" && ItemDates.undated(listedItem)) {
                    if (item.ownDateRead) lines += RunLine("skipped", "${label(item)}: yt-dlp gives it no date")
                    return@run null
                }
                // FR-4.5 (DEB-3567) — under `datetime` a date with no time has no number: skipped, and not one of FR-6.5's three failures.
                if (recipe.numbering.kind == "datetime" && PageDates.read(recipe.numbering, listedItem)?.minute == null) {
                    lines += RunLine("skipped", "${label(item)}: the page gives it no time")
                    return@run null
                }
                // FR-4.3 — a `mapped` item with no approved number is never downloaded on a guess: it waits for the admin.
                if (recipe.numbering.kind == "mapped") { waiting(f, item, "no number approved yet"); lines += RunLine("waiting", "${label(item)}: waiting for a number") }
                else lines += RunLine("skipped", "${label(item)}: no season and episode")
                null
            } ?: continue
            if (tmdbEpisodes != null && item.approved == null && (se.season to se.episode) !in tmdbEpisodes) {
                waiting(f, item, "${Numbering.code(se)} is not on TMDB and the pattern is not proven")
                lines += RunLine("waiting", "${Numbering.code(se)}: waiting for the admin's number, the pattern is not proven against TMDB"); continue
            }
            if (item.state == "waiting") store.setItemState(f.id, item.itemId, "new", "", item.failures)   // it has a number now (approved, or the pattern is proven)
            val replaced = replacing[row.itemId]?.takeIf { se !in takenThisRun.keys }
            if (replaced == null && (se in takenThisRun.keys || se in folderCodes)) {
                lines += RunLine("skipped", when (recipe.numbering.kind) {
                    "date" -> "${label(item)}: shares ${Numbering.isoDate(item.published) ?: Numbering.code(se)} with an episode already placed"
                    // FR-4.5 (DEB-3567) — the slot is taken by an episode a few minutes away: name where that one aired.
                    "datetime" -> "${label(item)}: airs too close to ${slotOwner(f, se, takenThisRun[se])}, already placed"
                    else -> "${label(item)}: ${Numbering.code(se)} already has a file"
                })
                continue
            }
            if (attempted >= LaneRules.MAX_PER_RUN) { skippedForCap++; continue }
            if (stop()) { cutShort = true; break }
            if (trigger != RunTrigger.MANUAL && world.waitForViewers()) { cutShort = true; lines += RunLine("waiting", "someone started watching; the rest waits for the next run"); break }
            if (lane.state().stopped) { cutShort = true; break }
            if (replaced != null && world.episodePlaying(replaced.placedPath.orEmpty())) {
                lines += RunLine("info", "${Numbering.codeFor(recipe.numbering.kind, se)} · the episode it replaces is playing now, it waits for the next run"); continue
            }
            attempted++
            when (val r = fetchOne(f, recipe, lane, listedItem, item, se, stop, replaced)) {
                is OneResult.Placed -> {
                    placed++; takenThisRun[se] = airedAt(recipe, listedItem)
                    // TMDB unreachable is not evidence the episode is there: it is filed, and the daily check clears it if TMDB has it.
                    val queued = replaced == null && !f.pageOnly && f.proven && tmdbHas(se) != true && queue.file(f, item, r.path, r.durationSec)
                    val text = r.text + if (queued) " · not on TMDB → queued" else ""
                    placedLines += text; lines += RunLine("placed", text)
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
        // FR-6.8 (DEB-3695) — what the run left behind: every item skipped with a line, and every item that failed.
        val skippedN = lines.count { it.kind == "skipped" }
        val failedN = lines.count { it.kind == "failed" }

        // ---- retention (FR-16) --------------------------------------------------------------------------------------------
        rows = rowsOf(f.id, listed.keys)
        val evidence = ListingEvidence(fullRead = read.full && !cutShort && !stop(), itemCount = listed.size)
        val plan = RetentionPlanner.deletions(rows, f.retention, evidence, world.nowSec())
        for (r in plan.released) store.release(f.id, r.itemId)
        var deleted = 0
        for (d in plan.deletes) if (deleteOne(f, d, lines)) deleted++
        plan.note?.let { lines += RunLine("retention", it) }

        val summary = buildString {
            val allPlaced = followed.replacedLines + placedLines
            val total = placed + followed.replacedLines.size
            if (total == 0) append("no new episodes") else append("+$total · ").append(allPlaced.joinToString(" · "))
            if (skippedN > 0) append(" · $skippedN skipped")
            if (failedN > 0) append(" · $failedN failed")
            if (followed.refiled > 0) append(" · ${followed.refiled} re-filed")
            if (deleted > 0) append(" · −$deleted by retention")
            if (cutShort) append(" · stopped early")
            if (undatedLeft > 0) append(" · $undatedLeft items not dated yet, on the next runs")
        }
        val status = if (cutShort && placed + followed.replacedLines.size == 0) "stopped" else "ok"
        return RunResult(status, summary, lines, placed + followed.replacedLines.size, deleted)
    }

    private fun label(item: FetchItem) = item.title ?: item.link

    /** `2026-11-03 18.30` — when a `datetime` item aired, as its file name carries it. */
    private fun airedAt(recipe: Recipe, listed: ListedItem): String =
        PageDates.read(recipe.numbering, listed)?.let { p -> listOfNotNull(p.date, p.minute?.let { Numbering.clock(it) }).joinToString(" ") }.orEmpty()

    /** When the episode holding [se] aired: this run's own placement, else the date and time in the placed file's name. */
    private fun slotOwner(f: Fetcher, se: SeasonEpisode, thisRun: String?): String =
        thisRun ?: store.items(f.id).firstOrNull { it.season == se.season && it.episode == se.episode && it.placedPath != null }?.placedPath
            ?.let { Regex("""(\d{4}-\d{2}-\d{2} \d{2}\.\d{2})""").find(it.substringAfterLast('/'))?.groupValues?.get(1) }
        ?: Numbering.codeFor("datetime", se)

    /** The item is waiting for the admin's number (FR-4.3): the fetcher's row counts it, and the next run looks at it again. */
    private fun waiting(f: Fetcher, item: FetchItem, why: String) {
        if (item.state != "waiting" || item.note != why) store.setItemState(f.id, item.itemId, "waiting", why, item.failures)
    }

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
    internal suspend fun existingCodes(seriesPath: String): Set<SeasonEpisode> = existingFiles(seriesPath).flatMap { it.second }.toSet()

    /** Every video in the series folder with the codes its name carries, whoever put it there (FR-6.1, FR-4.5). */
    internal suspend fun existingFiles(seriesPath: String): List<Pair<String, List<SeasonEpisode>>> {
        if (!shell.exists(seriesPath)) return emptyList()
        val out = shell.run("find ${q(seriesPath)} -type f 2>/dev/null").output
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && "/.jellystructure/" !in it && it.substringAfterLast('.').lowercase() in VIDEO_EXT }
            .mapNotNull { p -> val (s, eps) = parseSeasonEpisodes(p); if (s != null) p to eps.map { SeasonEpisode(s, it) } else null }
    }

    /** What FR-4.5's *A date that moves* did in a run: files renamed to their item's new number, and episodes a moved file replaced. */
    private class Followed(var refiled: Int = 0, val replacedLines: MutableList<String> = ArrayList())

    /**
     * DEB-3695 (FR-4.5, *A date that moves*) — a page may re-date an item after it was placed, and the row follows the page. The file follows
     * the row, never the other way: when its number is free the file is renamed to it, when a placed episode of this fetcher that has left
     * the page (FR-16.5) holds it the moved file replaces that episode, and otherwise the file keeps its name and the row keeps its number.
     * A file is never renamed while it plays. The engine never moves a file onto a date its item does not carry.
     */
    private suspend fun followDates(
        f: Fetcher, recipe: Recipe, listed: Map<String, Pair<ListedItem, SeasonEpisode?>>, evidence: ListingEvidence, now: Long, lines: MutableList<RunLine>,
    ): Followed {
        val out = Followed()
        val kind = recipe.numbering.kind
        if (kind != "date" && kind != "datetime") return out
        val moved = store.items(f.id).filter { it.placedPath != null && it.season != null && it.episode != null }.mapNotNull { row ->
            val path = row.placedPath ?: return@mapNotNull null
            val want = SeasonEpisode(row.season ?: return@mapNotNull null, row.episode ?: return@mapNotNull null)
            val (s, eps) = parseSeasonEpisodes(path)
            val has = if (s != null && eps.size == 1) SeasonEpisode(s, eps[0]) else return@mapNotNull null
            if (has == want || !shell.exists(path)) null else Triple(row, has, want)
        }
        if (moved.isEmpty()) return out
        val files = existingFiles(f.seriesPath).toMutableList()
        val departed = RetentionPlanner.leftThePage(rowsOf(f.id, listed.keys), evidence, now).associateBy { it.placedPath }
        for ((row, has, want) in moved) {
            val path = row.placedPath ?: continue
            val isoDate = Numbering.isoDate(row.published) ?: continue
            // Under `datetime` the time is on the item as the page printed it, so only an item on the page can be named.
            val time = if (kind == "datetime") listed[row.itemId]?.first?.let { PageDates.read(recipe.numbering, it)?.minute }?.let { Numbering.clock(it) } ?: continue else null
            val on = if (time != null) "$isoDate $time" else isoDate
            // Only a date that moved: a recipe that numbers the item another way (date to datetime) leaves the placed file's name alone (FR-4.5).
            val named = FILE_DATE_RE.find(path.substringAfterLast('/')) ?: continue
            if (named.groupValues[1] == isoDate && (named.groupValues[2].isEmpty() || named.groupValues[2] == time)) continue
            val from = Numbering.codeFor(kind, has)
            val to = Numbering.codeFor(kind, want)
            if (world.episodePlaying(path)) {
                lines += RunLine("info", "held · $from → $to · playing now, it waits for the next run"); continue
            }
            val holders = files.filter { it.first != path && want in it.second }
            val holder = holders.singleOrNull()?.let { departed[it.first] }?.takeIf { it.itemId != row.itemId }
            if (holders.isNotEmpty() && holder == null) {
                // Held by a hand-placed file, another fetcher's, a placed episode still on the page: the file keeps its name.
                store.setNumber(f.id, row.itemId, has)
                lines += RunLine("skipped", "${row.title?.takeIf { it.isNotBlank() } ?: row.link}: the page now dates it $on, and $to already has a file; kept as $from")
                continue
            }
            val base = path.substringBeforeLast('.')
            if (holder != null) {
                val target = holder.placedPath ?: continue
                if (world.episodePlaying(target)) { lines += RunLine("info", "held · $to · the episode it replaces is playing now, it waits for the next run"); continue }
                if (!swapOnto(path, target)) { lines += RunLine("failed", "$from → $to · the file could not be moved onto the episode that left the page"); continue }
                for (p in listOf("$base.nfo", "$base-thumb.jpg")) shell.remove(p)
                store.setReplaced(f.id, holder.itemId, "replaced by ${row.title ?: row.link}")
                store.setPath(f.id, row.itemId, target)
                files.removeAll { it.first == path }
                removeSeasonIfEmpty(path.substringBeforeLast('/'))
                world.history(path, "Auto-fetch moved $from onto the episode that left the page · ${target.substringAfterLast('/')}")
                world.forgetEpisode(path)
                world.announce(path, "Deleted")
                world.announce(target, "Modified")
                refreshThumbnail(f, target)
                val facts = runCatching { tools.inspect(target) }.getOrNull()
                val detail = listOfNotNull(facts?.height?.let { "${it}p" }, facts?.sizeBytes?.let { gb(it) }).joinToString(", ")
                out.replacedLines += "$to${if (detail.isNotEmpty()) " ($detail)" else ""} · replaces the episode that left the page"
                lines += RunLine("changed", "$from → $to · the page now dates it $on")
                lines += RunLine("placed", out.replacedLines.last())
                continue
            }
            val target = "${f.seriesPath}/${Numbering.seasonFolder(want.season)}/${Numbering.fileName(f.name, want, isoDate, row.title?.takeIf { it.isNotBlank() }, time)}"
            shell.run("mkdir -p ${q(target.substringBeforeLast('/'))}")
            if (!moveNoOverwrite(path, target)) {
                store.setNumber(f.id, row.itemId, has)
                lines += RunLine("skipped", "${row.title?.takeIf { it.isNotBlank() } ?: row.link}: the page now dates it $on, and $to already has a file; kept as $from")
                continue
            }
            val newBase = target.substringBeforeLast('.')
            for (suffix in listOf(".nfo", "-thumb.jpg")) if (shell.exists("$base$suffix") && !shell.exists("$newBase$suffix")) moveNoOverwrite("$base$suffix", "$newBase$suffix")
            store.setPath(f.id, row.itemId, target)
            files.removeAll { it.first == path }; files += target to listOf(want)
            if (path.substringBeforeLast('/') != target.substringBeforeLast('/')) removeSeasonIfEmpty(path.substringBeforeLast('/'))
            world.history(path, "Auto-fetch re-filed $from → $to · the page now dates it $on")
            world.forgetEpisode(path)
            world.announce(path, "Deleted")
            world.announce(target, "Created")
            out.refiled++
            lines += RunLine("changed", "$from → $to · the page now dates it $on")
        }
        return out
    }

    /** A page-only episode's thumbnail is a frame of the file, so the file that took the place of another gets its own (FR-15.7). */
    private suspend fun refreshThumbnail(f: Fetcher, path: String) {
        if (!f.pageOnly) return
        val facts = runCatching { tools.inspect(path) }.getOrNull() ?: return
        val dur = facts.durationSec ?: return
        runCatching { tools.thumbnail(path, dur, path.substringBeforeLast('.') + "-thumb.jpg") }
    }

    /** `rename(2)` over the file at [to] — the one place a fetcher replaces anything, and only an episode it placed that left the page (FR-6.6). */
    @OptIn(ExperimentalForeignApi::class)
    private fun swapOnto(from: String, to: String): Boolean = platform.posix.rename(from, to) == 0

    private sealed class OneResult {
        data class Placed(val text: String, val path: String, val durationSec: Double) : OneResult()
        data class Skipped(val reason: String) : OneResult()
        data class Failed(val reason: String) : OneResult()
        data class SiteDown(val reason: String) : OneResult()
    }

    /** What a download is handed: a manifest read through the lane (FR-6.3), or the item's own link for yt-dlp (FR-6.3, 2026-11-02). */
    private sealed class Source {
        class Manifest(val url: String, val reply: HttpReply) : Source()
        class YtDlp(val link: String) : Source()
    }

    private suspend fun fetchOne(
        f: Fetcher, recipe: Recipe, lane: SiteLane, listed: ListedItem, item: FetchItem, se: SeasonEpisode, stop: () -> Boolean,
        /** FR-4.5 *A re-upload* — the placed episode that left the page; the new copy takes its path once FR-6.5's checks pass. */
        replacing: RetentionRow? = null,
    ): OneResult {
        val source: Source = if (recipe.media.kind == "ytdlp") {
            // yt-dlp opens the item's own link, so it is held to what a page request in the lane is: a recorded host, and robots.txt (DEB-2480).
            val link = listed.link
            if (link == null || Urls.host(link) !in RecipeValidator.lanes(recipe)) {
                val why = "the item's link is not an http address on a host the recipe records"
                store.setItemState(f.id, item.itemId, "skipped", why, item.failures)
                return OneResult.Skipped(why)
            }
            lane.admit(link)?.let { return refusal(it) }
            Source.YtDlp(link)
        } else {
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
            val manifest = lane.page(manifestUrl, Expect.MANIFEST, player = true, hosts = RecipeValidator.lanes(recipe))
            if (manifest !is LaneOutcome.Ok) return refusal(manifest)
            if (Manifests.hasDrm(manifest.reply.body)) {
                store.setItemState(f.id, item.itemId, "refused", RecipeRefusal.DRM, item.failures)
                return OneResult.Skipped(RecipeRefusal.DRM)
            }
            Source.Manifest(manifestUrl, manifest.reply)
        }

        val isoDate = if (recipe.numbering.kind == "date" || recipe.numbering.kind == "datetime") Numbering.isoDate(item.published) else null
        // The stored `published` is the date alone; the time is on the item as the page printed it (FR-4.5, DEB-3567).
        val time = if (recipe.numbering.kind == "datetime") PageDates.read(recipe.numbering, listed)?.minute?.let { Numbering.clock(it) } else null
        val name = (item.title ?: "").let { t -> if (t.isNotBlank()) t else null }
        val fileName = Numbering.fileName(f.name, se, isoDate, name, time)
        val oldPath = replacing?.placedPath
        val finalPath = oldPath ?: "${f.seriesPath}/${Numbering.seasonFolder(se.season)}/$fileName"
        val seasonDir = finalPath.substringBeforeLast('/')
        if (oldPath == null && shell.exists(finalPath)) return OneResult.Skipped("${Numbering.code(se)} already has a file")
        if (oldPath != null && !shell.exists(finalPath)) return OneResult.Skipped("the episode it replaces is no longer there")
        val work = WorkFiles.pathFor(finalPath, WorkFiles.Kind.FETCH)
        shell.run(WorkFiles.prepareCommand(finalPath)).let { if (!it.ok) return OneResult.Failed("could not make the work folder") }

        val recipeHosts = RecipeValidator.lanes(recipe)
        // DEB-3613 (FR-3.6) — the session goes to yt-dlp as a file that exists for this call only, and to ffmpeg as cookies scoped to their own domains.
        val cookies = if (source is Source.YtDlp) lane.cookiesFile(recipeHosts) else null
        return lane.download {
            try {
                val dl = when (source) {
                    is Source.Manifest -> tools.download(DownloadSpec(source.reply.finalUrl ?: source.url, source.reply.body, recipe.media.kind, work, recipe.language.audio, recipe.language.subtitle, lane.ffmpegCookies(recipeHosts)), stop)
                    is Source.YtDlp -> tools.ytdlpDownload(YtDlpDownload(source.link, work, recipe.language.audio, recipe.language.subtitle, FetchIdentity.userAgent(), cookies), stop)
                }
                if (source is Source.YtDlp) {
                    // What yt-dlp's own last error says is an answer from the site: a 429 slows the lane, a 403 on a site that worked stops it (FR-2.4, FR-2.5).
                    if (dl.ok) lane.programSucceeded()
                    else {
                        if (dl.drm) {
                            store.setItemState(f.id, item.itemId, "refused", RecipeRefusal.DRM, item.failures)
                            return@download OneResult.Skipped(RecipeRefusal.DRM)
                        }
                        val o = lane.programFailure(dl.message, dl.status, dl.needsLogin, cookies != null)
                        if (o is LaneOutcome.Stopped || o is LaneOutcome.BackOff) return@download refusal(o)
                    }
                }
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
                if (replacing != null) {
                    if (!swapOnto(work, finalPath)) {
                        shell.remove(work)
                        return@download OneResult.Failed("the file could not be moved onto the episode that left the page; nothing was replaced")
                    }
                    store.setReplaced(f.id, replacing.itemId, "replaced by ${item.title ?: item.link}")
                } else if (!moveNoOverwrite(work, finalPath)) {
                    shell.remove(work)
                    return@download OneResult.Skipped("something is already at ${fileName}; the download was discarded and nothing was replaced")
                }
                store.setPlaced(f.id, item.itemId, finalPath, world.nowSec())
                if (f.pageOnly) {
                    runCatching { tools.thumbnail(finalPath, facts.durationSec, finalPath.substringBeforeLast('.') + "-thumb.jpg") }
                    runCatching { placePoster(f, lane) }
                }
                world.announce(finalPath, if (replacing != null) "Modified" else "Created")
                world.history(finalPath, if (replacing != null) "Auto-fetch replaced ${Numbering.code(se)} · ${finalPath.substringAfterLast('/')}" else "Auto-fetch placed ${Numbering.code(se)} · $fileName")
                val res = facts.height?.let { "${it}p" }
                val size = facts.sizeBytes?.let { gb(it) }
                OneResult.Placed(path = finalPath, durationSec = facts.durationSec, text = "${Numbering.code(se)}${if (res != null || size != null) " (${listOfNotNull(res, size).joinToString(", ")})" else ""}${if (dl.message.isNotBlank()) " · ${dl.message}" else ""}${if (replacing != null) " · replaces the episode that left the page" else ""}")
            } finally {
                shell.remove(work)
                cleanEmpty(seasonDir, f.seriesPath)
            }
        }
    }

    private fun refusal(o: LaneOutcome): OneResult = when (o) {
        is LaneOutcome.Stopped -> OneResult.SiteDown("Site stopped · ${o.reason}")
        is LaneOutcome.BackOff -> OneResult.SiteDown("waiting · ${o.reason}")
        is LaneOutcome.Forbidden -> OneResult.Failed(o.reason)
        is LaneOutcome.Failed -> OneResult.Failed(o.reason)
        is LaneOutcome.Ok -> OneResult.Failed("")
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
        queue.forget(f.id, d.row.itemId)
        return true
    }

    /** FR-16.6 — the season folder goes only when nothing is left in it except jellystructure's own empty work folder. */
    private suspend fun removeSeasonIfEmpty(seasonDir: String) {
        val left = shell.run("find ${q(seasonDir)} -mindepth 1 -maxdepth 1 2>/dev/null").output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (left.all { it.endsWith("/${WorkFiles.DIR}") }) shell.run("rmdir ${q("$seasonDir/${WorkFiles.DIR}")} 2>/dev/null; rmdir ${q(seasonDir)} 2>/dev/null; true")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * What a change to a fetcher's retention would do at its next run, counted by the server (FR-12): how many episodes it would delete, how
     * many of the listing's items it keeps, and how many the page lists. The screen words these and never counts for itself (FR-16.1).
     */
    fun previewRetention(f: Fetcher, retention: RetentionSpec): RetentionPreview {
        val items = store.items(f.id)
        val rows = items.map {
            val path = it.placedPath
            RetentionRow(it.itemId, it.published, it.order, path, path != null && shell.exists(path), it.missingSince, true)
        }
        // The preview counts what the newest-N rule alone would remove: *leaves the page* needs a listing read the preview does not make.
        val deletes = RetentionPlanner.deletions(rows, retention.copy(leavesPage = false), ListingEvidence(true, rows.size), world.nowSec()).deletes.size
        val lastRead = items.maxOfOrNull { it.lastSeenAt ?: 0L } ?: 0L
        val onPage = items.count { (it.lastSeenAt ?: 0L) == lastRead && it.state != "deleted" && it.state != "replaced" }
        return RetentionPreview(deletes, if (retention.keepsAll) onPage else minOf(retention.keep, onPage), onPage)
    }

    companion object {
        /** The date, and under `datetime` the time, straight after the code in a file name this engine wrote (`Series - S2026E300 - 2026-10-27 - Name.mkv`). */
        private val FILE_DATE_RE = Regex("""S\d+E\d+ - (\d{4}-\d{2}-\d{2})(?: (\d{2}\.\d{2}))?""")
        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")
    }
}
