package dev.jellystructure.autofetch

import dev.jellystructure.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 (FR-4.1, FR-3.3, FR-6.3, FR-13) — the json reader, the manifest reading, the ffmpeg commands and the config default. */
class ReadersAndManifestsTest {
    private val jsonList = ListSpec(
        kind = "json", url = "https://varpid.example.fo/api/dagbok", itemsPath = "data.items",
        fields = mapOf("title" to Field(path = "name"), "link" to Field(path = "url"), "published" to Field(path = "aired"), "id" to Field(path = "id"), "duration" to Field(path = "len")),
        next = Field(path = "paging.next"),
    )

    @Test
    fun `the json reader reads items by path and resolves links and the next page`() {
        val body = """{"data":{"items":[{"name":"Dagbók · 27. oktober","url":"/sendingar/dagbok/27","aired":"2026-10-27","id":"A27","len":1800},{"name":"Dagbók · 26. oktober","url":"/sendingar/dagbok/26","aired":"2026-10-26","id":"A26","len":1790}]},"paging":{"next":"/api/dagbok?page=2"}}"""
        val page = assertNotNull(ListingReader.readJson(jsonList, body, "https://varpid.example.fo/api/dagbok"))
        assertEquals(2, page.items.size)
        assertEquals("https://varpid.example.fo/sendingar/dagbok/27", page.items[0].link)
        assertEquals("A27", page.items[0].id); assertEquals("1800", page.items[0].duration)
        assertEquals("https://varpid.example.fo/api/dagbok?page=2", page.nextUrl)
    }

    @Test
    fun `the json reader takes a star path and a bare array path the same way`() {
        val body = """{"data":{"items":[{"name":"a","url":"/x/1"},{"name":"b","url":"/x/2"}]}}"""
        for (path in listOf("data.items", "data.items[*]")) {
            val spec = jsonList.copy(itemsPath = path, next = null)
            assertEquals(2, ListingReader.readJson(spec, body, "https://varpid.example.fo/api")!!.items.size, path)
        }
    }

    @Test
    fun `an answer that is not json is not a listing`() {
        assertNull(ListingReader.readJson(jsonList, "<!doctype html><html></html>", "https://varpid.example.fo/api"))
        assertEquals(0, ListingReader.readJson(jsonList, """{"other":1}""", "https://varpid.example.fo/api")!!.items.size)
    }

    @Test
    fun `json paths read indexes and wildcards`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement("""{"a":[{"b":"x"},{"b":"y"}],"n":null}""")
        assertEquals("y", JsonPaths.string(root, "a[1].b"))
        assertEquals(2, JsonPaths.select(root, "a[*].b").size)
        assertNull(JsonPaths.string(root, "n"))
        assertNull(JsonPaths.string(root, "missing.path"))
    }

    private val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Foroyskt",LANGUAGE="fao",DEFAULT=YES,URI="audio/fo.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Foroyskt",LANGUAGE="fao",URI="subs/fo.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="aud",SUBTITLES="subs"
360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,AUDIO="aud",SUBTITLES="subs"
1080/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,AUDIO="aud",SUBTITLES="subs"
720/index.m3u8
"""

    @Test
    fun `the highest variant is chosen with its audio and subtitle renditions`() {
        val m = assertNotNull(Manifests.parseHlsMaster(master, "https://media.example.fo/DOV7327/master.m3u8"))
        val best = m.best()!!
        assertEquals("https://media.example.fo/DOV7327/1080/index.m3u8", best.url)
        assertEquals("https://media.example.fo/DOV7327/audio/fo.m3u8", m.audioFor(best)?.uri)
        assertEquals("https://media.example.fo/DOV7327/subs/fo.m3u8", m.subtitleFor(best, "fao")?.uri)
        assertNull(m.subtitleFor(best, "eng"))
    }

    @Test
    fun `a media playlist is not a master`() {
        assertNull(Manifests.parseHlsMaster("#EXTM3U\n#EXTINF:6,\nseg1.ts\n", "https://x.example.fo/a.m3u8"))
    }

    @Test
    fun `a DRM key system is refused and plain AES 128 is not`() {
        assertTrue(Manifests.hasDrm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"skd://k\"\n"))
        assertTrue(Manifests.hasDrm("""<MPD><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/></MPD>"""))
        assertTrue(Manifests.hasDrm("""<MPD><ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"/></MPD>"""))
        assertFalse(Manifests.hasDrm("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://media.example.fo/key\"\n#EXTINF:6,\nseg1.ts\n"))
        assertFalse(Manifests.hasDrm(master))
    }

    @Test
    fun `the HLS command tags the audio at the mux and never writes a sidecar`() {
        val cmd = FetchCommands.hlsToMkv("https://m.example.fo/v.m3u8", "https://m.example.fo/a.m3u8", "https://m.example.fo/s.m3u8", "/lib/tv/S/Season 2026/.jellystructure/fetch_x.mkv", "fao", "fao")
        assertTrue("-metadata:s:a:0 language=fao" in cmd, cmd)
        assertTrue("-metadata:s:s:0 language=fao" in cmd, cmd)
        assertTrue("-c:v copy -c:a copy -c:s srt" in cmd, cmd)
        assertTrue("-f matroska '/lib/tv/S/Season 2026/.jellystructure/fetch_x.mkv'" in cmd, cmd)
        assertFalse("mkvpropedit" in cmd)
        assertFalse("-c:s" in FetchCommands.hlsToMkv("https://m.example.fo/v.m3u8", null, null, "/o.mkv", "fao", ""))
    }

    @Test
    fun `only the subtitle input is opened without the extension check`() {
        val cmd = FetchCommands.hlsToMkv("https://m.example.fo/v.m3u8", "https://m.example.fo/a.m3u8", "https://m.example.fo/s.m3u8", "/o.mkv", "fao", "fao")
        assertTrue("-extension_picky 0 -i 'https://m.example.fo/s.m3u8'" in cmd, cmd)
        assertEquals(1, Regex("-extension_picky").findAll(cmd).count(), cmd)
        assertFalse("-extension_picky" in FetchCommands.hlsToMkv("https://m.example.fo/v.m3u8", "https://m.example.fo/a.m3u8", null, "/o.mkv", "fao", ""))
    }

    @Test
    fun `the mkvinfo command looks at the first of Tracks and Cluster`() {
        assertTrue("mkvinfo -v '/a.mkv'" in FetchCommands.firstTopLevel("/a.mkv"))
        assertTrue("grep -E -m1" in FetchCommands.firstTopLevel("/a.mkv"))
    }

    @Test
    fun `auto-fetch is off by default`() {
        assertFalse(AppConfig().autofetch.enabled)
    }

    @Test
    fun `the page's own listing fields are read verbatim`() {
        val html = """<ul><li class="ep"><a href="/a/1">  Dagbók &middot;  16. oktober </a><p>Lýsing   16</p></li><li class="ep"><a href="/a/2">Dagbók · 17. oktober</a></li></ul>"""
        val spec = ListSpec(kind = "html", itemSelector = "li.ep", fields = mapOf("title" to Field("a"), "link" to Field("a", "href"), "description" to Field("p")))
        val items = ListingReader.readHtml(spec, html, "https://varpid.example.fo/a").items
        assertEquals("Dagbók · 16. oktober", items[0].title)   // whitespace is the page's layout, not its words
        assertEquals("Lýsing 16", items[0].description); assertNull(items[1].description)
    }
}
