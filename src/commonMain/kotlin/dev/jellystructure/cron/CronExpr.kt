package dev.jellystructure.cron

/**
 * Phase 166 (FR-166-1) — a real, shared 5-field cron parser, compiled into both the wasmJs admin
 * frontend and the linuxX64 backend (same placement convention as `jobs/MediaJobParams.kt`) so the two
 * can never disagree about whether a schedule is valid. This file is parsing/description only —
 * standard `*`, a literal, a range `A-B`, a step `*&#47;S` or `A-B/S`, and comma-separated lists of any
 * of those, per field. Evaluating a parsed [CronExpr] against wall-clock time (next-fire computation) needs
 * real POSIX `mktime`/DST handling and lives backend-side only (`dev.jellystructure.cron.CronEvaluator`
 * in linuxX64Main); the frontend gets next-run previews from the backend rather than re-implementing
 * that arithmetic a second time in JS, so there is exactly one evaluator to ever be wrong.
 *
 * Deliberately rejected, with a field-specific message: Quartz/extended syntax (`L`, `W`, `#`, `?`), a
 * seconds field (6 fields), named months/days (`JAN`, `MON`), and `@reboot`. Silently mis-evaluating one
 * of these is exactly the class of bug this phase exists to close (see the phase spec's defect #1).
 */
data class CronExpr(
    val minutes: Set<Int>,      // 0-59
    val hours: Set<Int>,        // 0-23
    val daysOfMonth: Set<Int>,  // 1-31
    val months: Set<Int>,       // 1-12
    val daysOfWeek: Set<Int>,   // 0-6, 0 = Sunday (a literal 7 is accepted on input and normalized to 0)
    /** True only when the RAW field text was exactly `*` — standard cron's day-of-month/day-of-week OR
     *  rule keys off this, not off whether the resolved value set happens to span the full range (a
     *  `*&#47;1` step legitimately restricts nothing but is not textually `*`). */
    val domIsWildcard: Boolean,
    val dowIsWildcard: Boolean,
)

sealed interface CronParse {
    data class Ok(val expr: CronExpr, val description: String) : CronParse
    data class Invalid(val fieldIndex: Int, val message: String) : CronParse
}

private val FIELD_NAMES = listOf("minute", "hour", "day-of-month", "month", "day-of-week")
private val FIELD_RANGES = listOf(0 to 59, 0 to 23, 1 to 31, 1 to 12, 0 to 7)

private val MACROS: Map<String, String> = mapOf(
    "@yearly" to "0 0 1 1 *",
    "@annually" to "0 0 1 1 *",
    "@monthly" to "0 0 1 * *",
    "@weekly" to "0 0 * * 0",
    "@daily" to "0 0 * * *",
    "@midnight" to "0 0 * * *",
    "@hourly" to "0 * * * *",
)

private sealed interface FieldResult {
    data class Ok(val values: Set<Int>) : FieldResult
    data class Err(val message: String) : FieldResult
}

private val ALLOWED_CHARS = "0123456789*,-/".toSet()

private fun parseField(raw: String, min: Int, max: Int, name: String): FieldResult {
    val badChar = raw.firstOrNull { it !in ALLOWED_CHARS }
    if (badChar != null) {
        val extra = when {
            badChar.isLetter() -> " (named values like 'MON'/'JAN', and letter-based extensions like 'L'/'W', are not supported)"
            badChar == '#' || badChar == '?' -> " (Quartz-style '#'/'?' extensions are not supported)"
            else -> ""
        }
        return FieldResult.Err("The $name field contains '$badChar', which isn't a supported character$extra.")
    }
    if (raw.isEmpty()) return FieldResult.Err("The $name field is empty.")

    // Not sortedSetOf() — that's JVM-only (java.util.TreeSet), unavailable in common Kotlin stdlib for
    // the wasmJs/linuxX64 targets this file compiles to. Ordering isn't needed on the Set itself; every
    // consumer that cares about order calls .sorted() explicitly (see CronEvaluator's sortedHours/Minutes).
    val values = mutableSetOf<Int>()
    for (part in raw.split(",")) {
        if (part.isEmpty()) return FieldResult.Err("The $name field has an empty entry between commas.")
        val slash = part.indexOf('/')
        val rangePart = if (slash >= 0) part.substring(0, slash) else part
        val stepText = if (slash >= 0) part.substring(slash + 1) else null
        if (rangePart.isEmpty()) return FieldResult.Err("The $name field has a step with no range before it.")
        val step = if (stepText != null) {
            val s = stepText.toIntOrNull()
            if (s == null || s <= 0) return FieldResult.Err("The $name field's step '$stepText' must be a positive whole number.")
            s
        } else null

        val (lo, hi) = when {
            rangePart == "*" -> min to max
            "-" in rangePart -> {
                val dash = rangePart.indexOf('-')
                val loText = rangePart.substring(0, dash)
                val hiText = rangePart.substring(dash + 1)
                val lo = loText.toIntOrNull() ?: return FieldResult.Err("The $name field's range start '$loText' isn't a number.")
                val hi = hiText.toIntOrNull() ?: return FieldResult.Err("The $name field's range end '$hiText' isn't a number.")
                lo to hi
            }
            else -> {
                val v = rangePart.toIntOrNull() ?: return FieldResult.Err("The $name field has an invalid value '$rangePart'.")
                v to v
            }
        }
        if (lo > hi) return FieldResult.Err("The $name field's range $lo-$hi is backwards (start must be ≤ end).")
        if (lo < min || hi > max) return FieldResult.Err("The $name field's range $lo-$hi is outside the valid $min-$max.")

        var v = lo
        while (v <= hi) { values.add(v); v += (step ?: 1) }
    }
    return FieldResult.Ok(values)
}

/** Parses a 5-field cron expression (or one of the `@hourly`/`@daily`/`@weekly`/`@monthly`/`@yearly`
 *  macros — `@reboot` and a seconds-first 6-field form are explicitly not supported). See this file's
 *  top-level doc for what is/isn't accepted. */
fun parseCron(text: String): CronParse {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return CronParse.Invalid(-1, "The schedule is empty.")

    if (trimmed.startsWith("@")) {
        val expanded = MACROS[trimmed.lowercase()]
            ?: return CronParse.Invalid(-1, "Unknown macro '$trimmed' — only @hourly, @daily, @weekly, @monthly, @yearly are supported (not @reboot).")
        return parseCron(expanded)
    }

    val fields = trimmed.split(Regex("\\s+"))
    if (fields.size == 6) return CronParse.Invalid(-1, "Found 6 fields — a leading seconds field isn't supported here, only the standard 5 (minute hour day-of-month month day-of-week).")
    if (fields.size != 5) return CronParse.Invalid(-1, "Expected 5 fields (minute hour day-of-month month day-of-week), found ${fields.size}.")

    val parsed = ArrayList<Set<Int>>(5)
    for (i in 0 until 5) {
        val (min, max) = FIELD_RANGES[i]
        when (val r = parseField(fields[i], min, max, FIELD_NAMES[i])) {
            is FieldResult.Err -> return CronParse.Invalid(i, r.message)
            is FieldResult.Ok -> parsed.add(r.values)
        }
    }

    val dows = parsed[4].map { if (it == 7) 0 else it }.toSet()
    val expr = CronExpr(
        minutes = parsed[0], hours = parsed[1], daysOfMonth = parsed[2], months = parsed[3], daysOfWeek = dows,
        domIsWildcard = fields[2].trim() == "*", dowIsWildcard = fields[4].trim() == "*",
    )
    return CronParse.Ok(expr, describeCron(fields, expr))
}

private val DOW_NAMES = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

private fun Int.pad2() = toString().padStart(2, '0')

/** Plain-English rendering for the Settings UI. Covers the shapes this app's own presets emit plus the
 *  common hand-written custom ones; anything more exotic falls back to a generic line — the next-runs
 *  preview (computed server-side, see the phase spec) is the actual source of truth for what a schedule
 *  does, this is just a hint while typing. */
private fun describeCron(fields: List<String>, expr: CronExpr): String {
    val (minF, hourF, domF, monF, dowF) = fields

    if (domF != "*" && dowF != "*") {
        return "On day $domF of the month, and on day-of-week $dowF — cron's OR rule: either match fires it."
    }
    if (domF != "*" || monF != "*") {
        return "Runs on the schedule shown in the preview below."
    }

    if (hourF.startsWith("*/") && minF.toIntOrNull() != null && dowF == "*") {
        val n = hourF.removePrefix("*/").toIntOrNull()
        if (n != null) {
            val m = minF.toInt()
            return if (m == 0) "Every $n hours, on the hour." else "Every $n hours, at minute ${m.pad2()}."
        }
    }
    if (minF.startsWith("*/") && hourF == "*" && dowF == "*") {
        val n = minF.removePrefix("*/").toIntOrNull()
        if (n != null) return "Every $n minutes."
    }
    val hour = hourF.toIntOrNull()
    val minute = minF.toIntOrNull()
    if (hour != null && minute != null) {
        val time = "${hour.pad2()}:${minute.pad2()}"
        return when {
            dowF == "*" -> "At $time, every day."
            expr.daysOfWeek == setOf(1, 2, 3, 4, 5) -> "At $time, Monday to Friday."
            expr.daysOfWeek == setOf(0, 6) -> "At $time, on weekends."
            expr.daysOfWeek.size == 1 -> "At $time, on ${DOW_NAMES[expr.daysOfWeek.first()]}."
            else -> "At $time, on ${expr.daysOfWeek.sorted().joinToString(", ") { DOW_NAMES[it] }}."
        }
    }
    return "Runs on the schedule shown in the preview below."
}
