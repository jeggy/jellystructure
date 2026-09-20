package dev.jellystructure.ravilo.i18n

/**
 * R279 — the one ladder every Ravilo client walks to decide which language to draw in.
 *
 * ```
 * the signed-in user's configured language   (RaviloConfig.uiLanguage)
 *   ↓ nobody is signed in, or it names a language we do not have
 * the language this device drew in last      (remembered locally, survives sign-out)
 *   ↓ this device has never drawn anything
 * English                                    (BASE_LANGUAGE)
 * ```
 *
 * It is pure on purpose. Every client persists the middle rung with whatever storage it already has
 * — SharedPreferences on Android, `localStorage` in a browser and on both receivers — but the rungs
 * themselves, which is where the bugs live, are decided here and unit-tested in one place.
 *
 * The rung that matters most is the middle one. Before it existed, every surface drawn *before* a
 * user is known — a login screen, a profile picker, a Chromecast sitting idle, a Tizen set that has
 * just been switched on — was English regardless of the household, which for a Faroese or Danish
 * house is most of what they see before they have done anything.
 */

/**
 * [code] reduced to a language this build actually has, or null.
 *
 * An exact match wins (`pt-BR` if `i18n/pt-BR.json` exists), then the base subtag (`pt-BR` → `pt`).
 * Case and separator are not significant: `FO_fo`, `fo-FO` and `fo` are one language. Null, blank
 * and anything we have no file for all answer null — so a junk code can never be *remembered*, only
 * fallen through.
 */
public fun normalizeLanguage(code: String?): String? {
    val raw = code?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
    SUPPORTED_LANGUAGES.firstOrNull { it.code.lowercase() == raw }?.let { return it.code }
    val base = raw.substringBefore('-').takeIf { it != raw } ?: return null
    return SUPPORTED_LANGUAGES.firstOrNull { it.code.lowercase() == base }?.code
}

/** True when this build has strings for [code] — see [normalizeLanguage] for what "has" means. */
public fun isSupportedLanguage(code: String?): Boolean = normalizeLanguage(code) != null

/**
 * Walks the ladder above and always answers a language this build has strings for.
 *
 * [configured] is the signed-in user's own `uiLanguage`; pass null when nobody is signed in.
 * [lastSession] is what this device last drew in. Both are normalised, so a caller may hand over
 * whatever it has — a stale config field, a `localStorage` string written by an older build, a
 * region-tagged code — without checking it first.
 */
public fun resolveLanguage(configured: String? = null, lastSession: String? = null): String =
    normalizeLanguage(configured) ?: normalizeLanguage(lastSession) ?: BASE_LANGUAGE
