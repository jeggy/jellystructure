# Phase R154 — Validate the Top 10 / Discover config and warn in the Ravilo config editor

> Extends **R48–R50** (Discover / Top 10) and **R51** (global vs per-user config), and consumes the global
> Discover config from **[Phase 107](../../requirements/phase-107-discover-sources-settings.md)**. A viewer's
> Top 10 tab silently breaks when the chosen **sources × country × lists** don't line up with what's actually
> ingested (a provider isn't available in that country, the country isn't ingested, Radarr isn't connected,
> no lists remain). Surface those problems **at configuration time**, in the jellystructure Ravilo config
> editor — not as an empty tab on the TV.

## Problem
`app/ravilo-config.html ▸ Top 10` lets an admin pick **Sources** (now **multiple** — Netflix, Viaplay,
Paramount+, SkyShowtime, Max… whatever is enabled globally), a **Country**, and which generated **lists** to
show. Nothing verified the combination:
- A source may publish **no chart for the selected country** (Viaplay only in the Nordics; JustWatch/
  movieofthenight services vary) — pick an unsupported country and those lists come back empty.
- The **country isn't ingested** globally (`discover.regions`) — its country lists will always be empty.
- Top 10 requests need **Radarr** connected; if it isn't, requests silently fail.
- No sources selected, or every list unavailable, leaves an **empty** Top 10 tab.

## Goal
The config editor **verifies the current Top 10 config against provider coverage** and shows the result
inline: a green "verified" confirmation when everything is supported, and clear **warnings/errors** (with a
fix action) the moment a combination won't work — at the field, on the affected list rows, and as a summary
banner.

## Requirements

### FR-R154-0 — Multiple sources, drawn from the global config
The **Sources** control is a **multi-select** (not a single dropdown), offering only the providers enabled
in the global Discover config (Phase 107). Enabling/disabling a source **regenerates the list rows**: Netflix
contributes its country (movies + TV), global, non-English and all-time lists; each JustWatch /
movieofthenight service contributes its country movies + series lists (“Top 10 Movies on <service>”). The
Country dropdown offers the ingested regions.

### FR-R154-1 — Verified/needs-attention banner
Below the Sources/Country fields, show a live status banner: **✓ verified** ("<sources> for <Country> — N
lists shown") when there are no issues, otherwise the list of issues (errors before warnings), each with a
short explanation and, where relevant, a fix link.

### FR-R154-2 — Checks
Validate on load and on every relevant change (sources, country, per-list show toggles, master enable):
1. **Radarr connection** — Top 10 requires Radarr connected ⇒ error with a "Connect Radarr →" deep-link.
2. **No sources selected** ⇒ warning (empty tab).
3. **Country not ingested** — the selected country isn't in `discover.regions` ⇒ warning with an
   "Add it in Settings → Discover" link, and every country list is locked.
4. **Provider country coverage** — for each enabled source, if it publishes no chart for the selected
   country ⇒ warning + that source's country lists locked.
5. **Empty result** — no lists work for the source×country combination ⇒ warning.
5. **Empty tab** — no lists shown ⇒ warning.

### FR-R154-3 — Inline markers + lock unavailable lists
The affected **list rows** show an inline warning chip (e.g. "⚠ No chart for Faroe Islands") and a warning
style; the **Country** field shows a warning border + hint when the country is unsupported. Country list
titles reflect the selected country live ("Top 10 Movies in <Country>"). A list that **can't work** for the
current source/country is **locked**: its show toggle is forced **off** and **disabled** (can't be enabled)
for as long as it's unavailable, and is **restored to its previous state** once the config becomes valid
again (e.g. switching the country back to a supported one).

### FR-R154-4 — Provider coverage is data, verified server-side
Provider coverage (which countries and list types each source supports) and the Radarr-connected flag are
**sourced from the jellystructure setup**, not hard-coded in the client — the same values the backend uses
when it actually builds the charts, so the editor's verdict matches runtime behaviour.

## Implementation

| Layer | File | Change |
|---|---|---|
| Config editor | `ui/RaviloConfig.kt` (Top 10 section) | Source/Country become real selects; add the status banner + per-row warning slots; run `validateTop10()` on load + change; render banner/inline markers. |
| Coverage data | backend Discover/chart service | Expose provider coverage (`countries[]`, `listTypes[]`, `ready`) + Radarr-connected to the config API so the editor verifies against real capability. |

### Design reference (already built)
`design/app/ravilo-config.html`: `PROVIDERS`/`COUNTRIES` model, `validate()` (readiness · Radarr · country
coverage · list-type coverage · empty), the `#t10-warnings` banner, `.rowwarn` inline chips, the
locked/disabled toggles on unavailable rows, and the Country-field warning. Demo: Netflix + Denmark →
**✓ verified**; switch to **Faroe Islands** → banner + both country rows warn "No chart for Faroe Islands"
and their toggles **lock off** + Country field flagged; back to Denmark → rows unlock and restore.

## Non-goals
- No live network probe of the third-party chart vendor at config time — verify against stored provider
  coverage (refreshed by the backend), not a synchronous fetch.
- No auto-fix (it suggests supported countries / hiding lists; the admin chooses).
- No new provider integrations (Disney+/Max stay "coming soon").
