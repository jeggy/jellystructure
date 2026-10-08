package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.config.QBittorrentPathMapping
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 315 — FR-315-5's tests: a hard link to data outside the library is refused for an in-place edit. */
@OptIn(ExperimentalForeignApi::class)
class LinkGuardTest {
    private val base = "/tmp/jellystructure-test-linkguard-${getpid()}"
    private val lib get() = "$base/library"
    private val seeds get() = "$base/cross-seed-links"

    private fun write(path: String) {
        val f = fopen(path, "w")!!; fputs("x", f); fclose(f)
    }

    @BeforeTest fun setUp() {
        system("rm -rf '$base'")
        mkdir(base, 0x1EDu); mkdir(lib, 0x1EDu); mkdir("$lib/Film (2020)", 0x1EDu); mkdir("$lib/Other (2021)", 0x1EDu)
        mkdir(seeds, 0x1EDu); mkdir("$seeds/Film.2020.Release", 0x1EDu)
        // a: a library file whose other name is a torrent's copy (cross-seed's link dir).
        write("$lib/Film (2020)/Film.mkv"); link("$lib/Film (2020)/Film.mkv", "$seeds/Film.2020.Release/Film.mkv")
        // c: two library names for one file, nothing outside.
        write("$lib/Other (2021)/Other.mkv"); link("$lib/Other (2021)/Other.mkv", "$lib/Other (2021)/Other.copy.mkv")
        // s: a single name.
        write("$lib/Other (2021)/Single.mkv")
        SeedingRefusals.clearForTest()
    }

    @AfterTest fun tearDown() { system("rm -rf '$base'") }

    private fun config(qb: QBittorrentConfig? = null) = AppConfig(
        libraries = listOf(LibraryMapping(name = "Film", localPath = "$lib/")),
        qbittorrent = qb,
    )

    private suspend fun snapshotFor(cfg: AppConfig): SeedingSnapshot {
        val store = ConfigStore("$base/config.toml").also { it.load() }
        store.update(cfg)
        return SeedingSnapshot(store, QBittorrentClient())
    }

    @Test fun theRule() {
        assertEquals(LinkVerdict.SINGLE, linkVerdict(1, 0))
        assertEquals(LinkVerdict.OUTSIDE, linkVerdict(2, 1))
        assertEquals(LinkVerdict.LIBRARY_ONLY, linkVerdict(2, 2))
        assertEquals(LinkVerdict.OUTSIDE, linkVerdict(3, 2))
        // An index built before the file existed still counts the name being checked.
        assertEquals(LinkVerdict.OUTSIDE, linkVerdict(2, 0))
    }

    @Test fun theWalkCountsLibraryNamesOnly() {
        val counts = buildInsideCounts(listOf(lib))
        val a = linkInfo("$lib/Film (2020)/Film.mkv")!!
        val c = linkInfo("$lib/Other (2021)/Other.mkv")!!
        assertEquals(2L, a.links)
        assertEquals(1, counts[a.device to a.inode])
        assertEquals(2, counts[c.device to c.inode])
        // Single-name files are never indexed.
        val s = linkInfo("$lib/Other (2021)/Single.mkv")!!
        assertEquals(null, counts[s.device to s.inode])
    }

    @Test fun aHardLinkOutsideTheLibraryIsBlockedWithoutQbittorrent() = runBlocking<Unit> {
        val cfg = config(qb = null)
        val snap = snapshotFor(cfg)
        val guard = SeedingGuard(snap)
        val blocked = guard.check("$lib/Film (2020)/Film.mkv", cfg)
        assertIs<SeedingCheckResult.Blocked>(blocked)
        assertTrue(blocked.hardLink)
        assertEquals(HARD_LINK_REFUSAL, blocked.message)
        assertIs<SeedingCheckResult.Unconfigured>(guard.check("$lib/Other (2021)/Other.mkv", cfg))   // library-only links
        assertIs<SeedingCheckResult.Unconfigured>(guard.check("$lib/Other (2021)/Single.mkv", cfg))
        assertEquals(1, SeedingRefusals.lastWeek()["video"])
    }

    @Test fun unreachableQbittorrentStillBlocksAHardLinkAndReportsTheRest() = runBlocking<Unit> {
        val cfg = config(QBittorrentConfig(url = "http://127.0.0.1:1", enabled = true, noAuth = true,
            pathMappings = listOf(QBittorrentPathMapping(local = lib, remote = "/media/movies"))))
        val guard = SeedingGuard(snapshotFor(cfg))
        val hard = guard.check("$lib/Film (2020)/Film.mkv", cfg)
        assertIs<SeedingCheckResult.Blocked>(hard); assertTrue(hard.hardLink)
        assertIs<SeedingCheckResult.Unreachable>(guard.check("$lib/Other (2021)/Single.mkv", cfg))
    }

    @Test fun aRenameBasedWriterIsNotStoppedByAHardLink() = runBlocking<Unit> {
        val cfg = config(qb = null)
        val guard = SeedingGuard(snapshotFor(cfg))
        assertIs<SeedingCheckResult.Unconfigured>(guard.check("$lib/Film (2020)/Film.mkv", cfg, inPlace = false))
        assertEquals(null, SeedingRefusals.lastWeek()["video"])
    }

    @Test fun aPreviewDoesNotCountAsARefusal() = runBlocking<Unit> {
        val cfg = config(qb = null)
        val guard = SeedingGuard(snapshotFor(cfg))
        assertIs<SeedingCheckResult.Blocked>(guard.check("$lib/Film (2020)/Film.mkv", cfg, countRefusal = false))
        assertEquals(null, SeedingRefusals.lastWeek()["video"])
    }

    @Test fun aTorrentIsMatchedToAnOutsideName() {
        val unmapped = QBTorrent(hash = "h", name = "Film.2020.Release", state = "stalledUP", savePath = "/media/cross-seed-links",
            contentPath = "/media/cross-seed-links/Film.2020.Release")
        val other = QBTorrent(hash = "o", name = "Other", state = "stalledUP", savePath = "/x", contentPath = "/x/Other.Release")
        val outside = "$seeds/Film.2020.Release/Film.mkv"
        assertTrue(SeedingDamageCheck.torrentCovers(unmapped, outside, null))
        assertTrue(!SeedingDamageCheck.torrentCovers(other, outside, null))
        val mapped = QBTorrent(hash = "m", name = "Film", state = "uploading", savePath = "/seed", contentPath = "/seed/Film.2020.Release/Film.mkv")
        val qb = QBittorrentConfig(pathMappings = listOf(QBittorrentPathMapping(local = seeds, remote = "/seed")))
        assertTrue(SeedingDamageCheck.torrentCovers(mapped, outside, qb))
    }

    @Test fun theMountRootIsFound() {
        val root = SeedingDamageCheck.mountRootOf("$lib/Film (2020)/Film.mkv")
        assertNotNull(root)
        assertTrue("$lib/Film (2020)/Film.mkv".startsWith(root))
    }
}
