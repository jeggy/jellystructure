package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.components.desktopMusicToggleVisible
import dev.jellystructure.shared.tv.MusicList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DEB-4091 FR-3, FR-4, FR-7 — failures remain visible, Retry and a socket-open tick start another load. */
class MusicEntryRecoveryTest {
    @Test fun `paged failure is kept and retry returns to Loading before the answer`() = runBlocking {
        val gate = CompletableDeferred<MusicList>()
        var calls = 0
        val list = MusicPagedList {
            calls++
            if (calls == 1) error("server down")
            gate.await()
        }
        list.load()
        withTimeout(3_000) { list.state.first { it is Load.Failed } }
        assertEquals("server down", list.lastFailure?.message)
        list.load()
        assertIs<Load.Loading>(list.state.value)
        gate.complete(MusicList(total = 1))
        withTimeout(3_000) { list.state.first { it is Load.Ready } }
        assertEquals(2, calls)
    }

    @Test fun `socket recovery reloads Failed Listen but leaves Ready Listen alone`() = runBlocking {
        val second = CompletableDeferred<String>()
        var failedCalls = 0
        var readyCalls = 0
        val failed = MusicLoader {
            failedCalls++
            if (failedCalls == 1) error("offline")
            second.await()
        }
        val ready = MusicLoader { readyCalls++; "available" }
        failed.load()
        ready.load()
        withTimeout(3_000) { failed.state.first { it is Load.Failed } }
        withTimeout(3_000) { ready.state.first { it is Load.Ready } }
        recoverMusicEntryStores(mapOf("mlisten:failed" to failed, "mlisten:ready" to ready))
        assertIs<Load.Loading>(failed.state.value)
        second.complete("recovered")
        withTimeout(3_000) { failed.state.first { it is Load.Ready } }
        assertEquals(2, failedCalls)
        assertEquals(1, readyCalls)
    }

    @Test fun `Browse recovery reloads failed lists and genres but leaves ready loaders alone`() = runBlocking {
        val listAnswer = CompletableDeferred<MusicList>()
        val genreAnswer = CompletableDeferred<String>()
        var listCalls = 0
        var readyListCalls = 0
        var genreCalls = 0
        var playlistCalls = 0
        val failedList = MusicPagedList {
            listCalls++
            if (listCalls == 1) error("browse offline")
            listAnswer.await()
        }
        val readyList = MusicPagedList { readyListCalls++; MusicList(total = 1) }
        val failedGenres = MusicLoader {
            genreCalls++
            if (genreCalls == 1) error("genres offline")
            genreAnswer.await()
        }
        val readyPlaylists = MusicLoader { playlistCalls++; "playlists" }
        failedList.load()
        readyList.load()
        failedGenres.load()
        readyPlaylists.load()
        withTimeout(3_000) { failedList.state.first { it is Load.Failed } }
        withTimeout(3_000) { readyList.state.first { it is Load.Ready } }
        withTimeout(3_000) { failedGenres.state.first { it is Load.Failed } }
        withTimeout(3_000) { readyPlaylists.state.first { it is Load.Ready } }
        reloadFailedBrowseLoaders(listOf(failedList, readyList), failedGenres, readyPlaylists)
        assertIs<Load.Loading>(failedList.state.value)
        assertIs<Load.Loading>(failedGenres.state.value)
        listAnswer.complete(MusicList(total = 2))
        genreAnswer.complete("genres")
        withTimeout(3_000) { failedList.state.first { it is Load.Ready } }
        withTimeout(3_000) { failedGenres.state.first { it is Load.Ready } }
        assertEquals(2, listCalls)
        assertEquals(1, readyListCalls)
        assertEquals(2, genreCalls)
        assertEquals(1, playlistCalls)
    }

    @Test fun `socket recovery reloads Failed album, artist and playlist pages and leaves Ready ones alone`() = runBlocking {
        val answer = CompletableDeferred<String>()
        val calls = mutableMapOf("malbum:a" to 0, "martist:r:en" to 0, "mplaylist:p" to 0, "malbum:ok" to 0)
        fun loader(key: String, fails: Boolean) = MusicLoader {
            val n = calls.getValue(key) + 1
            calls[key] = n
            if (fails && n == 1) error("down") else if (fails) answer.await() else "ok"
        }
        val failed = listOf("malbum:a", "martist:r:en", "mplaylist:p").associateWith { loader(it, true) }
        val ready = loader("malbum:ok", false)
        (failed.values + ready).forEach { it.load() }
        failed.values.forEach { withTimeout(3_000) { it.state.first { s -> s is Load.Failed } } }
        withTimeout(3_000) { ready.state.first { it is Load.Ready } }
        recoverMusicEntryStores(failed + ("malbum:ok" to ready))
        failed.values.forEach { assertIs<Load.Loading>(it.state.value) }
        answer.complete("back")
        failed.values.forEach { withTimeout(3_000) { it.state.first { s -> s is Load.Ready } } }
        assertEquals(listOf(2, 2, 2, 1), listOf("malbum:a", "martist:r:en", "mplaylist:p", "malbum:ok").map { calls.getValue(it) })
    }

    @Test fun `unknown availability shows the desktop toggle only in Music`() {
        assertTrue(desktopMusicToggleVisible(null, true))
        assertFalse(desktopMusicToggleVisible(null, false))
        assertFalse(desktopMusicToggleVisible(false, true))
        assertFalse(desktopMusicToggleVisible(false, false))
        assertTrue(desktopMusicToggleVisible(true, false))
    }
}
