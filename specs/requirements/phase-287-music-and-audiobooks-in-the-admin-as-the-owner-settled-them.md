# Phase 287 — Music and audiobooks in the admin, as the owner settled them on 2026-09-28

> Owner, 2026-09-28: a round of answers to the questions the built music and audiobooks phases (275–281) left open,
> plus one ask on the Library page: *"It should go Artists · Albums · Songs, so that Artists is the default one."*

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `cadc0b80`) — written 2026-09-28 from the owner's answers and the mockups `design/app/music-library.js`,
`design/app/activity.html`, `design/app/audiobook.js`. **Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review). Number verified free on `main`
2026-09-28. **Amends the built 275, 278, 279 and 281** — where they differ, this phase wins. The phone side of the
same answers is **R326**.

## Decisions (owner, 2026-09-28)

| Asked in | Question | Answer |
|---|---|---|
| 278 | Library → Music's views | **Artists · Albums · Songs**, Artists the default |
| 275 | Music's background lane | **Shares the media lane** with films and series (no music lane) |
| 281 | When Audnexus is asked | **Only when the admin pastes an ASIN** |
| 281 | *Split into two books…* | **A preview first**: two columns, parts dragged between them |
| — | Notifying the admin about new problems | **No** — the Dashboard is enough |

## Requirements

**FR-287-1 — Library → Music** reads **Artists · Albums · Songs** in that order, and opens on **Artists**. A saved
`?view=` still wins.

**FR-287-2 — One media lane.** `scan_music`, `match_musicbrainz`, `write_music_nfo`, `fetch_lyrics` and 284's
`write_tags` run in the media lane of the shared job pool (213) beside films and series (answers 275's open question 1 and 278
FR-278-10's lane choice). If the build added a `"music"` entry to `MediaJobQueue.QUEUE_NAMES`, it is removed and its
jobs move to `media`; Activity's music-lane preview goes too. Activity labels those steps *music*. MusicBrainz's
1 request/second pacing stays inside the step, so a film's re-order waits at most one request.

**FR-287-3 — Audnexus only for a pasted ASIN.** The book's Details editor gains an **ASIN** field with *Look up*;
Audnexus is asked only then, never on a search. The Metadata providers card's Audnexus row says *Asked only for an
ASIN you paste on a book*.

**FR-287-4 — *Split into two books…*** opens a preview before anything moves: two columns (*Book 1* · *Book 2*), each
with a title field, the parts listed by number, and parts **dragged between the columns**; *Split* stays disabled
until both columns hold a part. Nothing is moved on disk until *Split*; the result is two folders, each a book.

**FR-287-5 — No notifications.** jellystructure sends the admin no push, mail or webhook about new Dashboard rows;
285's Dashboard (and its *Since your last visit*) is the one place.

## Acceptance

1. Library → Music opens on Artists; the tab strip reads Artists · Albums · Songs.
2. During a music match, Activity shows the steps in the media lane; no music lane exists.
3. Searching book suggestions makes no Audnexus request; pasting an ASIN and *Look up* makes one.
4. *Split into two books…* shows the two columns; *Cancel* leaves the folder untouched.

## Dev review (2026-09-28, against `main` `32daeee2`)

Three of the five are already the built state or two lines; one contradicts 280's build and is corrected here.

1. **FR-287-1 is two lines** — `MusicLibrary.kt:24` `muView = "albums"` → `"artists"` and the tab order at `:107`.
   The URL key is **`?mview=`** (`:38`/`:45`), not `?view=`: read this FR with `mview`; `albums` becomes the
   non-default value and `artists` drops out of the URL.
2. **FR-287-2 is already true — nothing to remove.** `MediaJobQueue.QUEUE_NAMES` = `media · segments · subtitles`; the
   music steps (`scan_music`, `match_musicbrainz`, `fetch_music_artwork`, `fetch_lyrics`, `write_music_nfo`) are
   **pipeline steps** (`AppConfig` `PipelineSteps`), run inline by the scan pipeline, not pool jobs — so a film's
   re-order never waits on MusicBrainz — and Activity has no music lane (only the two pacing rows,
   `Activity.kt:644`). The FR stands as a statement of fact; the one thing that will use the `media` lane is 284's
   `write_tags` job (284 item 9).
3. **FR-287-3 is built** (281's dev review 3 → its build): `AudiobookProviders.audnexus(book, asin)` asks only with an
   ASIN, and the Details tab has the `bk-asin` field with *Look up* (`AudiobookPage.kt:263`). Left: the Metadata
   providers card's Audnexus row sentence (*Asked only for an ASIN you paste on a book*) — one string in `Settings.kt`.
4. **FR-287-4 conflicts with 280's build, and the build is right about the disk.** 280 FR-280-3's split is
   **virtual**: `splitPreview` groups the parts by their `Album` tag, `split()` sets `splitByAlbum`/`splitPrimary`
   and re-ingests — *the files are not moved* (its own History line). "The result is two folders" would move files
   under Jellyfin (remove + add, new item ids, the seeding guard on every file, Lidarr's watcher) for no viewer gain:
   Ravilo reads books through `AudiobooksTvService`, which already shows a split folder as two books. **The split
   stays virtual.** What changes is the preview: the owner's two-column dialog — parts dragged between *Book 1* and
   *Book 2*, titles editable — persisted as a per-part `split_group` (0/1) plus the two titles on the folder's book
   row (migration 62), replacing the album-tag grouping as the source of truth (the tag pre-fills the columns).
   *Split* disabled while a column is empty; *Cancel* touches nothing; *Join back* stays.
5. **FR-287-5 — nothing sends anything today.** 221's `WebhookStatus` is the health of the *inbound* webhooks
   (Jellyfin's plugin, the *arr routes) and Settings → Notifications holds those; no outbound push, mail or webhook
   exists for attention rows. The FR is the standing rule for 285.
6. **Wire:** none (admin only).

## Build notes (2026-09-29)

Built from the dev review (commit `cadc0b80`), the four items that were not already the built state:

1. **FR-287-1** — Library → Music opens on **Artists** (`MusicLibrary.kt`: `muView = "artists"`, the tab order, `?mview=`
   with `artists` as the default that drops out of the URL).
2. **FR-287-3** — the Metadata providers card's Audnexus row says *asked only for an ASIN you paste on a book*.
3. **FR-287-4** — the two-column **Split preview** (`AudiobookPage.kt`): parts dragged between *Book 1* and *Book 2*,
   both titles editable, *Split* disabled while a column is empty, *Cancel* touches nothing; the grouping and the titles
   are persisted on the parts and the folder's book row (`Audiobooks.kt`, `AudiobooksIngest.kt`, `AudiobooksMediaService.kt`,
   the `/split-preview` and `/split` routes). The split stays **virtual** (280's build was right about the disk); the
   album tag pre-fills the columns.
4. **FR-287-2 / FR-287-5** — statements of fact, nothing to build: the music steps are pipeline steps, not pool
   jobs (`MusicTvService.kt` lost a music-lane remnant), and nothing sends notifications.

`design/app/music.css` gained the split-preview rules and `check-mobile-css.sh` fences them.

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.
