# Phase R66 — Shared `ChannelButtonSpec` + accurate admin preview (FR-RV-C3)

> Authored from the design project. Unifies the channel-button rendering across the TV and the admin
> editor so the editor preview is pixel-true. Builds on **[R36](phase-R36-channel-button-editor.md)**
> (logo/text + brand fill), **[R39](phase-R39-channel-logo-fill.md)** (logo fills the button) and
> **[R53](phase-R53-channel-editor-page-padding.md)** (per-display padding).

## Problem
The channel button is drawn by **two independent renderers**: the TV `ChannelCard` (Compose) and the
admin editor's preview + row chip (`design/app/ravilo-builders.js` / `ravilo-config.html`). They have
drifted — the admin preview approximates the brand fill, doesn't always honour a **gradient**
`brandColor` (R36 §F4), renders the **logo** differently from the TV's cover-fill (R39), and does not
apply the new per-display **padding** (R53). So an operator styles a channel, the preview looks right,
and the TV shows something subtly different. With R36/R39/R52/R53 all adding knobs, the two renderers
will keep diverging unless they share one definition.

## Goal
Define **one `ChannelButtonSpec`** — the complete description of how a channel button looks — and have
**both** the TV and the admin preview render from it, so the admin preview is an exact stand-in for the
TV button.

## The spec object
`ChannelButtonSpec` carries everything needed to paint the button, derived from `ChannelConfig`:

| Field | Meaning |
|---|---|
| `display` | `LOGO` or `TEXT` (R36) |
| `logoUrl?` | image asset for LOGO mode; cover-fills the button, clipped to the radius (R39) |
| `label?` | text for TEXT mode (falls back to channel name) |
| `brandColor` | CSS fill — solid **or** `linear-gradient(deg, c1, c2)` (R36 §D) |
| `padding` | per-display `{top,right,bottom,left}`, 0–40px (R53) |
| `radius`, `size` | shared corner radius + aspect, so both renderers agree on geometry |

## Requirements
1. **Single source.** Put the spec (and the `ChannelConfig → ChannelButtonSpec` mapping, plus the
   `brandColor` parse: solid hex **or** single linear-gradient) where both targets can use it — a
   `:shared` helper for the gradient/clamp logic, mirrored by one JS builder
   (`RaviloBuilders.channelButton(spec)`) used by the admin preview **and** the saved row chip.
2. **TV renders from the spec.** `ChannelCard` consumes `ChannelButtonSpec`: gradient brand fill
   (R36 §F4), logo cover-fill (R39), text label, and the R53 padding — no ad-hoc per-call styling.
3. **Admin preview renders from the same spec.** The editor's live channel-button preview and the
   Channels-list row chip both call the one JS builder, so what the operator sees **is** what the TV
   draws: same gradient, same logo crop, same padding insets, same radius.
4. **One change, both sides.** Adding a future channel-button knob means extending `ChannelButtonSpec`
   once; both renderers pick it up. No parallel styling code paths remain.

## Invariants
- Exactly one definition of "how a channel button looks"; TV and admin preview are guaranteed to match.
- `brandColor` stays a CSS fill (R36) — no parallel color model.
- Renders server-pushed config only.

## Out of scope
- New channel-button capabilities beyond R36/R39/R52/R53 (this is unification, not new knobs).
- The filter/conditions half of the editor (R32) — unchanged.

## Mockup
`design/app/ravilo-builders.js` + `ravilo-builders.css` (the shared `channelButton(spec)` builder used
by preview + row chip), `design/app/ravilo-config.html` (Channels list chips + editor preview).
Code: `ravilo-ui/.../ChannelCard.kt`, `shared/.../tv/Models.kt` (`ChannelConfig`).
