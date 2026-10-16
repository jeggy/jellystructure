package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind

/**
 * R149 / DEB-1482 (FR-2) — the poster badge's two wire fields, from one condition so Home, Browse and Detail
 * cannot drift. `upcoming_episode` is the bare code an installed app wraps in its own "Soon • …"; `upcoming_air_utc`
 * is present exactly when it is.
 */
internal fun MediaItem.hasUpcomingEpisode(sonarrEnabled: Boolean): Boolean =
    sonarrEnabled && kind == MediaKind.TV_SHOW && sonarrStatus != "ended" && sonarrNextAiringDate != null &&
        sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null

internal fun MediaItem.upcomingEpisodeCode(sonarrEnabled: Boolean): String? =
    if (hasUpcomingEpisode(sonarrEnabled))
        "S${sonarrNextAiringSeason.toString().padStart(2, '0')}E${sonarrNextAiringEpisode.toString().padStart(2, '0')}"
    else null

internal fun MediaItem.upcomingAirUtc(sonarrEnabled: Boolean): String? =
    if (hasUpcomingEpisode(sonarrEnabled)) sonarrNextAiringUtc else null
