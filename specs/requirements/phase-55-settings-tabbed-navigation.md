# Phase 55 — Settings as URL-addressable tabs (FR-ST1)

## Goal

The Settings page grew past the point where a single long scroll works well: Connections,
Radarr/Sonarr (Phase 54), Library mapping, Scanning, Metadata, Cross-seed safety,
Notifications and Advanced were all stacked, and the left rail only smooth-scrolled +
highlighted via an `IntersectionObserver`. Convert it to **one panel visible at a time**,
selected by the left rail and **addressable in the URL** — reusing the Phase 28 Router
contract already used by detail/metadata pages.

Net effect: shorter page, faster orientation, deep-linkable/refresh-stable
(`/settings?tab=…`), and one fewer scroll-spy moving part.

## Tab consolidation (8 sections → 6 tabs)

| Tab (`?tab=`)   | Sections it contains              | Section ids                   |
|-----------------|-----------------------------------|-------------------------------|
| `connections`   | Connections (TMDB, Jellyfin)      | `sect-connections`            |
| `libraries`     | Library mapping **+** Scanning    | `sect-library`, `sect-scanning` |
| `metadata`      | Metadata                          | `sect-metadata`               |
| `downloads`     | Radarr/Sonarr **+** Cross-seed    | `sect-arr`, `sect-seeding`    |
| `notifications` | Notifications                     | `sect-notifications`          |
| `advanced`      | Advanced (danger zone)            | `sect-advanced`               |

Rationale for the two merges: **Libraries** groups everything about *what gets scanned and
from where*; **Download tools** groups the two optional torrent/*arr integrations that are
about *staying in sync with the acquisition side*. `connections` is the default tab.

## URL & navigation contract (constitution §Real-Time UI Contract / Phase 28)

- Tab state lives in a **query param** `?tab=<name>`, written with
  `Router.updateQuery(replace = true)` (`history.replaceState`) — **no `hashchange`**, no
  history entry per tab, no page re-render. Only the active panel toggles in the DOM.
- On load (and on refresh/deep-link), the active tab is reconstructed from `?tab=`; an
  unknown/absent value falls back to `connections`.
- Back/Forward navigate **between pages**, not between tabs (consistent with detail-page tabs).

## DOM / CSS

- Each section card keeps its `id` and gains `data-tab="<tab>"`. Hidden by default
  (`.set-section[data-tab] { display:none }`); the active tab's sections get `.tab-show`
  (`display:block`). The `.col` `gap` continues to space the two-card tabs (Libraries,
  Download tools) with no extra wrappers.
- Left rail items become `data-tab` triggers (replacing `data-goto`); `.active` follows the
  current tab instead of the scroll position. The `IntersectionObserver` scroll-spy is
  **removed**.
- The sticky pagebar (`#set-pagebar`) and the rail's `position: sticky; top: 88px` are
  unchanged (constitution invariant).

## Health-check badges (Phase 35) — now per tab

`runHealthCheck()` still keys failures by **section id**, but bubbles them up to **tab**
badges via a `sectionTab` map (e.g. `sect-seeding → downloads`). The top **Test connections**
button keeps its total-failure danger state; when a check fails, the page **switches to the
offending tab** (instead of the old smooth-scroll-to-section). Per-tab `.nav-badge` counts are
the sum of their sections' failures.

## Settings.kt changes

- Replace the section-nav builder + `IntersectionObserver` with a `showTab(name)` that toggles
  `.active` on rail items and `.tab-show` on sections, and writes `?tab=` via the Router.
- Rail markup: 6 items with `data-tab`; badges keyed by tab name.
- `runHealthCheck()`: add the `sectionTab` aggregation for `.nav-badge`; on `scrollToFail`,
  call `showTab(sectionTab[firstFailingSection])`.
- All existing controls (toggles, test chips, qBittorrent reveal, Radarr/Sonarr reveal,
  notifications, clear-data) are unchanged — they just live inside whichever tab now owns them.

## Invariants preserved

- Sticky pagebar + rail offsets unchanged.
- Same Router/`replaceState` tab contract as detail & metadata pages (Phase 28) — no new
  navigation idiom.
- Health-check semantics unchanged; only the **presentation** of failures moves from
  scroll-to-section to switch-to-tab + per-tab badge.
- No config-shape change (pure UI/navigation phase).

## Mockup

`design/app/settings.html` — left rail is 6 `data-tab` items; each `.set-section` carries
`data-tab` and is shown via `.tab-show`; `showTab()` reads/writes `?tab=` with
`history.replaceState`; health-check badges aggregate per tab.
