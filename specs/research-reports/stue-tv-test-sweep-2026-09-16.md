# Stue-TV test sweep — the ten most recently implemented phases

**Date:** 2026-09-16
**Device:** stue TV (Sony BRAVIA, `ro.product.model` = `BRAVIA_4K_VH21`, `10.10.11.128:5555`), profile `jogvan`
**Method:** live adb session — D-pad driving, `uiautomator dump` for the semantics tree, `screencap`
for pixels, `dumpsys media_session` for playback state, plus prod backend logs and a read-only copy
of the production DB (`~/jellystructure/config/jellystructure.db`).
**Status:** research only. Nothing was fixed, no spec was written, nothing was deployed.

---

## 0. The deployment gap this sweep ran into first

This is the most important context for everything below, and it was not knowable before testing.

| Component | What is running | Evidence |
|---|---|---|
| **Backend** (`jelly.jebster.net`) | `main` as of **2026-09-14 ~14:34**, i.e. **through phase 210** | `/app/jellystructure` binary mtime `2026-09-14 14:34`; image created `2026-09-14T14:41`. Phase 210 landed 11:29, phase 211 landed 16:22. |
| **Ravilo TV app** | **v1.17** (`4e428862`), installed `2026-09-14 16:34` | `dumpsys package … lastUpdateTime`; R242's backdrop renders on-device, and R242 (`5b38ca98`, v1.16) is a descendant of R241 (`baa57f84`, v1.15). |

Independent confirmation that the backend predates 213: `GET /api/health` returns no `job_queues`
block, which `Server.kt:397` emits unconditionally once 213 is in.

**So of the ten most recently implemented phases, only five are actually live:**

| Phase | Built | Live on prod? | Testable on the TV? | Verdict |
|---|---|---|---|---|
| **R241** — remembered track across ISO-639 granularities | 09-14 | ✅ client | yes | ❌ **still reproducible** — see F1 |
| **R242** — J's own backdrop | 09-14 | ✅ client | yes | ✅ works, 2 visual defects (F5, F6) |
| **208** — migrate off undocumented Jellyfin routes | 09-13 | ✅ | indirectly | ✅ no regression observed |
| **209** — bulk subtitle re-order no-op on sidecars | 09-14 | ✅ | no (admin) | not exercised |
| **210** — `prewarm_subtitles` fake success | 09-14 | ✅ | no (backend) | ✅ reporting looks honest — see F9 |
| **211** — `PlaystateCache` never fetched an episode id | 09-14 | ❌ **not deployed** | yes (symptom) | ❌ **symptom is live** — see F2 |
| **212** — Jellyfin settings advisor | 09-15 | ❌ not deployed | no (admin) | untested |
| **213** — shared job-queue pool | 09-15 | ❌ not deployed | no | untested |
| **214** — stopping background work | 09-15 | ❌ not deployed | no (admin) | untested |
| **215** — memory budget calculator | 09-15 | ❌ not deployed | no (admin) | untested |

Four phases (212–215) are admin-web surfaces and would not be testable from a TV even if deployed.
**Recommendation: deploy `main` before any further verification pass** — right now the client is
one phase ahead of the backend, which is itself the cause of F2.

---

## Findings

Severity: **P1** = viewer-visible broken behaviour · **P2** = viewer-visible wrong/confusing ·
**P3** = polish / log noise.

---

### F1 — P1 — The remembered subtitle for a series is never applied to the next episode. R241 did not fix it.

**This is the original 2026-09-14 user report, verbatim reproducible on today's build**, on the exact
title R241 was written against.

**Repro A (the reported one):**
1. *It's Always Sunny in Philadelphia* → Season 17 → **E07** → play.
2. Audio & Subs → Subtitles → **Dansk** → Danish subtitles appear. ✅
3. Jump to **E08** (episode rail, same season).
4. Subtitle picker shows **✓ English**, Dansk unselected. Danish was not remembered. ❌

**Repro B (independent, rules out the SDH explanation):**
1. Same series → Season 8 → **E04** → play. Picker opens on **✓ Off** — i.e. the Dansk pick from
   Repro A had already failed to carry here too, even though E04 has a Danish track.
2. Select **Dansk** → Danish subtitles appear. ✅
3. Advance to **S08E09**.
4. Picker shows **✓ Off** again, with Dansk sitting right there unselected. ❌

Three separate episode transitions, three failures. A manual pick always works; it just never survives.

**Why the existing diagnosis is incomplete.** R241 attributes this to ISO-639 granularity
(`dan` vs `da`) and fixes it with `sameLanguage()` (`PlayerScreen.kt:3480`), reusing `languageName()`.
That helper does map both `da` and `dan` to `"Danish"`, so the stated mechanism should work — and the
unit test at `PlayerScreenTrackResolutionTest.kt:210` passes. The device disagrees.

**Two things the spec's DB analysis missed**, both checked against the production DB today:

1. **The `dan`/`da` split is not a tagging inconsistency — it is embedded-vs-sidecar.** Across all 183
   episodes: **77 `dan` tracks, every one embedded; 16 `da` tracks, every one an external sidecar**
   (`external: true`, `specifier: "ext:s:0"`, codec `srt`). Zero embedded `da`, zero external `dan`.
   The "granularity mismatch" is a perfect proxy for "the subtitle lives in a separate file".
2. **What is actually selected is exactly what the source-default fallback tier would pick.** In
   `resolveTrackChoice`, when `tierSub` returns `null` both tiers fall through to
   `subtitleTracks.firstOrNull { it.isDefault }`, then to a forced track, then to `-1` (Off):
   - S17E08 flags its English track `default: true` → **English** was selected. ✔ matches
   - S08E04 and S08E09 flag **nothing** default → **Off** was selected. ✔ matches

   That is the signature of `tierSub` returning `null` every time, i.e. the remembered choice is
   either never stored or never matched — not of a near-miss inside the language group.

**Concrete lead to check first** (`PlayerScreen.kt:565`, inside `persistChoice`):

```kotlin
val seriesKey = currentSeriesId ?: currentItemId
```

If `currentSeriesId` is null while an episode plays, every episode persists under **its own item id**,
so a per-series memory can never be read back by a sibling episode. `setGlobalChoice` is called too,
so the global tier should still have carried Danish — unless it is being overwritten by each
episode's own auto-resolution. Worth instrumenting both `seriesKey` and the resolved
`RememberedChoice` on entry to `resolveTrackSelection` before changing any matching logic.

**Note for whoever picks this up:** the unit test proves the comparison, not the plumbing. A
regression test for this needs to assert on what `persistChoice` writes and what
`resolveTrackSelection` reads back across two different episode item ids, with at least one external
sidecar track in the fixture.

---

### F2 — P1 — A series' own progress never updates. Phase 211 is built but not deployed.

After watching part of **five** episodes in one session (S17E07, S17E08, S08E04, S08E07, S08E09), the
series detail page still read:

```
27 of 183 episodes watched
Resume · S3E9
```

— byte-identical to before the session. Meanwhile the Home **Continue Watching** row (after an app
restart) correctly showed **S17:E8** as the most recent. Two surfaces in the same app, disagreeing
about where the viewer is in the same series.

This is precisely phase **211**'s described defect ("`PlaystateCache` never fetched an episode id, so
every series lost its own progress and next-up"). 211 is `✓ Built` and committed (`4e428862`) but is
**not on the running backend**. No new work needed — **this closes on deploy**, and is the single
strongest argument for deploying `main`.

---

### F3 — P2 — Continue Watching does not refresh after playback within a live session.

Returning to Home straight after watching two episodes, *It's Always Sunny* was **not in the Continue
Watching row at all** — not reordered, absent. Scanning seven tiles right found no trace of it. After
`am force-stop` + relaunch it was **tile 1**.

So the data was correct server-side; the client never re-fetched. Related signal from the prod log
during that exact window:

```
[WARN] Jellyfin reportPlaybackProgress failed: Timed out waiting for 6000 ms
```

Worth deciding deliberately: should leaving the player invalidate the client Home-feed cache (R212)?
Today a viewer who finishes an episode and presses Back sees a Home screen that does not know it
happened.

---

### F4 — P2 — On a Continue Watching tile, the "Soon" badge paints over the episode badge.

The *It's Always Sunny* tile carries **two** badges at overlapping coordinates:

```
'S17:E8'        [97,329][173,357]
'Soon • S18E07' [117,331][276,359]
```

Visually only `Soon • S18E07` is readable — the upcoming-episode badge is drawn on top of the
resume-position badge. On the Continue Watching row specifically, "which episode am I on" is the one
thing the tile exists to say, and it is the thing that gets hidden. Either stack them, suppress
`Soon` on a Continue Watching tile, or merge them into one line.

---

### F5 — P2 — R242's backdrop scrim is too weak to read the panel over. (Closes the spec's own open question.)

R242 ships with "exact scrim contrast needs device verification" as an open question. **Verified on
the stue TV: it needs more scrim.** Over bright backdrops (*The Curse of Oak Island*, *Tomgang*) the
J panel's synopsis — grey body text — sits directly on high-frequency, high-luminance image detail
and is genuinely hard to read at sofa distance. The row headings above and below ("Continue Watching",
"Newly Added — Movies") lose contrast at the same time, and the Collections rail scrolled under the
translucent app bar becomes visible clutter behind the nav items.

Everything else about R242 checks out on-device:
- FR-R242-4 full-screen, fixed to the viewport, behind the app bar ✅
- FR-R242-5 lateral hop crossfades without dropping to `--bg` ✅ (and correctly skips the dwell)
- FR-R242-6 fades out on leaving the row ✅

---

### F6 — P2 — The J panel's text runs to x=1920 — no TV safe-area margin.

From the semantics tree, the focus-detail synopsis:

```
'I comedy serien "Tomgang" …'   [864,585][1920,761]
'Follow brothers Marty and …'   [864,585][1920,761]
```

The text box ends at **1920** — the exact right edge of the frame. Every other element respects a
margin: row tiles stop at ~1880, the app bar avatar at ~1820. On any set with overscan the last
characters of each line are cut off, and it already reads as crowded on a set without.

---

### F7 — P2 — Unmapped languages render as raw codes in the subtitle picker: `EL`, `HBS-SRP`, `HBS-HRV`.

Observed live in three different files. The flag is correct (the flag map covers `gre`), only the
**name** falls back:

- Greek → **`EL`** (should be `Ελληνικά`)
- Serbian → **`HBS-SRP`**
- Croatian → **`HBS-HRV`**

Root cause confirmed by reading both maps: `RAVILO_ENDONYMS` (`PlayerScreen.kt:3217`) and
`LANGUAGE_NAMES` (`RaviloPlayer.kt:154`) contain **no** Greek, Serbian or Croatian entry at all.
`HBS-SRP`/`HBS-HRV` also shows that the codes reaching the picker are **ExoPlayer-normalised**
(`sr` → `hbs-srp`, the Serbo-Croatian macrolanguage), a shape neither map anticipates.

That matters beyond cosmetics: **`sameLanguage()` — R241's whole fix — is built on `languageName()`**.
For any language missing from that map it degrades to a raw string compare, so the same gap that
prints `EL` also means a remembered Greek/Serbian/Croatian subtitle cannot survive a code change.
Fixing the map fixes both.

Related: an unmapped language falls back to a **microphone glyph** where the flag goes. On a
*subtitle* row a microphone reads as "audio" — the wrong idea. (R206 addressed the blank/mic row for
an unmapped language; the mic is still what renders here.)

---

### F8 — P3 — Player chrome auto-hides fast enough to make D-pad sequences unreliable.

Repeatedly during testing, the first keypress after a short pause was swallowed re-showing the
chrome, so an identical key sequence produced different results depending on timing. Concretely: I
twice intended "→ → Audio & Subs" and instead hit **>> Next**, skipping from S08E04 to E07 to E09 and
losing my place in the episode.

A viewer reaching for the subtitle picker can lose their position the same way. The controls sit
directly beside each other and one of them is destructive to the current playback position.

Two smaller things in the same area:
- **Subtitles are not lifted above the transport chrome.** With controls up, the subtitle line renders
  inside the same horizontal band as the button row.
- **Leaving the player from the picker takes three BACK presses** (picker → ? → player → detail); the
  middle press appears to do nothing visible.

---

### F9 — P3 — `prewarm_subtitles` reports `0 subtitle stream(s) warmed` on every run — and that is correct.

Every run in the last 24 h logs `0 subtitle stream(s) warmed`, including one over 12 items. Given that
phase 207 is literally titled "`prewarm_subtitles` has never warmed a subtitle", this looks alarming —
**but it checks out**. The most recently examined items are kids' shows and concert videos
(*Bananer i pyjamas*, *Teletubbies*, *U2 Zoo TV*, …) and every one has **zero embedded text subtitle
streams**. `warmedCountOf` filters to `!isExternal` text subs, so 0 is the honest answer.

The residual issue is the one 207 set out to kill and did not fully: the run-level log line still
cannot distinguish "nothing needed warming" from "everything failed" or "deferred behind playback".
Phase **213** adds per-queue reporting and deferral visibility — another reason to deploy.

---

### F10 — P3 — `/api/tv/events` logs an unhandled route exception on every client disconnect.

```
[ERROR] Unhandled route exception on /api/tv/events: ECONNRESET (104): Connection reset by peer   ×43 in 24h
```

A TV closing its event stream is normal lifecycle, not an error. 43 of these in a day is noise that
will mask a real unhandled-route exception.

---

### F11 — P3 — Background refreshers (phases 204/205) time out against Jellyfin regularly.

In 24 h of prod logs:

```
[WARN] Playstate refresh failed: Timed out waiting for 6000 ms          ×18 (+8 at 5000 ms)
[WARN] Continue Watching refresh failed: Timed out waiting for 6000 ms  ×15 (+3)
[WARN] Jellyfin reportPlaybackProgress failed: Timed out …              ×10
[INFO] Continue Watching refresh: 1 refreshed, 3 failed, 1 never built
```

Mostly self-healing by design, but `reportPlaybackProgress` timing out is a **lost write**, not a
retryable read — it is a plausible contributor to F3, and it means a viewer's position can silently
fail to persist.

---

### F12 — P3 — Two stale integration warnings on every relevant event.

```
[WARN] Webhook: radarr webhook is deprecated (Phase 165) — set up the Jellyfin webhook in Settings instead
[WARN] Webhook delivery failed: Could not connect to http://10.10.10.10:8585 (CURLE_COULDNT_CONNECT)  ×8
```

Radarr is still pointed at the deprecated ingest path, and something is configured to POST to
`10.10.10.10:8585`, where nothing is listening. Both are configuration debt, not code defects.

---

### F13 — P3 — Browsing a season spawns one ffmpeg per episode still, on the interactive path.

Scrolling Season 17 produced a burst of per-episode `ffmpeg … -vf scale=640:-2` artwork resizes, one
per still, synchronous with browsing. It is a cold-cache cost and presumably cached afterwards, but
given the 2026-09-15 incident (concurrent ffmpeg saturating the disk) it is worth confirming these
are gated by the interactive `ProcessGate` reserve and not the background pool.

---

### F14 — P3 — Smaller UX observations

- **Hero metadata shows a foreign-country certification verbatim** — *Two and a Half Men* renders
  `Från 15 år`, and a detail badge reads `SE Från 7 år`, in a Danish/Faroese household. The country
  prefix makes it honest, but it is worth a decision: phase 155 normalises these to numbers for the
  Maturity filter, and the hero/badge deliberately does not.
- **A hero synopsis is in Croatian** ("Poznati hrvatski poduzetnik na svoj rođendan saznaje…") —
  metadata-language resolution picking Croatian, the exact class of problem phase 184 exists for.
- **Discover cards say "In Cinemas" twice**, once as a poster overlay and once as the subtitle.
- **Player episode-rail titles are hard-clipped with no ellipsis** ("7. The Gang Gets Ready for").
- **An English audio track on an English-language show is badged "Dubbed"** in the picker
  (*It's Always Sunny*, `eng` audio, `originalLanguage: en`). Worth checking what drives that badge.
- **The focused season pill sits 25 px from the right edge** when it is the last visible one; the row
  does not scroll it inward.

---

## 2. One configuration finding, not a defect

The production global Ravilo config has:

```
focus_detail_delay_ms = 3200
```

(`ravilo_config`, `__global__`; `focus_detail_line` and `focus_detail_row_open` are absent, i.e. both
at their `true` defaults.) The shipped default is **170 ms**.

At 3.2 s the focus detail effectively never appears during ordinary browsing — I initially recorded J
as "not rendering at all" and only found it by holding focus for five seconds. Phase 202 removed the
600 ms ceiling deliberately, so this is a legal value, and it may well be an intentional owner setting
to suppress the reflow cost. **Flagging it rather than changing it** — but if it was not deliberate,
J and R242 are both invisible in practice on this household's TV.

---

## 3. What this sweep could not cover

- **212, 213, 214, 215** — not deployed, and all four are admin-web surfaces with no TV presence.
- **209** — deployed but admin-only (bulk subtitle re-order); no TV surface.
- **208** — no direct surface. Exercised indirectly and cleanly: item detail, season/episode lists and
  playback negotiation all worked, both `DIRECT PLAY` and `HLS` sessions were observed, and no route
  errors appeared in the logs during the session.
- **R242 with a null `backdropUrl`** (FR-R242-2's gradient fallback) — could not force the case.

## 4. Side effects left on the household library

Testing left **partial watch progress on five episodes** of *It's Always Sunny in Philadelphia*
(S17E07, S17E08, S08E04, S08E07, S08E09, each a few minutes in). *It's Always Sunny* is now the first
tile in Continue Watching. Nothing else was modified — no config was written, no deploy or restart was
performed, and the app was left on the Home screen.
