package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.TvApiClient

/** DEB-4070 (FR-1) — downloads are the Android phone's alone: no control on the desktops or the web. */
actual fun platformDownloads(api: TvApiClient): DownloadsController? = null
