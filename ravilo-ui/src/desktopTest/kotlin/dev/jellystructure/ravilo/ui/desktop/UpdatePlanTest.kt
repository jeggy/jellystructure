package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.shared.tv.RaviloVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R386 acceptance 1 — FR-R386-3's table, and the appcast reader and debug override around it. */
class UpdatePlanTest {
    private fun v(m: Int, n: Int) = RaviloVersion(m, n)
    private fun release(m: Int, n: Int) = AppcastRead.Release(v(m, n))
    private val feed = "https://github.com/jeggy/jellystructure/releases/download/v1.53/appcast.xml"

    @Test
    fun `a server ahead of the app asks Sparkle for the server's own release`() {
        assertEquals(UpdatePlan.AskSparkle(v(1, 53), feed), UpdateRules.plan("1.52", "v1.53", release(1, 53), null))
        // D12 — a build between releases floors to the release it sits on.
        assertEquals(UpdatePlan.AskSparkle(v(1, 53), feed), UpdateRules.plan("1.52", "v1.53-238-g188154f2", release(1, 53), null))
    }

    @Test
    fun `a 404 appcast on the server's release means nothing to offer, never an error`() {
        assertEquals(UpdatePlan.UpToDate, UpdateRules.plan("1.52", "v1.53", AppcastRead.NotPublished, null))
    }

    @Test
    fun `a server level with the app and a newer latest release is held back and Sparkle is not asked`() {
        assertEquals(UpdatePlan.HeldBack(v(1, 53), v(1, 52)), UpdateRules.plan("1.52", "v1.52", null, release(1, 53)))
        assertEquals(UpdatePlan.HeldBack(v(1, 53), v(1, 52)), UpdateRules.plan("1.52", "v1.52-4-gabcdef012", null, release(1, 53)))
    }

    @Test
    fun `a server behind the app is held back too when a newer release exists, and up to date otherwise`() {
        assertEquals(UpdatePlan.HeldBack(v(1, 53), v(1, 51)), UpdateRules.plan("1.52", "1.51", null, release(1, 53)))
        assertEquals(UpdatePlan.UpToDate, UpdateRules.plan("1.52", "1.51", null, release(1, 52)))
        assertEquals(UpdatePlan.UpToDate, UpdateRules.plan("1.52", "1.52", null, release(1, 51)), "latest behind the app offers nothing")
        assertEquals(UpdatePlan.UpToDate, UpdateRules.plan("1.52", "1.52", null, AppcastRead.NotPublished))
    }

    @Test
    fun `an unknown server or an unreachable GitHub is offline`() {
        assertEquals(UpdatePlan.Offline, UpdateRules.plan("1.52", null, null, release(1, 53)))
        assertEquals(UpdatePlan.Offline, UpdateRules.plan("1.52", "dev", null, release(1, 53)))
        assertEquals(UpdatePlan.Offline, UpdateRules.plan("1.52", "", null, release(1, 53)))
        assertEquals(UpdatePlan.Offline, UpdateRules.plan("1.52", "v1.53", AppcastRead.Unreachable, null))
        assertEquals(UpdatePlan.Offline, UpdateRules.plan("1.52", "v1.52", null, AppcastRead.Unreachable))
    }

    @Test
    fun `a development build of the app has no update block whatever the server says`() {
        assertEquals(UpdatePlan.NoBlock, UpdateRules.plan("1.52-3-gabcdef0", "v1.53", release(1, 53), release(1, 53)))
        assertEquals(UpdatePlan.NoBlock, UpdateRules.plan("dev", "v1.53", release(1, 53), release(1, 53)))
        assertEquals(UpdatePlan.NoBlock, UpdateRules.plan(null, "v1.53", release(1, 53), release(1, 53)))
    }

    @Test
    fun `the appcast's short version is read, and anything else is no release`() {
        val xml = """<rss xmlns:sparkle="x"><channel><item><title>Ravilo 1.53</title>
            <sparkle:version>1.53.0</sparkle:version>
            <sparkle:shortVersionString>1.53</sparkle:shortVersionString></item></channel></rss>"""
        assertEquals(v(1, 53), UpdateRules.versionInAppcast(xml))
        assertNull(UpdateRules.versionInAppcast("<rss><channel/></rss>"))
        assertNull(UpdateRules.versionInAppcast("<sparkle:shortVersionString>1.53-3-gabcdef0</sparkle:shortVersionString>"))
        assertNull(UpdateRules.versionInAppcast("<html>Not Found</html>"))
    }

    @Test
    fun `the feeds are the release assets of R386`() {
        assertEquals("https://github.com/jeggy/jellystructure/releases/latest/download/appcast.xml", UpdateRules.LATEST_APPCAST)
        assertEquals(feed, UpdateRules.appcastOf(v(1, 53)))
        assertEquals("https://github.com/jeggy/jellystructure/releases/tag/v1.53", UpdateRules.releasePageOf(v(1, 53)))
    }

    @Test
    fun `the feed override is honoured only by a build that was made with it allowed`() {
        val env = mapOf("RAVILO_UPDATE_FEED" to "http://127.0.0.1:8099/appcast.xml")
        val release = emptyMap<String, String>()
        val spike = mapOf(UpdateRules.OVERRIDE_ALLOWED_PROPERTY to "true")
        assertNull(UpdateRules.feedOverride(env::get, release::get), "a release build never honours it")
        assertNull(UpdateRules.feedOverride(env::get, mapOf(UpdateRules.OVERRIDE_ALLOWED_PROPERTY to "false")::get))
        assertEquals("http://127.0.0.1:8099/appcast.xml", UpdateRules.feedOverride(env::get, spike::get))
        assertEquals("http://localhost/a.xml", UpdateRules.feedOverride(env::get, (spike + ("ravilo.update.feed" to " http://localhost/a.xml "))::get))
        assertNull(UpdateRules.feedOverride(emptyMap<String, String>()::get, spike::get))
    }

    @Test
    fun `Sparkle's snapshot is read line by line and a bad one is unavailable`() {
        val s = SparkleSnapshot.parse("phase=ready\nversion=1.53\nfailure=\nreason=\nauto=1\nbytes=-1\ntotal=-1")
        assertEquals(SparkleSnapshot.Phase.READY, s.phase)
        assertEquals("1.53", s.version)
        assertNull(s.failure)
        assertEquals(true, s.autoDownload)
        val f = SparkleSnapshot.parse("phase=failed\nversion=1.53\nfailure=signature\nauto=0")
        assertEquals(SparkleSnapshot.Failure.SIGNATURE, f.failure)
        assertEquals(false, f.autoDownload)
        assertEquals(SparkleSnapshot.Phase.UNAVAILABLE, SparkleSnapshot.parse("garbage").phase)
        assertEquals(SparkleSnapshot.Phase.UNAVAILABLE, SparkleSnapshot.parse(null).phase)
    }

    @Test
    fun `the engine's changes of state become one log line each, and a snapshot without any writes none`() {
        val s = SparkleSnapshot.parse(
            "phase=ready\nversion=1.53\nfailure=\nreason=\nauto=1\nbytes=-1\ntotal=-1\n" +
                "change=checking|downloading|1.53||\nchange=downloading|ready|1.53||\nchange=idle|unavailable|||Sparkle did not start",
        )
        assertEquals(
            listOf(
                "update: engine checking -> downloading version=1.53",
                "update: engine downloading -> ready version=1.53",
                "update: engine idle -> unavailable reason=Sparkle did not start",
            ),
            s.changes.map { it.logLine() },
        )
        assertEquals("update: engine checking -> failed version=1.53 failure=download", SparkleSnapshot.EngineChange("checking", "failed", "1.53", "download", "").logLine())
        assertEquals(emptyList(), SparkleSnapshot.parse("phase=idle\nversion=\nauto=1").changes)
        assertEquals(emptyList(), SparkleSnapshot.parse("phase=idle\nchange=garbage\nchange=|ready|1.53||").changes)
    }

    @Test
    fun `a check names the feed it was pointed at`() {
        assertEquals(
            "update: check (background) feed=https://example.test/v1.53/appcast.xml",
            MacUpdater.checkLine("background", "https://example.test/v1.53/appcast.xml"),
        )
    }
}
