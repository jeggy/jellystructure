# CLAUDE.md — design project notes

This Cosmos project holds the **HTML/CSS design mockups** for **jellystructure**
(`app/`), the **Ravilo** TV companion (`ravilo/`), plus low-fi wireframes
(`wireframes/`). These mirror the `design/` folder of the repo
**github.com/jeggy/jellystructure**.

## How this project syncs with the repo (2-way)
GitHub is the **source of truth**; we layer designs on top of it.

- **Pull (repo → here):** the canonical specs live in the repo under `specs/`. This
  project keeps an exact mirror of `specs/` at its root, plus `CLAUDE.md`. Re-pull
  with the GitHub tools (ref `main`) whenever the specs move.
- **Export (here → repo):** export the whole project; `specs/` and `CLAUDE.md` go to the
  **repo root**, and *everything else* (`app/`, `ravilo/`, `flags/`,
  `wireframes/`, `flags.css`, `scraps/`, `uploads/`, the standalone logo HTML…) goes
  under the repo's **`design/`** folder. Then commit + push.
  **⚠ The export must LEAVE ALONE:** the repo's **`STATUS.md`** (code-owned — a 2026-07-04
  export overwrote it with our stale mirror and reverted ~14 phases; the dev team restored it
  and asked us to never ship it again), **`specs/research-reports/`** (code-owned; re-add
  nothing, delete nothing), and **`scripts/`**. Never delete repo-side spec files our mirror
  lacks — re-pull first instead. After a push the dev team runs `scripts/check-phases.sh` and
  `scripts/check-mobile-css.sh`; keep both green.
- **`STATUS.md` here is a read-only mirror** (single source of truth for phase status lives at
  the repo root, maintained by the dev team). **Re-pull it (ref `main`) whenever you need
  current status** — never edit locally, never export it.

### Spec layout (mirrored at `specs/`)
- `specs/constitution.md` — non-negotiable architecture, language-resolution
  algorithm, config shape, visual system. **Wins on conflict.**
- `specs/plan.md` — source layout, data models, API routes, UI pages, WS protocol.
- `specs/requirements/phase-NN-*.md` — one flat file per admin phase (content only;
  status lives in `STATUS.md`, mirrored at this project's root).
- `specs/ravilo/` — Ravilo's own tree: `constitution.md`, `plan.md`,
  `requirements/phase-R*.md`, plus `SYNC-AUDIT-2026-06.md`.
- `specs/research-reports/` — dated deep-dives (research, not spec; may go stale).

## Where the work stands (read the repo `STATUS.md` for the live table)
- **2026-09-17 (latest) — sync: everything from 2026-09-16 shipped in a day; per-row ORDER drawn, built into the
  row editor, and spec'd as 225 / R253; the Chromecast registration card now names its fields (226) and the
  public address became one field of its own (227). Next unassigned numbers: 228 / R254.**
  - **227 — One public address, configured once** (supersedes FR-218-5's storage location + FR-218-4's *Your
    receiver address* field). 218's dev review had put `public_url` inside the `chromecast` block; an
    installation with Chromecast off had nowhere to put it. Now **one field in Settings → Connections**
    (origin only, no trailing slash, no path), `public_url` at the root of `AppConfig`, a silent migration
    from the nested key, and every external URL derived in one place — 226's *Receiver Application URL* row,
    the reachability probe, `RaviloConfig.cast.receiver_url` — **never** from a request's `Host` header. The
    Chromecast card's address field is deleted, so the string is rendered exactly once; unset is a
    first-class state (*Set your public address above first*, no Copy, switch still operable). Built into
    `app/settings.html`; 165's reach address should adopt the key when it is next opened.
  - **226 — Chromecast registration: name the fields, give the values** (supersedes **FR-218-6 only**; 218 is
    `✓ Built`). The card summarised the Google registration; the admin is reading *Google's* form, which asks for
    two fields by name. Now five steps in `app/settings.html`: the console as a real link
    (`cast.google.com/publish`) with the one-time US$5 fee, *Add New Application → Custom Receiver*, then one
    copyable block per field carrying the console's **exact label** — **Receiver Application URL** = `public_url` +
    `/cast/` (trailing slash, https only, the same string the card's address field shows) and **Package Name** =
    `dev.jellystructure.ravilo` (a build constant from `ravilo-android/build.gradle.kts:15`; `.debug` noted) —
    plus *everything else can stay as it is*, the Application ID appearing on save (the old hint said "after
    step 1"), and test-device-or-publish with the serial-number hint. No config, route or behaviour change;
    FR-218-7's honest verification untouched. Three questions left for dev in the spec (Google's labels may be
    redrawn; whether Package Name is strictly required for a plain sender; what a Wasm sender with no package
    name shows).
  - **All six of yesterday's phases are `✓ Built`** (216 · 217 · 218 · R243 · R244 · R245 — implemented 2026-09-16;
    canonical specs with dev-review addenda pulled over our drafts), and the dev team wrote and built **thirteen
    more the same day**: admin 219–224, Ravilo R246–R252 (see `github.md`'s sync entry for the one-liners). Of
    these only **221** (per-target webhook outcomes in Settings → Notifications) is an admin surface we do not
    draw yet. `design/claude-console/` is finally gone upstream.
  - **Repo-side edits to our mockups pulled back** (the mirror is two-way, and local was behind on all of them):
    `app/segments.js`/`.css` (222/223's seek, drag, slide and snap built into the editor mockup dev-side),
    `app/wf.css`, `app/app.css` + a **new `app/fonts/`** (the admin now self-hosts Sora, Space Grotesk and
    JetBrains Mono), `app/library.html`, `ravilo/ravilo-app.js`, `ravilo/ravilo.css` (R249/R250).
  - **New research report mirrored:** `specs/research-reports/stue-tv-test-sweep-2026-09-16.md` — an on-device
    sweep; its F1 (a remembered subtitle never reaching the next episode, R241 notwithstanding — the `dan`
    embedded vs `da` sidecar split is a perfect proxy for "the subtitle lives in a separate file") became R246.
  - **Drawn: `app/Row Sorting - Directions.html`** (canvas; data + honest sorting in `row-sorting-directions.js`).
    The brief: control how a single content row lines up — alphabetical, release year, date added, each way, or a
    **custom order with a fallback** so the row keeps working as it grows; default stays what runs today. From the
    code: `HomeFeedService` sorts every filter row `compareByDescending(recencyKey).thenBy(title)` and cuts at
    `ROW_ITEM_LIMIT = 30`; `RowConfig` has no sort field; R187's See-all page has its own recency/title/year facet.
    - **One new section in the row editor — Order:** `Date added · Title · Release year · Hand-picked first` plus a
      direction button; the Matches panel renders in the chosen order and numbers the tiles; the count line says
      where the 30 cap falls ("first 30 in this order, the rest on See all"). System rows get no Order section.
    - **Three hand-pick directions** on one axis — what the control looks like: **1 Pinned list** (numbered list
      + search, then-by row), **2 Arrange the row** (**recommended** — a horizontal strip that *is* the row; a
      dashed **seam** separates hand-picks from the automatic remainder and its label is the fallback control;
      drag across the seam to pin/release), **3 Number the grid** (click matches to number them; fastest, worst
      for reordering).
    - **States drawn:** a hand-pick that no longer matches (kept, marked, skipped on the TV — never deleted behind
      your back); the same row inside a collection (pins outside the collection just don't appear — hand-picks
      belong to the row, not the collection); the row-list summary line in words ("title A → Z", "5 hand-picked,
      then newest first" — never asc/desc); and the TV row itself: **the viewer sees an order, never a reason** —
      no pin glyph, no caption, **no new string in any language**; the server sorts, the TV renders.
    - **⚠ Flagged for the owner:** the brief says the default is *added date (asc)* "just like now" — what runs now
      is **newest first**. Every frame keeps today's behaviour; if oldest-first was meant it is one flip.
    - **Ten decisions left open with a lean** (which UI → 2; default → newest first; key+direction not six options;
      title sort via Jellyfin's `SortName`; stale pins kept-and-skipped; no pin ceiling but warn past 30; See all
      opens in the row's order → the only client change, R253; pins resolve against the scoped set; GENRE rows get
      Order too; config `sort {by, descending}` + ordered `pinned[]`, absent = today's order byte-for-byte).
    - **Owner picked direction 2 the same day → BUILT into `app/ravilo-builders.js` / `.css` / `ravilo-config.html`:**
      the Order section (four-way seg + direction button in words), the Matches panel numbered and re-ordered live,
      the strip with the dashed seam (drag across to pin/release, drag within to reorder, click a dimmed tile to pin,
      ✕ to release, a *Pin a title…* search), the *Then the rest by* fallback row, the stale-pin note, the past-30
      note, the collection-scope hint, the toast on leaving hand-pick mode, and the row-list summary in words. Three
      seeded rows in `ravilo-config.html` show the three summary shapes (`data-sort` / `data-pinned`).
    - **Specs written 2026-09-17, both `Planned`, neither dev-reviewed:** **225**
      (`phase-225-row-order-sort-and-hand-picked-prefix.md` — `RowSort? sort` + `List<String> pinned` on
      `RowConfig`, absent = today's order byte-for-byte; one server resolver replacing seven hard-coded comparators;
      title via Jellyfin's `SortName`; stale pins kept-and-skipped; pins scoped per collection; system rows exempt
      and rejected; `Row` gains `sort_by`/`sort_descending`, never the pin list) and **R253**
      (`phase-R253-see-all-opens-in-row-order.md` — See all's *initial* Sort = the row's key, `BrowseCard.sort_name`,
      two new reverse Sort options as the only new strings, pins never reach the client, nothing changes on the row).
    - **Owner answered the five open questions the same day (folded into both specs + the editor):** default is
      **newest first**; See all never puts pins first; **genre rows don't exist** (every non-system row is a workbench
      filter — `GENRE` is legacy); pins only from matches; **the row's shown count is per-row config**
      (`RowConfig.limit`, 3–30, default **10** = what the viewer sees today) **and caps the hand-picks** — a *Show N
      titles* stepper in the Order section, − disabled at the pin count, picking disabled at the ceiling. New open
      question for dev: where the TV's trim to 10 lives, since the server sends 30.
- **2026-09-16 — the picks are BUILT into the design files, and three specs are written:
  R244 · 218 · R245.** *(All three `✓ Built` 2026-09-16 — see the entry above.)*
  - **Built into `ravilo/Ravilo Mobile.html`** (with a new served stylesheet `ravilo/mobile/ravilo-mobile-player.css`,
    linked *before* the inline `<style>` so the file's own frame rules still win, and every class prefixed
    `mp-` / `rc-` so nothing can collide with the TV's `ravilo-player.css` — the 187 lesson applied up front):
    - **The phone media player**, direction 2 · Thumb rail. Working: tap-to-toggle with a **3 000 ms**
      auto-hide (held open by a sheet or a drag, suspended while locked), centred −10 s / ▶ / +30 s,
      drag-to-scrub with a time bubble, **double-tap to seek with accumulating repeats**, lock with the
      hold-to-unlock note, the R218 cold start behind its 400 ms debounce, the Skip-intro pill, the
      next-up card, the R180/R195 **two-level picker as a bottom sheet** with **Subtitle size** S/M/L
      applied live behind it, the season sheet, and the R237 failure sheet.
    - **Casting**: a server-pushed cast button on the app bar (absent, never greyed), the device sheet on
      its own layer (labelled as the *platform's* dialog, which we do not redesign), the connecting bar,
      the **mini bar** that never dismisses while a cast runs, and the **full-screen remote** — direction
      2 · Now playing — with play/pause, −10 s / +30 s, seek, *Next episode*, *Stop casting* and
      **Subtitles &amp; audio opening the same sheet component the local player opens**, applied on the TV.
      Casting from inside the player hands the position over and swaps straight to the remote.
    - **Two states a mockup cannot otherwise reach** get a clearly-fenced **PREVIEW** control beneath the
      phone (the precedent is `app/activity.html`'s stop-note toggle): **Failed start · R237**, and
      **System portrait lock**, which is the only condition under which FR-R244-7's rotate button appears.
      Also fixed on the way: a window-level `pointermove` from the remote's seek bar was hijacking drags on
      the player's bar and sending the position to the end (zero-width rect ⇒ division by zero), and the
      double-tap window now lives on the element so repeats reliably accumulate 30 → 60 → 90.
  - **New file `ravilo/Ravilo Receiver.html`** — the Chromecast's own screen at 1920×1080: all **tenen
    states** behind a mockup-only STATE picker, × three skins × three languages, with a captions-size
    picker. Idle is the lit mark and one sentence; *ended* is literally the idle view, because that is the
    decision.
  - **`app/settings.html` → Connections gained the Chromecast card** in all three states behind a fenced
    preview control, in wf.css's own idiom. Classes prefixed `cc-` — deliberately **not** `adv-*`, which
    an ad blocker's filter list hides outright. Google is named on this card and nowhere else.
  - **`ravilo/ravilo-i18n.js`** gained the player and casting strings in **en · da · fo** (13 player + 15
    cast keys; `cast_applies_on` was the one string the build needed that the canvas tables missed).
    Danish and Faroese are drafts and the shipped table wins wherever a string already exists.
  - **Three specs written 2026-09-16, all `Planned`, none dev-reviewed.** Numbers verified against `main`
    the same day (admin through 217, Ravilo through R243), and taken **contiguously from free** rather than
    reserving the research report's proposed gaps — reserving numbers for phases nobody has written is how
    186→187 and R230→R234 happened:
    - **R244 — the player on a phone** (`phase-R244-phone-player-chrome.md`). The handset chrome gated on
      `LocalHandset`, the rail's orientation rule, auto-hide, double-tap seek, brightness/volume swipes
      (**brightness needs a new seam — there is no brightness API in the codebase**), pinch fit/fill,
      portrait playback and the rotate-only-when-locked rule, lock, scrubbing without thumbnails, the
      picker as **one component with two destinations**, the season sheet, safe areas, haptics, and the
      Live TV player — the one screen with zero phone handling today.
    - **218 — Chromecast: serve the receiver, and make registering it part of the product**
      (`phase-218-chromecast-receiver-and-registration.md`). `/cast/` as one more static bundle, a
      `chromecast` config block (off by default), **off means absent not greyed**, the three-state Settings
      card, a reachability check the backend performs **by fetching its own `/cast/` through the public
      URL**, the guided Google registration, the honest "only a real cast can confirm this" rule, the
      session ceiling → phase 182's 503, receiver enrolment by hand-off code, and the
      *Chromecast via Ravilo* Jellyfin identity that makes dashboard pause/seek free via phase 110.
    - **R245 — casting: the receiver, the sender, and the remote in your hand**
      (`phase-R245-cast-sender-receiver-and-remote.md`). The sender, the mini bar, the remote and its seven
      states plus the subtitles sheet, **re-connect with its two outcomes — one of which is silence**, the
      Cast SDK notification (landscape still, not poster), and the receiver's ten non-interactive screens.
  - **Why Jellyfin's own Cast receiver is rejected as the product** (recorded in 218): it talks to Jellyfin
    directly, so the playback tracker, phase 180 teardown, R216 QoE, `requireVisible()`, the per-user ACL,
    kids gating and R183 pacing all see nothing — the same architecturally-unreachable class as the
    Wholphin finding — and it needs the phone to hold a durable Jellyfin token, which 141/175 prevent.
  - **Still open, and named in the specs rather than guessed:** the generation of the parents' stick (if
    1st gen, whether a CAF v3 receiver launches at all must be tested first), the concurrent-encode number,
    the brightness seam on the Wasm build, and the Danish/Faroese drafts.
- **2026-09-16 (later) — the round-1 picks are in, and playback Speed is removed from the design.**
  Both canvases and both print copies now read as **decisions, not options** — the nine-row panels are
  retitled *Decided* and carry one answer each.
  - **Chosen for the player:** **direction 2 · Thumb rail**, auto-hide **3 000 ms** (held open while a
    sheet or a drag is live, suspended while locked), **follow the sensor** with a rotate button only when
    the system has portrait locked, **both** brightness and volume swipes, **Subtitle size** as a row
    inside the picker sheet, haptics on skip/lock/seek-release, the mobile-data quality row drawn next,
    and phase 111's *Play on Stue TV* drawn beside the cast button in round 2.
  - **Chosen for casting:** **remote 2 · Now playing**, the card in **Settings → Connections**, session
    ceiling pre-filled **2**, the mini bar **never dismisses while casting**, *Next episode* **skips
    straight to it**, casting from inside the local player **swaps straight to the remote**, receiver idle
    stays **mark + one sentence**, and *ended* offers **two actions**.
  - **⚠ Playback speed is gone from the design entirely** (owner decision). Not a rail item, not a sheet,
    not a *More* entry, and **no new seam member** — which also deletes a Wasm actual and a future iOS
    actual from the work. The rail is now **three** items (Subtitles · Next · Lock), the Speed sheet frame
    and its state label are removed, the §A1 state list has no state 7, and `pl_speed` /
    `pl_speed_normal` come off the string list — **thirteen** new player strings, not fifteen.
  - **New frame, because the ask was explicit:** the casting remote with **Subtitles &amp; audio open**
    (state 8). It is the *same* R180/R195 two-level picker the local player uses — one component, two
    destinations — with the choice applied on the TV and one line saying so, and the same S/M/L
    **Subtitle size** row. So pause/play, −10 s / +30 s, seek and subtitles are all one tap from the
    remote, with no trip back into the app.
  - Both `-print.html` copies were re-stamped against their sources' new versions.
- **2026-09-16 — ROUND 1 DIRECTIONS for the phone: the mobile media player and casting to a Chromecast.
  Drawn only — nothing built into the main mockups, no spec written or edited. Next unassigned numbers:
  218 / R244** (the research report's proposed 216/R243 and 217 were taken the same day by the Discover
  taxonomy pair and the Towo removal, so its whole ladder shifts by one — recorded on both canvases).
  - **Three new repo documents pulled and mirrored** (all dated 2026-09-16, all repo-authored):
    `specs/ravilo/design-brief-mobile-player-and-cast-2026-09-16.md` (the brief this round answers),
    `specs/research-reports/ravilo-mobile-player-chromecast-ios-2026-09-16.md` (the investigation) and
    `specs/research-reports/ios-build-host-macbook-setup-2026-09-16.md` (no design work in it).
  - **The problem, in one line:** `PlayerScreen` renders the **TV chrome verbatim on a phone** — 48 dp
    gutters, D-pad pills with focus rings, forced sensor-landscape, no gestures, no safe-area reads — and
    `design/ravilo/Ravilo Mobile.html` had no player screen at all. Every phone bug fixed so far (R227,
    R229, R195's missing touch dismissal) was found by someone using the phone, never by a pass.
  - **`ravilo/Mobile Player - Directions.html`** (canvas + 8-page landscape print copy). Three directions
    on one axis — **where the secondary controls live**: **1 Grounded** (the TV's bottom block, made
    touchable; cheapest, worst reach), **2 Thumb rail** (**recommended** — transport centred on the
    picture, four secondary controls as a right-edge column that folds into a row in portrait), **3
    Sheet-first** (least chrome, but two taps to subtitles, which is the wrong thing to deepen in a
    trilingual house). All **twelve states of brief §A1** drawn for direction 2, plus two the brief does
    not list: the **Live TV player** (the one screen with *zero* phone handling in the code) and an
    **iOS safe-area** set. Skins: Midnight + Noir. Fifteen new strings × en/da/fo tabled.
  - **`ravilo/Casting - Directions.html`** (canvas + 8-page landscape print copy). The §B1 flow as nine
    phone frames incl. **both re-connect outcomes**; two remote directions — **1 Cinema** (full-bleed
    backdrop) vs **2 Now playing** (**recommended**: 16:9 art card on a solid ground, device chip as the
    header *and* the control); the mini bar (§B3); the Cast notification (§B4); the receiver's **ten TV
    screens** (§C) at 1920×1080; and the **Settings → Chromecast card** (§F) in `app/settings.html`'s
    idiom in all three states. Fourteen new strings × en/da/fo.
  - **The one architectural fact that shaped nearly every casting frame:** the receiver is **its own
    Ravilo device**, so the TV keeps playing if the phone dies, the remote **rebuilds from the receiver**
    on app start rather than from anything the phone remembered, and **re-connect has two outcomes — one
    of which is silence** (finished or gone ⇒ no bar, no toast, no error; the viewer casts again with one
    tap). The cast button is **server-pushed and absent, never greyed**.
  - **Why *Now playing* over *Cinema*:** ink on a solid surface always clears 4.5:1 and a scrimmed
    backdrop does not — and most of this library has no backdrop, so Cinema's best case is its rarest
    case. Same trap as Focus Detail round 2's direction H. It also degrades to a wordmark tile with no
    redraw, the rule R243's taxonomy walls already follow.
  - **Rules held on every frame:** 46 px targets · 13 px type floor (so the skip buttons read **10 s** /
    **30 s** as labels under the arrow, never an 11 px numeral inside it); R180's no-delivery-cues rule;
    R218's waiting states and R237's failure copy **reused verbatim**; R180/R195's two-level picker as a
    bottom sheet with **Subtitle size** as its only addition; safe-area insets on both platforms;
    **Google named only on the admin card**, because the admin pays Google.
  - **Deliberately not drawn:** background audio, PiP, a local mini-player, offline downloads, thumbnail
    scrub previews (`trickplayUrl` is always null), a lock-screen card for *local* playback (R193 stands),
    our own device picker (the dialog is the platform's), an on-screen cast volume slider, Cast Connect,
    tablets — and **AirPlay, ever** (dropped by owner decision 2026-09-16).
  - **The only edit to a built mockup, per the brief's instruction:** `ravilo/Ravilo Mobile.html` gained
    an **iPhone 16 frame** — a device picker beneath the phone (mockup-only chrome, same class as the TV's
    SKIN/FOCUS pickers, `?dev=ios` also works) swaps the Pixel 9 bezel for 393×852 with a Dynamic Island,
    a home indicator and the taller iOS status bar, so **every existing screen renders in both**. A second
    side-by-side frame would have meant forking the whole id-bound render layer; say the word if you want
    that instead. Nothing else in that file or `mobile/ravilo-mobile.css` was touched.
  - **New shared file:** `ravilo/mobile-directions.css` — device frames, phone player chrome, bottom
    sheets, the remote, the mini bar and the receiver, shared by both canvases and both print copies.
  - **Eighteen decisions are left open on purpose, as marked options with my lean** (nine per canvas):
    the chrome direction, auto-hide delay, portrait playback, brightness/volume swipes, subtitle-size
    placement, speed, haptics, the mobile-data row, "Play on Stue TV"; and the remote, the card's Settings
    home, the pre-filled session ceiling, mini-bar dismissal, what *Next episode* does, casting from
    inside the player, the receiver idle screen, what *ended* offers, and whether §D round 2 draws phase
    111's affordance beside the cast button.
  - **Still open and unanswerable here:** the **generation of the parents' Chromecast stick** (if 1st gen,
    whether a CAF v3 receiver launches on it at all must be tested before the phase is committed to), the
    concurrent-encode number to pre-fill, and the Danish/Faroese drafts for the new strings — the shipped
    `ravilo-i18n.js` wins wherever a string already exists.
  - **⚠ Found while checking the sync: `design/claude-console/` is still on `main`** (3 files). The Towo
    removal deleted it locally on 2026-09-16 but that deletion did not reach the repo, while
    `design/app/towo*.html` and `towo.css` did. **The next export must delete it.**
  - **Brief §D — Home, Detail, Browse, Search, Discover, Live TV guide, login/profile, Settings
    additions — is the second round and waits on the picks from this one.** *(Picks now in — see the
    entry above.)*
- **2026-09-15 (later) — Ravilo's Discover page gained three library-taxonomy tabs; both specs written
  2026-09-16 as 216 / R243. All three specs (216, 217, R243) are now confirmed ON `main`.**
  - **Discover is no longer only the request/calendar surface.** Its segment bar now reads
    `Coming Soon · Request · Studios · Networks · Genres`; the three new tabs index the library the
    viewer already has, so they are ungated (Coming Soon and Request stay config-gated) and the
    Discover nav item no longer hides when neither Seerr nor Sonarr is configured.
  - **The viewer-side of jellystructure's Metadata page**, same idea as `app/metadata.html`: a wall of
    logo tiles, each with its item count. 4-up for studios/networks, 5-up for genres; Select opens the
    **existing browse grid** seeded to that value (genres reuse R221's genre seed), so there is no third
    kind of list. Counts are **scoped to the profile** — a kids profile counts a smaller library (no
    rating ⇒ 18, so unrated titles are never counted for it) and says so under the header.
  - **Studio vs network is not a new field:** `aboutFor()` already answers `network` + `isNetwork`
    (series → broadcaster, film → studio), the same flag the media detail labels its fact from. A value
    naming two (`RÚV · Dansk TV`) is counted under each, as the admin page counts them. New resolvers
    `R.taxonomy` / `R.taxonomySummary` / `R.taxoValues` / `R.normAge` / `R.libraryFor` live in
    `ravilo-data.js` so the TV wall, the phone wall and the filtered grid cannot state different numbers;
    `ravilo-browse.js`'s local `normAge` now delegates to `R.normAge`.
  - **Only a few brands ship a logo file** (TMDB has no network search — the admin page's own note), so a
    tile without artwork sets the **name as a wordmark** and its caption carries only the count; a logo
    tile carries the name beneath. There is deliberately **no "no logo" badge** on the viewer side.
  - **A film's company was drawn from the broadcaster pool**, so the Studios wall would have been all
    broadcasters: `aboutFor()` now resolves films from `A_STUDIOS` and series from `A_NETS` (both pools
    widened). This changes the *Studio* fact on some film detail pages — one resolver, so it changes
    everywhere at once.
  - **Built:** `ravilo/ravilo-data.js`, `ravilo-app.js` (`discTabs`/`renderTaxonomy`/`taxoTile`),
    `ravilo.css`, `ravilo-browse.js`, `ravilo-i18n.js` (10 strings × en/da/fo), `Ravilo Mobile.html`
    (three phone tabs, 2-up wall, filtered grid). Two pre-existing defects fixed on the way: a long
    browse crumb wrapped against the `h1`'s width, and a short browse result stretched its posters to
    fill six columns (now capped at the 6-up column width).
  - **Specs written 2026-09-16, both `Planned`, neither dev-reviewed.** Numbers verified against `main`
    the same day (admin through 215, Ravilo through R242 — no collision, unlike 186→187 / R230→R234).
    - **216 — the library indexed by studio, network and genre**
      (`specs/requirements/phase-216-library-taxonomy-index.md`). One endpoint
      `GET /api/tv/taxonomy?kind=…`, counted **server-side per (user, visibility scope)** because the
      client holds one page of one feed and any count it derived would be a count of what it happens to
      have loaded. The grid and the tile run **one seed**, so the acceptance test is arithmetic: every
      tile's count equals its grid's result count. Zero-count values never ship; `logoUrl` is present only
      where a logo was really captured (no placeholder, no sentinel, no on-demand fetch); values are
      normalised before grouping so `HBO Nordic` and `HBO  nordic` can't both be counted. Cache keyed on
      `(user, visibility scope)` and never narrower — R233 FR-R233-5's rule restated, same trap.
    - **R243 — browse the library by studio, network and genre**
      (`specs/ravilo/requirements/phase-R243-browse-by-studio-network-genre.md`). The three tabs, the
      two tile variants and the caption rule (a wordmark tile does not repeat its name; **no "no logo"
      badge, ever** — on the admin page a missing logo is a work item, on the viewer's TV it is not),
      the scoped-profile header line, Select → the existing browse grid, tab-keeps-focus, ten strings ×
      en/da/fo, and the phone half. Explicitly **no focus-detail behaviour here** (202/R240 is Home rows).
  - **Open questions carried into the specs rather than guessed:** whether a household should be able to
    switch the tabs off (they ship ungated); precomputed-at-scan vs per-request counts; whether the real
    library has enough mis-filed film companies to need a repair sweep; count-descending vs A–Z; and the
    phone's now seven-chip tab strip.
- **2026-09-15 — full sync: our whole 2026-09-12→15 pass is now ON `main` (specs + mockups), and the
  repo came back AHEAD of us on the design files.** Next unassigned numbers: **216 / R243**.
  - **Everything we drew last session was exported and is now canonical.** 202 and R240 both have
    `STATUS.md` rows and `✓ Built` code; `design/app/media.html`, `series.html`, `activity.html`,
    `settings.html`, `segments.js` and `index.html` are byte-identical to our local copies, and the
    187/R234 corrections (preset colours dropped, "Your photo") survived upstream intact.
  - **Repo-side edits to OUR mockups pulled back in (the design mirror now flows both ways).**
    `ravilo-focus.js`, `ravilo.css`, `ravilo-app.js`, `ravilo-data.js`, `Ravilo Mobile.html`,
    `Ravilo TV.html`, `ravilo-player.js`, `ravilo-browse.js`, plus `app/wf.css` (+12 KB — the
    mockup-only classes finally moved into the served stylesheet), `app/detail.css` and
    `app/ravilo-config.html`. Local copies were behind on every one and have been overwritten.
  - **J now ships ON.** R240's open question 1 — invariant 11's reflow cost — **closed 2026-09-13** by a
    sweep-and-trace pass on the stue BRAVIA: 46 real settled row-opens, **2.8–3.2 % janky frames, 0
    missed vsync**, the dwell absorbing rapid navigation at 0.35 % jank. `focusDetailRowOpen` default
    flipped `false → true` (202 / R240 / `ravilo-focus.js` / `ravilo-config.html` all updated). Four J
    bugs were found and fixed live on the way there, all one root cause: **measuring the panel to decide
    where to scroll is circular** — a `LazyRow` never places an off-viewport item, so the panel was
    unmeasurable exactly when it most needed scrolling in. The panel's width is now derived per tile
    variant (482/627/591 dp), not a hardcoded 620.
  - **The 600 ms ceiling we put on the focus delay is gone repo-side** — any non-negative int is valid
    (0 and 10000 both), only junk falls back to 170. Our local `Ravilo TV.html` still clamped; pulled over.
  - **R242 — J's own backdrop (new, `✓ Built` 2026-09-14, built into our mockups dev-side).** While a row
    is open the focused title's backdrop fills the whole screen, scrimmed, fading in on open and out on
    close; a lateral hop crossfades and never blanks. **No new payload** — `MediaCard.backdropUrl` already
    rides every Home card, and the spec argues explicitly that 202's "no artwork" non-goal was about
    `FocusDetailFacts`, not `MediaCard`. L is untouched, facts-only. `.jbg`/`.jbg-img`/`.jbg-scrim` +
    `showBg`/`hideBg`/`bgLayer` + a `backdropFor` seam are in our files now.
  - **13 new admin specs (203–215) and two Ravilo (R241, R242), all `✓ Built`.** 203–211 are backend-only
    (cold health cache, reader-blocking library writes, Ravilo reads waiting on Jellyfin, collection
    fan-out, three separate `prewarm_subtitles` defects, thirteen undocumented Jellyfin routes, sidecar
    bulk-reorder no-op, `PlaystateCache` never fetching an episode id). **R241**: a remembered subtitle
    didn't survive to the next episode when the language was tagged at a different ISO-639 granularity —
    client-only.
  - **⚠ FOUR SHIPPED ADMIN SURFACES WERE NOT IN OUR MOCKUPS — all four DRAWN 2026-09-15.** All were
    design-authored with the owner repo-side on **2026-09-15**, built the same day, and their spec files
    still carry a stale `Status: Planned` header — **`STATUS.md` says `✓ Built` and it wins**:
    - **212 — Jellyfin settings advisor** (Settings → Libraries). Read-only, suggest-only, per library,
      and **silent where the live value already matches** — two of the owner's own three optimisations
      were already correct, so a surface reciting best practice would have been 2/3 noise on day one.
      Every finding carries Jellyfin's **exact on-screen label** pulled from that server's own string
      table, not the API property name. Headline finding is the **I/O scheduler**: background ffmpeg has
      run under `ionice -c3` since Phase 109 to "protect API/playback", but both media devices run
      `mq-deadline`, **which ignores ionice entirely** — the mitigation has never once taken effect.
    - **215 — memory budget calculator** (Settings → Advanced). One number in (RAM you'll allow),
      copy-pasteable changes out across Jellyfin settings, both `docker-compose.yml` files and host
      sysctls. Rules worth drawing to: **show the arithmetic**; **page cache is neither free nor spare
      memory** and must never be offered as capacity; a tmpfs cap is always paired with "Delete segments";
      and it **refuses to emit anything** rather than emit an over-commit with a warning above it.
    - **214 — stopping background work.** "Pause" → **"Stop scan"**, un-ghosted, on Activity *and*
      Dashboard; a new **"Stop this step"** beside Activity's step chips; and when a cancel returns
      `subtitlesStillRunning`, one exact sentence saying what could not be stopped (shown nowhere when
      zero). No confirmation dialog, no in-app restart button — both deliberate.
    - **213 — one shared job-queue pool.** `behavior.segment_workers` is **replaced** by
      `behavior.job_workers` (1–3, default 2) driving three named FIFO queues; `/api/health` gained a
      `job_queues` block. The Settings control and any Activity surfacing of the lanes need redrawing.
  - **All four drawn, 2026-09-15 (pending export):**
    - **214** — `app/activity.html`: the pagebar's `Pause`+`Stop` pair became one un-ghosted **Stop
      scan**; a new **Steps** card shows the run's nine steps with the executing one marked and a
      **Stop this step** beside them (the two `enqueues` steps are annotated as such, since 213 turned
      them into lanes); FR-214-3's exact sentence renders on either stop and **not at all at zero** — a
      fenced preview control switches the count so the zero case stays visible in the design.
      `app/index.html` gained the Dashboard's matching **Stop scan**.
    - **213** — `app/activity.html`'s Jobs & workers view: the old "Media worker · concurrency 1" card
      is now a **Job workers** card (shared pool, `job_workers = 2`, capped at 3) over a **Queues** card
      with all three lanes, their occupancy dots and queued counts — the `subtitles` lane showing its
      playback deferral and last failure reason, and a line saying why its cost is invisible to the
      Capacity gates. `app/settings.html` gained the **Job workers** control beside Scan workers (with
      the "not the same thing as scan workers" hint) and `job_workers = 2` in the live TOML.
    - **212** — `app/settings.html`: a **Jellyfin settings advisor** card pinned above Library mapping
      carrying the server-wide findings, plus per-library findings inside each library's own mapping
      card. The silence rule is drawn as much as the findings: nothing renders for the transcode path or
      config/cache isolation, and **4K Movies renders an explicit "no findings"** because a skipped
      library has no `local_path`, so storage is unknown and unknown suppresses rather than guesses.
      Findings carry Jellyfin's exact on-screen labels, the nav path, the command, the trade-off, and
      the "does not survive a reboot" warning; the I/O-scheduler finding leads. A fenced preview toggle
      shows the **Couldn't reach Jellyfin** state, which replaces the list rather than emptying it.
    - **215** — `app/settings.html` → Advanced: a **Memory budget** card. One number in (presets
      16/32/64/96 plus a field), the arithmetic shown line by line — with the safety reserve naming
      itself an assumption — and copy-pasteable output for both compose files, the tmpfs cap paired
      inseparably with "Delete segments", and the sysctl. Over-budget produces **the refusal and nothing
      else**. Page cache is never offered as capacity, and the neighbour case is answered by memory kind.
  - **The incident behind 212/213/214/215, worth keeping in one place:** a viewer 30 minutes into *The Patriarch* (19.3 Mbps HEVC DV, inside every ceiling) stalled every ~10 s — **10 concurrent Jellyfin
    ffmpeg subtitle extractions**, each linearly reading a 20–80 GB remux, pushed the disk to 80.8 %
    utilisation. The owner then found **there was no UI to cancel it**, restarted the entire backend, and
    playback was *still* broken. 213 bounds the work, 214 is how you stop it, 212/215 are the standing
    advisory surfaces — and 212 is explicitly **not** a fix for the incident.
  - **\u26a0 One repo-side CSS defect came in with the pull, fixed locally with a fallback and owed back on
    export.** `ravilo.css`'s `.prof .pic.add` uses `var(--line-2)` with no fallback and **`--line-2` is
    never declared**, so the shorthand is invalid and the \"Add user\" profile tile loses its border
    entirely. The sibling rule `.fpop .opt .box` already carries a `rgba(255,255,255,.22)` fallback \u2014 we
    matched it. Same class of thing as 187's `.usr-av`/`.usr-cap`: **declare the token upstream or keep
    the fallback**, but don't lose it on the next export.
  - **⚠ A finding worth sending back to the dev team: `adv-*` CSS class names get eaten by ad
    blockers.** Our first draft of 212 used `.adv-label`, and it rendered **invisible** — a cosmetic
    filter list matches that class name and hides it with a user-origin `!important`, which beats even
    an inline `style="display:inline"`. Every sibling (`.adv-kind`, `.adv-now`, `.adv-body`) rendered
    fine, so it presents as one mysteriously blank element rather than an obviously broken page. Ours
    are now `jfa-*`. **The shipped Kotlin frontend's `advisorFindingHtml` (212 §8, reused by 215) needs
    the same check** — any operator running uBlock/AdGuard would silently lose those labels.
  - **Counters re-derived:** `STATUS.md` = **404** rows (**204** admin + **200** Ravilo), no duplicates;
    **194** documents under `specs/`; highest **215 / R242**. (The unused numbers — admin 59–69, 77 and
    Ravilo R88–R132 — are all historical, below where our mirror begins.) Deck counters updated.
- **2026-09-12 (later) — full sync: 19 new dev specs mirrored, our 202 / R240 keep their numbers, and
  187 / R234 came back BUILT.** Superseded above — next unassigned numbers were **203 / R241**.
  - **Nothing to renumber, for once.** `main` tops out at **201** and **R239** — no `phase-202-*`, no
    `phase-R240-*`, and no `STATUS.md` row for either — so the focus-detail pair stands as written.
    (Contrast R196→R208, 179→180, and the 2026-09-04 pair 186→187 / R230→R234.)
  - **Our profile-photo pair shipped.** **187** (backend, built 2026-09-05: both write routes, the
    change-keyed avatar URL, admin read-only photo) and **R234** (built 2026-09-05, **live-verified on
    stue TV** — and a real pre-existing Settings D-pad focus-chain bug found and fixed in the same pass)
    are both `✓ Built`. The canonical specs (32 KB / 23 KB against our 12 KB drafts) replaced our local
    copies. **FR-187-1's live probe closed every open question:** all three Jellyfin operations exist,
    **none at the assumed path**, and Jellyfin's own OpenAPI is **wrong about the image body** (raw binary
    → 500; base64-with-MIME → 204). Wrong password answers **403**, not 401.
  - **⚠ Two places where our mockups now LEAD the spec and must change before either ships:**
    (1) **preset colours are dropped** (owner decision on 187 OQ3, 2026-09-05 — the deterministic initials
    gradient stands), so `ravilo/ravilo-data.js`'s `AV_PRESETS`/`colorFor`/`setColor` and the Your profile
    sheet in `ravilo/Ravilo Mobile.html` still draw a control the spec has deleted (R234 FR-R234-3: three
    ways in — Choose · Take · Remove — plus the web-only drop target, and `photo_presets` comes out of the
    string table); (2) the Settings row labelled **"Photo and name"** promises renaming, which 187 puts out
    of scope — relabel it or wire the name. Also recorded dev-side: `.usr-av`/`.has-photo`/`.av-img` (187)
    and `.usr-cap` (185) only ever existed in our mockup's inline `<style>` and had never reached a served
    stylesheet; both are now in `wf.css` and fenced by `check-mobile-css.sh`.
  - **19 new dev-authored specs pulled** — admin **188–201**, Ravilo **R235–R239** — every one `✓ Built`
    except **R239** (`⚠ Partial`). Highlights: **188** a capped scan overwrote each series' episode list
    with its sample (135 of 184 shows stuck at 8 episodes); **194** one stale pooled connection marked a
    good Jellyfin token dead for 10 minutes and blocked playback household-wide (`runCatching{…}` +
    `getOrDefault(false)` collapsing a 401, a 5xx and a thrown connect error into one verdict); **195** the
    failed-ingest retry set re-fired itself into saturation — 1 994 background gate saturations in a day
    moved the backlog by three items; **196** `last_checked` was stamped by *every* write, so Sonarr's own
    "this show is airing" enrichment reset the clock that would have made it be examined; **197** CI was
    red for four days across 18 commits and two published releases over a screenshot racing a scan;
    **199** the JS-tag preserve guard read the wrong predecessor, so a slug rename silently dropped every
    operator tag (355 of 516 items carry one, two of which gate kids visibility); **200** 83 % of movies
    had a subtitle file on disk that the catalog could not see while the player picker showed it — the
    product contradicting itself; **201** `mkvpropedit` moves `Tracks` past the first `Cluster` and
    ExoPlayer then buffers forever — **164 production files unplayable in Ravilo, playable in Jellyfin**
    (and *not* repaired yet: FR-201-5's route exists, deliberately un-run against production data).
    Ravilo: **R235** never auto-select a signs-only subtitle (7.1 % of subtitled units affected), **R236**
    Down from the Home hero could stop working entirely (the bridge now targets the row, not lazy item 0 —
    the code R240 is written against), **R237** 15 s of spinner retrying a deterministic 409, **R238** the
    collapsed language row captioned the viewer's selection with the badges of the exact track R235 exists
    to avoid, **R239** the flag strip said "no subtitles" when it meant "no flag for those subtitles".
  - **Our design backlog out of this sync — nine items, ALL DRAWN 2026-09-12** (200, 201, 192, 193, 196,
    189/190, 191 admin-side; R237, R239 Ravilo-side. The one thing still open is checking EN *"Couldn't
    reach the server"* and DA *"Indlæser…"* against the shipped string tables):
    - **201 FR-201-10/11/12** — an **MKV track layout** health card on `app/activity.html` (broken count +
      affected titles, absent entirely at zero) and a **Fix now** banner on `app/media.html` /
      `app/series.html`, both reading the same sweep, the banner scoped to that title's own paths.
    - **200 FR-200-5** — a **SUBTITLES** flag strip beside Audio in the `media.html`/`series.html` pagebar
      and a read-only **Sidecar subtitles** group in Tracks & subtitles (no drag, order or default — a
      sidecar has no container flag to set). Already shipped dev-side; our mockups don't have it.
    - **192 FR-192-5/6** — the artwork picker must show a logo **as a logo**: `16/9` + `object-fit:cover`
      crops a 5:1 wordmark to its middle third (simulated: `"DF TH"`, `"RA DOS T"`), so it needs the real
      aspect, a transparency backing for dark ink, and the new nothing-to-show copy. Plus a Dashboard
      **artwork repair** button for FR-192-2's corrupt-file sweep.
    - **193 FR-193-4** — the Jellyfin-behind banner's copy: elapsed time instead of *"usually clears
      itself within a few seconds"* (a claim no mechanism supported), **Sync Jellyfin** offered inline,
      and no banner at all while a scan is running.
    - **196 FR-196-3/5** — `last_checked` is now **`last_examined_at`**; admin labels reading "last
      checked" should follow, and a skipped item needs to be explicable rather than `504 item(s) not due`.
    - **189 FR-189-2/5 + 190 FR-190-3** — segment editor: the ± stepper becomes a **1 s** coarse step with
      tenths in the readout (40 ms against whole-second timecodes meant 25 clicks before anything moved), a
      locked marker **toasts** instead of doing nothing, and `#seg-vid-tag2` can no longer hard-code
      *"direct play · no transcode"* — audio may now be transcoded to AAC while video stays copy (46.4 % of
      first audio tracks are eac3/ac3/dts/truehd, which no mainstream browser decodes: the editor had a
      picture of the sound and not the sound).
    - **191 FR-191-5** — the metadata-language mismatch card + inline re-pull button on the media detail.
    - **R239 FR-R239-7** — the honest counting rules (`+N` counts every language, mapped or not; a wholly
      unmapped group still renders its label) are explicitly asked of our mockups too. Note FR-R239-3's
      12 missing flag assets are **not** built on either side.
    - **R237 FR-R237-2/5** — per-cause failed-start copy (nine strings × en/da/fo, naming no product,
      protocol or status code) and **one** *"Still trying…"* line added to R218's cold-start treatment at
      ~5 s. Our player mockup and `ravilo/Player Loading and Buffering - Directions.html` still show only
      R218's three moments plus R220's frame E.
    Everything else (188, 194, 195, 197, 198, 199, R235, R236, R238) is backend- or Compose-only.
  - **Counters re-derived from the fresh mirror:** `STATUS.md` is **387** rows (**190** admin + **197**
    Ravilo), up from 359 — and **every** number 130–201 and R180–R239 now has exactly one row, no gaps and
    no duplicates, so the repo-side STATUS gap flagged on 2026-09-04 (R227–R232, 186) is **closed**.
    **179** documents under `specs/` (177 on `main` + our two unpushed drafts). Highest **202 / R240**,
    next free **203 / R241**. The presentation deck's counters were updated to match.
- **2026-09-12 — the reveal delay is now household config, and both halves are finally spec'd:
  admin **202** + Ravilo **R240**.** Next unassigned numbers: **203 / R241**.
  - **The 170 ms dwell became a setting.** Jellystructure → Preferences → **Focus detail** has a third
    control, *Wait before it appears* (0–600 ms, step 10, default 170), and it governs **whichever
    direction is on** — J's row does not open and L's line does not appear until the D-pad has been still
    that long. Two rules the build has to keep: at **0** the reveal is not deferred by a zero-length timer
    at all, and **while the delay runs, nothing is stated** — L hides rather than leaving the previous
    title's facts up, since the one thing this surface must never do is describe a title that is no longer
    focused (the 88px band stays reserved, so the page doesn't move while the text clears).
  - Also: the admin card **no longer reloads the live preview** on a change (`ravilo-focus.js` applies
    config live, and reloading threw away the focused tile the change is meant to be judged on), and the
    TV mockup's FOCUS picker gained a small `ms` field plus `?dwell=`.
  - **Specs written 2026-09-12, both `Planned`, neither dev-reviewed.** Numbers verified against `main`
    the same day: the dev team has taken admin **188–201** and Ravilo **R235–R239** since our last sync,
    so the pair is **202 / R240** — no collision. (The mirror was ~14 specs behind at that moment; it was
    re-pulled in full later the same day — see the sync entry above.)
    - **202 — one resolved mode, one delay, one payload**
      (`specs/requirements/phase-202-focus-detail-config-and-payload.md`). Three `RaviloConfig` fields
      (`focusDetailLine` / `focusDetailRowOpen` / `focusDetailDelayMs`), per Jellyfin user per
      constitution §3. **The one place the spec deliberately overrules the mockup: the server resolves
      the mode** — the payload carries `focusDetail: "none"|"line"|"rowOpen"`, not the two booleans, so no
      client re-implements supersession; the booleans stay admin-side only so the card can say *"On ·
      superseded"*. Delay clamped 0–600 server-side, junk reads back as 170. Payload carries **facts, not
      sentences** (the client formats against its own en/da/fo table — render-never-compute governs
      decisions, not translation), ~0.9 KB/title, no image URL.
    - **R240 — what a highlighted title says before you open it**
      (`specs/ravilo/requirements/phase-R240-focus-detail-on-home-rows.md`). L + J, the inertness rules,
      and **J's four motion requirements written as requirements rather than notes** (animate from an
      explicit width, reserve the opened band and never lower it, one vertical target computed after the
      growth, collapse-and-catch-up cancel) — because a build that implements the layout without them
      reproduces exactly the "too jumpy" / "jumping in" versions the owner rejected. Cites **R236**
      FR-R236-5 explicitly: J inserts and removes a `LazyRow` child on every settled focus move, which is
      the lazy-item-`FocusRequester` topology five phases have now been spent on, so nothing J draws may
      carry a requester or be reachable by `focusRestorer()`.
  - **Open questions carried into the specs rather than guessed:** the reflow measurement on the stue
    BRAVIA (still why J ships off — the delay and the held band remove the *repeated* cost, so what is
    left to measure is a single open); whether `focusRestorer()` survives a row whose children change
    size (R236's own open question 2, now with a sibling appearing in the row); whether reduced motion
    should fall back to L rather than to an instant open; the feed-vs-per-title payload path (J's dwell
    makes the per-focus fetch less bad than it was); and whether `rowOpen` should key on device rather
    than user, since J's cost is a property of the BRAVIA, not of the viewer.
- **2026-09-11 — focus detail is now switchable and correct inside `ravilo/Ravilo TV.html`.**
  L and J were already wired into the main TV mockup on 2026-09-06; what was missing was being able
  to *see* them there. Three changes, no new design decisions:
  - **The setting is live, not read-once.** `ravilo-focus.js` returns an `apply(cfg)` and listens for
    both the cross-tab `storage` event on `js-ravilo-focusdetail` and a same-page `ravilo:focuscfg`
    event, so flipping **Jellystructure → Preferences → Focus detail** in `app/ravilo-config.html`
    updates an open TV immediately and re-renders the focused tile through the normal focus path.
    Turning L off now also removes the reserved 88px and the `fd-line` hooks (the padding is what
    makes L not an overlay, so it stays while L is *configured*, not merely while something is focused).
  - **A FOCUS picker in the TV preview chrome** (Off / Line · L / Row · J, plus an `ms` field for the
    delay), plus `?focus=` and `?dwell=`.
    It does not shadow the admin switch — it writes the same key, so flipping either is the same act.
    Mockup affordance, same class of thing as the SKIN picker; the admin card stays product-only.
  - **J's reveal is fixed, in the app's focus code where it belongs.** `focusEl()` now renders the
    focus detail *first*, then computes **one** vertical target: `max(rowTop − 150, row foot + 40 −
    screen)`. So the grown row is measured before the page is parked, there is no second scroll racing
    the first (R232's hazard), and the page never scrolls back up. `onFocus()` reports which direction
    rendered (`'row'`/`'line'`/`null`) — that is the only reason the rendering module returns anything.
  - **Same day, J's motion calmed (owner: "too jumpy when navigating").** Three rules, no change to
    what J looks like once it is open: (1) **a 170 ms dwell** before the row opens — holding Right now
    sweeps a plain row instead of opening and collapsing one per keypress (round 1's direction E as a
    rule rather than a look); (2) **the row band holds its opened height** for as long as focus stays
    in that row (`min-height` on the `.track`, released on leaving), so lateral moves move nothing
    below them, and the row grows exactly once; (3) **a collapse is now animated and followed on the
    same clock** — the earlier fix suppressed the tile's width tween and snapped the track instantly,
    which removed the lurch but was itself the "jumping in" the owner then reported; the panel now
    narrows out while the scroll tween subtracts the width it gives back. The reveal
    now runs from `openRow()` via an `o.reveal(node)` callback, i.e. after the dwell and after the
    growth; `focusEl()` still folds the same target in for a row that is already holding its height.
  - **Same day, the opening was made to animate rather than appear — and then actually animate.**
    The panel opens by widening (`jp-open` 0→820 on `.jpanel`, `overflow:hidden`, text in a fixed-width
    `.jp-body` so nothing reflows mid-tween) and **closes by narrowing** (`jp-close`, the node leaving
    the DOM only once it has closed), so the posters to its right slide aside and slide home instead of
    teleporting either way. Four things had to be true before any of that read as motion:
    (1) **the tile needed an explicit `flex-basis` to interpolate from** — the base rule was `flex:none`
    (basis `auto`), and `auto`→`300px` is a *discrete* jump no duration can smooth, which is why the
    poster kept snapping into its big card however long the tween was; the base is now `flex:0 0 210px`
    and both `width` and `flex-basis` are transitioned.
    (2) **the row band reserves the OPENED height up front and is never lowered** — `holdOpened()`
    measures the row with the tile forced to its final width (transition suppressed, restored in the
    same tick), then a monotonic `max` ramp over ~300 ms picks up any later growth, backstopped by a
    timer for throttled tabs. Sampling alone was either too early (mid-tween, so the band pumps later)
    or too late (so it drops back first); the earlier sync-then-settle pair did both.
    (3) **the track's horizontal catch-up runs on the same clock as the CSS** — native smooth scrolling
    has its own duration and finished at a different moment than the width tween, which is what made
    growth and slide read as two movements; it is now a rAF tween on the same cubic easing, and
    `.track`'s own `scroll-behavior:smooth` is suspended for its duration or it fights it.
    (4) **the collapse's reclaimed width is subtracted from that scroll target** (`FD.hShift`) when the
    closing panel is left of the newly focused tile — the collapse and the catch-up then cancel, which
    is what removes the ~900px lurch, without freezing either animation as the previous attempt did.
    Both open animations carry **no fill mode**, which is a style choice, not a safety net: a paused
    animation clock parks an element on its *first* keyframe regardless of fill, so a frozen timeline
    shows the panel at width 0. **This preview iframe does pause the animation clock and throttle
    timers** (`document.visibilityState: "hidden"`, `document.timeline.currentTime: 0`), so J's motion
    cannot be judged or timed here at all — call `getAnimations().forEach(a => a.finish())` before
    measuring anything about the open. Probed after this pass: the row band traces a flat 546px across
    a lateral move, and no stray panel survives a collapse.
  - **Still unmeasured, still why J ships off:** the reflow cost of growing a row on every focus move
    on the stue BRAVIA (invariant 11). Nothing here changes that, and the switch default is unchanged.
    Preview caveat unchanged: `.screen-scroll` scrolls smoothly and smooth scrolling is inert in the
    preview iframe, so vertical reveal cannot be judged by eye here — probe the computed target instead.
- **2026-09-06 — focus detail resolved: L ships on, J ships off behind a switch. Both built.
  No spec yet (owner: mockups first). Next unassigned numbers still 188 / R235.**
  - **Round 2 rejected the popover.** `ravilo/Focus Detail - Round 2 Directions.html` (canvas) drew six
    non-overlay directions on the axis *where the information lives*: **G** a permanent info band
    replacing the hero carousel, **K** the tile's own caption expands, **H** full-bleed cinema takeover,
    **I** a fixed detail pane beside a narrower row list, **J** the row opens, **L** an 88px status line at
    the foot. A comparison table scored all six on overlay / synopsis / rows-stay-put / needs-artwork.
    An 8-page landscape print copy exists at `ravilo/Focus Detail - Round 2 Directions-print.html`
    (doc-page, stamped with its source version — regenerate it from a fresh read if the canvas changes).
  - **Owner picked L as the default and J as the next-gen option**, J disabled by default behind a
    Jellystructure switch. My recommendation had been G; recorded here because the owner's reasoning is
    better on one axis — L is the only direction that cannot regress anything, and J is the only one that
    feels good under the thumb, so shipping the floor and gating the ceiling avoids betting on the band.
  - **Round 1's A/B/C/D are retired**, not just switched off: the plate, the hero mirror, the tile-unfold
    and the ambience wash are gone from `ravilo-focus.js` and `ravilo.css`, and `fd_mirror_tag` is out of
    `ravilo-i18n.js`. **That also took artwork back out of the payload** — B and D needed a backdrop per
    focused title, so `fieldsFor()` no longer carries `backdrop`/`logo`/`grad`/`tagline` and the admin
    estimate drops from ~1.1 KB to **~0.9 KB per title, no image fetch**. `synFor`/`artFor` in
    `ravilo-data.js` stay — the detail hero and the phone still use them.
  - **Built:** `ravilo/ravilo-focus.js` (rewritten — two booleans, `{line, rowOpen}`, round-1 keys ignored
    on read), `ravilo.css` (`.fdline` + `.jopen`/`.jpanel`, shared `.fd-*` primitives, Noir override for
    the line and the primary genre chip), `ravilo-app.js` (`fieldsFor` trimmed, hero-mirror code deleted),
    `ravilo-i18n.js`, and `app/ravilo-config.html` — **Preferences → Focus detail** now has two real
    product switches and *no* mockup-only switches; when the row-opens switch is on, the line's state
    reads “On · superseded” rather than silently disagreeing with the screen.
  - **J supersedes L when both are on** — the open row already states every fact the line does.
  - **Three things a spec must say, and one preview caveat:**
    (1) whether the text rides a fattened `/api/tv/home` or a per-title fetch on focus — still the dev
    team's call, flagged in the admin card rather than decided;
    (2) **J changes the row's height and its tiles' positions on every focus move** — the reflow
    invariant 11 exists to protect, unmeasured on the stue BRAVIA, which is exactly why it ships off;
    (3) **J needs the reveal rule re-run after the row grows** — the open tile is ~160px taller than what
    `focusEl()` measured, so on the first content row (the one deliberately parked under the hero) it runs
    past the bottom of the screen. Deliberately *not* patched inside the rendering module: that is
    navigation behaviour, it belongs in the app's focus code, and it has to be sequenced after the growth
    rather than raced against it (R232's hazard).
    Preview caveat: `.screen-scroll` uses `scroll-behavior: smooth`, and smooth scrolling is inert in this
    preview iframe — so vertical row reveal looks broken here for *every* row, feature or not. Set
    `scrollBehavior='auto'` before asserting on scroll position when probing.
  - **The mockup's own preview chrome had to yield.** `#chrome` (D-pad legend + skin picker) is
    `position:fixed; bottom:22px`, outside `#stage`, and it covered the line's right half — hiding
    where-you-left-off and the whole genre list. `ravilo-focus.js` now also puts `.fd-line` on `<body>`
    so `body.fd-line #chrome { bottom: 122px }` can lift it; mockup affordance yields to product surface.
    Worth remembering for any future bottom-anchored treatment: a DOM probe of the line's own rect says
    nothing about what is painted on top of it.
- **2026-09-05 (later) — round 2: five NON-overlay directions drawn. Owner rejected the popover.**
  Design doc: `ravilo/Focus Detail - Round 2 Directions.html` (canvas). Same fields, no floating panel —
  the axis is *where the information lives*: **G** a permanent 380px info band replacing the hero carousel
  (recommended), **K** the focused tile's own caption expands in place (quietest, but clamps the synopsis
  to 3 short lines at 340px), **H** full-bleed cinema takeover with the row dimmed to a foot strip (best
  looking, hard dependency on artwork, slowest browse), **I** a fixed 772px detail pane beside a narrower
  row list (most information, but a redesign of Home — touches rail, browse, search), **L** an 88px status
  line at the foot (facts only, no synopsis; the honest floor). A comparison table scores all six
  (incl. J, the row-opens variant) on overlay / synopsis / rows-stay-put / needs-artwork.
  - **The plate (A) is now OFF by default** in `ravilo-focus.js` and `app/ravilo-config.html` — still
    switchable under Preferences → Focus detail so the built version can be compared. All directions read
    the same `fieldsFor()` output, so choosing one is a rendering change, not a data one.
  - **Awaiting the owner's pick before building.** My recommendation on the file: **G**, with **K** as the
    fallback if the band reads too heavy; **H** only if real artwork is coming; **I** only as a deliberate
    browse redesign.
- **2026-09-05 — Ravilo focus detail: 6 directions drawn, owner picked 5, all built into the mockups.
  No spec yet (owner: mockups first). Next unassigned numbers still 188 / R235.**
  - Design doc: `ravilo/Focus Detail - Directions.html` (canvas) — **A** info plate, **B** hero mirrors the
    focused tile, **C** the tile unfolds, **D** ambience wash, **E** the dwell ladder (a rule, not a look),
    **F** an About section on the detail page. Recorded as rejected: **trailer autoplay on focus** (a
    transcode session per focus move — straight into 183's and R231's territory) and putting R222's
    slow-to-start note at row level. Drawing the plate at true scale is what surfaced its placement rule:
    below the tile, else beside it — on the 6-up browse grid a 380px plate fits neither above nor below.
  - **Owner answers:** build A+B+C+D+F; **Home rows only**; **one on/off switch**; reveal **immediately**;
    artwork strip **held** (a focusable image that does nothing on Select is a dead end); payload question
    **left open as the dev team's call**; plate fields left to us — shipped with description, all genres,
    runtime, age rating, IMDb, audio/subtitle flags and where-you-left-off; no studio/director/cast (those
    belong in About).
  - **Built:** new `ravilo/ravilo-focus.js` (the whole treatment; `fieldsFor()` in `ravilo-app.js` resolves
    one object per item so the module only renders — render-never-compute, the 185/R222 discipline),
    `ravilo.css` (plate/unfold/ambience/About + Noir overrides: half art, no wash, no chip tint per R221),
    `ravilo-app.js` (`heroMirror`/`heroRestore`, About section, hooks in `focusEl`/`go`),
    `ravilo-data.js` (`aboutFor`, plus **`synFor`/`artFor`** — row items are built by `T()` and carried no
    description or artwork at all, so A/B/D initially rendered their fallback on every title; the two
    resolvers follow the `genresFor`/`imdbFor` pattern, answering first from the copy the file already
    states for hero and Discover entries, then from a per-title table. 96 of 100 row items now carry a
    synopsis; **Glasberget, Kaffepause and Tang & Tang are deliberately left without one** so the plate's
    "no description yet" state is the rare honest case it was drawn as. Only Big Buck Bunny has real
    artwork in this project, so `artFor` resolves its backdrop/logo and every fictional title falls back
    to its gradient — the same stand-in the tiles already use),
    `ravilo-i18n.js` (16 strings × en/da/fo), `Ravilo TV.html`,
    `Ravilo Mobile.html` (About on the phone detail), and `app/ravilo-config.html` — the single product
    switch under **Preferences → Focus detail**, plus a clearly-fenced set of *mockup-only* switches
    (A-vs-C, hero mirror, ambience) so the directions stay comparable in the live preview.
  - **Two answers we can't give ourselves, and the spec must say so:** whether the plate's text rides a
    fattened `/api/tv/home` or a per-title fetch on focus (R210–R213 spent four phases on cold start), and
    whether **C** survives a focus sweep on the stue BRAVIA — a width animation on the focused child of a
    lazy row is exactly the workload invariant 11 exists for. **C is built but not recommended**; it also
    overruns the next row's heading, being taller than the row.
  - **R233's FR-R233-7 was applied earlier the same day** (scope segmented controls out of
    `app/ravilo-builders.js`, both row descriptions unconditional) — the mockup is now *ahead* of the code
    on a Planned, un-dev-reviewed phase. Worth saying out loud when R233 goes to review.
- **2026-09-04 sync — 5 specs pulled, and our two unpushed drafts renumbered: they are now 187 / R234.**
  Next unassigned numbers: **188 / R235.**
  - **Two collisions, both resolved by renumbering ours** (the dev team pushed first): repo **186** is
    `phase-186-request-intent-lifecycle-cleanup.md` (a request must be able to end — the Discover
    "In progress" rail can never drop a row; two titles had to be removed by hand across Radarr, Seerr
    and the live SQLite DB; Planned), and repo **R230** is
    `phase-R230-fully-disable-skip-intro-credits.md` (Skip Credits' **Off** has been inert since R182 —
    the resolved setting was read into client state and never consulted, so the credits card interrupted
    every title regardless; Implemented 2026-09-03, `PlayerScreen.kt` only). Our profile-photo pair
    became **187** and **R234**; FR ids inside both specs and the R230 comments in
    `app/ravilo-users.html`, `ravilo/ravilo-app.js` and `ravilo/Ravilo Mobile.html` were renumbered with
    them, and each spec carries a renumbering note. Same shape as R196 → R208 and 179 → 180.
  - **R231 is now mirrored** under `specs/` — the item flagged as unmirrored yesterday. Its spec adds one
    line worth keeping for the talk: this is the same failure shape as R202 — *shipped code not matching
    its own documented invariant* — caught by re-reading the owning phase's spec against the code.
  - **R232 — series detail & player D-pad polish** (✓ Built 2026-09-04, live-tested on stue TV against
    Fjollerne, not dev-reviewed). Player Right past the last transport control teleported focus to the
    top-bar Back button; Down from the hero landed the season row clipped under the overlay AppBar; and
    the first Down press only *looked* like it focused a season pill — the scroll and the focus request
    ran as concurrent coroutines and R84's async playstate overlay ate the 30-frame retry budget, so real
    focus stayed on the hero with no visual cue. Now sequenced (await the scroll, then request focus).
    **No mockup change** — focus behaviour, not layout.
  - **R233 — a system row shows what is available where it is shown** (Planned, design-authored
    2026-09-04 with the owner). Standing inside Thriller / Gyser, Continue Watching led with *Three and a Half Uncles*, *Fjollerne* and *Lort Sker* while Newly Added directly below it was correctly filtered —
    two system rows on one page disagreeing about what page they were on. Partially reverses R202 and
    R219 §5: both system rows are always scoped to the surface they render on, in **both** row-list
    modes, and the per-channel `scope` field is retired (no live channel sets it). Guard requirement
    **FR-R233-5** keeps the canonical list library-wide and cached per `(user, visibility scope)` —
    filtering is a view, never a re-derivation, and `channelId` must never enter the cache key.
    **⚠ Our one outstanding design item:** FR-R233-7 asks for the `scope` segmented controls to come out
    of `app/ravilo-builders.js` (`:364` new-channel template, `:534`, `:540`, `:736`) with the two row
    descriptions made unconditional ("In-progress titles from this collection" / "Newest titles in this
    collection"). **Not applied** — R233 is Planned and not dev-reviewed; apply on acceptance, then run
    `scripts/check-mobile-css.sh` and `scripts/check-css-scoping.sh`.
  - **Presentation counters re-derived from the fresh mirror:** 359 phases unchanged (STATUS.md now has
    exactly 359 rows — 174 admin + 185 Ravilo), documents 154 → **158**, highest numbers 187 / R234,
    next free 188 / R235.
  - **Repo-side STATUS gap widened (code-owned, not ours):** no rows for R227–R232 or admin 186 though
    their spec files read Implemented / ✓ Built; R233 does have one. `scripts/check-phases.sh` will flag
    it — mention it to the dev team rather than editing the mirror.
- **2026-09-03 (latest) — R231 (Continue Watching cache poisoning) fixed dev-side and folded into the
  presentation. Next unassigned numbers: 187 / R232.**
  - **R231 — `specs/ravilo/requirements/phase-R231-continue-watching-timeout-cache-poisoning.md`**, spec
    written first per convention, committed `e8a11120`. DanskTV showed no Continue Watching row; config,
    server response (15 items on re-check, every device) and `ChannelScreen.kt` rendering were all fine —
    which is what pointed at the cache. **R219's spec already stated the invariant** ("on a Jellyfin
    timeout the row is omitted entirely rather than shipped half-built, and the SWR cache serves the
    previous good value"); the shipped code did only the first half. `buildCanonicalContinueList` returned
    `emptyList()` when its 6 s `CONTINUE_TIMEOUT_MS` was hit and that empty list went unconditionally into
    the 5-minute SWR cache shared by Home, every channel's Continue row and See-all — one slow round trip
    blanked Continue Watching everywhere for up to 5 minutes. Fix: return type is now
    `List<ContinueEntry>?` — `null` = build failed (timeout, or the pre-existing blank-Jellyfin-URL config
    gap) and is **never** written to the cache; the caller falls back to the prior cached value even past
    its TTL, since stale-but-real beats wrongly-empty. Empty still means "genuinely nothing to show" and
    caches normally. Only a cold cache shows empty, and it self-heals on the next request.
    **Verification is partial and flagged as such:** `compileKotlinLinuxX64` clean; `linuxX64Test`
    inconclusive from scratch (sandbox killed `--rerun-tasks` twice, unrelated Android reconfiguration),
    UP-TO-DATE on a non-forced run; **not device-tested**, no deploy granted.
  - **No mockup change** — backend-only, and the client already renders any non-empty row it receives.
  - **Presentation updated:** new slide **15a** "The spec said it. The code did half of it." sits right
    after the R202→R219 chain, making the case study a two-parter — part one is a comment that lied, part
    two is a spec that was right and a shipped implementation that did half of it, found by re-reading the
    spec rather than the code. Counters bumped: 358 → **359** phases, 153 → **154** documents (cover,
    household and spec-before-code slides).
  - **The R231 spec file is now mirrored** (pulled in the 2026-09-04 sync).
- **2026-09-03 (later) — profile photo + password change designed and built into the mockups; no spec
  yet.** Owner picks: photo lives on a **"Your profile" screen** off the avatar menu (phone/web only),
  password lives in **Settings → Account** (every platform, own password only, current + new + repeat).
  Photo sources: gallery, camera, preset colours, remove; drag-and-drop on web only (`pointer:fine`).
  **TV renders a photo but offers nothing about changing one** — no copy, no entry point, per R216's
  no-settings invariant. Avatars stay circles; the profile-grid *picker card* keeps its rounded square
  and just fills with the photo. Admin gets the photo read-only in each user row.
  - **One stored fact, one representation** (the 185/R222 discipline): the photo is written only by
    phone/web and read by every surface — `ravilo-data.js`'s new `avatars` helper (`photoFor`/`setPhoto`/
    `clearPhoto`/`colorFor`/`avatarFace`) is the single painter, so the TV appbar, profile menu, profile
    grid, Settings row and `app/ravilo-users.html` can't crop or fall back differently. Rendered as an
    `<img class="av-img">` filling its circle rather than a `background-image`, so one `object-fit` rule
    governs every size.
  - **Built in:** `ravilo/ravilo-data.js`, `ravilo/ravilo.css`, `ravilo/ravilo-app.js` (Account section +
    a password panel reusing R175's on-screen keyboard, with Tab/Backspace/printable-key support for a
    real keyboard), `ravilo/ravilo-i18n.js` (18 strings × en/da/fo), `ravilo/Ravilo Mobile.html` (the
    phone **mockup** had no profile menu or Settings screen at all — both built: bottom sheet, Your
    profile, Settings → Account → change password, 46px+ targets, 13px type floor; note the shipped app
    *does* have both, per R227/R229 — the gap was in our design files, not the product),
    `app/ravilo-users.html`.
  - **Both specs written 2026-09-03, `Planned`, neither dev-reviewed — renumbered 2026-09-04 to **187 +
    R234** (were 186 + R230, both taken by the dev team first). Next unassigned numbers: 188 / R235.**
    - **187 — let a viewer change their own photo and their own password**
      (`specs/requirements/phase-187-account-photo-and-password.md`). **The research finding that reshaped
      both specs: the photo's READ path already ships in full and nobody had noticed.** R65 wired it —
      `TvRoutes.kt:302` sets `avatarUrl = RaviloImageUrl.avatar(jellyfinUserId)` on login, `:328` per
      profile in the picker; `RaviloArtworkService.kt:126` proxies
      `GET /Users/{id}/Images/Primary?fillHeight=160`; `AppBar.kt:299` renders it via `RemoteImage`,
      initials as fallback. **A photo set in Jellyfin's own web UI already appears on the TV and phone
      today.** So the storage question is already answered by R65's own choice — Jellyfin's user Primary
      image, no new jellystructure store (FR-187-5) — and this phase is only the two write paths
      (`POST`/`DELETE /api/tv/account/photo`, `POST /api/tv/account/password`), both session-scoped so no
      route shape can name another user's account. Two teeth: **FR-187-1** probes all three Jellyfin
      endpoints against 10.11.11 *before* any code, per phase 163's 405 lesson (the image body shape is
      the real risk — base64-with-header vs multipart changes R234's contract too); **FR-187-7** is
      R214's exact bug pre-empted — `RaviloImageUrl.avatar()` has no version component and the proxy
      caches to disk, so a replaced photo would serve stale bytes. Also: rate-limit via the existing
      `LoginRateLimiter` (FR-187-4 — same credential-proxy exposure Phase 167 made internet-facing),
      server-side re-encode + centre-crop to square (FR-187-6, which is *why* there's no crop UI), and
      admin photo strictly read-only (FR-187-9 — an operator clearing someone's photo is moderation, which
      needs its own thinking about notice and recourse).
    - **R234 — your photo, and your password, without leaving Ravilo**
      (`specs/ravilo/requirements/phase-R234-profile-photo-and-password.md`). Your profile is
      **phone/web only, gated on platform not screen size** (a phone in landscape is still a phone; a
      10-foot UI is still a TV) — FR-R234-2 keeps the TV silent, no row and no "change this on your
      phone" hint, since a TV screen explaining where a setting lives is still a TV screen talking about
      settings. Settings → **Account** is every platform, because a password isn't a preference — which is
      exactly why the photo and the password ended up on different screens. TV reuses R175's keyboard
      verbatim incl. its physical-keyboard courtesy; phone/web use native inputs so password managers
      work. A wrong current password clears only that field and keeps the two new ones. Rejected copy
      recorded: **"Your Jellyfin password"** (live-corrected mid-pass — the viewer doesn't need to know
      what the server is called), "Upload avatar", and the TV hint line.
  - **Blocked / open, and honestly flagged rather than guessed:** (1) do all three Jellyfin endpoints
    exist and behave on **10.11.11** — unprobed; (2) **does changing a password invalidate existing
    tokens** — the single answer that most changes R234's flow, and with Phase 141's per-`(device, user)`
    identity one household change may sign out three TVs; (3) **where would a preset colour live** — the
    mockup's colour row is new stored state with no home in Jellyfin's user record, so it's either
    dropped, given a jellystructure column, or stored as a generated image (probably wrong); (4) whether
    `JellyfinPolicy` carries a "may not change own password" flag worth honouring. Name editing stays out
    of scope — the row is labelled "Photo and name" but only the photo is wired, so relabel or wire both
    before shipping.
- **2026-09-03 sync — both decode-ceiling specs shipped, on-device confirmation resolved the copy
  question, 181/182/183 all built with real measurements, 7 new Ravilo bug/deploy specs landed.**
  Next unassigned numbers: **186 / R230** *(true at the time; both were taken by the dev team — as of
  2026-09-04 the next free numbers are 188 / R235)*.
  - **185 + R222 are ✓ Built (2026-09-02).** One real deviation: the decoder ceiling persists as **two**
    columns (`decode_max_bitrate_hevc`/`_h264`, one shared timestamp) rather than one — found
    live-necessary the same session, since R183/R216 force an AVC transcode target, so a single column
    would silently record the AVC ceiling while an HEVC file's note needs the HEVC one. The client-side
    start timer (`PlayerScreen`/`PlayerStore`, commonMain) also landed 2026-09-02, so `basis: "measured"`
    is reachable in practice, not just implemented-but-dead. **Open question 1 is answered, on-device,
    for good:** R216 has been live on the stue TV since **2026-08-30** — 105 `playback_qoe` rows carry its
    fields, heavy 2026-09-01 sessions show `direct_play=0` (transcode fallback firing) and
    `dropped_frames=0` throughout. The *Till Daybreak* stutter that started this whole thread was a
    **Wholphin** session, architecturally unreachable by any of this. Through Ravilo the file re-encodes
    and starts slowly — it does not stutter — so `slow_lead`/`slow_tail_measured`/`slow_tail_expected` are
    the right copy, translation unblocked. `basis: "measured"` is still unreached on any real device
    today: it needs 3 completed starts of the same file on the same device, and there's been no device
    access since the timer shipped.
  - **181 (library convergence) — ✓ Built 2026-09-02.** FR-181-1/1a/2/4/5 all shipped (id set-difference
    sweep against Jellyfin's full item list, a reverse-diff writing to History instead of auto-deleting,
    activity-based freshness overriding premiere-year bucketing, the dead `JellyfinLibraryListener`
    deleted outright, a narrow persistent dirty-set). FR-181-3 (per-series count reconciliation)
    deliberately **not built** — FR-181-1's sweep already runs at the fastest cadence a count check would,
    making a second mechanism catch a strict subset for no benefit.
  - **182 (scan isolation) — ✓ Built, §A+§B, live-measured 2026-09-02** (dev-restart authorized).
    `/api/tv/series/{id}` held steady under a concurrent scan; **`/api/tv/home` degraded ~120× at p50**
    — root cause is `HomeFeedService`'s own cache invalidating on almost every request because
    `libraryVersion` (correctly, per 182's own fix) bumps on every single item write during a scan. **New
    candidate follow-up, not fixed here:** a home-feed cache keyed on a coarser signal than per-item
    `libraryVersion`.
  - **183 (outbound pacing) — ✓ Built, all FRs, incl. the DB-level rate-limit marking and the Outbound
    pacing card, built 2026-09-02.** Live test against the exact reported series (2777, 505 episodes)
    never hit a single 429. **New candidate follow-up, not fixed here:** `POST /api/media/{id}/sync` on
    that same series reliably self-saturates the 4-permit **interactive** `ProcessGate` reserve (not the
    background one) via a completely unbounded ffprobe fan-out in `syncSeriesEpisodes` — 183 bounded the
    TMDB fetch in that same loop but never the ffprobe call beside it. Household playback was unaffected
    throughout both attempts.
  - **7 new Ravilo specs, none touching a design mockup** (all Compose-only or CI/deploy): **R223**
    (season-picker focus + rapid-Up scroll-stranding, ✓ Built, deployed to both TVs, not re-verified
    on-device), **R224** (merges Ravilo TV + phone into one universal Play Store listing/APK, ✓ Built),
    **R225** (login-screen server indicator + `DEFAULT_SERVER_URL` env var for `ravilo-web` only,
    Planned), **R226** (drops the http/https toggle on server setup, infers `https://` unless typed
    otherwise, Planned), **R227** (system Back now closes the profile menu on mobile — it wasn't reachable
    at all before, ✓ Built), **R228** (a channel that resolves to zero heroes/rows for this specific
    viewer no longer renders as a tile anywhere, ✓ Built), **R229** (Settings screen adopts `LocalCompact`
    padding + a weighted toggle-row pill, fixing single-character-per-line wrapping on phone, ✓ Built).
    Also: **R213**'s Baseline Profile generation completed 2026-09-02 (root cause of the earlier blocker
    was a stale `androidx.benchmark` pin, fixed by a version bump); **R217** stays superseded/closed by
    **R219**; R216's last open question (on-device verification) is answered by 185's finding above.
  - **No design/mockup work is outstanding from this sync** — every new spec is backend/Compose-only.
- **2026-08-31 sync — both our 2026-08-28 specs shipped, and 5 new dev-authored specs landed.**
  Next unassigned numbers: **184 / R221**.
  - **R218 (player loading & buffering) is `Implemented`** — built the day it was spec'd, Android/Compose
    only, **on-device verified 2026-08-29** (stue TV, Offboarding S2E4 resume). **Phase 180 (session
    teardown) is `✓ Done`** — the Jellyfin stop call was confirmed against 10.11.11's OpenAPI before any
    code was written (the open question we flagged), and verified live against a real forced 4K/DV/HDR
    NVENC transcode.
  - **R219 — Continue Watching, one canonical list** (`Implemented`, design-authored with the owner
    2026-08-30, supersedes R217). One merged list per (user, visibility scope); Home rows, channel rows
    and See-all are filtered/capped **views** over it. Every Jellyfin fetch pages to `TotalRecordCount` —
    a `Limit` may never act as a cap (that one error caused four separate bugs); the card and the order
    are decided by **timestamp, never by which endpoint an entry came from**; See-all mirrors its row's
    own configured scope, not the page it was opened from. Row cap 30 → **20**. **No UI change** — nothing
    for us to draw.
  - **R220 — black picture, perfect audio, after Home/TV-standby** (`✓ Built 2026-08-31`, not dev-reviewed,
    not reproduced on-device). Frame-count detector plus a 4-rung recovery ladder (re-attach surface →
    seek flush → recreate `SurfaceView` → re-prepare item). **Touches our R218 design:** none of R218's
    three moments fire here (the player reports playing, not buffering, first frame already rendered), and
    FR-R220-5 says a recovery outliving the 400 ms debounce should show R218's **existing STALL**
    presentation — no new copy, no new visual language. The build note admits that signal is **not wired**
    (its open question 7), so a viewer can still sit on a frozen black frame for ~4.5 s with no chrome
    change. **Drawn here 2026-08-31** as frame **E** on
    `ravilo/Player Loading and Buffering - Directions.html` — R218's stall treatment verbatim, new trigger.
  - **181 — converge on Jellyfin's library, don't predict it** (FR-181-2 built; the rest `Planned`).
    Fjollerne S11E07 sat in Jellyfin for 15 h unnoticed: premiere-year freshness bucketing files a
    currently-airing 2005 show as monthly archive (9 of 16 provably-airing series were starved — now fixed
    via `sonarrNextAiringDate`), nothing ever compares our item set against Jellyfin's, and the
    Jellyfin-based realtime ingest has delivered **nothing, ever** since phase 165 (the WS listener
    subscribes to no message type and `LibraryChanged` is never sent — dead code reporting itself
    healthy). Remaining: an **id set-difference** sweep as the backstop (`DateCreated` is the file's mtime
    and 50% of files carry junk mtimes, so no timestamp watermark is sound), per-series count
    reconciliation, and a persistent dirty-set.
  - **182 — a scan must never stop the server** (`✓ Built 2026-08-31`, not live-verified). The whole
    4-thread scan pool wedged uncancellably for 21 minutes while `/api/tv/**` starved behind one global
    64-permit outbound semaphore — indistinguishable from the server being down. Shipped: locks/atomics
    on the scan write path, per-item deadlines, a cancel that actually cancels, and
    **INTERACTIVE/BACKGROUND partitioning** of `OutboundHttp` (16 reserved) and `ProcessGate` (4 reserved)
    with bounded acquires → 503 + `Retry-After`. **Design item: FR-182-9's Activity-page banner is not
    built** — `GET /api/health` now reports both gates per class, but there is no surface. Needs a
    plain-language banner on `app/activity.html`: "Ravilo requests are queuing behind background work".
  - **183 — pace outbound requests by rate, not concurrency** (`✓ Built 2026-08-31`, not live-measured).
    A 300-episode series fanned out ~1 500–2 000 TMDB requests from a single worker slot, retrying on a
    flat jitter-free 3 s that made every 429'd batch re-converge; an exhausted retry then wrote null
    title/overview and stamped the item as freshly checked. Shipped: a TMDB token bucket with AIMD on 429,
    `Retry-After` + jittered exponential backoff, an episode fan-out gate derived from `scan_workers`, and
    skipping details/credits we already hold. FR-183-6's per-run aggregate is **drawn here 2026-08-31** — an **Outbound pacing** card (rate now,
    the limiter's current ceiling, refusals in the last minute, per host) plus FR-183-5's run-summary line
    "12 fields not fetched: TMDB rate limit", on `app/activity.html`.
  - **This sync's design items, resolved 2026-08-31:** the **Outbound pacing** card and frame **E** are
    drawn; the **Capacity** card stays as read-only reporting; FR-182-9's banner was **dropped** (see
    above). Everything else is backend/platform. Nothing exported to the repo yet.
- **2026-09-02 — research pulled, design pass done, no spec yet.** New repo report
  `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`: *Till Daybreak (2025)*, an
  82 Mbps 4K DV/HDR10+ REMUX, stuttered on stue TV (decoder rated 60 Mbps) and was abandoned — third
  stutter on that TV in three weeks. The owner wants a per-device "this might not play well" warning on the
  Ravilo detail page. The report finds the measuring half already shipped (**177 + R216**) but three
  blockers: the failing session was **Wholphin** (third-party client, architecturally unreachable), nothing
  persists the ceiling at rest, and the ask reverses **R216**'s "no user-visible setting, ever" plus **R180
  FR-RV-ASP1-2**. Design: `ravilo/Decode Ceiling Warning - Directions.html` — **A + B′ recommended**
  (persist the ceiling and show it on `app/ravilo-users.html` as "picture it can take"; one plain line above
  Play in Ravilo). **Reframe that drives it:** with 177/R216 live the file doesn't stutter, it re-encodes and
  takes ~20 s to start, so the copy is an expectation not a warning — *"Slow to start on Stue TV. Give it a
  moment after you press play."* Badge inputs are the recorded ceiling × the file's bitrate on 177's own 0.9
  predicate and nothing else (QoE/link never feed it, so it can't flicker); per **file** so it rides episode
  rows not the series hero, one badge per Phase 149 combined row; Noir drops the amber tint per R221's
  precedent; no action, not focusable, not dismissible. **C (confirm gate) and D (real numbers) drawn and
  rejected.** Blocked on three answers before a spec: where the file bitrate comes from (`Track` has no
  bitrate field), whether other OEM decoders report honest ceilings, and whether the R216 build is actually
  installed on the stue TV. Prospective **185** (admin) / **R222** (Ravilo).
  **Owner decision same day — the note is backend-computed and history-informed, and B′ is now built into
  the mockups.** The client gets one resolved `playbackNote { device, basis, seconds }` per file per device
  on the detail payload and renders a sentence or nothing — no thresholds, bitrates or ceilings cross into
  Ravilo (R180's rule and the constitution's *server-pushed state only*). Split responsibility keeps the
  flicker out: **the ceiling decides whether the note shows** (deterministic, 177's own 0.9 predicate),
  **that device's own history decides how sure** — `basis:"measured"` yields *"Slow to start on Stue TV.
  The last few times it took about 20 seconds."* (median over that device's recent starts of that file,
  5 s steps, re-derived only on session completion), `basis:"expected"` yields *"…Give it a moment after
  you press play."* History may **never** toggle the note on or off, only choose the sentence. Built into
  `ravilo-data.js` (`playbackNoteFor`), `ravilo-app.js` (`playNoteHTML`, above `.dactions`), `ravilo.css`
  (`.dplaynote` + Noir ink/weight override), `ravilo-i18n.js` (3 strings × en/da/fo) and
  `Ravilo Mobile.html`. **Admin half (A) drawn 2026-09-02** — `app/ravilo-users.html` shows the persisted
  ceiling as a **second line inside each device row** ("picture it can take · up to 60 Mbps · measured
  yesterday"), not a sixth column, because that table is shared with the Admin web sessions and Recently
  watched sections; two honest unknown states (`not measured yet` = no Ravilo session since the update,
  `not measured` = browser never reports), and `.usr-cap` styles. Device names now agree across both
  mockups (Bedroom TV · Chromecast HD is the constrained one, Pixel 8 the phone).
  **Owner answers 2026-09-02:** start-timing history does **not** exist and will be built backend-side;
  the file bitrate should come from jellystructure and will be added if `Track` lacks it — so both are in
  scope for **185** rather than blockers. **Both specs written 2026-09-02, `Planned`, neither dev-reviewed
  — next unassigned numbers are now 186 / R223.**
  - **185 — remember what each device can take, and how long it actually took**
    (`specs/requirements/phase-185-device-decode-ceiling-and-start-history.md`). Three small gaps that
    together make the sentence impossible: the R216 ceiling is computed inside one `PlaybackInfo` call and
    thrown away, `Track` has no video bitrate, and nothing times a start. Adds `decode_max_bitrate` /
    `decode_codec` / `decode_measured_at` on `ravilo_device`, `video_bitrate` on `Track` (from Jellyfin's
    `MediaStreams[].BitRate` via Phase 175's unified ingest, backfilled on next scan), an append-only
    `playback_start_sample` written **only on session completion**, and the resolved `playbackNote` on the
    detail payload. **FR-185-6: one predicate, two consumers** — the note fires on exactly 177's
    0.9 × ceiling comparison. **FR-185-7: history chooses the sentence, never toggles the note** —
    `measured` needs **≥3** samples, `seconds` is their median rounded to 5 s, re-derived only on session
    completion so two page views can't disagree. Two permanent unknown states, and `NULL` ceiling ⇒ no
    note ever. Admin half is FR-185-8.
  - **R222 — one plain line: "slow to start on this TV"**
    (`specs/ravilo/requirements/phase-R222-slow-to-start-note.md`). Render-never-compute; absent field ⇒
    nothing renders, no reserved space, no layout shift. Three strings (`slow_lead`,
    `slow_tail_measured`, `slow_tail_expected`) × en/da/fo, with the three rejected copy candidates
    recorded so they aren't re-proposed. Above `.dactions`, never the meta row; Play must not move;
    episode rows not the series hero; not focusable, no action, no dismissal; Noir drops the tint.
  - **Still open (both specs):** whether the R216 build is actually installed on the living-room TV — if
    it isn't, the transcode fallback isn't live there either, the file genuinely stutters, and "slow to
    start" is a false sentence. Also: whether other OEM decoders report honest ceilings, per-codec vs
    per-device keying of the ceiling, and retention N for start samples.
- **2026-09-01 — two design-authored specs written, both `Planned`, neither dev-reviewed.**
  Next unassigned numbers: **185 / R222**.
  - **184 — choose the TMDB metadata language for a single title** (`specs/requirements/phase-184-choose-metadata-language.md`).
    열배's Korean first audio track makes the resolver fetch Korean metadata — correct by the rules,
    wrong for this house. Adds a nullable `metadataLanguage` consulted **above** the resolver (the
    cascade is not modified and its trace stays on screen, dimmed, after a choice), a picker offering
    only what TMDB actually holds for the title with per-language coverage (title · overview · poster
    count), artwork following the same pick via `include_image_language=<chosen>,null`, and a lock with
    the Phase 151/174 preserve shape so no scan can undo it. **Deliberately no new empty-field warnings
    or states** (owner decision — a first draft flagged missing fields and it invented a state the rest
    of the page doesn't have). Per title only: no rules, no per-library default, no global preference,
    no Ravilo work. Design: `app/Metadata Language Override - Directions.html` (Direction A chosen; B and
    C recorded as rejected) — **built into `app/media.html` + `app/detail.css` 2026-09-01**.
  - **R221 — every genre on a media detail** (`specs/ravilo/requirements/phase-R221-all-genres-media-detail.md`).
    The detail hero shows one genre; the library stores a set. Genres get their own labelled row built
    from the AUDIO/SUBTITLES flag-strip pattern, in TMDB's order with the primary one accent-tinted,
    capped at four visible plus a focusable `+N`, **never wrapping on TV**. Each chip opens the R187
    browse page seeded to that genre — the same contract R190 gave cast faces — with Back restoring
    focus to the chip. Row sits above the synopsis so Play doesn't move. Design:
    `ravilo/Media Detail Genres - Directions.html` (Direction B chosen with C's primacy tint; A and C
    rejected) — **built into the Ravilo mockups 2026-09-01** (`ravilo.css`, `ravilo-app.js`,
    `ravilo-browse.js`, `ravilo-i18n.js`, `Ravilo Mobile.html`, plus a canonical per-title genre list in
    `ravilo-data.js`). Noir's amber accent did read as a warning, so its primary chip is marked by weight
    and ink rather than tint. **Biggest open question: whether the Ravilo payload carries the full genre list or
    flattens it — that decides whether this is a UI phase or a backend one.**
- **2026-08-27 sync — 15 new dev-authored specs (admin 169–176, Ravilo R208–R214), all shipped, plus three
  amended specs that change *our* mockups.** Next unassigned numbers: **177 / R215**. Counts now:
  158 admin rows + 164 Ravilo rows = **322 numbered phases**; 46 + 53 phase files + 14 research reports;
  328 Kotlin files; 10 Gradle modules (new: `ravilo-android-benchmark`, `ravilo-web`, `web-static-server`).
  - **Phase 163 (our segment editor) is `✓ Done` — and its §E "publish to Jellyfin" was dropped entirely.**
    The 2026-08-13 dev review probed this house's Jellyfin **10.11.11** live: `GET /MediaSegments/{itemId}`
    is the only MediaSegments operation in its whole OpenAPI document, `POST` returns **405**, and Jellyfin
    takes segments only from server plugins implementing `IMediaSegmentProvider`. Jellyfin *read-in* as a
    candidate source stays (returns empty until such a plugin exists; none is installed).
    **`app/segments.js` updated 2026-08-27** — the Publish button, the "in Jellyfin" column and the
    `pub` state are gone; the sheet's last column is now **Confirmed** (checked-by-me · locked-by-me ·
    a guess), the header chip counts what still waits on you, and the primary action is
    "Take me to what needs me". The deck's segments slide tells the same story. Build notes: `media_segment`
    table replaced the flat blob, `manuallyConfirmed` back-filled onto **`locked`** (not `checked_at`),
    multi-episode files (`partCount > 1`) are permanently unsupported and read "not supported yet".
  - **Admin 169–176, all `✓ Done`:** **169** parallelises `syncSeriesEpisodes`'s per-episode probes to match
    `scanSeries`. **170** gives `detect_segments` its own process-slot pool (`SegmentProcessGate`), makes
    `force=true` respect marker **source precedence**, and logs segment writes to a title's own History.
    **171** fixes music-video artwork paths (`<basename>-poster.jpg` per file, since an artist folder holds
    several) and lets a music video match TMDB like anything else. **172 — filter support everywhere:**
    `"musicvideos"` is now a third value at every binary Movie/Series type-filter site — **updated here in
    `app/library.html`'s top-right picker and `app/ravilo-builders.js`'s Include segment**. **173** — the
    Artwork tab now shows the **asset actually on disk**, not only TMDB candidates (a music video routinely
    has zero); **drawn in `app/media.html` as a "Currently in use" block**. **174 — Clear a wrong TMDB
    match:** clears id + every TMDB-written field + the poster/backdrop **files**, then **locks** the item so
    the next `pull_tmdb` can't re-apply it; **drawn in `app/media.html`** (pagebar `Clear TMDB match`,
    per-asset `Clear`, and the Re-pull ▾ → From TMDB item greying out when locked). **175** unifies plain
    scan / pipeline / webhook ingest onto one engine and makes the freshness cooldown apply to the manual
    Scan button for the first time (567s → 11–60s for the equivalent window); `SCAN_ON_START` now runs the
    full configured pipeline. **176** guarantees an on-disk poster can never disagree with the current match.
  - **Ravilo R208–R214:** **R208** is our episode-rail 30s auto-hide, **renumbered from R196** repo-side
    (the dev tracker had already spent R196) — the stale local `phase-R196-episode-rail-autohide.md` is
    deleted. **R209** external text subs were dropped and PGS double-delivered (161's follow-on).
    **R210–R213 are the 2026-08-26/27 startup-performance cluster:** CIO kept for the WebSocket only with
    `ktor-client-android` for REST (routes around a live "loads forever" bug), session-store caching +
    FontFamily churn, a bounded-size **client-side Home-feed + display-settings cache** (no more shimmer
    or wrong-skin flash on cold start), and a generated Baseline Profile (**R213 is the only `Planned` row**
    — infra built, generation blocked). **R214** — Ravilo's image cache outliving a corrected poster.
  - **No design work is outstanding from this sync** beyond the four mockup updates above; everything else
    is backend/platform.
- (shipped — see the 2026-08-31 entry above) **2026-08-28 — two design-authored specs written for the player wait states (both `Planned`, not yet
  dev-reviewed).** Triggered by `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md`,
  which catalogued **four waiting moments** and found only one of them drawn.
  - **`phase-R218-player-loading-buffering-states.md` (client)** — moment A (session negotiation) unchanged;
    **B (cold start)** gets **Direction B "Grounded"**: black, three-dot brand pulse, title context
    (series · episode title · S/E), indeterminate sweep, the existing `"Loading…"` string; **C (stall)**
    keeps the frozen frame and **raises the transport chrome by itself**, spinner standing in the play
    button's place, so the viewer keeps their position — no centre overlay, no text; **D (seek)** is
    lightest: a spinner inside the scrub tile only. **~400 ms debounce on all three** (a direct play must
    never flash), deepen at **60 s** without changing a word, **one already-translated string**, and
    **no escape hatch** — Back is always available, never prompted, and ends the session.
    Binding constraint: **R180 FR-RV-ASP1-2**, nothing may vary by delivery method.
  - **`phase-180-playback-session-teardown.md` (backend)** — leaving must actually stop the work.
    `stopPlayback` (`tv/PlaybackService.kt:357`) reports the stop but nothing releases an in-flight
    transcode, so Back during a 20 s spin-up leaves NVENC encoding for nobody — invisible background I/O
    that Phase 178's `anyActive()` can't even see. Teardown keyed on the existing per-item
    `playSessionId` (`:218`), converging the client stop, the Phase 110 watchdog and session-supersede
    on one routine, plus a **queued stop** for the abandon-during-negotiation race. **Open first:** confirm
    an active-encoding stop actually exists in Jellyfin **10.11.11**'s OpenAPI before building — phase 163's
    405 is the standing reminder.
  - Design file: `ravilo/Player Loading and Buffering - Directions.html` (canvas; A/B/C cold-start
    directions, chosen stall + seek frames, two phone frames, and a panel answering the report's seven
    open questions). **Direction C "Title card" rejected**, **A "Quiet"** kept as the fallback if copy
    ever fails locale review. Next unassigned numbers now **181 / R219**. (Our teardown spec was **renumbered 179 → 180** on 2026-08-28: the dev team took **179** the same evening for `phase-179-subtitle-sideload-transcode-stall.md` — R183/161's deferred half, reactivated because phase 177 made the highest-bitrate tier transcode far more often; ✓ Implemented. Same collision shape as R196 → R208.)
- **2026-08-20 sync — large pull, 26 new spec files + a new `presentation/` directory we're taking
  over.** Next unassigned numbers: **169 / R208**.
  - **New admin specs (164–168, all `Planned`, dev-authored):** **164** takes `detect_segments` off
    the pipeline's critical path into a de-duplicated two-lane job queue (extends the Phase 109 media
    job queue rather than a second system). **165** replaces the *arr webhook ingest with Jellyfin's
    own Webhook plugin (id-based, fires only once Jellyfin has matched the item — no more 5-min path
    poll); a live delivery failure found mid-implementation added a real end-to-end delivery probe
    (FR-165-7/8) instead of a self-loop test. **166** makes the pipeline schedule any valid cron
    (Daily/Weekly/Every N hours/Custom), shared-parser validated on both save and while typing, with a
    15-min safety floor. **167** ships Docker packaging for internet exposure: `.dockerignore`,
    `fpcalc` in the runtime image, self-hosted fonts (fixes a real CSP break), a new plain-Kotlin
    `ravilo-web` static-server image, and versioned (`MAJOR.MINOR`, starting `1.0`) GHCR publishing
    gated on a GitHub Release — shipped 2026-08-17 with three rounds of real CI/runtime bugs found and
    fixed (a skiko-wasm race, dead Docker layer caching, CI runner OOM/disk). **168** adds filename-only
    Music Video library support (new `MediaKind.MUSIC_VIDEO`, no TMDB ever, no new UI surface — folded
    into the existing "all kinds" views since Movies/Series tabs are slated for removal later anyway).
  - **New Ravilo specs (R196–R207)** — mostly bug fixes from a live screenshot-capture session on
    2026-08-14/18: **R196** is a numbering collision (episode-rail 30s auto-hide, *and* a dev-found
    regression where R195 broke the entire remembered audio/subtitle tier via a stale Compose closure
    — both kept as R196, disambiguated by filename). **R197** reverses an R195 decision: picking a
    version in the picker's level 2 now closes the picker (was deliberately stay-open; felt broken).
    **R198** re-sorts Continue Watching by actual `LastPlayedDate` instead of trusting Jellyfin's own
    sort. **R199** falls back to jellystructure's own scanned episode number when Jellyfin's filename
    parse fails (found immediately after deploying R198). **R200/R201** fix the same underlying focus-
    bridge failure shape (a `FocusRequester` never attached because its target composable was off-
    screen/torn down) — R200 in content-row back-return restore (could permanently strand Down-nav,
    only an app restart recovered), R201 in the season picker (Fjollerne's 11-season, fully-watched case
    auto-selected the last season, which the picker never scrolled itself to reach). **R202** is the
    project's own case-study bug: a misleading code comment attributed an inherit-mode channel's missing
    Continue Watching row to R59; the user pushed back, `git log -S` traced it to an R05 leftover R143
    mislabeled — now fixed, and written up in `presentation-context.md` as the talk's centerpiece.
    **R203–R207** are small triage fixes (Faroese label typo, My List's missing heading, Loading-text
    not centered, a blank/mic-icon picker row for an unmapped language, and generation-guard + Retry
    hardening on the detail-screen Loading state) — two other triaged items turned out NOT to be bugs
    on closer inspection (TV Guide row-height/blank-row claim, Back-inside-a-channel behavior) and are
    recorded as such rather than silently dropped.
  - **New `presentation/` directory — we will be taking this over.** Mirrored in full:
    `presentation-context.md` (raw material for a talk on the project + its spec-driven method — the
    R202 story is its centerpiece) and `observed-issues-2026-08-18.md` (the triage log behind
    R203–R207), plus `presentation/screenshots/` (57 real 1920×1080 PNGs captured over adb from the
    live Android TV — home, channels, browse/filter, detail, the audio/subtitle picker, Discover, Live
    TV, settings). Not yet built into anything here — next step when we pick this up is turning
    `presentation-context.md`'s suggested structure into an actual HTML deck.
  - **New research reports pulled:** `internet-exposure-docker-packaging-2026-08-17.md` (phase-167's
    investigation), `music-video-support-investigation-2026-08-17.md` (phase-168's).
  - **STATUS.md mirror refreshed** (551 lines now; read-only here per CLAUDE.md).
- **All admin phases through 151 and Ravilo through R183 are ✓ Done** in code (per repo
  `STATUS.md`, ref `main`, synced 2026-07-31). Everything we designed has now shipped:
  **149/R179** (multi-episode files/combined card), **R180/R181** (flag-forward Audio &
  Subtitles picker + default/remembered tracks), and **150/R182** (intro & credits segment
  detection + Skip Intro / Skip Credits, implemented 2026-07-13 incl. cross-episode Chromaprint
  fingerprinting once `fpcalc` landed on the backend).
- **New since our last sync — 8 dev-authored specs pulled (not design work):**
  - **151** — manually-selected images never auto-overwritten (closes the Phase-133 hole on the
    Sync / Re-pull `updateOne` path; extends the lock to clearlogo, season posters, episode stills). ✓ Done.
  - **152** — scanner falls back to filename `(season, episode)` when Jellyfin has no `IndexNumber`. Implemented.
  - **153** — scheduled scan actively repairs an unmatched episode's numbering (writes corrective
    episode NFO + triggers Jellyfin refresh). Implemented.
  - **154** — pre-run dialog to untick slow pipeline steps (e.g. `detect_segments`) **for one run only**,
    nothing written to config. Implemented.
  - **R183** — Dolby Vision playback + decodable transcode fallback (extends R56/R173; DV was R173's
    non-goal and every DV title was force-transcoded to an undecodable Baseline/L4.1 4K stream). ✓ Done.
  - **R184** — auto-advance no longer starts the next episode minutes in (stale position leak). Implemented.
  - **R185** — Continue Watching hides fully-watched titles (Jellyfin `Played`/`PlaybackPositionTicks`
    desync, 62/115 rows corrupted live). Implemented.
  - **R186** — Continue Watching fetch window widened past the global top-20 so channel rows (e.g. DanskTV)
    aren't starved. Implemented.
- Recent landings: Live TV (147 + R177), Seerr pivot (136/137 + R170/R171), request-language
  steering (139 + R172), Workbench query blocks (140), HDR tone-map fix (R173), grid-columns
  config (R174), cover-as-video (144), event-driven pipeline (145).
- **⚠ Repo-side STATUS gap (dev team's to fix, not us):** `STATUS.md` on `main` has no rows for
  admin **152/153/154** or Ravilo **R184/R185/R186** though their spec files exist and read
  *Implemented* — `scripts/check-phases.sh` will flag them. `STATUS.md` is a read-only mirror here.
- (superseded — see the 2026-08-27 entry above) Next unassigned numbers were 164 / R196; `phase-163-segment-editor.md`
  was then design-authored and `Planned`. (2026-08-13 sync: **Phase 162 / Towo shipped** — dev-reviewed and
  built in full 2026-08-11 with three dev-review addenda, verified live against a real Claude account. Only
  spec change repo-side this sync; nothing else new. The build is backend-owned settings, not localStorage,
  and added fields our mockups don't have yet — see the Towo entry below.) (2026-08-10 sync #2: **R195 shipped** — dev-reviewed and built
  the same day; and new **161** — stop double-delivering embedded text subtitles on direct play, ✓ Done —
  which was found *because* R195's two-level picker made the duplication visible as two identical
  "English" rows. Earlier that day: dev took 158/159/160 and R192/R193/R194 — the
  MediaSession trio: R192 release-on-background, R193 rich metadata TV-only, R194 season-poster artwork,
  all Implemented; 158 IMDb ratings dataset, 159 segment-detection accuracy, 160 scanner Jellyfin
  numbering fallback, all Implemented. Our same-language subtitle-picker spec was renumbered
  **R192 → R195**.)
- (previous sync) 155 (age-rating normalization) and R187 (browse page)
  **shipped in code** (Implemented, pulled from repo `main` 2026-08-02). New dev specs pulled this sync:
  156 (Seerr per-user request attribution, Implemented), R188 (Upcoming-calendar per-device
  visibility, Implemented), R189 (Samsung Tizen TV client, M1+M2 build-verified). R190 =
  filter-by-person + Seerr overflow row + admin workbench Cast-or-crew facet (design-authored, was
  drafted as R188 but the dev team took R188/R189 — renumbered to R190). **Now `Implemented`
  (2026-08-02): the dev team adopted our design, dev-reviewed it, and built §A–§D + i18n end to end
  across backend, admin WASM workbench, ravilo-ui/Compose and ravilo-tizen — compile-clean, not yet
  live-tested; one deliberate Tizen omission (§C Seerr overflow row, since Discover is out of the
  Tizen build). Pulled the canonical Implemented spec over our stale `Planned` draft 2026-08-07.**
- **Same-language subtitle picker — `phase-R195-same-language-subtitle-picker.md` (✓ Done / Implemented
  2026-08-10; design-authored 2026-08-09, dev-reviewed and built the same day, not yet on-device tested).**
  Shipped as designed, with three notes: no `flag_br`/`flag_tw` assets yet (text-only fallback, never a
  wrong flag); **audio got the same two-level treatment** (was a §2 non-goal); a null-language cluster
  needed `groupDisplayName()` to fall back to the dominant kind word; and every picker row gained real
  tap/touch handling for the first time (shipped R180 was D-pad-only). Bazarr `hi`-flag plumbing deferred.
  Follow-on: **161** (below). Original brief: R180 assumed one row per language = one choice; the live library says
  otherwise (46.5% of movies / 47.5% of series have a file with 2+ same-language subtitle tracks;
  251 file-instances are identical on every stored field; worst case 32 unnamed tracks in one file).
  Picker becomes **two levels**: level 1 is one row per language, flag beside the native name, with
  a count + **›** arrow only when the language holds several versions — **no arrow means OK selects
  it outright** (the absence of the arrow is the whole cue; no extra badge). Level 2 lists the
  versions with the **flag shown once in the header bar**, never repeated per row, each version
  carrying a one-sentence plain-language line instead of a raw track title; a version shows its own
  flag **only** when its region differs from the header (🇧🇷 on 🇵🇹). Indistinguishable tracks are
  numbered under an **Unnamed** group — copy never says "disc". Backend prerequisites: detect SDH
  (title text + Bazarr `hi`), a region **synonym table** (never substring matching), suppress
  release-provenance duplicates, and **remember the variant, not the language** (fixes R181's
  always-picks-SDH-then-loses-it bug). Exploration: `ravilo/Audio & Subtitles Picker - Same-Language
  Directions.html` (Direction A chosen; B and C recorded as rejected). Not yet dev-reviewed — open
  question is whether R180's "nothing hidden or merged" invariant yields for provenance merging.
- **Bazarr subtitle integration — design-authored `phase-157-bazarr-subtitles.md` (`Planned`, 2026-08-07).**
  Optional Bazarr connection so subtitles never need Bazarr's own UI. Principle: **JS stores nothing**
  — reads Bazarr live and issues commands; Bazarr keeps owning providers, scoring and language
  profiles (mirrored read-only). Surfaces: Settings → Download tools **Bazarr** card (`[bazarr]` TOML,
  path-matched like *arr); a new sidebar **Subtitles** page (`app/subtitles.html` — live queue, wanted
  list, history, providers, read-only profiles) + a dashboard summary card; per-title Bazarr sections
  on the movie **Tracks & subtitles** tab (renamed from "Tracks & order") and the series **Seasons &
  episodes** tab. Actions driven through Bazarr: manual search/download, auto-search, sync-to-audio,
  upgrade, delete, full scan. Not yet dev-reviewed — open question is the exact Bazarr command API +
  how a JS item resolves to a Bazarr radarrId/sonarrId (confirm live before build).
- **2026-07-31 sync:** re-pulled the entire `specs/` tree (68 files) + `STATUS.md` from repo `main`;
  repo was well ahead. Wrote `github.md` as the sync receipt. Design now matches shipped code across
  admin 0–154 / Ravilo R01–R186. See `github.md` for the screen map and details.

## Design constraints to respect
- `design/app/` + `design/ravilo/` mockups are the **visual target** for the
  Kotlin/WASM admin frontend and the **Compose Multiplatform** Ravilo app
  (Android TV · phone · web). Real frontend styling is **Tailwind** (admin, scanned
  from Kotlin) / Compose tokens (Ravilo); our CSS-variable system is the *visual* spec.
- Admin frontend is **DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.
- **Frontend renders server-pushed state only** — no derived/optimistic state.

## Admin visual system
- Dark-primary with a three-way **Light / Dark / System** picker (default System).
  Single **Aurora** direction (cinematic, glassy, gradient); theme persisted in
  localStorage `js-theme` by `app/app-shell.js`. Purple→blue accent. Phase 58 retuned
  the dark surfaces ("Soft Charcoal").
- Type: **Space Grotesk** (display) · **Sora** (UI) · **JetBrains Mono** (code/IDs).
- Tokens: `--ok` resolved/success · `--warn` dirty/mixed · `--bad` error.
- Shared `app/wf.css` (tokens + components) + `app/app.css` (shell) + per-page
  `app/detail.css` (media/series) and `app/metadata.css`. `app/app-shell.js`
  injects the sidebar, mobile drawer, ambient scan dock, floating **Triage dock**,
  and ⌘K command palette.

## Admin screen set (current)
- **Towo — removed from this project 2026-09-16, and spec'd for removal from the product as 217.**
  Every `towo*.html` mockup, `app/towo.css`, the
  Settings → Towo tab, the `js-towo` feature flag, the sidebar Towo group and its nav icons, and the
  `claude-console/` directions are **deleted**. The Claude Code control plane is out of the design set;
  nothing in `app/` references it. `specs/requirements/phase-162-towo-agent-control-plane.md` and the
  `STATUS.md` row for 162 are **repo-owned read-only mirrors and were deliberately left alone** — retiring
  the phase itself is the dev team’s call, and our export must never re-add the deleted mockups.
  **`specs/requirements/phase-217-remove-towo.md` (written 2026-09-16, `Planned`, not dev-reviewed)** is
  the product-side removal: UI deleted rather than gated (and a stale `js-towo` key cannot resurrect it),
  routes deleted rather than stubbed, the auto-continue scheduler unregistered **first**, transcripts
  exported once then the tables dropped in one forward migration, and every runner **uninstalled rather
  than orphaned** in a reconnect loop. 162's spec and `STATUS.md` row are deliberately **kept** — marked
  `Removed`, pointing at 217 — because the record of a shipped phase is worth more than a tidy directory.
  Next unassigned numbers: **218 / R244**.
- **Intro & credits editor** (`segments.html` + `segments.js` + `segments.css`, Phase 163 design) — one
  fullscreen tool, no sidebar, opened from a season / episode row / **movie detail** and returning there.
  Reached via a dedicated **Intro & credits** tab — its own tab, not a card in Overview or a link in a
  toolbar — on both `media.html` (→ `segments.html?movie=<slug>`, straight into the trim view with a
  films-to-check rail) and every series page (→ the season sheet).
  **Season sheet** (front door): every episode on one aligned timeline so an outlier sticks out, season
  stats incl. **what the season agrees on** (median intro), multi-select bulk apply/lock/re-detect,
  drawer with player per row. **Trim view** (behind it): Jellyfin playback in the admin (direct play or
  nothing — never transcode for this), timeline with draggable handles, **waveform + evidence lane**
  (black frames, silences, the fingerprint's matched span, chapter marks, Jellyfin's own segments),
  per-marker rows with steppers/loop-the-cut/**per-marker lock**, review-queue rail, keyboard
  (`I`/`O`/`,`/`.`/`L`/`↵`). Five kinds: recap · intro · next time · credits · after-credits.
  Jellyfin is a **source only** — its own segments read in as one candidate; **publishing was dropped**
  in the 2026-08-13 dev review (Jellyfin 10.11.11: `POST /MediaSegments` → 405, plugins only). What a human
  confirms (checked or locked) is still the thing that matters — it just never leaves jellystructure.
  Locks are per marker and `detect_segments` never overwrites them (phase-151's guarantee, extended).
  Exploration: `Segment Editor - Directions.html` (A+C shipped as one tool; **Direction B rejected** —
  its CSS is kept in `segments.css` under an exploration-only comment so the directions file still renders).
- **Subtitles** (`subtitles.html`, Phase 157 design) — global **Bazarr** overview (**no left-nav item**;
  reached from the dashboard summary card): live queue/tasks, wanted list (filter by kind + language),
  download/sync/
  upgrade/remove history, provider health, read-only language profiles. Companion: a dashboard summary
  card + per-title Bazarr sections on `media.html` (Tracks & subtitles tab) and `series.html` (Seasons
  & episodes). Bazarr connection lives in Settings → Download tools. JS persists no subtitle state.
- **Dashboard** (`index.html`) · **Library** (`library.html` — audio-track filter,
  multi-axis filters, multi-language search, infinite scroll, shared filter
  workbench) · **Activity** (`activity.html`).
- **Metadata** (`metadata.html`) — Studios · Networks · Genres · Tags (JS-tag color
  **swatches** + dotted chips, Phase 82) · **Age ratings** (design-complete 2026-07-31, no spec
  yet): maps every raw certification (G, TV-MA, “Från 15 år”, Btl…) to a normalized age 0–18 —
  ladder summary, per-cert stepper (write-through pulse), unmapped-cert triage (NR/Btl → treated
  as 18 in filters/kids gating — never shown as an 18+ badge — and listed when no range is set).
  Feeds Ravilo’s Maturity filter, which shows only numbers
  (`0+ · 7+ · 13+…`, `normAge()` in `ravilo-browse.js`). **Spec: `phase-155-age-rating-normalization.md`
  (shipped/Implemented in code, synced 2026-08-02).**
- **Settings** (`settings.html`) — URL-addressable **tabs** (Phase 55): Connections ·
  Libraries · Metadata · **Download tools** (Radarr/Sonarr + Seerr + **Bazarr** subtitles + cross-seed) ·
  Notifications · Advanced · **Users & devices** (Phase 143 design: per-user Ravilo
  devices + admin web sessions, revoke / sign-out-everywhere).
- **Live TV** (`livetv.html`, Phase 147) — surfaces Jellyfin's Live TV into Ravilo: master
  enable toggle, connection/status badge + Test connection + Refresh from Jellyfin, a channel
  lineup (show/hide · number · logo · order · category, keyed by Jellyfin **channel id**),
  lineup-change diffing (new→hidden · removed→unavailable), and EPG source + refresh cadence.
  **Read-only** against Jellyfin (never touches the tuner). Lives in the sidebar's **Ravilo**
  group (Phase 148 nav restructure, which renamed "Apps" → **Ravilo** and split the config editor).
- **Movie detail** (`media.html`) / **Series detail** (`series.html`) — the single
  editing surface. **Write-through** editing (Phases 71/74): edits commit to disk
  immediately; the split button is **Save → NFO / Sync Jellyfin / Save & Sync** (the
  old staged "Save changes" + amber dirty borders are gone). Tabs: Tracks & order /
  Seasons & episodes · **Artwork** (manager + lightbox, Phases 47/48/71/81) ·
  **Cast & crew** (Phases 75/76/79/80; `Season Episode Cast.html`, `series-cast.js`) ·
  NFO raw viewer (Phase 44) · History. Pagebar shows **audio-language flags** (Phase
  87, via `flags/` + `flags.css`), TMDB-id edit, external links, drift banner,
  Jellyfin field-lock banner, **★ Feature in Ravilo**. **Login** (`login.html`).
  **Multi-episode files** (Phase 149, design-complete / awaiting code): Seasons & episodes
  renders **one combined row per file** (`S01E01–E03` · combined runtime · "3 in 1 file" badge ·
  triptych thumbnail, expandable to per-episode chapter offsets), and the Artwork tab picks
  stills **per episode** with a file switcher — target `series-johnnybravo.html`.

## Ravilo companion app (`ravilo/`)
- **Ravilo TV** (`ravilo/Ravilo TV.html` → `ravilo-app.js` + `ravilo.css`) — the TV UI:
  Home (hero carousel, channel rail, content rows), Movies/Series/My List grids,
  Search, Movie/Series **detail** (merged audio+subtitle flag line R75/R78/R134, cast &
  crew R81), **Discover** (profile-hub nav R170; Coming Soon + Seerr **Request** tab
  R171), Player. The in-player **Audio &amp; Subtitles** picker is **flag-forward** (Direction 2):
  a flat list where a country flag + plain native language name anchor every track, with
  jargon-free badges only — **Default · Surround 5.1 / Stereo · Signs only** (forced) ·
  **Sound described** (SDH) · **Describes action** (audio-description) · **Commentary** — no
  codec names and no delivery-method cues (`ravilo-player.js` `renderPicker`/`PL_KIND`/`plFlag`/
  `plBadges` + `ravilo-player.css`; track data in `ravilo-app.js` `tracksFor()`; exploration in
  `Audio &amp; Subtitles Picker.html`). **R195 (Planned) makes it two-level** — languages first,
  versions inside — see the phase list above. **Browse page** (**shipped/Implemented in code**, spec
  `specs/ravilo/requirements/phase-R187-browse-page.md`; admin half `phase-155`, also shipped):
  Movies/Series nav + an end-of-row **→ See all** tile (rows with &gt;8
  items, incl. Continue Watching + channel-scoped rows) open a shared browse page
  (`ravilo-browse.js`, Direction A: top facet bar → checklist popover). A **cast/crew face** on a media detail is now a link into this page seeded to that person's
  filmography (R190, `phase-R190-people-filter.md`, Planned): the browse facets narrow within it, and
  when Seerr is enabled a single **⚡ Seerr overflow row** ("More with {name} · request on Seerr")
  sits below all library results, opening the normal Seerr request flow (Back returns to the person
  page). The same person filter is a **Cast or crew** facet (People group) in the admin
  workbench (`ravilo-builders.js`, R190 §D). Facets Genre · Type ·
  Maturity · Year · Watched · Audio · Channel · Quality — multi-select OR within a facet, AND
  across facets, stacking on the row seed (breadcrumb + "Úr …" subtitle, no seed chip); popover
  values sort by count desc then A–Z. **Maturity is a D-pad range picker**, not a checklist:
  From / Up-to rows over the normalized age ladder (◂ ▸ adjusts, OK confirms, ladder viz
  highlights the span) expressing ≤7, 7–12, 15+, 4–14 — any integer bounds 0–18; chip shows the range
  label ("≤ 7", "7–12", "15+"). No/unmapped rating ⇒ treated as 18 (never badged). Sort defaults to Recently added (A–Z · Z–A · Year ·
  Maturity · IMDb). Popover owns the D-pad via capture keys (langPicker pattern); exploration in
  `Ravilo Browse - Filter UI Directions.html`. **Live TV** (`Ravilo Live TV.html` → `livetv-app.jsx` · `livetv.css` ·
  `livetv-data.js`, R177) is woven into Home — an **“On now”** row → full EPG guide, channel
  zapping + number entry, Now/Next overlay, and a live player; **never a top-nav tab**
  (placement configured in `ravilo-config.html`, Phase 147 §F). Three skins
  (Aurora/Midnight/Noir). `ravilo-i18n.js`
  (en/da/fo). `Ravilo Mobile.html` + `mobile/` = the Android **phone** target (R60).
  Series detail groups **multi-episode files** into one combined **triptych** card (R179,
  Option B, design-complete) on both TV and phone — the phone detail gains an Episodes section.
- **Ravilo config editor** (`app/ravilo-config.html`) — the Jellystructure-side editor
  for a viewer's TV layout. Has: **Global vs per-user scope switcher** (R51), Home
  hero carousel (single global height 40–100% + auto-advance, R58), Channels &
  collections, Content rows (system rows = Continue + Newly Added, R54/R61),
  **Top 10 / Discover** lists (R50), Behaviour (skin, tile shape, ui-lang),
  **Pair-a-TV** modal + **sticky** action navbar (R57), live preview iframe. TV sign-in
  is now the R175 **username/password login** (pairing code removed from the TV mockup).
- **Shared filter workbench** powers filters everywhere: `app/ravilo-builders.js`
  (+ `ravilo-builders.css`) exposes `window.RaviloBuilders` and is loaded by **both**
  `ravilo-config.html` and `library.html` (R32). Facets = **Studio · Network · Genre ·
  Tag · Age rating · Audio track · Cast or crew** (People group, R190 §D) plus the contextual
  Ravilo-layout facets (Hero, Content row). Library ↔ Ravilo
  round-trip: `⚙ Add filter` opens the builder; `Save filter as… → Channel / Content
  row`; per-poster `★ Save as hero item`.
- **Brand (R62):** the Ravilo **brand** mark/asset pack recolors to a
  **Jellyfin-inspired palette** — gradient `#AA5CC3 → #00A4DC` on `#000B25` navy,
  "lit-mark" treatment (`ravilo/assets/brand/*.svg` masters → `assets/android/**` +
  `store/**`; `Ravilo - Android TV Assets.html`). The **in-app Aurora UI accent**
  (focus rings/buttons in `ravilo.css`, still purple `#7b6ef0`) is a **separate token
  set and is intentionally unchanged** by R62.

## Config shape
`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
Optional `[qbittorrent]` (cross-seed guard), `[radarr]`/`[sonarr]` (read-only
root-folder import + best-effort rescan), and per-user Ravilo layout/discover blocks.
