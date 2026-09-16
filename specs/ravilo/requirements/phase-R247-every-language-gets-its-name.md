# Phase R247 — Every language in the library gets its name, not its code

> The subtitle picker on the stue TV, 2026-09-16, three different files: **`EL`**, **`HBS-SRP`**,
> **`HBS-HRV`** — with a Greek flag beside the first and a microphone beside the other two. In the same
> session an English audio track on an English-language show wore the badge **Dubbed**. All four are
> the same defect: language identity is decided by string equality against tables that don't know the
> strings the player actually produces.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, findings F7 and F14). Not dev-reviewed,
not built. Client-only (`ravilo-ui`).

**Numbering:** verified against `STATUS.md` on 2026-09-16 — Ravilo taken through R245, R246 taken by
the sibling spec written the same day.

## Why this is a correctness phase, not a cosmetic one

**R241**'s whole mechanism — `sameLanguage()` — is `languageName(a) == languageName(b)`, falling back to
a raw compare when either side is unmapped. Every language missing from that table is a language whose
remembered choice cannot survive a code change between files. `EL` on screen is the visible half of a
matching gap.

## What the code does (traced against `main`, 2026-09-16)

- **Where the strings come from.** On Android the picker's language is ExoPlayer's `Format.language`
  (`RaviloPlayerAndroid.kt:441` for subtitles; sideloaded tracks get `.setLanguage(sub.language)` at
  `:226-231` and ExoPlayer normalises on build). ExoPlayer normalises to BCP-47 — observed on-device:
  `sr` → `hbs-srp`, `hr` → `hbs-hrv`, `gre`/`ell` → `el`. **Audio** uses the server's own code first
  (`meta?.language ?: format.language`, `:414`), i.e. Jellyfin's 3-letter `eng`.
- **The label fallback.** `format.label ?: languageName(format.language) ?: format.language?.uppercase()`
  (`:427`, `:431`) — so an unmapped code becomes its own uppercased label, and
  `pickerName(language, fallbackLabel)` (`PlayerScreen.kt:2461`, `endonym ?: languageName ?: label`)
  passes it straight to the screen.
- **Two tables, both short.** `RAVILO_ENDONYMS` (`PlayerScreen.kt:3217-3245`) and `LANGUAGE_NAMES`
  (`seams/RaviloPlayer.kt:154-180`) cover 24 languages each and **neither has Greek, Serbian or
  Croatian**. The single S17E07 file the sweep opened also carried `hun`, `rum`, `slo` — none mapped.
- **The glyph.** `:2680-2693`: a row with no flag resource draws `PickerGlyphMic` unless it is the Off
  or Unnamed pseudo-row — on a *subtitle* row a microphone says "audio". The flag map
  (`AudioFlagStrip.kt:38-57`) does have `gre`/`ell`/`el` (hence the Greek flag) but not the `hbs-*`
  forms (hence the microphones).
- **`Dubbed`.** `audioBadges` (`:3304-3323`):
  `!originalLanguage.equals(track.language, ignoreCase = true)` — jellystructure's `originalLanguage`
  is `en`, Jellyfin's stream code is `eng`, so every original-language track on every title whose
  server-side code is 3-letter is badged Dubbed. Same bug R241 fixed for the resolver, one function
  over.

## Requirements

**FR-R247-1 — One canonical language identity.** A single `canonicalLanguage(code): String?` in the
`seams` package: lower-cases and trims; maps ISO-639-2/B and /T and ISO-639-1 to one key
(`gre`/`ell`/`el` → `el`, `ger`/`deu`/`de` → `de`, `fre`/`fra`, `cze`/`ces`, `dut`/`nld`, `rum`/`ron`,
`slo`/`slk`, `ice`/`isl`, `chi`/`zho`, `per`/`fas`, `may`/`msa`, `arm`/`hye`, `alb`/`sqi`, `bur`/`mya`,
`geo`/`kat`, `mac`/`mkd`, `wel`/`cym`, `baq`/`eus`, `tib`/`bod`, `mao`/`mri`, `scc`/`srp`, `scr`/`hrv`);
recognises ExoPlayer's macrolanguage forms (`hbs-srp` → `sr`, `hbs-hrv` → `hr`, `hbs-bos` → `bs`,
`hbs-cnr` → `cnr`, and the `ar-*`, `zh-*`, `ms-*` families to their base) and the deprecated 2-letter
codes ExoPlayer rewrites (`iw` → `he`, `in` → `id`, `ji` → `yi`); returns the input unchanged when it
recognises nothing, never null for a non-blank input. **Region is preserved separately** — R195's
region logic (`resolveRegion`) keeps reading the raw string; the canonical key is for identity only.

**FR-R247-2 — `sameLanguage()` is `canonicalLanguage(a) == canonicalLanguage(b)`**, hoisted out of
`resolveTrackChoice` (where R241 left it as a local function) into the seam beside FR-1, and used by
every language comparison in the player: the resolver's tiers, `audioBadges`' Dubbed rule,
`buildLanguageGroups`' grouping key (`e.language?.lowercase()`, `PlayerScreen.kt:3585`) so a file that
mixes `da` and `dan` groups them as one language, and the R239 flag strip's counting.

**FR-R247-3 — One name table, keyed by canonical code, derived once.** `RAVILO_ENDONYMS` and
`LANGUAGE_NAMES` become two columns of one list keyed by the canonical key; lookups go through
`canonicalLanguage` first. The list covers **every language code present in the production library**
— the build runs the DB scan (subtitle and audio `language` fields across `media.json`) and lists in
its build note each code it added. Known missing today from the sweep alone: `el`, `sr`, `hr`, `hu`,
`ro`, `sk`. Endonyms follow R180's rule (the language's own name for itself).

**FR-R247-4 — A recognised code never reaches the screen as a code.** For any code the canonicaliser
recognises, the row shows its endonym (or English name). The uppercased-code fallback remains only for
a code nothing recognises — and then it is shown as `xx` in the row's secondary style, not as the
primary label, with the track's own label (if any) as the primary.

**FR-R247-5 — No microphone on a subtitle row.** A subtitle row with no flag draws a captions glyph
(the design's `I.cc` shape, drawn in code like `PickerGlyphMic` is); the microphone stays for audio rows
only. A flag lookup goes through `canonicalLanguage` too, so `hbs-srp` finds `flag_rs` where one
exists.

**FR-R247-6 — `Dubbed` uses FR-R247-2.** A track in the title's original language is never badged
Dubbed. Acceptance: *It's Always Sunny* (`originalLanguage: en`, audio `eng`) shows Default ·
Surround 5.1 and nothing else.

**FR-R247-7 — Tests.** Table-driven `canonicalLanguage` cases including every string observed on the
device (`el`, `hbs-srp`, `hbs-hrv`, `dan`/`da`, `eng`/`en`, `gre`, `iw`); `pickerName` for each yields a
name; `audioBadges` for `eng` vs `en` yields no Dubbed; `buildLanguageGroups` merges `da` and `dan`
entries into one group.

## Non-goals

- **Flag artwork.** No new flag assets are part of this phase. R239's decision on the Tamil/Telugu
  flags is closed and stays closed; a language with a name and no flag shows the FR-5 glyph.
- The design mockups' own `ravilo-i18n.js` tables (design-owned; the mirror flows the other way).
- Backend normalisation of stored codes — the raw tags stay what ffprobe/Jellyfin report (R241's
  non-goal, unchanged).

## Verification

1. Unit tests in FR-R247-7 green.
2. Stue TV, release build: *It's Always Sunny* S17E07's picker shows `Ελληνικά`, `Magyar`, `Română`,
   `Slovenčina`; S08E04's shows `Srpski`; S08E09's shows `Hrvatski`; no microphone on any subtitle row;
   no `Dubbed` on the English audio row.
3. R246's on-device check (Danish across `dan`/`da`) still passes — FR-R247-2 changed the comparison
   it depends on.

## Open questions

- Serbian's endonym: Cyrillic `Српски` or Latin `Srpski`? The library's Serbian sidecars are Latin
  script; recommendation is `Srpski` with `(ћирилица)`/`(latinica)` only if both ever appear.
- Whether the Unnamed group (R195 §3.8) should also key on the canonical code so two untitled tracks
  in `da` and `dan` count as one cluster. Probably yes; small.
