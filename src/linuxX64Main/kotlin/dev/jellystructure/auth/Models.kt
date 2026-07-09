package dev.jellystructure.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserProfile(val id: String, val name: String)

@Serializable
data class SessionData(
    val token: String,
    val jellyfinUserId: String,
    val jellyfinUsername: String,
    val jellyfinUserToken: String,
    val expiresAt: Long,
)

data class DeviceData(
    val deviceId: String,
    val deviceToken: String,
    val jellyfinUserId: String,
    val jellyfinUsername: String,
    val jellyfinUserToken: String,
    val isAdmin: Boolean,
    val isKids: Boolean = false,   // R18: Jellyfin user has a parental-rating cap
    val displayName: String = "", // Phase 110: the TV's own name ("Stue TV"), for the Jellyfin session bridge
    val lastSeen: Long = 0L,      // Phase 111: for the remote-control / Ravilo config editor device list
    // Phase 142: this user's allowed Jellyfin library ids (GUID-normalized), or null if unrestricted
    // (admin / EnableAllFolders). Gates the Ravilo catalog — see MediaStore.visibleTo.
    val allowedLibraries: Set<String>? = null,
)

@Serializable
data class JellyfinPolicy(
    @SerialName("IsAdministrator") val isAdministrator: Boolean,
    // R18: present (non-null) when the Jellyfin user is restricted to a max rating → treat as a "Kids" profile.
    @SerialName("MaxParentalRating") val maxParentalRating: Int? = null,
    // Phase 142: library-level access. EnabledFolders is a list of Jellyfin library ItemIds; it's
    // meaningless (and typically empty) when EnableAllFolders=true.
    @SerialName("EnableAllFolders") val enableAllFolders: Boolean = true,
    @SerialName("EnabledFolders") val enabledFolders: List<String> = emptyList(),
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Policy") val policy: JellyfinPolicy,
)

@Serializable
data class JellyfinAuthResponse(
    @SerialName("User") val user: JellyfinUser,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class JellyfinLibraryOptions(
    @SerialName("MetadataSavers") val metadataSavers: List<String> = emptyList(),
)

@Serializable
data class JellyfinLibrary(
    @SerialName("ItemId") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Locations") val locations: List<String> = emptyList(),
    @SerialName("LibraryOptions") val libraryOptions: JellyfinLibraryOptions? = null,
)

@Serializable
data class JellyfinProviderIds(
    @SerialName("Tmdb") val tmdb: String? = null,
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("ProviderIds") val providerIds: JellyfinProviderIds? = null,
    @SerialName("Type") val type: String,
    @SerialName("LockData") val lockData: Boolean = false,
    @SerialName("LockedFields") val lockedFields: List<String> = emptyList(),
    @SerialName("Tags") val tags: List<String> = emptyList(),
    // The library "date added" (ISO-8601 UTC), e.g. "2021-06-27T18:51:37.0000000Z". Display only as of
    // Phase 108 (JS-owned createdAt/updatedAt now drive "recently added"; scannedAt is the scan timestamp).
    @SerialName("DateCreated") val dateCreated: String? = null,
    // Phase 108: Jellyfin's own "last updated" timestamp, display only (Overview ▸ Timestamps).
    @SerialName("DateLastSaved") val dateLastSaved: String? = null,
    // Phase 114 — set on Episode-type items; used to resolve a new episode back to its parent series
    // for a targeted re-scan (jellystructure has no standalone "episode" ingest path).
    @SerialName("SeriesId") val seriesId: String? = null,
)

@Serializable
data class JellyfinItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
)

@Serializable
data class JellyfinPlayItem(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String,
    @SerialName("Name") val name: String,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
)

@Serializable
data class JellyfinPlayItemsResponse(
    @SerialName("Items") val items: List<JellyfinPlayItem> = emptyList(),
)

@Serializable
data class JellyfinItemDetail(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStream> = emptyList(),
)

@Serializable
data class JellyfinMediaStream(
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = -1,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    // R56: per-stream delivery negotiated by PlaybackInfo (Embed | External | Hls | Encode).
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

// R56: Jellyfin PlaybackInfo response (POST /Items/{id}/PlaybackInfo with a DeviceProfile).
@Serializable
data class JellyfinPlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
data class JellyfinMediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStream> = emptyList(),
)

@Serializable
data class JellyfinEpisodeItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    // Phase 108: display-only, best-effort — only populated when this fetch runs (jellyfinId backfill).
    @SerialName("DateCreated") val dateCreated: String? = null,
)

@Serializable
data class JellyfinEpisodesResponse(
    @SerialName("Items") val items: List<JellyfinEpisodeItem> = emptyList(),
)

/** R83: thin projection used by the bulk /Users/{userId}/Items?Ids=…&Fields=UserData call.
 *  Phase note (2026-07-08): also carries Type + RecursiveItemCount so callers can reject a series
 *  whose Jellyfin child rollup is empty — such a series reports UserData.Played=true vacuously
 *  (0 unplayed of 0), which otherwise paints a false ✓ on the tile even though episodes exist. */
@Serializable
data class JellyfinUserDataItem(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String = "",
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
)

@Serializable
data class JellyfinUserDataItemsResponse(
    @SerialName("Items") val items: List<JellyfinUserDataItem> = emptyList(),
)
