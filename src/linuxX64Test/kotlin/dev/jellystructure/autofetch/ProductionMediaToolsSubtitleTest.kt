package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2872 (FR-6.4) — a subtitle that breaks the mux never fails the episode; a video or audio failure still does. */
class ProductionMediaToolsSubtitleTest {
    private val probe = """{"streams":[
        {"index":0,"codec_type":"video","width":1920,"height":1080,"bit_rate":"5000000"},
        {"index":1,"codec_type":"audio","bit_rate":"128000"},
        {"index":2,"codec_type":"subtitle"}],"format":{"duration":"60"}}"""

    /** [muxes] decides, per ffmpeg command line, how it goes; ffprobe answers [probe]. */
    private class FakeShell(val probe: String, val mux: (String) -> ShellResult) : FileFixShell {
        val commands = ArrayList<String>()
        val files = HashSet<String>()
        override suspend fun run(cmd: String): ShellResult = ShellResult(0, probe)
        override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? {
            commands += cmd
            val r = mux(cmd)
            if (r.ok) files += marker
            return r
        }
        override fun writeText(path: String, text: String) = true
        override fun exists(path: String) = path in files
        override fun remove(path: String) { files -= path }
        override fun stat(path: String): Pair<Long, Long>? = null
        override fun listDir(dir: String) = emptyList<String>()
    }

    private val spec = DownloadSpec("https://a.example/m.mpd", "", "dash", "/w/out.mkv", "fao", "fao")
    private fun withSub(cmd: String) = "-map 0:2" in cmd

    @Test
    fun `a mux that fails with the subtitle input is run again without it and the episode is kept`() = runBlocking<Unit> {
        val shell = FakeShell(probe) { if (withSub(it)) ShellResult(1, "x\nInvalid data found when processing input") else ShellResult(0, "") }
        val r = ProductionMediaTools(shell).download(spec) { false }
        assertTrue(r.ok)
        assertEquals("no subtitle: ffmpeg could not mux it: Invalid data found when processing input", r.message)
        assertEquals(2, shell.commands.size)
        assertTrue(withSub(shell.commands[0])); assertFalse(withSub(shell.commands[1]))
        assertTrue(shell.exists("/w/out.mkv"))
    }

    @Test
    fun `a mux that works with the subtitle runs once and says nothing`() = runBlocking<Unit> {
        val shell = FakeShell(probe) { ShellResult(0, "") }
        val r = ProductionMediaTools(shell).download(spec) { false }
        assertTrue(r.ok); assertEquals("", r.message); assertEquals(1, shell.commands.size)
    }

    @Test
    fun `a mux that fails without the subtitle too is a failure`() = runBlocking<Unit> {
        val shell = FakeShell(probe) { ShellResult(1, if (withSub(it)) "sub broke" else "video broke") }
        val r = ProductionMediaTools(shell).download(spec) { false }
        assertFalse(r.ok)
        assertEquals("ffmpeg exit 1: video broke", r.message)
        assertEquals(2, shell.commands.size)
    }

    @Test
    fun `a source with no subtitle that fails is not run twice`() = runBlocking<Unit> {
        val noSub = probe.replace("""{"index":2,"codec_type":"subtitle"}""", """{"index":2,"codec_type":"data"}""")
        val shell = FakeShell(noSub) { ShellResult(1, "audio broke") }
        val r = ProductionMediaTools(shell).download(spec) { false }
        assertFalse(r.ok); assertEquals(1, shell.commands.size)
    }
}
