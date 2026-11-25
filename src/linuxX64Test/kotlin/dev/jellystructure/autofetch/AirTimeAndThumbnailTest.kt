package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5432 (FR-1, FR-5a, auto-fetch FR-15.7, amended 2026-11-24) — the time a `datetime` file name holds, `datetime` counted as a date numbering,
 * and where a bulletin's thumbnail is cut. The stand-in programme is *Kvøldbók*, bulletins at 18.30 and 21.00 on 2026-11-23.
 */
class AirTimeAndThumbnailTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    @Test
    fun `FR-5a — the air time is the time in the name when the name's date is the air date`() {
        val name = "Kvøldbók - S2647E683 - 2026-11-23 21.00 - Storm closes the harbour.mkv"
        assertEquals("21:00", Numbering.airTimeInName(name, "2026-11-23"))
        assertEquals("21:00", Numbering.airTimeInName(name, "2026-11-23T00:00:00"), "an air date longer than ten characters")
        assertEquals("06:05", Numbering.airTimeInName("Kvøldbók - S2644E736 - 2026-11-03 06.05.mkv", "2026-11-03"), "a name with no episode name")
        assertEquals("00:00", Numbering.airTimeInName("Kvøldbók - S2648E001 - 2026-11-30 00.00 - x.mkv", "2026-11-30"))
    }

    @Test
    fun `FR-5a — no time when the date differs or the name has none or the air date is missing`() {
        val name = "Kvøldbók - S2647E683 - 2026-11-23 21.00 - Storm closes the harbour.mkv"
        assertNull(Numbering.airTimeInName(name, "2026-11-22"))
        assertNull(Numbering.airTimeInName(name, null))
        assertNull(Numbering.airTimeInName("Dagbók - S2026E302 - 2026-10-31 - Day 302.mkv", "2026-10-31"), "date numbering writes no time")
        assertNull(Numbering.airTimeInName("Show - S01E02 - Pilot.mkv", "2026-10-31"))
        assertNull(Numbering.airTimeInName("Kvøldbók - S2647E683 - 2026-11-23 25.00 - x.mkv", "2026-11-23"), "not a clock")
    }

    @Test
    fun `FR-5a — a name the numbering wrote gives back the time it was written from`() {
        for (minute in listOf(0, 61, 1110, 1260, 1439)) {
            val se = Numbering.fromDateTime("2026-11-23", minute)!!
            val name = Numbering.fileName("Kvøldbók", se, "2026-11-23", "Storm closes the harbour", Numbering.clock(minute))
            val want = (minute / 60).toString().padStart(2, '0') + ":" + (minute % 60).toString().padStart(2, '0')
            assertEquals(want, Numbering.airTimeInName(name, "2026-11-23"), name)
        }
    }

    @Test
    fun `FR-1 — a datetime fetcher's series counts as date numbered as a date one does`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        assertEquals(setOf(f.seriesPath.trimEnd('/')), h.store.dateNumberedSeriesPaths())
    }

    @Test
    fun `FR-15_7 — the engine asks for the thumbnail under the recipe's numbering`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertTrue(h.tools.thumbnailNumberings.isNotEmpty())
        assertTrue(h.tools.thumbnailNumberings.all { it == "datetime" }, h.tools.thumbnailNumberings.toString())
    }

    // ── the moments ────────────────────────────────────────────────────────────

    private val tools = ProductionMediaTools()

    @Test
    fun `FR-15_7 — under datetime a file over 150 s is tried at 90 or 120 or 150 s first then FR-9_4's order`() {
        val fixed = tools.thumbnailMoments(1500.0, "date")
        assertEquals(listOf(750, 525, 975, 375, 1125), fixed)
        assertEquals(listOf(90, 120, 150) + fixed, tools.thumbnailMoments(1500.0, "datetime"))
    }

    @Test
    fun `FR-15_7 — date or source or mapped or a file of 150 s or less keep today's order`() {
        for (n in listOf("date", "source", "mapped", "")) assertEquals(tools.thumbnailMoments(1500.0, "date"), tools.thumbnailMoments(1500.0, n), n)
        assertEquals(tools.thumbnailMoments(150.0, "date"), tools.thumbnailMoments(150.0, "datetime"))
        assertEquals(tools.thumbnailMoments(100.0, "date"), tools.thumbnailMoments(100.0, "datetime"))
    }

    private class FakeShell(val yavg: (Int) -> String) : FileFixShell {
        val files = HashMap<String, String>()
        val cuts = ArrayList<Int>()
        override suspend fun run(cmd: String): ShellResult {
            if (cmd.contains("ffmpeg")) {
                val at = Regex("""-ss (\d+)""").find(cmd)!!.groupValues[1].toInt()
                cuts += at
                files[Regex("""'([^']*)'""").findAll(cmd).last().groupValues[1]] = "jpg@$at"
                return ShellResult(0, "lavfi.signalstats.YAVG=${yavg(at)}")
            }
            return ShellResult(0, "")
        }
        override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? = null
        override fun writeText(path: String, text: String) = true
        override fun exists(path: String) = path in files
        override fun remove(path: String) { files.remove(path) }
        override fun stat(path: String): Pair<Long, Long>? = null
        override fun listDir(dir: String) = emptyList<String>()
    }

    @Test
    fun `FR-15_7 — a black frame at 90 s is refused and the next moment is cut and the slot keeps the frame that passed`() = runBlocking<Unit> {
        val shell = FakeShell { if (it == 90) "0.0" else "127.0" }
        assertTrue(ProductionMediaTools(shell).thumbnail("/m/e.mkv", 1500.0, "/m/e-thumb.jpg", "datetime"))
        assertEquals(listOf(90, 120), shell.cuts)
        assertEquals("jpg@120", shell.files["/m/e-thumb.jpg"])
    }

    @Test
    fun `FR-15_7 — when 90 or 120 or 150 s are all black the engine falls back to the middle`() = runBlocking<Unit> {
        val shell = FakeShell { if (it in setOf(90, 120, 150)) "0.0" else "127.0" }
        assertTrue(ProductionMediaTools(shell).thumbnail("/m/e.mkv", 1500.0, "/m/e-thumb.jpg", "datetime"))
        assertEquals(listOf(90, 120, 150, 750), shell.cuts)
    }

    @Test
    fun `FR-15_7 — under date the first cut is the middle`() = runBlocking<Unit> {
        val shell = FakeShell { "127.0" }
        assertTrue(ProductionMediaTools(shell).thumbnail("/m/e.mkv", 1500.0, "/m/e-thumb.jpg", "date"))
        assertEquals(listOf(750), shell.cuts)
    }
}
