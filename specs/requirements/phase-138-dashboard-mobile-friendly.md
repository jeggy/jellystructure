# Phase 138 — Dashboard: hide desktop-only panels on mobile (FR-DB1)

## Goal
On a mobile viewport, the Dashboard's **"Recently processed"** and **"Quick actions"** cards crowd the
screen and push the actually-important content — the **"Needs your attention"** queue — further down.
Below the existing `980px` mobile breakpoint, both cards are hidden and the attention queue takes the
full width. **Desktop is unaffected.**

## What happened (why this needed a spec, not just a CSS tweak)
This was implemented once already and **silently reverted by the next design-sync export**, which is
why it needs to be a tracked requirement with a regression fence, not just a one-off fix:

1. First landed: `Dashboard.kt` (the real wasmJs frontend) got a `dash-sidecol` class on the column
   `<div>` wrapping both cards; `design/app/app.css` got `.dash-sidecol { display: none; }` inside the
   existing `@media (max-width: 980px)` block; `design/app/index.html` (the mockup) got the same class
   for parity. Committed as `4c63f14`.
2. The **very next commit**, `885f0b3 "updated designs"`, is a wholesale export from the separate
   design-mockup project (see root `CLAUDE.md` → "How this project syncs with the repo"). That project
   has no knowledge of `dash-sidecol` — it doesn't originate there — so its export overwrote
   `design/app/app.css` and `index.html` in full, silently dropping the rule. `Dashboard.kt` is **not**
   part of the design-sync path, so the class survived there; the CSS that made it *do* anything did not.
3. Net effect: the fix looked done (a class was in the real app's HTML) but had zero visible effect,
   because the one rule that hid anything was gone. This is the same class of problem `check-phases.sh`
   and `check-fd-hygiene.sh` already guard against for other design-sync casualties (spec-file renames,
   wf.css phases 55/76/82/85/87/R75) — CSS added directly in this repo, with no matching source upstream,
   is fragile against every future "updated designs" sync until it's either upstreamed to that project or
   fenced with a check that catches its disappearance here.

## Requirements

### A. The mobile-hide rule
1. `src/wasmJsMain/kotlin/dev/jellystructure/ui/Dashboard.kt` — the column `<div>` wrapping the
   "Recently processed" and "Quick actions" cards carries a `dash-sidecol` class (in addition to the
   existing `col` class).
2. `design/app/app.css`, inside the existing `@media (max-width: 980px)` block:
   `.dash-sidecol { display: none; }`. The "Needs your attention" card (`.card.fill`) already expands to
   fill the row on its own — no other layout change needed.
3. `design/app/index.html` (the mockup) carries the matching `dash-sidecol` class on the equivalent
   column, for visual parity with the runtime page. This is best-effort documentation, not
   behavior-bearing — the real page is `Dashboard.kt` + `app.css`.

### B. Make it survive the next sync — regression fence
1. New `scripts/check-mobile-css.sh`: greps `design/app/app.css` for a small, growable list of
   design-sync-fragile rules (starting with `.dash-sidecol { display: none; }`), exits non-zero if any is
   missing. Mirrors `scripts/check-fd-hygiene.sh`'s shape (a flat list of known-fragile invariants, not a
   general linter).
2. Registered in `specs/requirements/README.md`'s post-sync checklist, alongside `check-phases.sh` and
   `check-fd-hygiene.sh` — **run all three after every "updated designs" sync.** This is the actual
   "should be respected" mechanism: a sync can still silently strip the rule (nothing in this repo can
   prevent that upstream), but the very next `check-mobile-css.sh` run — part of the established
   post-sync routine — catches it immediately instead of it going unnoticed until a user re-reports it.
3. Any future design-sync-fragile CSS fix should add one line to `check-mobile-css.sh`'s check list rather
   than inventing a new script.

## Scope
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/Dashboard.kt`, `design/app/app.css`,
  `design/app/index.html` — the fix itself (already applied).
- `scripts/check-mobile-css.sh` (new), `specs/requirements/README.md` — the regression fence.

## Non-goals
- No other Dashboard layout change. No change to the attention queue, stats grid, or scan banner.
- Not a general CSS/design-sync-conflict-resolution mechanism — just a growable tripwire for known
  casualties, matching the existing `check-phases.sh`/`check-fd-hygiene.sh` pattern.
- Does not attempt to upstream this rule into the separate design-mockup project (out of reach from this
  repo) — the fence is the mitigation, not a permanent prevention.

## Acceptance
- Below 980px, the Dashboard shows only the stats grid and the full-width "Needs your attention" queue —
  no "Recently processed" or "Quick actions" cards. Above 980px, both appear exactly as before.
- `./scripts/check-mobile-css.sh` exits 0 today, and exits 1 with a clear `MISSING` line if a future sync
  strips `.dash-sidecol` again (verified: temporarily removing the rule and re-running the script
  reproduces this failure).
- `specs/requirements/README.md` lists `check-mobile-css.sh` in the post-sync checklist next to the other
  two check scripts.
