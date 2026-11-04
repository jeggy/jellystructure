package dev.jellystructure.autofetch

import kotlinx.serialization.Serializable

/**
 * DEB-3613 (DEB-3567, DEB-2474 FR-3.6) — the admin's own pasted session for a site that plays only to a signed-in viewer.
 *
 * It is stored once per site as `cookies.txt` text and is **never answered by any route, logged, put in a run line, Activity or the bug report, or
 * sent to Anthropic**. Nothing here has a `toString` that shows a value: a [SessionCookie] prints its name and domain only.
 */
class SessionCookie(
    /** The domain without a leading dot; a `Cookie:` header paste has no domain of its own and is taken for the site. */
    val domain: String,
    val path: String,
    val secure: Boolean,
    /** Epoch seconds; null for a session cookie (no expiry given). */
    val expires: Long?,
    val name: String,
    val value: String,
) {
    /** Cookie matching by domain: the host is the cookie's domain or one of its subdomains. */
    fun matchesHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == domain || h.endsWith(".$domain")
    }

    fun matchesUrl(url: String): Boolean {
        val host = Urls.host(url) ?: return false
        if (!matchesHost(host)) return false
        if (secure && !url.startsWith("https://", true)) return false
        val p = Urls.path(url)
        return p == path || p.startsWith(if (path.endsWith("/")) path else "$path/") || path == "/"
    }

    fun live(nowSec: Long): Boolean = expires == null || expires > nowSec

    override fun toString(): String = "SessionCookie($name @ $domain)"
}

/** What `GET /api/autofetch` and `PUT …/session` say of a stored session: that it is set, when it was saved, and when its first cookie runs out. Never the value. */
@Serializable
data class SessionView(val set: Boolean, val savedAt: Long, val expiresAt: Long?)

sealed class SessionPaste {
    class Parsed(val cookies: List<SessionCookie>, val expiresAt: Long?) : SessionPaste()
    class Refused(val reason: String) : SessionPaste()
}

object SiteSessions {
    /** A cookies.txt of a browser's whole jar is a few hundred kilobytes at most; past this it is not a session. */
    const val MAX_PASTE = 400_000
    const val TOO_LARGE = "That is too large to be a session."
    const val UNREADABLE = "That is neither a cookies.txt export nor a Cookie header."
    fun noCookieFor(site: String) = "That holds no cookie for $site."

    /** FR-3.6 — the reason a stopped site carries when its session no longer works; a new paste resumes a site stopped for it. */
    fun expiredReason(site: String) = "The session for $site has run out. Paste a new one on AI ▸ Fetchers."
    fun isExpiredReason(site: String, reason: String?): Boolean = reason == expiredReason(site)

    /** Whether [domain] belongs to [site] (FR-2's lane key): the site itself, one of its subdomains, or a parent domain that covers it (`.example.fo` for `tv.example.fo`). A bare top-level name such as `com` never does. */
    fun belongsTo(site: String, domain: String): Boolean =
        domain == site || domain.endsWith(".$site") || (domain.contains('.') && site.endsWith(".$domain"))

    /**
     * Reads a paste: Netscape `cookies.txt` (seven tab-separated fields, `#HttpOnly_` lines included) or the value of a `Cookie:` header
     * (`a=b; c=d`, optionally with the `Cookie:` prefix). Only the cookies that belong to [site] are kept. A `Cookie:` header carries no domain, so
     * its cookies are taken for [site] itself.
     */
    fun parse(text: String, site: String): SessionPaste {
        val raw = text.replace("\r", "").trim()
        if (raw.isEmpty()) return SessionPaste.Refused(UNREADABLE)
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val netscape = lines.mapNotNull { netscapeLine(it) }
        val all: List<SessionCookie> = if (netscape.isNotEmpty()) netscape else headerCookies(raw, site)
        if (all.isEmpty()) return SessionPaste.Refused(UNREADABLE)
        val mine = all.filter { belongsTo(site, it.domain) }
        if (mine.isEmpty()) return SessionPaste.Refused(noCookieFor(site))
        return SessionPaste.Parsed(mine, mine.mapNotNull { it.expires }.minOrNull())
    }

    private fun netscapeLine(line0: String): SessionCookie? {
        val line = if (line0.startsWith("#HttpOnly_")) line0.removePrefix("#HttpOnly_") else line0
        if (line.startsWith("#")) return null
        val f = line.split('\t')
        if (f.size < 7) return null
        val domain = f[0].trim().trimStart('.').lowercase()
        if (domain.isEmpty() || !f[5].trim().isNotEmpty()) return null
        val exp = f[4].trim().toLongOrNull()
        return SessionCookie(domain, f[2].trim().ifEmpty { "/" }, f[3].trim().equals("TRUE", true), exp?.takeIf { it > 0 }, f[5].trim(), f.drop(6).joinToString("\t").trim())
    }

    private fun headerCookies(raw: String, site: String): List<SessionCookie> {
        val body = raw.lines().firstOrNull { it.trim().startsWith("cookie:", true) }?.substringAfter(':') ?: raw
        if (body.contains('\n')) return emptyList()
        return body.split(';').mapNotNull { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = p.substring(0, eq).trim()
            if (name.isEmpty() || name.any { it.isWhitespace() || it == '\t' }) return@mapNotNull null
            SessionCookie(site, "/", false, null, name, p.substring(eq + 1).trim())
        }
    }

    /** The cookies as a Netscape file (what yt-dlp reads): [cookies] only, one line each. */
    fun toNetscape(cookies: List<SessionCookie>): String = buildString {
        append("# Netscape HTTP Cookie File\n")
        for (c in cookies) {
            append('.').append(c.domain).append('\t').append("TRUE").append('\t').append(c.path).append('\t')
                .append(if (c.secure) "TRUE" else "FALSE").append('\t').append(c.expires ?: 0L).append('\t').append(c.name).append('\t').append(c.value).append('\n')
        }
    }

    /** Re-reads a stored file; it holds only this site's cookies, so nothing is filtered but a line that is not a cookie. */
    fun fromStored(text: String): List<SessionCookie> = text.lines().mapNotNull { netscapeLine(it.trim()) }
}

/** The stored sessions, one per site. Production binds [AutofetchStore]; a test binds memory. */
interface SessionStore {
    fun session(site: String): StoredSession?
    fun putSession(site: String, cookiesTxt: String, savedAt: Long, expiresAt: Long?)
    fun deleteSession(site: String)
}

/** The row, with the value. Only the engine's own request code ever holds one; [toString] shows no value. */
class StoredSession(val cookiesTxt: String, val savedAt: Long, val expiresAt: Long?) {
    fun view() = SessionView(true, savedAt, expiresAt)
    override fun toString(): String = "StoredSession(savedAt=$savedAt)"
}

/** What a lane view with a session does with it: the `Cookie:` header for one request, and the file yt-dlp reads. Null when there is nothing to send. */
class SessionJar(private val cookies: List<SessionCookie>, private val nowSec: () -> Long) {
    /** The header for [url] — only when [url]'s host is one of [allowedHosts] (FR-2.7) and a live cookie matches it. */
    fun headerFor(url: String, allowedHosts: Set<String>): String? {
        val host = Urls.host(url) ?: return null
        if (host !in allowedHosts) return null
        val now = nowSec()
        val pairs = cookies.filter { it.live(now) && it.matchesUrl(url) }
        return if (pairs.isEmpty()) null else pairs.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** ffmpeg's `-cookies` value: one `Set-Cookie`-style line per live cookie that matches a host in [allowedHosts], each scoped to its own domain. Null when none does. */
    fun ffmpegFor(allowedHosts: Set<String>): String? {
        val now = nowSec()
        val mine = cookies.filter { c -> c.live(now) && allowedHosts.any { c.matchesHost(it) } }
        if (mine.isEmpty()) return null
        return mine.joinToString("") { c -> "${c.name}=${c.value}; path=${c.path}; domain=.${c.domain}${if (c.secure) "; secure" else ""}\r\n" }
    }

    /** The Netscape file for a program that makes its own requests: the live cookies that match one of [allowedHosts], or null when none does. */
    fun fileFor(allowedHosts: Set<String>): String? {
        val now = nowSec()
        val mine = cookies.filter { c -> c.live(now) && allowedHosts.any { c.matchesHost(it) } }
        return if (mine.isEmpty()) null else SiteSessions.toNetscape(mine)
    }
}
