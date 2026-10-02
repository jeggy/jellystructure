# Phase 296 — The loudness finding is about music only

> Found 2026-10-02 from an owner question: Settings → Libraries told them to turn off *Enable LUFS scan* on six
> libraries, and Jellyfin's page showed that checkbox on none of the five non-music ones.

## Status

`✓ Built` 2026-10-02, not deployed (see Build notes). Written 2026-10-02 (dev-authored), against `main` `3127421c`.
Number verified free (admin specs top at 295). Not dev-reviewed.

**Amends** phase 212 FR-212-4 row (d) and phase 246 FR-246-12 (the LUFS trade-off). **Implements** phase 275
FR-275-5's row *LUFS scan on — the phone evens out volume from it — silent ✓*, which was written but never built.

## What happens

1. `JellyfinAdvisorService.perLibraryFindings` row (d) fires *LUFS loudness scan is on, on rotational storage* for
   every library with `EnableLUFSScan: true` on a spinning disk. On the household server that is Film, Musik
   Videoer, Blandet, Bøger, Serier and Musik.
2. Jellyfin turns the switch on by default for every library, but its library editor shows the checkbox only
   when the library's content type is `music` (jellyfin-web 12.1: `chkEnableLUFSScanContainer` is hidden when
   the collection type is not `"music"`). On the five other libraries the admin is sent to a checkbox that is
   not there.
3. It is also inert there. Jellyfin's loudness task measures songs and albums only. Read from the household's
   `jellyfin.db` on 2026-10-02: 479 of 487 `Audio` items and 50 of 83 `MusicAlbum` items carry a `LUFS` value;
   `AudioBook` (14), films and episodes carry none.
4. On Musik the finding is wrong the other way. Its trade-off says *Ravilo reads no loudness value*, but
   279/R322 made Ravilo's music player even out the volume from `NormalizationGain` / `AlbumNormalizationGain`
   (`MusicIngest.kt:88`, `MusicQueue.kt:116`). Following the advice would bring back the jumps between songs.

## Requirements

**FR-296-1 — Only a music library is asked about loudness.** Row (d) is evaluated only when the library's
`CollectionType` is `music`. Every other library is silent about LUFS whatever the switch says, because
Jellyfin neither shows the switch there nor measures anything there.

**FR-296-2 — On a music library, on is right.** `EnableLUFSScan: true` on a music library is silent, on any
storage (275 FR-275-5).

**FR-296-3 — On a music library, off is the finding.** `EnableLUFSScan: false` on a music library is a `warning`:

- summary *Loudness scan is off for a music library*;
- current value *Enable LUFS scan: Off*;
- cost here: Ravilo's music player evens out the volume between songs from Jellyfin's loudness values, and with
  the scan off the songs in this library carry none, so each plays at its own level;
- path *Dashboard → Libraries → {name} → Manage library*, label *"Enable LUFS scan" (LabelEnableLUFSScan)*;
- recommendation *Turn on.*;
- trade-off: each song is read once in full to measure it, on a spinning disk when the library is on one; songs
  already measured are not read again.

The finding's id stays `lufs_{libraryId}`.

**FR-296-4 — Absent is not off.** `JellyfinLibraryOptions.enableLufsScan` becomes nullable with no value
default (242 FR-242-4's rule). A missing key is unknown and is silent; only an explicit `false` fires FR-296-3,
so a key Jellyfin renames cannot start telling the admin to switch on something that is on.

## Not in scope

- The other storage findings (chapter images, trickplay) are unchanged; their checkboxes are on Jellyfin's page
  for every video library.
- The design mockup's stand-in Dashboard row `j-lufs` (`design/app/dashboard-data.js`, *Film · Serier · Musik*)
  is design-owned and left for the next design sync.

## Acceptance

1. With the household's settings as of 2026-10-02 (LUFS on in six libraries, Musik the only `music` one),
   Settings → Libraries shows no LUFS finding anywhere.
2. A music library with `EnableLUFSScan: false` shows FR-296-3's finding, rotational storage or not.
3. A music library whose options carry no `EnableLUFSScan` key shows nothing.
4. Unit tests pin 1–3 against `perLibraryFindings`' LUFS rule.

## Build notes

2026-10-02. `JellyfinAdvisorService.lufsFinding` replaces row (d) inside `perLibraryFindings`: `null` unless the
collection type is `music` and `EnableLUFSScan` is an explicit `false`. `JellyfinLibraryOptions.enableLufsScan` is
now `Boolean?` with no default; the advisor was its only reader. Four tests in `JellyfinAdvisorServiceTest` (the
household's six libraries as of 2026-10-02 are silent; off on music fires on either storage; off elsewhere is
silent; absent is silent); the advisor suite is 16/16. Not deployed, so acceptance 1 waits for the next deploy:
the advisor caches for its TTL, and the live server still carries the old finding until then.
