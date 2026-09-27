package dev.jellystructure.ai

import kotlin.math.ceil

/**
 * Phase 270 (FR-270-2/5/6, dev review item 6) — what a request costs, in micro-dollars, from a dated table.
 *
 * Standard per-million-token rates, confirmed on [AS_OF] against Anthropic's model reference as bundled with
 * Claude Code: Opus 5 $5 / $25, Sonnet 5 $2 / $10, Haiku 4.5 $1 / $5. Every job goes through the Message
 * Batches API, which bills every token at [BATCH] of the standard rate; a 5-minute cache write costs
 * [CACHE_WRITE] × input and a cache read [CACHE_READ] × input. Re-check the table before a release and move
 * [AS_OF] with it: the AI tab shows the date, so a stale table is visible rather than silent.
 */
object AiPricing {
    const val AS_OF = "2026-09-27"
    const val BATCH = 0.5
    const val CACHE_WRITE = 1.25
    const val CACHE_READ = 0.1

    data class Model(val id: String, val label: String, val inputPerMTok: Double, val outputPerMTok: Double, val effort: Boolean)

    /** Offered on the AI tab, in this order; the first is the default (FR-270-2). */
    val MODELS: List<Model> = listOf(
        Model("claude-opus-5", "Claude Opus 5", 5.0, 25.0, effort = true),
        Model("claude-sonnet-5", "Claude Sonnet 5", 2.0, 10.0, effort = true),
        // Haiku 4.5 rejects the effort parameter: it is left out of the request and the tab hides the control.
        Model("claude-haiku-4-5", "Claude Haiku 4.5", 1.0, 5.0, effort = false),
    )
    val DEFAULT_MODEL: String = MODELS.first().id

    fun model(id: String): Model = MODELS.firstOrNull { it.id == id } ?: MODELS.first()

    data class Usage(val input: Long, val output: Long, val cacheWrite: Long = 0, val cacheRead: Long = 0)

    /** A batch request's cost from the API's own `usage`, in micro-dollars, rounded up. */
    fun costMicroUsd(modelId: String, u: Usage): Long {
        val m = model(modelId)
        val dollarsPerTokenIn = m.inputPerMTok / 1_000_000.0 * BATCH
        val dollarsPerTokenOut = m.outputPerMTok / 1_000_000.0 * BATCH
        val usd = u.input * dollarsPerTokenIn +
            u.cacheWrite * dollarsPerTokenIn * CACHE_WRITE +
            u.cacheRead * dollarsPerTokenIn * CACHE_READ +
            u.output * dollarsPerTokenOut
        // Rounded up to the micro-dollar; the epsilon keeps a binary-fraction residue (…00001) from adding one.
        return ceil(usd * 1_000_000.0 - 1e-6).toLong()
    }

    /**
     * FR-270-6 (dev review item 7) — a batch's worst case before it is sent, with no extra call: every input
     * character counted as a third of a token (these prompts run ~4 characters a token, so this errs high),
     * written to cache at the write rate, and every request spending its whole `max_tokens` on output.
     */
    fun worstCaseMicroUsd(modelId: String, inputChars: Long, requests: Int, maxTokens: Int): Long {
        val inputTokens = (inputChars + 2) / 3
        return costMicroUsd(modelId, Usage(input = 0, output = requests.toLong() * maxTokens, cacheWrite = inputTokens))
    }

    /** FR-270-6 — true when this batch could take the month past the job's limit: the batch is not sent. */
    fun wouldPassLimit(spentMicroUsd: Long, worstCaseMicroUsd: Long, limitUsd: Double): Boolean =
        spentMicroUsd + worstCaseMicroUsd > (limitUsd * 1_000_000.0).toLong()

    fun usd(micro: Long): String {
        val cents = (micro + 5_000) / 10_000
        return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
    }
}
