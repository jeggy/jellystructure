package dev.jellystructure.ravilo.ui.i18n

import dev.jellystructure.ravilo.i18n.RaviloLanguage
import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t as translate

/**
 * R279 — the string table moved out of Kotlin entirely. It now lives in `i18n/<code>.json` at the
 * repo root and is compiled into `:ravilo-i18n` at build time, so the phone, the TV, `ravilo-web`,
 * the Chromecast receiver and the Tizen receiver all read one table and adding a language is adding
 * one file.
 *
 * What is left here is the forwarder the ~430 existing call sites already import. Nothing about
 * `t(key, lang, vars)` changed, so nothing about them had to.
 */

/** @see dev.jellystructure.ravilo.i18n.t */
fun t(key: String, lang: String, vars: Map<String, String> = emptyMap()): String = translate(key, lang, vars)

/** Every interface language `i18n/` declares — what the Settings picker lists (FR-R279-5). */
val uiLanguages: List<RaviloLanguage> get() = SUPPORTED_LANGUAGES
