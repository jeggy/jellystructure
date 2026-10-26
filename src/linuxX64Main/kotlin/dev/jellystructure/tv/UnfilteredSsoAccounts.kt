package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.SSO_AUTHENTICATION_PROVIDER_ID
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.ops.SpinLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DEB-2518 (FR-12) — the admin says when a single-sign-on account has no tag filter.
 *
 * The sign-in plugin is patched to give a new account the household's filter tag (FR-10, FR-11). An upstream update
 * replaces the patched build, and every account created after that sees the whole library. [noteUsers] is fed by the
 * successful `/Users` read `DevicePolicyReconciler` already makes and keeps the accounts that are on the plugin's
 * provider id, are not administrators, and have empty allowed and blocked lists. [row] is the one Dashboard row that
 * names them. It writes nothing to Jellyfin: the operator's only press is [dismissAll], which remembers each named
 * account in [filePath] so one opened up on purpose stops being counted, and a later account still raises the row.
 *
 * A failed `/Users` read never reaches [noteUsers], so it keeps the previous answer rather than clearing the row.
 */
class UnfilteredSsoAccounts(private val filePath: String) {
    @Serializable
    private data class Data(val dismissed: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SpinLock()
    private var found: List<JellyfinUser> = emptyList()
    private var dismissed: Set<String> = emptySet()

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) return
        runCatching { dismissed = json.decodeFromString<Data>(FileIo.readText(path)).dismissed.toSet() }
    }

    fun noteUsers(users: List<JellyfinUser>) {
        val next = users.filter(::hasNoFilter)
        lock.withLock { found = next }
    }

    private fun hasNoFilter(u: JellyfinUser): Boolean =
        u.policy.authenticationProviderId?.trim().equals(SSO_AUTHENTICATION_PROVIDER_ID, ignoreCase = true) &&
            !u.policy.isAdministrator &&
            u.policy.allowedTags.none { it.isNotBlank() } && u.policy.blockedTags.none { it.isNotBlank() }

    /** The accounts the row names: no filter and not dismissed. */
    fun accounts(): List<JellyfinUser> = lock.withLock { found.filter { it.id !in dismissed } }

    /** *Leave it* — remembers every account the row currently names. Returns how many. */
    fun dismissAll(): Int {
        val (named, all) = lock.withLock {
            val named = found.map { it.id }.filter { it !in dismissed }
            if (named.isNotEmpty()) dismissed = dismissed + named
            named to dismissed
        }
        if (named.isNotEmpty()) persist(all)   // file I/O stays out of the spin lock
        return named.size
    }

    fun row(): DashboardRow? {
        val names = accounts().map { it.name }
        if (names.isEmpty()) return null
        val n = names.size
        val shown = names.sorted().take(5).joinToString(", ") + if (n > 5) "…" else ""
        return DashboardRow(
            id = "sso_unfiltered", domain = "jf", severity = "warning",
            label = "$n single-sign-on ${if (n == 1) "account sees" else "accounts see"} the whole library — ${if (n == 1) "it has" else "they have"} no tag filter",
            sentence = "$shown. A new account should get the household's filter tag when it first signs in; the patched sign-in plugin does that, and an update to the plugin may have replaced it. If an account is meant to see everything, leave it.",
            count = n, unit = "account", fix = "here", action = "Leave it", actionId = "sso_unfiltered_dismiss",
        )
    }

    private fun persist(dismissed: Set<String>) {
        val tmp = Path("$filePath.tmp")
        FileIo.writeText(tmp, json.encodeToString(Data(dismissed.sorted())))
        SystemFileSystem.atomicMove(tmp, Path(filePath))
    }
}
