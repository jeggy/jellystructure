package dev.jellystructure.shared.tv

/**
 * R247 (FR-R247-1), moved here by R291 (FR-R291-1, dev review item 1) — one canonical language identity
 * for every string the player AND the server can meet: Jellyfin/ffprobe's ISO-639-2 (`eng`, `dan`,
 * `gre`), jellystructure's ISO-639-1 (`en`, `da`), and ExoPlayer's BCP-47 normalisation (`hbs-srp`,
 * `hbs-hrv`, `el`, deprecated `iw` → `he`). The server resolves the viewer's remembered audio choice
 * against the file's tracks with THIS comparison, so the start and the picker cannot disagree.
 *
 * Returns the canonical key — the ISO-639-1 code where one exists (`el`, `sr`, `de`), else the
 * ISO-639-2/T code (`cnr`, `arc`, `zxx`) — or the lower-cased, trimmed input when it recognises
 * nothing (never null for a non-blank input). Region is not this function's business: a BCP-47
 * region/script subtag (`pt-br`, `zh-hans`) is dropped because the key is for identity only.
 */
fun canonicalLanguage(code: String?): String? {
    val raw = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = raw.split('-', '_').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return raw
    // ExoPlayer's macrolanguage form: `hbs-srp` / `hbs-hrv` / `hbs-bos` / `hbs-cnr` — the second
    // subtag is the individual language, the first is only Serbo-Croatian's umbrella.
    val base = if (parts[0] in MACROLANGUAGE_PREFIXES && parts.size > 1) parts[1] else parts[0]
    return ISO_ALIASES[base] ?: base
}

/** R247 (FR-R247-2) / R241 — the one language comparison. Two nulls are the same (no language); a null and a
 *  code are not. */
fun sameLanguage(a: String?, b: String?): Boolean {
    val ca = canonicalLanguage(a)
    val cb = canonicalLanguage(b)
    return ca == cb
}

private val MACROLANGUAGE_PREFIXES = setOf("hbs", "sh")

/**
 * ISO-639-2/B, ISO-639-2/T and the deprecated / macrolanguage forms ExoPlayer emits, each → the
 * canonical key. A 639-1 code that is already canonical needs no row. The display names stay in
 * `:ravilo-ui`'s `LANGUAGE_TABLE`, keyed by the same canonical code.
 */
val ISO_ALIASES: Map<String, String> = mapOf(
    "eng" to "en",
    "dan" to "da",
    "fao" to "fo",
    "swe" to "sv",
    "nor" to "no", "nob" to "no", "nb" to "no", "nno" to "no", "nn" to "no",
    "ger" to "de", "deu" to "de",
    "fre" to "fr", "fra" to "fr",
    "spa" to "es",
    "fin" to "fi",
    "dut" to "nl", "nld" to "nl",
    "chi" to "zh", "zho" to "zh",
    "por" to "pt",
    "ita" to "it",
    "pol" to "pl",
    "rus" to "ru",
    "jpn" to "ja",
    "kor" to "ko",
    "ara" to "ar",
    "hin" to "hi",
    "cze" to "cs", "ces" to "cs",
    "tur" to "tr",
    "ice" to "is", "isl" to "is",
    "gre" to "el", "ell" to "el",
    "srp" to "sr", "scc" to "sr",
    "hrv" to "hr", "scr" to "hr",
    "bos" to "bs",
    "hun" to "hu",
    "rum" to "ro", "ron" to "ro",
    "slo" to "sk", "slk" to "sk",
    "slv" to "sl",
    "bul" to "bg",
    "ukr" to "uk",
    "heb" to "he", "iw" to "he",
    "tha" to "th",
    "vie" to "vi",
    "ind" to "id", "in" to "id",
    "may" to "ms", "msa" to "ms",
    "lav" to "lv",
    "lit" to "lt",
    "est" to "et",
    "tel" to "te",
    "tam" to "ta",
    "mac" to "mk", "mkd" to "mk",
    "fil" to "tl", "tgl" to "tl",
    "cat" to "ca",
    "glg" to "gl",
    "baq" to "eu", "eus" to "eu",
    "mal" to "ml",
    "kan" to "kn",
    "mon" to "mn",
    "ben" to "bn",
    "urd" to "ur",
    "pan" to "pa",
    "nep" to "ne",
    "mar" to "mr",
    "guj" to "gu",
    "sin" to "si",
    "alb" to "sq", "sqi" to "sq",
    "tah" to "ty",
    "per" to "fa", "fas" to "fa",
    "kir" to "ky",
    "khm" to "km",
    "kaz" to "kk",
    "geo" to "ka", "kat" to "ka",
    "aze" to "az",
    "arm" to "hy", "hye" to "hy",
    "bur" to "my", "mya" to "my",
    "wel" to "cy", "cym" to "cy",
    "tib" to "bo", "bod" to "bo",
    "mao" to "mi", "mri" to "mi",
    "yid" to "yi", "ji" to "yi",
    "lat" to "la",
    "afr" to "af",
    "swa" to "sw",
    "gle" to "ga",
    "ltz" to "lb",
    "mlt" to "mt",
    "bel" to "be",
)
