package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.PipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 265 (FR-265-5) — the steps a manual run executes, in the order it executes them: discovery first
 * (it runs whether or not it is listed), then the rest as configured. `runPipeline` orders its step plan
 * with [planOrder], and `GET /api/pipeline/plan` answers [pipelinePlan], so the pre-run dialog, Activity's
 * chips and the run itself cannot list different steps.
 */
fun planOrder(pipeline: List<PipelineStep>): List<PipelineStep> =
    listOf(pipeline.firstOrNull { it.step == "scan_files" } ?: PipelineStep(step = "scan_files")) +
        pipeline.filter { it.step != "scan_files" }

fun pipelinePlan(cfg: AppConfig): List<PipelineStep> = planOrder(effectivePipeline(cfg))

/** One row of `GET /api/pipeline/plan`. */
@Serializable
data class PipelinePlanStep(val step: String, val scope: String)

fun pipelinePlanRows(cfg: AppConfig): List<PipelinePlanStep> = pipelinePlan(cfg).map { PipelinePlanStep(it.step, it.scope) }

/** Phase 154 — the pre-run dialog's one-run skip. Defaulted so a bodyless call behaves as it always did. */
@Serializable
private data class PipelineRunRequest(val skipSteps: List<String> = emptyList())

private val skipJson = Json { ignoreUnknownKeys = true }

/**
 * Phase 265 (FR-265-4) — `POST /api/scan` and `POST /api/pipeline/run` parse their optional body here, so
 * they cannot drift. Read defensively: no body, an empty body or junk (curl, a test, an older frontend) is
 * no skip. `scan_files` is dropped: discovery always runs, so honouring it would be a lie (FR-PIPE1-4).
 * Never written to config.
 */
fun parseSkipSteps(body: String): Set<String> =
    runCatching { skipJson.decodeFromString<PipelineRunRequest>(body) }.getOrDefault(PipelineRunRequest())
        .skipSteps.filterNot { it == "scan_files" || it.isBlank() }.toSet()
