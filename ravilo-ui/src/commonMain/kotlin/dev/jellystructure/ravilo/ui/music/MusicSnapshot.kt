package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.screens.HOME_SNAPSHOT_STALE_AFTER_MS
import dev.jellystructure.ravilo.ui.screens.exceedsSnapshotSizeCap
import dev.jellystructure.shared.tv.MusicGenreCount
import dev.jellystructure.shared.tv.MusicHome
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.RaviloWireJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * DEB-4091 (FR-5) — the music entry pages as the server last answered them, one overwritten entry per user, so a cold
 * start draws Listen and the last-opened Browse chips at once and refreshes behind them. R212's idea, applied to music.
 * Always an exact copy of a server answer, never merged or derived; images are never part of it.
 */
@Serializable
data class MusicSnapshot(
    val home: Saved<MusicHome>? = null,
    /** Page 0 of Albums, Artists and Songs by chip (`albums`, `artists`, `songs`), each with the sort it was loaded with. */
    val lists: Map<String, SavedList> = emptyMap(),
    val genres: Saved<List<MusicGenreCount>>? = null,
    val playlists: Saved<List<MusicPlaylist>>? = null,
) {
    /** Each part carries its own age, so one part written today never keeps another one alive past 7 days. */
    @Serializable
    data class Saved<T>(val savedAtEpochMs: Long, val value: T)

    @Serializable
    data class SavedList(val sort: String, val savedAtEpochMs: Long, val list: MusicList)

    /** The saved first page of [chip], only when it was loaded with the sort now being asked for. */
    fun listFor(chip: String, sort: String): MusicList? = lists[chip]?.takeIf { it.sort == sort }?.list

    /** The parts younger than 7 days (a negative age, from clock skew, is stale). */
    internal fun fresh(nowEpochMs: Long): MusicSnapshot = MusicSnapshot(
        home = home?.takeIf { isMusicPartFresh(it.savedAtEpochMs, nowEpochMs) },
        lists = lists.filterValues { isMusicPartFresh(it.savedAtEpochMs, nowEpochMs) },
        genres = genres?.takeIf { isMusicPartFresh(it.savedAtEpochMs, nowEpochMs) },
        playlists = playlists?.takeIf { isMusicPartFresh(it.savedAtEpochMs, nowEpochMs) },
    )
}

/** FR-5 *Bounds* — older than 7 days counts as absent; the same window as R212's [HOME_SNAPSHOT_STALE_AFTER_MS]. */
internal fun isMusicPartFresh(savedAtEpochMs: Long, nowEpochMs: Long): Boolean =
    (nowEpochMs - savedAtEpochMs) in 0..HOME_SNAPSHOT_STALE_AFTER_MS

/** The platform's raw string store for the saved copy: a file, or a `localStorage` entry, under its own name. None may throw. */
interface MusicSnapshotStorage {
    fun read(userId: String): String?
    fun write(userId: String, json: String)
    fun delete(userId: String)
}

/** The device's [MusicSnapshotStorage]: `music_snapshot_<user>.json` beside `home_snapshot_<user>.json`. */
expect object MusicSnapshotCache : MusicSnapshotStorage {
    override fun read(userId: String): String?
    override fun write(userId: String, json: String)
    override fun delete(userId: String)
}

/** Reads and writes the saved copy through [storage]; [nowEpochMs] is a parameter so tests can pin the time. */
class MusicSnapshots(private val storage: MusicSnapshotStorage, private val nowEpochMs: () -> Long) {
    private val lock = Mutex()
    @Volatile private var clears = 0

    internal fun now(): Long = nowEpochMs()

    /** The saved copy's fresh parts, or null when there is none or it fails to decode (never breaks the page). */
    fun load(userId: String): MusicSnapshot? = read(userId)?.fresh(nowEpochMs())

    private fun read(userId: String): MusicSnapshot? = runCatching {
        storage.read(userId)?.let { RaviloWireJson.decodeFromString<MusicSnapshot>(it) }
    }.getOrNull()

    /**
     * Read, change one part, overwrite. A write over the 2 MB cap is skipped and the previous copy kept; a failed
     * write is silent, since the saved copy is a side-channel to the page it serves.
     */
    suspend fun update(userId: String, change: (MusicSnapshot) -> MusicSnapshot) {
        val seen = clears
        lock.withLock {
            runCatching {
                val json = RaviloWireJson.encodeToString(change(read(userId)?.fresh(nowEpochMs()) ?: MusicSnapshot()))
                // `clear` ran while this write was being worked out: a signed-out profile leaves nothing behind.
                if (!exceedsSnapshotSizeCap(json) && seen == clears) storage.write(userId, json)
            }
        }
    }

    fun clear(userId: String) {
        clears++
        runCatching { storage.delete(userId) }
    }

    companion object {
        val device = MusicSnapshots(MusicSnapshotCache) { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
}

/** One viewer's saved copy, as the music stores see it: what to seed from, and where a first-page answer is written. */
class UserMusicSnapshots(private val userId: String, private val snapshots: MusicSnapshots = MusicSnapshots.device) {
    private val now get() = snapshots.now()

    fun load(): MusicSnapshot? = snapshots.load(userId)

    suspend fun saveHome(home: MusicHome) =
        snapshots.update(userId) { it.copy(home = MusicSnapshot.Saved(now, home)) }

    /** Only ever a first page: [MusicPagedList] calls this from `load()`, never from `more()`. */
    suspend fun saveList(chip: String, sort: String, list: MusicList) =
        snapshots.update(userId) { it.copy(lists = it.lists + (chip to MusicSnapshot.SavedList(sort, now, list))) }

    suspend fun saveGenres(genres: List<MusicGenreCount>) =
        snapshots.update(userId) { it.copy(genres = MusicSnapshot.Saved(now, genres)) }

    suspend fun savePlaylists(playlists: List<MusicPlaylist>) =
        snapshots.update(userId) { it.copy(playlists = MusicSnapshot.Saved(now, playlists)) }
}

/** Listen's loader: starts `Ready(saved)` when there is a fresh copy, then loads quietly and saves each answer. */
fun musicListenLoader(saved: UserMusicSnapshots?, fetch: suspend () -> MusicHome): MusicLoader<MusicHome> {
    val seed = saved?.load()?.home?.value
    if (seed != null) MusicVersions.learn(seed.versionTypes)
    return MusicLoader(seed, saved?.let { s -> { home: MusicHome -> s.saveHome(home) } }, fetch)
}
