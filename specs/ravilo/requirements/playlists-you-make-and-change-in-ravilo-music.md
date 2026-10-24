# Playlists you make and change in Ravilo music

Task: DEB-2254

> Owner, 2026-10-23 (DEB-2254): *"Let's kickstart adding support for playlists within ravilo music"*

## Scope

This spec turns on the playlist "phase 2" that R321 left behind. Today you can list your playlists, open one and play
it. This spec adds making and changing them: create, add a song, add an album, save the queue, remove, reorder, rename
and delete. It covers the phone, the desktop (Mac, Linux) and Ravilo Web, which all share `ravilo-ui`. Android TV has
no music browsing (music mode is gated `!isTvPlatform`), and Tizen and Chromecast are receivers. **None of those three
gets a surface.**

Every playlist stays one of **Jellyfin's own**, as research §6.5, FR-R321-10 and 279 already chose. So a playlist made
here appears in every other client that reads the same library, and nothing new is stored in jellystructure's database.

It supersedes the "phase 2" marks in three places: R321 FR-R321-10 (*New playlist*), R322 FR-R322-11 (*Add to
playlist…*) and 279 FR-279-3 (*"Creating and editing playlists is phase 2"*). Each carries a dated amendment that
points here. The investigation is the `investigation` document on DEB-2254. The owner answered its four questions on
2026-10-23 (card `72a31821`).

## Decisions

| # | Question | Answer |
|---|----------|--------|
| D1 | How much goes into the first cut | **Full** (owner, 2026-10-23): create, add a song, remove, rename, delete, **plus** drag-to-reorder, *Add album to playlist* and *Save queue as playlist* |
| D2 | Adding a song that is already in the playlist | **Don't add it**; show *Already in {playlist}* (owner) |
| D3 | Which playlists *Add to playlist…* offers | **Only the ones the viewer owns** (owner). Playlists shared with the viewer stay listed and playable, read-only, as today |
| D4 | Deleting a playlist | **Confirm first**: *Delete {playlist}?* (owner). Jellyfin has no trash |
| D5 | Where an edit is checked | On the **server**, against Jellyfin, with the **viewer's own token**, never the server key (FR-1). This is not an owner call: it is the H2 rule `tvTokenForClient` exists for |
| D6 | The order of the *Add to* picker | *New playlist* first, then the viewer's own playlists **by name**, the order of the Playlists chip. This is the cheapest order: Jellyfin's list gives no reliable "last changed" for a playlist |

## What Jellyfin does (read from its source, `jellyfin/jellyfin` `master`, 2026-10-23)

Prod runs `jellyfin/jellyfin:12.1`. The parts this spec depends on:

- **`POST /Playlists`** (`CreatePlaylistDto`: `Name`, `Ids`, `UserId`, `MediaType`) sets `OwnerUserId = UserId`.
- **`POST /Playlists/{id}`** (`UpdatePlaylistDto`: `Name`, …) renames. **`POST /Playlists/{id}/Items?ids=`** adds,
  and **appends with no de-duplication**: D2 is ours to enforce. **`DELETE /Playlists/{id}/Items?entryIds=`** removes.
  **`POST /Playlists/{id}/Items/{entryId}/Move/{newIndex}`** moves.
- Those four are permitted when the calling user is the **owner** or holds a share with `CanEdit`:
  `playlist.OwnerUserId.Equals(callingUserId) || playlist.Shares.Any(s => s.CanEdit && …)`.
- **`GET /Playlists/{id}/Users`** answers **only the owner** (`isPermitted = playlist.OwnerUserId.Equals(userId)`).
  Anyone else gets `403`. This is the owner check D3 needs (FR-2).
- **`DELETE /Items/{id}`** on a playlist checks `Playlist.CanDelete(user)`, which is `IsAdministrator ||
  user.Id.Equals(OwnerUserId)`. The owner deletes with their own token.
- **`GET /Playlists/{id}/Items`** gives every entry a `PlaylistItemId`. That is the id `entryIds` and `Move` take.
  Jellyfin matches it against the entry's `LinkedChild.ItemId`. If a playlist made elsewhere holds the same song
  twice, removing it removes both. That is accepted: D2 means Ravilo never makes such a playlist.

## Requirements

**FR-1 — Every playlist write runs as the viewer, on a playlist the viewer owns.** Every route in FR-3 to FR-10 does
three things, in this order:

1. It takes Jellyfin's token from **`tvTokenForClient`** (`tv/PlaybackService.kt`), never from `tvToken`. When that
   returns null, the route answers `401` and changes nothing. `tvToken` is the call that falls back to the server key,
   and with the server key Jellyfin's own owner check stops protecting anyone.
2. Except for create, it checks ownership with FR-2's check. A playlist the viewer does not own answers `403` and
   changes nothing.
3. It makes the Jellyfin call with that token and `userId = device.jellyfinUserId`, then calls `forget(device)` so the
   next read is fresh. This mirrors `setFavorite` (`music/MusicTvService.kt`).

A Jellyfin failure answers `502` and changes nothing on our side.

**FR-2 — Mine or shared: the owner check and a new `mine` flag.** A playlist is the viewer's own when
`GET /Playlists/{id}/Users` answers `200` to the viewer's token, and not when it answers `403`.
- `GET /tv/music/playlists` gains `mine: Boolean` on each `MusicPlaylist`. It is additive and defaults to `false`.
  Every playlist the viewer can see is still listed, in today's order.
- `GET /tv/music/playlist/{id}` returns `MusicList` as today, plus a new top-level `mine: Boolean` (additive, default
  `false`).
- The check runs once per playlist per list read, with the viewer's token. Keep it under the cache `forget(device)`
  clears.
- When `tvTokenForClient` returns null, every playlist reads `mine = false`. The client then offers no edits, which is
  the same answer FR-1 would give.

**FR-3 — Create a playlist.** `POST /tv/music/playlists` with `MusicPlaylistCreate(name, item_ids = [])`.
- It calls `POST /Playlists` with `Name`, `Ids = item_ids` (FR-5's rule applied), `UserId = device.jellyfinUserId` and
  `MediaType = "Audio"`.
- It answers the new playlist's `MusicPlaylist`, with `mine = true`.
- The name is trimmed. An empty name, or one over 100 characters, answers `400`. Two playlists may share a name, since
  Jellyfin allows it.

The client opens it from three places:
- the Playlists chip's *New playlist* (its empty state and its list, desktop sidebar page included), which creates an
  empty playlist and opens it;
- the *Add to* picker's first row (FR-4), which creates the playlist holding what was being added;
- *Save queue as playlist* (FR-7).

All three use **one name sheet**: a text field with the system keyboard, *Create*, and *Cancel*. On create the sheet
closes and the toast reads *Created {name}* (or FR-4's toast when songs went in).

**FR-4 — *Add to playlist…* on a song.** The ⋯ sheet's *Add to playlist…* row (`TrackActionsSheet`, `MusicCommon.kt`)
stops being dim and loses its *Coming later* tag. Tapping it opens **the picker**:
- *New playlist* first, then every playlist with `mine = true`, by name (D6), each drawn as the Playlists chip draws
  one (collage · name · *N songs*);
- with no playlist of the viewer's own, only *New playlist*, then the line *Playlists you make show up here.*
  (`music.pl_none`).

*New playlist* is the first row, an action with a + tile in place of the collage (round, direction N1). On the phone the
picker is a sheet; on the desktop it is a **popover anchored to the ⋯ that opened it**, opening upward when that ⋯ sits
low in the window, and closing on a click outside it (round, direction 2). A long list scrolls inside either.

Picking a playlist calls `POST /tv/music/playlist/{id}/items` with `MusicPlaylistAdd(item_ids)` and closes the picker.
The answer is `MusicPlaylistAdded(added, already)`. The toast reads *Added to {name}*, or *Already in {name}* when
`added == 0`. The song added is the copy the row shows (`MusicTrackItem.id`). The picker is the same everywhere ⋯
opens on a song: Listen, Browse, Search, an album, an artist, a playlist, Playing and the queue.

**FR-5 — A song is never added twice (D2).** Before adding, the server reads the playlist's entries. It drops every
id that is already there, and every id one of whose **copies** is already there: the R373 same-song fold, `View.copies`,
the rule the folded row follows. It also drops repeats inside `item_ids` itself, keeping the first.
- `added` counts what went in and `already` counts what was dropped.
- When nothing is left, the server makes no Jellyfin call.
- Create (FR-3) applies the same rule to its own `item_ids`.

**FR-6 — *Add album to playlist*.** The album page gains a ⋯ to the right of its single *Play album ▾* split button, on
every album page, phone and desktop, with or without editions. R373's amendment of 2026-10-23 (owner, card
`cb6d0b67`) puts *Play album*, *Shuffle* and the extras in that button's menu, so there is no separate *Shuffle*
button. The ⋯ is the home of the album's secondary actions. For now it holds one row, *Add to playlist…*, which opens
FR-4's picker for **the official album's songs in order**, as the button's main part plays them (`official_ids`; on
an album without editions, `MusicAlbumDetail.tracks`). Artist and playlist pages keep their own buttons; this ⋯ is the
album page's only.
- The toast reads *Added {n} songs to {name}*, *Already in {name}* when `added == 0`, and *Added to {name}* when `n == 1`.
- An album with no songs shows no ⋯.

**FR-7 — *Save queue as playlist*.** The queue's *Up next* heading (`MusicQueueScreen`, `MusicPlayerScreens.kt`, the
phone tab and the desktop's queue panel) gains *Save as playlist* beside *Clear queue*. The two sit together **on a
line of their own inside the heading block**, under *Up next* and its count. On the heading line itself, *Up next*
wrapped at 360 dp and the Faroese pushed *Clear queue* off the edge (round, Q5).
- It is shown whenever there is a song playing.
- It opens FR-3's name sheet and creates a playlist from **the playing song, then *Up next*, in the order shown**, with
  FR-5's rule.
- The toast reads *Saved {n} songs as {name}*.
- On a cast queue it saves the queue as the phone shows it.

**FR-8 — The playlist page's ⋯, on a playlist of the viewer's own.** When `mine = true`, the playlist page's header
(`MusicPlaylistScreen`, `MusicDetailScreens.kt`, phone and desktop) gains a ⋯ beside *Play* and *Shuffle*. It holds
*Edit*, *Rename* and *Delete playlist*. A shared playlist (`mine = false`) has no ⋯. Beside its song count it shows a
small **Shared with you** label (`music.pl_shared`), on the phone and the desktop (owner, 2026-10-23, card `979f4e34`;
round frames `ph-pl-shared-badge`, `dk-pl-shared-badge`). Otherwise it stays as today. An empty
playlist of the viewer's own shows the ⋯ with *Rename* and *Delete playlist* only, and its page reads *No songs yet*
with the hint *Add songs from their ⋯ menu*. There, *Play* and *Shuffle* are shown **disabled** (round, Q2).

**FR-9 — Remove a song.** Inside a playlist of the viewer's own, a song's ⋯ sheet gains *Remove from playlist*, right
after *Add to playlist…*. It calls `DELETE /tv/music/playlist/{id}/items/{entry_id}`, which in turn calls Jellyfin's
`DELETE /Playlists/{id}/Items?entryIds={entry_id}`. The route answers the playlist's new `MusicList`, the page shows
that list, and the toast reads *Removed from {name}*.
- `MusicTrackItem` gains `entry_id: String?`, additive and defaulting to `null`. It is filled **only** in
  `GET /tv/music/playlist/{id}` and FR-9/FR-10 answers, from the entry's `PlaylistItemId`
  (`JellyfinAudioUserItem` gains the field).
- A row with no `entry_id` offers no *Remove*.

**FR-10 — Edit: reorder by dragging.** *Edit* (FR-8) puts the page in edit mode:
- each row shows the queue's grip, and dragging by it moves the row (the queue's lift-and-drop, `MusicQueueScreen`'s
  drag handle);
- swiping a row left removes it (the queue's gesture, FR-9's route);
- tapping a row no longer plays it;
- the header's *Play*/*Shuffle* give way to *Done*, and *Done* or Back leaves edit mode.

Each drop calls `POST /tv/music/playlist/{id}/move` with `MusicPlaylistMove(entry_id, to)`, where `to` is the 0-based
index in the list as shown. That route calls Jellyfin's `POST /Playlists/{id}/Items/{entry_id}/Move/{to}` and answers
the new `MusicList`. The page shows the moved row where it was dropped. When the answer arrives it shows that answer;
on a failure it puts the row back and toasts FR-13's line.

On the desktop the grip takes a mouse drag. Web and desktop draw the same grip as the queue panel. With no swipe for a
mouse, a row in edit mode shows a **×** at its end on hover, which removes it as the swipe does (round, direction E1).

**FR-11 — Rename.** *Rename* opens FR-3's name sheet with the name filled in and *Save* in place of *Create*. It calls
`PUT /tv/music/playlist/{id}` with `MusicPlaylistRename(name)`, which calls Jellyfin's `POST /Playlists/{id}` with
`Name` only. FR-3's name rule applies. It answers the `MusicPlaylist`, and the header and the Playlists chip show the
new name. There is no toast; the new name is the answer.

**FR-12 — Delete, after a confirm (D4).** *Delete playlist* opens a confirm sheet:
- title *Delete {name}?*, line *This can't be undone.*;
- *Delete* in the destructive style, and *Cancel*.

*Delete* calls `DELETE /tv/music/playlist/{id}`, which calls Jellyfin's `DELETE /Items/{id}` with the viewer's token.
The page closes back to the Playlists list, which reloads without it, and the toast reads *Deleted {name}*. If the
queue was started from it (`MusicContext("playlist", name)`), the music keeps playing: the queue is client state
(FR-R322-8).

**FR-13 — A write that fails changes nothing on screen.** On any `401`, `403`, `400`, `404` or `502` from FR-3 to
FR-12:
- the sheet closes, or the dragged row returns;
- the list stays as it was;
- the toast reads *Couldn't change the playlist*.

The text names no server and no other app, so Jellyfin is invisible to viewers. A `400` on a name keeps the name sheet
open, with the field marked and *Give it a name* under it.

**FR-14 — The Playlists list keeps up.** After any write, the Playlists chip, the desktop sidebar page and the picker
read `GET /tv/music/playlists` again the next time they show. They hold no copy of their own.

**FR-15 — The wire stays backward compatible.**
- Additions:
  - **routes:** `POST /tv/music/playlists`, `PUT` and `DELETE /tv/music/playlist/{id}`,
    `POST /tv/music/playlist/{id}/items`, `DELETE /tv/music/playlist/{id}/items/{entry_id}` and
    `POST /tv/music/playlist/{id}/move`;
  - **DTOs:** `MusicPlaylistCreate`, `MusicPlaylistAdd`, `MusicPlaylistAdded`, `MusicPlaylistMove` and
    `MusicPlaylistRename`;
  - **fields:** `MusicPlaylist.mine`, `MusicList.mine` and `MusicTrackItem.entry_id`.
- Nothing is renamed, retyped or removed. The R319 wire baseline gains them.
- An installed app ignores the new fields.
- A new app on a server without them sees `mine = false` everywhere, so it offers no edits. *Add to playlist…* then
  shows only *New playlist*, which fails into FR-13's toast. That is accepted, because the server ships first.
- `TvApiClient` gains one call per route.

**FR-16 — Words.** These keys exist and stay: `music.add_playlist`, `music.new_playlist`, `music.no_playlists` and
`music.no_playlists_hint`. `music.phase2` stays in the files but is no longer drawn on these two rows. New keys, with
en · da · fo drafts:

| Key | en | da | fo |
|-----|----|----|----|
| `music.pl_create` | Create | Opret | Stovna |
| `music.pl_name` | Playlist name | Navn på playliste | Navn á spælilista |
| `music.pl_name_empty` | Give it a name | Giv den et navn | Gev honum eitt navn |
| `music.pl_created` | Created {name} | Oprettede {name} | {name} stovnaður |
| `music.pl_added` | Added to {name} | Føjet til {name} | Lagt í {name} |
| `music.pl_added_n` | Added {n} songs to {name} | Føjede {n} sange til {name} | {n} løg løgd í {name} |
| `music.pl_already` | Already in {name} | Allerede i {name} | Longu í {name} |
| `music.pl_save_queue` | Save as playlist | Gem som playliste | Goym sum spælilista |
| `music.pl_saved_n` | Saved {n} songs as {name} | Gemte {n} sange som {name} | {n} løg goymd sum {name} |
| `music.pl_edit` | Edit | Rediger | Broyt |
| `music.pl_done` | Done | Færdig | Liðugt |
| `music.pl_rename` | Rename | Omdøb | Gev nýtt navn |
| `music.pl_delete` | Delete playlist | Slet playliste | Strika spælilista |
| `music.pl_delete_q` | Delete {name}? | Slet {name}? | Strika {name}? |
| `music.pl_delete_line` | This can't be undone. | Det kan ikke fortrydes. | Hetta kann ikki gerast ógjørt. |
| `music.pl_deleted` | Deleted {name} | Slettede {name} | {name} strikaður |
| `music.pl_remove` | Remove from playlist | Fjern fra playliste | Tak úr spælilistanum |
| `music.pl_removed` | Removed from {name} | Fjernet fra {name} | Tikið úr {name} |
| `music.pl_failed` | Couldn't change the playlist | Kunne ikke ændre playlisten | Tað eydnaðist ikki at broyta spælilistan |
| `music.pl_empty` | No songs yet | Ingen sange endnu | Eingi løg enn |
| `music.pl_empty_hint` | Add songs from their ⋯ menu | Føj sange til fra deres ⋯-menu | Legg løg afturat úr ⋯-valmyndini hjá teimum |
| `music.pl_shared` | Shared with you | Delt med dig | Deilt við teg |
| `music.pl_none` | Playlists you make show up here. | Playlister, du laver, vises her. | Spælilistar, tú stovnar, síggjast her. |

`action.cancel` and `action.save` are reused. The lexicon is regenerated, and the spelling fences
(`scripts/check-*`) run green.

**FR-17 — The look comes from the design round.** The picker, the name sheet, the playlist page's ⋯ and edit mode,
the confirm sheet, the album's ⋯ and *Save as playlist* are drawn in one round on the phone and the desktop. The build
reimplements what the round adopts. Until it is adopted the client slices wait (*Build order*). The rules above stand
whatever the pictures show; a picture that contradicts one comes back here as a question.

The round is `design/incoming/2026-10-23-music-playlists/` (DEB-2278, `28faff79`): 81 frames, phone and desktop, in all
three music skins, English with Danish and Faroese where they overrun. Its picks are written into FR-4, FR-7, FR-8 and
FR-10 above. Its `--pl-bad` token, the destructive red, is the colour of FR-12's *Delete* and the edit-mode ×. 
The album row took two more rounds. On an album with editions, a ⋯ beside R373's two split buttons left no room on the
phone (`ph-album-editions`). Even before it, R373's *Shuffle* already read "Sh…" at 360 dp
(`2026-10-23-album-more-placement`, DEB-2293). The owner asked for a single *Play album ▾* button with the ⋯ beside
it, and picked variant **A** of `design/incoming/2026-10-23-album-play-button/` (DEB-2297, `3aed2318`; card
`cb6d0b67`): frames `apb-a-menu`, `apb-plain-menu` and `apb-dk-a-menu`, plus `apb-abcd-more` for the ⋯. That row is
specified in R373's amendment of 2026-10-23, and slice 2 builds it.

## Acceptance

1. A viewer creates *Stand-in Mix* from the Playlists chip. It appears in the chip, the sidebar page and the picker,
   and in Jellyfin's own list for that user, owned by them.
2. From ⋯ on a song: *Add to playlist…* → *Stand-in Mix* toasts *Added to Stand-in Mix*. Doing it again toasts
   *Already in Stand-in Mix*, and the playlist still holds the song once. Adding **another copy** of the same song (an
   *Also on* release) also toasts *Already in*.
3. *Add to playlist…* on a 10-song album adds 10, and doing it again adds 0. *Save as playlist* on a 7-song queue makes
   a 7-song playlist in queue order.
4. A playlist shared with the viewer by another user is listed and plays. It shows no ⋯ and is absent from the picker.
   A hand-made `POST /tv/music/playlist/{its id}/items` from that viewer's device answers `403`, and Jellyfin's
   playlist is unchanged.
5. With the device's paired token rejected (`TokenCheck.REJECTED`), every write answers `401` and nothing in Jellyfin
   changes. The server key is never used for a write: a test asserts it.
6. Edit: dragging song 5 to the top survives a reload, and so does swiping one away. Rename survives a reload.
7. Delete asks first. *Cancel* keeps the playlist. *Delete* removes it from Ravilo and from Jellyfin, and music started
   from it keeps playing.
8. An app installed before this change lists and plays playlists exactly as before against the new server (the R319
   baseline).
9. No TV, Tizen or Chromecast build changes behaviour.

## Build order

One engineer. Each slice is blocked by the one before.

1. **Server and wire**: FR-1, FR-2, FR-3 (route), FR-5, FR-9 (route and `entry_id`), FR-10 (route), FR-11 (route),
   FR-12 (route), FR-15, and FR-16's keys.
   - New `JellyfinClient` calls: create, rename, add, remove, move, delete and the owner check.
   - `linuxX64Test` covers FR-1's token rule (no write with the server key), FR-2's `mine`, FR-5's de-duplication
     including copies, and each route's `403`/`401`.
   - `TvApiClient` gains the calls. No UI.
   - This slice needs no picture.
2. **Making and adding** (after the round is adopted): R373's amendment of 2026-10-23 (the single *Play album ▾* row),
   FR-3's three entry points and the name sheet, FR-4, FR-6,
   FR-7, FR-13 and FR-14.
3. **Changing**: FR-8, FR-9's ⋯ row, FR-10's edit mode, FR-11 and FR-12.

QA is filed when slice 3 lands, on the phone and on one desktop. Acceptance 4 needs a second Jellyfin user who shares a
playlist with the first.

## Amendment 2026-10-24 — what slice 2 settled while building (DEB-2280)

Four things the build had to decide that the spec above left open. None changes what a viewer was promised.

- **FR-16 gains one key**, the picker's heading that the round drew: `music.pl_add_title`, en *Add to playlist* · da *Føj til
  playliste* · fo *Legg í spælilista*. It is *Add to playlist…* without the ellipsis, because the heading names the
  sheet and the row names the action that opens it.
- **FR-4 on a computer.** The song's ⋯ sheet, the picker, the name sheet and the toast were hosted for the phone layout
  only (`RaviloApp`, `if (handset)`), so a song's ⋯ opened nothing in a window wider than 600 dp. They are hosted for the
  desktop layout too. The picker there is the popover the round adopted: 312 dp, under the ⋯ that opened it and above it
  when it would run off the window, clamped 12 dp inside the window's sides. When the ⋯ is not known the popover is centred.
- **FR-3's name sheet.** *Create* is shown and inert while the name is empty (the round's `ph-name-empty`); the field holds at
  most 100 characters, which is FR-3's limit, so *Give it a name* appears only on a `400` the server sends (`ph-name-error`).
  A name of spaces only counts as empty.
- **FR-3's toast after a create that carried songs** counts what the server kept, `MusicPlaylist.track_count` of its answer,
  not what was sent: *Added to {name}* for one song, *Added {n} songs to {name}* for an album, *Saved {n} songs as {name}*
  for the queue.

The proof is `PlaylistFlowTest` (`:ravilo-ui:desktopTest`: the picker's contents, the toasts, the popover's place) and
`PlaylistHostTest` and `MusicAlbumEditionsTest` (`:ravilo-ui:testDebugUnitTest`: the picker and name sheet over a stand-in
server, the album row's menus for an album with editions and a plain one).
What no automated test can show stays with QA: the picture of the album row against `apb-a-menu`, and the popover under a real ⋯ on a Mac and on Linux.

## Amendment 2026-10-24 — what slice 3 settled while building (DEB-2281)

What the build had to decide that the spec above left open. None changes what a viewer was promised.

- **FR-16 gains two keys**, the two words the round drew that no key held: `music.pl_remove_swipe`, en *Remove* · da *Fjern* · fo
  *Tak úr* (the red panel a swipe uncovers, `ph-edit-swipe`), and `music.pl_delete_do`, en *Delete* · da *Slet* · fo *Strika*
  (FR-12's button). *Cancel* is `action.cancel`.
- **FR-10, Back.** *Done* and the page's own Back arrow leave edit mode; so does the system Back (the Android gesture, the
  browser's Back), which `RaviloApp` hands to the page first (`PlaylistEditBack`) and pops the page only when edit mode was
  not up. Edit mode is the page's own state: leaving the page leaves it, and a playlist that empties leaves it.
- **FR-10, what a row shows.** In edit mode a row is cover · title over artist · grip, with no ⋯ and no length on the phone, and
  grip · cover · title over artist · length · × on a computer (round frames `ph-edit-rest`, `dk-edit-hover-e1`). The × is drawn
  while the pointer is over the row, in the text colour as the frame shows it; `--pl-bad` stays the swipe panel's and
  *Delete*'s. A swipe that does not reach the threshold, or whose write fails, brings the row back.
- **FR-10, the drop.** `to` is the dragged row's index plus the rows it was dragged past (rounded), kept inside the list.
  The row sits at `to` at once; a failed write puts the order back to what it was before the drop, never to a half-answered
  state, and the writes of one page go one at a time.
- **FR-11, the name on the page.** The page and the back stack carry the name the server answered, so the header, the
  *Playlists* chip (FR-14) and a later Back all show it. Saving the name it already has writes nothing.
- **FR-8, *Shared with you*.** The label the owner picked (card `979f4e34`) is drawn beside the count of a playlist whose
  `mine` is false, once its list has loaded. On a server older than this spec every playlist reads `mine = false`, so every
  page shows it; that is the same accepted case as FR-15's.

The proof is `PlaylistEditTest` (`:ravilo-ui:desktopTest` and `:ravilo-ui:testDebugUnitTest`: the drop index, the move sent
equals the drop position, a failed move restores the order, a failed remove keeps the list, rename and delete) and
`PlaylistPageTest` (`:ravilo-ui:testDebugUnitTest`, the phone layout over a stand-in server: a shared playlist draws no ⋯ and
says *Shared with you*, an own one draws the menu, an empty one has no *Edit*, delete asks first and *Cancel* keeps it, delete
goes back and toasts, a failed delete changes nothing, rename saves the trimmed name, edit mode, Back, a drag by the grip, a
refused drag). What no automated test can show stays with QA: the picture against the round on a real phone and a real
desktop, the mouse drag and the hover × (no pointer in the test), the swipe gesture's feel, the menu under a real ⋯, and
acceptance 4 (it needs a second Jellyfin user who shares a playlist).
