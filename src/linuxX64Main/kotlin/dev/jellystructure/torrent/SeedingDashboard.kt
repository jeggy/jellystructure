package dev.jellystructure.torrent

import dev.jellystructure.model.DashboardRow

/**
 * Phase 315 (FR-315-3/-4) — the Dashboard's two Services rows: edits refused this week to keep a seeding torrent intact
 * (information), and the torrents an earlier in-place edit may have changed (from [SeedingDamageCheck]).
 */
fun seedingDashboardRows(): List<DashboardRow> {
    val rows = ArrayList<DashboardRow>()
    val refused = SeedingRefusals.lastWeek()
    val total = refused.values.sum()
    if (total > 0) {
        val parts = refused.entries.sortedByDescending { it.value }.joinToString(", ") { (k, n) -> "$n $k ${if (n == 1) "file" else "files"}" }
        rows += DashboardRow(
            id = "seeding_refused", domain = "svc", severity = "info",
            label = "Edits refused to keep seeding torrents intact",
            sentence = "In the last 7 days ($parts). These files share their bytes with a torrent's copy, so they can't be changed in place.",
            count = total, unit = "edit", fix = "info",
        )
    }
    val check = SeedingDamageCheck.last()
    when {
        check == null -> rows += DashboardRow(
            id = "seeding_damage_check", domain = "svc", severity = "info",
            label = "Check whether earlier edits changed seeding torrents",
            sentence = "Until now a track edit could change a file a seeding torrent shares. One read-only pass finds the torrents; nothing is changed.",
            fix = "here", action = "Check", actionId = "seeding_damage_check",
        )
        check.running -> rows += DashboardRow(
            id = "seeding_damage_check", domain = "svc", severity = "info",
            label = "Checking whether earlier edits changed seeding torrents…",
            sentence = "Each disk is read once, at low priority.", fix = "info",
        )
        check.error != null -> rows += DashboardRow(
            id = "seeding_damage_check", domain = "svc", severity = "info",
            label = "The seeding check didn't finish", sentence = check.error,
            fix = "here", action = "Try again", actionId = "seeding_damage_check",
        )
        check.torrentCount > 0 -> {
            val names = check.entries.flatMap { it.torrents }.distinctBy { it.hash }.map { it.name }
            val shown = names.take(5).joinToString(" · ") + if (names.size > 5) " · and ${names.size - 5} more" else ""
            rows += DashboardRow(
                id = "seeding_damage", domain = "svc", severity = "warning",
                label = "Seeding torrents that may hold changed pieces",
                sentence = "jellystructure edited these files in place while a seeding torrent shared them: $shown. A recheck in qBittorrent re-downloads only the changed pieces.",
                count = check.torrentCount, unit = "torrent", fix = "elsewhere", where = "qBittorrent",
                path = "Select the torrent › Force recheck",
            )
        }
    }
    return rows
}
