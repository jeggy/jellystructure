repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo); presentation/ (full mirror, ours to build on); design/ (our export — now confirmed to flow BOTH ways, see 2026-09-15)
tree: main @ `ad3244eaf2af` (2026-09-18 pull, 15:37 — head of `main`; resolved tree hash, not a commit)

## Last sync (2026-09-18, 16:40 — no repo I/O: the picks built into the phone, a new receiver-app mockup, R269 + R270)
date: 2026-09-18T16:40:00Z
direction: none — design work only, on top of the 15:40 pull. Nothing fetched, nothing exported.
- **Owner picks recorded on the canvas and built:** AirPlay = the **footnote** row (against the drawn lean,
  so the full notice moves to the connecting bar + the admin help text — R270 FR-R270-2); a busy TV **names
  its viewer**, and offline keeps its weekday; the receiver's server changes by a **three-second hold on
  *Back***; an unreachable server makes the code screen **wait**, never show a stale code.
- **`ravilo/Ravilo Mobile.html` + `mobile/ravilo-mobile-player.css`:** the three-tier *Play on a TV* sheet
  (`sc-*`), its four row states, the collapsible tier 2, the Android-only Chromecast row, the iPhone-only
  AirPlay footnote, *Add a TV* with a native code input, AirPlay-active (the player is the remote — no mini
  bar), and the *Sending to …* → *Playing on …* bar. Replaces the platform-dialog stand-in.
- **New: `ravilo/Ravilo Receiver App.html`** — 18 states × 3 skins × 3 languages for the receiver-only TV
  app (R264 + R269), including the five server-setup states. Kept separate from `Ravilo Receiver.html`.
- **Specs:** **R269** updated with both decisions and repointed at the new mockup; **R270** written
  (*AirPlay is a footnote, and a busy TV names its viewer*) — supersedes R265 FR-R265-4's row shape.
  **Next free: 238 / R271.** Both `Planned`, **pending export**.

## Previous sync (2026-09-18, 16:05 — no repo I/O: the owner's two notes on the receiver — R269 written, the dimmed idle scratched)
date: 2026-09-18T16:05:00Z
direction: none — design work only, on top of the 15:40 pull. Nothing fetched, nothing exported.
- **R269 written** (`specs/ravilo/requirements/phase-R269-receiver-needs-a-server-first.md`, `Planned`): the
  receiver cannot mint or show a pairing code before it knows a server, and `main`'s R264 never says where
  that address comes from — `ravilo-cast` is served by the backend so its origin *is* the server, while a
  sideloaded `.wgt` has none. One setup screen (R225/R226 + R175's keyboard, re-drawn in DOM), the ordering
  rule **server → code → pair**, the inferred scheme, the two distinct no-server states, the address named on
  idle, a hold-*Back* way back, no discovery. Supersedes FR-R264-2 and FR-R264-6 in part. **Next free: 238 / R270.**
- **§C0 drawn into `ravilo/Play on a TV - Directions.html`** — four TV frames, the fencing panel, the
  rejected-discovery note and two questions with leans; §C1/C1b gained the server line under the TV's name.
- **Dimmed idle scratched** (owner): R264 OQ 2 is answered *no*. The frame is replaced by **C1c · idle naming
  its server** in Noir.
- Also fixed on the way: three class collisions with `mobile-directions.css` in the new canvas (`.dim` →
  `.pt-off`, stacked row text, `.sbar` reset to static — the last one applied to `Bottom Nav - Directions.html`
  too), and two text overlaps (the AirPlay caption over the chrome; `.tvov .tm` under the transport badge).
  ⚠ The same `.pz`/`.tm` collision exists in the built `ravilo/Ravilo Receiver.html` — one line, not yet fixed.

## Previous sync (2026-09-18, 15:40 — pull: the screens + web-install brief and its five specs; ALL FOUR of our drafts renumbered; round 1 drawn)
date: 2026-09-18T15:40:00Z
direction: pull (repo → this project) — 8 files mirrored, nothing exported. A filtered tree scan at `main`
(`phase-(2[3-9][0-9]|R2[5-9][0-9])`, briefs, 2026-09 research reports) found **five new specs, a new design
brief and a 54 KB research report**, all dated 2026-09-18. Admin now tops at **236**, Ravilo at **R265**.
- **⚠ A four-way numbering collision — the worst so far. Every unpushed draft we held was overtaken the
  same afternoon.** `main` took **235** (serve the web app like an app), **236** (screens: the backend drives
  a TV for a phone), **R263** (Ravilo web installs), **R264** (receiver-only TV app) and **R265** (play on a TV
  from the phone). Ours moved, files renamed and every in-file FR reference rewritten:
  **235 → 237** (Chromecast publish + Cast Connect + listing) · **R263 → R266** (Cast Connect, TV app as
  receiver) · **R264 → R267** (the phone's top row + bottom bar) · **R265 → R268** (Discover's tab order and
  scrolling). All four still `Planned`, not dev-reviewed, **pending export**. Next free: **238 / R269**.
  *(Earlier entries in this file say R264/R265 meaning our drafts — they are R267/R268 from here on.)*
- **The mockups' own comments were renumbered with them:** `ravilo/Ravilo Mobile.html`,
  `ravilo/Bottom Nav - Directions.html`, `ravilo/ravilo-app.js`, `ravilo/ravilo.css`. `app/` had no mentions.
- **New brief, and it is a design round:** `specs/ravilo/design-brief-screens-and-web-install-2026-09-18.md`.
  The owner's picture: *an iPhone installs the PWA and streams to the Samsung TV — our most important use
  case*. The TV runs a **receiver-only** app (no navigation); the phone lists its TVs (**on this network**
  first, then a collapsible **all your TVs**) and drives the TV **through the backend**, so the phone can be
  closed and the picture continues; all of it behind **the same Cast glyph**. **Two reversals:** AirPlay's
  "never draw it" is **withdrawn** (third tier, with a notice that the phone must stay on), and R245's "the
  picker is the platform's dialog" is **superseded** — the picker is now **Ravilo's own sheet**.
- **Drawn: `ravilo/Play on a TV - Directions.html`** (canvas) — the sheet's **ten states** in both phone
  frames plus Noir, the **two round-1 questions** as marked options (the AirPlay tier's weight: a full row
  with the notice vs a footnote link — **lean: the full row**; and whether a busy TV names *who* is watching
  — **lean: yes, and Offline keeps its weekday from the same decision**), the **AirPlay-active player** (the
  phone's own player is the remote — no mini bar, no R245 remote), the connecting bar's two moments and the
  **reconnect-shows-nothing** case, the receiver's **idle-with-code** (96 px tabular glyphs, no-server and
  dimmed-after-10-min variants) and its **player on the TV** (chrome, the two-level picker with Subtitle
  size, Skip Intro, next-up), the **TV-remote-pauses → phone-shows-paused** pair, §D's **screens table** for
  `app/settings.html`, and the 17 new strings × en/da/fo.
- **Flagged for the owner:** our **R266** (Cast Connect) and `main`'s **R264 + 236** answer the same wish by
  two roads (Google's cast hand-off vs the backend). Complementary, but the hand-off payload and the
  enrolment path should be **one mechanism, not two** — recorded as a ⚠ note at the top of R266.
- **Pulled:** the brief, `specs/research-reports/ravilo-web-pwa-player-cast-2026-09-18.md`, admin 235 + 236,
  Ravilo R263 + R264 + R265, and a fresh `STATUS.md`.
- **Not done in this round (needs no pick, waiting on a go):** §A into `Ravilo Mobile.html` (the
  unsupported-device notice, the install card, the update toast, the two standalone frames), §C1 into
  `Ravilo Receiver.html`, §D into `app/settings.html`, and a new `Ravilo - Web App Icons.html`.

## Previous sync (2026-09-18, 08:30 — no repo I/O: the phone's pages moved to a bottom bar, direction B built)
date: 2026-09-18T08:30:00Z
direction: none — design work only, on top of the 08:17 pull. Nothing fetched, nothing exported.
- **Drawn: `ravilo/Bottom Nav - Directions.html`** (canvas) — the baseline (R264's two top rows), **four
  directions** on Pixel 9 frames with real content behind them (**A** ink + stroke weight · **B** the
  selected icon in Ravilo's gradient pill · **C** a floating blurred capsule · **D** one label, on the
  active item only), a comparison table, and the three frames where a bottom bar meets the rest of the
  app: the **cast mini bar docking above it**, **Discover keeping its own five chips** at the top of its
  page, and the **player with no bar at all**.
- **Owner answered *decide for me*, so the picks are recorded in the spec:** direction **B · pill**; the
  13 sp label floor **bends to 11.5 sp** for these four labels only; tap-on-active **scrolls to top**
  with **Discover keeping R170's step-to-next-segment**; the pill **slides**; the brand **stays**;
  **handset only** (R256's seam); the mini bar **docks above** the nav; **no hide-on-scroll**.
- **R264 rewritten around it** and renamed —
  `specs/ravilo/requirements/phase-R264-phone-navigation-top-row-and-bottom-bar.md` (was
  `-phone-top-bar-two-rows.md`). Row one's FRs stand (brand · cast · search · avatar, no clock); the
  page row becomes **FR-R264-5/6/6a** (four items, a sliding gradient pill, the one type-floor
  exception), plus new FRs for opacity and absence on pushed screens (**-7**), the mini-bar stack
  (**-8**), tap-on-active (**-9**), always-visible (**-10**), Discover's chips (**-11**) and geometry
  from one place (**-12**). Still `Planned`, not dev-reviewed, **pending export**.
- **Built into `ravilo/Ravilo Mobile.html`:** the second top row and its guillotine fade are gone;
  `.bnav`/`.bn`/`.bnind` carry the four pages at the bottom with the gradient pill sliding between
  them; `.rc-mini` now docks above the bar; `.scroll` carries the bar's height as bottom padding; the
  nav's click handler owns tap-on-active (scroll-to-top, and the Discover segment step).

## Previous sync (2026-09-18, 08:17 — pull: three more dev specs incl. R262 Discover-as-one-frame; our two drafts renumbered again; R265 written and built)
date: 2026-09-18T08:17:00Z
direction: pull (repo → this project) — 3 spec files mirrored, nothing exported. A filtered tree scan for
`phase-(2[3-9][0-9]|R2[5-9][0-9])` at `main` — admin still tops out at **234** (so our **235** stands),
Ravilo now at **R262**.
- **Three new dev-authored Ravilo specs pulled, all `Planned`, written 2026-09-18:** **R260** (the
  player's Back is the phone's Back), **R261** (fullscreen only while something plays) and **R262**
  (**Discover is one page with five tabs, not five pages** — one frame, one app bar, one segment bar;
  loading/error become content states not page states; no slide between sections; TV Back is two stages
  defined by scroll, phone/web Back is one; warm all three stores on entry).
- **⚠ Our two unpushed drafts collided again and were renumbered again.** `main` took R260 · R261 · R262
  within hours of our 06:49 sync:
  - Cast Connect: **R254 → R260 → R263** (`phase-R263-cast-connect-tv-app-as-receiver.md`) — renumbered
    twice in one day; admin **235**'s cross-references updated with it.
  - The phone's two-row top bar: **R261 → R264** (`phase-R264-phone-top-bar-two-rows.md`).
- **Written this turn: R265 — Discover's tabs lead with the library, and the strip scrolls**
  (`phase-R265-discover-tab-order-and-scrolling.md`, `Planned`, not dev-reviewed). Owner direction: the
  order becomes **Networks · Studios · Genres · Coming Soon · Request** — left to right, what the
  household owns before what it does not — declared **once** as a list that gating *filters* and never
  re-orders (R243 FR-R243-1 stands), so entry is always **Networks** on every household, gated or not.
  Plus the strip scrolls on every platform: touch on the phone (five chips never fit portrait), and on a
  TV the D-pad carries the focused chip into view with `scrollRowTo`'s tween. Supersedes **R243
  FR-R243-1's order only**; pairs with R262. **Next free: R266.**
- **Built into both mockups the same turn:** `ravilo/ravilo-app.js` (a declared `SEG_ORDER`, `discTabs()`
  filters it, `TAXO_TABS` reordered, Discover's nav entry opens `discTabs()[0]`, and the focus-scroll
  helper now also scrolls `.discseg`), `ravilo/ravilo.css` (`.discseg` is a flex scroller with no
  scrollbar; the Seerr search pill stays pinned right — R265 OQ2), and `ravilo/Ravilo Mobile.html` (all
  five chips in the new order, gated and scrollable as `.discsegs`, with **two new phone views**:
  *Coming Soon* from `R.upcomingByDay()` + `R.overdue` day-grouped, and *Request* from
  `R.discover.lists[0]` — the old `renderTop10` body, now named for what it is). Verified on both: the TV
  opens Discover on *Støðir/Networks* with the strip in order; the phone shows five scrollable chips.
- **Also corrected on the phone, per the owner's instruction in the same breath:** the clock is **gone**
  from the phone's app bar (R264 FR-R264-3 — the platform already draws one in the status bar directly
  above); the TV and web keep `ClockDisplay`.

## Previous sync (2026-09-18, 08:12 — read-only: matched the phone's top nav to AppBar.kt + NavItems.kt)
date: 2026-09-18T08:11:58Z
direction: read-only (repo → this project). Nothing copied, nothing exported — two files read to ground
the mobile top-nav fix in the real component instead of the mockup's own (stale) shape.
- **Read `AppBar.kt` and `NavItems.kt`** (current `main`). Real shape: brand mark (drawn jellyfish SVG,
  matches the one already in `ravilo/ravilo-app.js`) + a plain ink-coloured wordmark (not gradient-clipped
  text); right cluster order is **Cast → Search → Clock → Avatar** (mobile had Search → Cast → Avatar,
  no clock at all). Real nav items are **Home · Movies · Series · Discover** (`raviloNavItems()`,
  `NavItems.kt:39`) — *not* the Compose default fallback's "My List" and *not* the mobile mockup's
  Home/Movies/Series/**Top 10**/Studios/Networks/Genies. Top 10 was retired end-to-end by phase 136/137
  (the Seerr pivot, long before R243); Studios/Networks/Genres are **Discover's own segments** (R170's
  avatar-menu/Discover merge + R243's taxonomy tabs), not top-level nav items.
  `ravilo/Ravilo Mobile.html` updated: appbar gets the mark + plain wordmark + Cast/Search/Clock/Avatar
  order + a live clock; the tab strip is now Home/Movies/Series/Discover, with Studios/Networks/Genres
  folded into a segment row inside the Discover tab (reusing the existing `renderTaxo`/`renderTaxoList`
  unchanged). The two stale `tab='top10'` aliases (the search icon, and the profile menu's "My List" row)
  now point at `discover` instead of a retired tab.
- **⚠ Left deliberately unfixed, flagged rather than guessed:** the search icon and "My List" still don't
  have their own real screens — they always aliased to a list tab (formerly Top 10, now Discover) rather
  than opening dedicated Search/My-List content. That gap pre-dates this pass and is a real product
  decision (what My List and Search look like on the phone), not a small nav-bar fix — worth its own turn.

## Previous sync (2026-09-18, 07:57 — read-only: matched the remote's "Converted" note to the built Kotlin, not invented copy)
date: 2026-09-18T07:57:30Z
direction: read-only (repo → this project). Nothing copied, nothing exported — one file read to ground a
mockup fix in the real component instead of guessing at its copy.
- **Read `CastRemoteScreen.kt`** (current `main`) after a first pass at a "Chromecast = transcode" warning
  invented its own text. The real, shipped UI is **FR-R245-19** ("Amendment 4", 2026-09-18, same day):
  a small drawn ⓘ glyph + *"Converted for {device}"* under the remote's state line, shown only while
  media is loaded and the receiver is reachable; tapping it opens one card (scrim + centered dialog) with
  the exact body copy pulled from `Strings.kt`'s `cast.converted` / `cast.converted_body` keys. Also read
  `R245`'s full spec off `main` to confirm this and three other amendments (1–4, all 2026-09-18: a
  `FragmentActivity` crash on the first real cast-button tap, a nonexistent CAF event type killing the
  receiver, an off-main-thread SDK call, and this note) are **not yet in our local mirror** — our copy of
  `phase-R245-cast-sender-receiver-and-remote.md` predates all four and is stale.
  `ravilo/Ravilo Mobile.html` (`#rcConv`/`#rcConvScrim`, verbatim copy) and
  `ravilo/mobile/ravilo-mobile-player.css` (`.rc-conv*`) updated to match.
- **⚠ Owed on the next real sync (not done this turn — read-only):** pull the canonical, amended
  `phase-R245-cast-sender-receiver-and-remote.md` over our stale draft, and check whether **R254**
  (Cast-Connect-is-TV-only, already in our mirror) or anything else references R245's pre-amendment shape.

## Previous sync (2026-09-18, 06:49 — full sync: our 225/226/227/R253 came back canonical + dev-reviewed; 12 new dev specs pulled; a double numbering collision resolved)
date: 2026-09-18T06:49:00Z
direction: pull (repo → this project), plus two local renumbers. `github_compare` against the last recorded
tree hash `7f22d328d5cb` returned 170 changed files across 47 commits.
- **Our whole 2026-09-17 export landed and is canonical, dev-reviewed, `✓ Built`.** `phase-225`, `-226`, `-227`
  and `phase-R253` all carry a 2026-09-17 dev-review addendum against `main` `8873cea7` and are `✓ Built` —
  pulled over our drafts. One real correction worth knowing: dev review found there is **no client-side trim
  to 10** (`StaticContentRow` draws every item it's sent), so a row's default `limit` is **30**, not the design's
  assumed 10 — FR-225-1b and R253 were corrected accordingly. The shipped row-order **editor is Kotlin**
  (`Workbench.kt`), not `design/app/ravilo-builders.js` — that file is not in the served bundle; our mockup stays
  the reference implementation the Kotlin port was built against. Pulled the four canonical specs plus the
  design files the row-order work touched: `app/ravilo-builders.js`/`.css`, `app/ravilo-config.html`,
  `app/Row Sorting - Directions.html` + `row-sorting-directions.js`, `screenshots/order-editor.png`,
  `ravilo/ravilo-app.js`, `ravilo/ravilo.css`.
- **⚠ Double numbering collision, both resolved by renumbering our side (repo wins, as with R196→R208,
  179→180, 186→R230→R234).** Our unpushed 2026-09-18 drafts had claimed **228** and **R254** the same day the
  dev team, working independently, took the *same two numbers* for unrelated phases — and then kept going six
  more admin phases and five more Ravilo phases before either side synced:
  - Admin **228** is on `main` as `phase-228-backend-memory-growth-curl-stableref-leak.md` (a Kotlin/Native
    `StableRef` GC leak in Ktor's Curl engine — unrelated). **Our Chromecast-publish/Cast-Connect draft is
    renumbered 228 → 235** (`specs/requirements/phase-235-chromecast-publish-cast-connect-and-listing.md`).
  - Ravilo **R254** is on `main` as `phase-R254-row-open-is-a-tv-thing.md` (J restricted to TV platforms only —
    unrelated). **Our Cast Connect draft is renumbered R254 → R260**
    (`specs/ravilo/requirements/phase-R260-cast-connect-tv-app-as-receiver.md`). All internal cross-references
    (228↔R254 → 235↔R260) updated in both files; a note flags that Ravilo's own "R235" (signs-only subtitles,
    pre-existing) is unrelated to admin's new phase 235 — same digits, different tracks, coincidence only.
  - **Both still `Planned`, not dev-reviewed, not exported** — this was a pure local renumber before either
    ever reached `main`. Verified against `main` on 2026-09-18: admin taken through **234**, Ravilo through
    **R259**. **Next free: 236 / R261.**
- **12 new dev-authored specs pulled, all `✓ Built` the same day (2026-09-17), none dev-reviewed:** admin
  **229** (a stop no longer takes Continue Watching off Home for 5 minutes — an ordering bug in
  `invalidatePlaystate`), **230** (the playstate refresher fetched *everything* every 20s — 94 requests/user;
  now titles every cycle + episodes on a 15-slice rotation), **231** (nothing publishes before CI passes — CI
  never ran a single unit test; a release-only `VerifyError` shipped to production twice), **232** (which ink a
  studio/network/title logo is drawn in — computed once server-side, extended same evening to title
  clearlogos after *Gone Missing*'s was found invisible), **233** (a credits marker starting inside the intro —
  775 episodes affected, three root causes, one position rule), **234** (an mkvpropedit edit reporting success
  over a corrupted file — the post-edit gate only checked one of two broken layouts, plus one-file-one-writer);
  Ravilo **R254** (J restricted to TV platforms — see collision above), **R255** (the backdrop scrim: **shipped
  as a flat 0.75 opacity layer, not the gradient the spec describes** — owner rejected the gradient on the stue
  TV the same day: "we only want an opacity … not only for the small parts where there is text"), **R256** (a TV
  was rendering the phone's player — `LocalHandset` used a bare 600dp threshold that a 540dp-short-side TV
  satisfies), **R257** (a TV sweep: focus lands on Home after selecting a taxonomy tile, dark logos on dark
  cards, no hero tint on detail pages, a heading under the app bar, swapped skip-arrow glyphs), **R258** (a
  scrub preview surviving an episode switch — and the fix's first attempt, a `LaunchedEffect`, itself crashed
  the TV by pushing `PlayerScreen` past ART's 256-register verifier ceiling), **R259** (the rest of the sweep:
  Search losing its query on Back, a general heading-clearance fix, TV Guide label pinning, the phone's profile
  picker wrapping, the two 232 ink rules reaching the client, and **walls going 6-up on a TV**, superseding
  R243's 4/5-up which were mockup numbers never seen on a real screen). Plus two research reports:
  `kotlin-native-gc-pacing-jetbrains-issue-2026-09-17.md` (228's GC-pacing write-up, drafted for JetBrains) and
  `mkv-payload-corruption-beyond-first-cluster-2026-09-17.md` (66 files with mid-file payload damage from the
  2026-09-13 repair race — data loss, not a layout bug; became phase 234).
- **No design/mockup work owed from any of the 12** — all are backend/Compose-only bug fixes and sweeps with
  no admin-mockup surface (232's client half rides R259; none of R254–R259 touch a file under `app/`).
- **STATUS.md re-pulled** (554 KB, read-only per CLAUDE.md — too large to fully re-render here; treat the repo
  copy as authoritative, this file's counters below are as of this sync).

## Previous sync (2026-09-17, 07:43 — sync check: repo unchanged where we mirror it; three design files pulled; four specs + five design files owed OUT)
date: 2026-09-17T07:43:14Z
direction: pull-side verified, **export owed**. `STATUS.md` (535 338 B), both constitutions, both plans and every
README are **byte-identical** to `main` — nothing to re-pull there. **No new phase specs upstream:** a regex sweep
for `phase-22[5-9]` / `phase-2[3-9]x` / `phase-R25[3-9]+` over all 217 spec files matched **0**, so **225 · 226 · 227
/ R253 are still unclaimed upstream** and our numbers hold. Counts: `specs/requirements` 97 upstream vs **100**
local (our 225/226/227), `specs/ravilo/requirements` 92 vs **93** (our R253), research reports 21 = 21.
- **Pulled (local was behind):** `app/segments.css` (22 974 → 24 290 B — a further 222/223 editor change dev-side
  since this morning's pull), `app/metadata.html` (+4 B) and `app/ravilo-users.html` (+190 B), neither of which had
  been size-checked in the 06:04 pass. Everything else in `design/` that we did not touch is byte-identical.
- **Owed OUT (this is the whole export):** four new specs —
  `specs/requirements/phase-225-row-order-sort-and-hand-picked-prefix.md`,
  `phase-226-chromecast-registration-fields.md`, `phase-227-one-public-address.md`,
  `specs/ravilo/requirements/phase-R253-see-all-opens-in-row-order.md` — plus `app/Row Sorting - Directions.html`
  and `app/row-sorting-directions.js` (new), and the four files we edited: `app/ravilo-builders.js` (82 550 →
  99 342 B), `app/ravilo-builders.css` (18 411 → 23 332), `app/ravilo-config.html` (75 201 → 76 662),
  `app/settings.html` (124 753 → 130 472). `CLAUDE.md` and `github.md` go to the repo root as always.
  **Leave alone on export:** the repo's `STATUS.md`, `specs/research-reports/` and `scripts/`.
- The tree hash moved (`0a6b45ea77b4` → `7f22d328d5cb`) on work outside our mirror (`src/`, `ravilo-ui/`) plus the
  three design files above.

## Previous sync (2026-09-17, 06:04 — pull: everything from 2026-09-16 shipped, 13 new dev specs, repo edits to our mockups pulled back)
date: 2026-09-17T06:04:26Z
direction: pull (repo → this project). 26 spec/STATUS files mirrored + **10 design files pulled BACK over our own
local copies**, nothing exported. Base commit unknown (the recorded `a5eeb8a82b50` is a tree hash), so this was a
tree + byte-size diff of every design file against `main`, as on 2026-09-15.
- **The whole 2026-09-16 export landed and is `✓ Built`.** `phase-218`, `phase-R244`, `phase-R245` are canonical
  on `main` (24.5 / 22.4 / 25.6 KB — dev-review addenda on all three; pulled over our drafts), and `STATUS.md` has
  every one of 216 · 217 · 218 · R243 · R244 · R245 as **✓ Built, implemented 2026-09-16** — the phone player, casting,
  the receiver, the taxonomy tabs and the Towo removal all shipped within a day of being drawn. `design/ravilo/Ravilo
  Receiver.html`, `mobile/ravilo-mobile-player.css`, the Settings Chromecast card and the i18n strings are all
  byte-identical upstream; `design/github.md` is identical (71 717 B); **`design/claude-console/` is gone** from
  `main` — the deletion we owed happened. **Nothing was pending export before this turn.**
- **13 new dev-authored specs pulled, every one `✓ Built` (2026-09-16/17):** admin **219** (a lost progress report
  is a lost write — `PlaybackWriter`), **220** (no ffmpeg per still on the interactive path), **221** (tell the
  operator an integration points at a dead endpoint — Settings → Notifications carries per-target webhook
  outcomes), **222** (segment editor seek + timeline truth), **223** (segment editor drag, slide and snap), **224**
  (Ravilo's real version + name in Jellyfin's dashboard); Ravilo **R246** (remembered track reaches the next
  episode — the F1 finding of the sweep below), **R247** (every language gets its name), **R248** (Home knows what you
  just watched — `home_changed` push), **R249** (episode badge not covered by *Soon*), **R250** (J's backdrop
  finished — readable scrim, panel inside the safe area), **R251** (player chrome that doesn't eat the keypress),
  **R252** (Ravilo knows its version). Plus the research report
  `specs/research-reports/stue-tv-test-sweep-2026-09-16.md` (an on-device sweep of the stue TV; F1 = the remembered
  subtitle never reaching the next episode, R241's fix notwithstanding — now R246). README files, both plans, both
  constitutions and `STATUS.md` re-pulled.
- **⚠ Repo-side edits to OUR mockups pulled back in** (local was behind on every one, all overwritten):
  `app/segments.js` (32.5 → 42.4 KB) + `app/segments.css` (16.6 → 23.0 KB) — 222/223's seek, drag, slide and snap
  built into the editor mockup dev-side; `app/wf.css` (50.3 → 51.8 KB) and `app/app.css` (14.3 → 16.2 KB) plus a
  **new `app/fonts/` directory** (`sora`, `space-grotesk`, `jetbrains-mono` .woff2 — the admin now self-hosts its
  three faces); `app/library.html` (5 B); `ravilo/ravilo-app.js` (143.7 → 144.3 KB) and `ravilo/ravilo.css` (94.9 →
  95.2 KB) — R249/R250's badge precedence and scrim. Every other Ravilo design file (`ravilo-data.js`, `-browse.js`,
  `-i18n.js`, `-focus.js`, `-player.js/.css`, both HTML mockups, both mobile stylesheets, `mobile-directions.css`,
  both direction canvases) is byte-identical to `main`.
- **Design work this sync inherits: none from the 13 specs** — 219/220/222/223/224 and R246–R252 are backend,
  Compose or already-drawn-dev-side; 221's Notifications outcome line is the one admin surface, and it is small
  enough to draw when the Settings page is next open.
- **Numbers taken this turn: 225 · 226 · 227 / R253 (all `Planned`, none dev-reviewed). Next free: 228 / R254.**
- **227 — One public address, configured once** (`phase-227-one-public-address.md`; supersedes FR-218-5's storage
  location and FR-218-4's *Your receiver address* field). 218's dev review had put `public_url` **inside the
  `chromecast` block** and named it "the first shipped instance of the explicit reach address pattern" — wrong home:
  an installation with Chromecast off has nowhere to put it. Now a single **Public address** field in Settings →
  Connections (origin only — `https://js.heimatún.fo`, no trailing slash, no path; rejected if it isn't absolute
  https with a host), `public_url` at the **root** of `AppConfig`, a silent migration from the nested key, and
  every external URL derived in one place (`+ "/cast/"` for 226's row, the reachability probe, and
  `RaviloConfig.cast.receiver_url`) — never from a request's `Host` header, which a reverse proxy makes a lie.
  The Chromecast card's own address field is **deleted**: the string now appears exactly once, in 226's step 3.
  Unset is a first-class state (the row says *Set your public address above first*, no Copy, switch still
  operable). **Built into `app/settings.html`** (`#pub-field`, a second fenced preview control for the unset
  state, and the status line now names the derivation).
- **226 — Chromecast registration: name the fields, give the values** (`phase-226-chromecast-registration-fields.md`,
  supersedes **FR-218-6 only**; 218 is `✓ Built` so its other FRs are untouched). The shipped card summarised the
  Google registration in three steps; the admin is reading *Google's* form, which asks for two fields by name. Now
  five steps: the console as a real link (`https://cast.google.com/publish/`) with the one-time US$5 fee, *Add New
  Application → Custom Receiver*, then one block per field with the console's **exact label**, the exact value and a
  Copy — **Receiver Application URL** = `public_url` + `/cast/` (trailing slash required, https only, same string as
  the card's own address field so they cannot disagree) and **Package Name** = `dev.jellystructure.ravilo` (a build
  constant from `ravilo-android/build.gradle.kts:15`, `.debug` variant noted) — plus *everything else can stay as it
  is*, the Application ID on save (the old hint said "after step 1", which was wrong), and test-device-or-publish
  with the serial-number hint. No config, route or behaviour change; FR-218-7's honest verification untouched.
  **Built into `app/settings.html`** (`#cc-steps`, page-local `.cc-fv*`).
- **Drawn, then BUILT, then spec'd this turn (pending export):** `app/Row Sorting - Directions.html` +
  `app/row-sorting-directions.js` (round-1 canvas: three hand-pick directions, states, TV consequence, ten
  decisions); the owner picked the recommended **direction 2 · Arrange the row**, so the **Order section is now
  built into the row editor** — `app/ravilo-builders.js` (`ORDER_KEYS`/`rowSort`/`autoSorted`/`orderTitles`/
  `stalePins`/`orderSummary`, the `orderHtml()` block, drag/click/search pin handlers, `seed()` reads
  `data-sort`/`data-pinned`, the row-list summary gains the order in words), `app/ravilo-builders.css` (`.cf-order`,
  `.cf-strip`/`.cf-seam`/`.cf-zone-*`, `.cf-pin`/`.cf-stale`, `.cf-thenby`, `.cf-rk`) and `app/ravilo-config.html`
  (three seeded workbench rows showing the three summary shapes). Specs:
  `specs/requirements/phase-225-row-order-sort-and-hand-picked-prefix.md` (two additive `RowConfig` fields,
  server-side resolver replacing seven hard-coded comparators, `SortName`, stale pins kept-and-skipped, the editor)
  and `specs/ravilo/requirements/phase-R253-see-all-opens-in-row-order.md` (See all's initial Sort = the row's key,
  `BrowseCard.sort_name`, two new Sort options — the only new strings; pins never reach the client).
  **Owner answered all five design questions the same day** and both specs + the editor were updated: default
  newest first; See all never puts pins first; genre rows don't exist (every non-system row is a workbench filter);
  pins only from matches; and **the row's shown count is now per-row config** (`RowConfig.limit`, 3–30, default
  **10** — what the viewer sees today, not the server's 30) **and is the ceiling on hand-picks** (a *Show N
  titles* stepper in the Order section; the − step never goes below the pin count). One new open question for the
  dev team: where the TV's client-side trim to 10 lives, since `HomeFeedService` sends 30.

## Previous sync (2026-09-16, later — pull: the mobile player + casting design brief, and our specs confirmed upstream)
date: 2026-09-16T00:10:22Z
direction: pull (repo → this project). 4 files mirrored, nothing exported. The design round this brief
asked for was then drawn locally — see *Pending export*.
- **Three new repo documents pulled**, all dated 2026-09-16 and all repo-authored:
  `specs/ravilo/design-brief-mobile-player-and-cast-2026-09-16.md` (the brief — the phone player, casting,
  the receiver, the admin card, the missing phone screens),
  `specs/research-reports/ravilo-mobile-player-chromecast-ios-2026-09-16.md` (the investigation: the
  phone-player gap table, three Chromecast architectures with B recommended, the iOS cost, and §7's
  owner decisions) and `specs/research-reports/ios-build-host-macbook-setup-2026-09-16.md`. `STATUS.md`
  re-pulled.
- **Our three 2026-09-16 specs are now canonical on `main`:** `phase-216-library-taxonomy-index.md`,
  `phase-217-remove-towo.md` and `phase-R243-browse-by-studio-network-genre.md`. A filtered tree scan for
  `phase-(215-219|22x|R242-R259)` matched exactly those three plus 215 and R242 — so **`main` tops out at
  217 / R243 and the next free pair is 218 / R244.** The research report's proposed numbering (216/R243 for
  mobile data policy, 217/R247 for Chromecast) **collides** and its whole ladder shifts by one; recorded on
  both new canvases, and no spec was written or edited to claim a number.
- **The design mirror needed nothing pulled this time.** Byte-compared every Ravilo design file the taxonomy
  build touched — `ravilo-app.js` 143,685 · `ravilo-data.js` 70,530 · `ravilo-browse.js` 22,974 ·
  `ravilo-i18n.js` 32,609 · `ravilo.css` 94,907 · `Ravilo TV.html` 4,955 — **all identical to `main`**. Only
  `Ravilo Mobile.html` differs (57,568 local vs 54,645 upstream) and that is exactly this turn's
  iPhone-frame addition. So our 2026-09-15/16 export landed in full and the repo has not edited our
  mockups since.
- **⚠ One piece of the Towo removal did NOT reach the repo: `design/claude-console/` is still on `main`**
  (`Dashboard - Direction A.html`, `Dashboard - Direction B.html`, `console.css`). `design/app/towo*.html`
  and `design/app/towo.css` are gone upstream as intended, and the directory is empty locally. **The next
  export must delete `design/claude-console/`.**
- **No `STATUS.md` edit, no `specs/research-reports/` edit, no `scripts/` touch** — all three remain
  repo-owned per CLAUDE.md.

## Previous sync (2026-09-16 — read-only: numbering check before writing two specs)
date: 2026-09-15T22:11:06Z   (repo query; the specs themselves are dated 2026-09-16 local)
direction: read-only (repo → this project). Nothing copied, nothing exported — the tree was queried only
to pick collision-free numbers for the Discover taxonomy pair.
- **No collision.** A filtered tree scan at `main` for `phase-(215-219|22x|R242-R259)` matched exactly
  **two** files, both already mirrored here: `phase-215-memory-budget-calculator.md` and
  `phase-R242-focus-detail-backdrop-background.md`. So `main` still tops out at **215 / R242** and our new
  pair keeps **216 / R243**. **Next free: 217 / R244.**
- **Written this turn, both `Planned`, neither dev-reviewed:**
  `specs/requirements/phase-216-library-taxonomy-index.md` (the served per-viewer index: one endpoint,
  profile-scoped counts, one seed shared with the grid, artwork only where it exists) and
  `specs/ravilo/requirements/phase-R243-browse-by-studio-network-genre.md` (the three Discover tabs, the
  wall, the two tile variants, Select → browse grid, the phone half).
- **⚠ The mirror was NOT refreshed this turn** — the task was two specs, not a sync. `STATUS.md` and
  `specs/` are as of the 2026-09-15 pull; re-pull before quoting status or counts anywhere.

## Previous sync (2026-09-15 — pull; our whole last pass landed upstream, and the repo edited our mockups back)
date: 2026-09-15T20:32:00Z
direction: pull (repo → this project). 25 spec/STATUS files mirrored + **12 design files pulled BACK over
our own local copies**, nothing exported. Repo wins on every disagreement, per CLAUDE.md.
- **Base commit unknown — `github_compare` failed** (`42885163eeee` from the last sync is a tree hash,
  not a commit, and is unreachable). Fell back to tree + blob-size diffing against every local file.
- **Everything we authored last session is now canonical on `main`.** 202 and R240 both have `STATUS.md`
  rows and shipped code; `design/app/media.html`, `series.html`, `activity.html`, `settings.html`,
  `segments.js`, `index.html` are **byte-identical** to our local copies. The 187/R234 corrections
  (preset colours dropped, "Your photo") **survived upstream** — verified by grep before overwriting.
  **Nothing is pending export any more.**
- **⚠ The design mirror is two-way now.** The dev team edited our mockups repo-side (R242 was built into
  them the same day it was spec'd). Local was BEHIND on all of: `ravilo/ravilo-focus.js` (15.4→18.0 KB),
  `ravilo.css` (89.3→92.2), `ravilo-app.js` (136.9→139.1), `ravilo-data.js` (66.0→66.9),
  `Ravilo Mobile.html` (43.8→49.9), `ravilo-player.js`, `ravilo-browse.js`, `app/wf.css` (**38.0→50.3**
  — mockup-only classes finally moved into the served stylesheet), `app/detail.css` (37.7→39.2),
  `app/ravilo-config.html`. All pulled over. `ravilo-i18n.js` was already identical.
  **Only local-ahead file was `Ravilo TV.html`** — and only because it still clamped the focus delay to
  600 ms, a ceiling 202 FR-202-3 deleted. Pulled over too.
- **J (the row opens) now ships ON.** R240's open question 1 — invariant 11's reflow cost — **closed
  2026-09-13**: 46 real settled row-opens on the stue BRAVIA, **2.8–3.2 % janky frames, 0 missed vsync**,
  dwell absorbing rapid navigation at 0.35 %. `focusDetailRowOpen` default `false → true`. Four live J
  bugs fixed on the way, all one root cause (**measuring the panel to decide where to scroll is
  circular** — a `LazyRow` never places an off-viewport item); panel width now derived per tile variant
  (482/627/591 dp), not a hardcoded 620.
- **R242 — J's own backdrop (`✓ Built` 2026-09-14), already built into our mockups dev-side.** The open
  row's title backdrop fills the whole screen, scrimmed, slower clock than the .22s panel tween; a
  lateral hop crossfades and never blanks. No new payload — `MediaCard.backdropUrl` already ships; the
  spec argues 202's "no artwork" non-goal was scoped to `FocusDetailFacts`, not `MediaCard`. L untouched.
- **15 new specs pulled:** admin **203–215**, Ravilo **R241**, **R242** — all `✓ Built`. 203–211 and R241
  need no design work (backend or Compose-only).
- **⚠ Four shipped admin surfaces are NOT in our mockups — this sync's entire design backlog: 212, 213,
  214, 215.** All design-authored with the owner repo-side on 2026-09-15, built the same day, from one
  playback incident (*The Patriarch* stalling every ~10 s — 10 concurrent Jellyfin ffmpeg subtitle
  extractions starving the disk at 80.8 % utilisation; the owner found **no UI to cancel it**, restarted
  the whole backend, and playback was still broken). **Their spec files still read `Status: Planned` —
  that header is stale; `STATUS.md` says `✓ Built` and STATUS.md is the declared source of truth.**
  See `CLAUDE.md` for the per-surface detail.
- **\u26a0 Owed back on export, beyond the mockups themselves: `adv-*` class names are ad-blocker bait.**\n  212's findings were first drawn with `.adv-label`, which renders **invisible** \u2014 a cosmetic filter\n  list hides that class with a user-origin `!important` that beats even an inline style, while every\n  sibling class renders normally, so it looks like one blank element rather than a broken page. Ours are\n  `jfa-*` now. The shipped `advisorFindingHtml` (212 \u00a78, reused by 215) should be checked for the same\n  thing before an operator running uBlock hits it.\n- **\u26a0 One repo-side defect found in the pulled CSS, and one deliberate local divergence.**  `design/ravilo/ravilo.css` uses `var(--line-2)` with **no fallback** in `.prof .pic.add`, and
  `--line-2` **is never declared** anywhere in the token set (29 properties: `--line`, `--bg-2`,
  `--card-2`, `--accent-2`, `--lt-live-2` \u2014 no `--line-2`). The shorthand is therefore invalid at
  computed-value time and the border is dropped outright, so the \"Add user\" profile tile renders with
  no border at all (probed: `border-top: 0px none`). The only other use, `.fpop .opt .box`, carries a
  `rgba(255,255,255,.22)` fallback and renders fine \u2014 which is why it shows up in exactly one place.\n  Given the `-2` suffix convention this reads as an intended sibling of `--line` that was never declared.\n  **Local divergence (deliberate, one character-level change):** we added the same fallback the sibling\n  rule already uses \u2014 `var(--line-2, rgba(255,255,255,.22))`. Same class of finding as 187's\n  `.usr-av`/`.usr-cap` (mockup-only classes that never reached a served stylesheet): **send it back with\n  the next export**, and either declare `--line-2` upstream or keep the fallback.\n- **Counters re-derived:** `STATUS.md` = **404** rows (**204** admin + **200** Ravilo), no duplicates;\n  **194** documents under `specs/`; highest **215 / R242**; next free **216 / R243**. Unused numbers
  (admin 59–69, 77; Ravilo R88–R132) are historical, below where our mirror starts. Deck counters
  updated in `presentation/Jellystructure & Ravilo - Spec-Driven Development.html` (387→404, 179→194).

## Previous sync (2026-09-12, later — pull; 19 new dev specs, no renumbering needed)
date: 2026-09-12T07:25:00Z
direction: pull (repo → this project). 29 files mirrored (STATUS.md, both plans/constitution, 19 new
specs, 8 amended), nothing exported. **Repo wins on every disagreement, per CLAUDE.md — our local 187 and
R234 drafts were overwritten by the canonical, dev-reviewed, now-built versions.**
- **No numbering collision, so nothing was renamed.** `main` tops out at **201** / **R239**: no
  `phase-202-*` or `phase-R240-*` file, and `STATUS.md` has no row for either number. Our 2026-09-12
  focus-detail pair keeps **202 / R240**. **Next free: 203 / R241.**
- **187 + R234 are `✓ Built` (2026-09-05)** — the profile-photo/password pair we design-authored on
  2026-09-03 and renumbered on 2026-09-04. 187's backend landed with both write routes, the change-keyed
  avatar URL (R214 pre-empted) and the admin read-only photo; R234 was **live-verified on stue TV**, and a
  pre-existing Settings D-pad focus-chain bug (Change password / Sign out / Unpair unreachable) was found
  and fixed in the same pass. Canonical specs are 32 KB / 23 KB against our 12 KB drafts — pulled over.
  **FR-187-1's live Jellyfin probe closed every open question:** all three operations exist, none at the
  assumed path, the OpenAPI document is wrong about the image body (raw binary → 500, base64-with-MIME →
  204), and a wrong password answers **403**.
- **⚠ Our mockups now lead both specs in two places** (they must change before either ships, and they are
  the only design work this sync inherits from our own earlier passes): **preset colours are dropped**
  (owner decision on 187 OQ3 — `AV_PRESETS`/`colorFor`/`setColor` in `ravilo/ravilo-data.js` and the Your
  profile sheet in `ravilo/Ravilo Mobile.html`), and the **"Photo and name"** row promises a rename that is
  out of scope. 187 also records that `.usr-av`/`.has-photo`/`.av-img` and 185's `.usr-cap` existed only in
  our mockup's inline `<style>` and had never reached `wf.css` — fixed dev-side, now fenced by
  `check-mobile-css.sh`.
- **19 new specs mirrored:** admin **188–201** (capped-scan episode destruction, segment-editor markers +
  audio, metadata-language override on every write, clearlogo pipeline, Jellyfin-behind banner, transient
  failure vs rejected token, retry-set drain, `last_checked` semantics, four days of red CI, a constraint
  violation breaking the build, JS tags surviving a slug rename, invisible sidecar subtitles,
  `mkvpropedit` evicting `Tracks`) and Ravilo **R235–R239** (signs-only subtitle auto-select, Home-hero
  Down stranding focus, don't retry an unretryable failure, wrong version badges on the language row,
  honest subtitle flag strip). All `✓ Built` except **R239** `⚠ Partial`. Also re-pulled as amended:
  `specs/constitution.md` (invariant #6's predecessor rule, per 199), `specs/plan.md`, `phase-155`,
  `phase-178`, `phase-186`, `R202`, `R219`.
- **Nine design items land on us** — see `CLAUDE.md` for the detail: 201's MKV-layout health card +
  per-title Fix now banner, 200's SUBTITLES strip + Sidecar subtitles group, 192's logo-as-logo picker fix
  + artwork-repair button, 193's honest banner copy, 196's `last_examined_at` labels, 189/190's stepper +
  lock toast + audio-transcode badge, 191's mismatch card, R239's honest flag counting, and R237's
  per-cause failed-start copy + the *"Still trying…"* line on R218's cold start. **None drawn yet.**
- **Counters re-derived:** `STATUS.md` = **387** rows (190 admin + 197 Ravilo, no gaps, no duplicates —
  the 2026-09-04 repo-side STATUS gap for R227–R232/186 is **closed**); **179** documents under `specs/`
  (177 on `main` + our two unpushed drafts); highest **202 / R240**; next free **203 / R241**. Deck
  counters updated in `presentation/Jellystructure & Ravilo - Spec-Driven Development.html`.
- `presentation/` is unchanged upstream (compare against `e8a11120` returns nothing) — the mirror is
  still current and ours to build on.

## Previous sync (2026-09-12 — read-only: numbering check before writing two specs)
date: 2026-09-12T06:43:33Z
direction: read-only (repo → this project). Nothing copied, nothing exported — the repo tree was queried
to pick collision-free numbers, and one adjacent spec was read in full because it touches the same code.
- **The dev team has moved a long way since 2026-09-04.** A tree scan at `main` shows admin phases taken
  through **201** and Ravilo through **R239**: admin `188` (scan episode cap destroys existing episodes),
  `189`/`190` (segment-editor markers + audio), `191` (metadata-language override survives every write —
  our 184's follow-up), `192` (clearlogo pipeline), `193`–`196` (Jellyfin-behind banner, transient failure
  vs rejected token, ingest retry set, last-checked semantics), `197`/`198` (CI red four days; a constraint
  violation breaks the build), `199` (js tags survive a slug rename), `200`/`201` (sidecar subtitles
  invisible; `mkvpropedit` evicts the Tracks element); Ravilo `R235` (never auto-select a signs-only
  subtitle), **`R236`** (Down from the Home hero can stop working entirely), `R237` (don't retry a failure
  that cannot succeed), `R238` (picker language row shows the wrong version badges), `R239` (honest
  subtitle flag strip).
- **Our two new specs are therefore 202 / R240**, written the same day, both `Planned`, neither
  dev-reviewed — the focus-detail pair (config + payload, and the viewer half incl. J's motion rules).
  **Next free: 203 / R241.** No renumbering needed this time.
- **R236 read in full, because it owns code R240 touches.** It rewired Home's Down bridge from a
  per-item `FocusRequester` to a row-level one decorated with `focusRestorer()` (✓ Built 2026-09-06, not
  device-tested). R240 cites it: J inserts and removes a `LazyRow` child on every settled focus move, so
  nothing J draws may carry a requester or be reachable by the restorer — and R236's own open question 2
  (does the restorer compose a *disposed* remembered child?) now has a sibling-resizing case layered on it.
- **⚠ This project's `STATUS.md` mirror and spec mirror are ~14 documents stale.** Neither was refreshed
  this turn (the task was two specs, not a sync). Re-pull both — and re-derive every presentation counter —
  before quoting status, phase counts or document counts anywhere.

## Previous sync (2026-09-04 — pull; two numbering collisions resolved)
date: 2026-09-04T18:08:00Z
direction: pull (repo → this project). 5 new spec files mirrored, STATUS.md refreshed, our two unpushed
drafts renumbered out of the way of numbers the dev team had already taken.
- **Collision 1 — admin 186.** The repo's `phase-186-request-intent-lifecycle-cleanup.md` (Planned,
  design-authored 2026-09-04) took the number our profile-photo draft was holding. **Ours is now 187**
  (`specs/requirements/phase-187-account-photo-and-password.md`); all `FR-186-*` ids inside renumbered.
- **Collision 2 — R230.** The repo's `phase-R230-fully-disable-skip-intro-credits.md` (Implemented
  2026-09-03) took R230. **Ours is now R234** (`specs/ravilo/requirements/phase-R234-profile-photo-and-password.md`);
  `FR-R230-*` → `FR-R234-*`, and the mockup comments that cited R230 (`app/ravilo-users.html`,
  `ravilo/ravilo-app.js`, `ravilo/Ravilo Mobile.html`) now cite R234. Both spec files carry a
  renumbering note at the top. Same shape as R196 → R208 and 179 → 180.
- **New specs pulled:** **R231** (Continue Watching timeout cache poisoning — Implemented; the report we
  folded into the deck yesterday, now mirrored), **R232** (series-detail & player D-pad polish — ✓ Built
  2026-09-04, live-tested on stue TV: season-row focus/clipping, player Right no longer teleports to
  Back), **R233** (system rows always scoped to the page they are on — Planned), repo **R230** (Skip
  Credits Off was inert since R182 — Implemented), repo **186** (request-intent lifecycle cleanup — Planned).
- **One outstanding design item, deliberately not built yet: R233 FR-R233-7** asks for the per-channel
  `scope` segmented controls to be removed from `app/ravilo-builders.js` (`:364` new-channel template,
  `:534`, `:540`, `:736`) and the two row descriptions made unconditional. R233 is Planned and not
  dev-reviewed, so the mockup still shows the control. Apply when R233 is accepted.
- **Counts re-derived:** STATUS.md now has **359** rows (174 admin + 185 Ravilo); **158** documents under
  `specs/`. Deck counters updated (359 unchanged, 154 → 158, highest 187 / R234, next free 188 / R235).
- **STATUS.md gap widened (repo-side, not ours):** no rows for R227–R232 or admin 186, though their spec
  files read Implemented / ✓ Built. R233 does have a row. `scripts/check-phases.sh` will flag it.

## Previous sync (2026-09-03, latest — R231 report folded into the deck)
date: 2026-09-03T21:04:12Z
direction: no repo I/O — a dev-authored bug report (R231) was handed over in chat and built into the deck
- **R231 — Continue Watching timeout cache poisoning** (`specs/ravilo/requirements/phase-R231-continue-watching-timeout-cache-poisoning.md`,
  committed `e8a11120`). R219's spec said a failed Jellyfin build must serve the previous good cache value;
  the shipped `buildCanonicalContinueList` returned `emptyList()` on its 6 s timeout and wrote that empty
  list into the shared 5-minute SWR cache, blanking Continue Watching on Home, every channel row and
  See-all at once. Return type is now `List<ContinueEntry>?` — `null` = failed (never cached, falls back to
  the prior value even past TTL), empty = genuinely nothing. Compile clean; `linuxX64Test` inconclusive on
  a from-scratch rerun; not device-verified.
- **Deck updated:** new slide **15a** "The spec said it. The code did half of it." after the R202→R219
  chain, and the phase/document counters moved 358→**359** and 153→**154** on the cover, household and
  spec-before-code slides (next free numbers now 187 / R232).
- **Not yet mirrored:** the R231 spec file itself is not in this project's `specs/` copy — pull it on the
  next sync.

## Previous sync (2026-09-03, later — read-only counting pass)
date: 2026-09-03T18:13:38Z
direction: read-only (no copy, no export) — counted the repo to refresh the presentation's stats
- **No files pulled or written to the project.** Every figure in
  `presentation/Jellystructure & Ravilo - Spec-Driven Development.html` was re-counted against `main`
  rather than carried over: **342** Kotlin source files, **9** Gradle modules (8 subprojects + root, from
  `settings.gradle.kts`), **18** route files under `server/routes/`, **27** Kotlin unit-test files (25
  backend + 2 `ravilo-ui` commonTest), **5** Playwright `.spec.ts` files under `tests/e2e/`. Largest
  files by bytes: `ui/MediaDetail.kt` 262 KB · `screens/PlayerScreen.kt` 186 KB · `ui/Settings.kt` 185 KB ·
  `ui/RaviloConfig.kt` 173 KB · `routes/MediaRoutes.kt` 158 KB.
- Counted from this project's own mirror: **153** documents under `specs/` (56 admin phase files, 69
  Ravilo phase files, 18 research reports), **358** STATUS.md phase rows (174 admin + 184 Ravilo), **305**
  design mockup files.
- **Lines of Kotlin (~84k) is an estimate**, not a count — derived from summed file bytes at ~56
  bytes/line, calibrated against three real files. Replace with real `wc -l` output when convenient; it is
  the one figure on that slide nobody has actually measured.
- **Commits shown as "1,300+"** for the same reason: the previous 1,130 could not be re-verified with the
  tools available, and the floor is safe. Worth replacing with a real `git rev-list --count` before the talk.
- Also on that deck: rewrote the Kotlin/Native constraint callout in plain language (was `select()` /
  `fd 1024`), and rebuilt the R202 case-study pair to end on **R219** — deleting R202's line exposed that
  there was no single Continue Watching list at all, which is the better story and the current truth.

## Previous sync (2026-09-03, earlier)
date: 2026-09-03T12:10:31Z
direction: pull (repo → this project) — 31 commits since the 2026-09-02 sync, no new export needed
- **Both our specs shipped as designed. Open question resolved on-device — the copy is confirmed correct.**
  **Phase 185** (✓ Built 2026-09-02, incl. the client-side start timer) and **R222** (✓ Built 2026-09-02)
  are both in `main` now, canonical versions pulled over our drafts. The one deviation: the ceiling is
  **two columns** (`decode_max_bitrate_hevc`/`_h264`, one shared timestamp), not one — found live-necessary
  because R216/R183 force an AVC transcode target, so a single column would silently record the wrong
  codec's ceiling for an HEVC file. **Open question 1 answered on-device 2026-09-02**: R216 has been live on
  the stue TV since 2026-08-30 (105 `playback_qoe` rows carrying its fields, `direct_play=0` on heavy
  sessions, `dropped_frames=0` throughout) — the *Till Daybreak* stutter was a Wholphin session, architecturally
  unreachable by any of this. Through Ravilo the file re-encodes and starts slowly; it does not stutter.
  `slow_lead`/`slow_tail_measured`/`slow_tail_expected` are the right copy, unblocked for translation.
  `basis: "measured"` is reachable in practice now (timer built) but unreached on any real device yet —
  needs 3 completed starts of the same file on the same device, and there's been no device access since.
- **181/182/183 all moved from Planned/partial to ✓ Built, with real measurements**, and both surfaced a
  genuine new follow-up bug each — logged for the next unassigned number, not fixed inline:
  - **181**: FR-181-1/1a/2/4/5 built (id set-difference sweep, reverse-diff-to-History, activity-based
    freshness, dead WS listener deleted, narrow dirty-set). FR-181-3 (count reconciliation) deliberately
    **not built** — FR-181-1's full sweep already runs at the fastest cadence a separate count check would,
    making it strictly redundant.
  - **182**: all of §A/§B built. **Live-measured 2026-09-02** (dev-restart authorized): `/api/tv/series/{id}`
    unaffected by a concurrent scan; `/api/tv/home` degrades **~120× at p50** during a scan — root cause is
    `HomeFeedService`'s cache invalidating almost every request because `libraryVersion` (correctly, per
    182's own fix) bumps on every item write. **New candidate follow-up, unfixed**: a home-feed cache keyed
    on a coarser signal than per-item `libraryVersion`.
  - **183**: all FRs built (TMDB token bucket + AIMD, jittered backoff, bounded per-episode fan-out, skip
    already-held metadata, DB-level rate-limit marking via 181's dirty-set, Outbound pacing card). **New
    candidate follow-up, unfixed**: `POST /api/media/{id}/sync` on a 505-episode series reliably
    self-saturates the 4-permit **interactive** `ProcessGate` reserve (not the background one) via a
    completely unbounded ffprobe fan-out in `syncSeriesEpisodes` that 183 never bounded (183 only bounded
    the TMDB fetch in the same loop) — confirmed live, household playback unaffected throughout.
- **7 new dev-authored Ravilo specs pulled**, none requiring a design/mockup change (all Compose-only or
  deploy/CI): **R223** (season-picker focus + rapid-Up scroll-stranding fixes, ✓ Built, deployed to both
  TVs), **R224** (merge Ravilo TV + phone into one universal Play listing, ✓ Built), **R225** (login-screen
  server indicator + `DEFAULT_SERVER_URL` for `ravilo-web`, Planned), **R226** (drop the http/https toggle
  on server setup, infer scheme, Planned), **R227** (profile menu now closable via system Back on mobile,
  ✓ Built), **R228** (a channel empty for this viewer no longer shows as a tile, ✓ Built), **R229**
  (Settings screen phone-width padding + toggle-row squeeze fix, ✓ Built). Also: **R213** (generated
  Baseline Profile) completed generation 2026-09-02 (root cause was a stale `androidx.benchmark` pin);
  **R217** superseded/closed by **R219** (re-pulled canonical, unchanged status); **R216**'s open question 4
  (on-device verification) is now answered by 185's finding above.
- **STATUS.md mirror refreshed.** No `design/**` changes needed this sync — every new spec is
  backend/Compose-only.
- **Next unassigned numbers: 186 / R230.**

## Previous sync (2026-09-02)
date: 2026-09-02T07:25:03Z
direction: pull (repo → this project) — one research report, then a design pass
- **Pulled `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`** (new repo-side,
  19 KB). Triggered by *Till Daybreak (2025)* — a 82 Mbps 4K DV/HDR10+ REMUX — stuttering on stue TV and
  being abandoned mid-watch, the third stutter on that TV in three weeks. Owner's proposal: record what
  bitrate each device can take and warn on the Ravilo detail page before Play.
- **What the report establishes:** the *measuring* half already exists (Phase 177 + R216, 2026-08-28 —
  `detectDecoderLimits()` reads the decoder's own declared ceiling, stue TV = exactly 60 Mbps, and the
  server turns it into a `VideoBitrate` condition at 0.9 margin so Jellyfin transcodes instead of
  breaking). Three blockers: (1) last night's session was **Wholphin**, a third-party client that
  negotiates straight against Jellyfin — architecturally unreachable by anything we design; (2) nothing
  persists the ceiling anywhere queryable at rest (`ravilo_device` has no column; `playback_qoe` is
  session-scoped and pruned); (3) the proposal reverses R216's stated invariant *"no user-visible
  setting, ever"* and R180 FR-RV-ASP1-2's no-delivery-cues rule.
- **Design pass done same day** — `ravilo/Decode Ceiling Warning - Directions.html` (canvas): baseline,
  four directions (A admin-only persistence · B meta-row chip · B′ line above Play · C confirm gate ·
  D full numbers), Noir + phone frames, a copy-candidates panel and panels answering all eight of the
  report's open questions. **Recommendation: A + B′.** Key reframe: if 177/R216 are working the file
  does *not* stutter — it re-encodes and takes ~20 s to start, so the honest signal is an expectation
  (**"Slow to start on Stue TV. Give it a moment after you press play."**), not a capability warning.
  The badge is a function of two static numbers only (recorded ceiling × file bitrate, same 0.9
  predicate as 177) — QoE and link state never feed it, so it can't flicker. C rejected (nothing to
  offer but dismissal), D rejected (undoes R180/R218 deliberately).
- **Owner steer, same day:** the note must be **backend-computed per device from the recorded limit plus
  that device's history**, not derived client-side. Design updated and **B′ built into the Ravilo mockups**
  — one server-pushed `playbackNote { device, basis, seconds }` per file per device; ceiling decides
  *whether*, history decides *how sure* (`measured` adds "about 20 seconds", `expected` says "give it a
  moment"), history can never toggle the note. Touched `ravilo-data.js`, `ravilo-app.js`, `ravilo.css`,
  `ravilo-i18n.js`, `Ravilo Mobile.html`. Admin half (A) **drawn 2026-09-02** in `app/ravilo-users.html` — persisted ceiling as a second line in
  each device row ("picture it can take"), two unknown states, no new column. Owner also confirmed the
  start-timing history and the file-bitrate field don't exist yet and will be built backend-side, so both
  fold into 185 instead of blocking it.
- **Both specs written 2026-09-02** (`Planned`, not dev-reviewed): `specs/requirements/phase-185-device-decode-ceiling-and-start-history.md`
  (persist the ceiling, add `Track.video_bitrate`, record start samples, resolve `playbackNote` server-side,
  admin device row) and `specs/ravilo/requirements/phase-R222-slow-to-start-note.md` (render the sentence,
  three strings × en/da/fo, placement + rejected directions). Next unassigned numbers now **186 / R223**.
- **Previously noted as blocking** — three answers needed first: where the file's bitrate comes from (`Track` has
  no bitrate field today), whether other OEM decoders report honest ceilings, and whether the R216 build
  is actually installed on the stue TV Ravilo. Prospective numbers **185** (admin persistence + device
  row) and **R222** (the Ravilo line).

## Previous sync (2026-09-01)
date: 2026-09-01T19:58:58Z
direction: pull (repo → this project) — mirror refresh, no export
- **Nothing new repo-side since the 2026-08-31 sync.** The full `specs/` diff against `4a2675f524cd`
  returns the same 17 files we already pulled (R215–R220, 177–183, amended 167, two research reports).
  Re-pulled **STATUS.md**, `specs/research-reports/README.md` and the amended `phase-167` to be certain
  our read-only mirrors match `main` — the repo wins on any disagreement, per CLAUDE.md.
- **No numbering collision.** A tree scan for `phase-18[4-9]` / `phase-R22[1-9]` matched **0 of 139**
  spec files, so our two 2026-09-01 design-authored specs keep their numbers: **184**
  (choose the TMDB metadata language) and **R221** (every genre on a media detail). Next unassigned
  numbers stay **185 / R222**. No repeat of the R196→R208 or 179→180 collisions.
- **Still not exported.** Both new specs, both directions files, and the 184 build in `app/media.html`
  + `app/detail.css` remain local — see *Pending export* below.

## Previous sync (2026-08-31)
date: 2026-08-31T15:28:03Z
direction: pull (repo → this project)
- **Both design-authored specs from 2026-08-28 shipped.** `phase-R218` (player loading/buffering states)
  is **Implemented** — built the same day it was spec'd, Android/Compose only, **on-device verified
  2026-08-29** on the stue TV. `phase-180` (playback session teardown) is **✓ Done** — the Jellyfin-side
  stop call was confirmed against 10.11.11's OpenAPI *before* any code was written (the open question we
  flagged), and it was verified live against a real forced 4K/DV/HDR NVENC transcode. Canonical versions
  pulled over our local drafts.
- **5 new dev-authored specs pulled:**
  - **R219 — Continue Watching: one canonical list** (`Implemented`, design-authored with the owner
    2026-08-30). Supersedes R217's FR-1/2/3. jellystructure owns one merged list per (user, visibility
    scope); every surface is a filtered/capped view. Every Jellyfin fetch is paged to `TotalRecordCount`
    (no `Limit` may act as a cap — the same truncation error caused four bugs); conflict resolved by
    **timestamp, never provenance**; See-all mirrors the row's own configured scope. Home/channel cap
    drops 30 → 20. Explicitly **no UI change**.
  - **R220 — video output lost on return from background** (`✓ Built 2026-08-31`, not dev-reviewed, not
    reproduced on-device). Black picture with perfect audio after Home-button/TV-standby round-trip;
    detector on rendered-frame count + a 4-rung recovery ladder. **Touches R218:** none of R218's three
    moments fire in this failure, and FR-R220-5 says a slow recovery should show R218's existing **STALL**
    presentation with no new copy or visual language. Build note admits that signal is **not wired**
    (open question 7) — so a viewer can still see several seconds of frozen black frame with no chrome.
  - **181 — converge on Jellyfin's library, don't predict it** (partially implemented; FR-181-2 built).
    Fjollerne S11E07 missing for 15h: premiere-year freshness bucketing filed a currently-airing 2005 show as
    monthly-archive (9 of 16 provably-airing series were starved), nothing ever compared our item set to
    Jellyfin's, and the Jellyfin-based realtime ingest has delivered **nothing, ever** since phase 165
    (the WS listener subscribes to nothing and `LibraryChanged` is never sent — dead code reporting
    itself healthy). Fix: id **set-difference** sweep as the backstop (`DateCreated` is the file's mtime —
    50% of files are junk-dated, so no timestamp watermark is sound), activity-based freshness, per-series
    count reconciliation, a persistent dirty-set.
  - **182 — a scan must never stop the server** (`✓ Built 2026-08-31`, not live-verified). Whole 4-thread
    scan pool wedged uncancellably for 21 min while `/api/tv/**` starved behind one global 64-permit
    outbound semaphore. Shipped: `SpinLock`/atomics on the scan write path, per-item deadlines, real
    cancel with a grace period, and **INTERACTIVE/BACKGROUND partitioning** of `OutboundHttp` (16 reserved)
    and `ProcessGate` (4 reserved) with bounded acquires → 503 + `Retry-After`.
  - **183 — pace outbound requests by rate, not concurrency** (`✓ Built 2026-08-31`, not live-measured).
    A 300-episode series fanned out ~1 500–2 000 unbounded TMDB requests from one worker slot, retried on a
    flat jitter-free 3 s; a rate-limited fetch then silently wrote null titles and stamped the item as
    checked. Shipped: `TmdbRateLimiter` token bucket + AIMD on 429, `Retry-After` + jittered backoff, an
    episode fan-out gate sized from `scan_workers`, and skipping already-held episode details/credits.
- **Also pulled:** amended `phase-167` (already-known publish-on-main change), `specs/research-reports/README.md`,
  and the **STATUS.md** mirror. No `design/**` changes to pull — the diff's `design/` entries are our own
  2026-08-27/28 export echoing back (segments/library/media/builders, the presentation deck + screenshots,
  and `Player Loading and Buffering - Directions.html`).
- **Next unassigned numbers: 184 / R221.**

### Design work from this sync — all three drawn 2026-08-31 (pending export)
- **FR-182-9 — banner DROPPED (owner decision, 2026-08-31).** The "Ravilo requests are queuing behind
  background work" banner was drawn and then removed: telling the admin about the queuing accepts it as a
  normal state. Ravilo clients get priority unconditionally, and if interactive work does slow down the
  right response is to **stop the running background activity**, not to display a warning. Only the
  read-only **Capacity** card remains (one bar per gate, background vs Ravilo/admin against the reserved
  share, from the `/api/health` gate blocks). The stop-background-work behaviour is **not spec'd** — it is
  a backend change for the dev team, and FR-182-9 as written no longer matches what we want.
- **FR-183-6 — rate limiting visible while it happens. Drawn.** An **Outbound pacing** card on
  `app/activity.html`: per host, the current rate, the limiter's ceiling (with when it last backed off)
  and refusals in the last minute; plus FR-183-5's plain run-summary line "12 fields not fetched: TMDB
  rate limit" and a matching chip in the Overall strip. Copy deliberately avoids promising a re-check —
  FR-183-5's DB half is not built.
- **R220 open question 7 — the recovery ladder shows nothing. Drawn.** New frame **E · video output
  recovery** on `ravilo/Player Loading and Buffering - Directions.html` (black frame, chrome up, spinner
  in the play button's place) plus a panel section recording the decision: reuse R218's stall treatment
  verbatim under a new trigger — no new copy, no new visual language, nothing to translate.

## Previous sync (2026-08-28, second pull — numbering collision resolved)
date: 2026-08-28T21:20:19Z
direction: pull (repo → this project)
- **New repo-side spec: `phase-179-subtitle-sideload-transcode-stall.md` (✓ Implemented, same evening).**
  R183/161's deferred half: phase 177 made the top bitrate tier transcode far more often, which forces
  `embedContainerSubs = false` and routes text subs onto Jellyfin's on-demand VTT extraction — the path
  R183 once measured at 4m37s. Adds a `prewarm_subtitles` pipeline step, a subtitle-only retry policy on
  the Android player, and `subtitle_load_errors` in the QoE report.
- **Collision:** our design-authored teardown spec also claimed 179. Renumbered **ours** to **180**
  (dev tracker wins, as with R196 → R208); all cross-references in R218, CLAUDE.md and this file updated.
- **R218 is uncontested** — no repo-side R218 exists. Next unassigned: **181 / R219**.
- STATUS.md mirror refreshed.

## Previous sync (2026-08-28, first pull)
date: 2026-08-28T16:08:36Z
direction: pull (repo → this project), no design work required
- **10 commits / 51 files since last sync, all backend/platform + CI + specs — nothing under `design/`.**
  New specs pulled: **phase-177** (delivery-aware playback negotiation — per-codec decode-bitrate
  ceiling, honour client audio-codec list for audio-only transcode, link-derived bitrate cap, QoE
  ingest endpoint; ✓ Done), **phase-178** (playback-aware background I/O — defer segment
  detection/artwork fetch and throttle qBittorrent while a TV is watching, "Paused — TV is watching"
  dashboard banner + Run anyway; ✓ Done), **R215** (Play Store internal-track auto-deploy on GitHub
  Release, CI-overridable signing/version; Planned — Play Console setup is manual), **R216** (client
  half of 177: real decoder bitrate ceilings, Wi-Fi link state, hardened `LoadControl`, ExoPlayer QoE
  capture; ✓ Done), **R217** (Continue Watching starvation fix — round-robin merge of resume+next-up
  before capping, not concatenate-then-cap; **Planned, implementation deliberately deferred** per user
  request). **phase-167 amended**: `publish.yml` now also fires on every push to `main` (not
  release-only), pushing `latest` + commit-SHA tags continuously; release publishing unchanged/additive.
  New research report `stue-tv-4k-playback-stutter-2026-08-28.md` — disproves a third-party DV-demux/
  lossless-audio/24p-matching briefing for the reported title, finds the real causes (60 Mbps decoder
  ceiling, 18.6% Wi-Fi retry ratio + 2.4GHz band-steering, shared-spindle I/O contention) → adopted as
  177/178/R216.
- **STATUS.md mirror refreshed** (read-only). **Deleted locally**: `presentation/observed-issues-
  2026-08-18.md` (removed repo-side).
- No screen map changes — nothing here touches a design file.
- Next unassigned numbers: **179 / R218** (per phase-178's/R217's own numbering — confirm on next sync).

### Design-authored 2026-08-28 (local, not yet pushed)
- `specs/ravilo/requirements/phase-R218-player-loading-buffering-states.md` — client buffering/loading
  states (Direction B cold start · chrome-up stall · scrub-tile seek · 400 ms debounce · no escape hatch).
- `specs/requirements/phase-180-playback-session-teardown.md` — server releases an in-flight transcode
  when a viewer leaves; keyed on the existing `playSessionId`. **Renumbered 179 → 180** on 2026-08-28 —
  the dev team took 179 the same evening (subtitle-sideload transcode stall).
- `ravilo/Player Loading and Buffering - Directions.html` — the design reference both specs cite.
- Mirrored `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md`.

### Sync 2026-08-27
direction: pull (repo → this project) + design catch-up here
- **15 new dev-authored specs pulled** — admin **169–176** (all `✓ Done`) and Ravilo **R208–R214**
  (R213 the only `Planned`). **3 changed specs re-pulled**: `phase-163-segment-editor.md` (12.9 KB
  design draft → 30.5 KB canonical, now `✓ Done` with the publish half **dropped**), `phase-168`,
  `phase-R195`. **STATUS.md mirror refreshed** (269 KB). `presentation/` re-pulled — both markdown
  files unchanged, still 57 screenshots.
- **Stale local file deleted:** `phase-R196-episode-rail-autohide.md` — our episode-rail spec was
  **renumbered R196 → R208** repo-side (the dev tracker had already spent R196 on the
  remembered-track regression).
- **Design updated here to match the shipped code** (4 files): `app/segments.js` — publishing to
  Jellyfin removed end to end (no Publish button, no `pub` state, no "in Jellyfin" column; last
  column is now **Confirmed**, primary action "Take me to what needs me"), because Jellyfin 10.11.11
  answers `POST /MediaSegments` with **405** and accepts segments only from provider plugins.
  `app/library.html` + `app/ravilo-builders.js` — **Music videos** added as a third type-filter value
  (phase 172). `app/media.html` — Artwork tab **"Currently in use"** on-disk block (173) and
  **Clear TMDB match** / per-asset **Clear** / locked Re-pull ▾ item (174).
- **Deck refreshed** (`presentation/Jellystructure & Ravilo - Spec-Driven Development.html`): 322
  numbered phases (158 admin + 164 Ravilo), 113 spec/research documents, 328 Kotlin files, 10 Gradle
  modules, arc endpoint 176 · R214, next free 177 / R215 — and the segments slide now tells the
  publish-was-dropped story instead of claiming markers reach Jellyfin.
- Next unassigned numbers: **177 / R215**.

### Sync 2026-08-21
date: 2026-08-21T07:45:05Z
direction: read-only (repo → this project) — no new mirror files
- **Read `initial-idea.md`** (repo root, 1,865 bytes, the project's founding brief) as source material
  for the deck. Not mirrored into this project — it is a historical document, not a spec, and the deck
  quotes it directly.
- Confirmed the codebase statistics quoted on the deck's "in numbers" slide against the repo tree at
  `main`: **311 `.kt` files**, **9 Gradle modules** (`settings.gradle.kts`), 18 route files, 15 Kotlin
  unit-test files, 13 Playwright specs.
- No spec, `STATUS.md` or `presentation/` changes pulled this turn — the 2026-08-20 mirror is still current.
- **Design-authored this turn:** `presentation/Jellystructure & Ravilo - Spec-Driven Development.html`
  (23-slide dev-audience deck) + `presentation/admin/*.png` (6 captures of our own admin mockups, since
  the repo has no Jellystructure screenshots) + `presentation/scratchpad.md` (outline). Pending export.

### Sync 2026-08-20
direction: pull (repo → this project)
- **26 new spec files pulled** (admin 164–168, Ravilo R196–R207 — R196 is a filename-disambiguated
  numbering collision) **+ STATUS.md refresh + a brand-new `presentation/` directory (59 files)** —
  see `CLAUDE.md`'s "Where the work stands" for the per-spec summary. `presentation/` is new ground:
  we don't own it yet but are about to (per the user), so it's mirrored in full rather than skipped —
  `presentation-context.md` (talk material), `observed-issues-2026-08-18.md` (triage log), and
  `presentation/screenshots/*.png` (57 real on-device captures).
- No `design/**` changes on the repo side this sync (only specs/STATUS/presentation moved).
- Next unassigned numbers: **169 / R208**.

### Sync 2026-08-13
- **Towo (Phase 162) shipped — our design spec is now `✓ Done`.** Authored here 2026-08-10 as `Planned,
  not yet dev-reviewed`; the dev team reviewed and built **all 8 build-order steps on 2026-08-11**, plus a
  same-day completeness pass, and verified it live end to end (real Claude account, real browser via
  Playwright, isolated scratch backend on port 19505 — the real backend was never touched). Pulled the
  canonical 49 KB spec (three dev-review addenda) over our 20 KB draft.
- **Only one spec file changed repo-side this sync** — a byte-for-byte compare of all 89 mirrored files
  found no additions, deletions or other edits. `STATUS.md` mirror refreshed. No new dev-authored phases.
- **What the build changed vs. our design** (all additive; **mockups updated the same day** — new Settings
  fields, the runners-list `mirror_error` chip, and a "runner offline" preview state on the session view):
  Towo settings are **backend-owned in `towo_settings`, not localStorage** (the auto-continue scheduler
  has no browser to read from) — only the `js-towo` feature flag stays client-side; new settings fields
  we never designed: **low-quota threshold %**, **permission-request timeout + reason** (default 30 min),
  **"where runners connect" URL** override, and the five notification toggles now actually fire.
  Auto-continue is **control-plane-owned**, not runner-owned. There is **no runner-detail screen** in the
  build, so the new `mirror_error` warning rides the runners **list row** instead of §B's detail page.
  Session view gained a live **"runner offline" banner**; a permission decision made while the runner is
  down is durably **queued and redelivered** (chosen over fail-fast, closing our open question); a
  timed-out request records `decision = "timeout"`.
- Both SDK unknowns we flagged are **resolved**: `canUseTool`'s signature is pinned
  (`@anthropic-ai/claude-agent-sdk@0.3.227`; returning `null` is a first-class out-of-band-approval
  mechanism, and there is **no built-in timeout**), and `rate_limit_event` is real + camelCase with
  `utilization` a **0–1 fraction, not 0–100** — the live account's active bucket is `seven_day`.
- Still open in the spec: subscription-vs-API-key, unattended OAuth on a headless host, and whether Towo
  eventually graduates out of this admin app. Remaining build polish: a resumed session loses its
  permission profile (falls back to Normal).
- Next unassigned numbers: **163 / R196** (unchanged).

### Sync 2026-08-10 (#2)
### Sync 2026-08-10 (earlier, numbering collision)
- **Numbering collision resolved:** the dev team took **R192/R193/R194** (MediaSession trio) while our
  same-language subtitle-picker draft sat at R192. Our spec renumbered **R192 → R195**
  (`phase-R195-same-language-subtitle-picker.md`); every R192 reference in `CLAUDE.md`,
  `ravilo/ravilo-player.js` and `ravilo-player.css` updated. Still `Planned`, not yet dev-reviewed.
- **New dev-authored specs pulled (all `Implemented`):** **R192** (release the OS MediaSession when the
  TV player backgrounds, not only on screen exit — lingering phone media card), **R193** (rich
  media-session metadata: title/episode/artwork, deliberately TV-only — phone gets no MediaSession at
  all, trading headset transport keys for the stronger privacy guarantee), **R194** (session artwork
  prefers the season poster, falling back to the series poster — never an episode still),
  **160** (scanner falls back to Jellyfin's own season/episode numbering when filename parsing fails —
  the numbering counterpart to phase-152's id join).
- Re-pulled `specs/plan.md`, `specs/ravilo/plan.md` and the `STATUS.md` mirror.
- No change to R190 or phase-157 (both still `Implemented`).
- Next unassigned numbers: **161 / R196**.

### Previous sync
date: 2026-08-09T00:08:26Z
direction: pull (repo → this project)
- Compared `6145f6fc7854...main` (173 files, 56 commits). Most were our own `design/**` export
  echoing back, or code (ravilo-tizen module, backend/UI wiring) not pulled here. **Pulled the real
  spec changes:**
- **Phase 157 (Bazarr subtitles) — our design spec is now `Implemented`.** The dev team built it
  end to end with a dev-review addendum: id-join matching (`imdbId`/`tvdbId`, no path-matching —
  simpler than either the spec or the Radarr precedent assumed), the full Bazarr command surface
  confirmed live against a real instance (4,999 wanted episodes + 35 movies, validating our "5,000+
  is normal" framing), "Upgrade" composed client-side (no single-item Bazarr endpoint exists),
  per-title History merges at render time only (never persisted — keeps "stores nothing" intact).
  Series per-title History merge deliberately NOT built (movies only, noted as a limitation).
- **New dev-authored specs pulled:** **158** (IMDb ratings — imdbapi.dev died; pivoted to IMDb's own
  bulk ratings dataset after the planned title-page scrape hit an unsolvable AWS WAF JS challenge),
  **159** (intro/credits detection overhaul — offset-histogram alignment for variable cold opens,
  outro fingerprinting, credits-heuristic hardening). Both `Implemented`.
  **R188** (Upcoming calendar per-device visibility — restricted/kids profiles no longer see
  unscoped *arr calendar speculation), `Implemented`. **R191** (single-profile sign-out without
  unpairing the whole TV — also fixed a real "Unpair this TV" 404 bug found in passing), `Implemented`.
- **R190 (our people-filter design) — confirmed still `Implemented`**, no change since last sync.
- **New research report pulled:** `subtitle-picker-same-language-disambiguation-2026-08-09.md` —
  quantifies same-language subtitle-track collisions in the live library (46.5% of movies, 47.5% of
  series affected) and scopes a future **R192** picker redesign. Research only, not a spec yet.
- **Modified specs re-pulled:** `specs/ravilo/plan.md` (R175/R191 route-table updates), `phase-R175`
  (D-pad field-navigation bug fix addendum), `phase-R184` (third root-cause addendum — non-deterministic
  Jellyfin episode-duplicate lookup).
- **STATUS.md mirror refreshed** (read-only, per CLAUDE.md — never edited here).
- Design-authored **158 = filter-by-person** is NOT a conflict — R190 already covers that; no
  renumbering needed this sync.

### Previous sync — 2026-08-07T11:26Z (pull)
- Compared `6145f6fc7854...main`, 135 files/35 commits — mostly our own design echo. Confirmed R190
  flipped `Planned → Implemented` in the repo (dev team shipped our people-filter design end to end,
  incl. the ravilo-tizen module).

### Previous sync — 2026-08-02T17:47Z (pull)
- Pulled specs/ + research-report changes only (`design/**` not pulled — design flows out, not
  back). Repo shipped Phase 155 (age-rating normalization) and R187 (browse page) — both
  `Implemented`, canonical dev versions pulled over our drafts. New specs: 155, 156, R187, R188,
  R189 (Tizen client, build-verified). Reconciled a numbering collision: our people-filter renamed
  R188 → **R190** (dev team had taken R188/R189 meanwhile).

## Screen map
| Design file(s) | Repo spec(s) |
|---|---|
| ravilo/Ravilo TV.html, ravilo/ravilo-app.js, ravilo/ravilo-browse.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js | R187 (browse page — shipped), R190 (filter by person + Seerr overflow — shipped) |
| app/ravilo-builders.js, app/library.html, app/ravilo-config.html | phase-140 (workbench query blocks), R190 §D (Cast-or-crew workbench facet — shipped) |
| app/metadata.html, app/metadata.css | phase-155 (age-rating normalization — shipped) |
| app/index.html | phase-146 (dashboard breakdown), phase-138 (mobile), phase-157 (Subtitles dashboard card — shipped) |
| app/series-simpsons.html, app/series-johnnybravo.html, app/detail.css | phase-149 (multi-episode files), phase-150 (segment scrubber), phase-151 (artwork lock), phase-159 (segment-detection accuracy — backend only, no design change) |
| app/settings.html | phase-150, phase-154, phase-156 (Seerr per-user attribution — shipped), phase-157 (Bazarr connection — shipped) |
| app/subtitles.html, app/app-shell.js (Subtitles nav) | phase-157 (Bazarr subtitle overview — shipped) |
| app/media.html, app/series.html | phase-157 (Tracks & subtitles / season Bazarr cards — shipped), phase-158 (IMDb re-sync label fix — shipped) |
| app/livetv.html | phase-147 (Live TV admin config) |
| app/segments.html, app/segments.js, app/segments.css, app/series-simpsons.js (entry points), app/Segment Editor - Directions.html | **phase-163** (intro & credits editor — shipped 2026-08-13/14; publishing to Jellyfin dropped in dev review, mockups updated 2026-08-27), **189** (markers cannot be moved — ✓ Built; FR-189-2's 1 s stepper + tenths readout and FR-189-5's locked-marker toast **not drawn**), **190** (editor audio — ✓ Built; FR-190-3's honest badge replaces the hard-coded "direct play · no transcode", **not drawn**) |
| app/library.html, app/ravilo-builders.js (Include segment) | phase-172 (music-video filter support — shipped, drawn 2026-08-27) |
| app/media.html (Artwork tab, pagebar Identity card) | phase-173 (current on-disk asset), phase-174 (clear a wrong TMDB match) — both shipped, drawn 2026-08-27 |
| (none — backend/platform only) | 169, 170, 171, 175, 176, R209–R214 (parallel probes, segment process pool, music-video artwork/TMDB, unified scan engine, stale-artwork guards, subtitle delivery, startup performance, image cache busting) |
| ravilo/Ravilo Mobile.html, ravilo-player.js/.css | R177, R179, R180, R181, R182, R184 (autoplay stale position fix — shipped), R188 (Upcoming visibility — shipped, no design change), R191 (single-user sign-out — shipped, no design change) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (no spec yet) |
| ravilo/ravilo-player.js, ravilo-player.css, ravilo-app.js, ravilo/Audio & Subtitles Picker - Same-Language Directions.html | R195 (same-language subtitle picker — shipped) |
| (removed from this project 2026-09-16 — every Towo mockup, `app/towo.css`, the Settings tab, the sidebar group and `claude-console/` are deleted) | **phase-162** (Towo agent control plane) → **retired by 217** (`specs/requirements/phase-217-remove-towo.md`, written 2026-09-16, `Planned`). 162's spec + `STATUS.md` row are deliberately **kept** (to be marked `Removed`); the next export **deletes** the design files repo-side and must not be read as a stale mirror |
| (none — backend/platform only) | R192/R193/R194 (MediaSession lifecycle, metadata, season artwork — shipped, no design change), phase-160 (scanner numbering fallback) |
| ravilo/Player Loading and Buffering - Directions.html, ravilo/ravilo-player.js/.css | **R218** (player loading/buffering states — shipped 2026-08-28, on-device verified 08-29), **phase-180** (session teardown — ✓ Done), R220 (video-output recovery — reuses R218's STALL; presentation not yet wired), **R237** (per-cause failed-start copy + one *"Still trying…"* line on the cold-start treatment — ✓ Built; drawn 2026-09-15, canonical upstream) |
| ravilo/Ravilo Mobile.html, ravilo/ravilo.css (flag strips) | **R239** — now `✓ Done`; the honest `+N` counting is drawn and canonical upstream |
| app/activity.html | phase-182 (Capacity card only — FR-182-9's banner dropped by owner decision), phase-183 FR-183-6/FR-183-5 (Outbound pacing card + run summary) — drawn 2026-08-31, backend built, UI not yet in code; **201** FR-201-10 (MKV track layout health card — **not drawn**) |
| app/media.html, app/series.html (pagebar + Tracks & subtitles + Artwork tab) | **200** FR-200-5, **201** FR-201-11/12, **192** FR-192-5/6, **191** FR-191-5, **193** FR-193-4, **196** FR-196-3/5 — all `✓ Built` dev-side and **drawn here 2026-09-12→15, now confirmed canonical upstream** |
| app/index.html (Dashboard) | **192** FR-192-2 (artwork-repair sweep button — drawn 2026-09-12, canonical upstream); **214** ("Stop scan" rename + un-ghost — **not drawn**) |
| (none — backend only) | R219 (Continue Watching canonical list — explicitly no UI change), 181 (library sync convergence) |
| ravilo/Decode Ceiling Warning - Directions.html | (no spec yet) research report `ravilo-per-device-decode-ceiling-warning-2026-09-02.md`; builds on phase-177 + R216, constrained by R180 FR-RV-ASP1-2. Admin half would land on app/ravilo-users.html |
| ravilo/Ravilo Mobile.html, ravilo/ravilo-app.js, ravilo/ravilo-data.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js, app/ravilo-users.html | **187** (`phase-187-account-photo-and-password.md`) + **R234** (`phase-R234-profile-photo-and-password.md`) — profile photo + change password. Design-authored 2026-09-03, renumbered from 186 / R230 on 2026-09-04, **both `✓ Built` 2026-09-05** (R234 live-verified on stue TV) and canonical specs pulled 2026-09-12. **⚠ The mockups now lead the specs:** the preset-colour row (`AV_PRESETS`/`colorFor`/`setColor` + the Your profile sheet) is dropped per R234 FR-R234-3, and the "Photo and name" row must be relabelled or wired |
| app/ravilo-builders.js (per-channel system rows), app/ravilo-config.html | **R233** (system rows always scoped — Planned; FR-R233-7 removes the `scope` segmented controls, **not yet applied to the mockup**), R143 (introduced `scope`, retired by R233) |
| (none — backend/client-only) | **R231** (Continue Watching timeout cache poisoning), **R232** (series-detail & player D-pad polish), repo **R230** (Skip Credits Off), repo **186** (request-intent lifecycle cleanup — explicitly no new Ravilo UI) |
| ravilo/Focus Detail - Directions.html, ravilo/Focus Detail - Round 2 Directions.html (+ -print copy), ravilo/ravilo-focus.js, ravilo/ravilo.css, ravilo/ravilo-app.js, ravilo/ravilo-i18n.js, ravilo/Ravilo TV.html, app/ravilo-config.html | **202** + **R240** — focus detail on Home rows. Both `✓ Built`; **J now defaults ON** (invariant-11 reflow cost measured and closed 2026-09-13). Plus **R242** (J's own full-screen backdrop — `.jbg`/`.jbg-img`/`.jbg-scrim`, `showBg`/`hideBg`/`bgLayer`, the `backdropFor` seam), **built into these mockups dev-side and pulled back 2026-09-15** |
| app/settings.html (Libraries tab) | **212** — Jellyfin settings advisor: read-only, suggest-only, per library, silent where the live value already matches; findings carry Jellyfin's exact on-screen label. `✓ Built` dev-side, **drawn 2026-09-15** (server-wide card + per-library findings + the unknown-storage silence case on 4K Movies) |
| app/settings.html (Advanced tab) | **215** — memory budget calculator: one RAM number in, copy-pasteable Jellyfin/compose/sysctl changes out; show the arithmetic, never offer page cache as capacity, refuse rather than over-commit. `✓ Built` dev-side, **drawn 2026-09-15** |
| app/activity.html, app/index.html (Dashboard) | **214** — "Stop scan" (renamed from Pause, un-ghosted, both surfaces), "Stop this step" beside Activity's new step strip, and the `subtitlesStillRunning` sentence (absent at zero). `✓ Built` dev-side, **drawn 2026-09-15** |
| app/settings.html (Scanning), app/activity.html (Jobs & workers) | **213** — `behavior.segment_workers` replaced by `behavior.job_workers` (1–3, default 2) over three named FIFO queues; `/api/health` `job_queues` block. `✓ Built` dev-side, **drawn 2026-09-15** (shared-pool card + three-lane Queues card + the Settings control) |
| (none — backend/client-only) | **203–211** (cold health cache, reader-blocking writes, Ravilo reads waiting on Jellyfin, collection fan-out, three `prewarm_subtitles` defects, undocumented Jellyfin routes, sidecar bulk-reorder no-op, `PlaystateCache` episode ids), **R241** (remembered track vs ISO-639 granularity) |
| ravilo/Ravilo TV.html, ravilo/ravilo-app.js, ravilo/ravilo-browse.js, ravilo/ravilo-data.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js, ravilo/Ravilo Mobile.html | **216** + **R243** — Discover's Studios / Networks / Genres tabs (the viewer-side of `app/metadata.html`). Design-authored: mockups 2026-09-15, both specs 2026-09-16, both `Planned`, neither dev-reviewed. **Pending export** |
| ravilo/Ravilo Mobile.html, ravilo/mobile/ravilo-mobile-player.css, ravilo/Ravilo Receiver.html, ravilo/ravilo-i18n.js, app/settings.html (Connections → Chromecast) | **R244** (`phase-R244-phone-player-chrome.md`) · **218** (`phase-218-chromecast-receiver-and-registration.md`) · **R245** (`phase-R245-cast-sender-receiver-and-remote.md`) — the phone player (direction 2 · Thumb rail, **no playback speed**), casting (remote direction 2 · Now playing, mini bar, server-pushed cast button), the receiver's ten screens, and the admin Chromecast card. Design-authored: mockups + specs 2026-09-16, all three `Planned`, none dev-reviewed. **Pending export** |
| app/settings.html (Connections) | **218** (built) + **226** + **227** — all three `✓ Built` and canonical as of 2026-09-18 (five-step registration, the two console fields, the root-level **Public address** field) — plus **235** (`phase-235-chromecast-publish-cast-connect-and-listing.md`, renumbered from 228 the same day; supersedes FR-226-1/-3) — the eight-step/three-group Cast-Connect + publish + listing extension, still `Planned`, local-only, **pending export** |
| (none — backend/client-only) | **229** (a stop keeps Continue Watching on Home), **230** (playstate refresh paced), **231** (CI gates publish), **232** (logo ink, server-computed) + client half **R259** FR-R259-5/6, **233** (credits/intro position rule), **234** (one file one writer) — all `✓ Built` 2026-09-17, no admin design surface |
| ravilo/Ravilo TV.html, ravilo/ravilo-app.js, ravilo/ravilo.css, ravilo/ravilo-focus.js | **R254** (J is TV-only; unrelated to our own renumbered draft, see below) · **R255** (backdrop scrim — shipped as flat 0.75 opacity, not the spec's gradient) · **R256** (`isTvPlatform` fixes `LocalHandset` on a TV) · **R257** (TV sweep: focus, logos, hero tint, heading clearance, skip-arrow glyphs) · **R258** (scrub preview dies with its episode) · **R259** (Search query, general heading fix, guide label, phone picker wrap, ink on client, 6-up walls) — all `✓ Built` 2026-09-17, all client-only, no design-mockup surface touched |
| ravilo/ (no file — local draft only) | **R260** (`phase-R260-cast-connect-tv-app-as-receiver.md`, renumbered from R254 the same day; pairs with admin **235**) — Cast Connect on the TV app side, `Planned`, not dev-reviewed, **pending export** |
| app/ravilo-config.html, app/ravilo-builders.js, app/ravilo-builders.css, app/Row Sorting - Directions.html (+ row-sorting-directions.js) | **225** (`phase-225-row-order-sort-and-hand-picked-prefix.md`) + **R253** (`phase-R253-see-all-opens-in-row-order.md`) — per-row Order for workbench rows: Date added · Title · Release year in either direction, or Hand-picked first with a fallback key; server resolves, TV renders, See all opens in the same order. Design-authored: canvas + build + both specs 2026-09-17, both `Planned`, neither dev-reviewed. Constrained by R187, R219/R233 (system rows exempt), R59/R143 (rows inside collections), 202 (mirror resolved config onto the feed). **Pending export** |
| ravilo/Mobile Player - Directions.html (+ -print), ravilo/Casting - Directions.html (+ -print), ravilo/mobile-directions.css | **Design brief** `specs/ravilo/design-brief-mobile-player-and-cast-2026-09-16.md` §A/§B/§C/§F + research report `ravilo-mobile-player-chromecast-ios-2026-09-16.md`. **Round 1 directions only, drawn 2026-09-16 — no spec written, nothing built into the main mockups.** Constrained by R218 (waiting states, reused verbatim), R237 (failure copy), R180/R195 (the picker as a sheet), R234 (46 px / 13 px floors), R193 (no local MediaSession), R182 (the busy state), R222. Prospective phases **218 + R244** (mobile data) · **R245** (player chrome) · **219 + R248** (Chromecast). **Pending export** |
| presentation/presentation-context.md, presentation/observed-issues-2026-08-18.md, presentation/screenshots/ | (not a spec — talk source material; documents R202 as its centerpiece and the R203–R207 triage) |

## Pending export
- **2026-09-18 — four design-authored specs, all `Planned`, none dev-reviewed, none pushed:**
  `specs/requirements/phase-235-chromecast-publish-cast-connect-and-listing.md` (renumbered from 228),
  `specs/ravilo/requirements/phase-R263-cast-connect-tv-app-as-receiver.md` (renumbered R254 → R260 →
  R263), `specs/ravilo/requirements/phase-R264-phone-navigation-top-row-and-bottom-bar.md` (renumbered
  from R261; rewritten around the bottom bar) and
  `specs/ravilo/requirements/phase-R265-discover-tab-order-and-scrolling.md`. New design file:
  `ravilo/Bottom Nav - Directions.html`. Modified mockups: `app/settings.html` (the eight-step/three-group
  Chromecast card, local-only), `ravilo/Ravilo Mobile.html` (one top row + the bottom nav, the
  FR-R245-19 *Converted for {device}* note + popover, Discover's five scrollable tabs and its two new
  phone views), `ravilo/mobile/ravilo-mobile-player.css` (`.rc-conv*`), `ravilo/ravilo-app.js` +
  `ravilo/ravilo.css` (R265 on the TV). **Next free numbers: 236 / R266.**
- ~~Everything below this line landed on `main` and is `✓ Built` as of the 2026-09-17 or 2026-09-18 pull.~~
- **2026-09-16 (latest) — the picks BUILT, and three specs.** New: `specs/ravilo/requirements/phase-R244-phone-player-chrome.md`,
  `specs/requirements/phase-218-chromecast-receiver-and-registration.md`,
  `specs/ravilo/requirements/phase-R245-cast-sender-receiver-and-remote.md`,
  `ravilo/mobile/ravilo-mobile-player.css` and `ravilo/Ravilo Receiver.html`. Modified:
  `ravilo/Ravilo Mobile.html` (the player, the remote, the mini bar and the cast button — well beyond the
  iPhone frame now that the picks are in), `ravilo/ravilo-i18n.js` (player + casting strings × en/da/fo)
  and `app/settings.html` (the Chromecast card in Connections, `cc-` prefixed page-local classes).
  **Numbers taken contiguously from free (218 / R244 / R245); next free 219 / R246.**
- **2026-09-16 (later) — the round-1 picks applied, and playback Speed removed from the design.** Both
  canvases and both print copies now read as decisions rather than options (nine settled rows each);
  Speed is out of every rail, sheet, state list and string table (the rail is three items, the player
  string list is thirteen); a new **state 8** on the casting canvas draws the remote with **Subtitles &amp;
  audio open** — the same R180/R195 picker, applied on the TV. Both `-print.html` copies re-stamped
  against their sources' new versions.
- **2026-09-16 (later) — the mobile player + casting design round (round 1, directions only).**
  New: `ravilo/Mobile Player - Directions.html`, `ravilo/Casting - Directions.html`, their two
  `-print.html` copies (both stamped with their source version) and `ravilo/mobile-directions.css`.
  Modified: `ravilo/Ravilo Mobile.html` — **iPhone 16 frame only** (device picker + `.phone.ios` +
  `.island` / `.homeind` + a `?dev=ios` param, and `fit()` adjusted for two frame sizes). Per the brief,
  nothing else in that file and nothing in `mobile/ravilo-mobile.css` was touched. **No spec files
  written or edited by this round.**
- **⚠ The export must also DELETE `design/claude-console/`** (3 files still on `main`) — the one part of
  the 2026-09-16 Towo removal that never reached the repo. `design/app/towo*.html` and `towo.css` did
  land; 162's spec and its `STATUS.md` row stay untouched by us, per 217 FR-217-8.
- **2026-09-15/16 — the Discover taxonomy pair: specs 216 + R243 and the mockup build.**
  `specs/requirements/phase-216-library-taxonomy-index.md`,
  `specs/ravilo/requirements/phase-R243-browse-by-studio-network-genre.md`, plus
  `ravilo/ravilo-data.js` (new `taxonomy`/`taxonomySummary`/`taxoValues`/`libraryFor`/`normAge`; films now
  resolve from `A_STUDIOS`, series from `A_NETS` — **this changes the *Studio* fact on some film detail
  pages**), `ravilo-app.js`, `ravilo-browse.js`, `ravilo.css`, `ravilo-i18n.js`, `Ravilo TV.html` and
  `Ravilo Mobile.html`. Two pre-existing CSS defects fixed on the way and owed back with it: the browse
  crumb wrapping against the `h1`'s width, and a short browse result stretching its posters to fill six
  columns.
- **The 2026-09-15 design pass: 212, 213, 214 and 215 drawn** into `app/settings.html`,
  `app/activity.html` and `app/index.html`, plus the `--line-2` fallback in `ravilo/ravilo.css`. All
  four specs are already on `main` and `✓ Built` in Kotlin — what is pending is only the mockups.
  New page-local classes (`.adv*`, `.mb-*`, `.steps`/`.stepchip`, `.stopnote`, `.lane`/`.occ`) live in
  each page's own `<style>`, following `.paced`/`.mkvh-*` precedent; the 187 lesson says they will want
  moving into `wf.css` dev-side, so expect `check-mobile-css.sh` to have an opinion.
- Everything before this pass is canonical upstream — see the note below.
- ~~2026-09-06 → 2026-09-12, Ravilo focus detail (202 / R240, the mockup build, both directions files
  and the print copy)~~ — **exported and canonical as of 2026-09-15.** The one deviation noted here
  (202 FR-202-2 resolving the mode server-side while the mockup read both booleans) is resolved
  upstream: the server resolves it, and our pulled `ravilo-focus.js` is the repo's own copy.
- ~~2026-09-03 — profile photo + password mockup build (187 / R234)~~ — **exported and canonical as of
  2026-09-15**, including both corrections (preset colours dropped, "Your photo"), verified by grep
  against the repo copies before they were pulled over.
- **All earlier design/spec work is already on `main`** — the 2026-09-02 decode-ceiling
  mockups + specs (185/R222) and the 2026-09-01 metadata-language/genre work (184/R221) were exported and
  are confirmed present in the repo diff this sync pulled.
- **phase-163 — intro & credits editor** (`Planned`, 2026-08-13): `specs/requirements/phase-163-segment-editor.md`
  + `design/app/segments.html`/`segments.js`/`segments.css`, the `Segment Editor - Directions.html`
  exploration, and the series-page entry points in `series-simpsons.js`. Still not confirmed pushed —
  check on next export pass.

## Sync history
- 2026-09-18 (08:17): pulled **R260 · R261 · R262** (Discover as one frame); **our drafts renumbered again** — Cast Connect R254→R260→**R263**, the phone top bar R261→**R264**; wrote **R265** (Discover order Networks · Studios · Genres · Coming Soon · Request + a scrolling strip) and built it into both mockups; dropped the phone's clock. Next free **236 / R266**.
- 2026-09-18 (06:49): `github_compare` against `7f22d328d5cb` → `main`, 170 files/47 commits; **225/226/227/R253 came back canonical + dev-reviewed** (the "no client-side trim to 10" correction, editor is Kotlin not JS); 12 new dev specs pulled (admin 229–234, Ravilo R254–R259, all `✓ Built`, none needing design work) + 2 research reports; **double numbering collision** — our unpushed 228/R254 renumbered to **235/R260** (repo had independently taken both numbers the same day for unrelated phases). Next free **236 / R261**.
- 2026-09-17: base commit unreachable, byte-size diff of every design file; **the entire 2026-09-16 export is canonical and `✓ Built`** (216 · 217 · 218 · R243 · R244 · R245, `claude-console/` deleted upstream); 13 new dev specs pulled (admin 219–224, Ravilo R246–R252, all `✓ Built`) + the stue-TV test-sweep report; **10 design files pulled BACK** (segments.js/.css for 222/223, wf.css, app.css, new `app/fonts/`, library.html, ravilo-app.js, ravilo.css); no design backlog from the specs; drew the Row Sorting directions canvas, **built direction 2 into the row editor** and wrote **225 / R253** (both `Planned`); next free **226 / R254**.
- 2026-09-16 (latest, same turn): no repo I/O — the picks were **built** into `Ravilo Mobile.html` (+ a new served `mobile/ravilo-mobile-player.css`), a new `Ravilo Receiver.html` was drawn, the Chromecast card was added to `app/settings.html`, the player + casting strings landed in `ravilo-i18n.js`, and **three specs were written: R244 · 218 · R245** (all `Planned`, none dev-reviewed). Next free **219 / R246**.
- 2026-09-16 (latest, same turn): no repo I/O — the round-1 picks were applied to both canvases and both print copies, playback **Speed removed from the design entirely** (owner decision), and the casting remote gained a **Subtitles &amp; audio** frame.
- 2026-09-16 (latest): pulled the mobile-player/casting **design brief** + two research reports + STATUS.md; confirmed 216 / 217 / R243 are canonical on `main` (next free **218 / R244**, and the research report's proposed ladder shifts by one); byte-compared the whole Ravilo design mirror — **nothing to pull, the repo has not edited our mockups**; found `design/claude-console/` still upstream and owed a deletion; drew the two round-1 direction canvases + print copies and added the iPhone frame.
- 2026-09-16 (later): no repo I/O — Towo deleted from the design set and `phase-217-remove-towo.md` written against the 2026-09-16 numbering scan (217 confirmed free).
- 2026-09-16: read-only numbering check — `main` still tops out at 215 / R242, so the Discover taxonomy pair was written as **216 / R243** (both `Planned`); mirror deliberately not refreshed.
- 2026-09-15: base commit unreachable, fell back to blob-size diffing; 15 new specs (admin 203–215, Ravilo R241/R242) + STATUS.md pulled; **12 design files pulled BACK over ours** (the mirror is two-way now — R242 was built into our mockups dev-side); our 202/R240 + the nine admin draws + the 187/R234 corrections all confirmed canonical upstream, so **nothing is pending export**; J flipped to default-on after invariant 11 was measured; new design backlog = 212/213/214/215; counters 387 → 404 phases, 179 → 194 documents.
- 2026-09-12: 19 new dev specs pulled (admin 188–201, Ravilo R235–R239) + 8 amended + STATUS.md; **no numbering collision — our 202 / R240 kept their numbers**; 187 + R234 came back `✓ Built` and their canonical specs replaced our drafts; nine design items logged; counters 359 → 387 phases, 158 → 179 documents.
- 2026-09-03: 31 commits pulled — 185/R222 shipped incl. on-device confirmation (R216 live on stue TV since 08-30, copy correct); 181/182/183 all built with real measurements, two new candidate follow-up bugs logged (home-feed cache thrashing, unbounded ffprobe fan-out); 7 new Ravilo specs (R223-R229), all backend/Compose-only.
- 2026-09-02: pulled the per-device decode-ceiling-warning research report; design pass (A+B′ recommended), no spec yet.
- 2026-09-01: mirror refresh; no repo changes since 08-31. Confirmed 184 / R221 uncontested.
- 2026-08-31: R218 + 180 shipped (both design-authored, on-device verified); pulled 5 new dev specs (R219, R220, 181, 182, 183) + amended 167 + STATUS.md. Three design items outstanding — see above.
- 2026-08-28 (#2): pulled phase-179; renumbered our teardown spec 179 → 180.
- 2026-08-28: pulled 177/178/R215–R217 + two research reports; design-authored R218 + 180.
- 2026-08-27: pulled admin 169–176 + Ravilo R208–R214 + 3 amended specs + STATUS.md; deleted the stale R196 episode-rail file; updated segments/library/media/builders mockups and the deck.
- 2026-08-21: read-only — `initial-idea.md` + repo tree stats for the deck; nothing new mirrored.
- 2026-08-20: pulled 26 new spec files (admin 164–168, Ravilo R196–R207) + STATUS.md + new `presentation/` directory (59 files, taking over).
- 2026-08-13: Towo/phase-162 shipped — pulled the canonical spec (3 dev-review addenda) + STATUS.md; no other repo-side spec changes.
- 2026-08-10 (#2): R195 shipped; pulled phase 161 + the Claude Code remote-agent research report.
- 2026-08-10: pulled R192/R193/R194 + 160; renumbered our subtitle-picker draft R192 → R195.
- 2026-08-07: confirmed R190 shipped; no other genuinely new content.
- 2026-08-02: pulled 155/156/R187/R188/R189; renumbered our people-filter draft to R190.
- 2026-07-31: re-pulled entire specs/ tree (68 files) + STATUS.md; repo well ahead of design mirror.
- 2026-07-13: pulled credits/intro research report; design-authored 150 + R182 (later shipped).
- 2026-07-13 (earlier): full specs/ + STATUS.md re-pull; only change was new R181 spec.
- 2026-07-12: re-pulled specs/ + STATUS.md mirror.
