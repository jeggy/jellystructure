package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalUserAvatarUrl
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * navFR — entry-point FocusRequester; callers focus this to bring focus into the bar.
 *          It is used as the first nav item's focusRequester so requesting it lands
 *          directly on the first item.
 * onDown — called when D-pad DOWN is pressed from any nav item; typically returns
 *           focus to the screen's hero/content area.
 */
@Composable
fun AppBar(
    navItems: List<String>? = null,
    activeNav: Int = 0,
    onNavSelect: (Int) -> Unit = {},
    navFR: FocusRequester = remember { FocusRequester() },
    onDown: () -> Unit = {},
    userInitials: String = "",
    onProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    scrolled: Boolean = false,
    title: String? = null,   // R136: page context (e.g. channel name) shown after the brand lockup
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val avatarFR = remember { FocusRequester() }
    val searchFR = remember { FocusRequester() }
    // R52: Search is no longer a nav tab — it lives as the magnifier icon in the right cluster.
    val items = navItems ?: listOf(
        str("nav.home"), str("nav.movies"), str("nav.series"), str("nav.my_list"),
    )
    val barGradient = remember {
        Brush.verticalGradient(
            0f to Color(0x8C000000),
            1f to Color.Transparent,
        )
    }
    // R62: animate from transparent (hero mode) to solid surface (scrolled mode)
    val solidBg by animateColorAsState(
        targetValue = if (scrolled) colors.surface.copy(alpha = 0.95f) else Color.Transparent,
        animationSpec = tween(180),
        label = "appBarBg",
    )

    // R67: place navFR at the activeNav slot so that callers calling navFR.requestFocus() land on
    // the currently active tab instead of always landing on item 0 (Home).
    val innerFRs = remember(items.size) { List(items.size) { FocusRequester() } }
    val allFRs: List<FocusRequester> = remember(navFR, innerFRs, activeNav) {
        innerFRs.mapIndexed { i, fr -> if (i == activeNav.coerceIn(0, innerFRs.lastIndex)) navFR else fr }
    }
    var focusedIdx by remember { mutableIntStateOf(-1) }

    // Bug fix: on a narrow/portrait screen the nav items + search + clock + avatar don't all fit and
    // used to just clip off the edge. horizontalScroll can't coexist with the weight(1f) spacer below
    // (it needs a bounded width to compute leftover space; a scrollable Row gives unbounded width) —
    // so compact mode drops the flexible spacer and scrolls instead. Wide (TV/desktop) is untouched:
    // no scroll, same weight-based layout as before.
    val compact = LocalCompact.current
    val navScrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RaviloDimens.appBarHeight)
            .background(solidBg)         // R62: solid layer (transparent when at top)
            .background(barGradient),    // gradient vignette on top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = raviloHPad)
                .matchParentSize()
                .then(if (compact) Modifier.horizontalScroll(navScrollState) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),   // R52: denser (was 28)
        ) {
            // Brand lockup (R51): gradient jellyfish mark + ink wordmark (not accent-purple text).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrandMark(size = 26.dp)
                Text(
                    text = "Ravilo",
                    color = colors.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-1).sp,
                )
            }

            // R136: page context (channel name) between the brand and the nav tabs.
            if (title != null) {
                Text(
                    text = "· $title",
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                    maxLines = 1,
                )
            }

            items.forEachIndexed { i, label ->
                val isFocused = focusedIdx == i
                val isActive  = i == activeNav

                val bgColor by animateColorAsState(
                    targetValue = if (isFocused) colors.text else Color.Transparent,
                    animationSpec = tween(120),
                    label = "navBg$i",
                )
                val textColor by animateColorAsState(
                    targetValue = when {
                        isFocused -> colors.background
                        isActive  -> colors.text
                        else      -> colors.textSecondary
                    },
                    animationSpec = tween(120),
                    label = "navText$i",
                )

                Text(
                    text = label,
                    color = textColor,
                    fontSize = 15.sp,                                   // R52: compacter (was 16)
                    fontWeight = if (isFocused || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                    modifier = Modifier
                        .onFocusChanged { state ->
                            focusedIdx = if (state.isFocused) i
                                         else if (focusedIdx == i) -1
                                         else focusedIdx
                        }
                        .background(bgColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)  // R52: compacter (was 14×6)
                        .dpadFocusable(
                            focusRequester = allFRs[i],
                            onFocused  = { focusedIdx = i },
                            onLeft     = { if (i > 0) allFRs[i - 1].requestFocus() },
                            onRight    = {
                                // R52: rightmost nav item → search icon → avatar.
                                if (i < items.lastIndex) allFRs[i + 1].requestFocus()
                                else if (onSearch != null) searchFR.requestFocus()
                                else if (onProfile != null) avatarFR.requestFocus()
                            },
                            onDown     = onDown,
                            onSelect   = { onNavSelect(i) },
                        ),
                )
            }

            // Wide: push the right cluster to the far edge. Compact: no bounded width to weight
            // against (see above) — the Row's own spacedBy(18.dp) gap is enough; items just scroll.
            if (!compact) Box(modifier = Modifier.weight(1f))
            // R245 (FR-R245-1) — the cast button, present only when the server says Chromecast is set up
            // and this platform has a sender (CastButton renders nothing otherwise — never greyed).
            CastButton()
            // R52 right cluster: search icon · clock · avatar (gaps from the Row's spacedBy).
            if (onSearch != null) {
                SearchIcon(
                    focusRequester = searchFR,
                    onLeft = { allFRs.last().requestFocus() },
                    onRight = { if (onProfile != null) avatarFR.requestFocus() },
                    onDown = onDown,
                    onSelect = onSearch,
                )
            }
            ClockDisplay()
            if (onProfile != null) {
                ProfileAvatar(
                    initials = userInitials.ifEmpty { "?" },
                    focusRequester = avatarFR,
                    onLeft = { if (onSearch != null) searchFR.requestFocus() else allFRs.last().requestFocus() },
                    onDown = onDown,
                    onSelect = onProfile,
                )
            }
        }

        // Bug fix: a discoverable hint that there's more to scroll to — gesture-only horizontalScroll
        // has no visible affordance on its own. Only rendered when content actually overflows
        // (maxValue > 0); a wide screen where everything fits shows nothing, same as before this fix.
        if (compact && navScrollState.maxValue > 0) {
            NavScrollIndicator(navScrollState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun NavScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = raviloHPad, vertical = 3.dp)
            .height(3.dp),
    ) {
        val viewport = scrollState.viewportSize.toFloat()
        val totalContent = viewport + scrollState.maxValue.toFloat()
        val thumbFraction = if (totalContent > 0f) (viewport / totalContent).coerceIn(0.12f, 1f) else 1f
        val scrollFraction = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue.toFloat() else 0f
        val thumbOffset = maxWidth * (1 - thumbFraction) * scrollFraction

        Box(Modifier.fillMaxSize().background(colors.textSecondary.copy(alpha = 0.15f), RoundedCornerShape(2.dp)))
        Box(
            Modifier
                .fillMaxWidth(thumbFraction)
                .fillMaxHeight()
                .offset(x = thumbOffset)
                .background(colors.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ProfileAvatar(
    initials: String,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val avatarUrl = LocalUserAvatarUrl.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (focused) colors.accent else colors.surfaceVariant, CircleShape)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, CircleShape) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onDown = onDown,
                onSelect = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            RemoteImage(avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Text(
                text = initials,
                color = if (focused) colors.onAccent else colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Sora,
            )
        }
    }
}

/**
 * R52 — the right-cluster search affordance: a 40 dp circle with a drawn magnifier glyph
 * (`design/ravilo/ravilo.css` `.search-ic`: circle r7 + 16.5,16.5→21,21 handle). Inverts on focus like
 * the avatar; `onSelect` opens the Search screen.
 */
@Composable
private fun SearchIcon(
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (focused) colors.accent else Color.Transparent, CircleShape)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, CircleShape) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onRight = onRight,
                onDown = onDown,
                onSelect = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (focused) colors.onAccent else colors.text
        Canvas(modifier = Modifier.size(22.dp)) {
            val s = size.minDimension / 24f          // glyph authored in a 24×24 viewBox
            drawCircle(
                color = glyph,
                radius = 7f * s,
                center = Offset(11f * s, 11f * s),
                style = Stroke(width = 2f * s, cap = StrokeCap.Round),
            )
            drawLine(
                color = glyph,
                start = Offset(16.5f * s, 16.5f * s),
                end = Offset(21f * s, 21f * s),
                strokeWidth = 2f * s,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ClockDisplay() {
    val colors = RaviloTheme.colors
    val sora = Sora
    var timeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeStr = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
            delay(30_000L)
        }
    }

    Text(
        text = timeStr,
        color = colors.textSecondary,
        fontSize = 15.sp,
        fontFamily = sora,
        textAlign = TextAlign.End,
    )
}
