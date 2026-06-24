# Phase 62 — Activity log: use stored timestamps (FR-AT1)

**Status:** Planned

## Goal

Every entry in the activity log always shows the **current browser time** instead of when
the event actually happened. Reload the page an hour after a scan and all entries show
"now." The backend correctly stores epoch-seconds timestamps; the frontend ignores them.

Fix `appendLogEntry()` to accept and display the stored timestamp for historical entries,
while keeping the current-time behaviour for real-time WebSocket events.

## Root cause

`Activity.kt` line ~462:

```kotlin
private fun appendLogEntry(
    container: Element, level: String, category: String,
    text: String, fromHistory: Boolean = false,
) {
    ...
    val ts = currentTimeString()   // ← always browser's current clock
```

`currentTimeString()` (line 22):
```kotlin
private fun currentTimeString(): String =
    js("new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})")
```

When the page loads historical entries from `GET /api/activity/log`, the call is:
```kotlin
page.entries.forEach { entry ->
    appendLogEntry(container, entry.level, entry.category, entry.message, fromHistory = true)
}
```

`entry.ts` (Long, epoch seconds — correctly stored and returned by the backend) is passed
to no one. `fromHistory = true` is present but unused in `appendLogEntry()`.

## Data model

`ActivityEntryDto` (Activity.kt line ~39):
```kotlin
private data class ActivityEntryDto(
    val id: Int,
    val ts: Long,        // epoch seconds — correct and non-null
    val level: String,
    val category: String,
    val message: String,
    val mediaId: String? = null,
)
```

The backend (`ActivityLog.kt`) sets `ts = epochSeconds()` (POSIX `time(null)`) at the
moment `log(...)` is called — always correct.

## Target behaviour

### Timestamp format

Real-time WS events (events arriving while the page is open): keep showing the **current
time** in `HH:MM:SS` format — these ARE happening now, and the clock-time gives context
within an active scan.

Historical entries (loaded from the API on page open / manual refresh): show the **stored
date + time** in a concise fixed format: `YYYY-MM-DD HH:MM:SS` (or locale equivalent).
For entries from today, a compact `HH:MM:SS` is sufficient; for entries from previous
days, include the date. Use JavaScript's `Date` API:

```javascript
// ts is epoch seconds
function formatStoredTs(ts) {
    const d = new Date(ts * 1000);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) {
        return d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
    } else {
        return d.toLocaleDateString([], {year:'numeric',month:'2-digit',day:'2-digit'})
             + ' ' + d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
    }
}
```

### Fix

1. **Add `ts: Long? = null` param** to `appendLogEntry()`.
2. **Format logic**: `val tsStr = if (ts != null) formatStoredTs(ts) else currentTimeString()`.
3. **Pass `entry.ts`** when loading history:
   ```kotlin
   appendLogEntry(container, entry.level, entry.category, entry.message, ts = entry.ts)
   ```
4. **All real-time WS call sites** (`appendLogEntry(container, level, category, msg)`) pass
   no `ts` → still receive `currentTimeString()`. No changes to those call sites.

`fromHistory: Boolean` parameter becomes unused and can be removed at the same time
(its original intent was to gate timestamp handling, but it was never wired up — `ts`
param replaces the intent cleanly).

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/Activity.kt` | Add `ts: Long?` to `appendLogEntry()`; add `formatStoredTs()` JS shim; pass `entry.ts` from history load; remove unused `fromHistory` param |

No backend changes required. No CSS changes required.

## Non-goals

- No relative-time display ("5 minutes ago") — the log is a technical stream, absolute
  times are more useful than relative ones for debugging scan events.
- No timezone conversion — display in the user's local browser timezone (already
  implicit in `toLocaleTimeString()`/`toLocaleDateString()`).
- No retroactive fix for entries already in the log file — once the fix ships, newly
  loaded entries display correctly; old entries still loaded from the JSON file will now
  show correctly too since `ts` was always stored right.
