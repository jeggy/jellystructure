package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.screens.fakeTvApiClient
import dev.jellystructure.ravilo.ui.seams.TvCastChannel
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.RaviloThemeState
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.Skin
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R380 (test 5) — the TV's Now playing at 1920 × 1080, in Aurora, Midnight and Noir. A TV composes at 960 × 540 dp
 * (1920 × 1080 px at density 2.0, `isHandset`'s note in `Platform.kt`), so that is the window these tests give the page.
 *
 * Two findings of the 2026-10-09 live test are pinned here, in en, da and fo (the spec still owed the controls row in
 * all three): the controls row's last button clipped to *Queu* (FR-R380-3, now a `FlowRow`), and the page's text
 * showing through the queue panel (FR-R380-5, now opaque). Stand-in titles only.
 *
 * "Screenshot" here is the rendered frame read back as pixels: the tests compare pixels inside one run and write each
 * frame to `build/test-screenshots/` for a person to look at; no golden image is kept (a golden would be tied to one
 * host's text rasteriser).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real text metrics and real pixels
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TvNowPlayingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val themeState = RaviloThemeState(Skin.AURORA)
    private var lang = androidx.compose.runtime.mutableStateOf("en")
    private var hidden = 0

    private val album = "Stand-in Album"
    private val queue = (1..5).map { n ->
        MusicTrackItem("t$n", "Stand-in Song $n", albumId = "a1", album = album, artists = listOf(MusicArtistRef("ar1", "Stand-in Artist")),
            durationMs = 200_000L)
    }

    @Before fun setUp() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        TvCastChannel.stopServerMusic = {}
        TvCastChannel.setLyrics(false)
        MusicEngine.clear()
        MusicEngine.loadPaused(queue, index = 1, positionMs = 30_000L, context = null)
        hidden = 0
    }

    @After fun tearDown() {
        TvCastChannel.appLeftScreen()
        MusicEngine.clear()
    }

    private fun render() {
        val api = fakeTvApiClient { null }
        rule.setContent {
            RaviloTheme(themeState) { WithLocale(lang.value) { TvNowPlayingScreen(api = api, onHide = { hidden++ }) } }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)   // the transport row's fade, the queue's scroll
    }

    /**
     * The frame, read back with `PixelCopy` the way `captureToImage` does. `captureToImage` itself waits for a draw
     * that the main looper has to run, and under Robolectric the test thread *is* the main thread: it times out
     * waiting for itself. Here the looper is turned by hand.
     */
    private fun capture(): ImageBitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        var result = Int.MIN_VALUE
        android.view.PixelCopy.request(rule.activity.window, bitmap, { result = it }, android.os.Handler(android.os.Looper.getMainLooper()))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(android.view.PixelCopy.SUCCESS, result, "PixelCopy did not read the frame back")
        return bitmap.asImageBitmap()
    }

    private fun frame(name: String): ImageBitmap {
        val image = capture()
        assertEquals(1920 to 1080, image.width to image.height, "$name: the frame is not the 1920 × 1080 a TV draws")
        runCatching {
            val dir = File("build/test-screenshots").apply { mkdirs() }
            File(dir, "tv-now-playing-$name.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return image
    }

    /** How many pixels at or right of [fromX] differ between two frames of the same size. */
    private fun differingPixels(a: ImageBitmap, b: ImageBitmap, fromX: Int): Int {
        val w = a.width - fromX
        val pa = IntArray(w * a.height); val pb = IntArray(w * a.height)
        a.readPixels(pa, startX = fromX, startY = 0, width = w, height = a.height)
        b.readPixels(pb, startX = fromX, startY = 0, width = w, height = b.height)
        return pa.indices.count { pa[it] != pb[it] }
    }

    private fun layoutOf(label: String): TextLayoutResult {
        val node = rule.onAllNodesWithText(label)[0].fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    // ── the controls row (FR-R380-3) ──

    @Test fun `the controls row fits the column in en da and fo, in every skin — no label clipped`() {
        render()
        val columnRight = 1920 - 96 * 2   // the page pads 96 dp from the right edge; 2 px per dp
        for (skin in Skin.entries) for (language in listOf("en", "da", "fo")) {
            themeState.skin = skin; lang.value = language
            rule.waitForIdle()
            press(Key.DirectionDown)   // any key raises the controls; ▼ is the one that changes no song
            val here = "${skin.name.lowercase()}-$language"
            val cell = t(if (TvCastChannel.lyricsOn.value) "tvmusic.lyrics_on" else "tvmusic.lyrics_off", language)
            val queueLabel = t("tvmusic.queue", language)
            for (label in listOf(cell, queueLabel)) {
                val node = rule.onAllNodesWithText(label)[0].fetchSemanticsNode()   // the button comes before the hint row's copy
                val layout = layoutOf(label)
                val box = node.boundsInRoot
                assertEquals(label, layout.layoutInput.text.text, "$here: the button reads '${layout.layoutInput.text.text}'")
                // (`hasVisualOverflow` is true for any `softWrap = false` text, so the width is compared with the text's own.)
                assertTrue(layout.size.width >= kotlin.math.ceil(layout.multiParagraph.maxIntrinsicWidth).toInt(),
                    "$here: '$label' is clipped (the text wants ${layout.multiParagraph.maxIntrinsicWidth} px and is given ${layout.size.width})")
                assertEquals(1, layout.lineCount, "$here: '$label' broke across lines")
                assertTrue(box.right <= columnRight, "$here: '$label' ends at ${box.right} px, past the column at $columnRight px")
                assertTrue(box.bottom <= 1080, "$here: '$label' ends at ${box.bottom} px, below the 1080 px frame")
            }
            frame("controls-$here")
        }
    }

    // ── the queue panel (FR-R380-5) ──

    @Test fun `the queue panel is opaque in every skin — the page behind it does not show through`() {
        render()
        val panelLeft = 1920 - 560 * 2   // the panel is 560 dp wide and sits against the right edge
        for (skin in Skin.entries) {
            themeState.skin = skin
            // Without the panel the line changes what is drawn there: the test can see a difference at all.
            TvCastChannel.startServerMusic(null)
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(600)
            val plain = frame("page-${skin.name.lowercase()}")
            TvCastChannel.startServerMusic("W".repeat(40) + " " + "M".repeat(40))
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(600)
            val withLine = frame("page-long-line-${skin.name.lowercase()}")
            assertTrue(differingPixels(plain, withLine, panelLeft) > 0, "${skin.name}: the page's own text does not reach under the panel — the test would prove nothing")

            press(Key.DirectionUp)
            val openLong = frame("queue-${skin.name.lowercase()}")
            TvCastChannel.startServerMusic(null)
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(600)
            val openPlain = frame("queue-plain-${skin.name.lowercase()}")
            assertEquals(0, differingPixels(openLong, openPlain, panelLeft), "${skin.name}: the page's text shows through the queue panel")
            press(Key.Back)   // closes the panel for the next skin
        }
    }

    @Test fun `the queue panel's headings — Now playing over the song, Up next from the album over the rest`() {
        render()
        press(Key.DirectionUp)
        rule.onNodeWithText(t("tvmusic.now_playing", "en")).assertExists()
        rule.onNodeWithText(t("tvmusic.up_next_from", "en", mapOf("x" to album))).assertExists()
        frame("queue-headings")
    }

    // ── the D-pad (FR-R380-4, FR-R380-5, FR-R380-6) ──

    @Test fun `every control is reachable with the D-pad`() {
        render()
        rule.onNode(isFocused()).assertExists()   // the page takes focus itself; nothing to navigate to first

        // ▼ lyrics: flips, and the hint under the row names the other way
        val lyricsBefore = TvCastChannel.lyricsOn.value
        press(Key.DirectionDown)
        assertEquals(!lyricsBefore, TvCastChannel.lyricsOn.value, "▼ did not toggle the lyrics")
        rule.onAllNodesWithText(t(lyricsHintKey(TvCastChannel.lyricsOn.value), "en"))[0].assertExists()

        // ▲ opens the queue; ▼ moves; OK jumps there and closes it
        assertEquals(1, MusicEngine.state.value.index)
        press(Key.DirectionUp)
        rule.onNodeWithText(t("tvmusic.up_next_from", "en", mapOf("x" to album))).assertExists()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(2, MusicEngine.state.value.index, "OK on the second row did not jump to it")
        rule.onAllNodesWithText(t("tvmusic.up_next_from", "en", mapOf("x" to album))).assertCountEquals(0)

        // ▶ next, ◀ previous
        press(Key.DirectionRight)
        assertEquals(3, MusicEngine.state.value.index, "▶ did not skip to the next song")
        press(Key.DirectionLeft)
        assertEquals(2, MusicEngine.state.value.index, "◀ did not go to the previous song")

        // ◀ closes the queue as well, and Back closes it without hiding the page
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        rule.onAllNodesWithText(t("tvmusic.up_next_from", "en", mapOf("x" to album))).assertCountEquals(0)
        press(Key.DirectionUp)
        press(Key.Back)
        assertEquals(0, hidden, "Back with the queue open hid the page")

        // Back hides the page, and the music stays loaded (it plays on)
        press(Key.Back)
        assertEquals(1, hidden, "Back did not hide the page")
        assertTrue(MusicEngine.state.value.active, "Back stopped the music")
    }
}
