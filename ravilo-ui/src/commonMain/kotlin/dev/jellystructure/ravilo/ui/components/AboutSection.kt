package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import dev.jellystructure.ravilo.ui.theme.raviloRowHeadPadB
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.AboutFacts

/**
 * R325 (FR-R325-1) — the About section under a film's or series' synopsis (after Episodes on a series): a grid of
 * label · value pairs, two columns where the width allows. A row is drawn only when the server sent its value;
 * nothing is invented and nothing says *Unknown*. Not focusable: it is read, never acted on (same as the flag line).
 */
@Composable
fun AboutSection(about: AboutFacts, series: Boolean, twoColumns: Boolean, modifier: Modifier = Modifier) {
    val rows = aboutRows(about, series)
    if (rows.isEmpty()) return
    val colors = RaviloTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
        Spacer(Modifier.height(raviloRowGap))
        Text(str("about.title"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = SpaceGrotesk, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(raviloRowHeadPadB))
        val perRow = if (twoColumns) 2 else 1
        rows.chunked(perRow).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                chunk.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                        Text(label.uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(value, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                    }
                }
                if (chunk.size < perRow) Spacer(Modifier.weight((perRow - chunk.size).toFloat()))
            }
        }
    }
}

/**
 * The rows in the spec's order, label already translated, each absent when its fact is. DEB-5699 (FR-56) — the rows are `:shared`'s
 * `aboutRows`, which the Samsung draws too; this passes the viewer's language as its lookup.
 */
@Composable
internal fun aboutRows(a: AboutFacts, series: Boolean): List<Pair<String, String>> {
    val lang = LocalLang.current
    return dev.jellystructure.shared.tv.aboutRows(a, series) { key, vars -> t(key, lang, vars) }
}
