package dev.jellystructure.model

/**
 * DEB-1854 (FR-4) — the installation's one optional page where an account whose password Jellyfin does not keep changes
 * it. Any absolute `https://` URL (a path and a query are normal for it), never defaulted: the identity provider is the
 * operator's, and this repository serves installations that are not this household's. Checked on a Settings SAVE only;
 * a stored value that fails never fails a load (Phase 227's rule), it is simply not sent to a client ([effective]).
 */
object PasswordChangeUrl {
    fun normalize(raw: String): String = raw.trim()

    /** `null` when [raw] is blank (= not set, always allowed) or an absolute https URL; else the reason, in a sentence. */
    fun problem(raw: String): String? {
        val v = normalize(raw)
        if (v.isEmpty()) return null
        if (!v.startsWith("https://", ignoreCase = true)) return "The password page must start with https://"
        val rest = v.substring("https://".length)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || host.startsWith('.') || host.startsWith(':')) return "The password page needs a host, e.g. https://account.example.org/password"
        if (v.any { it.isWhitespace() } || host.contains('@')) return "That does not look like a web address"
        return null
    }

    /** What a client receives: the value when it parses as `https://`, else `null` (not set). */
    fun effective(raw: String): String? = normalize(raw).takeIf { it.isNotEmpty() && problem(it) == null }
}
