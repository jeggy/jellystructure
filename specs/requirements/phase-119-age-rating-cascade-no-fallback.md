# Phase 119 — Age ratings: the region cascade is authoritative (no out-of-cascade fallback) (FR-AR2)

## Goal
A configured age-rating region cascade must be **authoritative**: when none of the admin's chosen
regions has a certification for a title, show **no age badge** and write **no `<mpaa>`** — instead of
silently picking an arbitrary region the admin never selected. This fixes the reported bug where a
`Denmark → Sweden → Norway → United States → United Kingdom` cascade produced **"KR ALL"** (South
Korea) for *Bluey's Big Play*.

## Root cause (verified in code + against the live item)
- The offending title is the **movie** *Bluey's Big Play* (tmdbId `1453792`). Its stored
  `certifications` map is `{"KR":"ALL","SG":"PG"}` — TMDB `/movie/{id}/release_dates` carries non-blank
  certifications only for KR and SG; **none** of DK/SE/NO/US/GB. (The real *Bluey* TV show has `DK→A`
  and resolves correctly.)
- `CertificationResolver.resolve(cascade, certifications)`
  (`src/commonMain/kotlin/dev/jellystructure/resolver/CertificationResolver.kt:81-94`) walks the
  cascade, and **when no region matches, falls back** to `US → GB → alphabetically-first present
  region` with `fallback = true` (lines 87-93). For `{KR, SG}` the alphabetical-first is `KR`. This
  faithfully implements Phase 106 §C.1 — **the spec's fallback design is the flaw, not a code bug.**
- Resolution is **derive-on-read** (never persisted, `:48-50`) and lives in one shared `commonMain`
  object (backend + admin FE compile the same code): `BrowseService.kt:161`, `HomeFeedService.kt:426`,
  `DetailService.kt:190`, `MediaStore.kt:544`, `NfoWriter.kt:198/254`, `MediaDetail.kt`. The cascade
  is correctly plumbed (`config.toml age_rating_cascade` → `MetadataConfig.ageRatingCascade`) — this is
  **not** an empty-cascade/plumbing bug.
- **NFO leaks the fallback to Jellyfin.** `NfoWriter` writes `<mpaa>{code}</mpaa>` for *any* resolution
  including `fallback = true` (`:198-200`, `:254-256`), so Jellyfin's `OfficialRating` + parental
  controls inherit the Korean rating.
- **Tier is wrong for foreign codes.** `tierFor("ALL") → 2` (neutral) via the default branch, though KR
  "ALL" is all-ages (tier 0). `NAMED_TIER` has no entries for the foreign codes the fallback can
  surface, so the badge colour is also misleading.

## Requirements

### A. Resolver — cascade-only, no fallback
1. `resolve()`: keep "first cascade region present in the map wins." **When no cascade region matches,
   return `null`** — delete the US→GB→alphabetical fallback (lines 87-93). An empty cascade already
   yields no match → `null` (feature off, consistent with Phase 106 §A.3). Net rule: `resolve` returns
   a `Certification` **only** for a region the admin explicitly configured; otherwise `null` (no badge
   anywhere, instantly, via derive-on-read).
2. `trace()`: with the fallback gone, a cascade that matches nothing yields all `SKIPPED`/`NOT_REACHED`
   rows and no `USED` row (already automatic once `resolve` returns null — lines 111-113 go inert).
3. `Certification.fallback` / `RatingBadge.fallback` become always-false; keep the fields for wire/DTO
   compatibility (a later cleanup may remove them) — do **not** repurpose.

### B. Admin detail — surface the out-of-cascade data without leaking it as a rating
1. So the admin can see *why* a title has no badge, the cascade-trace card
   (`MediaDetail.kt`) shows an **informational-only** line listing the regions TMDB *does* have that
   are not in the cascade (e.g. "TMDB also has: KR ALL · SG PG — add a region to your cascade to use
   one"). Plain text, **never a badge, never written to NFO, never sent to Ravilo**. Source: the item's
   raw `certifications` map minus the cascade regions (already on the detail payload).

### C. NFO / Jellyfin cleanup
1. `NfoWriter` already writes `<mpaa>` only when `resolve` returns non-null; with the fallback removed
   it now omits `<mpaa>` for cascade-miss titles. Stale `<mpaa>` in already-written NFOs clears on the
   next `write_nfo` pipeline step (Phase 115 content-hash tracking detects the removal and rewrites) →
   Jellyfin re-syncs `OfficialRating` to empty. **No forced mass-rewrite required** — the pipeline
   self-heals; the admin can trigger it immediately via a re-sync.
2. Safety belt (independent of A): `NfoWriter` must never write a `fallback = true` `<mpaa>` — assert
   this so any future re-introduction of a fallback can't leak to Jellyfin.

## Scope
- `CertificationResolver.resolve`/`trace` (commonMain — the whole fix).
- `MediaDetail.kt` trace card: the informational "not in your cascade" line.
- Verify `NfoWriter` `<mpaa>` gating (Requirement C.2).
- No config change, no migration, no TMDB change. Badge / Ravilo card / workbench facet all
  self-correct via derive-on-read.

## Non-goals
- No per-title manual rating override (later phase).
- No expanding the region catalog and no automatic "add KR to your cascade" — the admin decides.
- Episode-level ratings remain N/A (TMDB has none).

## Acceptance
- With `DK → SE → NO → US → GB`, *Bluey's Big Play* (`{KR, SG}`) shows **no age badge** in Library,
  detail, and Ravilo; its NFO has no `<mpaa>` after the next write.
- A title with a DK certification still shows DK; moving US above DK still re-resolves instantly
  (derive-on-read preserved).
- The admin detail trace card shows the cascade regions as skipped/not-reached and lists `KR ALL` /
  `SG PG` as informational "not in your cascade", with no badge and no NFO write.
- **No title anywhere shows a region the admin did not configure.**
