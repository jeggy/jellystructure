package dev.jellystructure.resolver

object LanguageResolver {
    // ffprobe tags tracks with ISO 639-2 three-letter codes; TMDB only accepts ISO 639-1 two-letter codes.
    // Map the common ones so TMDB queries actually hit the right translation.
    private val ISO2TO1 = mapOf(
        "afr" to "af", "alb" to "sq", "sqi" to "sq", "ara" to "ar",
        "baq" to "eu", "eus" to "eu", "bel" to "be", "bul" to "bg",
        "cat" to "ca", "chi" to "zh", "zho" to "zh", "hrv" to "hr",
        "cze" to "cs", "ces" to "cs", "dan" to "da", "dut" to "nl",
        "nld" to "nl", "eng" to "en", "est" to "et", "fao" to "fo",
        "fin" to "fi", "fre" to "fr", "fra" to "fr", "ger" to "de",
        "deu" to "de", "gle" to "ga", "glg" to "gl", "gre" to "el",
        "ell" to "el", "heb" to "he", "hin" to "hi", "hun" to "hu",
        "ice" to "is", "isl" to "is", "ind" to "id", "ita" to "it",
        "jpn" to "ja", "kor" to "ko", "lav" to "lv", "lit" to "lt",
        "mac" to "mk", "mkd" to "mk", "may" to "ms", "msa" to "ms",
        "mlt" to "mt", "nor" to "no", "nob" to "nb", "pol" to "pl",
        "por" to "pt", "rum" to "ro", "ron" to "ro", "rus" to "ru",
        "srp" to "sr", "slo" to "sk", "slk" to "sk", "slv" to "sl",
        "spa" to "es", "swe" to "sv", "tha" to "th", "tur" to "tr",
        "ukr" to "uk", "vie" to "vi", "wel" to "cy", "cym" to "cy",
    )

    /** Maps a raw ffprobe/user language code to the ISO 639-1 two-letter form TMDB understands. */
    fun normalize(code: String): String = ISO2TO1[code.lowercase()] ?: code

    /**
     * Returns an ordered list of TMDB language codes to try for this file,
     * derived from its audio track language tags (in track-index order) with
     * the global fallback appended.  Duplicate entries are removed.
     * Three-letter ISO 639-2 codes are normalized to ISO 639-1 automatically.
     *
     * The backend iterates the list until TMDB returns a non-empty result.
     * The WASM frontend uses the same function to render the live preview
     * without a round-trip.
     */
    fun priorityList(trackLanguages: List<String?>, fallbackLanguage: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (lang in trackLanguages) {
            if (!lang.isNullOrBlank()) seen.add(normalize(lang))
        }
        seen.add(normalize(fallbackLanguage.ifBlank { "en" }))
        return seen.toList()
    }
}
