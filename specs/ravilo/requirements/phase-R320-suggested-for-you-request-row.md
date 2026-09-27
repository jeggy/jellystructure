# Phase R320 — *Suggested for you*: the household's suggestions as a Request row, per viewer

## Status

`Planned` — written 2026-09-27 with admin **274**, from
`specs/ravilo/design-brief-suggested-movies-from-seerr-2026-09-27.md` §3. **Not dev-reviewed.** Numbering verified
against `main` `e437def3`: Ravilo taken through **R319**. Builds on **R171** (the Request tab), **137** (Request
feeds), **R318** (unknown row kinds never break an app), **155** (unrated is 18).

## Requirements

**FR-R320-1 — One more Request row, nothing new drawn.** A viewer whose Request feeds include *Suggested for you*
(274 FR-274-15) gets it in Discover ▸ Request exactly like *Trending* or *Popular*: the same tile with its request
status, the same request detail on Select/tap, the same *Request* button gated on `can_request`, the same states
afterwards. TV, phone and web. No new screen, component or layout.

**FR-R320-2 — It is this viewer's list.** The row's items are `suggestion_viewer` for this device's user and
visibility scope (274 FR-274-6), in stored order, at the feed's limit. The server resolves them; the app renders.

**FR-R320-3 — No history, no row.** A viewer with no sources (nothing finished, nothing in progress) gets **no
row** — not an empty row, and not someone else's list.

**FR-R320-4 — Kids profiles.** Built against the same pool, filtered by the Home feed's age gate using the
certification Seerr returns; a candidate with **no** certification is never shown to a kids profile (155).

**FR-R320-5 — What the viewer never sees.** No *No thanks*, no reason picker, no *because* line, no cluster name,
no product name (*Request*, never Seerr/Radarr/TMDB). Titles on Seerr's blacklist never appear (274 FR-274-16).

**FR-R320-6 — Installed apps keep working.** On the wire the feed is an ordinary resolved Request row. Whatever
new value the editor stores (a feed kind) never reaches anything an installed app decodes without a default.
`WireCompatTest` (R319) passes with a *Suggested for you* row in the payload.

**FR-R320-7 — One string.** The feed's default title, `rq_suggested`: *Suggested for you* · da *Forslag til dig* ·
fo *Uppskot til tín* (drafts; R288's lexicon — the shipped table wins). The admin can rename the row as any feed.

## Acceptance

1. With the feed on for a viewer who has finished films, the TV's Request tab shows *Suggested for you*; Select
   opens the existing request detail, and *Request* sends a request as that viewer (their own, as any Request row).
2. The same viewer with the feed off: no row. A viewer with no history: no row.
3. A kids profile's row holds only rated films its age allows.
4. A title dismissed on the admin page is gone from this row after the next feed refresh.
5. An app built before this phase shows the row like any other Request row.

## Mockup

`design/ravilo/ravilo-data.js` (`suggested`, first in Eyð's feeds), rendered by the existing Request tile in
`Ravilo TV.html` and `Ravilo Mobile.html`; the string in `ravilo-i18n.js`.
