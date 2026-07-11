# Phase R179 — Ravilo: multi-episode file as one combined card (FR-RV-MEF1)

> The viewing side of **[Phase 149](../../requirements/phase-149-multi-episode-files.md)**. When several
> episodes live in **one file** (`S01E01E02E03…`), Ravilo's series detail must show them without gaps —
> today only the file's first episode appeared, so E02/E03 looked **missing**. The chosen model is a single
> **combined card** whose still is a **skewed triptych** so all three episode images show at once. Applies
> on **TV** and **phone**. Design mockups: **`design/ravilo/Ravilo TV.html`** (`ravilo-app.js` ·
> `ravilo-data.js` · `ravilo.css`) and **`design/ravilo/Ravilo Mobile.html`**; direction picked in
> **`design/Multi-Episode Files — Option B.html`**.

## Goal
On a series whose season is stored as multi-episode files (e.g. Johnny Bravo S1 — 24 episodes in 8 files),
the episode strip shows **one card per file**: a combined "Episodes X–Y" card whose artwork is a
three-panel **triptych** (each episode's still, split by two lightly-skewed seams, numbered), listing the
contained episodes and their runtimes. Nothing reads as missing. Single-file episodes render as normal
episode cards, unchanged.

## Current state (verified)
- Ravilo's series detail ~~(`renderDetail`)~~ **(dev review: that's the design mockup's JS function name,
  `design/ravilo/ravilo-app.js` — the real screen is `SeriesDetailScreen.kt`, `ravilo-ui/src/commonMain/...`)**
  lays out episodes as individual `EpisodeCard`s from the server-pushed `SeriesDetail`. A multi-episode file
  surfaced only its first episode → visible gaps.
- Phase 149 makes the server model the file as **N episodes** that each carry a shared `file`, a
  `partIndex`, optional `chapterStart`, and `hasChapters`. The frontend groups by that server-provided
  file/group id — it **never parses filenames** (constitution: the frontend renders server-pushed state only).
  **(Dev review: none of these fields exist on the TV DTO yet — `shared/.../tv/Models.kt`'s `Episode` today
  is just `id`/`episode_number`/`title`/`runtime`/`overview`/`still_url`/`playback`/`air_date`. This phase is
  hard-blocked on Phase 149 landing the server-side fields first; `ignoreUnknownKeys` is confirmed as the
  house norm — see addendum §1 — so adding them additively is safe once 149 ships.)**

## Requirements

### A. Grouping & the combined card (Option B)
1. In series detail, **group consecutive episodes that share a `file`** into one render unit. A group of
   >1 renders as a single **combined card**; a lone episode renders as today's `EpisodeCard`. **(Dev review:
   `EpisodeCard.kt:46-235` takes exactly one `Episode` and has no group concept — this needs a genuinely new
   composable, not a parameter tweak. See addendum §2.)**
2. The combined still is a **triptych**: the group's episode stills placed left→right, divided by **two
   seams tilted ~8°**, each panel bearing its episode number. Files with >3 episodes show the first three
   panels; the label still spans the full range.
3. Card content: an **"Episodes X–Y"** range label (on the still and as the title), the contained episodes
   listed with their runtimes, and a **"1 file · N episodes · <total>m"** line. Combined runtime = sum of
   the episodes.

### B. Playback
1. Selecting the combined card **plays the file**. With chapters (`hasChapters`), playback enters at the
   resume/next episode's `chapterStart`, and the hero **Play/Resume** + "Up next · E{n}" resolve at
   **episode granularity** (which chapter to resume). Without chapters, the file plays as **one continuous
   unit** from the start.
2. Continue Watching / Next-Up reference the specific episode (and chapter offset where supported), not the
   whole file, so resume lands in the right segment.

### C. Watched-state
1. **Aggregate on the card:** all-episodes-watched shows the watched treatment (dimmed still + ✓); a
   partially-watched group shows an in-progress bar. The card's **mark-watched toggles every episode in the
   file together** **(dev review: this is new — see addendum §3; no existing action marks more than one
   episode at once)**.
2. **Season progress counts individual episodes** (e.g. "1 of 24 watched"), not files — the season bar and
   season-picker fractions stay per-episode. With chapters, finishing a chapter marks that episode watched
   ~~(rides the R147/R176 watched-state propagation)~~ **(dev review: R147/R176 only fan out an
   *already-decided* per-item watched state to other open screens/devices — see addendum §3; the
   position-within-file → which-episode/chapter decision is new logic that doesn't exist anywhere today)**;
   without chapters, finishing the file marks the group.

### D. Surfaces
1. Apply on **TV** (`Ravilo TV.html` series detail) and **phone** (`Ravilo Mobile.html`). The phone detail
   gains an **Episodes** section (it had none) rendering the same combined triptych cards.
2. Focus/D-pad navigation treats the combined card as one card (play surface + watched toggle), so the
   season strip stays keyboard-navigable with the correct default focus on the resume item's group. **(Dev
   review: `SeriesDetailScreen.kt:231-232` currently resolves a single `resumeEpId` and passes it down as
   `isResumeEpisode` on one `EpisodeCard`. This needs updating so the resume episode's *group* — not just
   the episode — gets the default focus requester once combined cards exist.)**

### E. Data
1. The combined card is built entirely from server-pushed `SeriesDetail` — episodes carry `file`,
   `partIndex`, `partCount`, `chapterStart`, `hasChapters`. No filename logic on the client.

### F. i18n
1. New en/da/fo strings: "Episodes {a}–{b}", "{n} episodes · one file", and the runtime/label bits
   (`Strings.kt` + `design/ravilo/ravilo-i18n.js`). **(Dev review: the two files use *different* key-naming
   conventions — see addendum §4 for exact key names in each.)**

## Reuse
`EpisodeCard`/tile + focus-row model, the player chrome + resume pointer, watched-state propagation
(R147/R176), and the R33 live-config push so a server-side re-group reaches the TV without a restart.

## Non-goals
- **No client-side file splitting** — the client shows what the server groups.
- The triptych shows **up to three** stills (first three of a larger group + the full range label); no
  N-panel montage.
- **No per-chapter scrubber UI** beyond the normal player when chapters are present; no timeshift here.
- Behaviour for single-file episodes is unchanged.

## Acceptance
- A multi-episode series (Johnny Bravo S1 — 24 eps / 8 files) shows **8 combined triptych cards**; nothing
  reads as missing; each card lists its contained episodes.
- Selecting a card plays the file (entering at the resume chapter when present); **mark-watched toggles the
  file's episodes together**; the season reads **N of 24** watched with a per-episode bar.
- Works on **TV and phone**; the strip stays D-pad-navigable with default focus on the resume group.
- en/da/fo strings present; the client never inspects filenames.

## Dev-review addenda (2026-07-12) — reconciled with live code

1. **DTO fields don't exist yet — hard dependency on Phase 149.** Current `Episode` DTO
   (`shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:236-247`): `id`,
   `episode_number`, `title`, `runtime`, `overview`, `still_url`, `playback: PlaybackState?`, `air_date`.
   None of `file`/`partIndex`/`partCount`/`chapterStart`/`hasChapters` are present; `Season` (249-254) has
   no group/file concept either. `ignoreUnknownKeys` is confirmed as the universal convention (every
   `Json{}` block in the repo sets it, both client and server), so additive fields are safe exactly as the
   spec assumes — this phase just can't start until Phase 149 lands them.

2. **Combined card is new code.** `EpisodeCard(episode: Episode, focusRequester, isResumeEpisode: Boolean,
   onSelect, playstateOverride: CardPlayState?)` (`EpisodeCard.kt:46-235`) is a single fixed-width
   (`320.dp`) Box: 16:9 still with episode-number/runtime/UP-NEXT/watched-✓ overlays + a text area
   (title/air-date/overview), laid out in a `LazyRow` from `SeriesDetailScreen.kt`. Build the triptych card
   as a new composable rather than retrofitting group-awareness into `EpisodeCard` — keep single-episode
   rendering exactly as-is (per the spec's own Non-goals) and add a sibling component the strip picks
   between.

3. **Watched-state: R147/R176 is fan-out only, not the decision.** Toggle path today:
   `SeriesDetailScreen.kt:538` → `DetailStore.setEpisodePlayed()` (`DetailStore.kt:132`) →
   `apiClient.setPlayed(episodeId, played)` → `WatchedBus.publish(...)`. `WatchedBus`
   (`WatchedBus.kt:17-20`, a `MutableSharedFlow<Map<String, CardPlayState>>`) lets *other already-open*
   screens patch their tiles from a server-authoritative result with no re-fetch; R176 added the
   cross-device leg (a playstate change on one device pushes `playstate_changed` over `/api/tv/events` to
   every other signed-in device, republished onto the same local `WatchedBus` in `RaviloApp.kt:277`).
   Neither piece decides *which* episode a mid-file position belongs to, nor marks more than one episode id
   per action — both are new: (a) a "mark all episode ids in this group" action (client loop calling
   `setPlayed` per id, or a new batch endpoint), and (b) position→chapter→episode resolution during
   playback (needs the player to report position-within-file, checked against each episode's
   `chapterStart`/`chapterEnd`) — greenfield on both client and server.

4. **i18n key names differ by file — use these exact keys.** Kotlin `Strings.kt` uses dotted namespaced
   keys (e.g. `"action.mark_watched" to "Mark Watched"`, repeated verbatim per locale block for en/da/fo) —
   add `"up.episodes_range" to "Episodes {a}–{b}"` and `"up.episodes_one_file" to "{n} episodes · one
   file"` (plus da/fo translations) in that style. `design/ravilo/ravilo-i18n.js` uses flat snake_case keys
   per locale (e.g. `mark_watched: 'Mark Watched'`) — add `episodes_range` / `episodes_one_file` there,
   **not** the dotted form. The two files are not key-compatible; don't assume one string constant covers
   both.

Pairs with **[Phase 149](../../requirements/phase-149-multi-episode-files.md)** (also dev-reviewed
2026-07-12 — its addenda cover the server-side fields this phase depends on).
