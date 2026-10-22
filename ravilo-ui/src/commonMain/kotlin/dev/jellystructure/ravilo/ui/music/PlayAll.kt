package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicSelection
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * R388 (DEB-1300) — *Play all* / *Shuffle all* on a music list. One split control, the route resolves the selection
 * (`GET /tv/music/selection`), and the queue takes what comes back. This file is the part with no pixels: what each
 * surface asks for, what the queue is told it came from, and the press itself — so the unit tests hold all of it.
 */

/** What one surface sends the selection route (FR-R388-2); the amendment of 2026-10-22 holds the table. */
data class PlayAllRequest(
    val scope: String,
    val sort: String? = null,
    val genre: String? = null,
    val query: String? = null,
) {
    /** FR-R388-7 — *Playing from …*. [libraryLabel] is *Your library*; [searchLabel] is *Search “{q}”*. */
    fun context(libraryLabel: String, searchLabel: String): MusicContext = when (scope) {
        "genre" -> MusicContext("genre", genre.orEmpty())
        "search" -> MusicContext("search", searchLabel)
        else -> MusicContext("library", libraryLabel)
    }
}

/** Listen's request: everything, in Recently added order (FR-R388-3). */
val LISTEN_PLAY_ALL = PlayAllRequest("library")

/**
 * The request for the Browse page as it stands, or null where the page has no pair (Genres' own list, Playlists,
 * Audiobooks). A query makes it a search whatever chip is behind it; the chip's sort goes on the wire as the browse
 * route takes it (`added` is the route's default); a genre has no sort pill, so it sends none.
 */
fun browsePlayAllRequest(chip: String, sorts: Map<String, String>, genre: String?, query: String): PlayAllRequest? = when {
    query.isNotBlank() -> PlayAllRequest("search", query = query)
    chip == "albums" && genre != null -> PlayAllRequest("genre", genre = genre)
    chip == "albums" -> PlayAllRequest("albums", sort = sorts["albums"] ?: "added")
    chip == "artists" -> PlayAllRequest("artists", sort = sorts["artists"] ?: "title")
    chip == "songs" -> PlayAllRequest("tracks", sort = sorts["songs"] ?: "added")
    else -> null
}

/** Which half the viewer pressed — FR-R388-8's busy ring replaces that half's glyph. */
enum class PlayAllHalf { PLAY, SHUFFLE }

/** The capped toast's numbers in the locale's grouping (FR-R388-6): `1,000` in English, `1.000` in Danish and Faroese. */
fun groupedCount(n: Int, lang: String): String {
    val sep = if (lang == "en") "," else "."
    val digits = n.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(sep).reversed()
    return if (n < 0) "-$grouped" else grouped
}

/**
 * The press (FR-R388-5, -6, -8). One request in flight at a time: a press while [busy] is ignored. *Shuffle all* starts
 * on a random song and turns the queue's shuffle on; *Play all* starts at the first with it off. A failure, an empty
 * answer and no connection are one outcome — [onFailure] — and the queue is left as it was.
 */
class PlayAll(
    private val fetch: suspend (PlayAllRequest, Boolean) -> MusicSelection,
    private val play: (List<MusicTrackItem>, Int, MusicContext, Boolean) -> Unit = { tracks, index, context, shuffle -> MusicPlayback.playQueue(tracks, index, context, shuffle) },
    private val pick: (Int) -> Int = { size -> (0 until size).random() },
) {
    constructor(api: TvApiClient) : this({ request, shuffle -> api.musicSelection(request.scope, request.sort, request.genre, request.query, shuffle) })

    private val _busy = MutableStateFlow<PlayAllHalf?>(null)
    val busy: StateFlow<PlayAllHalf?> = _busy

    /** True when the press was taken; false when one was already in flight. */
    suspend fun run(
        request: PlayAllRequest,
        half: PlayAllHalf,
        context: MusicContext,
        onFailure: () -> Unit,
        onCapped: (shown: Int, total: Int) -> Unit,
    ): Boolean {
        if (!_busy.compareAndSet(null, half)) return false
        try {
            val shuffle = half == PlayAllHalf.SHUFFLE
            val answer = try { fetch(request, shuffle) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (answer == null || answer.tracks.isEmpty()) { onFailure(); return true }
            play(answer.tracks, if (shuffle) pick(answer.tracks.size) else 0, context, shuffle)
            if (answer.capped) onCapped(answer.tracks.size, answer.total)
        } finally {
            _busy.value = null
        }
        return true
    }
}
