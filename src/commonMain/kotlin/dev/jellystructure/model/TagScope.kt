package dev.jellystructure.model

/**
 * DEB-3701 (FR-1) — the scope of a jellystructure tag: *All* (an empty set), or any of the Library's five kind
 * keys. The keys are the ones `libraryKindSeg()` already uses; there is no new enumeration. The admin frontend and
 * the backend share this one object, so *does this tag apply to this group?* has one answer.
 */
object TagScope {
    /** The Library's kind keys, in the picker's order: Movies · TV · Music videos · Music · Audiobooks. */
    val KEYS: List<String> = listOf("MOVIE", "TV_SHOW", "MUSIC_VIDEO", "music", "audiobooks")

    /** The groups albums and songs (`music`) and books (`audiobooks`) belong to. */
    const val MUSIC = "music"
    const val AUDIOBOOKS = "audiobooks"

    private val LABELS = mapOf(
        "MOVIE" to "Movies", "TV_SHOW" to "TV", "MUSIC_VIDEO" to "Music videos", "music" to "Music", "audiobooks" to "Audiobooks",
    )

    fun label(key: String): String = LABELS[key] ?: key

    /** The scope key of a film, series or music video. */
    fun groupOf(kind: MediaKind): String = kind.name

    /** FR-1 — the one predicate: an empty scope is *All*, otherwise the group must be named. */
    fun applies(scopes: Collection<String>, group: String): Boolean = scopes.isEmpty() || group in scopes

    /** Keys in the picker's order with unknown keys dropped; `null` when [scopes] holds a key that is not one. */
    fun normalize(scopes: Collection<String>): List<String>? {
        if (scopes.any { it !in KEYS }) return null
        return KEYS.filter { it in scopes }
    }

    /** The card's line: *All*, or the groups joined by ` · `. */
    fun summary(scopes: Collection<String>): String =
        if (scopes.isEmpty()) "All" else KEYS.filter { it in scopes }.joinToString(" · ") { label(it) }

    /**
     * FR-2 — the modal's tick-chip rules. [key] is a group key, or `null` for the *All* chip. Ticking *All* clears the
     * groups; ticking a group adds it (which un-ticks *All*, since *All* is the empty set); unticking a group removes
     * it, and unticking the last one leaves the empty set, which is *All* again. A tag never has a scope of nothing.
     */
    fun toggle(current: Collection<String>, key: String?): List<String> {
        if (key == null) return emptyList()
        val next = if (key in current) current.filter { it != key } else current + key
        return KEYS.filter { it in next }
    }

    /** FR-3 — the refusal text, or `null` when the tag applies. */
    fun refusal(tag: String, scopes: Collection<String>, group: String): String? =
        if (applies(scopes, group)) null else "$tag is not used for ${label(group)}"

    /**
     * FR-3 — the first **newly added** tag of [next] that is out of scope, as a refusal text. A tag already in [stored]
     * is never refused, and neither is a removal. [scopesOf] returns a defined tag's scope, or `null` for a name that is
     * not a jellystructure tag (not this check's business).
     */
    fun firstRefusal(stored: List<String>, next: List<String>, group: String, scopesOf: (String) -> Collection<String>?): String? {
        val had = stored.toSet()
        for (tag in next) {
            if (tag in had) continue
            val scopes = scopesOf(tag) ?: continue
            refusal(tag, scopes, group)?.let { return it }
        }
        return null
    }

    /**
     * FR-4 — *3 films · 1 series*, from a count per kind. Zero counts are left out.
     */
    fun outsideSummary(byKind: Map<String, Int>): String = KEYS.mapNotNull { k ->
        val n = byKind[k] ?: return@mapNotNull null
        if (n <= 0) return@mapNotNull null
        val noun = when (k) {
            "MOVIE" -> if (n == 1) "film" else "films"
            "TV_SHOW" -> "series"
            "MUSIC_VIDEO" -> if (n == 1) "music video" else "music videos"
            "music" -> if (n == 1) "song or album" else "songs and albums"
            else -> if (n == 1) "book" else "books"
        }
        "$n $noun"
    }.joinToString(" · ")
}
