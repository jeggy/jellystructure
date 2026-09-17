package dev.jellystructure.model

/**
 * Phase 227 — the installation's ONE public address: how Jellystructure is reached from outside the
 * network. An ORIGIN, not a URL: `https://host[:port]`, no path, query or fragment, no trailing slash.
 * Every external URL (the Chromecast receiver at `/cast/`, its reachability probe, what Ravilo is told)
 * is derived from it in exactly one place — never from a request's `Host` header, which a reverse
 * proxy makes a lie.
 */
object PublicUrl {
    /** Trimmed, trailing slashes stripped. Normalisation only — says nothing about validity. */
    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** `null` when [raw] (after [normalize]) is a valid origin or blank (= unset, always allowed);
     *  otherwise the reason, as a sentence an admin can act on. Used on the Settings WRITE path only
     *  (FR-227-1): a stored value that fails this must never fail the config load. */
    fun problem(raw: String): String? {
        val v = normalize(raw)
        if (v.isEmpty()) return null
        if (!v.startsWith("https://", ignoreCase = true)) return "The public address must start with https://"
        val rest = v.substring("https://".length)
        if (rest.isEmpty()) return "The public address needs a host, e.g. https://media.example.org"
        if (rest.any { it == '/' || it == '?' || it == '#' }) return "Scheme and host only — no path, query or fragment"
        if (rest.any { it.isWhitespace() } || rest.contains('@')) return "That does not look like a host name"
        val host = rest.substringBeforeLast(':', rest).ifEmpty { rest }
        val port = if (rest.contains(':')) rest.substringAfterLast(':') else null
        if (port != null && (port.isEmpty() || port.any { !it.isDigit() } || port.toIntOrNull() !in 1..65535)) return "The port is not a number between 1 and 65535"
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return "That does not look like a host name"
        return null
    }

    /** What every consumer reads: the normalised origin, or `null` when unset OR invalid (FR-227-1: an
     *  invalid stored value is kept and shown, and treated as unset everywhere else). */
    fun effective(raw: String): String? = normalize(raw).takeIf { it.isNotEmpty() && problem(it) == null }

    /** FR-227-4 — THE derivation of the receiver address. */
    fun receiverUrl(raw: String): String? = effective(raw)?.let { "$it/cast/" }
}
