package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.RaviloConfig

/**
 * DEB-1854 (FR-6, FR-7) — which of four things an account's *Change password* place draws. Decided from the viewer's
 * config alone (never stored, never derived on the client), so a missing field reads as [Form]: an older server and
 * a local account keep today's row.
 */
sealed interface PasswordRow {
    /** FR-6c — today's row and [ChangePasswordScreen]. */
    data object Form : PasswordRow
    /** FR-6a — managed elsewhere and the installation set a page: the same row with a link glyph, opening [url]. */
    data class Link(val url: String) : PasswordRow
    /** FR-6b — managed elsewhere, no page set: no row, and the one sentence in its place. */
    data object Line : PasswordRow
    /** FR-7 — managed elsewhere on a TV: no row and no line. */
    data object Hidden : PasswordRow

    /** Whether the D-pad chain has a row to land on here. */
    val focusable: Boolean get() = this is Form || this is Link
    val managedElsewhere: Boolean get() = this !is Form
}

/** FR-6 / FR-7 as a pure function of the wire fields, so a test can name every case without a platform. */
fun passwordRowOf(managedElsewhere: Boolean, url: String?, tv: Boolean): PasswordRow = when {
    !managedElsewhere -> PasswordRow.Form
    tv -> PasswordRow.Hidden
    !url.isNullOrBlank() -> PasswordRow.Link(url)
    else -> PasswordRow.Line
}

fun RaviloConfig.passwordRow(tv: Boolean = isTvPlatform): PasswordRow = passwordRowOf(passwordManagedElsewhere, passwordChangeUrl, tv)

/** Test tag on FR-6a's glyph, so a test can tell the link row from the form's row without reading a character. */
const val EXTERNAL_LINK_TAG = "pw-external-link"

/** FR-6a's glyph, after the label — drawn, not a character: the web app has no font that carries an arrow (R315). */
@Composable
fun ExternalLinkGlyph(tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 14.dp) =
    dev.jellystructure.ravilo.ui.components.DeskIcon(dev.jellystructure.ravilo.ui.components.DeskIcon.EXTERNAL, tint, size, Modifier.testTag(EXTERNAL_LINK_TAG))

/** FR-6b — the non-interactive sentence that stands where the row would be. */
@Composable
fun PasswordElsewhereLine(fontSize: Int = 14) {
    Text(str("account.pw_elsewhere_none"), color = RaviloTheme.colors.textSecondary, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
}

/** FR-8 (client half) — what [ChangePasswordScreen] shows in place of the form when the route says `managed_elsewhere`. */
@Composable
internal fun PasswordElsewhereBody(row: PasswordRow, onOpen: (String) -> Unit, linkFR: androidx.compose.ui.focus.FocusRequester, onUp: () -> Unit) {
    val colors = RaviloTheme.colors
    when (row) {
        is PasswordRow.Link -> {
            Text(str("account.pw_elsewhere_sub"), color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            AccountActionButton(
                label = str("account.pw_change"),
                focusRequester = linkFR, onSelect = { onOpen(row.url) }, onUp = onUp, tag = ChangePasswordTags.LINK, external = true,
            )
        }
        else -> Column { PasswordElsewhereLine() }
    }
}
