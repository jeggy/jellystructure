package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 200 (FR-200-3) — the filename-suffix parser, built from the real production filename shapes
 * the spec catalogued. Flag order is deliberately not assumed (`.da.hi` and `.hi.da` both occur).
 */
class SidecarSubtitleScannerTest {

    private fun parse(suffix: String) = SidecarSubtitleScanner.parseSidecarSuffix(suffix)

    @Test
    fun `a bare two-letter language suffix resolves`() {
        assertEquals("da", parse(".da").language)
        assertEquals("en", parse(".en").language)
        assertEquals("fo", parse(".fo").language)
    }

    @Test
    fun `an SDH suffix sets sdh and carries the language`() {
        val r = parse(".en.hi")
        assertEquals("en", r.language)
        assertTrue(r.sdh)
        assertFalse(r.forced)
    }

    @Test
    fun `flag order is not assumed — hi before the language parses the same as after`() {
        val a = parse(".en.hi")
        val b = parse(".hi.en")
        assertEquals(a.language, b.language)
        assertEquals(a.sdh, b.sdh)
    }

    @Test
    fun `a forced suffix sets forced and carries the language`() {
        val r = parse(".da.forced")
        assertEquals("da", r.language)
        assertTrue(r.forced)
        assertFalse(r.sdh)
    }

    @Test
    fun `a bare stem with no suffix has no language forced or sdh`() {
        val r = parse("")
        assertEquals(null, r.language)
        assertFalse(r.forced)
        assertFalse(r.sdh)
    }

    @Test
    fun `a cc marker with no language sets sdh and leaves language null`() {
        val r = parse(".cc")
        assertEquals(null, r.language)
        assertTrue(r.sdh)
    }

    @Test
    fun `underscore-separated three-letter codes resolve`() {
        assertEquals("en", parse("_eng").language)
        assertEquals("sv", parse("_swe").language)
    }

    @Test
    fun `full language names resolve`() {
        assertEquals("fa", parse(".persian").language)
        assertEquals("ar", parse(".arabic").language)
        assertEquals("ru", parse(".russian").language)
        assertEquals("pt", parse(".portuguese-brazil").language)
    }

    @Test
    fun `an unrecognised token is never guessed as a language`() {
        val r = parse(".xyz123")
        assertEquals(null, r.language)
        assertFalse(r.forced)
        assertFalse(r.sdh)
    }

    @Test
    fun `a forced and sdh suffix combine`() {
        val r = parse(".sv.forced.hi")
        assertEquals("sv", r.language)
        assertTrue(r.forced)
        assertTrue(r.sdh)
    }

    // ── discover(): real files on disk, basename-matching against a sibling episode ────────────

    private lateinit var dir: String

    @BeforeTest
    fun setUp() {
        dir = "/tmp/jellystructure-test-sidecar-${getpid()}"
        SystemFileSystem.createDirectories(Path(dir))
    }

    @AfterTest
    fun tearDown() {
        runCatching { SystemFileSystem.list(Path(dir)).forEach { SystemFileSystem.delete(it) } }
        runCatching { SystemFileSystem.delete(Path(dir)) }
    }

    private fun touch(name: String) = FileIo.writeText(Path("$dir/$name"), "x")

    @Test
    fun `discover finds every sidecar shape beside the video`() {
        touch("Movie.mkv")
        touch("Movie.da.srt")
        touch("Movie.en.hi.srt")
        touch("Movie.srt") // bare, language undetermined
        touch("Movie_eng.srt")
        touch("Movie.persian.srt")
        touch("Movie.cc.srt")
        touch("Movie.mp4") // not a subtitle extension — ignored

        val tracks = SidecarSubtitleScanner.discover("$dir/Movie.mkv", startStreamIndex = 10)

        assertEquals(6, tracks.size)
        assertTrue(tracks.all { it.external })
        assertTrue(tracks.all { it.kind == dev.jellystructure.model.TrackKind.SUBTITLE })
        assertEquals(setOf("da", "en", "fa"), tracks.mapNotNull { it.language }.toSet())
        assertEquals(2, tracks.count { it.sdh }) // .en.hi and .cc
        assertEquals(2, tracks.count { it.language == null }) // the bare one, and .cc — neither names a language
    }

    @Test
    fun `discover never attributes a sibling episode's subtitle to this file`() {
        // A numeric-prefix collision: "Show S01E1" must not match "Show S01E10.fo.srt".
        touch("Show S01E1.mkv")
        touch("Show S01E10.fo.srt")
        touch("Show S01E1.da.srt") // this one DOES belong

        val tracks = SidecarSubtitleScanner.discover("$dir/Show S01E1.mkv", startStreamIndex = 0)

        assertEquals(listOf("da"), tracks.mapNotNull { it.language })
    }

    @Test
    fun `discover ignores hidden and AppleDouble files`() {
        touch("Movie.mkv")
        touch("._Movie.da.srt") // AppleDouble sidecar of the real file — must be excluded

        assertEquals(emptyList(), SidecarSubtitleScanner.discover("$dir/Movie.mkv", startStreamIndex = 0))
    }

    @Test
    fun `discover returns nothing when no sidecar exists`() {
        touch("Movie.mkv")

        assertEquals(emptyList(), SidecarSubtitleScanner.discover("$dir/Movie.mkv", startStreamIndex = 0))
    }

    @Test
    fun `discovered tracks carry synthetic stream indices and external specifiers`() {
        touch("Movie.mkv")
        touch("Movie.da.srt")

        val tracks = SidecarSubtitleScanner.discover("$dir/Movie.mkv", startStreamIndex = 5)

        assertEquals(5, tracks.single().streamIndex)
        assertEquals("ext:s:0", tracks.single().specifier)
        assertEquals("$dir/Movie.da.srt", tracks.single().externalPath)
    }
}
