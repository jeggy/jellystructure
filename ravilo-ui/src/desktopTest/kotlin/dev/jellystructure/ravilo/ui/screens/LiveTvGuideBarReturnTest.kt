package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.sun.net.httpserver.HttpServer
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DEB-4284 — Down from the guide's top bar goes back into the guide (the first channel row), as it does on Movies,
 * Series and Discover. The guide gave the bar no `onDown`, so the key had nowhere to go and focus stayed on the tab.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class LiveTvGuideBarReturnTest {
    private val channelName = "Guide Test Channel"

    private fun <T> withGuideServer(block: (TvApiClient) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = if (ex.requestURI.path.endsWith("/channels")) {
                """[{"channel_id":"c1","name":"$channelName","number":1,"order":1,"shown":true,"logo_url":null,"category":"","has_guide":false}]"""
            } else "[]"
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            return block(TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:${server.address.port}", { "token" }, platform = "tv"))
        } finally { server.stop(0) }
    }

    @Test
    fun `Down from the top bar returns to the first channel row`() = withGuideServer { client ->
        runComposeUiTest {
            setContent { LiveTvGuideScreen(store = LiveTvGuideStore(client), displayName = "A", onBack = {}, onTuneChannel = {}) }
            val onChannel = isFocused().and(hasAnyDescendant(hasText(channelName)))
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(channelName)).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()

            // The viewer is on the bar's first tab (the repro: Up out of the grid, onto Home).
            onNode(hasText("Home")).performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            assertTrue(onAllNodes(onChannel).fetchSemanticsNodes().isEmpty(), "focus should be on the bar")
            onNode(isFocused()).assertTextContains("Home")

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNode(onChannel).assertExists()
        }
    }
}
