# Phase 197 — CI went red four days ago, 18 commits landed on top of it, and the test it was telling us about was never actually broken

## Status
`✓ Built` 2026-09-06 — found while preparing the v1.9 deploy. Not dev-reviewed.

## What happened

Every CI run since **2026-09-02T07:08:33Z (`17b3fe7c "updated designs"`)** has failed. The last green
run was `d8a6a6e3` on 2026-08-31. Between them, **18 commits** were merged and **two releases (v1.8,
v1.9) were cut and published** — including a prod deploy — on a permanently red pipeline.

The failure is one Playwright test, `shell-layout.spec.ts:49` — *"sidebar nav + logo are visible in
dark mode"*. 1 failed, 7 passed, every run.

Two separate problems are tangled here, and only one of them is about the test:

1. **A red pipeline stopped carrying information.** Four days of red is indistinguishable from four
   days of "that one always fails". Nothing gates a release on it, so nothing forced the question.
2. **The test asserts a full-page screenshot against shared, mutating server state**, so it was
   always going to go red eventually. It is not reporting a UI regression, and it never was.

## Root cause of the test failure

`playwright.config.ts` runs `workers: 1` — deliberately, because "the app under test is a single
shared backend (one config.toml, one SQLite DB) — every spec file here mutates global server state".
Spec files therefore run in filename order:

```
auth → bazarr-dashboard → ravilo-web → scan-fixture → shell-layout
```

`scan-fixture.spec.ts` runs a library scan immediately before `shell-layout.spec.ts`. Comparing the
CI artifacts:

| | baseline (`shell-dark-chromium-linux.png`, captured 2026-08-17) | actual |
|---|---|---|
| Movies / TV episodes | 0 / 0 | 2 / 6 |
| Items needing attention | 0 | 19 |
| Scan banner | absent | *"Scanning — 4 items processed so far… Cancel"* |
| Recently processed | *"No activity yet"* | two real rows |
| Attention dock | closed | open, overlaying the page |
| Sidebar status footer | *"0 to triage"* | *"19 to triage"* |

**The app shell itself is pixel-identical.** Sidebar, logo, nav links, spacing, colours — all
unchanged. Every structural assertion in the test passes, including the `elementFromPoint` occlusion
checks that exist specifically to catch the 2026-08-13 blank-sidebar incident this spec was written
for. The only thing that differs is dashboard *data* and the scan banner, which shifts everything
below it down ~40 px.

### Why only dark fails, and why that matters

Tests inside the file run in order: **dark, then light**. Both baselines were captured against an
empty database — the light baseline shows `0 / 0 / 0` too — yet light passes on every run.

Dark is simply *the first test to run after `scan-fixture`*, and it catches the residual async work
in flight. By the time light runs seconds later, it has settled.

That is the finding that decides the fix: **the state is transient, so re-recording the baseline
would be wrong.** A fresh dark baseline would capture one arbitrary moment of an in-flight scan and
fail again on the next timing shift. The test needs to stop depending on that state at all.

## Problem, stated plainly

A test that guards the *shell* was written to compare the *whole page*, so every change to unrelated
dashboard data — and every timing shift in a previous spec file — is reported as a shell regression.
And a pipeline that is red by default cannot tell anyone when something real breaks.

## Goal

`shell-layout.spec.ts` fails when the app shell regresses and at no other time. CI is green, so that
the next red run means something.

## Requirements

### FR-197-1 — The shell screenshot covers the shell

`toHaveScreenshot` targets `.app-side` (the sidebar), not `page`. That is the element this spec
exists to protect: its header comment names the 2026-08-13 report of "the sidebar rendering as a
blank column (nav links/logo invisible, only the theme picker showing)".

Both baselines are re-recorded at the new scope. The existing structural assertions — visibility,
bounding boxes, and the two `elementFromPoint` occlusion checks — are unchanged; they already carry
most of the regression coverage, and the screenshot is the backstop for the things they cannot see
(colour, contrast, the theme picker covering the column).

### FR-197-2 — Mask the one live-data region inside the shell

`.app-side .status` renders live counts (*"0 to triage"* / *"19 to triage"*, plus the Jellyfin/TMDB
health dots). It must be passed as a `mask` to `toHaveScreenshot`.

Masking its *contents* costs nothing: the test already asserts `.app-side .status` is visible
structurally, and its numbers are dashboard data, not shell layout.

### FR-197-3 — Do not re-record the failing baseline as a fix

Stated as a requirement because it is the obvious wrong move and it would look like it worked. The
state the current baseline disagrees with is a scan in flight; a new baseline would encode one
moment of it and rot on the next timing change.

### FR-197-4 — A release must not be publishable on a red pipeline

Out of scope to implement here, and deliberately left as a decision rather than a silent omission:
`publish.yml` and `deploy-play-store.yml` both ran to success on every one of these 18 red commits.
Whether CI should gate them is a judgement about how much friction a household deploy should carry —
worth an explicit answer either way, because right now the answer is "no gate, by accident".

## Non-goals

- No change to `workers: 1`. Serial execution is correct and load-bearing (see the config's own
  comment); the fix is to stop asserting on state it necessarily shares.
- No teardown/reset step added to `scan-fixture.spec.ts`. Ordering-dependent cleanup is the same
  class of coupling, just pointed the other way.
- No change to the structural assertions, which are the part of this spec that has always worked.

## Acceptance

1. `shell-layout.spec.ts` passes with a scan running, with a populated library, and against an empty
   database.
2. Blanking the sidebar (e.g. re-introducing the 2026-08-13 absolutely-positioned `.seg` overlay)
   still fails the test.
3. A full CI run is green on `main`.

## Source references

- `tests/e2e/shell-layout.spec.ts:49-83` — the two theme tests and the `toHaveScreenshot` call.
- `tests/e2e/shell-layout.spec.ts-snapshots/shell-{dark,light}-chromium-linux.png` — baselines, last
  recorded `f8d154dd`, 2026-08-17.
- `tests/playwright.config.ts:7-14` — `workers: 1` and the shared-state reasoning it records.
- `tests/e2e/scan-fixture.spec.ts` — the spec that runs immediately before this one.
- CI run `34020481434` (`173a0769`) — artifacts `shell-dark-{expected,actual,diff}.png`.
- First red run: `17b3fe7c`, 2026-09-02T07:08:33Z. Last green: `d8a6a6e3`, 2026-08-31T16:15:15Z.

## Open questions

1. **Why did it start exactly at `17b3fe7c`?** That commit changed only `design/` files, and the
   frontend ships `design/app/wf.css` + `app.css` verbatim — but the shell pixels are identical in the
   artifacts, so a CSS change is not the trigger. The likelier story is that the scan's timing moved
   (Phases 182/183 landed on 08-31, changing scan concurrency and outbound pacing) and the race
   started resolving the other way. Not proven, and it does not change the fix.
2. Should the other three screenshot-free specs gain shell coverage, or is one enough?
