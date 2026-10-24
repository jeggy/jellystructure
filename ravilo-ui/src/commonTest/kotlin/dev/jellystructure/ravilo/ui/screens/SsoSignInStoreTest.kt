package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvApiError
import dev.jellystructure.shared.tv.TvSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-11, FR-12) — *Sign in with ‹name›*'s state machine on a fake API and a fake platform: the button set for no names, one, two, a TV
 * and the misconfiguration case; the wait and its Cancel; the claim that needs the attempt's own return; a return that is not ours; the ten minutes.
 */
class SsoSignInStoreTest {
    private val pair = PairResult(TvSession("d", "u", "Anna", isAdmin = false), "token")

    private class FakeApi(var names: List<String> = emptyList()) : SsoApi {
        var startAnswer: () -> SsoAttemptStart = { SsoAttemptStart("https://idp.example.net/authorize?state=x", SsoAttempt("a1", "s3cret")) }
        var claimAnswer: () -> PairResult = { error("no claim expected") }
        var providerReads = 0
        val starts = mutableListOf<List<String?>>()
        val claims = mutableListOf<Triple<String, String, String>>()
        override suspend fun providers(): List<String> { providerReads++; return names }
        override suspend fun start(provider: String, deviceId: String, deviceName: String?, returnTo: String): SsoAttemptStart {
            starts += listOf(provider, deviceId, deviceName, returnTo)
            return startAnswer()
        }
        override suspend fun claim(attemptId: String, secret: String, handoff: String): PairResult {
            claims += Triple(attemptId, secret, handoff)
            return claimAnswer()
        }
    }

    private class FakeListener(override val returnTo: String) : SsoListener {
        val expected = mutableListOf<String>()
        var closed = 0
        override fun expect(attemptId: String) { expected += attemptId }
        override fun close() { closed++ }
    }

    private class FakePlatform(val returnTo: String = "app") : SsoPlatform {
        val listeners = mutableListOf<FakeListener>()
        val opened = mutableListOf<String>()
        var kept: SsoAttempt? = null
        var closeTabTexts = mutableListOf<String>()
        override fun listen(closeTabText: String): SsoListener { closeTabTexts += closeTabText; return FakeListener(returnTo).also { listeners += it } }
        override fun open(authorizeUrl: String) { opened += authorizeUrl }
        override fun persist(attempt: SsoAttempt?) { kept = attempt }
        override fun recall(): SsoAttempt? = kept
    }

    private fun TestScope.store(api: FakeApi, inbox: SsoReturnInbox, paired: MutableList<PairResult> = mutableListOf(), attemptMs: Long = SSO_ATTEMPT_MS) =
        SsoSignInStore(api, backgroundScope, { "dev-1" }, { "Anna's phone" }, { paired += it }, inbox, attemptMs)

    // ─── the button set ────────────────────────────────────────────────────────

    @Test fun `no names means no buttons`() = runTest {
        val s = store(FakeApi(emptyList()), SsoReturnInbox()); s.attach(FakePlatform(), null); runCurrent()
        assertEquals(emptyList<String>(), s.providers.value)
    }

    @Test fun `one name is one button and two names are two in the order the server lists them`() = runTest {
        val one = store(FakeApi(listOf("Harbour ID")), SsoReturnInbox()); one.attach(FakePlatform(), null); runCurrent()
        assertEquals(listOf("Harbour ID"), one.providers.value)
        val two = store(FakeApi(listOf("Harbour ID", "Fjord Login")), SsoReturnInbox()); two.attach(FakePlatform(), null); runCurrent()
        assertEquals(listOf("Harbour ID", "Fjord Login"), two.providers.value)
    }

    @Test fun `a TV has no platform and so no buttons and never asks the server`() = runTest {
        val api = FakeApi(listOf("Harbour ID"))
        val s = store(api, SsoReturnInbox()); s.attach(null, null); runCurrent()
        assertEquals(emptyList<String>(), s.providers.value)
        assertEquals(0, api.providerReads)
    }

    @Test fun `a start that answers unavailable hides the buttons and leaves the screen as it was`() = runTest {
        val api = FakeApi(listOf("Harbour ID")).apply { startAnswer = { throw TvApiError.Http(503, "never shown") } }
        val platform = FakePlatform()
        val s = store(api, SsoReturnInbox()); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "close"); runCurrent()
        assertEquals(emptyList<String>(), s.providers.value)
        assertEquals(SsoState.Idle, s.state.value)
        assertTrue(platform.opened.isEmpty())
        assertEquals(1, platform.listeners.single().closed)
        // A second attach on the same screen does not bring them back.
        s.attach(FakePlatform(), null); runCurrent()
        assertEquals(emptyList<String>(), s.providers.value)
    }

    @Test fun `any other failed start is an unreachable error and a tap again may try once more`() = runTest {
        val api = FakeApi(listOf("Harbour ID")).apply { startAnswer = { throw TvApiError.Http(429, "never shown") } }
        val s = store(api, SsoReturnInbox()); s.attach(FakePlatform(), null); runCurrent()
        s.start("Harbour ID", "close"); runCurrent()
        assertEquals(SsoState.Errored(SsoErrorKind.UNREACHABLE), s.state.value)
        assertEquals(listOf("Harbour ID"), s.providers.value)
        api.startAnswer = { SsoAttemptStart("https://idp.example.net/a", SsoAttempt("a2", "s2")) }
        s.start("Harbour ID", "close"); runCurrent()
        assertIs<SsoState.Waiting>(s.state.value)
    }

    // ─── the wait ──────────────────────────────────────────────────────────────

    @Test fun `a tap starts opens the browser and waits`() = runTest {
        val api = FakeApi(listOf("Harbour ID")); val platform = FakePlatform("app")
        val s = store(api, SsoReturnInbox()); s.attach(platform, null); runCurrent()
        val seen = mutableListOf<SsoAttempt?>(); s.onAttemptChanged = { seen += it }
        s.start("Harbour ID", "You can close this tab"); runCurrent()
        assertEquals(listOf(listOf<String?>("Harbour ID", "dev-1", "Anna's phone", "app")), api.starts)
        assertEquals(listOf("https://idp.example.net/authorize?state=x"), platform.opened)
        assertEquals(SsoState.Waiting("Harbour ID"), s.state.value)
        assertEquals(listOf("a1"), platform.listeners.single().expected)
        assertEquals(listOf("You can close this tab"), platform.closeTabTexts)
        assertEquals(SsoAttempt("a1", "s3cret"), platform.kept)
        assertEquals(listOf<SsoAttempt?>(SsoAttempt("a1", "s3cret")), seen)
    }

    @Test fun `a second tap while waiting or starting does nothing`() = runTest {
        val api = FakeApi(listOf("Harbour ID", "Fjord Login")); val platform = FakePlatform()
        val s = store(api, SsoReturnInbox()); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "c"); s.start("Fjord Login", "c"); runCurrent()
        s.start("Fjord Login", "c"); runCurrent()
        assertEquals(1, api.starts.size)
    }

    @Test fun `Cancel forgets the attempt closes the listener and the buttons come back`() = runTest {
        val inbox = SsoReturnInbox(); val platform = FakePlatform(); val api = FakeApi(listOf("Harbour ID"))
        val s = store(api, inbox); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "c"); runCurrent()
        s.cancel()
        assertEquals(SsoState.Idle, s.state.value)
        assertEquals(1, platform.listeners.single().closed)
        assertNull(platform.kept)
        // The return that arrives after a Cancel is nobody's: it is not claimed.
        inbox.offer(SsoReturn("a1", "h")); runCurrent()
        assertTrue(api.claims.isEmpty())
        assertEquals(SsoState.Idle, s.state.value)
    }

    @Test fun `the wait ends with the attempt at ten minutes`() = runTest {
        val platform = FakePlatform()
        val s = store(FakeApi(listOf("Harbour ID")), SsoReturnInbox(), attemptMs = 600_000); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "c"); runCurrent()
        advanceTimeBy(599_000); runCurrent()
        assertIs<SsoState.Waiting>(s.state.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(SsoState.Errored(SsoErrorKind.FAILED), s.state.value)
        assertEquals(1, platform.listeners.single().closed)
        assertNull(platform.kept)
    }

    // ─── the return ────────────────────────────────────────────────────────────

    @Test fun `the return of our attempt is claimed with the secret and the hand-off and signs in`() = runTest {
        val inbox = SsoReturnInbox(); val paired = mutableListOf<PairResult>()
        val api = FakeApi(listOf("Harbour ID")).apply { claimAnswer = { pair } }
        val platform = FakePlatform()
        val s = store(api, inbox, paired); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "c"); runCurrent()
        inbox.offer(SsoReturn("a1", "hand-off")); runCurrent()
        assertEquals(listOf(Triple("a1", "s3cret", "hand-off")), api.claims)
        assertEquals(listOf(pair), paired)
        assertEquals(SsoState.Idle, s.state.value)
        assertNull(inbox.pending.value)
        assertNull(platform.kept)
        assertEquals(1, platform.listeners.single().closed)
    }

    @Test fun `a return for another attempt is not claimed and the wait goes on`() = runTest {
        val inbox = SsoReturnInbox(); val api = FakeApi(listOf("Harbour ID"))
        val s = store(api, inbox); s.attach(FakePlatform(), null); runCurrent()
        s.start("Harbour ID", "c"); runCurrent()
        inbox.offer(SsoReturn("someone-elses", "h")); runCurrent()
        assertTrue(api.claims.isEmpty())
        assertIs<SsoState.Waiting>(s.state.value)
    }

    @Test fun `a refused claim is an error and the attempt is gone`() = runTest {
        val inbox = SsoReturnInbox(); val paired = mutableListOf<PairResult>()
        val api = FakeApi(listOf("Harbour ID")).apply { claimAnswer = { throw TvApiError.Http(401, "never shown") } }
        val platform = FakePlatform()
        val s = store(api, inbox, paired); s.attach(platform, null); runCurrent()
        s.start("Harbour ID", "c"); runCurrent()
        inbox.offer(SsoReturn("a1", "bad")); runCurrent()
        assertEquals(SsoState.Errored(SsoErrorKind.FAILED), s.state.value)
        assertTrue(paired.isEmpty())
        assertNull(platform.kept)
        s.dismissError()
        assertEquals(SsoState.Idle, s.state.value)
    }

    @Test fun `an attempt kept across a reload waits and claims on the return found there`() = runTest {
        // Ravilo Web: the page is loaded again with the return already in the inbox, and the secret in the platform's own memory.
        val inbox = SsoReturnInbox().apply { offer(SsoReturn("a1", "hand-off")) }
        val paired = mutableListOf<PairResult>()
        val api = FakeApi(listOf("Harbour ID")).apply { claimAnswer = { pair } }
        val platform = FakePlatform("web").apply { kept = SsoAttempt("a1", "s3cret") }
        val s = store(api, inbox, paired); s.attach(platform, null); runCurrent()
        assertEquals(listOf(Triple("a1", "s3cret", "hand-off")), api.claims)
        assertEquals(listOf(pair), paired)
    }

    @Test fun `an attempt restored from the screen's saved state claims on the return that arrives later`() = runTest {
        val inbox = SsoReturnInbox(); val paired = mutableListOf<PairResult>()
        val api = FakeApi(listOf("Harbour ID")).apply { claimAnswer = { pair } }
        val s = store(api, inbox, paired); s.attach(FakePlatform(), SsoAttempt.decode("a1|s3cret")); runCurrent()
        assertIs<SsoState.Waiting>(s.state.value)
        inbox.offer(SsoReturn("a1", "hand-off")); runCurrent()
        assertEquals(listOf(pair), paired)
    }

    @Test fun `a return with no attempt held is left alone`() = runTest {
        val inbox = SsoReturnInbox().apply { offer(SsoReturn("a1", "h")) }
        val api = FakeApi(listOf("Harbour ID"))
        val s = store(api, inbox); s.attach(FakePlatform(), null); runCurrent()
        assertTrue(api.claims.isEmpty())
        assertEquals(SsoState.Idle, s.state.value)
    }

    @Test fun `the attempt's saved form round-trips and refuses what is not one`() {
        assertEquals(SsoAttempt("a1", "s3cret"), SsoAttempt.decode(SsoAttempt("a1", "s3cret").encode()))
        for (bad in listOf(null, "", "a1", "|s", "a|", "|")) assertNull(SsoAttempt.decode(bad), "$bad")
    }
}
