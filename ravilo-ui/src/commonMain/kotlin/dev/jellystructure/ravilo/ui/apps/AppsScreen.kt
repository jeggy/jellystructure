package dev.jellystructure.ravilo.ui.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppsGlyph
import dev.jellystructure.ravilo.ui.components.BrandMark
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.keepsFocusInside
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/*
 * DEB-4912 (FR-11, FR-14, FR-15) — Apps and All apps. Six tiles across (the design round's recommendation: all eighteen of its stand-in
 * apps without scrolling), banners at 16:9. The pin mark, the pin menu and the order pills were not drawn by that round (it ran
 * from the brief before the pins were added), so they follow the brief's leans: two pills at the top, a long press for the menu.
 */

object AppsTags {
    const val SCREEN = "apps"
    const val ALL_SCREEN = "all-apps"
    const val MENU = "apps-menu"
    const val ORDER_AZ = "all-apps-order-az"
    const val ORDER_RECENT = "all-apps-order-recent"
    const val SETTINGS = "apps-settings"
    const val STOCK_HOME = "apps-stock-home"
    const val SEE_ALL = "apps-see-all"
    fun app(packageName: String) = "apps-app-$packageName"
}

private const val COLUMNS = 6
private const val NO_BAR_BACK_HINT_SIZE = 10

/** What a tile draws: a glyph the screen owns, or an app's own banner, or its icon on a plate. */
private sealed interface Art {
    data object Gear : Art
    data object House : Art
    data object Grid : Art
    data class Banner(val packageName: String) : Art
    data class Plate(val packageName: String) : Art
}

private class Cell(
    val tag: String,
    val label: String,
    val art: Art,
    val pinned: Boolean,
    val onSelect: () -> Unit,
    /** Opens the pin menu (true when the Select key that opened it is still down): null where the tile has none. */
    val menu: ((heldAtOpen: Boolean) -> Unit)?,
)

/**
 * The top of a screen with a bar (a TV with a viewer) or without one (a TV with nobody signed in: sign-in's own brand row, FR-12).
 * [bar] is given the focus requester the first row's Up goes to, and the action Down from the bar takes.
 */
typealias AppsTopBar = @Composable (navFR: FocusRequester, onDown: () -> Unit) -> Unit

/**
 * Apps and All apps as one flow, for a host that has no navigation stack of its own (the server-setup screen) and for the stack's
 * [AppsScreen]/[AllAppsScreen] pair alike. Back leaves All apps for Apps; Back on Apps calls [onExit] where the host has one, and is
 * left to the host's own Back (the stack's pop) where it does not. The pin menu takes Back first: it is below this in the tree.
 */
@Composable
fun AppsFlow(model: TvAppsModel, onExit: (() -> Unit)?, topBar: AppsTopBar?, modifier: Modifier = Modifier) {
    var all by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxSize().onKeyEvent { ev ->
            if (ev.key != Key.Back && ev.key != Key.Escape) return@onKeyEvent false
            when {
                all -> { if (ev.type == KeyEventType.KeyDown) all = false; true }
                onExit != null -> { if (ev.type == KeyEventType.KeyDown) onExit(); true }
                else -> false
            }
        },
    ) {
        if (all) AllAppsScreen(model, topBar) else AppsScreen(model, onSeeAll = { all = true }, topBar = topBar)
    }
}

/** FR-11 — Apps: Android settings, the stock home, the pinned apps, See all apps. Works with no network and with nobody signed in. */
@Composable
fun AppsScreen(
    model: TvAppsModel,
    onSeeAll: () -> Unit,
    topBar: AppsTopBar?,
    modifier: Modifier = Modifier,
) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // FR-11 — read from the TV each time Apps opens, and again when Ravilo comes back to the front (an app installed meanwhile).
    val entries = remember(model.version, windowFocused) { model.entries() }
    val pins = entries.filterIsInstance<AppsEntry.Pinned>()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuByHold by remember { mutableStateOf(false) }
    val cells = entries.map { e ->
        when (e) {
            AppsEntry.Settings -> Cell(AppsTags.SETTINGS, str("apps.tv_settings"), Art.Gear, false, { model.openSettings() }, null)
            is AppsEntry.StockHome -> Cell(AppsTags.STOCK_HOME, e.label, Art.House, false, { model.openStockHome() }, null)
            is AppsEntry.Pinned -> Cell(
                AppsTags.app(e.app.packageName), e.app.label, if (e.app.hasBanner) Art.Banner(e.app.packageName) else Art.Plate(e.app.packageName),
                false, { model.open(e.app.packageName) }, { held -> menuFor = e.app.packageName; menuByHold = held },
            )
            AppsEntry.SeeAll -> Cell(AppsTags.SEE_ALL, str("apps.see_all"), Art.Grid, false, onSeeAll, null)
        }
    }
    val openMenu = menuFor?.let { pkg -> pins.firstOrNull { it.app.packageName == pkg }?.app }
    Box(modifier.fillMaxSize()) {
        AppsPage(
            title = str("nav.apps"),
            subtitle = str(if (pins.isEmpty()) "apps.subtitle" else "apps.pinned_subtitle"),
            cells = cells,
            model = model,
            topBar = topBar,
            pills = null,
            tag = AppsTags.SCREEN,
            menuOpen = openMenu != null,
        )
        if (openMenu != null) {
            val at = pins.indexOfFirst { it.app.packageName == openMenu.packageName }
            val actions = buildList {
                if (at > 0) add(str("apps.move_left") to { model.move(openMenu.packageName, PinMove.LEFT) })
                if (at < pins.lastIndex) add(str("apps.move_right") to { model.move(openMenu.packageName, PinMove.RIGHT) })
                add(str("apps.unpin") to { model.unpin(openMenu.packageName) })
            }
            AppMenu(openMenu.label, actions, heldAtOpen = menuByHold, onDismiss = { menuFor = null })
        }
    }
}

/** FR-15 — All apps: every app on the TV, pinned ones marked, in A–Z or Recently opened (the last one used is remembered). */
@Composable
fun AllAppsScreen(
    model: TvAppsModel,
    topBar: AppsTopBar?,
    modifier: Modifier = Modifier,
) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val order = model.order
    val rows = remember(model.version, windowFocused, order) { model.allApps(order) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuByHold by remember { mutableStateOf(false) }
    val cells = rows.map { r ->
        Cell(
            AppsTags.app(r.app.packageName), r.app.label, if (r.app.hasBanner) Art.Banner(r.app.packageName) else Art.Plate(r.app.packageName),
            r.pinned, { model.open(r.app.packageName) }, { held -> menuFor = r.app.packageName; menuByHold = held },
        )
    }
    val openMenu = menuFor?.let { pkg -> rows.firstOrNull { it.app.packageName == pkg } }
    val azFR = remember { FocusRequester() }
    val recentFR = remember { FocusRequester() }
    Box(modifier.fillMaxSize()) {
        AppsPage(
            title = str("apps.all_title"),
            subtitle = str("apps.all_subtitle"),
            cells = cells,
            model = model,
            topBar = topBar,
            pills = { toBar, toGrid ->
                OrderPill(str("browse.sort.az"), order == AllAppsOrder.AZ, azFR, AppsTags.ORDER_AZ,
                    onRight = { recentFR.tryFocus() }, onUp = toBar, onDown = toGrid) { model.order = AllAppsOrder.AZ }
                OrderPill(str("apps.order_recent"), order == AllAppsOrder.RECENT, recentFR, AppsTags.ORDER_RECENT,
                    onLeft = { azFR.tryFocus() }, onUp = toBar, onDown = toGrid) { model.order = AllAppsOrder.RECENT }
            },
            firstPillFR = azFR,
            tag = AppsTags.ALL_SCREEN,
            menuOpen = openMenu != null,
        )
        if (openMenu != null) {
            val pkg = openMenu.app.packageName
            val actions = listOf(
                if (openMenu.pinned) str("apps.unpin") to { model.unpin(pkg) } else str("apps.pin") to { model.pin(pkg) },
            )
            AppMenu(openMenu.app.label, actions, heldAtOpen = menuByHold, onDismiss = { menuFor = null })
        }
    }
}

private fun FocusRequester.tryFocus() { runCatching { requestFocus() } }

@Composable
private fun AppsPage(
    title: String,
    subtitle: String,
    cells: List<Cell>,
    model: TvAppsModel,
    topBar: AppsTopBar?,
    pills: (@Composable (toBar: () -> Unit, toGrid: () -> Unit) -> Unit)?,
    tag: String,
    menuOpen: Boolean,
    firstPillFR: FocusRequester? = null,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val barFR = remember { FocusRequester() }
    val requesters = remember(cells.size) { List(cells.size) { FocusRequester() } }
    val toGrid: () -> Unit = { requesters.firstOrNull()?.tryFocus() }
    val toBar: () -> Unit = { barFR.tryFocus() }
    // The first target: the pills where there are any, otherwise the first tile. A menu closing gives focus back to the tile it came from.
    var returnTag by remember { mutableStateOf<String?>(null) }
    var returnIdx by remember { mutableStateOf(0) }
    LaunchedEffect(cells.isEmpty()) {
        val first = firstPillFR ?: requesters.firstOrNull() ?: return@LaunchedEffect
        requestFocusRetrying(scope, first)
    }
    LaunchedEffect(menuOpen) {
        val tag = returnTag ?: return@LaunchedEffect
        if (!menuOpen && requesters.isNotEmpty()) {
            // A pin that moved is found by its tile; one that was unpinned leaves its neighbour.
            val at = cells.indexOfFirst { it.tag == tag }.takeIf { it >= 0 } ?: returnIdx.coerceIn(0, requesters.lastIndex)
            requestFocusRetrying(scope, requesters[at])
        }
    }
    Column(Modifier.fillMaxSize().background(colors.background).testTag(tag)) {
        if (topBar != null) topBar(barFR, toGrid) else SignedOutHead()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(title, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            if (pills != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { pills(toBar, toGrid) }
                Spacer(Modifier.height(16.dp))
            }
            if (cells.isEmpty()) Text(str("apps.nothing_else"), color = colors.textSecondary, fontSize = 16.sp)
            cells.chunked(COLUMNS).forEachIndexed { row, rowCells ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCells.forEachIndexed { col, cell ->
                        val i = row * COLUMNS + col
                        Box(Modifier.weight(1f)) {
                            AppTile(
                                cell = cell, model = model, focusRequester = requesters[i],
                                onLeft = { if (col > 0) requesters[i - 1].tryFocus() },
                                onRight = { if (col < COLUMNS - 1 && i + 1 < cells.size) requesters[i + 1].tryFocus() },
                                onUp = {
                                    when {
                                        row > 0 -> requesters[i - COLUMNS].tryFocus()
                                        firstPillFR != null -> firstPillFR.tryFocus()
                                        else -> toBar()
                                    }
                                },
                                onDown = { requesters[tileBelow(i, cells.size)].tryFocus() },
                                onMenu = cell.menu?.let { open -> { held: Boolean -> returnTag = cell.tag; returnIdx = i; open(held) } },
                            )
                        }
                    }
                    // A short last row keeps the tiles' width: the empty cells are only there so the weights add up.
                    repeat(COLUMNS - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** FR-12 — with nobody signed in there is no bar: sign-in's own brand row, and Back says where it goes. */
@Composable
private fun SignedOutHead() {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp).height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandMark(size = 22.dp)
        Text("Ravilo", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(str("apps.back_to_signin"), color = colors.textDim, fontSize = NO_BAR_BACK_HINT_SIZE.sp)
    }
}

@Composable
private fun OrderPill(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    tag: String,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    RaviloButton(
        label = label, focusRequester = focusRequester,
        style = if (selected) ButtonStyle.PRIMARY else ButtonStyle.GHOST,
        modifier = Modifier.testTag(tag),
        onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
    )
}

@Composable
private fun AppTile(
    cell: Cell,
    model: TvAppsModel,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMenu: ((Boolean) -> Unit)?,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val shape = RoundedCornerShape(8.dp)
    val scale = if (focused) 1.04f else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRing(focused, shape, colors.focusRing, 3.dp)
            .clip(shape)
            .testTag(cell.tag)
            .selectOrHold(onSelect = cell.onSelect, onHold = onMenu)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true }, onBlurred = { focused = false },
                onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown,
                onSelect = cell.onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (val art = cell.art) {
            is Art.Banner -> {
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, art.packageName) {
                    value = withContext(Dispatchers.Default) { runCatching { model.banner(art.packageName) }.getOrNull() }
                }
                val b = bitmap
                if (b != null) Image(b, cell.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else NameOnPlate(cell.label)
            }
            is Art.Plate -> {
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, art.packageName) {
                    value = withContext(Dispatchers.Default) { runCatching { model.icon(art.packageName) }.getOrNull() }
                }
                PlateWithIcon(cell.label, bitmap)
            }
            Art.Gear -> GlyphTile(cell.label) { GearGlyph(colors.text, Modifier.size(30.dp)) }
            Art.House -> GlyphTile(cell.label) { HouseGlyph(colors.text, Modifier.size(30.dp)) }
            Art.Grid -> GlyphTile(cell.label) { AppsGlyph(colors.text, Modifier.size(28.dp)) }
        }
        if (cell.pinned) Text(
            str("apps.pinned_mark"), color = colors.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(colors.accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** An app with a banner that could not be read: its name on a plate, so the tile is never a blank. */
@Composable
private fun NameOnPlate(label: String) {
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxSize().background(colors.card).border(1.dp, colors.textDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
    }
}

/** An app with no banner: its icon and label inside the 16:9 cell, as FR-11 says. */
@Composable
private fun PlateWithIcon(label: String, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    val colors = RaviloTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.card).border(1.dp, colors.textDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
        else Box(Modifier.size(34.dp).background(colors.surfaceVariant, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GlyphTile(label: String, glyph: @Composable () -> Unit) {
    val colors = RaviloTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.card).border(1.dp, colors.textDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        glyph()
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * FR-14 — Select opens the tile; *press and hold* Select, or the Menu key where a remote has one, opens its menu. Where a tile has no
 * menu ([onHold] null) this changes nothing and Select acts on the key-down as every other control does.
 */
private fun Modifier.selectOrHold(onSelect: () -> Unit, onHold: ((Boolean) -> Unit)?): Modifier =
    if (onHold == null) this else composed {
        val press = remember { EnterPress { Clock.System.now().toEpochMilliseconds() } }
        val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
        val hold by androidx.compose.runtime.rememberUpdatedState(onHold)
        this
            .onFocusChanged { if (!it.hasFocus) press.reset() }
            .onPreviewKeyEvent { ev ->
                when (ev.key) {
                    Key.Menu -> { if (ev.type == KeyEventType.KeyDown) hold(false); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        when (ev.type) {
                            KeyEventType.KeyDown -> if (press.down()) hold(true)
                            KeyEventType.KeyUp -> if (press.up()) select()
                            else -> {}
                        }
                        true
                    }
                    else -> false
                }
            }
    }

/** FR-14 — the small menu a pin's actions live in. Back closes it; focus stays inside it. */
@Composable
private fun AppMenu(title: String, actions: List<Pair<String, () -> Unit>>, heldAtOpen: Boolean, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val requesters = remember(actions.size) { List(actions.size) { FocusRequester() } }
    val guard = remember { OpeningPressGuard({ Clock.System.now().toEpochMilliseconds() }, heldAtOpen) }
    LaunchedEffect(Unit) { requesters.firstOrNull()?.let { requestFocusRetrying(scope, it) } }
    Box(
        Modifier.fillMaxSize().background(colors.overlay).keepsFocusInside().testTag(AppsTags.MENU)
            .onPreviewKeyEvent { ev ->
                when {
                    ev.key == Key.Back || ev.key == Key.Escape -> { if (ev.type == KeyEventType.KeyDown) onDismiss(); true }
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.DirectionCenter -> guard.swallow(ev.type == KeyEventType.KeyDown)
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(min = 280.dp, max = 420.dp).background(colors.surface, RoundedCornerShape(14.dp)).padding(24.dp)) {
            Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            actions.forEachIndexed { i, (label, action) ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                RaviloButton(
                    label = label, focusRequester = requesters[i], style = if (i == 0) ButtonStyle.PRIMARY else ButtonStyle.GHOST,
                    onUp = { if (i > 0) requesters[i - 1].tryFocus() },
                    onDown = { if (i < actions.lastIndex) requesters[i + 1].tryFocus() },
                    // Every action closes the menu; the screen takes its own focus back (a pin that moved is found by its tile).
                    onSelect = { action(); onDismiss() },
                )
            }
        }
    }
}

/** The settings gear: a ring with eight teeth. A small circle with spokes read as a sun in the design round's first render. */
@Composable
private fun GearGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val c = Offset(12f * s, 12f * s)
        drawCircle(tint, radius = 5.2f * s, center = c, style = Stroke(2.4f * s))
        for (k in 0 until 8) {
            val a = k * kotlin.math.PI / 4
            val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
            drawLine(tint, Offset(c.x + dx * 7.6f * s, c.y + dy * 7.6f * s), Offset(c.x + dx * 10.4f * s, c.y + dy * 10.4f * s), strokeWidth = 3f * s, cap = StrokeCap.Round)
        }
    }
}

/** The stock home: a house outline with its door, drawn in a 24×24 box. */
@Composable
private fun HouseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val roof = Path().apply {
            moveTo(3f * s, 11f * s); lineTo(12f * s, 3.5f * s); lineTo(21f * s, 11f * s)
            moveTo(5.5f * s, 9.5f * s); lineTo(5.5f * s, 20.5f * s); lineTo(18.5f * s, 20.5f * s); lineTo(18.5f * s, 9.5f * s)
        }
        drawPath(roof, tint, style = Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawRoundRect(tint, Offset(9.8f * s, 13.5f * s), androidx.compose.ui.geometry.Size(4.4f * s, 7f * s), androidx.compose.ui.geometry.CornerRadius(1f * s), style = Stroke(2f * s))
    }
}

/**
 * The tile Down goes to: the one straight below, or the last tile when the row below is a short one that ends before this column,
 * or this one on the last row (so Down at the bottom is a no-op rather than a jump).
 */
internal fun tileBelow(index: Int, count: Int): Int = when {
    index + COLUMNS < count -> index + COLUMNS
    index / COLUMNS < (count - 1) / COLUMNS -> count - 1
    else -> index
}
