# Phase 58 — Dark-theme comfort palette: "Soft Charcoal" (FR-DK1)


## Goal

The default dark theme used a near-black floor (`--bg: #0b0c12`) under near-white ink
(`--ink: #eef0f7`). That pairing maximises contrast and, on the large dark surfaces this
app presents (Library grids, Activity log, detail pages), reads as harsh — the classic
dark-mode glare/halation where bright text vibrates against an almost-black void.

Retune the **dark** surface + ink tokens so the screen is comfortable to sit in front of,
**without** touching layout, the light theme, the accent system, or any per-screen CSS.
The fix is three coordinated moves: **lift the floor** off pure-black, **dim the ink** to
~88%, and **raise the card surfaces** so panels still read as elevated over the (now
lighter) background.

This is the "A2 · Soft Charcoal + lighter cards" option from the
`design/app/Dark Theme Comfort.html` study.

## Token changes (dark `:root` only)

| Token        | Before (near-black)            | After (Soft Charcoal)          | Why |
|--------------|--------------------------------|--------------------------------|-----|
| `--bg`       | `#0b0c12`                      | `#16181f`                      | Lift the floor; no more black hole |
| `--bg-2`     | `#0e1018`                      | `#1a1d25`                      | Track the floor (used by `.log`, gradients) |
| `--fill`     | `#15171f`                      | `#262a37`                      | Inputs/sidebar surface lifts with bg |
| `--fill-2`   | `#101219`                      | `#20242f`                      | Recessed fill stays a half-step under fill |
| `--fill-3`   | `#1c1f29`                      | `#2f3441`                      | Hover/raised fill |
| `--ink`      | `#eef0f7`                      | `#dde1ec`                      | ~88% white — kills glare/halation |
| `--ink-soft` | `#959bb0`                      | `#9aa0b4`                      | Nudge to keep secondary text legible on lighter bg |
| `--ink-dim`  | `rgba(238,240,247,.35)`        | `rgba(221,225,236,.32)`        | Track new ink |
| `--card-bg`  | `rgba(28,31,43,.62)`           | `rgba(46,51,66,.74)`           | **Lighter, more opaque** so glass cards lift off the lighter floor |
| `--acc-ink`  | `#c3b7fb`                      | `#c6bcfb`                      | Marginal lift to hold accent-text contrast on tinted bg |

**Unchanged:** `--line` / `--line-2` (still `rgba(255,255,255,.08 / .14)`), the entire accent
system (`--acc-a`, `--acc-b`, `--grad`, `--hi`, `--hi-soft`), all semantic status tokens
(`--ok` / `--warn` / `--bad` / `--info` + their `-soft` tints), shape/depth tokens, and the
Aurora ambient field (`--ambient`, the `body.wf-body::before` glows — values untouched; they
simply sit on a lighter floor and read slightly softer).

## Scope / invariants

- **Dark theme only.** The `[data-theme="light"]` block is **not** touched. The three-way
  Light / Dark / System picker (Phase 8) and `localStorage` `js-theme` contract are unchanged.
- **No per-screen CSS.** Every surface in `wf.css` and `app.css` already paints via these
  tokens (`var(--bg)`, `var(--fill-2)`, `var(--card-bg)`, …), so all screens — Dashboard,
  Library, Activity, Metadata, Settings, Movie/Series detail, Login, the injected sidebar /
  scan dock / Triage dock — retune from this single edit. No component rules change.
- **Translucent overlay scrims unchanged.** The literal dark scrims in `app.css`
  (`#cmd-palette` `rgba(8,10,16,.55)`, `.nav-backdrop` `rgba(8,10,16,.62)`) are deliberately
  left as-is — a semi-transparent dark veil works correctly over the lifted floor.
- **Terminals stay dark.** `.log` uses `--bg-2`; the light-theme override that keeps the
  console dark for contrast is unaffected.
- **Production-CSS note (per Phase 52):** the app ships `wf.css` / `app.css` **verbatim** via
  `syncDesignAssets` — there is no Tailwind/Kotlin compile step for these tokens. Editing the
  dark `:root` block in `design/app/wf.css` **is** the implementation; no `.kt` changes.

## Mockup

`design/app/wf.css` — dark `:root` surface + ink tokens retuned to the Soft Charcoal values
above. Comparison study (current vs Soft Charcoal vs Warm Slate vs Dim Neutral, plus the
lighter-card refinement that shipped) preserved at `design/app/Dark Theme Comfort.html`.
