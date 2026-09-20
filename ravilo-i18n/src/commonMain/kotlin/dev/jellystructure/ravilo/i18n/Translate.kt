package dev.jellystructure.ravilo.i18n

/**
 * R279 — the whole of Ravilo's localisation, for every client: Android TV, the phone, `ravilo-web`,
 * the Chromecast receiver (`:ravilo-cast`) and the Tizen receiver (`:ravilo-screen`).
 *
 * The strings themselves are **not in this source tree**. They live in `i18n/<code>.json` at the
 * repo root and are compiled into [LOCALES] and [SUPPORTED_LANGUAGES] by this module's
 * `generateRaviloStrings` task. Adding a language is adding one file there; nothing in Kotlin knows
 * how many there are.
 *
 * This file stays Compose-free on purpose — `:ravilo-cast` and `:ravilo-screen` are plain
 * Kotlin/JS with no Compose runtime. The `@Composable str()` shorthand lives in `:ravilo-ui` beside
 * the `LocalLang` it reads.
 */

/** A language Ravilo's interface is available in, as declared by that file's `_meta` block. */
public data class RaviloLanguage(
    /** BCP-47-ish code, and the filename: `fo` ⇒ `i18n/fo.json`. */
    public val code: String,
    /** What the language calls itself — what a picker shows. `Føroyskt`, not `Faroese`. */
    public val name: String,
    /** The English name, for anywhere an operator rather than a viewer is reading. */
    public val englishName: String,
)

/** The language every other one falls back to, key by key, and the one that defines the key set. */
public const val BASE_LANGUAGE: String = "en"

/**
 * Looks [key] up in [lang], substituting `{name}` placeholders from [vars].
 *
 * Resolution is per key, not per table: an unfinished translation renders [BASE_LANGUAGE] for the
 * keys it is missing and its own text for the rest, so a language can ship half-done. A key present
 * in no table at all renders as itself — visible in the UI, which is what you want from a typo.
 */
public fun t(key: String, lang: String, vars: Map<String, String> = emptyMap()): String {
    val base = LOCALES.getValue(BASE_LANGUAGE)
    val table = LOCALES[lang] ?: base
    var result = table[key] ?: base[key] ?: key
    for ((name, value) in vars) result = result.replace("{$name}", value)
    return result
}

/** [t] for the common case of one numeric placeholder, `{n}` — the receivers' whole vocabulary. */
public fun t(key: String, lang: String, n: Int): String = t(key, lang, mapOf("n" to n.toString()))

/** The [RaviloLanguage] for [code], or null if no `i18n/<code>.json` declares it. */
public fun languageOrNull(code: String?): RaviloLanguage? =
    code?.let { c -> SUPPORTED_LANGUAGES.firstOrNull { it.code == c } }
