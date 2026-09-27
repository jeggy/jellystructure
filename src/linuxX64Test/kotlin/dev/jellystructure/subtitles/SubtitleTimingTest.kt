package dev.jellystructure.subtitles

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 273 — the check's core on synthetic subtitles only (dev review item 3: no subtitle text or library title
 * enters the repo). A "subtitle" is a dialogue-shaped cue track: lines of 1–5 s, short gaps, now and then a long
 * stretch of music.
 */
class SubtitleTimingTest {

    private fun episode(seed: Int, lengthMs: Long = 22 * 60_000L): List<Cue> {
        val r = Random(seed)
        val out = ArrayList<Cue>()
        var t = 5_000L + r.nextLong(0, 20_000)
        while (t < lengthMs - 30_000) {
            val len = r.nextLong(1_000, 5_000)
            out += Cue(t, t + len)
            t += len + if (r.nextInt(12) == 0) r.nextLong(20_000, 60_000) else r.nextLong(200, 6_000)
        }
        return out
    }

    /** The same dialogue subtitled by someone else: boundaries moved a little, some lines merged. */
    private fun otherLanguage(cues: List<Cue>, seed: Int): List<Cue> {
        val r = Random(seed)
        val out = ArrayList<Cue>()
        var i = 0
        while (i < cues.size) {
            val c = cues[i]
            if (i + 1 < cues.size && r.nextInt(5) == 0 && cues[i + 1].startMs - c.endMs < 1_500) {
                out += Cue(c.startMs + r.nextLong(-250, 250), cues[i + 1].endMs + r.nextLong(-250, 250)); i += 2
            } else {
                out += Cue(c.startMs + r.nextLong(-300, 300), c.endMs + r.nextLong(-300, 300)); i++
            }
        }
        return out
    }

    private fun shifted(cues: List<Cue>, ms: Long) = cues.map { Cue(it.startMs + ms, it.endMs + ms) }
    private fun scaled(cues: List<Cue>, k: Double) = cues.map { Cue((it.startMs * k).toLong(), (it.endMs * k).toLong()) }

    private data class Result(val own: SubtitleTiming.Fit, val judgement: Judgement)

    private fun check(candidate: List<Cue>, own: List<Cue>, neighbours: Map<String, List<Cue>> = emptyMap(), durationMs: Long = 22 * 60_000L): Result {
        val last = (listOf(candidate, own) + neighbours.values).maxOf { it.last().endMs }
        val plan = FftPlan(SubtitleTiming.sizeFor(maxOf(last, durationMs)))
        val cand = SubtitleTiming.candidateSpectra(candidate, plan)
        val ownFit = SubtitleTiming.bestFit(cand, SubtitleTiming.spectrum(SubtitleTiming.cueSignal(own, plan.n), plan), plan)
        val nFits = neighbours.mapValues { (_, cues) ->
            SubtitleTiming.bestFit(cand, SubtitleTiming.spectrum(SubtitleTiming.cueSignal(cues, plan.n), plan), plan)
        }
        val chunks = if (VerdictRules.fitsSubtitle(ownFit)) SubtitleTiming.chunkShiftsMs(candidate, ownFit, own) else emptyList()
        val j = VerdictRules.judge(ownFit, RefKind.EMBEDDED, nFits, candidate.first().startMs, candidate.last().endMs, durationMs, chunks = chunks)
        return Result(ownFit, j)
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `SRT with a BOM and CRLF and comma milliseconds parses and empty cues are dropped`() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,250\r\nHello <i>there</i>\r\n\r\n2\r\n00:01:00,000 --> 00:01:02,000 X1:10 X2:20\r\n<i></i>\r\n\r\n3\r\n01:00:00,000 --> 01:00:01,5\r\nLast\r\n"
        val cues = CueParser.parse(srt)
        assertEquals(listOf(Cue(1_500, 3_250), Cue(3_600_000, 3_601_500)), cues)
    }

    @Test
    fun `WebVTT with cue settings and short clocks parses`() {
        val vtt = "WEBVTT\n\nNOTE a comment\n\n00:01.000 --> 00:02.500 align:start position:10%\n- Yes.\n\n1\n00:00:03.000 --> 00:00:04.000\nNo\n"
        assertEquals(listOf(Cue(1_000, 2_500), Cue(3_000, 4_000)), CueParser.parse(vtt))
    }

    @Test
    fun `ASS dialogue follows the Format line and drops override-only events`() {
        val ass = """
            [Script Info]
            Title: x

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\i1}Hi, you{\i0}
            Comment: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,ignored
            Dialogue: 0,0:00:06.00,0:00:07.00,Default,,0,0,0,,{\pos(1,2)}
        """.trimIndent()
        assertEquals(listOf(Cue(1_500, 3_000)), CueParser.parse(ass))
    }

    // ── the transform ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the FFT correlation equals a direct one`() {
        val n = 64
        val r = Random(7)
        val a = DoubleArray(n) { if (it < 40) r.nextDouble() - 0.5 else 0.0 }
        val b = DoubleArray(n) { if (it < 40) r.nextDouble() - 0.5 else 0.0 }
        val plan = FftPlan(n)
        val fa = SubtitleTiming.spectrum(a, plan)
        val fb = SubtitleTiming.spectrum(b, plan)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (k in 0 until n) {
            val ar = fa.re[k]; val ai = -fa.im[k]
            re[k] = ar * fb.re[k] - ai * fb.im[k]; im[k] = ar * fb.im[k] + ai * fb.re[k]
        }
        plan.transform(re, im, inverse = true)
        for (lag in 0 until n) {
            var direct = 0.0
            for (t in 0 until n) direct += a[t] * b[(t + lag) % n]
            assertTrue(abs(direct - re[lag]) < 1e-9, "lag $lag: $direct vs ${re[lag]}")
        }
    }

    // ── verdicts ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `another language of the same edit is in sync`() {
        val ref = episode(1)
        val r = check(otherLanguage(ref, 2), ref)
        assertEquals(Verdict.IN_SYNC, r.judgement.verdict, "$r")
        assertTrue(r.own.rho >= 0.35)
    }

    @Test
    fun `a subtitle 50 s late is off with the shift that fixes it`() {
        val ref = episode(3)
        val r = check(shifted(otherLanguage(ref, 4), 50_000), ref)
        assertEquals(Verdict.OFF, r.judgement.verdict, "$r")
        assertTrue(abs(r.own.shiftMs + 50_000) <= 300, "shift ${r.own.shiftMs}")
        assertTrue((r.judgement.worstMs ?: 0) >= 49_000)
        assertFalse(VerdictRules.offered(r.judgement.verdict, r.judgement.reason, r.judgement.refKind, r.judgement.worstMs))
    }

    @Test
    fun `a subtitle timed for 25 fps is off by the PAL speed`() {
        val ref = episode(5)
        val r = check(scaled(otherLanguage(ref, 6), 23.976 / 25.0), ref)
        assertEquals(Verdict.OFF, r.judgement.verdict, "$r")
        assertTrue(abs(r.own.scale - 25.0 / 23.976) < 1e-9, "scale ${r.own.scale}")
    }

    @Test
    fun `another episode's subtitle is not this video and names the episode when it is a neighbour`() {
        val a = episode(10)
        val b = episode(11)
        val alone = check(otherLanguage(b, 12), a)
        assertEquals(Verdict.NOT_THIS_VIDEO, alone.judgement.verdict, "$alone")
        assertTrue(alone.own.rho < 0.2)
        val withNeighbour = check(otherLanguage(b, 12), a, mapOf("S01E02" to b, "S01E03" to episode(13)))
        assertEquals(Verdict.OTHER_EPISODE, withNeighbour.judgement.verdict)
        assertEquals("S01E02", withNeighbour.judgement.matchKey)
    }

    @Test
    fun `a cut that differs after ten minutes is off mid-file`() {
        val ref = episode(20)
        val cand = otherLanguage(ref, 21).map { if (it.startMs > 10 * 60_000) Cue(it.startMs + 4_000, it.endMs + 4_000) else it }
        val r = check(cand, ref)
        assertEquals(Verdict.OFF_MID_FILE, r.judgement.verdict, "$r")
    }

    @Test
    fun `cues running far past the end of the file are a longer video`() {
        val ref = episode(30)
        val longer = ref + shifted(episode(31), 22 * 60_000L)
        assertEquals(Verdict.LONGER_VIDEO, check(longer, ref).judgement.verdict)
    }

    @Test
    fun `speech fits the right subtitle and never condemns one alone`() {
        val cues = episode(40)
        val r = Random(41)
        val frames = ByteArray((22 * 60 * SubtitleTiming.HZ)) { k ->
            val t = k * SubtitleTiming.FRAME_MS
            val talking = cues.any { t >= it.startMs && t < it.endMs }
            ((if (talking) 70 else 10) + r.nextInt(-10, 20)).coerceIn(0, 100).toByte()
        }
        val plan = FftPlan(SubtitleTiming.sizeFor(22 * 60_000L))
        val ref = SubtitleTiming.spectrum(SubtitleTiming.speechSignal(frames, plan.n), plan)
        val right = SubtitleTiming.bestFit(SubtitleTiming.candidateSpectra(otherLanguage(cues, 42), plan), ref, plan)
        val jRight = VerdictRules.judge(right, RefKind.SPEECH, emptyMap(), 0, cues.last().endMs, 22 * 60_000L)
        assertEquals(Verdict.IN_SYNC, jRight.verdict, "$right")
        val wrong = SubtitleTiming.bestFit(SubtitleTiming.candidateSpectra(episode(43), plan), ref, plan)
        val jWrong = VerdictRules.judge(wrong, RefKind.SPEECH, emptyMap(), 0, episode(43).last().endMs, 22 * 60_000L)
        assertEquals(Verdict.CANT_TELL, jWrong.verdict)
        assertEquals(CantTell.WEAK, jWrong.reason)
        assertFalse(VerdictRules.offered(jWrong.verdict, jWrong.reason, jWrong.refKind, jWrong.worstMs), "speech doubt hides it")
    }

    @Test
    fun `a thin embedded track is not a reference`() {
        val thin = episode(50).filterIndexed { i, _ -> i % 12 == 0 }
        assertFalse(VerdictRules.usableReference(thin, 22 * 60_000L))
        assertTrue(VerdictRules.usableReference(episode(50), 22 * 60_000L))
    }

    @Test
    fun `offered keeps small offsets and every unsettled subtitle reference`() {
        assertTrue(VerdictRules.offered(Verdict.OFF, null, RefKind.EMBEDDED, 1_500))
        assertFalse(VerdictRules.offered(Verdict.OFF, null, RefKind.EMBEDDED, 2_000))
        assertTrue(VerdictRules.offered(Verdict.CANT_TELL, CantTell.WEAK, RefKind.EMBEDDED, null))
        assertTrue(VerdictRules.offered(Verdict.CANT_TELL, CantTell.NO_REFERENCE, null, null))
        assertFalse(VerdictRules.offered(Verdict.OTHER_EPISODE, null, RefKind.EMBEDDED, null))
        assertNotNull(Verdict.of("in_sync"))
    }
}
