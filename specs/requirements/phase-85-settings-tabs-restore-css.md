# Phase 85 — Restore the Settings tabbed layout (sync-stripped CSS regression) (FR-ST2)

## Problem
Phase 55 turned the Settings page into 6 URL-addressable `?tab=` panels (Connections · Libraries ·
Metadata · Download tools · Notifications · Advanced). It worked for a while, but Settings is now back to
one long scroll — the tabs no longer switch panels.

## Findings (premise correction: the tab code is intact — a design re-sync deleted the load-bearing CSS)
The `Settings.kt` tab machinery is **fully present and correct**:
- Tab rail (`Settings.kt:46-55`, `settingsNavItemHtml :325-326`), panels `class="card set-section"
  data-tab="…"` (`:59,77,92,141,165,216,227,260`), `SECTION_TAB`/`SETTINGS_TABS` (`:343-353`).
- `showTab(name)` (`:1150-1169`) highlights the active nav button, toggles `.tab-show` on the
  `.set-section[data-tab]` panels, and writes `?tab=` via `Router.updateQuery(... replace = true)`
  (`:1168`, correct Phase-28 `replaceState`).
- `renderSettings` wires the tabs + applies `showTab(query["tab"])` (`:286-287`); per-tab health badges +
  switch-to-failing-tab are intact (`applyHealthFailures :355-394`).

`showTab` hides inactive panels purely by toggling a `.tab-show` class — which relies on two CSS rules:
```css
.set-section[data-tab] { display: none; }
.set-section[data-tab].tab-show { display: block; }
```
**Git history shows the regression:** Phase 55 (`7bc224f`) added those two lines to `design/app/wf.css`
(right after `hr.dash`); a later "updated designs" sync (`3650d61`) **removed** them — the Cosmos design
project's `wf.css` never carried them (they live only in `settings.html`'s inline `<style>`), so the
re-sync stripped them. Current grep: `set-section`/`tab-show` = **0** in `wf.css`/`app.css`. The frontend
ships only `wf.css` + `app.css` (`syncDesignAssets`, never the per-page inline `<style>`), so every panel
falls back to `display:block` → all six stack = long-scroll. `showTab` still flips `.tab-show`, highlights
the nav, and updates `?tab=`, but `.tab-show` is now a visual no-op. (Same failure class as the prior
sync-stripped cast-CSS incidents — `a7a918d`, `e3543bc`.)

## Goal
Settings shows one tabbed panel at a time again, `?tab=` addressable, with the per-tab health badges'
switch-to-failing-tab working — and it stays fixed across future syncs.

## Requirements
1. **Restore the two CSS rules** to `design/app/wf.css` (where Phase 55 placed them, right after
   `hr.dash`):
   ```css
   .set-section[data-tab] { display: none; }
   .set-section[data-tab].tab-show { display: block; }
   ```
   No Kotlin change is needed — the tab logic is intact.
2. **Make it survive the sync.** These two rules must also be carried in the **design-project source**
   (Cosmos) `wf.css`, not just `settings.html`'s inline `<style>` — otherwise the next `wf.css` re-sync
   strips them again (the recurring sync-stripped-CSS problem; see STATUS.md's note that the sync
   overwrites `design/`). Until then, restoring them in-repo fixes the running app.

## Scope
- `design/app/wf.css` (re-add the two `.set-section` rules) — and the design-project `wf.css` source.
- No `Settings.kt` change.

## Non-goals
- Not reworking the Settings tab UX (Phase 55 stands) — purely restoring the panel show/hide CSS.
- The Kotlin `.settings-nav-item` vs the mockup's `.navitem` divergence is intentional and not the bug.

## Acceptance
- `/#/settings?tab=metadata` shows only the Metadata panel; clicking each tab swaps the single visible
  panel and updates `?tab=`; a failing health check switches to and badges the offending tab.
- `grep set-section design/app/wf.css` returns the two restored rules.
