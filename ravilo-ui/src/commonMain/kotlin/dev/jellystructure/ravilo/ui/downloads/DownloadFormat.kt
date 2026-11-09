package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption

/** The sheet's 1 GB of headroom: a choice larger than the free space minus this is disabled (FR-3). */
const val DOWNLOAD_RESERVE_BYTES = 1_000_000_000L

/**
 * A size as the round draws it: decimal, one decimal place from a gigabyte up ("7.9 GB"), whole megabytes below ("650 MB"). The
 * round's arithmetic is 1 GB = 1000 MB, so a reviewer can add the pictures up.
 */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    if (b >= 1_000_000_000L) {
        val tenths = (b + 50_000_000L) / 100_000_000L
        return "${tenths / 10}.${tenths % 10} GB"
    }
    val mb = (b + 500_000L) / 1_000_000L
    return "$mb MB"
}

/** The rung's picture height when the server did not say: 1080p / 720p / 480p are the encoder's rungs (FR-4). */
fun optionHeight(option: DownloadOption): Int? = option.height ?: when (option.quality) {
    "high" -> 1080
    "medium" -> 720
    "small" -> 480
    else -> null
}

/** `1080p` / `720p` / `480p`, or null for the original, which the sheet names with `download.original`. */
fun resolutionLabel(option: DownloadOption): String? =
    if (option.quality == "original") null else optionHeight(option)?.let { "${it}p" }
