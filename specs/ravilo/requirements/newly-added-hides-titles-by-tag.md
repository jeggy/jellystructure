# Newly Added hides titles by tag

Task: DEB-5003

> Owner, 2026-11-18 (DEB-5003): *"Just like we did with Continue Watching section in ravilo, adding a way to exclude
> tags in the jellystructure ravilo admin settings. Let's do the same for Newly Added section. So we can exclude things
> that have a specific tag/s and then they won't show up in the newly added section."*

This is [`continue-watching-hides-titles-by-tag.md`](continue-watching-hides-titles-by-tag.md) (DEB-1264) applied to
the Newly Added section. Every rule it settled carries over unless a requirement below says otherwise, and the editor
reuses its *Hide titles tagged* sub-row and picker exactly as built (DEB-1529 amendment). That spec uses the stand-in tag
`kids-tv`, and so does this one. No real tag is written here.

## Scope

The admin gets a **Hide titles tagged** list on the Newly Added section: on Home, on a collection whose rows are set
to *Custom*, and on a user's custom layout. The server leaves those titles out of every Newly Added row on that page.
A hidden title is still everywhere else: library, search, Continue Watching, the other rows, its collection page and
its own detail page.

**Backend and admin only. No Ravilo client changes.** The rows are built on the server, so every installed app gets
the filtered rows as soon as the backend ships.

**No design round.** The surface already exists and the owner decided it in DEB-1264's round 6: the same sub-row, the
same chips, the same picker, the same `.cw-*` rules already in `design/app/wf.css`. This spec only says where a second
copy of it goes.

Builds on, and does not change:

- **DEB-1264**, all of its owner decisions: the rule lives on the row in the layout editor (1), admin only (2), each
  layout and each *Custom* collection has its own list while *Same as Home* uses Home's (5), the picker (6), no new
  Save (7), the amber `0` and the switched-off behaviour (8).
- **R71 / R143.** The Newly Added section is one row that splits into *Movies* and *Series*, one merged row when
  *Merge* is on, or typed rows (Movies, Series, Music videos) placed in the layout.
- **R233.** A system row shows what is available on the page it is rendered on. The tag filter is applied **on top
  of** the collection's scoping and never replaces it.

Not in scope:

- **One list shared with Continue Watching.** The owner asked for "the same" for Newly Added, and DEB-1264 put the
  rule on the row, so Newly Added has its own list. Adding `kids-tv` to one does not add it to the other.
- **The hero, Recommended, genre and custom rows.** None of them changes.
- **Jellyfin's `AllowedTags`/`BlockedTags` policy** (142/258), which hides a title everywhere. Unchanged and unrelated.

## Requirements

### FR-1 — The list on the wire

- `RowConfig.hide_tags` already exists (DEB-1264 FR-1) and was read only on a `CONTINUE` row. It is now also read on
  a row of kind `NEWLY_ADDED`. On every other kind it is still ignored.
- `SystemNewly.hide_tags: List<String> = []` is new. It is a *Custom* collection's Newly Added list
  (`rows.system.newly`), next to the existing `SystemContinue.hide_tags`.

On save, the server tidies the new field the way it tidies the others: it trims each entry, drops empty entries, and
collapses case-insensitive duplicates, keeping the first spelling. The field is additive. Installed clients decode
`RaviloConfig` with `ignoreUnknownKeys` and never write a whole config, so nothing reaches client code and nothing can
strip it. Existing configs read as empty, so nothing changes until the admin adds a tag.

### FR-2 — Matching

The same as DEB-1264 FR-2: a title is hidden when it carries a tag equal to an entry in the list, compared
**case-insensitively on the whole name**. A Newly Added card is a movie, a series or a music video, and the tags are that
item's own. Substring matches never count, and a tag that no title carries matches nothing.

### FR-3 — One list per Newly Added section

A layout has one Newly Added list: the `hide_tags` of its **first `NEWLY_ADDED` row in `order`**, whether that row is
switched on or not. That list applies to **every** Newly Added row the layout produces:

- the split *Movies — Newly Added* and *Series — Newly Added* rows;
- the merged *Newly Added* row when `mergeNewlyAdded` is on;
- any typed Newly Added row (Movies, Series, Music videos).

A `hide_tags` on a later `NEWLY_ADDED` row is ignored, and the editor never writes one there. This is the one place
this spec departs from DEB-1264 FR-3, where each Continue row reads its own list. A Newly Added section can be several
rows, and the owner asked for the section.

Which layout's list applies to which page is the same as DEB-1264 FR-3:

| Page | List used |
|---|---|
| Home | The Home layout's Newly Added list |
| A collection on *Same as Home* | That same Home list, on top of the collection's own scoping (R233) |
| A collection on *Custom* | That collection's `rows.system.newly.hide_tags`, on top of its scoping. Home's list does **not** apply there |

The layout is the user's custom layout if they have one, otherwise the global layout.

### FR-4 — Filter, then cap

The tag filter runs on the page's candidate list (already scoped to the collection on a collection page) **before** the
row is ordered and cut to its limit of 30. A hidden title never takes a slot: the row still shows up to 30 titles, and
the next newest title takes the hidden one's place.

Newly Added rows carry no See all, so there is no See all to filter.

Every place that builds or tests these rows applies the same filter:

- `buildRows`: the *Custom* collection branch (`addNewlyAddedRows` with `sys.newly`), the Home and *Same as Home*
  branch's `NEWLY_ADDED` rows, and the merged row injected after the loop;
- the collection-has-content check (`channelHasAnyMatch`). A collection must not be counted as having content only
  because of a Newly Added title its list hides. Today that check returns true for any *Custom* collection whose Newly
  Added row is on, and for any layout with `mergeNewlyAdded` on. Both become a check that at least one title survives
  the list.

### FR-5 — All hidden

If the list hides every candidate for a Newly Added row, that row and its header are left out, the same as an empty
Newly Added row today. With the split on, *Movies* and *Series* are decided separately: hiding every series leaves the
*Movies* row standing.

### FR-6 — A change shows up at once

The same as DEB-1264 FR-6. The feed caches key on `config.hashCode()` and the list is part of the config, so saving a
layout that changes it makes the next Home or collection read reflect it. A test pins it. No new push signal.

### FR-7 — The editor

The *Hide titles tagged* sub-row of DEB-1264 FR-7, unchanged in look and behaviour, appears in two more places:

- **Layout → Content rows**, under the first Newly Added system row in order, for the global layout and for a user's
  custom layout;
- **Edit collection → Content rows → *Custom* → System rows**, under that collection's Newly Added row.

It appears **nowhere** for a collection on *Same as Home*, because Home's list applies.

The Continue Watching sub-row stays where it is, so each of those editors now carries two independent sub-rows. Each
reads and writes only its own list. **Only one picker is open at a time across both**: opening one closes the other.

**While the row carrying it is switched off**, the sub-row is hidden and its tags are kept, as in DEB-1264 FR-7. On Home
a layout can still show Newly Added rows while that row is off: a typed row may be on, or *Merge* may be on. The sub-row
stays visible whenever the layout shows any Newly Added row, so a list that is filtering something is never hidden from
the admin.

The picker, its strings, its keys, the amber `0`, the catalogue and the no-catalogue state are DEB-1264 FR-8 and FR-10
and the DEB-1529 amendment, unchanged. No string is added.

### FR-8 — Saving, loading and errors

The same as DEB-1264 FR-9 and the DEB-1529 amendment's *After a Save*. The list rides the existing layout Save and the
collection editor's Save. After a Save the editor redraws **both** lists from the server's read-back, so what the server
tidied is what the editor shows. If the read fails, the editor keeps what it sent.

## Acceptance

On a backend with this change and a stand-in library in which `kids-tv` is on two recently added series and one
recently added movie:

1. **Home filter.** With the global layout's Newly Added list set to `kids-tv`, a user on the global layout sees none of
   the three in *Movies — Newly Added* or *Series — Newly Added*. Each row still shows up to 30 titles. The three still
   appear in search, browse, Continue Watching, the other rows and their own detail pages.
2. **Merged.** With *Merge* on, the single *Newly Added* row also leaves out all three.
3. **Independent of Continue.** The Continue Watching list stays empty and a `kids-tv` title in progress still shows in
   Continue Watching.
4. **Undo.** Removing the tag and saving brings all three back at their positions on the next Home load, without
   waiting out a cache TTL.
5. **Custom collection.** A collection on *Custom* with an empty Newly Added list shows those titles in its Newly Added
   rows, scoped to the collection, while Home hides them. Giving it `cartoons` hides only `cartoons` titles there.
6. **Same as Home.** A collection on *Same as Home* hides exactly what Home's list hides, within its own scope.
7. **Custom layout.** A user with a custom layout whose Newly Added list hides `kids-tv` and `cartoons` sees neither in
   Newly Added. A user on the global layout is unaffected by that user's list.
8. **All hidden.** A collection whose only titles all carry a hidden tag shows no Newly Added row and is not counted as
   having content because of one.
9. **No client change.** Steps 1 and 5 hold on an installed Android TV build that predates this change.
10. **Editor.** Both sub-rows appear where FR-7 says. Each edits only its own list. Opening one picker closes the other.
    A Save redraws both from the server.
