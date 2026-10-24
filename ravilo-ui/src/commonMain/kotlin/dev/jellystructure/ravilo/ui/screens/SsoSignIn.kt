package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * DEB-2233 (FR-11, FR-12) — the client half of single sign-on: the *Sign in with ‹name›* button's state machine, and the two things a
 * platform brings to it (a way to send the viewer to the browser, and a way for the browser's return to find its way back). Everything a
 * platform does not own is here, so it runs under `:ravilo-ui:desktopTest` against a fake [SsoApi] and a fake [SsoPlatform].
 */

/** How long the server keeps an attempt (FR-9): the client forgets it at the same moment, so a waiting screen is never left standing. */
const val SSO_ATTEMPT_MS = 10 * 60_000L

/** What the identity provider's return hands the client (FR-11): which attempt, and the one-time hand-off that completes it. */
data class SsoReturn(val attemptId: String, val handoff: String)

/** The two values a client keeps to finish an attempt (FR-9). The [secret] lives in memory: the activity's saved state, or `sessionStorage` on the web. */
data class SsoAttempt(val attemptId: String, val secret: String) {
    /** One string for a platform's saved state. Neither value contains a `|`: both are server-minted URL-safe tokens. */
    fun encode(): String = "$attemptId|$secret"

    companion object {
        fun decode(raw: String?): SsoAttempt? {
            val (id, secret) = (raw ?: return null).split('|', limit = 2).takeIf { it.size == 2 } ?: return null
            return if (id.isNotBlank() && secret.isNotBlank()) SsoAttempt(id, secret) else null
        }
    }
}

/**
 * Where a return waits until the screen that started the attempt is there to take it: the phone's activity offers it from the intent
 * (`dev.jellystructure.ravilo:/signin?attempt=…&h=…`), the desktop's loopback listener from its one request, Ravilo Web's `main()` from
 * `?signin=…&h=…`. Only the newest is kept. A return for an attempt nobody holds is simply never taken.
 */
class SsoReturnInbox {
    private val _pending = MutableStateFlow<SsoReturn?>(null)
    val pending: StateFlow<SsoReturn?> = _pending.asStateFlow()

    fun offer(r: SsoReturn?) { if (r != null) _pending.value = r }

    /** Spent once the claim has been tried, whatever it answered: a hand-off is single use. */
    fun clear(r: SsoReturn) { if (_pending.value == r) _pending.value = null }
}

/** The one inbox the platforms feed; tests make their own. */
val ssoReturnInbox = SsoReturnInbox()

/**
 * The attempt and hand-off in a location's query, or null. [attemptKey] is `attempt` for the app's scheme and the desktop's loopback and
 * `signin` for Ravilo Web (the server writes both, FR-11). [search] may carry its leading `?`.
 */
fun ssoReturnFromQuery(search: String, attemptKey: String): SsoReturn? {
    val q = queryPairs(search)
    val attempt = q.firstOrNull { it.first == attemptKey }?.second?.takeIf { it.isNotBlank() } ?: return null
    val handoff = q.firstOrNull { it.first == "h" }?.second?.takeIf { it.isNotBlank() } ?: return null
    return SsoReturn(attempt, handoff)
}

/**
 * Ravilo Web's address once the return has been read (FR-11): the same query without `signin` and `h`, so a reload, a bookmark or a shared
 * address never carries a hand-off, and everything else in it is left alone. Empty when nothing remains, otherwise with its `?`.
 */
fun ssoQueryWithoutReturn(search: String): String {
    val rest = search.removePrefix("?").split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in setOf("signin", "h") }
    return if (rest.isEmpty()) "" else "?" + rest.joinToString("&")
}

private fun queryPairs(search: String): List<Pair<String, String>> =
    search.removePrefix("?").split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to percentDecode(it.substringAfter('=', "")) }

/** `%XX` and `+`, nothing else: the values here are URL-safe tokens, so a malformed escape is kept as written rather than failing. */
internal fun percentDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        when {
            hex != null -> { bytes.add(hex.toByte()); i += 3 }
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            else -> { c.toString().encodeToByteArray().forEach { bytes.add(it) }; i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** What the store needs from the server. [TvSsoApi] is the real one. */
interface SsoApi {
    suspend fun providers(): List<String>
    suspend fun start(provider: String, deviceId: String, deviceName: String?, returnTo: String): SsoAttemptStart
    suspend fun claim(attemptId: String, secret: String, handoff: String): PairResult
}

class SsoAttemptStart(val authorizeUrl: String, val attempt: SsoAttempt)

class TvSsoApi(private val client: TvApiClient) : SsoApi {
    override suspend fun providers() = client.ssoProviders()
    override suspend fun start(provider: String, deviceId: String, deviceName: String?, returnTo: String): SsoAttemptStart {
        val r = client.ssoStart(provider, deviceId, deviceName, returnTo)
        return SsoAttemptStart(r.authorizeUrl, SsoAttempt(r.attemptId, r.secret))
    }
    override suspend fun claim(attemptId: String, secret: String, handoff: String) = client.ssoClaim(attemptId, secret, handoff)
}

/** What a platform opens before an attempt starts, and what it tells the server as `returnTo` (FR-9). */
interface SsoListener {
    /** `"app"`, `"web"`, or the desktop's `http://127.0.0.1:<port>/signin`. */
    val returnTo: String

    /** The attempt this listener is for, known once the server has answered the start. Until then, and for any other attempt, it ignores what it hears. */
    fun expect(attemptId: String) {}

    /** Stops listening. Called when the attempt ends any way: claimed, cancelled, failed, timed out. */
    fun close() {}
}

/** One platform's way into a browser and back. `null` where there is none to offer (a TV, FR-12). */
interface SsoPlatform {
    /** Opens what will catch the return. [closeTabText] is the *You can close this tab* line a desktop's loopback page shows. Throws when it cannot. */
    fun listen(closeTabText: String): SsoListener

    /** Sends the viewer to the identity provider: a Custom Tab, the default browser, or this tab. */
    fun open(authorizeUrl: String)

    /** Keeps the attempt across a round trip that reloads the page. Only the web needs it; everywhere else the attempt lives in the screen's saved state. */
    fun persist(attempt: SsoAttempt?) {}

    /** What [persist] kept, or null. */
    fun recall(): SsoAttempt? = null
}

enum class SsoErrorKind { FAILED, UNREACHABLE }

sealed class SsoState {
    data object Idle : SsoState()
    /** A tap, until the browser has been opened. The buttons are locked. */
    data object Starting : SsoState()
    /** *Finish signing in in your browser* and **Cancel** (FR-12). */
    data class Waiting(val provider: String) : SsoState()
    data class Errored(val kind: SsoErrorKind) : SsoState()
}

/**
 * DEB-2233 (FR-12, FR-11) — one sign-in screen's single sign-on. `providers` is the names to offer, empty on a TV (the screen never attaches
 * a platform there), when the server lists none, and after a start answered 503 (the identity provider would return viewers somewhere that is
 * not ours: hidden, never explained to a viewer). A tap runs start → open; the return arrives in [inbox] and is claimed when it names the
 * attempt this store holds. The claim's [PairResult] goes to [onPaired], which stores it as a password sign-in's is stored.
 */
class SsoSignInStore(
    private val api: SsoApi,
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val deviceName: () -> String?,
    private val onPaired: (PairResult) -> Unit,
    private val inbox: SsoReturnInbox = ssoReturnInbox,
    private val attemptMs: Long = SSO_ATTEMPT_MS,
) {
    private val _providers = MutableStateFlow<List<String>>(emptyList())
    val providers: StateFlow<List<String>> = _providers.asStateFlow()
    private val _state = MutableStateFlow<SsoState>(SsoState.Idle)
    val state: StateFlow<SsoState> = _state.asStateFlow()

    private var platform: SsoPlatform? = null
    private var listener: SsoListener? = null
    private var attempt: SsoAttempt? = null
    private var misconfigured = false
    private var run: Job? = null
    private var watch: Job? = null

    /** Called by the screen whenever the attempt it must keep in its saved state changes (null when there is none). */
    var onAttemptChanged: (SsoAttempt?) -> Unit = {}

    /**
     * Wires the platform and, if an attempt was kept across a reload or a process death ([restored], else the platform's own memory), waits for
     * its return. A null [platform] is a TV: nothing is fetched and nothing is shown. Safe to call again with the same platform.
     */
    fun attach(platform: SsoPlatform?, restored: SsoAttempt?) {
        if (this.platform === platform && watch != null) return
        this.platform = platform
        watch?.cancel()
        if (platform == null) { _providers.value = emptyList(); return }
        val kept = restored ?: platform.recall()
        if (attempt == null && kept != null) { attempt = kept; _state.value = SsoState.Waiting("") }
        watch = scope.launch { inbox.pending.collect { r -> if (r != null) claimIfOurs(r) } }
        scope.launch { if (!misconfigured) _providers.value = runCatching { api.providers() }.getOrElse { if (it is CancellationException) throw it else emptyList() } }
    }

    fun start(provider: String, closeTabText: String) {
        val p = platform ?: return
        if (_state.value is SsoState.Starting || _state.value is SsoState.Waiting) return
        _state.value = SsoState.Starting
        run = scope.launch {
            var opened: SsoListener? = null
            try {
                opened = p.listen(closeTabText)
                val s = api.start(provider, deviceId(), deviceName(), opened.returnTo)
                listener = opened
                attempt = s.attempt
                opened.expect(s.attempt.attemptId)
                p.persist(s.attempt)
                onAttemptChanged(s.attempt)
                _state.value = SsoState.Waiting(provider)
                p.open(s.authorizeUrl)
                // The attempt dies on the server at ten minutes; the screen does not outlive it.
                val mine = s.attempt
                scope.launch { delay(attemptMs); if (attempt == mine) forget(SsoState.Errored(SsoErrorKind.FAILED)) }
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            } catch (e: Throwable) {
                opened?.close()
                val unavailable = (e as? TvApiError.Http)?.status == 503
                if (unavailable) { misconfigured = true; _providers.value = emptyList() }
                forget(if (unavailable) SsoState.Idle else SsoState.Errored(SsoErrorKind.UNREACHABLE))
            }
        }
    }

    /** *Cancel* (FR-12): the attempt is forgotten here and, by its own clock, on the server. */
    fun cancel() { run?.cancel(); forget(SsoState.Idle) }

    /** Back to the buttons after an error. */
    fun dismissError() { if (_state.value is SsoState.Errored) _state.value = SsoState.Idle }

    private suspend fun claimIfOurs(r: SsoReturn) {
        val mine = attempt ?: return
        if (mine.attemptId != r.attemptId) return
        // Spent now, not after: the hand-off is single use, and a second collector must not race this one.
        inbox.clear(r)
        val result = runCatching { api.claim(mine.attemptId, mine.secret, r.handoff) }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val paired = result.getOrNull()
        val kind = (result.exceptionOrNull() as? TvApiError.Http)?.let { SsoErrorKind.FAILED } ?: SsoErrorKind.UNREACHABLE
        if (attempt != mine) return
        if (paired == null) { forget(SsoState.Errored(kind)); return }
        forget(SsoState.Idle)
        onPaired(paired)
    }

    private fun forget(next: SsoState) {
        listener?.close()
        listener = null
        attempt = null
        platform?.persist(null)
        onAttemptChanged(null)
        _state.value = next
    }
}
