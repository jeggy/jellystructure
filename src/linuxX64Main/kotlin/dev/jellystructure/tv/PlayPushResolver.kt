package dev.jellystructure.tv

import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.ClearlogoInk
import dev.jellystructure.media.MediaStore

/**
 * R303 (FR-R303-2/7, dev review item 2) — the fields a `play_item` push carries so that the player
 * fetches nothing on either road that does not start at a detail screen: the phone-driven TV play
 * (`POST /api/remote/play`) and the Jellyfin dashboard's own *Play on* (the Phase 110 bridge). The logo
 * URL is the same versioned proxy URL the detail payloads carry (R214), the ink the same Phase 232
 * judgement, resolved from the same two services — one source, so the player shows what the detail
 * hero shows.
 */
class PlayPushResolver(
    private val mediaStore: MediaStore,
    private val artwork: ArtworkDownloader,
    /** Phase 232 — null in tests (= every logo's ink unknown, so no plate is ever drawn). */
    private val clearlogoInk: ClearlogoInk? = null,
    /** R264 (FR-R264-3) — the detail payloads' own intro/credits lookup ([DetailService.segmentsFor]), so a
     *  TV that plays from a push offers Skip Intro at exactly the moment the phone and the TV app do. */
    private val segments: ((itemId: String, episodeKey: String, episodeNumber: Int, legacyStinger: dev.jellystructure.model.Stinger?) -> dev.jellystructure.shared.tv.TvSegmentMarkers)? = null,
) {
    data class Resolved(
        val kind: String,
        val title: String?,
        val kicker: String?,
        val seriesName: String?,
        val logoUrl: String?,
        val logoInk: String?,
        /** R264 — intro/credits for Skip Intro and the next-up card; null when not wired or not in the library. */
        val segments: dev.jellystructure.shared.tv.TvSegmentMarkers? = null,
        /** R264 — what plays next, for the receiver's next-up card and auto-advance. */
        val next: dev.jellystructure.media.NextEpisode? = null,
    )

    /** `("movie", no title, nothing)` for an id this library does not hold — the pre-R303 behaviour. */
    suspend fun resolve(jellyfinId: String): Resolved {
        val push = mediaStore.resolvePlayPush(jellyfinId) ?: return Resolved("movie", null, null, null, null, null)
        // FR-R303-2/3 — only a logo that is really on disk: the detail payloads send the proxy URL
        // unconditionally and let the image 404 into the text fallback, but a receiver's <img> onerror is
        // a visible flash, so the push says nothing when there is nothing.
        val logoItem = push.logoItem?.takeIf { artwork.assetVersion(it, "clearlogo") > 0L }
        return Resolved(
            kind = push.kind,
            title = push.title,
            kicker = push.kicker,
            seriesName = push.seriesName,
            logoUrl = logoItem?.let { RaviloImageUrl.logo(it.id, artwork.assetVersion(it, "clearlogo")) },
            logoInk = logoItem?.let { clearlogoInk?.inkFor(it) },
            segments = push.segmentItemId?.let { id -> segments?.invoke(id, push.episodeKey, push.episodeNumber, push.legacyStinger) },
            next = push.next,
        )
    }
}
