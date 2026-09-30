package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import dev.jellystructure.ravilo.ui.i18n.str
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
    "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

/**
 * R337 — the desktop chrome's icons, drawn from the design's own path data (`design/ravilo/desktop-kit.js`, a 24-unit
 * box, 1.8 stroke, round caps and joins; the transport glyphs are filled). One set for the sidebar, the rail, the
 * toolbar and the player bar on both platforms, so the app and the mockup cannot drift icon by icon.
 */
enum class DeskIcon(internal val d: String, internal val filled: Boolean = false) {
    HOME("M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"),
    COMPASS(circle(12f, 12f, 9f) + "M15.5 8.5l-2 5-5 2 2-5z"),
    BOOKMARK("M6 3h12v18l-6-4-6 4z"),
    FILM(rect(3f, 4f, 18f, 16f, 2f) + "M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"),
    TV(rect(3f, 5f, 18f, 12f, 2f) + "M8 21h8"),
    SEARCH(circle(11f, 11f, 7f) + "M20 20l-4-4"),
    NOTE("M9 18V5l11-2v13" + circle(6f, 18f, 3f) + circle(17f, 16f, 3f)),
    DISC(circle(12f, 12f, 9f) + circle(12f, 12f, 2.5f)),
    PERSON(circle(12f, 8f, 4f) + "M4 21c1-4 4-6 8-6s7 2 8 6"),
    BOOK("M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z" + "M19 19v2H6"),
    PHONES("M4 15v-3a8 8 0 0 1 16 0v3" + rect(3f, 14f, 4f, 6f, 1f) + rect(17f, 14f, 4f, 6f, 1f)),
    QUEUE("M4 6h16M4 12h16M4 18h10"),
    WAVE("M5 10v4M9 6v12M13 9v6M17 4v16M21 10v4"),
    // Not in the mockup's sidebar (Q13 added the two rows after it was drawn): the same stroke vocabulary.
    GENRES(rect(4f, 4f, 7f, 7f, 1.5f) + rect(13f, 4f, 7f, 7f, 1.5f) + rect(4f, 13f, 7f, 7f, 1.5f) + rect(13f, 13f, 7f, 7f, 1.5f)),
    PLAYLIST("M4 6h16M4 11h10M4 16h7" + "M16 20v-8l5-1.5v7.5" + circle(14.2f, 20f, 1.8f)),
    CAST("M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-6"),
    VOLUME("M4 9v6h4l5 4V5L8 9z" + "M16 9a4 4 0 0 1 0 6"),
    LYRICS("M4 5h16v11H9l-5 4z" + "M8 9h8M8 12h5"),
    MENU("M4 7h16M4 12h16M4 17h16"),
    SIDEBAR(rect(3f, 4f, 18f, 16f, 2f) + "M9 4v16"),
    BACK("M15 5l-7 7 7 7"),
    FORWARD("M9 5l7 7-7 7"),
    CLOSE("M6 6l12 12M18 6L6 18"),
    UP("M7 14l5-5 5 5"),
    MORE(circle(5f, 12f, 1.2f) + circle(12f, 12f, 1.2f) + circle(19f, 12f, 1.2f)),
    SHUFFLE("M3 7h3l9 10h6M3 17h3l3-3.4M14 10l1-1h6M18 4l3 3-3 3M18 14l3 3-3 3"),
    PLAY("M8 5v14l11-7z", filled = true),
    PAUSE("M7 5h4v14H7zM13 5h4v14h-4z", filled = true),
    NEXT("M6 5l9 7-9 7zM16 5h2.5v14H16z", filled = true),
    PREVIOUS("M18 5l-9 7 9 7zM5.5 5H8v14H5.5z", filled = true),
}

@Composable
fun DeskIcon(icon: DeskIcon, tint: Color, size: Dp = 16.dp, modifier: Modifier = Modifier, stroke: Float = 1.8f) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            if (icon.filled) drawPath(path, tint)
            else drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * R337 — the desktop's button on a hero and on a title's page (`.bt`, `.bt.pri`): 38 dp, 10 dp corners, Sora 13.5.
 * The primary one is ink on the page's colour; the other sits on a faint plate. A pointer's button — the TV's
 * [RaviloButton] grows and glows under focus, which a click does not need.
 */
@Composable
fun DeskButton(label: String, icon: DeskIcon? = null, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val ink = if (primary) colors.background else colors.text
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) colors.text else colors.fg.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = if (label.isEmpty()) 11.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) DeskIcon(icon, ink, 16.dp)
        if (label.isNotEmpty()) Text(label, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1)
    }
}

/** R337 — a pointer's feedback on a computer: the row under it takes a faint plate. Nothing on a TV or a phone. */
@Composable
fun Modifier.deskHover(shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape, alpha: Float = 0.06f): Modifier {
    if (!dev.jellystructure.ravilo.ui.theme.isDesktopLayout) return this
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    return this.hoverable(source).then(if (hovered) Modifier.background(RaviloTheme.colors.fg.copy(alpha = alpha), shape) else Modifier)
}

/**
 * R337 — a row that scrolls sideways, with the desktop's way to do it. A trackpad swipes a `LazyRow`; a plain mouse
 * wheel cannot, and the mockup's rows run off the pane's edge with nothing to press. On a computer the row shows a
 * glass arrow at each end it can still scroll towards, while the pointer is over it; a click moves it by most of what
 * is on screen. On a TV and a phone this **is** `LazyRow`, parameter for parameter — nothing is wrapped.
 */
@Composable
fun ArrowRow(
    modifier: Modifier = Modifier,
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    if (!dev.jellystructure.ravilo.ui.theme.isDesktopLayout) {
        androidx.compose.foundation.lazy.LazyRow(modifier = modifier, state = state, contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
        return
    }
    val colors = RaviloTheme.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val back = str("desk.row_back")
    val forward = str("desk.row_forward")
    androidx.compose.foundation.layout.Box(Modifier.hoverable(source)) {
        androidx.compose.foundation.lazy.LazyRow(modifier = modifier, state = state, contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
        @Composable
        fun arrow(icon: DeskIcon, label: String, align: Alignment, direction: Float) {
            androidx.compose.foundation.layout.Box(
                Modifier.align(align).padding(horizontal = 8.dp).size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background((if (colors.isLight) colors.background else colors.surface).copy(alpha = 0.92f))
                    .border(1.dp, colors.fg.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape)
                    // A tap, not `clickable`: a click must not take the focus. A focused child makes the row bring itself
                    // into view and scroll back to its focused tile — the page jumped and the row stayed (GNOME, 2026-09-30).
                    .pointerInput(direction) {
                        detectTapGestures(onTap = {
                            scope.launch { state.animateScrollBy(state.layoutInfo.viewportSize.width * 0.8f * direction) }
                        })
                    }
                    .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button },
                contentAlignment = Alignment.Center,
            ) { DeskIcon(icon, colors.text, 16.dp) }
        }
        if (hovered && state.canScrollBackward) arrow(DeskIcon.BACK, back, Alignment.CenterStart, -1f)
        if (hovered && state.canScrollForward) arrow(DeskIcon.FORWARD, forward, Alignment.CenterEnd, 1f)
    }
}
