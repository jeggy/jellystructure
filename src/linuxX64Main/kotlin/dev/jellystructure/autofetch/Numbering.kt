package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-4.1/4.5) — the regular-expression grammar a recipe's `pattern` may use, and how an item becomes
 * a season and an episode. A rule is a regex with the named groups `season` and/or `episode`; before one is stored it is
 * checked for size and for the constructs that can backtrack catastrophically. Kotlin/Native's engine backtracks, so the
 * only sound check is a grammar one: no nested quantifiers, no quantified alternation, no back-references, no lookaround.
 */
object PatternSafety {
    private const val MAX_LEN = 200
    private val GROUP_NAME = Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>""")

    /** The reason [regex] is refused, or null when it is stored as written. */
    fun check(regex: String, required: Set<String>, anyOf: Set<String> = emptySet()): String? {
        if (regex.isBlank()) return "the pattern is empty"
        if (regex.length > MAX_LEN) return "the pattern is longer than $MAX_LEN characters"
        shapeProblem(regex)?.let { return it }
        // FR-4.1 (DEB-3459): the engine's own message, so the model's correction is not a guess.
        val compiled = try { Regex(regex) } catch (e: Throwable) {
            return "the pattern is not a valid regular expression: ${(e.message ?: e.toString()).replace(Regex("\\s+"), " ").trim().take(160)}"
        }
        val names = GROUP_NAME.findAll(regex).map { it.groupValues[1] }.toList()
        val allowed = required + anyOf
        names.firstOrNull { it !in allowed }?.let { return "the group '$it' is not one a pattern may name" }
        required.firstOrNull { it !in names }?.let { return "the pattern needs a group named '$it'" }
        if (anyOf.isNotEmpty() && names.none { it in anyOf }) return "the pattern needs a group named ${anyOf.joinToString(" or ") { "'$it'" }}"
        // An empty match everywhere would make every item "match"; the engine never accepts a rule that matches the empty string alone.
        if (compiled.matchEntire("") != null && names.isEmpty()) return "the pattern matches nothing useful"
        return null
    }

    private val BRACE = Regex("""^\{(\d+)(,(\d*))?}""")

    /** True when a quantifier at [at] can repeat more than once (`*`, `+`, `{n,}`, `{n,m}` with m > 1). */
    private fun repeats(re: String, at: Int): Boolean {
        val c = re.getOrNull(at) ?: return false
        if (c == '*' || c == '+') return true
        if (c != '{') return false
        val m = BRACE.find(re.substring(at)) ?: return false
        val hasComma = m.groupValues[2].isNotEmpty()
        val max = if (!hasComma) m.groupValues[1].toIntOrNull() else m.groupValues[3].toIntOrNull() ?: return true
        return (max ?: 2) > 1
    }

    /** Scans the pattern as tokens (escapes and character classes aside) for the constructs the grammar excludes. */
    private fun shapeProblem(re: String): String? {
        data class Group(val start: Int, var hasQuant: Boolean = false, var hasAlt: Boolean = false)
        val stack = ArrayList<Group>()
        var i = 0
        var inClass = false
        while (i < re.length) {
            val c = re[i]
            if (c == '\\') {
                val nx = re.getOrNull(i + 1)
                if (nx != null && (nx in '1'..'9' || nx == 'k')) return "back-references are not allowed in a pattern"
                if (nx == 'p' || nx == 'P') { i += 2; continue }
                i += 2; continue
            }
            if (inClass) { if (c == ']') inClass = false; i++; continue }
            when (c) {
                '[' -> inClass = true
                '(' -> {
                    if (re.startsWith("(?=", i) || re.startsWith("(?!", i) || re.startsWith("(?<=", i) || re.startsWith("(?<!", i) || re.startsWith("(?>", i))
                        return "lookaround is not allowed in a pattern"
                    stack.add(Group(i))
                }
                ')' -> {
                    val g = stack.removeLastOrNull() ?: return "unbalanced parentheses"
                    val quantified = repeats(re, i + 1)
                    if (quantified && (g.hasQuant || g.hasAlt)) return "a repeated group cannot itself repeat or branch (it can backtrack without end)"
                    stack.lastOrNull()?.let { parent -> if (g.hasQuant || quantified) parent.hasQuant = true; if (g.hasAlt) parent.hasAlt = true }
                }
                '*', '+' -> stack.lastOrNull()?.hasQuant = true
                '{' -> if (repeats(re, i)) stack.lastOrNull()?.hasQuant = true
                '|' -> stack.lastOrNull()?.hasAlt = true
            }
            i++
        }
        if (inClass || stack.isNotEmpty()) return "unbalanced brackets or parentheses"
        return null
    }
}

/** An item's season and episode, however they were found. */
data class SeasonEpisode(val season: Int, val episode: Int)

object Numbering {
    private const val MAX_INPUT = 2_000

    /** `source` numbering: the first rule that yields a season and the first that yields an episode win; the listing's own season/episode fields come first. */
    fun fromSource(rules: List<PatternRule>, item: ListedItem): SeasonEpisode? {
        var season = item.season?.trim()?.toIntOrNull()
        var episode = item.episode?.trim()?.toIntOrNull()
        for (r in rules) {
            if (season != null && episode != null) break
            val text = when (r.on) {
                "title" -> item.title
                "link" -> item.link
                "description" -> item.description
                "published" -> item.published
                else -> null
            }?.take(MAX_INPUT) ?: continue
            val m = runCatching { Regex(r.regex).find(text) }.getOrNull() ?: continue
            if (season == null) season = group(m, "season")?.toIntOrNull()
            if (episode == null) episode = group(m, "episode")?.toIntOrNull()
        }
        if (season == null || episode == null || season < 0 || episode < 0) return null
        return SeasonEpisode(season, episode)
    }

    private fun group(m: MatchResult, name: String): String? = runCatching { m.groups[name]?.value }.getOrNull()

    /** The value an `item_page` pattern reads from a page's source, or null when it is absent or fails the character rule (FR-4.1). */
    fun itemPageValue(pattern: String, pageSource: String): String? {
        val m = runCatching { Regex(pattern).find(pageSource.take(Html.MAX_BYTES)) }.getOrNull() ?: return null
        return validMediaValue(group(m, "value"))
    }

    private val VALUE_RE = Regex("^[A-Za-z0-9._-]{1,128}$")

    /** `[A-Za-z0-9._-]{1,128}` or null (FR-4.1). */
    fun validMediaValue(v: String?): String? = v?.trim()?.takeIf { VALUE_RE.matches(it) }

    // ---- dates (FR-4.5) -------------------------------------------------------------------------------------------

    private val MONTHS = mapOf(
        "januar" to 1, "january" to 1, "jan" to 1, "februar" to 2, "february" to 2, "feb" to 2,
        "mars" to 3, "marts" to 3, "march" to 3, "mar" to 3, "apríl" to 4, "april" to 4, "apr" to 4,
        "mai" to 5, "maj" to 5, "may" to 5, "juni" to 6, "june" to 6, "jun" to 6, "juli" to 7, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "oktober" to 10, "october" to 10, "okt" to 10, "oct" to 10,
        "november" to 11, "nov" to 11, "desember" to 12, "december" to 12, "dec" to 12, "des" to 12,
    )

    /** A published date as the page prints it, read as a calendar date `yyyy-MM-dd`; null when it is not one. No time zone is applied. */
    fun isoDate(raw: String?): String? {
        val s = raw?.trim() ?: return null
        Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } // yt-dlp's `upload_date`
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ].*)?$""").find(s)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})$""").find(s)?.let { return build(it.groupValues[3], it.groupValues[2], it.groupValues[1]) }
        Regex("""^(\d{1,2})\.?\s+([\p{L}]+)\.?,?\s+(\d{4})$""").find(s)?.let { m ->
            val mo = MONTHS[m.groupValues[2].lowercase()] ?: return null
            return build(m.groupValues[3], mo.toString(), m.groupValues[1])
        }
        return null
    }

    /** `HH.MM` of a minute of the day, the form a file name carries (a colon is not allowed in one). */
    fun clock(minuteOfDay: Int): String = (minuteOfDay / 60).toString().padStart(2, '0') + "." + (minuteOfDay % 60).toString().padStart(2, '0')

    /** The UTC calendar date of [epochSec] as `yyyy-MM-dd` (a yt-dlp `timestamp`), no time-zone shift. */
    fun dateOfEpoch(epochSec: Long): String? {
        val z = epochSec.floorDiv(86_400L) + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return build(y.toString(), m.toString(), d.toString())
    }

    internal fun build(y: String, m: String, d: String): String? {
        val year = y.toIntOrNull() ?: return null; val month = m.toIntOrNull() ?: return null; val day = d.toIntOrNull() ?: return null
        if (year !in 1900..2200 || month !in 1..12 || day !in 1..daysIn(year, month)) return null
        return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    private fun daysIn(y: Int, m: Int) = when (m) { 2 -> if (leap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    /** FR-4.5: season = the year, episode = the day of the year (1–366). 27 October 2026 is S2026E300. */
    fun fromDate(iso: String): SeasonEpisode? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(iso) ?: return null
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        var doy = d
        for (k in 1 until mo) doy += daysIn(y, k)
        return SeasonEpisode(y, doy)
    }

    /**
     * FR-4.5 (amended 2026-11-04, DEB-3567) — `datetime`: season = `(year − 2000) × 100 + week`, episode = the day of the week and a ~10-minute slot
     * of the time. 3 November 2026 at 18.30 is S2644E808. Null outside 2010–2099.
     */
    fun fromDateTime(iso: String, minuteOfDay: Int): SeasonEpisode? {
        val de = fromDate(iso) ?: return null
        if (de.season !in 2010..2099 || minuteOfDay !in 0..1439) return null
        val d = de.episode
        return SeasonEpisode((de.season - 2000) * 100 + (d - 1) / 7 + 1, ((d - 1) % 7) * 140 + minuteOfDay * 140 / 1440 + 1)
    }

    // ---- names (FR-6.6) -------------------------------------------------------------------------------------------

    /** A name that is safe as one path component: no separators or reserved characters, whitespace collapsed, no leading/trailing dot. */
    fun cleanName(raw: String, maxLen: Int = 150): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) sb.append(if (c.code < 32 || c in "/\\:*?\"<>|") ' ' else c)
        val collapsed = sb.toString().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed.trim('.', ' ').take(maxLen).trim('.', ' ')
    }

    fun seasonFolder(season: Int): String = "Season " + if (season >= 100) season.toString() else season.toString().padStart(2, '0')

    fun code(se: SeasonEpisode): String {
        val s = if (se.season >= 100) se.season.toString() else se.season.toString().padStart(2, '0')
        val e = if (se.episode >= 100) se.episode.toString() else se.episode.toString().padStart(2, '0')
        return "S${s}E$e"
    }

    /** `datetime` writes the episode with three digits, `S2601E001`, since its episodes run to 980 (FR-4.5, amended 2026-11-04). */
    fun codeFor(numbering: String, se: SeasonEpisode): String =
        if (numbering == "datetime") "S${se.season}E${se.episode.toString().padStart(3, '0')}" else code(se)

    /**
     * The file name of an episode (FR-6.6, FR-4.5): `<Series> - SxxEyy - <name>.mkv`, and for date numbering
     * `<Series> - S2026E300 - 2026-10-27 - <name>.mkv`, and for `datetime` numbering the time follows the date:
     * `<Series> - S2644E808 - 2026-11-03 18.30 - <name>.mkv`. The name is absent when the page has none.
     */
    fun fileName(series: String, se: SeasonEpisode, isoDate: String?, episodeName: String?, time: String? = null): String {
        val parts = ArrayList<String>()
        parts += cleanName(series)
        parts += codeFor(if (time != null) "datetime" else "", se)
        if (isoDate != null) parts += if (time != null) "$isoDate $time" else isoDate
        episodeName?.let { cleanName(it) }?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" - ") + ".mkv"
    }
}

/** DEB-3567 (FR-4.5) — a date, and under `datetime` the minute of the day (0–1439), as the recipe's `numbering.when` read them. */
data class PublishedAt(val date: String, val minute: Int?) {
    /** `2026-11-03 18:30`, or the date alone. */
    val stamp: String get() = if (minute == null) date else "$date ${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
}

/**
 * DEB-3567 (FR-4.5, amended 2026-11-04) — an item's date, read the way the recipe says the page prints it. `numbering.when` is data: one
 * regular expression with the named groups `year`, `month`, `day` (and `hour`, `minute`) and the page's month names. Without it, `date`
 * numbering reads the published text as before.
 */
object PageDates {
    private const val MAX_INPUT = 2_000

    /** The text [w] reads from [item]. */
    fun textOf(on: String, item: ListedItem): String? = when (on) {
        "title" -> item.title
        "description" -> item.description
        else -> item.published
    }

    fun read(n: NumberingSpec, item: ListedItem): PublishedAt? {
        val w = n.whenPattern ?: return if (n.kind == "datetime") null else Numbering.isoDate(item.published)?.let { PublishedAt(it, null) }
        val text = textOf(w.on, item)?.take(MAX_INPUT) ?: return null
        return byPattern(w, text)
    }

    /** The date of [item] under [recipe], or the plain reading of its published text. */
    fun dateOf(recipe: Recipe, item: ListedItem): String? = read(recipe.numbering, item)?.date ?: Numbering.isoDate(item.published)

    /** The text a refusal quotes back to the model: at most 80 characters. */
    fun quote(n: NumberingSpec, item: ListedItem): String =
        (textOf(n.whenPattern?.on ?: "published", item) ?: item.title ?: item.link ?: "").replace(Regex("\\s+"), " ").trim().take(80)

    fun byPattern(w: WhenSpec, text: String): PublishedAt? {
        val m = runCatching { Regex(w.regex).find(text) }.getOrNull() ?: return null
        fun g(name: String) = runCatching { m.groups[name]?.value }.getOrNull()
        val year = g("year")?.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return null
        val rawMonth = g("month")?.trim()?.trimEnd('.')?.lowercase() ?: return null
        val month = rawMonth.toIntOrNull() ?: w.months[rawMonth] ?: return null
        val day = g("day")?.toIntOrNull() ?: return null
        val date = Numbering.build(year.toString(), month.toString(), day.toString()) ?: return null
        val hourText = g("hour"); val minuteText = g("minute")
        if (hourText == null && minuteText == null) return PublishedAt(date, null)
        val hour = hourText?.toIntOrNull() ?: return null
        val minute = minuteText?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return PublishedAt(date, hour * 60 + minute)
    }
}
