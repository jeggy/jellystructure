package dev.jellystructure.tv

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
 * Runs at backend start, every five minutes, and on an events-socket connect when that user's last pass
 * is older than 60 s ([reconcileOnConnect]) — a TV that just woke up gets today's policy before its first
 * Home fetch. One mutex: connect storms coalesce on one fetch rather than one per device.
 */
class DevicePolicyReconciler(
    private val deviceService: RaviloDeviceService,
    /** `null` = the fetch failed (Jellyfin unreachable / non-2xx / unparseable); `emptyList()` = no users. */
    private val fetchUsers: suspend () -> List<JellyfinUser>?,
    /** R248's `home_changed` for one user, sent only after a rewrite that changed something. */
    private val onPolicyChanged: (jellyfinUserId: String) -> Unit,
    private val clock: () -> Long = ::epochMs,
) {
    private val mutex = Mutex()
    private val lastPassForUser = HashMap<String, Long>()

    /** Outcome of one pass, for the log line and the tests: `null` when the fetch failed. */
    data class Pass(val usersSeen: Int, val usersSkipped: Int, val changes: List<PolicyChange>)

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
        lastPassForUser[userId]?.let { clock() - it < maxAgeMs } ?: false

    private suspend fun pass(trigger: String): Pass? {
        val users = fetchUsers()
        if (users == null) {
            // FR-258-3 / acceptance 5 — one line for the failed pass, no row touched, no device signed out.
            Logger.warn("Policy refresh ($trigger): could not read Jellyfin's users — every device keeps its current policy", "auth")
            return null
        }
        val byId = users.associateBy { it.id }
        val userIds = deviceService.allDevices().map { it.jellyfinUserId }.distinct()
        val now = clock()
        val all = ArrayList<PolicyChange>()
        var skipped = 0
        for (uid in userIds) {
            val user = byId[uid]
            if (user == null) { skipped++; continue }   // FR-258-3 — absent is not "unrestricted" and not "gone"
            val changes = deviceService.refreshPolicy(uid, DevicePolicy.of(user.policy), now)
            lastPassForUser[uid] = now
            if (changes.isEmpty()) continue
            // FR-258-7 — one line per (device, field) that changed; silence otherwise.
            for (c in changes) {
                Logger.info("Policy refreshed: user ${user.name}, device ${c.deviceName}: ${c.field} ${c.from} → ${c.to}", "auth")
            }
            all += changes
            onPolicyChanged(uid)   // FR-258-4 — an open Home re-fetches on the server's word
        }
        return Pass(usersSeen = userIds.size - skipped, usersSkipped = skipped, changes = all)
    }

    companion object {
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        private fun epochMs(): Long = platform.posix.time(null) * 1000L
        const val INTERVAL_MS = 5 * 60_000L
        const val CONNECT_MAX_AGE_MS = 60_000L
    }
}
