package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicPlaylistAdded
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * DEB-2254 (FR-3, FR-4, FR-6, FR-7, FR-13, FR-14) — making a playlist and adding to one, from three entry points
 * (the Playlists chip, the *Add to* picker, *Save queue as playlist*) through one name sheet.
 *
 * Every entry point only *asks*: it fills a request into [PlaylistFlow] and [PlaylistHost], drawn once over the whole
 * app, shows the picker or the name sheet, makes the write and says the outcome in a toast. The screens keep no copy
 * of the playlists: a write bumps [PlaylistFlow.version], and whatever lists playlists reads again (FR-14).
 */

/** What the flow writes through: [TvApiClient] in the app, a stand-in in tests. */
interface PlaylistWrites {
    suspend fun playlists(): List<MusicPlaylist>
    suspend fun create(name: String, itemIds: List<String>): MusicPlaylist
    suspend fun add(id: String, itemIds: List<String>): MusicPlaylistAdded
}

fun TvApiClient.asPlaylistWrites(): PlaylistWrites = object : PlaylistWrites {
    override suspend fun playlists() = getMusicPlaylists()
    override suspend fun create(name: String, itemIds: List<String>) = createMusicPlaylist(name, itemIds)
    override suspend fun add(id: String, itemIds: List<String>) = addToMusicPlaylist(id, itemIds)
}

/** Where the songs came from, which decides the words of the toast. */
enum class PlaylistSource { NOTHING, SONG, ALBUM, QUEUE }

/** FR-4 — the picker, asked to add [itemIds]; [anchor] is the ⋯ that asked (window pixels), for the desktop's popover. */
data class PlaylistAddRequest(val source: PlaylistSource, val itemIds: List<String>, val subtitle: String?, val anchor: IntRect? = null)

/** FR-3 — the name sheet, asked to make a playlist holding [itemIds]; [onCreated] runs with the new playlist (the chip opens it). */
data class PlaylistNameRequest(val source: PlaylistSource, val itemIds: List<String>, val onCreated: (MusicPlaylist) -> Unit = {})

object PlaylistFlow {
    private val _picker = MutableStateFlow<PlaylistAddRequest?>(null)
    val picker: StateFlow<PlaylistAddRequest?> = _picker
    private val _name = MutableStateFlow<PlaylistNameRequest?>(null)
    val nameSheet: StateFlow<PlaylistNameRequest?> = _name
    private val _version = MutableStateFlow(0)
    /** FR-14 — goes up after every write; a list of playlists reads again when it changes. */
    val version: StateFlow<Int> = _version

    fun addTo(r: PlaylistAddRequest) { _picker.value = r }
    fun closePicker() { _picker.value = null }
    fun nameIt(r: PlaylistNameRequest) { _name.value = r }
    fun closeName() { _name.value = null }
    fun changed() { _version.value = _version.value + 1 }
}

/** The ⋯ a viewer last pressed on a song, in window pixels: where the desktop's popover opens. Null when it is not known. */
object MoreAnchor {
    var last: IntRect? = null
}

// ── the pure parts ──

/** FR-4, D3, D6 — the picker offers the viewer's own playlists only, by name. */
fun pickerPlaylists(all: List<MusicPlaylist>): List<MusicPlaylist> = all.filter { it.mine }.sortedBy { it.name.lowercase() }

/** A toast as a key and its variables, so a test reads it without a language. */
data class ToastText(val key: String, val vars: Map<String, String> = emptyMap())

private fun ToastText.say(lang: String) = t(key, lang, vars)

/** FR-4 / FR-6 — after `POST …/items`: *Added to {name}*, *Added {n} songs to {name}* for an album, *Already in {name}* when nothing went in. */
fun addedToast(source: PlaylistSource, name: String, r: MusicPlaylistAdded): ToastText = when {
    r.added == 0 -> ToastText("music.pl_already", mapOf("name" to name))
    source == PlaylistSource.ALBUM && r.added > 1 -> ToastText("music.pl_added_n", mapOf("n" to r.added.toString(), "name" to name))
    else -> ToastText("music.pl_added", mapOf("name" to name))
}

/** FR-3 / FR-7 — after `POST /playlists`: *Created {name}*, or the words of what went in. [sent] is what the request carried. */
fun createdToast(source: PlaylistSource, made: MusicPlaylist, sent: Int): ToastText {
    val n = if (made.trackCount > 0) made.trackCount else sent
    val name = mapOf("name" to made.name)
    return when (source) {
        PlaylistSource.NOTHING -> ToastText("music.pl_created", name)
        PlaylistSource.QUEUE -> ToastText("music.pl_saved_n", name + ("n" to n.toString()))
        PlaylistSource.ALBUM -> if (n > 1) ToastText("music.pl_added_n", name + ("n" to n.toString())) else ToastText("music.pl_added", name)
        PlaylistSource.SONG -> ToastText("music.pl_added", name)
    }
}

/** FR-3 — Jellyfin allows a name of up to 100 characters; the field holds no more, and an empty one is not offered. */
const val PLAYLIST_NAME_MAX = 100
fun playlistNameReady(text: String): Boolean = text.trim().isNotEmpty()

/** FR-13 — a `400` on a name keeps the sheet open with the field marked; anything else closes it and toasts. */
fun isNameRefusal(e: Throwable): Boolean = e is TvApiError.Http && e.status == 400

/** The desktop popover's corner: under the anchor, or above it when it would run off the window; centred without an anchor. */
fun popoverPosition(anchor: IntRect?, window: IntSize, content: IntSize, gap: Int, edge: Int): IntOffset {
    if (anchor == null) return IntOffset((window.width - content.width) / 2, (window.height - content.height) / 2)
    val x = (anchor.left - edge).coerceIn(edge, (window.width - content.width - edge).coerceAtLeast(edge))
    val below = anchor.bottom + gap
    val y = if (below + content.height <= window.height - edge) below
    else (anchor.top - gap - content.height).takeIf { it >= edge } ?: (window.height - content.height - edge).coerceAtLeast(edge)
    return IntOffset(x, y)
}

/** `--pl-bad` of the round: the destructive colour, per skin. */
@Composable
fun playlistBad(): Color = when (LocalRaviloSkin.current) {
    Skin.AURORA -> Color(0xFFD64541)
    Skin.MIDNIGHT -> Color(0xFFE2544E)
    Skin.NOIR -> Color(0xFFE2603F)
}

// ── the host: one picker and one name sheet over the whole app ──

@Composable
fun PlaylistHost(writes: PlaylistWrites) {
    val picker by PlaylistFlow.picker.collectAsState()
    val name by PlaylistFlow.nameSheet.collectAsState()
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()

    val request = picker
    var own by remember(request) { mutableStateOf<List<MusicPlaylist>?>(null) }
    // FR-14 — the list is read when the picker shows, never kept.
    LaunchedEffect(request) {
        if (request != null) own = try { pickerPlaylists(writes.playlists()) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    PlaylistPicker(
        request = request, own = own, onDismiss = PlaylistFlow::closePicker,
        onNew = { r -> PlaylistFlow.closePicker(); PlaylistFlow.nameIt(PlaylistNameRequest(r.source, r.itemIds)) },
        onPick = { r, p ->
            PlaylistFlow.closePicker()
            scope.launch {
                try {
                    val done = writes.add(p.id, r.itemIds)
                    PlaylistFlow.changed()
                    MusicToasts.show(addedToast(r.source, p.name, done).say(lang))
                } catch (e: CancellationException) { throw e } catch (e: Exception) { MusicToasts.show(t("music.pl_failed", lang)) }
            }
        },
    )

    val nameRequest = name
    var typed by remember(nameRequest) { mutableStateOf("") }
    var refused by remember(nameRequest) { mutableStateOf(false) }
    var busy by remember(nameRequest) { mutableStateOf(false) }
    PlaylistNameSheet(
        visible = nameRequest != null, title = str("music.new_playlist"), confirm = str("music.pl_create"),
        text = typed, onText = { typed = it.take(PLAYLIST_NAME_MAX); refused = false }, refused = refused, busy = busy,
        onCancel = PlaylistFlow::closeName,
        onConfirm = {
            val r = nameRequest
            if (r != null && !busy && playlistNameReady(typed)) {
                busy = true
                scope.launch {
                    try {
                        val made = writes.create(typed.trim(), r.itemIds)
                        PlaylistFlow.changed(); PlaylistFlow.closeName()
                        MusicToasts.show(createdToast(r.source, made, r.itemIds.size).say(lang))
                        r.onCreated(made)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        busy = false
                        if (isNameRefusal(e)) refused = true else { PlaylistFlow.closeName(); MusicToasts.show(t("music.pl_failed", lang)) }
                    }
                }
            }
        },
    )
}

// ── the picker (FR-4) ──

/**
 * *Add to playlist* — *New playlist* first, then the viewer's own playlists ([own], already filtered and ordered by
 * [pickerPlaylists]; null while they load or when they could not be read). With none of their own: the one line
 * *Playlists you make show up here.* A sheet on the phone, a popover anchored to the ⋯ on a computer.
 */
@Composable
fun PlaylistPicker(
    request: PlaylistAddRequest?,
    own: List<MusicPlaylist>?,
    onDismiss: () -> Unit,
    onNew: (PlaylistAddRequest) -> Unit,
    onPick: (PlaylistAddRequest, MusicPlaylist) -> Unit,
) {
    var shown by remember { mutableStateOf(request) }
    if (request != null) shown = request
    val desk = isDesktopLayout
    val body: @Composable () -> Unit = {
        val r = shown
        if (r != null) PickerBody(r, own, desk, onNew = { onNew(r) }, onPick = { onPick(r, it) })
    }
    if (desk) {
        if (request != null) AnchoredPopover(request.anchor, onDismiss, width = 312.dp, content = body)
    } else {
        HandsetSheet(visible = request != null, onDismiss = onDismiss) { body() }
    }
}

@Composable
private fun PickerBody(r: PlaylistAddRequest, own: List<MusicPlaylist>?, desk: Boolean, onNew: () -> Unit, onPick: (MusicPlaylist) -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxWidth().testTag("playlist-picker")) {
        Column(Modifier.padding(horizontal = if (desk) 12.dp else 20.dp).padding(top = if (desk) 6.dp else 4.dp, bottom = if (desk) 8.dp else 12.dp)) {
            Text(str("music.pl_add_title"), color = colors.text, fontSize = if (desk) 14.sp else 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            r.subtitle?.let { Text(it, color = colors.textDim, fontSize = if (desk) 12.sp else 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (!desk) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.fg.copy(0.08f)))
        Column(Modifier.heightIn(max = if (desk) 286.dp else 402.dp).verticalScroll(rememberScrollState())) {
            PickerRow(desk, onClick = onNew, tag = "playlist-new", leading = { NewTile(if (desk) 40.dp else 62.dp) }) {
                Text(str("music.new_playlist"), color = colors.text, fontSize = if (desk) 13.5.sp else 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1)
            }
            if (own != null && own.isNotEmpty()) {
                if (desk) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).height(1.dp).background(colors.fg.copy(0.08f)))
                own.forEach { p ->
                    if (!desk) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.fg.copy(0.08f)))
                    PickerRow(desk, onClick = { onPick(p) }, tag = "playlist-row", leading = { Collage(p.covers, p.name, if (desk) 40.dp else 62.dp) }) {
                        Text(p.name, color = colors.text, fontSize = if (desk) 13.5.sp else 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songsCount(p.trackCount), color = colors.textDim, fontSize = if (desk) 11.5.sp else 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (own != null && own.isEmpty()) Text(
            str("music.pl_none"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp, bottom = if (desk) 10.dp else 8.dp).testTag("playlist-none"),
        )
    }
}

@Composable
private fun PickerRow(desk: Boolean, onClick: () -> Unit, tag: String, leading: @Composable () -> Unit, text: @Composable () -> Unit) {
    val rowMod = if (desk) Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(9.dp)).tap(onClick).padding(horizontal = 8.dp, vertical = 7.dp)
    else Modifier.fillMaxWidth().heightIn(min = 78.dp).tap(onClick).padding(horizontal = 18.dp, vertical = 8.dp)
    Row(rowMod.testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (desk) 11.dp else 14.dp)) {
        leading()
        Column(Modifier.weight(1f)) { text() }
    }
}

/** A dashed outline: what marks a control that makes something rather than shows something (N1's + tile, N2's button). */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** N1 — the + tile where a playlist's collage sits: dashed, no artwork, so the row reads as an action. */
@Composable
private fun NewTile(size: Dp) {
    val colors = RaviloTheme.colors
    val radius = if (size > 50.dp) 10.dp else 7.dp
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).background(colors.fg.copy(0.04f)).dashedBorder(colors.fg.copy(0.22f), radius),
        contentAlignment = Alignment.Center,
    ) { MusicGlyph(MusicIcon.PLUS, colors.textSecondary, if (size > 50.dp) 24.dp else 18.dp) }
}

/**
 * A popover on a computer: a card beside the control that opened it, under it or — when that runs off the window —
 * over it, closing on a click outside. [anchor] is that control in window pixels; null centres the card.
 */
@Composable
internal fun AnchoredPopover(anchor: IntRect?, onDismiss: () -> Unit, width: Dp, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val edge = with(density) { 12.dp.roundToPx() }
    val provider = remember(anchor, gap, edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                popoverPosition(anchor, windowSize, popupContentSize, gap, edge)
        }
    }
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(width).shadow(22.dp, shape).clip(shape)
                .background(if (colors.isLight) colors.surface else Color(0xFF0E1119))
                .border(1.dp, colors.fg.copy(alpha = 0.12f), shape)
                .padding(6.dp),
        ) { content() }
    }
}

// ── the name sheet (FR-3) ──

/**
 * One text field with the system keyboard, [confirm] and *Cancel*. *Create* stays in place and inert while the name is
 * empty. [refused] marks the field and says *Give it a name* under it (FR-13's `400`). A sheet on the phone; the
 * computer's window shows the same content as a dialog.
 */
@Composable
fun PlaylistNameSheet(
    visible: Boolean, title: String, confirm: String, text: String, onText: (String) -> Unit,
    refused: Boolean, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val bad = playlistBad()
    val desk = isDesktopLayout
    val ready = playlistNameReady(text) && !busy
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    HandsetSheet(visible = visible, onDismiss = onCancel, dismissible = !busy) {
        LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: IllegalStateException) { } }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = if (desk) 18.dp else 12.dp).testTag("playlist-name-sheet")) {
            Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(14.dp))
            Text(str("music.pl_name"), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(if (desk) 42.dp else 48.dp)
                    .background(if (desk) colors.fg.copy(0.06f) else colors.background, RoundedCornerShape(12.dp))
                    .border(1.dp, if (refused) bad else if (focused) colors.accent else colors.fg.copy(0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text, onValueChange = onText,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.reportTextFieldFocus().testTag("playlist-name-field"),
                    textStyle = TextStyle(color = colors.text, fontSize = if (desk) 14.5.sp else 16.sp, fontFamily = Sora),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    cursorBrush = SolidColor(colors.accent),
                )
            }
            if (refused) Text(str("music.pl_name_empty"), color = bad, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.padding(top = 8.dp).testTag("playlist-name-error"))
            Spacer(Modifier.height(14.dp))
            if (desk) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                SheetButton(str("action.cancel"), primary = false, ready = true, desk = true, tag = "playlist-name-cancel", onClick = onCancel)
                SheetButton(confirm, primary = true, ready = ready, desk = true, tag = "playlist-name-confirm", onClick = { if (ready) onConfirm() })
            } else {
                SheetButton(confirm, primary = true, ready = ready, desk = false, tag = "playlist-name-confirm", onClick = { if (ready) onConfirm() })
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).tap(onCancel).testTag("playlist-name-cancel"), contentAlignment = Alignment.Center) {
                    Text(str("action.cancel"), color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, ready: Boolean, desk: Boolean, tag: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val noir = LocalRaviloSkin.current == Skin.NOIR
    val fill = when {
        !ready -> Modifier.background(colors.fg.copy(0.10f), RoundedCornerShape(if (desk) 9.dp else 13.dp))
        primary && desk -> Modifier.background(colors.text, RoundedCornerShape(9.dp))
        primary && noir -> Modifier.background(colors.text, RoundedCornerShape(13.dp))
        primary -> Modifier.background(colors.accentGradient, RoundedCornerShape(13.dp))
        else -> Modifier.background(colors.fg.copy(0.10f), RoundedCornerShape(9.dp))
    }
    val ink = when {
        !ready -> colors.textDim
        primary && (desk || noir) -> colors.background
        primary -> colors.onAccent
        else -> colors.text
    }
    Box(
        (if (desk) Modifier.height(36.dp) else Modifier.fillMaxWidth().height(50.dp)).then(fill)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).testTag(tag)
            .then(if (desk) Modifier.padding(horizontal = 16.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ink, fontSize = if (desk) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1) }
}
