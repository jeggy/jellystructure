package dev.jellystructure.ui

import dev.jellystructure.api.ConfigApi
import dev.jellystructure.cron.IsoDate
import dev.jellystructure.todayIsoDate
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

// DEB-4402 (DEB-4387, FR-3) — the one schedule control's state and events. The markup is `SchedulePickerHtml.kt`'s (commonMain, tested); this
// holds what a page redraw must not lose — the form each setting is being edited in — outside the DOM, so `innerHTML = …` on a page never costs
// the admin their *Custom* mode or a half-typed number. A page hands over its string ([view]) and is handed back the new one ([onChange]); it
// asks nothing else of the control and derives nothing from it.
//
// The foot (sentence, zone, next five runs) is the server's, from `POST /api/config/validate-schedule`, asked 300 ms after the last change. A
// refusal shows no runs, never the last valid schedule's (the round's decision 2). The foot and the lines are updated in place, so a field being
// typed in keeps its caret.
internal object SchedPickers {
    private class Slot(
        var form: SchedForm,
        var emitted: String,
        var opts: SchedOptions,
        var onChange: (String) -> Unit,
        var onChecked: (Boolean) -> Unit,
    ) {
        var foot: SchedFoot = SchedFoot.Pending()
        var zone: String? = null
        var job: Job? = null
    }

    private val slots = HashMap<String, Slot>()
    private val scope = CoroutineScope(SupervisorJob())

    fun today(): IsoDate = parseIsoDate(todayIsoDate()) ?: IsoDate(2026, 1, 1)

    /** The control for one setting. [stored] is the page's draft string: a string the control did not write (first draw, a save, another row) is read again. */
    fun view(key: String, stored: String, opts: SchedOptions, onChange: (String) -> Unit, onChecked: (Boolean) -> Unit = {}): SchedView {
        var slot = slots[key]
        if (slot == null || slot.emitted != stored) {
            slot?.job?.cancel()
            slot = Slot(SchedForm.of(stored, opts), stored, opts, onChange, onChecked)
            slots[key] = slot
            ask(key, slot, immediately = true)
        }
        slot.opts = opts; slot.onChange = onChange; slot.onChecked = onChecked
        return SchedView(slot.form, slot.foot, slot.zone, today())
    }

    /** The control, as a `.field`. */
    fun field(key: String, stored: String, opts: SchedOptions, onChange: (String) -> Unit, onChecked: (Boolean) -> Unit = {}): String =
        schedulePickerField(key, view(key, stored, opts, onChange, onChecked), opts)

    /** False while the server refuses what the control holds (Save is held for it). A setting that was never drawn is valid. */
    fun valid(key: String): Boolean = slots[key]?.foot !is SchedFoot.Refused

    fun allValid(prefix: String): Boolean = slots.none { (k, v) -> k.startsWith(prefix) && v.foot is SchedFoot.Refused }

    /** Forgets every control under [prefix]: its page has loaded the saved settings again. */
    fun forget(prefix: String) {
        slots.keys.filter { it.startsWith(prefix) }.forEach { slots.remove(it)?.job?.cancel() }
    }

    /** The server's refusal at save time, the backstop behind the live check. */
    fun refuse(key: String, message: String) {
        val slot = slots[key] ?: return
        slot.job?.cancel()
        slot.foot = SchedFoot.Refused(message)
        paint(key, slot)
        slot.onChecked(false)
    }

    // ── events ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** True when [t] is inside a schedule control, which has then dealt with it. */
    fun onEvent(t: Element, type: String): Boolean {
        val root = t.closest("[data-sch-key]") ?: return false
        val key = root.getAttribute("data-sch-key") ?: return true
        val slot = slots[key] ?: return true
        val (field, value) = fieldOf(t, type) ?: return true
        change(key, root, slot, field, value)
        return true
    }

    /** Enter or Space on a weekday or the month shape presses it, as on any button. */
    fun onKey(t: Element, key: String): Boolean {
        if (key != "Enter" && key != " ") return false
        if (t.closest("[data-sch-key]") == null) return false
        val press = t.closest("[data-sch-day],[data-sch-mshape]") as? HTMLElement ?: return false
        press.click()
        return true
    }

    private fun fieldOf(t: Element, type: String): Pair<String, String>? {
        t.closest("[data-sch-day]")?.let { return if (type == "click") "day" to (it.getAttribute("data-sch-day") ?: return null) else null }
        t.closest("[data-sch-mshape]")?.let { return if (type == "click") "mshape" to (it.getAttribute("data-sch-mshape") ?: return null) else null }
        val field = t.getAttribute("data-sch") ?: return null
        // a text or number box is read as it is typed; a select, a time and a date when they are chosen
        val typed = field in setOf("n", "minute", "cron")
        if (type == "input" && !typed && t !is HTMLInputElement) return null
        if (type == "click") return null
        val value = (t as? HTMLInputElement)?.value ?: (t as? HTMLSelectElement)?.value ?: return null
        return field to value
    }

    private fun change(key: String, root: Element, slot: Slot, field: String, value: String) {
        val old = slot.form
        val new = old.with(field, value)
        if (new == old) return
        slot.form = new
        val text = new.schedule(today())
        slot.emitted = text
        slot.onChange(text)

        if (schedStructure(new) != root.getAttribute("data-sch-structure") || field == "unit") redrawLines(root, slot, field)
        else patch(root, slot, field)

        val stale = (slot.foot as? SchedFoot.Ok) ?: (slot.foot as? SchedFoot.Pending)?.stale
        slot.foot = SchedFoot.Pending(stale)
        paint(key, slot)
        ask(key, slot, immediately = false)
    }

    private fun redrawLines(root: Element, slot: Slot, field: String) {
        root.setAttribute("data-sch-structure", schedStructure(slot.form))
        (root.querySelector("[data-sch-lines]") as? HTMLElement)?.innerHTML = schedLinesHtml(SchedView(slot.form, slot.foot, slot.zone, today()), slot.opts)
        // the field the admin was using is a new element now
        (root.querySelector("[data-sch='$field']") as? HTMLElement)?.focus()
    }

    /** What a change to a field that is not structural touches, in place: the unit's word, the *starting* line, its default, the weekday marks. */
    private fun patch(root: Element, slot: Slot, field: String) {
        val f = slot.form
        if (field == "day") {
            val days = root.querySelectorAll("[data-sch-day]")
            for (i in 0 until days.length) {
                val el = days.item(i) as? HTMLElement ?: continue
                val on = (el.getAttribute("data-sch-day")?.toIntOrNull() ?: -1) in f.days
                if (on) el.classList.add("on") else el.classList.remove("on")
                el.setAttribute("aria-pressed", on.toString())
            }
        }
        // the unit menu follows N: *1 week*, *2 weeks*
        (root.querySelector("[data-sch='unit']") as? HTMLSelectElement)?.let { sel ->
            for (i in 0 until sel.options.length) {
                val opt = sel.options.item(i) ?: continue
                SchedMode.of(opt.getAttribute("value").orEmpty())?.let { opt.textContent = schedUnitWord(it, f.n) }
            }
        }
        val line = root.querySelector("[data-sch-from-line]")
        if (f.showsFrom && line == null) (root.querySelector("[data-sch-lines]") as? HTMLElement)?.insertAdjacentHTML("beforeend", schedFromLineHtml(f, today()))
        else if (!f.showsFrom && line != null) line.parentElement?.removeChild(line)
        else if (f.showsFrom && f.from == null && field != "from") (root.querySelector("[data-sch='from']") as? HTMLInputElement)?.value = f.defaultFrom(today()).toString()
    }

    // ── the server's answer ─────────────────────────────────────────────────────────────────────────────────────────

    private fun ask(key: String, slot: Slot, immediately: Boolean) {
        slot.job?.cancel()
        val text = slot.form.schedule(today())
        slot.job = scope.launch {
            if (!immediately) delay(300)
            // a Custom box left empty in a setting that cannot be off is refused here; the server would call it *off*
            val r = if (text.isBlank() && !slot.opts.canOff) null else ConfigApi.validateSchedule(text, slot.opts.floorMinutes)
            if (slots[key] !== slot || slot.emitted != text) return@launch
            slot.foot = when {
                text.isBlank() && !slot.opts.canOff -> SchedFoot.Refused("This needs a schedule.")
                r == null -> SchedFoot.Unreachable
                r.valid -> SchedFoot.Ok(r.sentence ?: r.description.orEmpty(), r.nextRunLabels)
                else -> SchedFoot.Refused(r.error ?: "Invalid schedule")
            }
            r?.zone?.let { slot.zone = it }
            paint(key, slot)
            slot.onChecked(slot.foot !is SchedFoot.Refused)
        }
    }

    /** The foot, the zone beside the time, and the outline of a refused control, in place. */
    private fun paint(key: String, slot: Slot) {
        val root = document.querySelector("[data-sch-key='$key']") as? HTMLElement ?: return
        (root.querySelector("[data-sch-foot]") as? HTMLElement)?.innerHTML = schedFootHtml(slot.foot, slot.opts, slot.form.mode == SchedMode.OFF)
        val bad = slot.foot is SchedFoot.Refused
        if (bad) root.classList.add("bad") else root.classList.remove("bad")
        for (sel in listOf(".sch-n", ".sch-cron")) (root.querySelector(sel) as? HTMLElement)?.let { if (bad) it.classList.add("bad") else it.classList.remove("bad") }
        slot.zone?.let { z ->
            val zs = root.querySelectorAll("[data-sch-zone]")
            for (i in 0 until zs.length) (zs.item(i) as? HTMLElement)?.textContent = z
        }
    }
}
