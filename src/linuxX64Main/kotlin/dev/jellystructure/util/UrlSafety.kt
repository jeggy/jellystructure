package dev.jellystructure.util

/**
 * Security fix (2026-08-02 review, finding M5) — a lightweight SSRF guard for admin-facing free-form
 * URL inputs where the server fetches the URL and writes/re-serves the response (the artwork "pick
 * from URL" flow: [dev.jellystructure.media.ArtworkDownloader.saveAsset]/`saveSeasonPoster`/
 * `saveEpisodeStill`). Confirmed during the audit as a **full-read** SSRF, not blind — the fetched
 * bytes land on disk and are re-servable via the public image routes, so this isn't just
 * "the server makes a request on your behalf", it's "you can read back arbitrary internal HTTP
 * responses through the artwork pipeline".
 *
 * Deliberately NOT applied to Jellyfin, *arr, qBittorrent, or Seerr URLs (config test routes, `PUT
 * /api/config`): those are the admin configuring their OWN infrastructure, which is near-universally
 * on localhost or a private LAN by design — blocking private ranges there would break the app's core
 * setup flow for the overwhelming majority of self-hosted deployments, and an admin session already
 * has full config read/write regardless.
 *
 * This is a best-effort literal-host check (scheme + string/CIDR match on the URL's own host, not a
 * DNS resolve-then-check) — it blocks the realistic, common payloads (localhost, the cloud metadata
 * address, RFC1918 ranges) but not a DNS-rebinding attack where an attacker-controlled hostname
 * resolves to a private address only at request time. Kotlin/Native's outbound HTTP stack (Curl)
 * doesn't expose a "validate the connecting IP" hook here; closing that class fully would need a
 * custom resolver or a connect-time callback, which is a larger follow-up, not a same-session fix.
 */
object UrlSafety {
    fun isSafeExternalUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val hostPort = if ('@' in authority) authority.substringAfter('@') else authority
        // Bracketed IPv6 (e.g. "[::1]:8080") must have its brackets stripped BEFORE any port-splitting
        // — splitting on the last ':' first (as an earlier version of this did) cuts mid-address
        // instead, leaving the brackets in place and silently defeating the "::1" loopback check below.
        val host = if (hostPort.startsWith("[")) {
            hostPort.removePrefix("[").substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }.lowercase()
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        return !isPrivateOrReservedIp(host)
    }

    private fun isPrivateOrReservedIp(host: String): Boolean {
        // IPv4 literal check
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt(); val b = octets[1].toInt()
            return a == 127 ||                                  // loopback
                (a == 169 && b == 254) ||                        // link-local incl. cloud metadata (169.254.169.254)
                a == 10 ||                                       // RFC1918
                (a == 172 && b in 16..31) ||                     // RFC1918
                (a == 192 && b == 168) ||                        // RFC1918
                a == 0 ||                                        // "this" network
                a >= 224                                         // multicast/reserved
        }
        // IPv6 literal check (best-effort: loopback and unique-local/link-local prefixes)
        if (':' in host) {
            val h = host.lowercase()
            return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        }
        return false
    }
}
