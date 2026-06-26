package dev.jellystructure.tv

/** Build a Jellyfin image URL with server-side size caps (R63). Jellyfin honours fillWidth/fillHeight
 *  by downscaling before encoding, so the client receives a fraction of the full-resolution bytes. */
object JellyfinImageUrl {
    fun poster(base: String, jellyfinId: String, token: String) =
        "$base/Items/$jellyfinId/Images/Primary?api_key=$token&fillHeight=480&fillWidth=320&quality=90"

    fun backdrop(base: String, jellyfinId: String, token: String) =
        "$base/Items/$jellyfinId/Images/Backdrop/0?api_key=$token&fillWidth=1920&quality=90"

    fun heroBackdrop(base: String, jellyfinId: String, token: String) =
        "$base/Items/$jellyfinId/Images/Backdrop/0?api_key=$token&fillWidth=1920&quality=90"

    fun logo(base: String, jellyfinId: String, token: String) =
        "$base/Items/$jellyfinId/Images/Logo?api_key=$token&fillHeight=300"

    fun still(base: String, jellyfinId: String, token: String) =
        "$base/Items/$jellyfinId/Images/Primary?api_key=$token&fillWidth=640&quality=90"

    fun avatar(base: String, userId: String, userToken: String) =
        "$base/Users/$userId/Images/Primary?api_key=$userToken&fillHeight=160"
}
