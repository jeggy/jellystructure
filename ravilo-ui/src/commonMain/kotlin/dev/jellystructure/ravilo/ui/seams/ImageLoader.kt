package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl

@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // R85: relative paths (e.g. /api/tv/image/{id}/poster) are resolved against the server base URL.
    // Absolute TMDB/Jellyfin URLs (https://…) pass through unchanged.
    val baseUrl = LocalServerBaseUrl.current
    val resolved = if (url.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$url" else url
    AsyncImage(
        model = resolved,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
    )
}
