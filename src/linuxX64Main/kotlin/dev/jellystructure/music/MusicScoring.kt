package dev.jellystructure.music

import dev.jellystructure.model.MusicCandidate
import dev.jellystructure.model.MusicGenreVote
import dev.jellystructure.model.MusicMbCredit
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicTrack
import kotlin.math.abs

/**
 * Phase 276 (FR-276-3) — how a candidate album is judged against the tracks on disk. Pure.
 *
 * A candidate is a release-group; each of its releases is checked track by track: the release's track at the same
 * disc and position, with a length within [LENGTH_TOLERANCE_MS] of the file's (or, with no lengths to compare, the
 * same title). A **partial album is normal** — the household holds one or two tracks of most albums — so the fit is
 * "how many of the tracks we have agree", never "how many of the release's tracks we have".
 *
 * The pick rule came out of the dry run over the household's 30 albums (2026-09-28, spec's acceptance 1): every album
 * text search could place had its best candidate strictly ahead of the next on agreeing tracks, with MusicBrainz's own
 * score at 100. So: **pick** when the best candidate agrees on at least one track *and* at least half the tracks on
 * disk, has a search score of at least [MIN_SCORE], and agrees on more tracks than the second; a tie is **needs you**;
 * a candidate with no agreeing track is never used (FR-276-4).
 */
object MusicScoring {
    const val LENGTH_TOLERANCE_MS = 3_000L
    const val MIN_SCORE = 80

    data class TrackHit(val track: MusicTrack, val mb: MbTrack?, val agrees: Boolean, val offMs: Long?)

    data class Agreement(val agreeing: Int, val total: Int, val offMaxSec: Int?, val hits: List<TrackHit>)

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    fun agreement(release: MbRelease, tracks: List<MusicTrack>): Agreement {
        val onDisk = tracks.filter { it.missingSince == null && it.position != null }
        val hits = onDisk.map { t ->
            val disc = t.disc ?: 1
            val media = release.media.filter { it.position == disc }.ifEmpty { if (release.media.size == 1) release.media else emptyList() }
            val mb = media.firstNotNullOfOrNull { m -> m.tracks.firstOrNull { it.position == t.position } }
            val mbLen = mb?.length ?: mb?.recording?.length
            val diskLen = t.durationMs
            when {
                mb == null -> TrackHit(t, null, false, null)
                mbLen != null && diskLen != null -> abs(mbLen - diskLen).let { TrackHit(t, mb, it <= LENGTH_TOLERANCE_MS, it) }
                else -> TrackHit(t, mb, norm(mb.title) == norm(t.title), null)
            }
        }
        val offMax = hits.filter { !it.agrees }.mapNotNull { it.offMs }.maxOrNull()?.let { ((it + 500) / 1000).toInt() }
        return Agreement(hits.count { it.agrees }, onDisk.size, offMax, hits)
    }

    /** The pressing that agrees best; ties go to an official release, then the one whose track count is closest to
     *  what the folder implies. Null for a release-group with no releases. */
    fun bestRelease(releases: List<MbRelease>, tracks: List<MusicTrack>): Pair<MbRelease, Agreement>? {
        val implied = tracks.mapNotNull { it.position }.maxOrNull() ?: 0
        return releases.map { it to agreement(it, tracks) }.maxWithOrNull(
            compareBy<Pair<MbRelease, Agreement>>({ it.second.agreeing })
                .thenBy { if (it.first.status.equals("Official", ignoreCase = true)) 1 else 0 }
                .thenBy { -abs(it.first.media.sumOf { m -> m.trackCount } - implied) },
        )
    }

    fun option(release: MbRelease, agreement: Agreement?): MusicReleaseOption = MusicReleaseOption(
        mbid = release.id,
        title = release.title,
        country = release.country,
        date = release.date,
        label = release.labelInfo.firstNotNullOfOrNull { it.label?.name?.takeIf { n -> n.isNotBlank() } },
        format = release.media.mapNotNull { it.format }.distinct().joinToString(" + ").ifBlank { null },
        trackCount = release.media.sumOf { it.trackCount },
        agreeing = agreement?.agreeing ?: 0,
        hasFront = release.coverArtArchive?.front == true,
        disambiguation = release.disambiguation?.trim()?.takeIf { it.isNotEmpty() },
    )

    /**
     * Phase 290 (FR-290-5) — *Find match…*'s pressings: best agreement first; among equals the pressing the album uses
     * now, then the one the files already name, then oldest first.
     */
    fun orderReleases(options: List<MusicReleaseOption>, current: String?, named: String?): List<MusicReleaseOption> =
        options.sortedWith(
            compareByDescending<MusicReleaseOption> { it.agreeing }
                .thenBy { if (current != null && it.mbid == current) 0 else 1 }
                .thenBy { if (named != null && it.mbid == named) 0 else 1 }
                .thenBy { it.date ?: "9999" },
        )

    fun candidate(rg: MbReleaseGroup, best: Pair<MbRelease, Agreement>?, total: Int, source: String = "search"): MusicCandidate =
        MusicCandidate(
            releaseGroupMbid = rg.id,
            title = rg.title,
            artist = creditText(rg.artistCredit.ifEmpty { best?.first?.artistCredit.orEmpty() }),
            primaryType = rg.primaryType,
            secondaryTypes = rg.secondaryTypes,
            firstReleaseDate = rg.firstReleaseDate,
            score = rg.score ?: 0,
            agreeing = best?.second?.agreeing ?: 0,
            total = best?.second?.total ?: total,
            lengthOffMaxSec = best?.second?.offMaxSec,
            bestRelease = best?.let { option(it.first, it.second) },
            source = source,
        )

    sealed class Decision {
        data class Pick(val candidate: MusicCandidate) : Decision()
        data class NeedsYou(val note: String) : Decision()
        data class Unmatched(val note: String) : Decision()
    }

    fun decide(candidates: List<MusicCandidate>): Decision {
        val usable = candidates.filter { it.agreeing > 0 }
            .sortedWith(compareByDescending<MusicCandidate> { it.agreeing }.thenByDescending { it.score })
        if (usable.isEmpty()) return Decision.Unmatched(
            if (candidates.isEmpty()) "Nothing on MusicBrainz with this album's title and artist"
            else "${candidates.size} candidate${if (candidates.size == 1) "" else "s"}, but no track on disk agrees on position and length",
        )
        val top = usable[0]
        val second = usable.getOrNull(1)
        return when {
            second != null && second.agreeing == top.agreeing ->
                Decision.NeedsYou("${usable.count { it.agreeing == top.agreeing }} candidates agree equally (${top.agreeing} of ${top.total} tracks)")
            top.agreeing * 2 < top.total ->
                Decision.NeedsYou("The best candidate agrees on only ${top.agreeing} of ${top.total} tracks")
            top.score < MIN_SCORE ->
                Decision.NeedsYou("The best candidate agrees, but MusicBrainz is unsure of the name (score ${top.score})")
            else -> Decision.Pick(top)
        }
    }

    /** The sentence a candidate row shows (H2's lean): *2 of 2 tracks agree on position and length (partial album)*. */
    fun fitSentence(c: MusicCandidate): String = when {
        c.agreeing == 0 -> "No track agrees with that candidate"
        else -> buildString {
            append("${c.agreeing} of ${c.total} track${if (c.total == 1) "" else "s"} agree on position and length")
            val relTracks = c.bestRelease?.trackCount ?: 0
            if (relTracks > c.total) append(" (partial album)")
            c.lengthOffMaxSec?.let { if (c.agreeing < c.total) append(" · the rest off by up to $it s") }
        }
    }

    fun credits(list: List<MbArtistCredit>): List<MusicMbCredit> =
        list.mapNotNull { c -> c.artist?.id?.takeIf { it.isNotBlank() }?.let { MusicMbCredit(it, c.name.ifBlank { c.artist.name }, c.joinphrase) } }

    fun creditText(list: List<MbArtistCredit>): String = list.joinToString("") { it.name + it.joinphrase }.trim()

    fun votes(genres: List<MbGenre>): List<MusicGenreVote> =
        genres.filter { it.name.isNotBlank() }.map { MusicGenreVote(it.name, it.count) }.sortedByDescending { it.count }

    /** URL relationships by type; the first of each type wins. */
    fun urls(relations: List<MbRelation>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (r in relations) { val u = r.url?.resource ?: continue; if (r.type !in out) out[r.type] = u }
        return out
    }

    /** `1987–1994`, `1999–`, or null when nothing is known. */
    fun lifeSpan(ls: MbLifeSpan?): String? {
        val b = ls?.begin?.take(4)?.takeIf { it.isNotBlank() }
        val e = ls?.end?.take(4)?.takeIf { it.isNotBlank() }
        return when {
            b == null && e == null -> null
            e == null -> if (ls?.ended == true) "$b–?" else "$b–"
            else -> "${b ?: "?"}–$e"
        }
    }

    /** Pair a Jellyfin credit with MusicBrainz's by name (letters and digits only); a lone credit on both sides pairs. */
    fun pairCredit(name: String, mb: List<MusicMbCredit>): MusicMbCredit? =
        mb.firstOrNull { norm(it.name) == norm(name) } ?: mb.singleOrNull()?.takeIf { mbOnly -> mb.size == 1 && norm(name).isNotEmpty() && (norm(mbOnly.name).contains(norm(name)) || norm(name).contains(norm(mbOnly.name))) }
}
