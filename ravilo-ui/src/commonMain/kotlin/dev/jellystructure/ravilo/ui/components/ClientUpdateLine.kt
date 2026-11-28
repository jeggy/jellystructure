package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.RaviloVersion

const val CLIENT_UPDATE_LINE_TAG = "client-update-line"

/** The release the quiet notice names (FR-1, FR-3), or null while there is none. */
@Composable
fun clientUpdateNotice(): RaviloVersion? = ClientUpdate.notice.collectAsState().value

/** Whether [surface]'s dot shows (FR-5 to FR-7). */
@Composable
fun clientUpdateDotShows(surface: UpdateSurface): Boolean {
    val notice by ClientUpdate.notice.collectAsState()
    val seen by ClientUpdate.seenFlow.collectAsState()
    return updateDotFor(notice, seen[surface])
}

/** The version as the line names it: MAJOR.MINOR (FR-12). */
@Composable
fun clientUpdateHeadline(notice: RaviloVersion): String = str("update.available", mapOf("version" to notice.toString()))

/**
 * DEB-5851 (FR-4, FR-5) — the quiet two-line notice: *Ravilo 1.73 is available* and where to get it. Nothing when there
 * is no [notice]. With [openable], a tap opens Play's page or the releases page in the browser; the TV's About row is
 * not actionable, because a TV has no browser to send anyone to. [source] picks the second line's words.
 */
@Composable
fun ClientUpdateLine(notice: RaviloVersion?, source: InstallSource, openable: Boolean, modifier: Modifier = Modifier) {
    if (notice == null) return
    val colors = RaviloTheme.colors
    val uri = LocalUriHandler.current
    val tap = if (openable) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        runCatching { uri.openUri(clientUpdateUrl(source)) }
    } else Modifier
    Column(modifier.testTag(CLIENT_UPDATE_LINE_TAG).then(tap)) {
        Text(clientUpdateHeadline(notice), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
        Text(str(clientUpdateSecondLineKey(source)), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
    }
}

/** Marks [surface] looked at for the current notice as soon as it is on screen with one (the line itself stays). */
@Composable
fun MarkUpdateSeen(surface: UpdateSurface) {
    val notice by ClientUpdate.notice.collectAsState()
    LaunchedEffect(notice) { if (notice != null) ClientUpdate.markSeen(surface) }
}
