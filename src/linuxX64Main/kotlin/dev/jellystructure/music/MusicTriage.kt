package dev.jellystructure.music

import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicFormats
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack

/**
 * Phase 293 (FR-293-1) — one predicate per music and audiobooks triage key. The Dashboard's count
 * (`musicTriageCounts` / `audiobookTriageCounts`) and the Library's list (`filter=<key>` on the browse routes) both
 * ask this object, so the row's number and the page it opens can never disagree.
 *
 * [unit] says which view the key opens (FR-293-3): an album key opens Albums, an artist key Artists, a song key Songs;
 * audiobooks have one view. [label] is the Dashboard row's label, which is also the Library's chip (FR-293-4).
 */
object MusicTriage {
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val SONG = "song"
    const val BOOK = "book"

    data class Key(val key: String, val unit: String, val label: String)

    val MUSIC: Map<String, Key> = listOf(
        Key("music_needs_match", ALBUM, "Albums need a match"),
        Key("music_no_cover", ALBUM, "Albums without a cover"),
        Key("music_shared_album", ALBUM, "One album in several folders"),
        Key("music_folder_disagrees", ALBUM, "Folder and songs disagree"),
        Key("music_no_picture", ARTIST, "Artists without a picture"),
        Key("music_reencodes", SONG, "Songs a phone plays only by re-encoding"),
        Key("music_files_no_ids", SONG, "Songs whose files don’t say what they are"),
        // Phase 292 (FR-292-15) — songs with no singing that still have lyrics beside them (no-words pieces included,
        // which no Version facet value can reach: they are *No version*).
        Key(INSTRUMENTAL_LYRICS, SONG, "Lyrics on an instrumental"),
    ).associateBy { it.key }

    const val INSTRUMENTAL_LYRICS = "music_instrumental_lyrics"

    val AUDIOBOOKS: Map<String, Key> = listOf(
        Key("audiobooks_missing_part", BOOK, "A part is missing"),
        Key("audiobooks_two_in_one", BOOK, "Folder holds two books"),
        Key("audiobooks_no_cover", BOOK, "No cover"),
        Key("audiobooks_no_narrator", BOOK, "No narrator"),
    ).associateBy { it.key }

    /** The music view a key opens: `albums` · `artists` · `songs`; null for a key this object does not know. */
    fun viewOf(key: String?): String? = when (MUSIC[key]?.unit) {
        ALBUM -> MusicBrowse.ALBUMS; ARTIST -> MusicBrowse.ARTISTS; SONG -> MusicBrowse.SONGS; else -> null
    }

    /**
     * The predicates over one snapshot. [roots] are the music libraries' folders (283's flags need them).
     */
    class Music(private val snap: MusicStore.Snapshot, private val roots: Set<String>) {
        private val liveAlbums by lazy { snap.albums.values.filter { it.missingSince == null } }
        private val flags by lazy { MusicFlags.of(liveAlbums, roots) }

        fun album(key: String, a: MusicAlbum): Boolean = when (key) {
            "music_needs_match" -> !a.matchLocked && a.matchState != MusicMatch.MATCHED
            "music_no_cover" -> a.matchState == MusicMatch.MATCHED && a.coverState == MusicArt.NONE
            "music_shared_album" -> flags[a.id].orEmpty().any { it.kind == MusicFlags.SHARED }
            "music_folder_disagrees" -> flags[a.id].orEmpty().any { it.kind == MusicFlags.FOLDER }
            else -> false
        }

        fun artist(key: String, r: MusicArtist): Boolean = when (key) {
            // An artist known only as a credit has no folder to put a picture in — not a work item.
            "music_no_picture" -> r.path != null && r.imageState == MusicArt.NONE
            else -> false
        }

        fun song(key: String, t: MusicTrack): Boolean = when (key) {
            "music_reencodes" -> MusicFormats.reencodesOnPhone(t.container, t.codec)
            "music_files_no_ids" -> t.albumId?.let { snap.albums[it] }?.matchState == MusicMatch.MATCHED &&
                t.jellyfinProviderIds.keys.none { k -> k.startsWith("MusicBrainz") }
            INSTRUMENTAL_LYRICS -> snap.versions.lyricsOnNoSinging(t)
            else -> false
        }

        /** The Dashboard's number for [key]: live rows only, the same rows the browse lists. */
        fun count(key: String): Int = when (MUSIC[key]?.unit) {
            ALBUM -> liveAlbums.count { album(key, it) }
            ARTIST -> snap.artists.values.count { it.missingSince == null && artist(key, it) }
            SONG -> snap.tracks.values.count { it.missingSince == null && song(key, it) }
            else -> 0
        }

        /** The albums the song rows of [key] sit on — the triage list's *titles*. */
        fun songAlbums(key: String): Int = snap.tracks.values.filter { it.missingSince == null && song(key, it) }.mapNotNull { it.albumId }.distinct().size
    }

    /** The audiobooks' predicates — the same as [dev.jellystructure.audiobooks.AudiobooksBrowse.flag]'s and the facets'. */
    fun book(key: String, b: Audiobook): Boolean = when (key) {
        "audiobooks_missing_part" -> b.gap.isNotEmpty() && !b.gapDismissed
        "audiobooks_two_in_one" -> b.albumTags.size > 1 && !b.twoInOneDismissed
        "audiobooks_no_cover" -> b.coverState == MusicArt.NONE
        "audiobooks_no_narrator" -> b.narrators.isEmpty()
        else -> false
    }

    fun bookCount(key: String, snap: AudiobooksStore.Snapshot): Int = snap.books.values.count { it.missingSince == null && book(key, it) }
}
