# Phase 48 — Artwork manager: make the Textless / With-text filter work (FR-AM2)

**Status:** Planned · _the artwork gallery's "Textless / With text" pills don't filter — they only
re-sort, and within a single-language bucket that sort is a no-op, so the pills appear dead. Unify the
language + textless/with-text controls into one real, single-select filter on TMDB's `iso_639_1`._

> Follow-up to **[Phase 47 — Artwork manager](archive/phase-47-artwork-manager.md)**. No backend/TMDB
> change — the candidate DTO already carries the language field. UI behaviour only, on the Movie/Series
> detail **Artwork** tab.

## Problem
On the Artwork tab's TMDB candidate gallery, the **Textless** and **With text** chips don't visibly do
anything. The gallery has two controls that both key off the same TMDB field (`iso_639_1`, exposed as
`ArtworkCandidate.lang`, `null` = textless):

1. A **language-chip row** — `All · No language · EN · DA · …` — a single-select **filter**
   (`MediaDetail.kt` `filteredCandidates()` filters by `artLang`).
2. A **Textless / With text** pill pair — which only **re-sorts**:
   `"textless" -> sortedByDescending { it.lang == null }`,
   `"withtext" -> sortedByDescending { it.lang != null }` (L2065–2069).

## Root cause
- The pills **sort, they don't filter**. And the default `artLang` is almost always a *single-language*
  bucket (resolved language, else `xx`/no-language — the never-empty ladder at L2045–2049). Within a
  single-language bucket every candidate has the same language-presence, so sorting by `lang == null`
  reorders nothing → the pills are a no-op in the common case.
- The two controls **overlap**: "Textless" is the same set as the **No language** language-chip
  (`lang == null`), and "With text" is the union of all the per-language chips (`lang != null`). Two
  controls over one field is the confusion at the heart of the bug.

## Goal
A single, intuitive, **working** filter: the operator can pick textless art, with-text art (any
language), or a specific language — and the gallery actually narrows to it.

## Requirements
### A. One single-select filter row (recommended)
Collapse the language-chip row and the Textless/With-text pills into **one mutually-exclusive filter
row** keyed on `iso_639_1`, each option a true filter applied in `filteredCandidates()`:

| Chip | Filter |
|---|---|
| **All** | no language filter |
| **Textless** | `lang == null` (replaces the old "No language" chip) |
| **With text** | `lang != null` (any language) |
| **EN / DA / …** | `lang == <code>` (with text in that language) |

- Remove the separate "prefer" pills and the `artPrefer` sort path; remove the now-redundant
  "No language" chip (folded into **Textless**).
- Each chip shows its candidate **count** (as today). Order: `All · Textless · With text · <languages
  sorted>` (only show **Textless**/**With text** when such candidates exist).

### B. Preserve the resolved-first, never-empty default
The default selection stays the Phase-47 ladder: the **resolved language** if it has candidates, else
**Textless**, else **All** — never an empty gallery. The amber "falling back to …" explainer line stays.

### C. Keep orthogonal controls untouched
**Hi-res only** and **Sort (highest-rated / resolution)** are independent of language and stay as-is
(applied after the language filter).

### D. Scope
The gallery is generalised across every artwork target (movie & series **poster/backdrop**, **season
posters**, **episode stills**), so the unified filter applies to all of them via the shared
`filteredCandidates()` / `renderArtGallery()`.

## Alternative (minimal) — if the two-row layout is kept
Make the pills genuine filters instead of sorts: `artPrefer == "textless" → lang == null`,
`"withtext" → lang != null`, applied **as a filter** in `filteredCandidates()`, and reconcile with the
language row (a prefer resets the language bucket to **All** so it isn't a no-op; selecting a specific
language clears the prefer). This fixes the bug with less restructuring but keeps the redundancy between
"Textless" and the "No language" chip. **A is preferred** for being unambiguous.

## Invariants
- One filter dimension for language/text presence — no two controls competing over `iso_639_1`.
- Never-empty fallback (Phase 47) preserved; textless (`xx`) stays its own bucket distinct from "All".
- Frontend renders server-provided candidates only; this is presentation filtering, no backend change.

## Out of scope
- Any backend / TMDB-client change — `ArtworkCandidate.lang` already carries `iso_639_1`.
- Upload / Paste-URL / stage-and-Save flow; the hi-res and sort controls' own behaviour.

## Design reference
`src/wasmJsMain/.../ui/MediaDetail.kt`: `filteredCandidates()` (~L2054), the chip row + counts
(~L2082–2092), the Textless/With-text pills (~L2144–2147), the chip wiring (~L2164–2176), the
`artLang`/`artPrefer` state (~L1901–1902) and the default ladder (~L2045–2049). Mockups:
`design/app/media.html` + `design/app/series.html` Artwork tab. (No code is changed by this spec.)
