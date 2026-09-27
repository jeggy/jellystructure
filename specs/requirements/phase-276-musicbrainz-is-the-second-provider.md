# Phase 276 — MusicBrainz is the second provider: the match ladder, Find match…, lock and clear

## Status

`Planned` — written 2026-09-28 from `specs/design-brief-music-in-the-admin-2026-09-27.md` (§B1, §B2, §E2, §H2,
§H5), the research report §3.1–§3.3 and §4.3, and the mockups `design/app/album.js` (the Find match… panel) and
`design/app/settings.html#sect-musicprov`. **Not dev-reviewed.** Builds on **275**. Numbering: see 275.

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
