package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * R189 milestone 2 — full detail screen, dispatching to the movie or series shape server-side
 * (`GET /api/tv/movie/{id}` / `GET /api/tv/series/{id}`) based on [card.kind]. Both variants share:
 * backdrop/logo, synopsis, a top action row (Play/Resume, Mark watched, Trailer), a cast rail, and a
 * related row. Series additionally gets a season tab row + episode list.
 */
class DetailScreen(private val card: MediaCard) : Screen {
    private lateinit var container: HTMLElement
    private var movie: MovieDetail? = null
    private var series: SeriesDetail? = null

    // Regions, in vertical order (index-based, region contents vary by kind):
    // 0 = actions row, 1 = seasons (series only), 2 = episodes (series only) or cast (movie), 3 = cast (series) / related (movie), 4 = related (series)
    private var region = 0
    private var actionIndex = 0
    private var seasonIndex = 0
    private var episodeIndex = 0
    private var castIndex = 0
    private var relatedIndex = 0

    private val actions: List<String> get() {
        val list = mutableListOf("Play")
        if (movie?.trailer != null || series?.trailer != null) list += "Trailer"
        list += "Mark watched"
        list += if (WatchlistStore.contains(card.id)) "− My List" else "+ My List"
        return list
    }
    private val regionCount get() = if (card.kind == MediaKind.SERIES) 5 else 3

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "detail-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            if (card.kind == MediaKind.MOVIE) {
                runCatching { app.api.getMovie(card.id) }.onSuccess { movie = it; render() }.onFailure { renderError() }
            } else {
                runCatching { app.api.getSeries(card.id) }.onSuccess { series = it; render() }.onFailure { renderError() }
            }
        }
    }

    private fun render() {
        container.clear()
        val root = container.child("div", "detail-screen")
        val backdrop = card.backdropUrl ?: card.posterUrl
        if (backdrop != null) root.child("img", "backdrop") { setAttribute("src", backdrop) }

        val logo = movie?.logoUrl ?: series?.logoUrl
        if (logo != null) root.child("img", "detail-logo") { setAttribute("src", logo) }
        else root.child("h1", "title", card.title)

        val meta = mutableListOf<String>()
        card.year?.let { meta += it.toString() }
        movie?.runtime?.let { meta += "${it / 60}h ${it % 60}m" }
        series?.seasons?.size?.let { meta += "$it season" + if (it != 1) "s" else "" }
        card.genre?.let { meta += it }
        root.child("p", "meta", meta.joinToString(" · "))

        val synopsis = movie?.synopsis ?: series?.synopsis
        synopsis?.let { root.child("p", "synopsis", it) }

        val actionsRow = root.child("div", "actions-row")
        for ((i, a) in actions.withIndex()) {
            actionsRow.child("div", "button") { setAttribute("data-action-index", i.toString()); textContent = a }
        }

        series?.let { s ->
            val seasonsRow = root.child("div", "season-tabs")
            for ((i, season) in s.seasons.withIndex()) {
                seasonsRow.child("div", "season-tab") { setAttribute("data-season-index", i.toString()); textContent = season.name }
            }
            renderEpisodes(root, s.seasons.getOrNull(seasonIndex))
        }

        val cast = movie?.cast ?: series?.cast ?: emptyList()
        if (cast.isNotEmpty()) {
            root.child("div", "row-title", "Cast")
            val castRow = root.child("div", "row cast-row")
            for ((i, p) in cast.withIndex()) {
                castRow.child("div", "cast-tile") {
                    setAttribute("data-cast-index", i.toString())
                    val photoUrl = p.imageUrl
                    if (photoUrl != null) child("img", "cast-photo") { setAttribute("src", photoUrl) }
                    else child("div", "cast-photo cast-photo-empty")
                    child("div", "tile-title", p.name)
                    p.role?.let { child("div", "tile-sub", it) }
                }
            }
        }

        val related = movie?.related ?: series?.related ?: emptyList()
        if (related.isNotEmpty()) {
            root.child("div", "row-title", "More like this")
            val relatedRow = root.child("div", "row")
            for ((i, r) in related.withIndex()) {
                relatedRow.child("div", "tile") {
                    setAttribute("data-related-index", i.toString())
                    child("img", "poster") { setAttribute("src", r.posterUrl ?: "") }
                    child("div", "tile-title", r.title)
                }
            }
        }

        highlight()
    }

    private fun renderEpisodes(root: HTMLElement, season: Season?) {
        if (season == null) return
        val list = root.child("div", "episode-list")
        for ((i, ep) in season.episodes.withIndex()) {
            list.child("div", "episode-row") {
                setAttribute("data-episode-index", i.toString())
                val stillUrl = ep.stillUrl
                if (stillUrl != null) child("img", "episode-still") { setAttribute("src", stillUrl) }
                child("div", "episode-info") {
                    child("div", "episode-title", "${ep.episodeNumber}. ${ep.title}")
                    ep.overview?.let { child("div", "episode-overview", it.take(140)) }
                    if (ep.playback?.watched == true) child("div", "badge-watched-inline", "✓ Watched")
                }
            }
        }
    }

    private fun highlight() {
        fun toggle(selector: String, attr: String, idx: Int) {
            val els = container.querySelectorAll(selector)
            for (i in 0 until els.length) {
                val el = els.item(i) as HTMLElement
                val focused = region == attrRegion(attr) && i == idx
                el.classList.toggle("focused", focused)
                if (focused) el.scrollIntoView()
            }
        }
        toggle(".actions-row .button", "action", actionIndex)
        toggle(".season-tab", "season", seasonIndex)
        toggle(".episode-row", "episode", episodeIndex)
        toggle(".cast-tile", "cast", castIndex)
        toggle(".tile[data-related-index]", "related", relatedIndex)
    }

    // Region numbering differs for movie (0=actions,1=cast,2=related) vs series
    // (0=actions,1=seasons,2=episodes,3=cast,4=related).
    private fun attrRegion(attr: String): Int = when (attr) {
        "action" -> 0
        "season" -> 1
        "episode" -> if (card.kind == MediaKind.SERIES) 2 else -1
        "cast" -> if (card.kind == MediaKind.SERIES) 3 else 1
        "related" -> if (card.kind == MediaKind.SERIES) 4 else 2
        else -> -1
    }

    private fun renderError() {
        container.clear()
        container.child("div", "detail-screen") { child("p", "error", "Couldn't load this title.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (movie == null && series == null) return false
        when (ev.key) {
            "ArrowDown" -> { if (region < regionCount - 1) { region++; clampIndices(); highlight() }; return true }
            "ArrowUp" -> { if (region > 0) { region--; clampIndices(); highlight() } else return app.back(); return true }
            "ArrowRight" -> { moveHoriz(1); return true }
            "ArrowLeft" -> { moveHoriz(-1); return true }
            "Enter" -> { activate(); return true }
            "Back", "Escape" -> return app.back()
        }
        return false
    }

    private fun clampIndices() {
        when (regionKind()) {
            "action" -> actionIndex = actionIndex.coerceIn(0, (actions.size - 1).coerceAtLeast(0))
            "season" -> seasonIndex = seasonIndex.coerceIn(0, ((series?.seasons?.size ?: 1) - 1).coerceAtLeast(0))
            "episode" -> episodeIndex = 0
            "cast" -> castIndex = 0
            "related" -> relatedIndex = 0
        }
    }

    private fun regionKind(): String = when {
        region == 0 -> "action"
        card.kind == MediaKind.SERIES && region == 1 -> "season"
        card.kind == MediaKind.SERIES && region == 2 -> "episode"
        (card.kind == MediaKind.SERIES && region == 3) || (card.kind != MediaKind.SERIES && region == 1) -> "cast"
        else -> "related"
    }

    private fun moveHoriz(delta: Int) {
        when (regionKind()) {
            "action" -> actionIndex = (actionIndex + delta).coerceIn(0, actions.size - 1)
            "season" -> {
                val max = (series?.seasons?.size ?: 1) - 1
                val newIndex = (seasonIndex + delta).coerceIn(0, max)
                if (newIndex != seasonIndex) { seasonIndex = newIndex; episodeIndex = 0; render() }
                return
            }
            "cast" -> castIndex = (castIndex + delta).coerceIn(0, ((movie?.cast ?: series?.cast ?: emptyList()).size - 1).coerceAtLeast(0))
            "related" -> relatedIndex = (relatedIndex + delta).coerceIn(0, ((movie?.related ?: series?.related ?: emptyList()).size - 1).coerceAtLeast(0))
        }
        highlight()
    }

    private fun activate() {
        when (regionKind()) {
            "action" -> when (actions.getOrNull(actionIndex)) {
                "Play" -> startPlayback(resumeItemId())
                "Trailer" -> { /* Milestone 2 scope: trailer playback via AVPlay for an external YouTube/Vimeo
                                  URL isn't wired yet -- AVPlay targets Jellyfin-served streams; deferred. */ }
                "Mark watched" -> markWatched()
                "+ My List", "− My List" -> { WatchlistStore.toggle(card); render() }
            }
            "episode" -> {
                val season = series?.seasons?.getOrNull(seasonIndex) ?: return
                val ep = season.episodes.getOrNull(episodeIndex) ?: return
                startPlayback(ep.id)
            }

            "cast" -> {} // Milestone 2 scope: no person detail page yet.
            "related" -> {
                val r = (movie?.related ?: series?.related ?: emptyList()).getOrNull(relatedIndex) ?: return
                app.show(DetailScreen(r))
            }
        }
    }

    // The "Play" action's target item: the series' own resume episode (server-computed, same as every
    // other Ravilo client's "Play"/"Resume" button) when known, else the card's own id (movie, or a
    // series with no progress yet -- the server resolves that to season 1 episode 1).
    private fun resumeItemId(): String = series?.progress?.resumeEpisodeId ?: card.id

    // Flat, season-ordered episode list -- used both to find "next episode" for autoplay and to locate
    // the currently-playing episode's own title/segments by id.
    private val flatEpisodes get() = series?.seasons?.sortedBy { it.index }?.flatMap { it.episodes } ?: emptyList()

    private fun startPlayback(itemId: String) {
        val ep = flatEpisodes.firstOrNull { it.id == itemId }
        val segments = ep?.segments ?: movie?.segments ?: dev.jellystructure.shared.tv.TvSegmentMarkers()
        val displayTitle = ep?.let { "${card.title} — ${it.episodeNumber}. ${it.title}" } ?: card.title
        val nextEp = ep?.let { current -> flatEpisodes.getOrNull(flatEpisodes.indexOf(current) + 1) }
        app.scope.launch {
            runCatching { app.api.startPlayback(itemId, defaultCapabilities()) }
                .onSuccess { ticket ->
                    app.show(PlayerScreen(PlaybackContext(
                        itemId = itemId,
                        displayTitle = displayTitle,
                        segments = segments,
                        nextEpisode = nextEp,
                        parentCard = card,
                    ), ticket))
                }
                .onFailure { /* Milestone 2 scope: no toast system yet. */ }
        }
    }

    private fun markWatched() {
        app.scope.launch { runCatching { app.api.markPlayed(card.id, true) } }
    }
}
