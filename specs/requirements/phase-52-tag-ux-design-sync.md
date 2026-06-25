# Phase 52 — Tag UX: design sync + JS/normal distinction in filters (FR-TG2)


## Problem
After [Phase 51](phase-51-tag-population-lifecycle.md) made tags actually populate, the tag **UI**
lagged the design and didn't surface the JS-vs-normal distinction where it matters:
- The media-detail tags section worked but was built from ad-hoc inline styles and diverged from the
  `design/app/media.html` target.
- The filter workbench + Library tag dropdown already filtered both JS and normal tags (one flat
  `item.tags` list), but showed every tag as a plain chip — you couldn't tell a Jellystructure tag
  (survives re-sync, has a color) from a TMDB/Jellyfin tag.

## Load-bearing correction
The admin frontend is **not** Tailwind. `src/wasmJsMain/resources/index.html` links only `wf.css` +
`app.css`, and `build.gradle.kts` → `syncDesignAssets` copies those **verbatim from `design/app/`**
into the build. The mockup CSS *is* the production CSS. So syncing the app to the design = use the
shared classes and add the few tag classes that previously lived only in the mockups' per-page
`<style>` blocks. (The old "Tailwind, classes scanned from Kotlin" note was wrong — corrected in
`constitution.md` / `plan.md` / `CLAUDE.md`.)

## Decisions
- **One grouped Tag facet** — keep the single `Tag` facet; JS tags get a colored dot and sort under a
  "Jellystructure tags" group, then "Other tags". The condition model and `tags=` OR-filtering are
  unchanged (filtering already worked; this is presentation only).
- **JS-ness marked server-side**: enrich the tag facet item with a nullable `color` (presence ⇒ JS
  tag), reusing `jsTagStore`. One request feeds both the Workbench and Library; no client-side join.
- Refresh all three tag surfaces for consistency (media detail, filter pickers, Metadata Tags tab).

## Implementation
**Backend** — `MediaStore.metaFacets()` builds a lowercase name→color map from `jsTagStore.all()`,
sets `color` on tag facets, sorts JS-first; `TrackFacetItem` (`MediaStore.kt` + `MediaApi.kt`) and the
route-local `FacetItem` (`MediaRoutes.kt /meta-facets`) gain `color: String? = null` (other facets
leave it null). Lowercase keys because `list()` OR-filters tags case-insensitively.

**CSS** — promoted into `design/app/wf.css`: `.tag-dot`, `.tag-dot-lg`, `.rm`, `#tags-section.dirty`
(outline ring), `.tag-card`, `.swatches`/`.swatch`/`.swatch.on`.

**Media detail** (`MediaDetail.kt`) — Tags is now its own `.card#tags-section`: header with a "manage
tags →" link (`#/metadata?tab=tags`), a `.dash` rule, a `.pill-row` of `.chip.tag-chip` (dotted JS via
`.tag-dot`, plain otherwise) with `.rm` remove, an input + `.card` dropdown + "Jellystructure tags
(dotted) survive re-syncs." helper. Dirty state toggles the `dirty` outline-ring class instead of the
text-field border-left; ids/`.tag-rm`/`data-tag` hooks and the diff (`≠`) popup are unchanged.

**Workbench** (`Workbench.kt`) — `wbTagColor()` + a tag special-case in `wbRenderConds`: JS values
dotted and grouped ("Jellystructure tags" / "Other tags"); `.wb-vchip` → inline-flex, new `.wb-vgroup`
label style. Click wiring unchanged.

**Library** (`Library.kt`) — `buildAudioDropdown` keys off `item.color`: prepends a `.tag-dot` and
groups JS vs other when both are present; non-tag facets (color null) render flat as before — no
call-site change.

**Metadata Tags tab** (`Metadata.kt`) — JS cards → `.card.tag-card` + `.tag-dot-lg` + `.badge.info`;
modal color swatches → `.swatches`/`.swatch`/`.swatch.on` (selection via the `on` class). Wiring hooks
(`.js-tag-card`/`data-name`, `.color-swatch`) preserved.

## Notes / non-goals
- A JS tag defined but applied to no item won't appear in the facet (it's count-based) — filtering by
  it would match nothing anyway.
- Three facet DTOs bridge by JSON field name only; `color` was added to all three with the exact name.
  `shared/.../tv/Models.kt` `FacetItem(name,count)` is unrelated and untouched.
