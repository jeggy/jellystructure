# CLAUDE.md — design project notes

This Cosmos project holds the **HTML/CSS design mockups** for **jellystructure**
(`app/`) plus the low-fi wireframes (`wireframes/`). These mirror the `design/`
folder of the repo **github.com/jeggy/jellystructure**.

## Before doing design work, read the repo's source-of-truth docs
- `CONSTITUTION.md` — non-negotiable architecture + the **language-resolution
  algorithm** and **config shape**. The document wins over the mockups.
- `CLAUDE.md` — project guidance, the canonical **UI Pages table**, phases.
- `design/wireframes/Requirements & Phases.html` and `Implementation Plan.html`.

Pull them with the GitHub tools (ref `main`).

## Design constraints to respect
- `design/app/` mockups are **the visual target for the Kotlin/WASM frontend**.
- Real frontend styling is **Tailwind CSS** (classes scanned from Kotlin source at
  build time). Our mockups use a custom CSS-variable system in `wf.css`/`app.css`
  — treat them as the *visual* spec; tokens map to Tailwind on implementation.
- Frontend is **DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.

## Current visual system (this redesign)
- Dark-primary with a light toggle. Single **Aurora** visual direction (cinematic,
  glassy, gradient); theme persisted in localStorage by `app/app-shell.js`.
  Jellyfin-style purple→blue gradient accent.
- Type: Space Grotesk (display) · Sora (UI) · JetBrains Mono (code/IDs).
- All 8 screens share `app/wf.css` (tokens + components) and `app/app.css` (shell).

## Spec conformance (done — was a content gap, now aligned)
The mockups were brought in line with CONSTITUTION.md + the repo CLAUDE.md:
- **Language Settings** (`language.html`, route `/language`) replaces the old "Cascade
  rules" page. Metadata language resolves by **physical track-index order** (track 0
  first); first TMDB hit wins; else a single global `fallback_language`. Track default
  flags/order are **never** changed automatically. Live in-WASM resolver preview.
- **Track Order** (`track-order.html`) is framed as a **manual** operator action
  (set default + order by hand → before/after diff → mkvpropedit/ffmpeg). No "cascade".
- **Triage** = untagged tracks only (no automatic "default ≠ cascade" mismatch).
- **Media detail** shows a read-only *resolved metadata language* trace — no per-title
  cascade override.
- **Settings → Library mapping** uses `[[libraries]]` discovered from the Jellyfin API
  (fields: jellyfin_id, name, collection_type, jellyfin_path, local_path, skip,
  fallback_language). No static `[paths]`.
- **Login** (`login.html`, `/login`) added — Jellyfin admin creds only.

NOTE on config shape: CONSTITUTION.md still shows an older `[paths]` example, but the
repo CLAUDE.md explicitly supersedes it ("There is no static `[paths]` section; library
paths come from the mapping"). CLAUDE.md wins here.
