package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.JellyfinAuthResponse
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.UNKNOWN_CLIENT_VERSION
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.model.PublicUrl
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvSession
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DEB-2233 (FR-8 to FR-11, FR-13) — single sign-on, with jellystructure fronting the identity provider's return. The server's
 * single-sign-on plugin does all the OpenID work; this class makes the three calls that put it behind jellystructure's own
 * address, so a viewer's browser never visits a Jellyfin page or address:
 *
 *  1. [startDevice] / [startAdmin] — `GET <jellyfin_internal_url>/SSO/OID/start/<provider>` with `Host: <public_url's host>`, the
 *     redirect read and not followed. The plugin builds its `redirect_uri` from that Host, so the identity provider returns the
 *     viewer to jellystructure. The attempt is kept ten minutes, keyed by the OIDC `state`, the client's secret only as its SHA-256.
 *  2. [handleReturn] — the return is replayed to the plugin, which exchanges the code itself; the plugin's `Auth` answers the account's
 *     token; a Quick Connect self-approval turns it into the device's own token under `ravilo-<deviceId>-<username>`, and the
 *     bootstrap token is logged out. The plugin's pages are read as a status and discarded: every outcome is a [Outcome.Page].
 *  3. [claim] — the device collects its [PairResult] with the secret AND the hand-off the success page delivered by redirect, so an
 *     attempt started on one device and finished in someone else's browser signs the first device in never (FR-11, acceptance 10).
 *
 * Every refusal here is a value, never text: the pages choose the words, and a viewer's page never names the software behind
 * sign-in (FR-15). The admin's attempt is bound by its `js_sso` cookie instead of a hand-off and ends in the ordinary admin session.
 */
class SingleSignOnService(
    private val db: JellystructureDb,
    private val deviceService: RaviloDeviceService,
    private val jellyfin: JellyfinClient,
    private val configStore: ConfigStore,
    private val sessionService: SessionService,
    private val tvEventBus: TvEventBus? = null,
    private val clock: () -> Long = ::wallClockMs,
) {
    companion object {
        /** An attempt lives ten minutes from its start (FR-9). */
        const val ATTEMPT_TTL_MS = 10 * 60_000L
        /** How long the plugin's provider list is remembered (FR-8). */
        const val PROVIDERS_TTL_MS = 60_000L
        /** The private-use scheme the phone activity declares (RFC 8252 §7.1; FR-11). */
        const val APP_SCHEME_TARGET = "dev.jellystructure.ravilo:/signin"
        const val KIND_DEVICE = "device"
        const val KIND_ADMIN = "admin"
        /** The Jellyfin device the admin's single sign-on token lives under, as the admin's password sign-in uses the server's one. */
        private const val ADMIN_DEVICE_ID = "jellystructure-sso-admin"
        private val LOOPBACK = Regex("""^http://(127\.0\.0\.1|\[::1]):(\d{1,5})/signin$""")

        /** FR-9 — `"app"`, `"web"`, or the desktop's loopback listener (RFC 8252 §7.3). Nothing else is ever redirected to. */
        fun validReturnTo(returnTo: String): Boolean =
            returnTo == "app" || returnTo == "web" || LOOPBACK.matchEntire(returnTo)?.groupValues?.get(2)?.toIntOrNull()?.let { it in 1..65535 } == true
    }

    // ── FR-8 — which providers the server lists ───────────────────────────────────

    private val providersLock = Mutex()
    private var providersAt = 0L
    private var providersFor: String? = null
    private var providersValue: List<String> = emptyList()

    /** The names the plugin lists, read at most once a minute; `[]` when it is absent, errors or lists nothing (or no address is set). */
    suspend fun providers(): List<String> {
        val base = configStore.jellyfinInternalUrl.trim()
        if (base.isEmpty()) return emptyList()
        return providersLock.withLock {
            val now = clock()
            if (providersFor == base && now - providersAt < PROVIDERS_TTL_MS) return@withLock providersValue
            val read = jellyfin.ssoProviderNames(base).orEmpty()
            providersFor = base; providersAt = now; providersValue = read
            read
        }
    }

    // ── FR-9 — start ──────────────────────────────────────────────────────────────

    sealed interface Start {
        class Ok(val authorizeUrl: String, val attemptId: String, val secret: String) : Start
        /** 503 `{reason: "unavailable"}`: nothing to sign in with, a plugin that cannot be reached, or a return address that is not ours. */
        object Unavailable : Start
        /** 400: the request itself is malformed. */
        object Invalid : Start
    }

    /** A Ravilo client's start. [appVersion] is its `X-Ravilo-Version`. */
    suspend fun startDevice(
        provider: String, deviceId: String, deviceName: String?, platform: String?, returnTo: String, appVersion: String?,
    ): Start {
        if (deviceId.isBlank() || deviceId.length > 128 || provider.isBlank() || !validReturnTo(returnTo)) return Start.Invalid
        if (returnTo == "web" && webBase() == null) return Start.Unavailable
        return begin(
            provider, KIND_DEVICE, deviceId, deviceName?.trim()?.take(80)?.ifBlank { null }, platform?.trim()?.take(64)?.ifBlank { null },
            appVersion?.trim()?.take(64)?.ifBlank { null }, returnTo,
        )
    }

    /** The admin web's start; the secret is for the `js_sso` cookie (FR-9, FR-13). */
    suspend fun startAdmin(provider: String): Start =
        if (provider.isBlank()) Start.Invalid else begin(provider, KIND_ADMIN, null, null, null, null, null)

    private suspend fun begin(
        provider: String, kind: String, deviceId: String?, deviceName: String?, platform: String?, appVersion: String?, returnTo: String?,
    ): Start {
        val base = configStore.jellyfinInternalUrl.trim()
        val host = publicHost() ?: return Start.Unavailable
        if (base.isEmpty() || provider !in providers()) return Start.Unavailable
        val reply = jellyfin.ssoStart(base, host, provider)
        val status = reply.status ?: return Start.Unavailable
        val location = reply.location?.takeIf { status in 300..399 } ?: return Start.Unavailable
        val parsed = runCatching { Url(location) }.getOrNull() ?: return Start.Unavailable
        val state = parsed.parameters["state"]?.takeIf { it.isNotBlank() } ?: return Start.Unavailable
        val redirectAuthority = parsed.parameters["redirect_uri"]?.let(::authorityOf)
        val fingerprint = setupFingerprint()
        // FR-9 — a return address that is not ours means the identity provider would send viewers to a page that is not
        // jellystructure: refused, and the dashboard says where they would go. It is read at every start (so the first one after a
        // config change is among them), and a start that passes takes the row down again.
        if (redirectAuthority == null || redirectAuthority != authorityOf("https://$host")) {
            val named = redirectAuthority ?: "an address it could not read"
            Logger.warn("Single sign-on: the provider's return address names '$named', not this server's '$host'; start refused", "auth")
            SsoSetupFlag.raise(named, fingerprint)
            return Start.Unavailable
        }
        SsoSetupFlag.clear()
        val now = clock()
        db.ssoAttemptQueries.sweep(now)
        val attemptId = generateSecureToken().take(32)
        val secret = generateSecureToken()
        runCatching {
            db.ssoAttemptQueries.insert(state, attemptId, provider, kind, deviceId, deviceName, platform, appVersion, returnTo, sha256Hex(secret), now, now + ATTEMPT_TTL_MS)
        }.onFailure { return Start.Unavailable }
        return Start.Ok(location, attemptId, secret)
    }

    // ── FR-10 — the return ────────────────────────────────────────────────────────

    /** What a viewer's or admin's page says. [ADMIN_REFUSED] is the admin's only: a return the browser it started in did not make. */
    enum class PageKind { SIGNED_IN, NOT_ALLOWED, TOO_LONG, CANCELLED, UNAVAILABLE, ADMIN_REFUSED }

    sealed interface Outcome {
        /** A page jellystructure renders. [handBack] is the address a signed-in page redirects the browser to (FR-11); [open] a link to the client
         *  an error page offers; [admin] picks the admin's wording, which may name Jellyfin. */
        class Page(val kind: PageKind, val admin: Boolean = false, val handBack: String? = null, val open: String? = null) : Outcome
        /** The admin attempt succeeded: [sessionToken] is the ordinary 7-day admin session (`js_session`). */
        class AdminSignedIn(val sessionToken: String) : Outcome
    }

    private val inFlightLock = Mutex()
    private val inFlight = HashSet<String>()

    /**
     * FR-10 — the identity provider returned to `/sso/OID/redirect/{provider}`. [params] are the decoded query, [rawQuery] the same
     * query verbatim (the plugin gets it back as it came), [adminCookie] the `js_sso` cookie if the browser sent one.
     */
    suspend fun handleReturn(provider: String, params: Map<String, String>, rawQuery: String, adminCookie: String?): Outcome {
        val now = clock()
        db.ssoAttemptQueries.sweep(now)
        val state = params["state"]?.takeIf { it.isNotBlank() }
        val attempt = state?.let { db.ssoAttemptQueries.getByState(it).executeAsOneOrNull() }?.takeIf { it.expires_at >= now && it.provider == provider }
        val admin = attempt?.kind == KIND_ADMIN
        fun page(kind: PageKind, handBack: String? = null) = Outcome.Page(kind, admin, handBack, open = attempt?.let(::openLink))
        val error = params["error"]?.takeIf { it.isNotBlank() }
        if (error != null) {
            if (state != null) db.ssoAttemptQueries.deleteByState(state)
            return page(if (error == "access_denied") PageKind.CANCELLED else PageKind.UNAVAILABLE)
        }
        if (attempt == null || state == null) return Outcome.Page(PageKind.TOO_LONG, admin = false, open = webBase())
        // The admin's binding is its cookie, checked before the code is spent: a return in another browser does nothing at all.
        if (admin && (adminCookie.isNullOrEmpty() || sha256Hex(adminCookie) != attempt.secret_hash)) return Outcome.Page(PageKind.ADMIN_REFUSED, admin = true)
        // One return per attempt: a second concurrent request for the same state finds it taken (the plugin would refuse it too).
        if (!inFlightLock.withLock { inFlight.add(state) }) return page(PageKind.TOO_LONG)
        try {
            return finish(provider, state, rawQuery, attempt, ::page)
        } finally {
            inFlightLock.withLock { inFlight.remove(state) }
        }
    }

    private suspend fun finish(
        provider: String, state: String, rawQuery: String, attempt: dev.jellystructure.db.Sso_attempt, page: (PageKind, String?) -> Outcome.Page,
    ): Outcome {
        val base = configStore.jellyfinInternalUrl.trim()
        val host = publicHost()
        val jellyfinUrl = configStore.current.apiKeys.jellyfinUrl.trim()
        if (base.isEmpty() || host == null || jellyfinUrl.isEmpty()) return page(PageKind.UNAVAILABLE, null)
        val admin = attempt.kind == KIND_ADMIN
        // 1 — the plugin exchanges the code itself. Its page is read as a status and thrown away.
        val replay = jellyfin.ssoRedirect(base, host, provider, rawQuery)
        when (replay.status) {
            null -> return page(PageKind.UNAVAILABLE, null)
            401, 403 -> { db.ssoAttemptQueries.deleteByState(state); return page(PageKind.NOT_ALLOWED, null) }
            in 200..299 -> Unit
            else -> { db.ssoAttemptQueries.deleteByState(state); return page(PageKind.TOO_LONG, null) }
        }
        // 2 — the account's own token, under a device identity we chose.
        val deviceId = attempt.device_id
        val deviceName = attempt.device_name ?: if (admin) "Jellystructure" else "Ravilo"
        val appVersion = attempt.app_version ?: UNKNOWN_CLIENT_VERSION
        val bootstrapId = if (admin) ADMIN_DEVICE_ID else "ravilo-$deviceId-sso"
        val (authStatus, boot) = jellyfin.ssoAuth(base, host, provider, bootstrapId, deviceName, appVersion, state)
        if (boot == null) {
            db.ssoAttemptQueries.deleteByState(state)
            return page(if (authStatus == null) PageKind.UNAVAILABLE else if (authStatus == 401 || authStatus == 403) PageKind.NOT_ALLOWED else PageKind.TOO_LONG, null)
        }
        val bootIdentity = JellyfinDeviceIdentity(bootstrapId, deviceName, appVersion)
        if (admin) return finishAdmin(state, boot, page)
        try {
            // 3 — the device's real token, minted by the account approving its own Quick Connect code.
            val identity = JellyfinDeviceIdentity("ravilo-$deviceId-${boot.user.name}", deviceName, appVersion)
            val real = jellyfin.quickConnectSelfApprove(jellyfinUrl, boot.accessToken, bootIdentity, identity)
            if (real == null || normalizeId(real.user.id) != normalizeId(boot.user.id)) {
                Logger.warn("Single sign-on: the device's own token could not be minted for '${boot.user.name}' (device $deviceId)", "auth")
                db.ssoAttemptQueries.deleteByState(state)
                return page(PageKind.UNAVAILABLE, null)
            }
            Logger.info("Single sign-on succeeded for user '${real.user.name}' on device $deviceId", "auth")
            if (deviceService.passwordManagement.record(real.user.id, real.user.policy.passwordManagedElsewhere)) tvEventBus?.notifyConfigChanged(real.user.id)
            // 4 — the unchanged sign-in; the attempt is complete, and the hand-off is the only thing that lets a client collect it.
            val (device, deviceToken) = deviceService.loginDevice(
                deviceId = deviceId!!,
                deviceName = attempt.device_name,
                jellyfinUserId = real.user.id,
                jellyfinUsername = real.user.name,
                jellyfinUserToken = real.accessToken,
                isAdmin = real.user.policy.isAdministrator,
                isKids = real.user.policy.maxParentalRating != null,
                allowedLibraries = if (real.user.policy.enableAllFolders) null else real.user.policy.enabledFolders.toSet(),
                allowedTags = real.user.policy.allowedTags.toSet(),
                blockedTags = real.user.policy.blockedTags.toSet(),
                appVersion = attempt.app_version,
                platform = attempt.platform,
            )
            val handoff = generateSecureToken()
            // No address to hand back to (a web start whose Ravilo Web address was cleared since): nothing could ever claim it.
            val handBack = handBackTo(attempt, handoff) ?: run { db.ssoAttemptQueries.deleteByState(state); return page(PageKind.UNAVAILABLE, null) }
            db.ssoAttemptQueries.complete(
                handoff_hash = sha256Hex(handoff), user_id = device.jellyfinUserId, token = deviceToken,
                avatar_url = RaviloImageUrl.avatar(device.jellyfinUserId, real.user.primaryImageTag), state = state,
            )
            return page(PageKind.SIGNED_IN, handBack)
        } finally {
            jellyfin.logoutSession(jellyfinUrl, boot.accessToken, bootIdentity)
        }
    }

    private suspend fun finishAdmin(state: String, boot: JellyfinAuthResponse, page: (PageKind, String?) -> Outcome.Page): Outcome {
        db.ssoAttemptQueries.deleteByState(state)
        if (!boot.user.policy.isAdministrator) {
            Logger.warn("Admin single sign-on refused for user '${boot.user.name}': not an administrator", "auth")
            return page(PageKind.NOT_ALLOWED, null)
        }
        Logger.info("Admin single sign-on succeeded for user '${boot.user.name}'", "auth")
        return Outcome.AdminSignedIn(sessionService.create(boot.user.id, boot.user.name, boot.accessToken))
    }

    // ── FR-11 — the claim ─────────────────────────────────────────────────────────

    /**
     * FR-11 — null (the route's 401) unless the attempt is finished AND [secret] AND [handoff] both match. A claim that succeeds deletes
     * the attempt, so it signs a device in exactly once; one that fails changes nothing.
     */
    fun claim(attemptId: String, secret: String, handoff: String): PairResult? {
        val now = clock()
        db.ssoAttemptQueries.sweep(now)
        val row = db.ssoAttemptQueries.getByAttemptId(attemptId).executeAsList().firstOrNull() ?: return null
        val userId = row.completed_user_id
        val token = row.completed_token
        val handoffHash = row.handoff_hash
        if (row.kind != KIND_DEVICE || row.expires_at < now || userId == null || token == null || handoffHash == null) return null
        if (sha256Hex(secret) != row.secret_hash || sha256Hex(handoff) != handoffHash) return null
        db.ssoAttemptQueries.deleteByState(row.state)
        val device = deviceService.listSessions(row.device_id.orEmpty()).firstOrNull { it.jellyfinUserId == userId } ?: return null   // removed in between
        return PairResult(
            session = TvSession(
                deviceId = device.deviceId, userId = device.jellyfinUserId, displayName = device.jellyfinUsername,
                isAdmin = device.isAdmin, isKids = device.isKids, avatarUrl = row.completed_avatar_url,
            ),
            deviceToken = token,
        )
    }

    // ── Addresses ─────────────────────────────────────────────────────────────────

    /** `<public_url>`'s host (with its port when it names one): what the plugin is told it is being called as. Null while it is unset. */
    fun publicHost(): String? = PublicUrl.effective(configStore.current.publicUrl)?.substringAfter("://")

    /** Where Ravilo Web is: `ravilo_web_url`, else the backend's own origin (FR-1). Never anything a client supplied. */
    fun webBase(): String? = configStore.raviloWebUrl ?: PublicUrl.effective(configStore.current.publicUrl)

    /** FR-11 — where the success page sends the browser, by redirect only. The hand-off never rides anything else. */
    private fun handBackTo(attempt: dev.jellystructure.db.Sso_attempt, handoff: String): String? {
        val query = "attempt=${attempt.attempt_id}&h=$handoff"
        return when (val to = attempt.return_to) {
            "app" -> "$APP_SCHEME_TARGET?$query"
            "web" -> webBase()?.let { "$it/?signin=${attempt.attempt_id}&h=$handoff" }
            null -> null
            else -> if (validReturnTo(to)) "$to?$query" else null
        }
    }

    /** What an error page offers as *Open Ravilo*: the client's own address, with no hand-off. A loopback listener is not offered. */
    private fun openLink(attempt: dev.jellystructure.db.Sso_attempt): String? = when (attempt.return_to) {
        "app" -> APP_SCHEME_TARGET
        "web" -> webBase()
        else -> null
    }

    private fun setupFingerprint(): String =
        "${configStore.current.publicUrl}|${configStore.jellyfinInternalUrl}|${configStore.current.apiKeys.jellyfinUrl}"

    /** `host[:port]` of an absolute URL, lower-cased, a scheme's default port dropped. Null when it is not one. */
    private fun authorityOf(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase().takeIf { it.isNotEmpty() } ?: return null
        var authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        when (scheme) { "https" -> authority = authority.removeSuffix(":443"); "http" -> authority = authority.removeSuffix(":80") }
        return authority.takeIf { it.isNotEmpty() }
    }

    private fun normalizeId(id: String) = id.replace("-", "").lowercase()
}

/**
 * DEB-2233 (FR-9) — the dashboard's *Single sign-on is set up wrong* row. Raised by a start whose `redirect_uri` does not name
 * `public_url`'s host, taken down by the next start that passes, and hidden the moment the addresses it was measured against change
 * (so a config change never leaves a stale row behind; the next start re-measures).
 */
object SsoSetupFlag {
    private class Wrong(val host: String, val fingerprint: String)
    @kotlin.concurrent.Volatile private var wrong: Wrong? = null

    fun raise(host: String, fingerprint: String) { wrong = Wrong(host, fingerprint) }
    fun clear() { wrong = null }

    /** The host the provider would return viewers to, while it is still true of [fingerprint]'s configuration. */
    fun host(fingerprint: String): String? = wrong?.takeIf { it.fingerprint == fingerprint }?.host

    fun dashboardRow(configStore: ConfigStore): DashboardRow? {
        val fingerprint = "${configStore.current.publicUrl}|${configStore.jellyfinInternalUrl}|${configStore.current.apiKeys.jellyfinUrl}"
        val host = host(fingerprint) ?: return null
        return DashboardRow(
            id = "sso_redirect_host", domain = "svc", severity = "warning",
            label = "Single sign-on is set up wrong: the identity provider would return viewers to $host",
            sentence = "It should return them to this server's public address. Until it does, the *Sign in with…* button stays hidden on every client.",
            fix = "elsewhere", where = "Jellyfin", path = "Dashboard › Plugins › SSO Authentication", now = host,
            recommendation = "Make the plugin build its return address from the address it is called on (its scheme override set, no fixed address).",
        )
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun wallClockMs(): Long = platform.posix.time(null) * 1000L
