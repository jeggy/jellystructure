package dev.jellystructure.shared.tv

/**
 * R346 (FR-R346-5) — the one spelling of an episode code a viewer reads: `S01E05`, and a range as
 * `S01E01–E03` (an en dash, the second episode with its own `E`). Every viewer screen and every label the
 * server builds for one (the Continue Watching next-up line, the play push's kicker) goes through here, so
 * the series page, the player, Home, Upcoming and a TV that plays from a push cannot disagree.
 *
 * Numbers are padded to two digits and never cut: episode 120 reads `E120`. [episodeEnd] is ignored when it
 * is not above [episode] (a lone episode). Display text only — never a wire value or a key.
 */
fun episodeCode(season: Int, episode: Int, episodeEnd: Int? = null): String {
    val base = "S${pad2(season)}E${pad2(episode)}"
    return if (episodeEnd != null && episodeEnd > episode) "$base–E${pad2(episodeEnd)}" else base
}

private fun pad2(n: Int): String = n.toString().padStart(2, '0')
