# Phase 61 — ⌘K command palette: media search results (FR-KM1)

**Status:** ✅ Done

## Goal

The `⌘K` / `Ctrl+K` command palette currently searches only a hardcoded list of 9
navigation/action commands. Typing a title finds nothing. The backend's
`GET /api/media?search=<query>` endpoint is fully implemented (Phase 29 multi-language
search — title, original title, all `titlesByLang` variants, case-insensitive substring),
and `MediaApi.list(search = ...)` already has the parameter wired in the frontend API
client. The palette just never calls it.

Add **media search results** to the palette: when the query is ≥ 2 chars, fire an async
search against `/api/media` and display matching titles below the command section.

## Current state

`Shell.kt` `renderPaletteList(query)` (line ~270):

```kotlin
val cmds = buildPaletteCommands()   // hardcoded 9 items
val filtered = cmds.filter { it.label / it.detail contains query }
// renders as .palette-cmd divs with inline styles
```

No API call is made. The result list is always a subset of the 9 static commands.

`app.css` already defines `.cmdp-item`, `.cmdp-label`, `.cmdp-sub`, `.cmdp-kind`,
`.cmdp-ico`, `.cmdp-empty` (lines 221–228) — but they are **not used** by the current
inline-style rendering.

`MediaApi.list()` (`MediaApi.kt` line 156–180) already accepts `search: String? = null`.

`MediaItemDto` fields available for display:
- `mediaId: String` — used to build the `/media/{id}` navigation URL
- `title: String`, `year: Int?` — display name + year
- `kind: String` — `"movie"` or `"tv_show"` — for the kind badge
- `posterPath: String?` — optional; skip for palette (thumbnails add latency)

## Target behaviour

### Query lifecycle

| Query length | Behaviour |
|---|---|
| 0 (blank) | Show full command list only (no media search) |
| 1 char | Show filtered commands only (too short to search media) |
| ≥ 2 chars | Show filtered commands **+** async media search results |

Commands are filtered client-side (instant). Media results are fetched from the API
(`pageSize = 6`, no pagination in the palette).

While a media fetch is in flight, show a brief "Searching…" placeholder in the media
section (not a spinner — just text, keeps the UI light). Replace it when results arrive.

### Result layout

The palette list is split into two sections when media results are present:

```
[ COMMANDS section ]
  ↳ filtered commands (existing)
[ MEDIA section header: "Library" ]
  ↳ up to 6 MediaItemDto results
  ↳ or "No results" if none found
```

Each media result row uses the existing CSS classes (adopting them properly):

```html
<div class="cmdp-item" data-id="<mediaId>">
  <span class="cmdp-ico">🎬 or 📺</span>   <!-- emoji or SVG, no poster fetch -->
  <span>
    <span class="cmdp-label">Title (Year)</span>
    <span class="cmdp-sub">2024 · tv show</span>  <!-- kind, year if available -->
  </span>
  <span class="cmdp-kind">MOVIE / TV</span>
</div>
```

Clicking a media result: `hidePalette()` → navigate to `/media/{mediaId}`.

The section header ("Library") is a simple non-interactive label styled like the command
section (small caps, muted, padded).

### Keyboard navigation

Current behaviour: `Enter` selects the first `.palette-cmd` item. Extend to cover both
sections:

- `↑` / `↓` arrow keys cycle through **all** result rows (commands + media) in DOM order.
- `Enter` activates the currently highlighted row.
- The currently highlighted row gets class `.sel` (already in app.css:
  `.cmdp-item.sel, .cmdp-item:hover { background: var(--hi-soft); }`).
- The `.palette-cmd` → `.cmdp-item` class rename is part of this work so the
  existing hover/sel styles apply uniformly.

### Debounce

The media fetch should be debounced (≥ 200 ms delay after the last keystroke) to avoid
firing on every character. If the query changes while a request is in flight, discard the
stale response.

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/Shell.kt` | See Kotlin changes below |
| `design/app/index.html` | Update cmd-palette mockup section to show mixed results |

## Kotlin changes (`Shell.kt`)

1. **`renderPaletteList(query)`** — make it async (or queue a coroutine job):
   - If `query.length < 2`: render commands-only (existing path), no media fetch.
   - If `query.length ≥ 2`: render filtered commands immediately, then launch a
     `MainScope().launch { }` to fetch `MediaApi.list(search = query, pageSize = 6)`.
     Cancel/replace any pending job on new input (debounce via a `Job` field on the
     companion object or via a delay).

2. **`renderCommandRow(cmd)`** — refactor current `.palette-cmd` inline-style render to
   use `.cmdp-item` + `.cmdp-label` + `.cmdp-sub` + `.cmdp-kind` classes instead. Remove
   the inline `style=` block; CSS handles layout. Keeps the same content, just adopts the
   existing CSS properly.

3. **`renderMediaRow(item: MediaItemDto)`** — new function. Builds the `.cmdp-item`
   HTML for a media result. Click handler: `hidePalette(); navigateTo("/media/${item.mediaId}")`.

4. **Keyboard handler** — extend the `keydown` handler that currently wires `↑`/`↓`/`Enter`
   to target all `.cmdp-item` elements (not `.palette-cmd`) and include both command and
   media rows in the cycle.

5. **Section header helper** — small `renderSectionHeader(label)` that emits a muted
   small-caps divider label.

## Non-goals

- No poster thumbnails — a 6-item list with 17×17 icons is fast; poster fetches add
  latency and complexity for a one-liner palette.
- No infinite scroll or "show more" — 6 results is sufficient for quick navigation;
  users who need full search go to the Library page.
- No result grouping by type inside the media section — results are ranked by the
  existing backend (substring match order); no re-ranking here.
- No changes to the backend API — it already supports `?search=`.
