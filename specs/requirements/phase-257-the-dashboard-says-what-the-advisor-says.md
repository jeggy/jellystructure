# Phase 257 — the Dashboard says what the advisor says

> Owner, 2026-09-24, on production running every transcode in software while the advisor had said so
> since v1.30: *"I would like that page to suggest changes like this"* — and, once it was clear the
> advisor already did, on the Settings tab only: *"Yes, let's show everything like that on the
> jellystructure dashboard."*

## Status

`Planned` — written 2026-09-24. Not dev-reviewed. Frontend only: no route, DTO, config or backend change.

## Context

Phase 212's Jellyfin settings advisor (extended by 242, 244 and 246) reads Jellyfin's live configuration
and reports what is costing this household something, with the exact Jellyfin screen, field label, value
and trade-off. Its findings are real and specific. On 2026-09-24 production carried a **critical** one
(246's `hwaccel_none`: *"Hardware encoding is switched on with no accelerator selected"*). Every
transcode (4K HEVC to software `libx264`) took 12–25 s to start or restream, and a day of device testing
kept running into it.

The finding had been on screen since v1.30, but only at the top of **Settings → Libraries**, a tab an
admin opens to change library mappings, not to learn what is wrong. The **Dashboard**, the page the
admin actually lands on, shows phase 221's webhook findings through the same renderer
(`advisorFindingHtml`) and nothing from the advisor.

## Requirements

### FR-257-1 — The Dashboard shows every advisor finding
A **Jellyfin settings advisor** card sits directly under phase 221's findings. It lists every finding
`GET /api/jellyfin/advisor` returns: the server-wide findings, then each library's findings, each
labelled with its library's name. Nothing is summarised away and nothing new is computed. Each finding
renders through `advisorFindingHtml`, the same function Settings uses, so the two pages cannot word a
finding differently.

### FR-257-2 — Most urgent first; notes last and folded
Order: `critical`, then `warning`, then `info`, server-wide before per-library within each. The header
badge counts only the findings that ask for something (critical + warning), following FR-246-9's rule
that notes must not inflate the number an admin decides by. `info` findings sit under one folded
*"N for information"* row, one click from open, never hidden.

### FR-257-3 — Silent when there is nothing to say
No findings means no card: no *"all clear"* banner (FR-212-2's rule, applied here too). An unreachable
Jellyfin also means no card. The shell's own *Jellyfin online/offline* indicator (`Shell.kt`) already
reports reachability on every page, and the *"couldn't reach Jellyfin"* state stays Settings' to explain. A library whose options Jellyfin didn't
return (FR-242-7) is shown there, not here: it is not a finding.

### FR-257-4 — Actions work the same in both places
A finding that carries an action (244's *Re-check*) gets the same button on the Dashboard, wired by the
same function as in Settings. When the re-check says it is closed, the Dashboard card is fetched again.

### FR-257-5 — The way to the detail
The card ends with *Open the advisor in Settings →* (`#/settings?tab=libraries`). Settings keeps its
per-library placement, its memory-budget calculator (phase 215) and everything else it has today.

## Invariants
- One source: the same endpoint and its 5-minute cache. The Dashboard adds no request pattern the
  Settings page does not already make.
- jellystructure still never writes to Jellyfin (212's read-only rule). The card says so in the same
  words as Settings.

## Out of scope
- Applying a suggested change from jellystructure (would break 212's read-only rule; its own phase if wanted).
- Notifications (phase 221's webhooks) for a newly appearing critical finding.

## Verification
- `compileKotlinWasmJs`; the e2e mock stack's Dashboard renders with the mock's (empty) advisor, which
  must produce no card at all.
- Against production (read-only): the Dashboard shows `hwaccel_none` at the top with *act on this first*.
