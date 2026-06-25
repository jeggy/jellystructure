# Phase R57 — Ravilo config: Pair-a-TV action + sticky action navbar (FR-RV-P1)


> Authored from the design project (`design/app/ravilo-config.html`). Resolves the
> **SYNC-AUDIT §3.2** open thread ("add a *Pair a TV* section, or keep it code-only?") and
> surfaces the R03/R15 pairing handshake on the admin side.

## Problem

- **Pairing a TV** (entering the on-screen code so a TV signs into a Jellyfin user) had **no
  surface** in the Jellystructure config screen — it was code-only. It also should **not** sit
  under the Global / per-user **scope switcher** (R51): pairing a *device* is independent of
  which *layout* a user receives.
- The config screen is long; the **Save** button scrolled out of reach.

## Change A — Pair a TV

- A **"Pair a TV"** action in the config screen's **top bar, beside Save** (with a small TV
  glyph) — a standalone action, not nested in the scope switcher.
- It opens a **popup**:
  1. **Sign in as** — pick which **Jellyfin user** this TV signs into (dropdown).
  2. **Pairing code** — enter the **6-character code** shown on the TV (auto-advancing,
     paste-aware boxes).
  - **Pair TV** enables only once all six characters are entered; on confirm the popup closes
     and confirms the device is pairing.
- This is the **admin side** of the R03/R15 pairing handshake: the TV's *Add user* flow shows
  the code; the admin enters it here against a chosen user. It creates a **device session**
  for that user — no layout change, and independent of the layout scope (global vs per-user
  override).

## Change B — sticky action navbar

- The **top action bar** (Save · Pair a TV · Open Ravilo + the *saved · synced* status) is
  **sticky** to the top of the page, so the primary actions — especially **Save** — stay
  reachable while scrolling the long config. The section-nav and live-preview sticky offsets
  were nudged down to clear the navbar.

## Scope / invariants

- Pairing creates/refreshes a **device session** (R03) for the selected user; code generation
  and expiry are server concerns (the popup just collects user + code).
- Pairing is **device-scoped**, orthogonal to the **R51** global/per-user layout model.

## Mockup

`design/app/ravilo-config.html` (top-bar **Pair a TV** button + popup; sticky pagebar).
