# Phase R162 — Behaviour preferences as a field-level per-user overlay (decoupled from the layout override)

> Splits the Ravilo config into **two independent per-user layers**: the **layout override** (R51 — hero,
> channels, rows, Top 10; *full-replace*) and a new **behaviour & preferences overlay** (interface language,
> theme, tile shape, progress bars; *field-level*). Overriding a preference **never creates a custom layout**,
> so a viewer keeps receiving global layout + default updates. Language & theme are additionally
> **viewer-editable on the TV** (R161, device-local) and surfaced here read-only. Removes the trap where an
> in-app change appeared to fork the whole config and orphan the user under "A specific user".

**Status:** Planned — **design built**, backend/app-integration unbuilt. ⚠ Dev review 2026-07-03
**confirmed the trap is a live, shipped bug** — see the Dev-review addenda at the bottom, which also
correct the storage model (no device-local lane; writer-tagged server overlay instead) and widen the
field set (+`autoplay_next`).

## Problem
In the Ravilo config editor (`app/ravilo-config.html`), the **Behaviour** section (interface language, default
skin/theme, tile shape, Continue-Watching progress bars) was rendered **inside the same config record that
R51's "custom layout" fully replaces**. Two things followed from that conflation:

1. **The lock.** Selecting a user who has **no custom layout** dimmed and locked the *entire* config column,
   including Behaviour, behind **"Create custom layout"**. You couldn't set a single per-user preference
   without forking the whole layout — an all-or-nothing choice for a field-level want.
2. **The orphaning.** R161 lets a viewer change **language** (and theme) **on their own TV**. Because those
   settings lived in the layout-scoped record, a viewer's in-app language change read as "this user now has
   their own config" — landing them under **"A specific user"** and detaching them from **global layout +
   default updates** (the exact confusion R141 §D calls out for scope mismatches). Changing language should
   set **one field**, not fork everything.

The fix is to model behaviour as a **separate, field-level overlay** that layers over the global defaults,
independent of the R51 layout override.

## Architectural constraint (driving decision)
Two orthogonal per-user layers, each with its own semantics:

- **Layout override (R51) — full-replace, binary.** Hero, channels, content rows, Top 10. A user either
  follows the global layout or has a custom one that *fully replaces* it (no piecemeal inheritance). Unchanged.
- **Behaviour & preferences — field-level, sparse.** Each of language / theme / tile shape / progress bars
  **independently** either **follows the global default** or is **overridden for the user**. The overlay is a
  sparse per-field map; absent keys follow global. It is **never** part of the layout record and creating/
  removing a layout override never touches it (and vice versa).

**Resolution order (per setting):** **device-local override (R161, language & theme only) → per-user admin
override (this overlay) → global default.** This nests cleanly inside R161's stated
`override → profile.lang → en`: the R161 `profile.lang` *is* the server-resolved default, which this phase
splits into (per-user admin override → global default). The device-local lane stays the R161 **exception**
to "render server state only" (UI preference / device action, never written back to the server profile).

## Current state (as-is)
**Design is built** in `design/app/ravilo-config.html`:

- **`behaviour` module** — owns `#sect-behaviour`. Holds the `GLOBAL` defaults
  (`lang:'en', skin:'aurora', tile:'poster', progress:true`) and renders each setting from the **resolved**
  value. `render(scope, user)` paints rows; `setVal(key,val)` writes (global scope → edits the default;
  user scope → sets/clears the user's overlay, **clearing back to "follow global" when the chosen value
  equals the global default**); `reset(key)` clears an override.
- **Per-field state chips** (`.beh-row` / `.beh-state`): **Following global · \<value\>** (muted) ·
  **Overridden for \<user\>** (accent) + **Reset to global** · **✱ Set by viewer on their TV** (device;
  teal) + **Reset to default**. Global scope shows **Default for all users**. Language & theme carry a
  **"viewer-editable"** tag.
- **Device-set values are read-only in the editor** — a language/theme value the viewer picked on the TV
  wins there; its control is **disabled** and the only editor action is **Reset to default** (clears the
  device override; the editor cannot force a value over it).
- **Behaviour is excluded from the layout lock** — `scopeCtl` filters `#sect-behaviour` out of the dimmed
  `set-section` list and calls `behaviour.render(scope, user)`, so the section stays **editable in both
  scopes even when the user follows the global layout**.
- **Copy clarifies layout vs preferences** — the scope switcher note, the custom-layout lock sub-text, and
  the "custom layout" badge all now say the layout override replaces **only** the layout (hero / channels /
  rows / Top 10) and that behaviour preferences are tracked **separately**. A footer note explains that the
  viewer's on-TV language/theme choice layers over the default and **does not fork their layout**.
- **Demo data** exercises every state: *Olivar* follows the global layout but changed **language to Danish
  on his TV** (device override, still global layout — the headline scenario); *Eyð* has a custom layout, a
  **device** language override, and an **admin** tile-shape override; *Kids* has admin theme + progress
  overrides; *Marjun* is pure global.

**The gap:** the backend has no field-level per-user behaviour store distinct from the layout record, and the
config API doesn't expose the R161 device-local overrides to the editor for read-only display.

## Requirements

### FR-R162-0 — Two independent per-user layers
The per-user store keeps the **layout override** (R51) and the **behaviour overlay** as **separate records**.
Adding or removing a custom layout must not create, clear, or alter the behaviour overlay, and setting or
clearing a behaviour field must not create or alter a layout override.

### FR-R162-1 — Behaviour is always editable (never gated by the layout lock)
The Behaviour & preferences section is editable in **both** scopes: **global** (edits the defaults for
everyone) and **user** (edits that user's overlay) — **including when the user follows the global layout**.
It is never dimmed or locked behind "Create custom layout".

### FR-R162-2 — Per-field resolved state + reset
For each setting in user scope, show its resolved value and source: **Following global · \<value\>**,
**Overridden for \<user\>** (with **Reset to global**), or **✱ Set by viewer on their TV** (with **Reset to
default**). Global scope shows the default value labelled for all users. Reset clears the corresponding
override and re-resolves.

### FR-R162-3 — Setting semantics
- Setting a per-user value **equal to the global default** stores **"follow global"** (no stale override),
  so later default changes still reach the user.
- **Tile shape** and **progress bars** are admin-set here (no on-TV control) and override freely.
- **Language** and **theme** carry an R161 **device-local** override that **wins on the TV**; the editor
  **cannot overwrite** a device-set value — the only editor action on it is **Reset to default** (which
  clears the device override).

### FR-R162-4 — Copy separates layout from preferences
The scope switcher, the custom-layout lock, and the "custom layout" note state that a custom layout replaces
**only** the layout (hero / channels / rows / Top 10) and that behaviour preferences are a separate,
field-level overlay that keeps following the global defaults.

### FR-R162-5 — Store + API
Persist the behaviour overlay as a **sparse per-user record** (only overridden fields), independent of the
layout record and live-pushed like other config changes (R33/R141). Expose the R161 **device-local**
language/theme overrides to the config API **read-only**, so the editor can display "Set by viewer on their
TV" and offer Reset-to-default without ever writing the device value back.

## Invariants
- **Two orthogonal layers.** Layout override (full-replace, R51) and behaviour overlay (field-level) are
  independent; neither forks or clears the other.
- **Overriding a preference never creates a custom layout** — a viewer with only preference overrides still
  follows the global layout and receives its updates.
- **Resolution order** is always **device-local (R161) → per-user admin override → global default**.
- **Device-local values win on the TV and are never written back** (the R161 exception is preserved); the
  editor's only action on them is reset.
- **Follow-global is sticky** — a per-user value equal to the global default is stored as "follow", so global
  default changes keep propagating.
- Behaviour preferences remain **server-owned** (constitution) apart from the bounded R161 device-local lane.

## Out of scope
- **New behaviour settings** beyond the existing four (language, theme, tile shape, progress bars).
- **New device-local (on-TV) settings** beyond R161's language + theme.
- Any change to **R51 layout-override** full-replace semantics.
- A merge/diff view of overlay-vs-global, or bulk "apply to all users" tools — out of scope this phase.

## Source references
- Design: `design/app/ravilo-config.html` — the `behaviour` module (`GLOBAL` defaults, per-user `pref`
  overlay, `render`/`setVal`/`reset`, `.beh-row`/`.beh-state` chips, device read-only lock), `scopeCtl`
  integration (`#sect-behaviour` excluded from `scope-dim`, `behaviour.render(scope,user)`, per-user `pref`
  demo data), and the revised scope/lock/note copy; `.beh-*` CSS.
- Backend / app: a **field-level per-user behaviour store** separate from the layout record; config-API
  read-only exposure of the R161 device-local language/theme overrides; the live-push path (R33/R141).
- Related: **R51** (global vs per-user *layout* override — the full-replace layer this decouples from),
  **R161** (in-app device-local theme/language the overlay resolves under), **R141 §D** (scope-mismatch
  legibility — this removes the biggest source of it), **constitution** (per-user config is server-owned;
  the R161 device-local exception).

## Dev-review addenda (2026-07-03 — supersede the storage model above)

1. **The orphaning trap is a live, shipped bug — this phase is a bug fix, not just editor UX.**
   `RaviloConfigService.applyViewerSettings` (the `PUT /api/tv/settings` handler) does
   `getConfig(userId)` — which **falls through to the global record** when the user has none — and then
   `save(userId, …)`: the first time a global-layout viewer changes *any* viewer setting from the
   shipped Settings screen (skin, Continue-progress, autoplay), the **entire resolved global layout is
   snapshotted into a personal record**. From then on `hasCustomConfig()` is true, the user shows under
   "A specific user", and global layout/default updates stop reaching them. Exactly the R141 §D trap;
   reachable today without R161.
2. **Overlay field set is five, not four:** `ui_language`, `skin`, `tile_shape`,
   `show_continue_progress`, **and `autoplay_next`** — everything `ViewerSettingsRequest` already lets a
   viewer write. Leaving `autoplay_next` in the layout record would keep the §1 fork alive for that one
   field. (`allowSkinOverride` stays a global-record admin gate, unchanged.)
3. **No device-local lane — replace "read-only exposure of R161 device overrides" with writer-tagged
   server entries.** Per the R161 dev review, viewer prefs are **server-owned** (localStorage was a mock
   artifact); there is nothing device-local to "expose read-only", and no contradiction with "never
   written back". Instead each **overlay entry carries its writer**: `viewer` (set via
   `PUT /api/tv/settings`) or `admin` (set via the config editor). The editor's three states map 1:1:
   *Following global* (no entry) · *Overridden for \<user\>* (admin entry, → Reset to global) · *"✱ Set
   by viewer on their TV"* (viewer entry — **reset-only in the editor**, preserving the design's
   guardrail that an operator doesn't silently clobber a viewer's explicit choice). Reset deletes the
   entry and live-pushes (R33/TvEventBus) so the TV re-resolves immediately — no server→device
   "clear localStorage" push needed, because there is no localStorage.
4. **Resolution order simplifies to two layers:** **viewer overlay entry → admin overlay entry → global
   default** (writer tags order the same overlay; R161's "device → admin → global" collapses into this).
5. **Migration.** On upgrade, for each existing per-user record: lift `viewerSkinOverride` into the
   overlay as a `viewer`-tagged `skin` entry (it is viewer-set by construction); lift
   `show_continue_progress` / `autoplay_next` / `tile_shape` / `ui_language` values that **differ from
   the global record** into `admin`-tagged entries (best guess — an admin can reset). The layout portion
   of the record stays a layout override; records that only ever existed because of the §1 fork can then
   be dropped by the admin via the existing "Remove custom layout" **without losing the user's
   preferences** — which is the whole point of the split.
