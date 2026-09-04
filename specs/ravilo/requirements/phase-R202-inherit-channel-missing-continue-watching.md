# Phase R202 — Inherit-mode channels never show Continue Watching (bug fix, FR-RV-R2)

> Reported live: "why is there no continue watching on the Apple TV channel?" First answered (wrongly) as
> by-design behavior per a misleading code comment attributing the skip to R59. Corrected by the user:
> R59's own description is "Same as Home / **Custom** switch (**default inherit**)... **System rows still
> appear unless removed**" — inherit mode means a channel gets Home's own rows, Continue Watching included,
> not "channels never get Continue." Traced the actual code history: the skip predates R59 entirely (it's
> from the very first channel implementation, R05, back when there was no inherit/custom distinction at
> all — *every* channel view skipped Continue). R143 later preserved that skip unmodified while adding the
> real custom-mode system-row config, and mischaracterized it in a comment as "R59 behaviour" — it never
> was; it's an R05 leftover that R59 should have retired for the inherit case and didn't.

**Status:** Implemented. Verified via `compileKotlinLinuxX64`.

## Bug report
"why is there no continue watching on the Apple tv channel?" — followed by a correction once first
answered as intentional: "It's in 'inherit' layout.mode, which means it should get the same settings as
the home screen has, and this page does have continue watching." (2026-08-18.)

## Investigation
`ChannelConfig` for the Apple channel confirmed live (`config/jellystructure.db`, `ravilo_config` table):
`rows.mode: None` — inherit mode, not custom. `HomeFeedService.buildRows` (`HomeFeedService.kt:326-335`):
```kotlin
// ── Home / inherit-mode channel — unchanged behaviour (config.rows drives system + filter rows).
val enabledRows = config.rows.filter { it.enabled }.sortedBy { it.order }
...
for (rowCfg in enabledRows) {
    when (rowCfg.kind) {
        RowKind.CONTINUE -> {
            if (channelFilter != null) continue // inherit-mode channels keep R59 behaviour (no Continue)
            ...
```
This is the ONLY code path an inherit-mode channel runs (the `channelRows?.mode == "custom"` branch above it
returns early and never reaches here) — so `channelFilter != null` is true for **every** inherit-mode
channel, unconditionally skipping Continue Watching regardless of R59's own "Same as Home" framing.

`git log -S"RowKind.CONTINUE"` traces this exact skip to **Phase R05** (`c6233e93`, 2026-06-19) — the very
first channel implementation, written before "inherit vs custom" existed as a concept at all:
```kotlin
RowKind.CONTINUE -> {
    if (channelFilter != null) continue // skip resume row inside channel view
    ...
```
Every channel view skipped Continue back then, full stop — there was no other mode to compare against.
**Phase R143** (`a2de8eb8`, per its own commit message: *"Home + inherit-mode channels keep their existing
behaviour"*) added the real custom-mode system-row config (`ChannelRowsConfig.system`, `show`/`scope`/
`merge`) but explicitly left the inherit path's R05-era skip untouched — and its comment retroactively
(and incorrectly) attributed that untouched skip to R59, when R59 itself (`STATUS.md`'s own R59 row:
*"a channel can override the Home content rows with its own ordered set... **System rows still appear
unless removed**"*) never said inherit-mode channels should have no Continue — the opposite, if anything.

## Requirements

### FR-RV-R2-1 — Inherit-mode channels render Continue Watching exactly as Home does
Remove the `channelFilter != null` skip for `RowKind.CONTINUE` in the inherit path
(`HomeFeedService.kt:331-335`). Build the row from `libraryAll` (the unfiltered library-wide list, already
threaded through both `buildRows` call sites — `getHomeFeed` passes the same list for both the `all` and
`libraryAll` params, so this is a no-op change there), not `all` (which is channel-filtered for a channel
call and therefore not "the same as Home"). This makes an inherit-mode channel's Continue Watching row
byte-for-byte the same content Home shows — matching "Same as Home" literally, not a channel-scoped
approximation of it.

## Invariants
- **An inherit-mode channel's system rows (Continue Watching, Newly Added) are Home's own rows**, not a
  channel-filtered variant of them — "inherit" means no override exists yet, not "these rows behave
  differently here." A channel opts out of this only by switching to custom mode and configuring its own
  system-row scope (already supported since R143).

## Out of scope
- `RowKind.NEWLY_ADDED` in the inherit path already uses the channel-filtered `all` list, not `libraryAll`
  — not reported as broken, and channel-scoped "Newly Added" reads as reasonable for a filter-driven row
  the way Continue Watching (a viewer-progress row, not a catalog filter) doesn't. Left unchanged; revisit
  only if reported.
- Re-numbering the misleading "R59 behaviour" comment history — captured here instead so the real
  provenance (R05 → carried by R143 → fixed by R202) isn't lost again.

## Source references
- Bug site: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt` (`buildRows`, inherit-mode
  branch, `RowKind.CONTINUE` case).
- Original skip: commit `c6233e93` (Phase R05, 2026-06-19).
- Preserved-not-fixed: commit `a2de8eb8` (Phase R143, 2026-06-29).
- R59's actual scope: `STATUS.md`'s R59 row (no dated spec file exists for R59; described only there).
