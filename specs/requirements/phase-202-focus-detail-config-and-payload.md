# Phase 202 — Own the focus detail: one resolved mode, one delay, one payload

> The jellystructure half of **R240**. A household decides what a highlighted title says before it is
> opened — a status line, an opening row, or nothing — and how still the remote has to be before it is
> said. Ravilo renders that decision and never makes it: **the server resolves which of the two
> directions applies**, so no client anywhere re-implements the supersession rule.

**Status:** see `STATUS.md` (the sole source of truth — this file's body no longer encodes status).
Written 2026-09-12, not dev-reviewed. Backend built same-session: `RaviloConfig` carries the three
fields, `GET /api/tv/config`/`GET /api/tv/home` both carry the resolved mode + delay, and Home
content-row `MediaCard`s carry `FocusDetailFacts`. **The Ravilo client half (R240) is not built** — L/J
still exist only in the mockups (`design/app/ravilo-config.html`, `design/ravilo/*`).

**Numbering:** verified against `main` on 2026-09-12 — admin phases are taken through **201** and Ravilo
through **R239**, so this pair is **202 / R240**. Next free: 203 / R241.

Design: `design/ravilo/Focus Detail - Round 2 Directions.html` (six non-overlay directions, comparison
table; **L** and **J** chosen) and `design/ravilo/Focus Detail - Directions.html` (round 1, all six
retired). Admin surface: `design/app/ravilo-config.html`. Viewer half: **R240**.

## Current state

A focused tile says nothing. The poster is highlighted and the title sits under it; every other fact
about the title — year, runtime, age rating, IMDb, languages, where you left off, what it is about —
exists only after the viewer commits to opening the detail page. Two rounds of design settled on two
non-overlay answers (a foot **line**, or the **row opening** in place), both of which need the same
three things from the server and neither of which Ravilo can compose on its own: **config is
server-owned, per Jellyfin user, and synced across devices** (constitution §3), and **the TV renders
server-pushed state** (§4).

Round 1's four directions are retired, and that decision is load-bearing here: the plate, the hero
mirror, the tile unfold and the ambience wash each needed a backdrop per focused title, so dropping
them took an image URL — and the fetch behind it — back out of this payload.

## Goal

Three fields of config, one resolved mode on the wire, and a per-title fact set small enough to ride the
feed the viewer is already waiting for.

## Functional requirements

**FR-202-1 — Three fields on `RaviloConfig`, per user.** Add to the user's config, server-owned and
synced across that user's devices like every other layout field (constitution §3):

| field | type | default | meaning |
| --- | --- | --- | --- |
| `focusDetailLine` | `Boolean` | `true` | L — the foot status line is configured |
| `focusDetailRowOpen` | `Boolean` | `false` | J — the focused row opens in place |
| `focusDetailDelayMs` | `Int` | `170` | how still the D-pad must be before either appears |

Read by `GET /api/tv/config` and `GET /api/tv/home`, written by the jellystructure Ravilo config screen
(R16). **No viewer-facing setting anywhere in Ravilo** — R216's invariant stands; this is household
configuration, not a preference the TV offers.

**FR-202-2 — The server resolves the mode; the client never implements supersession.** J states every
fact L does, so both may never render at once. The payload carries a resolved value, not the two
booleans:

```
focusDetail: "none" | "line" | "rowOpen"
```

`rowOpen` wins whenever `focusDetailRowOpen` is true, regardless of `focusDetailLine`. The two booleans
remain **admin-side state** precisely so the config screen can say *"On · superseded"* rather than
silently disagreeing with the screen (FR-202-6) — but that distinction is of no interest to a client,
and a client that received both would have to own the precedence rule. ⚠ **The mockup currently resolves
this client-side** (`design/ravilo/ravilo-focus.js` reads both booleans and prefers `rowOpen`); that is a
known deviation from this requirement and the build should follow the spec, not the mockup.

**FR-202-3 — The delay is any non-negative whole number of milliseconds.** (Revised 2026-09-12, owner
call: the 0–600 range and the 10 ms step are both dropped.) `focusDetailDelayMs` accepts **any integer
≥ 0** — `10` and `10000` are equally valid settings, as is anything between, and nothing is snapped to
a step. `0` means *show it immediately* and must remain reachable — it is the right setting for a
household that never holds a direction down. A few seconds is a deliberate setting too: it makes the
detail something a viewer gets only by stopping on purpose. Only a value that is **not a non-negative
number** (a hand-edited row, an older client, `null`, junk) resolves to the **default 170** — never an
error. The control is a **number field, not a slider**: a slider cannot express the range, and the
range is the point. The client is not required to re-clamp, must not invent its own floor, and must not
impose a ceiling of its own.

**FR-202-4 — A config change reaches an open TV without a restart.** Flipping either switch or moving
the delay is a change the household expects to see on the screen in front of them. The resolved
`focusDetail` and `focusDetailDelayMs` must therefore be readable by a running client on the next
config read, and the TV must apply them to the currently focused tile (R240 FR-R240-13) rather than at
next launch. ⚠ **How** a running TV learns of the change is genuinely open — see open question 2.

**FR-202-5 — The payload carries facts, not sentences.** Per title, the server supplies exactly what the
two directions draw, and nothing shaped like presentation:

`year` · `badge` (format) · season + episode counts for a series · `runtimeMinutes` · normalised age
rating (region + code + tier, per phase 155) · `imdbRating` (phase 131/158) · the **full** genre list in
TMDB order (R221) · audio and subtitle language codes with an overflow count · resume state
(`nextEpisode` / `resumePercent`) · `overview`.

Formatting stays with the client because Ravilo owns its own en/da/fo strings (R240 FR-R240-11) — *"2
Seasons · 18 episodes"* and *"12 min left"* are assembled from these numbers against the client's string
table, not shipped as English. **No artwork, no backdrop, no logo, no tagline, no image URL of any
kind.** Render-never-compute governs *decisions*, not translation: the client may format, but it may
never decide what is true.

**FR-202-6 — The admin card states the whole rule, including the part that is off.** Preferences →
**Focus detail** carries the three controls, scoped `Home rows`, plus: each switch's own state; **`On ·
superseded`** for the line while the row-opens switch is on; the delay's readout in ms and which
direction it currently governs; and, on the row-opens switch, the reason it ships off — it moves the
row's height and its tiles' positions on every focus move, which is the reflow **invariant 11** exists
to protect, unmeasured on the living-room BRAVIA. The card is **product-only**: the mockup's own
preview-chrome FOCUS picker and `?focus=`/`?dwell=` parameters (`design/ravilo/Ravilo TV.html`) write
the same key so the two never disagree, but they are scaffolding and ship nowhere.

**FR-202-7 — Cost is stated before it is spent.** ~**0.9 KB per title** of additional text, ~38 KB for a
42-title home, **no image fetch**. Whichever delivery path open question 1 settles on, the resolved
`focusDetail` must be readable by the client **before** the first tile takes focus, so a viewer never
sees the first focus of a session behave differently from the second.

## Non-goals

- **No viewer setting, no dismissal, no "don't show this again"** (R216).
- **No per-channel, per-row or per-library scoping.** One household answer for Home. R233's lesson —
  two surfaces disagreeing about what page they are on — argues against a second axis here.
- **No new route.** This is three config fields and a fact set on an existing payload.
- **No Ravilo-side persistence.** The TV caches nothing about focus detail beyond the payload it holds.
- **Nothing outside Home content rows** — not the channel rail, hero, browse, search, Discover or Live
  TV. Those rows are not library items in this shape.
- **No artwork in the payload**, and no re-litigating round 1's directions that needed it.

## Acceptance

1. A user with defaults (`line: true`, `rowOpen: false`, `delay: 170`) gets `focusDetail: "line"` and
   `focusDetailDelayMs: 170` from both `/api/tv/config` and `/api/tv/home`.
2. Turning the row-opens switch on yields `focusDetail: "rowOpen"` **without** the line switch changing
   value in the admin — and the admin shows the line as `On · superseded`.
3. Turning both off yields `focusDetail: "none"` and the per-title fact set is **omitted**, not sent and
   ignored.
4. `focusDetailDelayMs` is accepted at 0, at 137, at 600 and at 10000 — none of them snapped to a step;
   −1 and a non-numeric value read back as 170.
5. Saving from the config screen is visible on that user's second TV without either device restarting.
6. A 42-title home feed grows by ≲40 KB with focus detail on, and issues no additional image request.

## Source references

- `design/app/ravilo-config.html` — the card (`#sect-focus`), the three controls and the mocked
  config key `js-ravilo-focusdetail`.
- `design/ravilo/ravilo-app.js` — `fieldsFor()`, the seam that resolves one object per item; the field
  list in FR-202-5 is exactly its output.
- `specs/ravilo/constitution.md` §3 (config is server-owned per user), §4 (server-pushed state),
  invariant 11 (AOT / D-pad smoothness).
- `specs/ravilo/plan.md` — `GET /api/tv/config`, `GET /api/tv/home`, `RaviloConfig` ownership.
- Related: **R216** (no viewer-visible setting), **R221** (full genre list), **phase 155** (age-rating
  normalisation), **phase 131/158** (IMDb ratings), **R233** (one scope per surface).

## Open questions

1. **Does the text ride `/api/tv/home`, or a per-title fetch on focus?** Still the dev team's call, and
   deliberately not decided in a design phase. The feed path costs ~38 KB on a screen the viewer is
   already waiting for (R210–R213 spent four phases on cold start); the per-focus path costs a request
   per focus move on a D-pad, which is the same shape of workload invariant 11 exists for. Phase 182's
   measurement is a reason to distrust the second option under load: `/api/tv/home` already degrades
   ~120× at p50 during a scan.
2. **How does a running TV learn its config changed?** FR-202-4 requires the change to land without a
   restart, but there is no push channel for config today — the WS protocol carries playback state, and
   phase 181 found the Jellyfin-based realtime ingest has delivered nothing since phase 165. Polling on
   screen-resume may be enough for a household setting; it needs deciding, not assuming.
3. **Should `rowOpen` be per device, not per user?** J's cost is a *device* property — a 2019 BRAVIA and
   a Pixel 8 are not the same machine — but config is per user by constitution §3, so one household
   answer applies to a slow TV and a fast phone alike. Resolving this properly probably means a
   device-class capability the server consults, not a second config surface.
4. **Does the delay belong to focus detail, or to focus?** It currently governs only this surface. If it
   reads well, the hero's auto-advance pause and browse's focus reveals are the obvious next consumers —
   at which point it is a navigation setting with a different name and a different home.
