package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/*
 * DEB-2254 (FR-8 to FR-12) — changing a playlist of the viewer's own: remove a song, move one, rename, delete.
 *
 * The page asks; [PlaylistEditor] writes and keeps what the page shows. A write that fails changes nothing on screen
 * (FR-13): the answer of a write is the playlist as it now stands, and the page shows that and nothing else — except a
 * dropped row, which sits where it was dropped until the answer arrives (FR-10).
 */

/** What the page writes through: [TvApiClient] in the app, a stand-in in tests. */
interface PlaylistChanges {
    suspend fun remove(id: String, entryId: String): MusicList
    suspend fun move(id: String, entryId: String, to: Int): MusicList
    suspend fun rename(id: String, name: String): MusicPlaylist
    suspend fun delete(id: String)
}

fun TvApiClient.asPlaylistChanges(): PlaylistChanges = object : PlaylistChanges {
    override suspend fun remove(id: String, entryId: String) = removeFromMusicPlaylist(id, entryId)
    override suspend fun move(id: String, entryId: String, to: Int) = moveInMusicPlaylist(id, entryId, to)
    override suspend fun rename(id: String, name: String) = renameMusicPlaylist(id, name)
    override suspend fun delete(id: String) = deleteMusicPlaylist(id)
}

// ── the pure parts ──

/** FR-8 — the page's ⋯ is for a playlist of the viewer's own, and a shared one has none. */
fun playlistMenuShown(mine: Boolean): Boolean = mine

/** FR-8 — an empty playlist's ⋯ holds *Rename* and *Delete playlist* only: there is nothing to edit. */
fun playlistMenuHasEdit(songs: Int): Boolean = songs > 0

/** FR-9 — a song can be removed when the playlist is the viewer's own and the row carries the entry Jellyfin knows it by. */
fun canRemoveFromPlaylist(mine: Boolean, t: MusicTrackItem): Boolean = mine && t.entryId != null

/** FR-10 — the 0-based index a row dragged by [dragDy] pixels lands on, in a list of [count] rows [rowPx] tall: what `to` carries. */
fun dropIndex(from: Int, dragDy: Float, rowPx: Float, count: Int): Int =
    (from + (dragDy / rowPx).roundToInt()).coerceIn(0, (count - 1).coerceAtLeast(0))

/** The list with the row at [from] taken out and put at [to] — where the page shows a dropped row until the answer arrives. */
fun <T> reordered(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val out = list.toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/**
 * Holds what a playlist's page shows ([loader], kept across navigation) and writes the changes to it.
 * One write at a time, so two quick drops cannot answer out of order.
 */
class PlaylistEditor(
    private val id: String,
    private val loader: MusicLoader<MusicList>,
    private val changes: PlaylistChanges,
) {
    private val gate = Mutex()

    private fun shown(): MusicList? = (loader.state.value as? Load.Ready)?.value

    /** FR-9, FR-10 — take the song at [entryId] out. True when it went; the list then is the server's answer. */
    suspend fun remove(entryId: String): Boolean = gate.withLock {
        try {
            loader.put(changes.remove(id, entryId))
            PlaylistFlow.changed()
            true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /**
     * FR-10 — move the row at [from] to index [to] of the list as shown. The row sits there at once; the answer then
     * replaces the list, and a failure puts the order back. True when it went.
     */
    suspend fun move(from: Int, to: Int): Boolean {
        val before = shown() ?: return false
        val entry = before.tracks.getOrNull(from)?.entryId ?: return false
        if (from == to || to !in before.tracks.indices) return true
        loader.put(before.copy(tracks = reordered(before.tracks, from, to)))
        return gate.withLock {
            try {
                loader.put(changes.move(id, entry, to))
                PlaylistFlow.changed()
                true
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // Back to what the last answer said, not to `before`: an earlier drop may have been answered meanwhile.
                loader.put(shown()?.let { cur -> cur.copy(tracks = restore(cur.tracks, before.tracks)) } ?: before)
                false
            }
        }
    }

    /** The order a failed move returns to: [original]'s order, over whatever songs [now] holds. */
    private fun restore(now: List<MusicTrackItem>, original: List<MusicTrackItem>): List<MusicTrackItem> {
        val held = now.map { it.entryId }.toSet()
        val back = original.filter { it.entryId in held }
        return if (back.size == now.size) back else now
    }

    /** FR-11 — the new name, as the server kept it. Throws what the write threw: a `400` keeps the name sheet open. */
    suspend fun rename(name: String): MusicPlaylist = changes.rename(id, name).also { PlaylistFlow.changed() }

    /** FR-12 — delete it. Throws what the write threw. */
    suspend fun delete() { changes.delete(id); PlaylistFlow.changed() }
}
