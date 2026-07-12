# Phase R180 — Ravilo: flag-forward Audio & Subtitles picker (FR-RV-ASP1)

> The in-player **Audio & Subtitles** picker is redesigned so **no technical knowledge is needed to turn
> on subtitles or change the soundtrack**. A country **flag anchors every row**, the label is the plain
> native language name, and the only extras are **jargon-free badges** — never codec names
> (`hdmv_pgs_subtitle`, `E-AC3`, `mov_text`…) and never delivery-method talk. **Every track stays
> available** — nothing is hidden or collapsed away. Each picker **tab also shows the flag of the
> currently-selected track** (Audio tab → selected audio language; Subtitles tab → the chosen subtitle
> flag, or nothing when subtitles are Off). Design mockup: **`design/ravilo/Ravilo TV.html`**
> (`ravilo-player.js` `renderPicker`/`PL_KIND`/`plFlag`/`plBadges`/`plTabFlag` + `ravilo-player.css`;
> track data in `ravilo-app.js` `tracksFor()`). Direction exploration:
> **`design/ravilo/Audio & Subtitles Picker.html`** (four layouts on TV + phone — **Direction 2,
> flag-forward, was chosen**).

## Goal
A non-technical viewer opens Audio & Subs, recognises their language by its **flag** without reading, and
picks a track in one move. The picker communicates the few things that actually change the experience —
which track is the source's suggested one, surround vs. stereo, and the real subtitle *variants* (a
signs-only track, a track with sound descriptions, an audio-description track, a commentary) — in **plain
words**, and says nothing about codecs, containers, or how a track is delivered.

## Current state (verified)
- The player already has a two-tab **TrackPicker** popup (~~`PlayerScreen.kt:1252-1305`~~ **now
  `TrackPicker`@~1395 + `PickerOption`@~1475 — the file grew ~150 lines from today's playback fixes**,
  rows via `PickerOption`): Audio | Subtitles, a flat list of rows, ✓ tick on the active one, D-pad navigable,
  card ~`0xFF0E1119`@94% with a focus ring. Today each row shows a **raw-ish label + a `desc` string**
  (e.g. `5.1 · E-AC-3 · dub`, `Signs only` / `Forced`) — i.e. codec and delivery jargon leaks into the UI.
- Flags already exist for this exact use: **`AudioFlagStrip.kt`** `LANG_CC` map + `composeResources/
  drawable/flag_*.png` (incl. `da→flag_dk`, `fo→flag_fo`, `en→flag_gb`), used on the detail hero's merged
  audio+subtitle flag line (R75/R78/R134) and reused by the R172 request-language picker. ~~`languageName()`
  / `RAVILO_LANGS_UI` already resolve endonyms (`da→Dansk`, `fo→Føroyskt`).~~ **[dev-review — not true; see
  addendum §1. `RAVILO_LANGS_UI` exists only in the design mockup (`design/ravilo/ravilo-i18n.js:203`), NOT
  in the Compose app; `languageName()` (`RaviloPlayer.kt:75`) returns _English_ names (`da→"Danish"`). The
  endonym label has no existing Kotlin source and must be ported.]**
- The backend (`PlaybackService.kt`) buckets every subtitle codec into three **delivery methods**
  (external text / embed image / encode-PGS-burn-in); PGS still triggers a live transcode
  (`SubtitleMethod=Encode`). **This bucketing stays internal** — see FR-RV-ASP1-2.
- Jellyfin assembles a rich `DisplayTitle` server-side (e.g. `Dansk - Synstolkning - Dolby Digital -
  5.1`). Ravilo currently leans on pieces of that string; this phase stops surfacing the codec/
  delivery parts of it.
- **Note:** `LANG_CC`/`LANGUAGE_NAMES` are duplicated between `AudioFlagStrip.kt` and `RaviloPlayer.kt`
  (flagged in R172) — centralise if touched here.

## Requirements

### A. Flag-forward rows (both tabs)

#### FR-RV-ASP1-1 — Every row is flag + plain name + jargon-free badges
Each track row renders, left to right: a **✓ tick** (filled/accent when active), a **large country flag**
(the primary visual anchor), the **plain native language name** (endonym via `languageName()` /
`RAVILO_LANGS_UI` — `Dansk`, `Føroyskt`, `Español`, `Suomi`, `中文`), and zero or more **badges** drawn
from the fixed vocabulary in FR-RV-ASP1-3. A region/variant qualifier (e.g. `中文` → "Simplified") may
render as a small muted suffix on the name. No other text.

#### FR-RV-ASP1-2 — No codec names, no delivery-method cues, no latency cue
The picker must **never** display: codec names (PGS, E-AC3, AAC, DTS, TrueHD, `mov_text`, `subrip`, …),
container/delivery terms (external/embed/encode, "burn-in", "image subtitles", "text subtitles"), **or
any "takes a moment / starts in a moment / ⏳" latency cue for PGS/encode tracks.** The delivery bucketing
still governs playback internally (a PGS pick still transcodes), but it is **not** a user-facing concept —
a 0–5s start delay is acceptable and needs no explanation. This is a deliberate product decision, not an
oversight: do not "helpfully" re-add a codec/delivery/latency indicator.

#### FR-RV-ASP1-3 — Fixed, plain-language badge vocabulary
Badges are the **only** metadata shown, derived from track flags — never from codec strings — and limited
to this set:
- **Default** — the source's `isDefault` track (the "pick this automatically" one).
- **Surround 5.1** / **Stereo** — from channel layout (audio rows only). Surround shown for >2ch,
  Stereo for 2ch; mono/other map to the nearest plain word.
- **Signs only** — a **forced** subtitle (translates on-screen text & foreign-language lines only, not
  the whole film).
- **Sound described** — an **SDH** track (adds speaker names & sound-effect notes for deaf / hard-of-
  hearing viewers). Detected from the SDH flag / title marker, distinct from forced and from a plain full
  track.
- **Describes action** — an **audio-description** track (Jellyfin "Synstolkning"/AD; audio rows).
- **Commentary** — a filmmaker commentary track.
- **Dubbed** — a soft note on an audio track whose language differs from the title's original language.

A track with none of these (a plain full soundtrack / full subtitle) shows just flag + name — no "Full"
badge is required, absence reads as "the normal one." Multiple badges may co-occur (e.g. `Default` +
`Surround 5.1`).

#### FR-RV-ASP1-4 — Keep every track; nothing hidden or merged
All tracks the source exposes remain individually selectable rows — the friendly labelling must **not**
drop, hide behind a "more" affordance, or silently merge tracks (e.g. two Danish subtitle variants stay as
two rows: `Dansk` and `Dansk · Signs only`). Straightforward to read, but lossless.

#### FR-RV-ASP1-5 — Off row and no-flag tracks
The **Subtitles** tab starts with an **Off** row (a crossed-out subtitle glyph in the flag slot, not a
country flag). A track with **no meaningful language** (a commentary, or an unlabelled track) shows a
neutral placeholder glyph (e.g. a mic for commentary) in the flag slot instead of a flag — never a blank
box and never a wrong/`xx` flag.

### B. Selected-track flag in the tabs

#### FR-RV-ASP1-6 — Each tab shows its current selection's flag
The **Audio** tab shows the flag of the currently-selected audio track; the **Subtitles** tab shows the
flag of the currently-selected subtitle track. The flag appears **inline after the tab label**, sized to
sit with the text. It updates **immediately** when the selection changes.

#### FR-RV-ASP1-7 — No flag when there's nothing to show
A tab shows **no flag** (not a placeholder, not an empty box) when its selected track has no country flag:
Subtitles **Off**, or any selected track without a resolvable language (e.g. commentary). The tab
collapses cleanly to just its label + icon in that case.

### C. Navigation & scrolling

#### FR-RV-ASP1-8 — Focused row always stays in view
When a title has many tracks (the library has titles with 10+ audio tracks and long subtitle lists), the
list scrolls **within the popup** and D-pad focus must always remain visible: moving focus down past the
fold scrolls the focused row into view, and back up does the same. The popup opens **scrolled to the
active track** so the current selection is visible immediately. Follow-the-focus scrolling must not
disturb the surrounding app layout (no `scrollIntoView`-style side effects).

#### FR-RV-ASP1-9 — Behaviour otherwise unchanged
Tab switch, ✓-tick semantics, Select-to-choose, Back-to-close, the pre-highlighted active row, and the
confirmation flash all behave as today. This phase changes presentation (flags, plain labels, badges, tab
flags, scroll-follow) — not the picker's control model. (R178's Select-while-chrome-hidden fix stays in
force.)

## i18n
- **FR-RV-ASP1-10** — All badge words and the two tab labels are localized en/da/fo (`Strings.kt` +
  `design/ravilo/ravilo-i18n.js`): Audio, Subtitles, Off, Default, Surround 5.1, Stereo, Signs only,
  Sound described, Describes action, Commentary, Dubbed. Native language **endonyms** come from the
  existing `languageName()` / `RAVILO_LANGS_UI` and are **not** re-translated per UI language (a Danish
  track reads `Dansk` regardless of the viewer's UI language).

## Reuse (don't rebuild)
`PlayerScreen.kt` TrackPicker popup + `PickerOption` (the row/list/focus/tick model — restyle it, don't
replace it); **`AudioFlagStrip.kt`** `LANG_CC` + `composeResources/drawable/flag_*.png` (the flags, same
assets as the detail hero and R172); `languageName()` / `RAVILO_LANGS_UI` (endonyms); `RaviloButton`,
`dpadFocusable`/`focusRestorer`. Centralise the duplicated `LANG_CC`/`LANGUAGE_NAMES`
(`AudioFlagStrip.kt` ↔ `RaviloPlayer.kt`) if you touch it.

## Non-goals
- **No new track metadata source.** Badges derive from flags Jellyfin/the source already expose
  (`isDefault`, forced, SDH, channel count, language, commentary/AD markers) — this phase does not add
  detection the backend doesn't already have. Where a variant genuinely can't be determined, the row
  falls back to flag + name.
- **No change to playback/transcode behaviour.** PGS still burns in server-side; delivery bucketing in
  `PlaybackService.kt` is untouched — only its *visibility* changes (it becomes invisible).
- **No two-pane / grouped / question-based layouts.** Directions 1/3/4 from the exploration are
  explicitly not shipping; Direction 2 (flag-forward flat list) is the design.
- **Phone target.** `Ravilo Mobile.html` currently has **no player screen**, so there is nothing to wire
  the picker into on phone yet. A phone player (or a detail-screen bottom-sheet picker) is a **separate
  follow-up**, not part of this phase; the exploration file keeps a phone mock for when that lands.
- No per-show "remember my language" persistence (candidate for a later phase).

## Acceptance
- Opening Audio & Subs shows a flat list where **every row leads with a flag** and a plain native name;
  **no codec name, delivery term, or latency cue appears anywhere** in the picker.
- Badges are limited to the FR-RV-ASP1-3 vocabulary; a forced Danish subtitle reads `🇩🇰 Dansk · Signs
  only`, an SDH English one `🇬🇧 English · Sound described`, an AD Danish audio track `🇩🇰 Dansk ·
  Surround 5.1 · Describes action`, commentary `🎙 Director's commentary · Commentary`.
- Every source track remains an individually selectable row; none are hidden or merged.
- The **Audio** tab shows the selected audio flag and the **Subtitles** tab shows the selected subtitle
  flag; the Subtitles tab shows **no flag** when set to Off; both update the instant the selection changes.
- With a 10+-track title, D-pad down keeps the focused row visible and the popup opens scrolled to the
  active track; the rest of the screen doesn't shift.
- en/da/fo strings present for tab labels and all badges.

## Dev-review addenda (2026-07-12)

Reviewed against the live player (`PlayerScreen.kt`, `RaviloPlayer.kt` + Android/Wasm actuals,
`AudioFlagStrip.kt`, the shared TV DTOs) and a scan of the real library DB. The chosen design
(Direction 2, flag-forward) is sound and internally consistent, and the picker **is** a restyle of the
existing `TrackPicker`. **But the design's clean `tracksFor()` shape (`lang`/`fmt`/`def`/`kind`/`note`/
`sub`) hides that several badges depend on data that does not currently reach the client.** So this phase
is a restyle **plus** three small data-plumbing changes and one new scrolling list. Corrections/additions:

**1. The endonym label has no Kotlin source (Current-state is wrong here).**
`RAVILO_LANGS_UI` lives only in the design JS (`design/ravilo/ravilo-i18n.js:203`); `languageName()`
(`RaviloPlayer.kt:75`) returns **English** (`da→"Danish"`). FR-RV-ASP1-1's primary label (`Dansk`,
`Føroyskt`, `中文`) needs a **new `code→endonym` map ported to Kotlin** — cover at least the library's top
languages (en da fo sv no de fr es fi nl zh pt it pl, per the DB scan). Do **not** use `languageName()`
for the row label. The **flag** map (`LANG_CC`) is fine as-is (see §7).

**2. Audio rows drop `channels` + `isDefault` — blocks the "Surround 5.1 / Stereo" and audio "Default" badges.**
The runtime model `PlayerAudioTrack(index, label, language)` (`RaviloPlayer.kt:6`) carries neither, even
though the shared `AudioTrack` DTO has `channels` + `isDefault` and the Android actual
(`RaviloPlayerAndroid.kt` ~L202–211) **already holds that `meta` at the mapping site** and just doesn't
forward it. Fix: **widen `PlayerAudioTrack` with `channels: Int?` + `isDefault: Boolean`, populate in both
actuals.** Derive the badge from `channels` (>2 → Surround; 2 → Stereo; 1 → Mono) — never from the codec
string (which FR-RV-ASP1-2 bans showing anyway).

**3. SDH / Commentary / Describes-action / region-qualifier have no flags — parse them from the title, and coverage is partial.**
Only `forced` (→ "Signs only") and `isDefault` are real booleans. "Sound described" (SDH), "Commentary",
"Describes action" (AD), and the `中文 · Simplified` suffix must be **derived by scanning the track
`label`/title**. Real markers in this library (verified from the DB): SDH → `(SDH)` / `SDH` / `hard of
hearing`; AD → `Synstolkning` / `Audio Description` / `Described` / `AD`; commentary → `Commentary`;
region → `(Simplified)` / `(Traditional)` / `(Canadian)` / `(Latin America)`. Two cautions: (a) **many
tracks have no title at all** (every PGS track on the 28-Years-Later test title, and the 191
`unknown`-codec tracks, are untitled) → those correctly fall back to flag+name per the non-goal, so badge
coverage is inherently partial; (b) the same DisplayTitle string also contains the codec/channel words
FR-RV-ASP1-2 bans (`Dolby Digital - 5.1`) — the parser must **classify the marker and discard the rest,
never echo the title**.

**4. "Dubbed" needs the title's original language, which reaches the player nowhere.**
`originalLanguage` is on **no** TV DTO, no `Dest.Player`, no play context (grep-confirmed). Pick one:
   - **(a) plumb it** — add `originalLanguage: String?` to the series/movie detail DTO → `EpisodePlayContext`
     / `Dest.Player` → `PlayerScreen`, then flag audio rows whose language differs. Moderate (one nullable
     field down an existing path).
   - **(b) defer the Dubbed badge** to a follow-up; ship the other five badges now.

   Recommend (a) if cheap in your DTO layer, else (b). This is the **only** badge with a hard data gap —
   don't let it block the rest.

**5. FR-RV-ASP1-8 (scroll-follow) is net-new, not a restyle.**
Today's list is a plain `Column { items.forEachIndexed {…} }` (`PlayerScreen.kt` ~L1413) in a fixed-width
`Box` with **unbounded height** — a 10+-track title (the library has them: 28-Years-Later = 7 audio + 13
subtitle tracks) overflows off-screen. FR-RV-ASP1-8 requires: **bound the popup height**, make the list a
`LazyColumn`/`Column.verticalScroll` with a `LazyListState`, follow focus via `animateScrollToItem`, and
**open scrolled to the active index**. Real work — the "restyle, don't replace" note undersells this item.

**6. Consistency: the existing PGS "burn-in" overlay violates the spirit of FR-RV-ASP1-2.**
FR-RV-ASP1-2 bans delivery + latency cues, but choosing a PGS subtitle today shows a full-screen overlay
reading **"Burning in subtitle… (transcoding)"** (`Strings.kt:163`, shown at `PlayerScreen.kt:677`) —
which names the delivery method **and** is a latency cue. Either scope FR-RV-ASP1-2 explicitly to the
picker rows, or (recommended) **reword this overlay to a neutral loading state** (reuse the generic
buffering spinner / "Loading…") so the delivery method stays invisible end-to-end. Product decision.

**7. Minor.**
   - `LANG_CC` is **not duplicated** — it lives once in `AudioFlagStrip.kt` (`internal`, already reused by
     `RequestLanguagePicker` + `DiscoverDetailScreen`); just import it. The picker's real dup risk is only
     the English-name map (`LANGUAGE_NAMES` in `RaviloPlayer.kt`), which is separate from the new endonym
     map you add in §1. So "centralise the duplicated `LANG_CC`" is a non-issue.
   - The design **exploration** file's Direction-2 blurb still lists a *"Starts in a moment"* latency badge
     (`Audio & Subtitles Picker.html:524`); the final `plBadges` correctly dropped it and FR-RV-ASP1-2
     forbids it — **follow the spec, not the exploration blurb.**

**Ship order:** (1) endonym map + (2) `PlayerAudioTrack` channels/isDefault are prerequisites for the
visible badges; then (3) title-parsing, (5) scrollable list, and finally the flag-forward restyle + tab
flags (both straightforward once the data is present). (4) Dubbed and (6) overlay wording are the two
product decisions to confirm. Everything is grounded in the provided design files
(`design/ravilo/Audio & Subtitles Picker.html` Direction 2, `ravilo-player.{js,css}` `.pl-opt`/`.pl-flag`/
`.pl-badges`/`.pl-chip`/`.pl-tab-flag`) — the CSS/markup there is the visual target and needs no change.

## Status
Design-authored, **dev-reviewed 2026-07-12**, `Planned`. `scripts/check-phases.sh` will flag it for a
`STATUS.md` row — **STATUS.md is code-owned; do not add the row from the design side.** The deferred
"remember my language" follow-up (this spec's non-goal) is now specified as **R181** (client-side default
& remembered-language). **Next Ravilo number after R181 is R182.**

## Source references
- Design: `design/ravilo/Ravilo TV.html` — `ravilo-player.js` (`renderPicker`, `PL_CC`, `PL_KIND`,
  `plFlag`, `plBadges`, `plChip`, `plTabFlag`, `scrollFocusIntoView`, the `.pl-tab-flag` slots in the
  picker head) + `ravilo-player.css` (`.pl-opt`/`.pl-flag`/`.pl-badges`/`.pl-chip`/`.pl-tab-flag`); track
  data in `ravilo-app.js` `tracksFor()` (each track carries `lang`/`fmt`/`def`/`kind`/`note`/`sub`/`off`).
- Design exploration: `design/ravilo/Audio & Subtitles Picker.html` (Directions 1–4, TV + phone;
  Direction 2 chosen).
- Code to restyle: `PlayerScreen.kt:1252-1305` (TrackPicker popup + `PickerOption`).
- Code to reuse: `AudioFlagStrip.kt` (`LANG_CC` + `flag_*.png`), `RaviloPlayer.kt` (duplicate `LANG_CC`/
  `LANGUAGE_NAMES` — centralise), `languageName()` / `RAVILO_LANGS_UI` (endonyms).
- Related: **R75/R78/R134** (detail-hero merged audio+subtitle flag line — same flag assets/vocabulary
  source), **R172** (flag+endonym request picker — same TrackPicker pattern), **R178** (Select-while-
  chrome-hidden fix — unaffected, stays in force), backend **`PlaybackService.kt`** subtitle
  delivery-method bucketing (kept internal).
