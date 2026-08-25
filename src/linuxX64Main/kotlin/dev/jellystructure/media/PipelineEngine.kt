package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.imdb.ImdbClient
import dev.jellystructure.jobs.WsBroadcaster
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Phase 175 — what a [runPipeline] call is processing: the whole library (optionally scoped to one
 * Jellyfin library), or exactly one item (the realtime/webhook ingest path). The freshness/cooldown
 * filter only ever applies to [Library] — a [SingleItem] run exists specifically because that one item
 * just changed, so "is it due for a periodic recheck" doesn't apply (see FreshnessFilter.kt).
 */
sealed interface RunTarget {
    data class Library(val libraryJellyfinId: String? = null) : RunTarget
    data class SingleItem(val jellyfinId: String) : RunTarget
}

/** Bundles every collaborator [runPipeline] and [PipelineStepOps.dispatch] need, replacing the long
 *  positional parameter list `executePipeline`/`runScan` used to carry individually. */
class PipelineDeps(
    val store: MediaStore,
    val scanner: Scanner,
    val broadcaster: WsBroadcaster,
    val configStore: ConfigStore,
    val jellyfinClient: JellyfinClient,
    val scanDispatcher: CoroutineDispatcher,
    val artworkDownloader: ArtworkDownloader,
    val arrRescan: ArrRescanService,
    val sonarrEnrich: SonarrEnrichService? = null,
    val imdbClient: ImdbClient? = null,
    val mediaSegmentStore: MediaSegmentStore,
    val mediaJobQueue: MediaJobQueue,
)

/**
 * The one place every trigger resolves "what steps actually run" when Settings has no pipeline
 * configured (`cfg.scan.pipeline` empty/all-disabled) — reproduces today's plain-scan behavior
 * (`scan_files` + an unconditional inline TMDB match + gap-fill artwork when enabled), now expressed as
 * steps through the shared engine, so there's no capability regression for admins who never built a
 * pipeline in Settings.
 */
fun effectivePipeline(cfg: AppConfig): List<PipelineStep> =
    cfg.scan.pipeline.filter { it.enabled }.ifEmpty {
        buildList {
            add(PipelineStep(step = "scan_files"))
            add(PipelineStep(step = "pull_tmdb", scope = "all"))
            if (cfg.behavior.fetchImages) add(PipelineStep(step = "fetch_artwork"))
        }
    }
