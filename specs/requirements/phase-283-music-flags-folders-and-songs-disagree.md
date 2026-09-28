# Phase 283 — Music: say when folders and songs disagree, and let the admin fix it

> Owner, 2026-09-28, on an artist page: *"When opening an artist … I see this. How can we help me to understand
> what's happening here? I'm not sure if it's a bug or something I should fix with manual matching etc? At least it
> looks very ugly and confusing."* Then, once the cause was explained: *"We want in jellystructure go with the normal
> warnings/suggestions approach. So in jellystructure it should find these issues and flag them, so I can manually
> fix it."*

## Status

`Planned` — written 2026-09-28. Builds on **275** (the music library), **276** (the match ladder), **277** (covers
and `album.nfo`) and **278** (the admin's Album and Artist pages, the Library's Music kind, the attention list).

## What the household's library showed (2026-09-28)

Thirty albums. Three shapes the page could not explain:

1. **Ten folders, one album.** A band's folder holds ten folders named after its singles and EPs
   (`<Single> (Single)`, `<EP> (EP)`), one or two B-sides each. Every file's *Album* tag names the same 25-track
   B-sides compilation. Jellyfin makes one album per folder, so the artist has ten albums with the same name. 276's
   ladder searched that name, found the compilation, and each file sat at its position with its length, so *1 of 1
   tracks agree* was a pick — ten times. 277 then wrote the compilation's cover and an `album.nfo` into all ten
   folders. The Artist page shows ten identical tiles whose titles are cut off.
2. **The folder and the album tag name different things.** A folder named after a single (`<A> - <B> (Single)`)
   whose files say `<A> EP`. MusicBrainz lists it under the folder's name, so the search by the tag finds nothing:
   *unmatched*, and nothing on the page says why.
3. **A song in someone else's folder.** One file in `Various Artists/<Compilation> (2002)` whose tags say album
   *Misc Songs*, album artist the band. It shows on the band's page as an album called *Misc Songs*.

None of these is jellystructure's to correct on its own: whether ten single folders are ten singles or one
compilation is the owner's knowledge, not the files'. What was missing is the telling.

## Requirements

**FR-283-1 — Two flags, from what the scan already stores.** Computed on read from the stored albums (no model, no
new table, no request to anything), the same way the attention list is:

- **Several folders, one album** (`shared_album`) — two or more live albums, in different folders, whose album artist
  and title are the same (compared case-, punctuation- and space-blind), **or** whose match is the same MusicBrainz
  release group.
- **The folder and the songs disagree** (`folder_disagrees`) — the album folder's name and the *Album* tag name
  different things, or the artist folder above it and the *Album artist* tag do. Before comparing, a folder name
  loses what folder names carry and tags do not: bracketed and parenthesised parts (`(Single)`, `[FLAC]`, `(2004)`),
  a four-digit year, and a leading `<Artist> - ` or `<Year> - `. The two agree when either contains the other. The
  artist comparison is skipped when the album sits directly in the library folder, and a disc folder (`CD1`,
  `Disc 2`) counts as its parent.

On the household's library these flag the ten folders of shape 1 (both flags), and shapes 2 and 3 (the second
flag), and nothing else.

**FR-283-2 — The matcher does not give a second folder an album another folder has.** A pick made by searching or by
sound, for an album not already matched, whose release group another live folder is already matched to — or was
picked for earlier in the same run — is not taken: the album becomes *needs you*, with the note *"Another folder is
already matched to this album: {folder}"*. A pick from MusicBrainz ids in the files themselves is taken (the files
say so); the flag still shows. An album already matched is never un-matched by this rule.

**FR-283-3 — Where the flags show** (the attention list's usual places):

1. **Dashboard / attention list** — two entries, *Albums in several folders* and *Albums whose folder and songs
   disagree*, each with its count and one sentence of what to do.
2. **The dock** — one entry per flagged album, first in its order, reading *"♪ album · N folders say they are this
   album"* or *"♪ album · the folder and the songs disagree"*.
3. **The Album page** — a card above the tabs per flag: what the folder says and what the songs say, side by side;
   the other folders by name, each a link; and what the admin can do. When the cover and `album.nfo` in the folder
   came from a match the flag puts in doubt, it says so.
4. **The Artist page** — a flagged album's tile carries a warning chip, and an album whose title repeats on the page
   shows its folder's name under the title, so ten tiles are ten different things.
5. **The Library's Music kind** — a *Check* facet: *one album in several folders* · *folder and songs disagree* ·
   *nothing to check*.

**FR-283-4 — What the admin can do, from the card.** Nothing moves, renames or retags a file.

- **Find match… by the folder's name** — Find match opens with the folder's cleaned name as the album, so a folder
  named after its single searches for the single.
- **Find match…** as it is, and the other folders' pages.
- **This is right** — the flag goes away for this album. It is kept with what was dismissed (the other folders, or
  the two names); if that changes — a folder renamed, a tag rewritten, another folder added — the flag comes back.
  *Show it again* on the card undoes it.

**FR-283-5 — Nothing is repaired behind the admin's back.** No flag un-matches an album, deletes a cover or an
`album.nfo`, or changes a tag. The owner fixes it; the next scan and match see the fix and the flag goes.

**FR-283-6 — Unmatched says why.** A tile's *unmatched* and *needs you* chips carry the matcher's own note as their
tooltip, the one the Album page already shows.

## API (admin, additive)

- `MusicAlbumPageDto.flags: [MusicFlagDto]` and `dismissed_flags: [kind]`; `MusicFlagDto { kind, sentence,
  folder, folder_artist, files_title, files_artist, search, others: [{id, title, folder}], written_from_match }`.
- `MusicAlbumRow.folder`, `MusicAlbumRow.flags: [kind]`, `MusicAlbumRow.note` (the matcher's note).
- `POST /api/music/album/{id}/flags/{kind}/dismiss`, `DELETE` the same to show it again.
- Triage: counts `music_shared_album`, `music_folder_disagrees`; items `musicIssue = shared_album | folder_disagrees`.

## Acceptance

1. On the household's library: 12 albums flagged — ten with both flags, two with *the folder and the songs
   disagree* — and no other album.
2. The Artist page of the band shows each of the ten tiles with its folder's name and a warning chip.
3. *Find match… by the folder's name* on the single folder of shape 2 searches with the folder's name.
4. *This is right* removes the flag and it stays gone after a scan; renaming the folder brings it back.
5. A run that would match a second folder onto an album another folder already has leaves it *needs you* with the
   note naming the other folder.

## Non-goals

- Moving, merging or renaming folders, and writing tags from these cards.
- Undoing covers or `album.nfo` files already written.
- The phone: a viewer never sees a flag.
