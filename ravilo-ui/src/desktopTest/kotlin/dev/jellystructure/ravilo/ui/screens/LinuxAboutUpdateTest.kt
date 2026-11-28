package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.ClientUpdate
import dev.jellystructure.ravilo.ui.components.UpdateSurface
import dev.jellystructure.ravilo.ui.components.updateDotFor
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloThemes
import dev.jellystructure.shared.tv.RaviloVersion
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5851 (FR-7) — Linux Settings ▸ About: the quiet line sits between Version and Server, and a click opens the releases page. */
@OptIn(ExperimentalTestApi::class)
class LinuxAboutUpdateTest {
    private fun store(): SettingsStore {
        val config = RaviloConfig(themeFollow = false, theme = RaviloThemes.NOIR, themeDark = RaviloThemes.NOIR)
        val body = RaviloWireJson.encodeToString(RaviloConfig.serializer(), config)
        val client = HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    val answer = if (request.url.encodedPath == "/api/tv/config") body else null
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(if (answer != null) 200 else 404).message(if (answer != null) "OK" else "Not Found")
                        .body((answer ?: "").toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
        return SettingsStore(TvApiClient(client, baseUrl = "http://fake.invalid", deviceToken = { "test" }, platform = "desktop"))
    }

    @Test fun `the line is between Version and Server and a click opens the releases page`() = runComposeUiTest {
        ClientUpdate.resetForTest(RaviloVersion(1, 73))
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val store = store()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                // Tall, so About's last row is composed and measured, not scrolled out.
                Box(Modifier.requiredSize(1000.dp, 3000.dp)) { DesktopSettingsPanel(store, "Olivar", {}, {}, onChangePassword = {}, onSignOut = {}, onClose = {}) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Ravilo 1.73 is available")).fetchSemanticsNodes().isNotEmpty() }
        val version = onAllNodes(hasText("Version")).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        val line = onNodeWithText("Ravilo 1.73 is available").fetchSemanticsNode().boundsInRoot.top
        // "Server" can also name a sidebar entry; the About row is the one below the line.
        val server = onAllNodes(hasText("Server")).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        assertTrue(version < line && line < server, "Version, then the line, then Server")
        onNodeWithText("Download it from the Ravilo releases page.").assertExists()
        // The row is taller than the test window shows; the click is the row's own action.
        onNodeWithText("Ravilo 1.73 is available").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("https://github.com/jeggy/jellystructure/releases/latest"), opened)
    }

    @Test fun `with no newer release there is no line`() = runComposeUiTest {
        ClientUpdate.resetForTest(null)
        val store = store()
        setContent { DesktopSettingsPanel(store, "Olivar", {}, {}, onChangePassword = {}, onSignOut = {}, onClose = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodes(hasText("Ravilo 1.73 is available", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun `opening About once for a release takes the nav dot away`() = runComposeUiTest {
        ClientUpdate.resetForTest(RaviloVersion(1, 73))
        assertTrue(updateDotFor(ClientUpdate.notice.value, ClientUpdate.seenFlow.value[UpdateSurface.LinuxAbout]))
        val store = store()
        setContent { DesktopSettingsPanel(store, "Olivar", {}, {}, onChangePassword = {}, onSignOut = {}, onClose = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertFalse(updateDotFor(ClientUpdate.notice.value, ClientUpdate.seenFlow.value[UpdateSurface.LinuxAbout]))
    }
}
