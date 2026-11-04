package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2477 (DEB-2474, FR-1, FR-2.5, FR-9, FR-10, FR-11, FR-16) — the Fetchers tab's markup and words. The admin has no test target of its
 * own, so the markup is built in `commonMain` and read here. Stand-in corpus only.
 */
class AutofetchHtmlTest {
    private val clock = object : AfClock {
        override fun ago(sec: Long) = "3h ago"
        override fun at(sec: Long) = "04:00"
        override fun until(sec: Long) = "5h 12m"
        override fun runtime(sec: Int) = "44:12"
    }

    private fun fetcher(
        id: String = "f1", name: String = "Royndin", site: String = "varpid.example.fo", retention: AfRetention = AfRetention(),
        schedule: String = "daily@04:00", enabled: Boolean = true, state: String = "ok", last: AfRun? = AfRun(1, 2, "manual", "ok", "no new episodes"),
        tmdbId: Int? = 9000001, siteStopped: Boolean = false, reason: String? = null,
    ) = AfFetcher(
        id = id, name = name, site = site, tmdbId = tmdbId, pageOnly = tmdbId == null, schedule = schedule, enabled = enabled, retention = retention,
        state = state, siteStopped = siteStopped, siteStoppedReason = reason, siteStoppedAt = if (siteStopped) 9 else null, lastRun = last, runs = listOfNotNull(last),
        nextRunAt = 100, placed = 3, pattern = "By date", quality = "highest HLS",
        recipe = listOf(AfRecipeLine("#", "recipe v1 · $name · read-only here"), AfRecipeLine("source", "https://$site/sendingar/royndin", "hosts fixed at save")),
    )

    private fun overview(vararg f: AfFetcher, enabled: Boolean = true, queue: List<AfQueueEntry> = emptyList(), sites: List<AfSite> = emptyList()) =
        AfOverview(enabled, f.toList(), sites, queue)

    private fun panel(o: AfOverview, openF: String? = null, openQ: Long? = null, drafts: Map<String, AfDraft> = emptyMap()) =
        afPanelHtml(o, openF, openQ, drafts, emptySet(), emptyMap(), 50, clock)

    // ---- words -------------------------------------------------------------------------------------------------------------

    @Test
    fun `schedules read as the presets and round trip through their parts`() {
        assertEquals("Every 6 h", afScheduleWords("every6h"))
        assertEquals("Daily · 04:00", afScheduleWords("daily@04:00"))
        assertEquals("Weekly · Mon 04:30", afScheduleWords("weekly@1@04:30"))
        for (s in listOf("every6h", "daily@04:00", "weekly@5@23:15")) {
            val p = afScheduleParts(s)
            assertEquals(s, afScheduleOf(p.kind, p.day, p.time))
        }
    }

    @Test
    fun `Keep says what it costs from the server's counts and nothing else`() {
        assertEquals("keeps newest 12", afKeepsNote(AfRetention("newest", 12)))
        assertNull(afKeepsNote(AfRetention()))
        assertEquals("Every episode stays. Nothing is ever deleted.", afKeepHint(AfRetention(), null))
        assertEquals("Keeps 12 of the 14 on the page. Older ones are deleted from the library.", afKeepHint(AfRetention("newest", 12), AfPreview(deletes = 2, keeps = 12, onPage = 14)))
        assertEquals("Keeps 5 of the 5 on the page. Older ones are deleted from the library.", afKeepHint(AfRetention("newest", 12), AfPreview(0, 5, 5)), "N above the page's count keeps what is there")
        assertEquals("Saving this deletes 4 episodes at the next run", afPreviewLine(AfRetention("newest", 8), AfPreview(4, 8, 12)))
        assertEquals("Saving this deletes 1 episode at the next run", afPreviewLine(AfRetention("newest", 11), AfPreview(1, 11, 12)))
        assertNull(afPreviewLine(AfRetention("newest", 12), AfPreview(0, 12, 12)), "no warning when nothing would go")
        assertNull(afPreviewLine(AfRetention(), AfPreview(4, 12, 12)), "All episodes deletes nothing")
    }

    @Test
    fun `a reason has its codes in mono and its markup escaped`() {
        assertEquals("""The site answered <span class="mono">403</span> on the list, <span class="mono">robots.txt</span> &lt;b&gt;""", afMono("The site answered 403 on the list, robots.txt <b>"))
        assertEquals("a 4030 is not a code", afMono("a 4030 is not a code"))
    }

    @Test
    fun `a moment is typed as minutes and seconds or as seconds`() {
        assertEquals(725, afParseMoment("12:05")); assertEquals(725, afParseMoment("725")); assertEquals(3725, afParseMoment("1:02:05"))
        assertNull(afParseMoment("")); assertNull(afParseMoment("12:xx")); assertNull(afParseMoment("-3")); assertNull(afParseMoment("1:2:3:4"))
        assertEquals("12:05", afMoment(725)); assertEquals("0:09", afMoment(9))
    }

    // ---- the list (FR-10.2, FR-10.6) --------------------------------------------------------------------------------------

    @Test
    fun `a row shows keeps newest under the schedule and its state in one slot`() {
        val html = panel(overview(fetcher(retention = AfRetention("newest", 12, true))))
        assertTrue("""<div class="keep">keeps newest 12</div>""" in html, html)
        assertTrue("Daily · 04:00" in html && """<span class="badge ok">Up to date</span>""" in html && "no new episodes · 04:00" in html)
        assertFalse("keeps newest" in panel(overview(fetcher())))
    }

    @Test
    fun `a fetcher that ignores robots txt says so after any retention note on its list row - and an unticked one does not`() {
        assertTrue("""<div class="keep">keeps newest 12 · ignores robots.txt</div>""" in panel(overview(fetcher(retention = AfRetention("newest", 12, true)).copy(ignoreRobots = true))))
        assertTrue("""<div class="keep">ignores robots.txt</div>""" in panel(overview(fetcher().copy(ignoreRobots = true))))
        assertFalse("ignores robots.txt" in panel(overview(fetcher(retention = AfRetention("newest", 12, true)))))
    }

    @Test
    fun `the open row has the tick - editable in place - with the hint when ticked - and Save wakes when it changes`() {
        val f = fetcher()
        val plain = afOpenRow(f, AfDraft(f.schedule, f.retention), clock, false, AfGate(true))
        assertTrue("data-af-robots-row" in plain && """<span class="af-tick">✓</span>""" in plain && "Reads pages this site asks crawlers to leave alone." !in plain)
        assertTrue("""btn sm af-dis" data-af-save""" in plain, "nothing changed")
        val ticked = afOpenRow(f, AfDraft(f.schedule, f.retention, ignoreRobots = true), clock, false, AfGate(true))
        assertTrue("""<span class="af-tick on">✓</span>""" in ticked && "Reads pages this site asks crawlers to leave alone." in ticked)
        assertTrue("""btn sm" data-af-save""" in ticked, "the tick is a change to save")
        val saved = afOpenRow(f.copy(ignoreRobots = true), AfDraft(f.schedule, f.retention, ignoreRobots = true), clock, false, AfGate(true))
        assertTrue("""btn sm af-dis" data-af-save""" in saved)
    }

    @Test
    fun `a state the admin has to act on replaces Up to date`() {
        assertTrue("Needs re-investigation" in afStatusCell(fetcher(state = "needs_reinvestigation").copy(stateReason = "0 videos found"), clock))
        assertTrue("Paused" in afStatusCell(fetcher(enabled = false), clock))
        assertTrue("Last run failed" in afStatusCell(fetcher(last = AfRun(1, 2, "manual", "failed", "failed · boom")), clock))
        assertTrue("Not run yet" in afStatusCell(fetcher(last = null), clock) && "first check in 5h 12m" in afStatusCell(fetcher(last = null), clock))
        val stopped = afStatusCell(fetcher(siteStopped = true, reason = "the site answered 403 on a page that worked before"), clock)
        assertTrue("""<span class="badge bad">Site stopped</span>""" in stopped && """<span class="mono">varpid.example.fo</span>""" in stopped && """<span class="mono">403</span>""" in stopped, stopped)
    }

    @Test
    fun `a stopped site disables Run now on every fetcher and the open row opens with the band above the recipe`() {
        val f = fetcher(siteStopped = true, reason = "the site answered 403 on a page that worked before")
        val html = panel(overview(f, sites = listOf(AfSite("varpid.example.fo", true, "x", 9, null, 1))), openF = "f1")
        assertTrue("""af-stopped""" in html)
        assertTrue(Regex("""af-dis" data-af-run""").containsMatchIn(html), "Run now is shown disabled")
        val band = html.indexOf("Site stopped · varpid.example.fo")
        assertTrue(band > 0 && band < html.indexOf("""<div class="af-h">Recipe</div>"""), "the band is above the recipe")
        assertTrue("""data-af-resume="varpid.example.fo">Resume site""" in html)
        assertTrue("Nothing runs on varpid.example.fo until you resume it." in html && "never retried on a timer" in html)
        assertTrue("varpid.example.fo is stopped" in html, "the header names the stopped site as an extra")
        assertTrue("""data-af-runall""" in html && """af-dis" data-af-runall""" !in html, "Run all now still has work on other sites")
    }

    @Test
    fun `Resume site reads Checking the site while the one request is made`() {
        val f = fetcher(siteStopped = true, reason = "the site answered 403")
        val html = afFetchersCard(overview(f), "f1", emptyMap(), setOf("varpid.example.fo"), 50, clock)
        assertTrue("Checking the site…" in html && "data-af-resume" !in html)
    }

    @Test
    fun `the open row carries the recipe read only the last runs with their lines and the Keep control`() {
        val run = AfRun(1, 2, "schedule", "ok", "+1 · S2026E300 · −1 by retention", listOf(
            AfRunLine("placed", "S2026E300 (1080p, 1.9 GB)"), AfRunLine("deleted", "−1 · S2026E286 (2026-10-13) · newest 12 kept"),
            AfRunLine("retention", "left the page: not deleted, the listing could not be read in full"), AfRunLine("skipped", "ignored")))
        val f = fetcher(tmdbId = null, name = "Dagbók", retention = AfRetention("newest", 12, true), last = run)
        val html = panel(overview(f), openF = "f1")
        assertTrue("""<pre class="af-script">""" in html && """<span class="k">source   </span>""" in html && "read-only here" in html)
        assertTrue("""<div class="af-sub add">+1 · S2026E300 (1080p, 1.9 GB)</div>""" in html)
        assertTrue("""<div class="af-sub del">−1 · S2026E286 (2026-10-13) · newest 12 kept</div>""" in html)
        assertTrue("""<div class="af-sub guard">left the page: not deleted, the listing could not be read in full</div>""" in html)
        assertFalse("ignored" in html, "a skipped item is not one of the lines under a run")
        assertTrue("""<span class="af-tick on">✓</span>""" in html, "leaves the page is ticked")
        assertTrue("page only, no TMDB id" in html, "the footer says there is no TMDB id")
        assertTrue("""value="12"""" in html && """data-af-keep="newest" class="on"""" in html)
    }

    @Test
    fun `a changed Keep shows the server's preview and enables Save`() {
        val f = fetcher(tmdbId = null, name = "Dagbók", retention = AfRetention("newest", 12, true))
        val clean = panel(overview(f), openF = "f1")
        assertTrue("""af-dis" data-af-save""" in clean, "nothing to save")
        val draft = AfDraft("daily@04:00", AfRetention("newest", 8, true), AfPreview(4, 8, 12))
        val html = panel(overview(f), openF = "f1", drafts = mapOf("f1" to draft))
        assertTrue("Saving this deletes 4 episodes at the next run" in html && "Keeps 8 of the 12 on the page" in html)
        assertTrue("""af-dis" data-af-save""" !in html && "data-af-save>Save" in html)
    }

    @Test
    fun `All episodes shows no leaves the page tick and says nothing is ever deleted`() {
        val html = panel(overview(fetcher()), openF = "f1")
        assertFalse("data-af-leaves" in html); assertTrue("Every episode stays. Nothing is ever deleted." in html)
    }

    @Test
    fun `no fetchers and Auto-fetch off are each said plainly`() {
        assertTrue("No auto-fetchers yet" in panel(overview()))
        val off = panel(overview(enabled = false))
        assertTrue("Auto-fetch is switched off" in off && "[autofetch]" in off)
        assertTrue("""af-dis" data-af-runall""" in off)
        assertFalse("Auto-fetch is switched off" in panel(overview()))
    }

    // ---- Not on TMDB yet (FR-9.2, FR-8.2) ----------------------------------------------------------------------------------

    private fun queued(title: String? = "Royndin 2026 · 3. partur", overview: String? = null, state: String = "queued", stills: List<Int> = listOf(372, 1118, 1865)) = AfQueueEntry(
        id = 7, fetcherId = "f1", series = "Royndin", tmdbId = 9000001, season = 2026, episode = 3, code = "S2026E03", pageLabel = title ?: "/sendingar/royndin/3",
        state = state, filedAt = 5, submittedAt = if (state == "submitted") 6 else null, runtimeSec = 2652, title = title, overview = overview, airDate = "2026-03-10", stills = stills,
    )

    @Test
    fun `nothing waiting draws no card and a waiting episode draws the page's words and where they came from`() {
        assertEquals("", afQueueCard(overview(), null, emptyMap(), clock))
        val html = panel(overview(fetcher(), queue = listOf(queued())), openQ = 7)
        assertTrue("Not on TMDB yet" in html && "1 episode" in html)
        assertTrue("on the page as “Royndin 2026 · 3. partur”" in html && "downloaded 3h ago · 44:12" in html)
        assertTrue("from the page — the episode’s own heading" in html)
        assertTrue("No overview found for this episode on the source page" in html && "nothing is invented" in html, "no description: said plainly, nothing made up")
        assertTrue("""<span class="badge" title="The source page is missing the episode name and/or overview">source incomplete</span>""" in html)
        assertTrue("Three frames picked from the video" in html && "the model" !in html.lowercase().substringAfter("three frames"), "the still copy is the fixed rule's (Q4)")
        assertTrue("/api/autofetch/queue/7/still/0" in html && "/api/autofetch/queue/7/still/2" in html && "6:12" in html && "31:05" in html)
        assertTrue("data-tq-submit>Mark as submitted" in html)
        assertTrue("https://www.themoviedb.org/tv/9000001/season/2026/episode/3/edit" in html)
    }

    @Test
    fun `the page's words are escaped and a complete page is not source incomplete`() {
        val html = afQueueRow(queued(title = "<script>x</script>", overview = "Lýsing & more"), true, 0, clock)
        assertTrue("&lt;script&gt;x&lt;/script&gt;" in html && "<script>" !in html && "Lýsing &amp; more" in html)
        assertFalse("source incomplete" in html)
    }

    @Test
    fun `a submitted row says so and offers no second press and a row with no frames says that`() {
        val html = afQueueRow(queued(state = "submitted", stills = emptyList()), true, 0, clock)
        assertTrue("Marked as submitted" in html && "data-tq-submit" !in html)
        assertTrue("No frame could be cut from this video" in html && "tq-still" !in html)
    }

    @Test
    fun `the picked still is the one shown big and the one downloaded`() {
        val html = afQueueRow(queued(), true, 1, clock)
        assertTrue("""tq-big" style="background:url(/api/autofetch/queue/7/still/1)""" in html)
        assertTrue("""href="/api/autofetch/queue/7/still/1" download="S2026E03-still.jpg"""" in html)
        assertTrue("""data-tq-cut="1"""" in html)
    }
    // ---- DEB-2479 (FR-4.3, FR-10.2): items waiting for a number ---------------------------------------------------------------

    private fun waiting(vararg w: AfWaiting, numbering: String = "mapped") = fetcher().copy(numbering = numbering, waiting = w.toList())

    private val w1 = AfWaiting("R4", "Royndin 2026 · 4. partur", "https://varpid.example.fo/sendingar/royndin/4", "2026-04-10", "Lýsing 4", "no number approved yet")

    @Test
    fun `a fetcher with items waiting says so in its state cell and not Up to date`() {
        val one = afStatusCell(waiting(w1), clock)
        assertTrue("1 item waiting for a number" in one && "badge warn" in one && "Up to date" !in one, one)
        assertTrue("not downloaded until it has a number you approved" in one)
        assertTrue("3 items waiting for a number" in afStatusCell(waiting(w1, w1.copy(itemId = "R5"), w1.copy(itemId = "R6")), clock))
        assertTrue("Site stopped" in afStatusCell(waiting(w1).copy(siteStopped = true, siteStoppedReason = "403"), clock), "a stopped site comes first")
        assertTrue("Paused" in afStatusCell(waiting(w1).copy(enabled = false), clock))
    }

    @Test
    fun `the open row lists each waiting item with the pages words Season Episode Propose and Accept off until a number is there`() {
        val f = waiting(w1, w1.copy(itemId = "R5", title = null, description = null, published = null, link = "https://varpid.example.fo/sendingar/royndin/5"))
        val h = afOpenRow(f, AfDraft(f.schedule, f.retention), clock, false, AfGate(true))
        assertTrue("This fetcher uses a <b>mapped</b> number" in h)
        assertTrue("""data-wait="R4"""" in h && "Royndin 2026 · 4. partur" in h && "published 2026-04-10" in h && "Lýsing 4" in h && "from the page — the item's description, verbatim" in h)
        assertTrue("No description for this item on the page" in h && "https://varpid.example.fo/sendingar/royndin/5" in h, "a missing title shows the link and a missing description is said")
        assertTrue("data-wait-s" in h && "data-wait-e" in h && "data-wait-propose" in h)
        assertTrue("""primary sm af-dis" data-wait-accept""" in h && "<b>Accept</b> is off until a season and an episode are there." in h)
        assertFalse("af-dis\" data-wait-propose" in h)
    }

    @Test
    fun `a proposal fills the fields and turns Accept on`() {
        val p = w1.copy(proposal = AfProposal(1, 4, "S01E04", "title and description both name the fourth"))
        val h = afWaitingBand(waiting(p), AfGate(true))
        assertTrue("""class="se">S01E04""" in h && "title and description both name the fourth" in h)
        assertTrue("""data-wait-s value="1"""" in h && """data-wait-e value="4"""" in h)
        assertTrue("""btn primary sm" data-wait-accept""" in h && "af-dis\" data-wait-accept" !in h)
    }

    @Test
    fun `Propose is off with the reason when the model cannot be asked and the fields still work`() {
        val h = afWaitingBand(waiting(w1), AfGate(false, "This month's Auto-fetch limit is spent ($4.00 of $4.00)"))
        assertTrue("""af-dis" data-wait-propose""" in h)
        assertTrue("<b>Propose</b> is off: This month&#39;s".replace("&#39;", "'") in h || "<b>Propose</b> is off: This month" in h)
        assertTrue("type the number yourself and accept it" in h)
        val unproven = afWaitingBand(waiting(w1, numbering = "source"), AfGate(true))
        assertTrue("The pattern is <b>not proven</b>" in unproven)
        assertEquals("", afWaitingBand(fetcher(), AfGate(true)))
    }

    @Test
    fun `the panel draws the waiting band only in the open row`() {
        val f = waiting(w1)
        val closed = panel(overview(f))
        assertTrue("1 item waiting for a number" in closed && "data-wait=" !in closed)
        assertTrue("data-wait=" in panel(overview(f), openF = f.id))
    }

    @Test
    fun `an item id with slashes is percent encoded for the route`() {
        assertEquals("https%3A%2F%2Fvarpid.example.fo%2Fsendingar%2Froyndin%2F9", afUrlEncode("https://varpid.example.fo/sendingar/royndin/9"))
        assertEquals("R4", afUrlEncode("R4"))
    }
}
