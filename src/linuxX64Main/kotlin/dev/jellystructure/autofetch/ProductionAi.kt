package dev.jellystructure.autofetch

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AiPricing
import dev.jellystructure.ai.AiRequests
import dev.jellystructure.ai.AnthropicClient
import dev.jellystructure.config.ConfigStore

/**
 * DEB-2478 (DEB-2474, FR-5.4) — the investigation's side of the AI rail: the job's card (its model, effort and monthly limit), the key,
 * the ledger and the *Last run* line all belong to [AiJobs]; the synchronous call is [AnthropicClient.message].
 */
class AiJobsInvestigationAi(
    private val configStore: ConfigStore,
    private val jobs: () -> AiJobs? = { AiJobs.current },
    private val client: AnthropicClient = AnthropicClient(),
) : InvestigationAi {
    override fun settings(): InvestigationSettings? =
        jobs()?.autofetchSettings()?.let { InvestigationSettings(it.model, it.effort, (it.monthlyLimitUsd * 1_000_000.0).toLong(), it.turns()) }

    /** FR-1.2 — the words the disabled button carries. */
    override fun offReason(): String {
        val ai = configStore.current.ai
        return when {
            !ai.enabled -> "AI is switched off in Settings ▸ AI"
            ai.apiKey.isBlank() -> "There is no AI key — Settings ▸ AI"
            !ai.autofetch.enabled -> "Auto-fetch is switched off in Settings ▸ AI"
            else -> "AI jobs are not running"
        }
    }

    override fun spentMicroUsd(): Long = jobs()?.spentThisMonth(AiRequests.AUTOFETCH_JOB) ?: 0L

    override suspend fun send(requestJson: String): AnthropicClient.Message = client.message(configStore.current.ai.apiKey, requestJson)

    override suspend fun record(model: String, usage: AiPricing.Usage) { jobs()?.recordAutofetchTurn(model, usage) }

    override fun noteRun(line: String) { jobs()?.noteAutofetchRun(line) }
}
