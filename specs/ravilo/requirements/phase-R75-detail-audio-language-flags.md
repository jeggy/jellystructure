# Phase R75 — Detail hero: audio-track language flags

> Show the viewer, on a title's detail screen, which languages they can **hear** it in — a row of
> country flags under the meta line, one per audio track that has a language, in file order.

## Problem
The Ravilo detail screen tells the viewer the year, genres and rating, but not which audio
languages a title offers. On a Nordic/Faroese-leaning service this is exactly the deciding signal —
"is there a Faroese or Danish dub, or only English?" — and it is currently invisible until the viewer
starts playback and opens the audio picker. The metadata exists: Jellyfin's audio `MediaStreams`
carry a `language` per track, and Phase R46 already plumbs audio-track metadata to the client for the
player picker.

## Goal
Add an **audio-language flag strip** to the detail hero (`DetailScreen`), directly beneath the
existing meta row (badge · year · genre · rating). One flag per audio track that has a language, in
**physical track order**, so the viewer sees at a glance what dubs exist and in what priority — the
same rule and visual language as the Jellystructure admin pagebar (Phase 87).

## Current state (as-is)
- **Detail screen renders meta but no audio info.** `screens/DetailScreen.kt` builds the hero
  (kicker, title, `hero-meta` row, synopsis, action buttons) from the detail payload; audio languages
  are not part of what it shows.
- **Audio metadata reaches the client only at playback time.** Phase R46 added an `AudioTrack` list to
  the `StreamTicket` (`shared/.../tv/Models.kt`), populated by `PlaybackService` from
  `getItemDetail`'s `MediaStreams`. The **detail** DTO returned by the detail API (R07) does not yet
  carry the ordered audio-language list, so the screen has nothing to render.
- **Mockup already specifies the visual.** `design/ravilo/ravilo-app.js` (`renderDetail` →
  `audioFlagsHTML`) and `design/ravilo/ravilo.css` (`.dhero-audio`) define the strip; the live HTML
  preview (`design/ravilo/Ravilo TV.html`) shows it on Big Buck Bunny and on every other title.

## Requirements

### A. Detail API — expose ordered audio languages
1. Add an **ordered audio-language list** to the detail payload (reuse the Phase R46 `AudioTrack` shape
   or a slimmer `{ language }` projection), populated in the detail handler from
   `itemDetail.mediaStreams.filter { it.type == "Audio" }` in **physical track order**. Data plane is
   unchanged — this only enriches the **control-plane** detail response. For series, resolve at the
   level the detail screen represents (the played/episode file's audio), consistent with how the
   player ticket resolves tracks.

### B. DetailScreen — render the flag strip
2. In `screens/DetailScreen.kt`, render a flag row under `hero-meta`: an "AUDIO" label + up to **5**
   flags + an optional `+N` count, matching the mockup `.dhero-audio`.
3. **Rules (identical to Phase 87):** physical track order; **skip** tracks with no language; **hide
   the whole strip** when no audio track is tagged; **max 5 flags**, then a `+N` pill where `N` =
   remaining tagged tracks; keep duplicates.
4. Map ISO-639-1 language → ISO-3166-1 country via the shared map (same keys as Phase 87:
   `en→gb · da→dk · fo→fo · is→is · no→no · sv→se · de→de · es→es · fr→fr · it→it · nl→nl · pt→pt …`);
   an unmapped language is skipped rather than drawn broken.

### C. Flag assets for Compose Multiplatform
5. The web flag-icons CSS approach does not apply to the native TV target. Bundle the **same 99-flag
   SVG set** (Phase 87 / `design/flags/`) as a **`:ravilo-ui` compose resource**, addressed by country
   code, and render each flag as an `Image`; the `+N` pill reuses the existing chip style. The shared
   lang→country map lives in `:shared` (or `:ravilo-ui`) so backend and both client targets agree.
6. **Web actual** (`RaviloPlayerWasm` target / web build): reuse `design/flags.css` + `design/flags/`
   directly (the strip is plain `.fi.fi-<cc>` spans, exactly as the mockup), so the web build needs no
   separate asset path.

## Invariants
- DTOs defined once in `:shared`, reused by backend + android + wasmJs.
- Renders **server-pushed state only** — the flag list is server-derived from Jellyfin `MediaStreams`,
  never client-guessed.
- Bytes still stream straight from Jellyfin; this is detail-payload metadata only.

## Out of scope
- Subtitle-language flags (audio only here).
- Showing flags on poster tiles / rows — detail screen only.
- Auto-selecting or re-ordering audio by the viewer's language (R46 non-goal stands; display only).
- A focusable/clickable flag affordance — the strip is non-interactive.

## Design reference
`design/ravilo/Ravilo TV.html`, `design/ravilo/ravilo-app.js` (`renderDetail`, `audioFlagsHTML`,
`LANG_CC`/`LANG_NAME`, `FLAG_MAX`), `design/ravilo/ravilo.css` (`.dhero-audio`, `.aflag`,
`.aflag-more`); `design/flags/` + `design/flags.css` (shared asset set). Source:
`ravilo-ui/.../screens/DetailScreen.kt`, `shared/.../tv/Models.kt` (`AudioTrack`, detail DTO),
`src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (`getItemDetail` MediaStreams).
Related: Phase 87 (admin pagebar flags — canonical mapping + asset set), R46 (audio-track metadata to
client), R07/R13 (detail API + screens), R41 (Jellyfin MediaStreams subtitle tracks).
