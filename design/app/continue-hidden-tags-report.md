# continue-hidden-tags — report (final)

No phase number. Where a spec would cite itself, the place is left blank.

## Decisions applied

1. **The rule lives on each Continue Watching content row.** Under the system row there's one sub-row: *Hide titles tagged*, the tags as chips, then *＋ Add tag*. The row is drawn like Newly Added Movies and Newly Added Series: no tint and no explanatory text.
2. **Admin-only**, in Jellystructure's Ravilo layout configuration.
3. **Single-card hiding:** skipped.
4. **See all:** hidden there too, silently.
5. **Every Continue Watching row has its own tags (Q1, Q4).**
   - The Home row has its own list.
   - A collection on *Same as Home* reuses the Home rows and their tags, so it has no sub-row of its own.
   - A collection on *Custom* has its own Continue Watching row. Its tags are picked again there, can differ from Home's, and start empty.
   - If that row is off, there is no sub-row. Olivar is such a collection, with Continue Watching off in this case.
   - A user's custom layout (Eyð) works the same way.
6. **Picker with nothing typed: the six most-used tags (Q2 → b).**
7. **No new Save (Q3).** The list is saved by the existing layout Save.
8. **No kids functionality (Q6).** Only collections, content rows and per-user layout overrides exist. "Kids" in the stand-in data is just a user name.

## Files

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `app/Continue Hidden Tags - Directions.html` | 7464 | 63 | `<!DOCTYPE html>` | `</html>` |
| `app/continue-hidden-tags.css` | 21362 | 214 | `/* continue-hidden-tags — the one stylesheet for the decided-round canvas and its PNG frames.` | `.adm.nar .cond .tagpick{right:0;width:auto}` |
| `app/continue-hidden-tags.js` | 21193 | 181 | `/* continue-hidden-tags — decided round. Renders every frame of the canvas and of the PNG export.` | `})();` |
| `app/continue-hidden-tags-shots.html` | 1516 | 28 | `<!DOCTYPE html>` | `</html>` |

Report: `app/continue-hidden-tags-report.md`. It doesn't measure itself.

### PNGs — 23 files, all under 192 KiB (largest 126293 bytes)

- `continue-hidden-tags-png/continue-hidden-tags-collection-admin-desktop-olivar-continue-off.png` — 66553 bytes
- `continue-hidden-tags-png/continue-hidden-tags-collection-admin-desktop-olivar-continue-on.png` — 75030 bytes
- `continue-hidden-tags-png/continue-hidden-tags-collection-admin-desktop-olivar-continue-tags.png` — 76512 bytes
- `continue-hidden-tags-png/continue-hidden-tags-collection-admin-desktop-olivar-picker-open.png` — 94886 bytes
- `continue-hidden-tags-png/continue-hidden-tags-collection-admin-narrow-olivar-continue-tags.png` — 73114 bytes
- `continue-hidden-tags-png/continue-hidden-tags-filter-tv-home-after-eyd.png` — 104548 bytes
- `continue-hidden-tags-png/continue-hidden-tags-filter-tv-home-after.png` — 104138 bytes
- `continue-hidden-tags-png/continue-hidden-tags-filter-tv-home-before.png` — 101946 bytes
- `continue-hidden-tags-png/continue-hidden-tags-filter-tv-home-row-omitted.png` — 89525 bytes
- `continue-hidden-tags-png/continue-hidden-tags-filter-tv-see-all.png` — 43976 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-eyd-custom.png` — 126293 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-global-empty.png` — 102500 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-global-one-tag-light.png` — 105349 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-global-one-tag.png` — 104165 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-longest-and-zero.png` — 107355 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-picker-no-results.png` — 107151 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-picker-open.png` — 107166 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-picker-search-kid-light.png` — 109655 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-desktop-picker-search-kid.png` — 103449 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-narrow-global-one-tag.png` — 82106 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-narrow-longest-and-zero.png` — 86433 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-narrow-picker-search-kid.png` — 76298 bytes
- `continue-hidden-tags-png/continue-hidden-tags-layout-admin-narrow-strings-fo.png` — 82698 bytes

## New UI strings (en / da / fo — Danish and Faroese are drafts)

- **facet** — Continue row · sub-row label
  - en: Hide titles tagged
  - da: Skjul titler med tagget
  - fo: Fjal heiti merkt við
- **add** — Continue row · add button
  - en: Add tag
  - da: Tilføj tag
  - fo: Legg frámerki afturat
- **remove** — tag chip ✕ · accessible name
  - en: Remove {tag}
  - da: Fjern {tag}
  - fo: Strika {tag}
- **ph** — picker · search placeholder
  - en: Search {n} tags…
  - da: Søg i {n} tags…
  - fo: Leita í {n} frámerkjum…
- **top** — picker · nothing typed: header
  - en: Most used tags
  - da: Mest brugte tags
  - fo: Mest brúktu frámerki
- **res** — picker · results header
  - en: {n} tags contain “{q}”
  - da: {n} tags indeholder “{q}”
  - fo: {n} frámerki innihalda “{q}”
- **hint** — picker · footer
  - en: Hides the exact tag only — never tags that merely contain it.
  - da: Skjuler kun det præcise tag — aldrig tags, der blot indeholder det.
  - fo: Fjalir bert akkurat hetta frámerkið — ongantíð frámerki, sum bara innihalda tað.
- **cnt1** — picker · count, one
  - en: 1 title
  - da: 1 titel
  - fo: 1 heiti
- **cntN** — picker · count, many
  - en: {n} titles
  - da: {n} titler
  - fo: {n} heiti
- **none** — picker · no results
  - en: No tag called “{q}”.
  - da: Intet tag hedder “{q}”.
  - fo: Einki frámerki eitur “{q}”.
- **none2** — picker · no results, second line
  - en: Tags come from your library — nothing is created here.
  - da: Tags kommer fra dit bibliotek — her oprettes intet.
  - fo: Frámerki koma úr savninum — einki verður stovnað her.

## States drawn

- **Home, global layout:** empty (the default); one tag (dark, light, narrow, narrow Faroese).
- **Picker:** nothing typed, showing the most-used tags; search "kid" (dark, light, narrow); no results.
- **Tags:** the longest tag next to a tag carried by 0 titles (desktop, narrow).
- **Eyð's own layout:** kids-tv and cartoons.
- **Collection Olivar, rows set to Custom:**
  - Continue Watching off, so no sub-row.
  - On and empty.
  - Hiding cartoons, independently of Home's kids-tv (desktop, narrow).
  - Picker open with nothing typed.
- **TV Home, focus on the first card:**
  - A user on the global layout: 8 → 5 (cards 1, 3, 5, 7, 8).
  - Eyð: 4 (cards 1, 3, 5, 7).
  - Every card hidden: the row and its header are omitted.
- **TV See all:** silent, 5 cards.
- **Not drawn:** loading, saving and error. These come from the existing layout Save.
- **Offline:** not applicable on the admin.
- **Chromecast:** nothing drawn (no input). **Tizen:** nothing drawn (paused).

## Also decided

- **Q5 — A tag carried by 0 titles:** keep the amber "0" count on the chip.
- **Q7 — Continue Watching switched off:** the sub-row is hidden and the saved tags are kept, so they return unchanged when the row is switched back on. Nothing is shown greyed out.

Nothing is left open.

## Carried into the full designs

The decided design is now live in the admin mock. It's the same component in both places, and both places follow the same rules: hidden tags are part of the layout and saved by the existing Save, and the tag row is hidden (with its tags kept) while the row is switched off.

- **Home content rows** (`app/ravilo-config.html` → Layout → Content rows): the Continue Watching system row has its "Hide titles tagged" sub-row, seeded with `kids-tv`.
- **A collection's own rows** (Edit channel → Content rows → **Custom** → System rows): the collection's Continue Watching row has its own sub-row, starting empty and stored per collection. A collection on **Same as Home** shows none, because it uses the Home row and its tags.
- **Picker behaviour:** with nothing typed it shows the six most-used tags. Typing searches by substring, and a match is picked as the exact tag. Arrow keys, Enter and Esc work. A tag carried by 0 titles shows an amber "0".

### New file

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `app/cw-hide-tags.js` | 5451 | 66 | `/* Continue Watching · "Hide titles tagged" — the sub-row under a Continue Watching system row.` | `})();` |

## Existing files edited in place

- `app/ravilo-config.html`:
  - The Continue Watching row in `#rowlist` gains the `hascond` class and a `#cw-home-tags` mount.
  - `cw-hide-tags.js` is loaded before `ravilo-builders.js`.
  - A 3-line mount script follows it.
- `app/ravilo-builders.js`:
  - The collection editor's Continue Watching `.cf-sysrow` gains `hascond` and a `[data-cw-tags]` mount.
  - One mount line sits next to the existing `[data-systog]` binding, storing the tags on `state.rows.system.continue.hideTags`.
- `app/ravilo-builders.css`: the `.cw-*` sub-row, chip and picker rules are added after `.cf-sysrow + .cf-sysrow`.
- `app/library.html`, `app/media.html`: `cw-hide-tags.js` is loaded before `ravilo-builders.js`, because both can open the shared collection editor.

In this round's own files, I rewrote the doc, CSS, JS and report, and deleted the superseded PNGs.

## Confirmation

Nothing named `STATUS.md` or `CLAUDE.md` was created, and nothing under `specs/` or `scripts/`.
