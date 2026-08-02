package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * R189 milestone 2 — "My List". No backend endpoint for this exists anywhere in jellystructure (grepped
 * the shared DTOs and TV routes — there's no watchlist/favorite concept server-side to call into), so
 * this is a client-local list, scoped per profile (keyed by the active session's userId) so switching
 * profiles doesn't leak one person's list into another's. Same hand-rolled localStorage-JSON pattern as
 * `TokenStore`, for the same reason (avoids pulling kotlinx.serialization's JSON machinery into a tiny
 * local blob that's already trivial to encode by hand).
 */
object WatchlistStore {
    private fun key(userId: String) = "ravilo_watchlist_$userId"

    private fun activeUserId(): String? = MultiTokenStore.getActive()?.userId

    fun getAll(): List<MediaCard> {
        val uid = activeUserId() ?: return emptyList()
        val raw = localStorage[key(uid)] ?: return emptyList()
        return runCatching { parseAll(raw) }.getOrDefault(emptyList())
    }

    fun contains(id: String): Boolean = getAll().any { it.id == id }

    fun toggle(card: MediaCard) {
        if (contains(card.id)) remove(card.id) else add(card)
    }

    fun add(card: MediaCard) {
        val uid = activeUserId() ?: return
        val list = getAll().filter { it.id != card.id } + card
        localStorage[key(uid)] = encodeAll(list)
    }

    fun remove(id: String) {
        val uid = activeUserId() ?: return
        localStorage[key(uid)] = encodeAll(getAll().filter { it.id != id })
    }

    private fun parseAll(raw: String): List<MediaCard> =
        raw.removePrefix("[").removeSuffix("]")
            .split("}|{")
            .filter { it.isNotBlank() }
            .mapNotNull { chunk ->
                val s = chunk.trim().removePrefix("{").removeSuffix("}")
                val map = s.split("~~").associate { kv ->
                    val (k, v) = kv.split(":", limit = 2)
                    k to v
                }
                val id = map["id"] ?: return@mapNotNull null
                MediaCard(
                    id = id,
                    kind = if (map["kind"] == "SERIES") MediaKind.SERIES else MediaKind.MOVIE,
                    title = map["title"] ?: "",
                    year = map["year"]?.toIntOrNull(),
                    genre = map["genre"]?.takeIf { it.isNotBlank() },
                    rating = null,
                    posterUrl = map["poster"]?.takeIf { it.isNotBlank() },
                    backdropUrl = map["backdrop"]?.takeIf { it.isNotBlank() },
                )
            }

    private fun encodeAll(list: List<MediaCard>): String =
        list.joinToString("|", "[", "]") { c ->
            // '~~' / '|' are the field/record separators -- fine for this app's own title/genre text in
            // practice; not a general-purpose encoder (see TokenStore's identical, deliberate tradeoff).
            "{id:${c.id}~~kind:${c.kind}~~title:${c.title}~~year:${c.year ?: ""}~~genre:${c.genre ?: ""}~~poster:${c.posterUrl ?: ""}~~backdrop:${c.backdropUrl ?: ""}}"
        }
}
