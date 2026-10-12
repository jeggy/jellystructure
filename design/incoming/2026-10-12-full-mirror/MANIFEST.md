# First full mirror of the design project — 2026-10-12

Quarantine commit for DEB-792. **Received only; nothing promoted.** Reconcile and promotion are a
separate commit, and this directory is pruned at adoption (`design/PROCESS.md`).

Source project — reported before anything was read, as DEB-792 asked:

| | |
|---|---|
| id | `6c4a911d-07c4-4053-9634-09186e24dd11` |
| name | Jellystructure |
| type | `PROJECT_TYPE_PROJECT` — **not** `PROJECT_TYPE_DESIGN_SYSTEM` |
| owner | Jógvan |
| `canEdit` | `true` (we read only; nothing was written or deleted there) |

`list_projects` returns an empty list because it is filtered to design-system projects the caller can
write to, and this project is a plain project. `get_project` on the id the owner pointed at resolves,
and `ravilo/Ravilo.html` is present — so this is his studio.

## Resolutions — the owner's three answers, 2026-10-13

| asked | answered | done |
|---|---|---|
| the image archive has no transport | export the studio to a zip and extract what is needed from it | `design/PROCESS.md` now carries the zip procedure; extraction is its own task |
| 7 screenshots name a library title | keep them in the studio; never land them | nothing landed; the rule is now a named entry in PROCESS.md's gate, widened to every real-library capture |
| `app/wf.css` in the studio is stale | write it back | pushed and verified — studio 58312 B `90ae89d7…` = repo, byte for byte |

**The copied files in this directory were pruned when triage closed; only this manifest remains.**
Nothing in this round was promoted — the repo was already ahead of every file in it — so what sat
here was thirteen duplicates and one *stale* `wf.css`, which is the exact shape of bytes the nineteen
wipes were made of. Git history holds the quarantine commit (`97d8f92c`) if they are ever wanted.

## The three counts

`list_files` returns **paths only** — no size, no hash, no mtime. So identical-vs-differing cannot be
computed before pulling bytes; that split is a byte comparison, not a structural one. What the
structural diff gives, and what the byte pull then settled:

| | count |
|---|---|
| studio files | 617 |
| tracked under `design/` | 436 |
| **same path in both** | **433** |
| **studio-only (new)** | **184** |
| **repo-only** | **3** |
| of the 433: byte-compared this pass | 13 (the production surface) + 2 sampled on 10-13 |
| → identical | **14** |
| → differing | **1** (`app/wf.css`) |
| of the 433: bytes not yet compared | 418 (mockups + 144 binaries) |

The 3 repo-only files are repo-authored and expected: `PROCESS.md`,
`design-project-notes-2026-06-to-2026-10.md`, `github.md`. **The studio is missing nothing we have
except our own documents** — no deletion anywhere in the mirror.

## The production surface, byte-compared

The live set `syncDesignAssets` copies is the risk surface — every one of the nineteen wipes landed
inside it. All thirteen text files were pulled and diffed byte for byte:

| file | verdict |
|---|---|
| `app/app.css` | identical (15569 B) |
| `app/dashboard.css` | identical (7355 B) |
| `app/detail.css` | identical (39220 B) |
| `app/editions.css` | identical (4422 B) |
| `app/metadata.css` | identical (5944 B) |
| `app/music.css` | identical (28481 B) |
| `app/seeding.css` | identical (12086 B) |
| `app/seeding.js` | identical (23500 B) |
| `app/segments.css` | identical (24290 B) |
| `app/suggestions.css` | identical (10153 B) |
| `app/versions.css` | identical (6777 B) |
| `flags.css` | identical (6268 B) |
| **`app/wf.css`** | **differs — repo 58312 B, studio 55232 B** |

### The other 104 production files — sampled 2026-10-13, and the rest rides the zip

`app/fonts/**` (3 woff2) and `flags/**` (**100** svg under `4x3/` plus `README.md` — the earlier
"101 svg" counted the README as a flag) are in the production surface too. All 104 are
path-identical, and the bytes of two were compared:

| file | verdict |
|---|---|
| `flags/4x3/id.svg` | identical (189 B) — the smallest of the hundred |
| `flags/README.md` | identical (1292 B) |

Sampled rather than swept, deliberately:

- **The realistic failure here is deletion, not drift.** `fonts/**` and `flags/**` have been *deleted*
  by a sync twice; neither has ever been edited on either side, and the flags are a vendored MIT set
  (`lipis/flag-icons`) with the same provenance in both places. Deletion is a **path** difference, and
  the path diff above already covers all 104.
- **A byte sweep would cost more than it can prove.** The hundred SVGs are 835 KB in total and the
  largest single file is 183 KB; `get_file` returns content through the agent's context, so proving
  "no drift" one file at a time costs roughly a quarter of a million tokens for a result the zip gives
  for nothing. The three woff2 are binary, which is the wrong shape for this transport entirely.
- So the **whole remaining production surface is byte-compared from the owner's export** (DEB-809),
  not from 104 pulls. That is a second reason the zip matters, independent of the images: it is the
  only affordable way to prove the live set has not drifted anywhere.

### `app/wf.css` — one hunk, and it is ours

One hunk, 33 lines, **all repo-only; the studio has nothing new in this file.** The studio's copy is
the repo's copy minus the phase 304 (R368) `.ses-*` block — the admin's *Users & devices → Playing
now* session table, hand-ported here from `ravilo/ravilo-users.html` and amended 2026-10-12
(FR-304-8/9, DEB-760).

**Nothing to promote, and a wholesale copy of this file would have been wipe #20.** Under the
reconcile rule a differing production file is hand-edited, never copied — here there is not even an
edit to make: the repo is strictly ahead.

Two things follow, and both are reported on DEB-792 rather than acted on here:

1. That block had **no line in `scripts/check-mobile-css.sh`**, so nothing in the repo would have
   noticed it going missing. Invariant 8 says a hand-authored rule in a design-managed file gets a
   fence line; this one is now fenced, in its own commit.
2. It is a candidate for the write-back path — the studio's copy is otherwise byte-identical, so it
   has not been touched there. Write-back is a **write** to the studio and the owner's call.
   **Answered yes on 2026-10-13 and pushed**: the precondition was re-proven at push time (studio
   still 55232 B, `191b9cd3…`), the repo's 58312 B went up by `localPath`, and the read-back hashes
   equal. The studio is no longer stale in the one file it was stale in.

## What was dropped at the gate

Rejected by name, whatever path they claim:

- `CLAUDE.md`
- `ravilo/STATUS.md`
- `ravilo/constitution.md`
- `ravilo/plan.md`

`CLAUDE.md` is code-owned at the repo root. `ravilo/STATUS.md`, `ravilo/constitution.md` and
`ravilo/plan.md` are the three dead June mirrors deleted from this repo in `2928e55a`; they still
live in the studio, which is correct — we delete nothing there — and they are never pulled.

## Held back: 7 new screenshots that name a library title

7 of the new `presentation/screenshots/*.png` have a real library title **in the filename**,
and the set is captures of the real app on the real library rather than stand-in data. They are
recorded here by count only and are **not** in this commit — this repository is public. The owner's
call, asked on DEB-792.

**Answered 2026-10-13: keep them in the studio, never land them.** Applied to the whole class, not
just the 7 filenames — a capture of the real library does not land whatever it is called. The
filenames are not reproduced here either, which is why this section counts and does not list.

## Not pulled: the image archive

157 of the 184 new files are images (png/jpg/webp). `get_file` returns file content through
the agent's context and caps at 256 KiB, so it cannot carry an archive of that shape — a full-fidelity
image mirror needs a different transport. Path inventory below; bytes still only in the studio. This is
the one part of "nothing in the studio is lost" that this pass does **not** yet satisfy, and it is the
second question on DEB-792.

**Answered 2026-10-13: an owner-exported zip, extracted by named path against this inventory.** The
procedure is in `design/PROCESS.md`; the extraction is a separate task (DEB-809), and it covers the
16 new text files below as well — they are pullable but arrive in the same zip for free.

### The named-path list does not decide what lands (DEB-809)

Recorded before the zip arrived, because it changes what the extraction is. The inventory below was
built from `list_files`, which returns **paths and nothing else**. The gate that governs these 157
images is the owner's 2026-10-13 rule, and that rule is about **what is in the picture**, not what
the file is called. A filename can therefore only ever catch the careless cases — the 7 already
withheld above. So the list below says what comes *out of the zip*; a look at each image says what
gets committed. Where they sit now, by class:

| class | count | what the names suggest, pending a look |
|---|---|---|
| `presentation/admin/**` | 7 | the real admin app — dashboard, library, activity, language tracks. Captures, near-certainly of the real library |
| `presentation/screenshots/**` | 50 | the real Ravilo TV app, named for real channels and for whole-library screens (`movies-grid`, `my-list`, `search-results`). Same class as the 7 already withheld, minus a title in the filename |
| `screenshots/**` | 88 | mixed, and the only class where the names genuinely split: logo and component studies (`01-barna-logos`, `ravilo-stinger-card`) alongside what read as real-app captures (`01-mobile-library`, `lib-music`, `01-set-lib`) |
| `uploads/**` | 12 | pastes and two artwork files whose names are poster hashes — artwork for a specific title identifies that title as surely as its name does |

**So the expected yield of this round is well under 157, and most of `presentation/**` is expected to
stay out.** That is the rule working, not the round failing: the deck those captures were made for is
the owner's to publish, and the studio keeps them. Nothing here is decided — each number moves to a
verdict only once the image has been opened — but the extraction is not a mechanical unzip, and
estimating it as one is how a capture would reach a public repository.

## Inventory

### New — text and code, pullable and new design content (16)

- `presentation/Jellystructure &amp; Ravilo - Spec-Driven Development.html`
- `presentation/deck-stage.js`
- `presentation/presentation-context.md`
- `presentation/scratchpad.md`
- `ravilo/Ravilo.html`
- `ravilo/mobile/ravilo-phone-head.js`
- `ravilo/mobile/ravilo-phone-markup.js`
- `ravilo/mobile/ravilo-phone-state.js`
- `ravilo/mobile/ravilo-phone.css`
- `ravilo/mobile/ravilo-phone.js`
- `ravilo/platform/ravilo-platforms.js`
- `ravilo/platform/ravilo-shell.css`
- `ravilo/platform/ravilo-shell.js`
- `ravilo/platform/surface.css`
- `ravilo/platform/tv-boot.js`
- `ravilo/platform/tv-markup.js`

### New — images, inventoried by path only (bytes not pulled) (157)

- `presentation/admin/01-dashboard.png`
- `presentation/admin/02-library.png`
- `presentation/admin/03-segments.png`
- `presentation/admin/04-activity.png`
- `presentation/admin/05-language-tracks.png`
- `presentation/admin/05-media-tracks.png`
- `presentation/admin/06-settings-language.png`
- `presentation/screenshots/01-home-hero-carousel.png`
- `presentation/screenshots/02-home-hero-carousel-alt.png`
- `presentation/screenshots/03-home-hero-carousel-alt2.png`
- `presentation/screenshots/04-home-collections-continue-watching.png`
- `presentation/screenshots/05-home-continue-watching-focused.png`
- `presentation/screenshots/06-home-content-row.png`
- `presentation/screenshots/07-home-content-row-2.png`
- `presentation/screenshots/08-home-content-row-3.png`
- `presentation/screenshots/09-movies-grid.png`
- `presentation/screenshots/10-movies-grid-facet-bar-focused.png`
- `presentation/screenshots/11-movies-genre-facet-popover.png`
- `presentation/screenshots/12-maturity-range-picker.png`
- `presentation/screenshots/13-audio-language-facet.png`
- `presentation/screenshots/14-sort-options.png`
- `presentation/screenshots/15-series-grid.png`
- `presentation/screenshots/16-series-grid-tile-focused.png`
- `presentation/screenshots/18-series-detail-season-picker.png`
- `presentation/screenshots/19-series-detail-season-picker-navigating.png`
- `presentation/screenshots/20-series-detail-episode-rail.png`
- `presentation/screenshots/21-series-detail-cast-crew.png`
- `presentation/screenshots/24-player-transport-chrome.png`
- `presentation/screenshots/25-player-audio-subs-button-focused.png`
- `presentation/screenshots/26-audio-subtitles-picker-level1-languages.png`
- `presentation/screenshots/27-audio-subtitles-picker-subtitles-tab.png`
- `presentation/screenshots/28-subtitles-picker-language-list-full.png`
- `presentation/screenshots/28-subtitles-picker-language-list-scrolled.png`
- `presentation/screenshots/29-subtitles-picker-level2-versions.png`
- `presentation/screenshots/30-player-episode-rail.png`
- `presentation/screenshots/32-home-collections-channel-rail.png`
- `presentation/screenshots/33-channel-olivar-page-hero.png`
- `presentation/screenshots/34-channel-olivar-rows.png`
- `presentation/screenshots/35-channel-olivar-rows-2.png`
- `presentation/screenshots/38-home-channel-rail-dansktv-focused.png`
- `presentation/screenshots/40-channel-dansktv-hero.png`
- `presentation/screenshots/41-channel-dansktv-continue-watching.png`
- `presentation/screenshots/42-channel-dansktv-genre-rows-posters.png`
- `presentation/screenshots/43-channel-dansktv-documentary-row-watched-badge.png`
- `presentation/screenshots/44-discover-upcoming-calendar.png`
- `presentation/screenshots/45-discover-request-seerr-in-progress.png`
- `presentation/screenshots/46-discover-seerr-popular-in-queue-in-library.png`
- `presentation/screenshots/47-profile-menu.png`
- `presentation/screenshots/48-settings-skins-language-playback.png`
- `presentation/screenshots/49-search-with-keyboard.png`
- `presentation/screenshots/50-search-results.png`
- `presentation/screenshots/51-home-channel-rail-appletv.png`
- `presentation/screenshots/52-home-genre-row-comedy.png`
- `presentation/screenshots/53-home-live-tv-on-now-row.png`
- `presentation/screenshots/54-live-tv-guide-epg.png`
- `presentation/screenshots/55-series-grid-facet-bar.png`
- `presentation/screenshots/56-my-list.png`
- `screenshots/01-admin-settings-segments.png`
- `screenshots/01-barna-logos.png`
- `screenshots/01-barna-rainbow.png`
- `screenshots/01-barna-sjonvarp.png`
- `screenshots/01-dash-final.jpg`
- `screenshots/01-ed-check.jpg`
- `screenshots/01-ed-check2.jpg`
- `screenshots/01-fd-check.png`
- `screenshots/01-ft-1.jpg`
- `screenshots/01-ft-2.jpg`
- `screenshots/01-lyr-debug.png`
- `screenshots/01-mobile-bottom-nav.png`
- `screenshots/01-mobile-discover-tabs.png`
- `screenshots/01-mobile-library-menu.png`
- `screenshots/01-mobile-library-topbar.png`
- `screenshots/01-mobile-library.png`
- `screenshots/01-mu-bar.jpg`
- `screenshots/01-re-phone.jpg`
- `screenshots/01-set-adv.jpg`
- `screenshots/01-set-lib.jpg`
- `screenshots/01-sp.jpg`
- `screenshots/01-spk-phone.jpg`
- `screenshots/01-spk-phone2.jpg`
- `screenshots/01-spk-sheet.jpg`
- `screenshots/02-admin-settings-segments.png`
- `screenshots/02-barna-logos.png`
- `screenshots/02-barna-rainbow.png`
- `screenshots/02-barna-sjonvarp.png`
- `screenshots/02-dash-final.jpg`
- `screenshots/02-ed-check.jpg`
- `screenshots/02-ed-check2.jpg`
- `screenshots/02-fd-check.png`
- `screenshots/02-ft-1.jpg`
- `screenshots/02-ft-2.jpg`
- `screenshots/02-lyr-debug.png`
- `screenshots/02-mobile-bottom-nav.png`
- `screenshots/02-mobile-discover-tabs.png`
- `screenshots/02-mobile-library-menu.png`
- `screenshots/02-mobile-library-topbar.png`
- `screenshots/02-mobile-library.png`
- `screenshots/02-mu-bar.jpg`
- `screenshots/02-re-phone.jpg`
- `screenshots/02-set-adv.jpg`
- `screenshots/02-set-lib.jpg`
- `screenshots/02-sp.jpg`
- `screenshots/02-spk-phone.jpg`
- `screenshots/02-spk-phone2.jpg`
- `screenshots/02-spk-sheet.jpg`
- `screenshots/03-ed-check2.jpg`
- `screenshots/03-ft-1.jpg`
- `screenshots/03-mobile-library-topbar.png`
- `screenshots/03-mu-bar.jpg`
- `screenshots/03-re-phone.jpg`
- `screenshots/03-sp.jpg`
- `screenshots/03-spk-phone.jpg`
- `screenshots/04-re-phone.jpg`
- `screenshots/04-sp.jpg`
- `screenshots/04-spk-phone.jpg`
- `screenshots/ab-split.jpg`
- `screenshots/admin-ravilo-prefs.png`
- `screenshots/admin-segment-bar.png`
- `screenshots/admin-segment-bar2.png`
- `screenshots/admin-segment-scrubber.png`
- `screenshots/admin-settings-segments2.png`
- `screenshots/barna-opt5.png`
- `screenshots/ft-3.jpg`
- `screenshots/lib-music.jpg`
- `screenshots/lyr-debug.png`
- `screenshots/mobile-discover-request.png`
- `screenshots/mobile-sticky-scrolled.png`
- `screenshots/order-editor.png`
- `screenshots/ps-a.jpg`
- `screenshots/r192-picker-live.png`
- `screenshots/ravilo-person-facet.png`
- `screenshots/ravilo-skip-intro.png`
- `screenshots/ravilo-stinger-card.png`
- `screenshots/ravilo-stinger-card2.png`
- `screenshots/re-phone2.jpg`
- `screenshots/rv1-mobile.png`
- `screenshots/rv1-tv-to-mobile.png`
- `screenshots/sp-b4.jpg`
- `screenshots/sp-b4b.jpg`
- `screenshots/sp-b4c.jpg`
- `screenshots/sp-e8.jpg`
- `screenshots/spk-phone3.jpg`
- `screenshots/tv-discover-tabs.png`
- `screenshots/wb-audio-picker.png`
- `screenshots/wb-person-picker.png`
- `uploads/6uod2gwSZRfxqfrKXsndHoibJkk (1).webp`
- `uploads/6uod2gwSZRfxqfrKXsndHoibJkk.webp`
- `uploads/Screenshot 2026-09-30 at 11.28.41.png`
- `uploads/l0POVE4GPYg1zaXGMzvw5yCUwFj.webp`
- `uploads/pasted-1782141142818-0.png`
- `uploads/pasted-1782141646650-0.png`
- `uploads/pasted-1782145584975-0.png`
- `uploads/pasted-1785492364941-0.png`
- `uploads/pasted-1789547058414-0.png`
- `uploads/pasted-1789547368995-0.png`
- `uploads/pasted-1789547395220-0.png`
- `uploads/pasted-1789731070166-0.png`

> plus 7 further `presentation/screenshots/*.png` withheld: a library title in the filename.

### Same path in both — the production surface (byte-compared above) (13)

- `app/app.css`
- `app/dashboard.css`
- `app/detail.css`
- `app/editions.css`
- `app/metadata.css`
- `app/music.css`
- `app/seeding.css`
- `app/seeding.js`
- `app/segments.css`
- `app/suggestions.css`
- `app/versions.css`
- `app/wf.css`
- `flags.css`

### Same path in both — mockups and assets (bytes not compared this pass) (420)

- `.thumbnail`
- `App Icons - Flat Directions.html`
- `Design-Implementation Audit 2026-07-09.md`
- `Multi-Episode Files - Option B.html`
- `Multi-Episode Files.html`
- `Ravilo Logo Options (standalone).html`
- `Skip Intro & Credits - Directions.html`
- `app/Artwork Manager.html`
- `app/Bulk Audio Reorder.html`
- `app/Dark Theme Comfort.html`
- `app/Jellystructure Logo.html`
- `app/Metadata Language Override - Directions.html`
- `app/Music Editions - Directions.html`
- `app/Ravilo Create Flows.html`
- `app/Row Sorting - Directions.html`
- `app/Season Episode Cast.html`
- `app/Segment Editor - Directions.html`
- `app/Song Versions - Directions.html`
- `app/activity.html`
- `app/album.html`
- `app/album.js`
- `app/app-shell.js`
- `app/artist.html`
- `app/artist.js`
- `app/artwork-artboards.jsx`
- `app/audiobook-data.js`
- `app/audiobook-library.js`
- `app/audiobook.html`
- `app/audiobook.js`
- `app/author.html`
- `app/dashboard-data.js`
- `app/dashboard.js`
- `app/design-canvas.jsx`
- `app/editions-directions.js`
- `app/editions.js`
- `app/files-tab.js`
- `app/fonts/jetbrains-mono.woff2`
- `app/fonts/sora.woff2`
- `app/fonts/space-grotesk.woff2`
- `app/index.html`
- `app/library.html`
- `app/livetv.html`
- `app/login.html`
- `app/media.html`
- `app/metadata.html`
- `app/music-data.js`
- `app/music-library.js`
- `app/music-qs.js`
- `app/ravilo-builders.css`
- `app/ravilo-builders.js`
- `app/ravilo-config.html`
- `app/ravilo-createflows.jsx`
- `app/ravilo-users (before cleanup).html`
- `app/ravilo-users.html`
- `app/row-sorting-directions.js`
- `app/segments-directions.js`
- `app/segments.html`
- `app/segments.js`
- `app/series-cast.js`
- `app/series-johnnybravo.html`
- `app/series-simpsons.html`
- `app/series-simpsons.js`
- `app/series.html`
- `app/settings.html`
- `app/subtitles.html`
- `app/suggestions-data.js`
- `app/suggestions.html`
- `app/suggestions.js`
- `app/versions.js`
- `downloads/Music Editions - Directions (standalone).html`
- `flags/4x3/ad.svg`
- `flags/4x3/ae.svg`
- `flags/4x3/ar.svg`
- `flags/4x3/at.svg`
- `flags/4x3/au.svg`
- `flags/4x3/ax.svg`
- `flags/4x3/bd.svg`
- `flags/4x3/be.svg`
- `flags/4x3/bg.svg`
- `flags/4x3/bo.svg`
- `flags/4x3/br.svg`
- `flags/4x3/by.svg`
- `flags/4x3/ca.svg`
- `flags/4x3/ch.svg`
- `flags/4x3/cl.svg`
- `flags/4x3/cn.svg`
- `flags/4x3/co.svg`
- `flags/4x3/cr.svg`
- `flags/4x3/ct.svg`
- `flags/4x3/cu.svg`
- `flags/4x3/cy.svg`
- `flags/4x3/cz.svg`
- `flags/4x3/de.svg`
- `flags/4x3/dk.svg`
- `flags/4x3/do.svg`
- `flags/4x3/ec.svg`
- `flags/4x3/ee.svg`
- `flags/4x3/eg.svg`
- `flags/4x3/es.svg`
- `flags/4x3/et.svg`
- `flags/4x3/fi.svg`
- `flags/4x3/fj.svg`
- `flags/4x3/fo.svg`
- `flags/4x3/fr.svg`
- `flags/4x3/gb.svg`
- `flags/4x3/ge.svg`
- `flags/4x3/gh.svg`
- `flags/4x3/gr.svg`
- `flags/4x3/gt.svg`
- `flags/4x3/hk.svg`
- `flags/4x3/hn.svg`
- `flags/4x3/hr.svg`
- `flags/4x3/ht.svg`
- `flags/4x3/hu.svg`
- `flags/4x3/id.svg`
- `flags/4x3/ie.svg`
- `flags/4x3/il.svg`
- `flags/4x3/in.svg`
- `flags/4x3/ir.svg`
- `flags/4x3/is.svg`
- `flags/4x3/it.svg`
- `flags/4x3/jm.svg`
- `flags/4x3/jp.svg`
- `flags/4x3/ke.svg`
- `flags/4x3/kr.svg`
- `flags/4x3/kz.svg`
- `flags/4x3/li.svg`
- `flags/4x3/lk.svg`
- `flags/4x3/lt.svg`
- `flags/4x3/lu.svg`
- `flags/4x3/lv.svg`
- `flags/4x3/ma.svg`
- `flags/4x3/mc.svg`
- `flags/4x3/mt.svg`
- `flags/4x3/mx.svg`
- `flags/4x3/my.svg`
- `flags/4x3/ng.svg`
- `flags/4x3/nl.svg`
- `flags/4x3/no.svg`
- `flags/4x3/np.svg`
- `flags/4x3/nz.svg`
- `flags/4x3/pa.svg`
- `flags/4x3/pe.svg`
- `flags/4x3/pg.svg`
- `flags/4x3/ph.svg`
- `flags/4x3/pk.svg`
- `flags/4x3/pl.svg`
- `flags/4x3/pt.svg`
- `flags/4x3/py.svg`
- `flags/4x3/qa.svg`
- `flags/4x3/ro.svg`
- `flags/4x3/rs.svg`
- `flags/4x3/ru.svg`
- `flags/4x3/sa.svg`
- `flags/4x3/se.svg`
- `flags/4x3/sg.svg`
- `flags/4x3/si.svg`
- `flags/4x3/sk.svg`
- `flags/4x3/th.svg`
- `flags/4x3/tr.svg`
- `flags/4x3/tt.svg`
- `flags/4x3/tw.svg`
- `flags/4x3/tz.svg`
- `flags/4x3/ua.svg`
- `flags/4x3/us.svg`
- `flags/4x3/uy.svg`
- `flags/4x3/uz.svg`
- `flags/4x3/ve.svg`
- `flags/4x3/vn.svg`
- `flags/4x3/za.svg`
- `flags/README.md`
- `ravilo/01-_jump.png`
- `ravilo/02-_jump.png`
- `ravilo/Audio & Subtitles Picker - Same-Language Directions.html`
- `ravilo/Audio & Subtitles Picker.html`
- `ravilo/Barna TV Channel Logo.html`
- `ravilo/Bottom Nav - Directions.html`
- `ravilo/Casting - Directions-print.html`
- `ravilo/Casting - Directions.html`
- `ravilo/Decode Ceiling Warning - Directions.html`
- `ravilo/Desktop - D1.html`
- `ravilo/Desktop - Directions.html`
- `ravilo/Desktop - Music App Icon.html`
- `ravilo/Focus Detail - Directions.html`
- `ravilo/Focus Detail - Round 2 Directions-print.html`
- `ravilo/Focus Detail - Round 2 Directions.html`
- `ravilo/Føroyskt Logo Options II.html`
- `ravilo/Føroyskt Logo Options III.html`
- `ravilo/Føroyskt Logo Options.html`
- `ravilo/Føroyskt Streaming Names.html`
- `ravilo/Logo Options.html`
- `ravilo/Media Detail Genres - Directions.html`
- `ravilo/Mobile Player - Directions-print.html`
- `ravilo/Mobile Player - Directions.html`
- `ravilo/Olivar Channel Logo.html`
- `ravilo/Play on a TV - Directions.html`
- `ravilo/Playback Sessions - Desktop, TV & Admin.html`
- `ravilo/Playback Sessions - Directions.html`
- `ravilo/Player Loading and Buffering - Directions.html`
- `ravilo/Ravilo - Android TV Assets.html`
- `ravilo/Ravilo Browse - Filter UI Directions.html`
- `ravilo/Ravilo Desktop.html`
- `ravilo/Ravilo Directions.html`
- `ravilo/Ravilo Live TV.html`
- `ravilo/Ravilo Logo 10+6.html`
- `ravilo/Ravilo Logo Options II.html`
- `ravilo/Ravilo Logo Options.html`
- `ravilo/Ravilo Mobile.html`
- `ravilo/Ravilo Receiver App.html`
- `ravilo/Ravilo Receiver.html`
- `ravilo/Ravilo TV - Discover + Seerr (v3).html`
- `ravilo/Ravilo TV - Discover + Seerr (v4).html`
- `ravilo/Ravilo TV - Navbar Concepts v2.html`
- `ravilo/Ravilo TV - Navbar Concepts.html`
- `ravilo/Ravilo TV.html`
- `ravilo/Speakers - Directions.html`
- `ravilo/Start over & Shuffle - Directions.html`
- `ravilo/Varpið Lockup.html`
- `ravilo/assets/android/drawable-xhdpi/banner.png`
- `ravilo/assets/android/drawable/splash_logo.png`
- `ravilo/assets/android/mipmap-anydpi-v26/ic_launcher_background.png`
- `ravilo/assets/android/mipmap-anydpi-v26/ic_launcher_foreground.png`
- `ravilo/assets/android/mipmap-anydpi-v26/ic_launcher_monochrome.png`
- `ravilo/assets/android/mipmap-hdpi/ic_launcher.png`
- `ravilo/assets/android/mipmap-hdpi/ic_launcher_round.png`
- `ravilo/assets/android/mipmap-mdpi/ic_launcher.png`
- `ravilo/assets/android/mipmap-mdpi/ic_launcher_round.png`
- `ravilo/assets/android/mipmap-xhdpi/ic_launcher.png`
- `ravilo/assets/android/mipmap-xhdpi/ic_launcher_round.png`
- `ravilo/assets/android/mipmap-xxhdpi/ic_launcher.png`
- `ravilo/assets/android/mipmap-xxhdpi/ic_launcher_round.png`
- `ravilo/assets/android/mipmap-xxxhdpi/ic_launcher.png`
- `ravilo/assets/android/mipmap-xxxhdpi/ic_launcher_round.png`
- `ravilo/assets/barna-sjonvarp-logo.svg`
- `ravilo/assets/bbb-backdrop-bunny.png`
- `ravilo/assets/bbb-backdrop-landscape.png`
- `ravilo/assets/bbb-backdrop-opening.png`
- `ravilo/assets/bbb-backdrop-rodents.png`
- `ravilo/assets/bbb-logo.png`
- `ravilo/assets/big-buck-bunny-poster.jpg`
- `ravilo/assets/brand/dansk-tv-logo.svg`
- `ravilo/assets/brand/olivar-logo.svg`
- `ravilo/assets/brand/ravilo-lockup.svg`
- `ravilo/assets/brand/ravilo-mark.svg`
- `ravilo/assets/brand/varpid-logo.svg`
- `ravilo/assets/store/feature-graphic-1024x500.png`
- `ravilo/assets/store/ic_launcher-512.png`
- `ravilo/assets/store/tv-banner-1280x720.png`
- `ravilo/design-canvas.jsx`
- `ravilo/desktop-d1.js`
- `ravilo/desktop-directions.css`
- `ravilo/desktop-directions.js`
- `ravilo/desktop-kit.js`
- `ravilo/desktop/ravilo-desktop.css`
- `ravilo/desktop/ravilo-desktop.js`
- `ravilo/doc-page.js`
- `ravilo/livetv-app.jsx`
- `ravilo/livetv-data.js`
- `ravilo/livetv.css`
- `ravilo/mobile-directions.css`
- `ravilo/mobile/ravilo-books.js`
- `ravilo/mobile/ravilo-editions.js`
- `ravilo/mobile/ravilo-mobile-player.css`
- `ravilo/mobile/ravilo-mobile.css`
- `ravilo/mobile/ravilo-music-player.js`
- `ravilo/mobile/ravilo-music.css`
- `ravilo/mobile/ravilo-music.js`
- `ravilo/mobile/ravilo-sessions.css`
- `ravilo/mobile/ravilo-sessions.js`
- `ravilo/mobile/ravilo-speakers.js`
- `ravilo/mobile/ravilo-versions.js`
- `ravilo/mobile/ravilo-web-states.js`
- `ravilo/ravilo-app.js`
- `ravilo/ravilo-browse.js`
- `ravilo/ravilo-data.js`
- `ravilo/ravilo-focus.js`
- `ravilo/ravilo-i18n.js`
- `ravilo/ravilo-livetv-data.js`
- `ravilo/ravilo-livetv.css`
- `ravilo/ravilo-livetv.js`
- `ravilo/ravilo-player.css`
- `ravilo/ravilo-player.js`
- `ravilo/ravilo-states.js`
- `ravilo/ravilo.css`
- `ravilo/rewatch-directions.js`
- `ravilo/sessions-desk.js`
- `ravilo/sessions-directions.css`
- `ravilo/sessions-kit.js`
- `ravilo/sessions-live.js`
- `ravilo/sessions-phone.js`
- `ravilo/sessions-rest.js`
- `ravilo/speakers-directions.js`
- `ravilo/tweaks-panel.jsx`
- `scraps/01-e04.png`
- `scraps/01-e06-subs.png`
- `scraps/01-explainer.png`
- `scraps/01-final-default.png`
- `scraps/01-jb-admin.png`
- `scraps/01-jb-mobile.png`
- `scraps/01-jb-tv.png`
- `scraps/01-mismatch.png`
- `scraps/01-olivar-check2.png`
- `scraps/01-qa-blocks-row-editor.png`
- `scraps/01-sticky-test.png`
- `scraps/01-top10.png`
- `scraps/01-v-lib.png`
- `scraps/01-v-series.png`
- `scraps/02-e04.png`
- `scraps/02-e06-subs.png`
- `scraps/02-explainer.png`
- `scraps/02-final-default.png`
- `scraps/02-jb-admin.png`
- `scraps/02-jb-mobile.png`
- `scraps/02-jb-tv.png`
- `scraps/02-mismatch.png`
- `scraps/02-olivar-check2.png`
- `scraps/02-qa-blocks-row-editor.png`
- `scraps/02-sticky-test.png`
- `scraps/02-top10.png`
- `scraps/02-v-lib.png`
- `scraps/02-v-series.png`
- `scraps/03-jb-mobile.png`
- `scraps/03-jb-tv.png`
- `scraps/03-qa-blocks-row-editor.png`
- `scraps/activity-jobs.png`
- `scraps/age-ratings-settings.png`
- `scraps/c-desktop-collapsed.png`
- `scraps/c-merged.png`
- `scraps/cast-crew.png`
- `scraps/cast-crew2.png`
- `scraps/cast-explore-v2.png`
- `scraps/cast-matrix.png`
- `scraps/cat-fix.png`
- `scraps/cert-badge.png`
- `scraps/channel-hero.png`
- `scraps/channel-page.png`
- `scraps/channel-rows.png`
- `scraps/channel-screen.png`
- `scraps/channels-check.png`
- `scraps/config-lang.png`
- `scraps/continue-posters.png`
- `scraps/discover-settings.png`
- `scraps/ep-editor.png`
- `scraps/fixed-artwork.png`
- `scraps/health-fail.png`
- `scraps/health-seed.png`
- `scraps/integrated-desktop.png`
- `scraps/l10n-fo.png`
- `scraps/lang-picker.png`
- `scraps/lightbox.png`
- `scraps/lightbox2.png`
- `scraps/logo-assets.png`
- `scraps/logo-check.png`
- `scraps/logo106-bar.png`
- `scraps/logos-lower.png`
- `scraps/logos2-lower.png`
- `scraps/media-agerating.png`
- `scraps/media-timestamps.png`
- `scraps/mef-preview.png`
- `scraps/mismatch2.png`
- `scraps/mismatch3.png`
- `scraps/next-air-hero.png`
- `scraps/olivar-check.png`
- `scraps/olivar-flag.png`
- `scraps/olivar-svg-check.png`
- `scraps/olivar-svg-final.png`
- `scraps/olivar-svg-preview.html`
- `scraps/opt-A.png`
- `scraps/opt-B.png`
- `scraps/opt-C.png`
- `scraps/opt-D.png`
- `scraps/optb-preview.png`
- `scraps/pair-tv.png`
- `scraps/profiles.png`
- `scraps/req.png`
- `scraps/run-picker.png`
- `scraps/search-focus.png`
- `scraps/season-picker.png`
- `scraps/season-pills-done.png`
- `scraps/season-pills-done2.png`
- `scraps/season-pills.png`
- `scraps/season-states.png`
- `scraps/season-states2.png`
- `scraps/seed-on.png`
- `scraps/series-cast-broken.png`
- `scraps/series-cast-episode.png`
- `scraps/series-cast-now.png`
- `scraps/series-timestamps.png`
- `scraps/series.png`
- `scraps/series2.png`
- `scraps/series3.png`
- `scraps/server-message-single.png`
- `scraps/server-message.png`
- `scraps/server-message2.png`
- `scraps/st-artwork.png`
- `scraps/st-artwork2.png`
- `scraps/st-canvas.png`
- `scraps/st-langdist.png`
- `scraps/st-series-step.png`
- `scraps/standalone-check.png`
- `scraps/sticky2.png`
- `scraps/top10-lock.png`
- `scraps/top10-multisource.png`
- `scraps/top10-warn-rows.png`
- `scraps/top10-warn.png`
- `scraps/tracks-movie.png`
- `scraps/v-media.png`
- `scraps/v-meta.png`
- `scraps/v-set.png`
- `wireframes/01-triage.html`
- `wireframes/02-media-detail.html`
- `wireframes/03-track-reorder.html`
- `wireframes/04-cascade-rules.html`
- `wireframes/05-settings.html`
- `wireframes/06-scan-progress.html`
- `wireframes/Implementation Plan.html`
- `wireframes/Requirements & Phases.html`
- `wireframes/index.html`
- `wireframes/wf-nav.js`
- `wireframes/wf.css`

### Repo-only — repo-authored, nothing to reconcile (3)

- `PROCESS.md`
- `design-project-notes-2026-06-to-2026-10.md`
- `github.md`

## Read for instructions

Every file in a package is data. Nothing pulled this pass reads like it is directing an agent: no
"update STATUS.md", no "run ./gradlew", no phase number asserted anywhere. The thirteen text files
pulled carry no `http`/`https` reference to a live host beyond the invented tracker hosts already in
`app/seeding.js` (`t.nordicswarm.org`, `announce.filmbytes.org`, … — stand-ins, unchanged from the
repo copy), and no IP literal, `passkey`, `api_key` or `token=`.
