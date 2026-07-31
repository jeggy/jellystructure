# Phase 155 — Age-rating normalization: every certification maps to a number 0–18 (FR-AGE1)

> Certifications arrive from NFO and TMDB in every country's own vocabulary — `G`, `TV-MA`,
> `Från 15 år`, `Btl`, `U`, `11`… Ravilo can't compare them, so maturity filtering and kids
> profiles have nothing solid to stand on. This phase adds a jellystructure-owned mapping —
> **raw certification string → one normalized age (integer 0–18)** — managed on the Metadata
> page, and ships the number to Ravilo. Viewers only ever see numbers; nothing downstream is
> ever labelled "G" or "TV-PG" again. The Ravilo-side consumer (the browse page's Maturity
> range filter) is **Phase R187**.

## Goal
An operator opens **Metadata → Age ratings**, sees every certification value present in the
library grouped into a normalized 0–18 ladder, nudges any mapping with a stepper (write-through),
and triages the handful of values jellystructure couldn't map. Ravilo receives one nullable
integer per title and builds all maturity UX on it.

## Current state
- Certifications are stored as the raw strings the scanner found. A sample from one live library
  spans at least six systems: `A`, `NR`, `TV-Y`, `TV-G`, `Btl`, `G`, `U`, `7`, `PG`, `TV-PG`,
  `11`, `TV-Y7`, `Från 15 år`, `12`, `9`, `Från 7 år`, `15`, `TV-14`, `PG-13`, `R`, `TV-MA`, `18`.
- The detail hero shows a **regional cert chip** (R134 lane) — presentation only; nothing can
  *order* certifications across systems.
- The Metadata page has Studios · Networks · Genres · Tags · Trackers tabs; the unmapped-value
  triage pattern already exists (Phase 98's "unmapped announce hosts").

## Requirements

### A. The mapping
#### FR-AGE1-1 — One config-owned table: raw certification → integer age 0–18
A single mapping keyed by the **normalized raw string** (trimmed, case-preserving match on the
stored value; `PG-13` from any source is one row). Values are integers 0–18 — any integer, not a
fixed ladder (`9`, `11`, `13`, `14`, `17` are all legal). Stored in config (survives re-scans;
exported with the config file). Seeded with defaults for the common systems (MPAA, US TV, BBFC,
DK Medierådet, SE Statens medieråd, IS) so a fresh install maps the sample above out of the box;
`NR`-style values stay deliberately unseeded (see §C).

#### FR-AGE1-2 — Resolution: no rating ⇒ 18
`normalizedAge(item)`: look up the item's certification in the table → the integer. **No
certification at all, or an unmapped value → 18** — the safe adults-only default: an unrated
title is never accidentally exposed to a narrower audience, and kids-profile hiding follows
naturally from the 18 gate. This is a **gate value, not a label** — an unrated title never
displays “18+” anywhere; it simply behaves as 18 wherever an age is compared. Purely a lookup —
no per-system parsing at read time; all
intelligence lives in the seeding defaults and the operator's table.

### B. Metadata → Age ratings tab
#### FR-AGE1-3 — The management surface
A new **Age ratings** tab on the Metadata page (after Tags):
- **Normalized scale** ladder: one chip per distinct mapped age, showing the age (`7+`), how many
  certifications map to it and how many items that covers — plus a warn chip counting unmapped
  values. This is the "what viewers will see" summary.
- **Certification mappings** table: one row per raw value — mono cert badge, source-system badge,
  item count (links to the library filtered to it), and a **− / +** stepper over 0–18.
  Edits are **write-through** (Phase 71/74 conventions: no staged save; a brief saved pulse) and
  re-aggregate the ladder immediately.
- A **Suggest mappings** action re-applies seeding defaults to unmapped values only (never
  overwrites an operator's explicit choice).

### C. Unmapped triage
#### FR-AGE1-4 — Unmapped ≠ guessed — but always gated as 18+
Values with no mapping (e.g. `NR`, scraper artifacts like `Btl`) are listed in an **Unmapped
certifications** section with counts and an inline stepper + **Map** action. Until mapped, their
titles resolve to **18** (FR-AGE1-2): **treated as 18 in every filter and kids-profile gate,
but never labelled 18+ in any UI**, and matching only maturity ranges that reach 18.
jellystructure never silently guesses a *lower* age.

### D. Delivery to Ravilo
#### FR-AGE1-5 — One integer on the item DTO
The TV item payloads gain `ageRating: Int` (the normalized age; **18 when uncertified or
unmapped**, per FR-AGE1-2). The raw certification string stays available for the R134 regional
chip; everything *functional* (filtering, kids gating, sorting) uses only the number. Additive
and defaulted — old clients ignore it.

## Non-goals
- **No change to the detail hero's regional cert chip** (R134 lane keeps showing `PG-13` etc. as
  provenance; normalization is for filtering/gating, not display of origin).
- **No per-country mapping tables** — one global table; the number is jurisdiction-neutral.
- **No re-fetching certifications** from TMDB; this phase only interprets what's stored.

## Acceptance
- Metadata → Age ratings shows the ladder + all mapped rows; stepping `A` from 0 to 1 pulses,
  persists and moves it between ladder chips without a reload. *(Verified in the design mockup.)*
- `NR` and `Btl` appear as unmapped with item counts; mapping one inline moves it out of triage;
  until then their titles resolve to 18+ and stay hidden from kids profiles.
- The TV payload carries `ageRating` and R187's Maturity filter orders/filters purely on it.

## Status
Design-complete, **`Planned`**. Design lives in `design/app/metadata.html` (+ `metadata.css`) —
tab, ladder, stepper rows, unmapped triage. Consumer spec: **Phase R187**.
`scripts/check-phases.sh` will flag it for a `STATUS.md` row — **STATUS.md is code-owned; do not
add the row from the design side.** **Next admin number after this is 156.**
