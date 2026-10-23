package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.FileStamp
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-6) — the embedded-subtitle cache: keyed by path + size + mtime + the CONTAINER's stream index, so a sidecar
 * added later (which shifts Jellyfin's numbers and nothing in the file) changes nothing.
 */
class EmbeddedSubtitleCacheTest {
    private val root = "/tmp/jellystructure-test-deb2205-subs-${getpid()}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        runCatching { SystemFileSystem.list(Path(root)).forEach { d -> runCatching { SystemFileSystem.list(d).forEach { SystemFileSystem.delete(it, mustExist = false) } }; SystemFileSystem.delete(d, mustExist = false) } }
        runCatching { SystemFileSystem.delete(Path(root), mustExist = false) }
    }

    private fun track(streamIndex: Int, ordinal: Int, language: String, codec: String = "subrip") =
        Track(streamIndex = streamIndex, specifier = "0:s:$ordinal", kind = TrackKind.SUBTITLE, codec = codec, language = language, title = null, default = false, forced = false)

    private fun video() = Track(streamIndex = 0, specifier = "0:v:0", kind = TrackKind.VIDEO, codec = "hevc", language = null, title = null, default = true, forced = false)

    private val scanned = listOf(video(), track(2, 0, "eng"), track(3, 1, "ger"), track(4, 2, "spa", codec = "hdmv_pgs_subtitle"))

    private fun jf(index: Int, language: String, external: Boolean = false, codec: String = "subrip") =
        JellyfinMediaStream(type = "Subtitle", index = index, codec = codec, language = language, isExternal = external, isTextSubtitleStream = codec != "hdmv_pgs_subtitle")

    @Test
    fun `the key uses the container index and a sidecar added later changes nothing`() {
        // Jellyfin 12.1 numbers a video's sidecars first and shifts every stream in the file up by their count.
        val before = listOf(jf(2, "eng"), jf(3, "ger"), jf(4, "spa", codec = "hdmv_pgs_subtitle"))
        val after = listOf(jf(0, "dan", external = true, codec = "srt")) + listOf(jf(3, "eng"), jf(4, "ger"), jf(5, "spa", codec = "hdmv_pgs_subtitle"))

        val keysBefore = before.take(2).map { EmbeddedSubtitleCache.containerIndexOf(it, scanned, before)!! }.map { EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 53_000_000_000L, 1_760_000_000L, it) }
        val keysAfter = after.drop(1).take(2).map { EmbeddedSubtitleCache.containerIndexOf(it, scanned, after)!! }.map { EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 53_000_000_000L, 1_760_000_000L, it) }

        assertEquals(keysBefore, keysAfter, "Jellyfin's index 2 became 3, the container's did not move")
        assertNull(EmbeddedSubtitleCache.containerIndexOf(after[0], scanned, after), "a sidecar is not extracted: it keeps Jellyfin's URL")
        assertNull(EmbeddedSubtitleCache.containerIndexOf(after[3], scanned, after), "an image track is not a text track")
    }

    @Test
    fun `the key changes with the path the size the mtime and the index`() {
        val base = EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 100, 5, 2)
        assertNotEquals(base, EmbeddedSubtitleCache.relativePath("/media/a/other.mkv", 100, 5, 2))
        assertNotEquals(base, EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 101, 5, 2))
        assertNotEquals(base, EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 100, 6, 2))
        assertNotEquals(base, EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 100, 5, 3))
        assertEquals(base, EmbeddedSubtitleCache.relativePath("/media/a/film.mkv", 100, 5, 2))
    }

    @Test
    fun `a pass extracts every text track once and a changed file is extracted again`() = runBlocking {
        var stamp = FileStamp(100, 5, 1, 1)
        val passes = mutableListOf<List<Int>>()
        val cache = EmbeddedSubtitleCache(root, scope, stampOf = { stamp }, extract = { _, indices, out ->
            passes += indices
            indices.forEach { FileIo.writeText(Path("$out/$it.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\ncue $it\n") }
            true
        })

        assertTrue(cache.ensure("/media/a/film.mkv", scanned))
        assertEquals(listOf(listOf(2, 3)), passes, "one pass, both text tracks, the PGS track left alone")
        assertTrue(FileIo.readText(Path(cache.cachedFile("/media/a/film.mkv", 3)!!)).contains("cue 3"))
        assertNull(cache.cachedFile("/media/a/film.mkv", 4))

        assertTrue(cache.ensure("/media/a/film.mkv", scanned))
        assertEquals(1, passes.size, "already extracted: no second read of the file")

        stamp = FileStamp(100, 6, 1, 1)   // the file was replaced
        assertNull(cache.cachedFile("/media/a/film.mkv", 2))
        assertTrue(cache.ensure("/media/a/film.mkv", scanned))
        assertEquals(2, passes.size)
        assertEquals(2, SystemFileSystem.list(Path("$root/${EmbeddedSubtitleCache.dirName("/media/a/film.mkv")}")).count { it.name.endsWith(".vtt") }, "the old version's files are gone")
    }

    @Test
    fun `a request for a track not extracted yet starts the pass and answers pending then ready`() = runBlocking {
        val cache = EmbeddedSubtitleCache(root, scope, stampOf = { FileStamp(100, 5, 1, 1) }, extract = { _, indices, out ->
            delay(100)
            indices.forEach { FileIo.writeText(Path("$out/$it.vtt"), "WEBVTT\n\n") }
            true
        })
        assertEquals(EmbeddedSubtitleCache.Lookup.Pending, cache.lookup("/media/a/film.mkv", scanned, 2))
        assertEquals(EmbeddedSubtitleCache.Lookup.Unavailable, cache.lookup("/media/a/film.mkv", scanned, 4), "an image track")
        assertEquals(EmbeddedSubtitleCache.Lookup.Unavailable, cache.lookup("/media/a/film.mkv", scanned, 9), "no such track")
        var found: EmbeddedSubtitleCache.Lookup = EmbeddedSubtitleCache.Lookup.Pending
        repeat(50) { if (found == EmbeddedSubtitleCache.Lookup.Pending) { delay(50); found = cache.lookup("/media/a/film.mkv", scanned, 2) } }
        assertTrue(found is EmbeddedSubtitleCache.Lookup.Ready, "the client's retry finds it: $found")
    }

    @Test
    fun `a failed pass leaves nothing and is not retried for ten minutes`() = runBlocking {
        var now = 1_000L
        var calls = 0
        val cache = EmbeddedSubtitleCache(root, scope, stampOf = { FileStamp(100, 5, 1, 1) }, extract = { _, _, _ -> calls++; false }, clock = { now })
        assertFalse(cache.ensure("/media/a/film.mkv", scanned))
        assertEquals(EmbeddedSubtitleCache.Lookup.Unavailable, cache.lookup("/media/a/film.mkv", scanned, 2))
        assertEquals(1, calls)
        now += EmbeddedSubtitleCache.RETRY_AFTER_FAILURE_MS + 1
        assertTrue(cache.lookup("/media/a/film.mkv", scanned, 2) is EmbeddedSubtitleCache.Lookup.Pending, "after the hold, a request asks again")
    }
}
