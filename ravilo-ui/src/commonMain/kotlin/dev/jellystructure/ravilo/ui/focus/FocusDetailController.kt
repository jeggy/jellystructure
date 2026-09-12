package dev.jellystructure.ravilo.ui.focus

import dev.jellystructure.shared.tv.FocusDetailFacts
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase R240 — what a highlighted Home-row title says before it's opened. Mirrors
 * design/ravilo/ravilo-focus.js's dwell/eligibility state machine as closely as Compose allows:
 * the client renders, never computes (FR-R240-1), and owns no precedence rule (FR-R240-4) —
 * [FocusDetailUi.mode] is read straight off the server-resolved `HomeFeed.focusDetail`.
 *
 * Callers wire every Home content-row [dev.jellystructure.ravilo.ui.components.Tile]'s `onFocused`
 * to [onFocus] and every OTHER focusable surface on Home (hero, channel rail, app bar, On Now, the
 * "→ See all" tile) to [clear] — this phase is Home content rows only (non-goal list), so anything
 * else taking focus must say nothing (FR-R240-1's "absent ⇒ nothing renders" run in reverse).
 */
data class FocusDetailUi(
    val rowId: String,
    val itemKey: String,
    /** "line" | "rowOpen" — this controller never emits a UI state for "none"; see [onFocus]. */
    val mode: String,
    val card: MediaCard,
    val facts: FocusDetailFacts,
)

class FocusDetailController(private val scope: CoroutineScope) {
    private val _current = MutableStateFlow<FocusDetailUi?>(null)
    val current: StateFlow<FocusDetailUi?> = _current.asStateFlow()

    private data class FocusedTarget(val rowId: String, val itemKey: String, val card: MediaCard)

    private var dwellJob: Job? = null
    private var focusToken = 0
    private var lastFocused: FocusedTarget? = null

    /** Called from a Home content-row tile's onFocused. [mode]/[delayMs] come from the feed's own
     *  resolved `HomeFeed.focusDetail`/`focusDetailDelayMs` — never re-derived here (FR-R240-4). */
    fun onFocus(rowId: String, itemKey: String, card: MediaCard, mode: String, delayMs: Int) {
        val token = ++focusToken
        dwellJob?.cancel()
        lastFocused = FocusedTarget(rowId, itemKey, card)

        val facts = card.focusDetail
        // FR-R240-1 — absent fact set (not a Home content-row item under a household that has both
        // directions off) renders nothing, not an empty slot.
        if (mode == "none" || facts == null) {
            _current.value = null
            return
        }
        // FR-R240-6 — while the dwell runs, nothing is stated: not the new title, and never the
        // previous one either. Sweeping a row must stay a plain row.
        _current.value = null
        val reveal = { _current.value = FocusDetailUi(rowId, itemKey, mode, card, facts) }
        if (delayMs <= 0) {
            // A settled viewer at delay 0 waits no frame they did not ask for — not deferred by a
            // zero-length timer at all.
            reveal()
        } else {
            dwellJob = scope.launch {
                delay(delayMs.toLong())
                if (focusToken == token) reveal()
            }
        }
    }

    /** Focus left every Home content row — nothing eligible is focused, so nothing may be stated. */
    fun clear() {
        ++focusToken
        dwellJob?.cancel()
        lastFocused = null
        _current.value = null
    }

    /** FR-R240-13 — a config change (line/rowOpen/delay) re-runs the reveal rule for whichever tile
     *  is focused right now, through the exact path a real focus move takes — so J's reveal animates
     *  on a switch flip exactly as it does on navigation. No-op when nothing is currently focused. */
    fun reapply(mode: String, delayMs: Int) {
        val t = lastFocused ?: return
        onFocus(t.rowId, t.itemKey, t.card, mode, delayMs)
    }
}
