package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2480 (FR-6.3) — the command lines, how a failure is read, and the one-more-run without subtitles. Stand-in corpus only. */
class YtDlpTest {
    private val ua = "jellystructure/test (https://example.invalid)"
    private val dl = YtDlpDownload("https://varpid.example.fo/sendingar/dagbok/16", "/lib/Nordvest/Season 2026/.jellystructure/fetch_Nordvest - S2026E16.mkv", "fao", "fao", ua)

    // ---- the command lines ----

    @Test
    fun `every command ignores config and cache and skips the generic extractor and names us and sleeps between requests`() {
        for (cmd in listOf(YtDlpCommands.listing("https://varpid.example.fo/x", ua), YtDlpCommands.info("https://varpid.example.fo/x", ua), YtDlpCommands.download(dl, true))) {
            assertTrue("--ignore-config" in cmd, cmd); assertTrue("--no-cache-dir" in cmd, cmd)
            assertTrue("--use-extractors default,-generic" in cmd, cmd)
            assertTrue("--user-agent '$ua'" in cmd, cmd)
            assertTrue("--sleep-requests ${LaneRules.PAGE_INTERVAL_SEC}" in cmd, cmd)
            assertTrue(cmd.startsWith("nice -n 19 ionice -c3 "), cmd)
        }
    }

    @Test
    fun `the listing is the flat playlist capped at 100 and the info is one item`() {
        val l = YtDlpCommands.listing("https://varpid.example.fo/x", ua)
        assertTrue("--flat-playlist --playlist-end 100 -J 'https://varpid.example.fo/x'" in l, l)
        val i = YtDlpCommands.info("https://varpid.example.fo/x", ua)
        assertTrue("--no-playlist --skip-download --print " in i && i.endsWith("'https://varpid.example.fo/x'"), i)
        assertFalse("--flat-playlist" in i); assertFalse(" -J " in i, "the whole document is 11 MB for one video (DEB-3404)")
    }

    @Test
    fun `the download takes the best video and audio into mkv and tags the audio in its own mux`() {
        val c = YtDlpCommands.download(dl, withSubs = false)
        assertTrue("-f 'bv*+ba/b'" in c, c)
        assertTrue("--merge-output-format mkv --remux-video mkv" in c, c)
        assertTrue("--postprocessor-args 'Merger+ffmpeg_o:-metadata:s:a:0 language=fao'" in c, c)
        assertTrue("--postprocessor-args 'VideoRemuxer+ffmpeg_o:-metadata:s:a:0 language=fao'" in c, c)
        assertFalse("--embed-subs" in c); assertFalse("--write-subs" in c)
        assertTrue(c.endsWith("'https://varpid.example.fo/sendingar/dagbok/16'"), c)
    }

    @Test
    fun `no audio language means no tag`() {
        assertFalse("postprocessor-args" in YtDlpCommands.download(dl.copy(audioLang = ""), false))
    }

    @Test
    fun `subtitles are written and embedded and limited to the recipe's language and or all of them`() {
        val c = YtDlpCommands.download(dl, withSubs = true)
        assertTrue("--write-subs --embed-subs --sub-langs 'fao.*'" in c, c)
        assertTrue("--sub-langs 'all,-live_chat'" in YtDlpCommands.download(dl.copy(subtitleLang = ""), true))
    }

    @Test
    fun `everything yt-dlp writes is under the work file`() {
        val c = YtDlpCommands.download(dl, true)
        val tmp = YtDlpCommands.tempDir(dl.outPath)
        assertEquals("${dl.outPath}.part", tmp)
        assertTrue("-P 'temp:$tmp'" in c, c); assertTrue("-P 'subtitle:$tmp'" in c, c)
        assertTrue("-o '/lib/Nordvest/Season 2026/.jellystructure/fetch_Nordvest - S2026E16.%(ext)s'" in c, c)
    }

    @Test
    fun `a percent sign in the file name is doubled and a quote in the link cannot leave its quotes`() {
        val c = YtDlpCommands.download(dl.copy(outPath = "/w/.jellystructure/fetch_100% of it.mkv", link = "https://varpid.example.fo/a'b;touch x"), false)
        assertTrue("-o '/w/.jellystructure/fetch_100%% of it.%(ext)s'" in c, c)
        assertTrue("'https://varpid.example.fo/a'\\''b;touch x'" in c, c)
    }

    // ---- reading a failure ----

    @Test
    fun `an http status and DRM and no extractor and a missing binary are told apart`() {
        assertEquals(429, YtDlpErrors.classify(1, "ERROR: [x] a: Unable to download webpage: HTTP Error 429: Too Many Requests").status)
        val drm = YtDlpErrors.classify(1, "ERROR: [x] a: This video is DRM protected")
        assertTrue(drm.drm); assertEquals(RecipeRefusal.DRM, drm.message)
        assertEquals(YtDlpTexts.NO_EXTRACTOR, YtDlpErrors.classify(1, "ERROR: No suitable extractor found for URL https://varpid.example.fo/x").message)
        assertEquals(YtDlpTexts.NO_EXTRACTOR, YtDlpErrors.classify(1, "ERROR: Unsupported URL: https://varpid.example.fo/x").message)
        assertEquals(YtDlpOutput.NOT_INSTALLED, YtDlpErrors.classify(127, "sh: 1: yt-dlp: not found"))
        val plain = YtDlpErrors.classify(1, "[x] a: Downloading\nERROR: [x] a: Video unavailable")
        assertFalse(plain.drm); assertNull(plain.status); assertEquals("yt-dlp: [x] a: Video unavailable", plain.message)
        assertFalse(YtDlpErrors.classify(1, "ERROR: drmatic failure").drm, "a word that merely contains drm is not DRM")
    }

    // ---- an item's own metadata ----

    @Test
    fun `the info shows a video format and a duration and an audio-only item has no video`() {
        val f = assertNotNull(YtDlpInfo.facts("""{"duration": 1800.5, "formats":[{"vcodec":"none"},{"vcodec":"avc1.64001f"}]}"""))
        assertTrue(f.hasVideo); assertEquals(1800.5, f.durationSec)
        assertFalse(assertNotNull(YtDlpInfo.facts("""{"duration": 60, "formats":[{"vcodec":"none"},{"vcodec":"none"}]}""")).hasVideo)
        assertTrue(assertNotNull(YtDlpInfo.facts("""{"formats":[{"format_id":"hls-1"}]}""")).hasVideo, "an unknown codec is not 'none'")
        assertNull(YtDlpInfo.facts("not json"))
        assertTrue(assertNotNull(YtDlpInfo.facts("""WARNING: x
{"duration": 5, "formats":[{"vcodec":"vp9"}]}""")).hasVideo, "a line before the document is tolerated")
    }

    @Test
    fun `the two printed lines read as one answer and an answer past the capture cap still does`() {
        val two = "WARNING: x\n" + """{"duration": 19, "vcodec": "av01"}""" + "\n" + """[{"vcodec":"none","has_drm":false},{"vcodec":"vp9","has_drm":false}]"""
        val f = assertNotNull(YtDlpInfo.facts(two)); assertTrue(f.hasVideo); assertEquals(19.0, f.durationSec)
        assertFalse(YtDlpInfo.allDrm(two))
        assertTrue(YtDlpInfo.allDrm("""{"duration":1}""" + "\n" + """[{"has_drm":true}]"""))
        val big = """{"duration": 19}""" + "\n" + "[" + List(150_000) { """{"vcodec":"vp9","has_drm":false}""" }.joinToString(",") + "]"
        assertTrue(big.length > 4_000_000)
        assertTrue(assertNotNull(YtDlpInfo.facts(big)).hasVideo)
    }

    @Test
    fun `every format DRM is DRM and one clear format is not`() {
        assertTrue(YtDlpInfo.allDrm("""{"formats":[{"has_drm":true},{"has_drm":true}]}"""))
        assertFalse(YtDlpInfo.allDrm("""{"formats":[{"has_drm":true},{"has_drm":false}]}"""))
        assertFalse(YtDlpInfo.allDrm("""{"formats":[]}"""))
    }

    // ---- the production binding, over a fake shell ----

    private class FakeShell(val go: (String) -> ShellResult) : FileFixShell {
        val commands = ArrayList<String>()
        val files = HashSet<String>()
        override suspend fun run(cmd: String): ShellResult { commands += cmd; return go(cmd) }
        override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? {
            commands += cmd
            if (shouldStop()) return null
            val r = go(cmd)
            if (r.ok) files += marker
            return r
        }
        override fun writeText(path: String, text: String) = true
        override fun exists(path: String) = path in files
        override fun remove(path: String) { files -= path }
        override fun stat(path: String): Pair<Long, Long>? = null
        override fun listDir(dir: String) = emptyList<String>()
    }

    private fun cleaned(shell: FakeShell) = shell.commands.any { it == "rm -rf '${YtDlpCommands.tempDir(dl.outPath)}'" }

    @Test
    fun `a download that works runs once and says nothing and and its temporary folder is removed`() = runBlocking<Unit> {
        val shell = FakeShell { ShellResult(0, "") }
        val r = ProductionYtDlp(shell).download(dl) { false }
        assertTrue(r.ok); assertEquals("", r.message)
        assertEquals(1, shell.commands.count { "--embed-subs" in it })
        assertTrue(cleaned(shell))
    }

    @Test
    fun `a failure that blames the subtitle is run once more without it and the episode is kept`() = runBlocking<Unit> {
        val shell = FakeShell { if ("--embed-subs" in it) ShellResult(1, "ERROR: Postprocessing: Conversion failed for subtitle") else ShellResult(0, "") }
        val r = ProductionYtDlp(shell).download(dl) { false }
        assertTrue(r.ok)
        assertEquals("no subtitle: yt-dlp could not embed it: Postprocessing: Conversion failed for subtitle", r.message)
        assertEquals(2, shell.commands.count { it.startsWith("nice") })
        assertTrue(cleaned(shell))
    }

    @Test
    fun `a failure that is not about a subtitle is not run again`() = runBlocking<Unit> {
        val shell = FakeShell { ShellResult(1, "ERROR: [x] a: Video unavailable") }
        val r = ProductionYtDlp(shell).download(dl) { false }
        assertFalse(r.ok); assertEquals("yt-dlp: [x] a: Video unavailable", r.message)
        assertEquals(1, shell.commands.count { it.startsWith("nice") })
        assertTrue(cleaned(shell))
    }

    @Test
    fun `DRM and an http status are carried to the engine and never retried`() = runBlocking<Unit> {
        val drm = ProductionYtDlp(FakeShell { ShellResult(1, "ERROR: This video is DRM protected, subtitle or not") }).download(dl) { false }
        assertTrue(drm.drm); assertFalse(drm.ok)
        val shell = FakeShell { ShellResult(1, "ERROR: unable to download subtitle: HTTP Error 429: Too Many Requests") }
        val busy = ProductionYtDlp(shell).download(dl) { false }
        assertEquals(429, busy.status); assertEquals(1, shell.commands.count { it.startsWith("nice") })
    }

    @Test
    fun `a stop is a stop and a missing binary says so`() = runBlocking<Unit> {
        assertEquals("stopped", ProductionYtDlp(FakeShell { ShellResult(0, "") }).download(dl) { true }.message)
        val missing = ProductionYtDlp(FakeShell { ShellResult(127, "sh: 1: yt-dlp: not found") }).download(dl) { false }
        assertEquals(YtDlpTexts.NOT_INSTALLED, missing.message)
    }

    @Test
    fun `the listing and the info are the json on success and the classified failure otherwise`() = runBlocking<Unit> {
        val ok = ProductionYtDlp(FakeShell { ShellResult(0, "{\"entries\":[]}") }).listing("https://varpid.example.fo/x", ua)
        assertTrue(ok.ok); assertEquals("{\"entries\":[]}", ok.body)
        val bad = ProductionYtDlp(FakeShell { ShellResult(1, "ERROR: HTTP Error 403: Forbidden") }).info("https://varpid.example.fo/x", ua)
        assertFalse(bad.ok); assertEquals(403, bad.status)
    }

    @Test
    fun `yt-dlp's upload date reads as a calendar date`() {
        assertEquals("2026-11-02", Numbering.isoDate("20261102"))
        assertNull(Numbering.isoDate("20261302"))
    }
}
