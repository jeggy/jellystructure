# Design Fidelity Audit & Fix (2026-06-22)

**Status:** Planned — fixes not yet implemented.

Cross-cutting audit of discrepancies between the HTML/CSS **design mockups** (`design/app/`,
`design/ravilo/`) and the **shipped product** (admin Kotlin/WASM `src/wasmJsMain/…/ui/`, backend
`src/linuxX64Main/…`, Ravilo Compose `ravilo-ui/`). Produced by a parallel screen-by-screen review
after Phase 47 + Ravilo R32 landed.

Mockups are the *visual/behavioural target*; on any conflict the relevant spec wins
([`constitution.md`](constitution.md), the phase archives, and `specs/ravilo/`). Items are bucketed by
priority. **P0** = outright defects (several introduced by the R32 work in this cycle). **P1** =
spec-fidelity gaps where the mockup/spec leads the code. **P2** = decisions / mockup drift. Each item
carries `file:line` anchors and a concrete fix. Verify line numbers against the tree before editing —
they were true at the commit noted in [`../STATUS.md`](../STATUS.md).

---

## P0 — Defects to fix

### P0-1 — R32 workbench-built channels & rows are dropped on save *(regression, this cycle)*
**Symptom:** On the Ravilo config screen, a channel/row created with "⚙ Build with workbench"
re-renders as an empty GENRE typed filter and loses its conditions on the next Save.
**Root cause:** `ChannelConfig.kindAndValue()` and `collectConfig()` only read the legacy
`filterNetwork/Studio/Genre/Tag` + inline fields; they never read or re-emit `match`/`conditions`
(`src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt:336`, `:631`, `:658`).
**Fix:**
- In `collectConfig()`, preserve `match` + `conditions` for every channel and row: build each
  `ChannelConfig`/`RowConfig` from the *existing* `currentConfig` entry (copy) and overlay only the
  inline-edited legacy fields, rather than constructing a fresh object that omits the new fields.
- A row/channel that has a non-empty `conditions` stack should render as **read-only "N conditions ·
  match ALL/ANY"** with an **Edit** button (see P1-1), not as an editable legacy typed filter.
- Regression-guard: round-trip a workbench-built channel through `collectConfig()` → `RaviloApi.putConfig`
  → reload and assert `conditions` survive.

### P0-2 — Hero-height 30–100% only half-propagated *(regression, this cycle)*
**Symptom:** Three surfaces clamp hero height to three different ranges.
**Root cause:** the model/service moved to 30–100 (`shared/.../tv/Models.kt`,
`RaviloConfigService.normalize`), but the config slider is still `max="70"` + `coerceIn(30,70)`
(`RaviloConfig.kt:309`, `:597`) and the TV app clamps `20..80` (`ravilo-ui/.../ui/screens/HomeScreen.kt:93`).
**Fix:** set the slider `max="100"` and both `coerceIn(30, 100)`; align the Compose `HomeScreen`
clamp to `30..100`. R32 §F1 is the source of truth.

### P0-3 — Auto-advance default 7s is unreachable *(regression, this cycle)*
**Symptom:** Model default is 7s but the editor can never represent it; saving snaps to 6s.
**Root cause:** the config dropdown only offers Off/4/6/8/10 and `collectConfig` falls back to 6
(`RaviloConfig.kt:36` `AUTO_ADVANCE_OPTIONS`, `:680`).
**Fix:** add a `7 to "7 s"` option (make it the listed default) and have `collectConfig` default to 7.

### P0-4 — `TileShape` missing `SQUARE`
**Symptom:** R32 §F1 + the mockups specify three tile shapes (Standard poster / Wide landscape /
**Square**); only two exist.
**Root cause:** `enum class TileShape { POSTER, LANDSCAPE }` (`shared/.../tv/Models.kt:11`).
**Fix:** add `SQUARE`; add the option to the config tile-shape picker (`RaviloConfig.kt:522`); handle
`SQUARE` aspect in the Compose tile renderer (`ravilo-ui/.../components/MediaTile.kt` or equivalent).

### P0-5 — Activity "Overall" progress bar never updates
**Symptom:** The overall scan bar always reads empty.
**Root cause:** `#ov-bar` is hardcoded `width:0%` and no handler sets it; the `progress` WS event only
updates the filename (`src/wasmJsMain/kotlin/dev/jellystructure/ui/Activity.kt:80`, `:300`).
**Fix:** on each `progress`/`item_scanned` event compute `current/total` (from scan status or the
event payload) and set `#ov-bar` width; reset to 0 on `started`, 100 on `finished`.

### P0-6 — Shell palette/badge reference the removed Triage page *(stale since Phase 27)*
**Symptom:** Command-palette "Go to Triage queue" navigates to `/triage` (deleted in Phase 27); the
`triage-count-badge` is never rendered.
**Root cause:** `Shell.kt:175` navigates to `/triage`; the badge is only emitted for a `NAV` entry
with `href == "/triage"`, which no longer exists (`Shell.kt:50-58`, `:127-132`, `:551`).
**Fix:** drop the `/triage` palette command (the floating Triage dock already covers it), or repoint it
to open the dock; delete the dead `triage-count-badge` branch or move the count onto the dock.

---

## P1 — Spec-fidelity gaps (mockup / spec leads the code)

### P1-1 — Ravilo config screen: workbench is not the single filter UI (R16/R28/R32)
- Channels/rows still use the legacy **single typed-filter dropdown as the primary editor**, with the
  workbench only as a secondary button (`RaviloConfig.kt:344`, `:416`). R32 invariant: the workbench is
  the *single* filter UI for custom channels/rows. **Fix:** make "Build with workbench" the primary
  create path; keep legacy fields read-only for back-compat display only.
- **No click-to-edit-prefilled reopening** of the builder for existing items (R32 §C3). **Fix:** add an
  "Edit filter" affordance that calls `openWorkbench(... initialConds = <decoded from config>)` and
  writes the result back to that entry.
- **No hero builder on the config screen** — "+ Add hero" is a bare item-ID text box; `HeroConfig`
  `badge/tagline/clearlogoOverlay` are uneditable here (`RaviloConfig.kt:279`). The detail-page hero
  builder (§E3) already exists and can be factored into a shared helper. **Fix:** reuse it from the
  config "+ Add hero" / hero-row edit.
- Channel workbench `onApply` ignores the Include movies/series choice (`RaviloConfig.kt:390`). **Fix:**
  store it (channels have no `mediaKind` field today — either add one or fold an Include condition).

### P1-2 — Ravilo TV (Compose) vs prototype/specs
- **[R10] Hero has no Play / More Info / + My List buttons and no synopsis** — Enter only opens detail
  (`ravilo-ui/.../components/HeroCarousel.kt:132-203`). **Fix:** add the three actions + synopsis line.
- **[R19] Hardcoded English** bypasses the locale table in many runtime strings (player "Audio & Subs"/
  "Next"/"UP NEXT"/"NOW PLAYING", picker tabs, search "Clear/Suggestions/N results", browse "All/N
  titles", settings headers). **Fix:** route every user-facing string through `Strings`/`str(...)`.
- **[R15]** Settings missing the **autoplay-next-episode** toggle. **[R14]** no **trickplay** scrub
  thumbnail; player episode-rail cards render no still image despite carrying `stillUrl`; the next-up
  card shows a hardcoded "Episode" instead of the real next title
  (`PlayerScreen.kt`, `SeriesDetailScreen.kt:90`).
- **Lower:** no Trailer button on detail; channel header lacks the brand-logo tile + subtitle; stream
  pill omits codec/resolution; "Kids" profile badge defined (`Strings.kt:38`) but never rendered.

### P1-3 — Admin: Media / Series detail
- **Genres are read-only** — design has removable `✕` chips + a `＋` add chip (`design/app/media.html:318`,
  `series.html:164`); code emits plain chips (`MediaDetail.kt:129`). **Fix:** make genres editable (they
  already flow into the NFO/metadata PATCH).
- **No season selector** on the Episodes tab — code stacks every season; design shows one season via a
  seg/dropdown (`series.html:178`). **Fix:** add a season segmented control filtering the episode list.
- **Series left-rail cards vanish on non-Overview tabs** — poster/identity/series-language are nested in
  `tab-overview` (`MediaDetail.kt:358`); design keeps them outside the tab panels so they persist.
  **Fix:** lift the left rail out of the tab container.
- **Drift has only a banner, no per-field review modal** (`MediaDetail.kt:1603`); design has a
  field-by-field modal (`media.html:508`). See also P2-1 (direction). **Fix:** add the review modal.
- **Multiple-default-audio warning (Phase 21) not surfaced** on episodes/editor
  (`TrackEditor.kt:364`, `series.html:311`). **Fix:** show the explicit red explainer + row badge.
- Lock-banner remediation steps abbreviated vs `media.html:208` (chips + step-by-step + dismiss).

### P1-4 — Admin: Library / Metadata / Settings / Shell
- **Library [high]:** standalone Studio/Network/Genre/Tags/Audio popovers still ship *alongside* the new
  ⚙ Add filter (`Library.kt:127-170`); R32 folds them into the workbench. **Fix:** remove the standalone
  audio popover (and ideally the meta popovers), routing all filtering through the workbench. Surface the
  multi-language-search explainer note (Phase 29).
- **Metadata:** tab switches don't update the URL (Phase 28 inbound-only) (`Metadata.kt:62-86`); JS-tag
  **description is fetched but never rendered** (`Metadata.kt:190-196`); add the Networks "no TMDB
  network search" explainer.
- **Settings:** scheduled scan is interval-hours, not the design's **cron string** (`settings.html:108`
  vs `Settings.kt:123`); health-check failures don't bubble into nav badges / a danger top-button state /
  scroll-to-first-failure (`settings.html:257` vs `Settings.kt:526`); the danger zone sits mid-page
  (section order drift). **Fix:** decide cron vs interval (cron is the design); add health-failure
  surfacing; reorder so Advanced is last.
- **Shell:** Triage dock shows only a count + Prev/Next — add the item **title + "what's wrong"
  sub-line + "Open & fix" button + close/hide** affordance (`Shell.kt:355-396` vs `app-shell.js:180-220`);
  command palette omits attention-queue items, icons, and Up/Down selection nav.
- **Login:** no live "connected to jellyfin:… · online" status row; `Setup.kt` first-run flow has no
  mockup (add one to `design/app/` for parity).

---

## P2 — Decisions / mockup drift

### P2-1 — Drift direction is inverted (needs a product decision)
Design treats Jellystructure/NFO as source of truth and offers **"Re-assert NFO → Jellyfin"**
(`media.html:226`). Code instead instructs **"Re-pull from Jellyfin to absorb its version"**
(`MediaDetail.kt:1617`) — the opposite direction. [Phase 33](archive/phase-33-jellyfin-nfo-drift.md)
("re-assert") suggests the design is correct. **Decide**, then align code (and the P1-3 review modal).

### P2-2 — Section/nav ordering drift (low)
Settings section order and Shell sidebar nav order/grouping differ from the mockups (`Settings.kt:48`,
`Shell.kt:50` vs `settings.html`, `app-shell.js:35`). Cosmetic; fold into P1 fixes when touching those
files, or update the mockups to match if the code order is preferred.

---

## Non-issues (code is correct / mockup is stale — no action)
- Code is intentionally **richer** than the static mockups: real scan lifecycle + WebSocket, working
  log filters + clear, tag edit/delete, per-library scan/push, the artwork manager generalized to
  series, full per-field dirty-diff, URL-addressable Library filters, real metadata/facet APIs.
- Ravilo config "live `Ravilo TV.html` iframe" → code uses a **schematic preview**, which R28/R32 §31
  actually specify; the iframe is design-only.
- `series-simpsons.html`, `Ravilo Create Flows.html`, `Artwork Manager.html`, and the logo/"Directions"/
  "Android TV Assets" files are **explorations**, not 1:1 screens — not parity targets.

---

## Suggested sequencing
1. **P0-1…P0-4** (R32 polish — this cycle's regressions) as one commit; **P0-5, P0-6** (pre-existing) as
   a second.
2. **P1-1** (config-screen workbench-first + edit + hero builder) — completes the R32 intent; could be a
   Ravilo phase **R33** if formalised.
3. **P1-2** (Ravilo TV: hero actions + R19 localization pass + R14/R15 player/settings gaps).
4. **P1-3 / P1-4** (admin detail + Library/Metadata/Settings/Shell) — group per file.
5. Resolve **P2-1** (drift direction) before building the P1-3 drift modal.
