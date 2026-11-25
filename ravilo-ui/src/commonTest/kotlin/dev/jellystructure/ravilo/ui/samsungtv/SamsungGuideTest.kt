package dev.jellystructure.ravilo.ui.samsungtv

import dev.jellystructure.shared.tv.SamsungTvRequest
import dev.jellystructure.shared.tv.SamsungTvRequestCreate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            return row("REQUESTED", body.duid).also { rows.add(0, it) }
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
        override fun phoneAddress() = address
        override suspend fun probe(ip: String) = if (ip == tvIp) tv else null
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
        g.choose(pick.tvs[0], forInstall = false)
        assertIs<GuideScreen.Step1>(g.screen.value)
        g.next(); assertEquals("192.168.1.52", assertIs<GuideScreen.Step2>(g.screen.value).phoneIp)
        g.next(); assertIs<GuideScreen.Step3>(g.screen.value)
        g.next(); advanceUntilIdle(); assertIs<GuideScreen.Ask>(g.screen.value)
        g.sendRequest(); advanceUntilIdle()
        assertEquals(SamsungTvRequestCreate(duid, "UE55RU7445", "5.0", "Living"), b.created)
        assertEquals("REQUESTED", assertIs<GuideScreen.Waiting>(g.screen.value).request.state)
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
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0], false); g.next(); g.next(); return g
    }

    // DEB-5504 FR-13 — step 1 asks which remote.
    private suspend fun TestScope.atStep1(phone: Phone = Phone()): SamsungGuide {
        val g = guide(phone, Backend()); g.find(); advanceUntilIdle()
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0], false); return g
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
        g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0], false)
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
        g.find(); advanceUntilIdle(); g.choose(assertIs<GuideScreen.Pick>(g.screen.value).tvs[0], false); g.next(); g.next(); g.next(); advanceUntilIdle()
        g.sendRequest(); advanceUntilIdle()
        val p = assertIs<GuideScreen.Problem>(g.screen.value); assertEquals("samsungtv.problem.request_failed", p.key); assertEquals(Target.ASK, p.back)
    }

    @Test fun theBackendsStateIsWhatTheGuideShows() = runTest {
        for ((state, expect) in listOf("REQUESTED" to "Waiting", "APPROVED" to "Waiting", "READY" to "Ready", "REJECTED" to "Rejected")) {
            val g = guide(Phone(), Backend(mutableListOf(row(state, duid)))); g.open(); advanceUntilIdle()
            assertEquals(expect, g.screen.value::class.simpleName, state)
        }
        val withdrawn = guide(Phone(), Backend(mutableListOf(row("WITHDRAWN", duid)))); withdrawn.open(); advanceUntilIdle()
        assertIs<GuideScreen.Intro>(withdrawn.screen.value)
    }

    @Test fun aRequestedRowCanBeWithdrawn() = runTest {
        val b = Backend(mutableListOf(row("REQUESTED", duid))); val g = guide(Phone(), b)
        g.open(); advanceUntilIdle(); g.withdraw(); advanceUntilIdle()
        assertIs<GuideScreen.Intro>(g.screen.value); assertTrue(b.rows.isEmpty())
    }

    @Test fun readyInstallsWithoutAnyStepsWhenTheTvChecksOut() = runTest {
        val phone = Phone(); val g = guide(phone, Backend(mutableListOf(row("READY", duid, "https://github.com/jeggy/jellystructure/releases/download/v1.71/ravilo-tizen.wgt"))))
        g.open(); advanceUntilIdle(); g.install(); advanceUntilIdle()
        assertIs<GuideScreen.Done>(g.screen.value)
        assertEquals(1, phone.downloads); assertEquals(4, phone.fake.connections)   // read id, push, install, launch
    }

    @Test fun anInstallWhoseTvChangedItsAddressSendsYouToStepTwoOnly() = runTest {
        val g = guide(Phone(tv = tvBody(ip = "192.168.1.40")), Backend(mutableListOf(row("READY", duid, "https://x/y.wgt"))))
        g.open(); advanceUntilIdle(); g.install(); advanceUntilIdle()
        assertEquals(Target.STEP2, assertIs<GuideScreen.Problem>(g.screen.value).back)
    }

    @Test fun aDifferentTvThanTheApprovedOneIsRefusedBeforeDownloading() = runTest {
        val phone = Phone(answers = mapOf("0 getduid" to "AAAAAAAAAAAAA"))
        val g = guide(phone, Backend(mutableListOf(row("READY", duid, "https://x/y.wgt"))))
        g.open(); advanceUntilIdle(); g.install(); advanceUntilIdle()
        assertEquals("samsungtv.problem.wrong_tv", assertIs<GuideScreen.Problem>(g.screen.value).key); assertEquals(0, phone.downloads)
    }

    @Test fun aPackageThatDoesNotNameTheTvIsNeverPushed() = runTest {
        val phone = Phone(covers = setOf("BBBBBBBBBBBBB"))
        val g = guide(phone, Backend(mutableListOf(row("READY", duid, "https://x/y.wgt"))))
        g.open(); advanceUntilIdle(); g.install(); advanceUntilIdle()
        assertEquals("samsungtv.problem.not_covered", assertIs<GuideScreen.Problem>(g.screen.value).key)
        assertEquals(1, phone.fake.connections)   // only the id read; nothing was pushed
    }

    companion object {
        fun row(state: String, duid: String, wgt: String? = null) = SamsungTvRequest(
            id = 7, duid = duid, model = "UE55RU7445", tizenVersion = "5.0", name = "Living", state = state, createdAt = 1, wgtUrl = wgt,
        )
    }
}
