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
fun canonicalLanguage(code: String?): String? = dev.jellystructure.shared.tv.canonicalLanguage(code)   // R291 — one implementation, in :shared

/** R247 (FR-R247-2) — the one language comparison the player uses: the resolver's tiers (R241), the
 *  Dubbed rule, `buildLanguageGroups`' grouping key and the R239 flag strip all go through here. Two
 *  nulls are the same (no language); a null and a code are not. */
fun sameLanguage(a: String?, b: String?): Boolean = dev.jellystructure.shared.tv.sameLanguage(a, b)   // R291 — one implementation, in :shared

/** English display name (R46) for any code the canonicaliser recognises; null otherwise so callers
 *  can fall back to the raw code. */
fun languageName(code: String?): String? = dev.jellystructure.shared.tv.languageName(code)   // DEB-4899 — the table is in :shared

/** R180 (FR-RV-ASP1-1) — the language's own name for itself (`da → Dansk`), falling back to the
 *  English name for the few entries that have none (`und`, `zxx`, `cpe`); null when unrecognised. */
fun endonymOf(code: String?): String? = dev.jellystructure.shared.tv.endonymOf(code)

/** True when [code] resolves to an entry of the name table — i.e. FR-R247-4's "recognised". */
fun isKnownLanguage(code: String?): Boolean = dev.jellystructure.shared.tv.isKnownLanguage(code)


internal typealias LanguageEntry = dev.jellystructure.shared.tv.LanguageEntry

/** FR-R247-3 — one table, two columns, keyed by the canonical code. DEB-4899 moved it to `:shared` (`LanguageNames.kt`) so the
 *  Samsung picker reads the same names. */
internal val LANGUAGE_TABLE: Map<String, LanguageEntry> get() = dev.jellystructure.shared.tv.LANGUAGE_TABLE

/**
 * ISO-639-2/B, ISO-639-2/T and the deprecated / macrolanguage forms ExoPlayer emits, each → the
 * canonical key of [LANGUAGE_TABLE]. A 639-1 code that is already canonical needs no row.
 */
internal val ISO_ALIASES: Map<String, String> get() = dev.jellystructure.shared.tv.ISO_ALIASES   // R291 — lives in :shared now
