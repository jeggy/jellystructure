package dev.jellystructure.autofetch

import dev.jellystructure.ServerVersion
import dev.jellystructure.ai.AiPricing
import dev.jellystructure.ui.AF_BUG_REPORT_LINK_MAX
import dev.jellystructure.ui.afBugReportLink

/**
 * DEB-3572 (DEB-3569, DEB-2474 FR-10.4) — the bug report the server writes for a failed investigation, and the one redaction every reason in
 * it goes through. The repository is public, so what the report may carry is set by rule and never by judgement: the host, the server's
 * version, the model, counts and times, the *shape* of the last recipe, and the engine's own reasons with everything outside the engine
 * taken out. Never a title (an item's, the programme's, TMDB's), a link, the URL's path, page text, the model's words, a selector, a JSON
 * path, a pattern or a URL template. The TMDB id is not sent either: one lookup turns it into the title.
 */

/** One answer the engine refused (FR-5.6), in the turn it came in. [modelWords]: the reason is the model's own (`not_a_series`). */
internal class Refusal(val turn: Int, val reason: String, val modelWords: Boolean)

/** What one investigation kept for its report. */
internal class ReportInput(
    val id: String,
    val host: String,
    val pageOnly: Boolean,
    /** `not a series` · `limit reached` · `failed`. */
    val ended: String,
    val reason: String,
    /** The reason is the model's own words (a `not_a_series` answer): never shown. */
    val reasonIsModelWords: Boolean,
    val model: String?,
    val effort: String,
    val turns: Int,
    val requests: Int,
    val spentMicroUsd: Long,
    val steps: List<StepView>,
    val recipe: MRecipe?,
    val proof: ProofResult?,
    val refusals: List<Refusal>,
)

internal object BugReport {
    const val MODEL_WORDS = "the model answered not_a_series (its reason quotes the page and is left out)"
    const val MESSAGE_LEFT_OUT = "<message left out>"
    const val REASON_CHARS = 300

    /**
     * FR-10.4 step 6 — where another program's message starts in a reason. A new reason that carries a program's text adds its lead here, and
     * is built from the same constant, so this never has to guess at a colon.
     */
    val FOREIGN_LEADS = listOf(
        PatternSafety.INVALID_REGEX_LEAD,
        REQUEST_FAILED_LEAD,
        Investigator.UNREACHABLE_LEAD,
        YtDlpErrors.MESSAGE_LEAD,
    )

    fun build(i: ReportInput): ReportView {
        val redact = Redactor(i.host)
        val failedStep = i.steps.firstOrNull { it.state == "fail" }?.key
        val title = "Auto-fetch: could not set up ${i.host}" + (failedStep?.let { " · $it" } ?: "")

        val head = buildString {
            append("Host: ${i.host}\n")
            append("Fetcher: ${if (i.pageOnly) "page only" else "TMDB series"}\n")
            append("Server: ${ServerVersion.current.ifBlank { "dev" }}\n")
            append("Model: ${i.model?.let { m -> m + i.effort.takeIf { it.isNotBlank() }?.let { e -> " · effort $e" }.orEmpty() } ?: "none"}\n")
            append("Ended: ${i.ended} · turns: ${i.turns} of ${Investigator.MAX_TURNS} · site requests: ${i.requests} of ${Investigator.MAX_TOOL_REQUESTS} · cost: ${AiPricing.usd(i.spentMicroUsd)}\n")
            append("Reason: ${if (i.reasonIsModelWords) MODEL_WORDS else redact.reason(i.reason)}\n")
            append("Steps:\n")
            i.steps.forEach { s -> append("- ${s.key}: ${s.state}${s.ms?.let { " · ${seconds(it)}" }.orEmpty()}\n") }
        }
        val recipe = "Recipe (shape only):" + (i.recipe?.let { "\n" + shape(it, i.host) } ?: " none") + "\n"
        val proof = i.proof?.let {
            "Proof: items listed: ${it.items.size} · items numbered: ${it.numbered.size} · TMDB episodes checked: ${it.tmdbChecked} · TMDB episodes agreeing: ${it.agreements}\n"
        }.orEmpty()
        // Redacted once, in turn order, so a title reads the same in every line (step 2 numbers by first appearance in the body).
        val turnLines = i.refusals.map { "- turn ${it.turn}: ${if (it.modelWords) MODEL_WORDS else redact.reason(it.reason)}" }
        val closing = "Titles, the page's content, the model's answers and the URL's path are left out by rule. The server's log holds the full answers under autofetch, investigation ${i.id}."

        fun body(left: Int, withProof: Boolean, withRecipe: Boolean): String = buildString {
            append(head)
            if (withRecipe) append(recipe)
            if (withProof) append(proof)
            if (turnLines.isNotEmpty()) {
                append("Refused turns:\n")
                turnLines.drop(left).forEach { append(it).append('\n') }
                if (left > 0) append("(").append(left).append(" earlier refused turn").append(if (left == 1) "" else "s").append(" left out)\n")
            }
            append(closing)
        }
        fun fits(b: String) = afBugReportLink(title, b).length <= AF_BUG_REPORT_LINK_MAX

        // The refused turns go first, oldest first; then the Proof line, then the Recipe lines. The title and items 1 to 7 are never cut.
        var left = 0
        var b = body(left, withProof = true, withRecipe = true)
        while (!fits(b) && left < turnLines.size) { left++; b = body(left, true, true) }
        if (!fits(b)) b = body(left, withProof = false, withRecipe = true)
        if (!fits(b)) b = body(left, withProof = false, withRecipe = false)
        return ReportView(title, b)
    }

    private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}s"

    // ---- the last recipe's shape: kinds from a fixed set and counts, never a selector, a path, a pattern or a template ----------------

    private val FIELD_NAMES = listOf("title", "link", "published", "description", "id", "season", "episode", "duration", "image")
    private val LIST_KINDS = setOf("html", "json", "ytdlp")
    private val MEDIA_KINDS = setOf("hls", "dash", "ytdlp")
    private val NUMBERING_KINDS = setOf("source", "date", "datetime", "mapped")
    private val LANGUAGE = Regex("^[a-z]{3}$")

    private fun known(v: String, set: Set<String>) = if (v in set) v else "unknown"

    private fun shape(r: MRecipe, pageHost: String): String {
        val f = r.list.fields
        val set = FIELD_NAMES.filter { n ->
            when (n) {
                "title" -> f.title; "link" -> f.link; "published" -> f.published; "description" -> f.description; "id" -> f.id
                "season" -> f.season; "episode" -> f.episode; "duration" -> f.duration; else -> f.image
            }.isNotBlank()
        }
        val ip = r.media.itemPage
        val itemPage = when {
            ip == null -> null
            ip.pattern.isNotBlank() -> "item_page by pattern"
            ip.selector.isNotBlank() -> "item_page by selector"
            else -> null
        }
        fun lang(v: String) = if (v.isBlank()) "none" else if (LANGUAGE.matches(v)) v else "invalid"
        val others = r.hosts.map { Urls.host("https://" + it.trim()) ?: it.trim().lowercase() }.filter { it.isNotEmpty() && !it.equals(pageHost, ignoreCase = true) }.toSet().size
        return buildString {
            append("- list: ${known(r.list.kind, LIST_KINDS)} · fields: ${set.joinToString(", ").ifEmpty { "none" }} · next page: ${if (r.list.next.isNotBlank()) "yes" else "no"}\n")
            append("- media: ${known(r.media.kind, MEDIA_KINDS)}${itemPage?.let { " · $it" }.orEmpty()}\n")
            append("- numbering: ${known(r.numbering, NUMBERING_KINDS)} · pattern rules: ${r.pattern.size}\n")
            append("- series block: ${if (r.series != null) "yes" else "no"}\n")
            append("- audio: ${lang(r.audioLanguage)} · subtitles: ${lang(r.subtitleLanguage)}\n")
            append("- hosts recorded besides the page's: $others")
        }
    }

    /**
     * FR-10.4 — the redaction, one instance for a whole body so the numbering of quoted spans runs across it. The six steps, in the order the
     * spec gives them, with one difference in when step 6 runs: another program's message is cut off first, so the quoted spans inside it do
     * not take numbers the body never shows. The text that is left is the same as if it ran last.
     */
    class Redactor(private val pageHost: String) {
        private val spans = LinkedHashMap<String, Int>()

        fun reason(raw: String): String {
            var s = cutForeign(raw)
            s = quotes(s)
            s = urls(s)
            s = paths(s)
            s = slots(s)
            s = hosts(s)
            return if (s.length > REASON_CHARS) s.take(REASON_CHARS - 1) + "…" else s
        }

        /** Step 6 — everything after the first lead that marks another program's message. */
        private fun cutForeign(s: String): String {
            var at = -1; var end = 0
            for (lead in FOREIGN_LEADS) {
                val k = s.indexOf(lead)
                if (k >= 0 && (at < 0 || k < at)) { at = k; end = k + lead.length }
            }
            return if (at < 0) s else s.substring(0, end) + MESSAGE_LEFT_OUT
        }

        /**
         * Step 2 — every span in single quotes becomes `'<text n>'`. An apostrophe inside a word (`site's`, `Don't`) neither opens nor closes a
         * span. A closing quote with no span open (a title that holds `Dads' favourites`) extends the span before it, so the tail of a title is
         * never left in the open.
         */
        private fun quotes(s: String): String {
            val ranges = ArrayList<IntArray>()   // [open, close] indexes of the quote characters
            var i = 0
            while (i < s.length) {
                if (s[i] != '\'') { i++; continue }
                val opens = i == 0 || !s[i - 1].isLetterOrDigit()
                val closes = i + 1 >= s.length || !s[i + 1].isLetterOrDigit()
                if (opens) {
                    var j = i + 1; var close = -1
                    while (j < s.length) { if (s[j] == '\'' && (j + 1 >= s.length || !s[j + 1].isLetterOrDigit())) { close = j; break }; j++ }
                    if (close >= 0) { ranges += intArrayOf(i, close); i = close + 1; continue }
                }
                if (closes && !opens && ranges.isNotEmpty()) { ranges.last()[1] = i }
                i++
            }
            if (ranges.isEmpty()) return s
            val out = StringBuilder(); var from = 0
            for (r in ranges) {
                out.append(s, from, r[0])
                val n = spans.getOrPut(s.substring(r[0] + 1, r[1])) { spans.size + 1 }
                out.append("'<text ").append(n).append(">'")
                from = r[1] + 1
            }
            return out.append(s, from, s.length).toString()
        }

        private val url = Regex("""https?://[^\s'"<>)]+""", RegexOption.IGNORE_CASE)

        /** Step 3 — a URL keeps its host and nothing else. */
        private fun urls(s: String): String = url.replace(s) { m ->
            val rest = m.value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            rest.substringAfterLast('@').substringBefore(':') + "/…"
        }

        private val path = Regex("""(^|[\s(\[,;])/\S*""")

        /** Step 4 — every other word that starts with `/`. */
        private fun paths(s: String): String = path.replace(s) { it.groupValues[1] + "/…" }

        private val slot = Regex("""\b(the recipe records|names|is on) (\S+?), which\b""")

        /**
         * The engine's three sentences that name a host the model or the page gave, whether or not it reads as a host name (`localhost`, an
         * address): the word in the slot is a host by position, so step 5 does not have to recognise it.
         */
        private fun slots(s: String): String = slot.replace(s) { m ->
            val h = m.groupValues[2]
            "${m.groupValues[1]} ${if (h.equals(pageHost, ignoreCase = true)) h else "<host>"}, which"
        }

        private val host = Regex("""(^|[^A-Za-z0-9.-])((?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,})(?![A-Za-z0-9-])""")

        /** Step 5 — a word that reads as a host name and is not the page's. `robots.txt` is the engine's own file name, not a host. */
        private fun hosts(s: String): String = host.replace(s) { m ->
            val w = m.groupValues[2]
            if (w.equals(pageHost, ignoreCase = true) || w.equals("robots.txt", ignoreCase = true)) m.value else m.groupValues[1] + "<host>"
        }
    }
}
