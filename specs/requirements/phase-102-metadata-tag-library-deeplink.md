# Phase 102 — Clickable tag → open Library filtered by that tag (FR-TG4)

## Problem
On **Metadata ▸ Tags** every tag is listed with a count of how many items carry it, but the tag/count is
inert. The operator wants to **click a tag** and land on the **Library** page with that tag already
applied as a filter — the natural "show me these items" gesture.

## Findings (the deep-link already exists — only the Tags tab needs anchors)
The Library page **already** boots from a tag filter in the URL. `parseLibraryUrl`
(`src/wasmJsMain/.../ui/Library.kt:71–96`) reads hash query params and at `:91` does:
```kotlin
libTags = param("tags")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
```
Values are `decodeURIComponent`-decoded (`:77`). So the canonical hand-off URL is
**`#/library?tags=<encodeURIComponent(name)>`** (comma-separated for multiple). `renderLibrary` calls
`parseLibraryUrl()` on entry (`:127`) and `populateMetaFilterPanel` syncs the chips/highlights from that
state, so the filter boots active with no extra work. This exact pattern is already used by:
- the **tag edit modal**'s "View in library →" link (`Metadata.kt:262–263`),
- **genre chips** — plain anchor `<a href="#/library?genres=$encoded">` (`Metadata.kt:192`),
- **tracker cards** — `<a href="#/library?tracker=$encName">` (`Metadata.kt:427`).

So **no Library.kt or backend change is needed** — only `renderTagsTab` (`Metadata.kt:199–231`) must emit
anchors. Two tag groups render there:
- **JS-tag cards** (`:206–217`): the count badge is `:215`. ⚠ The whole `.js-tag-card` already has a
  click handler that opens the **edit modal** (`wireTagsTab` `:237–246` → `showTagModal`), so a
  badge-link click must `stopPropagation` or it will also open the modal.
- **"Other tags"** chips (`:220–227`): plain non-clickable spans (`:224`), no handler — safe to wrap
  directly.

**Two traps to avoid** (documented by the investigation):
1. **Double-hash bug:** do **not** route via `App.navigate("#/library?…")` — `Router.navigate`
   (`Router.kt:24`) prepends another `#`, yielding `##/library?…`. Use a **plain hash anchor** (genre-chip
   style); native `hashchange` handles it. (If a JS call is ever preferred, pass a slash-path without the
   `#`, like `applyWorkbenchToLibrary` → `App.navigate("/library?tags=$enc")`, `Library.kt:1017`.)
2. **Case:** use the tag's exact-case name through `encodeURIComponent` for the `tags=` value (the
   `other-tags` span currently only keeps a lowercased `data-filter-name`; read `tag.name`, not that).

## Goal
Clicking a tag (or its count badge) on Metadata ▸ Tags navigates to Library with that single tag applied,
reusing the existing `#/library?tags=` deep-link — no new Library or backend logic.

## Requirements
1. **"Other tags" chips** (`Metadata.kt:224`): wrap each chip in a plain anchor, mirroring the genre-chip
   pattern (`:192`):
   ```kotlin
   <a href="#/library?tags=${dev.jellystructure.encodeURIComponent(tag.name)}" style="text-decoration:none">…</a>
   ```
   Native hash navigation applies the filter; no JS handler.
2. **JS-tag cards** (`Metadata.kt:215`): make the count badge an anchor to the same URL, with inline
   `onclick="event.stopPropagation()"` so a badge click goes to Library while a click on the rest of the
   card keeps opening the edit modal (`:240`):
   ```kotlin
   <a href="#/library?tags=$enc" onclick="event.stopPropagation()" class="badge info" …>${tag.count}</a>
   ```
   (Inline `on*` handlers are already the house style in this file, e.g. the card's `onmouseover` at
   `:207`.) `$enc = dev.jellystructure.encodeURIComponent(tag.name)`.
3. **Affordance:** give the clickable badge/chip a pointer cursor + hover state so it reads as a link
   (the edit-modal card body keeps its own affordance).

## Scope
- `src/wasmJsMain/.../ui/Metadata.kt` — `renderTagsTab` only (`:215` JS-tag badge, `:224` other-tags
  chip; optional cursor styling). No `Library.kt`, no backend, no router change.

## Non-goals
- No multi-tag selection from this gesture (single tag → single `tags=` value); multi-tag is still
  available in the Library workbench.
- No change to the existing tag **edit modal** or its "View in library" link.
- The Library URL filter contract (`?tags=`, `?genres=`, `?tracker=`) is unchanged — this phase only
  *uses* it.

## Acceptance
- Metadata ▸ Tags: clicking a JS-tag's count badge opens Library filtered to that tag (and **does not**
  open the edit modal); clicking elsewhere on the JS-tag card still opens the edit modal.
- Clicking an "other tag" chip opens Library filtered to that tag.
- The landed Library shows the tag chip active, results filtered, and a refresh reproduces the same
  filtered view (URL-stable). Tag names with spaces/punctuation/non-ASCII round-trip correctly
  (encode/decode).
