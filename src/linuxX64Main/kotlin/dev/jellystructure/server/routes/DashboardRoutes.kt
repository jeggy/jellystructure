package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.TriageDetection
import dev.jellystructure.media.posterArtworkExists
import dev.jellystructure.model.DashboardDomain
import dev.jellystructure.model.DashboardDto
import dev.jellystructure.model.DashboardHeadline
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.model.DashboardSince
import dev.jellystructure.model.DashboardSinceItem
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Phase 285 — the Dashboard as one overview of what could be fixed. One computation builds every row the page
 * shows (FR-285-1): the triage counts (kind-split, FR-285-9), the Jellyfin advisor (server-wide and per library,
 * the host's findings as *This server*), the Bazarr advisor and 273's fit numbers, 221's operator findings and the
 * services' lines. Severity, the unit, the sentence and what fixing means are decided here (FR-285-2/3); a row at
 * zero is never sent (FR-285-5); a missing intro is not reported (FR-285-7).
 */
class DashboardService(
    private val store: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val mediaHistory: MediaHistory,
    private val segmentStore: MediaSegmentStore,
    private val music: dev.jellystructure.media.MusicPipeline?,
    private val castService: dev.jellystructure.tv.CastService?,
    private val playbackService: dev.jellystructure.tv.PlaybackService,
    private val suggestions: dev.jellystructure.suggestions.SuggestionService?,
    private val subtitles: dev.jellystructure.server.SubtitleCheckWiring?,
    private val lidarr: dev.jellystructure.arr.LidarrClient? = null,
    private val webhookSince: () -> Long?,
) {
    /** FR-285-2 — how one triage type reads as a row. `domain == null` ⇒ a film row and a series row from the kind split. */
    private data class Spec(
        val domain: String?, val severity: String, val fix: String, val label: String, val sentence: String,
        val instanceUnit: String? = null, val action: String? = null, val actionId: String? = null,
        val action2: String? = null, val action2Id: String? = null,
    )

    private val specs: Map<String, Spec> = mapOf(
        "untagged" to Spec(null, WARNING, "open", "Tracks with no language", "Across {titles}. Ravilo can’t choose by language until they have one.", "track"),
        "cascade_mismatch" to Spec(null, WARNING, "open", "Wrong default audio track", "The default audio isn’t the title’s metadata language."),
        "multi_default" to Spec(null, WARNING, "open", "More than one default audio track", "A file should have exactly one."),
        "language_mix" to Spec("series", WARNING, "open", "Episodes disagree on audio language", "The majority language is used for metadata."),
        "missing_artwork" to Spec(null, WARNING, "here", "No poster", "No poster.jpg on disk.", action = "Re-pull artwork", actionId = "fetch_artwork"),
        "missing_from_source" to Spec(null, WARNING, "open", "No longer in Jellyfin", "Kept for review, never deleted."),
        "missing_still" to Spec("series", WARNING, "open", "Episodes with no image", "Across {titles}: no TMDB still and no screen-grab, so Ravilo shows a blank card.", "episode"),
        "duplicate" to Spec(null, WARNING, "open", "Duplicate library entries", "The same Jellyfin item appears twice."),
        "duplicate_episode" to Spec("series", WARNING, "open", "Two files for one episode", "Across {titles}: each claims an episode another file already has — Ravilo plays one, and play-next stalls on the other.", "file"),
        "unresolved_jellyfin_id" to Spec("series", WARNING, "open", "Episodes Jellyfin never numbered", "Across {titles}: the file name says which episode, Jellyfin never matched it — they drop out of Continue Watching and next-episode.", "episode"),
        "zero_audio" to Spec(null, CRITICAL, "open", "No audio tracks", "Usually a truncated file.", "file"),
        "cover_as_video" to Spec(null, CRITICAL, "open", "Cover art muxed as a video track", "A still image rides as a second video stream — players may open the file and never start the video. Repair drops the cover stream.", "file"),
        "segments_lowconf" to Spec("series", WARNING, "open", "Intro and credits worth a look", "Across {titles}: found by the heuristic with low confidence.", "episode"),
        "mkv_track_layout" to Spec(null, CRITICAL, "open", "Unplayable in Ravilo (MKV structure)", "The Tracks element sits after the first Cluster. Repair rewrites the header in place.", "file"),
        "file_damage" to Spec(null, CRITICAL, "open", "Damaged video files", "Parts that can’t be read, in {titles}. A clean copy may still seed in qBittorrent.", "file"),
        "track_ends_early" to Spec(null, CRITICAL, "open", "Audio or video stops before the file ends", "Viewers hear silence or see black from that point.", "file"),
        "duration_header_wrong" to Spec(null, WARNING, "open", "File claims to be longer than it is", "It never reaches 90 %, so it is never marked watched.", "file"),
        "music_shared_album" to Spec("music", WARNING, "open", "One album in several folders", "Often a band’s singles whose files all name one compilation."),
        "music_folder_disagrees" to Spec("music", WARNING, "open", "Folder and songs disagree", "The folder’s name and the songs’ tags name different things."),
        "music_needs_match" to Spec("music", WARNING, "open", "Albums need a match", "MusicBrainz found several candidates and none clearly won, or found nothing.", action = "Find match…"),
        "music_no_cover" to Spec("music", WARNING, "open", "Albums without a cover", "Matched, but no cover on disk."),
        "music_no_picture" to Spec("music", WARNING, "open", "Artists without a picture", "Neither fanart.tv nor Wikimedia Commons had one."),
        "music_reencodes" to Spec("music", WARNING, "open", "Songs a phone plays only by re-encoding", "WMA. Convert… makes AAC copies and keeps the originals.", action = "Convert…"),
        "music_files_no_ids" to Spec("music", WARNING, "open", "Songs whose files don’t say what they are", "Matched, but none of it is in the files — no MusicBrainz ids. Write tags puts them there."),
        // Phase 292 (FR-292-15) — two actions, both by hand (amends FR-285-2's one-action rule for this row only).
        "music_instrumental_lyrics" to Spec("music", WARNING, "here", "Lyrics on an instrumental", "These songs have no singing, but have lyrics beside them.",
            action = "Remove the lyrics", actionId = "music_lyrics_remove", action2 = "Tell LRCLIB it is instrumental", action2Id = "music_lyrics_lrclib"),
        "audiobooks_missing_part" to Spec("books", WARNING, "open", "A part is missing", "The folder’s files skip a number — the book will jump."),
        "audiobooks_two_in_one" to Spec("books", WARNING, "open", "Folder holds two books", "The parts carry two different book titles."),
        "audiobooks_no_cover" to Spec("books", WARNING, "open", "No cover", "No cover.jpg, no embedded art, no provider had one."),
        "audiobooks_no_narrator" to Spec("books", INFO, "info", "No narrator", "A book plays the same without one."),
    )

    /** FR-285-7 — not a jellystructure problem: never a row. */
    private val dropped = setOf("no_segments")

    /** 246's host checks live in the Jellyfin advisor; they are *This server* on the Dashboard (Q5). */
    // Phase 297 FR-297-6 — Known proxies is a Jellyfin setting (Dashboard → Networking), so its rows stay under Jellyfin.
    private val hostFindingPrefixes = listOf("swappiness", "scheduler_", "readahead_", "max_sectors_")

    suspend fun build(jellyfinUserId: String?): DashboardDto {
        val cfg = configStore.current
        val all = store.allItems()
        val rows = ArrayList<DashboardRow>()

        // ── Films · Series · Music · Audiobooks — the triage types, kind-split ──
        val count = triageCountFor(store, configStore, segmentStore, music)
        val films = all.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.MUSIC_VIDEO }
        val series = all.filter { it.kind == MediaKind.TV_SHOW }
        for (t in count.types) {
            if (t.key in dropped || t.instances == 0) continue
            val spec = specs[t.key] ?: Spec(domainOfKey(t.key), WARNING, "open", t.label, t.description)
            if (spec.domain == null) {
                if (t.movies > 0) rows += mediaRow(t.key, spec, "films", "film", t.movies, t.movieTitles, films)
                if (t.series > 0) rows += mediaRow(t.key, spec, "series", "series", t.series, t.seriesTitles, series)
                if (t.movies == 0 && t.series == 0) rows += mediaRow(t.key, spec, "films", "title", t.instances, t.titles, all)
            } else when (spec.domain) {
                "series" -> if (t.series > 0 || t.instances > 0) rows += mediaRow(t.key, spec, "series", "series", t.series.takeIf { it > 0 } ?: t.instances, t.seriesTitles.takeIf { it > 0 } ?: t.titles, series)
                "music", "books" -> rows += DashboardRow(
                    id = t.key, domain = spec.domain, severity = spec.severity, label = spec.label, sentence = spec.sentence,
                    count = t.instances, unit = unitForMusic(t.key), fix = spec.fix, action = spec.action, actionId = spec.actionId,
                    action2 = spec.action2, action2Id = spec.action2Id,
                    href = "/library?kind=${if (spec.domain == "music") "music" else "audiobooks"}&filter=${t.key}",
                )
                else -> rows += mediaRow(t.key, spec, spec.domain, "title", t.instances, t.titles, all)
            }
        }

        // ── Jellyfin · This server — 212/246/257's advisor, one row per finding; a per-library finding counts libraries ──
        val advisor = runCatching { dev.jellystructure.advisor.JellyfinAdvisorService.findings(jellyfinClient, cfg) }.getOrNull()
        val jellyfinReachable = advisor?.reachable != false
        if (advisor != null && advisor.reachable) {
            for (f in advisor.serverWide) {
                val domain = if (hostFindingPrefixes.any { f.id.startsWith(it) }) "host" else "jf"
                rows += findingRow(f.id, domain, f.severity, f.summary, "", null, null, if (domain == "host") "the host" else "Jellyfin",
                    f.navigationPath, f.fieldLabel, f.currentValue, f.recommendation, f.tradeoff, f.action, f.id, href = "/settings?tab=${if (domain == "host") "advanced" else "libraries"}")
            }
            val perLib = advisor.perLibrary.flatMap { sec -> sec.findings.map { sec.libraryName to it } }
                .groupBy { (_, f) -> f.id.substringBeforeLast('_').ifBlank { f.id } }
            for ((_, group) in perLib) {
                val (firstLib, f) = group.first()
                val libs = group.map { it.first }.distinct()
                // Phase 297 FR-297-8 — a row for several libraries must not send the admin to the first one only.
                val navPath = if (libs.size > 1) f.navigationPath.replace(" → $firstLib → ", " → (each library listed) → ") else f.navigationPath
                rows += findingRow("lib_" + f.id, "jf", f.severity, f.summary, libs.joinToString(" · "), libs.size, "library", "Jellyfin",
                    navPath, f.fieldLabel, f.currentValue, f.recommendation, f.tradeoff, f.action, f.id, href = "/settings?tab=libraries")
            }
        }

        // ── Subtitles — 273's fit numbers and the Bazarr advisor ──
        subtitles?.let { w ->
            if (cfg.bazarr?.let { it.enabled && it.url.isNotBlank() } == true) {
                val counts = runCatching { w.db.subtitleCheckQueries.countByVerdict().executeAsList().associate { it.verdict to it.n.toInt() } }.getOrDefault(emptyMap())
                val off = (counts["off"] ?: 0) + (counts["off_mid_file"] ?: 0)
                val wrong = (counts["not_this_video"] ?: 0) + (counts["other_episode"] ?: 0) + (counts["longer_video"] ?: 0)
                if (off > 0) rows += DashboardRow("subs_off", "subs", WARNING, "Out of step with their video", "Bazarr’s sync could not line them up.", off, "subtitle", "open", href = "/subtitles?fit=out")
                if (wrong > 0) rows += DashboardRow("subs_wrong", "subs", WARNING, "Made for a different video", "Another cut or release — timings will never fit.", wrong, "subtitle", "open", href = "/subtitles?fit=wrong")
                val waiting = runCatching { w.db.subtitleCheckQueries.waiting().executeAsList().size }.getOrDefault(0)
                if (waiting > 0) rows += DashboardRow("subs_waiting", "subs", WARNING, "Subtitle changes waiting for your OK", "Each says what it would do; *Do it* or *Leave it* on the Subtitles page.", waiting, "proposal", "open", href = "/subtitles")
                val advice = runCatching { w.advisor?.advise() }.getOrNull()
                if (advice != null && advice.reachable) for (f in advice.findings) {
                    val here = f.action == "apply_bazarr"
                    rows += findingRow("bz_" + f.id, "subs", f.severity, f.summary, "", null, null, "Bazarr", f.navigationPath, f.fieldLabel, f.currentValue,
                        f.recommendation, f.tradeoff, f.action, f.id, href = "/settings?tab=downloads", fixOverride = if (here) "here" else null, actionLabel = if (here) "Apply in Bazarr" else null)
                }
            }
        }

        // ── Services — 221's operator findings, Suggestions, the Chromecast card ──
        val webhookUrl = cfg.behavior.notificationsWebhook.trim()
        for (f in runCatching { dev.jellystructure.ops.WebhookStatus.findings(webhookUrl, webhookSince()) }.getOrDefault(emptyList())) {
            rows += DashboardRow("wh_" + f.id, "svc", WARNING, f.summary, f.costHere, fix = "elsewhere", href = "/settings?tab=notifications",
                path = listOf(f.navigationPath, f.fieldLabel).filter { it.isNotBlank() }.joinToString(" › "), now = f.currentValue, recommendation = f.recommendation, tradeoff = f.tradeoff)
        }
        suggestions?.takeIf { it.available() }?.let { s ->
            val sum = runCatching { s.summary() }.getOrNull()
            if (sum != null && sum.waiting > 0) rows += DashboardRow("svc_suggestions", "svc", INFO, "Suggested films waiting",
                if (sum.newCount > 0) "${sum.newCount} new since the last build" + (sum.topClusters.takeIf { it.isNotEmpty() }?.let { " — most room in " + it.joinToString(" and ") } ?: "") else "Nothing new since the last build.",
                sum.waiting, "film", "info", action = "Suggestions", href = "/suggestions")
        }
        // Phase 284 (dev review 4) — Lidarr as a second writer: only when its settings make it one.
        cfg.lidarr?.takeIf { it.enabled && it.url.isNotBlank() }?.let { l ->
            val tagging = lidarrCached("tagging") { lidarr?.tagging(l.url, l.apiKey)?.let { t -> "${t.writeAudioTags}|${t.scrubAudioTags}" } }
            val parts = tagging?.split('|')
            if (parts != null && parts[0] !in setOf("no", "newFiles")) rows += DashboardRow("lidarr_rewrites", "svc", WARNING, "Lidarr rewrites the tags jellystructure writes",
                "Its *Tag Audio Files with Metadata* is set to ${if (parts[0] == "sync") "Sync" else "All files"}: every rescan rewrites what jellystructure wrote" + (if (parts.getOrNull(1) == "true") ", and *Scrub Existing Tags* drops the rest" else "") + ". Set it to *New files* (or *No*) so the two never fight.",
                fix = "elsewhere", where = "Lidarr", path = "Settings › Metadata › Tag Audio Files with Metadata", now = parts[0], href = "/settings?tab=downloads")
            val consumers = lidarrCached("consumers") { lidarr?.enabledConsumers(l.url, l.apiKey)?.joinToString("|") }
            if (consumers?.split('|')?.any { it.contains("Kodi", true) || it.contains("Emby", true) } == true) rows += DashboardRow("lidarr_nfo", "svc", WARNING, "Lidarr writes album.nfo and artist.nfo too",
                "Its Kodi / Emby metadata consumer is on: two writers on one file, the finding 277 made for Jellyfin’s NFO saver.", fix = "elsewhere", where = "Lidarr", path = "Settings › Metadata › Kodi (XBMC) / Emby", now = "On", href = "/settings?tab=downloads")
        }
        runCatching { castService?.status(dev.jellystructure.tv.activePlaybackDevices()) }.getOrNull()?.let { cs ->
            if (cs.enabled && cs.appIdSet && !cs.verified) rows += DashboardRow("svc_cast", "svc", WARNING, "Chromecast isn’t confirmed by a real cast",
                "The receiver is registered, but only a cast from a phone proves it reaches a TV.", fix = "info", href = "/settings?tab=connections")
            // 286 (FR-286-9) — for information until the first speaker cast; nothing once they are confirmed.
            if (cs.enabled && cs.appIdSet && cs.verified && cs.speakersConfirmedAt == null) rows += DashboardRow("svc_cast_speakers", "svc", INFO, "Chromecast · speakers not confirmed — step 5a",
                "Tick *Supports casting to audio-only devices* on the console, then cast a song to a speaker once.", fix = "info", href = "/settings?tab=connections")
        }

        // ── Order, headline, domains ──
        val ordered = rows.sortedWith(compareBy<DashboardRow>({ severityRank(it.severity) }, { -(it.count ?: 0) }, { it.label }))
        val domains = DOMAINS.mapNotNull { (id, label) -> ordered.count { it.domain == id }.takeIf { it > 0 }?.let { DashboardDomain(id, label, it) } }
        val headline = DashboardHeadline(
            critical = ordered.count { it.severity == CRITICAL }, warnings = ordered.count { it.severity == WARNING }, info = ordered.count { it.severity == INFO },
            rows = ordered.size, things = ordered.filter { it.severity != INFO && it.unit !in setOf(null, "library", "setting") }.sumOf { it.count ?: 0 },
        )
        return DashboardDto(headline, domains, ordered, since = since(jellyfinUserId, all), jellyfinReachable = jellyfinReachable, firstRun = all.isEmpty())
    }

    // Phase 294 (FR-294-2) — two Dashboard loads at once must not corrupt it: a LockedMap, not a HashMap.
    private val lidarrCache = dev.jellystructure.ops.LockedMap<String, Pair<Long, String?>>()
    private suspend fun lidarrCached(key: String, fetch: suspend () -> String?): String? {
        val now = nowEpochSec()
        lidarrCache[key]?.takeIf { now - it.first < 300 }?.let { return it.second }
        val v = runCatching { fetch() }.getOrNull()
        lidarrCache[key] = now to v
        return v
    }

    private fun domainOfKey(key: String) = when { key.startsWith("music_") -> "music"; key.startsWith("audiobooks_") -> "books"; else -> "films" }

    /** Phase 293 (FR-293-3) — the unit is the one the key's list shows (one table, [dev.jellystructure.music.MusicTriage]). */
    private fun unitForMusic(key: String) =
        (dev.jellystructure.music.MusicTriage.MUSIC[key] ?: dev.jellystructure.music.MusicTriage.AUDIOBOOKS[key])?.unit ?: "album"

    private fun mediaRow(key: String, spec: Spec, domain: String, titleUnit: String, instances: Int, titles: Int, pool: List<MediaItem>): DashboardRow {
        val names = titlesFor(key, pool).take(3)
        val n = if (spec.instanceUnit != null) instances else titles.takeIf { it > 0 } ?: instances
        val titleCount = titles.takeIf { it > 0 } ?: instances
        val sentence = spec.sentence.replace("{titles}", "$titleCount ${plural(titleUnit, titleCount)}") + (if (names.isEmpty()) "" else " — " + names.joinToString(", ") + if (titleCount > names.size) "…" else "")
        val kindParam = if (domain == "films") "&kind=movie" else if (domain == "series") "&kind=series" else ""
        return DashboardRow(id = "$key:$domain", domain = domain, severity = spec.severity, label = spec.label, sentence = sentence,
            count = n, unit = spec.instanceUnit ?: titleUnit, fix = spec.fix, action = spec.action, actionId = spec.actionId, href = "/library?filter=$key$kindParam")
    }

    private fun findingRow(
        id: String, domain: String, severity: String, label: String, sentence: String, count: Int?, unit: String?, where: String,
        navigationPath: String, fieldLabel: String, now: String, recommendation: String, tradeoff: String, actionKind: String?, findingId: String,
        href: String, fixOverride: String? = null, actionLabel: String? = null,
    ) = DashboardRow(
        id = id, domain = domain, severity = severity.ifBlank { WARNING }, label = label, sentence = sentence, count = count, unit = unit,
        fix = fixOverride ?: if (severity == INFO) "info" else "elsewhere", action = actionLabel ?: if (actionKind == "recheck_exposure") "Re-check" else null,
        href = href, where = where, path = listOf(navigationPath, fieldLabel).filter { it.isNotBlank() }.joinToString(" › "),
        now = now, recommendation = recommendation, tradeoff = tradeoff, actionKind = actionKind, findingId = findingId,
    )

    /** Up to a few titles for the sentence — the same predicates the Library's filter uses (Phase 117's rule). */
    private fun titlesFor(key: String, pool: List<MediaItem>): List<String> {
        val mkv = if (key == "mkv_track_layout") dev.jellystructure.media.MkvHealthCache.brokenPathsOrNull()?.keys else null
        val damaged = if (key == "file_damage") dev.jellystructure.media.FileDamage.damagedPathsOrNull() else null
        val flagged = if (key == "track_ends_early" || key == "duration_header_wrong") dev.jellystructure.media.TrackCoverageFlags.flaggedOrNull() else null
        val early = flagged?.filterValues { dev.jellystructure.media.TrackCoverageFlags.endsEarly(it) }?.keys
        val wrong = flagged?.filterValues { dev.jellystructure.media.TrackCoverageFlags.headerWrong(it) }?.keys
        val hit: (MediaItem) -> Boolean = when (key) {
            "untagged" -> { it -> TriageDetection.untaggedCount(it) > 0 }
            "cascade_mismatch" -> { it -> TriageDetection.hasCascadeMismatch(it) }
            "multi_default" -> { it -> TriageDetection.hasMultiDefault(it) }
            "language_mix" -> { it -> it.languageMix }
            "missing_artwork" -> { it -> !posterArtworkExists(it) }
            "missing_from_source" -> { it -> it.missingFromSource }
            "missing_still" -> { it -> TriageDetection.missingStillCount(it) > 0 }
            "duplicate_episode" -> { it -> TriageDetection.duplicateEpisodeCount(it) > 0 }
            "unresolved_jellyfin_id" -> { it -> TriageDetection.unresolvedJellyfinIdCount(it) > 0 }
            "zero_audio" -> { it -> TriageDetection.zeroAudioCount(it) > 0 }
            "cover_as_video" -> { it -> TriageDetection.coverAsVideoCount(it) > 0 }
            "segments_lowconf" -> { it -> TriageDetection.lowConfidenceSegmentsCount(it, segmentStore) > 0 }
            "mkv_track_layout" -> { it -> mkv != null && TriageDetection.mkvLayoutBrokenCount(it, mkv) > 0 }
            "file_damage" -> { it -> damaged != null && TriageDetection.fileDamageCount(it, damaged) > 0 }
            "track_ends_early" -> { it -> early != null && TriageDetection.trackCoverageCount(it, early) > 0 }
            "duration_header_wrong" -> { it -> wrong != null && TriageDetection.trackCoverageCount(it, wrong) > 0 }
            else -> { _ -> false }
        }
        return pool.asSequence().filter(hit).map { it.title }.filter { it.isNotBlank() }.take(3).toList()
    }

    /** FR-285-8 — what changed by domain since this admin last opened the Dashboard. */
    private fun since(jellyfinUserId: String?, all: List<MediaItem>): DashboardSince? {
        val uid = jellyfinUserId ?: return null
        val last = mediaHistory.lastDashboardVisit(uid) ?: return DashboardSince(null, emptyList())
        val kinds = all.associate { it.id to it.kind }
        val byDomain = LinkedHashMap<String, MutableList<String>>()
        for (e in mediaHistory.since(last)) {
            val domain = when {
                e.action.startsWith("music_") -> "music"
                e.action.startsWith("audiobook_") -> "books"
                e.action.startsWith("subtitle_") -> "subs"
                e.action.startsWith("jellyfin_") -> "jf"
                else -> when (kinds[e.mediaId]) { MediaKind.TV_SHOW -> "series"; null -> continue; else -> "films" }
            }
            byDomain.getOrPut(domain) { ArrayList() } += e.action
        }
        val items = DOMAINS.mapNotNull { (id, _) ->
            val actions = byDomain[id] ?: return@mapNotNull null
            val top = actions.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
                .joinToString(" · ") { (a, n) -> "$n ${a.replace('_', ' ')}" }
            DashboardSinceItem(id, "${actions.size} ${if (actions.size == 1) "change" else "changes"} — $top")
        }
        return DashboardSince(last, items)
    }

    private fun plural(unit: String, n: Int) = when {
        n == 1 -> unit
        unit == "series" -> "series"
        unit == "library" -> "libraries"
        else -> unit + "s"
    }

    private fun severityRank(s: String) = when (s) { CRITICAL -> 0; WARNING -> 1; else -> 2 }

    companion object {
        const val CRITICAL = "critical"
        const val WARNING = "warning"
        const val INFO = "info"
        val DOMAINS = listOf("films" to "Films", "series" to "Series", "music" to "Music", "books" to "Audiobooks", "subs" to "Subtitles", "jf" to "Jellyfin", "host" to "This server", "svc" to "Services")
    }
}

fun Route.dashboardRoutes(service: DashboardService, mediaHistory: MediaHistory) {
    route("/dashboard") {
        get {
            val uid = runCatching { call.attributes[SessionKey].jellyfinUserId }.getOrNull()
            call.respond(service.build(uid))
        }
        /** FR-285-8 — the page says it was seen when the admin leaves it; the next open measures from here. */
        post("/seen") {
            runCatching { call.attributes[SessionKey].jellyfinUserId }.getOrNull()?.let { mediaHistory.markDashboardVisit(it, nowEpochSec()) }
            call.respond(mapOf("ok" to true))
        }
    }
}
