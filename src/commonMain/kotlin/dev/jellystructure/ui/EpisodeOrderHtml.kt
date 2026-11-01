package dev.jellystructure.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DEB-2916 (FR-17) — the admin's *Episode order* control on a series page.
 *
 * In `commonMain` for the reason `MusicCoverHtml.kt` gives: the admin frontend has no test target of its own and
 * the markup is the requirement. Nothing here may touch the DOM.
 */

/** FR-2's two fields, as `GET /api/media/{id}` carries them for a series and `PUT …/episode-order` answers them. */
@Serializable
data class EpisodeOrderState(
    @SerialName("newest_first") val newestFirst: Boolean = false,
    /** `"auto"`, `"admin"` or `"off"` (FR-2). */
    @SerialName("newest_first_source") val newestFirstSource: String = "off",
)

internal const val EPISODE_ORDER_MOUNT_ID = "ep-order-mount"

/** FR-17's words — English literals, the admin has no string table (as DEB-2657's FR-13). */
internal const val EPISODE_ORDER_HELP = "How Ravilo lists this series' episodes, and what <b>Play</b> starts."
internal const val EPISODE_ORDER_NOTE_AUTO = "Set by Auto-fetch: numbered by air date."
internal const val EPISODE_ORDER_NOTE_ADMIN = "Set by hand. Auto-fetch will not change it."

/** The slot the control is loaded into, at the top of the episodes tab. */
internal fun episodeOrderShellHtml(): String =
    """<div class="card" id="$EPISODE_ORDER_MOUNT_ID" style="margin-bottom:16px;"><span class="muted tiny">Loading…</span></div>"""

/** The note beside the control — none when the series is not fed by Auto-fetch and the admin has not chosen. */
internal fun episodeOrderNote(source: String): String? = when (source) {
    "auto" -> EPISODE_ORDER_NOTE_AUTO
    "admin" -> EPISODE_ORDER_NOTE_ADMIN
    else -> null
}

/** The card's contents. [order] null is a read that failed. */
internal fun episodeOrderHtml(order: EpisodeOrderState?): String {
    if (order == null) {
        return """<h4 style="margin:0;">Episode order</h4><div class="tiny muted" style="margin-top:6px;">Could not read the episode order.</div>"""
    }
    val note = episodeOrderNote(order.newestFirstSource)
    return """
      <div class="row center" style="gap:12px;flex-wrap:wrap;">
        <h4 style="margin:0;">Episode order</h4>
        <span class="seg" id="ep-order-seg">
          <span class="${if (!order.newestFirst) "on" else ""}" data-order="oldest">Oldest first</span>
          <span class="${if (order.newestFirst) "on" else ""}" data-order="newest">Newest first</span>
        </span>
        ${if (note != null) """<span class="tiny muted" id="ep-order-note">$note</span>""" else ""}
      </div>
      <div class="tiny muted" style="margin-top:6px;">$EPISODE_ORDER_HELP</div>"""
}
