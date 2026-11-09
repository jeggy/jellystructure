package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.PlatformBackHandler
import dev.jellystructure.ravilo.ui.components.ChevronGlyph
import dev.jellystructure.ravilo.ui.components.CheckGlyph
import dev.jellystructure.ravilo.ui.components.CloseGlyph
import dev.jellystructure.ravilo.ui.components.GlyphDirection
import dev.jellystructure.ravilo.ui.components.TriangleGlyph
import dev.jellystructure.ravilo.ui.components.cardEpisodeCode
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** DEB-4071 — test hooks: the page's own handles, so a test finds the block and the confirm without knowing the layout. */
object DownloadsTags {
    const val PAGE = "downloads-page"
    const val DOWNLOADING = "downloads-block"
    const val OVERFLOW = "downloads-overflow"
    fun row(itemId: String) = "downloads-row-$itemId"
}

private val Danger = Color(0xFFF5667A)

/** The reveal a swipe uncovers: the red Delete tile (the round's `b-downloads-swipe`). */
private val RevealWidth = 88.dp

/**
 * DEB-4071 (DEB-3995 FR-9, FR-10, FR-12) — the Downloads page: the storage bar, **Other profiles on this phone**, a **Downloading** block at the
 * top while anything is on its way, then Films and Series (grouped by season) fully on the phone. Swipe or long-press to delete, ⋮ for Select,
 * *Delete watched* and *Delete all*, and **every delete confirms** with what it frees. Device state only; it renders what the phone holds.
 *
 * [offline] draws FR-9's quiet line at the top; it is the launch path's to set (a later slice), and false means nothing is said.
 */
@Composable
fun DownloadsScreen(controller: DownloadsController, onBack: () -> Unit, offline: Boolean = false) {
    val colors = RaviloTheme.colors
    val state = remember(controller) { DownloadsPageState(controller) }
    val records by controller.records.collectAsState()
    val phases by controller.phases.collectAsState()
    val other by controller.otherBytes.collectAsState()
    val page = remember(records, phases) { buildDownloadsPage(records, phases) }
    val bar = remember(page, other) { storageBar(page.profileBytes + other, controller.freeBytes(), controller.totalBytes()) }
    var menuOpen by remember { mutableStateOf(false) }

    // Back leaves a selection before it leaves the page, and closes the menu before either.
    PlatformBackHandler(enabled = state.selecting || menuOpen) { if (menuOpen) menuOpen = false else state.stopSelecting() }
    // The viewer went to another profile, or the last download went: nothing to select any more.
    LaunchedEffect(page.isEmpty) { if (page.isEmpty) state.stopSelecting() }

    Box(Modifier.fillMaxSize().background(colors.background).testTag(DownloadsTags.PAGE)) {
        Column(Modifier.fillMaxSize()) {
            DownloadsTitleBar(
                selecting = state.selecting, selectedCount = state.selected.size, total = page.profileBytes, canOverflow = !page.isEmpty,
                selectedBytes = if (state.selecting) state.selectedBytes() else 0L,
                onBack = onBack, onOverflow = { menuOpen = true }, onCancelSelect = state::stopSelecting, onDeleteSelected = state::askSelection,
            )
            if (page.isEmpty && other <= 0L) EmptyDownloads(offline)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (offline) item { Text(str("download.offline"), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(bottom = 4.dp)) }
                item { StorageSection(bar) }
                if (other > 0L) item { OtherProfilesRow(other, onDelete = state::askOtherProfiles) }
                if (page.isEmpty) item { EmptyDownloads(offline = false, modifier = Modifier.padding(top = 24.dp)) }
                downloadingBlock(page, controller)
                filmsBlock(page, state, controller)
                seriesBlock(page, state)
            }
        }

        if (menuOpen) OverflowMenu(
            watchedBytes = page.watchedBytes, allBytes = page.deleteAllBytes,
            onDismiss = { menuOpen = false },
            onSelect = { menuOpen = false; state.startSelecting() },
            onDeleteWatched = { menuOpen = false; state.askWatched() },
            onDeleteAll = { menuOpen = false; state.askAll() },
        )

        // FR-10 (amended) — one confirm for every kind of delete, naming what it frees. Cancel restores the entry exactly as it was.
        val p = state.pending
        val size = formatBytes(p?.bytes ?: 0L)
        DownloadActionSheet(
            visible = p != null,
            title = p?.let { confirmTitle(it, size) },
            body = p?.let { confirmBody(it, size) },
            actions = listOf(
                DownloadAction(str(if (p?.kind == PendingDelete.Kind.ALL) "download.delete_all" else "download.delete"), destructive = true) { state.confirm(p) },
                DownloadAction(str("action.cancel")) { state.cancel() },
            ),
            onDismiss = { state.cancel() },
        )
    }
}

@Composable
private fun confirmTitle(p: PendingDelete, size: String): String = when (p.kind) {
    PendingDelete.Kind.ONE, PendingDelete.Kind.SERIES -> str("download.delete_one_confirm", mapOf("title" to p.subject.orEmpty(), "size" to size))
    PendingDelete.Kind.SELECTION -> str("download.n_selected", mapOf("n" to p.ids.size.toString()))
    PendingDelete.Kind.WATCHED -> str("download.delete_watched")
    PendingDelete.Kind.ALL -> str("download.delete_all_title")
    PendingDelete.Kind.OTHER_PROFILES -> str("download.other_profiles")
}

/** A single delete's sentence already carries its size; every other kind says it on a second line. */
@Composable
private fun confirmBody(p: PendingDelete, size: String): String? = when (p.kind) {
    PendingDelete.Kind.ONE, PendingDelete.Kind.SERIES -> null
    else -> str("download.delete_all_confirm", mapOf("size" to size))
}

// ─── the title bar (FR-9, FR-10) ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DownloadsTitleBar(
    selecting: Boolean, selectedCount: Int, selectedBytes: Long, total: Long, canOverflow: Boolean,
    onBack: () -> Unit, onOverflow: () -> Unit, onCancelSelect: () -> Unit, onDeleteSelected: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            Text(
                str("action.cancel"), color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onCancelSelect).padding(horizontal = 12.dp).wrapCentre(),
            )
            Text(str("download.n_selected", mapOf("n" to selectedCount.toString())), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            val can = selectedCount > 0
            Text(
                str("download.delete") + " · " + formatBytes(selectedBytes), color = if (can) Danger else colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1,
                modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = can, onClick = onDeleteSelected).padding(horizontal = 12.dp).wrapCentre(),
            )
        } else {
            Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
                ChevronGlyph(GlyphDirection.LEFT, colors.text, 22.dp)
            }
            Text(str("download.page_title"), color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.padding(start = 4.dp))
            if (total > 0L) Text(formatBytes(total), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            if (canOverflow) Box(Modifier.size(44.dp).clickable(onClick = onOverflow).testTag(DownloadsTags.OVERFLOW), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { Box(Modifier.size(3.5.dp).background(colors.text, CircleShape)) } }
            }
        }
    }
}

private fun Modifier.wrapCentre(): Modifier = this.padding(vertical = 12.dp)

/** FR-10 — the ⋮ menu: Select, Delete watched and Delete all, the last two naming the space they free. */
@Composable
private fun OverflowMenu(
    watchedBytes: Long, allBytes: Long, onDismiss: () -> Unit, onSelect: () -> Unit, onDeleteWatched: () -> Unit, onDeleteAll: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 14.dp).width(250.dp)
                .background(colors.surface, RoundedCornerShape(16.dp)).border(1.dp, colors.fg.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(vertical = 6.dp),
        ) {
            MenuRow(str("download.select"), null, colors.text, onSelect)
            MenuRow(str("download.delete_watched"), formatBytes(watchedBytes), if (watchedBytes > 0L) colors.text else colors.textDim, onDeleteWatched, enabled = watchedBytes > 0L)
            MenuRow(str("download.delete_all"), formatBytes(allBytes), Danger, onDeleteAll)
        }
    }
}

@Composable
private fun MenuRow(label: String, size: String?, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tint, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
        if (size != null) Text(size, color = RaviloTheme.colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
    }
}

// ─── the storage bar (FR-9) and the other profiles' row (FR-12) ───────────────────────────────────────────────────

@Composable
private fun StorageSection(bar: StorageBar) {
    val colors = RaviloTheme.colors
    val ravilo = colors.accent
    val otherApps = colors.fg.copy(alpha = 0.22f)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(str("download.space_title"), color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.fg.copy(alpha = 0.07f))) {
            segment(bar.fraction(bar.ravilo), ravilo)
            segment(bar.fraction(bar.otherApps), otherApps)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(ravilo, str("download.seg_ravilo"), formatBytes(bar.ravilo))
            Legend(otherApps, str("download.seg_other"), formatBytes(bar.otherApps))
        }
        Spacer(Modifier.height(4.dp))
        Legend(Color.Transparent, str("download.seg_free"), formatBytes(bar.free), outlined = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.segment(fraction: Float, color: Color) {
    if (fraction > 0f) Box(Modifier.weight(fraction).fillMaxHeight().background(color))
    // A weight of zero is not allowed; the rest of the track is the free part and shows the track's own tint.
}

@Composable
private fun Legend(swatch: Color, label: String, size: String, outlined: Boolean = false) {
    val colors = RaviloTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).background(swatch, RoundedCornerShape(3.dp)).then(if (outlined) Modifier.border(1.dp, colors.fg.copy(alpha = 0.3f), RoundedCornerShape(3.dp)) else Modifier))
        Text(label, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
        Text(size, color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
    }
}

@Composable
private fun OtherProfilesRow(bytes: Long, onDelete: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.fg.copy(alpha = 0.04f), RoundedCornerShape(14.dp)).border(1.dp, colors.fg.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(str("download.other_profiles"), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
            Text(formatBytes(bytes), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
        }
        Box(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Danger.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .background(Danger.copy(alpha = 0.08f)).clickable(onClick = onDelete).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text(str("download.delete"), color = Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
    }
}

@Composable
private fun EmptyDownloads(offline: Boolean, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (offline) Text(str("download.offline"), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(bottom = 18.dp))
        Box(Modifier.size(64.dp).background(colors.fg.copy(alpha = 0.05f), RoundedCornerShape(18.dp)).border(1.dp, colors.fg.copy(alpha = 0.10f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            DownloadGlyph(ControlState.DOWNLOAD, colors.textSecondary, 26.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(str("download.empty_title"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        Spacer(Modifier.height(8.dp))
        Text(str("download.empty"), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, fontFamily = Sora, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ─── the lists ───────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeading(label: String, bytes: Long, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
    }
}

/** FR-9 (amended) — above Films: every entry that is running, queued, waiting, paused or failed, in queue order. Gone when empty. */
private fun LazyListScope.downloadingBlock(page: DownloadsPageModel, controller: DownloadsController) {
    if (page.downloading.isEmpty()) return
    item(key = "downloading-heading") { SectionHeading(str("download.downloading"), page.downloading.sumOf { it.heldBytes }, Modifier.testTag(DownloadsTags.DOWNLOADING)) }
    items(page.downloading.size, key = { "dl-" + page.downloading[it].itemId }) { i -> DownloadingEntry(page.downloading[i], controller) }
}

private fun LazyListScope.filmsBlock(page: DownloadsPageModel, state: DownloadsPageState, controller: DownloadsController) {
    if (page.films.isEmpty()) return
    item(key = "films-heading") { SectionHeading(str("nav.movies"), page.filmsBytes) }
    items(page.films.size, key = { "film-" + page.films[it].itemId }) { i ->
        val row = page.films[i]
        SwipeDeleteRow(row.itemId, state, onAsk = { state.askOne(row) }) {
            DownloadedEntry(row, state, thumb = { Thumb(row.itemId, row.record.title, poster = true) }, title = row.record.title, subtitle = null)
        }
    }
}

private fun LazyListScope.seriesBlock(page: DownloadsPageModel, state: DownloadsPageState) {
    if (page.series.isEmpty()) return
    item(key = "series-heading") { SectionHeading(str("nav.series"), page.seriesBytes) }
    items(page.series.size, key = { "series-" + page.series[it].seriesId }) { i -> SeriesCard(page.series[i], state) }
}

// ─── entries ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** `Original` for the file, the rung's picture height for a copy (FR-9). */
@Composable
private fun qualityLabel(r: DownloadRecord): String =
    if (r.quality == "original") str("download.original") else (r.height ?: when (r.quality) { "high" -> 1080; "medium" -> 720; "small" -> 480; else -> null })?.let { "${it}p" } ?: r.quality

@Composable
private fun cardModifier(selected: Boolean) = RaviloTheme.colors.let { c ->
    val shape = RoundedCornerShape(16.dp)
    Modifier.fillMaxWidth().clip(shape)
        .background(if (selected) c.accent.copy(alpha = 0.13f) else c.fg.copy(alpha = 0.04f), shape)
        .border(1.dp, if (selected) c.accent else c.fg.copy(alpha = 0.10f), shape)
}

@Composable
private fun Thumb(itemId: String, title: String, poster: Boolean, number: Int? = null) {
    val hue = remember(title) { (title.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
    val shape = RoundedCornerShape(if (poster) 10.dp else 8.dp)
    Box(
        Modifier.then(if (poster) Modifier.width(48.dp).height(68.dp) else Modifier.width(86.dp).height(48.dp)).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.hsl(hue.toFloat(), 0.38f, 0.26f), Color.hsl(hue.toFloat(), 0.30f, 0.14f)))),
    ) {
        // Online the poster is the server's; offline the saved copy is a later slice's, and the gradient and initials stand in.
        if (poster) {
            Text(title.take(2).uppercase(), color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.align(Alignment.Center))
            RemoteImage(url = "/api/tv/image/$itemId/poster", contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, requestedWidth = 160)
        }
        if (number != null) Text(number.toString().padStart(2, '0'), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

@Composable
private fun Meta(r: DownloadRecord, bytesText: String, watched: Boolean, gone: Boolean) {
    val colors = RaviloTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(qualityLabel(r), color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            Text("·  $bytesText", color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
            if (watched) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(14.dp).background(colors.badgeWatched, CircleShape), contentAlignment = Alignment.Center) { CheckGlyph(Color.White, 9.dp) }
                Text(str("action.watched"), color = Good, fontSize = 12.5.sp, fontFamily = Sora)
            }
        }
        if (gone) Text(
            str("download.gone"), color = Amber, fontSize = 11.5.sp, fontFamily = Sora,
            modifier = Modifier.background(Color(0x1FF5B542), RoundedCornerShape(8.dp)).border(1.dp, Color(0x8CF5B542), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedEntry(row: DownloadRow, state: DownloadsPageState, thumb: @Composable () -> Unit, title: String, subtitle: String?) {
    val colors = RaviloTheme.colors
    val picked = row.itemId in state.selected
    Row(
        cardModifier(picked).testTag(DownloadsTags.row(row.itemId))
            .combinedClickable(onClick = { if (state.selecting) state.toggle(row.itemId) }, onLongClick = { if (!state.selecting) state.startSelecting(row.itemId) })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.selecting) SelectDot(picked)
        thumb()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = colors.textDim, fontSize = 12.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Meta(row.record, formatBytes(row.heldBytes), row.watched, row.gone)
        }
    }
}

@Composable
private fun SelectDot(on: Boolean) {
    val colors = RaviloTheme.colors
    Box(Modifier.size(24.dp).background(if (on) colors.accent else Color.Transparent, CircleShape).border(2.dp, if (on) colors.accent else colors.fg.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
        if (on) CheckGlyph(colors.onAccent, 13.dp)
    }
}

/** FR-10 — a swipe uncovers the red Delete tile and **asks**; Cancel puts the row back where it was. */
@Composable
private fun SwipeDeleteRow(itemId: String, state: DownloadsPageState, onAsk: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val reveal = with(density) { RevealWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val asking = state.pending?.ids?.contains(itemId) == true && state.pending?.kind == PendingDelete.Kind.ONE
    // The confirm closed without a delete: the row slides back (FR-10 amended, acceptance 11).
    LaunchedEffect(asking) { if (!asking && offset.value != 0f) offset.animateTo(0f) }
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(RevealWidth).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Danger).clickable(onClick = onAsk),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(str("download.delete"), color = Color(0xFF1A0A10), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Sora) }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(state.selecting) {
                if (state.selecting) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offset.value < -reveal * 0.5f) { offset.animateTo(-reveal); onAsk() } else offset.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    onHorizontalDrag = { change, dx -> change.consume(); scope.launch { offset.snapTo((offset.value + dx).coerceIn(-reveal, 0f)) } },
                )
            },
        ) { content() }
    }
}

/** One running, queued, waiting, paused or failed download (FR-9): its progress or its waiting line, pause/resume and cancel. */
@Composable
private fun DownloadingEntry(row: DownloadRow, controller: DownloadsController) {
    val colors = RaviloTheme.colors
    val r = row.record
    val p = row.phase
    val episode = r.kind == "episode"
    Row(cardModifier(false).testTag(DownloadsTags.row(row.itemId)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (episode) Thumb(row.itemId, r.seriesTitle ?: r.title, poster = false, number = r.episode)
        else Thumb(row.itemId, r.title, poster = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (episode) "${cardEpisodeCode(r.season, r.episode ?: 0)} · ${r.title}" else r.title,
                color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (episode && r.seriesTitle != null) Text(r.seriesTitle, color = colors.textDim, fontSize = 12.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = if (p is DownloadPhase.Running || p is DownloadPhase.Paused) "${formatBytes(row.heldBytes)} / ${formatBytes(r.bytes)}" else formatBytes(r.bytes)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(qualityLabel(r), color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                Text("·  $size", color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
            }
            when (p) {
                is DownloadPhase.Running, is DownloadPhase.Paused -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.fg.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.percent() / 100f).background(colors.accent))
                    }
                    Text("${p.percent()} %", color = if (p is DownloadPhase.Paused) colors.textDim else colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                }
                DownloadPhase.Queued -> Waiting(str("download.queued"))
                DownloadPhase.WaitingWifi -> Waiting(str("download.waiting_wifi"))
                DownloadPhase.WaitingServer -> Waiting(str("download.waiting_server"))
                is DownloadPhase.Failed -> Text(str("download.failed"), color = Amber, fontSize = 12.sp, fontFamily = Sora)
                else -> Unit
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (p) {
                is DownloadPhase.Running -> RoundButton("Pause", str("download.pause")) { controller.pause(row.itemId) }
                is DownloadPhase.Paused -> RoundButton("Resume", str("download.resume")) { controller.resume(row.itemId) }
                is DownloadPhase.Failed -> RoundButton("Retry", str("download.retry")) { controller.retry(row.itemId) }
                else -> Unit
            }
            RoundButton("Cancel", str("action.cancel")) { controller.delete(row.itemId) }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    val colors = RaviloTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DownloadGlyph(ControlState.QUEUED, colors.textSecondary, 13.dp)
        Text(text, color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
    }
}

/** A 40 dp icon button (pause, resume, retry, cancel); its words are the accessibility description. */
@Composable
private fun RoundButton(glyph: String, description: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.fg.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).background(colors.fg.copy(alpha = 0.05f))
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (glyph) {
            "Pause" -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(2) { Box(Modifier.width(3.5.dp).height(14.dp).background(colors.text, RoundedCornerShape(1.dp))) } }
            "Resume" -> TriangleGlyph(GlyphDirection.RIGHT, colors.text, 16.dp)
            "Retry" -> DownloadGlyph(ControlState.RETRY, Amber, 17.dp)
            else -> CloseGlyph(colors.text, 18.dp)
        }
    }
}

@Composable
private fun SeriesCard(group: SeriesGroup, state: DownloadsPageState) {
    val colors = RaviloTheme.colors
    val ids = group.episodes.map { it.itemId }
    val allPicked = state.selecting && state.selected.containsAll(ids)
    val deleteSeriesLabel = "${str("download.delete")} ${group.title}"
    val seasonLine = (if (group.seasons.size == 1 && group.seasons.first().season != null) "${str("detail.season")} ${group.seasons.first().season}" else "${group.seasons.size} ${str("detail.season").lowercase()}") +
        " · ${group.episodeCount} ${str(if (group.episodeCount == 1) "detail.episode" else "detail.episodes").lowercase()}"
    Column(cardModifier(allPicked).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = state.selecting) { state.toggleAll(ids) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.selecting) SelectDot(allPicked)
            Thumb(group.seriesId, group.title, poster = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(seasonLine, color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
                    Text("·  ${formatBytes(group.bytes)}", color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                }
            }
            // FR-10 — the header deletes the whole series, behind the same confirm.
            if (!state.selecting) Box(
                Modifier.size(44.dp).clickable { state.askSeries(group) }.semantics { contentDescription = deleteSeriesLabel },
                contentAlignment = Alignment.Center,
            ) { CloseGlyph(colors.textSecondary, 20.dp) }
        }
        group.seasons.forEach { season ->
            if (group.seasons.size > 1 || season.season != null) Text(
                (if (season.season != null) "${str("detail.season")} ${season.season}" else str("detail.episodes")).uppercase(),
                color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
            season.episodes.forEach { row ->
                val r = row.record
                SwipeDeleteRow(row.itemId, state, onAsk = { state.askOne(row) }) {
                    DownloadedEntry(
                        row, state, thumb = { Thumb(row.itemId, group.title, poster = false, number = r.episode) },
                        title = "${cardEpisodeCode(r.season, r.episode ?: 0)} · ${r.title}", subtitle = null,
                    )
                }
            }
        }
    }
}
