# Continue Watching hides titles by tag

Task: DEB-1264

> Owner, 2026-10-15 (DEB-1264): *"Let's introduce some way of skipping items in the "Continue watching" section.
> Example I would like to not show anything that has a tag of [a children's-TV tag]. Let's try to get the design tool
> in this as well."*

The owner drew and decided this himself in the design tool on 2026-10-16 (design round 6, DEB-1265/DEB-1266). The
package is quarantined at `design/incoming/2026-10-16-continue-hidden-tags/`, and the decisions below are the ones
he made at the keyboard. The investigation is the `investigation` document on DEB-1264. This spec uses the stand-in
tag `kids-tv` throughout. The real tag is not written here.

## Scope

The admin gets a **Hide titles tagged** list on every Continue Watching row: on Home, on a collection whose rows are
set to *Custom*, and on a user's custom layout. The server leaves those titles out of that row and out of its
See all. A hidden title is still everywhere else: library, search, the other rows, its collection page, and its own
detail page, which still offers Resume. Its progress is never touched.

**Backend and admin only. No Ravilo client changes.** The row is built on the server (R219), so every installed app
gets the filtered row as soon as the backend ships.

Builds on, and does not change:

- **R219.** The canonical Continue list per Jellyfin user, and FR-R219-5's *filter, then cap*. FR-R219-6: the row and
  its See all never disagree about membership.
- **R233.** A system row always shows what is available on the page it is rendered on (FR-R233-1/3). The canonical
  list stays library-wide and cached per user (FR-R233-5). The tag filter is applied **on top of** the channel
  filter; it never replaces it.
- **R51 / R59 / R143.** The layout layer (hero, collections, rows) is full-replace per user. A collection's rows are
  either *Same as Home* (`inherit`) or *Custom* with their own system rows.
- **R162.** Not used. The owner put the rule on the row, not in the Behaviour overlay (decision 1 below).

Not in scope:

- **Hiding a single card** ("Remove from Continue Watching"). Skipped in the round. It stays deferred, as R219 left it
  on 2026-08-30.
- **A viewer-side control** on any Ravilo client.
- **Jellyfin's `AllowedTags`/`BlockedTags` policy** (142/258), which hides a title everywhere. Unchanged and
  unrelated.

## Owner decisions (design round 6, 2026-10-16)

1. **The rule lives on the Continue Watching row** in the layout (rows) editor. It is not a Behaviour preference.
   This was the alternative to the investigation's lean.
2. **Admin only.** No viewer control on any client.
3. **Single-card hiding: skipped.**
4. **See all: hidden there too, silently.** No footer and no "2 hidden" string.
5. **Every Continue Watching row has its own list.**
   - Home's row has one.
   - A collection on *Same as Home* uses Home's row, so it also uses Home's tags and shows no sub-row.
   - A collection on *Custom* picks its own list, which starts empty.
   - A user's custom layout works the same way.
6. **The picker** shows the six most-used tags when nothing is typed. Typing searches by substring. A match is picked
   as the exact tag.
7. **No new Save.** The list is part of the layout and is saved by the existing layout Save.
8. **A tag carried by 0 titles keeps its amber `0`.** When the row is switched off, its sub-row is hidden and its
   tags are kept, unchanged when the row comes back on.

The owner also rewrote one premise: **there are no kids profiles**. There are only collections, content rows and
per-user layout overrides, and "Kids" is just a user name.

## What this costs

**The per-user case is a forked layout.** Under decision 1 a hidden-tag list belongs to a *row*. A user on the global
layout therefore cannot have a list of their own. To give one user a different list, the admin creates a custom
layout for that user, which copies the global one, tags included (R51), and from then on that user's hero,
collections and rows are edited separately. The owner chose this knowingly (decision 5 and the rewritten premise).

**A row can disappear.** If every title in a viewer's Continue list is hidden, the row and its header are omitted,
the same as any empty Continue row today. The round drew this state (`filter-tv-home-row-omitted.png`) and the owner
accepted it.

## Requirements

### FR-1 — The list on the wire: `hide_tags`

Two layout models each get one additive field. Both default to empty and both are additive.

- `RowConfig.hide_tags: List<String> = []`. It is read **only** on a row of kind `CONTINUE`. On every other kind it is
  ignored, and the editor never writes it there.
- `SystemContinue.hide_tags: List<String> = []`. This is a *Custom* collection's own Continue Watching row
  (`rows.system.continue`).

Each entry is a tag name exactly as the catalogue spells it. On save, the server trims each entry, drops empty
entries, and collapses case-insensitive duplicates, keeping the first spelling.

Installed clients decode `RaviloConfig` with `ignoreUnknownKeys` and never write a whole config: they send only the
behaviour subset. A new field therefore reaches no client code and nothing can strip it. No field is renamed,
retyped or removed.

The global layout's row starts with an empty list. Existing configs read as empty, so nothing changes until the
admin adds a tag.

### FR-2 — Matching

A Continue entry is hidden by a list when its `mediaItem` carries a tag equal to an entry in the list, compared
**case-insensitively on the whole name**. The `mediaItem` is the movie, or the series an episode belongs to.

- Substring matches never count. `kids` does not hide a title tagged `kids-tv`.
- Episodes have no tags of their own, so a tag on a series hides both its resume cards and its next-up card.
- A tag that no title carries matches nothing and is not an error.

### FR-3 — Which list applies where

The list is resolved from the layout the viewer actually sees: the user's custom layout if they have one, otherwise
the global layout. Then:

| Page | List used |
|---|---|
| Home | The Home layout's Continue Watching row's `hide_tags` |
| A collection on *Same as Home* | That same Home row's list, on top of the collection's own scoping (R233) |
| A collection on *Custom* | That collection's `rows.system.continue.hide_tags`, on top of its scoping. Home's list does **not** apply there |

If a layout contains more than one `CONTINUE` row, each row uses its own list. The admin offers only one. See all
(FR-5) uses the first one in `order`.

### FR-4 — Filter, then cap

The tag filter runs at the view stage, **after** the canonical list and the channel filter and **before** the cap of
20 (FR-R219-5). A hidden title never takes a slot, and `seedTotalCount` counts what is left after the filter, so the
See all tile is honest.

The canonical list, its 60-second background rebuild and its per-user cache are untouched (FR-R233-5).
**No playstate is written**: removing a tag brings the title back at its true position in the order.

Every place that builds or tests this row applies the same filter:

- `buildRows`, both the custom-collection branch and the Home/inherit branch;
- the collection-has-content check (`continueRowNonEmpty`), so a collection is not counted non-empty because of a
  title its own row hides;
- `continueWatchingAll` (FR-5).

### FR-5 — See all, silently

`continueWatchingAll(device, channelId)` applies the list FR-3 picks for that page: with no `channelId` it uses Home's
list, and with one it uses that collection's list. See all is otherwise unchanged: uncapped, and in the same order as
the row. It adds no count, footer or string, so the row and See all can never disagree (FR-R219-6). The request
already carries `channelId`, so no client change is needed.

### FR-6 — A change shows up at once

Saving a layout that changes a list makes the next Home, collection or See all read reflect it. It must not wait out
the feed cache TTL.

The feed caches already key on `config.hashCode()`, and the list is part of the config, so this holds as long as the
field lives on the models in FR-1. A test pins it. A `hide_tags`-only change sends exactly the push a layout Save
sends today, and no new signal. Whichever push that is, the next Home, collection or See all fetch is correct.

### FR-7 — The editor: the *Hide titles tagged* sub-row

In the admin's Ravilo config, a dashed sub-row sits directly under the Continue Watching system row. In order it holds:

1. the label **Hide titles tagged**;
2. one chip per tag: the tag name in the monospace face, its title count, and a **✕** whose accessible name is
   **Remove {tag}**;
3. a **＋ Add tag** button.

It has no tint and no explanatory text, and it is drawn like its neighbours. It appears in three places:

- **Layout → Content rows**, for the global layout and for a user's custom layout;
- **Edit collection → Content rows → *Custom* → System rows**, under that collection's Continue Watching row;
- **nowhere** for a collection on *Same as Home*, because Home's row and its tags apply.

**While the Continue Watching row is switched off**, its sub-row is not shown at all, not even greyed out. Its stored
tags are kept and return unchanged when the row is switched back on.

**A chip's count** is the number of titles currently carrying that tag. A tag carried by **0** titles keeps its chip,
and the count shows `0` in amber. The admin decides whether to remove it.

The frames are `layout-admin-desktop-global-empty`, `-global-one-tag` (dark, light, narrow), `-longest-and-zero`
(desktop, narrow), `-eyd-custom`, and `collection-admin-desktop-olivar-continue-off`, `-continue-on`,
`-continue-tags`, plus the narrow variant.

### FR-8 — The picker

**＋ Add tag** opens a picker anchored under the sub-row. Only one picker is open at a time.

- **The search field** has the placeholder **Search {n} tags…**, where `{n}` is the size of the tag catalogue.
- **With nothing typed**, the header reads **Most used tags**, followed by the six tags with the most titles.
- **Typing** filters to tags whose name contains the text, case-insensitively, with the matched part marked. Tags
  that *start with* the text come first, then the rest by title count. The header reads
  **{n} tags contain "{q}"**.
- **Each result** shows a checkbox (ticked if it is already in the list), the name, and **1 title** / **{n} titles**.
  A count of `0` is in amber.
- **Picking a result** adds that exact tag, or removes it if it was ticked. The picker stays open, so several tags can
  be picked in one pass.
- **No match** shows **No tag called "{q}".** and, under it, **Tags come from your library — nothing is created
  here.** The picker never creates a tag.
- **The footer** always reads **Hides the exact tag only — never tags that merely contain it.**
- **Keys:** ↑/↓ move the highlight, Enter toggles the highlighted tag, and Esc closes the picker and returns focus to
  **＋ Add tag**. A click outside closes it.

The catalogue is the server's tag list with title counts, as the admin's tag views already read it. It covers about
2 700 tags, which is why the picker searches rather than lists. The frames are `layout-admin-desktop-picker-open`,
`-picker-search-kid` (dark, light, narrow), `-picker-no-results` and `collection-admin-desktop-olivar-picker-open`.

### FR-9 — Saving, loading and errors

The list rides the **existing** layout Save, with no Save of its own and no autosave. Picking or removing a tag
changes only the editor's unsaved layout, exactly as toggling a row does today. The constitution's server-pushed
state holds: after a Save the editor shows what the server echoes back, and nothing is shown as saved before then.

The round did not draw loading, saving or error, and by the owner's decision those are the layout Save's existing
states, unchanged:

- If the catalogue cannot be loaded, the picker shows its search field and the no-match lines for any query, and the
  existing chips still show their names. Their counts show `–` rather than a guessed number.
- A failed Save is reported the way the layout Save already reports one.

### FR-10 — Strings

The admin is English-only, so the strings in FR-7 and FR-8 ship in English. The round also drafted Danish and Faroese
versions in its report (`continue-hidden-tags-report.md`, "New UI strings"). They are kept there and not shipped,
because the admin has no string table to put them in.

**Not part of this spec:** the five Ravilo support strings that a separate section of the same round was asked for
did not come back. They belong to DEB-1500's work and move to a later design round.

### FR-11 — Styling

The `.cw-*` sub-row, chip and picker rules come from the round's `ravilo-builders.css`, which is a mockup stylesheet
and is **not** copied into the distribution. Rannvá promotes them by hand into a production sheet (`design/app/wf.css`
or `app.css`). If she uses a new file instead, that takes two more edits: `syncDesignAssets`' include list and the
`<link>` set in `index.html`. The admin code adds no inline styling of its own (CLAUDE.md, "production CSS").

## Acceptance

On a backend with this change and a stand-in library in which `kids-tv` is on three series that have Continue
entries:

1. **Home filter.** With the global layout's Continue row set to hide `kids-tv`, a user on the global layout sees
   those three titles neither in Home's Continue row nor in its See all. The row's See all tile count drops by three.
   The three titles still appear in search, browse, their collection's other rows, and their own detail pages, and
   Resume on a detail page still resumes at the stored position.
2. **Undo.** Removing the tag and saving brings all three back at their original positions on the next Home load,
   without waiting out a cache TTL.
3. **Custom collection.** A collection on *Custom* with an empty list shows those titles in its Continue row (scoped to
   the collection), even while Home hides them. Giving it `cartoons` hides only `cartoons` titles there.
4. **Same as Home.** A collection on *Same as Home* hides exactly what Home's row hides, within its own scope.
5. **Custom layout.** A user with a custom layout whose Continue row hides `kids-tv` and `cartoons` sees neither tag.
   A user on the global layout is unaffected by that user's list.
6. **All hidden.** If every entry in a viewer's list is hidden, the Continue row and its header are absent on Home.
   A collection whose only content is a hidden Continue title is not counted as having content because of it.
7. **No client change.** Steps 1 and 3 hold on an installed Android TV build that predates this change.
8. **Editor.** The sub-row, its switched-off behaviour, the amber `0`, the picker's states and its keys match FR-7 and
   FR-8 and the frames named there, on desktop and at narrow width.
