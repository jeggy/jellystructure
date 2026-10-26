package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.model.MediaItem
import dev.jellystructure.tv.normalizeGuid
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DEB-2518 (FR-7) — the admin says when a filter tag and Jellyfin disagree.
 *
 * A tag edit in the admin saves to the database only (Phase 74), so a title can carry the household's filter tag here
 * and not in its NFO or in Jellyfin. Ravilo then shows it to a viewer filtered by that tag and Jellyfin refuses the
 * stream. For each tag named by any account's allowed or blocked list ([noteUsers], fed by the `/Users` read
 * `DevicePolicyReconciler` already makes), this compares the titles holding it here with
 * `GET /Items?Tags=<tag>&Recursive=true` and keeps the difference for the Dashboard ([rows]).
 *
 * Runs at most hourly ([noteUsers] is called every five minutes and skips until the hour is up) and after each full
 * scan ([afterScan]). It writes nothing: *Save to Jellyfin* ([save]) is the operator's press, and queues the NFO write and
 * the refresh for exactly the titles that differ. A failed Jellyfin read keeps the previous answer for that tag — an
 * unreachable server is not "no title carries the tag".
 *
 * Titles with no Jellyfin id are left out of both sides: Jellyfin does not hold them, and the unresolved-id finding
 * already says so.
 */
class FilterTagCheck(
    private val items: suspend () -> List<MediaItem>,
    /** `null` = the read failed. Ids as Jellyfin writes them; compared after [normalizeGuid]. */
    private val jellyfinIdsWith: suspend (tag: String) -> Set<String>?,
    /** The NFO write and the Jellyfin refresh for exactly these titles. */
    private val push: suspend (List<MediaItem>) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** How long after a press the comparison runs again, to give Jellyfin's refresh time to land. */
    private val settleMs: Long = SETTLE_MS,
) {
    /** One tag the two sides disagree on: the titles here with it and not in Jellyfin, and the reverse. */
    data class Disagreement(val tag: String, val missingInJellyfin: List<MediaItem>, val missingHere: List<MediaItem>) {
        val all: List<MediaItem> get() = missingInJellyfin + missingHere
    }

    private val mutex = Mutex()
    private var tags: Set<String> = emptySet()
    private var lastRunAt: Long? = null
    private var found: Map<String, Disagreement> = emptyMap()

    /** The tags any account's allowed or blocked list names, from a successful `/Users` read; starts a run when the hour is up. */
    fun noteUsers(users: List<dev.jellystructure.auth.JellyfinUser>) {
        tags = users.flatMap { it.policy.allowedTags + it.policy.blockedTags }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val last = lastRunAt
        if (last == null || clock() - last >= INTERVAL_MS) launchRun()
    }

    /** After a full scan the stored tags are as fresh as they get: compare now, hour or not. */
    fun afterScan() = launchRun()

    private fun launchRun() {
        scope.launch {
            runCatching { run() }.onFailure { if (it is CancellationException && !isActive) throw it; Logger.warn("Filter tag check failed: ${it.message}", "jellyfin") }
        }
    }

    suspend fun run() = mutex.withLock {
        val now = clock()
        lastRunAt = now
        val all = items()
        val next = LinkedHashMap<String, Disagreement>()
        for (tag in tags.sorted()) {
            val d = compare(tag, all)
            if (d == null) found[tag]?.let { next[tag] = it }   // a failed read keeps what was known
            else if (d.all.isNotEmpty()) next[tag] = d
        }
        found = next
    }

    private suspend fun compare(tag: String, all: List<MediaItem>): Disagreement? {
        val inJellyfin = jellyfinIdsWith(tag)?.map(::normalizeGuid)?.toSet() ?: return null
        val held = all.mapNotNull { item -> item.jellyfinId?.takeIf { it.isNotBlank() }?.let { normalizeGuid(it) to item } }
        val (here, notHere) = held.partition { (_, item) -> item.tags.any { it.equals(tag, ignoreCase = true) } }
        return Disagreement(
            tag,
            missingInJellyfin = here.filter { (id, _) -> id !in inJellyfin }.map { it.second },
            missingHere = notHere.filter { (id, _) -> id in inJellyfin }.map { it.second },
        )
    }

    /** The Dashboard rows: one per tag that differs, nothing when they agree. */
    suspend fun rows(): List<DashboardRow> = mutex.withLock { found.values.map(::row) }

    /**
     * *Save to Jellyfin* — compares this tag again against Jellyfin as it is now and queues the NFO write and the refresh for
     * the titles that differ, and only those. Returns how many were queued; `null` when Jellyfin could not be read, so nothing
     * was queued.
     */
    suspend fun save(rawTag: String): Int? {
        val tag = rawTag.trim().lowercase()
        if (tag !in tags) return 0   // only a tag an account's list names is ever compared
        val d = compare(tag, items()) ?: return null
        val titles = d.all
        if (titles.isEmpty()) return 0
        scope.launch {
            runCatching { push(titles) }.onFailure { if (it is CancellationException && !isActive) throw it; Logger.warn("Save to Jellyfin for filter tag '$tag' failed: ${it.message}", "jellyfin") }
            delay(settleMs)
            runCatching { run() }.onFailure { if (it is CancellationException && !isActive) throw it }
        }
        return titles.size
    }

    private fun row(d: Disagreement): DashboardRow {
        val n = d.missingInJellyfin.size
        val m = d.missingHere.size
        val key = d.tag.encodeURLParameter(spaceToPlus = false)
        val sentence = buildList {
            if (n > 0) add("$n ${if (n == 1) "title has" else "titles have"} the ${d.tag} filter tag here but not in Jellyfin. Viewers filtered by it see them and can't play them.")
            if (m > 0) add("$m ${if (m == 1) "title has" else "titles have"} the ${d.tag} filter tag in Jellyfin but not here. Viewers filtered by it are shown a different list than Jellyfin plays.")
        }.joinToString(" ")
        val names = d.all.map { it.title }.filter { it.isNotBlank() }.take(3)
        val total = n + m
        return DashboardRow(
            id = "filter_tag:$key", domain = "jf", severity = "warning",
            label = "The ${d.tag} filter tag differs from Jellyfin",
            sentence = sentence + if (names.isEmpty()) "" else " — " + names.joinToString(", ") + if (total > names.size) "…" else "",
            count = total, unit = "title", fix = "here", action = "Save to Jellyfin", actionId = "filter_tag_save:$key",
        )
    }

    companion object {
        const val INTERVAL_MS = 60 * 60_000L
        const val SETTLE_MS = 30_000L
    }
}
