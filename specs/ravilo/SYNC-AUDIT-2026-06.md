# Ravilo — Design ↔ Code Sync Audit (2026-06)

A focused diff of the **implemented code** (`jeggy/jellystructure@main`) against the **current
designs** in this project (`design/app/*`, `design/ravilo/*`). Each finding cites the code evidence and
the spec that tracks the fix. Severity: **S1** = visible/behavioural mismatch, **S2** = missing capability,
**S3** = cosmetic / copy / doc drift.

> **Scope.** Diffed the full admin screen set — Ravilo config (`ui/RaviloConfig.kt`), the shared layout
> model (`shared/.../tv/Models.kt`), the filter workbench (`ui/Workbench.kt`), `Library`, `MediaDetail`,
> `Settings`, `Metadata`, `Activity`, `Dashboard`, `Shell`, `TrackEditor` — plus the TV `ChannelCard` +
> `AppBar` and the brand mark. The Compose TV screens were inventoried (all present; R34/R35 shipped) but
> not pixel-diffed. One deep-check remains (§6: artwork manager fidelity).

---

## 1 · Channel-button editor  → tracked by **R36**

The implemented admin editor is the **pre-R36 inline editor**; the current design moved all channel-button
styling into the row's **popup** with a real logo-image path and a custom color/gradient builder.

| # | Sev | Finding | Code evidence | Design intent |
|---|----|---------|---------------|---------------|
| 1.1 | S1 | **Inline Logo/Text `<select>` on every channel row** — the redundant per-row style control the design removed. | `RaviloConfig.kt` ~L397 `<select … data-ch-style>` | Style chosen **only in the popup** (Display: Logo/Text). |
| 1.2 | S1 | **Brand color is a solid-only `<input type="color">`; gradients are dropped.** | `RaviloConfig.kt` L375 `brandColor?.takeIf { it.startsWith("#") } ?: "#7b6ef0"`, L398 `<input type="color" data-ch-color>` | Preset swatches **+ custom solid/gradient builder**; `brandColor` = CSS color **or** `linear-gradient(…)`. |
| 1.3 | S2 | **"Logo" never means an image** — the editor never sets `logoUrl`; no upload, no asset picker. | `RaviloConfig.kt` channels render: no `logoUrl` control | Logo mode = **pick/upload an image asset** → `logoUrl`; initials only as no-asset fallback. |
| 1.4 | S1 | **No channel-button popup / edit affordance.** Styling is inline; "Edit filter" reopens the workbench for **conditions only**. | `RaviloConfig.kt` L380 `data-ch-edit` → workbench (conditions) | Row opens **one popup** (Display + logo/text + brand fill) via a visible **pencil edit** icon. |
| 1.5 | S2 | **No logo-asset upload/list endpoint**, and `brandColor` validation must accept a gradient. | backend (no asset route); `Models.kt` `brandColor: String?` already permits it | Upload→URL + reuse list; R26 validation accepts solid **or** single `linear-gradient`. |
| 1.6 | S2 | **TV won't render a gradient brand color** — `parseBrandColor` is hex-only, returns null otherwise → falls back to accent. | `ChannelCard.kt` L37–49 (`6 -> …; 8 -> …; else -> return null`) | Parse `linear-gradient(deg,c1,c2)` → `Brush.linearGradient`. (Logo image path **already works**: L121 `RemoteImage(logoUrl)`.) |

**Model is already adequate:** `ChannelConfig(style, brandColor: String?, logoUrl: String?, conditions…)`
and runtime `Channel(logoUrl, style, brandColor)` need **no field changes** — `brandColor` is a free
string, so a gradient fits. The work is editor UI + backend asset endpoint + validation + the TV
gradient parse.

## 2 · Brand mark + asset pack  → tracked by **R37**

| # | Sev | Finding | Code evidence | Design intent |
|---|----|---------|---------------|---------------|
| 2.1 | S1 | **Admin inline jellyfish uses the old off-centre frame** and brittle alignment. | `RaviloConfig.kt` L48 `RAVILO_MARK … viewBox="0 0 100 100" … width:30px…vertical-align:middle` | Recentred `viewBox="12 20 76 76"`; header mark **flex-centred at `em` size** next to "Ravilo TV". |
| 2.2 | S2 | **Launcher/store raster pack** (`ravilo-android` consumes `design/ravilo/assets/**`) predates the recentre — every icon/banner inherits the small, high glyph until regenerated from the corrected master. | repo `design/ravilo/assets/**` (pre-recentre) | Regenerate the whole pack from the recentred master (R37 §B). |
| 2.3 | — | **TV AppBar needs no change** — it shows the **"Ravilo" wordmark only**, no jellyfish path. | `AppBar.kt` L97–99 (Text "Ravilo") | n/a |

## 3 · Broader config-editor divergences (not yet specced)

These are real design↔code differences beyond R36/R37; flagged for a decision before writing a spec.

| # | Sev | Finding | Code evidence | Note |
|---|----|---------|---------------|------|
| 3.1 | S1 | **Hero section is an inline-input row** (item-ID / badge / tagline / Logo checkbox / Show), not the **guided hero builder** (title search + backdrop + badge + live 16:9 preview) the design + R32 §D describe. | `RaviloConfig.kt` L302–309 (`data-hero-id/badge/tagline/logo/enabled`) | The admin hero editor appears to predate the R32 hero builder. Decide: bring the builder to the admin editor, or accept inline. |
| 3.2 | S3 | **"Pair a TV" section exists in code but is absent from the design mock.** | `RaviloConfig.kt` L138 `sect-pair`, `renderPair()` | Design is **behind** here — add a Pair-a-TV section to `ravilo-config.html`, or keep it code-only by intent. |
| 3.3 | S3 | **Channels copy differs** — code: "maps to one Jellyfin filter (network/studio/genre/tag)"; design: workbench/condition-stack framing. | `RaviloConfig.kt` L408 | Align copy once R36 lands. |
| 3.4 | S3 | **Workbench modal is a bespoke `.wb-*` style** vs the design's `.cf-*` builder — functionally parallel, cosmetically divergent (chrome, preview grid). | `Workbench.kt` `injectWorkbenchStyles()` | Optional visual reconciliation; not blocking. |

## 4 · Screen-by-screen pass — results

Diffed each remaining admin screen's code against its design's distinctive features. **Verdict: the admin
app is broadly in sync.** Confirmed present + matching:

| Screen | Code | In-sync features verified |
|--------|------|---------------------------|
| **Library** | `Library.kt` | Audio-track filter panel (lang/title/codec/untagged), **⚙ Add filter** (workbench), **★ Save filter as…**, multi-language search note, active-chip row, URL-addressable filters, infinite scroll. |
| **Movie/Series detail** | `MediaDetail.kt` | Tabs incl. **Seasons & episodes** (TV) / **Tracks & order** (movie) + Artwork + NFO + History; **TMDB id** edit/save; **Find / fix match** modal (Phase 32); **★ Feature in Ravilo** (R32); **Re-pull from Jellyfin** modal + **Re-pull from TMDB**; **Jellyfin field-lock banner**; artwork **asset rail**; `?tab=` URL sync; unified track editor (Phase 27 merge). |
| **Metadata** | `Metadata.kt` | Studios · Networks · Genres · Tags tabs; **JS-tag color dots**; **Fetch missing logos**; `?tab=`. |
| **Settings** | `Settings.kt` | Connections · Library mapping · Scanning (**workers + thread pool**) · Metadata (**fallback language** + NFO/artwork toggles) · Advanced (**Clear all scanned data**). |
| **Activity** | `Activity.kt` | Live console (WS), **category filter bar**, **Errors-only** toggle, **workers chip**, Clear log, resizable console. |
| **Dashboard / Login / Shell** | `Dashboard.kt` etc. | Stats + batch actions; sign-in; sidebar + three-way theme + scan/triage docks. |

## 5 · Presentation deltas (real, low-severity)

Re-checked after a closer read. **The first-pass "Settings is missing sections" finding was a false
positive** — the mock already has both (`sect-seeding`, `sect-notifications`).

| # | Sev | Finding | Code | Mock |
|---|----|---------|------|------|
| 5.1 | — | **Resolved.** Settings parity confirmed: 7 sections both sides (Connections · Library mapping · Scanning · Metadata · Cross-seed · Notifications · Advanced). Only nits: section **id** differs (`sect-crossseed` code vs `sect-seeding` mock), and Notifications presentation differs (mock = chips + mini-toggles; code = webhook URL + per-event toggles + "Send test"). Same event set (scan finished / no TMDB match / write failed / drift). **No action needed.** | `Settings.kt` L157, L209 | `settings.html` L130, L172 |
| 5.2 | S3 | **Detail pagebar:** mock groups actions into dropdown menus (External links ▾, Re-pull ▾, Save split-button); code renders **flat buttons**. Design is the nicer target → **code-adoption** task, not a mock edit. | `MediaDetail.kt` L486–492 | `media.html` L181–205 |
| 5.3 | S3 | **Detail tab component:** mock `.tabs2`+`data-tab`; code `.seg`/`.seg-item`. Cosmetic → code-adoption. | `MediaDetail.kt` L220 | `media.html` L239 |

## 6 · Artwork manager — deep-check **PASSED**

The code's Artwork tab (`MediaDetail.kt` "Phase 47", L1851+) matches the design (`media.html` artwork tab
+ `Artwork Manager.html`) feature-for-feature:
- **Asset rail + inline TMDB candidate gallery** (`art-rail` + `art-gallery`). ✓
- **Resolved-first, never-empty language ladder:** `resolved → no-language → All` (L2016–2023). ✓
  (matches "language defaults to the title's resolved language then falls back").
- **`xx` no-language bucket is its own chip, distinct from "All"** (`artLang`: `""`=All, `"xx"`=no-language;
  chips "All / No language / per-language with counts", L1875, L2055–2066). ✓
- **Prefer textless / with-text, hi-res, sort; stage + Save to disk; dropzone / Upload / Paste-URL** (L1853–1877). ✓
- **Generalised** so the series season-poster + episode-still targets reuse it (L1854). ✓

(Upload accepts JPEG/PNG/WebP, not SVG — correct for photographic artwork, not a gap.) **No gaps.**

## Compose TV (`ravilo-ui`) — state

All design screens have a Compose counterpart (Pairing, Home, Channel, Browse, Search, Movie/Series
detail, Player, **ProfilePicker** = R18). R34/R35 TV sizing/hero framing already shipped (STATUS).
`AppBar` is **wordmark-only** (no jellyfish → R37 doesn't touch it). The only outstanding TV item is the
**channel gradient** (`ChannelCard.parseBrandColor` hex-only) — folded into **R36 §F4**.

## Recommended sequencing
1. **R37** (mechanical: recentre master, regenerate assets, fix the one admin inline mark) — low risk, unblocks the visible header bug.
2. **R36** (editor popup + logo upload endpoint + brandColor gradient validation + TV `parseBrandColor`).
3. Decide **§3.1 (hero builder)** and **§3.2 (Pair-a-TV in the mock)**; spec whichever you keep.
4. Optional **code-adoption polish §5.2/§5.3** (detail pagebar menus + tab classes) — small frontend edits, no spec needed.

_Resolved this audit: §5.1 (Settings parity — false alarm) and §6 (artwork manager — passed). No mock edits required._
