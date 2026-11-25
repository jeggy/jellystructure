package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.RatingBadge
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import dev.jellystructure.shared.tv.countFlagStrip
import dev.jellystructure.shared.tv.episodeCode
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** What the primary button of a series' page does with [episode]. */
internal enum class SeriesPrimaryMode { PLAY, RESUME, START_OVER }

/** [lastPart] is the last episode number of a multi-episode file that shares [episode]'s id (the kicker reads `S01E01–E03`). */
internal data class SeriesPrimary(val episode: Episode, val season: Int, val lastPart: Int, val mode: SeriesPrimaryMode)

/** Mirrors ravilo-ui's `Episode.isCatalogOnly`: a path id or a `#` suffix is a catalog row, never a file that plays. */
private fun Episode.playable() = !id.startsWith("/") && !id.contains('#')

/**
 * DEB-4660 (FR-7) — the episode a series' primary button plays, from what the server said about the viewer. It never offers a
 * watched episode (owner, 10-29): the server names the next one (`continue_episode_id`, R306/R375) and this only checks the
 * answer. Order, as ravilo-ui's `primaryEpisodeId`: a newest-first series' newest counted episode; on a finished series the
 * episode the server names for a re-watch, else the first counted one as *Start over* (when the server can clear it, R343);
 * else the Continue Watching episode, one in progress, the first unwatched counted episode, the first counted, any.
 * *Counted* is R346's: season 1 or later with a Jellyfin item. Null = nothing in it can play.
 */
internal fun seriesPrimary(detail: SeriesDetail, seriesId: String, overlay: Map<String, CardPlayState>): SeriesPrimary? {
    val counted = detail.seasons.filter { it.index >= 1 }.sortedBy { it.index }
        .flatMap { s -> s.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.map { s.index to it } }
    val all = detail.seasons.flatMap { s -> s.episodes.filter { it.playable() }.map { s.index to it } }
    val played = { id: String -> overlay[id]?.played == true }
    val inProgress = { id: String -> overlay[id].let { it != null && !it.played && it.resumeMs > 0 } }
    val finished = counted.isNotEmpty() && counted.all { played(it.second.id) }
    val named = overlay[seriesId]?.continueEpisodeId
    val started = counted.any { overlay[it.second.id].let { ps -> ps != null && (ps.played || ps.resumeMs > 0) } }
    fun pick(entry: Pair<Int, Episode>, mode: SeriesPrimaryMode): SeriesPrimary {
        val last = counted.lastOrNull { it.second.id == entry.second.id }?.second?.episodeNumber ?: entry.second.episodeNumber
        return SeriesPrimary(entry.second, entry.first, last, mode)
    }
    if (detail.newestFirst) counted.lastOrNull()?.let { return pick(it, if (inProgress(it.second.id)) SeriesPrimaryMode.RESUME else SeriesPrimaryMode.PLAY) }
    if (finished) {
        named?.let { id -> counted.firstOrNull { it.second.id == id } }?.let { return pick(it, SeriesPrimaryMode.RESUME) }
        return pick(counted.first(), if (detail.startOver) SeriesPrimaryMode.START_OVER else SeriesPrimaryMode.PLAY)
    }
    val entry = named?.takeIf { !played(it) || inProgress(it) }?.let { id -> all.firstOrNull { it.second.id == id } }
        ?: all.firstOrNull { inProgress(it.second.id) }
        ?: counted.firstOrNull { !played(it.second.id) }
        ?: counted.firstOrNull()
        ?: all.firstOrNull()
        ?: return null
    return pick(entry, if (started) SeriesPrimaryMode.RESUME else SeriesPrimaryMode.PLAY)
}

/**
 * DEB-4660 (FR-7) — a title's page. The server owns every fact on it: the labels come from `getPlaystate`, and `setPlayed` and
 * `setFavorite` change them by their answer (a refused write leaves the button as it was; nothing is flipped here first).
 * The page loads itself and calls [onChange] when it has something new to draw; the router owns focus and Back.
 */
internal class ScreenDetail(
    private val api: TvApiClient,
    private val serverUrl: String,
    private val id: String,
    private val kind: MediaKind,
    private val fallbackTitle: String,
    private val onChange: (ScreenDetail) -> Unit,
    private val onPlay: (ScreenPlayRequest) -> Unit,
    /** DEB-5631 (FR-49) — OK on a face: the person and the title the face was on. */
    private val onPerson: (Person, String) -> Unit = { _, _ -> },
    /** DEB-5649 (FR-51) — the poster tiles' maker: Home's own tile, so the row looks like every other poster tile. */
    private val tiles: ScreenHome? = null,
    /** DEB-5649 (FR-51) — OK on a *More like this* tile: that card's page, over this one. */
    private val onTitle: (MediaCard) -> Unit = {},
) {
    private var movie: MovieDetail? = null
    private var series: SeriesDetail? = null
    private var overlay: Map<String, CardPlayState> = emptyMap()
    private var loading = false
    private var busy = false
    private var failure: Int? = null
    private var season = Int.MIN_VALUE
    private var reading = false
    private var readAgain = false

    // DEB-5578 (DEB-5576, FR-31) — what the last draw left in the mount, so a redraw keeps the page still (see render).
    private var drawn: HTMLElement? = null
    private var drawnSeason = Int.MIN_VALUE
    private var backdrop: HTMLElement? = null
    private var logo: HTMLElement? = null
    private var logoLoaded = false
    private var titleNode: HTMLElement? = null

    val ready get() = movie != null || series != null

    /** DEB-5648 (FR-50) — the page's own reads failed, so nothing on it will ever be ready. */
    val failed get() = failure != null

    /** The primary button: where focus lands when the page has drawn. */
    val primaryFocusId = "detail-play"

    /**
     * DEB-5396 (DEB-5394, FR-35) — the item Play would play, the one the router warms an encode for: the film, or the series' primary
     * episode (what [seriesPrimary] names from the overlay as it is now, so an FR-31 re-read that moves it moves this). Null until the
     * page has drawn, and for a series nothing in which can play.
     */
    fun prewarmTarget(): String? = when {
        movie != null -> id
        else -> series?.let { seriesPrimary(it, id, overlay)?.episode?.id }
    }

    /** The series whose remembered audio the prewarm asks for (FR-23); null for a film, as a start's own. */
    val prewarmSeriesId: String? get() = if (series != null) id else null

    fun load() {
        if (loading || ready || failure != null) return   // a failed page is not retried by every redraw; leaving it and coming back is
        loading = true
        // DEB-5024 (FR-24) — Android TV's DetailPrewarm moment: measure this TV's path while the viewer reads the page. The server
        // answers 204 when it was measured within 24 h, so opening more pages costs nothing; nothing waits on it.
        GlobalScope.launch { api.probe() }
        GlobalScope.launch {
            if (kind == MediaKind.SERIES) {
                val result = runCatching { api.getSeries(id) }
                val detail = result.getOrNull()
                if (detail != null) {
                    val ids = (listOf(id) + detail.seasons.flatMap { s -> s.episodes.map { it.id } }).distinct()
                    overlay = runCatching { api.getPlaystate(ids) }.getOrDefault(emptyMap())
                    series = detail
                    season = seriesPrimary(detail, id, overlay)?.season ?: detail.seasons.firstOrNull()?.index ?: 0
                } else failure = (result.exceptionOrNull() as? TvApiError.Http)?.status ?: 0
            } else {
                val result = runCatching { api.getMovie(id) }
                val detail = result.getOrNull()
                if (detail != null) {
                    overlay = runCatching { api.getPlaystate(listOf(id)) }.getOrDefault(emptyMap())
                    movie = detail
                } else failure = (result.exceptionOrNull() as? TvApiError.Http)?.status ?: 0
            }
            loading = false
            onChange(this@ScreenDetail)
            if (readAgain) { readAgain = false; refresh() }
        }
    }

    /**
     * DEB-5050 (DEB-5047, FR-31) — Android TV's FR-R351-7: the page reads `getPlaystate` again for the title (a series: the series and
     * every episode) because it can have changed — it is shown again after the player, or the events socket pushed `home_changed` or
     * `playstate_changed`. The old values stay until new ones arrive; a failed read, or one that answers `{}`, keeps them. One read at
     * a time: a call during one asks for exactly one more after it, never a queue. Nothing is drawn but [onChange]'s plain redraw,
     * so focus and the season pill stay where they were and no skeleton appears. A page still loading reads once it has loaded.
     * DEB-5578 (DEB-5576, FR-31 amended) — an answer equal to what the page holds, for every id it asked for, draws nothing: the
     * server's 20 s push is mostly that, and the page's DOM is not touched.
     */
    fun refresh() {
        if (!ready) { if (loading) readAgain = true; return }
        if (reading) { readAgain = true; return }
        reading = true
        GlobalScope.launch {
            do {
                readAgain = false
                val ids = series?.let { s -> (listOf(id) + s.seasons.flatMap { sn -> sn.episodes.map { it.id } }).distinct() } ?: listOf(id)
                val fresh = runCatching { api.getPlaystate(ids) }.getOrNull()
                if (!fresh.isNullOrEmpty() && ids.any { fresh[it] != overlay[it] }) { overlay = fresh; onChange(this@ScreenDetail) }
            } while (readAgain)
            reading = false
        }
    }

    // ── What OK does ──────────────────────────────────────────────────────────

    fun activate(element: HTMLElement) {
        when (element.getAttribute("data-action")) {
            "play" -> play(startOver = false)
            "start-over" -> play(startOver = true)
            "list" -> write { api.setFavorite(id, overlay[id]?.favorite != true)?.let { mapOf(id to it) } }
            "watched" -> write { api.setPlayed(id, overlay[id]?.played != true) }
            "season" -> element.getAttribute("data-season")?.toIntOrNull()?.let { season = it; onChange(this) }
            "related" -> element.getAttribute("data-related")?.toIntOrNull()?.let { index -> related().getOrNull(index)?.let(onTitle) }
            "person" -> element.getAttribute("data-person")?.toIntOrNull()?.let { index ->
                cast().getOrNull(index)?.let { onPerson(it, (movie?.card ?: series?.card)?.title ?: fallbackTitle) }
            }
            "episode" -> element.getAttribute("data-episode-id")?.let { episodeId -> seasonEpisodes().firstOrNull { it.second.id == episodeId }?.let { playEpisode(it.first, it.second, startOver = false) } }
        }
    }

    /** A write-through: the server's answer replaces what was drawn, and a refusal changes nothing. One at a time. */
    private fun write(call: suspend () -> Map<String, CardPlayState>?) {
        if (busy) return
        busy = true
        GlobalScope.launch {
            val answer = runCatching { call() }.getOrNull()
            if (answer != null) overlay = overlay + answer
            busy = false
            onChange(this@ScreenDetail)
        }
    }

    private fun play(startOver: Boolean) {
        val m = movie
        if (m != null) {
            // Resume starts with no position: the server's playstate decides where, as it does for every start. Start over names 0.
            onPlay(ScreenPlayRequest(id, MediaKind.MOVIE, m.card.title, startOver = startOver,
                context = ScreenPlayContext("movie", logoUrl = m.logoUrl, logoInk = m.logoInk, segments = m.segments)))
            return
        }
        val s = series ?: return
        val primary = seriesPrimary(s, id, overlay) ?: return
        playEpisode(primary.season, primary.episode, primary.mode == SeriesPrimaryMode.START_OVER, primary.lastPart)
    }

    private fun playEpisode(seasonIndex: Int, episode: Episode, startOver: Boolean, lastPart: Int = episode.episodeNumber) {
        val s = series ?: return
        val counted = countedEpisodes(s)
        val at = counted.indexOfFirst { it.second.id == episode.id }
        val after = if (s.newestFirst || at < 0) emptyList() else counted.drop(at + 1).filter { it.second.id != episode.id }.distinctBy { it.second.id }
        val next = after.firstOrNull()
        onPlay(ScreenPlayRequest(episode.id, MediaKind.MOVIE, episode.title, startOver = startOver,
            context = ScreenPlayContext("episode", kicker = episodeCode(seasonIndex, episode.episodeNumber, lastPart), seriesId = id, seriesName = s.card.title,
                logoUrl = s.logoUrl, logoInk = s.logoInk, segments = episode.segments,
                nextId = next?.second?.id, nextTitle = next?.second?.title, nextKicker = next?.let { episodeCode(it.first, it.second.episodeNumber) },
                following = after.map { ScreenFollowing(it.second.id, it.second.title, episodeCode(it.first, it.second.episodeNumber), it.second.segments) },
                seasons = railSeasons(s), newestFirst = s.newestFirst)))
    }

    /**
     * DEB-5652 (FR-54) — ravilo-ui's `playerEntriesForSeason` for the player's rail: each season's episodes one card per file, with the
     * page's overlay as it is now. Specials (index 0) are a season of their own and nothing follows them.
     */
    private fun railSeasons(s: SeriesDetail): List<ScreenRailSeason> = s.seasons.sortedBy { it.index }.mapNotNull { sn ->
        val playable = sn.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }
        if (playable.isEmpty()) return@mapNotNull null
        val entries = playable.distinctBy { it.id }.map { ep ->
            val group = playable.filter { it.id == ep.id }
            val state = overlay[ep.id]
            ScreenRailEntry(ep.id, ep.episodeNumber.takeIf { group.size == 1 }, ep.title, episodeCode(sn.index, ep.episodeNumber, group.maxOf { it.episodeNumber }),
                group.sumOf { it.runtime }, ep.stillUrl, state?.playedPct?.coerceIn(0f, 1f) ?: 0f, state?.played == true, ep.segments)
        }
        ScreenRailSeason(sn.index, if (sn.index == 0) sn.name.ifBlank { "${t("detail.season", ScreenLanguage.lang)} 0" } else "${t("detail.season", ScreenLanguage.lang)} ${sn.index}", entries)
    }

    private fun countedEpisodes(s: SeriesDetail) = s.seasons.filter { it.index >= 1 }.sortedBy { it.index }
        .flatMap { sn -> sn.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.map { sn.index to it } }

    /** The chosen season's episodes, one per file: a multi-episode file is one tile that plays once. */
    private fun seasonEpisodes(): List<Pair<Int, Episode>> {
        val sn = series?.seasons?.firstOrNull { it.index == season } ?: return emptyList()
        return sn.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.distinctBy { it.id }.map { sn.index to it }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    /**
     * DEB-5578 (DEB-5576, FR-31 amended) — a redraw of a page that is already in [mount] keeps it still: the backdrop and logo are the
     * same elements (no new fade, no new `load`), the title is hidden at once when the logo has loaded (the head keeps its height),
     * and the body's scroll and the episode rail's place are put back. A season switch starts the rail again at the left.
     */
    fun render(mount: HTMLElement, fade: Boolean = false) {
        val previous = drawn?.takeIf { it.parentElement === mount }
        val scroll = mount.scrollTop
        val oldTrack = previous?.querySelector(".detail-episodes .rail-track") as? HTMLElement
        val oldRail = oldTrack?.parentElement as? HTMLElement
        val oldCast = previous?.querySelector(".detail-cast .rail-track") as? HTMLElement
        val oldRelated = previous?.querySelector(".detail-related .rail-track") as? HTMLElement
        val keepRail = previous != null && drawnSeason == season
        val keptBackdrop = if (previous != null) backdrop else null
        val keptLogo = if (previous != null) logo else null
        drawn = null
        backdrop = null
        logo = null
        titleNode = null
        if (previous == null) mount.textContent = ""
        val m = movie
        val s = series
        if (m == null && s == null) {
            mount.textContent = ""
            val f = failure
            if (f == null) {
                // DEB-4893 (FR-20) — a title's page that is still loading draws a skeleton, never a word.
                mount.appendChild(ScreenSkeleton.detail())
                return
            }
            // DEB-4660's failure lines stay.
            val placeholder = node("div", "shell-target shell-placeholder")
            placeholder.id = "shell-page-target"
            placeholder.setAttribute("data-row", "body")
            placeholder.setAttribute("tabindex", "-1")
            placeholder.textContent = when {
                f == 404 || f == 410 -> t("error.load.gone.title", ScreenLanguage.lang)
                f == 403 -> t("error.load.forbidden.title", ScreenLanguage.lang)
                else -> t("error.play.unreachable.title", ScreenLanguage.lang)
            }
            mount.appendChild(placeholder)
            return
        }
        val card = m?.card ?: s!!.card
        val page = node("div", "detail")
        if (fade) ScreenSkeleton.fadeIn(page)
        val backdropBox = keptBackdrop ?: card.backdropUrl?.let(::image)?.let { img ->
            node("div", "detail-backdrop").also { it.appendChild(ScreenSkeleton.fadeOnLoad(img)) }
        }
        backdropBox?.let { page.appendChild(it) }
        val head = node("div", "detail-head")
        val title = label("h1", "detail-title", card.title)
        titleNode = title
        val logoImage = keptLogo ?: (m?.logoUrl ?: s?.logoUrl)?.let(::image)?.let { img ->
            img.className = "detail-logo"
            // The listener reads the page's current title, so a logo kept across a redraw hides the new one.
            img.addEventListener("load", { logoLoaded = true; titleNode?.classList?.add("hidden") })
            img
        }
        if (keptLogo == null) logoLoaded = false
        logoImage?.let { head.appendChild(it) }
        if (logoImage != null && logoLoaded) title.classList.add("hidden")
        head.appendChild(title)
        val meta = node("div", "detail-meta")
        card.year?.let { meta.appendChild(label("span", "detail-fact", it.toString())) }
        if (m != null && m.runtime > 0) meta.appendChild(label("span", "detail-fact", t("up.runtime_min", ScreenLanguage.lang, m.runtime)))
        (m?.ratingBadge ?: s?.ratingBadge)?.let { meta.appendChild(badge(it)) }
        head.appendChild(meta)
        languageLine(m?.audioLanguages ?: s?.audioLanguages.orEmpty(), m?.subtitleLanguages ?: s?.subtitleLanguages.orEmpty())?.let { head.appendChild(it) }
        (m?.synopsis ?: s?.synopsis)?.takeIf { it.isNotBlank() }?.let { head.appendChild(label("p", "detail-overview", it)) }
        head.appendChild(actions(m, s))
        page.appendChild(head)
        if (s != null) page.appendChild(episodes(s))
        castRow()?.let { page.appendChild(it) }
        relatedRow()?.let { page.appendChild(it) }
        if (previous != null) mount.replaceChild(page, previous) else mount.appendChild(page)
        backdrop = backdropBox
        logo = logoImage
        drawn = page
        drawnSeason = season
        if (previous != null) {
            mount.scrollTop = scroll
            val track = page.querySelector(".detail-episodes .rail-track") as? HTMLElement
            if (keepRail && track != null && oldTrack != null) carryRail(oldTrack, track, oldRail?.scrollLeft ?: 0.0)
            // The cast row has no season: it stays where it was across every redraw.
            val castTrack = page.querySelector(".detail-cast .rail-track") as? HTMLElement
            if (castTrack != null && oldCast != null) carryRail(oldCast, castTrack, (oldCast.parentElement as? HTMLElement)?.scrollLeft ?: 0.0)
            val relatedTrack = page.querySelector(".detail-related .rail-track") as? HTMLElement
            if (relatedTrack != null && oldRelated != null) carryRail(oldRelated, relatedTrack, 0.0)
        }
    }

    private fun carryRail(old: HTMLElement, track: HTMLElement, scrollLeft: Double) {
        old.getAttribute("data-tx")?.let { track.setAttribute("data-tx", it) }
        track.style.transform = old.style.transform
        (track.parentElement as? HTMLElement)?.scrollLeft = scrollLeft
    }

    /**
     * DEB-4893 (DEB-4889, FR-19) — Android TV's `AudioSubtitleFlagLine`: `AUDIO` flags · `SUBTITLES` flags, counted by `:shared`'s
     * [countFlagStrip] (canonical codes, five flags a group, every other language an honest `+N`). Null — no line, no space —
     * when neither group has a language. The flags are PNGs packed in the widget as `flags/<cc>.png`, never fetched from the server.
     */
    private fun languageLine(audio: List<String>, subtitles: List<String>): HTMLElement? {
        val groups = listOf("fd.audio" to audio, "fd.subs" to subtitles).map { (key, languages) -> key to countFlagStrip(languages) }
            .filter { it.second.hasAnyLanguage }
        if (groups.isEmpty()) return null
        val line = node("div", "detail-langs")
        groups.forEachIndexed { index, (key, counts) ->
            if (index > 0) line.appendChild(label("span", "lang-dot", "·"))
            val group = node("span", "lang-group")
            group.appendChild(label("span", "lang-label", t(key, ScreenLanguage.lang)))
            counts.shown.forEach { cc ->
                val flag = node("img", "lang-flag")
                flag.setAttribute("src", "flags/$cc.png")
                flag.setAttribute("alt", "")
                group.appendChild(flag)
            }
            if (counts.extra > 0) group.appendChild(label("span", "lang-more", "+${counts.extra}"))
            line.appendChild(group)
        }
        return line
    }

    private fun actions(m: MovieDetail?, s: SeriesDetail?): HTMLElement {
        val row = node("div", "detail-actions")
        val state = overlay[id]
        if (m != null) {
            val played = state?.played == true
            val resumeMs = state?.resumeMs ?: 0L
            val resuming = resumeMs > 0 && !played
            val minutesLeft = if (resuming && m.runtime > 0) ((m.runtime.toLong() * 60_000L - resumeMs) / 60_000L).toInt() else 0
            val play = when {
                played -> t("action.play_again", ScreenLanguage.lang)
                // The metadata runtime can understate the file; a negative estimate is dropped rather than shown (as ravilo-ui does).
                resuming && minutesLeft > 0 -> t("action.resume_mins_left", ScreenLanguage.lang, mapOf("resume" to t("action.resume", ScreenLanguage.lang), "n" to minutesLeft.toString()))
                resuming -> t("action.resume", ScreenLanguage.lang)
                else -> t("action.play", ScreenLanguage.lang)
            }
            row.appendChild(button("detail-play", "play", play, primary = true))
            if (resuming) row.appendChild(button("detail-start-over", "start-over", t("detail.start_over", ScreenLanguage.lang)))
        } else if (s != null) {
            val primary = seriesPrimary(s, id, overlay)
            val code = primary?.let { episodeCode(it.season, it.episode.episodeNumber, it.lastPart) }
            val word = when (primary?.mode) {
                SeriesPrimaryMode.START_OVER -> t("detail.start_over", ScreenLanguage.lang)
                SeriesPrimaryMode.RESUME -> t("action.resume", ScreenLanguage.lang)
                else -> t("action.play", ScreenLanguage.lang)
            }
            row.appendChild(button("detail-play", "play", if (code != null) "$word · $code" else word, primary = true))
        }
        val list = t("nav.my_list", ScreenLanguage.lang)
        row.appendChild(button("detail-list", "list", (if (state?.favorite == true) "− " else "+ ") + list))
        if (m != null) {
            val watched = t("action.watched", ScreenLanguage.lang)
            row.appendChild(button("detail-watched", "watched", if (state?.played == true) "✓ $watched" else t("action.mark_watched", ScreenLanguage.lang)))
        }
        return row
    }

    private fun episodes(s: SeriesDetail): HTMLElement {
        val section = node("div", "detail-episodes")
        val seasons = s.seasons.filter { it.episodes.any { e -> e.playable() } }.sortedBy { it.index }
        if (seasons.size > 1) {
            val strip = node("div", "detail-seasons")
            for (sn in seasons) {
                val pill = button("detail-season-${sn.index}", "season", sn.name.ifBlank { "${t("detail.season", ScreenLanguage.lang)} ${sn.index}" }, row = "seasons")
                pill.setAttribute("data-season", sn.index.toString())
                pill.classList.toggle("cur", sn.index == season)
                strip.appendChild(pill)
            }
            section.appendChild(strip)
        }
        val rail = node("div", "detail-rail")
        val lane = node("div", "rail-track")
        rail.appendChild(lane)
        val list = seasonEpisodes()
        val groups = series?.seasons?.firstOrNull { it.index == season }?.episodes.orEmpty().filter { it.playable() }
        list.forEachIndexed { index, (seasonIndex, episode) ->
            val tile = node("div", "shell-target detail-ep")
            tile.id = "detail-ep-$index"
            tile.setAttribute("data-row", "episodes")
            tile.setAttribute("tabindex", "-1")
            tile.setAttribute("data-action", "episode")
            tile.setAttribute("data-episode-id", episode.id)
            val art = node("div", "detail-ep-art")
            episode.stillUrl?.let(::image)?.let { art.appendChild(it) }
            val state = overlay[episode.id]
            if (state?.played == true) art.appendChild(label("span", "detail-ep-check", "✓"))
            else if (state != null && state.resumeMs > 0) {
                val track = node("div", "tile-progress")
                val fill = node("div", "tile-progress-fill")
                fill.style.width = "${state.playedPct.coerceIn(0f, 1f) * 100f}%"
                track.appendChild(fill)
                art.appendChild(track)
            }
            tile.appendChild(art)
            val last = groups.filter { it.id == episode.id }.maxOfOrNull { it.episodeNumber } ?: episode.episodeNumber
            tile.appendChild(label("div", "detail-ep-code", episodeCode(seasonIndex, episode.episodeNumber, last)))
            tile.appendChild(label("div", "tile-title", episode.title))
            lane.appendChild(tile)
        }
        section.appendChild(rail)
        return section
    }

    private fun cast(): List<Person> = movie?.cast ?: series?.cast.orEmpty()

    /**
     * DEB-5631 (FR-7 amended) — Android TV's `CastCircle` in DOM: the round photo, the name, the role under it. A rail under FR-4, FR-39 and
     * FR-41; null with no cast, so there is no heading either. The photo's holder is a `.tile-art`, so FR-42's cap counts it like any
     * other tile's image; the initials sit under it and show for a person with no photo and for one whose image fails.
     */
    private fun castRow(): HTMLElement? {
        val people = cast()
        if (people.isEmpty()) return null
        val section = node("div", "detail-cast")
        section.appendChild(label("h2", "home-heading", t("detail.cast", ScreenLanguage.lang)))
        val rail = node("div", "detail-rail")
        val track = node("div", "rail-track")
        rail.appendChild(track)
        people.forEachIndexed { index, person ->
            val face = node("div", "shell-target detail-face")
            face.id = "detail-cast-$index"
            face.setAttribute("data-row", "cast")
            face.setAttribute("tabindex", "-1")
            face.setAttribute("data-action", "person")
            face.setAttribute("data-person", index.toString())
            val photo = node("div", "face-photo")
            photo.appendChild(label("span", "face-initials", initials(person.name)))
            val art = node("div", "tile-art face-art")
            person.imageUrl?.takeIf { it.isNotBlank() }?.let { art.setAttribute("data-art-url", if (it.startsWith("/")) serverUrl.trimEnd('/') + it else it) }
            photo.appendChild(art)
            face.appendChild(photo)
            face.appendChild(label("div", "face-name", person.name))
            person.role?.takeIf { it.isNotBlank() }?.let { face.appendChild(label("div", "face-role", it)) }
            track.appendChild(face)
        }
        section.appendChild(rail)
        return section
    }

    private fun related(): List<MediaCard> = movie?.related ?: series?.related.orEmpty()

    /**
     * DEB-5649 (DEB-5640, FR-51) — Android TV's *More like this* row: [ScreenHome.tile] at `TileShape.POSTER`, headed `section.related`, the
     * page's last row. A rail under FR-4, FR-39 and FR-41 whose tiles count toward FR-42's image slots; null with nothing related, so
     * there is no heading either. OK on a tile is [onTitle] (the router pushes that title's page; Back lands on the same tile).
     */
    private fun relatedRow(): HTMLElement? {
        val cards = related()
        val maker = tiles
        if (cards.isEmpty() || maker == null) return null
        val section = node("div", "detail-related")
        section.appendChild(label("h2", "home-heading", t("section.related", ScreenLanguage.lang)))
        val rail = node("div", "detail-rail")
        val track = node("div", "rail-track")
        rail.appendChild(track)
        cards.forEachIndexed { index, card ->
            val tile = maker.tile(card, "detail-related-$index", "related", TileShape.POSTER)
            tile.setAttribute("data-action", "related")
            tile.setAttribute("data-related", index.toString())
            track.appendChild(tile)
        }
        section.appendChild(rail)
        return section
    }

    /** The first letters of the first and last word of [name], upper-cased; the plain circle's face when there is no photo. */
    private fun initials(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        return when (words.size) {
            0 -> ""
            1 -> words[0].take(1)
            else -> words.first().take(1) + words.last().take(1)
        }.uppercase()
    }

    private fun badge(b: RatingBadge): HTMLElement {
        val chip = node("span", "detail-badge")
        chip.appendChild(label("span", "detail-badge-region", b.region))
        val code = label("span", "detail-badge-code", b.code)
        code.setAttribute("data-tier", b.tier.coerceIn(0, 4).toString())
        chip.appendChild(code)
        return chip
    }

    private fun button(elementId: String, action: String, text: String, primary: Boolean = false, row: String = "actions"): HTMLElement {
        val b = label("div", if (primary) "shell-target detail-btn primary" else "shell-target detail-btn", text)
        b.id = elementId
        b.setAttribute("data-row", row)
        b.setAttribute("data-action", action)
        b.setAttribute("tabindex", "-1")
        return b
    }

    private fun image(url: String): HTMLElement? {
        if (url.isBlank()) return null
        val img = node("img", "")
        img.setAttribute("src", if (url.startsWith("/")) serverUrl.trimEnd('/') + url else url)
        img.setAttribute("alt", "")
        return img
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private fun label(tag: String, className: String, value: String): HTMLElement =
        node(tag, className).also { it.textContent = value }
}
