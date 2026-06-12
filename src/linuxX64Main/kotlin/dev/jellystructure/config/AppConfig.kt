package dev.jellystructure.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("api_keys") val apiKeys: ApiKeys = ApiKeys(),
    @SerialName("language_rules") val languageRules: LanguageRules = LanguageRules(),
    val behavior: Behavior = Behavior(),
    val libraries: List<LibraryMapping> = emptyList(),
)

@Serializable
data class ApiKeys(
    @SerialName("tmdb_v3_key") val tmdbV3Key: String = "",
    @SerialName("jellyfin_token") val jellyfinToken: String = "",
    @SerialName("jellyfin_url") val jellyfinUrl: String = "",
)

@Serializable
data class LanguageRules(
    @SerialName("fallback_language") val fallbackLanguage: String = "en",
)

@Serializable
data class Behavior(
    @SerialName("overwrite_nfo") val overwriteNfo: Boolean = false,
    @SerialName("fetch_images") val fetchImages: Boolean = true,
)

@Serializable
data class LibraryMapping(
    @SerialName("jellyfin_id") val jellyfinId: String = "",
    val name: String = "",
    @SerialName("collection_type") val collectionType: String = "",
    @SerialName("local_path") val localPath: String = "",
    val skip: Boolean = false,
)
