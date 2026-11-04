package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2478 (DEB-2474, FR-5, FR-10.1, FR-10.4, FR-10.6, FR-15.1) — the New auto-fetcher card's markup and words. Stand-in corpus only. */
class AutofetchInvestigationHtmlTest {
    private val clock = object : AfClock {
        override fun ago(sec: Long) = "3h ago"
        override fun at(sec: Long) = "04:00"
        override fun until(sec: Long) = "5h 12m"
        override fun runtime(sec: Int) = "44:12"
    }

    private val steps = listOf("page" to "Read the page", "videos" to "Find how the videos are served", "series" to "Confirm this is a series", "pattern" to "Map episode titles to seasons and episodes", "script" to "Write the recipe")

    private fun inv(state: String, vararg st: String, reason: String? = null, review: AfReview? = null, notASeries: Boolean = false, limit: Boolean = false, report: AfReport? = null) = AfInvestigation(
        id = "i1", state = state, url = "https://varpid.example.fo/sendingar/royndin", host = "varpid.example.fo", tmdbId = 9000001, notASeries = notASeries, limitReached = limit,
        steps = steps.withIndex().map { (i, s) -> AfStep(s.first, s.second, st.getOrElse(i) { "pending" }, if (st.getOrElse(i) { "pending" } == "done") "fx ${s.first}" else "", if (st.getOrElse(i) { "" } == "done") 2100L else null) },
        reason = reason, review = review, report = report,
    )

    private fun review(pageOnly: Boolean = false, proven: Boolean = true, replaces: String? = null) = AfReview(
        name = if (pageOnly) "Dagbók" else "Royndin", tmdbId = if (pageOnly) null else 9000001, pageOnly = pageOnly, source = "varpid.example.fo · HLS manifest · best variant 1080p",
        found = 5, missing = 4, have = 1, notOnTmdb = 1, pattern = "Yearly seasons", numbering = if (pageOnly) "date" else "source",
        proof = if (pageOnly) "12 items, each with its own date" else if (proven) "pattern agrees with TMDB on 3 of 3 episodes TMDB has" else "pattern not proven — TMDB has 1 of these episodes, and at least two must agree.",
        proven = proven,
        rows = listOf(
            AfReviewRow("Royndin 2026 · 3. partur", "S2026E03", "2026-03-10", notOnTmdb = true), AfReviewRow("Royndin 2026 · 2. partur", "S2026E02"),
            AfReviewRow("Royndin 2026 · 1. partur", "S2026E01", have = true), AfReviewRow("Royndin 2025 · 2. partur", "S2025E02"), AfReviewRow("Royndin 2025 · 1. partur", "S2025E01"),
        ),
        libraries = listOf(AfLibrary("/tv", "TV")), libraryPath = "/tv", retention = if (pageOnly) AfRetention("newest", 12, true) else AfRetention(), replaces = replaces,
    )

    private fun draft(r: AfReview) = AfReviewDraft("i1", r.name, r.libraryPath, "daily@04:00", r.retention)

    private fun card(i: AfInvestigation?, gate: AfGate = AfGate(true), form: AfForm = AfForm("https://varpid.example.fo/sendingar/royndin", "9000001"), d: AfReviewDraft? = i?.review?.let { draft(it) }) =
        afNewCard(AfOverview(true, emptyList(), emptyList(), emptyList(), gate, i), form, d)

    @Test
    fun `the form has the page url the tmdb id the tick and Investigate`() {
        val h = card(null)
        assertTrue("""value="https://varpid.example.fo/sendingar/royndin"""" in h && """value="9000001"""" in h)
        assertTrue("This programme is not on TMDB" in h && "data-af-pageonly" in h)
        assertTrue("""class="btn primary" data-af-go""" in h && "Must resolve to a TV series on TMDB." in h)
        assertTrue("series only" in h)
    }

    @Test
    fun `a ticked page-only form empties and disables the id and says nothing asks TMDB`() {
        val h = card(null, form = AfForm("https://varpid.example.fo/sendingar/dagbok", "123", pageOnly = true))
        assertTrue("""data-af-tmdb value=""""" in h, h)
        assertTrue("disabled" in h.substringAfter("data-af-tmdb").substringBefore(">"))
        assertTrue("The episodes keep the page's own titles and descriptions, and nothing asks TMDB." in h)
        assertTrue("""af-tick on""" in h)
    }

    @Test
    fun `Investigate is shown disabled with the server's reason`() {
        val h = card(null, AfGate(false, "This month's Auto-fetch limit is spent (\$5.00 of \$5.00)"))
        assertTrue("btn primary af-dis" in h && "data-af-go" in h)
        assertTrue("This month's Auto-fetch limit is spent (\$5.00 of \$5.00)" in h, h)
    }

    @Test
    fun `while it runs the form is locked Stop replaces Investigate and the running step is marked now`() {
        val h = card(inv("running", "done", "run"), AfGate(false, "An investigation is already running"))
        assertTrue("data-af-stop" in h && "data-af-go" !in h)
        assertTrue("""class="af-step done"""" in h && """class="af-step run"""" in h && "— now" in h)
        assertEquals(1, Regex("— now").findAll(h).count())
        assertTrue(" disabled" in h.substringAfter("data-af-url").substringBefore(">"))
        assertTrue("2.1s" in h && "fx page" in h)
        assertTrue("Nothing has been written yet." in h)
        assertFalse("An investigation is already running" in h, "the form shows no reason while it is the one running")
    }

    private val report = AfReport("Auto-fetch: could not set up varpid.example.fo · pattern", "Host: varpid.example.fo\nFetcher: TMDB series\nReason: '<text 1>' maps to no season and episode")

    @Test
    fun `the failed state names the failing check and links the report the server wrote - DEB-3572`() {
        val h = card(inv("failed", "done", "done", "fail", reason = "1 video found, no recurring title", notASeries = true, report = report))
        assertTrue("""class="af-step fail"""" in h && "Not a series" in h && "reason: 1 video found, no recurring title" in h)
        assertTrue("nothing was written" in h)
        val url = assertNotNull(afBugReportUrl(inv("failed", "done", "done", "fail", reason = "x", report = report)))
        assertEquals("https://github.com/jeggy/jellystructure/issues/new?title=${afUrlEncode(report.title)}&body=${afUrlEncode(report.body)}", url)
        assertFalse("template=" in url, "no such issue template exists: $url")
        assertFalse("9000001" in url, "the TMDB id is no longer sent: $url")
        assertTrue("File a bug report on GitHub" in h && "Never a title, the page's content or the URL's path." in h)
    }

    @Test
    fun `the link carries what the server wrote and adds nothing so the model's reason is never put there by the admin - DEB-3572`() {
        val quote = "Eitt sending quoted from the page"
        val url = assertNotNull(afBugReportUrl(inv("failed", "done", "done", "fail", reason = "This page hosts one broadcast ('$quote')", notASeries = true, report = report)))
        assertTrue("Eitt" !in url && "quoted" !in url && "hosts" !in url, "the card's reason is not in the link: $url")
    }

    @Test
    fun `a failed investigation with no report draws no link and a limit stop and a stopped investigation say so - DEB-3572`() {
        assertNull(afBugReportUrl(inv("failed", "done", reason = "x")))
        assertFalse("File a bug report" in card(inv("failed", "done", reason = "the investigation failed unexpectedly; nothing was written")))
        val limit = card(inv("failed", "done", reason = "limit reached — x", limit = true, report = report))
        assertTrue("Stopped at this month's limit" in limit && "File a bug report" in limit)
        val stopped = card(inv("stopped", "done", reason = "stopped; nothing was written", report = report))
        assertTrue("What it found was discarded" in stopped && "File a bug report" !in stopped)
    }

    @Test
    fun `the review shows the series the proof and the mapping with three rows and a show all`() {
        val r = review()
        val h = card(inv("review", "done", "done", "done", "done", "done", review = r))
        assertTrue("<b>Royndin</b> · TMDB 9000001 · <span class=\"af-conf\">✓ confirmed series</span>" in h)
        assertTrue("<b>5 episodes</b> — 4 missing from the library, 1 already there · <b>1 not on TMDB yet</b>" in h)
        assertTrue("✓ proven" in h && "pattern agrees with TMDB on 3 of 3 episodes TMDB has" in h)
        assertEquals(3, Regex("""<td class="arr">""").findAll(h).count(), "three rows until show all")
        assertTrue("2 more · " in h && "show all 5 mappings" in h && "S2026E03" in h)
        assertTrue("have · skipped" in h && "not on TMDB" in h)
        assertTrue("Save auto-fetcher" in h && "data-rv-discard" in h && "Run the first time" in h)
        assertTrue("Nothing is deleted when you save" in h)
        val all = afNewCard(AfOverview(true, emptyList(), emptyList(), emptyList(), AfGate(true), inv("review", "done", review = r)), AfForm(), draft(r).copy(showAll = true))
        assertEquals(5, Regex("""<td class="arr">""").findAll(all).count()); assertTrue("show fewer" in all)
    }

    @Test
    fun `an unproven pattern says what that means`() {
        val h = card(inv("review", "done", review = review(proven = false)))
        assertTrue("not proven" in h && "af-proof unproven" in h && "never placed on a guess" in h)
    }

    @Test
    fun `a page-only review shows the page's own name the date and the page-only proof`() {
        val r = review(pageOnly = true)
        val h = card(inv("review", "done", review = r), form = AfForm("https://varpid.example.fo/sendingar/dagbok", "", pageOnly = true))
        assertTrue("<b>Dagbók</b> · page only · not on TMDB" in h && "confirmed series" !in h)
        assertTrue("12 items, each with its own date" in h && "✓ checked" in h)
        assertTrue("S2026E03 · 2026-03-10" in h, "the code and the date")
        assertTrue("""data-af-keep="newest"""" in h && """class="on"><input""" !in h)
    }

    @Test
    fun `the review's Keep hint comes from the server's own counts`() {
        val r = review(pageOnly = true)
        val d = draft(r).copy(preview = AfPreview(0, 12, 12))
        val h = afNewCard(AfOverview(true, emptyList(), emptyList(), emptyList(), AfGate(true), inv("review", "done", review = r)), AfForm(), d)
        assertTrue("Keeps 12 of the 12 on the page. Older ones are deleted from the library." in h)
    }

    @Test
    fun `re-investigating a fetcher reviews a new recipe and keeps its name schedule and Keep`() {
        val h = card(inv("review", "done", review = review(replaces = "f1")))
        assertTrue("Save the new recipe" in h && "the old one runs until you do" in h)
        assertFalse("data-rv-name" in h)
    }

    @Test
    fun `the open row offers Re-investigate page and shows the reason when it cannot start`() {
        val f = AfFetcher(id = "f1", name = "Royndin", site = "varpid.example.fo", tmdbId = 9000001, schedule = "daily@04:00")
        val on = afOpenRow(f, AfDraft(f.schedule, f.retention), clock, false, AfGate(true))
        assertTrue("data-af-reinv" in on && "Re-investigate page" in on && "af-dis\" data-af-reinv" !in on)
        val off = afOpenRow(f, AfDraft(f.schedule, f.retention), clock, false, AfGate(false, "AI is switched off in Settings ▸ AI"))
        assertTrue("""af-dis" data-af-reinv""" in off && "AI is switched off in Settings ▸ AI" in off)
    }

    @Test
    fun `the whole panel carries the new card between the queue and the list and none while Auto-fetch is off`() {
        val on = afPanelHtml(AfOverview(true, emptyList(), emptyList(), emptyList(), AfGate(true), null), null, null, emptyMap(), emptySet(), emptyMap(), 50, clock)
        assertTrue("New auto-fetcher" in on && on.indexOf("New auto-fetcher") < on.indexOf("Auto-fetchers"))
        val off = afPanelHtml(AfOverview(false), null, null, emptyMap(), emptySet(), emptyMap(), 50, clock)
        assertFalse("New auto-fetcher" in off)
    }
    // ---- DEB-2479 (FR-4.3): a mapped review ------------------------------------------------------------------------------------

    private fun mappedReview() = review().copy(
        numbering = "mapped", pattern = "Mapped", proven = false,
        proof = "mapped by the model's proposal, not by a pattern — 2 of 3 items have a proposed number. Nothing is stored that you do not tick; every other item waits for your number.",
        found = 3, missing = 3, have = 0, notOnTmdb = 0,
        rows = listOf(
            AfReviewRow("Royndin 2026 · 1. partur", "S01E01", itemId = "R1", tmdbName = "Fyrsti", why = "part 1 is TMDB's 1"),
            AfReviewRow("Royndin 2026 · 2. partur", "S01E02", itemId = "R2", tmdbName = "Annar", why = "part 2 is TMDB's 2"),
            AfReviewRow("Royndin 2026 · 3. partur", "", itemId = "R3"),
        ),
    )

    @Test
    fun `a mapped review has an accept tick per proposed row all unticked and a row the model left out waits`() {
        val r = mappedReview()
        val h = card(inv("review", "done", "done", "done", "done", "done", review = r), d = draft(r).copy(showAll = true))
        assertEquals(2, Regex("""data-rv-tick=""").findAll(h).count(), "a row with no proposal has no tick")
        assertFalse("af-tick on" in h, "all unticked")
        assertTrue("Nothing ticked" in h && "data-rv-tickall" in h && "Tick all" in h)
        assertTrue("waiting for a number" in h && "Fyrsti" in h && "part 1 is TMDB&#39;s 1".replace("&#39;", "'") in h)
        assertFalse("no-tick" in h, "the tick column is drawn")
        assertTrue("""class="af-proof"><span class="says">mapped by the model's proposal""" in h && "not proven" !in h, "a proposal is approved by the tick, not proven")
        assertTrue("Mapped." in h && ">Numbering<" in h)
        assertTrue("The 3 you leave unticked are not refused" in h)
    }

    @Test
    fun `ticking a row marks it and counts the kept ones`() {
        val r = mappedReview()
        val h = card(inv("review", "done", "done", "done", "done", "done", review = r), d = draft(r).copy(showAll = true, accept = setOf("R2")))
        assertEquals(1, Regex("""af-tick on""").findAll(h).count())
        assertTrue("""<tr class="keep">""" in h && "<b>1 of 3 kept</b>" in h)
        assertTrue("The 2 you leave unticked" in h)
        val all = card(inv("review", "done", "done", "done", "done", "done", review = r), d = draft(r).copy(showAll = true, accept = setOf("R1", "R2")))
        assertTrue("Untick all" in all)
    }

    @Test
    fun `a source review keeps its tickless table`() {
        val h = card(inv("review", "done", "done", "done", "done", "done", review = review()))
        assertTrue("no-tick" in h && "data-rv-tick" !in h)
    }
}
