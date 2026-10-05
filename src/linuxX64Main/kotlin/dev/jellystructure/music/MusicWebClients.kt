package dev.jellystructure.music

import dev.jellystructure.OutboundHttp
import dev.jellystructure.ServerVersion
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MusicArtCandidate
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private fun ua() = "jellystructure/${ServerVersion.current.ifBlank { "dev" }} ( https://github.com/jeggy/jellystructure )"

/** GET as text through the shared outbound gate; null on any failure or non-200 (logged once, never thrown). */
internal suspend fun fetchText(url: String, context: String, limiter: FixedRateLimiter? = null): String? {
    limiter?.acquire()
    return runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.get(url) { header(HttpHeaders.UserAgent, ua()); header(HttpHeaders.Accept, "application/json") }
            if (r.status == HttpStatusCode.OK) r.bodyAsText() else null
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        Logger.warn("$context failed: ${e.message}", "music"); null
    }
}

private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/** FR-277-1/2 — the Cover Art Archive: a release's (or release-group's) images, and the front to download. */
object CoverArtArchive {
    private val limiter = FixedRateLimiter(4.0, burst = 2.0)   // no published limit; stay polite

    fun frontUrl(releaseGroupMbid: String) = "https://coverartarchive.org/release-group/$releaseGroupMbid/front-1200"
    fun releaseFrontUrl(releaseMbid: String) = "https://coverartarchive.org/release/$releaseMbid/front-1200"

    /** Every image of the release (else the release-group's chosen release), `approved` marked. Null = no answer. */
    suspend fun images(releaseMbid: String?, releaseGroupMbid: String?): List<MusicArtCandidate>? {
        val url = releaseMbid?.let { "https://coverartarchive.org/release/$it/" } ?: releaseGroupMbid?.let { "https://coverartarchive.org/release-group/$it/" } ?: return emptyList()
        val body = fetchText(url, "Cover Art Archive", limiter) ?: return if (releaseMbid != null && releaseGroupMbid != null) images(null, releaseGroupMbid) else null
        val images = runCatching { json.parseToJsonElement(body).jsonObject["images"]?.jsonArray }.getOrNull() ?: return emptyList()
        return images.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val full = o["image"].str() ?: return@mapNotNull null
            val types = (o["types"] as? JsonArray)?.mapNotNull { it.str()?.lowercase() }.orEmpty()
            val thumbs = o["thumbnails"] as? JsonObject
            val front = (o["front"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
            MusicArtCandidate(
                kind = when { front -> "front"; "back" in types -> "back"; "booklet" in types -> "booklet"; else -> types.firstOrNull() ?: "other" },
                url = thumbs?.get("1200").str() ?: full,
                thumb = thumbs?.get("500").str() ?: thumbs?.get("250").str() ?: full,
                source = "Cover Art Archive",
                approved = (o["approved"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull != false,
            )
        }
    }
}

/** FR-277-1/2 — fanart.tv (needs a project key): artist thumbs, backgrounds, logos, and per-album covers and CD art. */
class FanartTvClient(private val key: () -> String) {
    private val limiter = FixedRateLimiter(2.0, burst = 2.0)
    val available: Boolean get() = key().isNotBlank()

    data class Artist(val thumbs: List<MusicArtCandidate>, val backgrounds: List<MusicArtCandidate>, val logos: List<MusicArtCandidate>, val albums: Map<String, List<MusicArtCandidate>>)

    /** Null = no key or no answer. The v3 answer carries `albums` as a map keyed by release-group id (v3.2 made it an
     *  array); both are read. */
    suspend fun artist(artistMbid: String): Artist? {
        val k = key().ifBlank { return null }
        limiter.acquire()
        // The answer also tells the providers card whether the key works (a 401 turns its dot red).
        val (status, text) = ProviderKeyChecks.probe("https://webservice.fanart.tv/v3/music/$artistMbid?api_key=${k.encodeURLParameter()}", "fanart.tv")
        ProviderKeyChecks.fanart.record(k, ProviderKeyChecks.fanartVerdict(status, text))
        val body = text?.takeIf { status == 200 } ?: return null
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun list(el: JsonElement?, kind: String) = (el as? JsonArray)?.mapNotNull { e ->
            val u = (e as? JsonObject)?.get("url").str() ?: return@mapNotNull null
            MusicArtCandidate(kind, u, u.replace("/fanart/", "/preview/"), "fanart.tv")
        }.orEmpty()
        val albums = HashMap<String, List<MusicArtCandidate>>()
        when (val a = o["albums"]) {
            is JsonObject -> a.forEach { (rg, v) -> val vo = v as? JsonObject ?: return@forEach; albums[rg] = list(vo["albumcover"], "albumcover") + list(vo["cdart"], "cdart") }
            is JsonArray -> a.forEach { v -> val vo = v as? JsonObject ?: return@forEach; val rg = vo["release_group_id"].str() ?: return@forEach; albums[rg] = list(vo["albumcover"], "albumcover") + list(vo["cdart"], "cdart") }
            else -> Unit
        }
        return Artist(list(o["artistthumb"], "thumb"), list(o["artistbackground"], "background"), list(o["hdmusiclogo"], "logo") + list(o["musiclogo"], "logo"), albums)
    }
}

/** FR-277-1 — an artist picture from Wikimedia Commons (MusicBrainz's `image` URL relationship), with its credit. */
object WikimediaCommons {
    private val limiter = FixedRateLimiter(2.0, burst = 2.0)

    /** `https://commons.wikimedia.org/wiki/File:X.jpg` → a 1000 px rendition and `author · licence · Wikimedia Commons`. */
    suspend fun picture(filePageUrl: String): MusicArtCandidate? {
        val title = filePageUrl.substringAfter("/wiki/", "").takeIf { it.startsWith("File:") } ?: return null
        val api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1000&titles=${title.encodeURLParameter()}"
        val body = fetchText(api, "Wikimedia Commons", limiter) ?: return null
        val page = runCatching { json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject?.values?.firstOrNull()?.jsonObject }.getOrNull() ?: return null
        val info = (page["imageinfo"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val url = info["thumburl"].str() ?: info["url"].str() ?: return null
        val meta = info["extmetadata"] as? JsonObject
        fun m(k: String) = (meta?.get(k) as? JsonObject)?.get("value").str()?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotBlank() }
        val credit = listOfNotNull(m("Artist"), m("LicenseShortName"), "Wikimedia Commons").joinToString(" · ")
        return MusicArtCandidate("thumb", url, url, "Wikimedia Commons", credit = credit)
    }
}

/** FR-277-6 — the lead of an artist's Wikipedia article, found through MusicBrainz's `wikidata`/`wikipedia` links. */
object WikipediaBio {
    private val limiter = FixedRateLimiter(3.0, burst = 3.0)

    /** Language → lead text for the languages asked (those with an article), plus where it came from. */
    suspend fun leads(urls: Map<String, String>, languages: List<String>): Pair<Map<String, String>, String?> {
        val titles = LinkedHashMap<String, String>()   // lang → article title
        urls["wikipedia"]?.let { u ->
            val lang = u.substringAfter("://").substringBefore(".wikipedia.org", "")
            val t = u.substringAfter("/wiki/", "")
            if (lang.isNotBlank() && t.isNotBlank()) titles[lang] = t
        }
        var description: String? = null
        urls["wikidata"]?.substringAfterLast('/')?.takeIf { it.startsWith("Q") }?.let { q ->
            val body = fetchText("https://www.wikidata.org/wiki/Special:EntityData/$q.json", "Wikidata", limiter)
            val ent = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["entities"]?.jsonObject?.get(q)?.jsonObject }.getOrNull() }
            val links = ent?.get("sitelinks") as? JsonObject
            for (lang in languages) (links?.get("${lang}wiki") as? JsonObject)?.get("title").str()?.let { if (lang !in titles) titles[lang] = it }
            description = ((ent?.get("descriptions") as? JsonObject)?.get("en") as? JsonObject)?.get("value").str()
        }
        val out = LinkedHashMap<String, String>()
        for (lang in languages) {
            val t = titles[lang] ?: continue
            val body = fetchText("https://$lang.wikipedia.org/api/rest_v1/page/summary/${t.replace(' ', '_').encodeURLPath()}", "Wikipedia", limiter) ?: continue
            runCatching { json.parseToJsonElement(body).jsonObject["extract"].str() }.getOrNull()?.trim()?.takeIf { it.length > 40 }?.let { out[lang] = it }
        }
        if (out.isEmpty() && !description.isNullOrBlank()) { out["en"] = description!!; return out to "Wikidata" }
        val first = out.keys.firstOrNull() ?: return out to null
        return out to "Wikipedia ($first)"
    }
}

/** FR-277-8 — LRCLIB: synced (LRC) or plain lyrics by artist, title, album and duration. No key. */
object Lrclib {
    private val limiter = FixedRateLimiter(2.0, burst = 2.0)

    /** [id] is LRCLIB's own id for the entry (292: recorded with the sidecar, dev review 8a). */
    data class Lyrics(val synced: String?, val plain: String?, val instrumental: Boolean, val id: Long? = null)

    /** Exact lookup first, then a search whose duration is within 3 s. Null = no answer; a Lyrics with both texts
     *  null and instrumental false = nothing found. */
    suspend fun find(artist: String, title: String, album: String?, durationSec: Int?): Lyrics? {
        val q = buildString {
            append("artist_name=${artist.encodeURLParameter()}&track_name=${title.encodeURLParameter()}")
            album?.takeIf { it.isNotBlank() }?.let { append("&album_name=${it.encodeURLParameter()}") }
            durationSec?.let { append("&duration=$it") }
        }
        fetchText("https://lrclib.net/api/get?$q", "LRCLIB", limiter)?.let { body ->
            parse(runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull())?.let { return it }
        }
        val search = fetchText("https://lrclib.net/api/search?track_name=${title.encodeURLParameter()}&artist_name=${artist.encodeURLParameter()}", "LRCLIB", limiter)
            ?: return null
        val hits = runCatching { json.parseToJsonElement(search).jsonArray }.getOrNull() ?: return Lyrics(null, null, false)
        val best = hits.mapNotNull { it as? JsonObject }.filter { h ->
            val d = (h["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            durationSec == null || d == null || kotlin.math.abs(d - durationSec) <= 3.0
        }.sortedByDescending { if (it["syncedLyrics"].str().isNullOrBlank()) 0 else 1 }.firstOrNull()
        return parse(best) ?: Lyrics(null, null, false)
    }

    private fun parse(o: JsonObject?): Lyrics? {
        o ?: return null
        val instrumental = (o["instrumental"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
        val synced = o["syncedLyrics"].str()?.takeIf { it.isNotBlank() }
        val plain = o["plainLyrics"].str()?.takeIf { it.isNotBlank() }
        if (!instrumental && synced == null && plain == null) return null
        return Lyrics(synced, plain, instrumental, (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull())
    }

    // ── Phase 292 (FR-292-15, Q9) — LRCLIB told a song is instrumental; since 307, only through the publish queue ──

    /**
     * 307 (FR-307-1) — the exact body `POST /api/publish` is sent, built once when the song is queued and frozen in
     * `publish_item.payload`: both lyrics fields empty mark the track instrumental.
     */
    fun instrumentalPayload(artist: String, title: String, album: String, durationSec: Int): String = kotlinx.serialization.json.buildJsonObject {
        put("trackName", kotlinx.serialization.json.JsonPrimitive(title))
        put("artistName", kotlinx.serialization.json.JsonPrimitive(artist))
        put("albumName", kotlinx.serialization.json.JsonPrimitive(album))
        put("duration", kotlinx.serialization.json.JsonPrimitive(durationSec))
        put("plainLyrics", kotlinx.serialization.json.JsonPrimitive(""))
        put("syncedLyrics", kotlinx.serialization.json.JsonPrimitive(""))
    }.toString()

    /** 307 (FR-307-4) — what one publish came to: [error] null = LRCLIB accepted it; [answer] is its reply, one line. */
    data class PublishOutcome(val error: String?, val answer: String)

    /**
     * LRCLIB's documented publish API (no account): `POST /api/request-challenge` answers `{prefix, target}`; the
     * client finds a nonce whose SHA-256 of `prefix + nonce` is at most `target` (both hex), and sends
     * `X-Publish-Token: prefix:nonce` with `POST /api/publish`. A publish with both lyrics fields empty marks the
     * track instrumental. It adds an entry; it does not correct the copied one.
     *
     * **Not verified against the live API** (292 build notes): verifying needs a real write to a public database,
     * which the build did not send. Every failure comes back as one sentence.
     *
     * 307 (FR-307-4) — called from one place only, the publish queue's worker ([dev.jellystructure.publish.LivePublishSender]),
     * with the [payload] frozen when the song was queued, sent byte for byte (`PublishCallSiteTest` holds the line).
     */
    suspend fun publishInstrumental(payload: String, maxTries: Long = 200_000_000L): PublishOutcome {
        fun fail(why: String) = PublishOutcome(why, why)
        val challenge = postText("https://lrclib.net/api/request-challenge", null, null) ?: return fail("LRCLIB didn't hand out a challenge")
        val o = runCatching { json.parseToJsonElement(challenge).jsonObject }.getOrNull() ?: return fail("LRCLIB's challenge could not be read")
        val prefix = o["prefix"].str() ?: return fail("LRCLIB's challenge had no prefix")
        val target = o["target"].str()?.lowercase() ?: return fail("LRCLIB's challenge had no target")
        val nonce = solve(prefix, target, maxTries) ?: return fail("the challenge was not solved in time")
        val reply = postText("https://lrclib.net/api/publish", payload, "$prefix:$nonce") ?: return fail("LRCLIB did not accept it")
        return PublishOutcome(null, "Accepted" + reply.trim().replace(Regex("\\s+"), " ").take(200).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
    }

    /** The proof of work: the first nonce whose hash is at most [target]. Yields now and then so it can be cancelled. */
    suspend fun solve(prefix: String, target: String, maxTries: Long): Long? {
        var n = 0L
        while (n < maxTries) {
            if (dev.jellystructure.auth.sha256Hex(prefix + n) <= target) return n
            n++
            if (n % 20_000L == 0L) kotlinx.coroutines.yield()
        }
        return null
    }

    private suspend fun postText(url: String, body: String?, token: String?): String? = runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.post(url) {
                header(HttpHeaders.UserAgent, ua())
                token?.let { header("X-Publish-Token", it) }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
            if (r.status.value in 200..299) r.bodyAsText() else { Logger.warn("LRCLIB $url answered ${r.status.value}", "music"); null }
        }
    }.getOrElse { e -> if (e is CancellationException) throw e; Logger.warn("LRCLIB $url failed: ${e.message}", "music"); null }
}
