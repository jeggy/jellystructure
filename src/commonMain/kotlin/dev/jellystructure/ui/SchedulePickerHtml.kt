package dev.jellystructure.ui

import dev.jellystructure.cron.ByDay
import dev.jellystructure.cron.CronSchedule
import dev.jellystructure.cron.Freq
import dev.jellystructure.cron.IsoDate
import dev.jellystructure.cron.Recurrence
import dev.jellystructure.cron.RecurrenceSchedule
import dev.jellystructure.cron.ScheduleNames
import dev.jellystructure.cron.ScheduleParse
import dev.jellystructure.cron.daysInMonth
import dev.jellystructure.cron.parseSchedule

/*
 * DEB-4402 (DEB-4387, FR-3) — the one schedule control, in `commonMain` for the reason `AutofetchHtml.kt` gives: the admin frontend has no
 * test target of its own and the markup is the requirement. Nothing here may touch the DOM.
 *
 * It is the round's *Lines* shape (`design/incoming/2026-11-12-schedule-picker/`): one decision per line, the lines wrap, so one markup is three
 * lines in a Processing row and six in a 280 px panel. Under the lines the foot — the server's sentence and its next five runs — is always there.
 *
 * The control holds a **form** (what the admin chose) and writes the stored string from it with the shared schedule type (`cron/Schedule.kt`), so
 * a choice a cron can say is stored as that cron (FR-1.2). It derives no sentence and no time: the foot is whatever `POST /api/config/validate-schedule`
 * answered (FR-3.8). The one date it works out is the default for *starting* (FR-3.6), which is a date and never a time.
 */

enum class SchedMode(val key: String) {
    OFF("off"), HOURS("hours"), DAYS("days"), WEEKS("weeks"), MONTHS("months"), YEARS("years"), CRON("cron");

    companion object {
        fun of(key: String): SchedMode? = entries.firstOrNull { it.key == key }
    }
}

/** What one setting asks of the control. [canOff] puts *Off* at the head of the unit menu (FR-3.10); [floorMinutes] is the setting's own floor (FR-2.2). */
data class SchedOptions(val canOff: Boolean = false, val floorMinutes: Int = 15, val label: String = "Schedule")

/**
 * What the admin has chosen. [days] are cron weekdays (0 = Sunday); [ordinal] is 1–4 or -1 (the last); [monthDay] is 1–31 or -1 (the last day);
 * [from] is null until the admin picks a *starting* date, and then the default is the next date that matches (FR-3.6). [cron] is the Custom text,
 * and also holds a stored string the simple modes cannot show (FR-3.9).
 */
data class SchedForm(
    val mode: SchedMode = SchedMode.DAYS,
    val n: Int = 1,
    val days: Set<Int> = setOf(1),
    val hour: Int = 4,
    val minute: Int = 0,
    val monthByDay: Boolean = true,
    val monthDay: Int = 1,
    val ordinal: Int = 1,
    val ordDay: Int = 1,
    val month: Int = 1,
    val dayOfMonth: Int = 1,
    val from: IsoDate? = null,
    val cron: String = "",
) {
    /** The `starting` field is shown for an N above 1 on days, weeks, months or years (FR-3.6). */
    val showsFrom: Boolean get() = n > 1 && mode in FROM_MODES

    private fun rule(today: IsoDate): Recurrence? {
        val start = if (showsFrom) (from ?: defaultFrom(today)) else null
        return when (mode) {
            SchedMode.HOURS -> Recurrence(Freq.HOURLY, n, minute = minute)
            SchedMode.DAYS -> Recurrence(Freq.DAILY, n, hour = hour, minute = minute, from = start)
            SchedMode.WEEKS -> Recurrence(Freq.WEEKLY, n, byDay = ScheduleNames.WEEK_ORDER.filter { it in days }.map { ByDay(it) }, hour = hour, minute = minute, from = start)
            SchedMode.MONTHS -> if (monthByDay) Recurrence(Freq.MONTHLY, n, byMonthDay = monthDay, hour = hour, minute = minute, from = start)
            else Recurrence(Freq.MONTHLY, n, byDay = listOf(ByDay(ordDay, ordinal)), hour = hour, minute = minute, from = start)
            SchedMode.YEARS -> Recurrence(Freq.YEARLY, n, byMonth = month, byMonthDay = dayOfMonth, hour = hour, minute = minute, from = start)
            SchedMode.OFF, SchedMode.CRON -> null
        }
    }

    /** The string to store. A choice a cron can say is that cron; anything else the `every:` form (FR-1.2). Off is the empty string (FR-1.3). */
    fun schedule(today: IsoDate): String = when (mode) {
        SchedMode.OFF -> ""
        SchedMode.CRON -> cron.trim()
        else -> RecurrenceSchedule(rule(today)!!).canonical
    }

    /** The next date, from [today], that this choice fires on: the default for *starting*, so a schedule set up today lists its first run (FR-3.6). */
    fun defaultFrom(today: IsoDate): IsoDate {
        for (i in 0..(366 * 8)) {
            val d = addDays(today, i)
            val ok = when (mode) {
                SchedMode.WEEKS -> weekday(d) in days
                SchedMode.MONTHS -> if (monthByDay) d.day == (if (monthDay == -1) daysInMonth(d.year, d.month) else monthDay)
                else weekday(d) == ordDay && ordinalMatches(d, ordinal)
                SchedMode.YEARS -> d.month == month && d.day == dayOfMonth
                else -> true
            }
            if (ok) return d
        }
        return today
    }

    /** One field changed. [value] is what the control holds; an unreadable value leaves the form as it was. */
    fun with(field: String, value: String): SchedForm = when (field) {
        "unit" -> SchedMode.of(value)?.let { unit(it) } ?: this
        "n" -> value.trim().toIntOrNull()?.let { copy(n = it.coerceIn(1, 100)) } ?: this
        "day" -> value.toIntOrNull()?.takeIf { it in 0..6 }?.let { d -> if (d in days) (if (days.size > 1) copy(days = days - d) else this) else copy(days = days + d) } ?: this
        "at" -> value.split(':').takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.takeIf { it in 0..23 }?.let { hh -> m.toIntOrNull()?.takeIf { it in 0..59 }?.let { copy(hour = hh, minute = it) } } } ?: this
        "minute" -> value.trim().toIntOrNull()?.let { copy(minute = it.coerceIn(0, 59)) } ?: this
        "mshape" -> copy(monthByDay = value == "day")
        "mday" -> (if (value == "last") -1 else value.toIntOrNull()?.takeIf { it in 1..31 })?.let { copy(monthDay = it) } ?: this
        "ord" -> (if (value == "last") -1 else value.toIntOrNull()?.takeIf { it in 1..4 })?.let { copy(ordinal = it) } ?: this
        "ordday" -> value.toIntOrNull()?.takeIf { it in 0..6 }?.let { copy(ordDay = it) } ?: this
        "month" -> value.toIntOrNull()?.takeIf { it in 1..12 }?.let { copy(month = it) } ?: this
        "dom" -> value.toIntOrNull()?.takeIf { it in 1..31 }?.let { copy(dayOfMonth = it) } ?: this
        "from" -> parseIsoDate(value)?.let { copy(from = it) } ?: this
        "cron" -> copy(cron = value)
        else -> this
    }

    /** Choosing a unit keeps N, the time and anything the new unit shares; the new unit's own fields keep what they held. */
    private fun unit(m: SchedMode): SchedForm = when {
        m == mode -> this
        m == SchedMode.CRON -> copy(mode = m, cron = cron.ifBlank { "0 4 * * *" })
        else -> copy(mode = m)
    }

    companion object {
        private val FROM_MODES = setOf(SchedMode.DAYS, SchedMode.WEEKS, SchedMode.MONTHS, SchedMode.YEARS)

        /** What a setting with no schedule yet opens as: daily at 04:00. */
        val DEFAULT = SchedForm()

        private val DAILY_LEGACY = Regex("""^daily@(\d{1,2}):(\d{2})$""")
        private val WEEKLY_LEGACY = Regex("""^weekly@([0-6])@(\d{1,2}):(\d{2})$""")
        private val FIELD = Regex("""^\d{1,2}$""")

        /**
         * A stored string, shown by the simple mode that says it, else as *Custom* holding the text as it is (FR-3.9). The old auto-fetcher words
         * (`every6h`, `daily@04:00`, `weekly@3@04:00`) are read here too, for good (auto-fetch FR-2.3). Nothing is written back: the string is
         * rewritten only when the admin changes the control.
         */
        fun of(stored: String, options: SchedOptions = SchedOptions()): SchedForm {
            val s = stored.trim()
            if (s.isEmpty()) return if (options.canOff) DEFAULT.copy(mode = SchedMode.OFF) else DEFAULT
            if (s == "every6h") return SchedForm(SchedMode.HOURS, n = 6)
            DAILY_LEGACY.matchEntire(s)?.let { return SchedForm(SchedMode.DAYS, hour = it.groupValues[1].toInt(), minute = it.groupValues[2].toInt()) }
            WEEKLY_LEGACY.matchEntire(s)?.let { return SchedForm(SchedMode.WEEKS, days = setOf(it.groupValues[1].toInt()), hour = it.groupValues[2].toInt(), minute = it.groupValues[3].toInt()) }
            val custom = SchedForm(SchedMode.CRON, cron = s)
            return when (val p = parseSchedule(s)) {
                is ScheduleParse.Ok -> when (val sch = p.schedule) {
                    is CronSchedule -> fromCron(s) ?: custom
                    is RecurrenceSchedule -> fromRule(sch.rule) ?: custom
                }
                else -> custom
            }
        }

        private fun fromCron(text: String): SchedForm? {
            val f = text.split(Regex("\\s+"))
            if (f.size != 5) return null
            val (min, hr, dom, mon, dow) = f
            fun num(x: String, range: IntRange) = x.takeIf { FIELD.matches(it) }?.toInt()?.takeIf { it in range }
            val minute = num(min, 0..59) ?: return null
            if (dom == "*" && mon == "*" && dow == "*") {
                if (hr == "*") return SchedForm(SchedMode.HOURS, minute = minute)
                if (hr.startsWith("*/")) return num(hr.removePrefix("*/"), 1..24)?.let { SchedForm(SchedMode.HOURS, n = it, minute = minute) }
                return num(hr, 0..23)?.let { SchedForm(SchedMode.DAYS, hour = it, minute = minute) }
            }
            val hour = num(hr, 0..23) ?: return null
            if (dom == "*" && mon == "*") {
                val days = dow.split(',').map { num(it, 0..6) ?: return null }.toSet()
                return SchedForm(SchedMode.WEEKS, days = days, hour = hour, minute = minute)
            }
            if (dow != "*") return null
            val day = num(dom, 1..31) ?: return null
            if (mon == "*") return SchedForm(SchedMode.MONTHS, monthDay = day, hour = hour, minute = minute)
            return num(mon, 1..12)?.let { SchedForm(SchedMode.YEARS, month = it, dayOfMonth = day, hour = hour, minute = minute) }
        }

        private fun fromRule(r: Recurrence): SchedForm? = when (r.freq) {
            Freq.HOURLY -> SchedForm(SchedMode.HOURS, n = r.interval, minute = r.minute, from = r.from)
            Freq.DAILY -> SchedForm(SchedMode.DAYS, n = r.interval, hour = r.hour, minute = r.minute, from = r.from)
            Freq.WEEKLY -> SchedForm(SchedMode.WEEKS, n = r.interval, days = r.byDay.map { it.dow }.toSet(), hour = r.hour, minute = r.minute, from = r.from)
            Freq.MONTHLY -> when {
                r.byMonthDay != null -> SchedForm(SchedMode.MONTHS, n = r.interval, monthDay = r.byMonthDay, hour = r.hour, minute = r.minute, from = r.from)
                r.byDay.size == 1 && r.byDay[0].ordinal != null && (r.byDay[0].ordinal in listOf(1, 2, 3, 4, -1)) ->
                    SchedForm(SchedMode.MONTHS, n = r.interval, monthByDay = false, ordinal = r.byDay[0].ordinal!!, ordDay = r.byDay[0].dow, hour = r.hour, minute = r.minute, from = r.from)
                else -> null
            }
            Freq.YEARLY -> if (r.byMonth != null && r.byMonthDay != null && r.byMonthDay > 0)
                SchedForm(SchedMode.YEARS, n = r.interval, month = r.byMonth, dayOfMonth = r.byMonthDay, hour = r.hour, minute = r.minute, from = r.from)
            else null
        }
    }
}

// ── calendar arithmetic for the one date the control works out ──────────────────────────────────────────────────────

private fun epochDay(y: Int, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

private fun fromEpochDay(z0: Long): IsoDate {
    val z = z0 + 719468L
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)
    return IsoDate((yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt(), m, d)
}

internal fun addDays(d: IsoDate, n: Int): IsoDate = fromEpochDay(epochDay(d.year, d.month, d.day) + n)

/** Cron's weekday: 0 = Sunday. 1970-01-01 was a Thursday. */
internal fun weekday(d: IsoDate): Int = (((epochDay(d.year, d.month, d.day) + 4) % 7 + 7) % 7).toInt()

private fun ordinalMatches(d: IsoDate, ordinal: Int): Boolean =
    if (ordinal == -1) d.day + 7 > daysInMonth(d.year, d.month) else (d.day - 1) / 7 + 1 == ordinal

internal fun parseIsoDate(s: String): IsoDate? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(s.trim()) ?: return null
    val (y, mo, d) = m.destructured.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
    return if (mo in 1..12 && d in 1..daysInMonth(y, mo)) IsoDate(y, mo, d) else null
}

// ── the foot: only ever the server's words ──────────────────────────────────────────────────────────────────────────

sealed interface SchedFoot {
    /** Nothing asked yet, or the answer is on its way. [stale] is the last good answer, shown dimmed — never under a refusal. */
    data class Pending(val stale: Ok? = null) : SchedFoot

    /** The server accepted it. [runs] is empty for *Off*. */
    data class Ok(val sentence: String, val runs: List<String>) : SchedFoot

    /** The server's refusal, in its words (FR-2.2). */
    data class Refused(val message: String) : SchedFoot

    /** The server could not be reached. Save is not held for it: the save itself validates. */
    data object Unreachable : SchedFoot
}

/** What a page hands the builder for one setting: the form, the foot, and the zone the server last named. */
data class SchedView(
    val form: SchedForm,
    val foot: SchedFoot = SchedFoot.Pending(),
    val zone: String? = null,
    /** The date *starting* defaults from. The page reads it off the browser's clock; a test passes a fixed one. */
    val today: IsoDate = IsoDate(2026, 1, 1),
) {
    val refused: Boolean get() = foot is SchedFoot.Refused
}

const val SCHED_HELD = "Save is held until the schedule is one the server accepts."

// ── the markup ──────────────────────────────────────────────────────────────────────────────────────────────────────

private fun esc(s: String) = afEsc(s)

private val ORDINAL_CHOICES = listOf("1" to "first", "2" to "second", "3" to "third", "4" to "fourth", "last" to "last")

/** The unit's word in the number N puts it in, in the closed select and in the open menu alike: *1 week*, *2 weeks* (the round's decision 4). */
internal fun schedUnitWord(m: SchedMode, n: Int): String = when (m) {
    SchedMode.OFF -> "Off"
    SchedMode.CRON -> "Custom (cron)"
    SchedMode.HOURS -> if (n == 1) "hour" else "hours"
    SchedMode.DAYS -> if (n == 1) "day" else "days"
    SchedMode.WEEKS -> if (n == 1) "week" else "weeks"
    SchedMode.MONTHS -> if (n == 1) "month" else "months"
    SchedMode.YEARS -> if (n == 1) "year" else "years"
}

private fun two(n: Int) = n.toString().padStart(2, '0')

private fun option(value: String, label: String, on: Boolean) = """<option value="${esc(value)}"${if (on) " selected" else ""}>${esc(label)}</option>"""

private fun select(field: String, cls: String, options: String, bad: Boolean = false) =
    """<select class="select $cls${if (bad) " bad" else ""}" data-sch="$field">$options</select>"""

/** The structural shape of the lines: when it changes the lines are drawn again, otherwise a keystroke is left alone. */
fun schedStructure(f: SchedForm): String = "${f.mode}|${f.monthByDay}"

/** The *starting* line, drawn last and added or taken away in place when N crosses 1, so typing a number never loses its field. */
fun schedFromLineHtml(form: SchedForm, today: IsoDate): String =
    """<div class="sch-line" data-sch-from-line><span class="sch-lead">starting</span><input class="input sch-from" type="date" data-sch="from" value="${form.from ?: form.defaultFrom(today)}"></div>"""

/** The lines: one decision each, in a fixed order (FR-3.1–3.7). */
fun schedLinesHtml(view: SchedView, o: SchedOptions): String {
    val f = view.form
    val bad = view.refused
    val zone = view.zone?.let { esc(it) } ?: ""
    fun line(vararg parts: String, wide: Boolean = false) = """<div class="sch-line${if (wide) " sch-line-wide" else ""}">${parts.joinToString("")}</div>"""
    fun lead(w: String) = """<span class="sch-lead">$w</span>"""
    fun zoneSpan() = """<span class="sch-zone" data-sch-zone>$zone</span>"""

    val units = buildString {
        if (o.canOff) append(option("off", "Off", f.mode == SchedMode.OFF))
        for (m in listOf(SchedMode.HOURS, SchedMode.DAYS, SchedMode.WEEKS, SchedMode.MONTHS, SchedMode.YEARS)) append(option(m.key, schedUnitWord(m, f.n), f.mode == m))
        append(option("cron", "Custom (cron)", f.mode == SchedMode.CRON))
    }
    val unit = select("unit", if (f.mode == SchedMode.OFF || f.mode == SchedMode.CRON) "sch-unit wide" else "sch-unit", units)
    val L = ArrayList<String>()
    if (f.mode == SchedMode.OFF || f.mode == SchedMode.CRON) L += line(lead("Repeat"), unit)
    else L += line(lead("Repeat every"), """<input class="input sch-n${if (bad) " bad" else ""}" type="number" min="1" max="100" data-sch="n" value="${f.n}">""", unit)

    when (f.mode) {
        SchedMode.WEEKS -> L += line(lead("on"),
            """<div class="sch-days" role="group" aria-label="Weekdays">""" + ScheduleNames.WEEK_ORDER.joinToString("") { d ->
                val on = d in f.days
                """<span data-sch-day="$d" role="button" tabindex="0" aria-pressed="$on" class="${if (on) "on" else ""}" title="${ScheduleNames.WEEKDAYS[d]}">${ScheduleNames.WEEKDAYS[d].take(2)}</span>"""
            } + "</div>")
        SchedMode.MONTHS -> {
            L += line(lead("on"), """<div class="seg sch-mshape"><span data-sch-mshape="day" role="button" tabindex="0"${if (f.monthByDay) """ class="on"""" else ""}>a day</span>""" +
                """<span data-sch-mshape="weekday" role="button" tabindex="0"${if (!f.monthByDay) """ class="on"""" else ""}>a weekday</span></div>""")
            if (f.monthByDay) {
                val days = (1..31).joinToString("") { option("$it", "$it", f.monthDay == it) } + option("last", "Last day", f.monthDay == -1)
                L += line(lead("day"), select("mday", "sch-mday", days))
            } else {
                val ords = ORDINAL_CHOICES.joinToString("") { (v, w) -> option(v, w, (if (v == "last") -1 else v.toInt()) == f.ordinal) }
                val dows = ScheduleNames.WEEK_ORDER.joinToString("") { option("$it", ScheduleNames.WEEKDAYS[it], f.ordDay == it) }
                L += line(lead("the"), select("ord", "sch-ord", ords), select("ordday", "sch-ordday", dows))
            }
        }
        SchedMode.YEARS -> L += line(lead("on"),
            select("month", "sch-month", ScheduleNames.MONTHS.withIndex().joinToString("") { (i, m) -> option("${i + 1}", m, f.month == i + 1) }),
            select("dom", "sch-dom", (1..31).joinToString("") { option("$it", "$it", f.dayOfMonth == it) }))
        SchedMode.CRON -> L += line("""<input class="input mono sch-cron${if (bad) " bad" else ""}" type="text" data-sch="cron" spellcheck="false" autocomplete="off" placeholder="0 4 * * 1,2" value="${esc(f.cron)}">""", wide = true)
        else -> {}
    }

    when (f.mode) {
        SchedMode.HOURS -> L += line(lead("at minute"), """<input class="input mono sch-min" type="number" min="0" max="59" data-sch="minute" value="${f.minute}">""", zoneSpan())
        SchedMode.OFF, SchedMode.CRON -> {}
        else -> L += line(lead("at"), """<input class="input mono sch-at" type="time" data-sch="at" value="${two(f.hour)}:${two(f.minute)}">""", zoneSpan())
    }
    if (f.showsFrom) L += schedFromLineHtml(f, view.today)
    return L.joinToString("")
}

/** The foot: both halves, always — a control that changes height as its mode is chosen moves under the hand using it (the round's decision 1). */
fun schedFootHtml(foot: SchedFoot, o: SchedOptions, off: Boolean): String {
    val shown = (foot as? SchedFoot.Pending)?.stale ?: foot as? SchedFoot.Ok
    val dim = foot is SchedFoot.Pending && foot.stale != null
    val says = when {
        foot is SchedFoot.Refused -> """<div class="sch-err" role="alert">${esc(foot.message)}</div>"""
        shown != null -> esc(shown.sentence)
        foot is SchedFoot.Unreachable -> """<span class="muted">Couldn't check the schedule right now — it will still be checked when you save.</span>"""
        else -> """<span class="muted">Asking the server…</span>"""
    }
    val runs = shown?.runs.orEmpty()
    val body = when {
        runs.isNotEmpty() -> """<ol class="sch-runs">""" + runs.joinToString("") { r ->
            val p = r.split(" · ")
            """<li><span class="d">${esc(p[0])}</span><span class="t mono">${esc(p.getOrElse(1) { "" })}</span></li>"""
        } + "</ol>"
        foot is SchedFoot.Refused -> """<div class="sch-none">No next runs while the schedule is refused.</div>"""
        shown != null || off -> """<div class="sch-none">Nothing scheduled.</div>"""
        else -> """<div class="sch-none">…</div>"""
    }
    // The slot never goes away (FR-3.8), but its heading does not promise five when it is about to say there are none.
    val head = if (runs.isNotEmpty()) "Next five runs" else "Next runs"
    return """<div class="sch-says">$says</div><div class="sch-next${if (dim) " sch-stale" else ""}"><div class="sch-next-h">$head</div>$body</div>"""
}

/** THE control. [key] names the setting (`find`, `music`, `af:<id>`…); the page's one delegated listener finds it by `data-sch-key`. */
fun schedulePickerHtml(key: String, view: SchedView, o: SchedOptions): String =
    """<div class="sch${if (view.refused) " bad" else ""}" data-sch-key="${esc(key)}" data-sch-structure="${esc(schedStructure(view.form))}"><div class="sch-lines" data-sch-lines>${schedLinesHtml(view, o)}</div>""" +
        """<div class="sch-foot" data-sch-foot>${schedFootHtml(view.foot, o, view.form.mode == SchedMode.OFF)}</div></div>"""

/** The setting's label and the control, as a `.field`. */
fun schedulePickerField(key: String, view: SchedView, o: SchedOptions): String =
    """<div class="field sch-field"><label>${esc(o.label)}</label>${schedulePickerHtml(key, view, o)}</div>"""
