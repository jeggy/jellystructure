package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ProcessingCarryOver
import dev.jellystructure.config.ProcessingConfig
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2196 (DEB-1664, FR-5, FR-6) — the Scan dialog's model and what a manual scan makes of the unticks the dialog sends back.
 */
class ScanDialogTest {
    private val now = 1_000_000L
    private fun cfg(p: ProcessingConfig = ProcessingConfig(), tags: Boolean = false): AppConfig =
        ProcessingCarryOver.settle(AppConfig(processing = p)).let { it.copy(music = it.music.copy(writeTags = tags)) }

    private val inputs = ProcessingInputs(now, directPlay = listOf("Stereo for everything" to true, "Surround for lossless-only audio" to false))

    private fun model(c: AppConfig = cfg(), scope: ScanScope? = null, counts: ScanCounts = ScanCounts(), inp: ProcessingInputs = inputs) =
        ScanDialogs.model(c, ProcessingViews.build(c, inp), scope, counts)

    private fun ScanDialogView.feature(id: String) = features.first { it.id == id }

    private val segmentsOn = ProcessingConfig().let { it.copy(segments = it.segments.copy(enabled = true)) }

    // ── FR-5.2 / FR-5.3 — the left pane ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the dialog lists the nine features in order and Find new and changed is ticked and locked`() {
        val m = model()
        assertEquals(ProcessingFeatures.ALL.map { it.id }, m.features.map { it.id })
        val find = m.feature(ProcessingFeatures.FIND)
        assertTrue(find.locked && find.canRun)
        assertEquals("always", find.short)
        assertTrue(find.parts.isEmpty() && find.tickId == null, "nothing to tick on it: the whole feature is the one tick, and it is not a choice")
        assertEquals("Scan library", m.title)
        assertNull(m.scopeName)
    }

    @Test
    fun `a feature marks queues work only when every one of its parts queues work`() {
        val m = model(cfg(segmentsOn))
        assertTrue(m.feature(ProcessingFeatures.HEALTH).queuesWork)
        assertTrue(m.feature(ProcessingFeatures.SUBTITLES).queuesWork)
        assertTrue(m.feature(ProcessingFeatures.SEGMENTS).queuesWork)
        assertFalse(m.feature(ProcessingFeatures.METADATA).queuesWork)
        assertFalse(m.feature(ProcessingFeatures.DIRECT_PLAY).queuesWork, "it has no part to say so about")
        assertTrue(m.feature(ProcessingFeatures.HEALTH).parts.all { it.queuesWork })
    }

    @Test
    fun `a feature with one part or none is a single tick and the others open their parts`() {
        val m = model(cfg(segmentsOn))
        assertEquals("detect_segments", m.feature(ProcessingFeatures.SEGMENTS).tickId)
        assertEquals("scan_audiobooks", m.feature(ProcessingFeatures.AUDIOBOOKS).tickId)
        assertEquals(ProcessingFeatures.DIRECT_PLAY, m.feature(ProcessingFeatures.DIRECT_PLAY).tickId)
        assertTrue(m.feature(ProcessingFeatures.SEGMENTS).parts.isEmpty())
        assertEquals(6, m.feature(ProcessingFeatures.METADATA).parts.size)
        assertEquals(3, m.feature(ProcessingFeatures.HEALTH).parts.size)
        assertEquals(7, m.feature(ProcessingFeatures.MUSIC).parts.size)
        assertNull(m.feature(ProcessingFeatures.MUSIC).tickId)
    }

    @Test
    fun `every feature explains itself in one sentence the server wrote`() {
        val m = model()
        assertTrue(m.features.all { it.lede.isNotBlank() })
        assertEquals("Three parts. All three queue work — the reading happens afterwards, in the background.", m.feature(ProcessingFeatures.HEALTH).lede)
        assertEquals("Two parts. Both queue work — the fit is read afterwards, in the background.", m.feature(ProcessingFeatures.SUBTITLES).lede)
        assertEquals("Six parts. All six are written during the run itself — none of them queue work.", m.feature(ProcessingFeatures.METADATA).lede)
    }

    // ── FR-5.5 — off, and behind another switch ────────────────────────────────────────────────────────────────────

    @Test
    fun `a feature that is off in Settings is shown disabled with its reason and a link to its row`() {
        val m = model()   // a fresh install has Intro and credits off
        val seg = m.feature(ProcessingFeatures.SEGMENTS)
        assertFalse(seg.canRun)
        assertEquals("Off in Settings", seg.short)
        assertEquals("Off in Settings — nothing here would run.", seg.reasonHead)
        assertTrue(seg.settingsLink && seg.reason!!.contains("cannot be ticked for one scan either"))
    }

    @Test
    fun `Write tags is disabled with its reason while tag writing is off and ticks again when it is on`() {
        val off = model(cfg(tags = false)).feature(ProcessingFeatures.MUSIC)
        val tags = off.parts.first { it.id == "write_tags" }
        assertFalse(tags.canRun)
        assertEquals("Tag writing is off in Settings. It cannot be ticked for one scan either.", tags.reason)
        assertFalse(tags.settingsLink, "its switch is not on Settings ▸ Processing")
        assertTrue(off.canRun, "the other six still can")
        assertTrue(off.parts.filter { it.id != "write_tags" }.all { it.canRun })

        val on = model(cfg(tags = true)).feature(ProcessingFeatures.MUSIC).parts.first { it.id == "write_tags" }
        assertTrue(on.canRun && on.reason == null)
    }

    @Test
    fun `a part switched off on the Processing tab links to that row`() {
        val p = ProcessingConfig().let { it.copy(health = it.health.copy(lengths = false)) }
        val lengths = model(cfg(p)).feature(ProcessingFeatures.HEALTH).parts.first { it.name == "Track lengths" }
        assertFalse(lengths.canRun)
        assertTrue(lengths.settingsLink)
        assertEquals("Off in Settings. It cannot be ticked for one scan either.", lengths.reason)
    }

    @Test
    fun `a feature that is off leaves its parts without a reason of their own because the pane says it once`() {
        val p = ProcessingConfig().let { it.copy(suggestions = it.suggestions.copy(enabled = false)) }
        val f = model(cfg(p)).feature(ProcessingFeatures.SUGGESTIONS)
        assertFalse(f.canRun)
        assertTrue(f.parts.all { !it.canRun && it.reason == null })
        assertNotNull(f.reason)
    }

    @Test
    fun `Music with no library mapped says what to do instead of Off in Settings`() {
        val f = model(inp = ProcessingInputs(now, musicLibraries = false)).feature(ProcessingFeatures.MUSIC)
        assertFalse(f.canRun)
        assertEquals("No library mapped", f.short)
        assertTrue(f.reason!!.startsWith("No music library is mapped"))
        assertFalse(f.settingsLink)
    }

    // ── FR-5.4 / FR-5.6 — the switch, and a scoped scan ────────────────────────────────────────────────────────────────

    @Test
    fun `the re-check note counts what would be read again and is a sentence the server built`() {
        val m = model(counts = ScanCounts(1240, 186, 7950))
        assertEquals("That is 1 240 films, 186 series and 7 950 episodes read again. It is safe to leave running; nothing waits on it.", m.recheckNote)
        assertEquals("Off · only titles that are due. This is the normal scan.", m.recheckOff)
        assertTrue(m.recheckOn.startsWith("On · every title in the library is read again"))
    }

    @Test
    fun `a scoped scan of a video library lists the library-wide features as not in it and says so`() {
        val scope = ScanScope("lib-films", "Films", "movies")
        val m = model(cfg(segmentsOn), scope, ScanCounts(films = 1240))
        assertEquals("Scan Films", m.title)
        assertEquals("Films", m.scopeName)
        assertEquals("lib-films", m.scopeLibraryId)
        assertEquals("Films only — 1 240 films. Recommendations, music and audiobooks are not in this library, so they cannot be ticked.", m.lede)
        for (id in listOf(ProcessingFeatures.SUGGESTIONS, ProcessingFeatures.MUSIC, ProcessingFeatures.AUDIOBOOKS)) {
            val f = m.feature(id)
            assertFalse(f.applies || f.canRun, id)
            assertEquals("Not in this library", f.short)
        }
        assertTrue(m.feature(ProcessingFeatures.HEALTH).canRun && m.feature(ProcessingFeatures.DIRECT_PLAY).canRun)
        assertEquals(9, m.features.size, "listed and explained, never hidden")
        assertTrue(m.recheckOn.contains("every title in Films"))
    }

    @Test
    fun `a scoped scan of a music library applies to Music alone besides Find new and changed`() {
        val m = model(scope = ScanScope("lib-music", "Music", "music"))
        assertEquals(setOf(ProcessingFeatures.FIND, ProcessingFeatures.MUSIC), m.features.filter { it.applies }.map { it.id }.toSet())
        val books = model(scope = ScanScope("lib-books", "Books", "books"))
        assertEquals(setOf(ProcessingFeatures.FIND, ProcessingFeatures.AUDIOBOOKS), books.features.filter { it.applies }.map { it.id }.toSet())
    }

    // ── FR-6 — what the unticks make of a run ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `unticking one part skips it and names it and nothing else`() {
        val m = model(cfg(segmentsOn))
        val sel = ScanDialogs.selection(m, listOf("detect_segments"))
        assertEquals(setOf("detect_segments"), sel.skipSteps)
        assertEquals(listOf("Intro & credits"), sel.skippedNames)
        assertEquals("Intro & credits", sel.skippedText)
        assertTrue(sel.queueDirectPlay)
    }

    @Test
    fun `unticking every part of a feature names the feature and some of them names the parts`() {
        val m = model(cfg().let { it.copy(avsync = it.avsync.copy(enabled = true)) })
        val all = ScanDialogs.selection(m, listOf("verify_files", "check_track_lengths", "check_av_sync"))
        assertEquals(listOf("File health"), all.skippedNames)
        assertEquals(setOf("verify_files", "check_track_lengths", "check_av_sync"), all.skipSteps)
        val some = ScanDialogs.selection(m, listOf("verify_files", "check_av_sync"))
        assertEquals(listOf("Read files end to end", "Sound in step with the picture"), some.skippedNames)
    }

    @Test
    fun `Sound in step is not tickable while its measuring service is not set up and the rest of File health is`() {
        val health = model().feature(ProcessingFeatures.HEALTH)
        assertFalse(health.parts.first { it.id == "check_av_sync" }.canRun)
        assertEquals("The measuring service is not set up. It cannot be ticked for one scan either.", health.parts.first { it.id == "check_av_sync" }.reason)
        val sel = ScanDialogs.selection(model(), listOf("verify_files", "check_track_lengths", "check_av_sync"))
        assertEquals(listOf("File health"), sel.skippedNames, "both parts that could run were unticked, and the third could not have run")
        assertEquals(setOf("verify_files", "check_track_lengths"), sel.skipSteps)
    }

    @Test
    fun `a feature id unticks every part of it that could run`() {
        val sel = ScanDialogs.selection(model(), listOf(ProcessingFeatures.SUBTITLES))
        assertEquals(setOf("prewarm_subtitles", "check_subtitles"), sel.skipSteps)
        assertEquals(listOf("Subtitles"), sel.skippedNames)
    }

    @Test
    fun `an untick on a part that could not have run skips nothing and is not named`() {
        val m = model(cfg(tags = false))
        val sel = ScanDialogs.selection(m, listOf("write_tags", "detect_segments"))   // tag writing is off, and Intro and credits is off
        assertTrue(sel.skippedIds.isEmpty() && sel.skippedNames.isEmpty())
        assertFalse("write_tags" in sel.skipSteps)
    }

    @Test
    fun `an id that is no part and the always-on feature are ignored`() {
        val sel = ScanDialogs.selection(model(), listOf("scan_files", ProcessingFeatures.FIND, "sync_jellyfin", "notify", "wait", "nonsense", ""))
        assertTrue(sel.skippedNames.isEmpty() && sel.skipSteps.isEmpty())
    }

    @Test
    fun `Make files play directly queues in a scan unless it was unticked or has no kind switched on`() {
        assertTrue(ScanDialogs.selection(model(), emptyList()).queueDirectPlay)
        val unticked = ScanDialogs.selection(model(), listOf(ProcessingFeatures.DIRECT_PLAY))
        assertFalse(unticked.queueDirectPlay)
        assertEquals(listOf("Make files play directly"), unticked.skippedNames)
        assertTrue(unticked.skipSteps.isEmpty(), "it is not an engine step")
        val noKinds = model(inp = ProcessingInputs(now, directPlay = listOf("Stereo for everything" to false)))
        assertFalse(ScanDialogs.selection(noKinds, emptyList()).queueDirectPlay)
    }

    @Test
    fun `a scoped scan leaves out what is not in the library without calling it skipped`() {
        val scope = ScanScope("lib-films", "Films", "movies")
        val sel = ScanDialogs.selection(model(scope = scope), listOf("verify_files"))
        assertTrue("scan_music" in sel.skipSteps && "scan_audiobooks" in sel.skipSteps && "build_recommendations" in sel.skipSteps)
        assertTrue("verify_files" in sel.skipSteps)
        assertEquals(listOf("Read files end to end"), sel.skippedNames, "only what was unticked is named")
        val music = ScanDialogs.selection(model(scope = ScanScope("m", "Music", "music")), emptyList())
        assertTrue("pull_tmdb" in music.skipSteps && "scan_music" !in music.skipSteps)
        assertFalse(music.queueDirectPlay, "repairing film files is not in a music library")
    }

    @Test
    fun `the old skipSteps ids and the dialog's part ids are the same ids`() {
        val ids = ScanDialogs.selection(model(), ProcessingFeatures.ALL.flatMap { it.partIds }).skipSteps
        assertEquals(ids, ProcessingFeatures.skippableParts(ids))
    }

    // ── FR-5.10 — what a finished scan says it queued ──────────────────────────────────────────────────────────────

    @Test
    fun `a run says what it queued one sentence for each feature and kind`() {
        val dir = "/tmp/jellystructure-test-scan-dialog-${getpid()}"
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        try {
            val tracker = ScanTracker(createDatabase("$dir/test.db"), persistToDb = false)
            assertTrue(tracker.queuedSentences().isEmpty())
            tracker.noteQueued(ProcessingFeatures.SEGMENTS, "episode", "episodes", 12)
            tracker.noteQueued(ProcessingFeatures.SEGMENTS, "episode", "episodes", 25)
            tracker.noteQueued(ProcessingFeatures.SEGMENTS, "film", "films", 1)
            tracker.noteQueued(ProcessingFeatures.HEALTH, "file", "files", 0)
            tracker.noteQueued(ProcessingFeatures.DIRECT_PLAY, "file", "files", 1)
            assertEquals(
                listOf("37 episodes queued for intro & credits", "1 film queued for intro & credits", "1 file queued for making files play directly"),
                tracker.queuedSentences(),
            )
            tracker.startNew()
            assertTrue(tracker.queuedSentences().isEmpty(), "a new run starts from nothing")
        } finally {
            dev.jellystructure.db.closeLastDatabaseForTests()
            platform.posix.system("rm -rf '$dir'")
        }
    }
}
