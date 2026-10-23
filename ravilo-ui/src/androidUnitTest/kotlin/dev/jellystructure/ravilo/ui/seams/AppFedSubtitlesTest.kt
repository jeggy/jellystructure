package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-6) — [AppFedSubtitles] as a pure state machine: a hand-cranked scheduler and a scripted source, no
 * threads and no network. The real-socket cases (a server that never answers) are in [AppFedSubtitlesServerTest].
 */
class AppFedSubtitlesTest {
    /** Time only moves when the test says so; a posted task runs at the next [advance]. */
    private class ManualScheduler : SubtitleScheduler {
        var now = 0L
        private class Item(val at: Long, val task: Runnable) { var cancelled = false }
        private val queue = ArrayList<Item>()
        override fun nowMs() = now
        override fun post(task: Runnable) = postDelayed(0, task)
        override fun postDelayed(delayMs: Long, task: Runnable): Cancellable {
            val item = Item(now + delayMs, task)
            queue += item
            return Cancellable { item.cancelled = true }
        }
        /** Runs everything due within [ms], in time order, moving the clock as it goes. */
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = queue.filter { !it.cancelled }.minByOrNull { it.at } ?: break
                if (next.at > until) break
                queue.remove(next)
                now = maxOf(now, next.at)
                next.task.run()
            }
            now = until
        }
        val waiting: Int get() = queue.count { !it.cancelled }
    }

    private class FakeCall(val url: String, val source: FakeSource, val attempt: Int) : SubtitleCall {
        var cancelled = false
        override fun execute(): SubtitleFetch {
            source.onExecute?.invoke(this)   // the "while the request is on the wire" moment
            return source.script(url, attempt)
        }
        override fun cancel() { cancelled = true }
    }

    private class FakeSource(val script: (url: String, attempt: Int) -> SubtitleFetch) : SubtitleSource {
        val calls = ArrayList<FakeCall>()
        var onExecute: ((FakeCall) -> Unit)? = null
        val requested: List<String> get() = calls.map { it.url }
        override fun newCall(url: String): SubtitleCall {
            val attempt = calls.count { it.url == url }
            return FakeCall(url, this, attempt).also { calls += it }
        }
    }

    private class Recorder : AppFedSubtitles.Listener {
        val shown = ArrayList<CueTimeline?>()
        val failures = ArrayList<String>()
        override fun onShown(timeline: CueTimeline?) { shown += timeline }
        override fun onAttemptFailed(url: String) { failures += url }
        val last: CueTimeline? get() = shown.lastOrNull()
    }

    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
    private val urls = listOf("https://x/0.vtt", "https://x/1.vtt", "https://x/2.vtt")

    private fun controller(script: (String, Int) -> SubtitleFetch = { _, _ -> SubtitleFetch.Body(vtt) }): Triple<AppFedSubtitles, FakeSource, Pair<ManualScheduler, Recorder>> {
        val source = FakeSource(script)
        val sched = ManualScheduler()
        val rec = Recorder()
        return Triple(AppFedSubtitles(source, sched, rec), source, sched to rec)
    }

    @Test
    fun `listing the tracks and leaving subtitles off requests nothing at all`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(-1)
        env.first.advance(60_000)
        assertEquals(0, source.calls.size)
        assertEquals(3, c.trackCount)
        assertEquals(0, c.pendingLoads)
    }

    @Test
    fun `only the shown track is requested, never its siblings`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(1)
        env.first.advance(1_000)
        assertEquals(listOf(urls[1]), source.requested)
        assertNotNull(env.second.last)
        assertEquals("Hello", env.second.last!!.activeAt(1_500).single().text)
    }

    @Test
    fun `select returns before anything is fetched - the work is on the scheduler`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(0)
        assertEquals(0, source.calls.size)   // nothing ran on the caller's thread
        assertEquals(1, c.pendingLoads)
        env.first.advance(0)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `an out-of-range or negative position is off`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(7)
        c.select(-3)
        env.first.advance(10_000)
        assertEquals(0, source.calls.size)
    }

    @Test
    fun `202 is retried after Retry-After and the subtitle shows when it arrives`() {
        val (c, source, env) = controller { _, attempt -> if (attempt < 2) SubtitleFetch.NotReady(4_000) else SubtitleFetch.Body(vtt) }
        val (sched, rec) = env
        c.reset(urls)
        c.select(0)
        sched.advance(0)
        assertEquals(1, source.calls.size)
        assertNull(rec.last)                      // nothing shown, the picture was never waiting
        assertEquals(1, c.pendingLoads)
        sched.advance(3_999)
        assertEquals(1, source.calls.size)
        sched.advance(1)
        assertEquals(2, source.calls.size)
        sched.advance(4_000)
        assertEquals(3, source.calls.size)
        assertNotNull(rec.last)
        assertEquals(0, c.pendingLoads)
        assertTrue(rec.failures.isEmpty(), "a 202 is not an error")
    }

    @Test
    fun `Retry-After is clamped to 1 to 15 seconds, and a missing one waits 3`() {
        for ((header, expected) in listOf(0L to 1_000L, 120_000L to 15_000L, null to 3_000L)) {
            val (c, source, env) = controller { _, attempt -> if (attempt == 0) SubtitleFetch.NotReady(header) else SubtitleFetch.Body(vtt) }
            c.reset(urls)
            c.select(0)
            env.first.advance(0)
            env.first.advance(expected - 1)
            assertEquals(1, source.calls.size, "header=$header")
            env.first.advance(1)
            assertEquals(2, source.calls.size, "header=$header")
        }
    }

    @Test
    fun `a track that stays 202 is given up after ten minutes`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.NotReady(15_000) }
        val (sched, rec) = env
        c.reset(urls)
        c.select(0)
        sched.advance(9 * 60_000 + 59_000)
        assertEquals(1, c.pendingLoads)
        sched.advance(30_000)
        assertEquals(0, c.pendingLoads)
        val calls = source.calls.size
        sched.advance(60_000)
        assertEquals(calls, source.calls.size, "no more requests after giving up")
        assertNull(rec.last)
        assertTrue(rec.failures.isEmpty())
    }

    @Test
    fun `a failure is counted once per attempt and retried twice, 3 seconds apart`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.Failed("HTTP 500") }
        val (sched, rec) = env
        c.reset(urls)
        c.select(0)
        sched.advance(0)
        assertEquals(1, rec.failures.size)
        sched.advance(2_999)
        assertEquals(1, source.calls.size)
        sched.advance(1)
        assertEquals(2, source.calls.size)
        sched.advance(3_000)
        assertEquals(3, source.calls.size)
        assertEquals(3, rec.failures.size)
        assertEquals(0, c.pendingLoads)
        sched.advance(60_000)
        assertEquals(3, source.calls.size, "three attempts, then it stops")
    }

    @Test
    fun `a failure that recovers delivers the track and counts only the failed attempt`() {
        val (c, _, env) = controller { _, attempt -> if (attempt == 0) SubtitleFetch.Failed("timeout") else SubtitleFetch.Body(vtt) }
        c.reset(urls)
        c.select(2)
        env.first.advance(3_000)
        assertEquals(1, env.second.failures.size)
        assertNotNull(env.second.last)
    }

    @Test
    fun `turning subtitles off while the request is on the wire cancels it, counts nothing and shows nothing`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.Body(vtt) }   // the response lands after the cancel
        source.onExecute = { c.select(-1) }
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        assertTrue(source.calls.single().cancelled)
        assertEquals(0, c.pendingLoads)
        assertTrue(env.second.failures.isEmpty())
        assertNull(env.second.last)
        source.onExecute = null
        c.select(0)
        env.first.advance(0)
        assertEquals(2, source.calls.size, "a cancelled response was not kept")
    }

    @Test
    fun `picking another track while the first is on the wire cancels the first and fetches only the second`() {
        val (c, source, env) = controller()
        source.onExecute = { if (it.url == urls[0]) c.select(2) }
        c.reset(urls)
        c.select(0)
        env.first.advance(1_000)
        assertTrue(source.calls.first().cancelled)
        assertEquals(listOf(urls[0], urls[2]), source.requested, "the sibling in between was never asked for")
        assertNotNull(env.second.last)
    }

    @Test
    fun `picking another track before the first one ran means the first is never requested`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(0)
        c.select(2)
        env.first.advance(1_000)
        assertEquals(listOf(urls[2]), source.requested)
    }

    @Test
    fun `a pending retry is cancelled when subtitles go off`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.NotReady(5_000) }
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        c.select(-1)
        env.first.advance(60_000)
        assertEquals(1, source.calls.size)
        assertEquals(0, env.first.waiting)
    }

    @Test
    fun `a track that arrived is not fetched again when it is picked again`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(1)
        env.first.advance(0)
        c.select(-1)
        c.select(1)
        env.first.advance(10_000)
        assertEquals(1, source.calls.size)
        assertNotNull(env.second.last, "shown again from the cache")
    }

    @Test
    fun `picking the track already on its way keeps that fetch`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.NotReady(5_000) }
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        c.select(0)   // a rebuilt engine re-applies the choice
        env.first.advance(0)
        assertEquals(1, source.calls.size)
        assertTrue(!source.calls.single().cancelled)
        assertEquals(1, c.pendingLoads)
    }

    @Test
    fun `a rebuilt engine re-selecting a loaded track shows it again without a request`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        val before = env.second.shown.size
        c.select(0)
        assertEquals(before + 1, env.second.shown.size)
        assertNotNull(env.second.last)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `a new load keeps tracks still listed and drops the rest`() {
        val (c, source, env) = controller()
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        c.select(1)
        env.first.advance(0)
        assertEquals(2, source.calls.size)
        c.reset(listOf(urls[0], "https://x/other.vtt"))
        assertNull(env.second.last)               // the new load starts with nothing shown
        c.select(0)
        assertNotNull(env.second.last)            // still cached
        c.select(1)
        env.first.advance(0)
        assertEquals(3, source.calls.size)        // the new one is fetched
        c.reset(listOf(urls[1]))
        c.select(0)
        env.first.advance(0)
        assertEquals(4, source.calls.size)        // urls[1] was dropped by the load before
    }

    @Test
    fun `a load cancels whatever was in flight`() {
        val (c, source, env) = controller { _, _ -> SubtitleFetch.Body(vtt) }
        source.onExecute = { c.reset(urls) }
        c.reset(urls)
        c.select(0)
        env.first.advance(0)
        assertTrue(source.calls.single().cancelled)
        assertEquals(0, c.pendingLoads)
        assertNull(env.second.last)
    }

    @Test
    fun `the row of an app-fed track is what the sideloaded one was`() {
        fun sub(label: String?, lang: String?, forced: Boolean, def: Boolean) =
            dev.jellystructure.shared.tv.SubTrack(index = 3, language = lang, label = label, forced = forced, isDefault = def, url = "/x")
        assertEquals("Dansk (SRT)", appFedSubtitleRow(sub("Dansk (SRT)", "da", false, false), 4).label)
        assertEquals(4, appFedSubtitleRow(sub(null, "da", false, false), 4).index)
        assertEquals("external", appFedSubtitleRow(sub(null, null, false, false), 4).deliveryMethod)
        assertEquals("Track 5", appFedSubtitleRow(sub(null, null, false, false), 4).label)
        val forced = appFedSubtitleRow(sub("F", "en", true, false), 0)
        assertTrue(forced.forced && !forced.isDefault)
        val both = appFedSubtitleRow(sub("D", "en", true, true), 0)
        assertTrue(!both.forced && both.isDefault, "default wins, as the selection flags did")
    }
}
