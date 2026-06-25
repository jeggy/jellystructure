# Phase R61 — Home rows: reduce system defaults to Continue + Newly Added (FR-RV-RD1)


## Problem

After R54 the default home screen ships with **three system rows**: Continue Watching, Movies —
Newly Added, and Series — Newly Added. For most operators this is one row too many — the movie /
series split adds visual noise without much value, and users who have also experimented with custom
rows (e.g. a Danish-titled "Se videre" row, or a generic "Newly Added" custom row) quickly
accumulate five or more rows that are hard to clean up because the three system rows are undeletable.

## Goal

Reduce the **system row set to two**:
1. **Continue Watching** (CONTINUE) — unchanged
2. **Newly Added** (NEWLY_ADDED, `mediaKind = null`) — all media, not split by type

Operators who want split rows can still add custom workbench rows; the `mergeNewlyAdded` toggle
stays available for power users who prefer to configure separate rows and collapse them at render
time.

## Changes

### Backend — `src/linuxX64Main/kotlin/dev/jellystructure/tv/RaviloConfigService.kt`

Change `DEFAULT_ROWS` from three entries to two:

```kotlin
// BEFORE
private val DEFAULT_ROWS = listOf(
    RowConfig(id = "continue",     kind = RowKind.CONTINUE,    title = "Continue Watching",    enabled = true, order = 0),
    RowConfig(id = "newly-movies", kind = RowKind.NEWLY_ADDED, title = "Movies — Newly Added", enabled = true, order = 1, mediaKind = "MOVIE"),
    RowConfig(id = "newly-series", kind = RowKind.NEWLY_ADDED, title = "Series — Newly Added",  enabled = true, order = 2, mediaKind = "SERIES"),
)

// AFTER
private val DEFAULT_ROWS = listOf(
    RowConfig(id = "continue",  kind = RowKind.CONTINUE,    title = "Continue Watching", enabled = true, order = 0),
    RowConfig(id = "newly-all", kind = RowKind.NEWLY_ADDED, title = "Newly Added",       enabled = true, order = 1),
)
```

### Admin frontend — `src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt`

Change `SYSTEM_ROW_DEFAULTS` to match:

```kotlin
// BEFORE
private val SYSTEM_ROW_DEFAULTS = listOf(
    RowConfig(id = "continue",     kind = RowKind.CONTINUE,    title = "Continue Watching",    enabled = true, order = 0),
    RowConfig(id = "newly-movies", kind = RowKind.NEWLY_ADDED, title = "Movies — Newly Added", enabled = true, order = 1, mediaKind = "MOVIE"),
    RowConfig(id = "newly-series", kind = RowKind.NEWLY_ADDED, title = "Series — Newly Added",  enabled = true, order = 2, mediaKind = "SERIES"),
)

// AFTER
private val SYSTEM_ROW_DEFAULTS = listOf(
    RowConfig(id = "continue",  kind = RowKind.CONTINUE,    title = "Continue Watching", enabled = true, order = 0),
    RowConfig(id = "newly-all", kind = RowKind.NEWLY_ADDED, title = "Newly Added",       enabled = true, order = 1),
)
```

The existing `normalizedRows()` function auto-prepends any missing system row on each editor render,
so opening the config page on an existing install will immediately surface the new `newly-all` row
without any migration script.

## Migration behaviour for existing configs

| Row in stored config | After this change |
|---------------------|-------------------|
| `continue` | still system — toggle only |
| `newly-all` (new) | auto-inserted by `normalizedRows()` as a system row |
| `newly-movies` | **no longer system** → gains ✕ delete button |
| `newly-series` | **no longer system** → gains ✕ delete button |
| any custom rows (e.g. "Se videre") | unchanged — already deletable |

The operator opens the config editor, deletes the unwanted rows via the new ✕ buttons, and saves.
No data loss, no forced migration.

## Scope

- `RaviloConfigService.kt` — `DEFAULT_ROWS` constant only.
- `RaviloConfig.kt` — `SYSTEM_ROW_DEFAULTS` constant only.
- No API changes, no TV app changes, no model changes.
- `HomeFeedService` already handles `RowKind.NEWLY_ADDED` with `mediaKind = null` (returns all media).

## Non-goals

- Removing `mergeNewlyAdded` from `RaviloConfig` (kept; useful for operators who still want split rows).
- Auto-deleting the old `newly-movies`/`newly-series` rows from existing stored configs.
