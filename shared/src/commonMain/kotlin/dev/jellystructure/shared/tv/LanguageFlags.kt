package dev.jellystructure.shared.tv

/**
 * DEB-4889 (FR-19) — the language → flag table and Android TV's flag-strip counting rule (R75, R134, R239, R247), moved here
 * from `:ravilo-ui`'s `AudioFlagStrip.kt` with no change in behaviour, so the Samsung app draws the same flags the same way.
 * The values are the flag file's country code (`gb`, `fo`, …): `:ravilo-ui` maps one to its `flag_<cc>` drawable and the
 * Samsung widget packs the same PNGs as `flags/<cc>.png`.
 */

// ISO 639-1 (2-letter) AND ISO 639-2/B + /T (3-letter) → flag country code.
// ffprobe and Jellyfin MediaStreams use 3-letter codes; include both so lookup never silently fails.
val LANG_FLAG_CC: Map<String, String> = mapOf(
    "en" to "gb", "eng" to "gb",
    "fr" to "fr", "fra" to "fr", "fre" to "fr",
    "de" to "de", "deu" to "de", "ger" to "de",
    "es" to "es", "spa" to "es",
    "da" to "dk", "dan" to "dk",
    "fo" to "fo", "fao" to "fo",
    "is" to "is", "isl" to "is", "ice" to "is",
    "no" to "no", "nor" to "no", "nob" to "no", "nno" to "no",
    "sv" to "se", "swe" to "se",
    "fi" to "fi", "fin" to "fi",
    "nl" to "nl", "nld" to "nl", "dut" to "nl",
    "it" to "it", "ita" to "it",
    "pt" to "pt", "por" to "pt",
    "pl" to "pl", "pol" to "pl",
    "ru" to "ru", "rus" to "ru",
    "ja" to "jp", "jpn" to "jp",
    "ko" to "kr", "kor" to "kr",
    "zh" to "cn", "zho" to "cn", "chi" to "cn",
    "ar" to "sa", "ara" to "sa",
    "hi" to "in", "hin" to "in",
    "cs" to "cz", "ces" to "cz", "cze" to "cz",
    "tr" to "tr", "tur" to "tr",
    "uk" to "ua", "ukr" to "ua",
    "el" to "gr", "ell" to "gr", "gre" to "gr",
    "hu" to "hu", "hun" to "hu",
    "ro" to "ro", "ron" to "ro", "rum" to "ro",
    "sk" to "sk", "slk" to "sk", "slo" to "sk",
    "hr" to "hr", "hrv" to "hr",
    "he" to "il", "heb" to "il",
    "th" to "th", "tha" to "th",
    "vi" to "vn", "vie" to "vn",
    // Phase R239 (FR-R239-3) — added 2026-09-12: flags now exist for these; Tamil/Telugu are
    // deliberately still absent — no ISO-3166 country flag fits them, and India already maps to
    // Hindi's flag_in, so reusing it for Tamil/Telugu would collapse three DISTINCT languages onto one
    // flag_in drawable, undercounting them via this file's own `.distinct()` dedup — exactly the
    // FR-R239-1 miscount this phase exists to prevent. They stay in the honest "+N" unmapped count.
    //
    // CLOSED 2026-09-13, permanently — do not revisit without a product decision overriding this.
    // Candidate images were reviewed and rejected: no legitimate single flag exists (Tamil
    // Nadu/Andhra Pradesh/Telangana have no official state flags), and one submitted candidate for
    // Tamil was the flag of a proscribed militant organization — entirely unsuitable regardless of the
    // undercount problem above. "No flag" is the final, intended rendering for these two languages.
    "sr" to "rs", "srp" to "rs",
    "bg" to "bg", "bul" to "bg",
    "id" to "id", "ind" to "id",
    "ms" to "my", "msa" to "my", "may" to "my",
    "sl" to "si", "slv" to "si",
    "et" to "ee", "est" to "ee",
    "lv" to "lv", "lav" to "lv",
    "lt" to "lt", "lit" to "lt",
    "tl" to "ph", "fil" to "ph",
    // R239 amendment (2026-09-13) — Catalonia's flag (Senyera) is a single, uncontested regional flag
    // unlike Tamil/Telugu's situation above, so Catalan gets its own dedicated drawable (flag_ct), not
    // a borrowed one.
    "ca" to "ct", "cat" to "ct",
)

/** R247 (FR-R247-5) — every flag lookup goes through [canonicalLanguage] first, so `hbs-srp` finds
 *  `rs` and `gre`/`ell`/`el` all find the same flag; the raw lower-cased key is the fallback
 *  for a code the canonicaliser does not know but the map happens to. */
fun flagCountryFor(code: String?): String? {
    if (code.isNullOrBlank()) return null
    return canonicalLanguage(code)?.let { LANG_FLAG_CC[it] } ?: LANG_FLAG_CC[code.trim().lowercase()]
}

const val FLAG_STRIP_MAX = 5

/** The result of counting one group's languages: the flags to draw (country codes) and the honest `+N`. */
data class FlagStripCounts(
    val shown: List<String>,
    val extra: Int,
) {
    /** True whenever the group has ANY language at all, mapped or not (FR-R239-2). */
    val hasAnyLanguage: Boolean get() = shown.isNotEmpty() || extra > 0
}

/** Phase R239 (FR-R239-1) — counts every language the group has, not every flag it can draw. */
fun countFlagStrip(languages: List<String>, maxFlags: Int = FLAG_STRIP_MAX): FlagStripCounts {
    // R247 (FR-R247-2) — identity is the canonical code, so `da`+`dan` or `sr`+`hbs-srp` are one
    // language whether or not a flag exists for it.
    val keys = languages.map { canonicalLanguage(it) ?: it.lowercase() }
    // Deduplicate mapped languages by FLAG (same language, different alias, e.g. nor/nob/nno →
    // no, should show once); an unmapped language is deduplicated by its canonical code.
    // Undercounting true duplicates there is the safe failure mode; over-counting (claiming fewer
    // languages than exist) is the one FR-R239-1 forbids.
    val mappedFlags = keys.mapNotNull { flagCountryFor(it) }.distinct()
    val unmappedCount = keys.filter { flagCountryFor(it) == null }.distinct().size
    val totalDistinct = mappedFlags.size + unmappedCount
    val shown = mappedFlags.take(maxFlags.coerceIn(0, FLAG_STRIP_MAX))
    return FlagStripCounts(shown, totalDistinct - shown.size)
}
