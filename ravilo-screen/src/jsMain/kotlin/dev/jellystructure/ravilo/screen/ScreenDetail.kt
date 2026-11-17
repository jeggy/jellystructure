package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.RatingBadge
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
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
) {
    private var movie: MovieDetail? = null
    private var series: SeriesDetail? = null
    private var overlay: Map<String, CardPlayState> = emptyMap()
    private var loading = false
    private var busy = false
    private var failure: Int? = null
    private var season = Int.MIN_VALUE

    val ready get() = movie != null || series != null

    /** The primary button: where focus lands when the page has drawn. */
    val primaryFocusId = "detail-play"

    fun load() {
        if (loading || ready || failure != null) return   // a failed page is not retried by every redraw; leaving it and coming back is
        loading = true
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
            context = ScreenPlayContext("episode", kicker = episodeCode(seasonIndex, episode.episodeNumber, lastPart), seriesName = s.card.title,
                logoUrl = s.logoUrl, logoInk = s.logoInk, segments = episode.segments,
                nextId = next?.second?.id, nextTitle = next?.second?.title, nextKicker = next?.let { episodeCode(it.first, it.second.episodeNumber) },
                following = after.map { ScreenFollowing(it.second.id, it.second.title, episodeCode(it.first, it.second.episodeNumber), it.second.segments) })))
    }

    private fun countedEpisodes(s: SeriesDetail) = s.seasons.filter { it.index >= 1 }.sortedBy { it.index }
        .flatMap { sn -> sn.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.map { sn.index to it } }

    /** The chosen season's episodes, one per file: a multi-episode file is one tile that plays once. */
    private fun seasonEpisodes(): List<Pair<Int, Episode>> {
        val sn = series?.seasons?.firstOrNull { it.index == season } ?: return emptyList()
        return sn.episodes.filter { it.playable() }.sortedBy { it.episodeNumber }.distinctBy { it.id }.map { sn.index to it }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    fun render(mount: HTMLElement) {
        mount.textContent = ""
        val m = movie
        val s = series
        if (m == null && s == null) {
            val placeholder = node("div", "shell-target shell-placeholder")
            placeholder.id = "shell-page-target"
            placeholder.setAttribute("data-row", "body")
            placeholder.setAttribute("tabindex", "-1")
            val f = failure
            placeholder.textContent = when {
                f == null -> fallbackTitle
                f == 404 || f == 410 -> t("error.load.gone.title", ScreenLanguage.lang)
                f == 403 -> t("error.load.forbidden.title", ScreenLanguage.lang)
                else -> t("error.play.unreachable.title", ScreenLanguage.lang)
            }
            mount.appendChild(placeholder)
            return
        }
        val card = m?.card ?: s!!.card
        val page = node("div", "detail")
        card.backdropUrl?.let(::image)?.let { img ->
            val backdrop = node("div", "detail-backdrop")
            backdrop.appendChild(img)
            page.appendChild(backdrop)
        }
        val head = node("div", "detail-head")
        val title = label("h1", "detail-title", card.title)
        (m?.logoUrl ?: s?.logoUrl)?.let(::image)?.let { logo ->
            logo.className = "detail-logo"
            logo.addEventListener("load", { title.classList.add("hidden") })
            head.appendChild(logo)
        }
        head.appendChild(title)
        val meta = node("div", "detail-meta")
        card.year?.let { meta.appendChild(label("span", "detail-fact", it.toString())) }
        if (m != null && m.runtime > 0) meta.appendChild(label("span", "detail-fact", t("up.runtime_min", ScreenLanguage.lang, m.runtime)))
        (m?.ratingBadge ?: s?.ratingBadge)?.let { meta.appendChild(badge(it)) }
        head.appendChild(meta)
        (m?.synopsis ?: s?.synopsis)?.takeIf { it.isNotBlank() }?.let { head.appendChild(label("p", "detail-overview", it)) }
        head.appendChild(actions(m, s))
        page.appendChild(head)
        if (s != null) page.appendChild(episodes(s))
        mount.appendChild(page)
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
            rail.appendChild(tile)
        }
        section.appendChild(rail)
        return section
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
