package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-5542 — the clearlogo ink map is kept in `<config dir>/clearlogo-ink.json` and read back on the next start. */
class ClearlogoInkPersistTest {
    private val base = "/tmp/clearlogo-ink-test-${getpid()}"

    private fun show() = MediaItem(id = "show-2020", title = "Show", year = 2020, kind = MediaKind.TV_SHOW, path = "$base/show", tmdbId = 42, originalLanguage = "en", posterPath = null, backdropPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0)

    private suspend fun waitFor(what: () -> Boolean) { repeat(100) { if (what()) return; delay(50) } }

    @Test
    fun `a verdict is written to the file and a second instance answers without judging`() = runBlocking {
        SystemFileSystem.createDirectories(Path("$base/show"))
        FileIo.writeBytes(Path("$base/show/clearlogo.png"), byteArrayOf(1, 2, 3))
        val file = Path("$base/clearlogo-ink.json")
        runCatching { SystemFileSystem.delete(file, mustExist = false) }
        val scope = CoroutineScope(Dispatchers.Default)
        var judged = 0
        val first = ClearlogoInk(scope, base, base, inkOf = { _, _ -> judged++; "light" })
        assertNull(first.inkFor(show()))                       // a miss queues the file
        waitFor { SystemFileSystem.exists(file) }
        assertTrue(SystemFileSystem.exists(file))
        assertEquals(1, judged)

        var judgedAgain = 0
        val second = ClearlogoInk(scope, base, base, inkOf = { _, _ -> judgedAgain++; "dark" })
        assertEquals("light", second.inkFor(show()))
        assertEquals(0, judgedAgain)
    }

    @Test
    fun `a corrupt file starts empty`() = runBlocking {
        SystemFileSystem.createDirectories(Path("$base/show"))
        FileIo.writeText(Path("$base/clearlogo-ink.json"), "{not json")
        val ink = ClearlogoInk(CoroutineScope(Dispatchers.Default), base, base, inkOf = { _, _ -> null })
        assertNull(ink.inkFor(show()))
    }
}
