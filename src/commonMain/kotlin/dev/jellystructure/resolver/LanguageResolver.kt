package dev.jellystructure.resolver

object LanguageResolver {
    /**
     * Returns an ordered list of TMDB language codes to try for this file,
     * derived from its audio track language tags (in track-index order) with
     * the global fallback appended.  Duplicate entries are removed.
     *
     * The backend iterates the list until TMDB returns a non-empty result.
     * The WASM frontend uses the same function to render the live preview
     * without a round-trip.
     */
    fun priorityList(trackLanguages: List<String?>, fallbackLanguage: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (lang in trackLanguages) {
            if (!lang.isNullOrBlank()) seen.add(lang)
        }
        seen.add(fallbackLanguage.ifBlank { "en" })
        return seen.toList()
    }
}
