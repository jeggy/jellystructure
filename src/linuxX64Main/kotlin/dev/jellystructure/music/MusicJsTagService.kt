package dev.jellystructure.music

import dev.jellystructure.media.JsTagStore
import dev.jellystructure.model.MusicJsTagApplyPreview
import dev.jellystructure.model.MusicJsTagGoneRow
import dev.jellystructure.model.MusicJsTagPreview
import dev.jellystructure.model.TagScope
import dev.jellystructure.nowEpochSec

/** A refusal with the status the route answers: 400 an unknown tag, 404 a subject that is not there, 409 a tag out of scope. */
class MusicJsTagRefused(val status: Int, override val message: String) : RuntimeException(message)

/**
 * DEB-3704 (DEB-3701 FR-3, FR-5, FR-6, FR-8) — the admin's side of jellystructure tags on albums and songs. It checks
 * what [MusicStore]'s writers do not know (that a tag is defined, and that its scope covers Music) and then calls the
 * one writer the change belongs to. Nothing here reads a scan; nothing a scan does reaches here.
 */
class MusicJsTagService(private val store: MusicStore, private val defined: JsTagStore) {

    /** FR-5 — a name that is not defined in `js-tags.json` is never stored; FR-3 — nor one whose scope leaves Music out. */
    private fun check(tag: String) {
        val t = defined.get(tag) ?: throw MusicJsTagRefused(400, "'$tag' is not a jellystructure tag")
        TagScope.refusal(tag, t.scopes, TagScope.MUSIC)?.let { throw MusicJsTagRefused(409, it) }
    }

    private fun subjects(kind: String, ids: Collection<String>, snap: MusicStore.Snapshot = store.snapshot()): List<String> =
        ids.distinct().filter { id ->
            when (kind) {
                MusicJsTagRow.ALBUM -> snap.albums[id]?.missingSince == null && snap.albums.containsKey(id)
                else -> snap.tracks[id]?.missingSince == null && snap.tracks.containsKey(id)
            }
        }

    /** The admin's add: one tag onto some albums or songs. Returns how many were new. */
    suspend fun add(kind: String, ids: Collection<String>, tag: String): Int {
        check(tag)
        val now = nowEpochSec()
        return store.addJsTags(MusicJsTagWriter.ADMIN, subjects(kind, ids).map { MusicJsTagRow(it, kind, tag, now) })
    }

    /** The admin's remove: a manual removal, which invariant #6 allows. A tag outside the scope can always be removed. */
    suspend fun remove(kind: String, ids: Collection<String>, tags: Collection<String>): Int {
        val snap = store.snapshot()
        val present = ids.distinct().filter { if (kind == MusicJsTagRow.ALBUM) snap.albums.containsKey(it) else snap.tracks.containsKey(it) }
        return store.removeJsTags(MusicJsTagWriter.ADMIN, present.flatMap { id -> tags.distinct().map { id to it } })
    }

    /** FR-6 — copy an album's tag onto each of its songs that is not missing; a song that has it is left as it is. */
    suspend fun applyToSongs(albumId: String, tag: String): Int {
        check(tag)
        val snap = store.snapshot()
        val album = snap.albums[albumId] ?: throw MusicJsTagRefused(404, "no such album")
        if (snap.jsTagsOf(albumId).none { it == tag }) throw MusicJsTagRefused(409, "'$tag' is not on ${album.title}")
        val now = nowEpochSec()
        return store.addJsTags(MusicJsTagWriter.APPLY_TO_SONGS, presentSongs(snap, albumId).map { MusicJsTagRow(it.id, MusicJsTagRow.TRACK, tag, now) })
    }

    fun applyPreview(albumId: String, tag: String): MusicJsTagApplyPreview? {
        val snap = store.snapshot()
        if (!snap.albums.containsKey(albumId)) return null
        val songs = presentSongs(snap, albumId)
        return MusicJsTagApplyPreview(songs.size, songs.count { tag in snap.jsTagsOf(it.id) })
    }

    private fun presentSongs(snap: MusicStore.Snapshot, albumId: String) = snap.tracksByAlbum[albumId].orEmpty().filter { it.missingSince == null }

    /** *Tag…* / *Untag…*'s dialog: how many rows are selected and how many carry each tag. */
    fun preview(kind: String, ids: Collection<String>): MusicJsTagPreview {
        val snap = store.snapshot()
        val rows = subjects(kind, ids, snap)
        val counts = HashMap<String, Int>()
        for (id in rows) for (t in snap.jsTagsOf(id)) counts[t] = (counts[t] ?: 0) + 1
        return MusicJsTagPreview(rows.size, counts)
    }

    // ── the tags page ──

    /** Present albums and songs carrying [tag] (the card's count). Missing rows are FR-8's. */
    fun count(tag: String): Int = presentWith(tag).size

    /** FR-4 — present albums and songs carrying [tag] although [scopes] leave Music out. They keep it. */
    fun outside(tag: String, scopes: Collection<String>): List<String> =
        if (scopes.isEmpty() || TagScope.applies(scopes, TagScope.MUSIC)) emptyList() else presentWith(tag)

    private fun presentWith(tag: String): List<String> {
        val snap = store.snapshot()
        return snap.jsTags.filter { (id, rows) ->
            rows.any { it.tag == tag } && when (rows.first().kind) {
                MusicJsTagRow.ALBUM -> snap.albums[id]?.missingSince == null
                MusicJsTagRow.TRACK -> snap.tracks[id]?.missingSince == null
                else -> false
            }
        }.keys.toList()
    }

    /** FR-4's *Remove from the ‹n› outside its scope*, for music. */
    suspend fun removeOutside(tag: String, scopes: Collection<String>): Int =
        store.removeJsTags(MusicJsTagWriter.OUTSIDE_SCOPE, outside(tag, scopes).map { it to tag })

    /** A tag's deletion on the tags page strips it from music too. */
    suspend fun strip(tag: String): Int = store.stripJsTag(MusicJsTagWriter.TAG_DELETE, tag)

    /** FR-8 — tagged rows that are gone and that no carry took over, for one tag (or all of them when [tag] is null). */
    fun gone(tag: String? = null): List<Pair<String, MusicJsTagGoneRow>> {
        val snap = store.snapshot()
        return MusicTagCarry.gone(snap).filter { tag == null || it.tag == tag }.mapNotNull { g ->
            when (g.kind) {
                MusicJsTagRow.ALBUM -> snap.albums[g.subjectId]?.let { a ->
                    g.tag to MusicJsTagGoneRow(a.id, g.kind, a.title, a.albumArtists.joinToString(" & ") { it.name })
                }
                else -> snap.tracks[g.subjectId]?.let { t ->
                    val album = t.albumId?.let { snap.albums[it] }
                    g.tag to MusicJsTagGoneRow(t.id, g.kind, t.title, t.artists.joinToString(" & ") { it.name }, album?.title, album?.id)
                }
            }
        }.sortedWith(compareBy({ it.second.title.lowercase() }, { it.second.id }))
    }
}
