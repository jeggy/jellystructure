package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.i18n.str

/**
 * DEB-4071 (DEB-3995 FR-8) — the item ids **fully on this phone** for the signed-in viewer. Empty everywhere that is not an Android phone with
 * downloads, so a card there carries nothing new. A title that is queued, running or failed is not in it: only a finished one gets the mark.
 */
val LocalDownloadedIds = compositionLocalOf<Set<String>> { emptySet() }

/** The ids of [phases] that are finished. */
fun downloadedIds(phases: Map<String, DownloadPhase>): Set<String> =
    phases.filterValues { it == DownloadPhase.Downloaded }.keys

/** True when [itemId] is fully on this phone (FR-8); the one question every card asks. */
@Composable
fun isDownloaded(itemId: String?): Boolean = itemId != null && itemId in LocalDownloadedIds.current

/** FR-8 (amended), the round's geometry: a 20 dp chip radius 7 inset 7 dp on a poster; 18 dp radius 6 inset 5 dp on a 16:9 episode thumb. */
enum class MarkSize(val box: Dp, val radius: Dp, val inset: Dp) {
    POSTER(20.dp, 7.dp, 7.dp),
    EPISODE(18.dp, 6.dp, 5.dp),
}

private val MarkFill = Color(0xFFF3F4FB)
private val MarkInk = Color(0xFF0A0C13)

/**
 * FR-8 (amended) — the white chip: `#f3f4fb`, with an arrow into a line in `#0a0c13`, 12 × 12. A chip with an arrow is never a green circle,
 * because that shape and colour already mean *watched* and a title that is both has to read as both. The caller places it bottom-start
 * with [MarkSize.inset] of padding, and lifts it clear of a ribbon or badge that already owns that corner.
 */
@Composable
fun DownloadedMark(size: MarkSize, modifier: Modifier = Modifier) {
    val label = str("download.downloaded")
    Box(
        modifier.size(size.box).background(MarkFill, RoundedCornerShape(size.radius)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = this.size.width
            val stroke = Stroke(width = w * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(MarkInk, Offset(w * .5f, w * .08f), Offset(w * .5f, w * .62f), stroke.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * .26f, w * .40f); lineTo(w * .5f, w * .64f); lineTo(w * .74f, w * .40f) }, MarkInk, style = stroke)
            drawLine(MarkInk, Offset(w * .2f, w * .92f), Offset(w * .8f, w * .92f), stroke.width, StrokeCap.Round)
        }
    }
}
