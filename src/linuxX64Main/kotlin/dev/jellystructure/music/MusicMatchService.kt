package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCandidate
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicMatchStatus
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicRecordingOption
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicSoundResult
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicReference

data class MusicMatchSummary(val tried: Int, val matched: Int, val needsYou: Int, val unmatched: Int, val failed: Int, val artists: Int, val versions: Int = 0, val editions: Int = 0) {
    fun sentence(): String = (if (tried == 0) "nothing to match" else buildString {
        append("$tried albums · $matched matched · $needsYou need you · $unmatched unmatched")
        if (artists > 0) append(" · $artists artists")
        if (failed > 0) append(" · MusicBrainz didn't answer for $failed")
    }) + (if (versions > 0) " · versions read for $versions album${if (versions == 1) "" else "s"}" else "") +
        if (editions > 0) " · official lists and singles read for $editions release${if (editions == 1) "" else "s"}" else ""
}

/** Phase 305 (FR-305-4) — what *Use as the official album* answered. */
sealed class MusicPickOutcome {
    data class Picked(val album: MusicAlbum) : MusicPickOutcome()
    data object NotFound : MusicPickOutcome()
    /** The album is not matched, or not an album (a single has no official tracklist). */
    data object NotAnAlbum : MusicPickOutcome()
    data object NotOfThisAlbum : MusicPickOutcome()
    data object NoAnswer : MusicPickOutcome()
}

/**
 * Phase 276 — the match ladder and everything the admin does to a match. One pass at a time (MusicBrainz is one
 * request a second, so parallel passes only queue on the same limiter); every write goes through [MusicStore].
 *
 * **A failed lookup never blanks a good match** (131/183's rule): MusicBrainz not answering leaves an album exactly
 * as it was. **A locked album is never touched by a run.**
 */
class MusicMatchService(
    private val store: MusicStore,
    val mb: MusicBrainzClient,
    val acoustId: AcoustIdClient,
    private val configStore: ConfigStore,
    private val fingerprint: suspend (String) -> Pair<String, Int>?,
    /** Phase 278 — the album page's History tab (the films' table, keyed by the album's id). */
    private val history: dev.jellystructure.media.MediaHistory? = null,
) {
    private fun note(id: String, action: String, detail: String) {
        runCatching { history?.record(id, action, detail) }
    }

    /** Phase 305 — a line in the album's History from a route (*Move to…*). */
    fun recordHistory(id: String, action: String, detail: String) = note(id, action, detail)

    private val passLock = Mutex()
    private val statusRef = AtomicReference(MusicMatchStatus())
    val status: MusicMatchStatus get() = statusRef.value

    private sealed class Outcome {
        data class Picked(val candidate: MusicCandidate, val source: String, val releaseMbid: String?) : Outcome()
        data class NeedsYou(val candidates: List<MusicCandidate>, val note: String) : Outcome()
        data class Unmatched(val candidates: List<MusicCandidate>, val note: String) : Outcome()
        data object NoAnswer : Outcome()
    }

    private fun tracksOf(albumId: String): List<MusicTrack> =
        store.snapshot().tracksByAlbum[albumId].orEmpty().filter { it.missingSince == null }

    private fun albumArtistName(album: MusicAlbum, tracks: List<MusicTrack>): String =
        album.albumArtists.firstOrNull()?.name ?: tracks.firstOrNull()?.artists?.firstOrNull()?.name.orEmpty()

    // ── the ladder (FR-276-3) ──

    private suspend fun candidatesFor(rgs: List<MbReleaseGroup>, tracks: List<MusicTrack>, source: String, max: Int = 3): List<MusicCandidate>? {
        val out = mutableListOf<MusicCandidate>()
        for (rg in rgs.take(max)) {
            val releases = mb.releasesOf(rg.id) ?: return null
            out += MusicScoring.candidate(rg, MusicScoring.bestRelease(releases, tracks), tracks.count { it.position != null }, source)
        }
        return out
    }

    private suspend fun ladder(album: MusicAlbum, tracks: List<MusicTrack>, allowSound: Boolean): Outcome {
        // Rung 1 — ids already in the files (Jellyfin's ProviderIds from the tags).
        val rgTag = album.jellyfinProviderIds["MusicBrainzReleaseGroup"]
        val relTag = album.jellyfinProviderIds["MusicBrainzAlbum"] ?: tracks.firstNotNullOfOrNull { it.jellyfinProviderIds["MusicBrainzAlbum"] }
        if (rgTag != null || relTag != null) {
            val rgId = rgTag ?: mb.release(relTag!!)?.releaseGroup?.id ?: return Outcome.NoAnswer
            val rg = mb.releaseGroup(rgId) ?: return Outcome.NoAnswer
            return Outcome.Picked(MusicScoring.candidate(rg, null, tracks.size, "tags"), "tags", relTag)
        }
        // Rung 2 — text search, then each candidate's releases scored against the tracks on disk.
        val artist = albumArtistName(album, tracks)
        var rgs = mb.searchReleaseGroups(artist, album.title) ?: return Outcome.NoAnswer
        if (rgs.isEmpty() && artist.isNotBlank()) rgs = mb.searchReleaseGroups("", album.title) ?: return Outcome.NoAnswer
        val candidates = candidatesFor(rgs, tracks, "search") ?: return Outcome.NoAnswer
        when (val d = MusicScoring.decide(candidates)) {
            is MusicScoring.Decision.Pick -> return Outcome.Picked(d.candidate, "search", d.candidate.bestRelease?.mbid)
            else -> Unit
        }
        // Rung 4 — AcoustID, only when the text did not decide and a key is set.
        val sound = if (allowSound && acoustId.available) identify(tracks) else null
        val merged = mergeSound(candidates, sound)
        return when (val d = MusicScoring.decide(merged)) {
            is MusicScoring.Decision.Pick -> Outcome.Picked(d.candidate, d.candidate.source, d.candidate.bestRelease?.mbid)
            is MusicScoring.Decision.NeedsYou -> Outcome.NeedsYou(merged, d.note)
            is MusicScoring.Decision.Unmatched -> soundPick(sound, merged) ?: Outcome.Unmatched(merged, d.note)
        }
    }

    private data class Sound(val fingerprinted: Int, val identified: Int, val groups: Map<String, Int>, val candidates: List<MusicCandidate>)

    /** Fingerprint up to four tracks and vote on release-groups. */
    private suspend fun identify(tracks: List<MusicTrack>): Sound? {
        val votes = LinkedHashMap<String, Int>()
        var fingerprinted = 0
        var identified = 0
        for (t in tracks.filter { it.path != null }.sortedByDescending { it.durationMs ?: 0 }.take(4)) {
            val (fp, dur) = fingerprint(t.path!!) ?: continue
            fingerprinted++
            val best = acoustId.lookup(fp, dur).firstOrNull { it.score >= 0.7 } ?: continue
            val groups = best.recordings.flatMap { r -> r.releasegroups.map { it.id } }.distinct()
            if (groups.isNotEmpty()) identified++
            groups.forEach { votes[it] = (votes[it] ?: 0) + 1 }
        }
        if (fingerprinted == 0) return null
        val top = votes.entries.sortedByDescending { it.value }.take(3)
        val rgs = top.mapNotNull { mb.releaseGroup(it.key) }
        val cands = candidatesFor(rgs, tracks, "acoustid").orEmpty()
        return Sound(fingerprinted, identified, votes, cands)
    }

    private fun mergeSound(text: List<MusicCandidate>, sound: Sound?): List<MusicCandidate> {
        if (sound == null) return text
        val ids = sound.candidates.map { it.releaseGroupMbid }.toSet()
        return sound.candidates + text.filter { it.releaseGroupMbid !in ids }
    }

    /** The sound names one release-group for at least half the fingerprinted tracks: that is the album, even if the
     *  lengths on disk differ (another cut of the same recordings). */
    private fun soundPick(sound: Sound?, merged: List<MusicCandidate>): Outcome? {
        sound ?: return null
        val (rg, n) = sound.groups.entries.maxByOrNull { it.value }?.toPair() ?: return null
        if (n * 2 < sound.fingerprinted) return null
        val c = merged.firstOrNull { it.releaseGroupMbid == rg } ?: return null
        return Outcome.Picked(c, "acoustid", c.bestRelease?.mbid)
    }

    // ── a pass over albums (the pipeline step, and *Match now*) ──

    /**
     * [ids] null = every album the scope allows. `missing` (the pipeline's default): albums that are not matched,
     * not locked, and not tried in the last day; *needs you* albums wait for the admin. `all`: every unlocked album —
     * a matched one is refreshed from its own ids, never searched again. [ids] given = exactly those, unlocked,
     * whatever their state (the admin asked).
     */
    suspend fun matchAlbums(ids: Collection<String>?, scopeAll: Boolean, allowSound: Boolean = true): MusicMatchSummary = passLock.withLock {
        val now = nowEpochSec()
        val albums = store.snapshot().albums.values.filter { it.missingSince == null && !it.matchLocked }.filter { a ->
            when {
                ids != null -> a.id in ids
                scopeAll -> true
                else -> a.matchState == MusicMatch.UNMATCHED && (a.matchAttemptedAt == null || now - a.matchAttemptedAt >= 86_400)
            }
        }.sortedBy { it.sortName ?: it.title }
        statusRef.value = MusicMatchStatus(running = true, done = 0, total = albums.size, startedAt = now)
        var matched = 0; var needs = 0; var unmatched = 0; var failed = 0; var artists = 0; var versions = 0; var editions = 0
        try {
            albums.forEachIndexed { i, a ->
                statusRef.value = statusRef.value.copy(done = i, currentAlbum = a.title)
                val tracks = tracksOf(a.id)
                val outcome = if (a.matchState == MusicMatch.MATCHED && a.releaseGroupMbid != null)
                    Outcome.Picked(MusicCandidate(a.releaseGroupMbid, a.title), a.matchSource ?: "search", a.releaseMbid)
                else runCatching { ladder(a, tracks, allowSound) }.getOrElse { e ->
                    Logger.warn("match_musicbrainz: ${a.id} failed: ${e.message}", "music"); Outcome.NoAnswer
                }
                // Phase 283 (FR-283-2) — a new pick by search or sound is not given to a second folder: another folder
                // already holding that album (matched before, or earlier in this run) makes it *needs you*. Ids in the
                // files are the files' own word and are taken; an album already matched is never un-matched here.
                val holder = (outcome as? Outcome.Picked)?.takeIf { a.matchState != MusicMatch.MATCHED && it.source != "tags" }?.let { p ->
                    store.snapshot().albums.values.firstOrNull { o ->
                        o.id != a.id && o.missingSince == null && o.releaseGroupMbid == p.candidate.releaseGroupMbid &&
                            (o.path == null || a.path == null || MusicFlags.albumDir(o.path) != MusicFlags.albumDir(a.path))
                    }
                }
                when {
                    holder != null && outcome is Outcome.Picked -> {
                        needs++
                        val folder = MusicFlags.folders(holder.path, emptySet())?.album ?: holder.title
                        val why = "Another folder is already matched to this album: $folder"
                        if (a.matchState != MusicMatch.NEEDS_YOU) note(a.id, "music_match", "match_musicbrainz · $why — needs you")
                        store.putAlbum(a.copy(matchState = MusicMatch.NEEDS_YOU, candidates = listOf(outcome.candidate), matchNote = why, matchAttemptedAt = now))
                    }
                    else -> when (outcome) {
                    is Outcome.Picked -> {
                        val r = applyMatch(a.id, outcome.candidate.releaseGroupMbid, outcome.releaseMbid, outcome.source, lock = false)
                        if (r != null) { matched++; artists += r } else failed++
                    }
                    is Outcome.NeedsYou -> {
                        needs++
                        if (a.matchState != MusicMatch.NEEDS_YOU) note(a.id, "music_match", "match_musicbrainz · ${outcome.candidates.size} candidates, no clear winner — needs you")
                        store.putAlbum(a.copy(matchState = MusicMatch.NEEDS_YOU, candidates = outcome.candidates, matchNote = outcome.note, matchAttemptedAt = now))
                    }
                    is Outcome.Unmatched -> {
                        unmatched++
                        if (a.matchAttemptedAt == null || a.matchState != MusicMatch.UNMATCHED) note(a.id, "music_match", "match_musicbrainz · ${outcome.note}")
                        store.putAlbum(a.copy(matchState = MusicMatch.UNMATCHED, candidates = outcome.candidates, matchNote = outcome.note, matchAttemptedAt = now))
                    }
                    Outcome.NoAnswer -> failed++
                    }
                }
            }
            // Phase 292 (dev review 5) — a matched album whose recordings were never read for versions is caught up
            // here: once per album, at MusicBrainz's one request a second. A run over every album leaves out none.
            if (ids == null) versions = catchUpVersionFacts()
            // Phase 305 (dev review 4) — official tracklists, extras' first pressings and singles' *single from*, read
            // here so a locked album is caught up too: once per release group, again on an `all` run.
            if (ids == null) editions = catchUpEditions(scopeAll, now)
        } finally {
            val summary = MusicMatchSummary(albums.size, matched, needs, unmatched, failed, artists, versions, editions)
            statusRef.value = MusicMatchStatus(running = false, done = albums.size, total = albums.size, lastSummary = summary.sentence())
        }
        MusicMatchSummary(albums.size, matched, needs, unmatched, failed, artists, versions, editions)
    }

    // ── Phase 305: official tracklists, extras, singles ──

    /** Matched albums (locked ones too) and singles/EPs whose editions were never read, or read for another group;
     *  every one on an `all` run (except those this run already read). */
    private suspend fun catchUpEditions(scopeAll: Boolean, runStart: Long): Int {
        var n = 0
        val due = store.snapshot().albums.values.filter { a ->
            a.missingSince == null && a.matchState == MusicMatch.MATCHED && a.releaseGroupMbid != null && when {
                // An `all` run re-reads the locked ones here; the unlocked ones were just re-read by their match.
                MusicOfficial.isAlbum(a) -> a.official?.groupMbid != a.releaseGroupMbid || (scopeAll && a.matchLocked && (a.relsReadAt ?: 0) <= runStart)
                MusicOfficial.isSingleOrEp(a) -> a.relsReadAt == null || (scopeAll && a.matchLocked)
                else -> false
            }
        }
        for (a in due.sortedBy { it.sortName ?: it.title }) if (runCatching { readEditions(a.id) }.getOrDefault(false)) n++
        return n
    }

    /** MusicBrainz's *single from* on a single's or EP's release group (forward: the single is the first entity). */
    private fun singleFromOf(rg: MbReleaseGroup): String? = rg.relations.firstOrNull { r ->
        r.type.equals("single from", ignoreCase = true) && (r.direction ?: "forward") == "forward" && !r.releaseGroup?.id.isNullOrBlank()
    }?.releaseGroup?.id

    /**
     * Phase 305 (FR-305-2/3/6, dev review 4) — read [albumId]'s editions: for an album, the vote over every official
     * pressing and the extras' first pressings (one or two requests; an extra on no pressing of the group costs one
     * recording lookup); for a single or EP, its *single from*. False when MusicBrainz did not answer: nothing is
     * written, and what was known stays.
     */
    suspend fun readEditions(albumId: String): Boolean {
        val a = store.album(albumId) ?: return false
        val rgId = a.releaseGroupMbid ?: return false
        val now = nowEpochSec()
        val rg = mb.releaseGroup(rgId) ?: return false
        if (MusicOfficial.isAlbum(rg.primaryType, rg.secondaryTypes)) {
            val releases = mb.releasesOf(rgId) ?: return false
            val official = MusicOfficial.vote(rg, releases, now) ?: a.official
            val effective = a.userOfficial ?: official
            val extras = tracksOf(albumId).filter { MusicOfficial.isExtra(effective, it) }.mapNotNull { it.recordingMbid }.toSet()
            val origins = MusicOfficial.firstReleases(releases, extras).toMutableMap()
            for (rec in extras - origins.keys) {
                val r = mb.recording(rec) ?: continue
                r.releases.filter { MusicOfficial.isOfficialStatus(it.status) }.minByOrNull { it.date?.takeIf { d -> d.length >= 4 } ?: "9999" }
                    ?.let { origins[rec] = MusicOfficial.origin(it) }
            }
            val cur = store.album(albumId) ?: return false
            store.putAlbum(cur.copy(official = official, extraOrigins = origins, singleFrom = singleFromOf(rg), relsReadAt = now))
        } else {
            val cur = store.album(albumId) ?: return false
            store.putAlbum(cur.copy(official = null, singleFrom = singleFromOf(rg), relsReadAt = now))
        }
        return true
    }

    /** FR-305-4 — *Change…*'s list: every pressing, what it has against the official list, and whether it can be
     *  picked (its extra songs are held; open question 3: else *+ N not in the library*). Null = no answer. */
    suspend fun pressings(albumId: String): List<dev.jellystructure.model.MusicPressingDto>? {
        val a = store.album(albumId) ?: return emptyList()
        val rg = a.releaseGroupMbid ?: return emptyList()
        val releases = mb.releasesOf(rg) ?: return null
        val tracks = tracksOf(albumId)
        val held = tracks.mapNotNull { it.recordingMbid }.toSet()
        val auto = a.official
        val pick = store.snapshot().picks[albumId]
        return releases.filter { MusicOfficial.isOfficialStatus(it.status) }.map { r ->
            val songs = MusicOfficial.songsOf(r)
            val extra = songs.map { it.recordingMbid }.filter { auto == null || it !in auto.recordings }
            val missing = extra.count { it !in held }
            dev.jellystructure.model.MusicPressingDto(
                option = MusicScoring.option(r, MusicScoring.agreement(r, tracks)), against = MusicOfficial.against(auto, songs),
                pickable = missing == 0, notInLibrary = missing, chosen = pick?.releaseMbid == r.id,
                automatic = pick == null && auto?.releaseMbid == r.id,
            )
        }.sortedWith(compareBy({ it.option.date ?: "9999" }, { it.option.country ?: "" }))
    }

    /** FR-305-4 — *Use as the official album*: kept across every scan and a re-match to the same group. */
    suspend fun pickOfficial(albumId: String, releaseMbid: String): MusicPickOutcome {
        val a = store.album(albumId) ?: return MusicPickOutcome.NotFound
        val rg = a.releaseGroupMbid ?: return MusicPickOutcome.NotAnAlbum
        if (a.matchState != MusicMatch.MATCHED || !MusicOfficial.isAlbum(a)) return MusicPickOutcome.NotAnAlbum
        val r = mb.release(releaseMbid) ?: return MusicPickOutcome.NoAnswer
        if (r.releaseGroup?.id != rg) return MusicPickOutcome.NotOfThisAlbum
        val now = nowEpochSec()
        val list = MusicOfficial.fromRelease(r, rg, a.official?.groupTitle ?: r.releaseGroup?.title?.ifBlank { null }, now)
        store.putPick(MusicOfficialPick(albumId, releaseMbid, rg, now), a.copy(userOfficial = list))
        note(albumId, "music_official", "Official album chosen by hand: “${r.title}”" + listOfNotNull(r.country, r.date?.take(4)).joinToString(" ", prefix = " (", postfix = ")").takeIf { r.country != null || r.date != null }.orEmpty() + " · ${list.songs.size} songs")
        runCatching { readEditions(albumId) }
        return MusicPickOutcome.Picked(store.album(albumId) ?: a)
    }

    /** *Back to automatic* (FR-305-4). */
    suspend fun unpickOfficial(albumId: String): MusicAlbum? {
        val a = store.album(albumId) ?: return null
        if (store.snapshot().picks[albumId] == null && a.userOfficial == null) return a
        store.deletePick(a.copy(userOfficial = null))
        note(albumId, "music_official", "Official album back to automatic")
        runCatching { readEditions(albumId) }
        return store.album(albumId)
    }

    /** Dev review 3 — a re-match to another group, or *Clear match*, drops the pick: its pressing is no longer one of
     *  the album's. */
    private suspend fun dropPickIfOtherGroup(albumId: String, newGroup: String?) {
        val pick = store.snapshot().picks[albumId] ?: return
        if (newGroup != null && pick.releaseGroupMbid == newGroup) return
        val a = store.album(albumId) ?: return
        store.deletePick(a.copy(userOfficial = null))
        note(albumId, "music_official", "Official album choice dropped — " + if (newGroup == null) "the match was cleared" else "the album was matched to another release group")
    }

    // ── Phase 292: version facts ──

    /** Every matched album (a locked one too: facts are not a match) whose recordings have no facts yet. */
    private suspend fun catchUpVersionFacts(): Int {
        var n = 0
        val due = store.snapshot().albums.values.filter { it.missingSince == null && it.matchState == MusicMatch.MATCHED && it.releaseMbid != null && it.versionFactsAt == null }
        for (a in due.sortedBy { it.sortName ?: it.title }) if (runCatching { readVersionFacts(a.id) }.getOrDefault(false)) n++
        return n
    }

    /**
     * Phase 292 (dev review 1, 5, 14) — read what MusicBrainz says about each recording of [albumId]: the release with
     * its recordings' relationships (one request), each recording chosen by hand on its own, and the artist of an
     * *instrumental version of* target that is not in the library. False when MusicBrainz did not answer (nothing is
     * written, and the album is tried again next run).
     */
    suspend fun readVersionFacts(albumId: String): Boolean {
        val album = store.album(albumId) ?: return false
        val releaseMbid = album.releaseMbid ?: return false
        val now = nowEpochSec()
        val release = mb.releaseWithRels(releaseMbid) ?: return false
        val facts = MusicVersionFacts.fromRelease(release, now).toMutableMap()
        for (t in tracksOf(albumId)) {
            val rec = t.recordingMbid ?: continue
            if (t.recordingState == MusicRecording.MANUAL && rec !in facts) mb.recordingRels(rec)?.let { facts[rec] = MusicVersionFacts.from(it, now) }
        }
        val known = store.snapshot().tracks.values.mapNotNull { it.recordingMbid }.toSet()
        val wanted = tracksOf(albumId).mapNotNull { it.recordingMbid }.toSet()
        val out = facts.filterKeys { it in wanted }.mapValues { (_, f) ->
            val target = f.instrumentalOf
            if (target != null && target.artist == null && target.mbid !in known) {
                val credit = mb.recordingCredit(target.mbid)?.artistCredit?.let { MusicScoring.creditText(it) }?.takeIf { it.isNotBlank() }
                f.copy(instrumentalOf = target.copy(artist = credit))
            } else f
        }
        store.putFacts(out.values.toList())
        store.album(albumId)?.let { store.putAlbum(it.copy(versionFactsAt = now)) }
        return true
    }

    // ── applying a match ──

    /**
     * Take [rgMbid] (and [releaseMbid], or the best-agreeing pressing) for the album: its ids, types, genre votes and
     * URL relationships; each track's recording at its position; and the album's and tracks' artists. Returns how many
     * artists were matched, or null when MusicBrainz did not answer (nothing is written then).
     */
    suspend fun applyMatch(albumId: String, rgMbid: String, releaseMbid: String?, source: String, lock: Boolean): Int? {
        val album = store.album(albumId) ?: return null
        val tracks = tracksOf(albumId)
        val rg = mb.releaseGroup(rgMbid) ?: return null
        val (release, agreement) = if (releaseMbid != null) {
            val r = mb.release(releaseMbid) ?: return null
            r to MusicScoring.agreement(r, tracks)
        } else MusicScoring.bestRelease(mb.releasesOf(rgMbid) ?: return null, tracks) ?: return null
        val now = nowEpochSec()
        val mbArtists = MusicScoring.credits(rg.artistCredit.ifEmpty { release.artistCredit })
        // Phase 305 (dev review 3) — another group: the owner's pick and the old group's lists go.
        if (album.releaseGroupMbid != rg.id) dropPickIfOtherGroup(albumId, rg.id)
        val base = store.album(albumId) ?: album
        val sameGroup = base.releaseGroupMbid == rg.id
        store.putAlbum(base.copy(
            official = if (sameGroup) base.official else null, extraOrigins = if (sameGroup) base.extraOrigins else emptyMap(),
            singleFrom = singleFromOf(rg), relsReadAt = if (sameGroup) base.relsReadAt else null,
            releaseGroupMbid = rg.id, releaseMbid = release.id, matchState = MusicMatch.MATCHED,
            matchLocked = lock || album.matchLocked, matchSource = source, matchedAt = now, matchAttemptedAt = now,
            matchNote = null, candidates = emptyList(), release = MusicScoring.option(release, agreement),
            primaryType = rg.primaryType, secondaryTypes = rg.secondaryTypes, firstReleaseDate = rg.firstReleaseDate,
            mbArtists = mbArtists, mbGenres = MusicScoring.votes(rg.genres), urls = MusicScoring.urls(rg.relations),
        ))
        if (album.matchState != MusicMatch.MATCHED || album.releaseGroupMbid != rg.id) {
            val how = when (source) {
                "tags" -> "from the ids already in the files"; "acoustid" -> "by sound (AcoustID)"
                "manual" -> "chosen by hand"; else -> "by text search"
            }
            note(albumId, "music_match", "Matched to “${rg.title}” $how · ${agreement.hits.size} of ${tracks.size} tracks agree" + if (lock) " · locked" else "")
        }
        val byTrack = agreement.hits.associateBy { it.track.id }
        val updated = tracks.map { t ->
            val hit = byTrack[t.id]
            val mbt = hit?.mb
            if (t.recordingState == MusicRecording.MANUAL) t.copy(releaseTrackMbid = mbt?.id ?: t.releaseTrackMbid)
            else t.copy(
                recordingMbid = mbt?.recording?.id, releaseTrackMbid = mbt?.id,
                recordingState = when { mbt == null -> null; hit.agrees -> MusicRecording.AGREES; else -> MusicRecording.DISAGREES },
                mbTitle = mbt?.title, mbLengthMs = mbt?.length ?: mbt?.recording?.length,
                mbArtists = MusicScoring.credits(mbt?.artistCredit.orEmpty().ifEmpty { mbt?.recording?.artistCredit.orEmpty() }),
            )
        }
        store.putTracks(updated)
        // Phase 292 (dev review 6) — a song that gained its recording takes its own version ticks along.
        moveVersionChoices(tracks, updated)
        runCatching { readVersionFacts(albumId) }.onFailure { Logger.warn("versions: $albumId: ${it.message}", "music") }
        runCatching { readEditions(albumId) }.onFailure { Logger.warn("editions: $albumId: ${it.message}", "music") }
        return matchArtists(album.albumArtists.map { it.artistId to it.name }, mbArtists) +
            tracks.sumOf { t -> matchArtists(t.artists.map { it.artistId to it.name }, store.track(t.id)?.mbArtists.orEmpty()) }
    }

    /** Pair Jellyfin's credits with MusicBrainz's by name and look each new artist up once. */
    private suspend fun matchArtists(credits: List<Pair<String, String>>, mbCredits: List<dev.jellystructure.model.MusicMbCredit>): Int {
        var n = 0
        for ((artistId, name) in credits) {
            val artist = store.artist(artistId) ?: continue
            if (artist.matchLocked || artist.matchState == MusicMatch.MATCHED) continue
            val credit = MusicScoring.pairCredit(name, mbCredits) ?: continue
            val a = mb.artist(credit.mbid) ?: continue
            store.putArtist(artist.withMb(a))
            n++
        }
        return n
    }

    private fun MusicArtist.withMb(a: MbArtist) = copy(
        mbid = a.id, matchState = MusicMatch.MATCHED, type = a.type, country = a.country,
        lifeSpan = MusicScoring.lifeSpan(a.lifeSpan), disambiguation = a.disambiguation?.takeIf { it.isNotBlank() },
        mbSortName = a.sortName, aliases = a.aliases.map { it.name }.filter { it.isNotBlank() }.distinct().take(12),
        mbGenres = MusicScoring.votes(a.genres), urls = MusicScoring.urls(a.relations),
    )

    // ── the admin's own actions (FR-276-4..7) ──

    /** Find match…: the admin's query (default artist · album · year) or a pasted MusicBrainz URL. Null = no answer. */
    suspend fun search(albumId: String, query: String?, url: String?): List<MusicCandidate>? {
        val album = store.album(albumId) ?: return emptyList()
        val tracks = tracksOf(albumId)
        val parsed = url?.let { MusicBrainzClient.parseUrl(it) }
        val rgs: List<MbReleaseGroup> = when {
            parsed?.first == "release-group" -> listOfNotNull(mb.releaseGroup(parsed.second) ?: return null)
            parsed?.first == "release" -> {
                val rel = mb.release(parsed.second) ?: return null
                val rg = rel.releaseGroup?.id?.let { mb.releaseGroup(it) } ?: return null
                val cand = MusicScoring.candidate(rg, rel to MusicScoring.agreement(rel, tracks), tracks.size)
                return listOf(cand)
            }
            !query.isNullOrBlank() -> mb.searchReleaseGroupsFree(query) ?: return null
            else -> mb.searchReleaseGroups(albumArtistName(album, tracks), album.title, limit = 8) ?: return null
        }
        return candidatesFor(rgs, tracks, "search", max = 5)?.sortedWith(compareByDescending<MusicCandidate> { it.agreeing }.thenByDescending { it.score })
    }

    /** The pressings of one candidate, best-agreeing first (the panel preselects the first). */
    suspend fun releases(albumId: String, rgMbid: String): List<MusicReleaseOption>? {
        val album = store.album(albumId)
        val tracks = tracksOf(albumId)
        val releases = mb.releasesOf(rgMbid) ?: return null
        return MusicScoring.orderReleases(
            releases.map { MusicScoring.option(it, MusicScoring.agreement(it, tracks)) },
            current = album?.releaseMbid, named = album?.jellyfinProviderIds?.get("MusicBrainzAlbum"),
        )
    }

    suspend fun identifyBySound(albumId: String): MusicSoundResult {
        if (!acoustId.available) return MusicSoundResult("Add an AcoustID key in Settings to identify by sound")
        val s = identify(tracksOf(albumId)) ?: return MusicSoundResult("None of this album's files could be fingerprinted")
        val groups = s.groups.size
        return MusicSoundResult(
            "${s.identified} of ${s.fingerprinted} track${if (s.fingerprinted == 1) "" else "s"} identified → $groups release-group${if (groups == 1) "" else "s"}",
            s.candidates,
        )
    }

    suspend fun setLocked(albumId: String, locked: Boolean): MusicAlbum? {
        val a = store.album(albumId) ?: return null
        if (a.matchLocked != locked) note(albumId, "music_lock", if (locked) "Match locked — runs leave it alone" else "Match unlocked — the next run may re-match it")
        return a.copy(matchLocked = locked).also { store.putAlbum(it) }
    }

    /** Phase 278 — an artist's lock: a run never re-reads a locked artist from an album's credits. */
    suspend fun setArtistLocked(artistId: String, locked: Boolean): MusicArtist? {
        val r = store.artist(artistId) ?: return null
        if (r.matchLocked != locked) note(artistId, "music_lock", if (locked) "Match locked" else "Match unlocked")
        return r.copy(matchLocked = locked).also { store.putArtist(it) }
    }

    /** Phase 278 — forget an artist's MusicBrainz id (and lock it, 174's rule); the fields it filled stay. */
    suspend fun clearArtist(artistId: String): MusicArtist? {
        val r = store.artist(artistId) ?: return null
        val cleared = r.copy(mbid = null, matchState = MusicMatch.UNMATCHED, matchLocked = true)
        note(artistId, "music_match", "Match cleared by hand — locked so no run re-matches it")
        return cleared.also { store.putArtist(it) }
    }

    /** FR-276-6 — forget the ids and the tracks' recordings; the fields the match filled stay. The album is **locked**
     *  as well (174's rule for *Clear TMDB match*): otherwise the next run would find the same wrong album again. */
    suspend fun clear(albumId: String): MusicAlbum? {
        dropPickIfOtherGroup(albumId, null)
        val a = store.album(albumId) ?: return null
        val cleared = a.copy(
            official = null, userOfficial = null, extraOrigins = emptyMap(), singleFrom = null, relsReadAt = null,
            releaseGroupMbid = null, releaseMbid = null, matchState = MusicMatch.UNMATCHED, matchLocked = true,
            matchSource = null, matchedAt = null, candidates = emptyList(), release = null,
            matchNote = "Cleared by hand — won't be matched again until you unlock it or choose a match",
        )
        store.putAlbum(cleared)
        note(albumId, "music_match", "Match cleared by hand — locked so no run re-matches it")
        store.putTracks(tracksOf(albumId).map { it.copy(recordingMbid = null, releaseTrackMbid = null, recordingState = null) })
        return cleared
    }

    suspend fun setGenres(albumId: String, genres: List<String>?): MusicAlbum? {
        val a = store.album(albumId) ?: return null
        val picked = genres?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
        note(albumId, "music_genres", if (picked == null) "Genres back to MusicBrainz's votes" else "Genres set by hand: ${picked.joinToString(", ").ifEmpty { "none" }}")
        return a.copy(genresOverride = picked).also { store.putAlbum(it) }
    }

    /** FR-276-5 — the artist's recordings of this title, closest length first. */
    suspend fun recordingsFor(trackId: String): List<MusicRecordingOption>? {
        val t = store.track(trackId) ?: return emptyList()
        val found = mb.searchRecordings(t.artists.firstOrNull()?.name.orEmpty(), t.title) ?: return null
        return found.map { r ->
            MusicRecordingOption(
                mbid = r.id, title = r.title, artist = MusicScoring.creditText(r.artistCredit), lengthMs = r.length,
                releases = r.releases.take(3).map { rel -> listOfNotNull(rel.title, rel.date?.take(4)).joinToString(" · ") },
            )
        }.sortedBy { o -> kotlin.math.abs((o.lengthMs ?: Long.MAX_VALUE / 2) - (t.durationMs ?: 0)) }
    }

    suspend fun useRecording(trackId: String, recordingMbid: String): MusicTrack? {
        val t = store.track(trackId) ?: return null
        t.albumId?.let { note(it, "music_recording", "Recording chosen by hand for “${t.title}”") }
        val next = t.copy(recordingMbid = recordingMbid, recordingState = MusicRecording.MANUAL).also { store.putTracks(listOf(it)) }
        // Phase 292 (dev review 1, 6) — its ticks move to the recording, and the recording's own facts are read.
        moveVersionChoices(listOf(t), listOf(next))
        runCatching { mb.recordingRels(recordingMbid)?.let { store.putFacts(listOf(MusicVersionFacts.from(it, nowEpochSec()))) } }
        return next
    }

    /** Dev review 6 — `trk:` ticks follow a song to its recording; a cleared match leaves them on the recording. */
    private suspend fun moveVersionChoices(before: List<MusicTrack>, after: List<MusicTrack>) {
        val old = before.associateBy { it.id }
        for (t in after) {
            val from = old[t.id]?.let { dev.jellystructure.model.MusicVersions.keyOf(it) } ?: continue
            val to = dev.jellystructure.model.MusicVersions.keyOf(t)
            if (from != to && from.startsWith("trk:") && to.startsWith("rec:")) store.moveChoices(from, to)
        }
    }

    /** Settings' *Test* for one provider: one real, cheap request. */
    suspend fun test(provider: String): String = when (provider) {
        // MusicBrainz's own special "Various Artists" entity — a fixed id, no search, one request.
        "musicbrainz" -> if (mb.artist("89ad4ac3-39f7-470e-963a-56509c546377") != null) "MusicBrainz answered" else "MusicBrainz didn't answer — ${mb.lastOutcome ?: "no reply"}"
        else -> "Nothing to test"
    }
}
