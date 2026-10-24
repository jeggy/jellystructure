package dev.jellystructure.server.routes

import dev.jellystructure.audiobooks.AudiobooksScanner
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.filefix.FileFixService
import dev.jellystructure.filefix.FixKind
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.ProcessingInputs
import dev.jellystructure.media.ProcessingRuns
import dev.jellystructure.media.ProcessingViews
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.music.MusicScanner
import dev.jellystructure.nowEpochSec
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * DEB-1664 (FR-9) — the processing features and their state. Reading only: saving goes through `PUT /api/config`
 * (`[processing]` round-trips there), and the old scan routes stay where they were, mapped onto the features.
 */
fun Route.processingRoutes(configStore: ConfigStore, runs: ProcessingRuns?, mediaJobQueue: MediaJobQueue, scanTracker: ScanTracker) {
    get("/processing") {
        val cfg = configStore.current
        val status = scanTracker.status()
        val fileFix = FileFixService.current
        call.respond(
            ProcessingViews.build(
                cfg,
                ProcessingInputs(
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
                ),
            ),
        )
    }
}
