package dev.jellystructure.model

/**
 * DEB-2233 (FR-1) — the two optional addresses `ravilo_web_url` and `jellyfin_internal_url`: blank (= not set), or an
 * absolute `http(s)` URL with no query and no fragment (a path is allowed). Validated like [PasswordChangeUrl] — on a
 * Settings SAVE only, never on a load — but http is allowed too, because the internal address is typically
 * `http://jellyfin:8096` on a shared Docker network. The server stays the authority; the admin field only says why early.
 */
object ServiceAddress {
    /** Trimmed, and without a trailing slash so `<address>/tv` never doubles it. */
    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** `null` when [raw] is blank or acceptable; else the reason, in a sentence naming [what]. */
    fun problem(raw: String, what: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val rest = when {
            v.startsWith("https://", ignoreCase = true) -> v.substring("https://".length)
            v.startsWith("http://", ignoreCase = true) -> v.substring("http://".length)
            else -> return "The $what must start with http:// or https://"
        }
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || authority.startsWith('.') || authority.startsWith(':')) return "The $what needs a host, e.g. https://example.org"
        if (v.any { it.isWhitespace() } || authority.contains('@')) return "That does not look like a web address"
        if ('?' in v || '#' in v) return "The $what cannot have a query or a fragment"
        return null
    }

    /** What the server uses: the normalised value when it passes [problem], else `null` (not set). */
    fun effective(raw: String, what: String): String? = normalize(raw).takeIf { it.isNotEmpty() && problem(it, what) == null }
}
