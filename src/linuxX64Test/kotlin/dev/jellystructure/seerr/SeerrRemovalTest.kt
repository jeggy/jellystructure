package dev.jellystructure.seerr

import dev.jellystructure.OutboundHttp
import dev.jellystructure.seerr.SeerrCall.Decline
import dev.jellystructure.seerr.SeerrCall.DeleteMedia
import dev.jellystructure.seerr.SeerrCall.DeleteRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 282 (FR-282-1) — the removal plan: decline only what is pending. */
class SeerrRemovalCallsTest {
    @Test
    fun onlyAPendingRequestIsDeclined() {
        val reqs = listOf(SeerrRequestRef(11, 1), SeerrRequestRef(12, 2), SeerrRequestRef(13, 3), SeerrRequestRef(15, 5))
        assertEquals(
            listOf(Decline(11), DeleteRequest(11), DeleteRequest(12), DeleteRequest(13), DeleteRequest(15), DeleteMedia(40)),
            seerrRemovalCalls(reqs, 40),
        )
    }

    @Test
    fun failedAndStatuslessRequestsAreOnlyDeleted() {
        assertEquals(listOf(DeleteRequest(14), DeleteMedia(40)), seerrRemovalCalls(listOf(SeerrRequestRef(14, 4)), 40))
        assertEquals(listOf(DeleteRequest(16), DeleteMedia(40)), seerrRemovalCalls(listOf(SeerrRequestRef(id = 16)), 40))
    }

    @Test
    fun requestsKeepSeerrsOrder() {
        val reqs = listOf(SeerrRequestRef(21, 5), SeerrRequestRef(22, 1), SeerrRequestRef(23, 2))
        assertEquals(
            listOf(DeleteRequest(21), Decline(22), DeleteRequest(22), DeleteRequest(23), DeleteMedia(7)),
            seerrRemovalCalls(reqs, 7),
        )
    }

    @Test
    fun noMediaRowMeansNoMediaDelete() {
        assertEquals(listOf(DeleteRequest(11)), seerrRemovalCalls(listOf(SeerrRequestRef(11, 2)), 0))
    }

    @Test
    fun aMediaRowWithNoRequestsIsStillDeleted() {
        assertEquals(listOf(DeleteMedia(40)), seerrRemovalCalls(emptyList(), 40))
    }
}

/** Phase 282 (FR-282-2) — what a decline answer means. */
class SeerrDeclineOutcomeTest {
    @Test
    fun okAndNoContentAreDeclined() {
        assertEquals(SeerrDecline.Declined, declineOutcome(200, null))
        assertEquals(SeerrDecline.Declined, declineOutcome(204, ""))
    }

    @Test
    fun aConflictIsARefusalWithSeerrsMessage() {
        assertEquals(
            SeerrDecline.Refused("Only pending requests can be approved or declined."),
            declineOutcome(409, """{"message":"Only pending requests can be approved or declined."}"""),
        )
    }

    @Test
    fun aConflictWithoutAMessageKeepsTheRawBody() {
        assertEquals(SeerrDecline.Refused("conflict"), declineOutcome(409, "conflict"))
        assertEquals(SeerrDecline.Refused("""{"error":"x"}"""), declineOutcome(409, """{"error":"x"}"""))
        assertEquals(SeerrDecline.Refused(""), declineOutcome(409, null))
    }

    @Test
    fun anythingElseFailed() {
        for (code in listOf(400, 403, 404, 500)) {
            val d = assertIs<SeerrDecline.Failed>(declineOutcome(code, "{}"))
            assertTrue(code.toString() in d.detail)
        }
    }
}

/** Phase 282 — the cascade's logging and its "always run to the end" rule. */
class SeerrRemovalRunTest {
    private val plan = listOf(Decline(11), DeleteRequest(11), DeleteRequest(12), DeleteRequest(13), DeleteRequest(15), DeleteMedia(40))

    private fun run(fail: (SeerrCall) -> SeerrCallResult?): Pair<List<SeerrCall>, List<SeerrRemovalLog>> = runBlocking {
        val ran = mutableListOf<SeerrCall>()
        val logs = runSeerrRemoval(plan) { c -> ran += c; fail(c) ?: SeerrCallResult.Ok }
        ran to logs
    }

    @Test
    fun aRefusalIsLoggedAtInfoAndTheDeleteStillRuns() {
        val (ran, logs) = run { if (it == Decline(11)) SeerrCallResult.Refused("Only pending requests can be approved or declined.") else null }
        assertEquals(plan, ran)
        val line = logs.single()
        assertEquals(SeerrLogLevel.INFO, line.level)
        assertTrue("11" in line.message && "Only pending requests" in line.message)
    }

    @Test
    fun aFailedRequestDeleteIsLoggedAtWarnAndTheCascadeGoesOn() {
        val (ran, logs) = run { if (it == DeleteRequest(12)) SeerrCallResult.Failed("HTTP 500") else null }
        assertEquals(plan, ran)
        val line = logs.single()
        assertEquals(SeerrLogLevel.WARN, line.level)
        assertTrue("request 12" in line.message)
    }

    @Test
    fun aFailedMediaDeleteIsLoggedAtWarn() {
        val (_, logs) = run { if (it == DeleteMedia(40)) SeerrCallResult.Failed("HTTP 403") else null }
        val line = logs.single()
        assertEquals(SeerrLogLevel.WARN, line.level)
        assertTrue("media 40" in line.message)
    }

    @Test
    fun aThrownCallCountsAsFailed() {
        val (ran, logs) = run { if (it == DeleteRequest(13)) throw IllegalStateException("timeout") else null }
        assertEquals(plan, ran)
        val line = logs.single()
        assertEquals(SeerrLogLevel.WARN, line.level)
        assertTrue("request 13" in line.message && "timeout" in line.message)
    }

    @Test
    fun aFailedDeclineIsLoggedAndTheDeleteStillRuns() {
        val (ran, logs) = run { if (it == Decline(11)) SeerrCallResult.Failed("HTTP 500") else null }
        assertEquals(plan, ran)
        assertEquals(SeerrLogLevel.INFO, logs.single().level)
    }

    @Test
    fun aCleanRemovalLogsNothingAtWarn() {
        val (ran, logs) = run { null }
        assertEquals(plan, ran)
        assertTrue(logs.none { it.level == SeerrLogLevel.WARN })
    }
}

/** Phase 282 — Seerr 3.5.0's `/movie/{id}` shape (with the new `hasActiveRequest`) decodes and plans the removal. */
class SeerrMediaInfoDecodeTest {
    private val body = """
        {"id": 900001, "mediaInfo": {"id": 77, "status": 3, "hasActiveRequest": true,
          "requests": [
            {"id": 101, "status": 1}, {"id": 102, "status": 2}, {"id": 103, "status": 3},
            {"id": 104, "status": 4}, {"id": 105, "status": 5}, {"id": 106}
          ]}}
    """.trimIndent()

    @Test
    fun aSeerr35MovieDecodesAndPlansTheRemoval() {
        val movie = OutboundHttp.clientJson.decodeFromString(SeerrMovieDetails.serializer(), body)
        val mi = assertNotNull(movie.mediaInfo)
        assertEquals(77, mi.id)
        assertEquals(listOf(1, 2, 3, 4, 5, 0), mi.requests.map { it.status })
        val calls = seerrRemovalCalls(mi.requests, mi.id)
        assertEquals(listOf(Decline(101)), calls.filterIsInstance<Decline>())
        assertEquals(6, calls.count { it is DeleteRequest })
        assertEquals(DeleteMedia(77), calls.last())
    }
}
