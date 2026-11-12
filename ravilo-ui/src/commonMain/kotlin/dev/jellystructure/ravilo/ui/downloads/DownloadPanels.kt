package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.music.rememberNotificationAsk
import dev.jellystructure.shared.tv.DownloadOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * DEB-4070 (FR-3, FR-8) — the sheet state of one film or episode's Download control: the quality sheet, the menu a tap on a running or
 * finished download opens, and the delete confirm (FR-10, amended). The **control** sits in the page's action row; the **sheets** are
 * full-screen overlays and must be drawn at the page's root ([Overlays]), never inside the row.
 */
class TitleDownloadPanel internal constructor(
    private val controller: DownloadsController,
    private val scope: CoroutineScope,
    private val target: () -> DownloadTarget,
    private val onPlay: () -> Unit,
    private val askNotifications: () -> Unit,
) {
    private enum class Sheet { None, Quality, Menu, ConfirmDelete }
    private var sheet by mutableStateOf(Sheet.None)
    private var load by mutableStateOf<SheetLoad>(SheetLoad.Loading)
    private var phaseAtTap by mutableStateOf<DownloadPhase>(DownloadPhase.None)

    private fun phase(): DownloadPhase = controller.phases.value[target().itemId] ?: DownloadPhase.None

    /** A tap on the control: where it goes depends on the state it shows. */
    fun onControlClick() {
        val p = phase()
        phaseAtTap = p
        when (p.controlState()) {
            ControlState.DOWNLOAD -> openQuality()
            ControlState.RETRY -> controller.retry(target().itemId)
            ControlState.QUEUED, ControlState.PERCENT, ControlState.DOWNLOADED -> sheet = Sheet.Menu
        }
    }

    private fun openQuality() {
        sheet = Sheet.Quality
        load = SheetLoad.Loading
        val t = target()
        scope.launch {
            load = try {
                val options = controller.options(t.itemId, t.mediaSourceId)
                SheetLoad.Ready(buildQualitySheet(options, controller.freeBytes(), controller.lastQuality(t.kind)))
            } catch (e: DownloadNotAllowed) {
                sheet = Sheet.None   // FR-2: the control hides and download.not_allowed shows
                SheetLoad.Failed
            } catch (e: Exception) {
                SheetLoad.Failed
            }
        }
    }

    /** The control for the page's action row. [width] is [rememberDownloadControlWidth]. */
    @Composable
    fun Control(width: Dp, modifier: Modifier = Modifier) {
        val phases by controller.phases.collectAsState()
        DownloadControl(phases[target().itemId] ?: DownloadPhase.None, width, ::onControlClick, modifier)
    }

    /** The line under the row: why a download is waiting or failed. */
    @Composable
    fun StatusLine(modifier: Modifier = Modifier) {
        val phases by controller.phases.collectAsState()
        val waits by controller.waits.collectAsState()
        DownloadStatusLine(phases[target().itemId] ?: DownloadPhase.None, modifier, waits[target().itemId])
    }

    /** The sheets: draw once, at the page's root. */
    @Composable
    fun Overlays() {
        val t = target()
        val ready = (load as? SheetLoad.Ready)?.model
        QualitySheet(
            visible = sheet == Sheet.Quality, title = t.title, load = load,
            onConfirm = { q ->
                val option = ready?.rows?.firstOrNull { it.option.quality == q }?.option
                sheet = Sheet.None
                if (option != null) { askNotifications(); controller.download(listOf(t), option) }
            },
            onDismiss = { sheet = Sheet.None },
        )
        val p = phaseAtTap
        val offers by controller.originalOffers.collectAsState()
        val actions = when (p.controlState()) {
            ControlState.DOWNLOADED -> listOf(
                DownloadAction(str("action.play")) { onPlay() },
                DownloadAction(str("download.delete"), destructive = true) { sheet = Sheet.ConfirmDelete },
            )
            else -> listOfNotNull(
                // FR-7 — a copy that waits for the server offers its original, first, above Pause and Cancel.
                offers[t.itemId]?.takeIf { p == DownloadPhase.WaitingServer }?.let { original ->
                    DownloadAction(str("download.get_original", mapOf("size" to formatBytes(original.bytes)))) { controller.getOriginal(t.itemId) }
                },
                if (p is DownloadPhase.Paused) DownloadAction(str("download.resume")) { controller.resume(t.itemId) }
                else DownloadAction(str("download.pause")) { controller.pause(t.itemId) },
                DownloadAction(str("action.cancel"), destructive = true) { controller.delete(t.itemId) },
            )
        }
        DownloadActionSheet(visible = sheet == Sheet.Menu, title = t.title, actions = actions, onDismiss = { if (sheet == Sheet.Menu) sheet = Sheet.None })
        // FR-10 (amended): a delete names the title and what it frees; Cancel puts everything back as it was.
        DownloadActionSheet(
            visible = sheet == Sheet.ConfirmDelete,
            title = str("download.delete_one_confirm", mapOf("title" to t.title, "size" to formatBytes(controller.recordBytes(t.itemId)))),
            actions = listOf(
                DownloadAction(str("download.delete"), destructive = true) { controller.delete(t.itemId) },
                DownloadAction(str("action.cancel")) { },
            ),
            onDismiss = { if (sheet == Sheet.ConfirmDelete) sheet = Sheet.None },
        )
    }
}

@Composable
fun rememberTitleDownloadPanel(controller: DownloadsController, target: DownloadTarget, onPlay: () -> Unit): TitleDownloadPanel {
    val scope = rememberCoroutineScope()
    val ask = rememberNotificationAsk()
    val targetState = androidx.compose.runtime.rememberUpdatedState(target)
    val playState = androidx.compose.runtime.rememberUpdatedState(onPlay)
    return remember(controller) { TitleDownloadPanel(controller, scope, { targetState.value }, { playState.value() }, ask) }
}

/**
 * FR-5 — **Download season**: the same sheet with the season's total per quality. The episodes are asked of the server one by one (each
 * has its own sizes); the button then queues each one not already on the phone, in episode order, at the quality picked.
 */
class SeasonDownloadPanel internal constructor(
    private val controller: DownloadsController,
    private val scope: CoroutineScope,
    private val episodes: () -> List<DownloadTarget>,
    private val askNotifications: () -> Unit,
) {
    private var open by mutableStateOf(false)
    private var load by mutableStateOf<SheetLoad>(SheetLoad.Loading)
    private var perEpisode: Map<String, List<DownloadOption>> = emptyMap()
    private var titleText by mutableStateOf("")

    private fun onPhone(id: String): Boolean = controller.phases.value[id].let { it != null && it != DownloadPhase.None && it !is DownloadPhase.Failed }

    fun open(title: String) {
        titleText = title
        open = true
        load = SheetLoad.Loading
        val eps = episodes()
        scope.launch {
            load = try {
                val fetched = coroutineScope {
                    eps.map { e -> async { e.itemId to if (onPhone(e.itemId)) emptyList() else controller.options(e.itemId, e.mediaSourceId) } }.awaitAll()
                }.toMap()
                perEpisode = fetched
                // An episode already on the phone is not asked: it counts in the row totals at a sibling's size (the season's shape), and the
                // button leaves it out. With every episode there already, no row is offered.
                val template = fetched.values.firstOrNull { it.isNotEmpty() }.orEmpty()
                val rows = eps.map { e -> SeasonEpisode(e.itemId, onPhone(e.itemId), fetched[e.itemId].orEmpty().ifEmpty { template }) }
                SheetLoad.Ready(buildSeasonSheet(rows, controller.freeBytes(), controller.lastQuality("episode")))
            } catch (e: DownloadNotAllowed) {
                open = false
                SheetLoad.Failed
            } catch (e: Exception) {
                SheetLoad.Failed
            }
        }
    }

    @Composable
    fun Overlays() {
        QualitySheet(
            visible = open, title = titleText, load = load,
            onConfirm = { q ->
                open = false
                val items = episodes().filter { !onPhone(it.itemId) }.mapNotNull { e ->
                    perEpisode[e.itemId]?.firstOrNull { it.quality == q }?.let { e to it }
                }
                if (items.isNotEmpty()) { askNotifications(); controller.downloadEach(items) }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun rememberSeasonDownloadPanel(controller: DownloadsController, episodes: List<DownloadTarget>): SeasonDownloadPanel {
    val scope = rememberCoroutineScope()
    val ask = rememberNotificationAsk()
    val eps = androidx.compose.runtime.rememberUpdatedState(episodes)
    return remember(controller) { SeasonDownloadPanel(controller, scope, { eps.value }, ask) }
}
