package dev.jellystructure.ui

import dev.jellystructure.model.MediaItem

/*
 * DEB-5345 (FR-1, FR-3, FR-4) — a film or series card in the admin Library grid, as one `<a href="#/media/{id}">`.
 * In `commonMain` for the reason `SeasonNameHtml.kt` gives: the markup is the requirement and nothing here may touch
 * the DOM. A link, not a `div` with a click listener, so right-click copies the address and a modified click opens a
 * new tab. It holds only text, an `<img>` and `<span>` badges: an `<a>` may not contain another `<a>` or a `<button>`.
 */

internal const val TMDB_IMG = "https://image.tmdb.org/t/p/w342"

// Phase 133: posterPath is usually a TMDB file_path fragment ("/abc.jpg"), but a manually-uploaded
// poster with no TMDB equivalent instead holds the jellystructure-served on-disk URL
// ("/tv/image/{id}/poster") — route to it directly instead of prefixing the TMDB CDN.
internal fun posterSrc(posterPath: String, tmdbPrefix: String): String =
    if (posterPath.startsWith("/tv/image/")) posterPath else "$tmdbPrefix$posterPath"

internal fun posterCardHtml(item: MediaItem): String {
    val id = (item.jellyfinId ?: item.id).esc()
    val badge = when {
        item.languageMix  -> """<span class="badge warn" style="font-size:.62rem;">lang mix</span>"""
        item.issueCount > 0 -> """<span class="badge bad" style="font-size:.62rem;">${item.issueCount} issue${if (item.issueCount != 1) "s" else ""}</span>"""
        else              -> """<span class="badge ok" style="font-size:.62rem;">ok</span>"""
    }
    // Phase 184 (FR-184-8) — a small chip on the handful of hand-set titles; the automatic majority
    // stays unmarked (no chip at all when metadataLanguage is null).
    val langChip = item.metadataLanguage?.let {
        """ <span class="badge info lang" style="font-size:.62rem;" title="Metadata language manually set to ${it.esc()}">${it.esc()}</span>"""
    } ?: ""
    val imgContent = if (item.posterPath != null) {
        """<img src="${posterSrc(item.posterPath, TMDB_IMG).esc()}" alt="${item.title.esc()}" loading="lazy"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    } else {
        // Bug fix: posterPath is only ever set from a TMDB match or a manual Artwork-tab save — a real
        // on-disk poster that arrived any other way (bundled with the original download, or Phase 171's
        // per-basename music-video convention) leaves this null even though art genuinely exists. Fall
        // back to the real serving route (the same one the Artwork tab's own "on disk" status comes
        // from) instead of assuming null means missing; a genuine miss swaps to the placeholder via the
        // delegated "error" listener wired in renderLibrary() (img error events don't bubble, only
        // capture, hence the capture-phase listener there).
        """<img class="poster-fallback" src="/api/tv/image/$id/poster?w=320" alt="${item.title.esc()}" loading="lazy"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    }
    return """
        <a class="poster" href="#/media/$id" data-id="$id">
          <div class="imgslot">$imgContent</div>
          <div class="ttl">${item.title.esc()}</div>
          <div class="yr">${item.year ?: "—"} · $badge$langChip</div>
        </a>"""
}
