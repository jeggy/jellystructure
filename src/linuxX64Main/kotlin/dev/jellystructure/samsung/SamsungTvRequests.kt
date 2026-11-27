package dev.jellystructure.samsung

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.shared.tv.*
import kotlin.time.Clock
import kotlin.time.Instant

/** DEB-4842 (FR-1/2/3) — guarded, persisted request transitions. */
class SamsungTvRequests(private val db: JellystructureDb, private val clock: () -> Long = { Clock.System.now().epochSeconds }) {
    private val q = db.samsungTvRequestsQueries

    /** DEB-5909 (FR-9) — the admin `/ws` broadcast; wired in `Main`. Called only after a write that changed a row has committed. */
    var pushAdmin: (JobEvent) -> Unit = {}

    fun requestedCount(): Int = all().count { it.state == "REQUESTED" }
    private fun changed() = pushAdmin(JobEvent.SamsungTvRequestsChanged(requestedCount()))

    data class Row(
        val id: Long, val accountId: String, val duid: String, val model: String,
        val tizenVersion: String, val name: String, val state: String, val createdAt: Long,
        val decidedAt: Long?, val decidedBy: String?, val decisionNote: String?,
        val readyFromRelease: String?, val readyWgtUrl: String?,
    ) {
        fun viewer() = SamsungTvRequest(id, duid, model, tizenVersion, name, state, createdAt, decidedAt, decisionNote, readyFromRelease, readyWgtUrl)
        fun admin(requesterName: String) = SamsungTvAdminRequest(id, duid, model, tizenVersion, name, state, createdAt, decidedAt, decisionNote, readyFromRelease, readyWgtUrl, requesterName, accountId, decidedBy)
    }

    private fun row(id: Long): Row? = q.requestById(id).executeAsOneOrNull()?.let {
        Row(it.id, it.account_id, it.duid, it.model, it.tizen_version, it.name, it.state,
            it.created_at, it.decided_at, it.decided_by, it.decision_note, it.ready_from_release, it.ready_wgt_url)
    }
    fun get(id: Long): Row? = row(id)
    fun mine(accountId: String): List<Row> = q.requestsForAccount(accountId).executeAsList().map {
        Row(it.id, it.account_id, it.duid, it.model, it.tizen_version, it.name, it.state,
            it.created_at, it.decided_at, it.decided_by, it.decision_note, it.ready_from_release, it.ready_wgt_url)
    }
    fun all(): List<Row> = q.allRequests().executeAsList().map {
        Row(it.id, it.account_id, it.duid, it.model, it.tizen_version, it.name, it.state,
            it.created_at, it.decided_at, it.decided_by, it.decision_note, it.ready_from_release, it.ready_wgt_url)
    }

    sealed interface CreateResult {
        data class Saved(val row: Row, val created: Boolean) : CreateResult
        data object Invalid : CreateResult
        data object AtCap : CreateResult
    }
    fun create(accountId: String, input: SamsungTvRequestCreate): CreateResult {
        val duid = input.duid.trim().uppercase()
        if (!duid.matches(Regex("[A-Z0-9]{8,32}")) ||
            !validText(input.model, 120) || !validText(input.name, 120) || !validText(input.tizenVersion, 32)) return CreateResult.Invalid
        val model = input.model.trim(); val name = input.name.trim(); val version = input.tizenVersion.trim()
        val result = db.transactionWithResult {
            val existing = q.requestForAccountAndDuid(accountId, duid).executeAsOneOrNull()
            if (existing != null && existing.state !in setOf("WITHDRAWN", "REJECTED")) {
                return@transactionWithResult CreateResult.Saved(row(existing.id)!!, false)
            }
            if (q.activeCount(accountId).executeAsOne() >= 10L) return@transactionWithResult CreateResult.AtCap
            if (existing == null) {
                q.insertRequest(accountId, duid, model, version, name, clock())
                CreateResult.Saved(row(q.lastInsertId().executeAsOne())!!, true)
            } else {
                q.reopenRequest(model, version, name, clock(), existing.id)
                if (q.changes().executeAsOne() != 1L) return@transactionWithResult CreateResult.Invalid
                CreateResult.Saved(row(existing.id)!!, true)
            }
        }
        if (result is CreateResult.Saved && result.created) changed()
        return result
    }

    enum class Transition { SAVED, NOT_FOUND, CONFLICT, INVALID }
    data class TransitionResult(val kind: Transition, val row: Row? = null)

    fun withdraw(accountId: String, id: Long): TransitionResult {
        var wrote = false
        val result = db.transactionWithResult {
            val before = row(id) ?: return@transactionWithResult TransitionResult(Transition.NOT_FOUND)
            if (before.accountId != accountId) return@transactionWithResult TransitionResult(Transition.NOT_FOUND)
            if (before.state in setOf("APPROVED", "READY")) return@transactionWithResult TransitionResult(Transition.CONFLICT)
            if (before.state == "WITHDRAWN" || before.state == "REJECTED") return@transactionWithResult TransitionResult(Transition.SAVED, before)
            q.withdrawRequest(id, accountId)
            if (q.changes().executeAsOne() != 1L) return@transactionWithResult TransitionResult(Transition.CONFLICT)
            wrote = true
            TransitionResult(Transition.SAVED, row(id))
        }
        if (wrote) changed()
        return result
    }

    fun decide(id: Long, decision: SamsungTvDecision, by: String, verified: Triple<String, String, Set<String>>? = null): TransitionResult {
        val state = when (decision.decision) { "APPROVE" -> "APPROVED"; "REJECT" -> "REJECTED"; else -> return TransitionResult(Transition.INVALID) }
        if (decision.note?.let { !validNote(it) } == true) return TransitionResult(Transition.INVALID)
        val result = db.transactionWithResult {
            if (row(id) == null) return@transactionWithResult TransitionResult(Transition.NOT_FOUND)
            q.decideRequest(state, clock(), by, decision.note?.trim()?.ifEmpty { null }, id)
            if (q.changes().executeAsOne() != 1L) return@transactionWithResult TransitionResult(Transition.CONFLICT)
            val decided = row(id)
            if (state == "APPROVED" && verified != null && decided != null && decided.duid in verified.third) {
                q.markReady(verified.first, verified.second, id)
                q.changes().executeAsOne()
            }
            TransitionResult(Transition.SAVED, row(id))
        }
        if (result.kind == Transition.SAVED) changed()
        return result
    }

    /** One guarded write per row; an admin decision can never be overwritten by a stale poll. Publishes once if it moved a row. */
    fun reconcile(tag: String, wgtUrl: String, covered: Set<String>) {
        val moved = db.transactionWithResult {
            var moved = 0
            for (current in all()) {
                if (current.state !in setOf("APPROVED", "READY")) continue
                if (current.duid in covered) {
                    if (current.state != "READY" || current.readyFromRelease != tag || current.readyWgtUrl != wgtUrl) {
                        q.markReady(tag, wgtUrl, current.id)
                        if (q.changes().executeAsOne() > 0L) moved++
                    }
                } else if (current.state == "READY") {
                    q.markApproved(current.id)
                    if (q.changes().executeAsOne() > 0L) moved++
                }
            }
            moved
        }
        if (moved > 0) changed()
    }

    private fun validText(value: String, max: Int): Boolean = value.length in 1..max && value.trim().isNotEmpty() && value.none { it.code < 32 || it.code == 127 }
    private fun validNote(value: String): Boolean = value.length <= 500 && value.none { it.code < 32 || it.code == 127 }
}

fun samsungCertificateStatus(now: Long = Clock.System.now().epochSeconds): SamsungTvCertificateStatus {
    fun days(date: String): Int = ((Instant.parse("${date}T00:00:00Z").epochSeconds - now) / 86_400L).toInt()
    return SamsungTvCertificateStatus("2027-09-18", days("2027-09-18"), "2027-11-16", days("2027-11-16"))
}
