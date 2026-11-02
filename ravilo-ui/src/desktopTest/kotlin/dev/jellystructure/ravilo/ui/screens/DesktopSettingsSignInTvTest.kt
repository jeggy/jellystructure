package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloThemes
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-2233 (FR-6, amendment 2026-10-29, finding F6) — the desktop app's Settings window: Account has *Sign in a TV*
 * after the password place and before *Sign out*, and a click hands over; with no callback nothing is drawn.
 * Runs on the Linux runner, so it draws the GNOME preferences (the Mac tab shares the callback and the string).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSettingsSignInTvTest {
    private fun fakeApi(path: String, body: String): TvApiClient {
        val client = HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    val answer = if (request.url.encodedPath == path) body else null
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(if (answer != null) 200 else 404).message(if (answer != null) "OK" else "Not Found")
                        .body((answer ?: "").toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        return TvApiClient(client, baseUrl = "http://fake.invalid", deviceToken = { "test" }, platform = "desktop")
    }

    private fun store(): SettingsStore {
        val config = RaviloConfig(themeFollow = false, theme = RaviloThemes.NOIR, themeDark = RaviloThemes.NOIR)
        return SettingsStore(fakeApi("/api/tv/config", RaviloWireJson.encodeToString(RaviloConfig.serializer(), config)))
    }

    @Test fun `the row follows Change password and a click hands over`() = runComposeUiTest {
        var opened = 0
        val store = store()
        setContent {
            DesktopSettingsPanel(store, "Olivar", {}, {}, onChangePassword = {}, onSignOut = {}, onClose = {}, onSignInTv = { opened++ })
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isNotEmpty() }
        val tops = listOf("Change password", "Sign in a TV", "Sign out").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "password place, then Sign in a TV, then Sign out")
        onNodeWithText("Sign in a TV").performClick()
        assertEquals(1, opened)
    }

    @Test fun `without the callback there is no row`() = runComposeUiTest {
        val store = store()
        setContent {
            DesktopSettingsPanel(store, "Olivar", {}, {}, onChangePassword = {}, onSignOut = {}, onClose = {})
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodes(hasText("Sign in a TV")).fetchSemanticsNodes().size)
    }
}
