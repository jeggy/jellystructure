package dev.jellystructure.music

import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionBulkPreview
import dev.jellystructure.model.MusicVersionCopy
import dev.jellystructure.model.MusicVersionCount
import dev.jellystructure.model.MusicVersionOf
import dev.jellystructure.model.MusicVersionPanelDto
import dev.jellystructure.model.MusicVersionPanelRow
import dev.jellystructure.model.MusicVersionTypeDto
import dev.jellystructure.model.MusicVersionTypePatch
import dev.jellystructure.model.MusicVersionTypesDto
import dev.jellystructure.model.MusicVersions
import dev.jellystructure.nowEpochSec

/**
 * Phase 292 — the admin's side of a song's version: the side panel, a tick, *Back to automatic*, *Set version…* on a
 * selection, Metadata → Versions and the artist doorway. Every answer comes from [MusicVersions.of] (through the
 * snapshot's index); every change is the owner's own row, written for the song's key so every copy of the recording
 * follows (FR-292-2), and recorded in the History of each album that holds a copy.
 */
class MusicVersionService(private val store: MusicStore, private val history: MediaHistory? = null) {

    // ── the colours and the chips ──

    fun chipTypes(snap: MusicStore.Snapshot = store.snapshot()): List<MusicVersionTypeDto> =
        snap.versionTypes.map { MusicVersionTypeDto(it.key, it.name, it.chip, it.color, it.meaning, it.foundFrom) }

    /** Metadata → Versions (FR-292-13): counts use the filter's reading, so *116 songs →* opens 116 songs. */
    fun types(): MusicVersionTypesDto {
        val snap = store.snapshot()
        val live = snap.tracks.values.filter { it.missingSince == null }
        val answers = live.map { snap.versions.of(it) }
        val byKey = live.groupBy { MusicVersions.keyOf(it) }
        fun choiceSongs(type: String, on: Boolean) = snap.choices.entries.filter { (_, m) -> m[type]?.on == on }.sumOf { (k, _) -> byKey[k]?.size ?: 0 }
        return MusicVersionTypesDto(
            types = snap.versionTypes.map { t ->
                MusicVersionTypeDto(t.key, t.name, t.chip, t.color, t.meaning, t.foundFrom,
                    songs = answers.count { it.matches(t.key) }, setByYou = choiceSongs(t.key, true), removedByYou = choiceSongs(t.key, false))
            },
            noVersion = answers.count { it.matches(MusicVersions.NONE) },
            palette = MusicVersions.PALETTE,
        )
    }

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /** Saved as the owner edits. An unknown key or a colour that is not `#rrggbb` is refused (null). */
    suspend fun patchType(p: MusicVersionTypePatch): MusicVersionTypesDto? {
        if (MusicVersions.info(p.key) == null) return null
        if (p.color != null && !HEX.matches(p.color)) return null
        store.putVersionType(p.key, p.color?.lowercase(), p.meaning?.trim()?.take(200))
        return types()
    }

    // ── the panel (FR-292-7) ──

    fun panel(trackId: String): MusicVersionPanelDto? {
        val snap = store.snapshot()
        val t = snap.tracks[trackId] ?: return null
        val a = snap.versions.of(t)
        val album = t.albumId?.let { snap.albums[it] }
        val others = snap.versions.copies(t).filter { it.id != t.id && it.albumId != t.albumId }.mapNotNull { c -> c.albumId?.let { snap.albums[it] } }
            .distinctBy { it.id }.map { MusicVersionCopy(it.id, it.title) }
        val facts = MusicVersions.recordingOf(a.key)?.let { snap.facts[it] }
        val instOf = facts?.instrumentalOf?.takeIf { MusicVersions.INSTRUMENTAL in a.shown }?.let { target ->
            val inLib = snap.tracks.values.firstOrNull { it.missingSince == null && it.recordingMbid == target.mbid }
            if (inLib != null) MusicVersionOf(inLib.id, inLib.albumId, inLib.title, inLib.artists.firstOrNull()?.name)
            else MusicVersionOf(null, null, target.title, target.artist)
        }
        return MusicVersionPanelDto(
            trackId = t.id, title = t.title, artist = t.artists.joinToString(" & ") { it.name }, album = album?.title, albumId = album?.id,
            position = t.position, matched = a.key.startsWith("rec:"),
            rows = snap.versionTypes.map { ty ->
                MusicVersionPanelRow(ty.key, ty.name, ty.meaning, ty.color, on = ty.key in a.shown, sources = a.sources[ty.key].orEmpty(), removed = ty.key in a.removed)
            },
            noWords = a.noWords, hasChoices = a.hasChoices, otherAlbums = others,
            lyricsBesideNoSinging = snap.versions.lyricsOnNoSinging(t), instrumentalOf = instOf,
        )
    }

    /** A tick in the panel: saved at once (*Saved as you tick · kept across runs*). */
    suspend fun set(trackId: String, type: String, on: Boolean): MusicVersionPanelDto? {
        if (MusicVersions.info(type) == null) return null
        val t = store.track(trackId) ?: return null
        apply(listOf(t), if (on) setOf(type) else emptySet(), if (on) emptySet() else setOf(type))
        return panel(trackId)
    }

    /** *Back to automatic*: every tick on this recording goes. */
    suspend fun reset(trackId: String): MusicVersionPanelDto? {
        val snap = store.snapshot()
        val t = snap.tracks[trackId] ?: return null
        val key = MusicVersions.keyOf(t)
        val before = snap.versions.of(t)
        store.clearChoices(listOf(key))
        afterChange(snap, mapOf(key to before), "Version of “${t.title}” back to automatic")
        return panel(trackId)
    }

    // ── *Set version…* (FR-292-8, FR-292-12) ──

    fun preview(trackIds: Collection<String>): MusicVersionBulkPreview {
        val snap = store.snapshot()
        val ts = trackIds.distinct().mapNotNull { snap.tracks[it] }
        val sel = ts.map { it.id }.toSet()
        val selAlbums = ts.mapNotNull { it.albumId }.toSet()
        val copies = ts.flatMap { snap.versions.copies(it) }.distinctBy { it.id }.filter { it.id !in sel }
        return MusicVersionBulkPreview(
            songs = ts.size,
            counts = MusicVersions.KEYS.associateWith { k -> ts.count { k in snap.versions.of(it).shown } },
            otherCopies = copies.size,
            otherAlbums = copies.mapNotNull { it.albumId }.filter { it !in selAlbums }.distinct().size,
        )
    }

    /** Apply writes the owner's rows for every recording in the selection, each once, in one transaction. */
    suspend fun bulk(trackIds: Collection<String>, add: Set<String>, remove: Set<String>): String {
        val snap = store.snapshot()
        val ts = trackIds.distinct().mapNotNull { snap.tracks[it] }
        if (ts.isEmpty()) return "No songs selected"
        val n = apply(ts, add, remove)
        return "Version set on ${ts.size} song${if (ts.size == 1) "" else "s"}" + if (n > ts.size) " · $n with the copies on other albums" else ""
    }

    /** Writes the plan for each distinct key of [tracks]; returns how many songs (copies included) it reaches. */
    private suspend fun apply(tracks: List<MusicTrack>, add: Set<String>, remove: Set<String>): Int {
        val snap = store.snapshot()
        val byKey = tracks.groupBy { MusicVersions.keyOf(it) }
        val before = byKey.mapValues { (_, l) -> snap.versions.of(l.first()) }
        val plans = before.mapValues { (_, a) -> MusicVersions.plan(a, add, remove) }.filterValues { it.isNotEmpty() }
        if (plans.isNotEmpty()) store.setChoices(plans, nowEpochSec())
        val words = (add.map { "+ ${MusicVersions.info(it)?.name ?: it}" } + remove.map { "− ${MusicVersions.info(it)?.name ?: it}" }).joinToString(", ")
        val what = if (tracks.size == 1) "Version of “${tracks.first().title}”: $words" else "Version set on ${tracks.size} songs: $words"
        afterChange(snap, before, what)
        return byKey.keys.sumOf { k -> byKey[k]!!.first().let { snap.versions.copies(it).size } }
    }

    /**
     * After a change: a song that is no longer Instrumental is looked up again on the next lyrics run (its 30-day
     * wait cleared — dev review 8c; a song whose lyrics the admin removed stays blocked), and every album holding a
     * copy says what happened in its History (dev review 6).
     */
    private suspend fun afterChange(before: MusicStore.Snapshot, answers: Map<String, MusicVersions.Answer>, sentence: String) {
        val after = store.snapshot()
        val reset = ArrayList<MusicTrack>()
        val albums = LinkedHashSet<String>()
        for ((key, was) in answers) {
            val copies = before.tracks.values.filter { it.missingSince == null && MusicVersions.keyOf(it) == key }
            copies.mapNotNullTo(albums) { it.albumId }
            val now = copies.firstOrNull()?.let { after.tracks[it.id] }?.let { after.versions.of(it) } ?: continue
            if (was.blocksLyrics && !now.blocksLyrics)
                copies.mapNotNull { after.tracks[it.id] }.filter { it.lyricsState != MusicLyrics.BLOCKED && it.lyricsCheckedAt != null }
                    .forEach { reset += it.copy(lyricsCheckedAt = null) }
        }
        if (reset.isNotEmpty()) store.putTracks(reset)
        albums.forEach { runCatching { history?.record(it, "music_versions", sentence) } }
    }

    // ── the artist doorway (FR-292-14) ──

    fun artistCounts(artistId: String): List<MusicVersionCount> {
        val snap = store.snapshot()
        val songs = snap.tracks.values.filter { t ->
            t.missingSince == null && (t.artists.any { it.artistId == artistId } || t.albumId?.let { snap.albums[it] }?.albumArtists?.any { it.artistId == artistId } == true)
        }
        val answers = songs.map { snap.versions.of(it) }
        return MusicVersions.KEYS.mapNotNull { k ->
            val n = answers.count { it.matches(k) }
            if (n == 0) null else MusicVersionCount(k, MusicVersions.countLabel(k, n), n)
        }
    }
}
