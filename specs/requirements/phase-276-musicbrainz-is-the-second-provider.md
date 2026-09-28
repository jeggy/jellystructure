# Phase 276 — MusicBrainz is the second provider: the match ladder, Find match…, lock and clear

## Status

`✓ Built` 2026-09-28 (build notes at the end; not deployed). `Planned` when written 2026-09-28 from
`specs/design-brief-music-in-the-admin-2026-09-27.md` (§B1, §B2, §E2, §H2,
§H5), the research report §3.1–§3.3 and §4.3, and the mockups `design/app/album.js` (the Find match… panel) and
`design/app/settings.html#sect-musicprov`. **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Builds on **275**. Numbering: see 275.

## Decisions (round 1 — leans; not yet answered)

| # | Question | Lean |
|---|---|---|
| H2 | How a candidate's fit is shown | **A sentence** (*2 of 2 tracks agree on position and length (partial album)*); the per-track table is the alternative |
| H5 | Where the providers card lives | **Settings → Connections**, after the public address. The alternative is Download tools, after Bazarr |

## Requirements

**FR-276-1 — The model.** An album is a MusicBrainz **release-group**. The match also picks one **release**
(for the track count, label, country and date), and each track gets a **recording**. Artists are MusicBrainz
**artists**. The ids go to 277's NFO writer as Jellyfin's `MusicBrainzReleaseGroup`, `MusicBrainzAlbum`,
`MusicBrainzAlbumArtist`, `MusicBrainzArtist` and `MusicBrainzTrack` keys.

**FR-276-2 — A paced client.** One request per second per IP (MusicBrainz's rule — a fixed ceiling, not
learned). The `User-Agent` names jellystructure, its version and `[musicbrainz].contact`. A **503** backs off
and retries. Responses are cached for the run. It shows on Activity's pacing card as *MusicBrainz · now 1.0/s ·
ceiling 1.0/s · fixed by MusicBrainz*. AcoustID gets its own row (3/s).

**FR-276-3 — The ladder, per album** (`match_musicbrainz`, a run step after `scan_music`):

1. **Ids already in the tags** (Jellyfin `ProviderIds`) — taken as they are.
2. **Text search** — artist + album + year from the tags and folder. Each candidate release-group is scored on
   its releases' track lists **against the tracks on disk**: position and length (±3 s) per track, with a
   **partial album** being normal (the household holds 1–9 tracks of most albums).
3. **Pick** when the top candidate clears the bar and leads the second by a clear margin. Otherwise the album is
   **needs you** (candidates stored) or **unmatched** (nothing above the bar).
4. **AcoustID** (only with `[api_keys].acoustid_client_key`): `fpcalc` on the album's tracks → recordings →
   the release-group that holds them. It runs only when rungs 1–3 did not decide, never on every album.

A failed lookup **never blanks a good match** (131/183's rule). A **locked** album is skipped. Scope `missing`
retries only unmatched, unlocked albums. Artists are matched through their albums' album-artist credits, with
`inc=genres+url-rels+aliases`.

**FR-276-4 — Find match… (the album page's panel).** A side panel with:

- **The query** — artist · album · year, pre-filled from the folder and editable. A MusicBrainz release-group URL
  can be pasted. *Search* shows *Searching MusicBrainz…* with the one-request-a-second reason.
- **Candidates** — thumb · title · artist · type badge · *first released* · score, plus the fit (H2). A candidate
  where **no** track agrees is shown, marked red, and **cannot be used** (*No track agrees with that candidate*).
- **Releases** of the chosen candidate — country · date · label · format · track count · how many of our tracks
  agree. The best-agreeing release is **preselected** and marked *best*.
- **Identify by sound** — *Run fpcalc on N tracks* → the result as one sentence (*2 of 2 tracks identified → 1
  release-group*). Without an AcoustID key the block says *Add an AcoustID key in Settings to identify by sound*,
  with a link. It is never greyed.
- **Use this match** / **Use and lock** / Cancel. Both are disabled until a candidate is chosen. After use, the page
  says what happens next: *Cover, genres and album.nfo will be fetched on the next run — or Run now*.

**FR-276-5 — Per-track recordings.** A track whose recording belongs to a **different release** (a single's
version on the album, say) is marked on the Tracks tab. *Match this track…* offers that artist's recordings of
the title with length and position, and *Use* fixes that one track.

**FR-276-6 — Lock and clear.** *Lock match* makes every later run leave the album alone (*Locked · won't be
re-matched*). *Clear match* forgets the ids, and the fields the match filled stay until the next run.
**Re-pull → From MusicBrainz** is disabled while the album is locked, with the reason in a tooltip — the one
place a disabled control is right, because the action exists. Bulk actions do the same on a selection
(278 FR-278-3).

**FR-276-7 — Genres from votes.** MusicBrainz's community genre votes on the release-group (album) and artist.
The album's genres are **the top four with at least 3 votes and at least a tenth of the top genre's votes**. The
admin can tick or untick any of them, and that override survives runs. Unmatched albums show the files' own genre
tags until matched. English names only (no provider localisation exists).

**FR-276-8 — The providers card.** Named **Metadata providers** once 281 adds the book rows. One row per
provider, each with a status dot, a last-call line and *Test*, plus *Test all*:

- **MusicBrainz** — no key; **Contact** (required: *MusicBrainz requires a contact in the request header — an
  e-mail or a URL*); the rate as a sentence, not a field.
- **Cover Art Archive** — nothing to configure (277).
- **AcoustID** — optional client key, with the *acoustid.org/new-application* link and what is lost without it.
- **fanart.tv** — optional project key; without it *artist pictures come from Wikimedia Commons only* (277).
- **Lyrics · LRCLIB** — no key; the *Fetch lyrics* switch (277 FR-277-8).

The card names its providers (the admin registers with them) — unlike anything a viewer reads.

**FR-276-9 — Config.** `[musicbrainz] { enabled, contact, rate_per_sec = 1.0 }`; `[api_keys]` gains
`acoustid_client_key` and `fanart_tv_key`, both optional.

## Acceptance

1. A dry run of the ladder over the household's 30 albums **before any UI** (research §9): report how many
   rung 2 decides, how many need you, and how many only AcoustID can place.
2. An album with two equally fitting candidates is *needs you*. Find match… preselects the best release of the
   chosen candidate, and *Use and lock* locks it.
3. A candidate with no agreeing track cannot be used.
4. With no AcoustID key, Find match… shows the one-line link and never a disabled fingerprint button.
5. The pacing card never shows MusicBrainz above 1.0/s. A forced 503 backs off and the run finishes.

## Mockup

`design/app/album.html?a=low-tide-radio&find=1` (needs you), `?a=summer-hits-2004` (a compilation),
`?a=kvold` (unmatched — the fence's *AcoustID key: not set* switch shows the no-key block),
`?a=glass-birds` (a track from a different release), `?a=myrkrid-og-ljosid` (locked);
`design/app/settings.html?tab=connections#sect-musicprov`; `design/app/activity.html` (pacing rows).

## Open questions

1. The pick margin and the bar — numbers after the dry run (acceptance 1), not before.
2. Does a pasted MusicBrainz *release* URL (not a release-group) resolve to its group and preselect that release?
   Lean: yes.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Nine items; no blocker. The dry run (acceptance 1) is the first build step.

1. **Rate limiter:** `TmdbRateLimiter` is a `private class` inside `tmdb/TmdbClient.kt` (AIMD, `:47-110`).
   MusicBrainz's ceiling is **fixed** at 1/s (research §3.2), so the music client needs a fixed-rate bucket
   with the same 503 back-off — extract a small `RateLimiter(rate, burst)` both can use rather than a second
   copy. The `User-Agent` is `jellystructure/{version} ( {[musicbrainz].contact} )`.
2. **Pacing card:** the health JSON carries one `tmdb_pacing` object (`server/Server.kt:408-412`,
   `TmdbPacingStats`) → add `musicbrainz_pacing` and `acoustid_pacing` (admin-only JSON, additive); Activity
   renders the rows.
3. **AcoustID's fingerprint is not the one we compute.** `FfmpegRunner.computeFingerprint` runs `fpcalc -raw
   -length 120` and returns ints (`media/FfmpegRunner.kt:528-535`); AcoustID wants the **compressed** string
   plus the duration → a second helper (`fpcalc -json <file>`, no `-raw`), same `nice`/`ionice` and
   `ProcessGate` wrapping.
4. **Lock and clear:** 174's `tmdbMatchLocked` + `TmdbMatchLock.kt` are per `MediaItem`; `match_locked` on
   `music_album` (FR-275-1) carries the same meaning; *Re-pull disabled while locked* copies
   `ui/MediaDetail.kt`'s idiom.
5. **Config:** `ApiKeys` has three fields (`config/AppConfig.kt:343-347`) → add `acoustid_client_key`,
   `fanart_tv_key`; a `MusicBrainzConfig(enabled, contact, ratePerSec)` on `AppConfig` as `[musicbrainz]`;
   `ui/Settings.kt`'s `sect-apikeys` card (`:98`) gains the two keys; the providers card is a new
   `data-tab="connections"` section after `sect-public` (`:171`) — H5's lean.
6. **Genres:** a separate `music_genre(name, mbid)` list, never 271's `GenreCatalog` (TMDB ids); the admin
   override survives runs as `genres_override` JSON on the album.
7. **The dry run first** (acceptance 1): a one-off over production's 30 albums before any UI; its result sets
   the bar and margin (open question 1).
8. **A pasted release URL** resolves to its group with `/release/{mbid}?inc=release-groups` and preselects
   that release (open question 2: yes).
9. **Wire:** none.

## Build notes (2026-09-28)

Built on `main` after 275. Compiles (backend + admin), and the music/config/plan tests pass (the ladder is tested
end to end against a fake MusicBrainz). **Not deployed.**

1. **Acceptance 1 — the dry run, done first** (read-only, 1 request/second, a script mirroring rungs 1–3 over the
   household's 30 albums, numbers only): **rung 1 decides 0** (no MusicBrainz ids in any file), **rung 2 decides
   24 of 30** — every one with its best candidate strictly ahead of the second on agreeing tracks and a search score
   of 100 — **0 need you**, **6 unmatched** (3 with no candidate at all, 3 whose candidates agree on no track): the six
   only AcoustID can place. Most albums on disk hold one or two tracks.
2. **The pick rule (open question 1, from that run):** the best candidate agrees on ≥ 1 track *and* ≥ half the
   tracks on disk, has a search score ≥ 80, and agrees on more tracks than the second. A tie → *needs you*; a
   candidate agreeing on nothing is never used. Agreement = same disc (none = disc 1) and position, length within
   ±3 s, or the same title when a length is missing. The best pressing of a release-group is the one agreeing on most
   tracks, then an official one, then the track count closest to the folder's highest position.
3. **Rungs as built.** 1: Jellyfin's `MusicBrainzReleaseGroup` / `MusicBrainzAlbum` ids. 2: `releasegroup:"…" AND
   artist:"…"` (title-only if the artist finds nothing), the top three candidates each browsed once for up to 25
   pressings with track lists. 4 (with a key only): up to four tracks fingerprinted (`fpcalc` without `-raw` — the
   compressed form; a new `FfmpegRunner.acoustIdFingerprint`), AcoustID by POST at ≤ 3/s, votes on release-groups;
   the sound's own pick wins when one release-group holds at least half the fingerprinted tracks, even if lengths
   differ (another cut of the same recordings).
4. **No answer is not "nothing".** The client's list calls return null when MusicBrainz did not answer and empty when
   it found nothing; only the latter unmatches. A run leaves an album it could not reach exactly as it was (tested).
5. **Scopes.** `missing` (default): unmatched, unlocked albums **not tried in the last day** — prod runs the pipeline
   hourly and an unmatched album costs ~4 requests a try — *needs you* waits for the admin. `all`: every unlocked
   album; a matched one is **refreshed from its own ids**, never searched again. *Match now* on a selection ignores
   the day.
6. **Applying a match** writes the album (release-group, chosen release as country · date · label · format · track
   count · CAA front flag, types, genre votes, URL relationships, MusicBrainz credits), each track's recording and
   release-track id with `agrees` / `disagrees` against the release (FR-276-5; a hand-picked recording is kept), and
   every album and track artist whose name pairs with a MusicBrainz credit (type, country, life span, disambiguation,
   aliases, genre votes, URL relationships). A locked or already-matched artist is left alone.
7. **Clear match locks** (deviation, 174's rule): it forgets the ids and the tracks' recordings, keeps the fields the
   match filled, and sets the lock — otherwise the next run would find the same wrong album again. The note says so.
8. **No job-queue lane.** Matching runs inline in the step, or in one background pass from *Match now*, one pass at a
   time; the rate limit makes a parallel lane pointless. `/api/music/status` carries the pass's progress.
9. **Pacing:** a fixed-rate limiter per host (MusicBrainz 1/s, AcoustID 3/s); 183's AIMD limiter is untouched.
   `/api/health` gains `musicbrainz_pacing` and `acoustid_pacing`; the `no_tmdb_match` webhook gains
   `music_unmatched` (its firing rule stays film/series-only, FR-275-6).
10. **Config and the card (FR-276-8/9):** `[musicbrainz] {enabled, contact, rate_per_sec}` (blank contact sends the
    project's page — MusicBrainz accepts an application URL), `[api_keys] acoustid_client_key / fanart_tv_key` (masked
    on read), `[music] fetch_lyrics` (277). The **Metadata providers** card in Settings → Connections saves through
    `PUT /api/music/providers`; the general Settings save keeps the stored values, since its form model has no such
    fields (273's pattern). *Test* for MusicBrainz is one lookup of its own fixed *Various Artists* entity.
11. **Routes** (`/api/music/…`): `status`, `match`, `album/{id}/search | releases | identify | use | lock | clear |
    genres`, `track/{id}/recordings | recording`, `providers`, `providers/test/{name}`. Their shapes live in
    `commonMain` (`model/MusicApi.kt`) so the admin decodes the same classes. The Find match… panel itself is 278's.

## Amendment (2026-09-28) — the providers card has no Save of its own

**What was wrong.** Build note 10 gave the **Metadata providers** card its own *Save providers* button (and 281 added
a second, *Save audiobook providers*). Every other setting on the page is saved by the one **Save** at the top of
Settings; two card-local buttons meant a change typed into the card and followed by the top Save was silently not
saved. Owner decision: configuration is only ever saved through the top Save.

**FR-276-8, amended.** The card has **no Save button**. The page's Save sends, in order: `PUT /api/config`, then —
only if that succeeded — `PUT /api/music/providers` and `PUT /api/audiobooks/providers` (one after the other, since
each rewrites `config.toml`), each **only when something in its half of the card changed** against what the card was
rendered from, so an untouched card never overwrites a value saved elsewhere. A failed provider write reads *Saved,
except the Metadata providers — couldn't save those.* in the page's message line. *Remove* on a key still marks it
for removal; the key goes when Save is pressed (*The AcoustID key goes when you press Save.*). After a save the card
re-reads, so *A key is saved.* and the status dots are the server's.

**The fanart.tv row says where the key comes from** (same day, owner). Like AcoustID's *Get a client key*, it links
*Get a project key* → `https://fanart.tv/get-an-api-key/` and says how: *Free. Sign in to fanart.tv (or create an
account), open Get an API key, and request a key under Project API Keys. Paste that one here — a personal key is a
different kind and does not work on its own.* (fanart.tv issues both kinds on that page; ours is sent as `api_key`,
which takes the project key — a personal key is a `client_key` sent alongside one.)

Unchanged: the two routes, and `PUT /api/config` still keeping the stored `[musicbrainz]`, `[music]`,
`[audiobooks]` and the three keys — a page loaded before the card existed must still not reset them. **Wire:** none.
