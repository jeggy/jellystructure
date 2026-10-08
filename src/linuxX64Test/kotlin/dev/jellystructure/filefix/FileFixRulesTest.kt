package dev.jellystructure.filefix

import dev.jellystructure.arr.ArrParseResult
import dev.jellystructure.media.WorkFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 314 — a file every device can play directly: the eligibility rules, the command lines, the verification and the
 * names (FR-314-8's tests 1–3 and 6). Probe fixtures are the shapes ffprobe printed on 2026-10-08 for scratch copies of
 * real library files (titles and names removed): a 2.0 AC-3 film, a DTS 5.1 episode, a Dolby Vision 7 clip and its
 * converted version.
 */
class FileFixRulesTest {
    private fun d(default: Int = 0, comment: Int = 0, vi: Int = 0) =
        """{"default": $default, "comment": $comment, "forced": 0, "visual_impaired": $vi, "attached_pic": 0}"""

    private val ac3Film = """{"streams": [
        {"index": 0, "codec_name": "h264", "codec_type": "video", "r_frame_rate": "25/1", "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 1, "codec_name": "ac3", "codec_type": "audio", "channels": 2, "disposition": ${d(1)}, "tags": {"language": "ita"}}],
        "format": {"format_name": "matroska,webm", "duration": "113.120000"}}"""

    private val ac3FilmWithCopy = """{"streams": [
        {"index": 0, "codec_name": "h264", "codec_type": "video", "r_frame_rate": "25/1", "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 1, "codec_name": "ac3", "codec_type": "audio", "channels": 2, "disposition": ${d(1)}, "tags": {"language": "ita"}},
        {"index": 2, "codec_name": "aac", "codec_type": "audio", "channels": 2, "disposition": ${d(0)}, "tags": {"language": "ita", "title": "Stereo", "JELLYSTRUCTURE_COPY_OF": "143302914"}}],
        "format": {"format_name": "matroska,webm", "duration": "113.120000"}}"""

    private val dtsEpisode = """{"streams": [
        {"index": 0, "codec_name": "h264", "codec_type": "video", "r_frame_rate": "24000/1001", "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 1, "codec_name": "dts", "codec_type": "audio", "channels": 6, "disposition": ${d(1)}, "tags": {"language": "eng", "title": "DTS"}}],
        "format": {"format_name": "matroska,webm", "duration": "1304.352000"}}"""

    private val dvClip = """{"streams": [
        {"index": 0, "codec_name": "hevc", "codec_type": "video", "r_frame_rate": "24000/1001", "disposition": ${d(1)}, "tags": {"language": "eng"},
         "side_data_list": [{"side_data_type": "DOVI configuration record", "dv_profile": 7, "el_present_flag": 1, "dv_bl_signal_compatibility_id": 6}]},
        {"index": 1, "codec_name": "truehd", "codec_type": "audio", "channels": 8, "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 2, "codec_name": "ac3", "codec_type": "audio", "channels": 6, "disposition": ${d(0)}, "tags": {"language": "eng"}},
        {"index": 3, "codec_name": "ac3", "codec_type": "audio", "channels": 2, "disposition": ${d(0)}, "tags": {"language": "eng", "title": "Commentary by the director"}},
        {"index": 4, "codec_name": "hdmv_pgs_subtitle", "codec_type": "subtitle", "disposition": ${d(1)}, "tags": {"language": "eng"}}],
        "format": {"format_name": "matroska,webm", "duration": "20.103000"}}"""

    private val dvVersion = """{"streams": [
        {"index": 0, "codec_name": "hevc", "codec_type": "video", "r_frame_rate": "24000/1001", "disposition": ${d(1)}, "tags": {"language": "eng"},
         "side_data_list": [{"side_data_type": "DOVI configuration record", "dv_profile": 8, "el_present_flag": 0, "dv_bl_signal_compatibility_id": 1}]},
        {"index": 1, "codec_name": "truehd", "codec_type": "audio", "channels": 8, "disposition": ${d(1)}, "tags": {"language": "eng"}},
        {"index": 2, "codec_name": "ac3", "codec_type": "audio", "channels": 6, "disposition": ${d(0)}, "tags": {"language": "eng"}},
        {"index": 3, "codec_name": "ac3", "codec_type": "audio", "channels": 2, "disposition": ${d(0)}, "tags": {"language": "eng", "title": "Commentary by the director"}},
        {"index": 4, "codec_name": "hdmv_pgs_subtitle", "codec_type": "subtitle", "disposition": ${d(1)}, "tags": {"language": "eng"}}],
        "format": {"format_name": "matroska,webm", "duration": "20.104000"}}"""

    private fun facts(json: String, path: String = "/media/Film (2020)/Film (2020).mkv") = assertNotNull(parseProbe(path, json)).facts

    // ── 1. Eligibility per kind ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `kind A adds one AAC stereo track per language with nothing a Chromecast plays`() {
        val v = planAudio(FixKind.STEREO, facts(ac3Film))
        assertEquals("add", v.action)
        val t = v.tracks.single()
        assertEquals(0, t.sourceOrder); assertEquals("ita", t.language); assertEquals("aac", t.codec); assertEquals(2, t.channels)
        assertEquals(256, t.bitrateKbps); assertEquals(STEREO_NAME, t.name)
        assertTrue(t.describe().startsWith("+ AAC 2.0 "), t.describe())
    }

    @Test fun `a language that already has a playable track or our copy gets nothing`() {
        assertEquals("done", planAudio(FixKind.STEREO, facts(ac3FilmWithCopy)).action)
        val withAac = facts(ac3Film).let { it.copy(audio = it.audio + AudioFacts(1, "aac", "ita", 2)) }
        assertEquals("skip", planAudio(FixKind.STEREO, withAac).action)
    }

    @Test fun `kind B adds E-AC-3 5_1 where a language has only lossless or DTS surround`() {
        val v = planAudio(FixKind.SURROUND, facts(dtsEpisode))
        assertEquals("add", v.action)
        val t = v.tracks.single()
        assertEquals("eac3", t.codec); assertEquals(6, t.channels); assertEquals(640, t.bitrateKbps); assertEquals(SURROUND_NAME, t.name)
        // The DV clip has AC-3 5.1 beside its TrueHD: no surround copy needed.
        assertEquals("skip", planAudio(FixKind.SURROUND, facts(dvClip)).action)
    }

    @Test fun `commentary and audio description are never sources`() {
        val f = FileFacts("/x.mkv", "matroska", 60_000, null, listOf(
            AudioFacts(0, "truehd", "eng", 8, title = "Commentary with the cast", commentary = true),
            AudioFacts(1, "truehd", "eng", 8, title = "Main", default = true),
            AudioFacts(2, "dts", "dan", 6, description = true),
        ))
        val a = planAudio(FixKind.STEREO, f)
        assertEquals(listOf(1), a.tracks.map { it.sourceOrder })   // English from the main track; Danish has only AD
        assertTrue(isCommentary("Commentary by the director"))
        assertTrue(isDescription("Synstolkning"))
    }

    @Test fun `an untagged source gives an untagged copy - and MP4 is skipped`() {
        val f = FileFacts("/x.mkv", "matroska", 60_000, null, listOf(AudioFacts(0, "eac3", null, 6, default = true)))
        assertNull(planAudio(FixKind.STEREO, f).tracks.single().language)
        val mp4 = f.copy(container = "mov")
        assertEquals("skip", planAudio(FixKind.STEREO, mp4).action)
        assertTrue(planAudio(FixKind.STEREO, mp4).reason!!.startsWith("MP4"))
    }

    @Test fun `kind C is a profile 7 film named after its folder`() {
        // `Film (2020) Remux-2160p` is NOT a version name to Jellyfin (its cleaner leaves `Remux`); ` - ` is.
        assertFalse(namedAfterFolder("/media/Film (2020)/Film (2020) Remux-2160p.mkv"))
        val path = "/media/Film (2020)/Film (2020) - Remux-2160p.mkv"
        val f = facts(dvClip, path)
        assertEquals(7, f.video?.dvProfile); assertTrue(f.video!!.dvElPresent)
        assertEquals("add", planDolbyVision(f, isFilm = true, otherVideosInFolder = listOf(path)).action)
        assertEquals("skip", planDolbyVision(f, isFilm = false, otherVideosInFolder = listOf(path)).action)
        assertEquals("done", planDolbyVision(f, true, listOf(path, dvVersionPath(path))).action)
        // A scene-named original: Jellyfin would show the copy as a second film.
        val scene = facts(dvClip, "/media/Film (2020)/Film.2020.2160p.UHD.BluRay.REMUX.DV.mkv")
        val v = planDolbyVision(scene, true, listOf(scene.path))
        assertEquals("skip", v.action); assertTrue(v.reason!!.contains("second film"))
        // Our own version is never a source.
        assertEquals("skip", planDolbyVision(facts(dvVersion, dvVersionPath(path)), true, listOf(path)).action)
    }

    @Test fun `Jellyfin's multi-version name rule`() {
        assertTrue(namedAfterFolder("/m/Film (2020)/Film (2020).mkv"))
        assertTrue(namedAfterFolder("/m/Film (2020)/Film (2020) - Directors Cut.mkv"))
        assertTrue(namedAfterFolder("/m/Film (2020)/film (2020) 2160p.mkv"))
        assertFalse(namedAfterFolder("/m/Film (2020)/Film.2020.2160p.mkv"))
        assertFalse(namedAfterFolder("/m/Film (2020)/Film (2020)abc.mkv"))
        assertEquals("/m/Film (2020)/Film (2020) - Dolby Vision.mkv", dvVersionPath("/m/Film (2020)/Film (2020) Remux.mkv"))
    }

    // ── 2. Command lines ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the encode maps the file's own audio stream into a work file - never default - tagged`() {
        val video = "/media/Film (2020)/Film (2020).mkv"
        val t = planAudio(FixKind.STEREO, facts(ac3Film, video)).tracks.single()
        val out = FileFixCommands.addedTrackPath(video, 0)
        assertEquals("/media/Film (2020)/.jellystructure/addaudio_Film (2020).mkv.0.mka", out)
        val cmd = FileFixCommands.encodeTrack(video, t, "143302914", "2026-10-08", out)
        assertTrue(cmd.contains("-map 0:a:0 "), cmd)
        assertTrue(cmd.contains("-c:a aac -b:a 256k -ac 2 "), cmd)
        assertTrue(cmd.contains("language='ita'"), cmd)
        assertTrue(cmd.contains("title='Stereo'"), cmd)
        assertTrue(cmd.contains("JELLYSTRUCTURE_COPY_OF='143302914'"), cmd)
        assertTrue(cmd.contains("JELLYSTRUCTURE_ADDED='2026-10-08'"), cmd)
        assertTrue(cmd.contains("-disposition:a:0 0 "), cmd)
        assertTrue(cmd.startsWith("nice -n 19 ionice -c3 "), cmd)
        val mux = FileFixCommands.appendTracks(video, listOf(out), WorkFiles.pathFor(video, WorkFiles.Kind.FILE_FIX))
        assertTrue(mux.contains("'/media/Film (2020)/Film (2020).mkv' --default-track-flag 0:no --forced-display-flag 0:no "), mux)
        assertTrue(mux.contains("/.jellystructure/filefix_Film (2020).mkv"), mux)
    }

    @Test fun `kind C's commands keep the original's timestamps and write into the work folder`() {
        val video = "/media/Film (2020)/Film (2020).mkv"
        val hevc = WorkFiles.pathFor(video, WorkFiles.Kind.DV_HEVC)
        assertEquals("/media/Film (2020)/.jellystructure/dvhevc_Film (2020).mkv.hevc", hevc)
        val conv = FileFixCommands.dvConvert(video, "/usr/local/bin/dovi_tool", hevc, "$hevc.rc")
        assertTrue(conv.contains("-bsf:v hevc_mp4toannexb -f hevc -"), conv)
        assertTrue(conv.contains("-m 2 convert --discard -"), conv)
        assertTrue(conv.endsWith("= 0 ]"), "ffmpeg's own exit status is checked: $conv")
        val mux = FileFixCommands.dvMux(hevc, "ts", video, "eng", "tags.xml", "out.mkv")
        assertTrue(mux.contains("--timestamps 0:'ts'") && mux.contains("--no-video '$video'") && mux.contains("--global-tags 'tags.xml'"), mux)
        assertTrue(FileFixCommands.dvTagsXml("2026-10-08", true).contains("<Name>JELLYSTRUCTURE_DV</Name><String>8.1-from-7-el-dropped</String>"))
    }

    @Test fun `every work file reads back to its library file - so the 311 sweep can tell whose it is`() {
        val video = "/media/Show/Season 1/Show - S01E01.mkv"
        for (k in WorkFiles.Kind.entries) {
            assertEquals(video, WorkFiles.libraryPathOf(WorkFiles.pathFor(video, k)), k.name)
            if (k.extension != null) assertEquals(video, WorkFiles.libraryPathOf(WorkFiles.pathFor(video, k, 3)), "${k.name} part")
        }
    }

    @Test fun `the sidecar name Jellyfin attaches`() {
        val t = PlannedTrack(0, "ita", "ac3", 2, "aac", 2, 256, STEREO_NAME, 0)
        assertEquals("/m/Film (2020)/Film.2020.1080p.ita.Stereo.mka", sidecarPath("/m/Film (2020)/Film.2020.1080p.mkv", t))
        assertEquals("/m/F/f.Surround.mka", sidecarPath("/m/F/f.mkv", t.copy(language = null, codec = "eac3")))
    }

    // ── 3. Verification ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an appended file passes only with every original stream unchanged and the new one last`() {
        val before = assertNotNull(parseProbe("a", ac3Film))
        val after = assertNotNull(parseProbe("b", ac3FilmWithCopy))
        val t = planAudio(FixKind.STEREO, before.facts).tracks
        assertNull(verifyAppended(before.streams, before.facts.durationMs, after.streams, after.facts.durationMs, t))
        // a missing stream
        assertNotNull(verifyAppended(before.streams, 113_120, after.streams.drop(1), 113_120, t))
        // a shifted duration
        assertNotNull(verifyAppended(before.streams, 113_120, after.streams, 113_400, t))
        // a changed language on an original
        val relabelled = after.streams.toMutableList().also { it[1] = it[1].copy(language = "eng") }
        assertNotNull(verifyAppended(before.streams, 113_120, relabelled, 113_120, t))
        // the added track marked default
        val defaulted = after.streams.toMutableList().also { it[2] = it[2].copy(default = true) }
        assertNotNull(verifyAppended(before.streams, 113_120, defaulted, 113_120, t))
    }

    @Test fun `a version passes with profile 8_1 - no EL and the same frame count`() {
        val before = assertNotNull(parseProbe("c", dvClip))
        val after = assertNotNull(parseProbe("v", dvVersion))
        assertNull(verifyDvVersion(before.streams, 481, after.streams, 481, after.facts.video))
        assertNotNull(verifyDvVersion(before.streams, 481, after.streams, 480, after.facts.video))
        assertNotNull(verifyDvVersion(before.streams, 481, after.streams, 481, before.facts.video))
        assertNotNull(verifyDvVersion(before.streams, 481, after.streams.dropLast(1), 481, after.facts.video))
    }

    // ── Radarr, the UIDs, the date ────────────────────────────────────────────────────────────────────────────────

    @Test fun `Radarr may never read the copy as better than the original`() {
        // 2026-10-08: every real DV 7 original parsed as Remux-2160p (or Unknown), every copy name as Unknown, score 0.
        assertNull(radarrUnsafe(ArrParseResult("Remux-2160p", 0), ArrParseResult("Unknown", 0)))
        assertNull(radarrUnsafe(ArrParseResult("Unknown", 0), ArrParseResult(null, 0)))
        assertNotNull(radarrUnsafe(ArrParseResult("Remux-2160p", 0), ArrParseResult("Bluray-2160p", 0)))
        assertNotNull(radarrUnsafe(ArrParseResult("Remux-2160p", 0), ArrParseResult("Unknown", 50)))
    }

    @Test fun `mkvmerge identification gives each audio track's UID in the file's audio order`() {
        val j = """{"tracks": [{"type": "video", "properties": {"uid": 1}}, {"type": "audio", "properties": {"uid": 143302914}},
            {"type": "subtitles", "properties": {"uid": 7}}, {"type": "audio", "properties": {"uid": 16944767297332401581}}]}"""
        assertEquals(mapOf(0 to "143302914", 1 to "16944767297332401581"), parseTrackUids(j))
    }

    // ── 5. The right track for each device (FR-314-5) ─────────────────────────────────────────────────────────────

    private val withCopies = listOf(
        JellyfinAudio(3, "truehd", "eng", null, isDefault = true),
        JellyfinAudio(4, "aac", "eng", "Stereo", isDefault = false),
        JellyfinAudio(5, "eac3", "eng", "Surround 5.1", isDefault = false),
        JellyfinAudio(6, "ac3", "dan", null, isDefault = false),
    )

    @Test fun `a device that decodes the source keeps it`() {
        assertNull(copyForDevice(withCopies, null, listOf("truehd", "eac3", "ac3", "aac")))
        assertNull(copyForDevice(withCopies, 3, emptyList()), "a client that declares nothing changes nothing")
    }

    @Test fun `a device that can't gets the copy of the same language - surround first`() {
        assertEquals(5, copyForDevice(withCopies, null, listOf("aac", "eac3", "ac3")))
        assertEquals(4, copyForDevice(withCopies, 3, listOf("aac", "mp3")), "a Chromecast: the stereo AAC copy")
        assertNull(copyForDevice(withCopies, 6, listOf("aac")), "no Danish copy: nothing to swap")
        // A sidecar's title is the file name's token (`Stereo`, `Surround`).
        val sidecar = listOf(JellyfinAudio(0, "dts", "eng", null, true), JellyfinAudio(1, "eac3", "eng", "Surround", false))
        assertEquals(1, copyForDevice(sidecar, null, listOf("eac3", "aac")))
    }

    @Test fun `the Dolby Vision version is picked for a device without dual-layer DV`() {
        val sources = listOf("a" to "/m/F (2020)/F (2020) - Remux.mkv", "b" to "/m/F (2020)/F (2020) - Dolby Vision.mkv")
        assertEquals("b", dvVersionSource(sources, deviceDecodesEl = false))
        assertNull(dvVersionSource(sources, deviceDecodesEl = true))
        assertNull(dvVersionSource(sources.take(1), deviceDecodesEl = false))
    }

    // ── 6. Radarr and Sonarr ──────────────────────────────────────────────────────────────────────────────────────

    /** `MediaFileExtensions.cs` on Radarr's and Sonarr's `develop`, read 2026-10-08: what their disk scans take as video. */
    private val radarrVideo = ".webm .m4v .3gp .nsv .ty .strm .rm .rmvb .m3u .ifo .mov .qt .divx .xvid .bivx .nrg .pva .wmv .asf .asx .ogm .ogv .m2v .avi .bin .dat .mpg .mpeg .mp4 .avc .vp3 .svq3 .nuv .viv .dv .fli .flv .wpl .img .iso .vob .mkv .mk3d .ts .wtv .m2ts".split(' ')
    private val sonarrVideo = ".webm .m4v .3gp .nsv .ty .strm .rm .rmvb .m3u .ifo .mov .qt .divx .xvid .bivx .nrg .pva .wmv .asf .asx .ogm .ogv .m2v .avi .bin .dat .mpg .mpeg .mp4 .avc .vp3 .svq3 .nuv .viv .dv .fli .flv .wpl .img .iso .vob .mkv .ts .wtv .m2ts".split(' ')

    @Test fun `a mka sidecar is never a video file to Radarr or Sonarr - but the DV version is`() {
        val sidecar = sidecarPath("/m/F/f.mkv", PlannedTrack(0, "eng", "dts", 6, "aac", 2, 256, STEREO_NAME, 0))
        val ext = "." + sidecar.substringAfterLast('.')
        assertFalse(ext in radarrVideo); assertFalse(ext in sonarrVideo)
        // The version IS a video file to Radarr: that's why its name is read through Radarr's /parse before it is written.
        assertTrue("." + dvVersionPath("/m/F (2020)/F (2020).mkv").substringAfterLast('.') in radarrVideo)
    }

    @Test fun `the added date in UTC`() {
        assertEquals("2026-10-08", isoDateUtc(1_791_460_000))
        assertEquals("1970-01-01", isoDateUtc(0))
        assertEquals("2000-02-29", isoDateUtc(951_782_400))
    }
}
