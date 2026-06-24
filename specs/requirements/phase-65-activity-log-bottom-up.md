# Phase 65 — Activity log: newest at bottom with auto-scroll (FR-AL1)

**Status:** Planned

## Goal

The activity log currently displays newest entries at the **top** — the opposite of every terminal/log viewer convention. The user has to scroll to the top to see new activity and there is no auto-scroll. Reverse the ordering so the log reads top-to-bottom chronologically (oldest first, newest last), scroll to the bottom on load, and auto-scroll as new entries arrive.

## Root cause

`ActivityLog.kt` (backend) line 72 calls `.reversed()` on the DB results before returning them:
```kotlin
}.reversed()
```
The DB returns oldest-first; `.reversed()` flips it to newest-first. The frontend `appendLogEntry()` appends with `console.appendChild(div)`, so it builds DOM in the received order (newest at top). The final `scrollTop = scrollHeight` after history load then scrolls to the **oldest** entry (DOM bottom).

## Target behaviour

1. **Oldest first**: Remove `.reversed()` from `ActivityLog.kt`. Server returns oldest-first.
2. **History load**: Frontend appends in received order → oldest at DOM top, newest at DOM bottom.
3. **Scroll-to-bottom on load**: The existing `it.scrollTop = it.scrollHeight` in `loadLogHistory()` (after appending all entries) already scrolls to the DOM bottom — with oldest-first ordering this correctly lands on the newest entry. No change needed here.
4. **Live entries auto-scroll**: `appendLogEntry()` calls `it.scrollTop = it.scrollHeight.toDouble()` after each new entry. This already works; keep it.
5. **Pinned-to-bottom**: Only auto-scroll if the user is already near the bottom (within ~80px). If the user has scrolled up to read old entries, do not yank them back down on each new WS event.

## Files

| File | Change |
|------|--------|
| `src/linuxX64Main/kotlin/dev/jellystructure/media/ActivityLog.kt` | Remove `.reversed()` at line 72 |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/Activity.kt` | In `appendLogEntry()`, check `scrollHeight - scrollTop - clientHeight < 80` before auto-scrolling on live events |

## Non-goals

- No pagination direction change — existing page/offset model is fine.
- No change to the filter controls (category, level).
- No visual redesign of the console area.
