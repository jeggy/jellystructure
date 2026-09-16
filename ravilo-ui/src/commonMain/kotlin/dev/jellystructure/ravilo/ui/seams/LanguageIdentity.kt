package dev.jellystructure.ravilo.ui.seams

/**
 * R247 (FR-R247-1) — one canonical language identity for every string the player can produce.
 *
 * The picker sees three vocabularies for the same language: Jellyfin/ffprobe's ISO-639-2 (`eng`,
 * `dan`, `gre`), jellystructure's ISO-639-1 (`en`, `da`), and ExoPlayer's BCP-47 normalisation
 * (`hbs-srp`, `hbs-hrv`, `el`, deprecated `iw` rewritten to `he`). Before this phase every table
 * (names, endonyms, flags, R241's `sameLanguage`) was keyed by the raw string, so a language missing
 * from a table rendered as its own code (`EL`, `HBS-SRP`) and a remembered choice could not survive
 * a code change between files.
 *
 * Returns the canonical key — the ISO-639-1 code where one exists (`el`, `sr`, `de`), else the
 * ISO-639-2/T code (`cnr`, `arc`, `zxx`) — or the lower-cased, trimmed input when it recognises
 * nothing (never null for a non-blank input). **Region is not this function's business**: R195's
 * `resolveRegion` keeps reading the raw title, and a BCP-47 region/script subtag (`pt-br`,
 * `zh-hans`) is dropped here because the key is for identity only.
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

/** R247 (FR-R247-2) — the one language comparison the player uses: the resolver's tiers (R241), the
 *  Dubbed rule, `buildLanguageGroups`' grouping key and the R239 flag strip all go through here. Two
 *  nulls are the same (no language); a null and a code are not. */
fun sameLanguage(a: String?, b: String?): Boolean {
    val ca = canonicalLanguage(a)
    val cb = canonicalLanguage(b)
    return ca == cb
}

/** English display name (R46) for any code the canonicaliser recognises; null otherwise so callers
 *  can fall back to the raw code. */
fun languageName(code: String?): String? =
    canonicalLanguage(code)?.let { LANGUAGE_TABLE[it]?.english }

/** R180 (FR-RV-ASP1-1) — the language's own name for itself (`da → Dansk`), falling back to the
 *  English name for the few entries that have none (`und`, `zxx`, `cpe`); null when unrecognised. */
fun endonymOf(code: String?): String? =
    canonicalLanguage(code)?.let { LANGUAGE_TABLE[it] }?.let { it.endonym ?: it.english }

/** True when [code] resolves to an entry of the name table — i.e. FR-R247-4's "recognised". */
fun isKnownLanguage(code: String?): Boolean =
    canonicalLanguage(code)?.let { LANGUAGE_TABLE.containsKey(it) } == true

private val MACROLANGUAGE_PREFIXES = setOf("hbs", "sh")

internal data class LanguageEntry(val english: String, val endonym: String?)

/**
 * FR-R247-3 — one table, two columns, keyed by the canonical code. Covers every language code present
 * in the production library on 2026-09-16 (subtitle and audio `language` fields across `media.json`)
 * plus the ISO pairs the spec names. Add the alias to [ISO_ALIASES] and the name here; a test asserts
 * every alias target has a name so a code can never be "recognised" and still reach the screen as
 * a code.
 */
internal val LANGUAGE_TABLE: Map<String, LanguageEntry> = mapOf(
    "en" to LanguageEntry("English", "English"),
    "da" to LanguageEntry("Danish", "Dansk"),
    "fo" to LanguageEntry("Faroese", "Føroyskt"),
    "sv" to LanguageEntry("Swedish", "Svenska"),
    "no" to LanguageEntry("Norwegian", "Norsk"),
    "de" to LanguageEntry("German", "Deutsch"),
    "fr" to LanguageEntry("French", "Français"),
    "es" to LanguageEntry("Spanish", "Español"),
    "fi" to LanguageEntry("Finnish", "Suomi"),
    "nl" to LanguageEntry("Dutch", "Nederlands"),
    "zh" to LanguageEntry("Chinese", "中文"),
    "pt" to LanguageEntry("Portuguese", "Português"),
    "it" to LanguageEntry("Italian", "Italiano"),
    "pl" to LanguageEntry("Polish", "Polski"),
    "ru" to LanguageEntry("Russian", "Русский"),
    "ja" to LanguageEntry("Japanese", "日本語"),
    "ko" to LanguageEntry("Korean", "한국어"),
    "ar" to LanguageEntry("Arabic", "العربية"),
    "hi" to LanguageEntry("Hindi", "हिन्दी"),
    "cs" to LanguageEntry("Czech", "Čeština"),
    "tr" to LanguageEntry("Turkish", "Türkçe"),
    "is" to LanguageEntry("Icelandic", "Íslenska"),
    // — added by R247 (every one observed in the production DB scan, 2026-09-16) —
    "el" to LanguageEntry("Greek", "Ελληνικά"),
    "sr" to LanguageEntry("Serbian", "Srpski"), // Latin — the library's Serbian sidecars are Latin script (open question 1)
    "hr" to LanguageEntry("Croatian", "Hrvatski"),
    "bs" to LanguageEntry("Bosnian", "Bosanski"),
    "cnr" to LanguageEntry("Montenegrin", "Crnogorski"),
    "hu" to LanguageEntry("Hungarian", "Magyar"),
    "ro" to LanguageEntry("Romanian", "Română"),
    "sk" to LanguageEntry("Slovak", "Slovenčina"),
    "sl" to LanguageEntry("Slovenian", "Slovenščina"),
    "bg" to LanguageEntry("Bulgarian", "Български"),
    "uk" to LanguageEntry("Ukrainian", "Українська"),
    "he" to LanguageEntry("Hebrew", "עברית"),
    "th" to LanguageEntry("Thai", "ไทย"),
    "vi" to LanguageEntry("Vietnamese", "Tiếng Việt"),
    "id" to LanguageEntry("Indonesian", "Bahasa Indonesia"),
    "ms" to LanguageEntry("Malay", "Bahasa Melayu"),
    "lv" to LanguageEntry("Latvian", "Latviešu"),
    "lt" to LanguageEntry("Lithuanian", "Lietuvių"),
    "et" to LanguageEntry("Estonian", "Eesti"),
    "te" to LanguageEntry("Telugu", "తెలుగు"),
    "ta" to LanguageEntry("Tamil", "தமிழ்"),
    "mk" to LanguageEntry("Macedonian", "Македонски"),
    "tl" to LanguageEntry("Filipino", "Filipino"),
    "ca" to LanguageEntry("Catalan", "Català"),
    "gl" to LanguageEntry("Galician", "Galego"),
    "eu" to LanguageEntry("Basque", "Euskara"),
    "ml" to LanguageEntry("Malayalam", "മലയാളം"),
    "kn" to LanguageEntry("Kannada", "ಕನ್ನಡ"),
    "mn" to LanguageEntry("Mongolian", "Монгол"),
    "bn" to LanguageEntry("Bengali", "বাংলা"),
    "ur" to LanguageEntry("Urdu", "اردو"),
    "pa" to LanguageEntry("Punjabi", "ਪੰਜਾਬੀ"),
    "ne" to LanguageEntry("Nepali", "नेपाली"),
    "mr" to LanguageEntry("Marathi", "मराठी"),
    "gu" to LanguageEntry("Gujarati", "ગુજરાતી"),
    "si" to LanguageEntry("Sinhala", "සිංහල"),
    "sq" to LanguageEntry("Albanian", "Shqip"),
    "ty" to LanguageEntry("Tahitian", "Reo Tahiti"),
    "fa" to LanguageEntry("Persian", "فارسی"),
    "ky" to LanguageEntry("Kyrgyz", "Кыргызча"),
    "km" to LanguageEntry("Khmer", "ខ្មែរ"),
    "kk" to LanguageEntry("Kazakh", "Қазақша"),
    "ka" to LanguageEntry("Georgian", "ქართული"),
    "az" to LanguageEntry("Azerbaijani", "Azərbaycanca"),
    "hy" to LanguageEntry("Armenian", "Հայերեն"),
    "arc" to LanguageEntry("Aramaic", "ܐܪܡܝܐ"),
    "cpe" to LanguageEntry("English-based Creole", null),
    "zxx" to LanguageEntry("No language", null),
    "und" to LanguageEntry("Unknown", null),
    // — the ISO-639-2 B/T pairs the spec names that the library does not (yet) hold —
    "my" to LanguageEntry("Burmese", "မြန်မာ"),
    "cy" to LanguageEntry("Welsh", "Cymraeg"),
    "bo" to LanguageEntry("Tibetan", "བོད་སྐད་"),
    "mi" to LanguageEntry("Māori", "Te Reo Māori"),
    "yi" to LanguageEntry("Yiddish", "ייִדיש"),
    "la" to LanguageEntry("Latin", "Latina"),
    "af" to LanguageEntry("Afrikaans", "Afrikaans"),
    "sw" to LanguageEntry("Swahili", "Kiswahili"),
    "ga" to LanguageEntry("Irish", "Gaeilge"),
    "lb" to LanguageEntry("Luxembourgish", "Lëtzebuergesch"),
    "mt" to LanguageEntry("Maltese", "Malti"),
    "be" to LanguageEntry("Belarusian", "Беларуская"),
)

/**
 * ISO-639-2/B, ISO-639-2/T and the deprecated / macrolanguage forms ExoPlayer emits, each → the
 * canonical key of [LANGUAGE_TABLE]. A 639-1 code that is already canonical needs no row.
 */
internal val ISO_ALIASES: Map<String, String> = mapOf(
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
