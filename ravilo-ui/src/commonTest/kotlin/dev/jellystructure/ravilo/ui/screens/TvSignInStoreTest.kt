package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvSession
import dev.jellystructure.shared.tv.TvSignInCodeResponse
import dev.jellystructure.shared.tv.TvSignInPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-5) — the TV sign-in screen's state machine on a fake API, on virtual time: waiting → approving → signed in,
 * an expired code replaced silently, `available: false` straight to the password form, and the could-not-reach-the-server state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSignInStoreTest {
    private val signedIn = PairResult(TvSession("tv-1", "u-1", "Anna", isAdmin = false), "token-1")

    private class FakeApi(
        val codes: ArrayDeque<Result<TvSignInCodeResponse>>,
        val polls: ArrayDeque<Result<TvSignInPoll>>,
    ) : TvSignInApi {
        val codeCalls = mutableListOf<Pair<String, String?>>()
        val pollCalls = mutableListOf<Pair<String, String>>()
        val pollTimes = mutableListOf<Long>()
        var clock: () -> Long = { 0L }
        override suspend fun code(deviceId: String, deviceName: String?): TvSignInCodeResponse {
            codeCalls += deviceId to deviceName
            return codes.removeFirst().getOrThrow()
        }
        override suspend fun poll(code: String, secret: String): TvSignInPoll {
            pollCalls += code to secret
            pollTimes += clock()
            return polls.removeFirst().getOrThrow()
        }
    }

    private fun minted(code: String, link: String? = "https://ravilo.example.net/tv?c=$code") =
        Result.success(TvSignInCodeResponse(available = true, code = code, expiresAt = 600L, secret = "secret-$code", linkUrl = link))

    private fun api(codes: List<Result<TvSignInCodeResponse>>, polls: List<Result<TvSignInPoll>> = emptyList()) =
        FakeApi(ArrayDeque(codes), ArrayDeque(polls))

    private fun TestScope.store(api: FakeApi, persisted: MutableList<PairResult> = mutableListOf()): TvSignInStore {
        api.clock = { currentTime }
        return TvSignInStore(api, { "device-1" }, { "Living room TV" }, { persisted += it })
    }

    private val waiting = Result.success<TvSignInPoll>(TvSignInPoll.Waiting(approving = false, approverName = null))
    private fun approving(name: String) = Result.success<TvSignInPoll>(TvSignInPoll.Waiting(approving = true, approverName = name))

    @Test
    fun `a code is asked for with this TV's id and name and shown`() = runTest {
        val a = api(listOf(minted("K7P4QX")), listOf(waiting))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(1)
        assertEquals(listOf<Pair<String, String?>>("device-1" to "Living room TV"), a.codeCalls)
        assertEquals(TvSignInState.ShowingCode("K7P4QX", "https://ravilo.example.net/tv?c=K7P4QX", null), s.state.value)
    }

    @Test
    fun `it polls every 3 seconds with the code and its secret`() = runTest {
        val a = api(listOf(minted("K7P4QX")), List(5) { waiting })
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(15_001)
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L, 15_000L), a.pollTimes)
        assertTrue(a.pollCalls.all { it == "K7P4QX" to "secret-K7P4QX" })
    }

    @Test
    fun `waiting then approving then signed in`() = runTest {
        val persisted = mutableListOf<PairResult>()
        val a = api(listOf(minted("K7P4QX")), listOf(waiting, approving("Anna"), Result.success(TvSignInPoll.Done(signedIn))))
        val s = store(a, persisted)
        backgroundScope.launch { s.run() }
        advanceTimeBy(3_001)
        assertNull((s.state.value as TvSignInState.ShowingCode).approverName)
        advanceTimeBy(3_000)
        assertEquals("Anna", (s.state.value as TvSignInState.ShowingCode).approverName)
        assertTrue(persisted.isEmpty())
        advanceTimeBy(3_000)
        assertEquals(TvSignInState.SignedIn("Anna"), s.state.value)
        assertEquals(listOf(signedIn), persisted)
        // Signed in is the end: no further poll.
        advanceTimeBy(30_000)
        assertEquals(3, a.pollCalls.size)
    }

    @Test
    fun `an approver that backs out clears the name`() = runTest {
        val a = api(listOf(minted("K7P4QX")), listOf(approving("Anna"), waiting))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(3_001)
        assertEquals("Anna", (s.state.value as TvSignInState.ShowingCode).approverName)
        advanceTimeBy(3_000)
        assertNull((s.state.value as TvSignInState.ShowingCode).approverName)
    }

    @Test
    fun `an expired code is replaced by a new one without leaving the code state`() = runTest {
        val a = api(listOf(minted("AAAAAA"), minted("BBBBBB")), listOf(Result.success(TvSignInPoll.Invalid), waiting))
        val s = store(a)
        val seen = mutableListOf<TvSignInState>()
        backgroundScope.launch { s.state.collect { seen += it } }
        backgroundScope.launch { s.run() }
        advanceTimeBy(3_001)
        assertEquals("BBBBBB", (s.state.value as TvSignInState.ShowingCode).code)
        assertEquals(2, a.codeCalls.size)
        // After the first code appeared, nothing but codes was ever shown: no Loading, no Unreachable between them.
        val afterFirst = seen.dropWhile { it !is TvSignInState.ShowingCode }
        assertTrue(afterFirst.all { it is TvSignInState.ShowingCode })
        // And the next poll is against the new code.
        advanceTimeBy(3_000)
        assertEquals("BBBBBB" to "secret-BBBBBB", a.pollCalls.last())
    }

    @Test
    fun `a failed request for a new code is the could-not-reach-the-server state`() = runTest {
        val a = api(listOf(minted("AAAAAA"), Result.failure(RuntimeException("down"))), listOf(Result.success(TvSignInPoll.Invalid)))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(3_001)
        assertEquals(TvSignInState.Unreachable, s.state.value)
    }

    @Test
    fun `a failed first request is the could-not-reach-the-server state`() = runTest {
        val a = api(listOf(Result.failure(RuntimeException("down"))))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(1)
        assertEquals(TvSignInState.Unreachable, s.state.value)
        assertTrue(a.pollCalls.isEmpty())
    }

    @Test
    fun `a server that cannot sign a TV in opens the password form with no code and no polling`() = runTest {
        val a = api(listOf(Result.success(TvSignInCodeResponse(available = false))))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(30_000)
        assertEquals(TvSignInState.PasswordOnly, s.state.value)
        assertTrue(a.pollCalls.isEmpty())
    }

    @Test
    fun `without a link the code is shown with no link`() = runTest {
        val a = api(listOf(minted("K7P4QX", link = null)), listOf(waiting))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(1)
        assertNull((s.state.value as TvSignInState.ShowingCode).linkUrl)
    }

    @Test
    fun `one dropped poll keeps the code and five in a row do not`() = runTest {
        val boom = Result.failure<TvSignInPoll>(RuntimeException("dropped"))
        val a = api(listOf(minted("K7P4QX")), listOf(boom, boom, boom, boom, waiting, boom, boom, boom, boom, boom))
        val s = store(a)
        backgroundScope.launch { s.run() }
        advanceTimeBy(12_001)   // four failures
        assertIs<TvSignInState.ShowingCode>(s.state.value)
        advanceTimeBy(3_000)    // a success: the count starts again
        advanceTimeBy(12_000)   // four more failures
        assertIs<TvSignInState.ShowingCode>(s.state.value)
        advanceTimeBy(3_000)    // the fifth in a row
        assertEquals(TvSignInState.Unreachable, s.state.value)
        assertEquals(10, a.pollCalls.size)
    }

    @Test
    fun `cancelling stops the polling`() = runTest {
        val a = api(listOf(minted("K7P4QX")), List(20) { waiting })
        val s = store(a)
        val job = backgroundScope.launch { s.run() }
        advanceTimeBy(6_001)
        assertEquals(2, a.pollCalls.size)
        job.cancel(CancellationException("password form opened"))
        advanceTimeBy(30_000)
        assertEquals(2, a.pollCalls.size)
    }

    @Test
    fun `running again after leaving asks for a fresh code`() = runTest {
        val a = api(listOf(minted("AAAAAA"), minted("BBBBBB")), List(4) { waiting })
        val s = store(a)
        val first = backgroundScope.launch { s.run() }
        advanceTimeBy(3_001)
        first.cancel()
        backgroundScope.launch { s.run() }
        advanceTimeBy(1)
        assertEquals("BBBBBB", (s.state.value as TvSignInState.ShowingCode).code)
    }

    @Test
    fun `the code is shown grouped three and three`() {
        assertEquals("K7P 4QX", groupCode("K7P4QX"))
        assertEquals("AB", groupCode("AB"))
    }
}
