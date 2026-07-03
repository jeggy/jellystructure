# Phase R161 — In-app Settings page: theme · language · unpair (device-local preferences on the TV)

> A dedicated **Settings** page reachable from the profile menu (the tile **right of "Add user"**) — the
> first place the viewer can change a few things **on the TV itself**: pick a **Theme** (Aurora / Midnight
> / Noir), pick an interface **Language** (English / Dansk / Føroyskt) as a per-user override, and
> **Unpair this TV** (with a confirm step). D-pad moves **up/down between the sections** and left/right
> within a row. These are deliberately **device-local preferences**, not server media state.

**Status:** Planned — **design built**. ⚠ Dev review 2026-07-03: parts of this already exist in the app
(see the **Dev-review addenda** at the bottom — they supersede the "gap" claims and the device-local
storage model below). What is genuinely new: the **language picker**, the **unpair flow** (client side),
and the design restyle of the existing Settings screen.

## Problem
Everything Ravilo shows is server-pushed, and there was **no on-TV settings surface at all**: the interface
language was fixed to the Jellystructure-set `profile.lang`, the visual skin was only switchable through the
**preview chrome** (not a shipped control), and there was no way to sign this TV out / unpair it from the
couch. A viewer who wants Danish tonight, or a darker skin, or to hand the TV back, had no in-app path.

## Architectural constraint (driving decision)
The Ravilo constitution says the frontend **renders server-pushed state**. This phase is the **explicit,
bounded exception**: **theme** and the **language override** are pure **UI preferences** (they change
nothing about the media/catalogue), and **unpair** is a **device-scoped** action. They are stored
**on the device** (localStorage in the mock; production: device-scoped prefs + the pairing-revoke call),
**not** derived from or written back to catalogue state. The **language default still comes from
Jellystructure** (`profile.lang`); the in-app choice is an **override layered on top**, per user id — it
never mutates the server-side profile.

## Current state (as-is)
**Design is built** in `design/ravilo/`:

- **Entry — `ravilo-app.js` `renderProfiles()`**: in the **"switch"** menu only (not the first-run
  "Who's watching?" gate), a **Settings** tile (`data-pid="__settings"`, gear glyph) is appended to the
  profile grid **after "Add user"**. `.profiles .grid` `max-width` was widened (1200 → 1340) so the demo's
  4 users + Add + Settings stay on one row.
- **Settings page — `pMode='settings'`, `renderSettings()`** (reuses the `.profiles` overlay shell):
  - **Theme**: `Aurora / Midnight / Noir` chips (each a colour dot + name), current highlighted.
    `setSkin(skin)` sets `data-skin` on `<html>`, persists `localStorage['js-ravilo-skin']`, syncs the
    preview skin buttons, and re-renders. On boot, mount reads `js-ravilo-skin` and applies it. **First
    time the skin is an in-app control** (previously preview-chrome only).
  - **Language**: `English / Dansk / Føroyskt` (endonyms), current highlighted. `setUserLang(code)` stores
    a per-user override `localStorage['js-ravilo-lang:<userId>']`, calls `setRaviloLang`, `relabelChrome`,
    `updateDiscoverNav`/`updateUpcomingNav`, and re-localizes the **screen behind the overlay** (`go(view)`)
    plus the panel. `resolveLang(p) = override(p.id) || p.lang || 'en'` is used at **boot** and in
    **`applyUser`**, so per-user overrides survive user-switch and reload. (Moved here out of the profile
    menu, where it briefly lived.)
  - **Unpair this TV**: a danger-styled action with a one-line description. Selecting it opens a
    **confirmation** (`pMode='unpair'`, `renderUnpairConfirm()`): "Unpair this TV?" with **Cancel**
    (default focus) and a red **"Yes, unpair"**. Confirm → `doLogout()` clears the current-user pairing
    (`localStorage.removeItem('js-ravilo-user')`), toasts, and returns to the **"Who's watching?"** gate.
- **D-pad navigation — `pGrid()` + `movePidx(dir)`**: the overlay's focusables are grouped into **visual
  rows** by on-screen top; **↑/↓ move between rows** (theme → language → unpair → back), **←/→ within a
  row**. Back/Esc: `unpair → settings`, `settings → profile menu`, `switch/signin → close`. A guard in the
  stage pointer handler (`if (e.target.closest('.profiles')) return`) stops overlay clicks leaking to the
  background focus engine.
- **`ravilo-i18n.js`**: `settings`, `theme`, `language`, `unpair`, `unpair_desc`, `unpair_confirm`,
  `unpair_yes`, `toast_unpaired` — en/da/fo (endonyms come from `RAVILO_LANGS_UI`).
- **`ravilo.css`**: `.pic.settings` tile; `.profiles.settings-panel`; `.set-sec` / `.set-danger` /
  `.set-danger-desc`; `.btn.danger`; `.lang-chip` / `.lang-endo` / `.lang-name`; `.theme-chip` /
  `.theme-dot.{aurora,midnight,noir}`.

**The gap (corrected by dev review, 2026-07-03):** the mock's claims above describe the *mock*, not the
app. The Compose app **already has** `SettingsScreen.kt` (skin picker gated on `allowSkinOverride`,
Continue-progress + autoplay toggles, sign-out), reachable from the profile picker's Settings tile
(already switcher-only, exactly per §A/Invariants), and the skin choice is **already server-owned**
(`PUT /api/tv/settings` → `RaviloConfig.viewerSkinOverride`, resolved by `effectiveSkin()`). The backend
also already has a device-initiated **`POST /api/tv/unpair`** route. What's actually missing: the
**language picker** (per-user `ui_language` override), the **unpair flow in the app** (`TvApiClient` has
no unpair call; no confirm dialog), and the **visual restyle** to this design (theme dots, section
layout, danger styling, 2-D d-pad grouping).

## Requirements

### A. Entry point
1. Add a **Settings** entry as the **last item of the profile switcher**, to the right of "Add user"
   (switcher only — **not** the first-run gate). Keep the row layout intact as the user count grows
   (wrap gracefully).

### B. Theme
2. Offer the three existing skins **Aurora / Midnight / Noir**. Applying one takes effect **immediately**
   and is **persisted on the device**; it is **re-applied on next launch**. This is a device preference —
   it does **not** touch the Jellystructure per-user/global config. Reuse the existing skin token sets.

### C. Language (override)
3. Offer the supported interface languages (en/da/fo; endonyms). The selection is a **per-user override**
   stored on the device, layered over the Jellystructure-set `profile.lang`. Applying re-localizes the
   whole UI immediately. Resolution order everywhere (boot, user-switch): **device override → profile.lang
   → en**. The override **never writes back** to the server profile.

### D. Unpair this TV (with confirm)
4. Provide an **Unpair this TV** action that **requires a confirmation** step ("Unpair this TV?" · Cancel /
   Yes, unpair) with focus defaulting to **Cancel**. On confirm, revoke this device's pairing / sign out,
   and return to the sign-in gate. Cancel/Back returns to Settings with no change.

### E. Navigation
5. The settings surface is **2-D**: **↑/↓ move between sections**, **←/→ within a section's row**. Back/Esc
   steps out one level (confirm → settings → profile menu → close). The overlay **owns its own pointer
   input** (clicks don't fall through to the background).

## Invariants
- **Device-local, not server media state.** Theme + language override live on the TV (localStorage in the
  mock; device-scoped prefs in production). They are the **explicit exception** to "render server state
  only", justified because they are UI preferences / device actions, not catalogue data.
- **Language default stays server-owned.** The Jellystructure `profile.lang` remains the default; the
  in-app pick is an override keyed by user id and is **never** written back to the server profile.
- **Resolution order** for interface language is always **override → profile.lang → en**, applied at boot
  and on every user-switch.
- **Unpair is confirmed** and defaults focus to the non-destructive choice.
- **Settings is switcher-only** — never shown on the first-run "Who's watching?" gate.
- Reuses the **existing** Aurora/Midnight/Noir skins — no new theme tokens invented here.

## Out of scope
- Any **new** settings beyond theme / language / unpair (no playback, subtitle-default, parental, or
  network settings this phase) — the surface is intentionally minimal.
- New **skins** or a theme editor — only the three existing skins.
- Editing another **user's** preferences, or **admin/global** config (that lives in Jellystructure) — this
  is the current viewer's own device-local prefs only.
- A settings entry in the **top app-bar** — entry is via the profile switcher only.

## Source references
- Design: `design/ravilo/ravilo-app.js` (`renderProfiles` Settings tile, `openSettings`/`renderSettings`,
  `setSkin`, `setUserLang`/`resolveLang`/`langOverride`, `renderUnpairConfirm`/`doLogout`, `pGrid`/`movePidx`,
  boot skin apply, stage-click `.profiles` guard); `design/ravilo/ravilo-i18n.js` (`settings`/`theme`/
  `language`/`unpair*`); `design/ravilo/ravilo.css` (`.pic.settings`, `.settings-panel`, `.set-*`,
  `.btn.danger`, `.lang-chip`, `.theme-chip`/`.theme-dot`).
- Backend / app: a **device-scoped preference store** (theme, language override) + the **pairing-revoke**
  call; `ravilo-ui/.../screens/SettingsScreen.kt` (+ theme/skin token switch, i18n locale switch, unpair
  confirm dialog); the pairing/session layer for revoke.
- Related: **R57** (Pair-a-TV — the pairing this "Unpair" reverses), **R51** (global vs per-user config in
  Jellystructure — the server-owned language default this overrides), **constitution** (interface-language
  rule + "renders server state" invariant this phase bounds an exception to), **R62** (Aurora/Midnight/Noir
  skins reused here).

## Dev-review addenda (2026-07-03 — supersede the device-local storage model above)

1. **No localStorage / device-local lane — viewer prefs are server-owned.** The mock stores theme +
   language in localStorage because a static mock has no server. The real app **already** persists the
   viewer's skin **server-side** (`PUT /api/tv/settings` → `viewerSkinOverride`, kept separate from the
   operator's `defaultSkin` so operator default changes still reach viewers who never picked one, and
   gated by the admin's `allowSkinOverride`). **Language follows the same lane**: add `ui_language` to
   `ViewerSettingsRequest` as a per-user viewer override resolved against the operator default. This is
   strictly better than device-local (survives reinstall, follows the user across TVs/phone/web) and it
   **dissolves the "bounded exception" this spec claims** — the constitution's "render server-pushed
   state" holds untouched. Resolution order becomes: **viewer override → per-user admin override → global
   default** (see R162, which owns the storage split).
2. **Ordering: R162 first (or together).** Today `applyViewerSettings` copies the **whole resolved
   config** into a per-user record on first write — a global-layout viewer changing any setting forks the
   entire layout (the exact trap R162 fixes, and it is **live in the shipped app already**). Landing the
   language picker on the current mechanism would widen that bug; implement R162's overlay split first.
3. **Unpair semantics.** "Unpair this TV" ≠ the existing per-session "Sign out". Unpair revokes **every
   session this device holds**: iterate `MultiTokenStore.getAll()`, call the **existing**
   `POST /api/tv/unpair` per token (add the missing `TvApiClient.unpair()` wrapper), then
   `MultiTokenStore.clear()` (the deliberately-kept Phase 127 symbol — this phase is its intended
   consumer) → first-run "Who's watching?" gate. Keep the existing single-session Sign out alongside it.
4. **Scope of the screen.** This design **restyles the existing `SettingsScreen.kt`** — the shipped
   Continue-progress and autoplay toggles **stay** (removing them would regress shipped behaviour; the
   design simply didn't know about them). Theme keeps its `allowSkinOverride` gate (section hidden when
   the admin disallows overrides).
