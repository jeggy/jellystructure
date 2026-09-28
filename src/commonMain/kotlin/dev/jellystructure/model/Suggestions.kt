package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 274 — the admin's Suggestions page, as the server resolves it: the page renders these and computes nothing
 * (constitution: server-pushed state). Admin-only; nothing here reaches a Ravilo app.
 */
@Serializable
data class SuggestionsPageDto(
    val built: Boolean = false,
    @SerialName("built_at") val builtAt: Long? = null,
    @SerialName("next_build_at") val nextBuildAt: Long? = null,
    /** `ai` · `fallback` — where this build's groups came from. */
    @SerialName("cluster_source") val clusterSource: String = "fallback",
    /** Why the fallback: `off` · `limit` · `bad` · `waiting`; empty when grouped by AI. */
    @SerialName("cluster_note") val clusterNote: String = "",
    @SerialName("seerr_ok") val seerrOk: Boolean = true,
    @SerialName("seerr_down_since") val seerrDownSince: Long? = null,
    /** Nobody has finished or started a film yet: nothing to build from. */
    @SerialName("no_history") val noHistory: Boolean = false,
    /** A build is running now (*Rebuild now* queued one, or the pipeline step). */
    val building: Boolean = false,
    @SerialName("finished_films") val finishedFilms: Int = 0,
    val viewers: List<SuggestionViewerDto> = emptyList(),
    val clusters: List<SuggestionClusterDto> = emptyList(),
    val items: List<SuggestionItemDto> = emptyList(),
    @SerialName("dismissed_count") val dismissedCount: Int = 0,
)

@Serializable
data class SuggestionViewerDto(val name: String, val finished: Int = 0)

/** One group, in share order; [shown] and [more] count its tiles above and below *Show more*. [finished] is how many
 *  finished films fall in it (series are in its share but not in this number, so the sentence stays true). */
@Serializable
data class SuggestionClusterDto(val name: String, val shown: Int = 0, val more: Int = 0, val finished: Int = 0)

@Serializable
data class SuggestionBecause(val viewer: String, val verb: String, val titles: List<String> = emptyList())

@Serializable
data class SuggestionItemDto(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val rating: Double? = null,
    @SerialName("runtime_min") val runtimeMin: Int? = null,
    val cert: String? = null,
    val synopsis: String? = null,
    /** A TMDB image path (`/abc.jpg`). */
    val poster: String? = null,
    val cluster: String = "",
    val because: List<SuggestionBecause> = emptyList(),
    val viewers: Int = 1,
    /** *First of a series you don't have — the match was {franchiseOf}.* */
    @SerialName("franchise_of") val franchiseOf: String? = null,
    /** *New this year, few ratings yet.* */
    val fresh: Boolean = false,
    /** Among the 20; false = under *Show more*. */
    val shown: Boolean = true,
    /** After *Download*: `requested` · `approved` · `downloading` · `library` · `refused`. */
    val state: String? = null,
    val progress: Int? = null,
    @SerialName("requested_for") val requestedFor: String? = null,
    /** FR-274-10a — the quality it was requested in (*HD-1080p · 4K*), for the tile's history; null before the dialog existed. */
    @SerialName("requested_in") val requestedIn: String? = null,
    /** Seerr's refusal, in one line. */
    val note: String? = null,
)

@Serializable
data class SuggestionDismissRequest(val reason: String, val note: String? = null)

/**
 * FR-274-10a — what the confirm dialog offers: Seerr's Radarr servers with their quality profiles and folders, exactly
 * as Seerr reports them. [canChoose] is whether the admin's own Seerr user may pick (Seerr's *Advanced requests*
 * permission); [steeredProfileId] is the profile 139's language steering would have applied for this admin, so the
 * dialog pre-selects it (marked with [steeredLabel]) instead of Seerr's default and never silently drops a rule.
 */
@Serializable
data class SuggestionRequestOptions(
    @SerialName("seerr_ok") val seerrOk: Boolean = true,
    val servers: List<SuggestionRadarrServer> = emptyList(),
    @SerialName("can_choose") val canChoose: Boolean = true,
    @SerialName("steered_profile_id") val steeredProfileId: Int? = null,
    @SerialName("steered_label") val steeredLabel: String? = null,
)

@Serializable
data class SuggestionRadarrServer(
    val id: Int,
    val name: String,
    @SerialName("is_4k") val is4k: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("active_profile_id") val activeProfileId: Int? = null,
    val profiles: List<SuggestionProfile> = emptyList(),
    val folders: List<String> = emptyList(),
    @SerialName("active_folder") val activeFolder: String? = null,
)

@Serializable
data class SuggestionProfile(val id: Int, val name: String)

/** FR-274-10a — the dialog's choice on `POST /{tmdbId}/download`; every field optional (Seerr's default when absent). */
@Serializable
data class SuggestionDownloadRequest(
    @SerialName("server_id") val serverId: Int? = null,
    @SerialName("profile_id") val profileId: Int? = null,
    @SerialName("root_folder") val rootFolder: String? = null,
)

@Serializable
data class SuggestionActionResult(val ok: Boolean, val sentence: String, val item: SuggestionItemDto? = null)

/** FR-274-12 — one title said no to, here or in Seerr's own blocklist ([fromSeerr]). */
@Serializable
data class SuggestionDismissedDto(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    /** `not_interested` · `already_seen` · `too_old` · `other` · `seerr`. */
    val reason: String,
    val note: String? = null,
    val who: String = "",
    val at: Long? = null,
    /** The blocklist write has landed (FR-274-11: *not in Seerr's blocklist yet* until it has). */
    @SerialName("in_seerr") val inSeerr: Boolean = true,
    @SerialName("from_seerr") val fromSeerr: Boolean = false,
)

/** FR-274-14 — the Dashboard card. */
@Serializable
data class SuggestionsSummaryDto(
    val waiting: Int = 0,
    @SerialName("new_count") val newCount: Int = 0,
    val since: Long? = null,
    @SerialName("top_clusters") val topClusters: List<String> = emptyList(),
    @SerialName("built_at") val builtAt: Long? = null,
)
