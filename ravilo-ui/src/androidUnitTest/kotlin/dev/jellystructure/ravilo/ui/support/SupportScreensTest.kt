package dev.jellystructure.ravilo.ui.support

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportSendResult
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.shared.tv.SupportTicketRow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-1501 — the screens as a viewer meets them (acceptance 2, 4, 5, 6, 8 and 9), drawn with a fake server. Stand-in
 * data is round 3's: Eyð, #41 / #38 / #36, Hervør and Hallur. Real playback, the unread dot across the app's bars and
 * the platforms' own windows are the QA ticket's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SupportScreensTest {
    @get:Rule val rule = createComposeRule()

    @Before fun arm() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        SupportLane.clear()
    }

    private class Fake : SupportGateway {
        var tickets: List<SupportTicketRow> = emptyList()
        var thread: SupportThread? = null
        var send: suspend () -> SupportSendResult = { error("no send") }
        val sent = mutableListOf<String>()
        override suspend fun unread() = 0
        override suspend fun tickets() = tickets
        override suspend fun thread(n: Long) = thread ?: error("no thread")
        override suspend fun markRead(n: Long, upTo: Long) {}
        override suspend fun file(body: String): SupportSendResult { sent += body; return send() }
        override suspend fun post(n: Long, body: String): SupportSendResult { sent += body; return send() }
    }

    private fun row(n: Long, cls: String?, title: String?, state: String = "OPEN", holder: String = "AI_AGENT", unread: Int = 0) =
        SupportTicketRow(number = n, state = state, holder = holder, ticketClass = cls, title = title, lastActivity = 1_760_000_000L, unread = unread)

    private fun viewer(id: Long, body: String) = SupportMessageView(id, "MESSAGE", "VIEWER", "Eyð", body, createdAt = 1_760_000_000L)
    private fun receipt(id: Long) = SupportMessageView(id, "SYSTEM", event = "RECEIVED", eventArgs = listOf("41"), createdAt = 1_760_000_000L)

    private fun show(lang: String = "en", content: @Composable () -> Unit) {
        rule.setContent { WithLocale(lang, content) }
        rule.waitForIdle()
    }

    private fun text(t: String) = rule.onNodeWithText(t, useUnmergedTree = true)

    // ── FR-3 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an empty list shows the chat state and a primary Write to support`() {
        val store = SupportListStore(Fake())
        show { SupportListScreen(store, onBack = {}, onOpen = {}, onWrite = {}, onGone = {}) }
        rule.onNodeWithTag(SupportTags.EMPTY).assertExists()
        text("No tickets yet").assertExists()
        text("Write to support").assertExists()
    }

    @Test fun `rows show the title or Ticket n, never the first message, and a chip only for a class with a string`() {
        val gw = Fake().apply {
            tickets = listOf(
                row(41, "BUG", "Sound drops in Nordvest S02E04 on the sitting-room TV", unread = 1),
                row(38, "REQUEST", null),
                row(36, "QUESTION", "Keeping subtitles on by default", state = "RESOLVED"),
                row(35, "ACCOUNT", null),
            )
        }
        val store = SupportListStore(gw)
        show { SupportListScreen(store, onBack = {}, onOpen = {}, onWrite = {}, onGone = {}) }
        rule.onAllNodesWithTag(SupportTags.ROW).assertCountEquals(4)
        text("Sound drops in Nordvest S02E04 on the sitting-room TV").assertExists()
        text("Ticket #38").assertExists()
        text("Ticket #35").assertExists()
        text("Resolved").assertExists()
        text("Bug").assertExists()
        text("Request").assertExists()
        text("Question").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Account", useUnmergedTree = true).fetchSemanticsNodes().size)
        // With tickets, a quieter Write to support sits at the foot (not the empty state's).
        rule.onNodeWithTag(SupportTags.WRITE).assertExists()
    }

    @Test fun `the list reads in Danish`() {
        val store = SupportListStore(Fake())
        show("da") { SupportListScreen(store, onBack = {}, onOpen = {}, onWrite = {}, onGone = {}) }
        text("Ingen sager endnu").assertExists()
        text("Skriv til support").assertExists()
    }

    // ── FR-4 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun threadScreen(gw: Fake, number: Long? = 41, isPhone: Boolean = true, onFiled: (SupportThread) -> Unit = {}): SupportThreadStore {
        val store = SupportThreadStore(gw, number)
        show { SupportThreadScreen(store, viewerName = "Eyð", isPhone = isPhone, onBack = {}, onFiled = onFiled, onGone = {}) }
        return store
    }

    @Test fun `the thread draws the viewer's bubble on the right with the receipt line`() {
        val gw = Fake().apply { thread = SupportThread(row(41, "BUG", "Sound drops"), listOf(viewer(1, "Sound drops in S02E04"), receipt(2))) }
        threadScreen(gw)
        text("Sound drops in S02E04").assertExists()
        text("Eyð · you").assertExists()
        text("Received · #41 · logged as a bug · titled “Sound drops”").assertExists()
        text("Open · waiting for a first reply").assertExists()   // nobody but the viewer has written (FR-9)
    }

    @Test fun `a long message clamps and Show the rest opens it inside the bubble`() {
        val long = (1..40).joinToString("\n") { "line $it of a very long message" }
        val gw = Fake().apply { thread = SupportThread(row(41, null, null), listOf(viewer(1, long))) }
        threadScreen(gw)
        rule.onNodeWithTag(SupportTags.SHOW_REST).assertExists()
        rule.onNodeWithTag(SupportTags.SHOW_REST).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(SupportTags.SHOW_REST).fetchSemanticsNodes().size)
    }

    // A soft-wrapped 2 000-character body (acceptance 8) needs real glyph widths, which Robolectric does not have; the
    // ravilo-web Playwright spec asserts it in Chromium.

    @Test fun `a short message has no Show the rest`() {
        val gw = Fake().apply { thread = SupportThread(row(41, null, null), listOf(viewer(1, "short"))) }
        threadScreen(gw)
        assertEquals(0, rule.onAllNodesWithTag(SupportTags.SHOW_REST).fetchSemanticsNodes().size)
    }

    @Test fun `while AI Agent is composing the last entry says so`() {
        val gw = Fake().apply { thread = SupportThread(row(41, null, null), listOf(viewer(1, "hello")), aiComposing = true) }
        threadScreen(gw)
        rule.onNodeWithTag(SupportTags.AI_WRITING).assertExists()
        text("AI Agent is writing").assertExists()
    }

    @Test fun `a resolved ticket reads Resolved and its composer asks to write again`() {
        val gw = Fake().apply { thread = SupportThread(row(36, "QUESTION", "Keeping subtitles", state = "RESOLVED"), listOf(viewer(1, "q"))) }
        threadScreen(gw, number = 36)
        text("Resolved").assertExists()
        text("Write here again to reopen").assertExists()
    }

    // ── FR-5 / FR-6 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the first message screen has the hint, the phone line on a phone only, and a disabled Send`() {
        threadScreen(Fake(), number = null, isPhone = true)
        text("New ticket").assertExists()
        text("Open · waiting for a first reply").assertExists()
        text("Tell us what happened and where — which TV or phone helps.").assertExists()
        rule.onNodeWithTag(SupportTags.SENT_FROM).assertExists()
        rule.onNodeWithTag(SupportTags.SEND).assertIsNotEnabled()
    }

    @Test fun `the web and the computers show no Sent from the phone line`() {
        threadScreen(Fake(), number = null, isPhone = false)
        assertEquals(0, rule.onAllNodesWithTag(SupportTags.SENT_FROM).fetchSemanticsNodes().size)
    }

    @Test fun `sending locks the composer with Sending and draws no bubble until the server answers`() {
        val answer = CompletableDeferred<SupportSendResult>()
        val gw = Fake().apply {
            thread = SupportThread(row(41, null, null), listOf(viewer(1, "first")))
            send = { answer.await() }
        }
        threadScreen(gw)
        rule.onNodeWithTag(SupportTags.COMPOSER + "-field").performTextInput("second message")
        rule.onNodeWithTag(SupportTags.SEND).assertIsEnabled().performClick()
        rule.waitForIdle()
        text("Sending…").assertExists()
        // Nothing is drawn for it as a bubble: the one bubble is still the first.
        assertEquals(1, rule.onAllNodesWithTag(SupportTags.BUBBLE).fetchSemanticsNodes().size)
        answer.complete(SupportSendResult.Sent(SupportThread(row(41, null, null), listOf(viewer(1, "first"), viewer(2, "second message")))))
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithTag(SupportTags.BUBBLE).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag(SupportTags.SENDING).fetchSemanticsNodes().size)
    }

    @Test fun `no answer shows Not sent with Try again and keeps the draft, and Try again sends it`() {
        var online = false
        val gw = Fake().apply {
            thread = SupportThread(row(41, null, null), listOf(viewer(1, "first")))
            send = { if (online) SupportSendResult.Sent(SupportThread(row(41, null, null), listOf(viewer(1, "first"), viewer(2, "kept draft")))) else throw RuntimeException("offline") }
        }
        threadScreen(gw)
        rule.onNodeWithTag(SupportTags.COMPOSER + "-field").performTextInput("kept draft")
        rule.onNodeWithTag(SupportTags.SEND).performClick()
        rule.waitForIdle()
        text("Not sent — you're offline. Your message is kept here.").assertExists()
        text("kept draft").assertExists()   // still in the field
        assertEquals(1, rule.onAllNodesWithTag(SupportTags.BUBBLE).fetchSemanticsNodes().size)
        online = true
        rule.onNodeWithTag(SupportTags.TRY_AGAIN).performClick()
        rule.waitForIdle()
        assertEquals(listOf("kept draft", "kept draft"), gw.sent)
        assertEquals(2, rule.onAllNodesWithTag(SupportTags.BUBBLE).fetchSemanticsNodes().size)
    }

    @Test fun `a 429 and a 400 each show their line and keep the draft`() {
        var status = 429
        val gw = Fake().apply {
            thread = SupportThread(row(41, null, null), listOf(viewer(1, "first")))
            send = { SupportSendResult.Refused(status) }
        }
        threadScreen(gw)
        rule.onNodeWithTag(SupportTags.COMPOSER + "-field").performTextInput("draft one")
        rule.onNodeWithTag(SupportTags.SEND).performClick()
        rule.waitForIdle()
        text("You've written a lot today. Try again later.").assertExists()
        status = 400
        rule.onNodeWithTag(SupportTags.SEND).performClick()
        rule.waitForIdle()
        text("That's too long to send in one message.").assertExists()
        text("draft one").assertExists()
    }

    @Test fun `the first message files a ticket and hands the 201 to the caller`() {
        var filed: SupportThread? = null
        val gw = Fake().apply {
            send = { SupportSendResult.Sent(SupportThread(row(43, "BUG", null), listOf(viewer(1, "my TV drops sound"), receipt(2)))) }
        }
        threadScreen(gw, number = null, onFiled = { filed = it })
        rule.onNodeWithTag(SupportTags.COMPOSER + "-field").performTextInput("my TV drops sound")
        rule.onNodeWithTag(SupportTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(43L, filed?.ticket?.number)
        assertTrue(gw.sent == listOf("my TV drops sound"))
    }

    // ── FR-8 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a thread read with no answer keeps the content under the offline strip`() {
        val gw = Fake().apply { thread = SupportThread(row(41, null, null), listOf(viewer(1, "kept"))) }
        val store = threadScreen(gw)
        gw.thread = null   // the next read fails
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { store.refresh() } }
        rule.waitForIdle()
        text("Offline — showing what was last loaded").assertExists()
        text("kept").assertExists()
    }
}
