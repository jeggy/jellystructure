package dev.jellystructure.autofetch

import dev.jellystructure.ServerVersion
import dev.jellystructure.ui.AF_BUG_REPORT_LINK_MAX
import dev.jellystructure.ui.afBugReportLink
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3572 (DEB-3569, DEB-2474 FR-10.4, Acceptance 19) — the bug report the server writes for a failed investigation. One sentinel word is
 * planted in every string that comes from outside the engine, each refusal the engine has is driven to `failed`, and neither the title nor the
 * body may hold the sentinel, a TMDB id, or the page's path.
 */
class InvestigationBugReportTest {
    private val h = Harness()
    private val ai = FakeAi()
    private val s = "ZQXSENT"

    @AfterTest
    fun tearDown() = h.close()

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" },
    )

    private fun run(url: String = h.dagbokUrl, tmdbId: Int? = null, pageOnly: Boolean = true): Outcome = runBlocking {
        investigator().run("inv1", url, tmdbId, pageOnly, null) {}
    }

    /** A Dagbók recipe with the sentinel in the places the model writes: a selector, a URL template, a pattern. */
    private fun answer(numbering: String = "date", pattern: String = "", examples: String = "[]", hosts: String = """["media.example.fo"]""", seg: String = "$s/") =
        """{"verdict":"series","reason":"$s the model's words","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p.${s}sel"}},"media":{"kind":"hls","manifest":"https://media.example.fo/${seg}{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"$numbering",$pattern"series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":$hosts,"audio_language":"fao"},"examples":$examples}"""

    private fun times(n: Int, text: String) = repeat(n) { ai.answer(text) }
    private val refusedThrice = Investigator.MAX_REPAIRS + 1

    /** An extra item on the page whose title, link and description carry the sentinel; [date] is its `datetime`. */
    private fun planted(date: String = "2026-10-28") {
        h.site.extraItems = """<li class="ep"><a href="/sendingar/dagbok/${s}link">$s title</a><time datetime="$date">28.10.2026</time><p>$s</p></li>"""
    }

    /** What every failed report holds, whatever failed. Returns the body. */
    private fun clean(out: Outcome, step: String? = null, refused: Int? = null): String {
        assertEquals("failed", out.view.state, out.view.reason)
        val report = assertNotNull(out.view.report, "a failed investigation has a report")
        for (text in listOf(report.title, report.body)) {
            assertFalse(s.lowercase() in text.lowercase(), "no sentinel: $text")
            assertFalse("9000001" in text, "no TMDB id: $text")
            assertFalse("/sendingar" in text || "oktober" in text || "Dagbók" in text, "no path or title: $text")
            assertFalse("selector" in text.replace("by selector", ""), text)
        }
        assertEquals("Auto-fetch: could not set up varpid.example.fo" + (step?.let { " · $it" } ?: ""), report.title)
        val body = report.body
        assertTrue(body.startsWith("Host: varpid.example.fo\nFetcher: ${if (out.view.pageOnly) "page only" else "TMDB series"}\nServer: ${ServerVersion.current.ifBlank { "dev" }}\nModel: claude-opus-5 · effort medium\nEnded: "), body)
        assertTrue("turns: ${out.view.turns} of 12 · site requests: ${out.view.requests} of 20 · cost: $" in body, body)
        assertTrue("\nSteps:\n- page: " in body && "- script: " in body, body)
        assertTrue(body.endsWith("The server's log holds the full answers under autofetch, investigation inv1."), body)
        if (refused != null) assertEquals(refused, body.lines().count { it.startsWith("- turn ") }, body)
        assertTrue(afBugReportLink(report.title, report.body).length <= AF_BUG_REPORT_LINK_MAX)
        return body
    }

    private fun reasonOf(body: String) = body.lines().first { it.startsWith("Reason: ") }.removePrefix("Reason: ")

    @Test
    fun `an item that maps to no season and episode`() {
        planted()
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        times(refusedThrice, answer("source", """"pattern":[{"on":"published","regex":"(?<season>\\d{4})"},{"on":"title","regex":"(?<episode>\\d+)\\. oktober"}],"""))
        val body = clean(run(), "pattern", refusedThrice)
        assertEquals("'<text 1>' maps to no season and episode", reasonOf(body))
        assertTrue("- turn 2: '<text 1>' maps to no season and episode" in body, body)
    }

    @Test
    fun `an example that disagrees with the engine`() {
        planted("2026-10-28")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        times(refusedThrice, answer(examples = """[{"title":"$s title","date":"2026-10-20"}]""", seg = ""))
        val body = clean(run(), "pattern", refusedThrice)
        assertTrue(reasonOf(body).startsWith("on '<text 1>' you said 2026-10-20, and the engine reads "), reasonOf(body))
    }

    @Test
    fun `two items on one number`() {
        // DEB-3695 (FR-4.2): one pair no longer fails the proof, three items on one date do.
        h.site.dagbokItems = listOf(100 to "2026-10-27", 101 to "2026-10-27", 27 to "2026-10-27", 26 to "2026-10-26", 25 to "2026-10-25", 24 to "2026-10-24")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        times(refusedThrice, answer())
        val body = clean(run(), "pattern", refusedThrice)
        assertTrue("share the date" in reasonOf(body), reasonOf(body))
    }

    @Test
    fun `a pattern that does not compile with the engine's message left out`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        times(refusedThrice, answer("source", """"pattern":[{"on":"title","regex":"$s(?<episode>[z-a])"}],"""))
        val body = clean(run(), "script", refusedThrice)
        assertEquals("pattern rule 1: the pattern is not a valid regular expression: <message left out>", reasonOf(body))
    }

    @Test
    fun `a robots txt refusal keeps the host and drops the path`() {
        h.site.robots = "User-agent: *\nDisallow: /sendingar\n"
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        val body = clean(run(), "page", 0)
        assertEquals("this site's robots.txt does not allow /…", reasonOf(body))
    }

    @Test
    fun `a non-public host the recipe records`() {
        times(refusedThrice, answer(hosts = """["${s}host"]"""))
        val body = clean(run(), "script", refusedThrice)
        assertEquals("the recipe records <host>, which is not a public host name", reasonOf(body))
        assertTrue("hosts recorded besides the page's: 1" in body, body)
    }

    @Test
    fun `a host that reads as a host name`() {
        times(refusedThrice, answer(hosts = """["${s.lowercase()}.example.net"]"""))
        val body = clean(run(), "script", refusedThrice)
        assertTrue(reasonOf(body).let { "<host>" in it || "'<text" in it }, reasonOf(body))
    }

    @Test
    fun `a yt-dlp failure whose message quotes the link`() {
        h.tools.ytdlpListingOut = YtDlpOutput(false, message = "${YtDlpErrors.MESSAGE_LEAD}Unable to download webpage: https://varpid.example.fo/sendingar/dagbok/$s ('$s')")
        ai.answer(
            """{"verdict":"series","reason":"r","recipe":{"list":{"kind":"ytdlp","fields":{"title":"","link":"","published":""}},"media":{"kind":"ytdlp"},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":[]},"examples":[]}""",
        )
        val body = clean(run(), "page", 1)
        assertEquals("yt-dlp: <message left out>", reasonOf(body))
    }

    @Test
    fun `an answer that is not in the recipe's form`() {
        times(refusedThrice, "$s this is not json")
        val body = clean(run(), "script", refusedThrice)
        assertEquals("the model's answer was not in the recipe's form", reasonOf(body))
    }

    @Test
    fun `a not_a_series answer is never shown whatever its reason quotes`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"The page 'Dagbók · 27. oktober' hosts one broadcast, $s"}""")
        val out = run()
        val body = clean(out, "series", 1)
        assertEquals(BugReport.MODEL_WORDS, reasonOf(body))
        assertTrue("Ended: not a series" in body && "- turn 2: ${BugReport.MODEL_WORDS}" in body, body)
        assertEquals("The page 'Dagbók · 27. oktober' hosts one broadcast, $s", out.view.reason, "the card still shows the model's reason; only the report leaves it out")
    }

    @Test
    fun `twelve turns without an answer - DEB-3737 ends out of steps`() {
        repeat(12) { ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""") }
        val body = clean(run(), "videos", 0)
        assertEquals("Auto-fetch stopped after 12 steps, its limit.", reasonOf(body))
        assertTrue("Ended: out of steps (limit 12) · turns: 12 of 12" in body, body)
        assertTrue("Recipe (shape only): none" in body && "\nProof:" !in body, "no recipe parsed and no proof ran: $body")
    }

    @Test
    fun `a limit stop`() {
        ai.limit = 1_000
        val body = clean(run(), "page", 0)
        assertTrue(reasonOf(body).startsWith("limit reached — the rest of this investigation could cost up to $"), reasonOf(body))
        assertTrue("Ended: limit reached" in body, body)
    }

    @Test
    fun `a model call that could not reach Anthropic leaves the client's message out`() {
        ai.script += { throw RuntimeException("connect to $s.example.net:443 failed: $s") }
        val body = clean(run(), "page", 0)
        assertEquals("Anthropic could not be reached: <message left out>", reasonOf(body))
    }

    @Test
    fun `a tmdb fetcher says TMDB series and sends no id`() {
        times(refusedThrice, "$s this is not json")
        val body = clean(run("https://varpid.example.fo/sendingar/royndin", 9000001, false), "script", refusedThrice)
        assertTrue("Fetcher: TMDB series\n" in body, body)
    }

    @Test
    fun `the last recipe's shape and the last proof's counts are in the body and no selector path pattern or template`() {
        planted()
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        times(refusedThrice, answer("source", """"pattern":[{"on":"published","regex":"(?<season>\\d{4})"},{"on":"title","regex":"(?<episode>\\d+)\\. oktober"}],"""))
        val body = clean(run(), "pattern", refusedThrice)
        assertTrue("""Recipe (shape only):
- list: html · fields: title, link, published, description · next page: no
- media: hls · item_page by pattern
- numbering: source · pattern rules: 2
- series block: yes
- audio: fao · subtitles: none
- hosts recorded besides the page's: 1
""" in body, body)
        assertTrue("Proof: items listed: 13 · items numbered: 0 · TMDB episodes checked: 0 · TMDB episodes agreeing: 0\n" in body, body)
        assertTrue("- page: done · " in body && "- pattern: fail" in body, body)
    }

    @Test
    fun `an investigation that fails before the model is asked has a report with no model`() {
        ai.on = false
        val out = run()
        val body = assertNotNull(out.view.report).body
        assertTrue("Model: none\n" in body && "Reason: AI is switched off in Settings ▸ AI\n" in body, body)
    }

    @Test
    fun `a review has no report`() {
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok/27"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        ai.answer("""{"verdict":"series","reason":"r","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":["media.example.fo"],"audio_language":"fao"},"examples":[]}""")
        val out = run()
        assertEquals("review", out.view.state, out.view.reason)
        assertNull(out.view.report)
    }

    // ---- the redaction, step by step -----------------------------------------------------------------------------------------------

    private fun redact(vararg reasons: String): List<String> = BugReport.Redactor("varpid.example.fo").let { r -> reasons.map { r.reason(it) } }

    @Test
    fun `a quoted span is numbered by first appearance across the body so one title reads the same everywhere`() {
        val (a, b, c) = redact("'One' and 'Two' map", "on 'Two' you said 3", "'One' again")
        assertEquals("'<text 1>' and '<text 2>' map", a)
        assertEquals("on '<text 2>' you said 3", b)
        assertEquals("'<text 1>' again", c)
    }

    @Test
    fun `an apostrophe inside a word opens nothing and a title's own apostrophe cannot end a span early`() {
        assertEquals("this site's robots.txt, TMDB's '<text 1>'", redact("this site's robots.txt, TMDB's 'Dagbók'").single())
        assertEquals("on '<text 1>' you said 3", redact("on 'Don't Look' you said 3").single())
        assertEquals("on '<text 1>' you said 3", redact("on 'The Dads' Hour' you said 3").single(), "a closing quote with no span open extends the one before it")
    }

    @Test
    fun `a url keeps its host a path becomes a slash and an ellipsis a host that is not the page's becomes host`() {
        assertEquals("<host>/… and varpid.example.fo/…", redact("https://cdn.example.net/a/b?c=d and https://varpid.example.fo/sendingar/x").single())
        assertEquals("this site's robots.txt does not allow /…", redact("this site's robots.txt does not allow /sendingar/dagbok?x=1").single())
        assertEquals("the recipe records <host>, which is not a public host name", redact("the recipe records cdn.example.net, which is not a public host name").single())
        assertEquals("the recipe records <host>, which is not a public host name", redact("the recipe records localhost, which is not a public host name").single())
        assertEquals("the recipe records <host>, which is not a public host name", redact("the recipe records 192.0.2.10, which is not a public host name").single())
        assertEquals("the recipe records varpid.example.fo, which", redact("the recipe records varpid.example.fo, which").single(), "the page's own host stays")
        assertEquals("up to \$0.52, in 3.1s, and/or S01E04.", redact("up to \$0.52, in 3.1s, and/or S01E04.").single(), "numbers, units and and/or are not hosts or paths")
    }

    @Test
    fun `every program's message after its lead is left out the engine's own words before it stay`() {
        for (lead in BugReport.FOREIGN_LEADS) {
            assertEquals("pattern rule 1: ${lead}<message left out>", redact("pattern rule 1: ${lead}Unclosed group near index 5 '$s' https://x.example.net/$s").single())
        }
    }

    @Test
    fun `a reason is cut at 300 characters`() {
        val cut = redact("a".repeat(600)).single()
        assertEquals(300, cut.length); assertTrue(cut.endsWith("…"))
    }

    // ---- the length ------------------------------------------------------------------------------------------------------------------

    private fun input(refusals: Int, proof: Boolean = true, recipe: Boolean = true) = ReportInput(
        id = "inv1", host = "varpid.example.fo", pageOnly = true, ended = "failed", reason = "'x' maps to no season and episode", reasonIsModelWords = false,
        model = "claude-opus-5", effort = "medium", turns = 12, requests = 20, spentMicroUsd = 120_000,
        steps = Investigator.STEP_LABELS.map { (k, l) -> StepView(k, l, if (k == "pattern") "fail" else "done", ms = 3_100) },
        recipe = if (recipe) MRecipe() else null,
        proof = if (proof) ProofResult(false, "x") else null,
        refusals = (1..refusals).map { Refusal(it, "'a title with some words $it' maps to no season and episode, and then ${"it goes on and on ".repeat(18)}", false) },
    )

    @Test
    fun `a body with more refused turns than fit gives a link of at most 8000 characters that drops the oldest turns and keeps items 1 to 7`() {
        val full = BugReport.build(input(2))
        assertTrue("- turn 1: " in full.body && "- turn 2: " in full.body && "earlier refused turn" !in full.body)
        val report = BugReport.build(input(60))
        assertTrue(afBugReportLink(report.title, report.body).length <= AF_BUG_REPORT_LINK_MAX)
        assertTrue(afBugReportLink(report.title, report.body).startsWith("https://github.com/jeggy/jellystructure/issues/new?title="))
        assertFalse("template=" in afBugReportLink(report.title, report.body))
        val turns = report.body.lines().filter { it.startsWith("- turn ") }
        val left = Regex("""\((\d+) earlier refused turns left out\)""").find(report.body)?.groupValues?.get(1)?.toInt()
        assertNotNull(left, report.body)
        assertEquals(60, left + turns.size, "the note counts what was dropped")
        assertTrue(turns.first().startsWith("- turn ${left + 1}: ") && turns.last().startsWith("- turn 60: "), "the oldest went first")
        assertTrue(report.body.lines().let { it.indexOf("(${left} earlier refused turns left out)") } == report.body.lines().size - 2, "the note ends the turns, ahead of the closing line")
        for (item in listOf("Host: ", "Fetcher: ", "Server: ", "Model: ", "Ended: ", "Reason: ", "Steps:")) assertTrue(item in report.body, item)
        assertTrue("Recipe (shape only):" in report.body && "Proof: " in report.body, "Proof and Recipe stay while the turns can be dropped to fit")
    }

    @Test
    fun `when dropping every turn is not enough the Proof line goes first then the Recipe lines and never the title or items 1 to 7`() {
        // Items 1 to 7 grow with the steps; find where Proof and Recipe stop fitting.
        val states = (1..200).map { n ->
            val steps = (1..n).map { StepView("page", "x", "done", ms = 1) }
            val r = BugReport.build(ReportInput("inv1", "varpid.example.fo", true, "failed", "r", false, "claude-opus-5", "medium", 12, 20, 0, steps, MRecipe(), ProofResult(false, "x"), emptyList()))
            val link = afBugReportLink(r.title, r.body).length
            Triple("Proof: " in r.body, "Recipe (shape only):" in r.body, link) to r
        }
        val order = states.map { (flags, _) -> flags.first to flags.second }
        assertEquals(order.sortedWith(compareBy({ !it.first }, { !it.second })).let { sortedAsExpected -> sortedAsExpected }, order.sortedWith(compareBy({ !it.first }, { !it.second })))
        assertTrue(order.first() == (true to true) && (false to true) in order && (false to false) in order, "all three states are reached: ${order.distinct()}")
        assertTrue(order.indexOf(false to true) < order.indexOf(false to false), "Proof goes before Recipe")
        assertTrue(order.indexOf(false to true) <= order.indexOfFirst { !it.second }, "Recipe is not dropped while Proof is still there")
        for ((flags, r) in states) {
            if (flags.third <= AF_BUG_REPORT_LINK_MAX) continue
            assertFalse(flags.first || flags.second, "a link over the limit has dropped everything it may drop")
            assertTrue("Steps:" in r.body && "Reason: " in r.body && r.title.startsWith("Auto-fetch: could not set up varpid.example.fo"), "items 1 to 7 are never cut")
        }
    }
}
