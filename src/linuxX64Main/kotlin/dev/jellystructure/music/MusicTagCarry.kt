package dev.jellystructure.music

import dev.jellystructure.audiobooks.AudiobooksIngest
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicTrack

/**
 * DEB-3704 (DEB-3701 FR-7, FR-8) — a jellystructure tag follows its song or album to a new Jellyfin id. Jellyfin derives
 * an item's id from its path, so a Convert, a Lidarr upgrade from MP3 to FLAC, a re-import and a folder rename all
 * give the song a new id and leave the old row behind with `missing_since` set (music rows are never deleted).
 *
 * Pure: it reads one snapshot and answers *which missing row's tags belong on which present row*. It writes nothing;
 * `MusicScanner` hands the answer to [MusicStore.addJsTags] as [MusicJsTagWriter.CARRY], which only ever adds.
 *
 * The first rung that matches wins. A rung with **two** candidates carries nothing and the search stops there (the
 * ambiguous case belongs to FR-8's list), and so does a rung where **two** missing rows reach one present row.
 *
 * DEB-3710 (DEB-3701 FR-12) — books ride the same machinery: [planBooks] and the `books` argument of [gone] apply FR-7's
 * album rungs to a book's folder, then to its author and title.
 */
object MusicTagCarry {
    /** One carry: [from]'s tags that [to] does not have yet, as rows for [to]. */
    class Carry(val from: String, val to: String, val kind: String, val rows: List<MusicJsTagRow>)

    /** A tag that stayed on a missing row with nothing to take it over (FR-8). */
    class Gone(val subjectId: String, val kind: String, val tag: String)

    // ── albums ──

    private fun folder(a: MusicAlbum): String? = a.path?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun artistTitle(a: MusicAlbum): String? =
        a.title.trim().lowercase().takeIf { it.isNotEmpty() }?.let { t -> a.albumArtists.joinToString("&") { it.name.trim().lowercase() } + "|" + t }

    /** Album rungs: the same folder · the same release MBID · else the same release-group MBID · the same album artist and title. */
    private val ALBUM_RUNGS: List<(MusicAlbum) -> String?> = listOf(
        { a -> folder(a) },
        { a -> a.releaseMbid?.takeIf { it.isNotBlank() } },
        { a -> a.releaseGroupMbid?.takeIf { it.isNotBlank() } },
        { a -> artistTitle(a) },
    )

    /** A missing album's FR-7 successor, for every missing album that has exactly one (tags or not). */
    fun albumSuccessors(snap: MusicStore.Snapshot): Map<String, String> {
        val missing = snap.albums.values.filter { it.missingSince != null }
        val present = snap.albums.values.filter { it.missingSince == null }
        return resolve(missing, present, ALBUM_RUNGS.map { k -> { a: MusicAlbum -> listOfNotNull(k(a)) } }, ALBUM_RUNGS) { it.id }
    }

    // ── books (FR-12) ──

    /**
     * A book's folder. A book split off a folder shares it with its siblings, so the split's tag (the id's suffix, which a
     * folder rename does not change) rides along: the two halves of one folder are not "two candidates" for each other.
     */
    private fun folder(b: Audiobook): String? =
        b.folderPath?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { f -> if (AudiobooksIngest.SPLIT in b.id) f + AudiobooksIngest.SPLIT + b.id.substringAfter(AudiobooksIngest.SPLIT) else f }

    private fun authorTitle(b: Audiobook): String? =
        b.title.trim().lowercase().takeIf { it.isNotEmpty() }?.let { t -> b.authors.joinToString("&") { it.trim().lowercase() } + "|" + t }

    /** Book rungs: the same folder · the same author and title (case-folded). */
    private val BOOK_RUNGS: List<(Audiobook) -> String?> = listOf({ b -> folder(b) }, { b -> authorTitle(b) })
    private val BOOK_SOURCE_KEYS: List<(Audiobook) -> List<String>> = BOOK_RUNGS.map { k -> { b: Audiobook -> listOfNotNull(k(b)) } }

    private fun bookPairs(sources: List<Audiobook>, books: AudiobooksStore.Snapshot): Map<String, String> =
        resolve(sources, books.books.values.filter { it.missingSince == null }, BOOK_SOURCE_KEYS, BOOK_RUNGS) { it.id }

    /** FR-12 — every carry for books: a tagged missing book's tags onto the present book that took its place. */
    fun planBooks(snap: MusicStore.Snapshot, books: AudiobooksStore.Snapshot, changed: Set<String>? = null): List<Carry> {
        if (snap.jsTags.isEmpty()) return emptyList()
        val tagged = books.books.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() }
        return bookPairs(tagged, books).mapNotNull { (from, to) -> carryOf(snap, from, to, MusicJsTagRow.BOOK, changed) }
    }

    // ── songs ──

    /** Song rungs. The album-bound ones key on the song's album **or** its successor, so a Convert that also gave the album a new id still matches. */
    private fun songRungs(succ: Map<String, String>): Pair<List<(MusicTrack) -> List<String>>, List<(MusicTrack) -> String?>> {
        fun albums(t: MusicTrack): List<String> = t.albumId?.let { listOfNotNull(it, succ[it]) }.orEmpty()
        fun disc(t: MusicTrack) = t.position?.let { "${t.disc ?: 1}|$it|${t.title.trim().lowercase()}" }
        val source = listOf<(MusicTrack) -> List<String>>(
            { t -> listOfNotNull(t.path?.substringBeforeLast('.', "")?.takeIf { it.isNotBlank() }) },
            { t -> t.recordingMbid?.takeIf { it.isNotBlank() }?.let { m -> albums(t).map { "$it|$m" } }.orEmpty() },
            { t -> disc(t)?.let { d -> albums(t).map { "$it|$d" } }.orEmpty() },
        )
        val target = listOf<(MusicTrack) -> String?>(
            { t -> t.path?.substringBeforeLast('.', "")?.takeIf { it.isNotBlank() } },
            { t -> t.recordingMbid?.takeIf { it.isNotBlank() }?.let { m -> t.albumId?.let { "$it|$m" } } },
            { t -> disc(t)?.let { d -> t.albumId?.let { "$it|$d" } } },
        )
        return source to target
    }

    /**
     * Every carry the snapshot calls for. [changed] limits it to pairs where either row is new or newly missing in this
     * scan (so a tag the owner removed from the new file is not put back by the scan after); null = every pair, which is
     * how FR-8 asks whether a tag was taken over.
     */
    fun plan(snap: MusicStore.Snapshot, changed: Set<String>? = null): List<Carry> {
        if (snap.jsTags.isEmpty()) return emptyList()
        val succ = albumSuccessors(snap)
        val out = ArrayList<Carry>()

        val albumsTagged = snap.albums.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() }
        val albumsPresent = snap.albums.values.filter { it.missingSince == null }
        val albumPairs = resolve(albumsTagged, albumsPresent, ALBUM_RUNGS.map { k -> { a: MusicAlbum -> listOfNotNull(k(a)) } }, ALBUM_RUNGS) { it.id }
        for ((from, to) in albumPairs) carryOf(snap, from, to, MusicJsTagRow.ALBUM, changed)?.let { out += it }

        val (sk, tk) = songRungs(succ)
        val tracksTagged = snap.tracks.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() }
        val tracksPresent = snap.tracks.values.filter { it.missingSince == null }
        val trackPairs = resolve(tracksTagged, tracksPresent, sk, tk) { it.id }
        for ((from, to) in trackPairs) carryOf(snap, from, to, MusicJsTagRow.TRACK, changed)?.let { out += it }
        return out
    }

    private fun carryOf(snap: MusicStore.Snapshot, from: String, to: String, kind: String, changed: Set<String>?): Carry? {
        if (changed != null && from !in changed && to !in changed) return null
        val have = snap.jsTags[to].orEmpty().mapTo(HashSet()) { it.tag }
        val rows = snap.jsTags[from].orEmpty().filter { it.tag !in have }.map { MusicJsTagRow(to, kind, it.tag, it.setAt) }
        return if (rows.isEmpty()) null else Carry(from, to, kind, rows)
    }

    /**
     * FR-8 — every tag on a missing album or song (and, when [books] is given, a missing book) that no present row carries
     * on its behalf.
     */
    fun gone(snap: MusicStore.Snapshot, books: AudiobooksStore.Snapshot? = null): List<Gone> {
        if (snap.jsTags.isEmpty()) return emptyList()
        val taken = HashMap<String, String>()   // missing subject id → the present row it resolves to
        val succ = albumSuccessors(snap)
        fun pairs(from: List<MusicAlbum>) = resolve(from, snap.albums.values.filter { it.missingSince == null }, ALBUM_RUNGS.map { k -> { a: MusicAlbum -> listOfNotNull(k(a)) } }, ALBUM_RUNGS) { it.id }
        taken += pairs(snap.albums.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() })
        val (sk, tk) = songRungs(succ)
        taken += resolve(snap.tracks.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() },
            snap.tracks.values.filter { it.missingSince == null }, sk, tk) { it.id }
        if (books != null) taken += bookPairs(books.books.values.filter { it.missingSince != null && snap.jsTags[it.id].orEmpty().isNotEmpty() }, books)
        val out = ArrayList<Gone>()
        for ((id, rows) in snap.jsTags) {
            val kind = rows.first().kind
            val missing = when (kind) {
                MusicJsTagRow.ALBUM -> snap.albums[id]?.missingSince != null
                MusicJsTagRow.TRACK -> snap.tracks[id]?.missingSince != null
                MusicJsTagRow.BOOK -> books?.books?.get(id)?.missingSince != null
                else -> false
            }
            if (!missing) continue
            val to = taken[id]
            for (r in rows) if (to == null || snap.jsTags[to].orEmpty().none { it.tag == r.tag }) out += Gone(id, kind, r.tag)
        }
        return out
    }

    /**
     * For each source, in rung order: the present rows that share its key on that rung. None → the next rung. Exactly one,
     * and no other source reaching it on that rung → a match. Anything else → nothing, and the search stops there.
     */
    private fun <T> resolve(
        sources: List<T>, targets: List<T>, sourceKeys: List<(T) -> List<String>>, targetKeys: List<(T) -> String?>, id: (T) -> String,
    ): Map<String, String> {
        if (sources.isEmpty() || targets.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val settled = HashSet<String>()   // a source that met candidates on a rung is done, matched or not
        for (r in sourceKeys.indices) {
            val byKey = HashMap<String, MutableList<T>>()
            for (t in targets) targetKeys[r](t)?.let { byKey.getOrPut(it) { ArrayList() } += t }
            val cands = sources.filter { id(it) !in settled }.associateWith { s -> sourceKeys[r](s).flatMap { byKey[it].orEmpty() }.distinctBy(id) }
            val reach = HashMap<String, Int>()
            for (c in cands.values) for (t in c) reach[id(t)] = (reach[id(t)] ?: 0) + 1
            for ((s, c) in cands) {
                if (c.isEmpty()) continue
                settled += id(s)
                if (c.size == 1 && reach[id(c[0])] == 1) out[id(s)] = id(c[0])
            }
        }
        return out
    }
}
