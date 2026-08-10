# Phase R195 — Telling apart subtitle tracks that share a language

**Status:** Planned — design-complete, **dev-reviewed 2026-08-10** (see addendum below; two corrections
to §5.1/§5.2's framing, one open plumbing decision resolved, sequencing recommended). Not yet built.
**Date:** 2026-08-09
**Supersedes nothing. Extends:** R75/R78/R134 (merged flag line), **R180** (flag-forward Audio &
Subtitles picker), **R181** (default and remembered tracks).
**Research basis:** `specs/research-reports/subtitle-picker-same-language-disambiguation-2026-08-09.md`

---

## 1. Problem

R180 shipped a flag-forward picker on the assumption that **one row per language reads as one
choice**. In this library it usually does not.

From the 2026-08-09 report, measured against the live library:

| Measure | Value |
|---|---|
| Movies with a file carrying 2+ same-language subtitle tracks | **46.5%** |
| Series likewise (episode files) | **47.5%** (1,668 files) |
| File-instances where same-language tracks are identical on **every** field we store | **251** |
| Largest single cluster | **32** tracks in one file — no language, no title |
| Distinct `title` strings across ~24,900 tracks | **489** — no usable shared vocabulary |

The viewer-visible failure is a list that shows `🇬🇧 English`, `🇬🇧 English`, `🇬🇧 English` and
gives no way to tell which is the plain translation, which is SDH, and which is a commentary
track — and, in the tail, a wall of 32 rows that are pixel-identical.

A second, quieter failure: **R181 remembers a language, not a track.** A viewer who always
chooses SDH gets whatever same-language track happens to sit first in stream order on the next
episode.

## 2. Goals / Non-goals

**Goals**
- Every row in the picker differs from its siblings on something the viewer can *read*.
- The common case (one usable version per language) gets **shorter**, not longer.
- The tail case (dozens of unnamed tracks) stays navigable with a D-pad.
- Remembering a choice survives across episodes and files.

**Non-goals**
- Rewriting the badge vocabulary. R180's words stand: **Default · Signs only · Sound described ·
  Describes action · Commentary**, no codec names, no delivery-method cues.
- Subtitle *acquisition*. Fetching, scoring and upgrading remain Bazarr's (Phase 157).
- Any change to the audio side beyond the same grouping mechanics — audio has the same duplicate
  problem in miniature (dub vs. audio-description) and gets the identical treatment for free.

---

## 3. Design — two levels: language first, versions inside

Chosen from three explored directions (`design/ravilo/Audio & Subtitles Picker - Same-Language
Directions.html`). The rejected two are recorded in §8.

### §A The language list (level 1)

One row per **language**, not per track. Each row:

```
[tick] [flag] Dansk                                  (nothing)
[tick] [flag] English    Default · Last used         4 versions ›
[tick] [flag] Português                              4 versions ›
[tick] [flag] Suomi                                  (nothing)
```

- **The flag sits beside the language name** on every row — this is R180's flag-forward
  principle, unchanged.
- The language name is the **native** name (`Dansk`, `Português`, `中文`), as shipped.
- A language with **more than one version** shows a count and a **›** arrow.
- A language with **exactly one version** shows **no arrow**, and **OK selects it immediately** —
  no second screen for a list of one. *The absence of the arrow is the entire affordance;* no
  extra label, badge or colour marks it. (Explicitly settled during design review: an earlier
  draft added a "Straight on" tag and a green edge — both rejected as noise.)
- Badges on a level-1 row belong to the language's single version, or — when the language has
  several — to the one currently playing (`Default`, `Last used`).
- `Off` is the first row, as today.

### §B The version list (level 2)

Entered with **OK** on a row that has an arrow; **Back** returns to level 1 (it must *not* close
the picker — see §D).

```
🇬🇧  English            4 versions
     ─────────────────────────────────────────────
[✓]  English    Default · Last used
     The full version of everything spoken.
[ ]  English    Sound described
     Adds speaker names and sound-effect notes.
[ ]  Commentary  Recording 1
     A recorded commentary on this title.
[ ]  Commentary  Recording 2
```

- **The flag is not repeated on every row.** It sits **once, in the header bar**, with the
  language name and the version count. Repeating one flag down a column of four rows carries no
  information and reads as noise.
- Each version carries a **one-sentence plain-language line** underneath. This is the mechanism
  that replaces the 489 inconsistent `title` strings — we never render a raw track title.
  Sentences are authored per *kind*, not per track:
  | kind | line |
  |---|---|
  | plain | The full version of everything spoken. |
  | `sdh` | Adds speaker names and sound-effect notes. |
  | `forced` | Only the on-screen text and foreign lines. |
  | `describe` | Narrates what happens on screen. |
  | `commentary` | A recorded commentary on this title. |
  | region variant | The {region} version. |
  | no distinguishing data | This one carries no name of its own — pick it to see it. |
- These strings live in `ravilo-i18n.js` (en/da/fo) like every other viewer-facing string.

### §C Regions inside a language

Where a language splits by region, the **version row carries its own flag** — and *only* then:

```
🇵🇹  Português          4 versions
[✓]  Português  Brasil                🇧🇷
[ ]  Português  Brasil   Sound described   🇧🇷
[ ]  Português  Brasil   Signs only        🇧🇷
[ ]  Português  Portugal                  (no flag — the header already shows 🇵🇹)
```

Rule: **a version shows a flag when its region differs from the header's flag; otherwise it shows
none.** When *any* version in the group carries a region flag, flagless rows keep an **empty slot
of the same width** so the text column stays straight.

Region resolution is a **synonym table, never string matching** (§5.2).

### §D Navigation (D-pad)

| Key | Level 1 | Level 2 |
|---|---|---|
| ↑ ↓ | move between languages | move between versions |
| ← → | switch Audio / Subtitles tab | switch Audio / Subtitles tab (returns to level 1) |
| OK | one version → select · several → **enter level 2** | select, stay open |
| Back | close picker | **return to level 1** |

Entering level 2 focuses the first version. Returning to level 1 restores focus to the language
row you came from. Switching tab always resets to level 1.

### §E The tail: tracks with nothing to tell them apart

When every same-language track in a group is identical on all stored fields (**251 file-instances**;
worst case **32**):

- Level 1 shows one row: **`Unnamed`** with a neutral globe placeholder where the flag would be,
  and the count.
- Level 2 numbers them: **`Version 1`** … **`Version n`**, each with the "carries no name of its
  own" line, the currently-showing one badged **`Now showing`**.
- Because a subtitle change applies instantly, **moving down the list is the preview** — the
  footer says so.

**Wording constraint (settled in review):** never say *disc*. Nothing in this library is a disc.
The copy is "nothing in this file names them", "no language name came with this track".

---

## 4. What the viewer sees change

- A title with one usable subtitle per language: the list gets **shorter** (no repeated rows) and
  behaves exactly as before — one press.
- A title with SDH: SDH stops being invisible and becomes a real, labelled second version.
- A Brazilian/European Portuguese title: two clearly different rows instead of two identical ones.
- The 32-track case: navigable instead of a wall.

---

## 5. Backend / data prerequisites

These are the real work. The picker cannot be honest without them.

### 5.1 Detect SDH
The single most common variant (1,300+ raw `SDH` occurrences in track titles) and **nothing sets
it today**. Detect from:
1. track title matching `SDH`, `HI`, `hearing`, `hard of hearing` (case-insensitive, word-boundary), **and**
2. Bazarr's own `hi` flag where a Bazarr connection exists (Phase 157).

Surface as `kind: 'sdh'` → renders as R180's existing **Sound described**.

### 5.2 Region synonym table
Map to a region code, never substring-match:

| Raw | → |
|---|---|
| `Castilian`, `Spanish (Spain)`, `es-ES` | 🇪🇸 `es` |
| `Latin American`, `Latin America`, `es-419` | neutral (no flag) + name `Latinoamérica` |
| `Brazilian`, `Portuguese (Brazil)`, `pt-BR` | 🇧🇷 `br` |
| `Portuguese (Portugal)`, `pt-PT` | 🇵🇹 `pt` |
| `Simplified`, `zh-Hans`, `zh-CN` | 🇨🇳 `cn`, name `简体` |
| `Traditional`, `zh-Hant`, `zh-TW` | 🇹🇼 `tw`, name `繁體` |

**An unconfident match falls back to the plain language flag.** A wrong flag is worse than no
region at all.

### 5.3 Suppress provenance
`BluRay` / `WEB-DL` / `iTunes` / release-group variants of the same language *and* kind are **not
a viewer choice**. Collapse to one row, keeping the best-scoring file. This is the one place the
design deliberately **merges** tracks — see §7.

### 5.4 Remember the version, not the language
`RememberedChoice` currently stores a language code. It must store a **variant signature**
(`lang` + `kind` + `region` + ordinal-within-group) so "always SDH" survives to the next episode.
Falls back to the language when the signature finds no match in the next file.

---

## 6. Implementation surface (design mockups)

| File | Change |
|---|---|
| `ravilo/ravilo-player.js` | `plGroups()` groups the flat track list by language; `plSameSig()` detects the indistinguishable case; `plVarName()` / `plBlurb()` render level 2; `renderPicker()` gains a level; `pickerChoose()` selects-or-descends; new `pickerBack()` |
| `ravilo/ravilo-player.css` | `.pl-more` (count + arrow), `.pl-crumb` (level-2 header bar with the single flag), `.pl-var` / `.pl-vh` (version row + its sentence), `.pl-rgn` (+ `.ghost` slot-holder) |
| `ravilo/ravilo-app.js` | `tracksFor()` sample data extended with `region`, a commentary sub-track, Brazilian/European Portuguese, 简体/繁體 and three indistinguishable French tracks |
| `ravilo/ravilo-i18n.js` | the §B sentences and "versions" / "Unnamed" strings, en/da/fo |
| `ravilo/mobile/` | phone target inherits the same two levels (sheet pushes a second sheet) |

Design reference: `design/ravilo/Audio & Subtitles Picker - Same-Language Directions.html`
(Direction A column = the chosen design; the other two columns are kept as rejected options).

---

## 7. Open question for dev review

R180 states the picker hides and merges **nothing**. §5.3 (provenance merge) and §3 (a second
level) both bend that.

**Recommendation:** keep the invariant for *tracks a viewer could meaningfully choose between*,
and let it yield where the difference is release plumbing rather than content. If the dev team
prefers the invariant absolute, §5.3 drops and the level-2 list simply gets longer — the design
survives it; the rejected Direction B does not.

## 8. Rejected directions

- **B — flat list, honest variants.** Smallest diff from shipped R180: keep one row per track,
  tint same-language runs into a block, add SDH badges and corner flags. Rejected because the
  32-track case degrades into either an endless scroll or an overflow row, which breaks the same
  R180 invariant it was trying to protect.
- **C — language rail + versions pane.** Two columns, nothing hidden, scales best of the three
  (all 32 fit as a number grid, four D-pad presses to the farthest). Rejected as the most
  screen-real-estate and the only one introducing a new left/right gesture inside the picker —
  kept on file as the fallback if §3's second level tests badly.

## Dev-review addendum (2026-08-10 — backend/frontend reality check before implementation starts)

Traced every backend-adjacent claim in this spec and its research basis against the **actual shipped
Kotlin/Compose code** (`ravilo-ui/src/commonMain/.../PlayerScreen.kt`,
`.../PlaybackPrefsStore.kt`, `shared/.../Models.kt`, `RaviloPlayerAndroid.kt`) — not
`design/ravilo/ravilo-player.js`, which is the static design mockup, never shipped
(`constitution.md`: mockups are the visual target; real behavior is the Compose app). Style/rigor
calibrated against the phase-157 and R190 addenda.

**❌ Correction — §5.1 overstates the SDH gap. Title-text SDH detection already ships**, since R180
stage 3 (commit `1b72d17`, 2026-07-12), not "nothing sets it today" as §5.1/the research report's §5
claim. `SDH_RE = Regex("""\bsdh\b|hard of hearing""", IGNORE_CASE)` (`PlayerScreen.kt:2517`) feeds
`trackVariant()` (`:2548-2556`), which `subtitleBadges()` (`:2593-2601`) already maps
`TrackVariant.SDH` → the **"Sound described"** badge — exactly the CLAUDE.md-documented mapping
("Sound described (SDH)"). What's genuinely still missing, narrower than the spec implies:
1. **Untitled SDH tracks** (research report: 12,480 of ~24,900 tracks have no title at all) get no
   signal from `SDH_RE` at all, since it only ever runs against `label`/title text — this is where
   §5.1's "Bazarr's own `hi` flag" bullet is the real, still-needed fix (see next finding).
2. `SDH_RE` doesn't match a bare **"HI"** token on its own (only "hard of hearing" as a phrase, or
   literal "sdh") — §5.1's proposed literal `HI`/`hearing` word list is a genuine small widening,
   not new work from scratch.

**❌ Correction — §5.2's "region synonym table" is largely already-shipped infrastructure, not new.**
`REGION_MARKERS`/`regionSuffix()` (`PlayerScreen.kt:2527-2546`, same R180 stage 3 commit) already
regex-matches `Simplified`/`Traditional`/`Canadian`/`Latin American`/`European`/`Brazilian`/`Taiwan`/
`United States`/`United Kingdom`/`VFF`/`VFQ` — with `\b`-bounded patterns, i.e. already "not raw
substring matching" in spirit. It's rendered today as a **muted text suffix** (`中文 · Simplified`),
never a flag. What §5.2/§C actually need on top of this: (a) mapping each marker to an actual
**flag glyph + region code** rather than display text, (b) a few markers `REGION_MARKERS` doesn't
yet cover (`Castilian`/`es-ES`, `Iberian`/`Portugal`/`pt-PT`, an explicit 🇧🇷 vs 🇵🇹 split — today
"Brazilian" is detected but "Portugal"/"Iberian" isn't), and (c) the neutral-fallback confidence
rule (§5.2: "an unconfident match falls back to the plain language flag") — genuinely new, since
today's `regionSuffix()` has no notion of "confident vs. not," it just matches or returns null. Net:
extend `REGION_MARKERS`, don't design a synonym table from zero.

**✅ Confirmed — the picker is a genuine flat, single-level list today.** `TrackPicker`
(`PlayerScreen.kt:1838-1926`) builds one `PickerRow` per track directly from `audioTracks`/
`subOptions` — no grouping by language anywhere. §3's two-level redesign is a real, non-trivial
change to both the data model (`rows: List<PickerRow>` → a language-grouped structure) and the D-pad
state machine (today: one `pickerIdx: Int`; needs a `pickerLevel`/group-index/version-index shape).
**Good news for §D's Back semantics**: all picker key handling funnels through one `when` block
(`PlayerScreen.kt:~900-1021`), and Back-closes-picker is a single isolated site (`:1021`,
`pickerOpen -> { pickerOpen = false; wake() }`) — making "Back returns to level 1, doesn't close"
a contained, low-risk change once the level state exists, not a deeper rewrite.

**✅ Confirmed — §5.4's remembered-choice gap is real and independently valuable.** `RememberedChoice`
(`PlaybackPrefsStore.kt:8-12`) stores only `audioLanguage`/`subtitleLanguage`/`subtitlesOff`.
`resolveTrackSelection()` (`PlayerScreen.kt:478-506`) does a bare
`firstOrNull { it.language.equals(lang, ignoreCase = true) }` — first match in stream order wins
among same-language tracks, exactly as the research report describes. This is a standalone,
shippable-first fix independent of the picker UI (a viewer who always picks SDH silently loses that
choice today, regardless of what the picker looks like) — recommend landing §5.4 before or alongside
§3, not after, since it's the correctness bug most likely to be user-visible in the interim.

**⚠ Needs a decision the spec doesn't make — where does `kind`/region live?** Client-side badge
computation (`trackVariant`/`regionSuffix`, both plain non-`@Composable` functions operating on
`label` text) is the existing pattern for subtitles; audio tracks additionally get a richer
server-computed `AudioTrack` list (`codec`/`channels`/`isDefault`, threaded as `audioMeta` and mapped
by index into `RaviloPlayerAndroid.audioTracks`, `:225-251`) that subtitles have **no equivalent
of** — `RaviloPlayerAndroid.subtitleTracks` (`:253-277`) only ever gets `format.label` (ExoPlayer's
view of whatever `SubTrack.label` — Jellyfin's raw `displayTitle`/`title`, `PlaybackService.kt:463`
— was set on the `SubtitleConfiguration` at `load()` time), no separate metadata list. Recommend:
- §5.1's title-regex SDH widening and §5.2's region-flag mapping can both stay **client-side**,
  extending the existing `trackVariant`/`regionSuffix` pattern — no DTO/backend change needed for
  either, since both operate on text already present on `label`.
- §4's Bazarr `hi`/`forced`/`provider` plumbing (research report §4, confirmed real — `grep` finds
  `bazarr`/`Bazarr` only in admin-side code, never in `PlaybackService.kt`/`PlayerScreen.kt`/the
  shared TV DTOs) **does** need real backend work: a sidecar record keyed by file path (or by
  `SubTrack.url`'s Jellyfin stream index) populated at Bazarr-download time
  (`BazarrRoutes.kt:258-288` already has `hi`/`forced`/`provider` in hand at that moment — see
  `BazarrProviderResult`, `BazarrClient.kt:127-135`), read back in `buildSubtracks`
  (`PlaybackService.kt:447-`) to set a new `SubTrack.hi: Boolean` (or fold straight into a
  server-computed `kind`) for exactly the subset of tracks jellystructure itself downloaded. This is
  the one piece of §5 that's genuinely "new backend plumbing," not "extend an existing regex."

**On §7 (does R180's "nothing hidden or merged" invariant survive §5.3's provenance merge?):** agree
with the spec's own recommendation — yield the invariant only for release-provenance duplicates, not
viewer-meaningful ones. One caution: unlike SDH/region, **provenance detection has no existing code
at all** to build on (no `PROVENANCE_MARKERS` equivalent) — recommend keeping the initial suppression
rule conservative (§5.3 already leans this way: "identical on language *and* kind," not a fuzzy
"looks like a rip tag" match) so a false-positive merge never silently hides a track that actually
differs. A `\b`-bounded keyword list styled after `REGION_MARKERS` (`BluRay`/`WEB-DL`/`iTunes`/etc.)
is the natural implementation, gated behind "and everything else about the two tracks is equal."

**Scope/sequencing recommendation** (not a spec change, an implementation-order suggestion): the
prerequisites split cleanly into three independent, separately-shippable pieces — (1) §5.4 remembered-
variant fix, a standalone correctness bug; (2) §5.1/§5.2 client-side regex widening, no backend
change; (3) §4/Bazarr `hi` backend plumbing + §5.3 provenance suppression, genuinely new backend
work. §3's two-level UI is the one piece that depends on having *something* to group/label by, so
it's reasonably last, but doesn't strictly require all three prerequisites to be complete first — it
degrades gracefully (a track with no detected `kind` just falls back to "flag + name," exactly
today's behavior, per §3.8's own `Unnamed`/"no distinguishing data" handling).

Verified via reading only (no code changes this pass, per "spec before fix" — this addendum is the
dev-review step, matching phase-157/R190's pattern of a reviewed-then-later-implemented spec).
