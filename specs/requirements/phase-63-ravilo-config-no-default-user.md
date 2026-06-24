# Phase 63 — Ravilo config editor: no user selected by default (FR-RC1)

**Status:** ✅ Done

## Goal

The Ravilo TV configuration editor (admin-side, `/config` or similar route in the
Jellystructure admin app) currently **auto-selects the first user** on load and immediately
shows all configuration sections for that user. This is confusing — you land on someone
else's config without meaning to.

Change the editor to **start with no user selected** and show all configuration sections
only **after the user explicitly picks a user** from the dropdown.

## Current state

`RaviloConfig.kt` (line ~64):

```kotlin
if (currentUserId.isEmpty()) currentUserId = users.first().id  // ← always auto-selects
facets = loadFacets()
currentConfig = RaviloApi.getConfig(currentUserId)             // loads first user's config
renderFull(container, scope)                                   // renders everything
```

`renderFull()` calls `renderSections()` unconditionally, which calls:
`renderPair`, `renderHeroes`, `renderChannels`, `renderRows`, `renderDiscover`,
`renderBehaviour`, `renderPreview` — all visible immediately on page load.

The `<select>` dropdown (line ~137):
```kotlin
<select id="rav-user-pick" ...>$userOptions</select>
```
where `$userOptions` uses `if (u.id == currentUserId) " selected"` — the first user is
always pre-selected since `currentUserId` is set to `users.first().id` before rendering.

The change handler already exists and works: when the user picks a different person it
loads that person's config and re-renders. That part needs no changes.

## Target behaviour

### On page load

1. `currentUserId` stays empty (do NOT assign `users.first().id`).
2. Render the page shell (pagebar, user select, preview) but show an **empty state**
   instead of all the config sections.
3. The select dropdown starts on a **disabled placeholder option**:
   `<option value="" disabled selected>Select a user…</option>`
   followed by the real user options (all un-selected).

### Empty state (no user selected)

Replace the sections area with a centred placeholder card:

```
[ icon: person / silhouette ]
Select a Jellyfin user
Choose a user from the dropdown above to configure their Ravilo TV layout.
```

Style: a `.card` with `text-align: center`, muted icon, `Space Grotesk` heading, `Sora`
body text. No content below the select until a user is chosen. The preview iframe can be
blank/hidden in this state.

### After user selection

Selecting a user from the dropdown:
1. Calls existing change handler which loads `RaviloApi.getConfig(userId)`.
2. Re-renders all sections (existing `renderSections()` path) — unchanged.
3. The select now shows the chosen user's name.

### State after first selection is persistent within the session

Once a user has been picked (e.g. you switch between Hero and Channels tabs), the
selected user stays. Only a full page reload returns to the empty state. This already
works because `currentUserId` is a Kotlin field on the editor object.

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt` | Remove auto-select; add empty state render path; update select options to include placeholder |
| `design/app/ravilo-config.html` | Update mockup: show "Select a user" state on load; update user picker to show placeholder option |

## Kotlin changes (`RaviloConfig.kt`)

1. **Remove line ~64**: delete `if (currentUserId.isEmpty()) currentUserId = users.first().id`.
2. **Split `renderFull()`**: check `currentUserId.isEmpty()` → call `renderEmptyState(container)`,
   else call the existing `renderSections(container, scope)` path.
3. **`renderEmptyState(container)`** — new function that renders a centred placeholder card
   with heading + body text (see above). Replace any existing section HTML in the target div.
4. **Select options** — prepend `<option value="" disabled selected>Select a user…</option>`
   when `currentUserId.isEmpty()`; when a user IS selected, the placeholder is no longer
   `selected` (browser behaviour handles this automatically once a real user is chosen).
5. **Change handler** — already correct; just ensure that after `currentUserId` is assigned
   and `renderSections()` is called, the placeholder option is removed or made non-selected.
   The simplest approach: after re-rendering, the select is rebuilt by `renderFull()` with
   the new `currentUserId` set, so the placeholder is naturally de-selected.

No backend changes. The backend API requires a `userId` — the empty state simply means
the frontend never calls it until a user is chosen.

## Non-goals

- No "last used user" persistence (localStorage etc.) — start clean each session; avoids
  editing the wrong user's config accidentally.
- No per-user URL param (`?userId=`) — out of scope here; could be a future addition.
- No changes to how config sections render once a user IS selected.
