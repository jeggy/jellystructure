package dev.jellystructure.audiobooks

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookSuggestion
import dev.jellystructure.model.MusicArtCandidate
import dev.jellystructure.music.FixedRateLimiter
import dev.jellystructure.music.MusicBrainzClient
import dev.jellystructure.music.fetchText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Phase 281 (FR-281-3/4) — the four places a book's facts may be *suggested* from, in the rail's order: iTunes (the
 * chosen store), Google Books (only with a key — the shared quota is always spent), Open Library (1 request a
 * second with an identifying User-Agent), and Audnexus (only with an ASIN the admin pastes — dev review 3: no
 * Audible scraping). MusicBrainz is asked too and gets a card only when it hits. Every card says in one sentence what
 * the provider knows, found or not. Nothing here writes anything: 281's editor applies what the admin accepts.
 */
class AudiobookProviders(private val configStore: ConfigStore, private val mb: MusicBrainzClient? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val openLibrary = FixedRateLimiter(1.0)
    private val itunes = FixedRateLimiter(1.0, burst = 3.0)

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun year(s: String?): Int? = s?.take(4)?.toIntOrNull()
    private fun stripHtml(s: String?): String? = s?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")?.replace(Regex("<[^>]+>"), "")
        ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&nbsp;", " ")?.trim()?.takeIf { it.isNotEmpty() }
    private fun words(b: Audiobook) = listOfNotNull(b.title, b.authors.firstOrNull()).joinToString(" ")
    private fun sameTitle(a: String?, b: String) = a != null && a.lowercase().filter { it.isLetterOrDigit() }.let { x -> val y = b.lowercase().filter { it.isLetterOrDigit() }; x == y || x.startsWith(y) || y.startsWith(x) }

    /** Every provider's card for [book]; [asin] asks Audnexus. */
    suspend fun ask(book: Audiobook, asin: String? = null): List<AudiobookSuggestion> = listOfNotNull(
        itunes(book), googleBooks(book), openLibrary(book), audnexus(book, asin), musicBrainz(book),
    )

    suspend fun itunes(book: Audiobook): AudiobookSuggestion {
        val store = configStore.current.audiobooks.itunesStore.ifBlank { "dk" }
        val body = fetchText("https://itunes.apple.com/search?term=${words(book).encodeURLParameter()}&media=audiobook&entity=audiobook&country=$store&limit=5", "iTunes", itunes)
            ?: return AudiobookSuggestion("iTunes", note = "iTunes didn't answer — ask again in a moment.")
        val results = runCatching { json.parseToJsonElement(body).obj()?.get("results").arr() }.getOrNull().orEmpty().mapNotNull { it.obj() }
        val hit = results.firstOrNull { sameTitle(it["collectionName"].s(), book.title) }
            ?: return AudiobookSuggestion("iTunes", note = if (results.isEmpty()) "The ${store.uppercase()} store has no audiobook by that title." else "The ${store.uppercase()} store has ${results.size} other audiobooks for that search, none of them this one.")
        val cover = hit["artworkUrl100"].s()?.replace("100x100", "600x600")
        return AudiobookSuggestion(
            "iTunes", found = true, note = "The ${store.uppercase()} store sells it.", title = hit["collectionName"].s(), author = hit["artistName"].s(),
            year = year(hit["releaseDate"].s()), coverUrl = cover,
            fields = buildMap {
                hit["collectionName"].s()?.let { put("title", it) }
                hit["artistName"].s()?.let { put("authors", it.replace(" & ", "; ").replace(", ", "; ")) }
                year(hit["releaseDate"].s())?.let { put("year", it.toString()) }
                stripHtml(hit["description"].s())?.let { put("description", it) }
                hit["primaryGenreName"].s()?.let { put("genres", it) }
            },
        )
    }

    suspend fun googleBooks(book: Audiobook): AudiobookSuggestion {
        val key = configStore.current.apiKeys.googleBooksKey.trim()
        if (key.isEmpty()) return AudiobookSuggestion("Google Books", note = "No API key — without one its shared quota is always used up, so it is skipped.")
        val q = "intitle:" + book.title + (book.authors.firstOrNull()?.let { " inauthor:$it" } ?: "")
        val body = fetchText("https://www.googleapis.com/books/v1/volumes?q=${q.encodeURLParameter()}&maxResults=5&key=${key.encodeURLParameter()}", "Google Books")
            ?: return AudiobookSuggestion("Google Books", note = "Google Books didn't answer — ask again in a moment.")
        val items = runCatching { json.parseToJsonElement(body).obj()?.get("items").arr() }.getOrNull().orEmpty().mapNotNull { it.obj()?.get("volumeInfo").obj() }
        val v = items.firstOrNull { sameTitle(it["title"].s(), book.title) }
            ?: return AudiobookSuggestion("Google Books", note = if (items.isEmpty()) "It has no book by that title." else "It has ${items.size} other books for that search, none of them this one.")
        val authors = v["authors"].arr()?.mapNotNull { it.s() }.orEmpty()
        return AudiobookSuggestion(
            "Google Books", found = true, note = "It knows the printed book.", title = v["title"].s(), author = authors.firstOrNull(), year = year(v["publishedDate"].s()),
            coverUrl = v["imageLinks"].obj()?.get("thumbnail").s()?.replace("http://", "https://"),
            fields = buildMap {
                v["title"].s()?.let { put("title", it) }
                v["subtitle"].s()?.let { put("subtitle", it) }
                if (authors.isNotEmpty()) put("authors", authors.joinToString("; "))
                v["publisher"].s()?.let { put("publisher", it) }
                year(v["publishedDate"].s())?.let { put("year", it.toString()) }
                v["language"].s()?.let { put("language", it) }
                stripHtml(v["description"].s())?.let { put("description", it) }
                v["categories"].arr()?.mapNotNull { it.s() }?.takeIf { it.isNotEmpty() }?.let { put("genres", it.joinToString("; ")) }
            },
        )
    }

    suspend fun openLibrary(book: Audiobook): AudiobookSuggestion {
        val author = book.authors.firstOrNull()
        val body = fetchText("https://openlibrary.org/search.json?title=${book.title.encodeURLParameter()}${author?.let { "&author=" + it.encodeURLParameter() } ?: ""}&limit=5", "Open Library", openLibrary)
            ?: return AudiobookSuggestion("Open Library", note = "Open Library didn't answer — ask again in a moment.")
        val docs = runCatching { json.parseToJsonElement(body).obj()?.get("docs").arr() }.getOrNull().orEmpty().mapNotNull { it.obj() }
        val d = docs.firstOrNull { sameTitle(it["title"].s(), book.title) }
        if (d == null) {
            val works = author?.let { authorWorks(it) }
            return AudiobookSuggestion("Open Library", note = when {
                works != null && works > 0 -> "It knows $author — $works works, none of them this one."
                else -> "It has no book by that title."
            })
        }
        val authors = d["author_name"].arr()?.mapNotNull { it.s() }.orEmpty()
        val cover = (d["cover_i"] as? JsonPrimitive)?.intOrNull?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
        return AudiobookSuggestion(
            "Open Library", found = true, note = "It knows the book.", title = d["title"].s(), author = authors.firstOrNull(),
            year = (d["first_publish_year"] as? JsonPrimitive)?.intOrNull, coverUrl = cover,
            fields = buildMap {
                d["title"].s()?.let { put("title", it) }
                if (authors.isNotEmpty()) put("authors", authors.joinToString("; "))
                (d["first_publish_year"] as? JsonPrimitive)?.intOrNull?.let { put("year", it.toString()) }
                d["publisher"].arr()?.firstOrNull().s()?.let { put("publisher", it) }
                d["subject"].arr()?.mapNotNull { it.s() }?.take(3)?.takeIf { it.isNotEmpty() }?.let { put("genres", it.joinToString("; ")) }
            },
        )
    }

    /** How many works Open Library credits to an author (the Author page's sentence, FR-281-9). */
    suspend fun authorWorks(name: String): Int? {
        val body = fetchText("https://openlibrary.org/search/authors.json?q=${name.encodeURLParameter()}&limit=1", "Open Library authors", openLibrary) ?: return null
        val d = runCatching { json.parseToJsonElement(body).obj()?.get("docs").arr()?.firstOrNull().obj() }.getOrNull() ?: return 0
        return (d["work_count"] as? JsonPrimitive)?.intOrNull
    }

    suspend fun audnexus(book: Audiobook, asin: String?): AudiobookSuggestion {
        val region = configStore.current.audiobooks.audnexusRegion.ifBlank { "uk" }
        val id = asin?.trim()?.uppercase()?.takeIf { Regex("[A-Z0-9]{10}").matches(it) }
            ?: return AudiobookSuggestion("Audnexus", note = "It covers what Audible sells; paste an Audible ASIN to ask it.")
        val body = fetchText("https://api.audnex.us/books/$id?region=$region", "Audnexus")
            ?: return AudiobookSuggestion("Audnexus", note = "Audible's ${region.uppercase()} store has no book $id.")
        val o = runCatching { json.parseToJsonElement(body).obj() }.getOrNull() ?: return AudiobookSuggestion("Audnexus", note = "Audnexus answered with nothing it could read.")
        val authors = o["authors"].arr()?.mapNotNull { it.obj()?.get("name").s() }.orEmpty()
        val narrators = o["narrators"].arr()?.mapNotNull { it.obj()?.get("name").s() }.orEmpty()
        val series = o["seriesPrimary"].obj()
        return AudiobookSuggestion(
            "Audnexus", found = true, note = "Audible's ${region.uppercase()} store sells it.", title = o["title"].s(), author = authors.firstOrNull(),
            year = year(o["releaseDate"].s()), coverUrl = o["image"].s(),
            fields = buildMap {
                o["title"].s()?.let { put("title", it) }
                o["subtitle"].s()?.let { put("subtitle", it) }
                if (authors.isNotEmpty()) put("authors", authors.joinToString("; "))
                if (narrators.isNotEmpty()) put("narrators", narrators.joinToString("; "))
                series?.get("name").s()?.let { put("series", it) }
                series?.get("position").s()?.let { put("series_position", it) }
                year(o["releaseDate"].s())?.let { put("year", it.toString()) }
                o["publisherName"].s()?.let { put("publisher", it) }
                o["language"].s()?.let { put("language", it) }
                stripHtml(o["summary"].s())?.let { put("description", it) }
                o["genres"].arr()?.mapNotNull { it.obj()?.get("name").s() }?.takeIf { it.isNotEmpty() }?.let { put("genres", it.joinToString("; ")) }
            },
        )
    }

    /** MusicBrainz lists some audiobooks as release-groups; a card only when it hits (FR-281-3). */
    suspend fun musicBrainz(book: Audiobook): AudiobookSuggestion? {
        val client = mb ?: return null
        if (!configStore.current.musicbrainz.enabled) return null
        val q = "releasegroup:\"${MusicBrainzClient.lucene(book.title)}\"" + (book.authors.firstOrNull()?.let { " AND artist:\"${MusicBrainzClient.lucene(it)}\"" } ?: "") + " AND secondarytype:audiobook"
        val rg = client.searchReleaseGroupsFree(q, limit = 3)?.firstOrNull { sameTitle(it.title, book.title) } ?: return null
        return AudiobookSuggestion(
            "MusicBrainz", found = true, note = "It lists the recording.", title = rg.title, author = rg.artistCredit.firstOrNull()?.name,
            year = year(rg.firstReleaseDate),
            fields = buildMap {
                put("title", rg.title)
                rg.artistCredit.map { it.name }.takeIf { it.isNotEmpty() }?.let { put("authors", it.joinToString("; ")) }
                year(rg.firstReleaseDate)?.let { put("year", it.toString()) }
            },
        )
    }

    /** FR-281-7 — the cover candidates the providers offer, from the cards already asked. */
    fun coverCandidates(book: Audiobook): List<MusicArtCandidate> =
        book.suggestions.mapNotNull { s -> s.coverUrl?.let { MusicArtCandidate("front", it, it, s.provider) } }
}
