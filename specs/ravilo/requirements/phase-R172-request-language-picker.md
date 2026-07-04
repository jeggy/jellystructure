# Phase R172 — Ravilo: Original-vs-Nordic language picker + change-later in the Request flow (FR-RV-RL1)

> The TV half of request-language steering. Requesting a title now asks **"in which language?"** with
> **flags**, remembers a per-viewer default, shows the chosen flag on the status, and lets a viewer
> **switch a still-waiting request to another language** later. Backend + *arr profiles:
> **[Phase 139](../../requirements/phase-139-request-language-steering.md)**. Design mechanics:
> [`../../research-reports/request-language-steering-2026-07-04.md`](../../research-reports/request-language-steering-2026-07-04.md).

## Goal
Instead of a bare **Request** press, the viewer picks the download language — **Original** (the standard
release) or **Dansk / Nordic** (Nordic release, usually incl. English) — as a **flags-first, jargon-free**
choice. Their default is pre-selected so it's usually a single confirm. A strict request that's still
waiting shows an honest status and can be **changed to another language from the app** days later.

## Current state (verified 2026-07-04)
- Requesting is a **single button, no confirmation, fires immediately**: `DiscoverDetailScreen.kt`
  `PrimaryAction()` (L163-194) → `DiscoverDetailStore.request()` (L46-51) → `TvApiClient.requestDiscover`
  (L270) → `POST /api/tv/discover/request {mediaKind, tmdbId, title}`. No language anywhere.
- Reusable pieces already in the app: the player **TrackPicker** popup (`PlayerScreen.kt:1252-1305`,
  rows via `PickerOption`) — the exact pattern for a language sheet; `SeasonPicker.kt` pill row;
  `RaviloButton`; **`AudioFlagStrip.kt`** `LANG_CC` map + `flag_*.png` (incl. `da→flag_dk`, `fo→flag_fo`,
  `en→flag_gb`); endonyms in `RAVILO_LANGS_UI` (`da→Dansk`, `fo→Føroyskt`) / `Strings.kt`.
- `device.isKids` exists (parental cap, `TvRoutes.kt:173`) but drives nothing on the request path.
- No "your requests / in-progress" surface exists — a viewer can only re-find a title via search.

## Requirements

### A. Language picker on Request (always-show, default pre-selected)
1. Pressing **Request** opens a small popup in the **TrackPicker** style (`PlayerScreen.kt` popup:
   card ~`0xFF0E1119`@94%, rounded, focus ring, ✓ tick). Rows = the Phase-139 intent catalog (from the
   DTO), each a **flag + endonym** (`🇩🇰 Dansk`, `🇬🇧 Original`), rendered via `AudioFlagStrip`'s
   `LANG_CC`/`flag_*.png` and the endonym labels. `original` with a blank flag → the title's own
   original-language flag (from its `AudioFlagStrip` data) or a globe glyph.
2. The viewer's **resolved default** (see B) is **pre-highlighted/focused** so **Select** immediately
   confirms (one tap). **Back** cancels without requesting. Selecting a row calls
   `requestDiscover(mediaKind, tmdbId, title, language = intentId)`.
3. If the catalog has **0 or 1** intent, skip the popup entirely (request fires as today) — the picker
   only appears when there's a real choice.

### B. Per-viewer default request language (+ kids)
1. Admin Ravilo config editor (`src/wasmJsMain/.../ui/RaviloConfig.kt`, the Request card
   `renderDiscover` ~L1957 and/or the behaviour section ~L2437): a **Default request language** picker,
   cloning the existing per-user **Interface language** override pattern (`#beh-u-lang` +
   `ResolvedBehaviourField`, R162) — global default with per-user override + reset.
2. Resolution (server, Phase 139 §C.2) is: explicit pick → per-user default → global `kids_default` when
   `device.isKids` → catalog default. The TV only needs to send the **explicit pick**; everything else
   resolves server-side, and the DTO tells the app which intent is the **pre-select** for the popup.

### C. Status carries the flag; strict-waiting is honest
1. The request status chip / button on the detail page (`DiscoverDetailScreen.kt` PrimaryAction + status
   line) and the tile badge (`DiscoverScreen.kt` `RequestTile`, reusing the `episodeBadge` slot) show the
   **chosen language flag** through every state: `Requested · 🇩🇰` → `Downloading 43% · 🇩🇰` → `Available`.
2. A **strict, still-unfulfilled** request (Phase 139 `request_intent.strict` + live Seerr status not yet
   available) shows honest copy instead of a generic stall — e.g. **"Waiting for a Dansk release"** /
   *"Bíðar eftir donskum útgávu"* — with the **Change language** action (D) alongside.

### D. Change a waiting request's language, later
1. On a not-yet-available request, a **Change language** affordance re-opens the picker; choosing a
   different intent calls `POST /api/tv/discover/request/{mediaKind}/{tmdbId}/language {language}`
   (Phase 139 §E) — which re-profiles the *arr item and re-searches. The status/flag update to the new
   choice.
2. **Find it again days later:** the Request tab gains an **"In progress"** rail at the top listing the
   viewer's own not-yet-available requests (their `request_intent` rows, status resolved live), each tile
   showing its flag + status and opening the detail with the Change-language action. This is what makes
   "come back later and switch Nordic → Standard" reachable without remembering to search.

### E. i18n
1. New keys in en/da/fo (`Strings.kt` + `design/ravilo/ravilo-i18n.js`): `request.in_language`
   ("Request in…"), `request.change_language` ("Change language"), `request.waiting_for`
   ("Waiting for a {lang} release"), `request.in_progress` ("In progress"). Language **rows** use
   endonyms (no translation). Watch the Seerr **lowercase ISO-639-1** gotcha already fixed in-tree for
   discover feeds when any code is sent.

## Reuse (don't rebuild)
`PlayerScreen.kt` TrackPicker popup + `PickerOption` (the picker); `AudioFlagStrip.kt` `LANG_CC` +
`composeResources/drawable/flag_*.png` (flags); `RAVILO_LANGS_UI` / `languageName()` (endonyms);
`RaviloButton`, `SeasonPicker`, `dpadFocusable`/`focusRestorer`. Note `LANG_CC`/`LANGUAGE_NAMES` are
duplicated between `AudioFlagStrip.kt` and `RaviloPlayer.kt` — centralise if touched.

## Non-goals
- No new modal framework — reuse the hand-rolled TrackPicker popup pattern (no generic Dialog exists).
- No language change **after** a title is available (Phase 139 non-goal — that's a re-acquire).
- No per-episode/season language choice.
- The intent **catalog** (labels/flags/profiles) is authored in admin Settings (Phase 139 §F), not on TV.

## Acceptance
- Pressing Request on a movie with 2 intents shows the flag popup with the viewer's default pre-selected;
  Select confirms in one press; Back cancels. With 0–1 intents, no popup (fires as before).
- A kids TV (`device.isKids`) with the global kids default = Dansk pre-selects 🇩🇰.
- The status chip shows the chosen flag through requested → downloading → available; a strict-waiting
  Nordic request shows "Waiting for a Dansk release" + **Change language**.
- **Change language → Original** on a waiting request updates the flag/status and (via Phase 139)
  re-profiles + re-searches the *arr item.
- The Request tab's **In progress** rail lists the viewer's waiting requests days later, each openable to
  change its language.
- en/da/fo strings present; endonyms render on the rows; flags render for da/fo/gb/original.
- Verified via `:ravilo-web:compileKotlinWasmJs` (+ Android compile for the seam-free common UI).
