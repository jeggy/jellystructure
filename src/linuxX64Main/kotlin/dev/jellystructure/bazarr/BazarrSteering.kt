package dev.jellystructure.bazarr

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.BazarrConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Subtitle_action
import dev.jellystructure.db.Subtitle_check
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.subtitles.CantTell
import dev.jellystructure.subtitles.RefKind
import dev.jellystructure.subtitles.SubtitleCheckService
import dev.jellystructure.subtitles.SubtitleSteering
import dev.jellystructure.subtitles.SubtitleVerdicts
import dev.jellystructure.subtitles.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.math.abs

/**
 * Phase 273 (§C) — jellystructure judges, Bazarr does. After a video's sidecars are checked this decides, per
 * verdict, what to ask Bazarr for: nothing (in sync: keep a copy, FR-273-15), a sync told exactly how (FR-273-12),
 * an upload of a mislabelled file to the episode it belongs to (FR-273-11), a blacklist so Bazarr searches again
 * (FR-273-13), a neighbour's candidate downloaded onto this episode (FR-273-14), or the kept copy put back after a
 * blind upgrade (FR-273-15). Every change to a subtitle on disk is a Bazarr API call. Rather nothing than wrong
 * (FR-273-23): a wrong subtitle is removed even when nothing replaces it, and never kept or put back.
 *
 * Work runs one video at a time on its own queue, never inside a check. [act] is idempotent: an action is taken
 * at most once per file content, a sync at most once a day per file, three blacklist rounds a day per language, all
 * inside the daily download budget (FR-273-16). On *Only report* nothing is done; on *Ask me first* every action
 * waits in *Needs your OK*; on *Fix it* only a speech-only doubt waits.
 */
class BazarrSteering(
    private val db: JellystructureDb,
    private val store: MediaStore,
    private val configStore: ConfigStore,
    private val client: BazarrOps,
    val service: BazarrService,
    private val jellyfinClient: JellyfinClient,
    private val mediaHistory: MediaHistory,
    private val scope: CoroutineScope,
) : SubtitleSteering {
    lateinit var checks: SubtitleCheckService
    /** Queues a `check_subtitles` job for a video whose reference must be fetched or decoded first. */
    var enqueueCheck: (MediaItem, String) -> Unit = { _, _ -> }

    private val q get() = db.subtitleCheckQueries
    private val work = Channel<Pair<String, String>>(Channel.UNLIMITED)

    companion object {
        const val SYNC = "sync"
        const val MOVE = "move"
        const val BLACKLIST = "blacklist"
        const val REMOVE = "remove"
        const val NEIGHBOUR = "neighbour"
        const val RESTORE = "restore"
        const val WAITING = "waiting"
        const val DONE = "done"
        const val FAILED = "failed"
        const val DISMISSED = "dismissed"
        const val APPROVED = "approved"
        const val ROUNDS_PER_DAY = 3
        val MAX_OFFSETS = listOf(60, 120, 300, 600)
        var current: BazarrSteering? = null

        internal fun needsAction(r: Subtitle_check): Boolean = when (r.verdict) {
            Verdict.IN_SYNC.wire -> false
            Verdict.CANT_TELL.wire -> r.reason == CantTell.WEAK && r.reference?.startsWith(RefKind.SPEECH.wire) == true
            else -> true
        }

        /** Dev review item 8 — what *Fix it* would do with the verdicts as they stand, first action per sidecar, so
         *  the admin can read the report run before switching. A verdict from the speech track only ever waits. */
        fun preview(rows: List<Subtitle_check>): FixPreview {
            var sync = 0; var replace = 0; var move = 0; var ask = 0
            for (r in rows) {
                if (!needsAction(r)) continue
                if (r.reference?.startsWith(RefKind.SPEECH.wire) == true) { ask++; continue }
                when (r.verdict) {
                    Verdict.OFF.wire -> if (abs(r.shift_ms ?: 0) / 1000 < MAX_OFFSETS.last()) sync++ else replace++
                    Verdict.OTHER_EPISODE.wire -> move++
                    else -> replace++
                }
            }
            return FixPreview(sync, replace, move, ask, rows.count { SubtitleVerdicts.isHidden(it) })
        }
    }

    data class FixPreview(val sync: Int, val replace: Int, val move: Int, val ask: Int, val hide: Int)

    fun start() {
        current = this
        scope.launch {
            for ((itemId, videoPath) in work) {
                runCatching { act(itemId, videoPath) }
                    .onFailure { Logger.warn("subtitle steering failed for ${videoPath.substringAfterLast('/')}: ${it.message}", "subtitles") }
            }
        }
    }

    private fun mode() = configStore.current.subtitleCheck
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun now(): Long = platform.posix.time(null)
    private fun midnight(): Long = now() - (now() % 86_400L)

    // ── After a check ───────────────────────────────────────────────────────────────────────────

    override suspend fun afterCheck(item: MediaItem, videoPath: String, rows: List<Subtitle_check>, changed: List<Subtitle_check>) {
        settle(videoPath, rows)
        if (mode().reportOnly || service.config() == null) return
        if (rows.any { needsAction(it) } || q.settledForVideo(videoPath).executeAsList().isNotEmpty()) work.trySend(item.id to videoPath)
    }

    /** FR-273-15 — keep a copy of every sidecar judged in sync, so a blind upgrade can be undone. */
    private fun settle(videoPath: String, rows: List<Subtitle_check>) {
        for (r in rows) {
            if (r.verdict != Verdict.IN_SYNC.wire) continue
            val have = q.settledByPath(r.sidecar_path).executeAsOneOrNull()
            if (have != null && have.content_hash == r.content_hash) continue
            val bytes = runCatching { FileIo.readBytes(Path(r.sidecar_path)) }.getOrNull() ?: continue
            q.putSettled(r.sidecar_path, videoPath, r.language, r.forced, r.hi, r.sidecar_path.substringAfterLast('/'), bytes, r.content_hash, now())
        }
    }

    // ── Acting ──────────────────────────────────────────────────────────────────────────────────

    private sealed interface Target {
        data class Ep(val seriesId: Int, val episode: BazarrEpisode, val unit: Episode) : Target
        data class Film(val movie: BazarrMovie) : Target
    }

    private suspend fun targetFor(item: MediaItem, videoPath: String): Target? = when (item.kind) {
        MediaKind.MOVIE -> service.resolveMovie(item)?.let { Target.Film(it) }
        MediaKind.TV_SHOW -> {
            val ep = item.episodes.firstOrNull { it.path == videoPath }
            val series = service.resolveSeries(item)
            if (ep == null || series == null) null
            else service.resolveEpisode(series.sonarrSeriesId, ep)?.let { Target.Ep(series.sonarrSeriesId, it, ep) }
        }
        else -> null
    }

    /** Bazarr's own path for a sidecar: its subtitle list's entry with this file name, else its video's folder. */
    private fun bazarrPath(t: Target, sidecarPath: String): String {
        val name = sidecarPath.substringAfterLast('/')
        val (subs, video) = when (t) {
            is Target.Ep -> t.episode.subtitles to t.episode.path
            is Target.Film -> t.movie.subtitles to t.movie.path
        }
        return subs.firstOrNull { it.path?.substringAfterLast('/') == name }?.path ?: (video.substringBeforeLast('/') + "/" + name)
    }

    suspend fun act(itemId: String, videoPath: String) {
        val cfg = service.config() ?: return
        if (mode().reportOnly) return
        val item = store.resolve(itemId) ?: return
        val target = targetFor(item, videoPath) ?: return
        // Put back what was in sync first: when an upgrade overwrote the same file name, a blacklist would delete it.
        val restored = restore(cfg, item, target, videoPath)
        var touched = restored.isNotEmpty()
        for (r in q.checksForVideo(videoPath).executeAsList()) {
            if (r.language in restored) continue
            val bySpeech = r.reference?.startsWith(RefKind.SPEECH.wire) == true
            val alone = mode().actsAlone && !bySpeech
            when (r.verdict) {
                Verdict.OFF.wire -> {
                    // A sync is judged by a check made after it: until then, wait; still off after it, replace.
                    val syncedAt = q.lastActionAt(r.sidecar_path, SYNC).executeAsOne().at
                    val syncedToday = q.roundsSince(SYNC, videoPath, r.language, now() - 86_400).executeAsOne() > 0
                    touched = when {
                        syncedAt != null && r.checked_at <= syncedAt -> touched
                        !syncedToday && abs(r.shift_ms ?: 0) / 1000 < MAX_OFFSETS.last() -> step(item, r, SYNC, alone) { sync(cfg, item, target, r) } || touched
                        else -> replace(cfg, item, target, r, alone) || touched
                    }
                }
                Verdict.OFF_MID_FILE.wire, Verdict.NOT_THIS_VIDEO.wire, Verdict.LONGER_VIDEO.wire ->
                    touched = replace(cfg, item, target, r, alone) || touched
                Verdict.OTHER_EPISODE.wire -> {
                    touched = step(item, r, MOVE, alone) { move(cfg, item, target, r) } || touched
                    touched = replace(cfg, item, target, r, alone) || touched
                }
                Verdict.CANT_TELL.wire -> if (needsAction(r)) touched = step(item, r, REMOVE, alone = false) { remove(cfg, item, target, r) } || touched
            }
        }
        if (touched) afterChange(item, videoPath, target)
    }

    /** One action on one file's content: done now when [alone], else proposed once (FR-273-16). Never twice. */
    private suspend fun step(item: MediaItem, r: Subtitle_check, action: String, alone: Boolean, run: suspend () -> Boolean): Boolean {
        if (q.actedOnContent(r.sidecar_path, action, "%hash=${r.content_hash}%").executeAsOne() > 0) return false
        if (!alone) {
            record(item, r, action, WAITING, costs = false, detail = proposalText(r, action))
            return false
        }
        return run()
    }

    private fun proposalText(r: Subtitle_check, action: String): String = when (action) {
        SYNC -> "Sync it (Bazarr), ${checks.verdictWords(r)}"
        MOVE -> "Give it to ${r.match_label ?: "the episode it belongs to"}"
        REMOVE -> "Remove it: it does not follow the speech"
        RESTORE -> "Put back the copy that was in sync"
        else -> "Replace it: ${checks.verdictWords(r)}"
    }

    private fun record(item: MediaItem, r: Subtitle_check?, action: String, state: String, costs: Boolean, detail: String,
                       videoPath: String? = null, provider: String? = null, subsId: String? = null, language: String? = null) {
        val hash = r?.content_hash?.let { "hash=$it;" } ?: ""
        q.insertAction(action, state, item.id, videoPath ?: r?.video_path ?: "", r?.sidecar_path, language ?: r?.language, provider ?: r?.provider,
            subsId ?: r?.subs_id, if (costs) 1 else 0, hash + detail, now(), if (state == WAITING) null else now())
        if (state != WAITING) mediaHistory.record(item.id, "subtitle_$action", "${r?.sidecar_path?.substringAfterLast('/') ?: ""} $state · $detail")
    }

    private fun budgetLeft(): Boolean = q.downloadsSince(midnight()).executeAsOne() < mode().dailyDownloadBudget

    /** FR-273-12 — Bazarr's sync, told which reference, how far and whether to fix the speed; then re-checked. */
    private suspend fun sync(cfg: BazarrConfig, item: MediaItem, t: Target, r: Subtitle_check): Boolean {
        if (dev.jellystructure.tv.isPlaybackActive()) { later(item.id, r.video_path); return false }
        val reference = syncReference(t, r)
        val maxOffset = MAX_OFFSETS.firstOrNull { it > abs(r.shift_ms ?: 0) / 1000 + 10 } ?: MAX_OFFSETS.last()
        val (type, id) = when (t) { is Target.Ep -> "episode" to t.episode.sonarrEpisodeId; is Target.Film -> "movie" to t.movie.radarrId }
        val ok = client.syncSubtitle(cfg.url, cfg.apiKey, type, id, r.language ?: "", bazarrPath(t, r.sidecar_path),
            reference = reference, maxOffsetSeconds = maxOffset, noFixFramerate = false, gss = (r.drift_ms_per_hour ?: 0) != 0L,
            forced = r.forced == 1L, hi = r.hi == 1L)
        record(item, r, SYNC, if (ok) DONE else FAILED, costs = false, detail = "reference=${reference ?: "Bazarr's default"};max=$maxOffset")
        return ok
    }

    /** `s:N` for the embedded stream when Bazarr can take it (three characters), else a sibling in sync, else audio. */
    private fun syncReference(t: Target, r: Subtitle_check): String? {
        val ref = r.reference ?: return null
        if (ref.startsWith("${RefKind.EMBEDDED.wire}:")) {
            val src = ref.removePrefix("${RefKind.EMBEDDED.wire}:")
            if (src.length == 3 && src.startsWith("s:")) return src
        }
        val sibling = q.checksForVideo(r.video_path).executeAsList().firstOrNull {
            it.sidecar_path != r.sidecar_path && it.verdict == Verdict.IN_SYNC.wire && FileIntegrityService.stampOf(it.sidecar_path) != null
        }
        if (sibling != null) return bazarrPath(t, sibling.sidecar_path)
        return "a:0"
    }

    /** FR-273-11 — a subtitle that belongs to another episode is given to it, when that episode needs one in this language. */
    private suspend fun move(cfg: BazarrConfig, item: MediaItem, t: Target, r: Subtitle_check): Boolean {
        val dest = r.match_video_path ?: return false
        if (t !is Target.Ep) return false
        val destEp = item.episodes.firstOrNull { it.path == dest } ?: return false
        val destBazarr = service.resolveEpisode(t.seriesId, destEp) ?: return false
        val destRows = q.checksForVideo(dest).executeAsList()
        val hasGood = checks.sidecarsOf(dest).any { s -> s.language == r.language && destRows.any { it.sidecar_path == s.path && it.verdict == Verdict.IN_SYNC.wire } }
        if (hasGood) return false
        val bytes = runCatching { FileIo.readBytes(Path(r.sidecar_path)) }.getOrNull() ?: return false
        val ok = client.uploadEpisodeSubtitle(cfg.url, cfg.apiKey, t.seriesId, destBazarr.sonarrEpisodeId, r.language ?: "", r.forced == 1L, r.hi == 1L,
            r.sidecar_path.substringAfterLast('/'), bytes)
        record(item, r, MOVE, if (ok) DONE else FAILED, costs = false, detail = "to=${r.match_label ?: dest.substringAfterLast('/')}")
        if (ok) later(item.id, dest, delayMs = 5_000)
        return ok
    }

    /**
     * FR-273-13/23 — a wrong subtitle goes, even when nothing replaces it. Bazarr's blacklist when its source is known
     * (Bazarr deletes it and searches again), else a plain delete through Bazarr. Three rounds a day per language,
     * inside the budget; past that, one of a neighbour's candidates when the series shows a shift (FR-273-14).
     */
    private suspend fun replace(cfg: BazarrConfig, item: MediaItem, t: Target, r: Subtitle_check, alone: Boolean): Boolean {
        val action = if (sourceOf(cfg, t, r) != null) BLACKLIST else REMOVE
        return step(item, r, action, alone) {
            val rounds = q.roundsSince(BLACKLIST, r.video_path, r.language, now() - 86_400).executeAsOne()
            when {
                action == BLACKLIST && rounds >= ROUNDS_PER_DAY -> neighbour(cfg, item, t, r) || remove(cfg, item, t, r)
                action == BLACKLIST && !budgetLeft() -> false
                else -> remove(cfg, item, t, r)
            }
        }
    }

    private suspend fun sourceOf(cfg: BazarrConfig, t: Target, r: Subtitle_check): Pair<String, String>? {
        if (!r.provider.isNullOrBlank() && !r.subs_id.isNullOrBlank()) return r.provider to r.subs_id
        val name = r.sidecar_path.substringAfterLast('/')
        val history = when (t) {
            is Target.Ep -> client.episodeHistoryRows(cfg.url, cfg.apiKey, episodeId = t.episode.sonarrEpisodeId, length = 50)
            is Target.Film -> client.movieHistoryRows(cfg.url, cfg.apiKey, radarrId = t.movie.radarrId, length = 50)
        }
        val row = history.firstOrNull { it.action in BazarrHistoryRow.PLACED && it.subtitlesPath?.substringAfterLast('/') == name && !it.provider.isNullOrBlank() && !it.subsId.isNullOrBlank() }
            ?: return null
        q.setSource(row.provider, row.subsId, r.sidecar_path)
        return row.provider!! to row.subsId!!
    }

    /** Blacklist (when the source is known and the budget allows) or delete, through Bazarr. */
    private suspend fun remove(cfg: BazarrConfig, item: MediaItem, t: Target, r: Subtitle_check): Boolean {
        val src = sourceOf(cfg, t, r)
        val path = bazarrPath(t, r.sidecar_path)
        val lang = r.language ?: ""
        return if (src != null && budgetLeft()) {
            val ok = when (t) {
                is Target.Ep -> client.blacklistEpisodeSubtitle(cfg.url, cfg.apiKey, t.seriesId, t.episode.sonarrEpisodeId, src.first, src.second, lang, path)
                is Target.Film -> client.blacklistMovieSubtitle(cfg.url, cfg.apiKey, t.movie.radarrId, src.first, src.second, lang, path)
            }
            record(item, r, BLACKLIST, if (ok) DONE else FAILED, costs = ok, detail = checks.verdictWords(r), provider = src.first, subsId = src.second)
            ok
        } else {
            val ok = when (t) {
                is Target.Ep -> client.deleteEpisodeSubtitle(cfg.url, cfg.apiKey, t.seriesId, t.episode.sonarrEpisodeId, lang, r.forced == 1L, r.hi == 1L, path)
                is Target.Film -> client.deleteMovieSubtitle(cfg.url, cfg.apiKey, t.movie.radarrId, lang, r.forced == 1L, r.hi == 1L, path)
            }
            record(item, r, REMOVE, if (ok) DONE else FAILED, costs = false, detail = checks.verdictWords(r))
            ok
        }
    }

    /**
     * FR-273-14 — when this series' uploads are shifted (two or more subtitles in this language that belong N episodes
     * away), the right one for this episode is likely filed N episodes away. Bazarr searches that episode, and its
     * best untried candidate is downloaded onto this one; it is checked like any other and deleted (not blacklisted:
     * it may well be right where it was filed) when it does not fit. One candidate per pass, inside the budget.
     */
    private suspend fun neighbour(cfg: BazarrConfig, item: MediaItem, t: Target, r: Subtitle_check): Boolean {
        if (t !is Target.Ep || !budgetLeft()) return false
        val season = t.unit.seasonNumber ?: return false
        val number = t.unit.episodeNumber ?: return false
        val shifts = checks.checksForItem(item.id)
            .filter { it.verdict == Verdict.OTHER_EPISODE.wire && it.language == r.language && it.match_video_path != null }
            .mapNotNull { c ->
                val placed = item.episodes.firstOrNull { it.path == c.video_path } ?: return@mapNotNull null
                val content = item.episodes.firstOrNull { it.path == c.match_video_path } ?: return@mapNotNull null
                if (placed.seasonNumber != season || content.seasonNumber != season) return@mapNotNull null
                (content.episodeNumber ?: return@mapNotNull null) - (placed.episodeNumber ?: return@mapNotNull null)
            }
        val shift = shifts.groupingBy { it }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key ?: return false
        val source = item.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == number - shift } ?: return false
        val sourceBazarr = service.resolveEpisode(t.seriesId, source) ?: return false
        val tried = q.triedFor(r.video_path, r.language).executeAsList().filterNotNull().toSet()
        val candidate = client.searchProvidersEpisode(cfg.url, cfg.apiKey, sourceBazarr.sonarrEpisodeId)
            .filter { it.language.substringBefore(':') == r.language && it.forced == (r.forced == 1L) }
            .filter { "${it.provider}:${it.releaseInfo.joinToString("|")}:${it.uploader}" !in tried }
            .maxByOrNull { it.score ?: 0 } ?: return false
        val key = "${candidate.provider}:${candidate.releaseInfo.joinToString("|")}:${candidate.uploader}"
        val ok = client.downloadProviderEpisodeSubtitle(cfg.url, cfg.apiKey, t.seriesId, t.episode.sonarrEpisodeId, candidate.hi, candidate.forced,
            candidate.provider, candidate.subtitle)
        record(item, r, NEIGHBOUR, if (ok) DONE else FAILED, costs = ok, subsId = key,
            detail = "from=${SubtitleCheckService.episodeLabel(season, number - shift)};shift=$shift")
        return ok
    }

    /**
     * FR-273-15 — a language whose subtitle was in sync and now is not (a blind upgrade, a wrong download that was then
     * removed): the kept copy goes back through Bazarr, which records it at the maximum score so it is not upgraded
     * again. Returns the languages restored. A copy kept before the video itself was replaced is timed for the old
     * release and is dropped instead.
     */
    private suspend fun restore(cfg: BazarrConfig, item: MediaItem, t: Target, videoPath: String): Set<String?> {
        val rows = q.checksForVideo(videoPath).executeAsList()
        val videoMtime = FileIntegrityService.stampOf(videoPath)?.mtime ?: return emptySet()
        val restored = HashSet<String?>()
        for (s in q.settledForVideo(videoPath).executeAsList()) {
            if (s.settled_at < videoMtime) { q.removeSettled(s.sidecar_path); continue }
            val sameLang = rows.filter { it.language == s.language }
            if (sameLang.any { it.verdict == Verdict.IN_SYNC.wire || it.content_hash == s.content_hash }) continue
            if (sameLang.any { it.verdict == Verdict.CANT_TELL.wire && it.reason != CantTell.WEAK }) continue  // not judged yet
            if (q.actedOnContent(s.sidecar_path, RESTORE, "%hash=${s.content_hash}%").executeAsOne() > 0) continue
            if (!mode().actsAlone) {
                q.insertAction(RESTORE, WAITING, item.id, videoPath, s.sidecar_path, s.language, null, null, 0,
                    "hash=${s.content_hash};Put back the copy that was in sync", now(), null)
                continue
            }
            val ok = upload(cfg, t, s.language, s.forced == 1L, s.hi == 1L, s.file_name, s.content)
            q.insertAction(RESTORE, if (ok) DONE else FAILED, item.id, videoPath, s.sidecar_path, s.language, null, null, 0,
                "hash=${s.content_hash};" + (sameLang.firstOrNull()?.let { "over one that is ${checks.verdictWords(it)}" } ?: "the language was empty"), now(), now())
            mediaHistory.record(item.id, "subtitle_restore", "${s.file_name} ${if (ok) DONE else FAILED}")
            if (ok) restored += s.language
        }
        return restored
    }

    private suspend fun upload(cfg: BazarrConfig, t: Target, language: String?, forced: Boolean, hi: Boolean, name: String, content: ByteArray): Boolean = when (t) {
        is Target.Ep -> client.uploadEpisodeSubtitle(cfg.url, cfg.apiKey, t.seriesId, t.episode.sonarrEpisodeId, language ?: "", forced, hi, name, content)
        is Target.Film -> client.uploadMovieSubtitle(cfg.url, cfg.apiKey, t.movie.radarrId, language ?: "", forced, hi, name, content)
    }

    /** After a change: tell Jellyfin about this one item (dev review item 1) and check the video again. The video's
     *  own path goes as *Modified*: what changed beside it may be a file that no longer exists. */
    private suspend fun afterChange(item: MediaItem, videoPath: String, t: Target) {
        val jfId = when (t) { is Target.Ep -> t.unit.jellyfinId; is Target.Film -> item.jellyfinId }
        tellJellyfin(configStore, jellyfinClient, videoPath, "Modified", jfId)
        later(item.id, videoPath, delayMs = 3_000, recheck = true)
    }

    /** Look again later: a TV was playing, or Bazarr is still placing a file. */
    private fun later(itemId: String, videoPath: String, delayMs: Long = 10 * 60_000L, recheck: Boolean = true) {
        scope.launch {
            delay(delayMs)
            val item = store.resolve(itemId) ?: return@launch
            if (recheck) runCatching { checks.checkVideo(item, videoPath, SubtitleCheckService.Mode.INLINE) }
        }
    }

    // ── Needs your OK ─────────────────────────────────────────────────────────────────────────

    fun waiting(): List<Subtitle_action> = q.waiting().executeAsList()

    /** The admin said yes: run the proposed action now, whatever the mode. */
    suspend fun approve(actionId: Long): Boolean {
        val a = q.actionById(actionId).executeAsOneOrNull() ?: return false
        if (a.state != WAITING) return false
        val cfg = service.config() ?: return false
        val item = store.resolve(a.item_id) ?: return false
        val target = targetFor(item, a.video_path) ?: return false
        q.decide(APPROVED, now(), a.detail, a.id)
        val row = a.sidecar_path?.let { q.checkByPath(it).executeAsOneOrNull() }
        val ok = when (a.action) {
            SYNC -> row?.let { sync(cfg, item, target, it) } ?: false
            MOVE -> row?.let { move(cfg, item, target, it) } ?: false
            RESTORE -> { restoreOne(cfg, item, target, a) }
            else -> row?.let { remove(cfg, item, target, it) } ?: false
        }
        if (ok) afterChange(item, a.video_path, target)
        return ok
    }

    private suspend fun restoreOne(cfg: BazarrConfig, item: MediaItem, t: Target, a: Subtitle_action): Boolean {
        val s = a.sidecar_path?.let { q.settledByPath(it).executeAsOneOrNull() } ?: return false
        val ok = upload(cfg, t, s.language, s.forced == 1L, s.hi == 1L, s.file_name, s.content)
        q.insertAction(RESTORE, if (ok) DONE else FAILED, item.id, a.video_path, s.sidecar_path, s.language, null, null, 0, "hash=${s.content_hash};approved", now(), now())
        return ok
    }

    fun dismiss(actionId: Long): Boolean {
        val a = q.actionById(actionId).executeAsOneOrNull() ?: return false
        if (a.state != WAITING) return false
        q.decide(DISMISSED, now(), a.detail, a.id)
        return true
    }
}
