package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.CheckGlyph
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

/** FR-8 — the round's amber for a failure, and its green for *Downloaded*. */
private val Amber = Color(0xFFF0C07A)
private val AmberLine = Color(0x8CF5B542)
private val AmberFill = Color(0x1FF5B542)
private val Good = Color(0xFF7FE4C0)
private val GoodFill = Color(0x1F2DD49A)
private val GoodLine = Color(0x852DD49A)

/** The control's label text style: measured and drawn with the same one, so the fixed width is the real one. */
private fun controlStyle(sora: androidx.compose.ui.text.font.FontFamily) =
    TextStyle(fontFamily = sora, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

/**
 * FR-8, amended — the one width this language's Download control has: the widest of its five short labels, measured in the control's own
 * type, plus its padding and glyph. **Never a function of the state**, which is what keeps Play from moving (acceptance 9).
 */
@Composable
fun rememberDownloadControlWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = controlStyle(Sora)
    val strings = mapOf(
        "download.action" to str("download.action"), "download.queued" to str("download.queued"),
        "download.downloaded" to str("download.downloaded"), "download.retry" to str("download.retry"),
    )
    return remember(strings, style, density) {
        val labels = DownloadControlLayout.shortLabels { strings.getValue(it) }
        DownloadControlLayout.controlWidthDp(labels) { with(density) { measurer.measure(it, style).size.width.toDp().value } }.dp
    }
}

/** The glyphs of the round: an arrow into a line, a clock and a warning triangle. Drawn, so they take the label's colour. */
@Composable
private fun DownloadGlyph(kind: ControlState, tint: Color, size: Dp = 17.dp) {
    when (kind) {
        ControlState.DOWNLOADED -> CheckGlyph(tint, size)
        ControlState.PERCENT -> Unit
        else -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (kind) {
                ControlState.DOWNLOAD -> {
                    drawLine(tint, Offset(w * .5f, w * .12f), Offset(w * .5f, w * .66f), stroke.width, StrokeCap.Round)
                    drawPath(Path().apply { moveTo(w * .26f, w * .44f); lineTo(w * .5f, w * .68f); lineTo(w * .74f, w * .44f) }, tint, style = stroke)
                    drawLine(tint, Offset(w * .2f, w * .88f), Offset(w * .8f, w * .88f), stroke.width, StrokeCap.Round)
                }
                ControlState.QUEUED -> {
                    drawCircle(tint, radius = w * .4f, center = Offset(w * .5f, w * .5f), style = stroke)
                    drawPath(Path().apply { moveTo(w * .5f, w * .28f); lineTo(w * .5f, w * .52f); lineTo(w * .66f, w * .62f) }, tint, style = stroke)
                }
                else -> {   // RETRY: a warning triangle
                    drawPath(Path().apply { moveTo(w * .5f, w * .12f); lineTo(w * .92f, w * .84f); lineTo(w * .08f, w * .84f); close() }, tint, style = stroke)
                    drawLine(tint, Offset(w * .5f, w * .4f), Offset(w * .5f, w * .62f), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = w * .02f, center = Offset(w * .5f, w * .74f))
                }
            }
        }
    }
}

/**
 * FR-8 — the Download control, five states, beside Play. [width] is the language's one width ([rememberDownloadControlWidth]); the
 * progress is the control's own fill, left to right, behind the label. A failure shows *Retry* and nothing else: its reason is a
 * message under the row ([DownloadStatusLine]), never inside the control.
 */
@Composable
fun DownloadControl(phase: DownloadPhase, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val state = phase.controlState()
    val shape = RoundedCornerShape(12.dp)
    val (tint, fill, line) = when (state) {
        ControlState.DOWNLOADED -> Triple(Good, GoodFill, GoodLine)
        ControlState.RETRY -> Triple(Amber, AmberFill, AmberLine)
        ControlState.QUEUED -> Triple(colors.textSecondary, colors.fg.copy(alpha = 0.07f), colors.fg.copy(alpha = 0.16f))
        else -> Triple(colors.text, colors.fg.copy(alpha = 0.07f), colors.fg.copy(alpha = 0.16f))
    }
    val label = when (state) {
        ControlState.DOWNLOAD -> str("download.action")
        ControlState.QUEUED -> str("download.queued")
        ControlState.PERCENT -> "${phase.percent()} %"
        ControlState.DOWNLOADED -> str("download.downloaded")
        ControlState.RETRY -> str("download.retry")
    }
    Box(
        modifier = modifier
            .width(width).height(44.dp)
            .clip(shape).background(fill).border(1.dp, line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state == ControlState.PERCENT) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(phase.percent() / 100f).background(colors.accent.copy(alpha = 0.38f)).align(Alignment.CenterStart))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DownloadGlyph(state, tint)
            Text(label, style = controlStyle(Sora), color = tint, maxLines = 1, softWrap = false)
        }
    }
}

/** FR-8 — My List without its label (44 dp): the label stays as the accessibility description. */
@Composable
fun MyListIconButton(inList: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Box(
        modifier = modifier.size(DownloadControlLayout.MY_LIST_DP.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.fg.copy(alpha = 0.13f)).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (inList) "−" else "+", color = colors.text, fontSize = 22.sp, fontFamily = Sora)
    }
}

/** The short message under the row when a download is waiting or failed (FR-6, FR-7, FR-8); nothing otherwise. */
@Composable
fun DownloadStatusLine(phase: DownloadPhase, modifier: Modifier = Modifier) {
    val key = phase.statusKey() ?: return
    Text(str(key), color = if (phase is DownloadPhase.Failed) Amber else RaviloTheme.colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, modifier = modifier)
}

// ─── The quality sheet (FR-3, FR-5) ──────────────────────────────────────────────────────────────────────────────

/** What the sheet is showing: the server has not answered, it could not, or the rows are here. */
sealed interface SheetLoad {
    data object Loading : SheetLoad
    data object Failed : SheetLoad
    data class Ready(val model: QualitySheetModel) : SheetLoad
}

@Composable
private fun QualityRowView(row: QualityRow, picked: Boolean, onPick: () -> Unit) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(13.dp)
    val o = row.option
    val name = if (o.quality == "original") str("download.original") else resolutionLabel(o) ?: o.quality
    val size = formatBytes(o.bytes).let { if (o.exact) it else str("download.about_size", mapOf("size" to it)) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape)
            .background(if (picked) colors.accent.copy(alpha = 0.16f) else colors.fg.copy(alpha = 0.04f))
            .border(1.dp, if (picked) colors.accent else colors.fg.copy(alpha = 0.12f), shape)
            .clickable(enabled = row.enabled, onClick = onPick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(22.dp).border(2.dp, if (picked) colors.accent else colors.fg.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            if (picked) Box(Modifier.size(12.dp).background(colors.accent, CircleShape))
        }
        Text(name, color = if (row.enabled) colors.text else colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Text(size, color = if (o.exact) colors.text else colors.textSecondary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, softWrap = false)
    }
}

/**
 * FR-3, FR-5 — the sheet. It opens at once and the rows arrive (three skeleton rows, no spinner); a row that does not fit is shown and
 * disabled with `download.no_room` and the number to free; the free space sits above the button; and the button names what pressing it
 * downloads. [onConfirm] gets the picked quality. For a season [model] carries the counts and `download.season_have` says them.
 */
@Composable
fun QualitySheet(
    visible: Boolean,
    title: String,
    load: SheetLoad,
    onConfirm: (quality: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaviloTheme.colors
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        var picked by remember(load) { mutableStateOf((load as? SheetLoad.Ready)?.model?.selected?.option?.quality) }
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Text(str("download.sheet_title"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.padding(horizontal = 20.dp))
            Text(title, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            Spacer(Modifier.height(14.dp))
            when (load) {
                SheetLoad.Loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(3) { Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(13.dp)).background(colors.fg.copy(alpha = 0.055f))) }
                    Text(str("download.loading"), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
                }
                SheetLoad.Failed -> Text(str("download.failed"), color = Amber, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                is SheetLoad.Ready -> {
                    val m = load.model
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        m.rows.forEach { row ->
                            QualityRowView(row, picked = row.option.quality == picked && row.enabled, onPick = { picked = row.option.quality })
                            if (!row.enabled) Text(
                                str("download.no_room", mapOf("size" to formatBytes(row.shortfallBytes))),
                                color = colors.textSecondary, fontSize = 11.5.sp, fontFamily = Sora,
                                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (m.isSeason) Text(
                        str("download.season_have", mapOf("have" to m.haveEpisodes.toString(), "all" to m.allEpisodes.toString(), "size" to formatBytes(m.rows.firstOrNull { it.option.quality == picked }?.downloadBytes ?: m.buttonBytes))),
                        color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Text(
                        str("download.free_space", mapOf("size" to formatBytes(m.freeBytes))), color = colors.textSecondary, fontSize = 12.5.sp,
                        fontFamily = Sora, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    val chosen = m.rows.firstOrNull { it.option.quality == picked && it.enabled }
                    val bytes = chosen?.downloadBytes ?: 0L
                    Box(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                            .background(colors.accent.copy(alpha = if (chosen != null) 1f else 0.35f))
                            .clickable(enabled = chosen != null) { chosen?.let { onConfirm(it.option.quality) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        val about = chosen?.option?.exact == false
                        val size = formatBytes(bytes).let { if (about) str("download.about_size", mapOf("size" to it)) else it }
                        Text(str("download.sheet_button", mapOf("size" to size)), color = colors.onAccent, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                    }
                }
            }
        }
    }
}

// ─── The control's menus (FR-8) ──────────────────────────────────────────────────────────────────────────────────

/** One line of a small action sheet. */
data class DownloadAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

@Composable
fun DownloadActionSheet(visible: Boolean, title: String?, actions: List<DownloadAction>, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (title != null) Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            actions.forEach { a ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(13.dp)).background(colors.fg.copy(alpha = 0.06f))
                        .clickable { onDismiss(); a.run() }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(a.label, color = if (a.destructive) Amber else colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                }
            }
        }
    }
}
