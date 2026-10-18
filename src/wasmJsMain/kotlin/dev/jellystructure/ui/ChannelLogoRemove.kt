package dev.jellystructure.ui

import dev.jellystructure.api.RaviloApi
import dev.jellystructure.shared.tv.ChannelLogo

// DEB-1788 — what the two stored-logo pickers (the collection editor's and the Workbench's) share: a tile's title,
// the ✕'s confirm, and the one call that removes. The picker re-renders from a fresh GET afterwards; nothing is
// dropped from the screen before the server has answered.

/** FR-4 — the tile's title: its label, the live-text caution, and who uses it. */
internal fun ChannelLogo.pickerTitle(liveTextCaution: String): String = buildString {
    append(label)
    if (liveText) append(" — ").append(liveTextCaution)
    if (usedBy.isNotEmpty()) append(" — In use by ").append(usedBy.joinToString(", "))
}

/** FR-4 — whether a logo field currently holding [fieldValue] has this logo picked (relative or with a host). */
internal fun ChannelLogo.isHeldBy(fieldValue: String?): Boolean =
    !fieldValue.isNullOrBlank() && fieldValue.substringBefore('#').substringBefore('?').endsWith(url)

/** FR-4 — the ✕ shows on a tile nothing uses and the editor does not have picked. */
internal fun ChannelLogo.removable(fieldValue: String?): Boolean = usedBy.isEmpty() && !isHeldBy(fieldValue)

internal fun ChannelLogo.removeConfirm(): String =
    "Remove the logo \u201C$label\u201D? The file is deleted from the server, and this can't be undone."

/** FR-4/FR-5 — the confirm; true = go ahead. */
internal fun confirmRemoveLogo(logo: ChannelLogo): Boolean = kotlinx.browser.window.confirm(logo.removeConfirm())

/** FR-1 — removes; null on success, else the text for "Couldn't remove: …". */
internal suspend fun removeStoredLogo(logo: ChannelLogo): String? =
    runCatching { RaviloApi.removeChannelLogo(logo.url.substringAfterLast('/')) }.exceptionOrNull()?.let { it.message ?: "request failed" }

internal fun removeFailedLine(message: String): String =
    """<div class="alert-bad" data-logo-remove-failed style="margin-bottom:8px">Couldn't remove: ${message.replace("&", "&amp;").replace("<", "&lt;")}</div>"""
