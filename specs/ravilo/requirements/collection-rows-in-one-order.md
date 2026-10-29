# A collection's rows in one order, with Continue Watching and Newly Added among them

Task: DEB-2848

> Owner, 2026-10-29 (DEB-799, with a screenshot of the collection editor): *"Can you also create task for adding
> support for reordering the content rows within a collection? This re-ordering should also have support for moving
> the 'Continue Watching' and 'Newly Added' sections up and down, so it can be mixed together with the created
> content rows."*

## Scope

A collection whose rows are set to **Custom** gets one ordered list of its rows in the admin, like Home's row list
already is: Continue Watching, Newly Added, every filter row and *Recommended for you* can be dragged to any position.
The server serves the collection's rows in that order. A collection nobody reorders looks exactly as it does today.

**Backend and admin only. No Ravilo client changes and no redeploy of any app.** Every client (Android TV, phone,
web, desktop, Tizen, receiver) renders `feed.rows` in the order the server sends them. `ChannelScreen` drops empty
rows and nothing else, and no client reads `ChannelConfig` at all (checked 2026-10-29: no reference to
`ChannelConfig`/`ChannelRowsConfig` in `ravilo-ui`). So an installed app shows the new order as soon as the backend
ships.

**What this supersedes.** R143 (`9d7b3ec3`, 2026-06-29) gave a Custom collection its own system rows and promised
that the editor's *System rows* block is *"pinned above the filter rows (system rows sit at the top, as on Home)"*,
and that the server emits them *"above the custom filter rows"*. R143's file was pruned in `d3b24ab8` (2026-09); the
quotes are from `git show d3b24ab8^:specs/ravilo/requirements/phase-R143-per-channel-system-rows.md`. This spec
replaces that one promise: the system rows sit on top **by default**, and can be moved. Everything else R143 set up
stays (per-collection `show`, Newly Added's `merge`).

Builds on, and does not change:

- **R233.** A system row always shows what is available on the page it is rendered on, in both row-list modes.
  Moving a row changes where it is, never what is in it.
- **R59.** The *Same as Home* / *Custom* switch. A *Same as Home* collection follows Home's own order, where the
  system rows are already movable. Unchanged.
- **DEB-1264.** Continue Watching's *Hide titles tagged* list (`system.continue.hide_tags`). It moves with its row.
- **Phase 225 / 269.** A filter row's sort, pins and limit, and the Recommended row's shown count. Untouched.

Not in scope:

- **Reordering on a touch screen.** HTML5 drag does not fire on most mobile browsers. Home's list has the same gap
  today. If the owner wants touch, it is one follow-up for both lists, so they stay alike.
- **Hiding a system row in a *Same as Home* collection** (R233 open question 1). Still open.
- **Live TV's *On now* row** inside a collection. It exists only on Home.

## Requirements

### FR-1 — The order on the wire: `rows.order`

`ChannelRowsConfig` gains one optional field:

```kotlin
/** DEB-2848 (FR-1) — the order of this Custom collection's rows. Entries are `system:continue`, `system:newly`,
 *  or a filter row's RowConfig.id. Empty = the default order (FR-2). Read only in Custom mode. */
val order: List<String> = emptyList(),
```

- It is **additive**. A stored config without it decodes as empty, so the default order applies (FR-2). An older
  backend that meets it ignores it (`RaviloConfigService`'s `Json { ignoreUnknownKeys = true }`) and shows the
  default order. Nothing else in the config changes shape, and no field is removed or retyped.
- The two system tokens carry a `system:` prefix so that no filter row id can collide with them (the admin mints
  `row-NNNNNN`).
- `ChannelSystemRows`, `SystemContinue` and `SystemNewly` keep every field they have: `show`, `hide_tags`, `merge`.

### FR-2 — The default order is today's

When `order` is empty, the served order is exactly today's: Continue Watching, then Newly Added (one merged row, or
Movies then Series), then the enabled filter rows in their stored sequence (`items` sorted by `RowConfig.order`,
stable). A collection that is never reordered must serve **byte-identical rows** before and after this ships.

### FR-3 — The served order follows `order`

In `HomeFeedService.buildRows()`, Custom-mode branch, the rows are emitted by walking the resolved order:

1. Take `order` in sequence. `system:continue` emits the Continue Watching row, `system:newly` emits the Newly Added
   row(s), and an id equal to an item's `RowConfig.id` emits that filter (or Recommended) row. What each row
   contains, and whether it is emitted at all (`show`, `enabled`, R102's no-empty-row rule), is decided exactly as
   today. Only the position changes.
2. **A system token missing from a non-empty `order`** is placed at the head, in default order (Continue before
   Newly Added). A missing system row falls back to where it used to be; it is never dropped.
3. **A filter row whose id is not in `order`** (added through the API, or by an older admin) is appended at the end,
   in stored sequence. The "+ Add row" button already adds at the bottom, so this matches what the editor shows.
4. **An id in `order` that matches no item** (a deleted row) is skipped. **A repeated entry** is used once, at its
   first position.

The Newly Added entry is **one** entry. Split (`merge: false`) emits Movies then Series together at that position.
Merged emits the one row there.

`channelHasAnyMatch()` (the R228 empty-collection test) does not depend on order and is unchanged.

### FR-4 — The editor: one draggable list

`openChannelEditorPage()` in `src/wasmJsMain/.../ui/RaviloConfig.kt`, Custom body:

- The separate **System rows** and **Filter rows** blocks become one list, **Rows**, in the resolved order (FR-3).
  It is built the way Home's row list is (`renderRows()`, `RowSlot`, `wireDragReorder`): each entry gets a grab
  handle and `draggable="true"`, and is dropped with the same `drop-before`/`drop-after` highlight.
- **Continue Watching and Newly Added** keep the `system` badge, their show toggle and their source line. They can be
  hidden and moved, never removed, as on Home. A hidden system row stays in the list, dimmed, and keeps its position.
- **Continue Watching's *Hide titles tagged* mount** (DEB-1264 FR-7) sits inside the Continue Watching entry, as it
  does on Home's list (`hasTags` at `renderRows()`). **The Newly Added merge switch** sits inside the Newly Added
  entry. Both travel with their row.
- **Filter rows and *Recommended for you*** keep every control they have today: title, filter summary, workbench
  edit, delete, the Recommended row's shown count.
- The intro line *"System rows (Continue Watching, Newly Added) sit on top; your filter rows follow below."* becomes
  **"Drag rows to reorder them. Continue Watching and Newly Added can be hidden and moved, not removed."**
- "+ Add row" and "+ Recommended for you" stay under the list and add at the bottom.

**Styling.** Reuse Home's row-list markup and classes (`cfg-row`, `grab`, `badge ok`, `dragging`, `drop-before`,
`drop-after`). No new CSS class is expected. If one turns out to be needed, it goes into `design/app/wf.css` and
`scripts/check-css-scoping.sh` and `scripts/check-mobile-css.sh` run green. No design round: this is Home's existing
list on a second page.

### FR-5 — What the editor writes

- **A drag writes the whole order.** After a drop, the editor sets `rows.order` to every entry currently in the list
  (both system tokens and every item id), and also rewrites `rows.items` in the same relative order with
  `order = 0, 1, 2…`. The second write is for rollback: an older backend that ignores `rows.order` still shows the
  filter rows in their new relative order, with the system rows on top.
- **No drag, no write.** A collection whose list is never dragged keeps an empty `order` (FR-2), so opening and
  saving a collection does not change its served rows.
- **Deleting a filter row** removes its id from `order` when `order` is non-empty. A stale id is harmless (FR-3.4);
  this keeps the stored list tidy.
- Switching a collection from *Custom* to *Same as Home* and back keeps `order`, as it keeps `items` and `system`
  today.

### FR-6 — Saving and reopening

`rows.order` survives a save and a reopen in both config scopes (global and a user's own layout, R51). The
post-save echo merge (`withServerHideTags()`) copies only `hide_tags` and must leave `order` as sent. DEB-2847 shows
that a field the editor draws but the save path drops is lost silently, so the proof includes a round-trip (see
*Acceptance*).

## Acceptance

**Build proof (Sjúrður):**

- A new `linuxX64Test`, `HomeFeedChannelRowOrderTest`, built like `HomeFeedContinueHideTagsTest`, with a Custom
  collection holding filter rows `row-a`, `row-b` and a Recommended row:
  1. empty `order` → served ids are Continue, Newly Added Movies, Newly Added Series, `row-a`, `row-b`, Recommended
     (FR-2);
  2. `order = [row-b, system:newly, row-a, system:continue]` → `row-b`, Newly Added (Movies, Series), `row-a`,
     Continue, then Recommended appended (FR-3.1, FR-3.3);
  3. the same with `merge: true` → one Newly Added row at that position;
  4. `order = [row-b, row-gone, row-b]` → Continue and Newly Added first (FR-3.2), then `row-b`, `row-a`, Recommended;
     `row-gone` and the repeated `row-b` skipped (FR-3.4);
  5. `system.continue.show = false` with `order` naming it → the Continue row is absent and every other row keeps its
     relative order;
  6. a `RaviloConfig` JSON with no `order` key decodes with `order` empty, and one with it round-trips unchanged
     (FR-1).
- `compileKotlinLinuxX64` and the wasmJs frontend compile.
- In the admin, on a non-production config or a throwaway user's own layout: drag Newly Added below a filter row,
  save, reload the page, reopen the collection → the list shows the same order. Then drag nothing, save → `order`
  unchanged.

**On-device QA:** one test, after the backend is deployed. The client code does not change, so this checks that a
moved system row renders right where it lands (landscape Continue tiles mid-page, focus entering the page on
whatever row is now first). Written as its own QA row with its `Devices:` line.
