package dev.jellystructure.cron

/**
 * DEB-4387 (FR-1) — the one schedule type. A schedule is a single string in one of two forms:
 *
 *  - a **cron**, the five fields or a macro, exactly as [parseCron] accepts them (every stored Find schedule stays valid
 *    and is never rewritten); or
 *  - a **recurrence**, `every:` followed by `;`-separated `KEY=value` pairs — a subset of RFC 5545 RRULE:
 *    `FREQ=HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL=1-100`, `BYDAY=MO,TU` (`1MO`/`-1FR` with MONTHLY),
 *    `BYMONTHDAY=1-31|-1`, `BYMONTH=1-12`, `AT=HH:MM` (`MM` with HOURLY), `FROM=YYYY-MM-DD` (the anchor).
 *
 * An empty string is off. This file owns parsing, the canonical string and the plain-words sentence, and the weekday and
 * month names exist once, here. It is `commonMain` so the admin and the backend can never disagree about what a schedule
 * says; the arithmetic of when it fires is the backend's ([ScheduleEvaluator], in linuxX64Main).
 */
sealed interface Schedule {
    /** The string to store. A recurrence a cron can say is written as that cron (FR-1.2); everything else keeps its form. */
    val canonical: String

    /** One plain-words sentence, times in the schedule zone (FR-4.2). */
    val sentence: String
}

data class CronSchedule(val text: String, val expr: CronExpr) : Schedule {
    override val canonical: String get() = text
    override val sentence: String get() = ScheduleWords.cron(text, expr)
}

data class RecurrenceSchedule(val rule: Recurrence) : Schedule {
    override val canonical: String get() = rule.toCron() ?: rule.text()
    override val sentence: String get() = ScheduleWords.recurrence(rule)
}

enum class Freq { HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

/** A weekday as cron counts it (0 = Sunday … 6 = Saturday), with the ordinal MONTHLY allows (1 = first, -1 = last). */
data class ByDay(val dow: Int, val ordinal: Int? = null)

data class IsoDate(val year: Int, val month: Int, val day: Int) {
    override fun toString() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    /** In week order, Monday first, no duplicates. */
    val byDay: List<ByDay> = emptyList(),
    val byMonthDay: Int? = null,
    val byMonth: Int? = null,
    /** 0-23. Unused by HOURLY. */
    val hour: Int = 0,
    val minute: Int = 0,
    val from: IsoDate? = null,
) {
    /** The literal `every:` form, keys in one fixed order. */
    fun text(): String = buildString {
        append("every:FREQ=").append(freq.name)
        if (interval != 1) append(";INTERVAL=").append(interval)
        if (byMonth != null) append(";BYMONTH=").append(byMonth)
        if (byMonthDay != null) append(";BYMONTHDAY=").append(byMonthDay)
        if (byDay.isNotEmpty()) append(";BYDAY=").append(byDay.joinToString(",") { (it.ordinal?.toString() ?: "") + ScheduleNames.WEEKDAY_CODES[it.dow] })
        if (freq == Freq.HOURLY) {
            if (minute != 0) append(";AT=").append(minute.pad2())
        } else append(";AT=").append(hour.pad2()).append(':').append(minute.pad2())
        if (from != null) append(";FROM=").append(from)
    }

    /**
     * The cron that says exactly this, or null when none does: an interval above 1 on days, weeks, months or years, an
     * ordinal weekday, *last day of the month*, an hourly interval that does not divide 24, and anything with a `FROM`
     * (a cron has no start date). A backend rolled back one release still reads every schedule that does not need `every:`.
     */
    fun toCron(): String? {
        if (from != null) return null
        return when (freq) {
            Freq.HOURLY -> when {
                interval == 1 -> "$minute * * * *"
                24 % interval == 0 -> "$minute */$interval * * *"
                else -> null
            }
            Freq.DAILY -> if (interval == 1) "$minute $hour * * *" else null
            Freq.WEEKLY -> if (interval == 1 && byDay.isNotEmpty()) "$minute $hour * * ${byDay.map { it.dow }.sorted().joinToString(",")}" else null
            Freq.MONTHLY -> if (interval == 1 && byMonthDay != null && byMonthDay > 0) "$minute $hour $byMonthDay * *" else null
            Freq.YEARLY -> if (interval == 1 && byMonth != null && byMonthDay != null && byMonthDay > 0) "$minute $hour $byMonthDay $byMonth *" else null
        }
    }
}

sealed interface ScheduleParse {
    /** The empty string: the job runs only when started by hand. */
    data object Off : ScheduleParse
    data class Ok(val schedule: Schedule) : ScheduleParse
    data class Invalid(val message: String) : ScheduleParse
}

/** The weekday and month names, once. Weekdays are indexed as cron counts them (0 = Sunday). */
object ScheduleNames {
    val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val WEEKDAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
    val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** Monday first, the order a week is shown and a BYDAY list is written in. */
    val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
}

private fun Int.pad2() = toString().padStart(2, '0')

private const val EVERY = "every:"

fun parseSchedule(text: String): ScheduleParse {
    val t = text.trim()
    if (t.isEmpty()) return ScheduleParse.Off
    if (t.startsWith(EVERY, ignoreCase = true)) return parseRecurrence(t.substring(EVERY.length))
    return when (val p = parseCron(t)) {
        is CronParse.Ok -> ScheduleParse.Ok(CronSchedule(t, p.expr))
        is CronParse.Invalid -> ScheduleParse.Invalid(p.message)
    }
}

private fun bad(message: String) = ScheduleParse.Invalid(message)

private val TIME = Regex("""(\d{1,2}):(\d{2})""")
private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val BYDAY_TOKEN = Regex("""([+-]?\d{1,2})?([A-Za-z]{2})""")

fun isLeapYear(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

fun daysInMonth(y: Int, m: Int): Int = when (m) {
    2 -> if (isLeapYear(y)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun parseRecurrence(body: String): ScheduleParse {
    val pairs = LinkedHashMap<String, String>()
    for (part in body.split(';')) {
        if (part.isBlank()) continue
        val eq = part.indexOf('=')
        if (eq <= 0) return bad("'$part' isn't KEY=value.")
        val key = part.substring(0, eq).trim().uppercase()
        if (key in pairs) return bad("$key is given twice.")
        pairs[key] = part.substring(eq + 1).trim()
    }
    val known = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "AT", "FROM")
    pairs.keys.firstOrNull { it !in known }?.let { return bad("$it isn't supported — a schedule takes FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYMONTH, AT and FROM.") }

    val freq = Freq.entries.firstOrNull { it.name == pairs["FREQ"]?.uppercase() }
        ?: return bad("FREQ must be HOURLY, DAILY, WEEKLY, MONTHLY or YEARLY.")

    val interval = pairs["INTERVAL"]?.let { it.toIntOrNull() ?: return bad("INTERVAL '$it' isn't a whole number.") } ?: 1
    if (interval !in 1..100) return bad("INTERVAL must be between 1 and 100.")

    val allowed = when (freq) {
        Freq.HOURLY -> setOf("FREQ", "INTERVAL", "AT", "FROM")
        Freq.DAILY -> setOf("FREQ", "INTERVAL", "AT", "FROM")
        Freq.WEEKLY -> setOf("FREQ", "INTERVAL", "BYDAY", "AT", "FROM")
        Freq.MONTHLY -> setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "AT", "FROM")
        Freq.YEARLY -> setOf("FREQ", "INTERVAL", "BYMONTH", "BYMONTHDAY", "AT", "FROM")
    }
    pairs.keys.firstOrNull { it !in allowed }?.let { return bad("$it doesn't apply to FREQ=${freq.name}.") }

    var hour = 0
    var minute = 0
    pairs["AT"]?.let { at ->
        if (freq == Freq.HOURLY) {
            minute = at.toIntOrNull()?.takeIf { it in 0..59 } ?: return bad("AT with HOURLY is the minute past the hour, 0-59.")
        } else {
            val m = TIME.matchEntire(at) ?: return bad("AT '$at' isn't HH:MM.")
            hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt()
            if (hour > 23 || minute > 59) return bad("AT '$at' isn't a time of day.")
        }
    }

    val from = pairs["FROM"]?.let { f ->
        val m = DATE.matchEntire(f) ?: return bad("FROM '$f' isn't YYYY-MM-DD.")
        val (y, mo, d) = m.destructured.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
        if (mo !in 1..12 || d !in 1..daysInMonth(y, mo)) return bad("FROM '$f' isn't a real date.")
        IsoDate(y, mo, d)
    }
    if (interval > 1 && freq != Freq.HOURLY && from == null) return bad("An interval above 1 needs FROM, the date the intervals count from.")

    val byMonthDay = pairs["BYMONTHDAY"]?.let { v ->
        val n = v.toIntOrNull() ?: return bad("BYMONTHDAY '$v' isn't a whole number.")
        if (n != -1 && n !in 1..31) return bad("BYMONTHDAY must be 1-31, or -1 for the last day of the month.")
        n
    }
    val byMonth = pairs["BYMONTH"]?.let { v ->
        val n = v.toIntOrNull() ?: return bad("BYMONTH '$v' isn't a whole number.")
        if (n !in 1..12) return bad("BYMONTH must be 1-12.")
        n
    }

    val byDay = ArrayList<ByDay>()
    pairs["BYDAY"]?.let { v ->
        for (tok in v.split(',')) {
            val m = BYDAY_TOKEN.matchEntire(tok.trim()) ?: return bad("BYDAY '$tok' isn't a weekday like MO or an ordinal weekday like 1MO.")
            val dow = ScheduleNames.WEEKDAY_CODES.indexOf(m.groupValues[2].uppercase())
            if (dow < 0) return bad("BYDAY '${m.groupValues[2]}' isn't a weekday — use MO, TU, WE, TH, FR, SA or SU.")
            val ord = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
            if (ord != null) {
                if (freq != Freq.MONTHLY) return bad("An ordinal weekday ('$tok') only goes with FREQ=MONTHLY.")
                if (ord == 0 || ord !in -5..5) return bad("The ordinal in '$tok' must be 1-5, or -1 for the last.")
            }
            val entry = ByDay(dow, ord)
            if (entry !in byDay) byDay += entry
        }
        if (byDay.isEmpty()) return bad("BYDAY is empty.")
    }
    byDay.sortWith(compareBy({ ScheduleNames.WEEK_ORDER.indexOf(it.dow) }, { it.ordinal ?: 0 }))

    when (freq) {
        Freq.WEEKLY -> if (byDay.isEmpty()) return bad("FREQ=WEEKLY needs BYDAY, the weekdays it runs on.")
        Freq.MONTHLY -> {
            if (byDay.isEmpty() == (byMonthDay == null)) return bad("FREQ=MONTHLY needs BYMONTHDAY (a day of the month) or BYDAY (an ordinal weekday), one of them.")
        }
        Freq.YEARLY -> if (byMonth == null || byMonthDay == null) return bad("FREQ=YEARLY needs BYMONTH and BYMONTHDAY.")
        else -> {}
    }
    if (freq == Freq.YEARLY && byMonthDay != null && byMonthDay > 0 && byMonth != null && byMonthDay > daysInMonth(2024, byMonth)) {
        return bad("${ScheduleNames.MONTHS[byMonth - 1]} has no day $byMonthDay.")
    }

    return ScheduleParse.Ok(RecurrenceSchedule(Recurrence(freq, interval, byDay, byMonthDay, byMonth, hour, minute, from)))
}

/** The plain-words sentence, for both forms. Weekday and month names come from [ScheduleNames]. */
object ScheduleWords {
    const val OFF = "Not on a schedule — it runs when you ask it to"

    private fun time(h: Int, m: Int) = h.pad2() + ":" + m.pad2()

    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun ordinalWord(n: Int) = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"
        -1 -> "last"; -2 -> "second to last"; -3 -> "third to last"; -4 -> "fourth to last"; else -> "fifth to last"
    }

    private fun dateWords(d: IsoDate) = "${d.day} ${ScheduleNames.MONTHS[d.month - 1]} ${d.year}"

    private fun hourly(n: Int, minute: Int) =
        (if (n == 1) "Every hour" else "Every $n hours") + if (minute != 0) " at :" + minute.pad2() else ""

    fun recurrence(r: Recurrence): String {
        val at = time(r.hour, r.minute)
        val starting = r.from?.let { ", starting ${dateWords(it)}" } ?: ""
        val every = { unit: String -> if (r.interval == 1) null else "Every ${r.interval} $unit" }
        return when (r.freq) {
            Freq.HOURLY -> hourly(r.interval, r.minute) + starting
            Freq.DAILY -> (every("days") ?: "Daily") + " at $at" + starting
            Freq.WEEKLY -> {
                val days = list(r.byDay.map { ScheduleNames.WEEKDAYS[it.dow] })
                (every("weeks")?.let { "$it on $days" } ?: "Weekly on $days") + " at $at" + starting
            }
            Freq.MONTHLY -> {
                val on = when {
                    r.byMonthDay == -1 -> "the last day"
                    r.byMonthDay != null -> "day ${r.byMonthDay}"
                    else -> "the " + list(r.byDay.map { d -> (d.ordinal?.let { ordinalWord(it) + " " } ?: "") + ScheduleNames.WEEKDAYS[d.dow] })
                }
                (every("months")?.let { "$it on $on" } ?: "Monthly on $on") + " at $at" + starting
            }
            Freq.YEARLY -> {
                val month = ScheduleNames.MONTHS[(r.byMonth ?: 1) - 1]
                val day = if (r.byMonthDay == -1) "the last day of $month" else "${r.byMonthDay} $month"
                (every("years")?.let { "$it on $day" } ?: "Yearly on $day") + " at $at" + starting
            }
        }
    }

    /** The shapes the picker writes and the common hand-written ones; anything else is *Custom* with its expression. */
    fun cron(text: String, expr: CronExpr): String {
        val f = text.trim().let { if (it.startsWith("@")) MACRO_FIELDS[it.lowercase()] ?: it else it }.split(Regex("\\s+"))
        if (f.size != 5) return "Custom · $text"
        val (minF, hourF, domF, monF, dowF) = f
        val minute = minF.toIntOrNull()
        val hour = hourF.toIntOrNull()
        fun custom() = "Custom · $text"

        if (minute != null && monF == "*" && domF == "*" && dowF == "*") {
            if (hourF == "*") return hourly(1, minute)
            if (hourF.startsWith("*/")) hourF.removePrefix("*/").toIntOrNull()?.takeIf { it > 0 }?.let { return hourly(it, minute) }
            if (hour != null) return "Daily at ${time(hour, minute)}"
        }
        if (minF.startsWith("*/") && hourF == "*" && domF == "*" && monF == "*" && dowF == "*") {
            minF.removePrefix("*/").toIntOrNull()?.let { return "Every $it minutes" }
        }
        if (minute == null || hour == null) return custom()
        val at = time(hour, minute)
        if (domF == "*" && monF == "*") {
            val days = ScheduleNames.WEEK_ORDER.filter { it in expr.daysOfWeek }
            return "Weekly on ${list(days.map { ScheduleNames.WEEKDAYS[it] })} at $at"
        }
        val dom = domF.toIntOrNull()
        if (dom != null && dowF == "*") {
            if (monF == "*") return "Monthly on day $dom at $at"
            monF.toIntOrNull()?.let { return "Yearly on $dom ${ScheduleNames.MONTHS[it - 1]} at $at" }
        }
        return custom()
    }

    private val MACRO_FIELDS = mapOf(
        "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *", "@monthly" to "0 0 1 * *", "@weekly" to "0 0 * * 0",
        "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@hourly" to "0 * * * *",
    )
}
