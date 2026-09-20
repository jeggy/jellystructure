package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.BrowseKind
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.ALL_KIND

/**
 * R267 (FR-R267-5c) — the Library page's type filter: one pill in the handset top row, opening a
 * short menu anchored under itself.
 *
 * ## What makes this different from a permanent *Kategorier* button
 *
 * The slot it sits in is **empty on Home, Search, Discover and every pushed screen**. A filter must
 * not sit in the chrome of a page it does not apply to — that is the whole rule, and it is what stops
 * the slot becoming a home for controls with nowhere else to live.
 *
 * ## Music is offered even when the household holds none
 *
 * It reads `0` and the page says so. Music videos are a real library type (phase 172), and hiding a
 * filter the admin can fill next week is worse than an honest empty state.
 *
 * The pill reads the type's **own word**, never "Filter" or "Kategorier", and the choice does not
 * persist across app restarts — it is a browse control, not a setting.
 */
@Composable
fun LibraryTypePill(
    current: BrowseKind,
    counts: Map<String, Int>,
    onSelect: (BrowseKind) -> Unit,
) {
    val colors = RaviloTheme.colors
    var open by remember { mutableStateOf(false) }
    val types = listOf(BrowseKind.ALL, BrowseKind.MOVIES, BrowseKind.SERIES, BrowseKind.MUSIC)

    // The anchor wraps its content, so the popup below is positioned relative to the PILL rather
    // than to a box that has stretched across the row. Found on the Pixel 9: without this the menu
    // rendered full-width across the top of the screen instead of under its own control.
    Box(Modifier.wrapContentSize()) {
        Row(
            Modifier
                .heightIn(min = 46.dp)   // R234's target floor, not waived for a quiet control
                .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = libraryTypeLabel(current),
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Sora,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (open) "︿" else "﹀", color = colors.textDim, fontSize = 12.sp)
        }
        if (open) {
            // A tap anywhere outside closes it.
            Popup(
                // Anchored under itself, right-aligned with the pill (the pill sits at the right of
                // the top row, so a left-aligned menu would hang off-screen).
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 50.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(220.dp)
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .padding(vertical = 6.dp),
                ) {
                    types.forEach { t ->
                        val n = counts[t.apiKey ?: ALL_KIND]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { open = false; onSelect(t) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (t == current) "✓" else " ",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontFamily = Sora,
                            )
                            Text(
                                text = libraryTypeLabel(t),
                                color = colors.text,
                                fontSize = 15.sp,
                                fontWeight = if (t == current) FontWeight.SemiBold else FontWeight.Medium,
                                fontFamily = Sora,
                                modifier = Modifier.weight(1f),
                            )
                            // Absent rather than "0" only when the server did not report counts at
                            // all (an older backend): an actual zero is shown, because that is the
                            // honest empty state the Music option exists to give.
                            if (n != null) {
                                Text("$n", color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun libraryTypeLabel(kind: BrowseKind): String = when (kind) {
    BrowseKind.ALL -> str("lib.type.all")
    BrowseKind.MOVIES -> str("nav.movies")
    BrowseKind.SERIES -> str("nav.series")
    BrowseKind.MUSIC -> str("lib.type.music")
    BrowseKind.MY_LIST -> str("nav.my_list")
}
