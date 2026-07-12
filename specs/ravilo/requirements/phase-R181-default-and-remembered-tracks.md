# Phase R181 — Ravilo: respect default tracks & remember the viewer's language choices (client-side) (FR-RV-TRK1)

> When a title opens, the player should pick a **sensible audio + subtitle track automatically** instead of
> always "first track, subtitles off" — honouring the source's own defaults, and, more importantly,
> **whatever the viewer last chose**. When a viewer changes the soundtrack or turns on subtitles, Ravilo
> **remembers that choice and applies it next time** — persisted **on the device, per profile, never sent
> to the jellystructure backend.** This is the follow-up the R180 non-goal defers ("No per-show 'remember
> my language' persistence"). This phase is behaviour + persistence **beneath** the R180 picker UI; it does
> not change how the picker looks.

## Goal
A viewer who watches everything in `Dansk` (or always turns on `Føroyskt` subtitles) sets it **once** and
never touches Audio & Subs again — each new title, and each next episode in a binge, comes up in their
language automatically. A viewer who has expressed no preference gets the source's intended default, not a
mechanical "track 0 / subs off". All of this memory is **local to the device and the profile** — a
deliberate contrast to the server-stored viewer settings (skin/ui-lang/autoplay), and with no cross-device
sync.

## Current state (verified)
- **Initial track selection is hardcoded.** `PlayerScreen.kt:197-198`: `selectedAudio = 0` (first track),
  `selectedSub = -1` (Off). Nothing consults the source `isDefault` flags or any stored preference. On an
  auto-advance to the next episode the new `PlayerScreen` re-initialises the same way, so a binge does
  **not** carry the chosen language forward.
- **Track flags are available** (post-R180): each audio/subtitle track carries `language`, `isDefault`,
  and (subtitles) `forced`; audio carries `channels`/`isDefault` once R180 §2 widens `PlayerAudioTrack`.
  Remembered prefs must key on **language (+ variant)**, never on the ExoPlayer track index — indices
  differ across episodes and files.
- **Ravilo has no client-local preferences store today.** `SettingsStore` (`SettingsScreen.kt:51`) persists
  skin / ui-language / autoplay-next / continue-progress to the **backend** via
  `apiClient.putViewerSettings(...)` (server-side per-user `RaviloConfig`). The **only** existing
  client-local persistence is **`MultiTokenStore`** — an `expect object` with an Android actual
  (`SharedPreferences("ravilo_sessions")`, `MultiTokenStoreAndroid.kt`) and a web actual (`localStorage`,
  `MultiTokenStoreWasm.kt`), storing auth sessions. So "remember on the app side, not the backend"
  genuinely **requires a new local store** — modelled on `MultiTokenStore`, not on `SettingsStore`.
- **Profiles** are multiple-per-device, each keyed by **jellyfin user id** (`LocalSession.userId`);
  `MultiTokenStore.getActive()` gives the active one, `.remove()/.clear()` on sign-out/unpair.

## Requirements

### A. Client-side preferences store (new local seam)

#### FR-RV-TRK1-1 — A new `expect object` for local playback prefs, keyed by active profile
Introduce a small persistence seam (e.g. `PlaybackPrefsStore`) mirroring **`MultiTokenStore`'s** shape:
`expect object` in commonMain, Android actual over `SharedPreferences`, web actual over `localStorage`.
It **never** calls `TvApiClient` and **never** round-trips to jellystructure — all state is local to the
device. Every stored value is scoped to the **active profile's jellyfin user id**, so profiles on one
device keep independent memory. Cleared for a profile when that profile is removed/unpaired (hook into
`MultiTokenStore.remove`/`clear`).

### B. Default-track resolution (what plays when the viewer hasn't touched it)

#### FR-RV-TRK1-2 — Resolution order, applied silently on load
On opening a title (before playback starts, no user interaction, no flash — set the selection during the
same pre-paint init that currently hardcodes `0`/`-1`), resolve **audio** and **subtitles** independently,
highest priority first:
1. **Remembered per-series choice** — if the viewer has previously chosen a track for **this series**
   (see FR-RV-TRK1-4), honour it.
2. **Remembered global language preference** — the viewer's learned preferred **audio language** and
   preferred **subtitle language (or explicit Off)** (FR-RV-TRK1-5).
3. **Source default** — the audio track flagged `isDefault`; for subtitles, the `isDefault`/`forced`
   subtitle the source marks, else **Off**.
4. **Fallback** — first audio track; subtitles **Off**.
Each tier is tried in order and **skipped if it can't be satisfied in the current title** (e.g. a
remembered `Dansk` audio that this title doesn't have falls through to the next tier). "Subtitles Off" is
a real, first-class remembered state — not the absence of a preference.

#### FR-RV-TRK1-3 — Match by language + variant, not by index
When applying tiers 1–2, resolve the remembered **language code** (plus, where determinable, the
forced/SDH variant from R180 §3) to the **best-matching track in the current title**. Prefer an exact
language match; among multiple same-language tracks prefer the one matching the remembered variant, else
the source `isDefault`, else the first. If no language match exists, fall through.

### C. Remembering the viewer's choices

#### FR-RV-TRK1-4 — Remember on every explicit change (per series)
When the viewer picks an audio or subtitle track **in the player**, persist that choice against **this
series** (movies: against the title): `series → {audioLang, subtitleLang-or-Off, +variant}`. A subsequent
open of the same series (or its next episode) resolves to it via FR-RV-TRK1-2 tier 1. PGS/encode subtitle
picks are remembered **by language**, never by codec/delivery.

#### FR-RV-TRK1-5 — Learn a global language preference from the same choices
Each explicit change also updates a device+profile **global** preferred **audio language** and preferred
**subtitle language (or Off)** — the viewer's most-recent deliberate choice becomes the default for titles
they haven't watched before (FR-RV-TRK1-2 tier 2). This is what lets "I always watch in Dansk" work on a
brand-new title with zero interaction.

#### FR-RV-TRK1-6 — Carry the choice across a binge
Within a series, the audio + subtitle selection applied to an episode carries to each **auto-advanced next
episode** (R179/R176 continuity) — implemented as the per-series remembered choice (FR-RV-TRK1-4) being
re-resolved on each new `PlayerScreen`, so the language never silently resets mid-binge.

## Open design decision (flag for the design tool / product)
**Granularity.** This spec adopts a **layered model** — per-series remembered choice (tier 1) *over* a
learned global language preference (tier 2). Alternatives the design tool may prefer: **(a) global-only**
(one "preferred audio / preferred subtitle language" pair, no per-series memory — simplest, but can't
honour "English for this one show, Dansk for everything else"); **(b) per-title only** (no global learning
— every new title is source-default until the viewer chooses). The layered model is the recommendation
because it matches the two things the user asked for at once ("respect defaults" *and* "remember what was
watched"); confirm before building.

## i18n
- **FR-RV-TRK1-7** — No new player-chrome strings are strictly required (selection is silent). If a
  settings affordance is added to view/reset remembered prefs (optional, see non-goals), localise its
  labels en/da/fo in `Strings.kt` + `design/ravilo/ravilo-i18n.js`.

## Reuse (don't rebuild)
`MultiTokenStore` (the `expect object` + SharedPreferences/localStorage pattern — model the new store on
it, and hook its `remove`/`clear` for per-profile cleanup); the R180 track model (`language`/`isDefault`/
`forced`/`channels`); the existing `PlayerScreen` init that sets `selectedAudio`/`selectedSub` (change what
it resolves to, not the mechanism); `LANG_CC` / the R180 endonym map only if a settings/reset UI is added.

## Non-goals
- **No backend / RaviloConfig storage.** Deliberately client-local — the opposite of `SettingsStore`. No
  new `putViewerSettings` field, no server schema change.
- **No cross-device sync.** Memory lives on the device that made the choice; another TV/phone starts from
  source defaults until the viewer chooses there. (A future server-backed sync is a separate phase if ever
  wanted.)
- **No new track detection.** Reuses only the flags R180 already surfaces (`language`/`isDefault`/`forced`,
  and the title-derived SDH/variant where available). Where a variant can't be determined, remember by
  plain language.
- **No picker UI change.** R180 owns the Audio & Subs presentation; this phase is the behaviour beneath it.
- **A dedicated "manage remembered languages" settings screen is out of scope** (a small reset affordance
  is optional, not required) — the memory is meant to be invisible and self-maintaining.

## Acceptance
- Opening a title the viewer has never watched selects the **source `isDefault`** audio (not mechanically
  track 0) and the source's default/forced subtitle or Off — verified on a multi-audio title.
- After choosing `Dansk` audio on one title, opening a **different** title that has a Danish track comes up
  in `Dansk` automatically, with no interaction.
- After turning on `Føroyskt` subtitles on a series, the **next episode** (auto-advanced) keeps `Føroyskt`
  subtitles; re-opening that series later still does.
- Choosing **Subtitles Off** is remembered as Off (not "no preference") and applied next time.
- A remembered language absent from a given title falls through cleanly to the next tier (no crash, no
  blank selection).
- Two profiles on one device keep **independent** memory; nothing about these choices appears in any
  backend request (verify: no `putViewerSettings`/config call fires on a track change).

## Status
Design-authored companion to R180, **`Planned`**. `scripts/check-phases.sh` will flag it for a `STATUS.md`
row — **STATUS.md is code-owned; do not add the row from the design side.** Depends on R180 §2 (widened
`PlayerAudioTrack`) for variant/channel matching. **Next Ravilo number after this is R182.**

## Source references
- Hardcoded default to replace: `PlayerScreen.kt:197-198` (`selectedAudio = 0`, `selectedSub = -1`).
- Local-persistence precedent (model the new store on this): `MultiTokenStore` — `MultiTokenStoreAndroid.kt`
  (`SharedPreferences`), `MultiTokenStoreWasm.kt` (`localStorage`); active profile via `getActive()`.
- Server-side settings contrast (do **not** use this path): `SettingsScreen.kt:51` `SettingsStore` →
  `apiClient.putViewerSettings`.
- Track flags: R180 addendum §2/§3 (audio `channels`/`isDefault`; subtitle `forced`/title-derived variant).
- Continuity: R176 (playstate cache), R179 (multi-episode grouping), the auto-advance path in
  `PlayerScreen.advanceNext()` / `RaviloApp` `Dest.Player` replaceTop.
