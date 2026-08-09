# Research report — disambiguating multiple same-language subtitle tracks in Ravilo's picker

**Date:** 2026-08-09
**Status:** Research only — not a spec. Intended as the design brief for a follow-up design-tool
session that will produce a new Ravilo phase spec (next free number: **R192** — R191 was claimed
this same day by the single-user-sign-out phase) covering the actual redesign. This report supplies
the *why* and the *real-world shape of the problem*, grounded in this deployment's live library data
and the shipped R180/R181 code — the design pass should not need to re-derive either.

## Why this report exists
The user's own framing: *"Very often we have subtitles and have many of them and even the same
language multiple times."* This report (1) quantifies how often that's actually true in this
deployment's real library, (2) catalogs the different real reasons two tracks share a language, with
concrete examples, and (3) documents exactly what the current picker does and doesn't do about it, so
a design pass has real constraints and real data to design against instead of a hypothetical.

## 1 — How big is this, really? (live library, 2026-08-09, 446 titles / 7,750 files)

| Scope | Total | With 2+ subtitle tracks sharing a language | Rate |
|---|---|---|---|
| Movie files | 288 | 134 | **46.5%** |
| Distinct movies affected | 288 | 134 | **46.5%** |
| Episode files | 7,462 | 1,668 | 22.4% |
| Distinct series affected | 158 | 75 | **47.5%** |

This is not an edge case — **almost half of every movie and almost half of every series** in this
library has at least one file where the same language appears on 2+ subtitle tracks. For context,
the same measurement on **audio** tracks (not subtitles, but the same underlying phenomenon) shows
90/288 movies (31.2%) with 2+ same-language audio tracks — smaller, but real, and probably worth the
design pass keeping in the back of its mind even though this report's scope is subtitles.

Distribution of the *largest* same-language subtitle group per file, across the 1,802 affected files
(movie + episode):

| Group size | 2 | 3 | 4 | 5 | 6 | 9 | 13 | 32 |
|---|---|---|---|---|---|---|---|---|
| Files | 2,801 | 619 | 88 | 12 | 2 | 1 | 1 | 1 |

The tail matters for design: a picker that assumes "at most 2-3 duplicates" will visibly break on
the handful of titles with 9-32 same-language tracks (real examples below, §3.7-3.8) — these are rare
but not synthetic; they're real files in this library today.

**251 file-instances** have subtitle tracks that are **fully identical across every field we have**
(language, title, forced flag, codec) — meaning there is *no metadata-based way* to tell them apart,
only their raw stream order. See §3.8 — this is the hard floor the design needs an explicit answer
for, not just better badges.

## 2 — Metadata reliability: neither `title` nor `forced` can be trusted alone

Across ~24,900 subtitle tracks in the library, **489 distinct non-null `title` strings** exist — a
huge, inconsistent vocabulary (see the raw frequency table in the investigation transcript for this
report; the top entries are `SDH` (1,300×), `English (SDH)` (447×), `Traditional` (429×), `Forced`
(413×), down through one-off strings like `English / SDH / SRT`, `PGS (SDH)`, `Original | English
(United Kingdom) | (SDH)`). Roughly half of all subtitle tracks have **no title at all** (12,480
absent vs. 12,379 present).

Cross-checking the `forced` boolean against the literal word "Forced" appearing in the title turns up
real disagreement:
- **119 tracks** have `"Forced"` in the title but `forced = false`.
- **44 tracks** have `forced = true` but no "Forced" (or synonymous) word in the title.

Neither signal is authoritative on its own — a design that keys disambiguation off *only* the
`forced` flag, or *only* title-text parsing, will misclassify real tracks in this library either way.
(This mirrors R180's own dev-review finding that badge coverage is "inherently partial" — see §5.)

## 3 — Taxonomy of real "same language, different track" scenarios (with live examples)

### 3.1 Plain vs. SDH (hearing-impaired)
The single most common case. Two English tracks: one plain, one `SDH`/`(SDH)`/`[SDH]`/`Text SDH`
(caption conventions vary by source). Already the case R180's badge system was built to handle
(`Signs only`/similar via the `forced` flag — SDH itself doesn't get its own dedicated badge word
today, see §5).
```
Dream Scenario (2023): eng "WEB-DL" | eng "SDH - UHD BluRay" | eng "SDH - BluRay"
Evil Dead Rise (2023): eng (no title) | eng "SDH"
```

### 3.2 Forced (foreign-dialogue-only) vs. full track, same language
```
Bluey's Big Play: dan "Forced" (forced=true) | fin "Forced" (forced=true) | nor "Forced" (forced=true) | swe "Forced" (forced=true)
                  eng (no title) | eng "SDH"
Den sidste viking: dan "Danish Forced" (forced=true, default=true)
```

### 3.3 Regional/dialect variants sharing one ISO language code
The subtitle format's 3-letter code (`spa`, `por`, `fre`, `chi`) doesn't distinguish dialect — the
distinction lives only in freeform `title` text, and that text itself is inconsistent between
releases:
```
Balls Up: spa ×4 (no titles at all — dialects indistinguishable)
Bugonia: spa "Castilian" | spa "Latin American"
         fre "Canadian" | fre "Parisian"
         por "Brazilian" | por "Iberian"
         chi "Simplified" | chi "Traditional"
Avatar: Fire and Ash: eng (no title) | eng "SDH" | spa "Latin American"  (only one Spanish variant here)
The Bride!: spa "Spanish (Spain)" | spa "Spanish (Latin America)"
            por "Portuguese (Brazil)" | por "Portuguese (Portugal)"
```
This is a genuinely *meaningful* distinction to many viewers (a Mexican Spanish speaker and a Spain
Spanish speaker will both see "eng"/"spa" flags today with no way to tell which subtitle is which
without trial and error) — and one where a flag-based UI could actually help, IF the raw title text
can be reliably mapped to a specific country/region flag. It often can (`Latin American` → 🇲🇽 or a
generic Latin America marker, `Castilian` → 🇪🇸, `Brazilian` → 🇧🇷, `Iberian`/`Portugal` → 🇵🇹), but the
text is inconsistent enough (`Spanish (Latin America)` vs `Latin American` vs `Latin America` vs no
title at all) that this needs a real synonym table, not exact string matching.

### 3.4 Commentary tracks — often literally identically labeled
```
Better Call Saul S6E5 / S6E9: eng "PGS (Forced)" (forced) | eng "PGS" | eng "PGS (SDH)"
                               | eng "Commentary PGS" | eng "Commentary PGS"   ← two commentary
                               tracks, IDENTICAL title, IDENTICAL every other field
```
Two commentary tracks (e.g., writer commentary + cast commentary) with the exact same generic label
is a case no title-parsing heuristic can solve — only ordinal numbering ("Commentary 1"/"Commentary
2") or the raw stream order can distinguish them, and neither conveys *which* commentary is which.

### 3.5 "Dubtitle" — a subtitle transcribing the dub, not the original dialogue
```
Reservatet S1E1-4: por "Portuguese (Brazil) [Forced]" (forced, default)
                    | por "Portuguese (Brazil) [Dubtitle]"
                    | por "Portuguese (Brazil)"
                    | por "Portuguese (Brazil) [SDH]"
                    | por "Portuguese (Portugal)"
```
"Dubtitle" is a real, distinct subtitle category (transcribes the dubbed audio track word-for-word,
useful when watching with the dub rather than original audio) that most viewers have never heard the
term for. This is the kind of jargon R180 explicitly said to avoid surfacing raw — but simply
dropping the distinction loses real information for someone using an audio dub.

### 3.6 Source/rip-provenance variants — same semantic content, different release
```
Dream Scenario: eng "WEB-DL" | eng "SDH - UHD BluRay" | eng "SDH - BluRay"
                dan "iTunes" | dut "iTunes" | fin "iTunes" | fre "Parisian - BluRay" | fre "Parisian - iTunes"
```
These aren't meaningfully different to a viewer (both are just "the French subtitles"), but they get
muxed in side-by-side when a release aggregates multiple subtitle sources. A design should probably
actively suppress/de-emphasize this distinction rather than surface "BluRay" vs "iTunes" as if it
were a real choice — it's release-provenance noise, not a viewer-relevant variant, and R180's own
non-goals already rule out surfacing codec/container/source jargon (§5) — this is the subtitle
equivalent.

### 3.7 Untagged bulk PGS tracks (no language at all)
```
28 Days Later (2002) remux: 32× hdmv_pgs_subtitle, ALL language=null, ALL title=null
                             (only the first has default=true)
28 Years Later (2025) remux: 9× hdmv_pgs_subtitle, ALL language=null, ALL title=null
28 Years Later: The Bone Temple: 13× hdmv_pgs_subtitle, ALL language=null, ALL title=null
```
Large BD remuxes commonly carry a full regional PGS subtitle set with **zero** ffprobe-visible
language metadata (PGS bitmap subtitles don't carry text to infer language from, and many BD
authoring tools omit the stream language tag entirely). This is the worst case in the library: **32
tracks, completely indistinguishable by any metadata field this system has access to.** There is a
real, known convention for Blu-ray PGS track ordering (a canonical alphabetical/regional ordering
some authoring tools follow) but it's not guaranteed and jellystructure has no code that assumes it
today (confirmed: `Track`/`SubTrack` never encode "BD PGS track index N of M in known order").

### 3.8 Fully-identical-signature tracks (the hard floor)
251 file-instances in this library have 2+ subtitle tracks where `(language, title, forced, codec)`
are **all** equal — not just same-language, but literally indistinguishable across every field the
system stores. The `28 Years Later: The Bone Temple` and untagged-PGS cases above are extreme
examples, but even ordinary titled releases hit this (e.g. `Balls Up`'s 4 untitled Spanish tracks,
4 untitled French tracks). **No amount of smarter title-parsing fixes this category** — the design
needs an explicit, honest answer for "we genuinely cannot tell these apart" (ordinal labeling? raw
stream order with a neutral "Track 2 of 4" cue? hide duplicates beyond N with an overflow?) rather
than silently rendering pixel-identical rows as the current picker does today.

## 4 — What Bazarr already knows, and why none of it reaches the picker

Bazarr's own download API carries **exactly** the structured metadata this problem needs —
`BazarrProviderResult(provider, subtitle, language, forced, hi)` (hearing-impaired is a real,
first-class boolean at this layer: `src/linuxX64Main/kotlin/dev/jellystructure/bazarr/BazarrClient.kt:127-132`,
also `BazarrMissingSubtitle.hi`/`BazarrLanguageProfileItem.hi` elsewhere in the same file) — and
jellystructure's own Bazarr integration (Phase 157) already uses `hi`/`forced`/`provider` when
*requesting* a download (`BazarrClient.kt:225-282`, `server/routes/BazarrRoutes.kt:258-288`).

None of this survives to playback time. Bazarr writes the subtitle file to disk and jellystructure
never re-tags or re-embeds it — Jellyfin's own library scanner rediscovers the file independently and
exposes it through ordinary `MediaStreams`, at which point only whatever Jellyfin/ffprobe itself can
infer from the file's own header/filename (not Bazarr's own `hi`/`provider` values) makes it into
`SubTrack`. Confirmed by grep: `bazarr`/`Bazarr` appears only in the admin-side Bazarr management
code (`Dashboard.kt`, `Subtitles.kt`, `BazarrApi.kt`, `Settings.kt`, `MediaDetail.kt`) — never in
`PlaybackService.kt`, `PlayerScreen.kt`, or the shared TV DTOs.

**This is a real, fixable gap independent of any picker UI redesign**: if jellystructure recorded (or
Bazarr's own `hi`/`forced`/`provider` were persisted alongside the downloaded file — e.g. via a
sidecar record keyed by file path, checked at playback-track-build time in `PlaybackService.buildSubtracks`),
a meaningful fraction of the "identical, can't tell them apart" cases in §3.8 would become
distinguishable again, for exactly the tracks jellystructure itself downloaded. Worth the design
pass considering even though it's a data-plumbing fix, not a UI one — the UI can't display metadata
that was thrown away three layers below it.

## 5 — What the current picker actually does (and was deliberately scoped not to do)

Shipped in R180/R181 (`ravilo-ui/.../screens/PlayerScreen.kt`), already live:
- Every row shows **flag + endonym/language name only** — the raw `title`/`label` text is **never**
  displayed verbatim (`pickerName()`); it's only fed through two fixed regex extractors
  (`trackVariant()`, `regionSuffix()`) that pull a small fixed badge/suffix vocabulary out of it and
  discard the rest. This was a deliberate FR-RV-ASP1-2 decision ("never echo codec/container/source
  jargon"), but it means all the rich distinguishing text in §3.1-3.6 above is thrown away at render
  time even when it's present and reliable.
- Badge vocabulary is fixed and audio/subtitle-specific: `Default`, `Surround 5.1`/`Stereo`, `Signs
  only` (from `forced`), `Sound described`, `Describes action`, `Commentary`, `Dubbed` — **there is no
  SDH badge**, no dialect/region badge, no "dubtitle" badge, no provenance badge. Two same-language
  SDH-vs-plain tracks get *zero* differentiating badge today (SDH doesn't map to any of R180's fixed
  words) unless the SDH track also happens to be `forced`.
- R180's own non-goals **explicitly accept** that a track with no detectable badge falls back to
  "flag + name" with **no further disambiguation cue at all** — this was a conscious tradeoff, not an
  oversight, but it's the direct cause of "three identical English rows" for any title where the
  duplicate tracks don't differ in `forced`/`isDefault`/a regex-matched title word.
- **Default/remembered-track resolution has a real, unshipped-per-spec gap**: R181's own
  FR-RV-TRK1-3 says that among multiple same-language tracks, resolution should prefer the
  remembered *variant*, then source `isDefault`, then first — but the shipped code
  (`resolveTrackSelection()`, `PlayerScreen.kt:468-496`) only ever does a bare
  `firstOrNull { it.language.equals(lang) }` on a language match, with no variant field even stored
  in `RememberedChoice`. In practice: a viewer who always picks the SDH track will silently get
  whichever English track happens to be first in stream order on every subsequent open, not
  necessarily SDH again. This is a real bug against R181's own written intent, not a design gap — the
  design pass should treat "which variant was actually selected" as data that needs to start being
  remembered, independent of whatever UI solves the rendering side.

## 6 — Open questions for the design pass

These are genuine open decisions, not disguised recommendations — the design tool should resolve
them with real tradeoffs in mind, using the scenarios above as ground truth:

1. **Grouping vs. flat list.** R180 chose a flat list deliberately (non-goal: no two-pane/grouped/
   question-based layouts). Does a same-language cluster of 3-32 tracks change that calculus? A flat
   list of 32 pixel-similar rows is arguably worse than the "flat list" principle intended to avoid.
2. **How to label the truly indistinguishable (§3.8).** Ordinal numbering ("English · 2 of 3")? Raw
   stream order with a neutral marker? An overflow/"more variants" affordance for large clusters
   (conflicts with R180 FR-RV-ASP1-4's "nothing hidden or merged" — does that invariant need revising
   for this case, or does it still hold and the UI must show all 32 some other way)?
3. **SDH as a first-class badge.** Given SDH is the single most common variant (§3.1, plus 1,300+
   raw title occurrences of literally "SDH"), should it join the fixed badge vocabulary alongside
   "Signs only"/"Sound described"?
4. **Dialect/region flags.** Worth attempting a synonym table (Latin American/Latin America/spa+no-title
   → 🌎 or 🇲🇽, Castilian/Spain → 🇪🇸, Brazilian/Brazil → 🇧🇷, Iberian/Portugal → 🇵🇹, Simplified → 🇨🇳,
   Traditional → 🇹🇼/🇭🇰, Parisian/France → 🇫🇷, Canadian → 🇨🇦)? Given the text inconsistency in §3.3,
   how tolerant does the matching need to be, and what happens when it can't confidently map (fall
   back to the plain language flag, presumably)?
5. **Suppressing source/provenance noise (§3.6).** Should "BluRay" vs "iTunes" vs "WEB-DL" tagged
   variants of the same language be actively de-duplicated/merged when they appear to be genuinely
   redundant, or does that risk hiding a real difference (e.g. one release's rip has better sync)?
6. **Remembered-variant fix (§5).** Independent of rendering, should `RememberedChoice` start storing
   which *variant* (not just language) was selected, so re-opening a series consistently returns to
   the same track a viewer actually wants? This is arguably a prerequisite for any UI fix to feel
   trustworthy — a nicer picker doesn't help if the app silently reverts to track index 0 every time.
7. **Bazarr metadata plumbing (§4).** Out of pure-UI scope, but worth flagging to the design pass:
   should this phase's spec include wiring Bazarr's own `hi`/`forced`/`provider` through to
   `PlaybackService.buildSubtracks` for jellystructure-downloaded subtitles, since that's a real,
   already-available signal being thrown away today?
8. **Scale/D-pad navigability.** Any design must stay usable via TV remote for the 9-32-track tail
   cases (§1) — confirm whatever grouping/labeling scheme is chosen doesn't regress the scroll-follow
   behavior R180 already built (`PlayerScreen.kt` `LazyColumn`/`animateScrollToItem`).

## Source references
- Live library measurements: ad-hoc queries against this deployment's `config/jellystructure.db`
  (`media` table, `json` column, `tracks`/`episodes[].tracks` arrays), run 2026-08-09. Not persisted
  as a script — rerun equivalent queries against a fresh DB snapshot if these numbers need updating.
- Backend track model: `src/commonMain/kotlin/dev/jellystructure/model/Media.kt:62-82` (`Track`,
  `TrackKind`); scan-side population: `FfprobeRunner.kt:91-141`.
- Shared/playback DTOs: `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt`
  (`SubTrack:96-105`, `AudioTrack:114-121`); build logic:
  `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (`buildSubtracks:447-492`,
  `buildAudioTracks:543-559`); Jellyfin source shape: `auth/Models.kt:150-174`
  (`JellyfinMediaStream`).
- Player runtime models: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayer.kt:6-25`;
  Android label sourcing gap: `RaviloPlayerAndroid.kt:230` (vs. audio at `:68`/`:205`); Wasm:
  `RaviloPlayerWasm.kt:113-116`.
- Picker UI: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
  — `TrackPicker`/`PickerOption` (`:1820-2039`), `pickerName`/`trackVariant`/`regionSuffix`
  (`:1951-2039`, `:2531-2539`), badge functions (`audioBadges`/`subtitleBadges`, `:2552-2587`),
  endonym table (`RAVILO_ENDONYMS`, `:2471-2494`), remembered-track resolution
  (`resolveTrackSelection`, `:468-496`, `RememberedChoice`/`persistChoice`, `:446-457`).
- Bazarr download-side metadata: `src/linuxX64Main/kotlin/dev/jellystructure/bazarr/BazarrClient.kt`
  (`BazarrProviderResult:127-132`, `downloadMovieSubtitle`/`downloadProviderMovieSubtitle:225-282`);
  route wiring: `server/routes/BazarrRoutes.kt:258-288`.
- Specs read in full: `specs/ravilo/requirements/phase-R180-audio-subtitle-picker-overhaul.md`,
  `specs/ravilo/requirements/phase-R181-default-and-remembered-tracks.md` (including both specs'
  Non-goals sections and R180's dev-review addendum §3/§5).

## Relationships
- **Amends nothing; supersedes nothing.** This is pure research feeding a future design pass — R180
  and R181 remain shipped and correct for what they scoped. Any resulting spec should explicitly
  frame itself as extending R180/R181's picker, not replacing it.
- Next free Ravilo phase number is **R192** as of 2026-08-09 (R191 = single-user sign-out, same day)
  — confirm the current free number before drafting the follow-up spec, since other work may have
  claimed it since.
