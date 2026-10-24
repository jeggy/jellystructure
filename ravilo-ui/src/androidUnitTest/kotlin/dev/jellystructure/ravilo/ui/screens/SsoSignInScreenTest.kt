package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DEB-2233 (FR-12) — the sign-in screen's *Sign in with ‹name›* set on a phone, from the names the server lists: none leaves the screen as it is, one
 * is one button, two are two, a TV shows none whatever the server lists, and a start that answers unavailable takes them away. A tap waits with
 * *Finish signing in in your browser*, and Cancel brings the form back. Answers come from an interceptor, so nothing touches a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class SsoSignInScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun client(providers: String, startStatus: Int = 200): TvApiClient {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val http = HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val r = chain.request()
                    val (code, body) = when (r.url.encodedPath) {
                        "/api/tv/login/providers" -> 200 to providers
                        "/api/tv/login/sso/start" ->
                            if (startStatus == 200) 200 to """{"authorizeUrl":"https://idp.example.net/authorize","attemptId":"a1","secret":"s3cret"}"""
                            else startStatus to """{"reason":"unavailable"}"""
                        else -> 404 to ""
                    }
                    Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "No")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        return TvApiClient(http, baseUrl = "http://fake.invalid", deviceToken = { null }, platform = "phone")
    }

    private fun render(api: TvApiClient) {
        rule.setContent { LoginScreen(store = LoginStore(api), onSignedIn = {}) }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun waitForButtons() = rule.waitUntil(5_000) { count(SsoTags.button(0)) > 0 }

    @Test fun `with no names the screen is exactly the form`() {
        render(client("""{"providers":[]}"""))
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
        assertEquals(0, count(SsoTags.button(0)))
        assertEquals(0, count(SsoTags.OR))
    }

    @Test fun `one name is one button with the name verbatim above the username field`() {
        render(client("""{"providers":["Harbour ID"]}"""))
        waitForButtons()
        rule.onNodeWithText("Sign in with Harbour ID").assertIsDisplayed()
        assertEquals(0, count(SsoTags.button(1)))
        assertEquals(1, count(SsoTags.OR))
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
    }

    @Test fun `two names are two buttons in the order listed`() {
        render(client("""{"providers":["Harbour ID","Fjord Login"]}"""))
        waitForButtons()
        rule.onNodeWithText("Sign in with Harbour ID").assertIsDisplayed()
        rule.onNodeWithText("Sign in with Fjord Login").assertIsDisplayed()
        assertEquals(1, count(SsoTags.button(1)))
        assertEquals(0, count(SsoTags.button(2)))
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
    fun `a TV shows no button whatever the server lists`() {
        render(client("""{"providers":["Harbour ID"]}"""))
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        assertEquals(0, count(SsoTags.button(0)))
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
    }

    @Test fun `a start that answers unavailable takes the buttons away and leaves the form`() {
        render(client("""{"providers":["Harbour ID"]}""", startStatus = 503))
        waitForButtons()
        rule.onNodeWithTag(SsoTags.button(0)).performClick()
        rule.waitUntil(5_000) { count(SsoTags.button(0)) == 0 }
        assertEquals(0, count(SsoTags.WAITING))
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
    }

    @Test fun `a tap waits for the browser and Cancel brings the form back`() {
        render(client("""{"providers":["Harbour ID"]}"""))
        waitForButtons()
        rule.onNodeWithTag(SsoTags.button(0)).performClick()
        rule.waitUntil(5_000) { count(SsoTags.WAITING) > 0 }
        rule.onNodeWithText("Finish signing in in your browser").assertIsDisplayed()
        assertEquals(0, count(LoginTags.USERNAME))
        rule.onNodeWithTag(SsoTags.CANCEL).performClick()
        rule.waitUntil(5_000) { count(LoginTags.USERNAME) > 0 }
        rule.onNodeWithTag(SsoTags.button(0)).assertIsDisplayed()
        assertEquals(0, count(SsoTags.WAITING))
    }
}
