package dev.jellystructure.ravilo.ui.apps

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.LoadErrorState
import dev.jellystructure.ravilo.ui.components.LocalOpenApps
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-4912 (FR-10, FR-11, FR-12, FR-15) — the screens, against a fake app list and a fake store. Nothing here needs a network or
 * a signed-in viewer, which is FR-11's own claim: nothing in Apps comes from jellystructure.
 */
private class Store(
    override var pins: List<String> = emptyList(),
    override var order: AllAppsOrder = AllAppsOrder.AZ,
    override var lastOpened: Map<String, Long> = emptyMap(),
) : TvAppsStore

private class Apps(var installed: List<TvApp>) : TvAppsPlatform {
    override val ownPackage = "dev.ravilo"
    val launched = mutableListOf<String>()
    var settings = 0
    override fun installedApps() = installed
    override fun launch(packageName: String): Boolean { launched += packageName; return true }
    override fun openSettings() { settings++ }
    override fun banner(packageName: String): ImageBitmap? = null
    override fun icon(packageName: String): ImageBitmap? = null
}

private fun a(label: String, pkg: String = "app.${label.lowercase().replace(" ", "")}") = TvApp(pkg, label, hasBanner = false)
private val TV = listOf(a("Netflix"), a("Kodi"), a("Ravilo", "dev.ravilo"), a("YouTube"))

private fun model(platform: Apps, store: Store = Store(), stock: String? = "Google TV", openedStock: () -> Unit = {}) =
    TvAppsModel(platform, store, { 1L }, stockHomeLabel = { stock }, openStockHomeAction = openedStock)

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AppsScreensTest {
    // FR-10 — the header's Apps entry exists only where the frame provides the action, which it does only while the hook is on.
    @Test fun `the bar has no Apps entry while the hook is off`() = runComposeUiTest {
        setContent { CompositionLocalProvider(LocalOpenApps provides null) { AppBar(navItems = listOf("A", "B"), onSearch = {}, onProfile = {}) } }
        onAllNodesWithText("Apps").assertCountEquals(0)
    }

    @Test fun `the bar has an Apps entry while the hook is on and pressing it opens Apps`() = runComposeUiTest {
        var opened = 0
        setContent { CompositionLocalProvider(LocalOpenApps provides { opened++ }) { AppBar(navItems = listOf("A", "B"), onSearch = {}, onProfile = {}) } }
        onNodeWithText("Apps").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    // FR-12 — the can't-reach screen carries Apps under Retry while the hook is on, and nothing new while it is off.
    @Test fun `the cant-reach screen offers Apps while the hook is on`() = runComposeUiTest {
        var opened = 0
        setContent { CompositionLocalProvider(LocalOpenApps provides { opened++ }) { LoadErrorState(LoadErrorKind.UNREACHABLE, onRetry = {}, offerApps = true) } }
        onNodeWithText("Retry").assertIsDisplayed()
        onNodeWithText("Apps").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    // DEB-5062 (FR-12) — the focus order QA could not reach on the Streamer: Retry keeps the arrival focus, Apps is one Down after it.
    @Test fun `the cant-reach screen focuses Retry first and Apps after it`() = runComposeUiTest {
        setContent { CompositionLocalProvider(LocalOpenApps provides {}) { LoadErrorState(LoadErrorKind.UNREACHABLE, onRetry = {}, offerApps = true) } }
        waitForIdle()
        onNode(isFocused() and hasAnyDescendant(hasText("Retry")) or (isFocused() and hasText("Retry")), useUnmergedTree = true).assertExists()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNode(isFocused() and hasAnyDescendant(hasText("Apps")) or (isFocused() and hasText("Apps")), useUnmergedTree = true).assertExists()
    }

    @Test fun `the cant-reach screen is today's screen while the hook is off`() = runComposeUiTest {
        setContent { CompositionLocalProvider(LocalOpenApps provides null) { LoadErrorState(LoadErrorKind.UNREACHABLE, onRetry = {}, offerApps = true) } }
        onNodeWithText("Retry").assertIsDisplayed()
        onAllNodesWithText("Apps").assertCountEquals(0)
    }

    @Test fun `a screen that did not ask for Apps does not get it`() = runComposeUiTest {
        setContent { CompositionLocalProvider(LocalOpenApps provides {}) { LoadErrorState(LoadErrorKind.UNREACHABLE, onRetry = {}) } }
        onAllNodesWithText("Apps").assertCountEquals(0)
    }

    // FR-11 — Settings, the stock home, then See all apps; Ravilo is not listed; each opens through the platform.
    @Test fun `Apps with no pins shows settings, the stock home and See all apps, and Ravilo is left out`() = runComposeUiTest {
        val platform = Apps(TV)
        var stockOpened = 0
        var seeAll = 0
        setContent { AppsScreen(model(platform, openedStock = { stockOpened++ }), onSeeAll = { seeAll++ }, topBar = null) }
        onNodeWithTag(AppsTags.SETTINGS).assertIsDisplayed()
        onNodeWithTag(AppsTags.STOCK_HOME).assertIsDisplayed()
        onNodeWithText("Google TV").assertIsDisplayed()
        onNodeWithTag(AppsTags.SEE_ALL).assertIsDisplayed()
        onAllNodesWithText("Ravilo").assertCountEquals(1)   // the signed-out screen's own brand row, and no tile
        onNodeWithTag(AppsTags.SETTINGS).performClick()
        onNodeWithTag(AppsTags.STOCK_HOME).performClick()
        onNodeWithTag(AppsTags.SEE_ALL).performClick()
        assertEquals(1, platform.settings)
        assertEquals(1, stockOpened)
        assertEquals(1, seeAll)
    }

    // FR-12 (DEB-4912 amendment, DEB-5063) — the hint names where Back goes: server setup from there, sign-in by default.
    @Test fun `the no-bar Back hint names server setup when Apps was opened from it and sign-in otherwise`() = runComposeUiTest {
        setContent { AppsFlow(model(Apps(TV)), onExit = {}, topBar = null, backHintKey = "apps.back_to_setup") }
        onNodeWithText("Back returns to server setup").assertIsDisplayed()
        onAllNodesWithText("Back returns to sign-in").assertCountEquals(0)
    }

    @Test fun `the no-bar Back hint defaults to sign-in`() = runComposeUiTest {
        setContent { AppsFlow(model(Apps(TV)), onExit = null, topBar = null) }
        onNodeWithText("Back returns to sign-in").assertIsDisplayed()
    }

    // FR-11/FR-14 — the pinned apps sit between the stock home and See all apps, and opening one launches it.
    @Test fun `a pinned app is a tile on Apps and opens its app`() = runComposeUiTest {
        val platform = Apps(TV)
        setContent { AppsScreen(model(platform, Store(pins = listOf("app.kodi"))), onSeeAll = {}, topBar = null) }
        onNodeWithTag(AppsTags.app("app.kodi")).assertIsDisplayed().performClick()
        assertEquals(listOf("app.kodi"), platform.launched)
        onNodeWithText("Kodi").assertIsDisplayed()
    }

    // FR-15 — All apps lists every app but Ravilo, and the pill switches the order and remembers it.
    @Test fun `All apps lists every app and its order pills switch and remember the order`() = runComposeUiTest {
        val store = Store(pins = listOf("app.kodi"))
        val m = model(Apps(TV), store)
        setContent { AllAppsScreen(m, topBar = null) }
        listOf("Kodi", "Netflix", "YouTube").forEach { onNodeWithText(it).assertIsDisplayed() }
        onNodeWithText("Pinned").assertIsDisplayed()
        onNodeWithTag(AppsTags.ORDER_RECENT).performClick()
        assertEquals(AllAppsOrder.RECENT, store.order)
        onNodeWithTag(AppsTags.ORDER_AZ).performClick()
        assertEquals(AllAppsOrder.AZ, store.order)
    }

    // FR-11 — nothing else installed says so, instead of an empty grid.
    @Test fun `an All apps with nothing installed says so`() = runComposeUiTest {
        setContent { AllAppsScreen(model(Apps(listOf(a("Ravilo", "dev.ravilo")))), topBar = null) }
        onNodeWithText("Nothing else is installed on this TV.").assertIsDisplayed()
    }
}
