# Phase R143 — Per-channel system rows: scope Continue Watching & Newly Added to the channel

> Extends **R59** (per-channel custom content rows). R59 lets a
> channel define its own ordered **filter** rows but left the **system rows** (Continue Watching, Newly
> Added) as an un-configurable footnote ("still appear unless removed"). This phase makes them
> first-class per-channel settings — a **show** toggle, an **All titles / Only this channel** scope, and
> a **merge** option for Newly Added. Builds on **R54** /
> **R61** (the system-row model) and reuses the channel's own
> conditions from **R32** for scoping.

## Problem
R59 gave a channel its own **Custom** content rows, but the two **system rows** — Continue Watching and
Newly Added — were not configurable there. Two concrete gaps:

1. **No per-channel Newly Added setting.** Whether a channel shows Newly Added, and whether its movies and
   series are split or merged (the choice Home already has), couldn't be set on the channel.
2. **Continue Watching can't be scoped to the channel.** On an "HBO" channel the viewer wants their
   **HBO** in-progress titles — "continue the HBO show I was watching" — not their whole global Continue
   row. There was no way to filter Continue Watching to the channel.

The filter rows in a Custom channel are already channel-scoped; the time-based system rows should get the
same option.

## Change
The channel editor's **Content rows → Custom** section gains a **System rows** block, pinned above the
filter rows (system rows sit at the top, as on Home):

- **Continue Watching** — a **show** toggle + a scope control **All titles · This channel**.
  - **All titles** — the viewer's whole Continue + Next Up row (today's behaviour).
  - **This channel** — Continue + Next Up **filtered to titles that match the channel's own conditions**,
    so only in-progress items belonging to the channel appear.
- **Newly Added** — a **show** toggle + the same **All titles · This channel** scope + a **Merge movies &
  series** sub-toggle (the per-channel equivalent of Home's merge option). Scope "This channel" restricts
  newest items to the channel's matches; "All titles" shows the library-wide Newly Added.
- The block only applies in **Custom** mode. In **Same as Home** mode the channel keeps inheriting Home's
  system rows (already scoped to the channel per R59) with nothing to configure.

## Data model
- `ChannelConfig.rows` (R59: `{ mode, items }`) gains a **`system`** block:
  ```
  system: {
    continue: { show: bool, scope: 'all' | 'channel' },
    newly:    { show: bool, scope: 'all' | 'channel', merge: bool }
  }
  ```
  Default `{ continue:{ show:true, scope:'all' }, newly:{ show:true, scope:'all', merge:false } }` — i.e.
  **today's behaviour** (both shown, library-wide, split). Absent/legacy channels back-fill to the
  default, so nothing changes until an operator opts in.
- **`scope:'channel'`** reuses the **channel's existing condition stack** (R32) — no parallel filter and
  no per-row builder; the system row is the time-based feed **intersected** with the channel's matches.
- `newly.merge` mirrors the Home merge flag (R71) at channel scope.

## Feed / TV
- The home/channel feed composes the channel layout from `rows`: when `mode:'custom'` it emits the system
  rows per `rows.system` (honouring `show`, `scope`, `merge`) **above** the custom filter rows, then the
  filter `items`. `scope:'channel'` ANDs the channel conditions onto the Continue / Newly-Added query;
  `scope:'all'` leaves them library-wide. Continue Watching stays **landscape with resume progress**
  regardless of scope.

## Scope / invariants
- **Default preserves R59 behaviour** — both system rows shown, library-wide, Newly Added split.
- **No parallel taxonomy** — channel scoping reuses the channel's own conditions (R32 facets: Studio ·
  Network · Genre · Tag), never a separate filter.
- System rows remain **time-based** and are **not** counted by the R59 coverage-gap check (which only
  measures the workbench filter rows).
- Per-channel system-row settings live only on **Custom** channels; **Same as Home** is unchanged.

## Mockup
`design/app/ravilo-config.html` + `design/app/ravilo-builders.js` (`openFilter` channel editor →
**Content rows → Custom** → **System rows** block: `.cf-sysrows` / `.cf-sysrow` / `.cf-sysscope`
scope seg + `data-systog` show toggles + `data-sysmerge`; `state.rows.system` model, `ensureRows()`
back-fill), `design/app/ravilo-builders.css` (`.cf-sysrows`, `.cf-sysrow`, `.cf-sysscope`,
`.cf-sysmerge`). Ravilo TV renders the channel's system rows per `rows.system` (scoped Continue / Newly
Added) when set. Related: R59 (per-channel custom rows), R54 / R61 (system-row model), R71 (merge
Newly Added), R32 (shared workbench / channel conditions reused for scope).
