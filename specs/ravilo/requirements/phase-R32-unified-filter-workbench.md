# Phase R32 — Unified filter workbench + hero builder + Library round-trip

> Revises the filter parts of **[R16](phase-R16-jellystructure-config-screen.md)** and the typed
> filters of **[R28](phase-R28-config-editor-fidelity.md)**: custom Channel/Content-row filters are
> now built with a **condition-stack workbench** instead of single typed dropdowns. Layout params from
> **[R27](phase-R27-layout-model-extensions.md)** are unchanged (re-affirmed in §F). On the admin side
> it supersedes the standalone Library audio-track popover from
> **[Phase 20](../../requirements/archive/phase-20-library-audio-track-filter.md)**, folding it into
> the same builder, and extends the **[Phase 30](../../requirements/archive/phase-30-library-multi-axis-filters.md)**
> facet set.

## Problem
Filters are built two different ways. The Ravilo config screen (R16/R28) uses single typed dropdowns
for channel/row filters; the Library (Phase 20/30) uses a separate filter bar with a bespoke
audio-track popover. They share concepts (studio/network/genre/tag) but no UI, so building "the same"
filter in both places means re-learning and re-typing it. There is also no guided way to add a hero
carousel item, and no path from "I found a good set of titles in the Library" to "feature this for a
viewer."

## Goal
**One workbench builder**, used everywhere a filter is defined — Ravilo Content rows, Ravilo Channels,
and the Library — with a live match count and result preview. A guided **hero-item builder**. And a
Library **round-trip** that pushes a filter or a single title straight into a viewer's per-user Ravilo
layout.

## Current state (as-is)
- `/ravilo` config (R16/R28): hero list, channel list, row list, behaviour, live schematic preview;
  channel/row custom filters are single typed dropdowns; system rows are kind-typed + protected.
- Library (Phase 29/30): Movies/TV switch, multi-language search, studio/network/genre/tag dropdowns,
  meta-facets, and a **standalone audio-track popover** (Phase 20).
- Per-user `RaviloConfig` store + validation exist (R04/R26); feed filters by enabled / sorts by order
  / passes layout params (R27). The home/browse feed filters reuse Phase 29/30 (R05/R06).

## Requirements

### A. The shared workbench builder
1. A single builder component (design: `window.RaviloBuilders.openFilter`) is loaded by **both** the
   Ravilo config screen and the Library. It opens in a modal with: a **Match ALL / ANY** segment, a
   stack of **condition rows**, an **Include** All/Movies/Series segment, and a live **"N titles
   match"** count with a **result-preview** grid that updates as conditions change.
2. A **condition row** is `facet ▾  ·  operator ▾  ·  value(s)`. List facets use **is any of / is none
   of** with multi-value chips; the numeric/text facets use their own operators (§B). Conditions can
   be added and removed; the join word reflects ALL→`AND` / ANY→`OR`.

### B. Facet set — Phase-30 axes + Audio track + Hero-item (universal)
1. The facet set is the **Phase-30 set: Studio · Network · Genre · Tag**, plus an **Audio track**
   group: **Audio language** (is any/none of; includes an **Untagged** value covering the old
   untagged-only toggle), **Audio codec**, and **Audio track title** (*contains / does not contain*
   free text — finds "Synstolkning", "Commentary", "SDH", …), plus a **Ravilo layout** group:
   **Hero item** (is any of **Featured / Not featured** — whether the title is currently in the
   viewer's hero carousel; lets you find or exclude already-featured titles).
2. These facets are **available on every surface the workbench runs** — Library filters, Channels, and
   Content rows. The facet picker groups the audio facets under an "Audio track" header and the
   hero-item facet under a "Ravilo layout" header.
3. **Backend extension:** the Ravilo feed/`/api/media` filter must serve **audio-track conditions**
   (language incl. untagged, codec, track-title-contains) and the **hero-item** membership predicate
   (against the per-user layout) in addition to the Phase-30 axes, since channels/rows can now use
   them. This is a deliberate extension of the R16 "reuse Phase-30 facets" invariant, scoped to
   **adding the audio-track and hero-item axes only**.
4. **Year / Rating and other axes the filter endpoint cannot serve remain excluded** — no parallel
   taxonomy beyond the audio-track + hero-item additions.

### C. Channels & Content rows via the builder
1. **Content row:** the builder result becomes a row (title auto-derived from the first condition or
   overridden); Include movies/series; appended to the stack, drag-reorderable; show/hide toggle.
   System rows (Continue, Newly Added, …) remain **kind-typed and protected** (R28) — not built here.
2. **Channel:** the same builder plus channel-button styling — **Logo / Text** style and a **brand
   color** swatch — with a live channel-button preview. The channel's conditions scope its view's
   rows on the TV (R16).
3. Existing channels/rows are **click-to-edit**, reopening the builder prefilled.

### D. Hero-item builder
1. **Step 1 — pick a title:** a multi-language search (reuse Phase 29) over the viewer's library.
   When invoked from a **detail page** (§E3) the title is **locked** (no search) and shown as a
   "Featuring" card instead.
2. **Step 2 — dress it:** **badge** (New Season / 4K / Top 10 / Premiere / None / custom), **tagline /
   kicker**, **backdrop** choice, and a **clearlogo overlay** toggle (text-title fallback).
3. A live **16:9 preview** shows the banner as seen on the TV. Hero **height** and **auto-advance**
   are carousel-wide settings (§F), not per item. When invoked outside the config screen, a **"For
   viewer" picker** in the footer targets whose layout the item is added to.

### E. Library round-trip + detail-page hero entry (admin side)
1. The Library's **"Add filter"** opens the **same** workbench builder (labelled for the Library);
   active conditions render as **removable chips** with a live **"N titles match"** count; the
   standalone Phase-20 audio popover is **removed** (audio is now a workbench facet, §B).
2. **"Save filter as… ▾"** saves the current filter into a chosen viewer's per-user Ravilo layout as a
   **Channel** or a **Content row** — same conditions, no retyping.
3. **Featuring a single title moves to the detail page.** Movie & Series detail get a **"★ Feature in
   Ravilo…"** action in the pagebar that opens the hero builder (§D) with the **title locked** and a
   **"For viewer"** picker. The Library's former per-poster "Save as hero item" hover action is
   **removed** (the Library grid hover keeps only "Open detail"); whether a title is already featured
   is instead surfaced/filterable via the **Hero item** facet (§B).
4. All round-trip / feature writes go through the **per-user `RaviloConfig` store** (R04/R26) and sync
   to the viewer's devices.

### F. Layout params (re-affirm R27/R28)
1. **Hero height 30–100%** of screen; **auto-advance default 7 s**; **tile shapes Standard poster /
   Wide landscape / Square**. (Corrects earlier mock values; matches R27/R28.)

## Invariants
- The workbench is the **single** filter UI for custom channels/rows and Library filters — no parallel
  typed-filter UI for custom filters; system rows stay kind-typed + protected (R28).
- Facets = **Phase-30 axes + the audio-track axis**; nothing the filter endpoint can't serve (no
  Year/Rating).
- All viewer-targeted writes go through the **server-owned per-user store** (R04/R26); the TV renders
  **server-pushed state** only.
- Multi-language search (Phase 29) backs both the Library and the hero title picker.

## Out of scope
- New layout-model fields beyond R27 (hero/channel/row already modelled).
- Year / Rating / runtime or other non-Phase-30 facet axes.
- The on-device Ravilo client UI (R10–R14) — this phase is the **jellystructure-web** authoring side
  plus the backend filter extension in §B3.

## Design reference
`design/app/ravilo-config.html` (Add row / Add channel / Add featured open the workbench + hero
builder; existing items are click-to-edit), `design/app/library.html` (Add filter → same builder;
Save filter as… → Channel/Content row; the per-poster hero action is gone — grid hover is "Open
detail" only; **Hero item** is a workbench facet), `design/app/media.html` (pagebar **★ Feature in
Ravilo…** → locked-title hero builder + viewer picker), and the shared builder
`design/app/ravilo-builders.js` (+ `ravilo-builders.css`). The side-by-side exploration of the
create-flow directions is preserved in `design/app/Ravilo Create Flows.html`.
