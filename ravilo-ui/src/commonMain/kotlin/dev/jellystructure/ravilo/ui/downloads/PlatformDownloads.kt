package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.runtime.staticCompositionLocalOf
import dev.jellystructure.shared.tv.TvApiClient

/**
 * DEB-4070 (DEB-3995 FR-1) — the Android phone's [DownloadsController], or null everywhere else: a TV, Tizen, the Chromecast receiver, the
 * web and the desktops get no download surface. The platform decides; a page that finds null draws nothing.
 */
expect fun platformDownloads(api: TvApiClient): DownloadsController?

/** Provided by the app for the signed-in viewer: non-null only on a phone, and only on a server whose config says `downloads` (FR-2). */
val LocalDownloads = staticCompositionLocalOf<DownloadsController?> { null }
