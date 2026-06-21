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

    // ISO 639-1 two-letter → ISO 639-2/T three-letter, covering every code the UI language picker
    // (LanguagePicker.LANGUAGES) can emit. Built fresh (NOT inverted from ISO2TO1, which is partial),
    // and the inverse fills the gaps in normalize() below. Prefer the /T (terminological) form where
    // B/T differ (e.g. de→deu not ger). Used at the file-tag write boundary (Phase 46).
    private val ISO1TO2 = mapOf(
        "af" to "afr", "ak" to "aka", "sq" to "sqi", "am" to "amh", "ar" to "ara", "hy" to "hye",
        "az" to "aze", "eu" to "eus", "be" to "bel", "bn" to "ben", "bs" to "bos", "br" to "bre",
        "bg" to "bul", "my" to "mya", "ca" to "cat", "zh" to "zho", "hr" to "hrv", "cs" to "ces",
        "da" to "dan", "nl" to "nld", "en" to "eng", "eo" to "epo", "et" to "est", "fo" to "fao",
        "fi" to "fin", "fr" to "fra", "ff" to "ful", "gl" to "glg", "ka" to "kat", "de" to "deu",
        "el" to "ell", "gn" to "grn", "gu" to "guj", "ht" to "hat", "ha" to "hau", "he" to "heb",
        "hi" to "hin", "hu" to "hun", "ia" to "ina", "id" to "ind", "ga" to "gle", "ig" to "ibo",
        "ik" to "ipk", "is" to "isl", "it" to "ita", "iu" to "iku", "ja" to "jpn", "jv" to "jav",
        "kl" to "kal", "kn" to "kan", "ks" to "kas", "kk" to "kaz", "km" to "khm", "ki" to "kik",
        "rw" to "kin", "ky" to "kir", "kv" to "kom", "kg" to "kon", "ko" to "kor", "ku" to "kur",
        "la" to "lat", "lb" to "ltz", "lg" to "lug", "ln" to "lin", "lo" to "lao", "lt" to "lit",
        "lu" to "lub", "lv" to "lav", "gv" to "glv", "mk" to "mkd", "mg" to "mlg", "ms" to "msa",
        "ml" to "mal", "mt" to "mlt", "mi" to "mri", "mr" to "mar", "mn" to "mon", "na" to "nau",
        "nv" to "nav", "nb" to "nob", "nd" to "nde", "ne" to "nep", "nn" to "nno", "no" to "nor",
        "oc" to "oci", "om" to "orm", "or" to "ori", "os" to "oss", "pa" to "pan", "fa" to "fas",
        "pl" to "pol", "ps" to "pus", "pt" to "por", "qu" to "que", "rm" to "roh", "rn" to "run",
        "ro" to "ron", "ru" to "rus", "sa" to "san", "sc" to "srd", "sd" to "snd", "se" to "sme",
        "sm" to "smo", "sg" to "sag", "sr" to "srp", "gd" to "gla", "sn" to "sna", "si" to "sin",
        "sk" to "slk", "sl" to "slv", "so" to "som", "st" to "sot", "es" to "spa", "su" to "sun",
        "sw" to "swa", "ss" to "ssw", "sv" to "swe", "ta" to "tam", "te" to "tel", "tg" to "tgk",
        "th" to "tha", "ti" to "tir", "bo" to "bod", "tk" to "tuk", "tl" to "tgl", "tn" to "tsn",
        "to" to "ton", "tr" to "tur", "ts" to "tso", "tt" to "tat", "tw" to "twi", "ty" to "tah",
        "ug" to "uig", "uk" to "ukr", "ur" to "urd", "uz" to "uzb", "ve" to "ven", "vi" to "vie",
        "vo" to "vol", "wa" to "wln", "cy" to "cym", "wo" to "wol", "fy" to "fry", "xh" to "xho",
        "yi" to "yid", "yo" to "yor", "za" to "zha", "zu" to "zul",
    )
    private val INV_ISO1TO2: Map<String, String> = ISO1TO2.entries.associate { (two, three) -> three to two }

    /**
     * Maps a raw ffprobe/user language code to the ISO 639-1 two-letter form TMDB understands.
     * Handles both ISO 639-2/B and /T three-letter forms (via ISO2TO1 and the inverse of ISO1TO2),
     * so the verify-after-write check (Phase 46) is B/T-agnostic: `fao` ≡ `fo`, `ger`/`deu` ≡ `de`.
     */
    fun normalize(code: String): String {
        val c = code.trim().lowercase()
        return ISO2TO1[c] ?: INV_ISO1TO2[c] ?: c
    }

    /**
     * Converts a language code to the ISO 639-2 three-letter form required at the file-tag boundary
     * (MP4 `mdhd`, Matroska `Language`). A code already in 3-letter form is returned as-is; a 2-letter
     * code is mapped via [ISO1TO2]. Returns null for any code with no known mapping — callers must
     * then fail rather than write a guess or `und` (Phase 46 §A2).
     */
    fun toIso6392(code: String): String? {
        val c = code.trim().lowercase()
        if (c.isBlank()) return null
        if (c.length == 3) return c
        return ISO1TO2[c]
    }

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
