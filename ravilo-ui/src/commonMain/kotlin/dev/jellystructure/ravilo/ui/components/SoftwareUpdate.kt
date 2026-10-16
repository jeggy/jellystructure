package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.SystemUiFont
import dev.jellystructure.ravilo.ui.theme.accentGradient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R386 (FR-R386-6/-7) — what the *Software update* block says, as one value. The Mac app's [SoftwareUpdate] holds the
 * latest: it is built from the last check and Sparkle's last report and from nothing else, so the screens below draw
 * what is true and never what is expected (FR-R386-7).
 */
sealed interface UpdateBlockState {
    /** State 1. [checked] is null until a check has reached the server. */
    data class UpToDate(val checked: CheckedAt?) : UpdateBlockState
    /** State 2. */
    data object Checking : UpdateBlockState
    /** State 3, the toggle on. [bytes] and [total] are -1 when Sparkle reports none (it does not, for a background download). */
    data class Downloading(val version: String, val bytes: Long, val total: Long) : UpdateBlockState
    /** State 3's offer form, the toggle off: **Download** hands over to Sparkle's window (D9). */
    data class Offer(val version: String) : UpdateBlockState
    /** States 4 and 5 — [SoftwareUpdate.playing] decides whether *Restart to update* shows. [notesUrl] is the tag's release page. */
    data class Ready(val version: String, val notesUrl: String) : UpdateBlockState
    /** State 6. */
    data class HeldBack(val latest: String, val server: String) : UpdateBlockState
    /** State 7. [signature]: the download arrived but could not be confirmed; otherwise it did not arrive. */
    data class Failed(val signature: Boolean) : UpdateBlockState
    /** State 8 (FR-R386-5). [fromDiskImage]: the app runs from the mounted image; otherwise from a temporary copy. */
    data class CannotUpdate(val fromDiskImage: Boolean) : UpdateBlockState
    /** State 12. */
    data class Offline(val checked: CheckedAt?) : UpdateBlockState
    /** The handover line: Ravilo's last, before Sparkle's own window. */
    data class Handover(val version: String) : UpdateBlockState
}

/** When a check finished, already in the viewer's own formats; [today] picks *Checked today at {t}*. */
data class CheckedAt(val today: Boolean, val date: String, val time: String)

/**
 * What the block shows. [appRelease] is the app's own release (`1.52`); [autoDownload] is state 9's toggle as stored.
 * A null model is state 10: the block is absent — not greyed, not empty.
 */
data class SoftwareUpdateModel(val block: UpdateBlockState, val appRelease: String, val autoDownload: Boolean)

/** What the block's controls do; the Mac app installs the real ones. */
class SoftwareUpdateActions(
    val checkNow: () -> Unit = {},
    val restart: () -> Unit = {},
    val download: () -> Unit = {},
    val tryAgain: () -> Unit = {},
    val setAutoDownload: (Boolean) -> Unit = {},
    val quit: () -> Unit = {},
)

/**
 * R386 (FR-R386-6/-7/-8) — the update block's state and what its controls do. A platform without the updater (every one
 * but the Mac release build) never sets a model, so [model] stays null and no screen draws the block, the dot or the row.
 */
object SoftwareUpdate {
    private val _model = MutableStateFlow<SoftwareUpdateModel?>(null)
    val model: StateFlow<SoftwareUpdateModel?> = _model.asStateFlow()
    fun set(model: SoftwareUpdateModel?) { _model.value = model }

    /** Installed once, at start-up, by the platform that has the updater. */
    var actions: SoftwareUpdateActions = SoftwareUpdateActions()

    /** True while anything plays or casts (a film, music, a cast session this Mac controls): *Restart to update* is withheld (state 5). */
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    fun setPlaying(on: Boolean) { _playing.value = on }

    /** FR-R386-8 / D10 — *Hide* removes the row until the next launch. The dot stays until the update is installed. */
    private val _rowHidden = MutableStateFlow(false)
    val rowHidden: StateFlow<Boolean> = _rowHidden.asStateFlow()
    fun hideRow() { _rowHidden.value = true }
    /** Tests only: a launch starts with the row showing. */
    internal fun resetForTest() { _rowHidden.value = false; _playing.value = false; _model.value = null; _aboutRequested.value = false }

    /** FR-R386-9 — *Check for Updates…* asks Settings to show About; the Settings page takes the request when it draws. */
    private val _aboutRequested = MutableStateFlow(false)
    val aboutRequested: StateFlow<Boolean> = _aboutRequested.asStateFlow()
    fun requestAbout() { _aboutRequested.value = true }
    fun aboutShown() { _aboutRequested.value = false }

    /** The Ready state's version, or null — the one condition of the notice's dot and row (FR-R386-8). */
    fun readyVersion(model: SoftwareUpdateModel?): String? = (model?.block as? UpdateBlockState.Ready)?.version

    /** *Restart to update*, and the notice's *Restart*, do exactly this — and never while something plays (state 5). */
    fun restart() { if (!_playing.value) actions.restart() }
}

// ───────────────────────────────── drawing ─────────────────────────────────

// The round's own tokens (`mac-update.css`: --ok, --warn, --bad, --acc-ink), dark and light.
private val OK @Composable get() = if (RaviloTheme.colors.isLight) Color(0xFF15966A) else Color(0xFF2DD49A)
private val WARN @Composable get() = if (RaviloTheme.colors.isLight) Color(0xFFC98A12) else Color(0xFFF5B542)
private val BAD @Composable get() = if (RaviloTheme.colors.isLight) Color(0xFFD14B3E) else Color(0xFFFF6F61)

/** `--acc-ink` — the accent, lightened on a dark ground and deepened on a light one: icons and links on the tinted ground. */
@Composable
private fun accInk(): Color {
    val colors = RaviloTheme.colors
    return androidx.compose.ui.graphics.lerp(colors.accent, if (colors.isLight) Color.Black else Color.White, if (colors.isLight) 0.2f else 0.55f)
}

/** `border: 1px dashed` — Compose's [border] has no dash, and state 5 is drawn dashed: nothing there can be pressed. */
private fun Modifier.dashedBorder(width: androidx.compose.ui.unit.Dp, color: Color, corner: androidx.compose.ui.unit.Dp): Modifier = this.drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))),
    )
}

private enum class Tone { NEUTRAL, OK, DOWNLOAD, BAD, WARN, READY }

/** One block's worth of sentences, picked from the state — a pure function, so the strings can be tested without drawing. */
internal class BlockText(val title: String, val sub: String?)

/**
 * `.mu-blk` — the block in one of the two families. [desk] is the desktop family's 13 sp system type, a 24 dp button
 * and a row on the right; otherwise the phone family's Sora, 46 dp buttons stacked under the text.
 */
@Composable
fun SoftwareUpdateBlock(desk: Boolean, modifier: Modifier = Modifier) {
    val model by SoftwareUpdate.model.collectAsState()
    val playing by SoftwareUpdate.playing.collectAsState()
    val m = model ?: return
    val colors = RaviloTheme.colors
    val block = m.block
    val tone = when (block) {
        is UpdateBlockState.UpToDate -> Tone.OK
        is UpdateBlockState.Downloading, is UpdateBlockState.Offer, UpdateBlockState.Checking, is UpdateBlockState.Handover -> Tone.DOWNLOAD
        is UpdateBlockState.Ready -> if (playing) Tone.NEUTRAL else Tone.READY
        is UpdateBlockState.HeldBack, is UpdateBlockState.CannotUpdate -> Tone.WARN
        is UpdateBlockState.Failed -> Tone.BAD
        is UpdateBlockState.Offline -> Tone.NEUTRAL
    }
    // Ready stays the one prominent state while it is waiting for playback too, but dashed (`.mu-blk.wait`).
    val prominent = block is UpdateBlockState.Ready || block is UpdateBlockState.Handover
    val waiting = block is UpdateBlockState.Ready && playing
    val shape = RoundedCornerShape(if (desk) 10.dp else 16.dp)
    val edge = when {
        prominent -> colors.accent.copy(alpha = if (waiting) 0.6f else 1f)
        tone == Tone.BAD -> BAD.copy(alpha = 0.45f)
        tone == Tone.WARN -> WARN.copy(alpha = 0.5f)
        else -> colors.fg.copy(alpha = 0.08f)
    }
    val ground = if (prominent && !waiting) colors.accent.copy(alpha = 0.16f) else Color.Transparent
    Column(modifier.fillMaxWidth().testTag(UPDATE_BLOCK_TAG)) {
        Text(
            str("mac.update.title"), color = colors.textSecondary, fontSize = if (desk) 12.sp else 13.sp,
            fontWeight = if (desk) FontWeight.Bold else FontWeight.SemiBold, fontFamily = if (desk) SystemUiFont else Sora,
            modifier = Modifier.padding(start = if (desk) 0.dp else 4.dp, bottom = 8.dp),
        )
        Column(
            Modifier.fillMaxWidth().clip(shape).background(colors.card, shape).background(ground, shape)
                .then(if (waiting) Modifier.dashedBorder(1.5.dp, edge, if (desk) 10.dp else 16.dp) else Modifier.border(if (prominent) 1.5.dp else 1.dp, edge, shape)),
        ) {
            StatusRow(m, block, tone, playing, desk)
            val toggle = block !is UpdateBlockState.CannotUpdate && block !is UpdateBlockState.Handover
            if (toggle) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.fg.copy(alpha = 0.08f)))
                ToggleRow(m.autoDownload, desk)
            }
        }
    }
}

@Composable
private fun StatusRow(m: SoftwareUpdateModel, block: UpdateBlockState, tone: Tone, playing: Boolean, desk: Boolean) {
    val colors = RaviloTheme.colors
    val actions = SoftwareUpdate.actions
    val uri = LocalUriHandler.current
    val body = if (desk) SystemUiFont else Sora
    val head = if (desk) SystemUiFont else SpaceGrotesk
    val text = blockText(block, m.appRelease, playing)
    val inkTitle = when (tone) { Tone.BAD -> BAD; Tone.WARN -> WARN; else -> colors.text }
    val ready = block is UpdateBlockState.Ready
    val icon = when (block) {
        is UpdateBlockState.UpToDate -> DeskIcon.CHECK
        is UpdateBlockState.Downloading, is UpdateBlockState.Offer -> DeskIcon.DOWNLOAD
        is UpdateBlockState.Ready -> DeskIcon.INSTALL
        is UpdateBlockState.HeldBack -> DeskIcon.CLOCK
        is UpdateBlockState.Failed -> DeskIcon.ALERT
        is UpdateBlockState.CannotUpdate -> DeskIcon.FOLDER
        is UpdateBlockState.Offline -> DeskIcon.OFFLINE
        UpdateBlockState.Checking, is UpdateBlockState.Handover -> null
    }
    val glyph = when (tone) {
        Tone.OK -> OK; Tone.DOWNLOAD -> colors.accentSecondary; Tone.BAD -> BAD; Tone.WARN -> WARN
        Tone.READY -> Color.White; Tone.NEUTRAL -> colors.textSecondary
    }
    val glyphBg = when (tone) {
        Tone.READY -> colors.accent
        Tone.NEUTRAL -> if (ready) colors.accent.copy(alpha = 0.16f) else colors.fg.copy(alpha = 0.10f)
        else -> glyph.copy(alpha = 0.16f)
    }
    val glyphInk = if (ready && tone == Tone.NEUTRAL) accInk() else glyph
    val circle = if (desk) 32.dp else 38.dp

    // The buttons and the link this state offers, as (label, primary, onClick).
    val buttons: List<Triple<String, Boolean, () -> Unit>> = when (block) {
        is UpdateBlockState.UpToDate -> listOf(Triple(str("mac.update.check_now"), false, actions.checkNow))
        is UpdateBlockState.Offer -> listOf(Triple(str("mac.download"), true, actions.download))
        is UpdateBlockState.Ready -> if (playing) emptyList() else listOf(Triple(str("mac.update.restart"), true, { SoftwareUpdate.restart() }))
        is UpdateBlockState.Failed -> listOf(Triple(str("mac.update.try_again"), false, actions.tryAgain))
        is UpdateBlockState.CannotUpdate -> listOf(Triple(str("mac.menu_quit"), false, actions.quit))
        else -> emptyList()
    }
    val link = (block as? UpdateBlockState.Ready)?.let { r -> str("mac.update.whats_new") + " ›" to { runCatching { uri.openUri(r.notesUrl) }; Unit } }

    @Composable
    fun Texts(modifier: Modifier) = Column(modifier.padding(top = 1.dp)) {
        Text(text.title, color = inkTitle, fontSize = if (desk) (if (ready) 15.sp else 13.5.sp) else (if (ready) 18.sp else 16.sp), fontWeight = if (desk) FontWeight.SemiBold else FontWeight.Bold, fontFamily = head, lineHeight = if (desk) 18.sp else 22.sp)
        if (text.sub != null) Text(text.sub, color = colors.textSecondary, fontSize = if (desk) 12.sp else 13.5.sp, lineHeight = if (desk) 17.sp else 20.sp, fontFamily = body, modifier = Modifier.padding(top = 3.dp))
        (block as? UpdateBlockState.Downloading)?.takeIf { it.total > 0 && it.bytes >= 0 }?.let { d ->
            Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.fg.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth((d.bytes.toFloat() / d.total).coerceIn(0f, 1f)).height(5.dp).background(colors.accentSecondary, RoundedCornerShape(3.dp)))
            }
            Text(str("mac.update.progress", mapOf("a" to megabytes(d.bytes), "b" to megabytes(d.total))), color = colors.textDim, fontSize = if (desk) 11.5.sp else 13.sp, fontFamily = body, modifier = Modifier.padding(top = 5.dp))
        }
    }

    @Composable
    fun Glyph() = Box(Modifier.size(circle).clip(CircleShape).background(glyphBg), contentAlignment = Alignment.Center) {
        if (icon != null) DeskIcon(icon, glyphInk, if (desk) 17.dp else 19.dp, stroke = 2.2f)
        else Box(Modifier.size(15.dp).border(2.dp, colors.fg.copy(alpha = 0.2f), CircleShape).padding(2.dp).border(2.dp, colors.accentSecondary, CircleShape))
    }

    if (desk) Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph()
        Texts(Modifier.weight(1f))
        if (buttons.isNotEmpty() || link != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { (label, primary, click) -> UpdateButton(label, primary, true, click) }
            link?.let { (label, click) -> UpdateLink(label, true, click) }
        }
    } else Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph()
            Texts(Modifier.weight(1f))
        }
        if (buttons.isNotEmpty() || link != null) Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { (label, primary, click) -> UpdateButton(label, primary, false, click) }
            link?.let { (label, click) -> UpdateLink(label, false, click) }
        }
    }
}

/** The sentences a state shows, from the strings — kept apart from the drawing so a test can read them. */
@Composable
internal fun blockText(block: UpdateBlockState, appRelease: String, playing: Boolean): BlockText = when (block) {
    is UpdateBlockState.UpToDate -> BlockText(
        str("mac.update.version", mapOf("v" to appRelease)) + " · " + str("mac.update.uptodate"),
        block.checked?.let { checkedLine(it) },
    )
    UpdateBlockState.Checking -> BlockText(str("mac.update.checking"), null)
    is UpdateBlockState.Downloading -> BlockText(str("mac.update.downloading", mapOf("v" to block.version)), str("mac.update.dl_sub"))
    is UpdateBlockState.Offer -> BlockText(str("mac.update_available", mapOf("version" to block.version)), null)
    is UpdateBlockState.Ready -> BlockText(
        str("mac.update.ready", mapOf("v" to block.version)),
        str(if (playing) "mac.update.wait_sub" else "mac.update.ready_sub"),
    )
    is UpdateBlockState.HeldBack -> BlockText(
        str("mac.update.held", mapOf("v" to block.latest)),
        str("mac.update.held_sub", mapOf("sv" to block.server, "v" to block.latest)),
    )
    is UpdateBlockState.Failed -> if (block.signature) BlockText(str("mac.update.fail_ver"), str("mac.update.fail_ver_sub"))
        else BlockText(str("mac.update.fail_dl"), str("mac.update.fail_dl_sub"))
    is UpdateBlockState.CannotUpdate -> BlockText(
        str("mac.install_place.title"),
        str(if (block.fromDiskImage) "mac.install_place.body_volume" else "mac.install_place.body_translocated"),
    )
    is UpdateBlockState.Offline -> BlockText(
        str("mac.update.offline"),
        block.checked?.let { c -> if (c.today) str("mac.update.offline_sub", mapOf("t" to c.time)) else str("mac.update.offline_sub_on", mapOf("d" to c.date, "t" to c.time)) }
            ?: str("mac.update.offline_sub_never"),
    )
    is UpdateBlockState.Handover -> BlockText(str("mac.update.closing", mapOf("v" to block.version)), str("mac.update.closing_sub"))
}

@Composable
private fun checkedLine(c: CheckedAt): String =
    if (c.today) str("mac.update.checked", mapOf("t" to c.time)) else str("mac.update.checked_on", mapOf("d" to c.date, "t" to c.time))

private fun megabytes(bytes: Long): String = (bytes / 1_000_000L).toString()

@Composable
private fun UpdateButton(label: String, primary: Boolean, desk: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(if (desk) 6.dp else 12.dp)
    Box(
        Modifier.then(if (desk) Modifier else Modifier.fillMaxWidth()).height(if (desk) 24.dp else 46.dp).clip(shape)
            .background(if (primary) colors.accent else colors.fg.copy(alpha = 0.12f))
            .handCursor().clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(UPDATE_BUTTON_TAG_PREFIX + label),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else colors.text, fontSize = if (desk) 12.5.sp else 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, fontFamily = if (desk) SystemUiFont else Sora, maxLines = 1)
    }
}

@Composable
private fun UpdateLink(label: String, desk: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Text(
        label, color = accInk(), fontSize = if (desk) 12.sp else 14.sp, fontFamily = if (desk) SystemUiFont else Sora, maxLines = 1,
        textAlign = if (desk) TextAlign.End else TextAlign.Center,
        modifier = Modifier.then(if (desk) Modifier else Modifier.fillMaxWidth()).handCursor().clickable(onClick = onClick).padding(vertical = if (desk) 0.dp else 4.dp),
    )
}

/** State 9 — *Download updates automatically*, and what each position means. */
@Composable
private fun ToggleRow(on: Boolean, desk: Boolean) {
    val colors = RaviloTheme.colors
    val actions = SoftwareUpdate.actions
    val body = if (desk) SystemUiFont else Sora
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (desk) 40.dp else 52.dp).handCursor().clickable { actions.setAutoDownload(!on) }
            .padding(horizontal = if (desk) 14.dp else 16.dp, vertical = 8.dp).testTag(UPDATE_TOGGLE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(str("mac.update.auto"), color = colors.text, fontSize = if (desk) 13.sp else 15.sp, fontWeight = FontWeight.Medium, fontFamily = body)
            Text(str(if (on) "mac.update.auto_on" else "mac.update.auto_off"), color = colors.textDim, fontSize = if (desk) 11.5.sp else 13.sp, fontFamily = body, modifier = Modifier.padding(top = 2.dp))
        }
        val w = if (desk) 32.dp else 44.dp
        val h = if (desk) 18.dp else 26.dp
        val knob = h - 4.dp
        Box(
            Modifier.size(w, h).background(if (on) colors.accent else colors.fg.copy(alpha = 0.18f), RoundedCornerShape(h / 2)).padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(knob).background(Color.White, CircleShape)) }
    }
}

// ───────────────────────────────── the notice ─────────────────────────────────

/** `.dot` — the 8 dp accent dot that says "something is here" (D6); on Profile it is the shared Profile dot (D11). */
@Composable
fun UpdateDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp, ring: Color = RaviloTheme.colors.background) {
    Box(modifier.size(size).background(RaviloTheme.colors.accent, CircleShape).border(2.dp, ring, CircleShape).testTag(UPDATE_DOT_TAG))
}

/** Whether the notice's dot shows: the update is ready (states 4 and 5) and not yet installed. */
@Composable
fun updateDotShows(): Boolean {
    val model by SoftwareUpdate.model.collectAsState()
    return SoftwareUpdate.readyVersion(model) != null
}

/**
 * FR-R386-8 — the ready notice's row: *Update ready* · **Restart** · × in the sidebar's foot ([desk]) and at the top of
 * the phone layout's Profile page. In state 5 **Restart** is the dashed, inert *After playback*: nothing moves, and
 * nothing here can stop playback. Absent unless the update is ready and the viewer has not hidden it this launch.
 */
@Composable
fun UpdateNoticeRow(desk: Boolean, modifier: Modifier = Modifier) {
    val model by SoftwareUpdate.model.collectAsState()
    val playing by SoftwareUpdate.playing.collectAsState()
    val hidden by SoftwareUpdate.rowHidden.collectAsState()
    if (SoftwareUpdate.readyVersion(model) == null || hidden) return
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(if (desk) 9.dp else 16.dp)
    val body = if (desk) SystemUiFont else Sora
    val hide = str("mac.update.hide")
    val inner: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        DeskIcon(DeskIcon.INSTALL, accInk(), if (desk) 15.dp else 20.dp, stroke = if (desk) 1.8f else 2.2f)
        Text(str("mac.update.notice"), color = colors.text, fontSize = if (desk) 12.5.sp else 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = body, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (playing) Text(
            str("mac.update.notice_wait"), color = colors.textSecondary, fontSize = if (desk) 11.5.sp else 13.sp, fontFamily = body, maxLines = 1,
            modifier = Modifier.testTag(UPDATE_NOTICE_WAIT_TAG),
        ) else Box(
            Modifier.height(if (desk) 22.dp else 44.dp).clip(RoundedCornerShape(if (desk) 6.dp else 12.dp)).background(colors.accent)
                .handCursor().clickable { SoftwareUpdate.restart() }.padding(horizontal = if (desk) 9.dp else 16.dp).testTag(UPDATE_NOTICE_RESTART_TAG),
            contentAlignment = Alignment.Center,
        ) { Text(str("mac.update.notice_restart"), color = Color.White, fontSize = if (desk) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = body, maxLines = 1) }
        Box(
            Modifier.size(if (desk) 20.dp else 44.dp).clip(CircleShape).handCursor().clickable { SoftwareUpdate.hideRow() }
                .semantics { contentDescription = hide }.testTag(UPDATE_NOTICE_HIDE_TAG),
            contentAlignment = Alignment.Center,
        ) { DeskIcon(DeskIcon.CLOSE, colors.textDim, if (desk) 11.dp else 14.dp, stroke = 2.4f) }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = if (desk) 34.dp else 56.dp).background(if (playing) Color.Transparent else colors.accent.copy(alpha = 0.16f), shape)
            .then(
                if (playing) Modifier.dashedBorder(if (desk) 1.dp else 1.5.dp, colors.accent.copy(alpha = 0.6f), if (desk) 9.dp else 16.dp)
                else Modifier.border(if (desk) 1.dp else 1.5.dp, colors.accent.copy(alpha = 0.45f), shape),
            )
            .padding(start = if (desk) 8.dp else 16.dp, end = if (desk) 6.dp else 6.dp).testTag(UPDATE_NOTICE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (desk) 6.dp else 12.dp),
    ) { inner() }
}

/**
 * The rail's form of the notice (the frame `about-desktop-ready-notice-rail`): a 62 dp tile, *Restart* under the mark; in
 * state 5 dashed and inert, saying *After playback*. It follows [SoftwareUpdate.rowHidden], like the row it stands for.
 */
@Composable
fun UpdateRailTile(modifier: Modifier = Modifier) {
    val model by SoftwareUpdate.model.collectAsState()
    val playing by SoftwareUpdate.playing.collectAsState()
    val hidden by SoftwareUpdate.rowHidden.collectAsState()
    if (SoftwareUpdate.readyVersion(model) == null || hidden) return
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.width(62.dp).clip(shape).background(if (playing) Color.Transparent else colors.accent.copy(alpha = 0.16f))
            .then(if (playing) Modifier.dashedBorder(1.dp, colors.accent.copy(alpha = 0.6f), 10.dp) else Modifier.border(1.dp, colors.accent.copy(alpha = 0.45f), shape))
            .then(if (playing) Modifier else Modifier.handCursor().clickable { SoftwareUpdate.restart() })
            .padding(top = 6.dp, bottom = 5.dp).testTag(UPDATE_NOTICE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        DeskIcon(DeskIcon.INSTALL, accInk(), 17.dp)
        Text(
            str(if (playing) "mac.update.notice_wait" else "mac.update.notice_restart"), color = if (playing) colors.textSecondary else colors.text,
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, textAlign = TextAlign.Center, lineHeight = 12.sp,
            modifier = Modifier.testTag(if (playing) UPDATE_NOTICE_WAIT_TAG else UPDATE_NOTICE_RESTART_TAG),
        )
    }
}

// Test tags — the screens' tests and the test driver find the block, its buttons and the notice by these.
const val UPDATE_BLOCK_TAG = "update-block"
const val UPDATE_BUTTON_TAG_PREFIX = "update-button:"
const val UPDATE_TOGGLE_TAG = "update-toggle"
const val UPDATE_DOT_TAG = "update-dot"
const val UPDATE_NOTICE_TAG = "update-notice"
const val UPDATE_NOTICE_RESTART_TAG = "update-notice-restart"
const val UPDATE_NOTICE_WAIT_TAG = "update-notice-wait"
const val UPDATE_NOTICE_HIDE_TAG = "update-notice-hide"

// ───────────────────────────────── Settings ▸ About ─────────────────────────────────

/** The server this app is signed in to, as a host (`media.example.net`): no scheme, no trailing slash. */
fun aboutServerHost(): String =
    dev.jellystructure.ravilo.ui.raviloBaseUrl().removePrefix("https://").removePrefix("http://").trimEnd('/')

/** `.mu-app` — the app's mark, its name and its version. */
@Composable
fun AboutHeader(desk: Boolean, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val size = if (desk) 48.dp else 56.dp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(if (desk) 12.dp else 14.dp)).background(colors.accentGradient))
        Column {
            Text("Ravilo", color = colors.text, fontSize = if (desk) 15.sp else 18.sp, fontWeight = FontWeight.Bold, fontFamily = if (desk) SystemUiFont else SpaceGrotesk)
            Text(
                str("mac.about_version", mapOf("version" to dev.jellystructure.shared.raviloVersion())),
                color = colors.textSecondary, fontSize = if (desk) 12.sp else 13.sp, fontFamily = if (desk) SystemUiFont else Sora,
            )
        }
    }
}

/** `.mu-grp` with one row — *Server* and the host it is signed in to, in the system's monospace. */
@Composable
fun AboutServerGroup(desk: Boolean, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(if (desk) 10.dp else 16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(colors.card, shape).border(1.dp, colors.fg.copy(alpha = 0.08f), shape)
            .heightIn(min = if (desk) 40.dp else 52.dp).padding(horizontal = if (desk) 14.dp else 16.dp, vertical = 8.dp).testTag(ABOUT_SERVER_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(str("mac.update.row_server"), color = colors.text, fontSize = if (desk) 13.sp else 15.sp, fontFamily = if (desk) SystemUiFont else Sora)
        Text(
            aboutServerHost(), color = colors.textSecondary, fontSize = if (desk) 12.sp else 13.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * FR-R386-6 — Settings ▸ About, top to bottom: the app and its version, the *Software update* block (absent on Linux and
 * on a development build: [SoftwareUpdate.model] is null there), and the server.
 */
@Composable
fun AboutSection(desk: Boolean, modifier: Modifier = Modifier) {
    val model by SoftwareUpdate.model.collectAsState()
    Column(modifier.fillMaxWidth().testTag(ABOUT_TAG), verticalArrangement = Arrangement.spacedBy(if (desk) 18.dp else 18.dp)) {
        AboutHeader(desk)
        if (model != null) SoftwareUpdateBlock(desk)
        AboutServerGroup(desk)
    }
}

const val ABOUT_TAG = "about"
const val ABOUT_SERVER_TAG = "about-server"
