package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-6) — the *Sign in a TV* flow on a fake API: the code field, the confirm screen, each outcome by kind, and a link's code
 * that is checked at once.
 */
class TvApproveStoreTest {
    private class FakeApi : TvApproveApi {
        var previewAnswer: () -> String = { "Living room TV" }
        var approveAnswer: () -> Unit = {}
        val previews = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        override suspend fun preview(code: String): String { previews += code; return previewAnswer() }
        override suspend fun approve(code: String) { approvals += code; approveAnswer() }
    }

    private fun http(status: Int) = TvApiError.Http(status, "body that is never shown")

    @Test fun `typing normalises the code and a spaced lower-case code is accepted`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        store.type("k7p 4qx")
        assertEquals(TvApproveState.Entering("K7P4QX"), store.state.value)
        store.submit()
        assertEquals(TvApproveState.Confirm("K7P4QX", "Living room TV"), store.state.value)
        assertEquals(listOf("K7P4QX"), api.previews)
    }

    @Test fun `a code of the wrong length or with an excluded letter is not sent`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        for (bad in listOf("K7P4Q", "K7P4Q0", "K7P4QI", "")) {
            store.type(bad); store.submit()
            val s = store.state.value
            assertIs<TvApproveState.Entering>(s)
            assertTrue(s.problem, bad)
        }
        assertTrue(api.previews.isEmpty())
    }

    @Test fun `the field takes no more than six characters`() {
        val store = TvApproveStore(FakeApi())
        store.type("K7P 4QX 9")
        assertEquals(TvApproveState.Entering("K7P4QX"), store.state.value)
    }

    @Test fun `typing again clears the problem`() = runTest {
        val store = TvApproveStore(FakeApi())
        store.type("K7P"); store.submit()
        assertTrue((store.state.value as TvApproveState.Entering).problem)
        store.type("K7P4")
        assertFalse((store.state.value as TvApproveState.Entering).problem)
    }

    @Test fun `a link's code is checked at once and the confirm screen names the TV`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        store.start("k7p4qx")
        assertEquals(TvApproveState.Confirm("K7P4QX", "Living room TV"), store.state.value)
        assertEquals(listOf("K7P4QX"), api.previews)
    }

    @Test fun `no link opens the empty field`() = runTest {
        val store = TvApproveStore(FakeApi())
        store.start(null)
        assertEquals(TvApproveState.Entering(), store.state.value)
    }

    @Test fun `Sign in approves the code and the TV is signed in`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        store.start("K7P4QX"); store.confirm()
        assertEquals(TvApproveState.Done("Living room TV"), store.state.value)
        assertEquals(listOf("K7P4QX"), api.approvals)
    }

    @Test fun `Cancel returns to an empty field and approves nothing`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        store.start("K7P4QX"); store.reset()
        assertEquals(TvApproveState.Entering(), store.state.value)
        assertTrue(api.approvals.isEmpty())
    }

    @Test fun `an unknown or expired code is expired whether the preview or the approval says so`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.previewAnswer = { throw http(404) }
        store.start("K7P4QX")
        assertEquals(TvApproveState.Failed("K7P4QX", TvApproveFailure.EXPIRED, null), store.state.value)
        api.previewAnswer = { "Living room TV" }; api.approveAnswer = { throw http(404) }
        store.start("K7P4QX"); store.confirm()
        assertEquals(TvApproveState.Failed("K7P4QX", TvApproveFailure.EXPIRED, "Living room TV"), store.state.value)
    }

    @Test fun `a code already used is used`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.approveAnswer = { throw http(409) }
        store.start("K7P4QX"); store.confirm()
        assertEquals(TvApproveFailure.USED, (store.state.value as TvApproveState.Failed).kind)
    }

    @Test fun `a server that says no for any other reason is unavailable`() = runTest {
        for (status in listOf(503, 429, 403, 500)) {
            val api = FakeApi(); val store = TvApproveStore(api)
            api.approveAnswer = { throw http(status) }
            store.start("K7P4QX"); store.confirm()
            assertEquals(TvApproveFailure.UNAVAILABLE, (store.state.value as TvApproveState.Failed).kind, "$status")
        }
    }

    @Test fun `no answer at all is could not reach the server`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.previewAnswer = { throw RuntimeException("connection refused") }
        store.start("K7P4QX")
        assertEquals(TvApproveFailure.UNREACHABLE, (store.state.value as TvApproveState.Failed).kind)
    }

    @Test fun `a failed fetch that is an Error and not an Exception is could not reach the server and Retry approves`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.approveAnswer = { throw Error("Fail to fetch") }
        store.start("K7P4QX"); store.confirm()
        assertEquals(TvApproveState.Failed("K7P4QX", TvApproveFailure.UNREACHABLE, "Living room TV"), store.state.value)
        api.approveAnswer = {}
        store.retry()
        assertEquals(TvApproveState.Done("Living room TV"), store.state.value)
    }

    @Test fun `a lookup that fails with an Error and not an Exception is could not reach the server`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.previewAnswer = { throw Error("Fail to fetch") }
        store.start("K7P4QX")
        assertEquals(TvApproveState.Failed("K7P4QX", TvApproveFailure.UNREACHABLE, null), store.state.value)
    }

    @Test fun `Retry after a failed approval approves again without asking for the TV`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        var calls = 0
        api.approveAnswer = { if (calls++ == 0) throw http(503) }
        store.start("K7P4QX"); store.confirm(); store.retry()
        assertEquals(TvApproveState.Done("Living room TV"), store.state.value)
        assertEquals(listOf("K7P4QX"), api.previews)
        assertEquals(listOf("K7P4QX", "K7P4QX"), api.approvals)
    }

    @Test fun `Retry after a failed preview looks the code up again`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        var calls = 0
        api.previewAnswer = { if (calls++ == 0) throw RuntimeException("offline") else "Bedroom TV" }
        store.start("K7P4QX"); store.retry()
        assertEquals(TvApproveState.Confirm("K7P4QX", "Bedroom TV"), store.state.value)
    }

    @Test fun `a link with a code that cannot be one is expired without asking the server`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        store.start("NOPE")
        assertEquals(TvApproveFailure.EXPIRED, (store.state.value as TvApproveState.Failed).kind)
        assertTrue(api.previews.isEmpty())
    }

    @Test fun `Enter a new code after an outcome clears the field`() = runTest {
        val api = FakeApi(); val store = TvApproveStore(api)
        api.previewAnswer = { throw http(404) }
        store.start("K7P4QX"); store.reset()
        assertEquals(TvApproveState.Entering(), store.state.value)
    }
}

class TvCodeTest {
    @Test fun `a spaced lower-case code normalises`() = assertEquals("K7P4QX", normalizeTvCode("  k7p 4qx "))

    @Test fun `six characters from the alphabet are a code`() = assertTrue(isValidTvCode("K7P4QX"))

    @Test fun `the wrong length is not a code`() { assertFalse(isValidTvCode("K7P4Q")); assertFalse(isValidTvCode("K7P4QXX")) }

    @Test fun `the letters and digits the alphabet leaves out are not a code`() {
        for (c in "01OI") assertFalse(isValidTvCode("K7P4Q$c"), "$c")
    }

    @Test fun `a code is grouped three and three`() = assertEquals("K7P 4QX", groupTvCode("K7P4QX"))
}

class TvLinkInboxTest {
    @Test fun `a link's code is taken once`() {
        TvLinkInbox.take()
        TvLinkInbox.offer("k7p4qx")
        assertEquals("K7P4QX", TvLinkInbox.take())
        assertNull(TvLinkInbox.take())
    }

    @Test fun `a link with a bad code leaves the inbox empty`() {
        TvLinkInbox.take()
        TvLinkInbox.offer("nope"); TvLinkInbox.offer(null); TvLinkInbox.offer("")
        assertNull(TvLinkInbox.take())
    }

    @Test fun `a held link comes back as the page it names`() {
        assertEquals("/account/tv/K7P4QX", tvLinkRoute("K7P4QX"))
    }
}

class TvCodeFromLocationTest {
    @Test fun `the QR's address carries its code`() = assertEquals("K7P4QX", tvCodeFromLocation("/tv", "?c=K7P4QX"))

    @Test fun `a lower-case or spaced code is read`() {
        assertEquals("K7P4QX", tvCodeFromLocation("/tv", "?c=k7p4qx"))
        assertEquals("K7P4QX", tvCodeFromLocation("/tv", "?c=K7P%204QX"))
    }

    @Test fun `other parameters around it do not matter`() = assertEquals("K7P4QX", tvCodeFromLocation("/tv", "?x=1&c=K7P4QX&y=2"))

    @Test fun `any other path is not a sign-in link`() {
        assertNull(tvCodeFromLocation("/", "?c=K7P4QX"))
        assertNull(tvCodeFromLocation("/tv/", "?c=K7P4QX"))
        assertNull(tvCodeFromLocation("/tv/home", "?c=K7P4QX"))
    }

    @Test fun `no code or a code that cannot be one is nothing`() {
        assertNull(tvCodeFromLocation("/tv", ""))
        assertNull(tvCodeFromLocation("/tv", "?c="))
        assertNull(tvCodeFromLocation("/tv", "?c=K7P4Q"))
        assertNull(tvCodeFromLocation("/tv", "?c=K7P4Q0"))
    }
}
