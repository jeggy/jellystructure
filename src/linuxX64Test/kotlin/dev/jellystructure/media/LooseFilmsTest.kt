package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 316 (FR-316-5) — a loose film keeps its artwork to itself, and the move into its own folder. */
class LooseFilmsTest {
    private val root = "/lib/films"
    private var saved: List<String> = emptyList()

    @BeforeTest fun setRoots() { saved = LibraryRootsRegistry.roots; LibraryRootsRegistry.roots = listOf(root, "/lib/series") }
    @AfterTest fun restoreRoots() { LibraryRootsRegistry.roots = saved }

    private fun film(path: String, id: String = "f") = MediaItem(
        id = id, title = "x", year = null, kind = MediaKind.MOVIE, path = path,
        tmdbId = null, originalLanguage = null, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0,
    )

    // ── 1 — FR-316-1 ──

    @Test fun `a loose film's artwork takes Jellyfin's per-file names`() {
        val f = film("$root/Some.Film.2023.mkv")
        assertEquals("$root/Some.Film.2023-poster.jpg", assetFilePath(f, "poster.jpg"))
        assertEquals("$root/Some.Film.2023-backdrop.jpg", assetFilePath(f, "fanart.jpg"))
        assertEquals("$root/Some.Film.2023-logo.png", assetFilePath(f, "clearlogo.png"))
        assertEquals("$root/Some.Film.2023-landscape.jpg", assetFilePath(f, "landscape.jpg"))
    }

    @Test fun `a film in its own folder keeps folder names`() {
        assertEquals("$root/Some Film (2023)/poster.jpg", assetFilePath(film("$root/Some Film (2023)/Some.Film.2023.mkv"), "poster.jpg"))
        assertEquals("$root/Some Film (2023)/clearlogo.png", assetFilePath(film("$root/Some Film (2023)/Some.Film.2023.mkv"), "clearlogo.png"))
    }

    // ── 2 — FR-316-2 ──

    @Test fun `a folder-named image is never written into a library root`() {
        assertNotNull(refuseRootFolderImage("$root/clearlogo.png"))
        assertNotNull(refuseRootFolderImage("$root/poster.jpg.tmp"))
        assertNull(refuseRootFolderImage("$root/Some Film (2023)/clearlogo.png"))
        assertNull(refuseRootFolderImage("$root/Some.Film.2023-logo.png"))
    }

    // ── 6 — two loose films never share an image ──

    @Test fun `two loose films in one root resolve different files`() {
        val a = assetFilePath(film("$root/A.Film.2023.mkv", "a"), "clearlogo.png")
        val b = assetFilePath(film("$root/B.Film.2024.mkv", "b"), "clearlogo.png")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("$root/A.Film.2023") && b.startsWith("$root/B.Film.2024"))
    }

    // ── 3 — the plan ──

    private val base = "Some.Film.2023.1080p"
    private val video = "$base.mkv"

    @Test fun `the plan moves the video and its own files and renames basename artwork to folder names`() {
        val entries = listOf(video, "$base.nfo", "$base.da.srt", "$base.trickplay", "$base-poster.jpg", "$base-poster.jpg.src",
            "$base-backdrop.jpg", "$base-logo.png", "$base-landscape.jpg", "Other.Film.2020.mkv", "Other.Film.2020-poster.jpg")
        val plan = planLooseFilm(video, entries, emptyList()).associate { it.name to it.target }
        assertEquals(video, plan[video])
        assertEquals("poster.jpg", plan["$base-poster.jpg"])
        assertEquals("poster.jpg.src", plan["$base-poster.jpg.src"])
        assertEquals("fanart.jpg", plan["$base-backdrop.jpg"])
        assertEquals("clearlogo.png", plan["$base-logo.png"])
        assertEquals("landscape.jpg", plan["$base-landscape.jpg"])
        assertEquals("$base.nfo", plan["$base.nfo"])
        assertEquals("$base.da.srt", plan["$base.da.srt"])
        assertEquals("$base.trickplay", plan["$base.trickplay"])
        assertTrue("Other.Film.2020.mkv" !in plan && "Other.Film.2020-poster.jpg" !in plan)
    }

    @Test fun `a manual root image wins over the film's own and the loser is backed up`() {
        val entries = listOf(video, "$base-poster.jpg", "poster.jpg", "poster.jpg.src", "poster.jpg.manual", "clearlogo.png", "$base-logo.png")
        val plan = planLooseFilm(video, entries, listOf("poster.jpg", "clearlogo.png"))
        val byName = plan.associate { it.name to it.target }
        assertEquals("poster.jpg", byName["poster.jpg"])
        assertEquals("poster.jpg.manual", byName["poster.jpg.manual"])
        assertNull(byName["$base-poster.jpg"], "the film's older poster is backed up")
        // A tie (no .manual on either): the film's own logo wins; the root copy is backed up.
        assertEquals("clearlogo.png", byName["$base-logo.png"])
        assertNull(byName["clearlogo.png"])
    }

    // ── 3 — matching a root image to its film ──

    @Test fun `a root image belongs to a film by its src or by identical bytes`() {
        assertTrue(rootImageMatches("/abc.jpg", listOf("/abc.jpg", null), sameBytesAsOwn = false))
        assertTrue(rootImageMatches("/zzz.png", listOf("/abc.jpg"), sameBytesAsOwn = true))
        assertTrue(!rootImageMatches("/zzz.png", listOf("/abc.jpg"), sameBytesAsOwn = false))
        assertTrue(!rootImageMatches(null, listOf(null), sameBytesAsOwn = false))
    }

    @Test fun `radarr is updated only when it has no file or exactly this one`() {
        assertEquals(RadarrPlan.UPDATE, radarrPlan(true, true, null, "/media/films/x.mkv"))
        assertEquals(RadarrPlan.UPDATE, radarrPlan(true, true, "/media/films/x.mkv", "/media/films/x.mkv"))
        assertEquals(RadarrPlan.OTHER_FILE, radarrPlan(true, true, "/media/films/Other/y.mkv", "/media/films/x.mkv"))
        assertEquals(RadarrPlan.NOT_MANAGED, radarrPlan(true, false, null, "/media/films/x.mkv"))
        assertEquals(RadarrPlan.NOT_CONFIGURED, radarrPlan(false, false, null, "/media/films/x.mkv"))
    }

    @Test fun `the new folder is Title and Year with characters a folder cannot carry replaced`() {
        assertEquals("Some Film - Part Two (2025)", looseFilmFolderName("Some Film: Part Two", 2025))
        assertEquals("What (2020)", looseFilmFolderName("What?", 2020))
        assertEquals("No Year", looseFilmFolderName("No Year", null))
    }

    // ── 4 — the move, with fakes ──

    private fun looseFilm(torrents: List<LooseTorrent> = emptyList(), radarr: String = RadarrPlan.UPDATE) = LooseFilm(
        key = "$root/$video", root = root, mediaId = "some-film-2023", title = "Some Film", year = 2023, jellyfinId = "old",
        video = video, files = planLooseFilm(video, listOf(video, "$base.nfo", "$base-poster.jpg", "poster.jpg", "poster.jpg.manual"), listOf("poster.jpg")),
        torrents = torrents, radarr = radarr, radarrId = 7, targetFolder = "$root/Some Film (2023)",
    )

    private class Fake(
        var playing: Boolean = false,
        var targetExists: Boolean = false,
        var failRenameOf: String? = null,
        var newId: String? = "new",
        var newItemData: ViewerData? = null,
    ) : LooseFilmPorts {
        val calls = ArrayList<String>()
        val renamed = ArrayList<Pair<String, String>>()
        override suspend fun isPlaying(film: LooseFilm) = playing.also { calls += "isPlaying" }
        override suspend fun isLocked(path: String) = false
        override fun exists(path: String) = targetExists
        override fun isEmptyDir(path: String) = false
        override fun mkdir(path: String) = true.also { calls += "mkdir" }
        override fun rmdir(path: String) = true.also { calls += "rmdir" }
        override fun rename(from: String, to: String): Boolean {
            if (failRenameOf != null && from.endsWith("/$failRenameOf")) return false
            renamed += from to to; calls += "rename"; return true
        }
        override fun backup(path: String) = true.also { calls += "backup:${path.substringAfterLast('/')}" }
        override fun delete(path: String) = true.also { calls += "delete:${path.substringAfterLast('/')}" }
        override suspend fun setLocation(hash: String, localFolder: String) = true.also { calls += "setLocation:$hash" }
        override suspend fun recheck(hash: String) = true.also { calls += "recheck:$hash" }
        override suspend fun radarrUpdatePath(movieId: Int, localFolder: String) = true.also { calls += "radarrUpdatePath:$localFolder" }
        override suspend fun radarrRefresh(movieId: Int) = true.also { calls += "radarrRefresh" }
        override suspend fun radarrFilePath(movieId: Int) = "/lib/films/Some Film (2023)/Some.Film.2023.1080p.mkv"
        override suspend fun readUserData(jellyfinId: String) = mapOf("u1" to ViewerData(true, 0, true, 2, "2026-01-01"), "u2" to ViewerData(false, 0, false, 0, null)).also { calls += "readUserData" }
        override suspend fun readUserDataFor(userId: String, jellyfinId: String) = newItemData
        override suspend fun writeUserData(userId: String, jellyfinId: String, data: ViewerData) = true.also { calls += "writeUserData:$userId" }
        override suspend fun jellyfinNotify(oldVideoPath: String, newFolder: String) { calls += "jellyfinNotify" }
        override suspend fun findJellyfinItem(videoPath: String) = newId.also { calls += "findJellyfinItem" }
        override fun remapJellyfinId(oldId: String, newId: String) = 3.also { calls += "remap:$oldId>$newId" }
        override suspend fun updateMedia(mediaId: String, newVideoPath: String, newJellyfinId: String?) { calls += "updateMedia:$newJellyfinId" }
        override suspend fun torrentsNotFine(hashes: List<String>) = emptyList<String>()
        override fun rootStillHas(root: String, base: String) = false
        override fun history(mediaId: String, detail: String) { calls += "history" }
    }

    @Test fun `a move follows the spec's order and only renames`() = runBlocking {
        val fake = Fake()
        val out = LooseFilmMover(fake).move(looseFilm(torrents = listOf(LooseTorrent("t1", "Seeding", "h1", false), LooseTorrent("t2", "Seeding", "h2", true))))
        assertEquals("moved", out.state, out.error)
        val order = fake.calls.filter { !it.startsWith("rename") && !it.startsWith("backup") && !it.startsWith("delete") }
        val i = { s: String -> order.indexOfFirst { it.startsWith(s) } }
        assertTrue(i("isPlaying") < i("readUserData") && i("readUserData") < i("mkdir") && i("mkdir") < i("setLocation") &&
            i("setLocation") < i("radarrUpdatePath") && i("radarrUpdatePath") < i("radarrRefresh") && i("radarrRefresh") < i("jellyfinNotify") &&
            i("jellyfinNotify") < i("findJellyfinItem") && i("findJellyfinItem") < i("remap") && i("remap") < i("updateMedia") && i("updateMedia") < i("history"), order.toString())
        // Every planned file was renamed into the new folder; the losing image was backed up, then removed.
        assertTrue(fake.renamed.all { (_, to) -> to.startsWith("$root/Some Film (2023)/") })
        assertTrue("backup:$base-poster.jpg" in fake.calls && "delete:$base-poster.jpg" in fake.calls)
        // Only the torrent whose own path is the library file is repointed; the hard-link one is left alone.
        assertTrue("setLocation:h2" in fake.calls && "recheck:h2" in fake.calls && "setLocation:h1" !in fake.calls)
        // The new item lacked the data: the viewer with data gets it back, the one without nothing.
        assertTrue("writeUserData:u1" in fake.calls && "writeUserData:u2" !in fake.calls)
        assertTrue("remap:old>new" in fake.calls && "updateMedia:new" in fake.calls)
        assertEquals("$root/Some Film (2023)/$video", out.key)
    }

    @Test fun `user data already on the new item is not written again`() = runBlocking {
        val fake = Fake(newItemData = ViewerData(true, 0, true, 2, "2026-01-01"))
        LooseFilmMover(fake).move(looseFilm())
        assertTrue(fake.calls.none { it.startsWith("writeUserData") })
    }

    @Test fun `a failed rename puts everything back and leaves the film where it was`() = runBlocking {
        val fake = Fake(failRenameOf = "$base.nfo")
        val out = LooseFilmMover(fake).move(looseFilm())
        assertEquals("failed", out.state)
        val forward = fake.renamed.filter { it.second.startsWith("$root/Some Film (2023)/") }
        val back = fake.renamed.filter { it.first.startsWith("$root/Some Film (2023)/") }
        assertEquals(forward.map { it.second to it.first }.reversed(), back, "each done rename undone, in reverse")
        assertTrue("rmdir" in fake.calls && fake.calls.none { it.startsWith("radarr") || it.startsWith("jellyfinNotify") || it.startsWith("backup") })
    }

    @Test fun `nothing moves while someone is playing it or the folder is taken`() = runBlocking {
        val playing = Fake(playing = true)
        assertEquals("failed", LooseFilmMover(playing).move(looseFilm()).state)
        assertTrue(playing.renamed.isEmpty())
        val taken = Fake(targetExists = true)
        assertEquals("failed", LooseFilmMover(taken).move(looseFilm()).state)
        assertTrue(taken.renamed.isEmpty())
    }

    @Test fun `radarr mapping another file is left alone`() = runBlocking {
        val fake = Fake()
        val out = LooseFilmMover(fake).move(looseFilm(radarr = RadarrPlan.OTHER_FILE))
        assertTrue(fake.calls.none { it.startsWith("radarr") })
        assertEquals("moved", out.state)
    }

    // ── Phase 314c — a Dolby Vision original renamed after its own folder (the same mover, in place) ──

    private val dvDir = "$root/Some Film (2023)"
    private val dvVideo = "Some.Film.2023.2160p.UHD.BluRay.REMUX.mkv"

    @Test fun `a rename after the folder takes the video and every file named after it - folder-named files stay`() {
        val plan = planRenameToFolder(dvVideo, listOf(dvVideo, "Some.Film.2023.2160p.UHD.BluRay.REMUX.nfo",
            "Some.Film.2023.2160p.UHD.BluRay.REMUX.da.srt", "Some.Film.2023.2160p.UHD.BluRay.REMUX-thumb.jpg", "poster.jpg", "movie.nfo"), "Some Film (2023)")
        assertEquals(mapOf(
            dvVideo to "Some Film (2023).mkv",
            "Some.Film.2023.2160p.UHD.BluRay.REMUX.nfo" to "Some Film (2023).nfo",
            "Some.Film.2023.2160p.UHD.BluRay.REMUX.da.srt" to "Some Film (2023).da.srt",
            "Some.Film.2023.2160p.UHD.BluRay.REMUX-thumb.jpg" to "Some Film (2023)-thumb.jpg",
        ), plan.associate { it.name to it.target })
        assertEquals(emptyList(), planRenameToFolder("Some Film (2023).mkv", listOf("Some Film (2023).mkv"), "Some Film (2023)"))
    }

    private fun renameFilm(torrents: List<LooseTorrent> = emptyList()) = LooseFilm(
        key = "$dvDir/$dvVideo", root = dvDir, mediaId = "some-film-2023", title = "Some Film", year = 2023, jellyfinId = "old",
        video = dvVideo, files = planRenameToFolder(dvVideo, listOf(dvVideo, "Some.Film.2023.2160p.UHD.BluRay.REMUX.nfo", "poster.jpg"), "Some Film (2023)"),
        torrents = torrents, radarr = RadarrPlan.UPDATE, radarrId = 7, targetFolder = dvDir,
    )

    @Test fun `a rename in place makes no folder - renames inside it - and Radarr and Jellyfin are told`() = runBlocking {
        val fake = Fake()
        val out = LooseFilmMover(fake).move(renameFilm(torrents = listOf(LooseTorrent("t1", "Seeding", "h1", false))))
        assertTrue(out.state != "failed", out.error)
        assertTrue("mkdir" !in fake.calls && "rmdir" !in fake.calls)
        assertEquals(setOf("$dvDir/$dvVideo" to "$dvDir/Some Film (2023).mkv", "$dvDir/Some.Film.2023.2160p.UHD.BluRay.REMUX.nfo" to "$dvDir/Some Film (2023).nfo"), fake.renamed.toSet())
        assertTrue("setLocation:h1" !in fake.calls, "a hard-link seed is untouched: the inode is the same")
        assertTrue("radarrRefresh" in fake.calls && "jellyfinNotify" in fake.calls && "updateMedia:new" in fake.calls)
        assertEquals("$dvDir/Some Film (2023).mkv", out.key)
    }

    @Test fun `a rename in place stops when the new name is taken`() = runBlocking {
        val fake = Fake(targetExists = true)
        assertEquals("failed", LooseFilmMover(fake).move(renameFilm()).state)
        assertTrue(fake.renamed.isEmpty())
    }

    @Test fun `jellyfin not finding it yet marks the move partial and not failed`() = runBlocking {
        val fake = Fake(newId = null)
        val out = LooseFilmMover(fake).move(looseFilm())
        assertEquals("partial", out.state)
        assertTrue(fake.calls.none { it.startsWith("remap") })
    }
}
