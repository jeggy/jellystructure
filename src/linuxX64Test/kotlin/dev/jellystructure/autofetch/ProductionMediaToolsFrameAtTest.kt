package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-3317 (FR-9.4) — *Pick a different moment* refuses a black frame the way the automatic cut does, and a refusal leaves the slot's picture. */
class ProductionMediaToolsFrameAtTest {
    /** ffmpeg prints [yavg] and writes its last argument; `mv -f a b` moves a file. Files are name → content. */
    private class FakeShell(val yavg: (Int) -> String) : FileFixShell {
        val files = HashMap<String, String>()
        private fun quoted(cmd: String) = Regex("""'([^']*)'""").findAll(cmd).map { it.groupValues[1] }.toList()
        override suspend fun run(cmd: String): ShellResult {
            val args = quoted(cmd)
            if (cmd.contains("ffmpeg")) {
                val at = Regex("""-ss (\d+)""").find(cmd)!!.groupValues[1].toInt()
                files[args.last()] = "jpg@$at"
                return ShellResult(0, "lavfi.signalstats.YAVG=${yavg(at)}")
            }
            if (cmd.startsWith("mv -f")) { files[args[1]] = files.remove(args[0])!!; return ShellResult(0, "") }
            return ShellResult(0, "")
        }
        override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? = null
        override fun writeText(path: String, text: String) = true
        override fun exists(path: String) = path in files
        override fun remove(path: String) { files.remove(path) }
        override fun stat(path: String): Pair<Long, Long>? = null
        override fun listDir(dir: String) = emptyList<String>()
    }

    private val slot = "/d/stills/1/0.jpg"

    @Test
    fun `a frame that is not black replaces the slot`() = runBlocking<Unit> {
        val shell = FakeShell { 127.0.toString() }
        shell.files[slot] = "old"
        assertTrue(ProductionMediaTools(shell).frameAt("/m/e.mkv", 30, slot))
        assertEquals("jpg@30", shell.files[slot])
        assertEquals(setOf(slot), shell.files.keys, "no cut-aside file is left behind")
    }

    @Test
    fun `an all black frame is refused and the slot keeps its previous picture`() = runBlocking<Unit> {
        val shell = FakeShell { if (it == 30) "0.0" else "127.0" }
        shell.files[slot] = "jpg@180"
        assertFalse(ProductionMediaTools(shell).frameAt("/m/e.mkv", 30, slot))
        assertEquals("jpg@180", shell.files[slot])
        assertEquals(setOf(slot), shell.files.keys)
    }
}
