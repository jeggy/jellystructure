package dev.jellystructure.autofetch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * DEB-2476 (DEB-2474, FR-2) — one lane per site. Every fetcher pointed at a site shares it: page and listing requests are
 * paced (10 s, or the site's `Crawl-delay`), downloads run one at a time with 60 s between two episodes, `robots.txt` is
 * read for a general crawler, a `429`/`503` slows the lane, and a real block **stops** it until the admin presses *Resume
 * site*. The pacing numbers are constants, not settings (FR-13): a household that wants to fetch faster than a site
 * allows is not a case to support.
 */
object LaneRules {
    const val PAGE_INTERVAL_SEC = 10L
    const val EPISODE_GAP_SEC = 60L
    const val ROBOTS_TTL_SEC = 24 * 3600L
    const val MAX_PER_RUN = 20
    const val RUN_FLOOR_SEC = 6 * 3600L
    val BACKOFF_SEC = longArrayOf(3600L, 4 * 3600L, 24 * 3600L)
    const val STOP_AFTER_429S_PER_DAY = 3
    const val MAX_BODY_BYTES = 5_000_000
}

/** Time for the lane, so a test advances it instead of waiting. */
interface LaneClock {
    fun nowSec(): Long
    /** A monotonic reading in milliseconds. */
    fun monoMs(): Long
    suspend fun sleepMs(ms: Long)
}

object SystemLaneClock : LaneClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nowSec(): Long = dev.jellystructure.nowEpochSec()
    override fun monoMs(): Long = origin.elapsedNow().inWholeMilliseconds
    override suspend fun sleepMs(ms: Long) = delay(ms)
}

/** A site's persistent state (the `autofetch_site` row). */
data class SiteState(
    val site: String,
    val stoppedReason: String? = null,
    val stoppedAt: Long? = null,
    val robotsText: String? = null,
    val robotsFetchedAt: Long? = null,
    val backoffUntil: Long? = null,
    val backoffLevel: Int = 0,
    val rateLimitedDay: String? = null,
    val rateLimitedCount: Int = 0,
    /** True once any request to this site has been answered `2xx` (FR-2.5: a `401`/`403` on a URL that worked). */
    val everOk: Boolean = false,
) {
    val stopped: Boolean get() = stoppedReason != null
}

interface SiteStore {
    fun get(site: String): SiteState
    fun put(state: SiteState)
}

/** A reply the lane can judge. [body] is text; a binary answer is never read through the lane. */
data class HttpReply(val status: Int, val headers: Map<String, String>, val body: String)

fun interface Transport {
    suspend fun get(url: String): HttpReply
}

/** What a page request is expected to answer: a challenge page is an HTML answer where something else was expected. */
enum class Expect { HTML, JSON, MANIFEST, ANY }

sealed class LaneOutcome {
    data class Ok(val reply: HttpReply) : LaneOutcome()
    /** The site is stopped (FR-2.5): nothing was requested. */
    data class Stopped(val reason: String) : LaneOutcome()
    /** A `429`/`503` slowed the lane (FR-2.4): nothing more until [untilSec]. */
    data class BackOff(val untilSec: Long, val reason: String) : LaneOutcome()
    /** `robots.txt` forbids the path: never requested (FR-2.2). */
    data class Forbidden(val reason: String) : LaneOutcome()
    /** Any other failure: the request was made and refused or did not answer. The lane carries on. */
    data class Failed(val reason: String, val status: Int? = null) : LaneOutcome()
}

/** `robots.txt` for a general crawler (FR-2.2): the `User-agent: *` group only. A rule naming only AI agents does not bind. */
class Robots private constructor(private val rules: List<Pair<Boolean, String>>, val crawlDelaySec: Long?) {
    fun allows(path: String): Boolean {
        var best: Pair<Boolean, String>? = null
        for (r in rules) {
            if (r.second.isEmpty()) continue
            if (matches(r.second, path) && (best == null || r.second.length > best.second.length ||
                    (r.second.length == best.second.length && r.first))) best = r
        }
        return best?.first ?: true
    }

    private fun matches(pattern: String, path: String): Boolean {
        val anchored = pattern.endsWith("$")
        val p = if (anchored) pattern.dropLast(1) else pattern
        val parts = p.split('*')
        if (!path.startsWith(parts[0])) return false
        var pos = parts[0].length
        for (i in 1 until parts.size) {
            val part = parts[i]
            if (i == parts.size - 1 && anchored) return part.isEmpty() || (path.length - part.length >= pos && path.endsWith(part))
            val at = path.indexOf(part, pos)
            if (at < 0) return false
            pos = at + part.length
        }
        return !anchored || pos == path.length
    }

    companion object {
        val ALLOW_ALL = Robots(emptyList(), null)

        fun parse(text: String): Robots {
            var inStar = false
            var seenAgentLine = false
            val rules = ArrayList<Pair<Boolean, String>>()
            var delay: Long? = null
            var groupHasStar = false
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val k = line.substringBefore(':', "").trim().lowercase()
                val v = line.substringAfter(':', "").trim()
                when (k) {
                    "user-agent" -> {
                        // A new group starts after a rule line; consecutive agent lines share one group.
                        if (!seenAgentLine) { inStar = false; groupHasStar = false }
                        seenAgentLine = true
                        if (v == "*") { inStar = true; groupHasStar = true }
                    }
                    "allow", "disallow" -> { seenAgentLine = false; if (inStar) rules += (k == "allow") to v }
                    "crawl-delay" -> { seenAgentLine = false; if (inStar) delay = v.toDoubleOrNull()?.let { kotlin.math.ceil(it).toLong() } }
                    else -> seenAgentLine = false
                }
            }
            return Robots(rules, delay)
        }
    }
}

class SiteLane(
    val site: String,
    private val store: SiteStore,
    private val transport: Transport,
    private val clock: LaneClock = SystemLaneClock,
) {
    private val pageGate = Mutex()
    private val downloadGate = Mutex()
    private var lastPageAtMs: Long? = null
    private var lastDownloadEndMs: Long? = null

    fun state(): SiteState = store.get(site)

    /** A stop is never retried automatically (FR-2.5); the admin's *Resume site* clears it, and the next request is the one probe. */
    fun resume() {
        store.put(store.get(site).copy(stoppedReason = null, stoppedAt = null, backoffUntil = null, backoffLevel = 0))
    }

    fun stop(reason: String) {
        val s = store.get(site)
        if (!s.stopped) store.put(s.copy(stoppedReason = reason, stoppedAt = clock.nowSec()))
    }

    /**
     * One page, listing or API request, in the lane. [listing] marks the recipe's listing (a `robots.txt` that newly forbids
     * it stops the site, FR-2.5); [expect] says what a good answer looks like.
     */
    suspend fun page(url: String, expect: Expect, listing: Boolean = false, player: Boolean = false): LaneOutcome {
        preflight()?.let { return it }
        // A manifest is a player's traffic and is not checked against robots.txt (FR-2.2), though it is still paced.
        if (player) return paced(Robots.ALLOW_ALL) { requestAndJudge(url, expect) }
        val path = Urls.path(url)
        val robots = robots(Urls.origin(url)) ?: return LaneOutcome.Failed("robots.txt could not be read, so nothing was requested")
        if (!robots.allows(path)) {
            val s = store.get(site)
            if (listing && s.everOk) {
                val reason = "robots.txt now forbids the listing ($path)"
                stop(reason)
                return LaneOutcome.Stopped(reason)
            }
            return LaneOutcome.Forbidden("this site's robots.txt does not allow $path")
        }
        return paced(robots) { requestAndJudge(url, expect) }
    }

    private fun preflight(): LaneOutcome? {
        val s = store.get(site)
        if (s.stopped) return LaneOutcome.Stopped(s.stoppedReason!!)
        val until = s.backoffUntil
        if (until != null && clock.nowSec() < until) return LaneOutcome.BackOff(until, "the site asked us to slow down")
        return null
    }

    private suspend fun <T> paced(robots: Robots, block: suspend () -> T): T = pageGate.withLock {
        val gap = maxOf(LaneRules.PAGE_INTERVAL_SEC, robots.crawlDelaySec ?: 0L) * 1000
        lastPageAtMs?.let { last ->
            val wait = last + gap - clock.monoMs()
            if (wait > 0) clock.sleepMs(wait)
        }
        try { block() } finally { lastPageAtMs = clock.monoMs() }
    }

    /** `robots.txt`, read once a day and cached. Null when it can be neither read nor taken from the cache. */
    private suspend fun robots(origin: String): Robots? {
        val s = store.get(site)
        val fresh = s.robotsFetchedAt?.let { clock.nowSec() - it < LaneRules.ROBOTS_TTL_SEC } == true
        if (fresh && s.robotsText != null) return Robots.parse(s.robotsText)
        val reply = pageGate.withLock {
            val gap = LaneRules.PAGE_INTERVAL_SEC * 1000
            lastPageAtMs?.let { last -> val wait = last + gap - clock.monoMs(); if (wait > 0) clock.sleepMs(wait) }
            try { runCatching { transport.get("$origin/robots.txt") }.getOrNull() } finally { lastPageAtMs = clock.monoMs() }
        }
        return when {
            reply == null -> s.robotsText?.let { Robots.parse(it) }
            reply.status in 200..299 -> {
                store.put(store.get(site).copy(robotsText = reply.body.take(500_000), robotsFetchedAt = clock.nowSec()))
                Robots.parse(reply.body)
            }
            reply.status in 400..499 -> {
                store.put(store.get(site).copy(robotsText = "", robotsFetchedAt = clock.nowSec()))
                Robots.ALLOW_ALL
            }
            else -> s.robotsText?.let { Robots.parse(it) }
        }
    }

    private suspend fun requestAndJudge(url: String, expect: Expect): LaneOutcome {
        val reply = try { transport.get(url) } catch (e: Throwable) {
            // Phase 310 — only the caller's own cancellation ends the request; a foreign one is a failed call like any other.
            if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw e
            return LaneOutcome.Failed("the request failed: ${e.message ?: e::class.simpleName}")
        }
        return judge(reply, expect)
    }

    /** FR-2.4/2.5 — what an answer means for the lane. */
    internal fun judge(reply: HttpReply, expect: Expect): LaneOutcome {
        val s = store.get(site)
        val st = reply.status
        if (st == 429 || st == 503) return slow(s, reply)
        if (st == 401 || st == 403) {
            if (s.everOk) {
                val reason = "the site answered $st on a page that worked before"
                stop(reason)
                return LaneOutcome.Stopped(reason)
            }
            return LaneOutcome.Failed("the site answered $st", st)
        }
        if (st !in 200..299) return LaneOutcome.Failed("the site answered $st", st)
        if (isChallenge(reply, expect)) {
            val reason = "the site answered with a challenge page instead of ${expect.name.lowercase()}"
            stop(reason)
            return LaneOutcome.Stopped(reason)
        }
        if (!s.everOk || s.backoffLevel != 0) store.put(store.get(site).copy(everOk = true, backoffLevel = 0))
        return LaneOutcome.Ok(reply)
    }

    private fun slow(s0: SiteState, reply: HttpReply): LaneOutcome {
        val now = clock.nowSec()
        val day = (now / 86400).toString()
        val count = if (s0.rateLimitedDay == day) s0.rateLimitedCount + 1 else 1
        val retryAfter = reply.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim()?.toLongOrNull()
        val level = s0.backoffLevel
        val wait = retryAfter ?: LaneRules.BACKOFF_SEC[level.coerceAtMost(LaneRules.BACKOFF_SEC.size - 1)]
        val until = now + wait
        store.put(s0.copy(backoffUntil = until, backoffLevel = if (retryAfter != null) level else level + 1, rateLimitedDay = day, rateLimitedCount = count))
        if (reply.status == 429 && count >= LaneRules.STOP_AFTER_429S_PER_DAY) {
            val reason = "the site answered 429 for the third time today"
            stop(reason)
            return LaneOutcome.Stopped(reason)
        }
        return LaneOutcome.BackOff(until, "the site answered ${reply.status}${if (retryAfter != null) " with Retry-After $retryAfter" else ""}")
    }

    private fun isChallenge(reply: HttpReply, expect: Expect): Boolean {
        if (reply.headers.keys.any { it.equals("cf-mitigated", true) }) return true
        val body = reply.body.trimStart()
        val looksHtml = body.startsWith("<!doctype html", true) || body.startsWith("<html", true)
        if (expect == Expect.JSON || expect == Expect.MANIFEST) {
            if (looksHtml) return true
        }
        if (looksHtml && Regex("""(?i)(just a moment|attention required|cf-chl|challenge-platform|captcha)""").containsMatchIn(body.take(4000))) return true
        return false
    }

    /** A paced request that is not a text page (the programme's image): the same stop, back-off and `robots.txt` rules, the same 10 s. Null when it may not be made. */
    suspend fun <T> pacedRaw(url: String, block: suspend () -> T): T? {
        if (preflight() != null) return null
        val robots = robots(Urls.origin(url)) ?: return null
        if (!robots.allows(Urls.path(url))) return null
        return paced(robots, block)
    }

    /** FR-2.3: one download at a time per site, 60 s between two episodes. */
    suspend fun <T> download(block: suspend () -> T): T = downloadGate.withLock {
        lastDownloadEndMs?.let { last ->
            val wait = last + LaneRules.EPISODE_GAP_SEC * 1000 - clock.monoMs()
            if (wait > 0) clock.sleepMs(wait)
        }
        try { block() } finally { lastDownloadEndMs = clock.monoMs() }
    }

    /** The pause a run would add before its first download, for tests. */
    internal fun lastDownloadEnd(): Long? = lastDownloadEndMs
}

/** The lanes of this process, one per site. */
class SiteLanes(
    private val store: SiteStore,
    private val transport: Transport,
    private val clock: LaneClock = SystemLaneClock,
) {
    private val lanes = HashMap<String, SiteLane>()
    private val lock = Mutex()
    suspend fun forSite(site: String): SiteLane = lock.withLock { lanes.getOrPut(site) { SiteLane(site, store, transport, clock) } }
}
