package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.SeedingCheckResult
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2051 (FR-5, FR-7, FR-11) — the conversion on real files: a generated AVI, real ffmpeg and ffprobe, fake ports for
 * what lives outside the disk (the seeding guard, Jellyfin, Radarr).
 */
class ContainerConvertTest {
    private val dir = "/tmp/jellystructure-test-convert-${getpid()}"
    private val folder = "$dir/Film (2020)"
    private val avi = "$folder/Film (2020).avi"

    @BeforeTest fun setUp() { SystemFileSystem.createDirectories(Path(folder)) }
    @AfterTest fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    private fun sh(cmd: String) = assertEquals(0, platform.posix.system(cmd), cmd)
    private fun exists(p: String) = SystemFileSystem.exists(Path(p))
    private fun inode(p: String) = captureShell("stat -c %i '$p'")!!.trim()
    private fun names(d: String) = SystemFileSystem.list(Path(d)).map { it.name }.sorted()

    /** A one-second AVI with an untagged audio track, and the film row that points at it. */
    private fun film(): MediaItem {
        sh("ffmpeg -v error -y -f lavfi -i testsrc=size=64x48:rate=10:duration=1 -f lavfi -i sine=duration=1 -c:v mpeg4 -c:a mp3 '$avi'")
        val tracks = runBlocking { FfprobeRunner.probe(avi) }
        return MediaItem(
            id = "film-2020", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = avi, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 1, scannedAt = 0L, tracks = tracks,
        )
    }

    private val audio = "0:a:0"
    private val faroese = listOf(ConversionRequested(audio, "fao"))

    private open class Ports(
        var seeding: SeedingCheckResult = SeedingCheckResult.Allowed,
        var jellyfinPath: String? = null,
        var free: Long? = Long.MAX_VALUE,
        var refuseRename: (String, String) -> Boolean = { _, _ -> false },
        var stripLanguages: Boolean = false,
    ) : ConversionPorts {
        val moved = ArrayList<Pair<String, String>>()
        override suspend fun seeding(path: String) = seeding
        override suspend fun jellyfinPath(item: MediaItem) = jellyfinPath
        override fun freeBytes(dir: String) = free
        override fun rename(from: String, to: String) = !refuseRename(from, to) && super.rename(from, to)
        override suspend fun probe(path: String): List<Track> {
            val real = super.probe(path)
            return if (stripLanguages && path.contains("/.jellystructure/convert_")) real.map { it.copy(language = null) } else real
        }
        override suspend fun moveIdentity(item: MediaItem, oldPath: String, newPath: String): ConversionIdentityResult {
            moved += oldPath to newPath
            return ConversionIdentityResult(jellyfinFound = true, radarrTaken = null, steps = emptyList())
        }
    }

    private fun run(item: MediaItem, ports: Ports, steps: MutableList<Int> = ArrayList()): ConvertOutcome =
        runBlocking { ContainerConverter(ports).run(item, faroese, { steps += it }, { _, _, _ -> }, { false }) }

    // ── FR-7 — the three steps ──

    @Test fun `a verified conversion leaves the mkv with the tag and the original untouched at the hidden path`() {
        val item = film()
        val inodeBefore = inode(avi)
        sh("cp '$avi' '$dir/original-copy.avi'")
        val ports = Ports(); val steps = ArrayList<Int>()
        val outcome = run(item, ports, steps)

        assertIs<ConvertOutcome.Done>(outcome)
        assertEquals(listOf(1, 2, 3), steps)
        val mkv = "$folder/Film (2020).mkv"
        val kept = "$folder/.jellystructure/Film (2020).avi"
        assertTrue(exists(mkv)); assertFalse(exists(avi))
        val tagged = runBlocking { FfprobeRunner.probe(mkv) }.first { it.kind == TrackKind.AUDIO }
        assertEquals("fao", tagged.language)
        assertEquals(0, platform.posix.system("cmp -s '$kept' '$dir/original-copy.avi'"), "the kept original is byte-identical")
        assertEquals(inodeBefore, inode(kept), "the kept original is the same inode: renamed, never rewritten")
        assertEquals(listOf("Film (2020).avi"), names("$folder/.jellystructure"), "no work file is left")
        assertEquals(listOf(avi to mkv), ports.moved)
        assertEquals(listOf(".jellystructure", "Film (2020).mkv"), names(folder))
    }

    @Test fun `a clean exit with no tag after the re-check is failed-unverified and nothing is put in place`() {
        val item = film()
        val inodeBefore = inode(avi)
        val outcome = run(item, Ports(stripLanguages = true))

        val failed = assertIs<ConvertOutcome.Failed>(outcome)
        assertEquals(ContainerConversion.JobDetail.UNVERIFIED, failed.code)
        assertEquals(2, failed.step)
        assertTrue("no language tag" in failed.message, failed.message)
        assertTrue(exists(avi)); assertEquals(inodeBefore, inode(avi))
        assertFalse(exists("$folder/Film (2020).mkv"))
        assertEquals(listOf("Film (2020).avi"), names(folder), "the work file and its folder are gone")
    }

    @Test fun `a failed second rename takes the first back and the original is untouched`() {
        val item = film()
        val inodeBefore = inode(avi)
        val ports = Ports(refuseRename = { from, _ -> from == avi })
        val outcome = run(item, ports)

        val failed = assertIs<ConvertOutcome.Failed>(outcome)
        assertEquals(ContainerConversion.JobDetail.TOOL, failed.code)
        assertEquals(3, failed.step)
        assertTrue(exists(avi)); assertEquals(inodeBefore, inode(avi))
        assertFalse(exists("$folder/Film (2020).mkv"), "the new file was taken back")
        assertEquals(listOf("Film (2020).avi"), names(folder))
        assertTrue(ports.moved.isEmpty(), "the title's identity did not move")
    }

    @Test fun `step three asks FR-5 again and so a torrent that began seeding in between stops it before any rename`() {
        val item = film()
        val ports = object : Ports() {
            var asked = 0
            override suspend fun seeding(path: String): SeedingCheckResult =
                if (++asked >= 2) SeedingCheckResult.Blocked("Some Torrent") else SeedingCheckResult.Allowed
        }
        val outcome = run(item, ports)

        val blocked = assertIs<ConvertOutcome.Blocked>(outcome)
        assertEquals(ContainerConversion.SEEDED, blocked.block.reason)
        assertEquals(3, blocked.step)
        assertEquals(listOf("Film (2020).avi"), names(folder))
    }

    // ── FR-5 — the five blocks, each in order ──

    private fun safety(item: MediaItem, ports: Ports) = runBlocking { ContainerConverter(ports).safety(item) }

    @Test fun `a safe film has no block`() = assertNull(safety(film(), Ports()))

    @Test fun `a path that is not on disk or that Jellyfin has elsewhere is a path mismatch`() {
        val item = film()
        assertEquals(ContainerConversion.PATH_MISMATCH, safety(item.copy(path = "$folder/Gone.avi"), Ports())?.reason)
        assertEquals(ContainerConversion.PATH_MISMATCH, safety(item, Ports(jellyfinPath = "$folder/Other.avi"))?.reason)
        assertNull(safety(item, Ports(jellyfinPath = avi)))
    }

    @Test fun `a seeded path is blocked and an unreachable guard is refused as unknown`() {
        val item = film()
        assertEquals(ContainerConversion.SEEDED, safety(item, Ports(seeding = SeedingCheckResult.Blocked("T")))?.reason)
        val unknown = safety(item, Ports(seeding = SeedingCheckResult.Unreachable("timeout")))
        assertEquals(ContainerConversion.UNKNOWN, unknown?.reason)
    }

    @Test fun `a name already taken in the folder or in the hidden one is blocked`() {
        val item = film()
        sh("touch '$folder/Film (2020).mkv'")
        val block = safety(item, Ports())
        assertEquals(ContainerConversion.NAME_TAKEN, block?.reason)
        assertEquals("A file named Film (2020).mkv is already there, so nothing was converted.", block?.message)
        sh("rm '$folder/Film (2020).mkv' && mkdir -p '$folder/.jellystructure' && touch '$folder/.jellystructure/Film (2020).avi'")
        assertEquals(ContainerConversion.NAME_TAKEN, safety(item, Ports())?.reason)
    }

    @Test fun `too little room names the space needed and the space free`() {
        val item = film()
        val size = SystemFileSystem.metadataOrNull(Path(avi))!!.size
        val block = safety(item, Ports(free = size))
        assertEquals(ContainerConversion.NO_ROOM, block?.reason)
        assertEquals(ContainerConversion.requiredFreeBytes(size), block?.neededBytes)
        assertEquals(size, block?.freeBytes)
        assertNull(safety(item, Ports(free = ContainerConversion.requiredFreeBytes(size))))
    }

    @Test fun `the blocks are asked in the spec's order`() {
        fun block(
            exists: Boolean = true, jf: String? = null, seeding: ContainerConversion.Seeding = ContainerConversion.Seeding.ALLOWED,
            dest: Boolean = false, kept: Boolean = false, free: Long? = Long.MAX_VALUE,
        ) = ContainerConversion.firstBlock("/m/F/F.avi", exists, jf, seeding, null, dest, kept, 100L, free)?.reason
        assertEquals("path_mismatch", block(exists = false, seeding = ContainerConversion.Seeding.BLOCKED, dest = true, free = 1))
        assertEquals("seeded", block(seeding = ContainerConversion.Seeding.BLOCKED, dest = true, free = 1))
        assertEquals("unknown", block(seeding = ContainerConversion.Seeding.UNREACHABLE, dest = true, free = 1))
        assertEquals("name_taken", block(kept = true, free = 1))
        assertEquals("no_room", block(free = 1))
        assertNull(block())
        assertNull(block(free = null), "free space that can't be read proves nothing")
    }

    // ── the work file, the sweep, the estimate ──

    @Test fun `a convert work file reads back to the mkv it will become`() {
        val work = ContainerConversion.workPathOf("/m/Film (2020)/Film (2020).avi")
        assertEquals("/m/Film (2020)/.jellystructure/convert_Film (2020).mkv", work)
        assertEquals("/m/Film (2020)/Film (2020).mkv", WorkFiles.libraryPathOf(work))
        assertEquals("/m/Film (2020)/.jellystructure/Film (2020).avi", ContainerConversion.keptPathOf("/m/Film (2020)/Film (2020).avi"))
    }

    @Test fun `the work-file sweep leaves a 25-hour-old kept original in place and still removes a stale work file`() {
        val now = 1_000_000L
        val kept = "/m/Film (2020)/.jellystructure/Film (2020).avi"
        val work = ContainerConversion.workPathOf("/m/Film (2020)/Film (2020).avi")
        val old = now - 25 * 3600
        assertEquals(listOf(work), workFilesToRemove(listOf(WorkFileEntry(kept, old), WorkFileEntry(work, old)), now) { false })
    }

    @Test fun `the estimate is the size over the median throughput of the last three jobs and and unavailable without them`() {
        assertEquals(10L, ContainerConversion.estimateSeconds(1_000L, listOf(100.0, 50.0, 200.0)))   // median 100 B/s
        assertEquals(13L, ContainerConversion.estimateSeconds(1_000L, listOf(100.0, 50.0)))          // two: their mean, 75 B/s
        assertEquals(10L, ContainerConversion.estimateSeconds(1_000L, listOf(100.0, 100.0, 100.0, 1.0)), "only the last three count")
        assertNull(ContainerConversion.estimateSeconds(1_000L, emptyList()))
        assertNull(ContainerConversion.estimateSeconds(null, listOf(100.0)))
        assertNull(ContainerConversion.estimateSeconds(0L, listOf(100.0)))
    }

    @Test fun `a job row reads back as the file's latest conversion`() {
        assertEquals("queued", ContainerConversion.latestOf("j", "queued", 0, 0.0, null, null).state)
        val running = ContainerConversion.latestOf("j", "running", 1, 41.0, "2.3x", null)
        assertEquals("running" to 1, running.state to running.step)
        val ok = ContainerConversion.latestOf("j", "done", 3, 100.0, ContainerConversion.JobDetail.verified(true, true), null)
        assertTrue(ok.state == "verified" && ok.jellyfinPending && ok.radarrNotTaken)
        assertFalse(ContainerConversion.latestOf("j", "done", 3, 100.0, ContainerConversion.JobDetail.verified(false, false), null).jellyfinPending)
        assertEquals("failed_unverified", ContainerConversion.latestOf("j", "failed", 2, 0.0, "failed_unverified", "no tag").state)
        assertEquals("failed_tool", ContainerConversion.latestOf("j", "failed", 1, 0.0, "failed_tool", "x").state)
        assertEquals("failed_tool", ContainerConversion.latestOf("j", "failed", 1, 0.0, "interrupted", "x").state)
        val blocked = ContainerConversion.latestOf("j", "failed", 3, 0.0, ContainerConversion.JobDetail.blocked("seeded"), "seeded")
        assertEquals("blocked" to "seeded", blocked.state to blocked.reason)
        assertNotNull(blocked.message)
    }
}
