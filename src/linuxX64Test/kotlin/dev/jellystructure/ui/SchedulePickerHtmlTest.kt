package dev.jellystructure.ui

import dev.jellystructure.cron.IsoDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DEB-4402 (DEB-4387, FR-3) — the one schedule control: what it stores for what is chosen (FR-1.2), how it reads a stored string back (FR-3.9) and
 * the markup the round's ten points ask for. Stand-in corpus only. The date it works out for *starting* takes a fixed `today`; nothing reads a clock.
 */
class SchedulePickerHtmlTest {
    private val today = IsoDate(2026, 11, 12)   // a Thursday
    private val off = SchedOptions(canOff = true)
    private val plain = SchedOptions()

    private fun weeks(vararg days: Int, n: Int = 1) = SchedForm(SchedMode.WEEKS, n = n, days = days.toSet())
    private fun html(f: SchedForm, foot: SchedFoot = SchedFoot.Pending(), o: SchedOptions = plain, zone: String? = "Atlantic/Faroe") =
        schedulePickerHtml("k", SchedView(f, foot, zone, today), o)

    // ---- what is stored (FR-1.2) ---------------------------------------------------------------------------------------------

    @Test
    fun `Monday and Tuesday at 04-00 is stored as the cron that says it`() {
        assertEquals("0 4 * * 1,2", weeks(1, 2).schedule(today))
        assertEquals("0 4 * * 0,3", weeks(3, 0).schedule(today), "a week is shown Monday first, a cron counts Sunday as 0")
    }

    @Test
    fun `every 2 weeks needs the recurrence form and the date it counts from`() {
        val f = weeks(3, n = 2).copy(from = IsoDate(2026, 11, 18))
        assertEquals("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", f.schedule(today))
        // FR-3.6: with no date chosen, *starting* is the next date that matches
        assertEquals(IsoDate(2026, 11, 18), weeks(3, n = 2).defaultFrom(today))
        assertEquals("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", weeks(3, n = 2).schedule(today))
        // an N of 1 carries no FROM, so it stays a cron even when the admin once chose a date
        assertEquals("0 4 * * 3", f.copy(n = 1).schedule(today))
    }

    @Test
    fun `every 10 years starts counting at the next March 1 when no date is chosen`() {
        val f = SchedForm(SchedMode.YEARS, n = 10, month = 3, dayOfMonth = 1)
        assertEquals(IsoDate(2027, 3, 1), f.defaultFrom(today))
        assertEquals("every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2027-03-01", f.schedule(today))
        assertEquals("every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01", f.copy(from = IsoDate(2026, 3, 1)).schedule(today))
        assertEquals("0 4 1 3 *", f.copy(n = 1).schedule(today))
    }

    @Test
    fun `the last Friday and the last day are recurrences day 31 is a cron`() {
        val lastFri = SchedForm(SchedMode.MONTHS, monthByDay = false, ordinal = -1, ordDay = 5)
        assertEquals("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00", lastFri.schedule(today))
        assertEquals(IsoDate(2026, 11, 27), lastFri.defaultFrom(today))
        assertEquals("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00", SchedForm(SchedMode.MONTHS, monthDay = -1).schedule(today))
        assertEquals("0 4 31 * *", SchedForm(SchedMode.MONTHS, monthDay = 31).schedule(today))
        assertEquals("every:FREQ=MONTHLY;BYDAY=1MO;AT=04:00", SchedForm(SchedMode.MONTHS, monthByDay = false, ordinal = 1, ordDay = 1).schedule(today))
    }

    @Test
    fun `hours are a cron when N divides 24 and a recurrence when it does not`() {
        assertEquals("20 * * * *", SchedForm(SchedMode.HOURS, minute = 20).schedule(today))
        assertEquals("0 */6 * * *", SchedForm(SchedMode.HOURS, n = 6).schedule(today))
        assertEquals("0 */1 * * *".replace("*/1", "*"), SchedForm(SchedMode.HOURS, n = 1).schedule(today))
        assertEquals("every:FREQ=HOURLY;INTERVAL=5", SchedForm(SchedMode.HOURS, n = 5).schedule(today))
    }

    @Test
    fun `off is empty and Custom is the text as typed`() {
        assertEquals("", SchedForm(SchedMode.OFF).schedule(today))
        assertEquals("30 3 * * 0", SchedForm(SchedMode.CRON, cron = "  30 3 * * 0 ").schedule(today))
    }

    // ---- what a stored string opens as (FR-3.9) ------------------------------------------------------------------------------

    private fun opens(stored: String, o: SchedOptions = plain) = SchedForm.of(stored, o)

    @Test
    fun `a stored schedule opens in the mode that says it and is never rewritten for it`() {
        assertEquals(weeks(1, 2), opens("0 4 * * 1,2"))
        for (stored in listOf("0 */1 * * *", "30 3 * * 0", "0 4 * * 1,2", "15 * * * *", "0 4 31 * *", "0 4 1 3 *", "0 */6 * * *")) {
            assertNotEquals(SchedMode.CRON, opens(stored).mode, stored)
        }
        assertEquals(SchedMode.HOURS, opens("0 */1 * * *").mode)
        assertEquals(1, opens("0 */1 * * *").n)
        assertEquals(SchedMode.WEEKS, opens("30 3 * * 0").mode)
        assertEquals(setOf(0), opens("30 3 * * 0").days)
        assertEquals(3, opens("30 3 * * 0").hour)
        assertEquals(30, opens("30 3 * * 0").minute)
    }

    @Test
    fun `a recurrence opens in the mode it names with its start date`() {
        val f = opens("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18")
        assertEquals(SchedMode.WEEKS, f.mode); assertEquals(2, f.n); assertEquals(setOf(3), f.days); assertEquals(IsoDate(2026, 11, 18), f.from)
        val m = opens("every:FREQ=MONTHLY;BYDAY=-1FR;AT=04:00")
        assertEquals(SchedMode.MONTHS, m.mode); assertFalse(m.monthByDay); assertEquals(-1, m.ordinal); assertEquals(5, m.ordDay)
        assertEquals(-1, opens("every:FREQ=MONTHLY;BYMONTHDAY=-1;AT=04:00").monthDay)
        assertEquals(SchedMode.YEARS, opens("every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01").mode)
    }

    @Test
    fun `what the simple modes cannot show opens as Custom holding the text`() {
        for (stored in listOf("30 3 * * 9", "*/20 * * * *", "0 4 * * 1-5", "@daily", "every:FREQ=MONTHLY;BYDAY=1MO,3MO;AT=04:00", "not a schedule")) {
            val f = opens(stored)
            assertEquals(SchedMode.CRON, f.mode, stored)
            assertEquals(stored, f.schedule(today), "$stored is kept as it is")
        }
    }

    @Test
    fun `blank is off only where the setting can be off`() {
        assertEquals(SchedMode.OFF, opens("", off).mode)
        assertNotEquals(SchedMode.OFF, opens("", plain).mode)
    }

    @Test
    fun `the auto-fetcher's old words open in the simple modes and are written as the shared form`() {
        assertEquals("0 4 * * 3", opens("weekly@3@04:00").schedule(today))
        assertEquals(SchedMode.WEEKS, opens("weekly@3@04:00").mode)
        assertEquals("30 5 * * *", opens("daily@05:30").schedule(today))
        assertEquals("0 */6 * * *", opens("every6h").schedule(today))
    }

    // ---- the form's own rules ------------------------------------------------------------------------------------------------

    @Test
    fun `at least one weekday stays on`() {
        assertEquals(weeks(1), weeks(1).with("day", "1"))
        assertEquals(weeks(1, 2), weeks(1).with("day", "2"))
        assertEquals(weeks(2), weeks(1, 2).with("day", "1"))
    }

    @Test
    fun `an unreadable value leaves the form as it was`() {
        val f = weeks(1, 2)
        assertEquals(f, f.with("n", "")); assertEquals(f, f.with("at", "25:00")); assertEquals(f, f.with("from", "2026-02-30")); assertEquals(f, f.with("nonsense", "x"))
        assertEquals(100, f.with("n", "500").n)
        assertEquals(1, f.with("n", "0").n)
    }

    @Test
    fun `starting is shown for an N above 1 on days weeks months and years and never for hours`() {
        assertFalse(weeks(1).showsFrom)
        assertTrue(weeks(1, n = 2).showsFrom)
        assertFalse(SchedForm(SchedMode.HOURS, n = 5).showsFrom)
        assertTrue("data-sch=\"from\"" in html(weeks(1, n = 2)))
        assertFalse("data-sch=\"from\"" in html(weeks(1)))
    }

    @Test
    fun `calendar arithmetic names the right weekday and crosses months and years`() {
        assertEquals(4, weekday(IsoDate(2026, 11, 12)))
        assertEquals(0, weekday(IsoDate(2024, 2, 25)))
        assertEquals(IsoDate(2027, 1, 2), addDays(IsoDate(2026, 12, 30), 3))
        assertEquals(IsoDate(2024, 2, 29), addDays(IsoDate(2024, 2, 28), 1))
        assertEquals(IsoDate(2026, 11, 12), parseIsoDate("2026-11-12"))
        assertEquals(null, parseIsoDate("2026-13-01"))
    }

    // ---- the markup: the round's ten points ----------------------------------------------------------------------------------

    @Test
    fun `the foot never collapses - the sentence and the runs slot are there in every state`() {
        val ok = SchedFoot.Ok("Every Monday and Tuesday at 04:00 Atlantic/Faroe.", listOf("Mon 16 Nov 2026 · 04:00", "Tue 17 Nov 2026 · 04:00"))
        for ((f, foot) in listOf(weeks(1, 2) to ok, SchedForm(SchedMode.OFF) to SchedFoot.Ok("Not on a schedule", emptyList()), weeks(1) to SchedFoot.Refused("no"),
            weeks(1) to SchedFoot.Pending(), weeks(1) to SchedFoot.Unreachable)) {
            val h = html(f, foot, off)
            assertTrue("class=\"sch-says\"" in h && "class=\"sch-next" in h && "sch-next-h" in h, "$f $foot")
        }
        assertTrue("Next five runs" in html(weeks(1, 2), ok))
        assertTrue("Mon 16 Nov 2026" in html(weeks(1, 2), ok) && "04:00" in html(weeks(1, 2), ok))
        // over "Nothing scheduled." the heading does not promise five
        val offFoot = html(SchedForm(SchedMode.OFF), SchedFoot.Ok("Not on a schedule", emptyList()), off)
        assertTrue("Next runs" in offFoot && "Next five runs" !in offFoot && "Nothing scheduled." in offFoot)
    }

    @Test
    fun `a refusal is one block note with no runs never a badge and never the last good times`() {
        val last = SchedFoot.Ok("Daily at 04:00", listOf("Fri 13 Nov 2026 · 04:00"))
        val h = html(weeks(1), SchedFoot.Refused("Day of week is 0 to 6. 9 is not a day."))
        assertTrue("""<div class="sch-err" role="alert">Day of week is 0 to 6. 9 is not a day.</div>""" in h)
        assertFalse("badge" in h)
        assertFalse("<ol" in h || "Next five runs" in h, "a refusal shows no runs")
        assertTrue("No next runs while the schedule is refused." in h)
        assertTrue("""class="sch bad"""" in h)
        // waiting for an answer, the last good one is shown dimmed; that is a pending foot, not a refusal
        val pending = html(weeks(1), SchedFoot.Pending(last as SchedFoot.Ok))
        assertTrue("sch-stale" in pending && "Fri 13 Nov 2026" in pending)
    }

    @Test
    fun `months ask for a day or a weekday first and Last day is in the day menu`() {
        val day = html(SchedForm(SchedMode.MONTHS))
        assertTrue("""data-sch-mshape="day"""" in day && """data-sch-mshape="weekday"""" in day)
        assertTrue("""<option value="31">31</option>""" in day && """<option value="last">Last day</option>""" in day)
        assertFalse("sch-ordday" in day)
        val weekday = html(SchedForm(SchedMode.MONTHS, monthByDay = false, ordinal = -1, ordDay = 5))
        assertTrue("""<option value="last" selected>last</option>""" in weekday && """<option value="5" selected>Friday</option>""" in weekday)
        assertFalse("sch-mday" in weekday)
    }

    @Test
    fun `the unit word follows N in the select and the menu alike`() {
        val one = html(weeks(1)); val two = html(weeks(1, n = 2))
        assertTrue("""<option value="weeks" selected>week</option>""" in one && """<option value="hours">hour</option>""" in one)
        assertTrue("""<option value="weeks" selected>weeks</option>""" in two && """<option value="years">years</option>""" in two)
        assertFalse("1 weeks" in one)
    }

    @Test
    fun `the menu heads with Off only where the setting can be off and ends on Custom`() {
        val withOff = html(weeks(1), o = off); val without = html(weeks(1), o = plain)
        assertTrue(withOff.indexOf("""value="off"""") in 0 until withOff.indexOf("""value="hours""""))
        assertFalse("""value="off"""" in without)
        assertTrue(withOff.indexOf("""value="cron"""") > withOff.indexOf("""value="years""""))
        assertTrue(">Custom (cron)</option>" in withOff)
    }

    @Test
    fun `the weekdays are seven toggles Monday first each on-day marked`() {
        val h = html(weeks(1, 2))
        val order = Regex("""data-sch-day="(\d)"""").findAll(h).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "0"), order)
        assertTrue("""data-sch-day="1" role="button" tabindex="0" aria-pressed="true" class="on"""" in h)
        assertTrue("""data-sch-day="3" role="button" tabindex="0" aria-pressed="false" class=""""" in h)
        assertTrue("sch-days" in h && "<span class=\"seg" !in h.substringAfter("sch-days"))
    }

    @Test
    fun `hours say at minute every other unit says at a time and the zone is the server's`() {
        val hours = html(SchedForm(SchedMode.HOURS, minute = 20))
        assertTrue("at minute" in hours && "data-sch=\"minute\"" in hours && "sch-at" !in hours)
        val daily = html(SchedForm(SchedMode.DAYS))
        assertTrue("""class="input mono sch-at" type="time" data-sch="at" value="04:00"""" in daily)
        assertTrue("""<span class="sch-zone" data-sch-zone>Atlantic/Faroe</span>""" in daily)
        assertTrue("""<span class="sch-zone" data-sch-zone></span>""" in html(SchedForm(SchedMode.DAYS), zone = null))
    }

    @Test
    fun `Custom is the text box with the line wide and Off shows no time`() {
        val cron = html(SchedForm(SchedMode.CRON, cron = "30 3 * * 0"))
        assertTrue("""data-sch="cron"""" in cron && """value="30 3 * * 0"""" in cron && "sch-at" !in cron && "sch-line-wide" in cron)
        val offHtml = html(SchedForm(SchedMode.OFF), o = off)
        assertFalse("sch-at" in offHtml || "sch-cron" in offHtml || "data-sch=\"n\"" in offHtml)
    }

    @Test
    fun `the admin writes no words about the anchor`() {
        val decade = html(SchedForm(SchedMode.YEARS, n = 10, month = 3, from = IsoDate(2026, 3, 1)),
            SchedFoot.Ok("Every 10 years on 1 March at 04:00, starting 1 March 2026", listOf("Sat 1 Mar 2036 · 04:00")))
        assertFalse("anchor" in decade || "behind" in decade || "counts from" in decade || "sch-look" in decade, decade)
        assertTrue("starting 1 March 2026" in decade, "only the server's sentence says it")
        assertTrue("""value="2026-03-01"""" in decade)
    }

    @Test
    fun `the control is built from classes in wf-css and carries no inline width`() {
        val h = html(weeks(1, 2, n = 2))
        assertFalse("style=" in h, h)
        for (c in listOf("sch-lines", "sch-line", "sch-lead", "sch-n", "sch-unit", "sch-days", "sch-at", "sch-from", "sch-foot", "sch-says", "sch-next", "sch-next-h")) assertTrue(c in h, c)
    }

    @Test
    fun `the field carries the setting's label and the structure key redraws on a mode change only`() {
        val field = schedulePickerField("music", SchedView(weeks(1)), SchedOptions(label = "Read the music library"))
        assertTrue("""<label>Read the music library</label>""" in field && """data-sch-key="music"""" in field)
        assertEquals(schedStructure(weeks(1)), schedStructure(weeks(1, 2, n = 12)), "typing a number never redraws the lines")
        assertNotEquals(schedStructure(weeks(1)), schedStructure(SchedForm(SchedMode.DAYS)))
        assertNotEquals(schedStructure(SchedForm(SchedMode.MONTHS)), schedStructure(SchedForm(SchedMode.MONTHS, monthByDay = false)))
    }

    @Test
    fun `the auto-fetcher's panel holds Save while the server refuses the Check`() {
        val f = AfFetcher(id = "f1", name = "Evening News", site = "example.net", tmdbId = 9000001, schedule = "0 4 * * 3")
        val held = afOpenRow(f, AfDraft("0 * * * *", f.retention, sched = SchedView(SchedForm.of("0 * * * *", AF_SCHED), SchedFoot.Refused("Checks on a site are never closer than 6 hours apart."))),
            object : AfClock { override fun ago(sec: Long) = ""; override fun at(sec: Long) = ""; override fun until(sec: Long) = ""; override fun runtime(sec: Int) = "" }, false, AfGate(true))
        assertTrue("""data-af-save""" in held && """af-dis" data-af-save""" in held, held)
        assertTrue(SCHED_HELD in held && "data-sch-held>" in held)
        assertTrue("Checks on a site are never closer than 6 hours apart." in held)
        assertTrue("""<label>Check</label>""" in held)
        assertFalse("data-af-sched" in held, "the old picker is gone")
    }
}
