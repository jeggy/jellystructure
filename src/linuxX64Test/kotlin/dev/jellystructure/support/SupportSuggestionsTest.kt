package dev.jellystructure.support

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AiJobConfig
import dev.jellystructure.config.AiSupportConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.SupportConfig
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.support.SupportAgentTest.FakeAnthropic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-1667 (FR-8) — `AI Agent` answers "what should I watch?" from the viewer's own Recommended row. Every title here is an
 * invented stand-in: the repository is public, so no library title goes in a fixture.
 */
class SupportSuggestionsTest {
    /** The spec's stand-in table, then generated filler so the row can reach 30. */
    private fun item(n: Int, title: String, year: Int, kind: MediaKind = MediaKind.MOVIE, tags: List<String> = emptyList()) = MediaItem(
        id = "m$n", title = title, year = year, kind = kind, path = "/lib/m$n.mkv", tmdbId = 9000 + n, originalLanguage = "en",
        posterPath = null, overview = "A calm story number $n.", tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        jellyfinId = "jf-secret-$n", genres = listOf("Drama"), tags = tags,
    )

    private val copper = item(1, "The Copper Orchard", 2016)
    private val lantern = item(2, "Lantern Street Mysteries", 2021, MediaKind.TV_SHOW, tags = listOf("hidden"))
    private val winters = item(3, "Nine Quiet Winters", 2009)
    private val owl = item(4, "Owl", 2018)
    private val harbour = item(5, "Harbour of Small Lights", 2014)
    private fun filler(from: Int, to: Int) = (from..to).map { item(it, "Placeholder Reel Number $it", 2000 + it) }

    /** A stand-in for the server's reads: the viewer's device row, Home's row for it, and every live item. */
    private class Lib(var row: List<MediaItem>, var live: List<MediaItem>, var device: DeviceData? = DeviceData(
        deviceId = "d1", deviceToken = "tok", jellyfinUserId = "u-anna", jellyfinUsername = "anna", jellyfinUserToken = "jf-user-token", isAdmin = false,
    )) {
        var rowReads = 0
        fun library() = SupportAgent.Library(
            deviceOf = { device },
            row = { rowReads++; row },
            live = { live },
        )
    }

    private fun SupportAgentTest.Rig.config(rerank: Boolean) = runBlocking {
        store.update(AppConfig(
            ai = AiConfig(enabled = true, apiKey = "sk-ant-test-0000abcd", rerank = AiJobConfig(enabled = rerank), support = AiSupportConfig(enabled = true)),
            support = SupportConfig(enabled = true),
        ))
    }

    private inline fun rig(tag: String, lib: Lib, rerank: Boolean = true, body: SupportAgentTest.Rig.() -> Unit) {
        val r = SupportAgentTest.Rig("sug-$tag", library = lib.library())
        try { r.config(rerank); r.body() } finally { r.close() }
    }

    private fun standard() = Lib(row = listOf(copper, lantern, winters, owl) + filler(10, 35), live = listOf(copper, lantern, winters, owl, harbour) + filler(10, 35))

    private fun SupportAgentTest.Rig.user() = FakeAnthropic.userOf(anthropic.sent.single())

    private fun reply(text: String) = { _: String -> FakeAnthropic.good(reply = text, title = null, englishTitle = null) }

    // ── FR-1 — the block ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theBlockHoldsShortIdsAndDescribeLinesBeforeTheThreadAndNothingTheServerKeepsToItself() = rig("block", standard()) {
        file(body = "What should I watch tonight? Something calm.")
        send()
        val user = user()
        assertTrue("<suggestions>\n" in user, user)
        assertTrue(user.indexOf("</suggestions>") < user.indexOf("<thread>"), "the block sits before the thread")
        assertTrue(user.indexOf("Current class") < user.indexOf("<suggestions>"), "and after the language and class lines")
        assertTrue("s1 | The Copper Orchard (2016) | film | Drama | A calm story number 1." in user, user)
        assertTrue("s2 | Lantern Street Mysteries (2021) | series" in user, user)
        assertTrue("\ns30 | " in user && "\ns31 | " !in user, "the first 30 titles of the row, no more")
        // The whole request, not just the user message: no Jellyfin id, user id, user name, device name or token.
        val request = anthropic.sent.single().toString()
        for (secret in listOf("jf-secret", "u-anna", "Name-of-u-anna", "jf-user-token")) assertFalse(secret in request, "the request carries \"$secret\"")
    }

    @Test fun theSystemPromptStaysTheSameWhateverTheBlockHolds() {
        // The prompt is cached; the block is per request. FR-2's paragraph is in it whether or not a block is sent.
        assertTrue("{s3}" in SupportAi.SYSTEM && "<suggestions>" in SupportAi.SYSTEM)
    }

    @Test fun noBlockAndARequestByteIdenticalToTodayWhenAnyOneConditionFails() {
        fun requestFor(tag: String, lib: Lib?, rerank: Boolean): String {
            val r = SupportAgentTest.Rig("sug-$tag", library = lib?.library())
            try {
                r.config(rerank)
                r.file(body = "What should I watch tonight?")
                r.send()
                return FakeAnthropic.userOf(r.anthropic.sent.single())
            } finally { r.close() }
        }
        val today = requestFor("today", null, rerank = true)
        assertFalse("<suggestions>" in today)
        val off = Lib(row = standard().row, live = standard().live)
        assertEquals(today, requestFor("rerank-off", off, rerank = false), "re-rank not active")
        assertEquals(0, off.rowReads, "and the row is not even read")
        assertEquals(today, requestFor("no-device", Lib(row = standard().row, live = standard().live, device = null), rerank = true), "no device row")
        assertEquals(today, requestFor("empty-row", Lib(row = emptyList(), live = standard().live), rerank = true), "an empty row")
        assertTrue("<suggestions>" in requestFor("all-three", standard(), rerank = true))
    }

    @Test fun aKidsDeviceGetsTheBlockLikeAnyOther() {
        val lib = standard().also { it.device = it.device!!.copy(isKids = true) }
        rig("kids", lib) {
            file(body = "What should I watch tonight?")
            send()
            assertTrue("<suggestions>" in user())
        }
    }

    // ── FR-5 — the server writes the titles ──────────────────────────────────────────────────────────────────────────

    @Test fun aReplyWithTwoIdsSendsWithBothTitlesAndYearsAndTheTranscriptKeepsTheIds() = rig("send", standard()) {
        anthropic.answerFor = reply("Try {s2} or {s3} tonight.")
        val n = file(body = "What should I watch tonight? Something calm.")
        cycle()
        assertEquals("Try Lantern Street Mysteries (2021) or Nine Quiet Winters (2009) tonight.", lastAiBody(n))
        assertEquals(listOf<String?>("SENT"), verdicts(n))
        val turn = service.agentTranscript(n)!!.turns.single()
        assertTrue("<suggestions>" in turn.sent && "s2 | Lantern Street Mysteries (2021)" in turn.sent, "the exact block that was sent")
        assertTrue("Try {s2} or {s3} tonight." in turn.answer!!, "and the answer as returned, with its ids")
    }

    // ── FR-3 — rule 7, each part failing alone ───────────────────────────────────────────────────────────────────────

    private fun SupportAgentTest.Rig.forwardedFor(text: String, n: Long = file(body = "What should I watch tonight?")): String {
        anthropic.answerFor = reply(text)
        cycle()
        assertTrue("passing it on to them" in lastAiBody(n)!!, "the viewer is told it was passed on, and nothing the model wrote")
        return verdicts(n).single()!!
    }

    @Test fun anIdBeyondTheThirtyHandedForwards() = rig("r7-31", standard()) {
        val v = forwardedFor("Try {s31}.")
        assertTrue(v.startsWith("FORWARDED:") && "rule 7 — an id not in the suggestions" in v, v)
    }

    @Test fun sixDistinctIdsForward() = rig("r7-six", standard()) {
        val v = forwardedFor("{s1} {s2} {s3} {s4} {s6} {s7}")
        assertTrue("rule 7 — more than five suggestions" in v, v)
        assertFalse("an id not in the suggestions" in v, v)
    }

    @Test fun fiveDistinctIdsAndARepeatedOneSend() = rig("r7-five", standard()) {
        anthropic.answerFor = reply("{s1}, {s2}, {s3}, {s6}, {s7} and again {s1}.")
        val n = file(body = "What should I watch tonight?")
        cycle()
        assertEquals(listOf<String?>("SENT"), verdicts(n))
    }

    @Test fun aHandedTitleWrittenOutInsteadOfByIdForwards() = rig("r7-handed", standard()) {
        val v = forwardedFor("Try the copper orchard tonight!")
        assertTrue("rule 7 — the reply names a library title itself" in v, v)
        assertFalse("copper" in v.lowercase(), "the verdict quotes no title: $v")
    }

    @Test fun aLibraryTitleTheViewerWasNeverHandedForwardsToo() = rig("r7-unhanded", standard()) {
        val v = forwardedFor("You might like Harbour of Small Lights.")
        assertTrue("rule 7 — the reply names a library title itself" in v, v)
    }

    @Test fun aTitleThatTheViewerCanNoLongerSeeWhenTheBatchReturnsForwards() {
        val lib = standard()
        rig("r7-hidden", lib) {
            val n = file(body = "What should I watch tonight?")
            send()
            // The policy narrows while the batch is out: the stand-in title carries the tag the device now blocks.
            lib.device = lib.device!!.copy(blockedTags = setOf("hidden"))
            anthropic.answerFor = reply("Try {s2}.")
            poll()
            assertTrue("passing it on" in lastAiBody(n)!!)
            assertTrue("rule 7 — a suggestion is no longer visible to this viewer" in verdicts(n).single()!!)
        }
    }

    @Test fun aTitleThatLeftTheLibraryWhenTheBatchReturnsForwards() {
        val lib = standard()
        rig("r7-gone", lib) {
            val n = file(body = "What should I watch tonight?")
            send()
            lib.live = lib.live.filter { it !== winters }
            anthropic.answerFor = reply("Try {s3}.")
            poll()
            assertTrue("passing it on" in lastAiBody(n)!!)
            assertTrue("rule 7 — a suggestion is no longer visible to this viewer" in verdicts(n).single()!!)
        }
    }

    @Test fun anyIdWhenNoBlockWasSentForwards() = rig("r7-noblock", standard(), rerank = false) {
        val v = forwardedFor("Try {s1}.")
        assertTrue("rule 7 — an id not in the suggestions" in v, v)
    }

    // ── FR-3 — the case title, and part 2's edges ────────────────────────────────────────────────────────────────────

    @Test fun aLibraryTitleInTheCaseTitleDropsTheTitleAndTheReplyStillSends() = rig("title", standard()) {
        anthropic.answerFor = { FakeAnthropic.good(reply = "Try {s1}.", title = "Harbour of Small Lights", englishTitle = "Calm films tonight") }
        val n = file(body = "What should I watch tonight?")
        cycle()
        val v = verdicts(n).single()!!
        assertTrue(v.startsWith("SENT | TITLE_DROPPED: title rule 7"), v)
        assertFalse("Harbour" in v, v)
        assertEquals(null to "Calm films tonight", admin(n).ticket.let { it.title to it.englishTitle })
        assertEquals("Try The Copper Orchard (2016).", lastAiBody(n))
    }

    @Test fun aThreeLetterStandInTitleInProseDoesNotTripPartTwo() = rig("owl", standard()) {
        anthropic.answerFor = reply("An owl would not mind the dark. Open the subtitle menu.")
        val n = file()
        cycle()
        assertEquals(listOf<String?>("SENT"), verdicts(n))
    }

    // ── The pure checks ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nameKeysFoldAndSkipShortTitlesAndWholeWordsDecide() {
        val keys = SupportAi.nameKeys(listOf("The Copper Orchard", "Owl", null, "Æble Træ!", "1917 ab"))
        assertTrue(SupportAi.namesATitle("Have you seen THE COPPER ORCHARD?", keys))
        assertTrue(SupportAi.namesATitle("able tra", keys), "Faroese and Danish letters fold")
        assertFalse(SupportAi.namesATitle("The Copper Orchards", keys), "a longer word is not the title")
        assertFalse(SupportAi.namesATitle("an owl", keys), "under four letters is skipped")
        assertFalse(SupportAi.namesATitle("anything", emptySet()))
    }

    @Test fun substituteLeavesAnUnknownIdAlone() {
        assertEquals("A (1) and {s9}", SupportAi.substitute("{s1} and {s9}") { if (it == "s1") "A (1)" else null })
    }
}
