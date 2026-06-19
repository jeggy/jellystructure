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
)

@Serializable
data class JellyfinPolicy(@SerialName("IsAdministrator") val isAdministrator: Boolean)

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
data class JellyfinLibrary(
    @SerialName("ItemId") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Locations") val locations: List<String> = emptyList(),
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
