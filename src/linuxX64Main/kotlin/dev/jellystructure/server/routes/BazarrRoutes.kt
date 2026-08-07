package dev.jellystructure.server.routes

import dev.jellystructure.bazarr.BazarrClient
import dev.jellystructure.bazarr.BazarrHistoryEvent
import dev.jellystructure.bazarr.BazarrLanguageProfile
import dev.jellystructure.bazarr.BazarrMissingSubtitle
import dev.jellystructure.bazarr.BazarrProviderStatus
import dev.jellystructure.bazarr.BazarrService
import dev.jellystructure.bazarr.BazarrSubtitleFile
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class BazarrOverview(
    val connected: Boolean,
    val wantedMovies: Int = 0,
    val wantedEpisodes: Int = 0,
    val providersHealthy: Int = 0,
    val providersTotal: Int = 0,
)

@Serializable
data class BazarrWantedRow(
    val kind: String, // "movie" | "episode"
    val title: String,
    val subtitle: String? = null, // episode number/title for series rows
    val radarrId: Int? = null,
    val sonarrSeriesId: Int? = null,
    val sonarrEpisodeId: Int? = null,
    val missing: List<BazarrMissingSubtitle> = emptyList(),
)

@Serializable
data class BazarrWantedPageDto(val items: List<BazarrWantedRow>, val total: Int)

@Serializable
data class BazarrLanguageRow(
    val language: String,
    val code2: String,
    val forced: Boolean = false,
    val hi: Boolean = false,
    val present: BazarrSubtitleFile? = null,
)

@Serializable
data class BazarrTitleState(
    val connected: Boolean,
    val matched: Boolean,
    val radarrId: Int? = null,
    val sonarrSeriesId: Int? = null,
    val sonarrEpisodeId: Int? = null,
    val languages: List<BazarrLanguageRow> = emptyList(),
)

@Serializable
private data class BazarrActionRequest(
    val language: String,
    val forced: Boolean = false,
    val hi: Boolean = false,
    val path: String = "",
    val episodeSeason: Int? = null,
    val episodeNumber: Int? = null,
)

/**
 * Phase 157 — Bazarr subtitle routes: the global overview (wanted/history/providers/profiles) and
 * the per-title subtitle surfaces (movie Tracks & subtitles / series season-scoped card). All reads
 * go live to Bazarr — nothing here is persisted, per the spec's "stores nothing" principle.
 */
fun Route.bazarrRoutes(mediaStore: MediaStore, service: BazarrService, client: BazarrClient) {
    fun cfgOrNull() = service.config()

    route("/bazarr") {
        get("/overview") {
            val cfg = cfgOrNull()
            if (cfg == null) { call.respond(BazarrOverview(connected = false)); return@get }
            val wantedM = client.wantedMovies(cfg.url, cfg.apiKey, 0, 1)
            val wantedE = client.wantedEpisodes(cfg.url, cfg.apiKey, 0, 1)
            val providers = client.providers(cfg.url, cfg.apiKey)
            call.respond(
                BazarrOverview(
                    connected = true,
                    wantedMovies = wantedM.total,
                    wantedEpisodes = wantedE.total,
                    providersHealthy = providers.count { it.status.equals("Good", ignoreCase = true) },
                    providersTotal = providers.size,
                )
            )
        }

        get("/wanted") {
            val cfg = cfgOrNull() ?: return@get call.respond(BazarrWantedPageDto(emptyList(), 0))
            val start = call.request.queryParameters["start"]?.toIntOrNull() ?: 0
            val length = call.request.queryParameters["length"]?.toIntOrNull() ?: 50
            val kind = call.request.queryParameters["kind"] // "movie" | "episode" | null = both
            val rows = mutableListOf<BazarrWantedRow>()
            var total = 0
            if (kind == null || kind == "movie") {
                val page = client.wantedMovies(cfg.url, cfg.apiKey, start, length)
                total += page.total
                rows += page.items.map { BazarrWantedRow("movie", it.title, radarrId = it.radarrId, missing = it.missingSubtitles) }
            }
            if (kind == null || kind == "episode") {
                val page = client.wantedEpisodes(cfg.url, cfg.apiKey, start, length)
                total += page.total
                rows += page.items.map {
                    BazarrWantedRow(
                        "episode", it.seriesTitle, subtitle = "${it.episodeNumber} · ${it.episodeTitle}",
                        sonarrSeriesId = it.sonarrSeriesId, sonarrEpisodeId = it.sonarrEpisodeId, missing = it.missingSubtitles,
                    )
                }
            }
            call.respond(BazarrWantedPageDto(rows, total))
        }

        post("/wanted/search-all") {
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val kind = call.request.queryParameters["kind"]
            var ok = true
            if (kind == null || kind == "movie") ok = ok && client.runTask(cfg.url, cfg.apiKey, "wanted_search_missing_subtitles_movies")
            if (kind == null || kind == "episode") ok = ok && client.runTask(cfg.url, cfg.apiKey, "wanted_search_missing_subtitles_series")
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        post("/scan") {
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            // "Run full Bazarr scan" fans out to both task ids — Bazarr splits movies/series indexing
            // into separate tasks, see the phase-157 addendum.
            val a = client.runTask(cfg.url, cfg.apiKey, "movies_full_scan_subtitles")
            val b = client.runTask(cfg.url, cfg.apiKey, "series_full_scan_subtitles")
            call.respond(if (a && b) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        get("/history") {
            val cfg = cfgOrNull() ?: return@get call.respond(emptyList<BazarrHistoryEvent>())
            val start = call.request.queryParameters["start"]?.toIntOrNull() ?: 0
            val length = call.request.queryParameters["length"]?.toIntOrNull() ?: 30
            val movies = client.movieHistory(cfg.url, cfg.apiKey, start = start, length = length)
            val episodes = client.episodeHistory(cfg.url, cfg.apiKey, start = start, length = length)
            call.respond((movies + episodes).sortedByDescending { it.timestamp })
        }

        get("/providers") {
            val cfg = cfgOrNull() ?: return@get call.respond(emptyList<BazarrProviderStatus>())
            call.respond(client.providers(cfg.url, cfg.apiKey))
        }

        get("/profiles") {
            val cfg = cfgOrNull() ?: return@get call.respond(emptyList<BazarrLanguageProfile>())
            call.respond(client.languageProfiles(cfg.url, cfg.apiKey))
        }
    }

    // Per-title surfaces, nested under /media/{id}/bazarr — matches the existing MediaRoutes.kt
    // convention of routing detail-page sub-resources under the item's own id.
    route("/media/{id}/bazarr") {
        get {
            val id = call.parameters["id"]!!
            val item = mediaStore.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull()
            if (cfg == null) { call.respond(BazarrTitleState(connected = false, matched = false)); return@get }
            when (item.kind) {
                MediaKind.MOVIE -> {
                    val movie = service.resolveMovie(item)
                    if (movie == null) { call.respond(BazarrTitleState(connected = true, matched = false)); return@get }
                    val profile = client.languageProfiles(cfg.url, cfg.apiKey).firstOrNull { it.profileId == movie.profileId }
                    val rows = profile?.items?.map { p ->
                        val present = movie.subtitles.firstOrNull { it.code2 == p.language }
                        BazarrLanguageRow(present?.name ?: p.language, p.language, p.forced == "True", p.hi == "True", present)
                    } ?: emptyList()
                    call.respond(BazarrTitleState(connected = true, matched = true, radarrId = movie.radarrId, languages = rows))
                }
                MediaKind.TV_SHOW -> {
                    val series = service.resolveSeries(item)
                    if (series == null) { call.respond(BazarrTitleState(connected = true, matched = false)); return@get }
                    call.respond(BazarrTitleState(connected = true, matched = true, sonarrSeriesId = series.sonarrSeriesId))
                }
            }
        }

        // Series: per-season episode rows (have/wanted languages), matched by season+episode number.
        get("/season/{n}") {
            val id = call.parameters["id"]!!
            val season = call.parameters["n"]!!.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = mediaStore.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val series = service.resolveSeries(item) ?: return@get call.respond(HttpStatusCode.NotFound)
            val bazarrEpisodes = client.episodesFor(cfg.url, cfg.apiKey, series.sonarrSeriesId)
                .filter { it.season == season }
            call.respond(bazarrEpisodes)
        }

        // Per-title history, for the title's History tab to merge at render time only — nothing here
        // is ever written to mediaHistoryQueries, per the phase-157 addendum's fix to FR-BZ1-1.
        // Movies only: a series would need one call per episode to aggregate, which doesn't scale.
        get("/history") {
            val id = call.parameters["id"]!!
            val item = mediaStore.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@get call.respond(emptyList<BazarrHistoryEvent>())
            if (item.kind != MediaKind.MOVIE) return@get call.respond(emptyList<BazarrHistoryEvent>())
            val movie = service.resolveMovie(item) ?: return@get call.respond(emptyList<BazarrHistoryEvent>())
            call.respond(client.movieHistory(cfg.url, cfg.apiKey, radarrId = movie.radarrId, length = 20))
        }

        post("/search") {
            val id = call.parameters["id"]!!
            val item = mediaStore.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = when (item.kind) {
                MediaKind.MOVIE -> service.resolveMovie(item)?.let { client.movieAction(cfg.url, cfg.apiKey, it.radarrId, "search-wanted") } ?: false
                MediaKind.TV_SHOW -> service.resolveSeries(item)?.let { client.seriesAction(cfg.url, cfg.apiKey, it.sonarrSeriesId, "search-wanted") } ?: false
            }
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        post("/sync") {
            val id = call.parameters["id"]!!
            val req = call.receive<BazarrActionRequest>()
            val item = mediaStore.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = when (item.kind) {
                MediaKind.MOVIE -> service.resolveMovie(item)?.let { client.syncSubtitle(cfg.url, cfg.apiKey, "movie", it.radarrId, req.language, req.path) } ?: false
                MediaKind.TV_SHOW -> {
                    val series = service.resolveSeries(item) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val season = req.episodeSeason ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val episode = item.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == req.episodeNumber }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val bazarrEp = service.resolveEpisode(series.sonarrSeriesId, episode) ?: return@post call.respond(HttpStatusCode.NotFound)
                    client.syncSubtitle(cfg.url, cfg.apiKey, "episode", bazarrEp.sonarrEpisodeId, req.language, req.path)
                }
            }
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        // "Upgrade" has no 1:1 Bazarr endpoint (upgrade_subtitles is a library-wide task, not
        // per-item) — composed here as re-search + auto-pick the top-scoring result, per the
        // phase-157 addendum.
        post("/upgrade") {
            val id = call.parameters["id"]!!
            val req = call.receive<BazarrActionRequest>()
            val item = mediaStore.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = when (item.kind) {
                MediaKind.MOVIE -> {
                    val movie = service.resolveMovie(item) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val best = client.searchProvidersMovie(cfg.url, cfg.apiKey, movie.radarrId)
                        .filter { it.language == req.language }.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    client.downloadProviderMovieSubtitle(cfg.url, cfg.apiKey, movie.radarrId, req.hi, req.forced, best.provider, best.subtitle)
                }
                MediaKind.TV_SHOW -> {
                    val series = service.resolveSeries(item) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val season = req.episodeSeason ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val episode = item.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == req.episodeNumber }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val bazarrEp = service.resolveEpisode(series.sonarrSeriesId, episode) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val best = client.searchProvidersEpisode(cfg.url, cfg.apiKey, bazarrEp.sonarrEpisodeId)
                        .filter { it.language == req.language }.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    client.downloadProviderEpisodeSubtitle(cfg.url, cfg.apiKey, bazarrEp.sonarrEpisodeId, req.hi, req.forced, best.provider, best.subtitle)
                }
            }
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        post("/download") {
            val id = call.parameters["id"]!!
            val req = call.receive<BazarrActionRequest>()
            val item = mediaStore.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = when (item.kind) {
                MediaKind.MOVIE -> service.resolveMovie(item)?.let { client.downloadMovieSubtitle(cfg.url, cfg.apiKey, it.radarrId, req.language, req.forced, req.hi) } ?: false
                MediaKind.TV_SHOW -> {
                    val series = service.resolveSeries(item) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val season = req.episodeSeason ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val episode = item.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == req.episodeNumber }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val bazarrEp = service.resolveEpisode(series.sonarrSeriesId, episode) ?: return@post call.respond(HttpStatusCode.NotFound)
                    client.downloadEpisodeSubtitle(cfg.url, cfg.apiKey, series.sonarrSeriesId, bazarrEp.sonarrEpisodeId, req.language, req.forced, req.hi)
                }
            }
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }

        delete {
            val id = call.parameters["id"]!!
            val req = call.receive<BazarrActionRequest>()
            val item = mediaStore.get(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
            val cfg = cfgOrNull() ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = when (item.kind) {
                MediaKind.MOVIE -> service.resolveMovie(item)?.let { client.deleteMovieSubtitle(cfg.url, cfg.apiKey, it.radarrId, req.language, req.forced, req.hi, req.path) } ?: false
                MediaKind.TV_SHOW -> {
                    val series = service.resolveSeries(item) ?: return@delete call.respond(HttpStatusCode.NotFound)
                    val season = req.episodeSeason ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val episode = item.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == req.episodeNumber }
                        ?: return@delete call.respond(HttpStatusCode.NotFound)
                    val bazarrEp = service.resolveEpisode(series.sonarrSeriesId, episode) ?: return@delete call.respond(HttpStatusCode.NotFound)
                    client.deleteEpisodeSubtitle(cfg.url, cfg.apiKey, series.sonarrSeriesId, bazarrEp.sonarrEpisodeId, req.language, req.forced, req.hi, req.path)
                }
            }
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadGateway)
        }
    }
}
