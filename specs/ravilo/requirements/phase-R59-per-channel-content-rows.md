# Phase R59 — Per-channel content rows (inherit Home by default, overridable) (FR-RV-R1)

> Authored from the design project. Extends **[R52](phase-R52-per-page-hero-carousels.md)** /
> **[R32](phase-R32-unified-filter-workbench.md)** and the channel editor of
> **[R53](phase-R53-channel-editor-page-padding.md)**.

## Problem

Every channel/collection page reused the **Home content rows** (scoped to that channel) with
no way to override them. Some channels want a **different row set** than Home — but the common
case (just show the Home rows) must stay zero-config.

## Change

The channel editor gains a **Content rows** section:

- A **Same as Home / Custom** switch (default **Same as Home**).
  - **Same as Home** — the channel page shows the Home content rows, scoped to the channel
    (today's behaviour; nothing to configure).
  - **Custom** — the channel gets its **own ordered row set**, built with the **shared content-
    row builder** (the same workbench used for Home rows / Library filters, reused exactly —
    add / edit / remove). It opens as a normal popup over the channel **page** (R53), like the
    hero-item picker.
- System rows (Continue Watching, Newly Added) still appear unless explicitly removed.
- A saved channel with a custom set shows a **· ▤ custom rows** tag in its config row.

## Data model

- `ChannelConfig` gains a **rows** block: `{ mode: 'inherit' | 'custom', items: RowConfig[] }`,
  default `inherit` (empty `items`). `inherit` → the feed serves the layout's Home rows scoped
  to the channel; `custom` → the feed serves `items` (each a standard `RowConfig` condition
  stack) scoped to the channel.
- Reuses `RowConfig` verbatim — **no parallel row model**.

## Scope / invariants

- Default is **inherit** — existing channels are unchanged with zero config.
- Custom rows reuse the shared workbench builder; channels never get a bespoke row editor.
- Rows are still **scoped to the channel** by the channel's own conditions, whether inherited
  or custom.

## Mockup

`design/app/ravilo-config.html` + `design/app/ravilo-builders.js` (channel editor **Content
rows** Same-as-Home / Custom switch; Custom reuses the row builder popup). Ravilo TV renders a
channel's custom rows when set, else the scoped Home rows.
