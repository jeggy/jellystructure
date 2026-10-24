package dev.jellystructure.ravilo.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * DEB-2254 (FR-8 to FR-13) — a playlist's page: Play, Shuffle and, on a playlist of the viewer's own, the ⋯ (*Edit*,
 * *Rename*, *Delete playlist*), edit mode (drag by the grip, swipe or × to remove, *Done*), rename through the one name
 * sheet, and delete after a confirm. A playlist shared with the viewer keeps today's page, with a *Shared with you* label.
 */

/** Back with edit mode up leaves edit mode (FR-10): the page registers while it is editing, the app's Back asks first. */
object PlaylistEditBack {
    private var leave: (() -> Unit)? = null
    internal fun register(f: (() -> Unit)?) { leave = f }
    /** True when Back was spent leaving edit mode. */
    fun consume(): Boolean { val f = leave ?: return false; f(); return true }
}

@Composable
fun MusicPlaylistScreen(
    id: String,
    name: String,
    loader: MusicLoader<MusicList>,
    changes: PlaylistChanges?,
    onBack: () -> Unit,
    onTrackMore: (TrackSheetRequest) -> Unit,
    /** FR-11 — the page was renamed: the back stack carries the new name. */
    onRenamed: (String) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by loader.state.collectAsState()
    LaunchedEffect(Unit) { loader.load() }
    val scope = rememberCoroutineScope()
    val editor = remember(id, loader, changes) { changes?.let { PlaylistEditor(id, loader, it) } }
    var shownName by remember(name) { mutableStateOf(name) }
    var editing by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<IntRect?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    val l = (state as? Load.Ready)?.value
    val mine = playlistMenuShown(l?.mine == true) && editor != null
    // Edit mode is for a playlist the viewer owns, and ends when the page does or the list empties.
    val editOn = editing && mine && !l!!.tracks.isEmpty()
    DisposableEffect(editOn) {
        if (editOn) PlaylistEditBack.register { editing = false }
        onDispose { if (editOn) PlaylistEditBack.register(null) }
    }

    val failedText = str("music.pl_failed")
    val removedText = str("music.pl_removed", mapOf("name" to shownName))
    val deletedText = str("music.pl_deleted", mapOf("name" to shownName))

    /** FR-9 — remove one song; the toast says it, and a failure says the other thing and changes nothing. */
    suspend fun removeSong(t: MusicTrackItem): Boolean {
        val entry = t.entryId ?: return false
        val ok = editor?.remove(entry) ?: false
        MusicToasts.show(if (ok) removedText else failedText)
        return ok
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        val context = MusicContext("playlist", shownName)
        val deskWide = isDesktopLayout
        val tracks = l?.tracks.orEmpty()
        val empty = l != null && tracks.isEmpty()
        val canPlay = tracks.isNotEmpty()
        val play: () -> Unit = { MusicPlayback.playQueue(tracks, 0, context) }
        val shuffle: () -> Unit = { MusicPlayback.playQueue(tracks, tracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
        val buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            PlaylistButtons(
                editing = editOn, canPlay = canPlay, showDisabled = mine && empty, mine = mine,
                onPlay = play, onShuffle = shuffle, onDone = { editing = false },
                onMore = { at -> menuAt = at; menuOpen = true },
            )
        }
        LazyColumn(contentPadding = PaddingValues(top = if (deskWide) 52.dp else 64.dp, bottom = 24.dp)) {
            // R337 — the album header's shape (`.alh`) for a playlist: its collage, the kind, the name, the count, Play and Shuffle.
            if (deskWide) item(key = "head") {
                Row(Modifier.padding(horizontal = raviloHPad).padding(top = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Collage(tracks.mapNotNull { it.imageUrl }.distinct().take(4), shownName, 210.dp)
                    Column(Modifier.weight(1f)) {
                        Text(str("music.type.playlist"), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                        Text(shownName, color = colors.text, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.7).sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                        if (l != null) Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(listOf(songsCount(tracks.size), fmtTotal(tracks.sumOf { it.durationMs ?: 0L })).joinToString(" · "), color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
                            if (!mine) SharedLabel()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                    }
                }
            } else item(key = "head") {
                Column(Modifier.padding(horizontal = raviloHPad), horizontalAlignment = Alignment.CenterHorizontally) {
                    Collage(tracks.mapNotNull { it.imageUrl }.distinct().take(4), shownName, 180.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(shownName, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    if (l != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(songsCount(tracks.size), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                        if (!mine) SharedLabel()
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                }
            }
            // FR-8 — an empty playlist of the viewer's own says so, and where songs come from.
            if (mine && empty) item(key = "empty") { EmptyOwnPlaylist() }
            if (l != null) itemsIndexed(tracks, key = { i, t -> t.entryId ?: "$i-" + t.id }) { i, t ->
                if (editOn) EditRow(
                    t = t, index = i, count = tracks.size, desk = deskWide,
                    onMove = { to -> scope.launch { if (editor?.move(i, to) == false) MusicToasts.show(failedText) } },
                    onRemove = { removeSong(t) },
                ) else Box(Modifier.padding(horizontal = raviloHPad)) {
                    val remove: (() -> Unit)? = if (canRemoveFromPlaylist(mine, t)) ({ scope.launch { removeSong(t) }; Unit }) else null
                    TrackRow(t, showCover = true, striped = i % 2 == 0, onPlay = { MusicPlayback.playQueue(tracks, i, context) }, onMore = { onTrackMore(TrackSheetRequest(t, remove)) })
                }
            }
        }
        DetailBack(onBack = { if (editOn) editing = false else onBack() }, title = shownName)

        if (menuOpen) PlaylistMenu(
            anchor = menuAt, hasEdit = playlistMenuHasEdit(tracks.size), onDismiss = { menuOpen = false },
            onEdit = { menuOpen = false; editing = true },
            onRename = { menuOpen = false; renameOpen = true },
            onDelete = { menuOpen = false; deleteOpen = true },
        )

        // FR-11 — the one name sheet, filled in, *Save* for *Create*.
        var typed by remember(renameOpen) { mutableStateOf(shownName) }
        var refused by remember(renameOpen) { mutableStateOf(false) }
        var busy by remember(renameOpen) { mutableStateOf(false) }
        PlaylistNameSheet(
            visible = renameOpen, title = str("music.pl_rename"), confirm = str("action.save"),
            text = typed, onText = { typed = it.take(PLAYLIST_NAME_MAX); refused = false }, refused = refused, busy = busy,
            onCancel = { renameOpen = false },
            onConfirm = {
                val wanted = typed.trim()
                if (!busy && playlistNameReady(typed) && editor != null) {
                    if (wanted == shownName) renameOpen = false
                    else {
                        busy = true
                        scope.launch {
                            try {
                                val kept = editor.rename(wanted)
                                shownName = kept.name; renameOpen = false; onRenamed(kept.name)
                            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                busy = false
                                if (isNameRefusal(e)) refused = true else { renameOpen = false; MusicToasts.show(failedText) }
                            }
                        }
                    }
                }
            },
        )

        // FR-12 — delete asks first.
        var deleting by remember(deleteOpen) { mutableStateOf(false) }
        DeleteConfirm(
            visible = deleteOpen, name = shownName, busy = deleting,
            onCancel = { deleteOpen = false },
            onDelete = {
                if (!deleting && editor != null) {
                    deleting = true
                    scope.launch {
                        try {
                            editor.delete()
                            deleteOpen = false
                            MusicToasts.show(deletedText)
                            onBack()   // the list it came from reloads without it; the queue, if started here, is client state and plays on
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            deleteOpen = false; MusicToasts.show(failedText)
                        }
                    }
                }
            },
        )
    }
}

// ── the header's buttons ──

/**
 * *Play* · *Shuffle* · ⋯ on a playlist of the viewer's own; *Play* · *Shuffle* on a shared one; *Done* alone in edit mode.
 * An empty playlist of the viewer's own draws *Play* and *Shuffle* disabled (round, Q2).
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PlaylistButtons(
    editing: Boolean, canPlay: Boolean, showDisabled: Boolean, mine: Boolean,
    onPlay: () -> Unit, onShuffle: () -> Unit, onDone: () -> Unit, onMore: (IntRect?) -> Unit,
) {
    val colors = RaviloTheme.colors
    val desk = isDesktopLayout
    if (editing) {
        // *Done* is the way out of edit mode, not its action (dragging is), so it takes the secondary fill.
        if (desk) PillButton(str("music.pl_done"), null, primary = false, Modifier.testTag("playlist-done")) { onDone() }
        else Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant).border(1.dp, colors.fg.copy(0.10f), RoundedCornerShape(14.dp))
                .tap(onDone).testTag("playlist-done"),
            contentAlignment = Alignment.Center,
        ) { Text(str("music.pl_done"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora) }
        return
    }
    if (canPlay) {
        PillButton(str("music.play"), MusicIcon.PLAY, primary = true, pillWidth(), onPlay)
        PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, pillWidth(), onShuffle)
    } else if (showDisabled) {
        PillButton(str("music.play"), MusicIcon.PLAY, primary = true, pillWidth().alpha(0.4f).testTag("playlist-play-disabled")) { }
        PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, pillWidth().alpha(0.4f).testTag("playlist-shuffle-disabled")) { }
    }
    if (mine) MoreButton(onMore)
}

/** FR-8 — the ⋯ beside *Play* and *Shuffle*: 48 dp on the phone, 38 dp on a computer. It tells the menu where it is. */
@Composable
private fun MoreButton(onOpen: (IntRect?) -> Unit) {
    val colors = RaviloTheme.colors
    val desk = isDesktopLayout
    var bounds by remember { mutableStateOf<IntRect?>(null) }
    val shape = RoundedCornerShape(if (desk) 10.dp else 24.dp)
    Box(
        Modifier.size(if (desk) 38.dp else 48.dp).clip(shape).background(if (desk) colors.fg.copy(0.10f) else colors.surfaceVariant)
            .onGloballyPositioned { c -> if (c.isAttached) bounds = c.boundsInWindow().let { r -> IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()) } }
            .tap { onOpen(bounds) }.testTag("playlist-more"),
        contentAlignment = Alignment.Center,
    ) { MusicGlyph(MusicIcon.MORE, colors.text, if (desk) 16.dp else 20.dp, description = str("music.more")) }
}

/** FR-8 — *Shared with you*, beside the count of a playlist someone else made. */
@Composable
private fun SharedLabel() {
    val colors = RaviloTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, colors.fg.copy(0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("playlist-shared"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MusicGlyph(MusicIcon.SHARED, colors.textSecondary, 13.dp)
        Text(str("music.pl_shared").uppercase(), color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** FR-8 — *No songs yet* and the hint, in the dashed card of the round. */
@Composable
private fun EmptyOwnPlaylist() {
    val colors = RaviloTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = raviloHPad + 22.dp).padding(top = 28.dp).clip(RoundedCornerShape(14.dp)).dashedBorder(colors.fg.copy(0.22f), 14.dp)
            .padding(horizontal = 18.dp, vertical = 22.dp).testTag("playlist-empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(str("music.pl_empty"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, textAlign = TextAlign.Center)
        Text(str("music.pl_empty_hint"), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center)
    }
}

// ── the ⋯ menu ──

/** FR-8 — *Edit* (not on an empty playlist), *Rename* and, set apart in the destructive colour, *Delete playlist*. */
@Composable
private fun PlaylistMenu(anchor: IntRect?, hasEdit: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = RaviloTheme.colors
    val bad = playlistBad()
    val desk = isDesktopLayout
    AnchoredPopover(anchor, onDismiss, width = if (desk) 196.dp else 216.dp, alignEnd = true) {
        Column(Modifier.testTag("playlist-menu")) {
            if (hasEdit) MenuRow(MusicIcon.REORDER, str("music.pl_edit"), colors.text, "playlist-menu-edit", onEdit)
            MenuRow(MusicIcon.PENCIL, str("music.pl_rename"), colors.text, "playlist-menu-rename", onRename)
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(1.dp).background(colors.fg.copy(0.10f)))
            MenuRow(MusicIcon.TRASH, str("music.pl_delete"), bad, "playlist-menu-delete", onDelete)
        }
    }
}

@Composable
private fun MenuRow(icon: MusicIcon, label: String, ink: Color, tag: String, onClick: () -> Unit) {
    val desk = isDesktopLayout
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (desk) 38.dp else 48.dp).clip(RoundedCornerShape(9.dp)).tap(onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MusicGlyph(icon, ink, if (desk) 16.dp else 19.dp)
        Text(label, color = ink, fontSize = if (desk) 13.5.sp else 15.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1)
    }
}

// ── the confirm sheet (FR-12) ──

/** *Delete {name}?* · *This can't be undone.* · *Delete* in the destructive style and *Cancel*. A sheet on the phone, a dialog on a computer. */
@Composable
private fun DeleteConfirm(visible: Boolean, name: String, busy: Boolean, onCancel: () -> Unit, onDelete: () -> Unit) {
    val colors = RaviloTheme.colors
    val desk = isDesktopLayout
    HandsetSheet(visible = visible, onDismiss = onCancel, dismissible = !busy) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = if (desk) 18.dp else 14.dp).testTag("playlist-confirm"), horizontalAlignment = if (desk) Alignment.Start else Alignment.CenterHorizontally) {
            Text(str("music.pl_delete_q", mapOf("name" to name)), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, textAlign = if (desk) TextAlign.Start else TextAlign.Center)
            Text(str("music.pl_delete_line"), color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.padding(top = 6.dp), textAlign = if (desk) TextAlign.Start else TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, if (desk) Alignment.End else Alignment.CenterHorizontally)) {
                ConfirmButton(str("action.cancel"), bad = false, desk = desk, tag = "playlist-confirm-cancel", modifier = if (desk) Modifier else Modifier.weight(1f), onClick = onCancel)
                ConfirmButton(str("music.pl_delete_do"), bad = true, desk = desk, tag = "playlist-confirm-delete", modifier = (if (desk) Modifier else Modifier.weight(1f)).alpha(if (busy) 0.6f else 1f)) { if (!busy) onDelete() }
            }
        }
    }
}

@Composable
private fun ConfirmButton(label: String, bad: Boolean, desk: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(if (desk) 9.dp else 13.dp)
    Box(
        modifier.height(if (desk) 36.dp else 50.dp).clip(shape).background(if (bad) playlistBad() else colors.fg.copy(0.10f)).then(if (desk) Modifier.padding(horizontal = 16.dp) else Modifier)
            .tap(onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (bad) Color.White else colors.text, fontSize = if (desk) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1) }
}

// ── edit mode (FR-10) ──

/**
 * A row in edit mode: cover · title over artist · the grip (the queue's handle). Dragging by the grip lifts the row and
 * it lands where it is dropped ([onMove] gets that index); swiping left past the threshold removes it ([onRemove]; a
 * failure brings it back); on a computer, a × at the end does the same on hover. Tapping the row does nothing.
 */
@Composable
private fun EditRow(t: MusicTrackItem, index: Int, count: Int, desk: Boolean, onMove: (Int) -> Unit, onRemove: suspend () -> Boolean) {
    val colors = RaviloTheme.colors
    val density = LocalDensity.current
    val rowDp = if (desk) 44.dp else 62.dp
    val rowPx = with(density) { rowDp.toPx() }
    val scope = rememberCoroutineScope()
    val swipe = remember(t.entryId, index) { Animatable(0f) }
    var dragDy by remember(t.entryId, index) { mutableFloatStateOf(0f) }
    var lifted by remember(t.entryId, index) { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val panelPx = with(density) { 132.dp.toPx() }

    suspend fun removeIt() {
        swipe.animateTo(-panelPx * 3, tween(140))
        if (!onRemove()) swipe.animateTo(0f, tween(140))
    }

    Box(Modifier.padding(horizontal = if (desk) raviloHPad else 0.dp).fillMaxWidth().height(rowDp).zIndex(if (lifted) 1f else 0f).testTag("playlist-edit-row")) {
        // The panel the swipe uncovers.
        if (!desk) Row(Modifier.fillMaxSize().background(playlistBad()).padding(end = 22.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            MusicGlyph(MusicIcon.CLOSE, Color.White, 19.dp)
            Spacer(Modifier.width(9.dp))
            Text(str("music.pl_remove_swipe"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
        Row(
            Modifier.fillMaxSize()
                .offset { IntOffset(swipe.value.roundToInt(), if (lifted) dragDy.roundToInt() else 0) }
                .background(if (lifted) colors.surfaceVariant else colors.background, RoundedCornerShape(if (desk) 8.dp else 0.dp))
                .hoverable(hover)
                .pointerInput(t.entryId, index) {
                    if (desk) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = { scope.launch { if (swipe.value < -rowPx * 1.8f) removeIt() else swipe.animateTo(0f, tween(140)) } },
                        onDragCancel = { scope.launch { swipe.animateTo(0f, tween(140)) } },
                        onHorizontalDrag = { _, d -> scope.launch { swipe.snapTo((swipe.value + d).coerceAtMost(0f)) } },
                    )
                }
                .padding(start = if (desk) 6.dp else 18.dp, end = if (desk) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (desk) Grip(index, count, rowPx, desk, onLift = { lifted = it }, onDy = { dragDy = it }, current = { dragDy }, onDrop = onMove)
            MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(if (desk) 32.dp else 44.dp), corner = if (desk) 5.dp else 6.dp, requestedWidth = 120, wordmarkSize = if (desk) 7 else 9)
            Column(Modifier.weight(1f)) {
                Text(t.title, color = colors.text, fontSize = if (desk) 13.sp else 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = artistLine(t)
                if (sub.isNotBlank()) Text(sub, color = if (desk) colors.textDim else colors.textSecondary, fontSize = if (desk) 11.5.sp else 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (desk) {
                Text(fmtLen(t.durationMs), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                // E1 — a × at the row's end, on hover: no swipe with a mouse.
                Box(Modifier.size(34.dp).then(if (hovered) Modifier.tap { scope.launch { removeIt() } } else Modifier).testTag("playlist-edit-remove"), contentAlignment = Alignment.Center) {
                    if (hovered) MusicGlyph(MusicIcon.CLOSE, colors.text, 15.dp, description = str("music.pl_remove"))
                }
            } else Grip(index, count, rowPx, desk, onLift = { lifted = it }, onDy = { dragDy = it }, current = { dragDy }, onDrop = onMove)
        }
    }
}

/** The queue's handle: lift and move; the row lands where it is dropped. */
@Composable
private fun Grip(index: Int, count: Int, rowPx: Float, desk: Boolean, onLift: (Boolean) -> Unit, onDy: (Float) -> Unit, current: () -> Float, onDrop: (Int) -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.size(if (desk) 26.dp else 40.dp).testTag("playlist-grip").pointerInput(index, count) {
            detectVerticalDragGestures(
                onDragStart = { onLift(true); onDy(0f) },
                onDragEnd = {
                    val to = dropIndex(index, current(), rowPx, count)
                    onLift(false); onDy(0f)
                    if (to != index) onDrop(to)
                },
                onDragCancel = { onLift(false); onDy(0f) },
                onVerticalDrag = { _, d -> onDy(current() + d) },
            )
        },
        contentAlignment = Alignment.Center,
    ) { MusicGlyph(MusicIcon.DRAG, colors.textDim, if (desk) 15.dp else 20.dp) }
}
