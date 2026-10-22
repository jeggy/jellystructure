package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ChevronGlyph
import dev.jellystructure.ravilo.ui.components.GlyphDirection
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.handCursor
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The control's two heights (R388 amendment): 38 dp in a top bar and on the desktop's page head, 28 dp on a text line. */
val PLAY_ALL_BAR_HEIGHT = 38.dp
val PLAY_ALL_LINE_HEIGHT = 28.dp

/** The tap target, whichever height is drawn (R388 amendment). */
val PLAY_ALL_TARGET = 44.dp

private val MENU_WIDTH = 176.dp

/**
 * R388 (DEB-1300) — *▶ Play all | ▾*: one split control, the album page's [SplitPlayButton] shape in the sort pill's
 * quiet fill. *Play all* plays; the ▾ opens a one-item menu, *⤮ Shuffle all*. It is drawn only on a row that is already
 * there; the caller says which (a top bar, a text line, the desktop's page head) through [height].
 *
 * While a request is in flight the *Play all* half's glyph is a progress ring and presses are ignored (FR-R388-8).
 */
@Composable
fun PlayAllControl(request: PlayAllRequest, runner: PlayAll, height: Dp, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    val busy by runner.busy.collectAsState()
    var open by remember { mutableStateOf(false) }
    val shuffleFocus = remember { FocusRequester() }
    val failText = str("music.fail_t")
    val context = request.context(str("music.from_library"), str("music.from_search", mapOf("q" to request.query.orEmpty())))

    fun press(half: PlayAllHalf) {
        scope.launch {
            runner.run(
                request, half, context,
                onFailure = { MusicToasts.show(failText) },
                onCapped = { shown, total ->
                    MusicToasts.show(t("music.selection_capped", lang, mapOf("shown" to groupedCount(shown, lang), "total" to groupedCount(total, lang))))
                },
            )
        }
    }

    // ② opens with *Shuffle all* focused (the desktop keyboard: ↓ and Enter pick it, Esc closes).
    LaunchedEffect(open) { if (open) { delay(60); runCatching { shuffleFocus.requestFocus() } } }

    val bar = height >= PLAY_ALL_BAR_HEIGHT
    val radius = height / 2
    val ink = colors.text
    val glyph = if (bar) 14.dp else 13.dp
    val line = colors.fg.copy(alpha = 0.14f)
    val target = maxOf(height, PLAY_ALL_TARGET)
    Box(modifier.height(target), contentAlignment = Alignment.CenterEnd) {
        // The pill is `height` tall and each half's tap area is 44 dp: the fill and border are drawn behind the halves.
        Row(
            Modifier.height(target).drawBehind {
                val h = height.toPx(); val top = (size.height - h) / 2
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
                drawRoundRect(colors.fg.copy(alpha = 0.09f), androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), r)
                drawRoundRect(line, androidx.compose.ui.geometry.Offset(0.5f, top + 0.5f), androidx.compose.ui.geometry.Size(size.width - 1f, h - 1f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Half(
                Modifier.height(target).padding(start = if (bar) 12.dp else 10.dp, end = 8.dp), height, RoundedCornerShape(topStart = radius, bottomStart = radius),
                onClick = { press(PlayAllHalf.PLAY) },
            ) {
                if (busy != null) CircularProgressIndicator(Modifier.size(glyph), color = ink, strokeWidth = 2.dp)
                else MusicGlyph(MusicIcon.PLAY, ink, glyph)
                Text(str("music.play_all"), color = ink, fontSize = if (bar) 13.sp else 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(height).background(line))
            Half(
                Modifier.height(target).padding(start = 7.dp, end = if (bar) 10.dp else 8.dp), height, RoundedCornerShape(topEnd = radius, bottomEnd = radius),
                onClick = { open = true },
            ) { ChevronGlyph(GlyphDirection.DOWN, ink, glyph, description = str("ed.more_play")) }
        }
        // The menu hangs under the control's right edge: a zero-size anchor there, the menu's own width to its left.
        Box(Modifier.align(Alignment.BottomEnd).size(0.dp)) {
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = MENU_WIDTH),
                offset = DpOffset(-MENU_WIDTH, (PLAY_ALL_TARGET - height) / 2 + 6.dp), containerColor = colors.surface,
            ) {
                DropdownMenuItem(
                    text = { Text(str("music.shuffle_all"), color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora) },
                    leadingIcon = { MusicGlyph(MusicIcon.SHUFFLE, colors.text, 18.dp) },
                    modifier = Modifier.focusRequester(shuffleFocus),
                    onClick = { open = false; press(PlayAllHalf.SHUFFLE) },
                )
            }
        }
    }
}

/** One half: clickable (so Tab reaches it and Enter or Space press it), the app's focus ring while focused. */
@Composable
private fun Half(modifier: Modifier, pill: Dp, shape: RoundedCornerShape, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = RaviloTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier.handCursor().drawBehind {
            if (focused) {
                // The app's ring: 2 dp `ring` inside a 2 dp `bg` gap, round the pill's half.
                val h = pill.toPx(); val top = (size.height - h) / 2; val g = 2.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
                drawRoundRect(colors.accent, androidx.compose.ui.geometry.Offset(-g, top - g), androidx.compose.ui.geometry.Size(size.width + 2 * g, h + 2 * g), r, style = androidx.compose.ui.graphics.drawscope.Stroke(g))
            }
        }.clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) { content() }
}
