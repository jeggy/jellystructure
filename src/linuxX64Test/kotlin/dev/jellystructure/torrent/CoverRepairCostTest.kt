package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.link
import platform.posix.mkdir
import platform.posix.system
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 315 FR-315-7 — the repair card's cost: files with a name outside the library, counted once, their sizes summed. */
@OptIn(ExperimentalForeignApi::class)
class CoverRepairCostTest {
    private val base = "/tmp/jellystructure-test-coverrepaircost-${getpid()}"
    private val lib get() = "$base/library"
    private val seeds get() = "$base/cross-seed-links"

    private fun write(path: String, text: String) { val f = fopen(path, "w")!!; fputs(text, f); fclose(f) }
    private fun size(path: String) = SystemFileSystem.metadataOrNull(Path(path))!!.size

    @BeforeTest fun setUp() {
        system("rm -rf '$base'")
        mkdir(base, 0x1EDu); mkdir(lib, 0x1EDu); mkdir(seeds, 0x1EDu); mkdir("$lib/S1", 0x1EDu)
    }

    @AfterTest fun tearDown() { system("rm -rf '$base'") }

    private suspend fun guard(): Pair<SeedingGuard, AppConfig> {
        val cfg = AppConfig(libraries = listOf(LibraryMapping(name = "Series", localPath = "$lib/")))
        val store = ConfigStore("$base/config.toml").also { it.load() }
        store.update(cfg)
        return SeedingGuard(SeedingSnapshot(store, QBittorrentClient())) to cfg
    }

    private suspend fun cost(vararg paths: String): CoverRepairCost {
        val (g, cfg) = guard()
        return coverRepairCost(paths.map { it to null }, { g.linkVerdict(it, cfg) }) { SystemFileSystem.metadataOrNull(Path(it))?.size }
    }

    @Test fun `two shared files and one single-name file give two files and their summed size`() = runBlocking<Unit> {
        write("$lib/S1/E1.mkv", "aaaa"); link("$lib/S1/E1.mkv", "$seeds/E1.mkv")
        write("$lib/S1/E2.mkv", "bbbbbbbb"); link("$lib/S1/E2.mkv", "$seeds/E2.mkv")
        write("$lib/S1/E3.mkv", "cc")
        val c = cost("$lib/S1/E1.mkv", "$lib/S1/E2.mkv", "$lib/S1/E3.mkv")
        assertEquals(2, c.sharedFiles)
        assertEquals(size("$lib/S1/E1.mkv") + size("$lib/S1/E2.mkv"), c.sharedBytes)
    }

    @Test fun `extra names only inside the library cost nothing`() = runBlocking<Unit> {
        write("$lib/S1/E1.mkv", "aaaa"); link("$lib/S1/E1.mkv", "$lib/S1/E1.copy.mkv")
        val c = cost("$lib/S1/E1.mkv")
        assertEquals(CoverRepairCost(0, 0L, 0.0), c)
    }

    @Test fun `episodes that share one file count once and the size is one decimal of GB`() = runBlocking<Unit> {
        write("$lib/S1/E1.mkv", "a"); link("$lib/S1/E1.mkv", "$seeds/E1.mkv")
        val (g, cfg) = guard()
        val files = listOf("$lib/S1/E1.mkv" to 4_600_000_000L, "$lib/S1/E1.mkv" to 4_600_000_000L)
        val c = coverRepairCost(files, { g.linkVerdict(it, cfg) }) { null }
        assertEquals(1, c.sharedFiles)
        assertEquals(4_600_000_000L, c.sharedBytes)
        assertEquals(4.6, c.sharedGb)
    }
}
