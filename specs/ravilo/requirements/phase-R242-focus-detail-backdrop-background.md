# Phase R242 — J's own backdrop: the opened row's picture fills the screen

> A refinement of **R240**'s direction **J** (the row opens). Today J tells you everything about the
> focused title except what it looks like — the panel is text on a plain `--bg`. This phase lets the
> title's own backdrop stand behind the *whole screen* for as long as that row stays open, fading in
> when it opens and back out when it closes. **L is untouched** — it stays the facts-only floor R240's
> own history exists to protect.

## Status

`✓ Built` — written 2026-09-14, not dev-reviewed, not device-tested. Built into the design mockup the
same day (`design/ravilo/ravilo-focus.js`, `ravilo.css`, `ravilo-app.js`), and into the real Compose
app the same day (`ravilo-ui/.../components/FocusDetailBackdrop.kt`, wired into `HomeScreen.kt`,
motion constants in `theme/Motion.kt`) — `:ravilo-ui:compileCommonMainKotlinMetadata` and
`:ravilo-ui:compileDebugKotlinAndroid` both clean.

**Numbering:** verified against `STATUS.md` on 2026-09-14 — Ravilo is taken through **R241**, admin
through **209**. This is Ravilo-only (no backend/DTO change — see FR-R242-2), so it takes the next
free Ravilo number, **R242**, with no admin-side pair.

Design: builds directly on `design/ravilo/Focus Detail - Round 2 Directions.html` (J, unchanged) and
the shipped mockup surface for J, `design/ravilo/ravilo-focus.js` + `ravilo.css`.

## Current state

J (`specs/ravilo/requirements/phase-R240-focus-detail-on-home-rows.md`) opens the focused row in place:
the tile grows, a panel of facts appears beside it, the row band holds its height. All of that plays
out on the same flat `--bg` colour every other screen uses. The room doesn't change, only the row does.

Round 1 of this whole feature (`Focus Detail - Directions.html`) tried exactly this idea twice —
direction **B** mirrored the focused tile into the hero, direction **D** washed the screen's ambient
colour from it — and the owner rejected both, and Phase 202 recorded a hard non-goal: *"No artwork in
the payload, and no re-litigating round 1's directions that needed it."*

**That non-goal is narrower than it reads, and this phase leans on the gap.** 202's own FR-202-5 is
about `FocusDetailFacts` — the per-item fact set J and L render from — and it is right to keep that
field artwork-free; nothing here changes it. But `FocusDetailFacts` was never the only thing riding a
Home row's `MediaCard`. Every `MediaCard`, hero or content-row alike, already carries `backdropUrl`
(`HomeFeedService.toMediaCard`, via `RaviloImageUrl.backdrop`) — the same field the Home hero has used
since before focus detail existed. Round 1's B and D were rejected because they needed a backdrop
fetch that **didn't exist for a row item at all**; today it already does, for every item, on the
payload the client already holds. This phase spends nothing new on the wire — it renders a field
that's already there, for the one item that's already open, at the one moment it already animates.

## Goal

While J's panel is open, the title's own backdrop fills the entire screen behind it — app bar, other
rows, everything — scrimmed for legibility, fading in on open and back to plain `--bg` on close. Moving
to a different open tile in the same row crossfades directly between the two backdrops. Nothing about
L changes, nothing about J's own layout/scroll/reflow mechanics (R240 FR-R240-7 through -10) changes,
and nothing rides the wire that wasn't already there.

## Functional requirements

**FR-R242-1 — J only, same scope as R240.** The backdrop renders exactly when and where the row-open
panel renders: Home content rows, only while the resolved mode is `rowOpen`. It never appears under
`line` or `none`, never on the hero, channel rail, "On now" row, nav bar, or the "→ See all" tile
(R240 FR-R240-1's list, inherited verbatim). Reduced-motion sessions already downgrade `rowOpen` to
`line` before this code ever runs (R240's `effectiveFocusDetailMode`) — this phase adds no separate
reduced-motion handling because there is nothing left for it to gate.

**FR-R242-2 — No new payload, no new DTO, no backend change.** The image is `MediaCard.backdropUrl`,
already present on the exact card that opened — the same field and the same proxied Jellyfin backdrop
URL (`/api/tv/image/{id}/backdrop`) the Home hero already renders via `RemoteImage`. If a card's
`backdropUrl` is null (Jellyfin holds no backdrop for that item), the layer shows that title's
placeholder gradient instead — the same fallback the hero and detail page already use — never a
broken-image state, never reserved space, never an error.

**FR-R242-3 — Two speeds, not one.** The panel and the grown tile keep R240's own `.22s` tween
unchanged. The backdrop is a separate, slower fade (mock: `.5s`–`.6s`) that starts the instant the row
commits to opening (same trigger as `openRow()`, not gated behind FR-R240-9's post-growth reveal
callback — the picture is atmosphere, not a fact that has to wait for the row to finish measuring
itself). The panel snaps into its slot; the room around it catches up a beat later.

**FR-R242-4 — Full screen, fixed to the viewport.** The layer fills the entire frame — behind the app
bar, behind every row above and below the open one — not clipped to the open row's own band, and not
scrolled by `.screen-scroll`: it stays put while the list scrolls under it, the way a hero would if
Home had no hero at all.

**FR-R242-5 — A hop crossfades, it never blanks.** R240 FR-R240-10 lets focus move laterally from one
open tile straight to another in the same row (two panels mid-tween at once, one opening, one
closing). The backdrop follows the same shape: the new title's image crossfades in directly against
whatever was already showing, never dropping to `--bg` in between. In the mockup this is two stacked
layers swapping which one carries `.on`, the same idiom `.hero-slide` already uses for the hero
carousel.

**FR-R242-6 — Closing fades it out, all the way to plain `--bg`.** Whatever already clears L today —
`FD.clear()`, called on every view change and on every tile that fails R240 FR-R240-1's eligibility
check — clears this too. Turning the row-opens switch off, leaving Home, or focus landing anywhere
J doesn't reach all fade the backdrop back to the screen's ordinary background, not just remove the
panel.

**FR-R242-7 — A scrim, not a photo behind glass.** The image alone is not legible background for row
titles, badges, and panel text wherever they happen to fall across it. A dark scrim rides over the
image at all times it's visible — the same idiom as the existing `hero-scrim`/`ddt-scrim` (a gradient
darkening tuned for whatever sits on top of the image), but a different shape: those fade left-to-right
for a fixed left-aligned hero text block, while this backdrop sits behind content spanning the whole
width, so the mockup uses a plain top/bottom darkening instead. Noir goes darker still, matching the
skin's existing preference for less colour, not less picture — this is the title's own backdrop, not
an accent tint, so Noir keeps the image; only the scrim strengthens.

**FR-R242-8 — No new prefetch.** The backdrop is requested only for the item that actually opens, only
at the moment it opens — never ambiently for a row's other items, never during the dwell before a hop
resolves, never for a title that's merely scrolled past. If the image hasn't finished loading by the
time the panel and tile finish their own tween, the panel and facts appear on schedule regardless — the
scrim (over plain `--bg`) holds the screen until the backdrop is ready, then crossfades in. The
backdrop's own network latency may never delay or visibly gate anything else, matching the "no focus
stops" rule R240 already holds itself to for the row's own opening.

## Non-goals

- **No revival of round 1's B (hero mirror), C (tile unfold) or D (ambience wash).** Those are still
  retired. This phase is a rendering decision inside J alone — L stays exactly as R240 shipped it,
  facts-only, no artwork, and nothing here reopens that question.
- **No new admin/config field.** This inherits the existing `focusDetailRowOpen` switch (Phase 202);
  there is no separate on/off for the backdrop. (See open question 4 — a household that wants J's facts
  without the backdrop has no way to ask for that yet.)
- **No blur, parallax, or Ken-Burns motion on the image itself.** A plain crossfade only. A moving
  background behind an already-reflowing row is exactly the kind of compounding cost invariant 11
  exists to keep in view — out of scope here, not rejected outright.
- **No change to R240's own layout, scroll-target, or two-panel mechanics** (FR-R240-7/8/9/10). This
  phase adds a background layer underneath all of it and touches nothing else.
- **No prefetching ahead of the dwell.** See FR-R242-8 and open question 2.

## Acceptance

1. Focusing a Home content-row tile with J active, and holding still past the configured dwell, fills
   the entire screen with that title's own backdrop, scrimmed for legibility, while the row's panel
   opens on its own (faster) clock.
2. Moving along the *same* open row to a different tile crossfades directly to the new title's
   backdrop — the screen never drops to plain `--bg` in between.
3. Moving focus to the hero, the channel rail, a row where J is inactive, or navigating off Home
   entirely fades the backdrop back to plain `--bg` — the same moment the panel/line would clear.
4. A title with no backdrop art shows its placeholder gradient (or plain `--bg`) instead of a
   broken-image state, with no layout shift and no error.
5. Turning the row-opens switch off removes the backdrop along with the panel; L continues to show
   facts only, and the backdrop never appears while L (not J) is the resolved mode.
6. A reduced-motion session, already downgraded to L by R240, never shows this backdrop.
7. No additional network request appears anywhere the row-open panel doesn't already appear (channel
   rail, hero, browse, search, Discover, Live TV, any non-Home view) — confirm against the network
   panel that only the single opened item's own backdrop is requested, once, at open.

## Source references

- `design/ravilo/ravilo-focus.js` — `openRow`/`closeRow`/`clear`/`apply` (this phase's actual home in
  the mockup: `showBg`/`hideBg`/`bgLayer`).
- `design/ravilo/ravilo.css` — `.jbg`/`.jbg-img`/`.jbg-scrim`; the same dark-gradient-over-backdrop
  idiom as `.hero-scrim`/`.ddt-scrim`, reshaped from their left-to-right fade (built for a fixed
  left-aligned hero text block) to a top/bottom fade (this backdrop sits behind full-width content).
- `design/ravilo/ravilo-app.js` — `fieldsFor()` is unchanged; a new `backdropFor` seam is passed into
  `initRaviloFocus` resolving to `R.artFor(item)`, kept deliberately separate from `fieldsFor` so
  FR-202-5's fact/artwork split stays visible in the code, not just in prose.
- `design/ravilo/ravilo-data.js` — `artFor()` (existing; already used by the hero and detail page).
- `specs/ravilo/requirements/phase-R240-focus-detail-on-home-rows.md` — the mechanism this phase rides:
  scope (FR-R240-1), dwell (FR-R240-6), inertness (FR-R240-5), the lateral-hop two-slot shape
  (FR-R240-10), reduced-motion downgrade.
- `specs/requirements/phase-202-focus-detail-config-and-payload.md` — FR-202-5 / non-goals, and the
  `FocusDetailFacts`-vs-`MediaCard` distinction this phase's whole premise rests on.
- `ravilo-ui/.../components/FocusDetailBackdrop.kt` (new, this phase) — the actual Compose build.
  `AnimatedVisibility(visible = bgActive)` wraps an `AnimatedContent` keyed on `backdropUrl`, mirroring
  `HeroCarousel.kt`'s own `AnimatedContent`/`fadeIn`/`fadeOut` idiom; a held `bgUi` snapshot (not the
  live `fd` directly) reproduces `ContentRowItem`'s own `panelKey`/`panelUi` "survive the dwell's null
  gap" pattern so FR-R242-5's hop crossfade doesn't blank. Reads `FocusDetailUi.card.backdropUrl`
  directly, never `facts` — keeps FR-202-5's fact/artwork split visible in code. Null `backdropUrl`
  falls back to a plain `colors.surface` fill (Compose has no per-title CSS gradient equivalent to the
  mockup's `artFor().grad` — see open question 5).
- `ravilo-ui/.../screens/HomeScreen.kt` — `FocusDetailBackdrop(fd = fdUi)` inserted as the first child
  of `HomeLoaded`'s own `Box` (before the `LazyColumn`), so it paints behind the AppBar overlay and
  every row, reusing the same `store.focusDetail.current` the row-open panel already collects — no new
  state at the feed/store level.
- `ravilo-ui/.../theme/Motion.kt` — `ROW_OPEN_BG_FADE_IN_MS` (550) / `ROW_OPEN_BG_FADE_OUT_MS` (500),
  deliberately slower than `ROW_OPEN_TWEEN_MS` (220) per FR-R242-3.
- `shared/.../tv/Models.kt` (`MediaCard.backdropUrl`, nullable — unlike `Hero.backdropUrl`, which the
  server always populates), `HomeFeedService.kt` (`toMediaCard`, `RaviloImageUrl.backdrop`),
  `ravilo-ui/.../seams/ImageLoader.kt` (`RemoteImage`, existing Coil3 crossfade + cache).

## Open questions

1. **Exact scrim contrast.** The mockup's top/bottom gradient is a placeholder value, not a measured
   one — a full-screen backdrop behind arbitrary row content (not a fixed left-aligned text block like
   the hero) is a different legibility problem and needs device verification against real backdrops,
   the same discipline R221/Noir's own tint calls got, before this is more than a starting guess.
2. **Should the backdrop prefetch during the dwell** so it's already decoded the instant the row opens,
   rather than starting its own fetch at that moment (FR-R242-8)? Left as the simpler, zero-prefetch
   behaviour here; revisit if the plain on-commit fetch reads as a visible pop-in on a slow connection.
3. **Paint/composite cost of a full-screen crossfading image layer, on top of J's own reflow cost.**
   R240's invariant-11 measurement (46 settled opens, 2.8–3.2% janky frames) covered J's *layout* cost
   only. A full-bleed image crossfading underneath it is a different GPU workload and needs its own
   device pass on the stue BRAVIA before this ships — R240's closed open question 1 does not answer it.
4. **Should the backdrop be a switch of its own, independent of J?** A household might want the facts
   panel without the immersive background (slower device, or simply taste). This phase deliberately
   ships with no separate control (see Non-goals) — an owner call for a follow-up, not assumed here.
5. **The mockup's per-title placeholder gradient has no Compose equivalent.** `design/ravilo/
   ravilo-data.js`'s `artFor()` fabricates a CSS gradient for titles with no real backdrop (the design
   data set); the real backend has no such field — a null `MediaCard.backdropUrl` gets a flat
   `colors.surface` fill in the shipped build instead (`FocusDetailBackdrop.kt`). Worth deciding
   whether that's the permanent answer or whether a generated-tint fallback (à la the profile-avatar
   initials gradient, 187/R234) is worth adding here too.
