# The workbench's studio and network pickers list every value and can be searched

Task: DEB-2859

> Owner, 2026-10-29 (DEB-2859): *"I manually set the studio to [a studio] for [a film]. But I can['t] use this
> studio in any filters as it's not listed in the workbench filter builder. Please figure out why that is and fix
> it."*

## Scope

Admin frontend only (`src/wasmJsMain/kotlin/dev/jellystructure/ui/Workbench.kt`). No route, no DTO, no schema,
no Ravilo client. The phase-140 query tree and its evaluation do not change.

**Why the studio is missing.** The hand-set studio was saved correctly: `PATCH /api/media/{id}/metadata` writes
`MediaItem.studio`, and `GET /api/media/meta-facets` (`MediaStore.buildMetaFacetsFrom`) counts it on the next
read, since its memo is keyed on `libraryVersion` and `MediaDetail` clears `FacetsCache` after the save. A
condition on that studio already matches the film (`ConditionEvaluator` reads the same field). The value is lost
at the last step. The workbench draws studio, network and every other facet without its own branch through one
generic `else` branch, and that branch keeps only the first 80 values: `wbItemsFor(c.facet).take(80)`. The API
sorts values by item count, most first, so a studio on a single title is near the end of the list and is cut.
Tags lost the same cap earlier, and gained a search box at the same time, because *"a fixed list can grow past any
cap"*. `cast_crew` has never had the cap. Studio and network kept it.

Measured on prod on 2026-10-29 (counts only, since the repository is public):

- **674** distinct studios. That counts primary and TMDB secondary production companies, which the picker lists
  together. Only 80 are drawn. The 80th value is on 2 titles, and **529** studios are on just one, so no studio
  on a single title can ever be picked. The hand-set studio ranks 674th of 674.
- **70** distinct networks. That is under the cap today but close to it. The next network that comes in from a
  scan or a hand edit could be cut silently in the same way.
- About 41 genres. Age ratings, audio languages and codecs are well under the cap and are out of scope.

**What this does not change.** In a channel content-row scope the picker lists only the values present in the
channel (R127, `wbNarrowed`). A studio on a title outside the channel is still left out there on purpose.

**Seen while tracing, not changed here.** An explicit *Rescan metadata* (`Scanner.rescanMetadata`) sets `studio`
from TMDB's first production company, so it replaces a hand-set studio, unlike genres (phase 94,
`mergeUserGenres`). Whether a hand-set studio should survive a rescan is a separate owner question. It is not
part of this spec.

## Requirements

- **FR-1.** The studio condition's value picker draws **every** studio in its scope, the library-wide list or the
  channel-narrowed one. No `take(N)` or any other cap. The order stays the API's (most titles first), and the
  count badge is unchanged.
- **FR-2.** The network condition's value picker does the same: every network in scope, no cap.
- **FR-3.** Both pickers have a search box above their chips, built like the tag picker's (`wb-vsearch` input,
  chips inside a `wb-vsection`, each chip with `data-search` set to its value in lower case, and a `wb-vempty`
  line). Typing hides the chips that don't contain the typed text, without re-rendering. The placeholders are
  *Search studios…* and *Search networks…*. When the scope has no values, the box is replaced by a muted *No
  studios in scope* / *No networks in scope*, like cast and crew.
- **FR-4.** A chip that is already selected (`c.values` contains it) still toggles exactly as it does today. Search
  only hides chips, so a selected chip stays selected while it is hidden and comes back still selected.
- **FR-5.** Every other facet that uses the generic branch (age rating, audio language, audio codec, metadata
  language, hero item, …) renders exactly as it does today, cap included.

No CSS is added: `wb-vsearch`, `wb-vsection` and `wb-vempty` already exist and the search handler in
`wbWireCondRows` is generic over every `.wb-vsearch`.

## Acceptance

On a library with more than 80 studios, the workbench's studio picker shows a search box. Typing a studio that is
on one title shows that chip, and selecting it makes the preview count that title. The network picker behaves the
same way. Nothing else in the workbench changes.
