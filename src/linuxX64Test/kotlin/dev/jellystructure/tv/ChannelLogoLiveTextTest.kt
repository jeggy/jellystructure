package dev.jellystructure.tv

import dev.jellystructure.io.FileIo
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-1511 (FR-1, FR-2) — an SVG logo with live `<text>` is refused at upload and marked in the picker. */
class ChannelLogoLiveTextTest {
    private val base = "/tmp/jellystructure-test-logo-live-text-${getpid()}"
    private val store = ChannelLogoStore(base)

    private val withText = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h4v4z"/><text x="1" y="2">Hi</text></svg>""".encodeToByteArray()
    private val pathsOnly = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h4v4z"/><rect width="2" height="2"/></svg>""".encodeToByteArray()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @AfterTest fun tearDown() {
        runCatching { SystemFileSystem.list(Path("$base/channel-logos")).forEach { SystemFileSystem.delete(it) } }
        runCatching { SystemFileSystem.delete(Path("$base/channel-logos")) }
        runCatching { SystemFileSystem.delete(Path(base)) }
    }

    private fun names() = runCatching { SystemFileSystem.list(Path("$base/channel-logos")).map { it.name } }.getOrDefault(emptyList())

    @Test fun `a live text SVG is refused with the reason and the fix and nothing is stored`() = runBlocking {
        val reason = assertNotNull(store.liveTextRefusal("brand.svg", withText))
        assertTrue("live text" in reason && "outline" in reason && "PNG" in reason, reason)
        assertNull(store.save("brand.svg", withText))
        assertEquals(emptyList(), names())
    }

    @Test fun `the check is case insensitive and catches a namespaced element`() {
        assertTrue(ChannelLogoStore.carriesLiveText("<svg><TEXT>a</TEXT></svg>".encodeToByteArray()))
        assertTrue(ChannelLogoStore.carriesLiveText("<svg:svg><svg:text>a</svg:text></svg:svg>".encodeToByteArray()))
        assertTrue(ChannelLogoStore.carriesLiveText("<svg><text\n x=\"1\">a</text></svg>".encodeToByteArray()))
        assertFalse(ChannelLogoStore.carriesLiveText("<svg><path d=\"M0 0\"/><title>texture</title></svg>".encodeToByteArray()))
    }

    @Test fun `a path only SVG and a PNG still store`() = runBlocking {
        assertNull(store.liveTextRefusal("ok.svg", pathsOnly))
        assertNotNull(store.save("ok.svg", pathsOnly))
        assertNotNull(store.save("ok.png", png))
        assertEquals(2, names().size)
    }

    @Test fun `a PNG whose bytes spell a text tag is not an SVG and is not refused`() {
        assertNull(store.liveTextRefusal("odd.png", "<text>".encodeToByteArray()))
    }

    @Test fun `list sets liveText on a stored SVG with text only`() = runBlocking {
        assertNotNull(store.save("clean-svg.svg", pathsOnly))
        assertNotNull(store.save("clean-png.png", png))
        // A file stored before the check existed: written straight into the directory.
        FileIo.writeBytes(Path("$base/channel-logos/1-old-text.svg"), withText)
        val byLabel = store.list().associateBy { it.label }
        assertEquals(3, byLabel.size)
        assertEquals(true, byLabel["old-text"]?.liveText)
        assertEquals(false, byLabel["clean-svg"]?.liveText)
        assertEquals(false, byLabel["clean-png"]?.liveText)
    }
}
