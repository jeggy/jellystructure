package dev.jellystructure.model

/**
 * Phase 268 (FR-268-4) — THE size formatter for every admin surface: decimal units (1 GB = 1,000,000,000
 * bytes, as a drive is sold), one decimal place below 100 of a unit and none above (*23.4 GB*, *812 MB*,
 * *16.1 TB*). Unknown is *—*, never *0*.
 */
fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "—"
    val units = listOf("B", "kB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000.0 && unit < units.size - 1) { value /= 1000.0; unit++ }
    if (unit == 0) return "$bytes B"
    if (value >= 99.95) return "${(value + 0.5).toLong()} ${units[unit]}"
    val tenths = (value * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
