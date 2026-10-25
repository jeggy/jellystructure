package dev.jellystructure.server.routes

import dev.jellystructure.audiobooks.AudiobooksScanner
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.filefix.FileFixService
import dev.jellystructure.filefix.FixKind
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.ScanCounts
import dev.jellystructure.media.ScanDialogs
import dev.jellystructure.media.ScanScope
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import dev.jellystructure.media.ProcessingInputs
import dev.jellystructure.media.ProcessingRuns
import dev.jellystructure.media.ProcessingViews
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.music.MusicScanner
import dev.jellystructure.nowEpochSec
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Everything `GET /api/processing` and the Scan dialog read besides the configuration (FR-12.1: gathered here, so the views are pure functions). */
internal suspend fun processingInputs(configStore: ConfigStore, runs: ProcessingRuns?, mediaJobQueue: MediaJobQueue, scanTracker: ScanTracker): ProcessingInputs {
    val cfg = configStore.current
    val status = scanTracker.status()
    val fileFix = FileFixService.current
    return ProcessingInputs(
        nowSec = nowEpochSec(),
        lastRan = runs?.all() ?: emptyMap(),
        queuedJobTypes = mediaJobQueue.queued().map { it.type },
        directPlay = FixKind.entries.map { it.label to (fileFix?.enabled(it) == true) },
        lastBuilt = buildMap {
            dev.jellystructure.tv.RecommendationService.current?.lastBuiltAt()?.let { put(RecommendationsStep.STEP, it) }
            dev.jellystructure.suggestions.SuggestionService.current?.lastBuiltAt()?.let { put(SuggestionsStep.STEP, it) }
        },
        activeStep = status.activeStep,
        runActive = status.status == "RUNNING",
        deferred = status.deferred,
        musicLibraries = MusicScanner.musicLibraries(cfg).isNotEmpty(),
        audiobookLibraries = AudiobooksScanner.audiobookLibraries(cfg).isNotEmpty(),
        playingDevices = dev.jellystructure.tv.activePlaybackDeviceNames(),
    )
}

/** FR-5.6 — the library a scoped scan was opened for, or null when [id] names none. */
internal fun scanScopeOf(cfg: dev.jellystructure.config.AppConfig, id: String): ScanScope? =
    cfg.libraries.firstOrNull { it.jellyfinId == id }?.let { ScanScope(id, it.name.ifBlank { "this library" }, it.collectionType) }

/**
 * DEB-1664 (FR-9) — the processing features and their state, and the Scan dialog's model. Saving goes through `PUT /api/config`
 * (`[processing]` round-trips there); a scan is started by `POST /api/processing/scan` (in the media routes, beside the scan it
 * shares its run with), and the old scan routes stay where they were, mapped onto the features.
 */
fun Route.processingRoutes(configStore: ConfigStore, runs: ProcessingRuns?, mediaJobQueue: MediaJobQueue, scanTracker: ScanTracker, store: MediaStore) {
    get("/processing") {
        call.respond(ProcessingViews.build(configStore.current, processingInputs(configStore, runs, mediaJobQueue, scanTracker)))
    }

    // FR-5 — the two-pane dialog: every sentence, reason and marker, for everything or for one library (`?library=<jellyfin id>`).
    get("/processing/scan") {
        val cfg = configStore.current
        val libraryId = call.request.queryParameters["library"]?.takeIf { it.isNotBlank() }
        val scope = libraryId?.let { scanScopeOf(cfg, it) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such library")) }
        val counts = if (scope == null) ScanCounts(store.movieCount(), store.tvShowCount(), store.tvEpisodeCount())
        else store.allItems().filter { it.libraryId == scope.libraryId }.let { items ->
            ScanCounts(items.count { it.kind == MediaKind.MOVIE }, items.count { it.kind == MediaKind.TV_SHOW }, items.sumOf { it.episodes.size })
        }
        val view = ProcessingViews.build(cfg, processingInputs(configStore, runs, mediaJobQueue, scanTracker))
        call.respond(ScanDialogs.model(cfg, view, scope, counts))
    }
}
