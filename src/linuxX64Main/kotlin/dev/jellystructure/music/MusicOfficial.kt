package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicExtraOrigin
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import dev.jellystructure.model.MusicTrack

/**
 * Phase 305 (FR-305-2..5, dev review 4) — an album's official tracklist, read from MusicBrainz's pressings, and what
 * of the held files sits on it. Pure: the vote, the extras, the gaps, the numbering and the edition's name.
 */
object MusicOfficial {
    /** Dev review 2e — tested on the **primary** type: an Album that is not a Compilation (a live album and a
     *  soundtrack are albums). A Single, an EP, a compilation or a box set never get one. */
    fun isAlbum(primaryType: String?, secondaryTypes: List<String>): Boolean =
        primaryType.equals("album", ignoreCase = true) && secondaryTypes.none { it.equals("compilation", ignoreCase = true) }

    fun isAlbum(a: MusicAlbum): Boolean = isAlbum(a.primaryType, a.secondaryTypes)

    /** FR-305-6 — what is homed under an album: a Single or an EP, by its primary type. */
    fun isSingleOrEp(a: MusicAlbum): Boolean = a.primaryType.equals("single", true) || a.primaryType.equals("ep", true)
    fun isSingle(a: MusicAlbum): Boolean = a.primaryType.equals("single", true)

    private val VIDEO_FORMATS = listOf("dvd", "blu-ray", "vhs", "vcd", "video", "hd-dvd", "umd")

    /** A medium that carries pictures, not songs (a CD+DVD deluxe's second disc). DVD-Audio is sound. */
    fun isVideoMedium(format: String?): Boolean {
        val f = format?.lowercase()?.trim() ?: return false
        if ("audio" in f) return false
        return VIDEO_FORMATS.any { it in f }
    }

    fun isOfficialStatus(status: String?): Boolean = status == null || status.equals("official", ignoreCase = true)

    /** A release's songs in order: every medium flattened, video media and video recordings dropped. */
    fun songsOf(r: MbRelease): List<MusicOfficialSong> {
        val out = ArrayList<MusicOfficialSong>()
        var disc = 0
        for (m in r.media.sortedBy { it.position }) {
            if (isVideoMedium(m.format)) continue
            disc++
            for (t in m.tracks.sortedBy { it.position }) {
                val rec = t.recording ?: continue
                if (rec.video || rec.id.isBlank()) continue
                out += MusicOfficialSong(rec.id, t.title.ifBlank { rec.title }, t.length ?: rec.length, disc, t.position)
            }
        }
        return out
    }

    private fun audioMedia(r: MbRelease) = r.media.count { !isVideoMedium(it.format) }.coerceAtLeast(1)

    /** `YYYY` · `YYYY-MM` · `YYYY-MM-DD` sorts as text; an undated pressing sorts last. */
    private fun dateKey(d: String?) = d?.takeIf { it.length >= 4 } ?: "9999"

    /**
     * FR-305-2 (dev review 4) — the vote on the **set** of recording ids over the group's official pressings. The most
     * shared set wins; **a tie goes to the smaller set, then to the earliest**. Order, discs and titles come from the
     * earliest pressing carrying the winning set. Null for anything that is not an album (dev review 2e) and for a
     * group with no official pressing that has songs.
     */
    fun vote(group: MbReleaseGroup, releases: List<MbRelease>, now: Long = 0): MusicOfficialList? {
        if (!isAlbum(group.primaryType, group.secondaryTypes)) return null
        val official = releases.filter { isOfficialStatus(it.status) }
        val withSongs = official.map { it to songsOf(it) }.filter { it.second.isNotEmpty() }
        if (withSongs.isEmpty()) return null
        val bySet = withSongs.groupBy { (_, songs) -> songs.map { it.recordingMbid }.toSet() }
        val winner = bySet.entries.sortedWith(
            compareByDescending<Map.Entry<Set<String>, List<Pair<MbRelease, List<MusicOfficialSong>>>>> { it.value.size }
                .thenBy { it.key.size }
                .thenBy { e -> e.value.minOf { dateKey(it.first.date) } },
        ).first()
        val (release, songs) = winner.value.minWith(compareBy<Pair<MbRelease, List<MusicOfficialSong>>> { dateKey(it.first.date) }.thenBy { it.first.id })
        return MusicOfficialList(
            songs = songs, releaseMbid = release.id, releaseTitle = release.title, k = winner.value.size, m = official.size,
            groupMbid = group.id.ifBlank { null }, groupTitle = group.title.ifBlank { null }, media = audioMedia(release), readAt = now,
        )
    }

    /** FR-305-4 — one pressing as the official tracklist (the owner's pick). */
    fun fromRelease(r: MbRelease, groupMbid: String?, groupTitle: String?, now: Long = 0): MusicOfficialList =
        MusicOfficialList(songsOf(r), r.id, r.title, 1, 1, groupMbid, groupTitle, audioMedia(r), now)

    /** The tracklist an album shows: the owner's pick, else the vote. Never for an unmatched album (FR-305-5). */
    fun effective(a: MusicAlbum): MusicOfficialList? =
        if (a.matchState != MusicMatch.MATCHED || a.releaseGroupMbid == null) null else a.userOfficial ?: a.official

    /**
     * The tracklist an album's held files can be laid against: [effective], but only when at least one held file carries
     * a recording id. Found on the dev stack 2026-10-05: 82 matched albums (~1 460 files) were never mapped track by
     * track, so dev review 3's "no recording id ⇒ extra" turned every file into an extra (*0 songs + 54 extras*) and put a
     * Bonus chip on songs that are on the official album. With nothing to compare, the album is today's plain list
     * (FR-305-5) and none of its files is an extra. An album with some ids keeps dev review 3 for the rest.
     */
    fun usable(a: MusicAlbum, tracks: List<MusicTrack>): MusicOfficialList? =
        effective(a)?.takeIf { tracks.any { it.recordingMbid != null } }

    /** FR-305-3 / dev review 3 — a held track is an extra when its recording is not on the official list, or it has
     *  none (it is not shown to be on the official album). Whatever its recording state (dev review 2a). */
    fun isExtra(official: MusicOfficialList?, t: MusicTrack): Boolean {
        official ?: return false
        val rec = t.recordingMbid ?: return true
        return rec !in official.recordings
    }

    /** One medium: `1`…`n`; more than one: `disc·track`, as on the official release. */
    fun number(official: MusicOfficialList, index: Int): String {
        val s = official.songs[index]
        return if (official.media > 1) "${s.disc}·${s.position}" else "${index + 1}"
    }

    /** What the Tracks tab lays out (FR-305-12): the official rows in order (held copies), the gaps, the extras. */
    data class Layout(
        val official: MusicOfficialList?,
        /** (index in the official list, held track) — in the official order. */
        val rows: List<Pair<Int, MusicTrack>>,
        /** Official songs the held files lack: (index, song). */
        val gaps: List<Pair<Int, MusicOfficialSong>>,
        /** In the held edition's own order. */
        val extras: List<MusicTrack>,
    )

    /** FR-305-3/5 — [tracks] in the files' order. An unmatched album (or one with no list yet) is today's plain list. */
    fun layout(a: MusicAlbum, tracks: List<MusicTrack>): Layout {
        val official = usable(a, tracks) ?: return Layout(null, emptyList(), emptyList(), tracks)
        val index = official.songs.withIndex().associate { it.value.recordingMbid to it.index }
        val rows = ArrayList<Pair<Int, MusicTrack>>()
        val extras = ArrayList<MusicTrack>()
        for (t in tracks) {
            val i = t.recordingMbid?.let { index[it] }
            if (i == null) extras += t else rows += i to t
        }
        val held = rows.mapTo(HashSet()) { it.first }
        val gaps = official.songs.withIndex().filter { it.index !in held }.map { it.index to it.value }
        return Layout(official, rows.sortedBy { it.first }, gaps, extras)
    }

    /**
     * Owner decision 3 — the extras section's name: the earliest pressing's own title when it differs from the group's
     * (*Signal Found 20th Anniversary*), else its disambiguation as it is (*super deluxe*), else its country code (the
     * page says the country in its own language), else nothing (*Extras*).
     */
    data class Edition(val title: String? = null, val country: String? = null)

    fun edition(groupTitle: String?, origin: MusicExtraOrigin?): Edition {
        origin ?: return Edition()
        val t = origin.title.trim()
        if (t.isNotEmpty() && !t.equals(groupTitle?.trim(), ignoreCase = true)) return Edition(title = t)
        origin.disambiguation?.trim()?.takeIf { it.isNotEmpty() }?.let { return Edition(title = it) }
        origin.country?.trim()?.takeIf { it.isNotEmpty() && it != "XW" && it != "XE" }?.let { return Edition(country = it) }
        return Edition()
    }

    /** The first extra's origin decides the section's name (the held edition's first extra). */
    fun editionOf(a: MusicAlbum, extras: List<MusicTrack>): Edition {
        val official = effective(a) ?: return Edition()
        val origin = extras.firstNotNullOfOrNull { t -> t.recordingMbid?.let { a.extraOrigins[it] } }
        return edition(official.groupTitle ?: a.title, origin)
    }

    /** FR-305-3 — per recording, the earliest dated official pressing that carries it. */
    fun firstReleases(releases: List<MbRelease>, recordings: Set<String>): Map<String, MusicExtraOrigin> {
        val out = HashMap<String, Pair<String, MusicExtraOrigin>>()
        for (r in releases.filter { isOfficialStatus(it.status) }) {
            val key = dateKey(r.date)
            for (s in songsOf(r)) {
                if (s.recordingMbid !in recordings) continue
                val had = out[s.recordingMbid]
                if (had == null || key < had.first) out[s.recordingMbid] = key to origin(r)
            }
        }
        return out.mapValues { it.value.second }
    }

    fun origin(r: MbRelease) = MusicExtraOrigin(
        releaseMbid = r.id, title = r.title, date = r.date?.takeIf { it.isNotBlank() }, country = r.country?.takeIf { it.isNotBlank() },
        disambiguation = r.disambiguation?.trim()?.takeIf { it.isNotEmpty() },
        format = r.media.mapNotNull { it.format }.distinct().joinToString(" + ").ifBlank { null },
    )

    /** *first on the Japanese CD, 2006* — the admin's English (FR-305-12). */
    fun firstOnText(o: MusicExtraOrigin): String {
        val country = o.country?.let { MusicCountries.adjective(it) }
        val what = when {
            country != null && o.format != null -> "the $country ${o.format}"
            o.format != null && o.title.isBlank() -> "the ${o.format}"
            else -> o.title.ifBlank { "another pressing" }
        }
        return listOfNotNull(what, o.date?.take(4)).joinToString(", ")
    }

    /** What a pressing has against the official list: *same as the official* · *+1 · −0*. */
    fun against(official: MusicOfficialList?, songs: List<MusicOfficialSong>): String {
        official ?: return ""
        val set = songs.map { it.recordingMbid }.toSet()
        val plus = (set - official.recordings).size
        val minus = (official.recordings - set).size
        return if (plus == 0 && minus == 0) "same as the official" else "+$plus · −$minus"
    }
}

/** Country names for the admin's English (*Extras · Japan*, *the Japanese CD*). Ravilo has its own per language. */
object MusicCountries {
    private val NAMES = mapOf(
        "JP" to ("Japan" to "Japanese"), "US" to ("United States" to "US"), "GB" to ("United Kingdom" to "UK"),
        "DE" to ("Germany" to "German"), "FR" to ("France" to "French"), "DK" to ("Denmark" to "Danish"),
        "FO" to ("Faroe Islands" to "Faroese"), "NO" to ("Norway" to "Norwegian"), "SE" to ("Sweden" to "Swedish"),
        "FI" to ("Finland" to "Finnish"), "IS" to ("Iceland" to "Icelandic"), "NL" to ("Netherlands" to "Dutch"),
        "CA" to ("Canada" to "Canadian"), "AU" to ("Australia" to "Australian"), "IT" to ("Italy" to "Italian"),
        "ES" to ("Spain" to "Spanish"), "KR" to ("South Korea" to "Korean"), "BR" to ("Brazil" to "Brazilian"),
        "XE" to ("Europe" to "European"), "XW" to ("Worldwide" to "worldwide"),
    )
    fun name(code: String): String = NAMES[code.uppercase()]?.first ?: code.uppercase()
    fun adjective(code: String): String? = NAMES[code.uppercase()]?.second
}
