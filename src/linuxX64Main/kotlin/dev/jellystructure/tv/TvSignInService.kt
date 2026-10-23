package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DEB-2233 (FR-2, FR-3, FR-4) — a TV signs in from another device. The TV mints a code and shows it (as text and as a QR of
 * [mint]'s `linkUrl`); a viewer who is already signed in on a phone, in a browser or on a computer approves it, and the TV
 * collects a device token of its OWN, bound to that viewer. Unlike Phase 236's [ScreenPairingService], whose screen
 * *borrows* the phone's token, the TV here is a full sign-in: jellystructure runs a Quick Connect cycle against the server
 * with the approving viewer's own token, so the TV holds a token the server issued to it, under its own device identity.
 *
 *  1. [mint] — the unsigned-in TV (no credential): code + secret, after checking sign-in by code is possible at all.
 *  2. [preview] — the approving viewer's client asks what the code is for; the code moves to `approving`.
 *  3. [approve] — the cycle: initiate under the TV's identity, authorise with the CALLER'S token, exchange the secret,
 *     then the unchanged [RaviloDeviceService.loginDevice]. The server-side secret never leaves this class.
 *  4. [poll] — the TV, with its secret, until approved; a 200 deletes the row, so a code signs a TV in exactly once.
 *
 * Every refusal here is a value, never text: the routes choose the words, and none of them may name the server software
 * behind sign-in (FR-15).
 */
class TvSignInService(
    private val db: JellystructureDb,
    private val deviceService: RaviloDeviceService,
    private val jellyfin: JellyfinClient,
    private val configStore: ConfigStore,
    private val tvEventBus: TvEventBus? = null,
    private val clock: () -> Long = ::wallClockMs,
) {
    companion object {
        /** Long enough to walk to a phone and scan or type six characters off a TV across the room (FR-2). */
        const val CODE_TTL_MS = 10 * 60_000L
        /** How long "is sign-in by code possible" is remembered (FR-2). */
        const val AVAILABILITY_TTL_MS = 60_000L
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // Phase 236's: no 0/O/1/I
        /** A borrowing row has no sign-in of its own to approve with (FR-3). */
        private val BORROWING_KINDS = setOf("cast", "screen")

        fun mayApprove(caller: DeviceData): Boolean = caller.kind !in BORROWING_KINDS
    }

    // ── Is sign-in by code possible at all? ───────────────────────────────────────

    private val availabilityLock = Mutex()
    private var availableAt = 0L
    private var availableFor: String? = null
    private var availableValue = false

    /** FR-2 — the server's answer to "is this switched on", read at most once a minute. Unreachable reads as no, and is not remembered. */
    suspend fun available(): Boolean {
        val base = configStore.current.apiKeys.jellyfinUrl.trim()
        if (base.isEmpty()) return false
        return availabilityLock.withLock {
            val now = clock()
            if (availableFor == base && now - availableAt < AVAILABILITY_TTL_MS) return@withLock availableValue
            val read = jellyfin.quickConnectEnabled(base) ?: return@withLock false
            availableFor = base; availableAt = now; availableValue = read
            read
        }
    }

    // ── 1. Mint ───────────────────────────────────────────────────────────────────

    class Minted(val code: String, val expiresAt: Long, val secret: String, val linkUrl: String?)

    private fun freshCode(): String {
        while (true) {
            // 32 random bytes, each reduced mod 32: the alphabet has exactly 32 characters, so every one is equally likely.
            val hex = generateSecureToken()
            val code = (0 until 6).map { i -> CODE_ALPHABET[hex.substring(i * 2, i * 2 + 2).toInt(16) % CODE_ALPHABET.length] }.joinToString("")
            if (db.tvSigninCodeQueries.getByCode(code).executeAsOneOrNull() == null) return code
        }
    }

    /** FR-2 — null when sign-in by code is not possible (the route answers `{available: false}`). A second mint from the same
     *  device replaces that device's earlier code. */
    suspend fun mint(deviceId: String, deviceName: String?, platform: String?): Minted? {
        if (!available()) return null
        val now = clock()
        db.tvSigninCodeQueries.sweep(now)
        db.tvSigninCodeQueries.deleteByDevice(deviceId)
        val code = freshCode()
        val secret = generateSecureToken()
        val expires = now + CODE_TTL_MS
        db.tvSigninCodeQueries.insert(
            code, deviceId, deviceName?.trim()?.take(80)?.ifBlank { null }, platform?.trim()?.take(64)?.ifBlank { null },
            sha256Hex(secret), now, expires,
        )
        val link = configStore.raviloWebUrl?.let { "$it/tv?c=$code" }
        return Minted(code, expires, secret, link)
    }

    // ── 2. Preview ────────────────────────────────────────────────────────────────

    class Previewed(val deviceName: String, val platform: String?)

    private fun live(code: String) = db.tvSigninCodeQueries.getByCode(normalize(code)).executeAsOneOrNull()?.takeIf { it.expires_at >= clock() }

    /** FR-3 — what the approving viewer is asked to confirm; moves the code to `approving` with their display name. Null = unknown,
     *  expired or already used. */
    fun preview(code: String, approver: DeviceData): Previewed? {
        db.tvSigninCodeQueries.sweep(clock())
        val row = live(code) ?: return null
        if (row.state == "approved") return null
        db.tvSigninCodeQueries.preview(approver_name = approver.jellyfinUsername, code = row.code)
        return Previewed(row.device_name ?: "Ravilo TV ${row.device_id.take(6)}", row.platform)
    }

    // ── 3. Approve ────────────────────────────────────────────────────────────────

    enum class Approval { SIGNED_IN, NOT_FOUND, USED, UNAVAILABLE }

    private val inFlightLock = Mutex()
    private val inFlight = HashSet<String>()

    /**
     * FR-3, FR-4 — signs the TV in as [caller]. The caller must already have passed [mayApprove]. The token handed to the
     * server's authorise step is `caller.jellyfinUserToken` and nothing else; the machine token is never in reach of this class.
     */
    suspend fun approve(code: String, caller: DeviceData): Approval {
        db.tvSigninCodeQueries.sweep(clock())
        val row = live(code) ?: return Approval.NOT_FOUND
        if (row.state == "approved") return Approval.USED
        // Two approvals racing for one code: the second is "already used", and only one cycle runs.
        if (!inFlightLock.withLock { inFlight.add(row.code) }) return Approval.USED
        try {
            val base = configStore.current.apiKeys.jellyfinUrl.trim()
            if (base.isEmpty()) return Approval.UNAVAILABLE
            val tvName = row.device_name ?: "Ravilo TV ${row.device_id.take(6)}"
            // FR-4: the shape a password sign-in uses, so a second viewer adds a profile and a repeat approval replaces only their own.
            val identity = JellyfinDeviceIdentity("ravilo-${row.device_id}-${caller.jellyfinUsername}", tvName)
            val started = jellyfin.quickConnectInitiate(base, identity) ?: return Approval.UNAVAILABLE
            if (!jellyfin.quickConnectAuthorize(base, caller.jellyfinUserToken, started.code, JellyfinDeviceIdentity.forDevice(caller))) return Approval.UNAVAILABLE
            val result = jellyfin.authenticateWithQuickConnect(base, started.secret, identity) ?: return Approval.UNAVAILABLE
            if (normalizeId(result.user.id) != normalizeId(caller.jellyfinUserId)) {
                // Authorising with the caller's own token can only ever approve the caller; anything else is a server fault.
                Logger.warn("TV sign-in: the account that signed in is not the approver; refused (device ${row.device_id})", "auth")
                return Approval.UNAVAILABLE
            }
            Logger.info("TV sign-in approved for user '${result.user.name}' on device ${row.device_id}", "auth")
            if (deviceService.passwordManagement.record(result.user.id, result.user.policy.passwordManagedElsewhere)) {
                tvEventBus?.notifyConfigChanged(result.user.id)
            }
            val (device, deviceToken) = deviceService.loginDevice(
                deviceId = row.device_id,
                deviceName = row.device_name,
                jellyfinUserId = result.user.id,
                jellyfinUsername = result.user.name,
                jellyfinUserToken = result.accessToken,
                isAdmin = result.user.policy.isAdministrator,
                isKids = result.user.policy.maxParentalRating != null,
                allowedLibraries = if (result.user.policy.enableAllFolders) null else result.user.policy.enabledFolders.toSet(),
                allowedTags = result.user.policy.allowedTags.toSet(),
                blockedTags = result.user.policy.blockedTags.toSet(),
                platform = row.platform,
                kind = "tv",
            )
            db.tvSigninCodeQueries.approve(
                user_id = device.jellyfinUserId, token = deviceToken,
                avatar_url = RaviloImageUrl.avatar(device.jellyfinUserId, result.user.primaryImageTag), code = row.code,
            )
            return Approval.SIGNED_IN
        } finally {
            inFlightLock.withLock { inFlight.remove(row.code) }
        }
    }

    // ── 4. Poll ───────────────────────────────────────────────────────────────────

    sealed interface Poll {
        class Waiting(val state: String, val approverName: String?) : Poll
        class Done(val result: PairResult) : Poll
    }

    /** FR-2 — null for an unknown, expired or used code, or a wrong secret. A [Poll.Done] is returned once: the row goes with it. */
    fun poll(code: String, secret: String): Poll? {
        val row = live(code) ?: return null
        if (sha256Hex(secret) != row.secret_hash) return null
        val userId = row.approved_user_id
        val token = row.approved_token
        if (row.state != "approved" || userId == null || token == null) return Poll.Waiting(row.state, row.approver_name.takeIf { row.state == "approving" })
        db.tvSigninCodeQueries.deleteByCode(row.code)
        val device = deviceService.listSessions(row.device_id).firstOrNull { it.jellyfinUserId == userId } ?: return null   // removed in between
        return Poll.Done(PairResult(
            session = TvSession(
                deviceId = device.deviceId, userId = device.jellyfinUserId, displayName = device.jellyfinUsername,
                isAdmin = device.isAdmin, isKids = device.isKids, avatarUrl = row.approved_avatar_url,
            ),
            deviceToken = token,
        ))
    }

    /** Six characters, case-insensitive, spaces ignored (FR-6's code field sends what was typed). */
    private fun normalize(code: String) = code.filter { !it.isWhitespace() }.uppercase()

    private fun normalizeId(id: String) = id.replace("-", "").lowercase()
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun wallClockMs(): Long = platform.posix.time(null) * 1000L
