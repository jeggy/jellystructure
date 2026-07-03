# Phase 124 — Metadata page fixes: tag "View in library" deep-link + Trackers CSS (FR-MP1)

Two small, independent Metadata-page fixes bundled (same page, both low-risk).

## Goal
1. The Tags → tag-modal **"View in library →"** link opens the Library filtered to that tag — not the
   Dashboard — with a clean single-`#` URL.
2. The **Trackers** tab renders styled (its CSS is currently missing from the shipped stylesheet, so it
   looks unstyled/ugly).

## Current state (verified in code)

### Issue 1 — `##` double-hash landing on Dashboard
- The tag-modal link (`src/wasmJsMain/kotlin/dev/jellystructure/ui/Metadata.kt:265`) builds
  `href="#/library?tags=$enc"`; its click handler (`:309-314`) calls `App.navigate(href)` with the
  leading `#` **intact**.
- `App.navigate(route)` → `Router.navigate` (`Main.kt:52-53`) does `val hash = "#$path"`
  (`Router.kt:24`), so `"#" + "#/library?tags=X"` = **`##/library?tags=X`**, then
  `window.location.hash = hash` (`:28`).
- `Router.currentPath()` does `hash.removePrefix("#")` — strips only **one** `#` → `#/library`, then
  `substringBefore('?')` → path `#/library` (`:8-9`). In `handleRoute`, `path.startsWith("/library")`
  fails (`#/library` starts with `#`) → `else -> renderDashboard` (`Main.kt:63,78`).
- Every other cross-page nav passes a **bare** path to `App.navigate` — e.g.
  `App.navigate("/library?filter=$key")` (`Dashboard.kt:230`), `Library.kt:671`, `RaviloConfig.kt:1313`.
  The sibling count-badge links (`Metadata.kt:216,226`) escape the bug because they are plain
  `<a href="#/library?...">` anchors with **no** JS handler (native fragment nav, no `#`-prepend).

### Issue 2 — Trackers tab unstyled (missing CSS)
- The entire tracker CSS block lives **only** in `design/app/metadata.html`'s `<style>` (lines 20-48)
  and was **never ported** into the shipped `design/app/wf.css` (confirmed absent from both `wf.css` and
  `app.css`). The markup (`Metadata.kt` `renderTrackersTab`/`trackerCardHtml`/`showTrackerModal`,
  `:365-434,518+`) references these classes, so they render raw:
  `.trk-card`, `.trk-top`/`.nm`, `.pvt-badge`(+`.private`/`.public`), `.host-list`, `.host`/`.hx`,
  `.trk-meta`, `.trk-link`(+`b`), `.add-host`(+`input`), `.unmapped-row`/`.host-name`, and the shared
  modal classes `.modal-back`(+`.open`), `.modal`(+`h3`/`.x`).
- This is the recurring "each design sync strips wf.css classes" issue noted in project memory.

## Requirements

### A. Tag deep-link (logic — one line)
1. Strip the leading `#` before navigating: `App.navigate(href.removePrefix("#"))` (or build the bare
   path directly — `App.navigate("/library?tags=$enc")`). The Library then opens filtered to the tag
   with a single-`#` `#/library?tags=…` URL, URL-addressable cold.

### B. Trackers CSS (CSS-only — port)
1. Port `design/app/metadata.html` lines 20-48 (the tracker + shared-modal rules) **verbatim** into the
   shipped `design/app/wf.css` so the classes resolve. No markup/logic change. Verify the tags modal
   (which uses inline styles via `showTagModal`) is unaffected.

## Scope
- `Metadata.kt` — the one-line nav fix (Issue 1).
- `design/app/wf.css` — add the tracker/modal rules from `metadata.html` (Issue 2). The wasmJs app ships
  `wf.css` verbatim, so porting there is the fix.

## Non-goals
- No redesign of the trackers tab; no change to tag semantics.
- No router refactor. (A broader "make `App.navigate` tolerate a leading `#`" hardening is optional —
  the one-line strip is the fix.)

## Acceptance
- Clicking "View in library →" on a JS tag opens the Library filtered to that tag with a single-`#`
  `#/library?tags=…` URL (not the Dashboard); deep-linking that URL cold works.
- The Trackers tab renders styled (cards, private/public badges, host chips, mapping rows, modal),
  matching the design mockup.
