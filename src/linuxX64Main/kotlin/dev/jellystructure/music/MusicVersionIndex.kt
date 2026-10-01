package dev.jellystructure.music

import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersions

/**
 * Phase 292 — every song's version answer over one [MusicStore.Snapshot], through [MusicVersions.of] and nothing
 * else. Built once per snapshot ([MusicStore.Snapshot.versions]); a write replaces the snapshot and with it this.
 *
 * Copies are the live songs that share a key: the same recording on an album, a best-of and a box set.
 */
class MusicVersionIndex(private val snap: MusicStore.Snapshot) {
    private val byKey: Map<String, List<MusicTrack>> by lazy {
        snap.tracks.values.filter { it.missingSince == null }.groupBy { MusicVersions.keyOf(it) }
    }
    /** One answer per key, computed once (a `lazy` is safe to read from many request threads; a HashMap filled on
     *  read is not). */
    private val answers: Map<String, MusicVersions.Answer> by lazy {
        byKey.mapValues { (k, copies) -> answer(k, copies.first(), copies) }
    }

    private fun answer(k: String, t: MusicTrack, copies: List<MusicTrack>) =
        MusicVersions.of(t, copies, MusicVersions.recordingOf(k)?.let { snap.facts[it] }, snap.choices[k].orEmpty())

    fun key(t: MusicTrack): String = MusicVersions.keyOf(t)

    /** Every live song holding the same recording as [t] (itself included). */
    fun copies(t: MusicTrack): List<MusicTrack> = byKey[key(t)] ?: listOf(t)

    /** [t]'s answer; a song no longer in the library is answered on its own. */
    fun of(t: MusicTrack): MusicVersions.Answer = answers[key(t)] ?: answer(key(t), t, listOf(t))

    /** What the Library's *Lyrics* facet calls *has lyrics*: our sidecar, or Jellyfin found some. */
    fun hasLyrics(t: MusicTrack): Boolean = t.lyricsState == MusicLyrics.SYNCED || t.lyricsState == MusicLyrics.PLAIN || t.hasLyrics

    /**
     * FR-292-15 / dev review 8e — the Dashboard's *Lyrics on an instrumental*: the song has lyrics (our sidecar or
     * Jellyfin's flag), has no singing (Instrumental from any source, or a piece never sung), and the admin has not
     * pressed *Remove the lyrics* for it.
     */
    fun lyricsOnNoSinging(t: MusicTrack): Boolean = t.lyricsState != MusicLyrics.BLOCKED && hasLyrics(t) && of(t).blocksLyrics

    /** Dev review 8d — a song a viewer is shown no lyrics for, whatever Jellyfin holds. */
    fun lyricsHidden(t: MusicTrack): Boolean = t.lyricsState == MusicLyrics.BLOCKED || of(t).blocksLyrics
}
