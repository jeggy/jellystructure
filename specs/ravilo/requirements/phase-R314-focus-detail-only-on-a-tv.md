# Phase R314 — Focus detail only on a TV: nothing on a phone or the web

> Owner, 2026-09-26: *"The focus detail Ravilo configuration within jellystructure, should only apply to
> TVs. So let's make sure that neither the status line or the row opens happens on web or mobile devices.
> But then the TV should still read this configuration as it's configured within jellystructure."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Client (`ravilo-ui`), one server-side payload trim, one
admin copy fix. **Numbering:** verified against `STATUS.md` the same day — Ravilo taken through **R313**.

Completes **R254** (row-open is TV-only) and **R298** (no focus visuals on a handset). Both stopped
halfway on the platforms this phase is about.

## What is there today

All of it is in one pure function, `effectiveFocusDetailMode` (`focus/FocusDetailReducedMotion.kt`),
which both Home call sites use:

```kotlin
handset -> "none"                                                         // R298
resolvedMode == "rowOpen" && (reduceMotion || !isTv) -> "line"            // R240 + R254
else -> resolvedMode
```

| Where | Household set to *row opens* | Household set to *status line* |
|---|---|---|
| TV (`isTvPlatform`) | row opens | line |
| phone, handset-sized (R256 `isHandset`) | nothing | nothing |
| **web app in a desktop browser** | **line** | **line** |
| **phone app on a tablet-sized screen** | **line** | **line** |

So the web app still draws R240's 88 dp status line at the foot of Home, with its reserved band and its
dwell timer. It describes whatever card the mouse or a click last focused, which is exactly the
"describes a card nobody chose" problem R298 fixed on the phone. The admin card says so in words, too:
the *row opens* switch is captioned *"TVs only — a phone or the web app shows the status line instead"*
(`RaviloConfig.kt`).

### The phone and the web also download what they never draw

The server attaches R240's per-title facts (`MediaCard.focus_detail`) to every Home row card whenever the
household's mode is not `none` (`HomeFeedService.attachFocusDetail`), whoever asks. Measured on
production, 2026-09-26, with the Pixel 9's and the web app's own device tokens (both got the same feed):

| `/api/tv/home` | Uncompressed | gzip on the wire |
|---|---|---|
| as sent today | 304 KB | 82 KB |
| without the facts | ~117 KB | ~20 KB |

That is 347 fact objects, **61 %** of the feed and **three quarters of the bytes on the wire**, on every
Home load and every live refresh of a phone on mobile data. The phone then parses them and draws none.

## Requirements

**FR-R314-1 — Focus detail exists only on a TV.** `effectiveFocusDetailMode` returns `"none"` whenever
the platform is not a TV (`isTvPlatform` false), whatever the household's resolved mode. On a TV nothing
changes: the household's `line` / `rowOpen` / `none` and its delay, as configured in Jellystructure, and
R240's reduced-motion downgrade of `rowOpen` to `line`. One rule replaces the separate `handset` and
`!isTv` branches. The function is still the only place the decision is made, and both Home call sites
keep calling it. Its tests are updated so that the web and a non-handset phone get `none` for every
resolved mode.

**FR-R314-2 — `none` means nothing, everywhere it is read.** On the web app and on the phone app at any
size: no status line, no reserved 88 dp band at the foot of Home, no dwell timer, no row-open panel, no
backdrop (R242). Home lays out exactly as a household set to *off* does today. This is what
`effectiveFocusDetail == "none"` already produces on a TV; the requirement is that no call site
consults the raw `feed.focusDetail` instead.

**FR-R314-3 — The server stops sending facts to a device that says it is not a TV.** In `/api/tv/home`,
when the request's device reports `platform` `phone` or `web` (R252's `X-Ravilo-Platform`, carried on
`DeviceData.platform`), every row card's `focus_detail` facts are dropped from the response. It is a
**projection applied after the Home cache is read**, never part of the cache key. This avoids the trap
R254 recorded when it rejected a server-side mode (R233 FR-R233-5: a per-platform answer must not
fragment the per-user cache). A device with no reported platform, or `tv`, gets the feed exactly as
today. The top-level `focus_detail` / `focus_detail_delay_ms` values are left as they are, because the
client makes the decision (FR-R314-1) and an installed older phone app already draws nothing on a
handset. The facts field is optional on the wire, so an installed app on any platform reads a card
without it as it always has.

**FR-R314-4 — The admin says what the setting reaches.** The *Focus detail* card in Jellystructure →
Ravilo layout → Preferences says, once, at the top: **TVs only**, and that phones and the web app never
show focus detail. The *"a phone or the web app shows the status line instead"* caption goes. The three
controls (status line, row opens, delay) are unchanged and still apply to every TV in the household.

**FR-R314-5 — Tests.** The pure function: `none` for `isTv = false` across `none` / `line` / `rowOpen`,
with and without reduced motion; the TV cases unchanged. Server: a `phone` and a `web` request get row
cards with no `focus_detail`, a `tv` request and a request with no platform get them, and one cached
feed serves all of them.

## Non-goals

- The setting itself (Phase 202's three fields, the server's per-user resolution): unchanged.
- The channel rail, heroes, browse and search, where focus detail never applied (R240's scope).
- The web app running on a TV's own browser. The web app says `web`, so it gets nothing. A household that
  wants focus detail on a TV uses the TV app.

## Acceptance

1. The web app in a desktop browser, household set to *row opens*: Home has no strip at the foot and no
   reserved space for one; clicking or hovering a tile shows nothing extra. The same with *status line*.
2. The Pixel 9, portrait and landscape: no focus detail, as today.
3. The stue TV and the bedroom TV: exactly what the household's setting says, and a change in
   Jellystructure reaches them live as before.
4. `/api/tv/home` with the Pixel 9's token: no `focus_detail` object on any card, about 20 KB on the wire.
   With a TV's token: unchanged.
5. The admin card reads *TVs only* and no longer promises the status line on a phone or the web.
