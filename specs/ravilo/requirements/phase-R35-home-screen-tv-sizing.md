# Phase R35 — Home-screen TV sizing + hero backdrop framing

> Builds directly on **[R34](phase-R34-detail-layout-content-size.md)** (detail-screen TV sizing) and the
> layout model from **[R24](phase-R24-component-visual-fidelity.md)** / **[R27](phase-R27-layout-model-extensions.md)**.
> **Supersedes R24's home "Hero 600 dp / 72 sp" numbers.**

## Problem
R34 reduced the *detail* screens for 10-foot viewing (title 46→34sp, meta→15, synopsis→14, section
headers→18, button row `spacedBy(12.dp)`, hero bottom inset 44dp) but **deliberately left the *home*
hero carousel and content rows at the older, larger scale** (R34 §B.1 + Out-of-scope). The result: the
home hero title/buttons read oversized next to the now-tighter detail pages, and the action buttons in
particular dominate the hero. Separately, all three heroes (home + both detail) cropped the backdrop with
`Alignment.TopCenter`, pushing subjects up under the AppBar and showing headroom/letterbox — worst at
short (30–40%) hero heights.

## Goal
Bring the home hero + content-row headers to the **R34 detail scale**, finish R34's "tighter button/row
spacing" intent by shrinking the shared button chrome **globally** (so home and detail match), and correct
the hero backdrop framing to the value the HTML mockup already specifies (`object-position: center 26%`).

## Requirements

### A. Home hero carousel (`components/HeroCarousel.kt`) → R34 scale
- Title 46→**34**sp, lineHeight 54→**40**, letterSpacing -1→**-0.5** (= detail title).
- Meta 17→**15**sp; synopsis 15→**14**sp / lineHeight 22→**20** (= detail meta/synopsis, the body floor).
- Kicker held at **14**sp (raised from 13sp to clear the 14sp body floor — the one hero element
  not shrunk) / letterSpacing 2→**1.5**sp.
- Spacers: kicker→title 10→**8**, title→meta 12→**8**, meta→synopsis 14→**10**, synopsis→buttons 22→**18**,
  button row gap 14→**12** (= detail `spacedBy(12.dp)`), buttons→dots 22→**18**.
- Page dots: active width 28→**24**dp, row gap 8→**6**dp (height 6dp / inactive 6dp kept).
- Hero text-column bottom inset (`RaviloDimens.heroBodyBot`) 56→**44**dp (= detail's 44dp).

### B. Global button chrome (`components/RaviloButton.kt`)
- `heightIn(min)` 52→**44**dp, padding 24/10→**18/8**dp, corner 12→**10**dp, label 16→**15**sp,
  focus shadow 20→**16**dp. Focus scale (1.06) and lift (-3dp) unchanged.
- One spec everywhere: applies to the 3 home hero buttons **and** the 4 detail Play/My-List buttons.

### C. Content-row headers (`components/ContentRow.kt` / `StaticContentRow`)
- Row title 22→**18**sp (= detail section headers), "See All" 17→**15**sp, letterSpacing -0.3 kept.
- App-wide: used by Home / Browse / Channel / Search.

### D. Hero backdrop framing (all three heroes)
- New shared constant `RaviloDimens.heroBackdropAlignment = BiasAlignment(0f, -0.48f)` — the Compose
  equivalent of the mockup's `object-position: center 26%` (vertical bias `2*0.26 - 1 = -0.48`).
- Used by the `RemoteImage(alignment = …)` call in `HeroCarousel.kt`, `MovieDetailScreen.kt`, and
  `SeriesDetailScreen.kt`, replacing `Alignment.TopCenter`. `ContentScale.Crop` and the `ImageLoader`
  seam are unchanged (the seam already forwards `alignment`).

## Invariants
- These are **fixed design constants** layered on top of config — they do **not** override config-driven
  values. The hero **height** stays config-driven (`heroHeightPct.coerceIn(30,100)`), `autoAdvanceSeconds`
  and `tileShape` still come from the feed, and grid tiles still scale by `uiDensity` / `LocalTileScale`.
  The home hero stays **fixed-tuned (not density-scaled)** — identical to R34's invariant that density
  governs grid tiles only. (Smaller hero fonts also fit better when a viewer picks a short 30% hero.)
- Renders server-pushed state only (constitution).

## Alignment with 10-foot UI guidance
The chosen sizes sit within published TV guidance: body text ≥14sp (Amazon Fire TV ≈28px @1080p) — our
meta 15sp / synopsis 14sp are at/above the floor; primary content 18–32sp — title 34sp and row headers
18sp fit; the 48dp hero left gutter matches the Android overscan-safe margin (48dp h / 27dp v). **No text
drops below the 14sp body floor** — the kicker is held at 14sp for this reason. Do not go below 14sp text /
44dp button (constitution: TV-legible minimums).

## Out of scope
- Poster/landscape **Tile** label sizes (18/14sp) and the **AppBar** (wordmark 24sp, nav 16sp) — left
  unchanged per the "hero + row titles" scope, to avoid app-wide churn.
- Any change to the config schema, the feed, or the image seam.

## Implemented (2026-06-22)
- `ravilo-ui/.../theme/Dimens.kt` — `heroBodyBot` 56→44dp; new `heroBackdropAlignment` constant.
- `ravilo-ui/.../components/HeroCarousel.kt` — hero type/spacers/dots → R34 scale; backdrop alignment.
- `ravilo-ui/.../components/RaviloButton.kt` — global button shrink.
- `ravilo-ui/.../components/ContentRow.kt` — row header 22→18, See All 17→15.
- `ravilo-ui/.../screens/MovieDetailScreen.kt`, `SeriesDetailScreen.kt` — backdrop alignment (shared constant).
- `design/ravilo/ravilo.css` — mockup mirrored (hero-title 76→64px, btn 60→50px/21→18px, crow-head 29→24px,
  hero-syn 22→19px; hero-kicker kept at 17px); `object-position: center 26%` was already the source of truth.
