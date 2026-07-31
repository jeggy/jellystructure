# Phase 155 — Age-rating normalization: every certification maps to a number 0–18 (FR-AGE1)

> Certifications arrive from **TMDB only** (never NFO — see Backend review) in every country's own
> vocabulary — `G`, `TV-MA`, `Från 15 år`, `Btl`, `U`, `11`… Ravilo can't compare them, so maturity
> filtering and kids profiles have nothing solid to stand on. This phase adds a jellystructure-owned
> mapping — **cascade-resolved certification code → one normalized age (integer 0–18)** — managed on
> the Metadata page, and ships the number to Ravilo. Viewers only ever see numbers; nothing downstream
> is ever labelled "G" or "TV-PG" again. The Ravilo-side consumer (the browse page's Maturity range
> filter) is **Phase R187**.

**Status:** Planned. Backend-reviewed 2026-07-31 (see addendum) — corrects the design tool's mistaken
"NFO + TMDB" sourcing claim and its R134 citation, and resolves an ambiguity (raw per-country map vs.
cascade-resolved code) that materially changes table size and storage choice.

## Goal
An operator opens **Metadata → Age ratings**, sees every certification value present in the library
(after this install's own Settings → Metadata region cascade resolves each item down to one code)
grouped into a normalized 0–18 ladder, nudges any mapping with a stepper (write-through), and triages
the handful of values jellystructure couldn't map. Ravilo receives one integer per title (never null —
unrated resolves to 18, see FR-AGE1-2) and builds all maturity UX on it.

## Current state
- **Certifications come from TMDB only, never NFO.** `MediaItem.certifications: Map<String, String>`
  (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt:229`) stores one code per ISO-3166-1
  country, e.g. `{"DK":"15","US":"PG-13"}` — populated by `Scanner.kt` from
  `TmdbClient.getMovieCertifications()` (`/release_dates`) for movies and
  `getTvCertifications()` (`/content_ratings`) for TV (`TmdbClient.kt`). **The same country code means
  a different vocabulary depending on `MediaItem.kind`** — `"US":"NR"` on a movie is an MPA rating,
  `"US":"TV-MA"` on a series is a TV Parental Guidelines rating. `NfoWriter.kt` *writes* `<mpaa>` from
  the resolved cascade code but nothing ever reads it back — NFO is not a certification source.
- **No single "the" certification is stored.** It's resolved live, per read, from the raw map by
  `CertificationResolver.resolve(cascade, certifications)` (`src/commonMain/kotlin/dev/jellystructure/resolver/CertificationResolver.kt:83-90`)
  walking the admin-configured region cascade (`MetadataConfig.ageRatingCascade`, `AppConfig.kt:159-161`
  — this install's live value is `["DK","SE","NO","US","GB"]`, `config/config.toml`) and taking the
  first region present. **An empty cascade makes every item resolve to no certification at all** — the
  Age ratings tab would then have nothing to show. This is a real first-run state, not a hypothetical.
- A live sample (428-item library, `config/jellystructure.db`) makes the raw/resolved distinction
  concrete: flattening every raw per-country value across the whole catalog yields **209 distinct
  strings** — non-Latin scripts, whitespace variants, and short codes that mean different things in
  different countries (`"A"` is all-ages in Denmark's own scale, adults-only in India's). Resolving the
  *same* library through this install's actual cascade instead yields exactly **22 distinct codes** —
  `A, NR, TV-Y, TV-G, Btl, G, U, 7, PG, TV-PG, 11, TV-Y7, Från 15 år, 12, 9, Från 7 år, 15, TV-14,
  PG-13, R, TV-MA, 18` — which is where the sample below comes from. **This phase's mapping table is
  keyed on the cascade-resolved code, not the raw per-country map** (see FR-AGE1-1) — the raw map is
  too large, too ambiguous, and too script-mixed to be a sane per-string mapping target.
- `CertificationResolver` already has a hardcoded 0–4 "tier" (`NAMED_TIER` + numeric fallback,
  `CertificationResolver.kt:55-75`) used for badge colour and rough ordering today — related prior art,
  not the same thing as (and not a source for) the new 0–18 age.
- The detail hero's regional cert chip is **Phase 106 + R153**, not R134 (`STATUS.md:351,518`;
  component `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/CertBadge.kt`,
  rendering `RatingBadge`). R134 is unrelated (merging the audio/subtitle flag line into one row).
- The Metadata page has Studios · Networks · Genres · Tags · Trackers tabs
  (`src/wasmJsMain/kotlin/dev/jellystructure/ui/Metadata.kt:19`); the unmapped-value triage *shape*
  (grouped raw values → count → assign/create → instant persist, no staged save) already exists for
  Phase 98's announce-host mapping (`Metadata.kt:363-509`) and is a good pattern to copy structurally.
  **There is no numeric +/− stepper control anywhere in shipped Kotlin admin UI today** — only the
  design mockup has one (`design/app/metadata.html:107-108`) — so FR-AGE1-3's stepper is new UI to
  build, not an existing widget to wire up.
- **Naming collision to be aware of:** Settings already has a section literally titled **"Age ratings"**
  (`Settings.kt:214-215`, `id="sect-ageratings"`, badge "region cascade") — that one manages *which
  raw certification gets picked* (the cascade order). This phase's new Metadata-page tab, also
  reasonably named "Age ratings," manages a completely different mapping (cascade-resolved code →
  normalized integer). Cross-link the two in the UI so an operator doesn't confuse them.

## Requirements

### A. The mapping
#### FR-AGE1-1 — One config-owned table: cascade-resolved certification code → integer age 0–18
A single mapping keyed by the **cascade-resolved certification code** (the same value already computed
by `CertificationResolver.resolve()` and exposed as `MediaCard.rating`/`RatingBadge.code` — trimmed,
case-preserving match; `PG-13` is one row regardless of which title produced it). Values are integers
0–18 — any integer, not a fixed ladder (`9`, `11`, `13`, `14`, `17` are all legal). Stored in config
(`config.toml`, alongside `MetadataConfig`) — at cascade-resolved-code granularity this is ~22 rows for
a 428-item library, the same order of magnitude as the existing Trackers registry
(`AppConfig.kt:23`, `List<TrackerEntry>`), a validated precedent for this exact shape. **Do not key this
table on the raw per-country map** — at that granularity it's 200+ rows including non-ASCII scripts, a
poor fit for `config.toml`, and semantically broken (the same raw string can mean different ages in
different countries). Seeded with defaults for the catalog's 10 already-modelled regions
(`CertificationCatalog.REGIONS`, `CertificationResolver.kt:16-27`: Denmark/Medierådet, United
States/MPA — not "MPAA" — United Kingdom/BBFC, Germany/FSK, Sweden/Statens medieråd,
Norway/Medietilsynet, France/CNC, Netherlands/Kijkwijzer, Ireland/IFCO, Iceland/SMÁÍS) so a fresh
install maps the sample above out of the box; there is no catalog entry for "US TV" today — either add
one (distinguishing movie-MPA from TV-Parental-Guidelines under the same `"US"` code, since they share
a country key but not a vocabulary — see Current state) or seed US TV codes (`TV-Y`, `TV-G`, `TV-Y7`,
`TV-PG`, `TV-14`, `TV-MA`) as unlabelled/generic entries. `NR`-style values stay deliberately unseeded
(see §C).

#### FR-AGE1-2 — Resolution: no rating ⇒ 18
`normalizedAge(item)`: resolve the item's certification via the existing cascade
(`CertificationResolver.resolve`), then look up that code in the new table → the integer. **No cascade
match at all (raw map empty, or none of the configured regions present), or a cascade match with no
table entry → 18** — the safe adults-only default: an unrated title is never accidentally exposed to a
narrower audience, and kids-profile hiding follows naturally from the 18 gate. This is a **gate value,
not a label** — an unrated title never displays "18+" anywhere; it simply behaves as 18 wherever an age
is compared. Purely a lookup at read time — no per-system parsing; all intelligence lives in the
seeding defaults and the operator's table. **This resolution is transitively dependent on the Settings
region cascade** (`MetadataConfig.ageRatingCascade`): the cascade is re-evaluated live on every read
(no re-scan needed — same as today's `rating` badge), so reordering it in Settings can shift which
table row an item resolves to, and can move whole swaths of the library to/from the 18 gate, with no
scan involved. Worth surfacing to the operator (e.g. a note on the new tab linking to the Settings
cascade editor), not silently.

### B. Metadata → Age ratings tab
#### FR-AGE1-3 — The management surface
A new **Age ratings** tab on the Metadata page (after Tags) — cross-linked from (and to) Settings'
existing "Age ratings" (region cascade) section, since both share a name but manage different things
(see Current state):
- **Normalized scale** ladder: one chip per distinct mapped age, showing the age (`7+`), how many
  certifications map to it and how many items that covers — plus a warn chip counting unmapped
  values. This is the "what viewers will see" summary.
- **Certification mappings** table: one row per cascade-resolved code — mono cert badge, source-region
  badge (needs `MediaKind` alongside the code to label correctly, since e.g. `"US"` is MPA for a movie
  and TV Parental Guidelines for a series — a plain per-country label is not enough on its own), item
  count (links to the library filtered to it), and a **− / +** stepper over 0–18 (new UI — no existing
  Kotlin stepper component to reuse, see Current state; the design mockup's `.age-step`/`.st-btn`
  markup in `design/app/metadata.html:107-108` is the visual reference).
  Edits are **write-through** (Phase 71/74 conventions: no staged save; a brief saved pulse) and
  re-aggregate the ladder immediately.
- A **Suggest mappings** action re-applies seeding defaults to unmapped values only (never
  overwrites an operator's explicit choice).

### C. Unmapped triage
#### FR-AGE1-4 — Unmapped ≠ guessed — but always gated as 18+
Values with no mapping (e.g. `NR`, scraper artifacts like `Btl`) are listed in an **Unmapped
certifications** section with counts and an inline stepper + **Map** action (structurally, the Phase 98
announce-host triage pattern — `Metadata.kt:363-509` — is the closest thing to copy: grouped raw values,
counts, an assign action, instant persist). Until mapped, their titles resolve to **18**
(FR-AGE1-2): **treated as 18 in every filter and kids-profile gate, but never labelled 18+ in any UI**,
and matching only maturity ranges that reach 18. jellystructure never silently guesses a *lower* age.

### D. Delivery to Ravilo
#### FR-AGE1-5 — One integer on each item DTO the client renders
`ageRating: Int` (never null — always resolved per FR-AGE1-2, defaulting to 18) lands on:
- `MediaCard` (`shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:149-168`) — every
  browse/row/search tile.
- `MovieDetailResponse` and `SeriesDetailResponse` (`Models.kt:355`, `Models.kt:383`) — so a deep-linked
  detail page is gated too, not just grid rows.
The raw cascade-resolved code (`MediaCard.rating` / `RatingBadge.code`) stays available for the
Phase 106/R153 regional chip; everything *functional* (filtering, kids gating, sorting) uses only the
number. Additive and defaulted — old clients ignore it.

## Non-goals
- **No change to the detail hero's regional cert chip** (Phase 106 / R153 keep showing `PG-13` etc. as
  provenance; normalization is for filtering/gating, not display of origin).
- **No per-country mapping tables** — one global table, keyed on the cascade-resolved code; the number
  is jurisdiction-neutral.
- **No re-fetching certifications** from TMDB; this phase only interprets what's stored.
- **No change to the Settings → Metadata region-cascade editor** — this phase consumes its output, it
  doesn't touch its UI or config shape.

## Acceptance
- Metadata → Age ratings shows the ladder + all mapped rows; stepping a code from 0 to 1 pulses,
  persists and moves it between ladder chips without a reload.
- `NR` and `Btl` appear as unmapped with item counts; mapping one inline moves it out of triage;
  until then their titles resolve to 18+ and stay hidden from kids profiles.
- With the Settings region cascade empty, the tab shows an explicit empty state (not a silent blank
  table) explaining that every item currently resolves to 18 because no cascade is configured.
- The TV payload carries `ageRating` on `MediaCard` and both detail responses; R187's Maturity filter
  orders/filters purely on it.

## Status
Design-complete, **`Planned`**, backend-reviewed 2026-07-31. Design lives in `design/app/metadata.html`
(+ `metadata.css`) — tab, ladder, stepper rows, unmapped triage (visual reference only; the stepper
control itself has no shipped Kotlin equivalent yet). Consumer spec: **Phase R187**.
`scripts/check-phases.sh` will flag it for a `STATUS.md` row — **STATUS.md is code-owned; do not
add the row from the design side.** **Next admin number after this is 156.**

## Backend review addendum (2026-07-31)

Reviewed against the real codebase before implementation (the design tool that authored this spec has
no code access — only `design/app/*.html` + `specs/constitution.md`/`plan.md`). Corrections folded into
the body above; summary of what changed:

1. **Sourcing was wrong.** The original spec said "Certifications arrive from NFO and TMDB" — it's
   TMDB only. `NfoWriter.kt` writes `<mpaa>` but nothing reads it back.
2. **"R134 regional cert chip" was the wrong citation** — that's Phase 106 (backend cascade) + R153
   (Ravilo hero badge). R134 is the unrelated audio/subtitle flag-line merge.
3. **The mapping key was ambiguous** in the original (read as "the raw certification string... `PG-13`
   from any source is one row," which sounds like the raw per-country map). Confirmed live that the raw
   map is 209+ distinct, often non-Latin, ambiguous strings across a modest 428-item library — a bad
   `config.toml` fit. The spec's own sample list (22 values) only makes sense as the **cascade-resolved**
   code, which is what FR-AGE1-1 now says explicitly. This is the single most consequential correction —
   implementing against the raw map would have produced a table 10× larger than intended and semantically
   broken across countries that reuse short codes for different ages.
4. **`MPAA` → `MPA`** (`CertificationCatalog`'s actual field value) and **"US TV" isn't a real catalog
   entry** — the catalog only has 10 movie-style regional systems; US TV Parental Guidelines shares the
   `"US"` country key with MPA but is a different vocabulary, distinguished only by `MediaItem.kind`.
   Flagged as an open decision (add a catalog entry vs. seed TV codes generically) rather than silently
   assumed.
5. **DTO list was vague** ("TV item payloads") — enumerated the three real DTOs (`MediaCard`,
   `MovieDetailResponse`, `SeriesDetailResponse`) so detail-page kids-gating isn't accidentally left
   ungated.
6. **New dependency surfaced:** resolution is live and cascade-order-dependent, including the empty-
   cascade first-run case where every item is 18. Not mentioned in the original at all.
7. **Naming collision surfaced:** Settings already has an unrelated "Age ratings" section; the two
   should cross-link.
8. Confirmed accurate and left as-is: the write-through convention (Phase 71/74, real and correctly
   cited), the Phase 98 unmapped-triage pattern as a structural reference, and the numbering (155/156
   don't collide with anything on the admin track or this session's 151–154 work).
