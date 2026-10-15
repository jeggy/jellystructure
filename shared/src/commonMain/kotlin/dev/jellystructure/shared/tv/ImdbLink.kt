package dev.jellystructure.shared.tv

/**
 * R387 (FR-R387-1/2) — an IMDb title id is `tt` followed by digits and nothing else. Checked by hand
 * rather than with a regex so a trailing newline can never pass, and so a value that carries a `/`, `?`,
 * `#` or whitespace can never become part of a link.
 */
fun validImdbTitleId(id: String?): String? =
    id?.takeIf { it.length > 2 && it.startsWith("tt") && it.substring(2).all { c -> c in '0'..'9' } }

/**
 * R387 (FR-R387-2) — the only place a Ravilo client writes an IMDb URL: the title's canonical page,
 * the same one the admin's title page links. Null unless [id] is a well-formed IMDb title id.
 */
fun imdbTitleUrl(id: String?): String? =
    validImdbTitleId(id)?.let { "https://www.imdb.com/title/$it/" }
