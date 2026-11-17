package dev.jellystructure.shared.tv

/**
 * R247 (FR-R247-3), moved here by DEB-4899 — the language display names, keyed by [canonicalLanguage]'s key, so the Samsung
 * app's picker names a track by its language the way Android TV's does (R180 FR-RV-ASP1-1). `:ravilo-ui`'s
 * `LanguageIdentity.kt` keeps `languageName` / `endonymOf` / `isKnownLanguage` as thin readers of this one table.
 */
data class LanguageEntry(val english: String, val endonym: String?)

val LANGUAGE_TABLE: Map<String, LanguageEntry> = mapOf(
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

/** English display name (R46) for any code [canonicalLanguage] recognises; null otherwise so callers can fall back to the raw code. */
fun languageName(code: String?): String? = canonicalLanguage(code)?.let { LANGUAGE_TABLE[it]?.english }

/** R180 (FR-RV-ASP1-1) — the language's own name for itself (`da → Dansk`), falling back to the English name for the few
 *  entries that have none (`und`, `zxx`, `cpe`); null when unrecognised. */
fun endonymOf(code: String?): String? = canonicalLanguage(code)?.let { LANGUAGE_TABLE[it] }?.let { it.endonym ?: it.english }

/** True when [code] resolves to an entry of the name table — FR-R247-4's "recognised". */
fun isKnownLanguage(code: String?): Boolean = canonicalLanguage(code)?.let { LANGUAGE_TABLE.containsKey(it) } == true
