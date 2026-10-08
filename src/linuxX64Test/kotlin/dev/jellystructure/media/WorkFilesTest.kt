package dev.jellystructure.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.posix.access
import platform.posix.F_OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 311 (FR-311-5) — work files live in `<dir>/.jellystructure/`, where Radarr, Sonarr and Jellyfin never look. */
class WorkFilesTest {

    @Test
    fun `a work file is in the hidden folder of its own directory and keeps its extension`() {
        assertEquals("/media/movies/A Film (2020)/.jellystructure/remux_A Film (2020).mkv",
            WorkFiles.pathFor("/media/movies/A Film (2020)/A Film (2020).mkv", WorkFiles.Kind.REMUX))
        assertEquals("/media/series/Show/Season 1/.jellystructure/replace_Show - S01E01.mp4",
            WorkFiles.pathFor("/media/series/Show/Season 1/Show - S01E01.mp4", WorkFiles.Kind.REPLACE))
        val odd = "/media/series/Bob's Show/Season 1/Bøb – Ép 1.mkv"
        assertEquals("/media/series/Bob's Show/Season 1/.jellystructure/remux_Bøb – Ép 1.mkv", WorkFiles.pathFor(odd, WorkFiles.Kind.REMUX))
        assertEquals(odd, WorkFiles.libraryPathOf(WorkFiles.pathFor(odd, WorkFiles.Kind.REMUX)))
        assertNull(WorkFiles.libraryPathOf("/media/movies/A/.jellystructure/notes.txt"))
    }

    @Test
    fun `every remux command writes only to the work path and ends with a move into place`() {
        val file = "/media/movies/Bob's Film (2020)/Bob's Film (2020).mkv"
        val work = WorkFiles.pathFor(file, WorkFiles.Kind.REMUX).replace("'", "'\\''")
        val lib = file.replace("'", "'\\''")
        val commands = listOf(
            TrackCommandBuilder.ffmpegLanguage(file, 1, "da")!!,
            TrackCommandBuilder.ffmpegDefault(file, 1, listOf(1, 2), "a"),
            TrackCommandBuilder.ffmpegReorder(file, listOf(2, 1), isAudio = true),
            TrackCommandBuilder.ffmpegRepairTracksLayout(file),
        )
        for (cmd in commands) {
            assertTrue(cmd.startsWith("mkdir -p '/media/movies/Bob'\\''s Film (2020)/.jellystructure'"), cmd)
            assertTrue(cmd.trimEnd().endsWith("'$work' && mv '$work' '$lib'"), cmd)
            assertFalse(".jstmp_" in cmd, cmd)
        }
    }

    // Copied from Radarr's and Sonarr's DiskScanService (2026-10): a file is skipped only by name, a folder by any
    // dot-name. If upstream changes these, update them here (FR-311-5 #3).
    private val arrExcludedFiles = Regex("^\\.(_|unmanic|DS_Store$)|^Thumbs\\.db$", RegexOption.IGNORE_CASE)
    private val arrExcludedSubFolders = Regex("(?:\\\\|\\/|^)(?:extras|@eadir|extrafanart|plex versions|\\.[^\\\\/]+)(?:\\\\|\\/)", RegexOption.IGNORE_CASE)
    private fun arrSkips(relative: String) =
        arrExcludedSubFolders.containsMatchIn(relative) || arrExcludedFiles.containsMatchIn(relative.substringAfterLast('/'))
    /** Jellyfin's `**` + `/.*` ignore rule: any path segment that starts with a dot. */
    private fun jellyfinSkips(path: String) = path.split('/').any { it.startsWith(".") }

    @Test
    fun `Radarr Sonarr and Jellyfin all skip the work folder - and the old dot-file was seen`() {
        for (kind in WorkFiles.Kind.entries) {
            val work = WorkFiles.pathFor("/media/movies/A Film (2020)/A Film (2020).mkv", kind)
            assertTrue(arrSkips(work.removePrefix("/media/movies/A Film (2020)/")), work)
            assertTrue(arrSkips(work.removePrefix("/media/movies/")), work)
            assertTrue(jellyfinSkips(work), work)
        }
        // The bug this phase fixes: the old name beside the video is NOT skipped by Radarr or Sonarr.
        assertFalse(arrSkips(".jstmp_A Film (2020).mkv"))
        assertFalse(arrSkips(".jsreplace_A Film (2020).mkv"))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `a failed remux leaves no work file and no work folder`(): Unit = runBlocking {
        val dir = "/tmp/js311-test-${platform.posix.getpid()}"
        platform.posix.mkdir(dir, 0x1EDu)
        val file = "$dir/not a video.mkv"
        platform.posix.fopen(file, "w")?.let { platform.posix.fputs("junk", it); platform.posix.fclose(it) }
        assertFalse(FfmpegRunner.repairTracksLayout(file))
        assertTrue(access(WorkFiles.pathFor(file, WorkFiles.Kind.REMUX), F_OK) != 0)
        assertTrue(access(WorkFiles.dirFor(file), F_OK) != 0)
        platform.posix.remove(file); platform.posix.rmdir(dir)
        Unit
    }

    @Test
    fun `the sweep removes only old work files no job holds`() {
        val now = 1_000_000_000L
        val lib = "/media/movies/A/A.mkv"
        val old = WorkFileEntry(WorkFiles.pathFor(lib, WorkFiles.Kind.REMUX), now - 25 * 3600)
        val fresh = WorkFileEntry(WorkFiles.pathFor("/media/movies/B/B.mkv", WorkFiles.Kind.REMUX), now - 3600)
        val held = WorkFileEntry(WorkFiles.pathFor("/media/movies/C/C.mkv", WorkFiles.Kind.REPLACE), now - 30 * 3600)
        val legacy = WorkFileEntry("/media/movies/D/.jstmp_D.mkv", now - 48 * 3600)
        val notOurs = WorkFileEntry("/media/movies/E/.jellystructure/notes.txt", now - 99 * 3600)
        val removed = workFilesToRemove(listOf(old, fresh, held, legacy, notOurs), now) { it == "/media/movies/C/C.mkv" }
        assertEquals(listOf(old.path, legacy.path), removed)
    }
}
