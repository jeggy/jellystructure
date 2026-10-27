package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvLadderRung
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2635 (DEB-2627, FR-1, FR-2, FR-4 backend half). A fake Jellyfin whose `/LiveStreams/Open` hands back a configurable
 * `Path` / `TranscodingUrl`, and a fake broadcaster on the same loopback serving the playlists the classifier reads. The
 * real [LiveTvService]; [LiveTvTuneTokenTest] owns the token and visibility rules this file does not repeat.
 */
class LiveTvLadderTest {

    private class Fake(val serverKey: String) {
        var base = ""
        /** What the open answers as `MediaSource.Path`: a full URL, or a Jellyfin-local path. */
        var openPath = "/live/stream.m3u8"
        var openTranscodingUrl: String? = null
        val upstreamHits = mutableMapOf<String, Int>()
        val closed = mutableListOf<String>()
        fun up(path: String) = "$base/up/$path"
        fun hits(name: String) = upstreamHits[name] ?: 0
    }

    private fun ladder(vararg rungs: Triple<Long, Int, Int>) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        for ((bw, w, h) in rungs) {
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=$bw,AVERAGE-BANDWIDTH=${bw - 1},RESOLUTION=${w}x$h,CODECS=\"avc1.64001f,mp4a.40.2\"")
            appendLine("v$h/index.m3u8")
        }
    }

    // Deliberately not in bandwidth order: the wire says highest first whatever the master's order is.
    private val fiveRungs = ladder(
        Triple(560_000L, 426, 240), Triple(7_700_000L, 1920, 1080), Triple(1_400_000L, 640, 360),
        Triple(3_000_000L, 1280, 720), Triple(2_000_000L, 854, 480),
    )
    private val oneVariant = ladder(Triple(2_000_000L, 854, 480))
    private val sampleAes = fiveRungs.replaceFirst("#EXTM3U\n", "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n")
    private val mediaPlaylist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"

    private fun device(run: String, token: String) = DeviceData(
        deviceId = "dev-ladder-$run", deviceToken = "dt-ladder-$run", jellyfinUserId = "u1", jellyfinUsername = "u1",
        jellyfinUserToken = token, isAdmin = false, allowedTags = emptySet(),
    )

    private fun tokenOf(authorization: String?): String? =
        Regex("Token=\"([^\"]+)\"").find(authorization.orEmpty())?.groupValues?.get(1)

    private fun harness(body: suspend (Fake, LiveTvService, DeviceData) -> Unit) = runBlocking {
        DeviceIdentityRegistry.clear()
        val run = getpid().toString()
        val fake = Fake("server-key-$run")
        val tok = "viewer-ladder-$run"
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/LiveTv/Channels") {
                    call.respondText("""{"Items":[{"Id":"c1","Name":"Channel c1"}]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    call.respondText("""{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","OpenToken":"open1"}]}""", ContentType.Application.Json)
                }
                post("/LiveStreams/Open") {
                    // The transcoding URL carries the caller's own token, the way Jellyfin spells the credential it was asked under.
                    val seen = tokenOf(call.request.headers["Authorization"])
                    val transcoding = fake.openTranscodingUrl?.replace("{token}", seen.orEmpty())?.let { ""","TranscodingUrl":"$it"""" }.orEmpty()
                    call.respondText(
                        """{"MediaSource":{"Id":"ms1","Path":"${fake.openPath}","LiveStreamId":"ls1"$transcoding}}""",
                        ContentType.Application.Json,
                    )
                }
                post("/LiveStreams/Close") {
                    fake.closed += "ls1"
                    call.respondText("{}", ContentType.Application.Json)
                }
                fun playlist(name: String, text: String) = get("/up/$name") {
                    fake.upstreamHits[name] = fake.hits(name) + 1
                    call.respondText(text, ContentType.parse("application/vnd.apple.mpegurl"))
                }
                playlist("five.m3u8", fiveRungs)
                playlist("one.m3u8", oneVariant)
                playlist("aes.m3u8", sampleAes)
                playlist("media.m3u8", mediaPlaylist)
                playlist("page", "<html>not a playlist</html>")
                get("/up/redirect") {
                    fake.upstreamHits["redirect"] = fake.hits("redirect") + 1
                    call.respondRedirect("/up/five.m3u8?session=per-viewer-1")
                }
                get("/up/loop.m3u8") {
                    fake.upstreamHits["loop.m3u8"] = fake.hits("loop.m3u8") + 1
                    call.respondRedirect("/up/loop.m3u8")
                }
                get("/up/gone.m3u8") { call.respond(HttpStatusCode.NotFound) }
                get("/up/gone") { call.respond(HttpStatusCode.NotFound) }
                get("/up/slow.m3u8") { delay(6_000); call.respondText(fiveRungs) }
            }
        }.start(wait = false)
        fake.base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val cfgPath = "/tmp/jellystructure-test-livetv-ladder-$run.toml"
        val storePath = "/tmp/jellystructure-test-livetv-ladder-$run-store.json"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = fake.base, jellyfinToken = fake.serverKey)))
        val service = LiveTvService("/tmp/jellystructure-test-livetv-ladder-$run", LiveTvStore(storePath), JellyfinClient(), configStore)
        try {
            body(fake, service, device(run, tok))
        } finally {
            server.stop(100, 500)
            for (f in listOf(cfgPath, storePath)) platform.posix.remove(f)
            runCatching { kotlinx.io.files.SystemFileSystem.delete(kotlinx.io.files.Path("/tmp/jellystructure-test-livetv-ladder-$run/artwork/livetv-logos")) }
            DeviceIdentityRegistry.clear()
        }
    }

    private suspend fun LiveTvService.tuneC1(d: DeviceData, via: String? = null) =
        assertNotNull(tune(d, "c1", ClientCapabilities(), via))

    @Test
    fun `five rungs are an hls ladder and the ladder is listed highest first`() = harness { fake, service, d ->
        fake.openPath = fake.up("five.m3u8")
        val t = service.tuneC1(d)
        assertEquals("hls_ladder", t.streamKind)
        assertEquals(
            listOf(
                LiveTvLadderRung(7_700_000, 1920, 1080), LiveTvLadderRung(3_000_000, 1280, 720), LiveTvLadderRung(2_000_000, 854, 480),
                LiveTvLadderRung(1_400_000, 640, 360), LiveTvLadderRung(560_000, 426, 240),
            ),
            t.ladder,
        )
        assertEquals(fake.up("five.m3u8"), t.hlsUrl)
    }

    @Test
    fun `one variant is plain hls with no ladder`() = harness { fake, service, d ->
        fake.openPath = fake.up("one.m3u8")
        val t = service.tuneC1(d)
        assertEquals("hls", t.streamKind)
        assertEquals(emptyList(), t.ladder)
    }

    @Test
    fun `a SAMPLE-AES protected master is plain hls`() = harness { fake, service, d ->
        fake.openPath = fake.up("aes.m3u8")
        val t = service.tuneC1(d)
        assertEquals("hls", t.streamKind)
        assertEquals(emptyList(), t.ladder)
    }

    @Test
    fun `a media playlist is plain hls`() = harness { fake, service, d ->
        fake.openPath = fake.up("media.m3u8")
        assertEquals("hls", service.tuneC1(d).streamKind)
    }

    @Test
    fun `a Jellyfin-local path is other and nothing is fetched`() = harness { fake, service, d ->
        fake.openPath = "/live/stream.m3u8"
        val t = service.tuneC1(d)
        assertEquals("other", t.streamKind)
        assertEquals(emptyList(), t.ladder)
        assertEquals("${fake.base}/live/stream.m3u8", t.hlsUrl, "a relative path is made absolute against Jellyfin, as before")
        assertTrue(fake.upstreamHits.isEmpty())
    }

    @Test
    fun `a body that is not a playlist is other`() = harness { fake, service, d ->
        fake.openPath = fake.up("page")
        assertEquals("other", service.tuneC1(d).streamKind)
    }

    @Test
    fun `a fetch that times out is hls when the URL says m3u8 and never a tune failure`() = harness { fake, service, d ->
        fake.openPath = fake.up("slow.m3u8")
        val t = service.tuneC1(d)
        assertEquals("hls", t.streamKind)
        assertEquals(emptyList(), t.ladder)
        assertEquals(fake.up("slow.m3u8"), t.hlsUrl)
    }

    @Test
    fun `a failed fetch is hls with m3u8 in the URL and other without`() = harness { fake, service, d ->
        fake.openPath = fake.up("gone.m3u8")
        assertEquals("hls", service.tuneC1(d).streamKind)
        fake.openPath = fake.up("gone")
        assertEquals("other", service.tuneC1(d).streamKind)
    }

    @Test
    fun `a redirect loop stops after three hops and is classified from the URL`() = harness { fake, service, d ->
        fake.openPath = fake.up("loop.m3u8")
        assertEquals("hls", service.tuneC1(d).streamKind)
        assertEquals(4, fake.hits("loop.m3u8"), "the first request and three redirects, no more")
    }

    @Test
    fun `the ticket's url is Path byte for byte when the upstream redirects`() = harness { fake, service, d ->
        fake.openPath = fake.up("redirect")
        val t = service.tuneC1(d)
        assertEquals("hls_ladder", t.streamKind, "the redirect was followed for the look")
        assertEquals(fake.up("redirect"), t.hlsUrl, "the per-viewer URL the redirect minted is never the client's")
        assertFalse("per-viewer" in t.hlsUrl)
    }

    @Test
    fun `a second tune of the same channel and path within the hour does not fetch again`() = harness { fake, service, d ->
        fake.openPath = fake.up("one.m3u8")
        service.tuneC1(d)
        val second = service.tuneC1(d)
        assertEquals(1, fake.hits("one.m3u8"))
        assertEquals("hls", second.streamKind)
    }

    @Test
    fun `via jellyfin returns the absolute TranscodingUrl under the device's token and never looks at the source`() = harness { fake, service, d ->
        fake.openPath = fake.up("five.m3u8")
        fake.openTranscodingUrl = "/videos/ls1/master.m3u8?api_key={token}&LiveStreamId=ls1"
        val t = service.tuneC1(d, via = "jellyfin")
        assertEquals("${fake.base}/videos/ls1/master.m3u8?api_key=${d.jellyfinUserToken}&LiveStreamId=ls1", t.hlsUrl)
        assertEquals("hls", t.streamKind)
        assertEquals(emptyList(), t.ladder)
        assertEquals(d.jellyfinUserToken, t.accessToken)
        assertFalse(fake.serverKey in t.hlsUrl)
        assertTrue(fake.upstreamHits.isEmpty())
    }

    @Test
    fun `via jellyfin keeps an already absolute TranscodingUrl as it is`() = harness { fake, service, d ->
        fake.openTranscodingUrl = "https://jf.example/videos/ls1/master.m3u8?api_key={token}"
        assertEquals("https://jf.example/videos/ls1/master.m3u8?api_key=${d.jellyfinUserToken}", service.tuneC1(d, via = "jellyfin").hlsUrl)
    }

    @Test
    fun `via jellyfin with no TranscodingUrl is no tune and the opened stream is closed`() = harness { fake, service, d ->
        fake.openPath = fake.up("five.m3u8")
        assertNull(service.tune(d, "c1", ClientCapabilities(), "jellyfin"))
        assertEquals(listOf("ls1"), fake.closed)
    }

    @Test
    fun `an unknown via is the source as no via`() = harness { fake, service, d ->
        fake.openPath = fake.up("five.m3u8")
        fake.openTranscodingUrl = "/videos/ls1/master.m3u8?api_key={token}"
        val t = service.tuneC1(d, via = "somewhere-else")
        assertEquals(fake.up("five.m3u8"), t.hlsUrl)
        assertEquals("hls_ladder", t.streamKind)
    }
}
