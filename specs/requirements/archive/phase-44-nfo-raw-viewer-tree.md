# Phase 44 — NFO Raw viewer with multi-file tree sidebar (FR-NR1)

**Status:** ✓ Done (2026-06-21) · _the "NFO raw" tab on Media/Series detail becomes a read-only viewer
of the exact on-disk NFO XML, with a left tree sidebar to pick among the multiple NFO files an item has._

> **As built:** shared DTOs `NfoFileNode`/`NfoFileTree` (commonMain). Backend adds
> `GET /api/media/{id}/nfo/files` (tree with server-built `readUrl`s, episode filename
> `encodeURLPathPart`-encoded) and `GET /api/media/{id}/episodes/{epFilename}/nfo` (episode resolved by
> filename ⇒ 404 on unknown/`..`); `NfoWriter` gains `nfoPath`/`episodeNfoPath`. Frontend `#tab-nfo`
> is now a two-pane tree + read-only `<pre>` (injected via `textContent`), lazy-loaded on tab activate
> (click + `#tab=nfo` deep link), auto-selecting the first existing file; series episodes group into
> collapsible seasons. The post-write `#nfo-raw` push is removed (tab re-fetches on activate). No
> writes from this tab. CSS in `app.css`; mockups updated.

## Problem
The **NFO raw** tab on Media Detail shows **nothing**:
1. It only ever fills `#nfo-raw` **after** a `POST /api/media/{id}/nfo` write — `MediaDetail.kt`
   sets `nfo-raw.textContent` inside `handleWriteNfo`. On a fresh page load the tab is an **empty
   `<pre>`** with placeholder copy ("Click 'Save → NFO' to write and view here"), even when a
   `movie.nfo` / `tvshow.nfo` already exists on disk.
2. There is no way to view the **per-episode** NFO files. A series has **one `tvshow.nfo` plus one
   `episodedetails.nfo` per episode** (35-season shows ⇒ hundreds of files). `NfoWriter.readRawEpisode()`
   exists but **no route serves it** and the tab has no UI to choose a file.
3. The on-disk XML is the **ground truth** that Jellyfin actually reads. Operators currently can't
   inspect it from the app — they'd have to shell into the media volume.

## Goal
The **NFO raw** tab becomes a **read-only viewer** that:
- Loads and shows the **exact bytes on disk** for the selected NFO file (no re-rendering from the
  model — show what's actually written).
- Has a **left tree sidebar** listing every NFO file for the item: for a movie, a single
  `movie.nfo`; for a series, `tvshow.nfo` at the top then episodes grouped by season, each its own
  `episodedetails.nfo`.
- Is **view-only** — all editing stays on the other tabs (metadata, tracks, language, artwork). This
  tab never writes.

## Current state (as-is)
- **Frontend** `MediaDetail.kt`: `#tab-nfo` is a card with one `<pre id="nfo-raw">`. It is filled only
  by `handleWriteNfo` after a successful write (`MediaApi.getNfo(id)`); no auto-load on tab open.
- **Frontend** `MediaApi.kt`: `getNfo(id)` → `GET /api/media/{id}/nfo` (returns `text/xml` or 404).
  No per-episode read; no "list NFO files" call.
- **Backend** `MediaRoutes.kt`: `GET /api/media/{id}/nfo` → `NfoWriter.readRaw(item)` (movie.nfo /
  tvshow.nfo only). `POST` writes movie/tvshow **and** all `episodedetails.nfo`.
- **Backend** `NfoWriter.kt`: `readRaw(item)` (movie/tvshow), `readRawEpisode(episode)`,
  `exists(item)`, `episodeNfoExists(episode)`. Episode NFO path = `<episode dir>/<basename>.nfo`
  next to the video file (`filename` → basename). Tree info (which exist) is derivable from these.
- **Model** `Media.kt`: `Episode(filename, path, seasonNumber, episodeNumber, title, …)` — enough to
  label and group the tree.

## Requirements

### A. Backend — list the NFO files for an item
1. `GET /api/media/{id}/nfo/files` returns the **tree of NFO files** the item could have. Each node
   carries its existence flag and the **read URL the client should fetch** — no separate "key" the
   client has to map back to a route:
   - **Movie:** one node — `{ label: "movie.nfo", readUrl: "/api/media/{id}/nfo", exists, path }`.
   - **Series:** a `tvshow.nfo` node `{ label: "tvshow.nfo", readUrl: "/api/media/{id}/nfo", … }` plus
     one node per **episode**
     `{ label: "S01E03 — <title>", season, episode, readUrl: "/api/media/{id}/episodes/{filename}/nfo", exists, path }`.
     Episodes are returned in season/episode order so the frontend can group by season.
   - `exists` comes from `NfoWriter.exists` / `episodeNfoExists`; `path` is the on-disk path (display
     only). `readUrl` is **server-built** (the client never constructs paths). Items with **no** NFO
     yet still return the full tree, all `exists: false`.
   - This endpoint reuses the **already-loaded** item + episode list — no re-scan; it's one `stat`
     per potential file, which bounds the cost even for a 35-season show.
2. **Read routes (path-safe).** Each `readUrl` resolves on the server only:
   - `GET /api/media/{id}/nfo` keeps serving `movie.nfo` / `tvshow.nfo` by item kind (**unchanged**).
   - Add `GET /api/media/{id}/episodes/{epFilename}/nfo` → `NfoWriter.readRawEpisode(episode)`,
     resolving the episode the **same way the existing episode routes do** — `item.episodes.firstOrNull
     { it.filename == epFilename }` (see the existing `route("/{epFilename}")` block in
     `MediaRoutes.kt`; nest the new `get("/nfo")` there alongside the track/still routes). An unknown
     or `..` `epFilename` matches no episode ⇒ **404**; no filesystem path from the client ever reaches
     `NfoWriter`.
   - A not-yet-written file returns **404** (the frontend renders an empty-state, not an error).
3. New install / fresh item with nothing written: `/nfo/files` returns the tree with all
   `exists: false`; reads 404. No 500s.

### B. Frontend — the viewer + tree sidebar
1. **Lazy-load on tab activate.** Hook into the existing tab mechanism in `MediaDetail.kt`: the
   tab-click handler already special-cases `history`/`artwork`/`tracks` (line ~632), and the initial
   render special-cases the deep-linked `activeTab` (line ~656). Add the `nfo` branch in **both**
   places, so opening the tab — by click or by `#tab=nfo` deep link (Phase 28) — fetches `/nfo/files`,
   renders the tree, auto-selects the **first existing** file (movie.nfo / tvshow.nfo), and loads its
   XML. If nothing exists yet, show the empty-state (B5).
2. **Left tree sidebar.** A two-pane layout: a scrollable tree on the left, the XML viewer on the
   right.
   - **Movie:** a single leaf (`movie.nfo`).
   - **Series:** `tvshow.nfo` pinned at top; then **collapsible season groups** ("Season 1", …) each
     containing its episodes labelled `S01E03 — Title` (fall back to filename when no episode title).
     Seasons collapsible so a 35-season show stays navigable; the sidebar scrolls independently.
   - Each node shows whether it **exists** vs **not written yet** (e.g. a muted "—" / "not written"
     badge on missing files; existing files look active). The selected node is highlighted.
3. **Selecting a node** fetches its `readUrl` (from A1) and renders the response. Existing files render
   their exact on-disk bytes in a read-only, horizontally-scrollable `<pre>`, injected via
   **`textContent`** (never `innerHTML`) so the XML displays literally and is never parsed as markup —
   correctness *and* hygiene, since NFO carries arbitrary title/overview text. Monospace; XML syntax
   highlighting is optional/nice-to-have. Show the on-disk **path** as a caption above the viewer.
4. **View-only.** No edit controls in this tab; include a one-line note that NFO is generated from the
   other tabs and written on **Save** (link the operator's attention to the metadata/tracks tabs).
   This tab issues **no writes**.
5. **Empty states.**
   - Item with no NFO at all: the tree still lists every potential file (all "not written"); the
     viewer shows "No NFO on disk yet — written when you Save on the metadata tab."
   - Selecting a "not written" node shows the same per-file empty-state, **not** an error.
6. **Always fetch fresh on activate.** The tab re-fetches `/nfo/files` (and the selected file) **every
   time it is activated**, so writes made on other tabs (`handleWriteNfo`, the series episode editor,
   batch push) are reflected with **no cross-component eventing** — newly-written files simply appear
   as existing the next time the tab is opened. (Replaces today's "set `#nfo-raw` after write" path.)

## Invariants
- **Read-only & exact** — the viewer shows the bytes on disk verbatim, injected via `textContent`; it
  never re-serialises from the model and never writes. All NFO editing remains on the other detail
  tabs (per Phase 27, the detail page is the single editing surface).
- **Path-safe reads** — files are addressed by server-built `readUrl`s / server-resolved episodes
  only; no client-supplied filesystem path reaches `NfoWriter`.
- **Frontend renders server-pushed state only** (`fe-reflects-be-no-derived-state`) — file existence
  and contents come from the backend, not inferred client-side.
- Works for items with **no** NFO yet (full tree, empty viewer) and for **large** series (hundreds of
  episode files) without breaking the layout.

## Interacts with (not re-specified here)
- **Phase 22** field-lock banner / **Phase 33** drift banner render elsewhere on detail; independent
  of this tab. Note: NFOs are written **unlocked** (no `<lockdata>`, Phase 22) — the viewer just shows
  whatever is on disk.
- The NFO **write** flow (Phase 3 + the episode writes) is unchanged; this phase only adds reading.

## Out of scope
- Editing/saving NFO from this tab; diffing on-disk NFO vs model (that's the dirty-indicator/drift
  work, Phases 9/33).
- Season-level NFOs or collection NFOs beyond `movie.nfo` / `tvshow.nfo` / `episodedetails.nfo`.

## Design reference
`design/app/media.html` and `design/app/series.html` — add the NFO raw tab's **tree sidebar + read-only
XML viewer** layout (movie = single file; series = tvshow + per-season episode tree) to the mockups.
