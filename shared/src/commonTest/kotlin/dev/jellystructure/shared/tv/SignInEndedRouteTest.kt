package dev.jellystructure.shared.tv

import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5781 (DEB-5777, FR-143-5) — `sign_in_removed` reaches its own callback, `signed_out` keeps its own, and neither reaches the other. */
class SignInEndedRouteTest {
    // The callbacks never suspend, so the coroutine completes before startCoroutine returns.
    private fun route(type: String): List<String> {
        val calls = mutableListOf<String>()
        suspend { routeSignInEnded(type, { calls += "signed_out" }, { calls += "sign_in_removed" }) }
            .startCoroutine(kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { it.getOrThrow() })
        return calls
    }

    @Test fun `sign_in_removed reaches only its own callback`() = assertEquals(listOf("sign_in_removed"), route("sign_in_removed"))
    @Test fun `signed_out keeps its callback`() = assertEquals(listOf("signed_out"), route("signed_out"))
    @Test fun `another type reaches neither`() = assertEquals(emptyList(), route("something_else"))
}
