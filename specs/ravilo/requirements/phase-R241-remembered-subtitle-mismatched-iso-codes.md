# Phase R241 — remembered subtitle/audio track doesn't survive a language tagged at a different ISO-639 granularity

> User report, 2026-09-14, verbatim: "When choosing a subtitle for a series while watching within
> ravilo and we start playing the next episode, it doesn't remember it and chooses something else
> (probably the default one). Ravilo should remember which language for a specific series." Confirmed
> live the same evening on the stue TV, on *It's Always Rainy in Pittsburgh*.

## Status
✓ Built 2026-09-14 — spec'd and fixed same day. Client-only (`ravilo-ui` commonMain), no backend or
admin-side change. `compileKotlinLinuxX64`/`compileKotlinWasmJs` unaffected (this module doesn't touch
either target); verified via the module's own commonTest suite. Not dev-reviewed, not deployed, not
re-tested live against the reported title (see "Open questions").

## The finding

### The remembered-track feature itself is not broken

The per-series remembered audio/subtitle choice (`PlaybackPrefsStore`, R181; the R196 regression that
made it dead entirely, fixed 2026-08-14) was re-verified by reading the current source line by line:
storage is correctly keyed by `seriesId`, `resolveTrackSelection()` (`PlayerScreen.kt:595`) correctly
re-arms once per episode via `rememberUpdatedState`-backed live groups, and every manual pick correctly
calls `persistChoice()`. A dedicated test suite
(`ravilo-ui/src/commonTest/.../PlayerScreenTrackResolutionTest.kt`) already guards the R196 shape. None
of that explains a report on 2026-09-14, over a month after that fix shipped and was released
(present in every tag from v1.9 onward, `41e7af14`..`v1.13`).

### The real cause: language codes aren't canonicalized before being compared

`resolveTrackChoice`'s `tierAudio`/`tierSub` (`PlayerScreen.kt`, near line 3464) matched a remembered
language against a file's own track groups with a plain
`it.language.equals(lang, ignoreCase = true)`. That only ever matches identical strings — it does
nothing about two different ISO-639 encodings of the *same* language ("da" vs "dan", "en" vs "eng").

Checked against the actual production database (`~/jellystructure/config/jellystructure.db`, copied to
a scratch file for read-only inspection) for the exact reported title. *It's Always Rainy in Pittsburgh* — 182 episodes across 16 seasons, assembled over time from many different release
groups — shows real, confirmed inconsistency:

- **15 distinct subtitle-language-set "shapes"** across its own episodes, including
  `{cze, dan, dut, eng, fin, fre, ger, gre, hun, ita, jpn, kor, nor, pol, por, rum, slo, spa, swe, tur}`
  (3-letter codes) on some episodes and `{da, eng, sr}` / `{da, en, sr}` / `{da, en}` (a **mix** of
  3-letter and 2-letter, changing which one, episode to episode) on others.
- **11 episodes carry a `null`-language subtitle track** (S12E01-04, S10E03, etc.) — an unavoidable
  fallthrough (there is nothing to match), left alone.
- **26 episodes (all of season 14+) carry two English subtitle tracks** — plain `"English"` and
  `"English [SDH]"`/bare `"SDH"` — a shape most earlier seasons don't have at all.

A household that remembers "Danish" (`dan`) on a season using 3-letter tags gets a `RememberedChoice`
that a season using `da` can never match by plain string equality — the language tier returns `null`,
both remembered tiers miss, and the resolver falls through to the file's own **source-default** tier —
exactly "chooses something else, probably the default one," and exactly why it "remembers on this
episode, not the next": the mismatch is real, per-file, and has nothing to do with which episode comes
first.

### A second, related gap: `tierSub`'s unmatched-variant fallback had no PLAIN preference

`tierAudio`'s fallback (when the remembered exact variant signature isn't present in this file) already
prefers a `PLAIN` version over `SDH`/`COMMENTARY`/`DESCRIBE` in the same language group (R235,
FR-R235-1/4 — commentary/SDH exist to supplement a soundtrack the viewer already understands, not
substitute for it). `tierSub` never got the equivalent: its fallback was
`native.firstOrNull { !it.forced } ?: native.firstOrNull()` — whichever non-forced version sorts first
by stream position, with no opinion on kind. Season 14+'s duplicate plain/SDH English pair (found while
investigating the primary bug above) is exactly the shape this asymmetry would bite on: an unmatched
remembered variant landing on the SDH copy instead of the plain one it actually meant, depending on
which one the file happens to list first.

## Requirements

**FR-R241-1 — a remembered language must match across ISO-639 granularities.** `resolveTrackChoice`'s
`tierAudio` and `tierSub` compare languages via a `sameLanguage()` helper: two codes are equal if
`languageName()` (the existing 2↔3-letter display-name map, R46) resolves them to the same name;
otherwise fall back to the previous case-insensitive raw compare so an unmapped/unrecognized code still
matches itself. No new alias table — reuses the map that already exists for display.

**FR-R241-2 — `tierSub`'s unmatched-variant fallback prefers PLAIN, matching `tierAudio`.** When the
remembered exact variant signature isn't present in this file's language group, prefer a non-forced
`PLAIN`-kind version over `SDH`/`COMMENTARY`/`DESCRIBE`; only fall back to stream order when no PLAIN
version exists in the group.

## Out of scope

- **Fixing the library's own inconsistent subtitle-language tagging.** The mixed 2-/3-letter codes and
  null-language tracks are real per-file data, unrelated to a Ravilo bug — the raw tags stay exactly as
  ffprobe/Jellyfin report them; only the *comparison* is made tolerant of it. Re-tagging 182 episodes'
  worth of muxed subtitle streams is a library-maintenance task, not this phase's.
- **`buildLanguageGroups`'s within-file grouping key.** Checked against the same production data: every
  single episode's own subtitle track set uses ONE convention throughout (never a 2-letter/3-letter mix
  *within* one file) — the mismatch is only ever across episodes/files, which is exactly where
  `resolveTrackChoice` does its comparison. No evidence found that the grouping key itself needs
  canonicalizing too.
- **The 11 null-language episodes and duplicate-English-track episodes are not separately "fixed."**
  A null-language track has nothing to match against by design; the duplicate-English case is exactly
  what FR-R241-2 already covers via kind-preference, not a distinct problem needing its own logic.

## Open questions

- **Not re-tested against the actual reported title on-device.** The fix is verified by the module's
  commontest suite (new cases below) and by direct inspection of this show's real subtitle metadata,
  not by re-watching two consecutive *Always Sunny* episodes on the stue TV. Needs the standard
  release-build deploy + on-device confirmation before this can move past `✓ Built`.
- **Whether other libraries hide the same class of bug less visibly.** This was found because one
  16-season show's episodes came from enough different sources to make the tag drift obvious; a
  2-3-season show sourced from one release batch would show it far less often, so this may have been
  silently degrading remembered-track accuracy household-wide at a lower rate all along.
