package dev.jellystructure.media

import dev.jellystructure.auth.CollectionDeleteResult
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** DEB-4074 (DEB-4032, FR-3) — how often the cache is re-read, so a change made in Jellyfin's own dashboard shows within this. */
internal const val COLLECTIONS_REFRESH_INTERVAL_MS = 5 * 60_000L

/** One Jellyfin collection as the cache holds it: [titleIds] are jellystructure media ids, in Jellyfin's order; [unmappedCount] is the members that are none. */
class JellyfinCollection(val id: String, val name: String, val titleIds: List<String>, val unmappedCount: Int)

/** An immutable copy of every Jellyfin collection. Readers hold one; a refresh swaps in the next. */
class JellyfinCollectionsSnapshot(val collections: List<JellyfinCollection>) {
    private val byId = collections.associateBy { it.id }
    private val byTitle: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (c in collections) for (t in c.titleIds) getOrPut(t) { ArrayList() }.add(c.id)
    }

    fun get(id: String): JellyfinCollection? = byId[id]

    /** The ids of the collections that hold the title [titleId]; empty for a title in none. */
    fun boxSetsOf(titleId: String): List<String> = byTitle[titleId].orEmpty()

    companion object {
        val EMPTY = JellyfinCollectionsSnapshot(emptyList())

        /**
         * FR-3 mapping — a member maps to a title by `MediaItem.jellyfinId`; one that maps to none (an episode, a season, music, a title in
         * a skipped library, an unknown id) is counted in `unmappedCount` and otherwise ignored. A collection with no mapped member is kept.
         */
        fun build(collections: List<Pair<JellyfinItem, List<JellyfinItem>>>, titleIdByJellyfinId: Map<String, String>): JellyfinCollectionsSnapshot =
            JellyfinCollectionsSnapshot(collections.map { (c, members) ->
                val titles = members.mapNotNull { titleIdByJellyfinId[jfKey(it.id)] }.distinct()
                JellyfinCollection(c.id, c.name, titles, members.size - members.count { titleIdByJellyfinId[jfKey(it.id)] != null })
            })

        internal fun jfKey(id: String): String = id.replace("-", "").lowercase()
    }
}

/**
 * DEB-4074 (DEB-4032, FR-3) — every Jellyfin collection, in memory. Jellyfin is the only store: nothing here is persisted, and the
 * whole copy is rebuilt from Jellyfin at start-up, every [COLLECTIONS_REFRESH_INTERVAL_MS], after a library scan ([refresh] from the scan
 * run) and straight after each of the writes below.
 *
 * **Reads never wait on Jellyfin** (phase 205): [current] is the last good copy, empty until the first refresh lands. A refresh that
 * fails anywhere (the list, or any one collection's members) keeps the previous copy whole and logs one line.
 */
class JellyfinCollections(
    private val client: JellyfinClient,
    private val configStore: ConfigStore,
    private val mediaStore: MediaStore,
) {
    @kotlin.concurrent.Volatile private var snapshot = JellyfinCollectionsSnapshot.EMPTY
    private val refreshing = Mutex()

    fun current(): JellyfinCollectionsSnapshot = snapshot

    /** Called once from `Main.kt` at boot: a refresh now, then every five minutes. */
    fun start(scope: CoroutineScope) {
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                runCatching { refresh() }.onFailure { if (it is CancellationException) throw it }
                delay(COLLECTIONS_REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Re-reads Jellyfin and swaps the copy in. True when it did; false when Jellyfin is not configured or any read failed (the old copy stays). */
    suspend fun refresh(): Boolean = refreshing.withLock {
        val keys = configStore.current.apiKeys
        if (keys.jellyfinUrl.isBlank() || keys.jellyfinToken.isBlank()) return false
        val collections = client.getCollections(keys.jellyfinUrl, keys.jellyfinToken)
            ?: return keepOld("the list of collections")
        val read = ArrayList<Pair<JellyfinItem, List<JellyfinItem>>>(collections.size)
        for (c in collections) {
            val members = client.getCollectionMembers(keys.jellyfinUrl, keys.jellyfinToken, c.id) ?: return keepOld("the members of a collection")
            read += c to members
        }
        val titles = HashMap<String, String>()
        for (item in mediaStore.allItems()) item.jellyfinId?.takeIf { it.isNotBlank() }?.let { titles[JellyfinCollectionsSnapshot.jfKey(it)] = item.id }
        snapshot = JellyfinCollectionsSnapshot.build(read, titles)
        true
    }

    private suspend fun keepOld(what: String): Boolean {
        Logger.warn("Jellyfin collections refresh failed reading $what; keeping the last copy")
        return false
    }

    // ── FR-3: the writes. Each one re-reads afterwards, whether or not it answered success: a partly-landed batch is still in Jellyfin. ──

    /** The new collection's Jellyfin id, or null. [jellyfinIds] are Jellyfin ids of the member titles. */
    suspend fun create(name: String, jellyfinIds: List<String>): String? {
        val keys = configStore.current.apiKeys
        return client.createCollection(keys.jellyfinUrl, keys.jellyfinToken, name, jellyfinIds).also { refresh() }
    }

    suspend fun add(collectionId: String, jellyfinIds: List<String>): Boolean {
        val keys = configStore.current.apiKeys
        return client.addToCollection(keys.jellyfinUrl, keys.jellyfinToken, collectionId, jellyfinIds).also { refresh() }
    }

    suspend fun remove(collectionId: String, jellyfinIds: List<String>): Boolean {
        val keys = configStore.current.apiKeys
        return client.removeFromCollection(keys.jellyfinUrl, keys.jellyfinToken, collectionId, jellyfinIds).also { refresh() }
    }

    /** [userId] is any Jellyfin user (the signed-in admin): the rename reads the item in a user's context. */
    suspend fun rename(collectionId: String, userId: String, name: String): Boolean {
        val keys = configStore.current.apiKeys
        return client.renameCollection(keys.jellyfinUrl, keys.jellyfinToken, userId, collectionId, name).also { refresh() }
    }

    /** FR-2 — through [JellyfinClient.deleteCollection] only. */
    suspend fun delete(collectionId: String): CollectionDeleteResult {
        val keys = configStore.current.apiKeys
        return client.deleteCollection(keys.jellyfinUrl, keys.jellyfinToken, collectionId).also { refresh() }
    }
}
