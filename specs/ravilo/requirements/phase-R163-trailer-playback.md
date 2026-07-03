# Phase R163 — Ravilo TV: Play-Trailer button + fullscreen YouTube / Vimeo embed player

> The detail hero's **▷ Trailer** button becomes real: it appears **only when the title has a trailer**
> (the `trailer` ref on the detail DTO from [Phase 130](../../requirements/phase-130-tmdb-trailer-ingest.md)),
> and selecting it opens the trailer **fullscreen** in an **embedded YouTube / Vimeo player**, chrome-matched
> to the real media player. Back / Select on Close exits and stops playback. Because TMDB trailers live on
> YouTube/Vimeo, they **can't** run through the Media3/ExoPlayer engine (no direct stream) — this is a
> deliberately **embedded provider player**, the same way the real player streams from Jellyfin.

**Status:** Planned — **design built**, app integration unbuilt.

## Problem
The Movie/Series detail hero already shows a **Trailer** button, but it is a **stub**: it is shown on **every**
title regardless of whether a trailer exists, and selecting it just flashes the title — there is no trailer
data and no player. Viewers expect the button to (a) only appear when there's actually a trailer and (b)
actually play it.

## Architectural constraint (driving decision)
Ravilo renders **server-pushed state**; the trailer **reference** (`{ site, key, name }`) arrives on the
detail DTO from Phase 130 — the client never queries TMDB/YouTube directly. **Playback** of that reference is
an **embedded provider player** (YouTube/Vimeo **cannot** be fed to the Media3/ExoPlayer path — there is no
direct byte stream), which is the **same bounded exception** the real player already is (it streams from
Jellyfin). On web it is the provider's **iframe** (`youtube-nocookie.com/embed` · `player.vimeo.com`); on
Android it is the platform embed (a `WebView`-hosted iframe or a YouTube/Vimeo player component) — **not**
ExoPlayer.

## Current state (as-is)
**Design is built** in `design/ravilo/`:

- **`ravilo-data.js`** — `TRAILERS` map (per title `{ site, key, name }`; YouTube + one Vimeo example) and
  `trailerFor(item)`, exported on `window.RAVILO`. Stands in for Phase 130's `detail.trailer` DTO field.
- **`ravilo-app.js`**
  - The hero **Trailer** button is now **conditional**: `${R.trailerFor(item) ? …button… : ''}` — hidden when
    there's no trailer.
  - `openTrailer(item)` builds a fullscreen `.rv-trailer` overlay: a provider **iframe**
    (`trailerEmbed()` → `youtube-nocookie.com/embed/{key}?autoplay=1…` or
    `player.vimeo.com/video/{key}?autoplay=1…`) plus a top chrome bar (**Trailer** kicker · title · trailer
    name · **YouTube/Vimeo** pill · **✕ Close**). `closeTrailer()` clears the iframe (**stops playback**),
    hides the overlay, and restores focus to the Trailer button.
  - A **capture-phase** key handler owns input while open: **Back / Esc / Select** → close; arrows are
    swallowed (single control). `back()` also closes the trailer first. Pointer: clicking **✕ Close** closes.
- **`ravilo.css`** — `.rv-trailer` fullscreen black surface, `.rv-tr-frame` iframe, `.rv-tr-top` gradient
  chrome, `.rv-tr-kick` / `.rv-tr-name` / `.rv-tr-src` / `.rv-tr-close` (focus ring on the close button).
- **`ravilo-i18n.js`** — `trailer` is **"Trailer" in en / da / fo** (intentionally the same word in all three).

**The gap:** the Compose app has no Play-Trailer control, no embedded YouTube/Vimeo surface, and no gating on
a DTO trailer field.

## Requirements

### A. Gate the button on a real trailer
1. Show the **▷ Trailer** action on Movie **and** Series detail **only when** `detail.trailer` is present
   (Phase 130 DTO). No trailer ⇒ no button (never a dead affordance).

### B. Fullscreen embedded player
2. Selecting **Trailer** opens the trailer **fullscreen above the app**, **autoplaying**. YouTube →
   `youtube-nocookie.com/embed/{key}`; Vimeo → `player.vimeo.com/video/{key}`. Chrome matches the real
   player: a top bar with a **Trailer** kicker, the **title · trailer name**, a **provider** pill
   (YouTube / Vimeo), and a focusable **Close**. On **Android** use a platform embed (WebView-hosted iframe or
   a YouTube/Vimeo player component), **not** ExoPlayer.

### C. Input + lifecycle
3. The trailer surface **owns input while open**: remote **Back** or **Select on Close** exits, **stops
   playback**, and restores focus to the **Trailer** button on the detail hero. It never leaks D-pad moves to
   the screen behind it.

### D. Label
4. The button/label reads **"Trailer"** in all three UI languages (no translation) — already in
   `ravilo-i18n.js`.

## Invariants
- **Button appears iff a trailer exists** on the detail DTO.
- **Embedded provider playback, never ExoPlayer** — YouTube/Vimeo have no direct stream; this is the same
  bounded exception as the Jellyfin-streaming player.
- **Closing stops playback** (iframe torn down / player released) and **restores focus** to the Trailer button.
- **Back always exits** the trailer first; the surface owns its own input while open.
- The trailer **reference is server-pushed** (Phase 130 DTO) — the client never calls TMDB/YouTube to discover
  it.

## Out of scope
- Trailers on **Top 10 / Discover / Upcoming** detail (those aren't library items) — Movie/Series detail only.
- **Autoplay-on-hover** hero previews, or a picture-in-picture mini player.
- **Multiple** trailers / an extras gallery (Phase 130 stores one).
- Downloading / caching the trailer locally.

## Source references
- Design: `design/ravilo/ravilo-data.js` (`TRAILERS`, `trailerFor`); `design/ravilo/ravilo-app.js`
  (`openTrailer` / `closeTrailer` / `trailerEmbed`, the conditional hero button, the capture-phase key handler,
  `back()` guard); `design/ravilo/ravilo.css` (`.rv-trailer` / `.rv-tr-*`); `design/ravilo/ravilo-i18n.js`
  (`trailer`).
- Backend / app: **[Phase 130](../../requirements/phase-130-tmdb-trailer-ingest.md)** `detail.trailer` DTO
  field; a Compose **TrailerScreen/overlay** hosting the provider embed (WebView / YouTube-Vimeo player) with
  Back + Close; button gating on the detail screens.
- Related: **Phase 130** (TMDB ingest + DTO — the data source), **R14** (the media-player chrome this mirrors),
  **constitution** (renders server state; embedded provider playback is a bounded exception like the Jellyfin
  player).
