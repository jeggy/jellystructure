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
    val createdAt: Long = 0L,      // Phase 143
    val lastUsedAt: Long = 0L,     // Phase 143
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
    val createdAt: Long = 0L,     // Phase 143: for the Users & Devices admin overview
    // Phase 142: this user's allowed Jellyfin library ids (GUID-normalized), or null if unrestricted
    // (admin / EnableAllFolders). Gates the Ravilo catalog — see MediaStore.visibleTo.
    val allowedLibraries: Set<String>? = null,
    // Phase 142 follow-up: this user's Jellyfin Policy AllowedTags/BlockedTags (lowercased), empty sets
    // when Jellyfin sent none. Gates the Ravilo catalog alongside allowedLibraries.
    val allowedTags: Set<String> = emptySet(),
    val blockedTags: Set<String> = emptySet(),
    // Phase 224 (FR-224-2): the build and platform this device last reported (R252's headers). Null =
    // never reported; the Jellyfin header then carries no Version, and the admin row says so.
    val appVersion: String? = null,
    val platform: String? = null,
    // Phase 236 (FR-236-1): tv | phone | web | cast | screen. Defaults to "tv" for every construction
    // site that predates this phase (tests, and any code not yet updated) — the same historical default
    // 47.sqm backfills a pre-R252 row to.
    val kind: String = "tv",
    // Phase 236 (FR-236-6): stamped on an events-socket open and a playback/status post. Null until
    // either has happened since this column existed.
    val lastPublicAddress: String? = null,
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
    // Phase 143 — display-only on the Users & Devices access line (Jellyfin 10.9+; absent on older
    // servers, hence the safe empty-list default). Enforcement is a documented follow-up to Phase 142,
    // not built here.
    @SerialName("AllowedTags") val allowedTags: List<String> = emptyList(),
    @SerialName("BlockedTags") val blockedTags: List<String> = emptyList(),
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Policy") val policy: JellyfinPolicy,
    // Phase 187 (FR-187-7) — the change-keyed avatar cache-busting id; null when the user has no photo.
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class JellyfinAuthResponse(
    @SerialName("User") val user: JellyfinUser,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class JellyfinLibraryOptions(
    // Phase 242 (FR-242-4) — NULLABLE, deliberately, and this is the decision FR-242-7 stands or falls
    // on. `OutboundHttp` parses with `ignoreUnknownKeys = true`, so a key Jellyfin renames deserialises
    // to whatever default is declared here. For a list whose healthy value is *also* empty — every field
    // below except the booleans — a value default makes "Jellyfin renamed this" and "this is correctly
    // empty" the same observation, and the check fails open, silently, forever. Null is the third state
    // that makes them distinguishable. Confirmed live against 12.1.0 (GET /Library/VirtualFolders,
    // 2026-09-19), where `MetadataSavers` is genuinely absent on two libraries and `[]` on two others.
    @SerialName("MetadataSavers") val metadataSavers: List<String>? = null,
    @SerialName("EnableInternetProviders") val enableInternetProviders: Boolean? = null,
    @SerialName("TypeOptions") val typeOptions: List<JellyfinTypeOptions>? = null,
    // Phase 212 — the flags FR-212-4's findings read. Confirmed live against 10.11.11
    // (GET /Library/VirtualFolders, 2026-09-15) and re-confirmed unchanged on 12.1.0 by the
    // 2026-09-18 upgrade audit — every key here is the real JSON field name.
    @SerialName("EnableChapterImageExtraction") val enableChapterImageExtraction: Boolean = false,
    @SerialName("ExtractChapterImagesDuringLibraryScan") val extractChapterImagesDuringLibraryScan: Boolean = false,
    @SerialName("EnableTrickplayImageExtraction") val enableTrickplayImageExtraction: Boolean = false,
    @SerialName("ExtractTrickplayImagesDuringLibraryScan") val extractTrickplayImagesDuringLibraryScan: Boolean = false,
    @SerialName("EnableLUFSScan") val enableLufsScan: Boolean = false,
)

// Phase 242 (FR-242-4) — the per-item-type fetcher lists, i.e. whether Jellyfin goes to an external
// metadata or image provider itself for this library. Confirmed live against 12.1.0
// (GET /Library/VirtualFolders, 2026-09-19): `TypeOptions` is absent entirely on libraries Jellyfin
// does not scan for media (`Recordings`, `Samlinger` here), which is why it is nullable.
@Serializable
data class JellyfinTypeOptions(
    @SerialName("Type") val type: String? = null,
    @SerialName("MetadataFetchers") val metadataFetchers: List<String>? = null,
    @SerialName("ImageFetchers") val imageFetchers: List<String>? = null,
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
    // Phase 251 (FR-251-2) — NULLABLE on purpose: null means "this payload does not carry the field",
    // which is a different fact from "Jellyfin says nothing is locked". Jellyfin 12.1 stopped sending
    // both on the `/Items?Ids=…&Fields=…` list shape (measured 2026-09-20 with the lock deliberately
    // SET and the request explicitly asking for them), while `GET /Items/{id}?userId=…` still returns
    // them unconditionally. With a `false`/`emptyList()` default the difference was invisible, so the
    // product quietly concluded nothing was ever locked — and then wrote that conclusion to its own
    // database. Every write site must keep the prior value on null.
    @SerialName("LockData") val lockData: Boolean? = null,
    @SerialName("LockedFields") val lockedFields: List<String>? = null,
    @SerialName("Tags") val tags: List<String> = emptyList(),
    // The library "date added" (ISO-8601 UTC), e.g. "2021-06-27T18:51:37.0000000Z". Display only as of
    // Phase 108 (JS-owned createdAt/updatedAt now drive "recently added"; scannedAt is the scan timestamp).
    @SerialName("DateCreated") val dateCreated: String? = null,
    /** Phase 225 (FR-225-3) — Jellyfin's own sort name ("Bear, The" / "bear"), so a title-sorted row files a title where Jellyfin does. */
    @SerialName("SortName") val sortName: String? = null,
    // Phase 108: Jellyfin's own "last updated" timestamp, display only (Overview ▸ Timestamps).
    // ⚠ Phase 251 (FR-251-3): **Jellyfin 12.1 sends this on no shape at all** — not the list form, not
    // `GET /Items/{id}?userId=…`, with or without `Fields=DateLastSaved` (measured 2026-09-20). It is
    // kept declared so that if a future server starts sending it again the value flows through, but
    // nothing may treat a null here as "never saved": it means "not reported". See
    // [dev.jellystructure.model.MediaItem.jellyfinUpdatedAt].
    @SerialName("DateLastSaved") val dateLastSaved: String? = null,
    // Phase 114 — set on Episode-type items; used to resolve a new episode back to its parent series
    // for a targeted re-scan (jellystructure has no standalone "episode" ingest path).
    @SerialName("SeriesId") val seriesId: String? = null,
)

@Serializable
data class JellyfinItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    // Phase 181 (FR-181-1) — needed to page the full-catalog sweep to completion rather than trusting
    // Jellyfin to return everything unpaged (it does today at this library's size, but relying on that
    // forever is exactly the "Limit is never a cap" mistake R219 already paid for once).
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

/** Phase 207 — the `Fields=MediaStreams` sibling of [JellyfinItemsResponse], for `/Items?Ids=…` reads
 *  that want [JellyfinItemDetail] (UserData + MediaStreams) rather than the plain [JellyfinItem]. */
@Serializable
data class JellyfinItemDetailsResponse(
    @SerialName("Items") val items: List<JellyfinItemDetail> = emptyList(),
)

/** Phase 163 (step 6) — Jellyfin's own `GetItemSegments` shape. Ticks are 100ns units (Jellyfin's usual
 *  convention, matching this codebase's own TICKS_PER_MS constant elsewhere). */
@Serializable
data class JellyfinMediaSegment(
    @SerialName("Type") val type: String = "Unknown",
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0,
)

@Serializable
data class JellyfinMediaSegmentsResponse(
    @SerialName("Items") val items: List<JellyfinMediaSegment> = emptyList(),
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    // Phase 143 — ISO-8601 UTC; when this item was last played, for the Users & Devices "Recently
    // watched" history (sorted server-side via SortBy=DatePlayed).
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    // Bug fix — Ravilo's "My List"/Favorite state: this field already came back on every UserData
    // response (bulk playstate + item detail) but was never modeled, so the detail screens' "+ My
    // List" button had no state to read and was never wired to write it either.
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    /** Phase 269 (FR-269-4) — how many times this viewer played it; > 1 is a rewatch. */
    @SerialName("PlayCount") val playCount: Int = 0,
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
    // R219 (FR-R219-2) — reported independently of Limit; the only reliable guard against a paged fetch
    // silently stopping short. See the merge-completeness rule at the top of phase-R219's spec.
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
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
    // Phase 147 — Live TV's MediaSource carries these; a VOD MediaSource never sets them (defaults are
    // the VOD case). `RequiresOpening`/`RequiresClosing` gate the LiveStreams/Open+Close lifecycle
    // (dev-review addendum D — Live TV is NOT a branch of the VOD PlaybackInfo→stream flow).
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("IsInfiniteStream") val isInfiniteStream: Boolean = false,
    @SerialName("RequiresOpening") val requiresOpening: Boolean = false,
    @SerialName("RequiresClosing") val requiresClosing: Boolean = false,
    @SerialName("OpenToken") val openToken: String? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
)

// ── Phase 147 — Live TV (surfaced from Jellyfin; jellystructure never manages tuners/guide) ──────────

@Serializable
data class JellyfinLiveTvInfo(
    @SerialName("IsEnabled") val isEnabled: Boolean = false,
)

@Serializable
data class JellyfinChannelImageTags(
    @SerialName("Primary") val primary: String? = null,
)

/** Dev-review addendum C: `CurrentProgram` is embedded on the channel itself — no separate Programs
 *  call is needed for "On now" data. Addendum B: no category/kids/news/sports data exists here. */
@Serializable
data class JellyfinLiveTvProgram(
    @SerialName("Name") val name: String = "",
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("ChannelId") val channelId: String? = null,
    @SerialName("IsSeries") val isSeries: Boolean = false,
)

@Serializable
data class JellyfinLiveTvChannel(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ImageTags") val imageTags: JellyfinChannelImageTags? = null,
    @SerialName("CurrentProgram") val currentProgram: JellyfinLiveTvProgram? = null,
)

@Serializable
data class JellyfinLiveTvChannelsResponse(
    @SerialName("Items") val items: List<JellyfinLiveTvChannel> = emptyList(),
)

@Serializable
data class JellyfinLiveTvProgramsResponse(
    @SerialName("Items") val items: List<JellyfinLiveTvProgram> = emptyList(),
)

/** `POST /LiveStreams/Open` response — activates the tuner/provider stream; `mediaSource.path`
 *  is the definitive playable URL, `mediaSource.liveStreamId` (or top-level [id]) closes it later. */
@Serializable
data class JellyfinLiveStreamOpenResponse(
    @SerialName("MediaSource") val mediaSource: JellyfinMediaSourceInfo? = null,
    @SerialName("Id") val id: String? = null,
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
    // Phase 152 — fallback join key when Jellyfin never numbered this episode (no IndexNumber).
    @SerialName("Path") val path: String? = null,
    // Phase 207 — populated only when the caller asks with `Fields=MediaStreams`
    // (getSeriesEpisodesMediaStreams); getSeriesEpisodesMeta's own Fields list doesn't request it,
    // so this is an empty list there, same as any field Jellyfin simply wasn't asked for.
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStream> = emptyList(),
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

// Phase 165 — GET /Plugins (installed plugins) and GET /Packages (the default repository's catalog),
// used to detect whether the Webhook plugin is installed and, if not, whether it's installable.
@Serializable
data class JellyfinPluginInfo(
    @SerialName("Name") val name: String,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Status") val status: String? = null,   // "Active" | "Restart" | "Superseded" | ...
)

@Serializable
data class JellyfinPackageVersion(
    @SerialName("version") val version: String? = null,
    @SerialName("targetAbi") val targetAbi: String? = null,
)

@Serializable
data class JellyfinPackageInfo(
    @SerialName("name") val name: String,
    @SerialName("guid") val guid: String? = null,
    @SerialName("versions") val versions: List<JellyfinPackageVersion> = emptyList(),
)

// Phase 212 — GET /System/Configuration/encoding, the source for FR-212-5's server-wide findings.
// Field names confirmed live against 10.11.11, 2026-09-15. Deliberately narrow (not the full
// EncodingOptions document) — only what the advisor's predicates actually read.
@Serializable
data class JellyfinEncodingConfig(
    @SerialName("TranscodingTempPath") val transcodingTempPath: String? = null,
    // Phase 246 (FR-246-1) — the field that decides whether [enableHardwareEncoding] means anything.
    // Confirmed live against 12.1.0 (GET /System/Configuration/encoding, 2026-09-19): a string enum
    // whose disabled value is "none". `EnableHardwareEncoding` is true on this server while this reads
    // "none", i.e. hardware encoding is advertised and inert — which is precisely the state phase 212
    // could not see and reported as "Hardware encoding: On".
    @SerialName("HardwareAccelerationType") val hardwareAccelerationType: String? = null,
    @SerialName("EnableThrottling") val enableThrottling: Boolean = false,
    @SerialName("ThrottleDelaySeconds") val throttleDelaySeconds: Int = 0,
    @SerialName("EnableSegmentDeletion") val enableSegmentDeletion: Boolean = false,
    @SerialName("SegmentKeepSeconds") val segmentKeepSeconds: Int = 0,
    @SerialName("EnableHardwareEncoding") val enableHardwareEncoding: Boolean = false,
    @SerialName("AllowHevcEncoding") val allowHevcEncoding: Boolean = false,
    @SerialName("AllowOnDemandMetadataBasedKeyframeExtractionForExtensions")
    val allowOnDemandMetadataBasedKeyframeExtractionForExtensions: List<String> = emptyList(),
)

// Phase 212 — GET /System/Info (authenticated; distinct from the public /System/Info/Public
// [testConnection] already uses, which does not carry HasPendingRestart).
@Serializable
data class JellyfinSystemInfoAuth(
    @SerialName("HasPendingRestart") val hasPendingRestart: Boolean = false,
    // Phase 246 (FR-246-4) — the RESOLVED transcode directory, which is not the same thing as the
    // encoding configuration's `TranscodingTempPath`: that one is unset (meaning "use the default")
    // on this server, while this one reports the default Jellyfin actually picked. Confirmed live
    // against 12.1.0 (GET /System/Info, 2026-09-19), where it reads "/cache/transcodes" — having read
    // "/transcode" before the 10.11.11 -> 12.1.0 upgrade. Printing the unset value as "(default)" is
    // what hid that move.
    @SerialName("TranscodingTempPath") val transcodingTempPath: String? = null,
)

// Phase 244 (FR-244-1) — GET /System/Configuration/network. `KnownProxies` is the field that decides
// whether Jellyfin honours `X-Forwarded-For` at all: with it empty Jellyfin ignores the header entirely
// and classifies every caller by the address it sees, which behind a reverse proxy is the proxy's own
// private address — so every internet request is "in network". `EnableRemoteAccess` is what FR-244-2
// gates on, per the dev review: it is a fact about the server the finding describes, whereas
// jellystructure's own `public_url` is a fact about a different server that merely correlates here.
// Confirmed live against 12.1.0, 2026-09-19.
@Serializable
data class JellyfinNetworkConfig(
    @SerialName("KnownProxies") val knownProxies: List<String>? = null,
    @SerialName("EnableRemoteAccess") val enableRemoteAccess: Boolean? = null,
)

// Phase 244 (FR-244-1) — GET /System/Endpoint. The capability probe's answer: how Jellyfin classifies
// the caller it is currently answering. Confirmed live against 12.1.0, 2026-09-19.
@Serializable
data class JellyfinEndpointInfo(
    @SerialName("IsLocal") val isLocal: Boolean = false,
    @SerialName("IsInNetwork") val isInNetwork: Boolean = false,
)

// Phase 165 amendment (2026-08-14, FR-165-8) — GET /ScheduledTasks, used to find the Webhook plugin's
// own "Webhook Item Added Notifier" task by its stable Key (never assume the Id is stable across
// installs) so the live delivery probe can trigger it.
@Serializable
data class JellyfinTaskInfo(
    @SerialName("Id") val id: String,
    @SerialName("Key") val key: String? = null,
    @SerialName("Name") val name: String? = null,
    // Phase 246 (FR-246-12) — what the task is doing right now, so the extraction findings can state
    // observed work instead of a hypothetical cost. Confirmed live against 12.1.0 (GET /ScheduledTasks,
    // 2026-09-19): `State` is "Idle"/"Running"/"Cancelling", `CurrentProgressPercentage` is present
    // only while running, and `LastExecutionResult` is null until the task has completed once.
    @SerialName("State") val state: String? = null,
    @SerialName("CurrentProgressPercentage") val currentProgressPercentage: Double? = null,
    @SerialName("LastExecutionResult") val lastExecutionResult: JellyfinTaskResult? = null,
)

@Serializable
data class JellyfinTaskResult(
    @SerialName("EndTimeUtc") val endTimeUtc: String? = null,
    @SerialName("Status") val status: String? = null,
)
