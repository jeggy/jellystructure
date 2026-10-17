package dev.jellystructure.support

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AiRequests
import dev.jellystructure.config.AiJobConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.log.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 321 — `AI Agent`, the support job's half that is not the rail. It builds a ticket's request, applies the answer
 * under FR-321-5's rules, and keeps the transcript. It talks to the ticket only through [SupportService]'s `agent*` methods,
 * which are the only writes `AI Agent` has: a reply, a forward and a flag. Never a state change, never a delete.
 */
class SupportAgent(
    private val service: SupportService,
    private val config: () -> AppConfig,
    private val clock: () -> Long = { dev.jellystructure.nowEpochSec() },
) : AiJobs.SupportLane {
    /** What a batch item remembers about a request: the ticket, who owns it (never sent), and how far the thread went. */
    @Serializable
    private data class Ctx(val t: Long, val o: String, val upTo: Long, val lang: String)

    private val json = Json { ignoreUnknownKeys = true }
    /** FR-321-8 — requests built for each viewer in the pass in progress, on top of what the ledger already holds. */
    private val passCounts = HashMap<String, Int>()

    override fun ticketOf(ctx: String): Long? = runCatching { json.decodeFromString(Ctx.serializer(), ctx).t }.getOrNull()

    override fun beginPass() = passCounts.clear()

    override fun build(subject: String, customId: String, cfg: AiJobConfig): AiJobs.BuiltSupport? {
        val number = subject.toLongOrNull() ?: return null
        val tk = service.agentTicket(number) ?: return null
        // A ticket a person has taken, flagged, or that nothing is owed on is nothing to ask. The viewer's indicator
        // turns off with the queue row, so tell them.
        if (tk.holder != SupportService.HOLDER_AI_AGENT || tk.flagged() || tk.owes != SupportService.OWES_AI_AGENT) {
            service.agentChanged(number)
            return null
        }
        limitReason(tk)?.let { why ->
            forwardWith(tk, why)
            return null
        }
        passCounts[tk.ownerId] = (passCounts[tk.ownerId] ?: 0) + 1
        val sup = config().ai.support
        val user = SupportAi.userBlock(tk.language, tk.ticketClass, SupportAi.threadBlock(tk.entries, sup.maxInputChars))
        val request = SupportAi.request(customId, cfg.model, cfg.effort, user)
        return AiJobs.BuiltSupport(request, json.encodeToString(Ctx.serializer(), Ctx(tk.number, tk.ownerId, tk.lastViewerMessageId, tk.language)))
    }

    /** FR-321-8 — the per-viewer and per-ticket bounds. A ticket past either forwards, with the reason in words. */
    private fun limitReason(tk: SupportService.AgentTicket): String? {
        val sup = config().ai.support
        if (service.agentRepliesSent(tk.number) >= sup.turnsPerTicket) return "this ticket has had its ${sup.turnsPerTicket} AI replies"
        val used = service.agentRequestsSince(tk.ownerId, clock() - SupportService.DAY) + (passCounts[tk.ownerId] ?: 0)
        if (used >= sup.requestsPerViewerPerDay) return "daily AI limit for this viewer"
        return null
    }

    private fun SupportService.AgentTicket.flagged() = flagged

    private fun forwardWith(tk: SupportService.AgentTicket, why: String) {
        val note = "AI Agent forwarded this without asking the model: $why."
        service.agentForward(tk.number, tk.lastViewerMessageId, SupportService.Classification(null, null),
            SupportService.HOLDER_ADMIN, SupportAi.forwardNotice(tk.language), note)
    }

    override fun heldByLimit(subject: String, why: String) {
        val number = subject.toLongOrNull() ?: return
        val tk = service.agentTicket(number) ?: return
        forwardWith(tk, why)
    }

    override fun sent(ctx: String, batchId: String, customId: String, model: String, sent: String) {
        val c = runCatching { json.decodeFromString(Ctx.serializer(), ctx) }.getOrNull() ?: return
        service.agentTurnSent(c.t, c.o, batchId, customId, model, sent)
    }

    override suspend fun answered(ctx: String, batchId: String, r: AiRequests.Result, model: String, cost: Long): AiJobs.SupportVerdict {
        val c = runCatching { json.decodeFromString(Ctx.serializer(), ctx) }.getOrNull()
            ?: return AiJobs.SupportVerdict("DISCARDED: unreadable context", null, false)
        suspend fun done(verdict: String, readable: String?, used: Boolean): AiJobs.SupportVerdict {
            service.agentTurnAnswered(batchId, r.customId, r.text ?: r.error, verdict, r.usage?.input ?: 0L, r.usage?.output ?: 0L, cost)
            Logger.info("Support: ticket #${c.t} — ${verdict.substringBefore(" |").substringBefore(':')}", "support")
            return AiJobs.SupportVerdict(verdict, readable, used)
        }
        suspend fun discarded(applied: SupportService.AgentApplied): AiJobs.SupportVerdict? = when (applied) {
            SupportService.AgentApplied.APPLIED -> null
            SupportService.AgentApplied.GONE -> done("DISCARDED: the ticket was deleted", null, false)
            SupportService.AgentApplied.HANDLED -> done("DISCARDED: the ticket was forwarded, flagged or answered by a person meanwhile", null, false)
            SupportService.AgentApplied.SUPERSEDED -> {
                // The viewer wrote again while this was out: the request queued for that message answers both.
                if (service.agentTicket(c.t) != null) service.askAgentAgain(c.t)
                done("DISCARDED: the viewer wrote again — the newer request answers it", null, false)
            }
        }

        // FR-321-5 rule 6 — the job must still be active. If it is not, the forward already ran (or runs next); a late
        // answer is discarded, and its cost is entered in the ledger by the rail.
        if (!service.agentActive) return done("DISCARDED: AI Agent was switched off while the request was out", null, false)

        val (answer, why) = SupportAi.parse(r)
        val none = SupportService.Classification(null, null)
        if (answer == null) {
            val applied = service.agentForward(c.t, c.upTo, none, SupportService.HOLDER_ADMIN, SupportAi.forwardNotice(c.lang),
                "AI Agent forwarded this: $why.")
            return discarded(applied) ?: done("FORWARDED: $why", null, true)
        }

        val ruling = SupportAi.rule(answer, c.lang, service.hervorEffective)
        // DEB-1500 (FR-13) — the titles ride the same pass. ABUSE stores neither (agentFlag takes the summary alone), so it
        // has nothing to report dropped either.
        val titles = SupportAi.titles(answer, c.lang)
        val cl = SupportService.Classification(answer.ticketClass, answer.summary, titles.title, titles.englishTitle)
        val dropped = titles.dropped?.takeIf { ruling.kind != SupportAi.Kind.ABUSE }?.let { " | TITLE_DROPPED: $it" }.orEmpty()
        val readable = "${answer.ticketClass} · ${answer.summary}"
        return when (ruling.kind) {
            SupportAi.Kind.SEND -> {
                val applied = service.agentSend(c.t, c.upTo, cl, answer.reply)
                discarded(applied) ?: done("SENT$dropped", readable, true)
            }
            SupportAi.Kind.ABUSE -> {
                val applied = service.agentFlag(c.t, c.upTo, cl, "AI Agent flagged this as abuse and sent nothing. Summary: ${answer.summary}")
                discarded(applied) ?: done("FLAGGED", readable, true)
            }
            SupportAi.Kind.REQUEST -> {
                val applied = service.agentForward(c.t, c.upTo, cl, SupportService.HOLDER_ADMIN, SupportAi.requestLogged(c.lang, c.t), adminNote(ruling))
                discarded(applied) ?: done("FORWARDED: ${ruling.reason}$dropped", readable, true)
            }
            SupportAi.Kind.FORWARD -> {
                val applied = service.agentForward(c.t, c.upTo, cl, ruling.to, SupportAi.forwardNotice(c.lang), adminNote(ruling))
                discarded(applied) ?: done("FORWARDED: ${ruling.reason}$dropped", readable, true)
            }
        }
    }

    /** FR-321-6 — the English summary, the reproduction and the reason, for whoever holds the ticket next. */
    private fun adminNote(ruling: SupportAi.Ruling): String = buildString {
        append("AI Agent forwarded this: ${ruling.reason}.")
        append("\nSummary: ${ruling.answer.summary}")
        ruling.answer.reproduction?.let { append("\nReproduction: $it") }
    }

    override fun lost(ctx: String) {
        val c = runCatching { json.decodeFromString(Ctx.serializer(), ctx) }.getOrNull() ?: return
        val tk = service.agentTicket(c.t) ?: return
        forwardWith(tk, "the request was lost at Anthropic")
    }

    override fun settled(tickets: List<Long>) = tickets.distinct().forEach { service.agentChanged(it) }

    override fun reconcile(): List<Long> =
        service.agentForwardAllHeld("AI Agent was switched off (AI, the key, the support job or the support lane): forwarded to an admin.")
}
