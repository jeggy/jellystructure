package dev.jellystructure.ravilo.ui.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.CheckGlyph
import dev.jellystructure.ravilo.ui.deviceDisplayName
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.SupportWire
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** Test tags for the support screens (FR-3 to FR-8): what the Playwright spec and the common UI tests read. */
object SupportTags {
    const val LIST = "support-list"
    const val MESSAGES = "support-messages"
    const val ROW = "support-row"
    const val EMPTY = "support-empty"
    const val SKELETON = "support-skeleton"
    const val WRITE = "support-write"
    const val THREAD = "support-thread"
    const val HEADER = "support-header"
    const val BUBBLE = "support-bubble"
    const val SYSTEM = "support-system"
    const val SHOW_REST = "support-show-rest"
    const val AI_WRITING = "support-ai-writing"
    const val COMPOSER = "support-composer"
    const val SEND = "support-send"
    const val SENDING = "support-sending"
    const val NOTICE = "support-notice"
    const val TRY_AGAIN = "support-try-again"
    const val OFFLINE = "support-offline"
    const val FIRST_HINT = "support-first-hint"
    const val SENT_FROM = "support-sent-from"
}

private val AMBER = Color(0xFFF2B544)

// ── the frame every support screen sits in ───────────────────────────────────────────────────────────────────────────

/**
 * The page: the app's background, the content in a centred column (a phone's width as it is; a computer's or the web's
 * window gets the same screens at [CONTENT_MAX], round 3 having drawn the phone only), and on a computer the
 * platform toolbar, whose Back is the page's Back. Elsewhere the page draws its own ‹.
 */
@Composable
private fun SupportFrame(content: @Composable BoxScope.(desk: Boolean) -> Unit) {
    val desk = isDesktopLayout
    Box(Modifier.fillMaxSize().background(RaviloTheme.colors.background)) {
        Box(Modifier.fillMaxSize().padding(top = if (desk) 58.dp else 0.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = CONTENT_MAX).fillMaxSize()) { content(desk) }
        }
        if (desk) AppBar()
    }
}

private val CONTENT_MAX = 720.dp

@Composable
private fun BackChevron(onBack: () -> Unit) {
    Text(
        "‹", color = RaviloTheme.colors.text, fontSize = 26.sp,
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** FR-8 — the strip under the header while a read has failed; the content stays. */
@Composable
private fun OfflineStrip() {
    val colors = RaviloTheme.colors
    Text(
        str("support.offline_bar"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = 7.dp, horizontal = 16.dp).testTag(SupportTags.OFFLINE),
    )
}

// ── FR-13 — the marks ────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * FR-13 — round 3's vocabulary: `AI Agent` a round mark with a dot, Hervør a square one with her initial, an admin and
 * support a round one with an initial, a resolved ticket a check. [initial] is for the two with a letter.
 */
@Composable
internal fun SupportMark(mark: SupportModel.Mark, initial: String, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val letter = initial.take(1).uppercase()
    when (mark) {
        SupportModel.Mark.AI_AGENT -> Box(modifier.size(size).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size / 4).background(colors.onAccent, CircleShape))
        }
        SupportModel.Mark.HERVOR -> Box(modifier.size(size).clip(RoundedCornerShape(size / 4)).background(colors.accentDim), contentAlignment = Alignment.Center) {
            Text(letter.ifEmpty { "H" }, color = colors.onAccent, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
        SupportModel.Mark.SUPPORT -> Box(modifier.size(size).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(letter, color = colors.accentSecondary, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
        SupportModel.Mark.CHECK -> Box(modifier.size(size).border(1.5.dp, colors.accentSecondary, CircleShape), contentAlignment = Alignment.Center) {
            CheckGlyph(colors.accentSecondary, size * 0.5f)
        }
        SupportModel.Mark.EMPTY -> Box(modifier.size(size).border(1.5.dp, colors.textDim, CircleShape))
    }
}

@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    Box(modifier.size(9.dp).background(RaviloTheme.colors.accentSecondary, CircleShape))
}

@Composable
private fun ClassChip(label: String) {
    val colors = RaviloTheme.colors
    Text(
        label, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
        modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun PrimaryButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.accentGradient)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = colors.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
}

@Composable
private fun QuietButton(label: String, tag: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
}

// ── FR-3 — My tickets ────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SupportListScreen(
    store: SupportListStore,
    onBack: () -> Unit,
    onOpen: (SupportTicketRow) -> Unit,
    onWrite: () -> Unit,
    /** The lane answered 404: it is closed (FR-1). The caller pops back to Profile. */
    onGone: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val lang = LocalLang.current
    // FR-3/FR-7 — read on open, again on every support_changed and after a reconnect; nothing is patched in place.
    LaunchedEffect(store) {
        if (store.refresh() == ReadEnd.GONE) onGone()
        SupportLane.changes.collect { if (store.refresh() == ReadEnd.GONE) onGone() }
    }
    SupportFrame { desk ->
        Column(Modifier.fillMaxSize().testTag(SupportTags.LIST)) {
            if (!desk) Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BackChevron(onBack)
                Text(str("support.my_tickets"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            } else Text(
                str("support.my_tickets"), color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
            if (store.offline) OfflineStrip()
            val rows = store.rows
            when {
                rows == null -> Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp).testTag(SupportTags.SKELETON)) { repeat(3) { SkeletonRow() } }
                rows.isEmpty() -> EmptyState(onWrite)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp)) {
                    rows.forEach { row -> TicketRow(row, lang) { onOpen(row) }; Spacer(Modifier.height(8.dp)) }
                    Spacer(Modifier.height(8.dp))
                    QuietButton(str("support.write"), SupportTags.WRITE, onWrite)
                }
            }
        }
    }
}

@Composable
private fun TicketRow(row: SupportTicketRow, lang: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val presence = SupportModel.presence(row, answered = null, aiComposing = false, lang = lang)
    val cls = SupportModel.classLabel(row.ticketClass, lang)
    Row(
        Modifier.fillMaxWidth().testTag(SupportTags.ROW)
            .background(colors.surface, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SupportMark(presence.mark, SupportModel.holderName(row.holder, lang), size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${row.number}", color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                if (cls != null) ClassChip(cls)
                Spacer(Modifier.weight(1f))
                if (row.lastActivity > 0) Text(
                    SupportModel.time(row.lastActivity, Clock.System.now(), TimeZone.currentSystemDefault(), lang),
                    color = colors.textDim, fontSize = 12.sp, fontFamily = Sora,
                )
            }
            Text(SupportModel.title(row, lang), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(presence.text, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.unread > 0) UnreadDot()
    }
}

@Composable
private fun SkeletonRow() {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(colors.surface, RoundedCornerShape(14.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(colors.surfaceVariant, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(90.dp).height(10.dp).background(colors.surfaceVariant, RoundedCornerShape(5.dp)))
            Box(Modifier.width(210.dp).height(12.dp).background(colors.surfaceVariant, RoundedCornerShape(6.dp)))
            Box(Modifier.width(150.dp).height(10.dp).background(colors.surfaceVariant, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun EmptyState(onWrite: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).testTag(SupportTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(30.dp)) {
                val r = size.minDimension * 0.07f
                listOf(0.28f, 0.5f, 0.72f).forEach { x -> drawCircle(colors.textSecondary, r, Offset(size.width * x, size.height * 0.45f)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(str("support.empty_title"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        Spacer(Modifier.height(6.dp))
        Text(str("support.empty_body"), color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PrimaryButton(str("support.write"), SupportTags.WRITE, onClick = onWrite)
    }
}

// ── FR-4, FR-5, FR-6 — the thread ────────────────────────────────────────────────────────────────────────────────────

/**
 * The thread, or — with [SupportThreadStore.number] null — the first message of a ticket not yet filed (FR-6). The
 * composer owns the bottom edge; the caller hides the bottom bar on this screen.
 */
@Composable
fun SupportThreadScreen(
    store: SupportThreadStore,
    viewerName: String,
    /** FR-6 — the phone's first-message screen names the device; the web and the computers show no such line. */
    isPhone: Boolean,
    onBack: () -> Unit,
    /** FR-6 — the 201 filed the ticket: the screen is replaced by that ticket's thread, receipt included. */
    onFiled: (SupportThread) -> Unit,
    /** FR-5 — a 404: back to My tickets (and FR-1 if the lane closed). */
    onGone: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    val thread = store.thread
    val number = store.number
    // FR-4/FR-7 — read on open, then on this ticket's support_changed (or a reconnect).
    if (number != null) LaunchedEffect(store) {
        if (store.refresh() == ReadEnd.GONE) onGone()
        SupportLane.changes.filter { it == number || it == SupportLane.ALL }.collect { if (store.refresh() == ReadEnd.GONE) onGone() }
    }
    var draft by remember(store) { mutableStateOf("") }
    val resolved = thread?.ticket?.state == SupportWire.STATE_RESOLVED

    fun send() {
        val text = draft
        scope.launch {
            when (val end = store.send(text)) {
                is SendEnd.Sent -> { draft = ""; if (end.filed) onFiled(end.thread) }
                SendEnd.Gone -> onGone()
                SendEnd.Failed -> Unit
            }
        }
    }

    SupportFrame { desk ->
        Column(Modifier.fillMaxSize().testTag(SupportTags.THREAD)) {
            ThreadHeader(thread, desk, onBack, lang)
            if (store.offline) OfflineStrip()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (thread == null) FirstMessageBody(isPhone)
                else MessageList(thread, viewerName, lang)
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                phase = store.phase,
                placeholder = str(if (resolved) "support.placeholder_reopen" else "support.placeholder"),
                onSend = ::send,
            )
        }
    }
}

/** FR-4 — pinned: back, `#41` and the class chip, the title, the presence line, the holder's mark at the end. */
@Composable
private fun ThreadHeader(thread: SupportThread?, desk: Boolean, onBack: () -> Unit, lang: String) {
    val colors = RaviloTheme.colors
    val ticket = thread?.ticket
    val presence = if (ticket == null) SupportModel.Presence(str("support.pres_waiting"), SupportModel.Mark.EMPTY)
    else SupportModel.presence(ticket, SupportModel.answered(thread.messages), thread.aiComposing, lang)
    val cls = ticket?.let { SupportModel.classLabel(it.ticketClass, lang) }
    Row(
        Modifier.fillMaxWidth().background(colors.background).padding(horizontal = if (desk) 24.dp else 12.dp, vertical = 10.dp).testTag(SupportTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!desk) BackChevron(onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (ticket != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${ticket.number}", color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                if (cls != null) ClassChip(cls)
            }
            Text(
                if (ticket == null) str("support.new_ticket") else SupportModel.title(ticket, lang),
                color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(presence.text, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SupportMark(presence.mark, ticket?.let { SupportModel.holderName(it.holder, lang) }.orEmpty(), size = 34.dp)
    }
}

/** FR-6 — nothing is asked before writing, and there is no class picker. */
@Composable
private fun FirstMessageBody(isPhone: Boolean) {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(str("support.write"), color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        Text(str("support.first_hint"), color = colors.textSecondary, fontSize = 15.sp, lineHeight = 22.sp, fontFamily = Sora, modifier = Modifier.testTag(SupportTags.FIRST_HINT))
        // The server records the device, and the line says so. The round's string names the phone, so only a phone shows it.
        if (isPhone) Text(
            str("support.sent_from_phone", mapOf("name" to deviceDisplayName())),
            color = colors.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(SupportTags.SENT_FROM),
        )
    }
}

/** More than any bubble is tall: `scrollToItem` clamps it to the list's real end. */
private const val PAST_THE_END = 1_000_000

@Composable
private fun MessageList(thread: SupportThread, viewerName: String, lang: String) {
    val listState = rememberLazyListState()
    val entries = remember(thread, lang) { thread.messages.map { it to SupportModel.systemLine(it, thread.ticket, lang) }.filter { (m, sys) -> sys != null || m.kind == SupportWire.KIND_MESSAGE } }
    // FR-4 — opens at its end, and a re-read keeps it there when it was already there. Only the viewer's own scroll
    // decides whether the thread follows its end: a layout that lands short (a tall bubble measured late, a new
    // entry) is not the viewer leaving, so it must not flip this off.
    var following by remember { mutableStateOf(true) }
    val userScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) following = !listState.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                following = !listState.canScrollForward
                return Velocity.Zero
            }
        }
    }
    // Whenever the list sits short of its end while following (first layout, a new entry, `aiComposing`, a bubble
    // whose height changed after it was measured), scroll past the last item's top to the real end.
    LaunchedEffect(listState) {
        snapshotFlow { Triple(following, listState.canScrollForward, listState.layoutInfo.totalItemsCount) }
            .filter { (follow, canScroll, total) -> follow && canScroll && total > 0 }
            .collect { (_, _, total) -> listState.scrollToItem(total - 1, PAST_THE_END) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(userScroll).testTag(SupportTags.MESSAGES),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries.size, key = { entries[it].first.id }) { i ->
            val (m, sys) = entries[i]
            if (sys != null) SystemLineRow(sys) else Bubble(m, viewerName, lang)
        }
        if (thread.aiComposing) item(key = "ai-writing") { AiWritingBubble() }
    }
}

@Composable
private fun SystemLineRow(line: SupportModel.SystemLine) {
    val colors = RaviloTheme.colors
    Text(
        line.text, color = if (line.accent) AMBER else colors.textDim, fontSize = 12.sp, fontFamily = Sora, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(SupportTags.SYSTEM),
    )
}

@Composable
private fun Bubble(m: SupportMessageView, viewerName: String, lang: String) {
    val colors = RaviloTheme.colors
    val mine = m.author == SupportWire.AUTHOR_VIEWER
    val time = SupportModel.time(m.createdAt, Clock.System.now(), TimeZone.currentSystemDefault(), lang)
    val author = SupportModel.author(m)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mine) {
                Text(SupportModel.youLabel(viewerName, lang), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            } else {
                SupportMark(author.mark, author.name, size = 20.dp)
                Text(author.name, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                when (author.badge) {
                    SupportModel.Badge.AI -> Badge(str("support.badge_ai"))
                    SupportModel.Badge.AI_SUPPORT -> { Badge(str("support.badge_ai")); Badge(str("support.badge_support")) }
                    SupportModel.Badge.ADMIN -> Badge(str("profile.admin"))
                    SupportModel.Badge.NONE -> Unit
                }
            }
            Text(time, color = colors.textDim, fontSize = 11.sp, fontFamily = Sora)
        }
        BubbleBody(m.body.orEmpty(), mine)
    }
}

@Composable
private fun Badge(label: String) {
    val colors = RaviloTheme.colors
    Text(
        label, color = colors.accentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
        modifier = Modifier.border(1.dp, colors.accentSecondary.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** FR-4 — plain text; past [SupportModel.CLAMP_LINES] lines it is clamped, with *Show the rest* inside the bubble. */
@Composable
private fun BubbleBody(body: String, mine: Boolean) {
    val colors = RaviloTheme.colors
    var expanded by remember(body) { mutableStateOf(false) }
    var overflows by remember(body) { mutableStateOf(false) }
    val ink = if (mine) colors.onAccent else colors.text
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth(0.86f).testTag(SupportTags.BUBBLE)
            .let { if (mine) it.background(colors.accentGradient, RoundedCornerShape(16.dp)) else it.background(colors.surface, RoundedCornerShape(16.dp)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            body, color = ink, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = Sora,
            maxLines = if (expanded) Int.MAX_VALUE else SupportModel.CLAMP_LINES, overflow = TextOverflow.Clip,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows && !expanded) Text(
            str("support.show_rest"), color = if (mine) colors.onAccent else colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
            modifier = Modifier.padding(top = 6.dp).testTag(SupportTags.SHOW_REST)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = true },
        )
    }
}

/** FR-4 — while `aiComposing`: an `AI Agent` bubble with *AI Agent is writing* and three dots. */
@Composable
private fun AiWritingBubble() {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxWidth().testTag(SupportTags.AI_WRITING), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SupportMark(SupportModel.Mark.AI_AGENT, "", size = 20.dp)
            Text(SupportModel.AI_AGENT, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            Badge(str("support.badge_ai"))
        }
        Row(
            Modifier.background(colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(str("support.ai_writing"), color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(5.dp).background(colors.textDim, CircleShape)) } }
        }
    }
}

// ── FR-5 — the composer ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * FR-5 — owns the screen's bottom edge. It locks while a send is out and shows *Sending…* with a thin progress line;
 * a failure is one line above the field with the draft kept, never a bubble marked "failed". The keyboard is the
 * system's own.
 */
@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, phase: SendPhase, placeholder: String, onSend: () -> Unit) {
    val colors = RaviloTheme.colors
    val sending = phase == SendPhase.SENDING
    Column(Modifier.fillMaxWidth().background(colors.surface).testTag(SupportTags.COMPOSER)) {
        when (phase) {
            SendPhase.SENDING -> {
                Text(str("support.sending"), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(SupportTags.SENDING))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.surfaceVariant)
            }
            SendPhase.NOT_SENT_OFFLINE -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str("support.not_sent_offline"), color = AMBER, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.weight(1f).testTag(SupportTags.NOTICE))
                Text(str("support.try_again"), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                    modifier = Modifier.testTag(SupportTags.TRY_AGAIN).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSend))
            }
            SendPhase.LIMIT -> Notice(str("support.limit"))
            SendPhase.TOO_LONG -> Notice(str("support.too_long"))
            SendPhase.IDLE -> Unit
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).background(colors.surfaceVariant, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = draft, onValueChange = onDraft, enabled = !sending, readOnly = sending,
                    modifier = Modifier.fillMaxWidth().testTag(SupportTags.COMPOSER + "-field"),
                    textStyle = TextStyle(color = colors.text, fontSize = 15.sp, fontFamily = Sora),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(placeholder, color = colors.textDim, fontSize = 15.sp, fontFamily = Sora)
                        inner()
                    },
                )
            }
            val canSend = draft.isNotBlank() && !sending
            Box(
                Modifier.size(44.dp).testTag(SupportTags.SEND)
                    .clip(CircleShape)
                    .background(if (canSend) colors.accent else colors.surfaceVariant)
                    .clickable(enabled = canSend, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val tint = if (canSend) colors.onAccent else colors.textDim
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.88f); lineTo(size.width * 0.5f, size.height * 0.14f)
                        moveTo(size.width * 0.2f, size.height * 0.44f); lineTo(size.width * 0.5f, size.height * 0.14f); lineTo(size.width * 0.8f, size.height * 0.44f)
                    }
                    drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, color = AMBER, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(SupportTags.NOTICE))
}
