package dev.jellystructure.autofetch

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.autofetchRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3613 (DEB-3567, DEB-2474 FR-3.6, FR-12) — Acceptance 21: a site that plays only to a signed-in viewer. The stand-in site's media host answers `403`
 * unless the request carries the cookie `s=ok`; the page host is `varpid.example.fo`, the media host `media.example.fo`, the cookie's domain `example.fo`.
 */
class SignInSessionTest {
    private val h = Harness()
    private val ai = FakeAi()
    private val site = "varpid.example.fo"

    @AfterTest
    fun tearDown() = h.close()

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-04" },
    )

    private fun run() = runBlocking { investigator().run("inv1", h.dagbokUrl, null, true, null) { } }

    private val recipeJson = """{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":["media.example.fo"],"audio_language":"fao"}"""
    private fun answer(verdict: String, reason: String = "Log ind for at afspille") =
        """{"verdict":"$verdict","reason":"$reason","recipe":$recipeJson,"examples":[{"title":"Dagbók · 27. oktober","date":"2026-10-27"}]}"""

    private val cookiesTxt = "# Netscape HTTP Cookie File\n.example.fo\tTRUE\t/\tTRUE\t1893456000\ts\tok\n.example.fo\tTRUE\t/\tFALSE\t0\tlang\tfo\n"
    private val sentence = "It didn't work: $site lists 12 episodes but plays them only to someone signed in. Auto-fetch cannot sign in by itself."

    private fun gate() { h.site.mediaNeedsCookie = "s=ok"; h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">""" }

    /** DEB-3839 (FR-3.6, *Only on proof*) — two turns in which the model reads the page, then its own `probe` of the media host is refused `403`: the read that makes `needs_sign_in` admissible. */
    private fun aRefusedRead() { gate(); ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}"""); ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""") }

    // ---- the ending ----

    @Test
    fun `the model's needs_sign_in after a listing of two or more ends as Needs a sign-in in the servers words`() {
        gate()
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok/27"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        ai.answer(answer("needs_sign_in"))
        val out = run().view

        assertEquals("failed", out.state)
        assertTrue(out.needsSignIn); assertFalse(out.notASeries)
        assertEquals(sentence, out.reason)
        assertEquals(site, out.site)
        assertTrue("The request failed" in ai.resultOf(3) && "403" in ai.resultOf(3), "the model's own probe is refused signed out: ${ai.resultOf(3)}")
        assertFalse(runBlocking { h.lanes.forSite(site).state().stopped }, "a finding of the investigation does not stop the site")
        assertFalse("Log ind" in out.report!!.body, "the model's words are not in the bug report")
        assertFalse(sentence in out.report!!.body, "the report names the host once, in its head")
        assertEquals("needs a sign-in", out.report!!.body.lineSequence().first { it.startsWith("Ended:") }.removePrefix("Ended: ").substringBefore(" ·"))
    }

    @Test
    fun `the server ends it itself when the proofs own media read is refused whatever the model answered`() {
        gate()
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.answer(answer("series", "A daily programme."))
        val out = run().view
        assertEquals("failed", out.state)
        assertTrue(out.needsSignIn); assertFalse(out.notASeries)
        assertEquals(sentence, out.reason)
        assertFalse(runBlocking { h.lanes.forSite(site).state().stopped })
    }

    @Test
    fun `yt-dlp asking for a login ends it the same way`() {
        gate()
        val recipe = """{"list":{"kind":"ytdlp"},"media":{"kind":"ytdlp"},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":[],"audio_language":"fao"}"""
        h.tools.ytdlpInfoOut = YtDlpOutput(false, message = "yt-dlp: This video is only available for registered users", needsLogin = true)
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"series","reason":"x","recipe":$recipe,"examples":[]}""")
        val out = run().view
        assertTrue(out.needsSignIn, out.reason)
        assertTrue("lists 12 episodes" in out.reason!!, out.reason)
        assertFalse(runBlocking { h.lanes.forSite(site).state().stopped })
    }

    @Test
    fun `a needs_sign_in with no listing of two items is not accepted and the model is told why`() {
        aRefusedRead()
        ai.answer("""{"verdict":"needs_sign_in","reason":"login wall"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"one clip"}""")
        val out = run().view
        assertFalse(out.needsSignIn)
        assertTrue(out.notASeries)
        assertTrue("accepted only when your recipe's listing yields at least two items" in ai.sent[3], "the model is told the rule")
    }

    // DEB-3715 (DEB-3567, DEB-2474 FR-3.6) — the sign-in ending asks for a listing of two items, not a valid recipe: the model cannot see the signed-in media.
    private val brokenMediaRecipe = """{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime"}},"media":{"kind":"ytdlp","manifest":"{id}"},"numbering":"datetime","series":{"name":"h1"},"hosts":[],"audio_language":"fao"}"""
    private fun brokenMedia(selector: String = "li.ep") =
        """{"verdict":"needs_sign_in","reason":"Log ind","recipe":${brokenMediaRecipe.replace("li.ep", selector)},"examples":[]}"""

    @Test
    fun `a needs_sign_in with a listing of three items ends as Needs a sign-in though its media part is broken`() {
        h.site.dagbokItems = listOf(27 to "2026-10-27", 28 to "2026-10-28", 29 to "2026-10-29")
        aRefusedRead()
        ai.answer(brokenMedia())
        val out = run().view
        assertEquals("failed", out.state)
        assertTrue(out.needsSignIn, out.reason); assertFalse(out.notASeries)
        assertTrue("lists 3 episodes" in out.reason!!, out.reason)
    }

    @Test
    fun `a needs_sign_in whose listing reads one item is refused and the model is told the count`() {
        h.site.dagbokItems = listOf(27 to "2026-10-27")
        aRefusedRead()
        ai.answer(brokenMedia())
        ai.answer("""{"verdict":"not_a_series","reason":"one clip"}""")
        val out = run().view
        assertFalse(out.needsSignIn)
        assertTrue("the listing read gave 1 item" in ai.sent[3], "the model is told which check failed")
        assertTrue("accepted only when your recipe's listing yields at least two items" in ai.sent[3])
    }

    @Test
    fun `a needs_sign_in with a broken listing part is told the validators own listing error`() {
        aRefusedRead()
        ai.answer(brokenMedia(selector = ""))
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run()
        assertTrue("the html list needs an item selector" in ai.sent[3], ai.sent[3])
    }

    // ---- DEB-3839 (FR-3.6, *Only on proof*): needs_sign_in is accepted only after a read was refused for a sign-in ----

    private val noProof = "Nothing this investigation read was refused for a sign-in. Text on a page that asks the viewer to sign in is not proof: find how the videos are served."

    @Test
    fun `a needs_sign_in with no read refused is refused with the proof sentence and the next turn can still end as Videos not found`() {
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.answer(answer("needs_sign_in"))
        // DEB-4341 (FR-5.12) — a give-up answer whose recipe has a `media` part is proved; this one says it found none.
        ai.answer(answer("videos_not_found", "no manifest").replace("\"manifest\":\"https://media.example.fo/{value}/master.m3u8\",", "\"manifest\":\"\","))
        val out = run().view
        assertTrue(noProof in ai.sent[2], "the model reads the sentence: ${ai.sent[2]}")
        assertEquals(3, ai.sent.size)
        assertFalse(out.needsSignIn, out.reason); assertFalse(out.notASeries)
        assertTrue(out.videosNotFound, out.reason)
        assertTrue(h.site.nonRobots().none { it.contains("master.m3u8") }, "no media read was made: the answer was refused before the listing")
        assertEquals(2, h.site.nonRobots().count { it.endsWith("/sendingar/dagbok") }, "the model's fetch, and the listing read for the second answer")
    }

    @Test
    fun `a needs_sign_in with no read refused three times ends as a refusal and never as Needs a sign-in`() {
        repeat(3) { ai.answer(answer("needs_sign_in")) }
        val out = run().view
        assertEquals("failed", out.state)
        assertFalse(out.needsSignIn, out.reason)
        assertEquals(noProof, out.reason)
    }

    @Test
    fun `page text asking the viewer to sign in on a read that was answered 200 is not proof`() {
        gate()
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.answer(answer("needs_sign_in", "Log ind for at afspille"))
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        val out = run().view
        assertFalse(out.needsSignIn, out.reason)
        assertTrue(noProof in ai.sent[2])
    }

    @Test
    fun `a needs_sign_in after a fetch on a site host answered 403 is accepted`() {
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""") { h.site.listingStatus = 403 }
        ai.answerFrom { h.site.listingStatus = 200; answer("needs_sign_in") }
        val out = run().view
        assertTrue("403" in ai.resultOf(1), ai.resultOf(1))
        assertTrue(out.needsSignIn, out.reason); assertFalse(out.notASeries)
        assertEquals(sentence, out.reason)
        assertEquals(2, ai.sent.size, "accepted on the first answer: no correction turn")
    }

    @Test
    fun `a needs_sign_in after a probe of a media host answered 403 is accepted`() {
        aRefusedRead()
        ai.answer(answer("needs_sign_in"))
        val out = run().view
        assertTrue(out.needsSignIn, out.reason)
        assertEquals(3, ai.sent.size)
    }

    @Test
    fun `a needs_sign_in after a yt-dlp run that asked for a login is accepted`() {
        val ytdlpList = recipeJson.replace(""""list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}}""", """"list":{"kind":"ytdlp"}""")
        h.tools.ytdlpEntries = (16..27).toList().reversed().map { d -> listOf("N$d", "https://$site/sendingar/dagbok/$d", "Dagbók · $d. oktober", "") }
        h.tools.ytdlpDateOut = YtDlpOutput(false, message = "yt-dlp: This video is only available for registered users", needsLogin = true)
        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"series","reason":"x","recipe":$ytdlpList,"examples":[]}""")
        ai.answer("""{"verdict":"needs_sign_in","reason":"Log ind","recipe":$ytdlpList,"examples":[]}""")
        val out = run().view
        assertTrue(h.tools.ytdlpDateReads.isNotEmpty(), "the proof asked yt-dlp for an item's date")
        assertTrue(out.needsSignIn, out.reason)
        assertEquals(sentence, out.reason)
        assertEquals(3, ai.sent.size, "the first answer was corrected by the engine; the second needs_sign_in was accepted, not refused for want of proof")
        assertFalse(noProof in ai.sent[2])
    }

    // ---- the session ----

    @Test
    fun `a pasted cookies txt is stored once the next investigation reaches the review and the cookie went only where it belongs`() {
        gate()
        val saved = assertIs<SessionResult.Saved>(h.service.setSession(site, cookiesTxt))
        assertEquals(1_893_456_000L, saved.view.expiresAt)

        ai.tool("fetch", """{"url":"https://$site/sendingar/dagbok"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        var modelPhase = 0
        ai.answerFrom { modelPhase = h.site.cookiesSeen.size; answer("series", "A daily programme.") }
        val out = run()
        assertEquals("review", out.view.state, out.view.reason)

        val seen = h.site.cookiesSeen
        assertTrue(modelPhase > 0)
        assertTrue(seen.take(modelPhase).all { it.second == null }, "the model's fetch and probe went out without the session: ${seen.take(modelPhase)}")
        val media = seen.drop(modelPhase).filter { Urls.host(it.first) == "media.example.fo" }
        assertTrue(media.isNotEmpty() && media.all { it.second?.contains("s=ok") == true }, "the media host saw the cookie on the proof's read: $media")
        assertTrue(ai.sent.none { "s=ok" in it }, "no request to Anthropic carries the session")
        assertTrue(ai.sent[0].contains("signed-in session"), "the model is told, in a line, that one is set")
        assertTrue(ai.results(1).none { "s=ok" in it })
    }

    @Test
    fun `no session is told to the model when none is set`() {
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        run()
        assertFalse("signed-in session" in ai.sent[0])
    }

    @Test
    fun `the cookie goes to yt-dlp as a file for the call and to ffmpeg scoped by domain`() = runBlocking<Unit> {
        h.service.setSession(site, cookiesTxt)
        val recipe = h.dagbokRecipe.copy(list = ListSpec(kind = "ytdlp"), media = MediaSpec(kind = "ytdlp"))
        val f = when (val s = h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe))) { is SaveResult.Saved -> s.fetcher; is SaveResult.Refused -> error(s.reason) }
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertTrue(h.tools.ytdlpCookies.isNotEmpty() && h.tools.ytdlpCookies.all { it != null && "\ts\tok" in it }, "every yt-dlp call was handed the cookie file: ${h.tools.ytdlpCookies.map { it != null }}")
        assertTrue(h.tools.ytdlpDownloads.all { it.cookies != null })
        assertFalse(h.tools.ytdlpDownloads.first().toString().contains("s\tok"), "a download prints no session")

        val line = YtDlpCommands.listing("https://$site/x", "ua", "/tmp/jellystructure-cookies.AbC")
        assertTrue("--cookies '/tmp/jellystructure-cookies.AbC'" in line && "s=ok" !in line)
        assertFalse("--cookies" in YtDlpCommands.listing("https://$site/x", "ua"))
        val ff = FetchCommands.hlsToMkv("https://media.example.fo/v.m3u8", null, null, "/o.mkv", "fao", "", "s=ok; path=/; domain=.example.fo\r\n")
        assertTrue("-cookies" in ff && ff.indexOf("-cookies") < ff.indexOf("-i "), ff)
    }

    @Test
    fun `an expired session stops the site with the reason and a new paste resumes it`() = runBlocking<Unit> {
        gate()
        h.service.setSession(site, cookiesTxt)
        val f = when (val s = h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))) { is SaveResult.Saved -> s.fetcher; is SaveResult.Refused -> error(s.reason) }
        h.site.mediaRefusesAll = true
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("stopped", r.status)
        val lane = h.lanes.forSite(site)
        assertEquals("The session for $site has run out. Paste a new one on AI ▸ Fetchers.", lane.state().stoppedReason)
        assertEquals("stopped", h.engine.run(f.id, RunTrigger.MANUAL)!!.status, "a stopped site is not retried")

        h.site.mediaRefusesAll = false
        assertIs<SessionResult.Saved>(h.service.setSession(site, cookiesTxt))
        assertFalse(lane.state().stopped, "a new paste resumes a site stopped for its session")
        val again = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", again.status)
        assertTrue(again.placed > 0)
    }

    @Test
    fun `a site stopped for another reason is not resumed by a paste`() = runBlocking<Unit> {
        h.lanes.forSite(site).stop("the site answered 403 on a page that worked before")
        h.service.setSession(site, cookiesTxt)
        assertTrue(h.lanes.forSite(site).state().stopped)
    }

    @Test
    fun `forgetting removes it and fetchers that needed it stop with the same reason`() = runBlocking<Unit> {
        gate()
        h.service.setSession(site, cookiesTxt)
        val f = when (val s = h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))) { is SaveResult.Saved -> s.fetcher; is SaveResult.Refused -> error(s.reason) }
        assertTrue(h.service.forgetSession(site)); assertFalse(h.service.forgetSession(site))
        assertNull(h.store.session(site))
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(r.status == "stopped" || r.status == "failed", r.status)
    }

    // ---- the paste ----

    @Test
    fun `a cookies txt and a Cookie header both read - and expiry is the first cookie to run out`() {
        val a = assertIs<SessionPaste.Parsed>(SiteSessions.parse("#HttpOnly_.example.fo\tTRUE\t/\tTRUE\t2000000000\ta\tb\n.example.fo\tTRUE\t/\tFALSE\t1900000000\tc\td\n.example.fo\tTRUE\t/\tFALSE\t0\te\tf\n", site))
        assertEquals(3, a.cookies.size); assertEquals(1_900_000_000L, a.expiresAt)
        val b = assertIs<SessionPaste.Parsed>(SiteSessions.parse("Cookie: s=ok; lang=fo", site))
        assertEquals(listOf("s", "lang"), b.cookies.map { it.name }); assertNull(b.expiresAt)
        assertEquals(site, b.cookies.first().domain)
        assertIs<SessionPaste.Parsed>(SiteSessions.parse("s=ok", site))
    }

    @Test
    fun `an unreadable paste and one with no cookie for the site are refused in the servers words`() {
        assertEquals(SiteSessions.UNREADABLE, assertIs<SessionPaste.Refused>(SiteSessions.parse("hello world", site)).reason)
        assertEquals(SiteSessions.UNREADABLE, assertIs<SessionPaste.Refused>(SiteSessions.parse("  ", site)).reason)
        val other = ".other.example\tTRUE\t/\tFALSE\t0\ts\tok\n"
        assertEquals("That holds no cookie for $site.", assertIs<SessionPaste.Refused>(SiteSessions.parse(other, site)).reason)
        assertEquals("That holds no cookie for $site.", assertIs<SessionPaste.Refused>(SiteSessions.parse(".com\tTRUE\t/\tFALSE\t0\ts\tok\n", site)).reason)
    }

    @Test
    fun `the jar sends only to allowed hosts it matches - only live cookies - and a secure one only on https`() {
        val cookies = SiteSessions.fromStored(cookiesTxt)
        val jar = SessionJar(cookies) { 1_800_000_000L }
        val allowed = setOf(site, "media.example.fo")
        assertEquals("s=ok; lang=fo", jar.headerFor("https://media.example.fo/x/master.m3u8", allowed))
        assertEquals("lang=fo", jar.headerFor("http://media.example.fo/x", allowed), "the secure cookie is not sent over http")
        assertNull(jar.headerFor("https://evil.example.fo/x", allowed), "a host the recipe does not record gets nothing")
        assertNull(jar.headerFor("https://cdn.other.example/x", allowed))
        assertNull(SessionJar(cookies) { 1_900_000_000L }.headerFor("https://media.example.fo/x", allowed).takeIf { it?.contains("s=ok") == true }, "an expired cookie is not sent")
        assertNull(jar.fileFor(setOf("cdn.other.example")))
        assertTrue("s\tok" in jar.fileFor(allowed)!!)
    }

    @Test
    fun `nothing prints a value`() {
        val c = SiteSessions.fromStored(cookiesTxt).first { it.name == "s" }
        assertFalse("ok" in c.toString().substringAfter("("));
        assertFalse("ok" in StoredSession(cookiesTxt, 1, null).toString())
        assertFalse("s=ok" in YtDlpDownload("https://x/y", "/w/o.mkv", "fao", "", "ua", cookiesTxt).toString())
        assertFalse("s=ok" in DownloadSpec("https://m/x", "", "hls", "/w/o.mkv", "fao", "", "s=ok; path=/").toString())
    }

    @Test
    fun `yt-dlp's login errors are told apart from the rest`() {
        assertTrue(YtDlpErrors.classify(1, "ERROR: [site] 123: This video is only available for registered users. Use --cookies for the authentication").needsLogin)
        assertTrue(YtDlpErrors.classify(1, "ERROR: [site] Login required").needsLogin)
        assertTrue(YtDlpErrors.classify(1, "ERROR: Sign in to confirm you're not a bot").needsLogin)
        assertFalse(YtDlpErrors.classify(1, "ERROR: [site] Unable to download webpage: HTTP Error 404").needsLogin)
        assertFalse(YtDlpErrors.classify(1, "ERROR: DRM protected").needsLogin)
    }

    // ---- the routes ----

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = ConfigStore("${h.dir}/config.toml").also { it.load() }
        runBlocking { cfg.update(cfg.current.copy(autofetch = dev.jellystructure.config.AutofetchConfig(enabled = true))) }
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { route("/api") { autofetchRoutes(cfg, runtime) } }
        }
        block()
    }

    @Test
    fun `PUT answers 422 for an unreadable paste and one for another domain - 200 for a good one - and no route ever shows the value`() = app {
        val unreadable = client.put("/api/autofetch/sites/$site/session") { contentType(ContentType.Application.Json); setBody("""{"cookies":"hello world"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, unreadable.status)
        assertTrue(SiteSessions.UNREADABLE in unreadable.bodyAsText())
        val other = client.put("/api/autofetch/sites/$site/session") { contentType(ContentType.Application.Json); setBody("""{"cookies":".other.example\tTRUE\t/\tFALSE\t0\ts\tok"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
        assertTrue("That holds no cookie for $site." in other.bodyAsText())
        assertNull(h.store.session(site), "a refused paste keeps nothing")

        val ok = client.put("/api/autofetch/sites/$site/session") { contentType(ContentType.Application.Json); setBody(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject { put("cookies", kotlinx.serialization.json.JsonPrimitive(cookiesTxt)) })) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = ok.bodyAsText()
        assertTrue("\"set\":true" in body && "\"savedAt\":" in body && "\"expiresAt\":1893456000" in body, body)
        assertFalse("s=ok" in body || "\tok" in body)

        val list = client.get("/api/autofetch").bodyAsText()
        assertTrue("\"session\":{\"set\":true" in list, list)
        assertFalse("s=ok" in list || "\\tok" in list || "lang" in list, "the overview never holds the value: $list")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/autofetch/sites/$site/session").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/autofetch/sites/$site/session").status)
        assertFalse("\"session\":{" in client.get("/api/autofetch").bodyAsText())
    }

    @Test
    fun `nothing the run leaves behind holds the session - the log lines - the run record - activity and the bug report`() = runBlocking<Unit> {
        gate()
        h.service.setSession(site, cookiesTxt)
        val f = when (val s = h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))) { is SaveResult.Saved -> s.fetcher; is SaveResult.Refused -> error(s.reason) }
        h.site.mediaRefusesAll = true
        h.engine.run(f.id, RunTrigger.MANUAL)
        val record = h.store.runs(f.id, 10).joinToString("\n") { it.summary + "\n" + it.lines.joinToString("\n") { l -> l.text } }
        assertFalse("s=ok" in record || "ok;" in record, record)
        assertTrue(h.world.activities.none { "s=ok" in it })
        assertTrue(h.world.stops.none { "s=ok" in it.second })
        ai.answer(answer("series"))
        val fail = assertNotNull(run().view.report)
        assertFalse("s=ok" in fail.body || "s=ok" in fail.title)
    }
}
