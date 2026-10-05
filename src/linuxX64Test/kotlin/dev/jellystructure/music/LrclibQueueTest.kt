package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.publish.PublishQueue
import dev.jellystructure.publish.PublishSendResult
import dev.jellystructure.publish.PublishSender
import dev.jellystructure.server.routes.musicRoutes
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 307 (FR-307-2) — 292's second action queues: one selection rule gives the count and the queued set, and
 * pressing *Queue for LRCLIB* sends nothing (acceptance 1).
 */
class LrclibQueueTest {
    private val base = "/tmp/jellystructure-test-lrclib-queue-${getpid()}"
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var store: MusicStore
    private lateinit var cfg: ConfigStore
    private lateinit var media: MusicMediaService
    private val hl = MusicCredit("x", "Harbour Lights")

    private class CountingSender : PublishSender {
        var calls = 0
        override suspend fun send(target: String, kind: String, payload: String): PublishSendResult { calls++; return PublishSendResult(null, "ok") }
    }

    @BeforeTest fun setUp() = runBlocking {
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$base.db$s") }
        db = createDatabase("$base.db")
        store = MusicStore(db)
        cfg = ConfigStore("$base.toml").also { it.load() }
        fun t(id: String, title: String, lyrics: String?, dur: Long? = 221_000L) =
            MusicTrack(id = id, albumId = "kw", libraryId = "l", title = title, artists = listOf(hl), lyricsState = lyrics, durationMs = dur, position = id.last().digitToInt())
        store.replaceLibrary(MusicLibraryRows("l", listOf(dev.jellystructure.model.MusicArtist(id = "x", name = "Harbour Lights", libraryId = "l")),
            listOf(MusicAlbum(id = "kw", libraryId = "l", title = "Kite Weather", albumArtists = listOf(hl), matchState = MusicMatch.MATCHED)), listOf(
                t("t1", "Fog Bank (Instrumental)", MusicLyrics.PLAIN),        // lyrics on an instrumental → yes
                t("t2", "Low Tide (Instrumental)", MusicLyrics.BLOCKED),      // lyrics removed by *Remove the lyrics* → yes
                t("t3", "Salt on the Window", MusicLyrics.SYNCED),           // sung → no
                t("t4", "Tidewater (Instrumental)", null),                   // no lyrics anywhere → no
                t("t5", "Shortwave (Instrumental)", MusicLyrics.PLAIN, dur = null),   // can't be described: no length
            )))
        media = MusicMediaService(store, ArtworkDownloader(TmdbClient(cfg), Screengrabber()), cfg, JellyfinClient(), FanartTvClient { "" })
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } }
    }

    @Test
    fun one_selection_gives_the_count_and_the_queued_set() {
        val songs = media.lrclibInstrumentalSongs(store.snapshot())
        assertEquals(listOf("t1", "t2", "t5"), songs.map { it.id })
        val p = media.lrclibInstrumentalProposals()
        assertEquals(songs.map { it.id }.filter { it != "t5" }, p.proposals.map { it.subject }, "the queued set is the selection, less what can't be described")
        assertEquals(listOf("Shortwave (Instrumental)"), p.skipped)
        val fog = p.proposals.first()
        assertEquals("Fog Bank (Instrumental) — Harbour Lights · Kite Weather", fog.label)
        assertEquals(Lrclib.instrumentalPayload("Harbour Lights", "Fog Bank (Instrumental)", "Kite Weather", 221), fog.payload)
        assertTrue(fog.payload.contains("\"plainLyrics\":\"\"") && fog.payload.contains("\"syncedLyrics\":\"\""))
    }

    @Test
    fun queue_for_lrclib_queues_and_sends_nothing() = testApplication {
        val sender = CountingSender()
        val queue = PublishQueue(db, sender).also { it.scope = CoroutineScope(Dispatchers.Default) }
        val pipeline = MusicPipeline(
            scanner = MusicScanner(cfg, JellyfinClient(), store), store = store,
            matcher = MusicMatchService(store, MusicBrainzClient(cfg), AcoustIdClient({ "" }), cfg, { null }),
            media = media,
        )
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/api") { musicRoutes(cfg, pipeline, CoroutineScope(Dispatchers.Default), publishQueue = queue) } }
        }
        val r = client.post("/api/music/lyrics/tell-lrclib")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("2 songs waiting to publish"), r.bodyAsText())
        assertEquals(0, sender.calls, "acceptance 1 — no request to lrclib.net")
        assertEquals(2, queue.waitingCount())
        assertEquals(2, queue.dashboardRow()?.count, "the Dashboard says the same n the queue holds")
        // Pressed again: nothing new.
        assertTrue(client.post("/api/music/lyrics/tell-lrclib").bodyAsText().contains("2 already queued or published"))
        assertEquals(2, queue.waitingCount())
        assertEquals(0, sender.calls)
    }
}
