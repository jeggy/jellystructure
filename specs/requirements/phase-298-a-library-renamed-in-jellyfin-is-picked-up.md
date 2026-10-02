# Phase 298 — A library renamed in Jellyfin is picked up by jellystructure

> Found 2026-10-02 from an owner question: *"How come we still have two 'Musik' libraries? One of them should have
> changed its name. It has been changed in Jellyfin, jellystructure just hasn't picked it up yet."*

## Status

`✓ Built` 2026-10-02, not deployed (see Build notes). Written 2026-10-02 (dev-authored), against `main` `477c2fb8`.
Number verified free (admin specs top at 297). Not dev-reviewed.

**Amends** phase 142 (an item's `libraryId`) and the Settings → Libraries merge (no phase owns it; it has been
`Settings.kt`'s `fetchAndRenderLibraries` since the library mappings were first auto-discovered).

## What happened

1. On 2026-09-28 the music-videos library *Musik* was renamed *Musik Videoer* in Jellyfin, and a new music library
   was created under the name *Musik*.
2. **A Jellyfin library's `ItemId` is a hash of its name.** The rename gave *Musik Videoer* a new id (`c49e…`), and
   the new music library *Musik* got the old one (`8a05…`). Only `Locations` and `CollectionType` survive a rename.
3. `config.toml` still has the music-videos mapping as `name = "Musik"`, `jellyfin_id = "8a05…"`,
   `jellyfin_path = "/media/music_videos"`. The music mapping that was added next to it carries the same id.
   Two mappings, one name, one id.
4. Nothing ever corrects that:
   - **Settings → Libraries:** merges Jellyfin's list with the saved mappings **by id only**, and keeps the saved
     entry as it is, name included.
   - **No other path:** the backend never looks.
   - **The duplicate name has visible effects:** `/config/path-check` lists *Musik* twice, and Settings indexes
     that list by name (`Settings.kt` `diagsByName`), so the two collide.
5. **The scanner trusts the mapping.** Every scan stamps the 22 music videos with `libraryId = 8a05…`, the music
   library's id (prod DB, 2026-10-02).
6. **Visibility follows the stamp.** Phase 142 decides what a viewer sees from an item's `libraryId` against
   their Jellyfin `EnabledFolders`. One restricted viewer is granted *Musik* and not *Musik Videoer*, so Ravilo
   shows them the music videos and Jellyfin does not.

## Requirements

**FR-298-1 — One reconcile rule, and Jellyfin's list is the list.** A pure function takes the saved mappings and
Jellyfin's `GET /Library/VirtualFolders`. For each Jellyfin library it looks for a saved mapping, in this order:
1. **By location:** the mapping's `jellyfin_path` equals one of the library's `Locations`, ignoring a trailing `/`.
   This is what survives a rename.
2. **By id:** a mapping with **no** `jellyfin_path` (the skipped libraries) and the same id.

A matched mapping takes the library's current **id, name and collection type**. It keeps its own paths, `skip` and
`fallback_language`. A library with no match is added as **skipped**, with no local path. It used to be added
not skipped, with the container path as its local path, which no scan could read. A saved mapping that matches no
Jellyfin library is dropped, as Settings' Save already did. The output follows Jellyfin's order.

**FR-298-2 — An empty answer changes nothing.** If Jellyfin returns no libraries (unreachable, or an error), the
mappings are left exactly as they are. A missing answer must never empty the config.

**FR-298-3 — It runs without anyone pressing Save.**
- **When:** once at startup, and every time Settings → Libraries opens. No timer (owner, 2026-10-02: *"No hourly stuff"*).
- **What it writes:** the config, only when the result differs from what is saved.
- **What it logs:** one line per change (*Library renamed in Jellyfin: Musik → Musik Videoer (8a05… → c49e…)*,
  *new library … added as skipped*, *… no longer in Jellyfin, removed*).

**FR-298-4 — Settings shows the reconciled list, so Save cannot undo it.** Settings → Libraries gets its mappings
from a new `GET /api/jellyfin/libraries/reconcile`, which runs FR-298-1/3 and returns the result. The page's own
id-only merge is deleted. Otherwise a Save from a page that merged differently would write the old names back.

**FR-298-5 — Titles follow their library at once.** When a mapping's id changes, every stored title whose
`libraryId` is the old id and whose path is inside that mapping's folder is re-stamped with the new id, in one
write. Path boundaries are respected: `/music` must not match `/music_videos`. The Home feed's version is bumped,
so a viewer's visibility follows straight away rather than at the next scan; the scanner would only have fixed it
then. Music albums and audiobooks are re-stamped by their own scanners, which read the mapping on every pass.

## Not in scope

- **Jellyfin's own user policies:** Jellyfin does not move `EnabledFolders` when a library is renamed. The
  restricted viewer above now holds the music library's id where they used to hold the music videos'. That is
  for the owner to set in Jellyfin.
- **Any change to a Jellyfin setting.**

## Acceptance

With the household's 2026-10-02 config and Jellyfin:

1. Startup reconciles the music-videos mapping to *Musik Videoer* / `c49e…`, and the music mapping stays
   *Musik* / `8a05…`.
2. The 22 music videos carry `c49e…`.
3. Settings → Libraries and `/config/path-check` list *Musik* once and *Musik Videoer* once.
4. Unit tests pin the rename, the skipped-by-id match, a new library (skipped), a removed library, an empty
   answer, the trailing-slash rule, and that a reconciled list reconciles to itself.

## Build notes

2026-10-02. Not deployed; the live config is untouched until the backend restarts on a build with this.

- **`config/LibraryReconciler`:**
  - `reconcile` is pure (FR-298-1/2).
  - `reconcileNow` reads Jellyfin and saves only on a difference. It re-reads the config just before writing, so
    a Save that lands meanwhile is reconciled rather than overwritten. It then logs each change and re-stamps
    titles (FR-298-3/5).
- **When it runs (FR-298-3):** `Main.kt` once at startup, and `GET /api/jellyfin/libraries/reconcile` whenever
  Settings → Libraries opens. There is no timer, as the owner asked.
- **`MediaStore.restampLibraryId`:** moves titles inside one folder (boundary-safe), in one transaction, and bumps
  `feedVersion`.
- **Settings:** adopts the reconciled list (FR-298-4). The id-only merge is deleted, along with the frontend's
  now-unused `getJellyfinLibraries` and its `JellyfinLibrary` type.
- **Tests:** `LibraryReconcilerTest` (7, built from the household's config and Jellyfin on 2026-10-02) and
  `RestampLibraryIdTest` (1, a scratch DB: the music videos move, `/music` and films do not). Advisor 26/26 and
  `MusicIngestTest` 7/7 still pass. `compileKotlinWasmJs` is clean.

**Expected on the household's first start with this build:** one log line, *Library renamed in Jellyfin: Musik →
Musik Videoer (8a05… → c49e…)*, then *22 title(s) moved*. Every other mapping matches as it is: by location for
Film, Serier, Bøger and Musik, and by id for the skipped Recordings, Blandet and Samlinger.
