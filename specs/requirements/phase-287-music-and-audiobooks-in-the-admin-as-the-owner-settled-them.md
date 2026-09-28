# Phase 287 — Music and audiobooks in the admin, as the owner settled them on 2026-09-28

> Owner, 2026-09-28: a round of answers to the questions the built music and audiobooks phases (275–281) left open,
> plus one ask on the Library page: *"It should go Artists · Albums · Songs, so that Artists is the default one."*

## Status

`Planned` — written 2026-09-28 from the owner's answers and the mockups `design/app/music-library.js`,
`design/app/activity.html`, `design/app/audiobook.js`. **Not dev-reviewed.** Number verified free on `main`
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
