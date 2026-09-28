package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phases 275/276 — the admin's `/api/music/**` shapes, shared by the backend and the admin frontend so the two
// cannot drift. Nothing here reaches a Ravilo app (279 has its own DTOs in `:shared`).

/** FR-275-6 — the `/api/health` music block and the Library's status line. */
@Serializable
data class MusicHealth(
    val artists: Int,
    val albums: Int,
    val tracks: Int,
    val matched: Int,
    @SerialName("needs_you") val needsYou: Int,
    val unmatched: Int,
    @SerialName("covers_missing") val coversMissing: Int,
    @SerialName("artist_images_missing") val artistImagesMissing: Int,
    val reencodes: Int,
)

/** Where a matching pass stands — Activity and the Library's *Matching… n of 30*. */
@Serializable
data class MusicMatchStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    @SerialName("current_album") val currentAlbum: String? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("last_summary") val lastSummary: String? = null,
)

/** One row of Activity's *Outbound pacing* card for a fixed-rate host: what it ran at over the last minute. */
@Serializable
data class PacingStats(
    @SerialName("rate_per_sec") val ratePerSec: Double,
    @SerialName("ceiling_per_sec") val ceilingPerSec: Double,
    @SerialName("refused_last_minute") val refusedLastMinute: Int,
)

/** FR-276-5 — one recording offered by *Match this track…*. */
@Serializable
data class MusicRecordingOption(
    val mbid: String,
    val title: String,
    val artist: String,
    @SerialName("length_ms") val lengthMs: Long? = null,
    /** The first few releases it is on, as a reader would name them. */
    val releases: List<String> = emptyList(),
)

/** FR-276-4 — *Identify by sound*: one sentence and whatever candidates the sound pointed at. */
@Serializable
data class MusicSoundResult(val sentence: String, val candidates: List<MusicCandidate> = emptyList())

@Serializable
data class MusicStatusDto(
    val mapped: Boolean,
    val health: MusicHealth?,
    val match: MusicMatchStatus,
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean,
    @SerialName("acoustid") val acoustIdAvailable: Boolean,
)

@Serializable data class MusicMatchRequest(@SerialName("album_ids") val albumIds: List<String>? = null, val scope: String = "missing")
@Serializable data class MusicSearchRequest(val query: String? = null, val url: String? = null)
@Serializable data class MusicUseRequest(@SerialName("release_group") val releaseGroup: String, val release: String? = null, val lock: Boolean = false, val source: String = "manual")
@Serializable data class MusicLockRequest(val locked: Boolean)
@Serializable data class MusicGenresRequest(val genres: List<String>? = null)
@Serializable data class MusicRecordingRequest(val recording: String)

/** A candidate as Find match… shows it: the fit already in words (H2's lean). */
@Serializable
data class MusicCandidateDto(val candidate: MusicCandidate, val fit: String, val usable: Boolean)

/** FR-276-8 — the Metadata providers card. Keys never leave the server: `*_key_set` says whether one is saved. */
@Serializable
data class MusicProvidersDto(
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean,
    @SerialName("musicbrainz_contact") val musicbrainzContact: String,
    @SerialName("musicbrainz_contact_effective") val musicbrainzContactEffective: String,
    @SerialName("musicbrainz_rate") val musicbrainzRate: Double,
    @SerialName("musicbrainz_last") val musicbrainzLast: String? = null,
    @SerialName("acoustid_key_set") val acoustIdKeySet: Boolean,
    @SerialName("fanart_key_set") val fanartKeySet: Boolean,
    /** Phase 277 (FR-277-8) — the *Fetch lyrics* switch. */
    @SerialName("lyrics_enabled") val lyricsEnabled: Boolean = true,
)

/** Phase 277 — one picture a provider offers (FR-277-2). */
@Serializable
data class MusicArtCandidate(
    /** `front` / `back` / `booklet` / `albumcover` / `cdart` / `thumb` / `background` / `logo`. */
    val kind: String,
    val url: String,
    val thumb: String = url,
    /** `Cover Art Archive` / `fanart.tv` / `Wikimedia Commons`. */
    val source: String,
    val approved: Boolean = true,
    val credit: String? = null,
)

/** FR-277-2 — an album's Artwork tab: what is in use, and what the providers offer. */
@Serializable
data class MusicArtworkDto(
    @SerialName("in_use") val inUse: String? = null,
    @SerialName("in_use_bytes") val inUseBytes: Long? = null,
    val locked: Boolean = false,
    val source: String? = null,
    val candidates: List<MusicArtCandidate> = emptyList(),
)

/** FR-277-2 — an artist's Artwork tab: thumb (1:1), background (16:9), logo (transparent). */
@Serializable
data class MusicArtistArtworkDto(
    val thumb: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val locked: List<String> = emptyList(),
    val credit: String? = null,
    val candidates: List<MusicArtCandidate> = emptyList(),
    @SerialName("fanart_key") val fanartKey: Boolean = false,
)

@Serializable data class MusicArtUseRequest(val url: String, val kind: String = "front", val source: String = "manual", val credit: String? = null)
@Serializable data class MusicBiographyRequest(val text: String? = null)

/** FR-277-4/7 — the NFO tab: the path, the file as written, and how many fields differ if someone rewrote it. */
@Serializable
data class MusicNfoDto(val path: String? = null, val text: String? = null, val drift: Int? = null)

@Serializable
data class MusicProvidersUpdate(
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean? = null,
    @SerialName("musicbrainz_contact") val musicbrainzContact: String? = null,
    /** Absent = keep; "" = remove the saved key. */
    @SerialName("acoustid_key") val acoustIdKey: String? = null,
    @SerialName("fanart_key") val fanartKey: String? = null,
    @SerialName("lyrics_enabled") val lyricsEnabled: Boolean? = null,
)
