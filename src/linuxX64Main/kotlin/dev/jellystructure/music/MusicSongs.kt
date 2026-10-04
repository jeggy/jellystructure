package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTitleVersions
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersions
import dev.jellystructure.model.originalYear

/**
 * Phase 305 (FR-305-10, owner decision 2, dev review 8) — which copy of a song is shown and played: **bitrate first**
 * (a copy a phone re-encodes is not ranked lower), then bit depth, then sample rate; on a tie the album's official
 * copy, the album's extra, a single's, a compilation's or box set's, a live album's; then the earliest added.
 * An unknown bitrate sorts below every known one.
 */
object MusicCopyRank {
    const val OFFICIAL = 0
    const val EXTRA = 1
    const val SINGLE = 2
    const val COMPILATION = 3
    const val LIVE = 4

    /** Dev review 8 — what the copies panel calls lossless. Shown only; not a rank key (owner decision 2). */
    private val LOSSLESS = setOf("flac", "alac", "wav", "ape", "wavpack", "tta")
    fun lossless(codec: String?, container: String?): Boolean {
        val c = codec?.lowercase()
        return c in LOSSLESS || c?.startsWith("pcm_") == true || (c == null && container?.lowercase() in setOf("flac", "wav"))
    }

    data class Copy(val track: MusicTrack, val kind: Int)

    val comparator: Comparator<Copy> = compareBy<Copy> { -(it.track.bitrate?.takeIf { b -> b > 0 } ?: -1) }
        .thenBy { -(it.track.bitDepth ?: -1) }
        .thenBy { -(it.track.sampleRate ?: -1) }
        .thenBy { it.kind }
        .thenBy { it.track.addedAt ?: it.track.createdAt.takeIf { c -> c > 0 } ?: Long.MAX_VALUE }
        .thenBy { it.track.id }

    /** Negative when [a] is the better copy. */
    fun compare(a: Copy, b: Copy): Int = comparator.compare(a, b)

    /** The tie order's kind of one copy (owner: album · extra · single · compilation/box set · live). */
    fun kindOf(album: MusicAlbum?, extra: Boolean): Int = when {
        album == null -> OFFICIAL
        else -> when (MusicBrowse.albumType(album)) {
            "live" -> LIVE
            "compilation" -> COMPILATION
            "single" -> SINGLE
            else -> if (extra) EXTRA else OFFICIAL
        }
    }

    fun kindName(k: Int): String = when (k) { EXTRA -> "extra"; SINGLE -> "single"; COMPILATION -> "compilation"; LIVE -> "live"; else -> "album" }
}

/**
 * Phase 305 (FR-305-6, dev review 4) — the album a single or EP lives under: MusicBrainz's *single from*, then a remix
 * of an album recording (following the hop through the single the remix points at), then — singles only — the same
 * base title by the same artist within two years; the owner's *Move to…* wins over all three.
 */
object MusicSingleHome {
    const val MB = "mb_single_from"
    const val REMIX = "via_remix"
    const val TITLE = "by_title"
    const val USER = "user"

    /** [albumId] null with [how] = [USER] is *No album*. */
    data class Home(val albumId: String?, val how: String)

    /** The owner's row: [albumId] null = *No album*. */
    data class Override(val albumId: String?, val setAt: Long = 0)

    /** The library as the rules read it: albums that may be a home (with their official lists), held singles, and
     *  292's facts. */
    class Library(
        val albums: List<MusicAlbum>,
        val singles: List<MusicAlbum>,
        val tracksOf: (String) -> List<MusicTrack>,
        val facts: Map<String, MusicRecordingFacts>,
    )

    /** The artist a release is credited to: MusicBrainz's ids, else Jellyfin's. */
    fun artistKeys(a: MusicAlbum): Set<String> =
        a.mbArtists.map { "mb:" + it.mbid }.toSet().ifEmpty { a.albumArtists.map { "jf:" + it.artistId }.toSet() }

    private fun sameArtist(a: MusicAlbum, b: MusicAlbum): Boolean {
        val x = artistKeys(a); val y = artistKeys(b)
        if (x.intersect(y).isNotEmpty()) return true
        // One side matched and the other not: compare Jellyfin's credits.
        val jx = a.albumArtists.map { it.artistId }.toSet(); val jy = b.albumArtists.map { it.artistId }.toSet()
        return jx.isNotEmpty() && jx.intersect(jy).isNotEmpty()
    }

    /** 292's title-finder's normalisation: brackets and a dashed tail dropped, letters and digits only. */
    fun baseTitle(title: String?): String {
        var t = title.orEmpty()
        t = Regex("\\([^)]*\\)|\\[[^]]*]").replace(t, " ")
        t = Regex("\\s[-–—]\\s.*$").replace(t, " ")
        return t.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun recordingsOf(a: MusicAlbum, lib: Library): Set<String> =
        (MusicOfficial.effective(a)?.recordings.orEmpty()) + lib.tracksOf(a.id).mapNotNull { it.recordingMbid }

    fun resolve(single: MusicAlbum, lib: Library, override: Override?): Home? {
        if (override != null) return Home(override.albumId, USER)
        if (!MusicOfficial.isSingleOrEp(single)) return null
        val candidates = lib.albums.filter { it.id != single.id && it.missingSince == null && MusicOfficial.isAlbum(it) && sameArtist(single, it) }
        if (candidates.isEmpty()) return null
        // Rule 1 — MusicBrainz's *single from*.
        single.singleFrom?.let { rg -> candidates.firstOrNull { it.releaseGroupMbid == rg }?.let { return Home(it.id, MB) } }
        // Rule 2 — a remix (or edit) of an album recording; or of a held single's recording, then that single's album.
        val tracks = lib.tracksOf(single.id)
        for (t in tracks) {
            val target = t.recordingMbid?.let { lib.facts[it]?.remixOf?.mbid } ?: continue
            candidates.firstOrNull { target in recordingsOf(it, lib) }?.let { return Home(it.id, REMIX) }
            val via = lib.singles.firstOrNull { s -> s.id != single.id && lib.tracksOf(s.id).any { it.recordingMbid == target } }
            via?.singleFrom?.let { rg -> candidates.firstOrNull { it.releaseGroupMbid == rg }?.let { return Home(it.id, REMIX) } }
        }
        // Rule 3 — singles only (dev review 4): the A-side's base title on an official song, within two years.
        if (!MusicOfficial.isSingle(single)) return null
        val aSide = tracks.minWithOrNull(compareBy({ it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE })) ?: return null
        val base = baseTitle(aSide.title).takeIf { it.isNotEmpty() } ?: return null
        val year = single.originalYear() ?: return null
        return candidates.filter { a ->
            val ay = a.originalYear() ?: return@filter false
            kotlin.math.abs(ay - year) <= 2 && (MusicOfficial.effective(a)?.songs.orEmpty().any { baseTitle(it.title) == base } ||
                lib.tracksOf(a.id).any { baseTitle(it.title) == base && !MusicOfficial.isExtra(MusicOfficial.effective(a), it) })
        }.minWithOrNull(compareBy({ kotlin.math.abs((it.originalYear() ?: 0) - year) }, { it.originalYear() ?: 0 }))?.let { Home(it.id, TITLE) }
    }
}

/**
 * Phase 305 (FR-305-9, dev review 6) — two full-length raw Chromaprint fingerprints against each other: the best
 * alignment within ±10 s, the bit error over the overlap, and how much of the longer song lines up. They are one song
 * when the bit error is at most 0.15 and at least 95 % of the longer song lines up.
 */
object MusicSoundMatch {
    const val MAX_BER = 0.15
    const val MIN_COVERAGE = 0.95
    const val MAX_OFFSET_SEC = 10.0

    data class Score(val ber: Double, val coverage: Double, val offsetMs: Long)

    /** [offsetMs] > 0: [b] starts that much later than [a] (play [b] from `offset` to line up with [a] at 0). */
    fun score(a: List<Int>, b: List<Int>): Score? {
        if (a.isEmpty() || b.isEmpty()) return null
        val maxOff = (MAX_OFFSET_SEC / dev.jellystructure.media.SegmentDetection.FRAME_SEC).toInt()
        val longer = maxOf(a.size, b.size)
        var best: Score? = null
        for (off in -maxOff..maxOff) {
            // b[i + off] against a[i].
            val start = maxOf(0, -off)
            val end = minOf(a.size, b.size - off)
            val n = end - start
            if (n <= 0) continue
            var bits = 0L
            for (i in start until end) bits += dev.jellystructure.media.SegmentDetection.popcount(a[i] xor b[i + off])
            val ber = bits.toDouble() / (n * 32.0)
            val s = Score(ber, n.toDouble() / longer, (off * dev.jellystructure.media.SegmentDetection.FRAME_SEC * 1000).toLong())
            if (best == null || s.ber < best.ber - 1e-9 || (kotlin.math.abs(s.ber - best.ber) < 1e-9 && s.coverage > best.coverage)) best = s
        }
        return best
    }

    fun joins(s: Score?): Boolean = s != null && s.ber <= MAX_BER && s.coverage >= MIN_COVERAGE
}

/** One stored measurement (`music_sound_pair`), [a] < [b]. */
data class MusicSoundPair(val a: String, val b: String, val ber: Double, val coverage: Double, val offsetMs: Long, val measuredAt: Long = 0) {
    val score get() = MusicSoundMatch.Score(ber, coverage, offsetMs)
}

/** The owner's decision on a pair (`music_same_song`), [a] < [b]. */
object MusicSameSong {
    const val SAME = "same"
    const val NOT_SAME = "not_same"
    fun key(x: String, y: String): Pair<String, String> = if (x < y) x to y else y to x
}

/**
 * Phase 305 (FR-305-3/6/7, dev review 3) — per album: the official list, which held tracks are extras, and the
 * singles homed under it; per single: its home. Built once per snapshot, like 292's versions.
 */
class MusicEditionsIndex(private val snap: MusicStore.Snapshot) {
    private val liveAlbums by lazy { snap.albums.values.filter { it.missingSince == null } }
    fun liveTracks(albumId: String): List<MusicTrack> = snap.tracksByAlbum[albumId].orEmpty().filter { it.missingSince == null }

    fun official(a: MusicAlbum): MusicOfficialList? = MusicOfficial.effective(a)

    fun isExtra(t: MusicTrack): Boolean {
        val a = t.albumId?.let { snap.albums[it] } ?: return false
        return MusicOfficial.isExtra(official(a), t)
    }

    /** On the album's official tracklist (a copy that makes a song *not bonus*, owner decision 1). */
    fun isOfficial(t: MusicTrack): Boolean {
        val a = t.albumId?.let { snap.albums[it] } ?: return false
        val o = official(a) ?: return false
        return t.recordingMbid != null && t.recordingMbid in o.recordings
    }

    fun kindOf(t: MusicTrack): Int = MusicCopyRank.kindOf(t.albumId?.let { snap.albums[it] }, isExtra(t))

    private val library by lazy {
        MusicSingleHome.Library(liveAlbums, liveAlbums.filter { MusicOfficial.isSingleOrEp(it) }, ::liveTracks, snap.facts)
    }

    /** Every single's and EP's home (absent = not homed). A *No album* override is a home with no album. */
    val homes: Map<String, MusicSingleHome.Home> by lazy {
        val out = HashMap<String, MusicSingleHome.Home>()
        for (s in liveAlbums) {
            val o = snap.homeOverrides[s.id]
            if (o == null && !MusicOfficial.isSingleOrEp(s)) continue
            MusicSingleHome.resolve(s, library, o)?.let { out[s.id] = it }
        }
        out
    }

    fun homeOf(single: MusicAlbum): MusicSingleHome.Home? = homes[single.id]

    /** The singles and EPs living under [albumId], in year order. */
    fun singlesUnder(albumId: String): List<MusicAlbum> = homes.filterValues { it.albumId == albumId }.keys.mapNotNull { snap.albums[it] }
        .sortedWith(compareBy<MusicAlbum>({ it.originalYear() ?: 0 }, { it.firstReleaseDate ?: "" }, { it.title.lowercase() }))

    /**
     * FR-305-7 — a homed single's tracks that are not already one of the album's songs (official or extra) after
     * folding, in single order; the A-side is never repeated.
     */
    fun bsides(albumId: String): List<Pair<MusicAlbum, MusicTrack>> {
        val album = snap.albums[albumId] ?: return emptyList()
        val own = liveTracks(albumId)
        val ownSongs = own.mapTo(HashSet()) { snap.songs.songOf(it) }
        val ownTitles = own.mapTo(HashSet()) { MusicSingleHome.baseTitle(it.title) } + official(album)?.songs.orEmpty().map { MusicSingleHome.baseTitle(it.title) }
        val out = ArrayList<Pair<MusicAlbum, MusicTrack>>()
        val seen = HashSet<String>()
        for (s in singlesUnder(albumId)) {
            val ts = liveTracks(s.id)
            val aSide = ts.firstOrNull()
            for (t in ts) {
                if (snap.songs.songOf(t) in ownSongs) continue
                if (t == aSide && MusicSingleHome.baseTitle(t.title) in ownTitles) continue
                if (!seen.add(snap.songs.songOf(t))) continue
                out += s to t
            }
        }
        return out
    }
}

/**
 * Phase 305 (FR-305-8, dev review 1/3) — one copy of every song. A song is 292's `keyOf` group (the same trusted
 * recording), joined by sound where one side is untrusted (rule 2), and by the owner's *same song*; the owner's *not the
 * same song* takes the named copy (the lower-ranked of the pair) out of every automatic group it is in, and only an
 * explicit *same* brings it back. Two trusted, different recordings that sound alike are a **suggestion**, never a
 * join. Title and length never decide. Computed on read from the snapshot; nothing here is stored.
 */
class MusicSongIndex(private val snap: MusicStore.Snapshot) {
    private val live: List<MusicTrack> = snap.tracks.values.filter { it.missingSince == null }.sortedBy { it.id }
    private val byId = live.associateBy { it.id }
    private val parent = HashMap<String, String>()
    private fun find(x: String): String { var r = x; while (parent[r] != r) r = parent[r]!!; var c = x; while (parent[c] != r) { val n = parent[c]!!; parent[c] = r; c = n }; return r }
    private fun union(x: String, y: String) { val a = find(x); val b = find(y); if (a != b) { if (a < b) parent[b] = a else parent[a] = b } }

    fun copy(t: MusicTrack) = MusicCopyRank.Copy(t, snap.editions.kindOf(t))
    private fun better(x: MusicTrack, y: MusicTrack) = MusicCopyRank.compare(copy(x), copy(y)) <= 0

    private fun trusted(t: MusicTrack) = MusicVersions.keyOf(t).startsWith("rec:")

    /** Copies taken out of their automatic groups by a *not the same song*. */
    private val detached = HashSet<String>()
    private val joinedByYou = HashSet<String>()
    private val joinedBySound = HashSet<String>()

    /** Open suggestions: two trusted, different recordings that sound alike, not yet decided. */
    val suggestions: List<MusicSoundPair>

    /** Filled once in init: the snapshot is read from many request threads, and path compression writes. */
    private val rootOf: HashMap<String, String> = HashMap()

    init {
        for (t in live) parent[t.id] = t.id
        val byKey = live.groupBy { MusicVersions.keyOf(it) }
        val decisions = snap.sameSong
        // Not the same: the lower-ranked copy of a pair inside one recording group leaves every automatic group.
        for ((pair, state) in decisions) {
            if (state != MusicSameSong.NOT_SAME) continue
            val x = byId[pair.first] ?: continue; val y = byId[pair.second] ?: continue
            if (MusicVersions.keyOf(x) == MusicVersions.keyOf(y)) detached += if (better(x, y)) y.id else x.id
        }
        // Rule 1 — the same trusted recording.
        for ((k, group) in byKey) {
            if (!k.startsWith("rec:")) continue
            val members = group.filter { it.id !in detached }
            for (i in 1 until members.size) union(members[0].id, members[i].id)
        }
        // Rule 2 — one side untrusted, and they sound the same.
        val sugg = ArrayList<MusicSoundPair>()
        for (p in snap.soundPairs) {
            val x = byId[p.a] ?: continue; val y = byId[p.b] ?: continue
            if (!MusicSoundMatch.joins(p.score)) continue
            val decided = decisions[MusicSameSong.key(p.a, p.b)]
            if (trusted(x) && trusted(y)) {
                if (MusicVersions.keyOf(x) != MusicVersions.keyOf(y) && decided == null) sugg += p
                continue
            }
            if (decided == MusicSameSong.NOT_SAME || x.id in detached || y.id in detached) continue
            if (find(x.id) != find(y.id)) { union(x.id, y.id); joinedBySound += x.id; joinedBySound += y.id }
        }
        // Rule 3 — the owner's *same song*, whatever else.
        for ((pair, state) in decisions) {
            if (state != MusicSameSong.SAME) continue
            if (pair.first !in byId || pair.second !in byId) continue
            union(pair.first, pair.second); joinedByYou += pair.first; joinedByYou += pair.second
        }
        suggestions = sugg.filter { find(it.a) != find(it.b) }
        for (t in live) rootOf[t.id] = find(t.id)
    }

    private val groups: Map<String, List<MusicTrack>> = live.groupBy { rootOf[it.id]!! }
        .mapValues { (_, l) -> l.sortedWith { x, y -> MusicCopyRank.compare(copy(x), copy(y)) } }

    /** A stable id for [t]'s song (the smallest track id in it). A track not in the library is its own song. */
    fun songOf(t: MusicTrack): String = rootOf[t.id] ?: t.id

    /** Every copy of [t]'s song, the shown copy first. */
    fun copies(t: MusicTrack): List<MusicTrack> = groups[songOf(t)] ?: listOf(t)

    /** FR-305-10 — the copy lists show (over every library: the admin's). */
    fun shown(t: MusicTrack): MusicTrack = copies(t).first()

    fun isShown(t: MusicTrack): Boolean = shown(t).id == t.id

    /** Every song's shown copy. */
    val songs: List<MusicTrack> by lazy { groups.values.map { it.first() } }

    /** Copies folded away (files − songs). */
    val folded: Int get() = live.size - groups.size

    /** Why [t] is a copy of its song's shown copy: `recording` · `sound` · `you`; null for the shown copy itself. */
    fun reason(t: MusicTrack): String? {
        val s = shown(t)
        if (s.id == t.id) return null
        if (MusicVersions.keyOf(s) == MusicVersions.keyOf(t) && trusted(t) && t.id !in detached) return REASON_RECORDING
        if (t.id in joinedByYou || (t.id !in joinedBySound && s.id in joinedByYou)) return REASON_YOU
        if (t.id in joinedBySound || s.id in joinedBySound) return REASON_SOUND
        return REASON_YOU
    }

    /** Owner decision 1 — a folded row says *Bonus* only when no copy of the song is on any official tracklist, and
     *  at least one is an extra. */
    fun bonus(t: MusicTrack, among: List<MusicTrack> = copies(t)): Boolean =
        among.any { snap.editions.isExtra(it) } && among.none { snap.editions.isOfficial(it) }

    companion object {
        const val REASON_RECORDING = "recording"
        const val REASON_SOUND = "sound"
        const val REASON_YOU = "you"

        /** The admin's words for a reason (FR-305-13). */
        fun reasonText(r: String?): String? = when (r) {
            REASON_RECORDING -> "same recording · MusicBrainz"
            REASON_SOUND -> "sounds the same"
            REASON_YOU -> "you said so"
            else -> null
        }
    }
}

/**
 * Phase 305 (dev review 5) — the pairs `compare_songs` measures: the same artist (MusicBrainz id, else Jellyfin's)
 * and the same base title (292's title-finder), not already one song by recording, not already decided by the owner.
 * Title only picks the pairs; it never decides.
 */
object MusicCompareSongs {
    data class Candidate(val a: MusicTrack, val b: MusicTrack) {
        /** Rule 2 when either side is untrusted; else a possible suggestion. */
        val suggestion: Boolean get() = MusicVersions.keyOf(a).startsWith("rec:") && MusicVersions.keyOf(b).startsWith("rec:")
    }

    private fun artistOf(t: MusicTrack): String? =
        t.mbArtists.firstOrNull()?.mbid?.let { "mb:$it" } ?: t.artists.firstOrNull()?.artistId?.let { "jf:$it" }

    /** The base title: [MusicTitleVersions.segments]' brackets and dashed tail removed. */
    fun baseTitle(title: String): String = MusicSingleHome.baseTitle(title).ifEmpty { MusicTitleVersions.segments(title).joinToString("") }

    fun candidates(snap: MusicStore.Snapshot, decided: Set<Pair<String, String>> = snap.sameSong.keys): List<Candidate> {
        val live = snap.tracks.values.filter { it.missingSince == null }
        val groups = live.groupBy { (artistOf(it) ?: return@groupBy null) to baseTitle(it.title) }.filterKeys { it != null && it.second.isNotEmpty() }
        val out = ArrayList<Candidate>()
        for ((_, g) in groups) {
            val sorted = g.sortedBy { it.id }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val a = sorted[i]; val b = sorted[j]
                if (MusicVersions.keyOf(a) == MusicVersions.keyOf(b) && MusicVersions.keyOf(a).startsWith("rec:")) continue
                if ((a.id to b.id) in decided) continue
                out += Candidate(a, b)
            }
        }
        return out
    }
}
