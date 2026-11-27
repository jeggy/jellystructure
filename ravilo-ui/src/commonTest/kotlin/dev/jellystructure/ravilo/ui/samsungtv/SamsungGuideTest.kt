package dev.jellystructure.ravilo.ui.samsungtv

import dev.jellystructure.shared.tv.SamsungTvRequest
import dev.jellystructure.shared.tv.SamsungTvRequestCreate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DEB-4843 (FR-2–FR-7, FR-9) — every transition of the guide, every auto-check branch, the returning-user shortcut. */
class GuideTest {
    private val duid = "EXCKEBBVWCGFC"
    private fun tvBody(dev: String = "1", ip: String = "192.168.1.52") =
        """{"device":{"modelName":"UE55RU7445","name":"Living","developerMode":"$dev","developerIP":"$ip"}}"""

    private class Backend(var rows: MutableList<SamsungTvRequest> = mutableListOf()) : SamsungTvBackend {
        var created: SamsungTvRequestCreate? = null
        var fail = false
        override suspend fun list() = rows.toList()
        override suspend fun create(body: SamsungTvRequestCreate): SamsungTvRequest {
            if (fail) error("409")
            created = body
            return row("REQUESTED", body.duid).copy(id = 100L + rows.size, model = body.model, name = body.name).also { rows.add(0, it) }
        }
        override suspend fun withdraw(id: Long) = rows.removeAll { it.id == id }.let { row("WITHDRAWN", "X") }
    }

    private inner class Phone(
        var address: String? = "192.168.1.52",
        var tv: String? = tvBody(),
        val tvIp: String = "192.168.1.9",
        val answers: Map<String, String> = mapOf("0 getduid" to duid),
        var covers: Set<String>? = setOf(duid),
    ) : SamsungTvPlatform {
        val fake = FakeSdbTv(answers + mapOf(
            "0 vd_appinstall RaviloScrn /home/owner/share/tmp/sdk_tools/RaviloScrn.wgt" to "install completed",
            "0 was_execute RaviloScrn.RaviloScrn" to "launched",
        ))
        var downloads = 0
        /** What the TV answers for `…/applications/RaviloScrn.RaviloScrn`; null = nothing answered. */
        var app: TvAppReply? = TvAppReply(200, """{"id":"RaviloScrn.RaviloScrn","name":"Ravilo","running":false,"version":"1.70.0","visible":false}""")
        val tags = mutableMapOf<String, String>()
        override fun phoneAddress() = address
        override suspend fun probe(ip: String) = if (ip == tvIp) tv else null
        override suspend fun appReply(ip: String) = if (ip == tvIp) app else null
        override fun lastInstalledTag(duid: String) = tags[duid]
        override fun rememberInstalledTag(duid: String, tag: String) { tags[duid] = tag }
        override suspend fun download(url: String, onProgress: (Long, Long?) -> Unit): ByteArray { downloads++; onProgress(5, 10); return ByteArray(32) }
        override fun packageDeviceIds(wgt: ByteArray) = covers
        override val sdb = SdbClient(fake, {}, pause = {})
        override fun log(line: String) {}
    }

    private fun TestScope.guide(phone: Phone, backend: Backend) = SamsungGuide(phone, backend, this)

    @Test fun noRequestsOpensTheIntroWithOrWithoutWifi() = runTest {
        val g = guide(Phone(), Backend()); g.open(); advanceUntilIdle()
        assertEquals(GuideScreen.Intro(wifi = true), g.screen.value)
        val off = guide(Phone(address = null), Backend()); off.open(); advanceUntilIdle()
        assertEquals(GuideScreen.Intro(wifi = false), off.screen.value)
        off.find(); advanceUntilIdle()
        assertEquals(GuideScreen.Intro(wifi = false), off.screen.value)   // no scan without Wi-Fi
    }

    @Test fun theWholeGuideToTheRequest() = runTest {
        val b = Backend(); val g = guide(Phone(), b)
        g.open(); advanceUntilIdle(); g.find(); advanceUntilIdle()
        val pick = assertIs<GuideScreen.Pick>(g.screen.value)
        assertEquals(listOf("192.168.1.9"), pick.tvs.map { it.ip })
        g.choose(pick.tvs[0])
        assertIs<GuideScreen.Step1>(g.screen.value)
        g.next(); assertEquals("192.168.1.52", assertIs<GuideScreen.Step2>(g.screen.value).phoneIp)
        g.next(); assertIs<GuideScreen.Step3>(g.screen.value)
        g.next(); advanceUntilIdle(); assertIs<GuideScreen.Ask>(g.screen.value)
        g.sendRequest(); advanceUntilIdle()
        assertEquals(SamsungTvRequestCreate(duid, "UE55RU7445", "5.0", "Living"), b.created)
        // FR-15 — the send lands on the overview, whose card for the TV is the server's answer.
        val ov = assertIs<GuideScreen.Overview>(g.screen.value)
        assertTrue(ov.sent)
        assertEquals(Badge.WAITING, ov.cards.single().badge); assertEquals("192.168.1.9", ov.cards.single().tv?.ip)
    }

    @Test fun noTvOnTheSubnetIsNotFound() = runTest {
        val g = guide(Phone(tv = null), Backend()); g.find(); advanceUntilIdle()
        assertEquals(GuideScreen.NotFound, g.screen.value)
    }

    @Test fun theScanStaysOnThePhonesSubnet() = runTest {
        val probed = mutableSetOf<String>()
        val p = object : SamsungTvPlatform by Phone() {
            override suspend fun probe(ip: String): String? { probed += ip; return null }
        }
        SamsungGuide(p, Backend(), this).find(); advanceUntilIdle()
        assertEquals(253, probed.size)
        assertTrue(probed.all { it.startsWith("192.168.1.") })
    }

    private suspend fun TestScope.atStep3(phone: Phone): SamsungGuide {
        val g = guide(phone, Backend()); g.find(); advanceUntilIdle()
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0]); g.next(); g.next(); return g
    }

    // DEB-5504 FR-13 — step 1 asks which remote.
    private suspend fun TestScope.atStep1(phone: Phone = Phone()): SamsungGuide {
        val g = guide(phone, Backend()); g.find(); advanceUntilIdle()
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0]); return g
    }

    @Test fun stepOneOpensOnTheQuestion() = runTest {
        val g = atStep1()
        assertEquals(null, assertIs<GuideScreen.Step1>(g.screen.value).remote)
    }

    @Test fun eachChoiceLeadsToItsOwnInstruction() = runTest {
        for (r in Remote.values()) {
            val g = atStep1(); g.pickRemote(r)
            assertEquals(r, assertIs<GuideScreen.Step1>(g.screen.value).remote)
            g.next(); assertIs<GuideScreen.Step2>(g.screen.value)   // the instruction's one button is FR-3's Next
        }
    }

    @Test fun aTabTapSwitchesTheInstructionWithoutLeavingStepOne() = runTest {
        val g = atStep1(); g.pickRemote(Remote.KEYS); g.pickRemote(Remote.NOKEYS)
        val s = assertIs<GuideScreen.Step1>(g.screen.value)
        assertEquals(Remote.NOKEYS, s.remote); assertEquals("192.168.1.9", s.tv.ip)
        g.pickRemote(Remote.KEYS); assertEquals(Remote.KEYS, assertIs<GuideScreen.Step1>(g.screen.value).remote)
    }

    @Test fun backAndAnFr4CheckLandOnTheChosenInstruction() = runTest {
        val g = atStep1(Phone(tv = tvBody(dev = "0"))); g.pickRemote(Remote.NOKEYS)
        g.next(); g.next(); g.next(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value); assertEquals(Target.STEP1, p.back)
        g.back(p.back); assertEquals(Remote.NOKEYS, assertIs<GuideScreen.Step1>(g.screen.value).remote)
        g.next(); g.back(Target.STEP1)   // Back from a later step too
        assertEquals(Remote.NOKEYS, assertIs<GuideScreen.Step1>(g.screen.value).remote)
    }

    @Test fun closingTheGuideForgetsTheRemote() = runTest {
        val g = atStep1(); g.pickRemote(Remote.KEYS)
        g.open(); advanceUntilIdle(); g.find(); advanceUntilIdle()
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0])
        assertEquals(null, assertIs<GuideScreen.Step1>(g.screen.value).remote)
    }

    @Test fun aPickOffStepOneIsHeldForWhenTheGuideGetsThere() = runTest {
        val g = atStep1(); g.next(); g.pickRemote(Remote.KEYS)
        assertIs<GuideScreen.Step2>(g.screen.value)   // the screen did not move
        g.back(Target.STEP1); assertEquals(Remote.KEYS, assertIs<GuideScreen.Step1>(g.screen.value).remote)
    }

    @Test fun developerModeOffSendsYouBackToStepOne() = runTest {
        val g = atStep3(Phone(tv = tvBody(dev = "0"))); g.next(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value)
        assertEquals("samsungtv.problem.devmode_off", p.key); assertEquals(Target.STEP1, p.back)
        g.back(p.back); assertIs<GuideScreen.Step1>(g.screen.value)
    }

    @Test fun aWrongAddressNamesBothAndSendsYouToStepTwo() = runTest {
        val g = atStep3(Phone(tv = tvBody(ip = "192.168.1.40"))); g.next(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value)
        assertEquals(mapOf("tv" to "192.168.1.40", "phone" to "192.168.1.52"), p.vars); assertEquals(Target.STEP2, p.back)
    }

    @Test fun aSilentTvAsksToFindItAgain() = runTest {
        val phone = Phone(); val g = atStep3(phone); phone.tv = null; g.next(); advanceUntilIdle()
        assertEquals(Target.FIND, assertIs<GuideScreen.Problem>(g.screen.value).back)
    }

    @Test fun aTvThatGivesNoIdIsASentenceAndAWayBack() = runTest {
        val g = atStep3(Phone(answers = mapOf("0 getduid" to "error"))); g.next(); advanceUntilIdle()
        g.sendRequest(); advanceUntilIdle()
        assertEquals("samsungtv.problem.no_duid", assertIs<GuideScreen.Problem>(g.screen.value).key)
    }

    @Test fun aRefusedRequestIsASentenceAndAWayBack() = runTest {
        val b = Backend().also { it.fail = true }; val g = guide(Phone(), b)
        g.find(); advanceUntilIdle(); g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0]); g.next(); g.next(); g.next(); advanceUntilIdle()
        g.sendRequest(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value); assertEquals("samsungtv.problem.request_failed", p.key); assertEquals(Target.ASK, p.back)
    }

    // ─── DEB-5905 (FR-14) — open() lands on the overview for every row mix ───────────────────────────────────

    private fun TestScope.opened(phone: Phone, vararg rows: SamsungTvRequest): Pair<SamsungGuide, GuideScreen.Overview> {
        val g = guide(phone, Backend(rows.toMutableList())); g.open(); advanceUntilIdle()
        return g to assertIs<GuideScreen.Overview>(g.screen.value)
    }
    private val wgt = "https://github.com/jeggy/jellystructure/releases/download/v1.71/ravilo-tizen.wgt"

    @Test fun theBackendsStateIsWhatTheGuideShows() = runTest {
        for ((state, badge) in listOf("REQUESTED" to Badge.WAITING, "APPROVED" to Badge.APPROVED, "READY" to Badge.APPROVED, "REJECTED" to Badge.REJECTED)) {
            val (_, ov) = opened(Phone(), row(state, duid))
            assertEquals(badge, ov.cards.single().badge, state)
        }
        val withdrawn = guide(Phone(), Backend(mutableListOf(row("WITHDRAWN", duid)))); withdrawn.open(); advanceUntilIdle()
        assertIs<GuideScreen.Intro>(withdrawn.screen.value)
    }

    @Test fun oneRequestedRowOpensTheOverviewWithAWithdrawLink() = runTest {
        val (_, ov) = opened(Phone(), row("REQUESTED", duid))
        val card = ov.cards.single()
        assertEquals(Badge.WAITING, card.badge); assertEquals(CardAction.WITHDRAW, card.action); assertEquals("192.168.1.9", card.tv?.ip)
        assertFalse(ov.sent)
    }

    @Test fun oneRejectedRowOpensTheOverviewWithAskAgain() = runTest {
        val rejected = row("REJECTED", duid).copy(decisionNote = "Not this one")
        val (_, ov) = opened(Phone(), rejected)
        assertEquals(Badge.REJECTED, ov.cards.single().badge); assertEquals(CardAction.ASK_AGAIN, ov.cards.single().action)
        assertEquals("Not this one", ov.cards.single().request?.decisionNote)
    }

    @Test fun aReadyRowBesideARequestedOneIsOneListWithBothTvs() = runTest {
        val other = row("REQUESTED", "AAAAAAAAAAAAA").copy(id = 8, model = "QE43Q60B", name = "Bedroom")
        val (_, ov) = opened(Phone(), row("READY", duid, wgt).copy(readyFromRelease = "v1.71"), other)
        assertEquals(listOf(Badge.APPROVED, Badge.WAITING), ov.cards.map { it.badge })
        assertTrue(ov.notFound(ov.cards[1]))   // the second TV is not on this Wi-Fi
        assertFalse(ov.notFound(ov.cards[0]))
        assertEquals(1, ov.found)
    }

    @Test fun theOverviewStartsFromTheServerRowsWhileTheScanRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val phone = Phone()
        val slow = object : SamsungTvPlatform by phone {
            override suspend fun probe(ip: String): String? { gate.await(); return phone.probe(ip) }
        }
        val g = SamsungGuide(slow, Backend(mutableListOf(row("READY", duid, wgt))), this); g.open(); runCurrent()
        val first = assertIs<GuideScreen.Overview>(g.screen.value)
        assertTrue(first.scanning); assertEquals(Badge.APPROVED, first.cards.single().badge); assertEquals(null, first.cards.single().tv)
        assertFalse(first.notFound(first.cards.single()))   // nothing is "not found" before the scan ends
        gate.complete(Unit); advanceUntilIdle()
        val done = assertIs<GuideScreen.Overview>(g.screen.value)
        assertFalse(done.scanning); assertEquals("192.168.1.9", done.cards.single().tv?.ip)
    }

    @Test fun withoutWifiTheOverviewIsTheServerRowsAndNoScan() = runTest {
        val phone = Phone(address = null)
        val (_, ov) = opened(phone, row("READY", duid, wgt))
        assertFalse(ov.wifi); assertFalse(ov.scanning); assertFalse(ov.notFound(ov.cards.single()))
        assertEquals(CardAction.NONE, ov.cards.single().action)
    }

    // ─── FR-10 — which TV a request belongs to ────────────────────────────────────────────────────────────────

    private fun found(ip: String, model: String = "UE55RU7445", name: String = "Living", on: InstalledRavilo = InstalledRavilo.Unknown) =
        FoundTv(SamsungTv(ip, model, name, "5.0", developerMode = true, developerIp = null), on)

    @Test fun aTvMatchesTheRequestWithItsModelAndName() {
        val cards = overviewCards(listOf(row("READY", duid)), listOf(found("1.1.1.1")), { null })
        assertEquals(1, cards.size); assertEquals("1.1.1.1", cards[0].tv?.ip); assertEquals(Badge.APPROVED, cards[0].badge)
    }

    @Test fun twoIdenticalTvsAreNotGuessedAndTheRowKeepsItsOwnCardWithBothCandidates() {
        val cards = overviewCards(listOf(row("READY", duid, wgt)), listOf(found("1.1.1.1"), found("1.1.1.2")), { null })
        assertEquals(listOf(Badge.NOT_SET_UP, Badge.NOT_SET_UP, Badge.APPROVED), cards.map { it.badge })
        val rowCard = cards.last()
        assertEquals(null, rowCard.tv); assertEquals(listOf("1.1.1.1", "1.1.1.2"), rowCard.candidates.map { it.ip })
        assertEquals(CardAction.INSTALL, rowCard.action)   // DUID check picks the TV (FR-7)
    }

    @Test fun aRenamedTvIsNotSetUpBesideItsRowsNotFoundCard() {
        val cards = overviewCards(listOf(row("READY", duid)), listOf(found("1.1.1.1", name = "Renamed")), { null })
        assertEquals(listOf(Badge.NOT_SET_UP, Badge.APPROVED), cards.map { it.badge })
        assertEquals(null, cards[1].tv); assertTrue(cards[1].candidates.isEmpty())
    }

    @Test fun cardsAreOrderedApprovedWaitingRejectedNotSetUpThenTheRowsTheScanMissed() {
        val rows = listOf(
            row("REJECTED", "AAAAAAAAAAAAA").copy(id = 1, model = "M1", name = "One"),
            row("READY", duid).copy(id = 2, model = "M2", name = "Two"),
            row("REQUESTED", "BBBBBBBBBBBBB").copy(id = 3, model = "M3", name = "Three"),
            row("APPROVED", "CCCCCCCCCCCCC").copy(id = 4, model = "M4", name = "Four"),
        )
        val cards = overviewCards(rows, listOf(found("1.1.1.1", "M1", "One"), found("1.1.1.2", "M9", "Nine"), found("1.1.1.3", "M3", "Three"), found("1.1.1.4", "M2", "Two")), { null })
        assertEquals(listOf(Badge.APPROVED, Badge.WAITING, Badge.REJECTED, Badge.NOT_SET_UP, Badge.APPROVED), cards.map { it.badge })
        assertEquals(null, cards.last().tv)   // the Approved row nothing found comes after every found TV
    }

    // ─── FR-11 — the version the TV says it has ───────────────────────────────────────────────────────────────

    @Test fun theThreeAnswersOfTheTvAndTheStatusItGaveThem() {
        assertEquals(InstalledRavilo.Version(RaviloVersion(1, 71)), InstalledRavilo.read(TvAppReply(200, """{"id":"RaviloScrn.RaviloScrn","version":"1.71.0","running":false}""")))
        assertEquals(InstalledRavilo.NotInstalled, InstalledRavilo.read(TvAppReply(404, """{"code":404,"message":"Not found error.","status":404}""")))
        assertEquals(InstalledRavilo.NotInstalled, InstalledRavilo.read(TvAppReply(404, "")))
        assertEquals(InstalledRavilo.NotInstalled, InstalledRavilo.read(TvAppReply(200, """{"code":404}""")))
        assertEquals(InstalledRavilo.Unknown, InstalledRavilo.read(TvAppReply(500, "boom")))
        assertEquals(InstalledRavilo.Unknown, InstalledRavilo.read(TvAppReply(200, """{"id":"x"}""")))
        assertEquals(InstalledRavilo.Unknown, InstalledRavilo.read(TvAppReply(200, "<html>")))
        assertEquals(InstalledRavilo.Unknown, InstalledRavilo.read(null))
    }

    @Test fun theVersionIsReadDuringTheScanAndShownBesideTheLatest() = runTest {
        val (_, ov) = opened(Phone(), row("READY", duid, wgt).copy(readyFromRelease = "v1.71"))
        val card = ov.cards.single()
        assertEquals(InstalledRavilo.Version(RaviloVersion(1, 70)), card.onTv); assertEquals("1.71", card.latestText)
        assertEquals(CardAction.UPDATE, card.action)
    }

    @Test fun whenTheTvDoesNotKnowTheLocalTagForThatDuidIsUsed() = runTest {
        val phone = Phone().also { it.app = TvAppReply(200, "{}"); it.tags[duid] = "v1.71" }
        val (_, ov) = opened(phone, row("READY", duid, wgt).copy(readyFromRelease = "v1.71"))
        assertEquals(InstalledRavilo.Version(RaviloVersion(1, 71)), ov.cards.single().onTv); assertEquals(CardAction.UP_TO_DATE, ov.cards.single().action)
        val none = Phone().also { it.app = null }
        assertEquals(InstalledRavilo.Unknown, opened(none, row("READY", duid, wgt).copy(readyFromRelease = "v1.71")).second.cards.single().onTv)
    }

    @Test fun aNotInstalledAnswerIsNotOverriddenByALocalTag() = runTest {
        val phone = Phone().also { it.app = TvAppReply(404, """{"code":404}"""); it.tags[duid] = "v1.71" }
        val (_, ov) = opened(phone, row("READY", duid, wgt).copy(readyFromRelease = "v1.71"))
        assertEquals(InstalledRavilo.NotInstalled, ov.cards.single().onTv); assertEquals(CardAction.INSTALL, ov.cards.single().action)
    }

    @Test fun versionsCompareAsMajorAndMinorOnly() {
        fun action(on: InstalledRavilo?, latest: String?) = TvCard(
            SamsungTv("1.1.1.1", "UE55RU7445", "Living", "5.0", true, null), row("READY", duid).copy(readyFromRelease = latest), on,
        ).action
        fun v(text: String) = InstalledRavilo.Version(RaviloVersion.parse(text)!!)
        assertEquals(CardAction.UPDATE, action(v("1.70.0"), "v1.71"))
        assertEquals(CardAction.UP_TO_DATE, action(v("1.71.0"), "v1.71"))
        assertEquals(CardAction.UP_TO_DATE, action(v("1.71"), "1.71.0"))
        assertEquals(CardAction.UPDATE, action(v("1.9"), "v1.10"))   // numbers, not text
        assertEquals(CardAction.INSTALL, action(InstalledRavilo.Unknown, "v1.71"))
        assertEquals(CardAction.INSTALL, action(InstalledRavilo.NotInstalled, "v1.71"))
        assertEquals(CardAction.INSTALL, action(null, "v1.71"))
    }

    // ─── FR-15 — adding a TV from its card, and installing from a card ───────────────────────────────────────

    @Test fun setUpThisTvLeadsToStepOneForThatTv() = runTest {
        val (g, ov) = opened(Phone(), row("REQUESTED", "AAAAAAAAAAAAA").copy(model = "QE43Q60B", name = "Bedroom"))
        val card = ov.cards.single { it.badge == Badge.NOT_SET_UP }
        assertEquals(CardAction.SET_UP, card.action)
        g.setUp(card)
        assertEquals("192.168.1.9", assertIs<GuideScreen.Step1>(g.screen.value).tv.ip)
    }

    @Test fun aSecondTvIsAddedFromItsCardAndTheGuideComesBackToTheOverview() = runTest {
        val b = Backend(mutableListOf(row("READY", "AAAAAAAAAAAAA", wgt).copy(model = "QE43Q60B", name = "Bedroom")))
        val g = guide(Phone(), b); g.open(); advanceUntilIdle()
        val ov = assertIs<GuideScreen.Overview>(g.screen.value)
        g.setUp(ov.cards.first { it.badge == Badge.NOT_SET_UP }); g.next(); g.next(); g.next(); advanceUntilIdle()
        g.sendRequest(); advanceUntilIdle()
        val after = assertIs<GuideScreen.Overview>(g.screen.value)
        assertTrue(after.sent)
        // the TV that was just set up is found, so its card comes first; the approved TV the scan did not find follows
        assertEquals(listOf(Badge.WAITING, Badge.APPROVED), after.cards.map { it.badge })
        assertEquals(duid, after.cards[0].request?.duid); assertEquals(2, b.rows.size)
        // *Request sent* stays until the guide is closed; reopening forgets it.
        g.refresh(); advanceUntilIdle(); assertTrue(assertIs<GuideScreen.Overview>(g.screen.value).sent)
        g.open(); advanceUntilIdle(); assertFalse(assertIs<GuideScreen.Overview>(g.screen.value).sent)
    }

    @Test fun aRefusedSendIsNeverShownAsSent() = runTest {
        val b = Backend(mutableListOf(row("READY", "AAAAAAAAAAAAA", wgt).copy(model = "QE43Q60B", name = "Bedroom"))).also { it.fail = true }
        val g = guide(Phone(), b); g.open(); advanceUntilIdle()
        g.setUp(assertIs<GuideScreen.Overview>(g.screen.value).cards.first { it.badge == Badge.NOT_SET_UP }); g.next(); g.next(); g.next(); advanceUntilIdle()
        g.sendRequest(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value); assertEquals(Target.ASK, p.back)
        g.back(p.back); assertIs<GuideScreen.Ask>(g.screen.value)
    }

    @Test fun aRequestedCardCanBeWithdrawnAndTheListShrinks() = runTest {
        val rows = mutableListOf(row("REQUESTED", duid), row("READY", "AAAAAAAAAAAAA", wgt).copy(id = 9, model = "QE43Q60B", name = "Bedroom"))
        val b = Backend(rows); val g = guide(Phone(), b); g.open(); advanceUntilIdle()
        val ov = assertIs<GuideScreen.Overview>(g.screen.value)
        g.withdraw(ov.cards.first { it.badge == Badge.WAITING }); advanceUntilIdle()
        // the TV that was waiting is Not set up again, and the approved TV the scan did not find follows it
        val after = assertIs<GuideScreen.Overview>(g.screen.value)
        assertEquals(listOf(Badge.NOT_SET_UP, Badge.APPROVED), after.cards.map { it.badge }); assertEquals(1, b.rows.size)
        g.withdraw(after.cards.last()); advanceUntilIdle()   // an approved card has no withdraw: nothing is sent
        assertEquals(1, b.rows.size)
    }

    @Test fun lastWithdrawnRequestSendsTheViewerToTheIntro() = runTest {
        val b = Backend(mutableListOf(row("REQUESTED", duid))); val g = guide(Phone(), b)
        g.open(); advanceUntilIdle(); g.withdraw(assertIs<GuideScreen.Overview>(g.screen.value).cards.single()); advanceUntilIdle()
        assertIs<GuideScreen.Intro>(g.screen.value); assertTrue(b.rows.isEmpty())
    }

    @Test fun readyInstallsFromItsCardWithoutAnyStepsWhenTheTvChecksOut() = runTest {
        val phone = Phone(); val (g, ov) = opened(phone, row("READY", duid, wgt).copy(readyFromRelease = "v1.71"))
        g.install(ov.cards.single()); advanceUntilIdle()
        assertIs<GuideScreen.Done>(g.screen.value)
        assertEquals(1, phone.downloads); assertEquals(4, phone.fake.connections)   // read id, push, install, launch
        assertEquals("v1.71", phone.tags[duid])   // FR-11's per-DUID fallback
        g.done(); advanceUntilIdle()
        assertIs<GuideScreen.Overview>(g.screen.value)   // Done returns to the overview
    }

    @Test fun installRunsOnTheCardsOwnRequestNotTheFirstRow() = runTest {
        val phone = Phone()
        val first = row("READY", "AAAAAAAAAAAAA", "https://x/other.wgt").copy(id = 1, model = "QE43Q60B", name = "Bedroom")
        val (g, ov) = opened(phone, first, row("READY", duid, wgt).copy(id = 2))
        g.install(ov.cards.first { it.tv != null }); advanceUntilIdle()
        assertIs<GuideScreen.Done>(g.screen.value); assertEquals(2L, (g.screen.value as GuideScreen.Done).request.id)
    }

    @Test fun twoIdenticalTvsAreToldApartByTheRequestsDuid() = runTest {
        val phone = Phone()
        val twin = object : SamsungTvPlatform by phone {
            override suspend fun probe(ip: String) = if (ip == "192.168.1.9" || ip == "192.168.1.8") phone.tv else null
            override suspend fun appReply(ip: String) = phone.app
            // .8 answers with another id, .9 with the request's
            override val sdb = SdbClient(object : SdbDialer {
                override suspend fun dial(host: String, port: Int) = FakeSdbTv(mapOf(
                    "0 getduid" to (if (host.endsWith(".9")) duid else "ZZZZZZZZZZZZZ"),
                    "0 vd_appinstall RaviloScrn /home/owner/share/tmp/sdk_tools/RaviloScrn.wgt" to "install completed",
                    "0 was_execute RaviloScrn.RaviloScrn" to "launched",
                )).dial(host, port)
            }, {}, pause = {})
        }
        val g = SamsungGuide(twin, Backend(mutableListOf(row("READY", duid, wgt))), this); g.open(); advanceUntilIdle()
        val ov = assertIs<GuideScreen.Overview>(g.screen.value)
        assertEquals(listOf(Badge.NOT_SET_UP, Badge.NOT_SET_UP, Badge.APPROVED), ov.cards.map { it.badge })
        g.install(ov.cards.last()); advanceUntilIdle()
        // .8 answers with another id; only .9, the TV whose id the request names, can get through the DUID check to Done
        assertIs<GuideScreen.Done>(g.screen.value); assertEquals(1, phone.downloads)
    }

    @Test fun anInstallWhoseTvChangedItsAddressSendsYouToStepTwoOnly() = runTest {
        val (g, ov) = opened(Phone(tv = tvBody(ip = "192.168.1.40")), row("READY", duid, "https://x/y.wgt"))
        g.install(ov.cards.single()); advanceUntilIdle()
        assertEquals(Target.STEP2, assertIs<GuideScreen.Problem>(g.screen.value).back)
    }

    @Test fun aSilentTvFromACardGoesBackToTheOverviewNotToTheListOfEveryTv() = runTest {
        val phone = Phone(); val (g, ov) = opened(phone, row("READY", duid, wgt))
        phone.tv = null
        g.install(ov.cards.single()); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value)
        assertEquals(Target.OVERVIEW, p.back)
        g.back(p.back); advanceUntilIdle(); assertIs<GuideScreen.Overview>(g.screen.value)
    }

    @Test fun aDifferentTvThanTheCardsRequestGoesBackToTheOverviewBeforeDownloading() = runTest {
        val phone = Phone(answers = mapOf("0 getduid" to "AAAAAAAAAAAAA"))
        val (g, ov) = opened(phone, row("READY", duid, wgt))
        g.install(ov.cards.single()); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value)
        assertEquals("samsungtv.problem.wrong_tv", p.key); assertEquals(Target.OVERVIEW, p.back); assertEquals(0, phone.downloads)
        g.back(p.back); advanceUntilIdle(); assertIs<GuideScreen.Overview>(g.screen.value)
    }

    @Test fun aPackageThatDoesNotNameTheTvIsNeverPushed() = runTest {
        val phone = Phone(covers = setOf("BBBBBBBBBBBBB"))
        val (g, ov) = opened(phone, row("READY", duid, wgt))
        g.install(ov.cards.single()); advanceUntilIdle()
        assertEquals("samsungtv.problem.not_covered", assertIs<GuideScreen.Problem>(g.screen.value).key)
        assertEquals(1, phone.fake.connections)   // only the id read; nothing was pushed
    }

    companion object {
        fun row(state: String, duid: String, wgt: String? = null) = SamsungTvRequest(
            id = 7, duid = duid, model = "UE55RU7445", tizenVersion = "5.0", name = "Living", state = state, createdAt = 1, wgtUrl = wgt,
        )
    }
}
