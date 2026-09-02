# Phase R222 — One plain line: "slow to start on this TV"

> The viewer half of **Phase 185**. The server has decided whether tonight's film will be slow to start
> on the device asking, and how sure it is. Ravilo's whole job is to render one sentence, or nothing.
> No thresholds, no numbers of its own, no decision logic — and, per **R180 FR-RV-ASP1-2**, not one word
> about bitrates, codecs, decoders, transcoding or delivery methods.

**Status:** ✓ Built 2026-09-02 (not yet dev-reviewed, not yet on-device tested — compiles clean across all
5 Ravilo targets, `:ravilo-ui:testDebugUnitTest` green). New `PlaybackNoteLine` composable
(`ravilo-ui/.../components/PlaybackNoteLine.kt`) shared by the movie hero (`MovieDetailScreen.kt`, between
synopsis and the actions row) and every episode row (`EpisodeCard.kt`, `MultiEpisodeCard.kt` — one line
per file group, not per contained episode). Five i18n strings shipped × en/da/fo (`slow_lead`,
`slow_tail_measured`, `slow_tail_expected`, `this_tv`, `this_phone`), da/fo translated to match the
mockup's own already-translated copy verbatim. Noir's tint-drop (FR-R222-7) reads `LocalRaviloSkin.current`
directly, the same pattern R221's genre chip already established. **Depends on Phase 185's backend**,
which resolves the `playbackNote` field this phase only renders — see that spec for what's built there
(notably: no client yet actually measures/sends `startupMs`, so every note that fires will read
`basis: "expected"` — "Give it a moment after you press play." — until that lands; `basis: "measured"`
is fully implemented and tested but has no live data to reach it yet).

Research: `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`
Design: `design/ravilo/Decode Ceiling Warning - Directions.html` (Direction B′ chosen; B, C and D
recorded as rejected). Built in the mockups at `design/ravilo/Ravilo TV.html` and
`design/ravilo/Ravilo Mobile.html`.

## Current state

The detail hero shows what the *file* is — format badge, year, runtime, age rating, IMDb, and after R221
its genre chips. All of it is true of the film everywhere, on every device, forever. Nothing on the page
is true of *tonight*: the same page renders identically on a TV that will start this file in two seconds
and one that will take twenty.

R216 deliberately closed the door on exposing capability to viewers — *"the viewer's experience is that
playback simply works"*. That line is still right about picture quality: with 177/R216 live the file does
not stutter, it re-encodes. But "simply works" is wrong about one thing, and it is the thing the viewer
actually experiences: a black screen for ~20 seconds with no explanation, which reads as broken and gets
abandoned.

## Goal

Set the expectation before Play, in the viewer's own language, using the one fact that helps: how long
this will take here. Nothing to configure, nothing to dismiss, nothing to understand.

## Functional requirements

**FR-R222-1 — Render, never compute.** ✅ Built — `PlaybackNoteLine` takes the resolved `PlaybackNote` directly; callers only invoke it inside a null-check (`detail.playbackNote?.let { ... }` / `episode.playbackNote?.let { ... }`), so absent really does mean nothing composes. The client reads `playbackNote { device, basis, seconds }` off the
detail payload (Phase 185 FR-185-5) and renders the corresponding sentence. It never derives, caches past
the payload, re-checks or second-guesses the field. **Absent ⇒ nothing renders** — not an empty slot, not
a placeholder, no reserved space and no layout shift.

**FR-R222-2 — Two sentences, five strings.** ✅ Built, all five × en/da/fo. Split so the lead can be emphasised structurally rather
than with markup inside a translatable string. Three carry the sentence; the last two are FR-R222-3's
fallback device names, which are copy in their own right and must not be assembled in code:

| key | en |
| --- | --- |
| `slow_lead` | `Slow to start on {device}.` |
| `slow_tail_measured` | `The last few times it took about {n} seconds.` |
| `slow_tail_expected` | `Give it a moment after you press play.` |
| `this_tv` | `this TV` |
| `this_phone` | `this phone` |

`basis: "measured"` uses the lead + `slow_tail_measured`; `basis: "expected"` uses the lead +
`slow_tail_expected`. Full en/da/fo at ship, like every string since R180. Deliberately rejected copy,
recorded so it is not re-proposed: *"may not play smoothly"* (predicts a stutter we now prevent),
*"can't handle this file at full quality"* (implies a quality control exists), *"better on the Living
room TV"* (household logistics — see 185's non-goals).

**FR-R222-3 — Name the device.** ✅ Built — `note.device.ifBlank { str("this_tv"/"this_phone") }`. In practice the server never sends a blank name (`DeviceData.displayName` always has its own fallback), so this fallback is defensive rather than reachable today. `device` arrives resolved from the server (185 FR-185-5) and is shown
verbatim, because a three-TV household needs to know which one is being talked about. When the server
sends no name, fall back to the localised `this_tv` / `this_phone`. A model string
(`BRAVIA VH21`, `Chromecast HD`) is **never** shown to a viewer — that lives in the admin.

**FR-R222-4 — Placement: directly above the actions.** ✅ Built — between the synopsis block and the actions `Row` in `MovieDetailScreen.kt`, its own `Spacer`-separated slot. On a movie detail the line sits between the
synopsis block and `.dactions`, so it is read at the moment the decision is made. It is deliberately
**not** in the meta row: after R221 that row already carries format, year, age, IMDb and genre chips,
and this is not a fact about the film — it is a fact about tonight. Play must not move: the line takes
its own vertical slot above the button row and never reflows it.

**FR-R222-5 — Series carry it on episodes.** ✅ Built — `Episode.playbackNote` (never on `SeriesDetail` itself, which has no such field); `MultiEpisodeCard` reads only the group's first episode's note, since Phase 185 resolves the identical note for every episode sharing a file. A ceiling is per device and a bitrate is per file, so the
series hero never shows the line (its episodes may come from different sources). The line belongs to the
episode row in the rail. A Phase 149 combined `S01E01–E03` row is one file and shows **one** line;
expanding it does not repeat it per episode.

**FR-R222-6 — Not an interaction.** ✅ Built — plain `Text`/`Box`, no `dpadFocusable`, no click target. Not focusable, not in the D-pad order, no action, no target, no
"don't show this again", nothing revealed on focus or hover. It is a label, like the age badge. Back
behaves exactly as it does today.

**FR-R222-7 — Noir drops the tint.** ✅ Built — a fixed amber (`0xFFF5B542`, matching the mockup's `--warn`) for Aurora/Midnight; Noir renders the bar and lead text in `colors.text` at bold weight instead, via `LocalRaviloSkin.current == Skin.NOIR`. Aurora and Midnight mark the line with the `--warn` amber (a 3px
rule plus amber ink on the lead). Noir's own accent *is* amber, so a tinted rule there reads as
decoration — the same collision R221's primary-genre chip hit, solved the same way: **keep the ink and
the weight, drop the colour**.

**FR-R222-8 — Phone parity.** ✅ Built — `PlaybackNoteLine`'s `compact` param (driven by the same `LocalCompact.current` the rest of this screen already uses) wraps freely with no cap; the phone resolves its own verdict server-side same as TV, from its own request. The phone detail shows the same sentence above its action row, wrapping
freely (no D-pad, no cap). The phone resolves its **own** verdict from the server, so a title that
carries the line on a TV may carry nothing on the phone — that is correct, not a bug.

## Non-goals

- **No numbers except the seconds.** No bitrate, no ceiling, no resolution maths, no percentage, no
  "82 of 60 Mbps". Direction D was drawn and rejected for exactly this.
- **No gate.** Direction C (confirm-before-play) was drawn and rejected: Ravilo has no quality picker, no
  alternate version and no device switcher, so the dialog's only possible content is *press OK again*,
  and it would fire on every play of every heavy file forever.
- **No setting, no dismissal, no preference.** R216's invariant.
- **No player-side change.** R218 owns every waiting moment once Play is pressed; this phase says nothing
  after the press and adds no new player state.
- **No note for non-Ravilo playback**, and no sentence explaining that gap (185's non-goals).

## Open questions

1. ~~**Copy hangs on 185's open question #1.**~~ **Resolved on-device 2026-09-02 — the copy is correct
   and translation is unblocked.** R216 has been live on the living-room TV since 2026-08-30, proven by
   105 `playback_qoe` rows carrying R216's own fields, `direct_play = 0` on the heavy sessions (the
   transcode fallback is firing) and `dropped_frames = 0` on every row. Through Ravilo the file
   re-encodes and starts slowly rather than stuttering, which is exactly what `slow_lead` says. The
   *Till Daybreak* stutter was a **Wholphin** session that never touches this path. See 185's open
   question 1 for the full evidence.
2. **At launch nothing is measured**, so every note starts as `expected` and the `measured` sentence
   appears only once a device has actually started that file three times (185 FR-185-7). Worth stating in
   release notes so the softer sentence isn't read as the feature being broken. **Sharper as of
   2026-09-02: this isn't just a launch-day state, it's the current permanent state.** Phase 185's server
   side is fully built and tested, but the client-side timer that measures negotiation-to-first-frame and
   sends it (`startup_ms` on the stop call) was time-boxed out of that pass — so today `basis: "measured"`
   is unreachable in practice, not just unreached-yet. Every note that fires reads `slow_tail_expected`
   until that client work lands; not a regression in what R222 built, but worth knowing before demoing it.
3. **Does a 60 s-plus start deserve different words?** R218 deepens its loading state at 60 s without
   changing a word; the equivalent question here is whether "about 90 seconds" should read differently
   from "about 20 seconds". Current answer: no — same sentence, bigger number.
