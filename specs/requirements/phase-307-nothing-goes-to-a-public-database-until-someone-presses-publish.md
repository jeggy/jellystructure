# Phase 307 — Nothing goes to a public database until someone presses Publish

> Owner, 2026-10-05: *"Let's create some queue in jellystructure, where it's be that will click publish to the public
> database. And let this queue live within the dashboard and let it show what it wants to publish. Create a spec for
> it."* — asked when phase 292's *Tell LRCLIB it is instrumental* was about to be sent for real for the first time.

## Status

`✓ Built` 2026-10-05 — built, not deployed, not live-tested (nothing has been sent to LRCLIB; acceptance 3 is the
first live publish). Written 2026-10-05 (dev-authored, from the owner's ask above), checked against `main` `6ee8197c`.
Not dev-reviewed. Backend (a table, a service, routes, one Dashboard row) and the admin Dashboard. No Ravilo change, no
wire change for installed apps. **Amends 292 FR-292-15** (its second action no longer publishes; it queues).

## What happens today

jellystructure writes to exactly **one** public database: LRCLIB, from 292's Dashboard row *Lyrics on an
instrumental* (`music_instrumental_lyrics`, `DashboardRoutes.kt:80–81`). Everything else it sends goes to the
household's own services (Seerr, Bazarr, Radarr/Sonarr/Lidarr, qBittorrent), to Anthropic through 272's queue, or to
the owner's webhook; MusicBrainz, AcoustID (`/v2/lookup` only), TMDB, the Cover Art Archive and the audiobook
providers are read only.

The LRCLIB write, as built (`music/MusicWebClients.kt:196–245`, `MusicMediaService.kt:247–268`,
`MusicRoutes.kt:515–519`, `ui/Dashboard.kt:506–511`):

- **One press publishes at once.** The row's second button posts `POST /api/music/lyrics/tell-lrclib`, which launches
  the work and answers *Telling LRCLIB about n songs…*. There is no confirm and no preview of what is sent.
- **Per song:** a proof-of-work challenge (`request-challenge` → nonce search → `X-Publish-Token`), then
  `POST /api/publish` with `{trackName, artistName, albumName, duration, plainLyrics:"", syncedLyrics:""}` — empty
  lyrics mark the track instrumental. The submitter is the server's IP and the User-Agent
  `jellystructure/<version> (https://github.com/jeggy/jellystructure)`.
- **Nothing is remembered per song.** No *published at*, so a second press publishes the same songs again, and the
  row stays because the local lyrics are still there. Two presses can run at once; publishes are not rate-limited
  (the 2 req/s limiter guards reads only).
- **Two rules count the songs.** The route's reply counts by `lyricsOnNoSinging`, the service selects by
  `blocksLyrics && (hasLyrics || lyricsState == BLOCKED)` — the number shown and the number sent can differ.
- **Never sent live** (292 is `⚠ Partial` for exactly this).

The owner's point: a write to a public database is a different kind of act from fixing something in the house. It is
visible to everyone, it is hard to take back, and it carries the household's IP. It should wait until a person has
seen exactly what will go out.

## Requirements

### FR-307-1 — One queue for public writes, kept by the server

A new table `publish_item`: `id`, `target` (`lrclib` today), `kind` (`instrumental` today), `subject` (the thing it is
about — a song id), `label` (one line a person reads: *Song — Artist · Album*), `payload` (the exact JSON body that
will be sent, frozen when the item is queued), `reason` (why it is proposed: *MusicBrainz says this recording has no
words*), `state` (`waiting · publishing · published · failed · dismissed`), `queued_at`, `queued_by` (`admin` or the
step that proposed it), `decided_at`, `decided_by`, `sent_at`, `answer` (what the target replied, one line),
`attempts`. `UNIQUE(target, kind, subject)` while not `dismissed`, so the same song is never queued twice. It
persists; a restart loses nothing. It is not `media_job` (no human state, local file work) and not `ai_queue`.

### FR-307-2 — Proposing never publishes

Every place that would write to a public database **adds an item and stops**. For LRCLIB: 292's second action
becomes *Queue for LRCLIB* (was *Tell LRCLIB it is instrumental*) and queues one item per song the row counts — the
service's one selection rule, used for the count too (the two-rule mismatch above goes). A song already `waiting`,
`publishing` or `published` is not queued again. No code path may call a public write except FR-307-4.

### FR-307-3 — The Dashboard shows what is waiting, and exactly what

A Dashboard row **Waiting to publish** in a new domain chip **Public databases**, severity `info`, `fix = here`,
count = the waiting items, unit *things*. Zero waiting ⇒ no row (285 FR-285-5). Its button opens a panel on the
Dashboard (the `opens` mechanism 305 added), listing every waiting item:

- the target's name and a link to its own page (*LRCLIB*, `https://lrclib.net`),
- the label, the reason, when and by what it was queued,
- **the exact payload**, rendered field by field as it will be sent (*Track name · Artist · Album · Length 3:41 ·
  Lyrics: none — marks it instrumental*), with a *Show what is sent* toggle for the raw JSON,
- per item: **Publish** and **Don't publish**; above the list: **Publish all n** and **Don't publish any**.

Above the list, one plain sentence of what publishing means, per target: *LRCLIB is a public lyrics database. Publishing
adds an entry anyone can read, from this server's address. It cannot be taken back from here.*

### FR-307-4 — Publish is the only door out

`POST /api/publish/items/{id}/publish` and `POST /api/publish/items/publish` (`{ids}`) move items to `publishing` and
hand them to one worker: **one item at a time**, in queue order, each with its own proof of work, never two presses
at once (a second press while one runs adds its items to the same run). The payload sent is the one frozen in
FR-307-1 — never rebuilt. On LRCLIB's success the item is `published` with `sent_at` and the answer; on a failure it
is `failed` with the one-sentence reason (today's error sentences) and stays in the panel with **Try again**. A
publish writes the album's History as today (`music_lyrics`), one line per song.

### FR-307-5 — Don't publish is remembered

**Don't publish** sets `dismissed` (`decided_at/by`). A dismissed item is not proposed again for the same subject
until the reason changes (the payload would differ) — the Dashboard does not nag about a song the admin declined.
A **Dismissed (n)** link at the foot of the panel lists them with **Queue again**.

### FR-307-6 — A receipt

The panel's foot keeps a **Published** tab: the last 50 published items with their payload, when, and LRCLIB's answer,
so the household can always see what it has put into a public database. Older rows are kept in the table (they are
the never-twice record) but not listed.

### FR-307-7 — Nothing automatic, ever

No pipeline step, scan, schedule or AI job may move an item past `waiting`. Only an admin's press does. A setting to
publish automatically is out of scope and must not be added later without a spec saying why.

### FR-307-8 — The local fix is separate

292's first action (*Remove the lyrics*) is local and unchanged. Queuing or publishing never touches the household's
own files or lyrics; the Dashboard row *Lyrics on an instrumental* still counts by the local state.

## Non-goals

- New public targets (MusicBrainz edits, AcoustID submissions). The table is shaped for them (`target`, `kind`), but
  none is built; each needs its own spec.
- Accounts or credentials on LRCLIB (it has none) or showing the household's IP.
- Retracting a publish (LRCLIB has no delete API).

## Acceptance

1. Pressing *Queue for LRCLIB* on *Lyrics on an instrumental* publishes nothing (no request to `lrclib.net`), and the
   Dashboard shows *Waiting to publish · n things* with the same n the queue holds.
2. The panel lists each song with its exact payload; *Show what is sent* shows the JSON that is then sent byte for byte.
3. **Publish** on one song sends exactly one challenge + one publish to LRCLIB, the item becomes `published` with
   LRCLIB's answer, and the row's count drops by one. *(The first live publish — 292 OQ3 — is this test.)*
4. **Publish all** sends one at a time; pressing it again mid-run sends nothing twice.
5. A published or waiting song is never queued again; **Don't publish** removes it and it does not come back.
6. A restart in the middle keeps `waiting` items; an item left `publishing` by a restart returns to `waiting`, not sent
   twice.
7. With nothing waiting, no row and no domain chip.

## Tests

- `PublishQueueTest`: queue/dedupe/unique-while-not-dismissed; frozen payload; dismiss suppresses re-proposal until the
  payload changes; restart turns `publishing` into `waiting`; one-at-a-time worker; failure → `failed` with reason.
- 292's selection: the count and the queued set come from one function.
- Dashboard: the row appears with the waiting count, disappears at zero; `opens` the panel.
- No call site of `Lrclib.publishInstrumental` outside the worker (a grep-style test).

## Build notes (2026-10-05)

- **Table and service.** `publish_item` (`Publish.sq`, migration `69.sqm`) with a partial unique index
  `(target, kind, subject) WHERE state != 'dismissed'`. `publish/PublishQueue.kt`: `propose` (skips a subject that is
  waiting, publishing, published or failed; a dismissed one only when its payload is byte-identical), `publish` /
  `dismiss` / `queueAgain`, `recover` (run at start in `Main.kt`: `publishing` → `waiting`), and `drain`, the one worker
  (a `Mutex.tryLock`; re-checks after unlocking so a press that lands as it finishes is not stranded). A failure keeps
  the one-sentence reason in `answer`; *Try again* re-publishes the same frozen payload.
- **The only door out.** `Lrclib.publishInstrumental(payload)` now takes the frozen body and is called only from
  `LivePublishSender` in `PublishQueue.kt` (`PublishCallSiteTest` greps the backend for it). The body is built once by
  `Lrclib.instrumentalPayload` when the song is queued. A success's answer is *Accepted* plus LRCLIB's reply, one line.
- **292's second action** is *Queue for LRCLIB*: `POST /api/music/lyrics/tell-lrclib` (path kept) queues and answers
  how many are waiting; `MusicMediaService.lrclibInstrumentalSongs` is the one selection (no singing, and lyrics now or
  removed by *Remove the lyrics*), used for the queued set; `tellLrclibInstrumental` is gone. A song with no artist,
  album or length is left out and counted in the reply. History: one `music_lyrics` line per song on each answer.
- **Routes** (admin, cookie-gated like the rest): `GET /api/publish/items`; `POST /api/publish/items/publish` and
  `/dismiss` (`{ids}`); `POST /api/publish/items/{id}/publish`, `/dismiss`, `/try-again`, `/queue-again`.
- **Dashboard.** Row `publish_waiting` (*Waiting to publish*, domain `public` = *Public databases*, info, `fix = here`,
  unit *thing*, `opens = publish_queue`), absent at zero. The panel (`ui/PublishQueueUi.kt`) follows 305's modal:
  per-target sentence and link, each item's label, reason, queued when/by, the payload field by field and *Show what is
  sent*, Publish / Don't publish, Publish all n / Don't publish any, failed items with *Try again*, and foot tabs
  *Published* (last 50) and *Dismissed (n)* with *Queue again*. While items are publishing it re-reads every 2 s.
- **Open questions taken as leaned:** accepted = published (OQ1); *Public databases* is its own chip (OQ2).
- **Tests:** `PublishQueueTest` (7), `LrclibQueueTest` (2, incl. the route sending nothing), `PublishCallSiteTest`.

## Open questions

1. **When does a song count as done?** When LRCLIB accepts the publish (lean), or when LRCLIB's own `get` answers
   *instrumental* afterwards (it may keep preferring an older entry)? Lean: accepted = published; a later read that
   still returns lyrics is not this phase's to chase.
2. **Is *Public databases* its own domain chip, or does the row sit under Music?** Lean: its own chip — it will hold
   other targets, and it is a different kind of decision from a fix.
