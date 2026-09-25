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
) {
    data class Resolved(
        val kind: String,
        val title: String?,
        val kicker: String?,
        val seriesName: String?,
        val logoUrl: String?,
        val logoInk: String?,
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
        )
    }
}
