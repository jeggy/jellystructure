# Phase R66 — Shared channel-button spec + pixel-accurate admin preview (FR-RV-CB1)

## Problem
The channel button is **defined twice**: once in the Ravilo TV renderer (Compose `ChannelCard.kt`) and
again, three separate times, as rough approximations in the Jellystructure admin config editor. The
admin **preview disagrees with the TV on nearly every value** — size, corner radius, logo cropping,
watermark, gradient direction, padding — so operators can't trust what they're configuring. We want a
**single source of truth** for the button's visual definition and an admin preview that's as accurate as
possible.

## Findings

### Code sharing IS possible (confirmed via build wiring)
`:shared/commonMain` is on the compile classpath of **both** consumers:
- `ravilo-ui/build.gradle.kts:39-41` — `commonMain { implementation(projects.shared) }` (android + wasmJs)
- root `build.gradle.kts:251-252` — `wasmJsMain { implementation(projects.shared) }` (the admin app)
Both files already import `dev.jellystructure.shared.tv.ChannelButtonPadding`. So a plain-Kotlin `object`
of `Int`/`Float`/`String` constants (no Compose, no DOM types) in `shared/src/commonMain` is visible to
the Compose renderer **and** the wasm admin. **Rendering** can't be shared (Compose `dp`/Canvas Brush vs
DOM CSS), but the **definition** (numbers, ratios, fill-parse rules, fonts) can be one source of truth.

### The TV button's canonical values — `ravilo-ui/.../components/ChannelCard.kt`
size `224×94 dp` (`:132`) · corner radius `13 dp` (`:102,:155`) · focus scale `1.08` (`:98`) · focus ring
`3 dp` accent@0.7 (`:99,:159`) · glow elevation `22 dp` accent@0.55 (`:100`) · brand gradient: authored
stops if ≥2 colors else accent@0.28→card, **always forced diagonal — the authored angle is discarded**
(`:110-118`) · sheen white@0.08→transparent (`:120`) · watermark `name.take(8).uppercase()`, `29 sp`,
white@0.06, Space Grotesk, bottom-start, pad start 12/bottom 8 (`:174-183`) · logo `RemoteImage` with
**`ContentScale.Fit`** inset by the shared `ChannelButtonPadding` (`:185-200`) · text fallback `16 sp`,
SemiBold, pad 12 (`:202`) · `parseBrandFill` (`:45-81`) accepts solid hex or one `linear-gradient(deg,…)`
and **strips the deg**, keeping ordered stops.

### What `:shared` already holds — `shared/.../tv/Models.kt`
`ChannelConfig` (`:309`: name, `style: ChannelStyle` LOGO/TEXT, `brandColor: String?`, `logoUrl`,
`paddingLogo`/`paddingText`), `ChannelButtonPadding` (`:278`: top/right/bottom/left Int), `ChannelStyle`,
`TileShape`. **Content/config is shared; dimensions are not** (no size/radius/watermark constant exists).

### The admin previews today — `src/wasmJsMain/.../ui/RaviloConfig.kt` (three hand-rolled approximations)
| Aspect | TV `ChannelCard` | chip `:851` | editor preview `:995/1094` | mini pill `:1926` |
|---|---|---|---|---|
| Size | 224×94 dp | 84×34 px | 100%×38 px | auto |
| Corner radius | **13** | 8 | 8 | 5 |
| Logo fit | **Fit** | **cover (crops!)** | n/a | n/a |
| Watermark | yes 29sp | none | none | none |
| Sheen | yes | none | none | none |
| Brand gradient | **forced diagonal** | browser-honored angle | honored angle | honored angle |
| `paddingLogo/Text` | applied | **ignored** | **ignored** | ignored |
The two worst correctness gaps: logo `object-fit:cover` (admin) vs `Fit` (TV) → different cropping; and
the gradient **direction** diverges because the TV throws the angle away while CSS honors it. Authored
padding is stored but shown in no preview.

## Goal
One shared definition feeds both renderers, and the admin config-editor preview is **proportionally and
stylistically accurate** to the on-TV button — same aspect ratio, relative corner radius, logo fit,
gradient direction, watermark, and padding.

## Requirements

### A. Shared `ChannelButtonSpec` (single source of truth)
Add a pure-data `object ChannelButtonSpec` to `shared/src/commonMain/.../tv/` (sibling to `Models.kt`)
holding the canonical numbers/strings — width/height, corner radius, focus scale/ring/glow, watermark
size/alpha/pad/maxchars, text size/pad, sheen alpha, solid-wash alpha, `LOGO_TAKE_CHARS`, font name,
default fill, and a **`LOGO_FIT`** policy value both sides honor (Compose `ContentScale.Fit` ↔ CSS
`object-fit:contain`). Optionally move `parseBrandFill`'s angle-stripping stop extraction into shared so
the admin can reproduce the TV's **forced-diagonal** gradient instead of the browser's authored angle.

### B. TV renderer reads the spec
`ChannelCard.kt` replaces its literals (`224.dp`, `94.dp`, `13.dp`, `1.08f`, `29.sp`, `16.sp`, `0.08f`,
`0.28f`, pads) with `ChannelButtonSpec.X.dp`/`.sp`. No visual change — same values, now sourced.

### C. Admin preview reads the spec and becomes accurate
Rework the config-editor preview(s) in `RaviloConfig.kt` (`channelChipHtml:851`, `#ch-color-preview`
`:995/1094`, mini pills `:1926`) to emit CSS derived from `ChannelButtonSpec` (scaled to a sensible
preview size that **keeps the 224:94 aspect ratio and the 13/224 corner-radius ratio**), switch the logo
`<img>` from `object-fit:cover` to `object-fit:${ChannelButtonSpec.LOGO_FIT}`, render the gradient with
the **same forced-diagonal** direction as the TV, and add the missing **watermark + sheen** and the
**`paddingLogo`/`paddingText`** inset so what the operator sees matches the TV.

## Scope
- `shared/src/commonMain/.../tv/ChannelButtonSpec.kt` (new) — and maybe shared `parseBrandFill`.
- `ravilo-ui/.../components/ChannelCard.kt` — consume the spec (no behavior change).
- `src/wasmJsMain/.../ui/RaviloConfig.kt` — rebuild the channel-button preview from the spec.
- `design/app/*` channel-button mockup CSS, if any, aligned to the same ratios (so the sync stays consistent).

## Non-goals
- Not pixel-identical rendering (Compose Canvas ≠ DOM) — **proportionally accurate** is the bar.
- No change to `ChannelConfig`/`ChannelButtonPadding` data shape (already shared).
- Not a new editor UX (R36/R53 channel-button editor stands) — only the **preview fidelity** + the shared spec.

## Acceptance
- `ChannelButtonSpec` exists in `:shared` and is referenced by both `ChannelCard.kt` and `RaviloConfig.kt`
  (no duplicated size/radius literals on either side).
- In the admin editor, a channel with a logo + custom gradient + padding renders a preview whose aspect
  ratio, corner rounding, logo fit (contain, not cropped), gradient direction, watermark, and padding
  visibly match the same channel on the TV.
