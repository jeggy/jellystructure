package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.log.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 258 — a device enforces the policy Jellyfin has now, not the one it signed in with.
 *
 * The five policy fields on `ravilo_device` were copied once at sign-in (Phase 142) and never refreshed;
 * a TV never signs in again, so a viewer whose access was widened in Jellyfin kept day-0's fence for
 * weeks while Settings → Users & devices read the live policy and showed the wider one. Measured
 * 2026-09-24: 4 of 17 rows disagreed with Jellyfin, all on tags.
 *
 * One `/Users` fetch, every row of every user compared after normalisation (FR-258-2), rewritten only
 * where it differs, its tokens evicted so the rewrite is seen at once, `home_changed` pushed to that
 * user's open devices (FR-258-4). A failed fetch changes nothing (FR-258-3): [fetchUsers] answers `null`
 * for a failure — never an empty list, which would be indistinguishable from "no users" — and a user
 * missing from a successful answer is skipped, never widened and never narrowed.
 *
 * DEB-1854 rides the same pass: the account's authentication provider (is its password kept elsewhere?) and its live
 * name are taken from the same `/Users` answer, and a change of either pushes `config_changed`.
 *
 * Runs at backend start, every five minutes, and on an events-socket connect when that user's last pass
 * is older than 60 s ([reconcileOnConnect]) — a TV that just woke up gets today's policy before its first
 * Home fetch. One mutex: connect storms coalesce on one fetch rather than one per device.
 *
 * DEB-2675 (DEB-2672) answers the question FR-258-3 left open — a user Jellyfin no longer lists. The same `/Users` answer is
 * read a second way: a user the answer lacks twice in a row is *gone* and [onUserGone] removes them (FR-1, FR-4, FR-5). Who is
 * looked at is FR-10's [candidates]; a read that is not the whole user list (FR-1) or that loses every signed-in user (FR-2) is
 * neutral and changes nothing. Marks live in memory, so a restart can only delay a removal.
 */
class DevicePolicyReconciler(
    private val deviceService: RaviloDeviceService,
    /** `null` = the fetch failed (Jellyfin unreachable / non-2xx / unparseable); `emptyList()` = no users. */
    private val fetchUsers: suspend () -> List<JellyfinUser>?,
    /** R248's `home_changed` for one user, sent only after a rewrite that changed something. */
    private val onPolicyChanged: (jellyfinUserId: String) -> Unit,
    /** DEB-1854 (FR-3, FR-9) — `config_changed` for one user, sent when the provider answer or the account's name moved. */
    private val onConfigChanged: (jellyfinUserId: String) -> Unit = {},
    /** DEB-2518 (FR-7) — the successful `/Users` answer, for the filter tags the accounts name. Never called for a failed fetch. */
    private val onUsersRead: (List<JellyfinUser>) -> Unit = {},
    /** DEB-2675 (FR-10) — every user id the data tables hold rows for (any form); the reconciler adds the users with a device row. */
    private val candidates: () -> Collection<String> = { emptyList() },
    /** DEB-2675 (FR-4, FR-5) — runs once for a user who became gone, with the normalised id. A throw leaves them marked for the next pass. */
    private val onUserGone: suspend (normalisedUserId: String) -> Unit = {},
    /** DEB-2675 (FR-6) — a name for a user with no device row (their session, key or playback owner), or `null`. */
    private val nameFromData: (normalisedUserId: String) -> String? = { null },
    private val clock: () -> Long = ::epochMs,
) : UserPresence {
    private val mutex = Mutex()
    /** Keyed on the normalised id. */
    private val lastPassForUser = HashMap<String, Long>()

    /** DEB-2675 (FR-1) — normalised ids the last complete read lacked once. Written under [mutex] only; read by the refreshers without it. */
    @kotlin.concurrent.Volatile
    private var marks: Set<String> = emptySet()

    /** DEB-2675 (FR-3, FR-6) — whether the last complete read lacked [userId]. The refreshers skip a marked user; the overview says so. */
    override fun isMarked(userId: String): Boolean = normalizeGuid(userId) in marks

    /** DEB-2675 (FR-8) — a 404 for [userId] is a hint to look, never a verdict: one connect-style pass, behind its freshness rule. */
    override suspend fun notFoundHint(userId: String) {
        runCatching { kotlinx.coroutines.withTimeoutOrNull(HINT_TIMEOUT_MS) { reconcileOnConnect(userId) } }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** Outcome of one pass, for the log line and the tests: `null` when the fetch failed. */
    data class Pass(
        val usersSeen: Int, val usersSkipped: Int, val changes: List<PolicyChange>,
        /** DEB-1854 — users whose "password managed elsewhere" answer moved, and users whose name did. */
        val providerChanges: List<String> = emptyList(), val renames: List<String> = emptyList(),
        /** DEB-2675 — normalised ids marked absent by this pass, and ids it made gone. */
        val marked: List<String> = emptyList(), val gone: List<String> = emptyList(),
    )

    /** FR-258-2 — the start and interval trigger: every user with a row. */
    suspend fun reconcileAll(trigger: String): Pass? = mutex.withLock { pass(trigger) }

    /**
     * FR-258-2 — the connect trigger. Skipped when [jellyfinUserId]'s last pass is younger than [maxAgeMs]
     * (a device that reconnects every minute must not turn into a `/Users` call every minute); a pass that
     * runs covers every user, so ten TVs waking together cost one fetch, not ten.
     */
    suspend fun reconcileOnConnect(jellyfinUserId: String, maxAgeMs: Long = CONNECT_MAX_AGE_MS): Pass? {
        if (isFresh(jellyfinUserId, maxAgeMs)) return null
        return mutex.withLock {
            if (isFresh(jellyfinUserId, maxAgeMs)) null else pass("connect")
        }
    }

    private fun isFresh(userId: String, maxAgeMs: Long): Boolean =
        lastPassForUser[normalizeGuid(userId)]?.let { clock() - it < maxAgeMs } ?: false

    private suspend fun pass(trigger: String): Pass? {
        val users = fetchUsers()
        if (users == null) {
            // FR-258-3 / acceptance 5 — one line for the failed pass, no row touched, no device signed out.
            Logger.warn("Policy refresh ($trigger): could not read Jellyfin's users — every device keeps its current policy", "auth")
            return null
        }
        runCatching { onUsersRead(users) }
        val byId = users.associateBy { normalizeGuid(it.id) }   // DEB-2675 (FR-1) — both sides normalised, so a format difference never reads as absence
        val devices = deviceService.allDevices()
        val userIds = devices.map { it.jellyfinUserId }.distinct()
        val now = clock()
        val all = ArrayList<PolicyChange>()
        val providerChanges = ArrayList<String>()
        val renames = ArrayList<String>()
        var skipped = 0
        for (uid in userIds) {
            val user = byId[normalizeGuid(uid)]
            if (user == null) { skipped++; continue }   // FR-258-3 — absent is not "unrestricted"; "gone" is DEB-2675's two-read rule below
            val changes = deviceService.refreshPolicy(uid, DevicePolicy.of(user.policy), now)
            lastPassForUser[normalizeGuid(uid)] = now
            // DEB-1854 (FR-3/FR-9) — the same /Users answer carries the provider and the live name; no extra call.
            val providerMoved = deviceService.passwordManagement.record(uid, user.policy.passwordManagedElsewhere)
            val renamed = deviceService.refreshUsername(uid, user.name)
            if (providerMoved) {
                providerChanges += uid
                Logger.info("Password management refreshed: user ${user.name}: managed elsewhere = ${user.policy.passwordManagedElsewhere}", "auth")
            }
            if (renamed) {
                renames += uid
                Logger.info("Username refreshed: user ${user.name}", "auth")
            }
            if (providerMoved || renamed) onConfigChanged(uid)
            if (changes.isEmpty()) continue
            // FR-258-7 — one line per (device, field) that changed; silence otherwise.
            for (c in changes) {
                Logger.info("Policy refreshed: user ${user.name}, device ${c.deviceName}: ${c.field} ${c.from} → ${c.to}", "auth")
            }
            all += changes
            onPolicyChanged(uid)   // FR-258-4 — an open Home re-fetches on the server's word
        }
        val presence = checkPresence(users, byId.keys, devices, now)
        return Pass(
            usersSeen = userIds.size - skipped, usersSkipped = skipped, changes = all, providerChanges = providerChanges, renames = renames,
            marked = presence.first, gone = presence.second,
        )
    }

    /**
     * DEB-2675 (FR-1, FR-2, FR-10, FR-11) — the second reading of the same `/Users` answer. Returns the ids this pass marked and the
     * ids it made gone.
     */
    private suspend fun checkPresence(users: List<JellyfinUser>, listed: Set<String>, devices: List<DeviceData>, now: Long): Pair<List<String>, List<String>> {
        val none = emptyList<String>() to emptyList<String>()
        // FR-1 — complete only when non-empty and it lists an administrator; Main's "no Jellyfin configured" empty list is neutral.
        if (users.isEmpty() || users.none { it.policy.isAdministrator }) return none
        val withDevice = devices.map { normalizeGuid(it.jellyfinUserId) }.filter(::isUserId).toSet()   // H
        val all = (candidates().map(::normalizeGuid).filter(::isUserId) + withDevice).toSet()           // C (FR-11: 32 hex digits, so never __global__)
        if (all.isEmpty()) { marks = emptySet(); return none }
        // FR-2 (restated) — H stays the test while it has anyone in it; only an empty H falls back to C.
        val loses = if (withDevice.isNotEmpty()) withDevice.none { it in listed } else all.none { it in listed }
        if (loses) {
            val n = if (withDevice.isNotEmpty()) withDevice.size else all.size
            Logger.warn("User check: none of the $n signed-in users is in Jellyfin's answer, so nothing is removed. Is `jellyfinUrl` pointing at the right server?", "auth")
            return none
        }
        val newMarks = HashSet<String>()
        val marked = ArrayList<String>()
        val gone = ArrayList<String>()
        for (id in all) {
            lastPassForUser[id] = now   // FR-8 (corrected) — every candidate looked at, listed or not, so a burst of 404s costs one read a minute
            if (id in listed) continue   // a complete read that lists them clears the mark
            if (id !in marks) {
                newMarks += id; marked += id
                Logger.info("User check: ${nameOf(id, devices)} is not in Jellyfin's answer. Their devices will be removed if the next check agrees.", "auth")
                continue
            }
            val removed = runCatching { onUserGone(id) }
            removed.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (removed.isSuccess) gone += id
            else { newMarks += id; Logger.warn("User check: removing ${nameOf(id, devices)} failed, will retry at the next check: ${removed.exceptionOrNull()?.message}", "auth") }
        }
        // Listed users are cleared, a user removed is done, and a mark whose candidate has no rows left (removed by hand) is dropped with them.
        marks = newMarks
        return marked to gone
    }

    private fun nameOf(id: String, devices: List<DeviceData>): String =
        devices.firstOrNull { normalizeGuid(it.jellyfinUserId) == id && it.jellyfinUsername.isNotBlank() }?.jellyfinUsername
            ?: nameFromData(id)?.takeIf { it.isNotBlank() }
            ?: anOldAccount(id)

    companion object {
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        private fun epochMs(): Long = platform.posix.time(null) * 1000L
        const val INTERVAL_MS = 5 * 60_000L
        const val CONNECT_MAX_AGE_MS = 60_000L
        /** How long a refresher waits for the hint's pass; Server.kt's connect trigger waits 3 s the same way. */
        const val HINT_TIMEOUT_MS = 3_000L

        /** DEB-2675 (FR-11) — a user is exactly 32 hex digits once normalised; anything else (`__global__`, `household`, "") is not one. */
        fun isUserId(normalised: String): Boolean = normalised.length == 32 && normalised.all { it in '0'..'9' || it in 'a'..'f' }

        /** DEB-2675 (FR-6) — the name of a user whose rows carry none. */
        fun anOldAccount(normalised: String): String = "an old account (${normalised.take(8)})"
    }
}

/**
 * DEB-2675 (FR-3, FR-8) — what the background refreshers ask the reconciler. A user the last complete `/Users` read lacked is
 * *marked*: every loop that reads Jellyfin on their behalf skips them. A 404 from one of those reads is a *hint*: it asks for a
 * check and never marks, skips or removes anyone.
 */
interface UserPresence {
    fun isMarked(userId: String): Boolean
    suspend fun notFoundHint(userId: String)
}
