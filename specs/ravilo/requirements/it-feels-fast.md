# Ravilo feels fast

Task: DEB-5839

> Owner, on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece, polish group E, on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

Client-only, in `:ravilo-ui` common code plus two platform `actual`s and Android's start-up profile. **No wire change, no
server change, no schema, no new string.** Old installed apps are untouched because nothing they read changes.

**Clients in:** Android TV, the Android phone, Ravilo Web, desktop (Mac and Linux). They share every screen named here.
**Clients out:** Samsung (`ravilo-screen`, its own screens; its gaps are DEB-5685's) and Chromecast (no title pages, no
Home). FR-6 is Android only.

**Touches:** R350 (amended in its own file, 2026-11-27: FR-1 lifts its "Back from a cast face" non-goal). FR-3 and FR-5
restore R212 (FR-RV-R212-1, a snapshot is per user). FR-6 restores R213's 2026-10-23 amendment (FR-RV-R213-4). FR-2 extends
R139 and R361 (the row you came from), which are unchanged. FR-4 keeps R100 (the backdrop is warmed on open) and R109 (the
hero is item 0).

**Neighbour:** [One look for waiting](one-look-for-waiting-and-for-nothing-here.md) (DEB-5837, polish group C) owns the
skeletons. Its FR-1 makes the shimmer draw without recomposing, which the investigation also listed under "feels fast",
so this spec does not repeat it. Its FR-3 gives the title page a skeleton in the page's shape. FR-4 here fills the top of
that skeleton from the tile and draws no skeleton of its own.

## Why

The investigation (DEB-5687, evidence §4 and §5) read these from the code on 2026-11-26. They were re-checked on
2026-11-27 against `921645877`:

- **Back to a film page lands at the top.** `MovieDetailScreen` keeps its `LazyListState` in the composable
  (`MovieDetailScreen.kt:184`), and only the top of the stack is composed. Coming back rebuilds the page and focuses
  Play (`:208-213`). The series page remembers only a return from the player (R350 FR-2, `SeriesDetailScreen.kt:912`,
  `:571-573`). Back from a cast face or a related title lands on Play there too (R350's non-goal).
- **Home's other rows snap back to their first tile.** Each row's scroll is `rememberLazyListState()` inside the row
  (`ContentRow.kt:196`). Only the row you came from is restored (R139).
- **With several profiles, Home can open on another profile's rows.** The seed is the cold-start profile's snapshot
  (`RaviloApp.kt:546`). Every Home store is handed it (`:2597`), whoever is signed in. The write-through then saves the
  shown feed under the new profile's id (`:2619-2636`), so the wrong rows can outlive the session.
- **A title page opens as a skeleton although the tile held its title and art.** `Dest.MovieDetail` and
  `Dest.SeriesDetail` carry only the id (`RaviloApp.kt:323-324`, `:1090-1091`), and a first load is
  `DetailLoadingShell` (`MovieDetailScreen.kt:136`, `SeriesDetailScreen.kt:157`).
- **Home's saved copy is encoded and written on the main thread** on every Home change
  (`RaviloApp.kt:2619-2636`, `HomeSnapshotCacheAndroid.kt:19-28`, `DesktopStores.kt:34-40`).
- **Android's start-up profile covers only server setup.** `ravilo-android/src/main/baseline-prof.txt` was last
  committed on 2026-09-02 and names no Home, row, tile, detail or player class. `scripts/check-baseline-profile.sh`,
  which R213 FR-RV-R213-4 requires, does not exist. The research report measured 63.9 % janky frames cold against
  0.2 % compiled.

**QoE and performance first.** Nothing here adds work to the playback start path or to TV focus motion. No FR fetches or
prewarms a detail on focus, decodes an image, or adds an animation. FR-5 takes work off the main thread, and FR-6 takes
JIT work out of a cold start.

## Requirements

### FR-1 — Back to a title page lands where you left it
- The film page and the series page each keep a return target in their store, which outlives the page.
- Opening a cast face, a related title or a genre chip records which control it was and the page's scroll (first
  visible item and its offset, the existing `ListScroll`). On the film page, Play, Resume and Start over record it too.
- Coming Back to the page puts it at that scroll and focuses that control (TV), or shows it at that scroll (phone,
  web, desktop pointer). It uses R350's scroll-then-focus sequence (`requestFocusRetrying`), never a bare
  `requestFocus`. R72's reframe does not run for that one focus.
- The target is read once. A later arrival focuses Play, as today. If the page no longer holds that control (the cast
  or related list changed), focus goes to Play at the top.
- The series page's existing targets (Play, Shuffle, an episode card) are unchanged.

### FR-2 — Home's rows keep their place on Back
- `HomeStore` keeps each row's horizontal scroll (first visible item and its offset), keyed by `Row.id`, while the store
  lives.
- Coming back to Home, every row opens at its kept scroll. The row you came from still restores to the tile you opened
  (R139/R361). That rule wins for that row.
- A row whose items changed opens at the kept item's key if the row still holds it, else at its start. It never opens at
  a stale index past its end.
- TV: no focus moves except the R139 restore. This is a scroll position on composition, not an animation.

### FR-3 — Home opens on this profile's own rows
- The Home store is seeded only from the snapshot of the profile that opens Home: `HomeSnapshotCache.load(userId)` for
  that profile's own id. That is the same synchronous read the cold start does today.
- A profile with no fresh snapshot of its own starts at `Loading`, as a first sign-in does (R212).
- The write-through saves a feed only under the profile whose fetch or snapshot it came from. A seed never crosses
  profiles.
- The kept Home store is keyed by the profile's user id, not its display name. Two profiles with the same name do not
  share one store.

### FR-4 — A title page opens on the tile's own title and art
- `Dest.MovieDetail` and `Dest.SeriesDetail` gain an optional `card: MediaCard? = null`. This is a client value, not a
  wire field. `openDetail` passes the tile's card. Other openers (deep links, Surprise me, Search) may pass none.
- On a first load with a card, the page draws the loaded page's own hero at its loaded size. It shows the card's
  backdrop (already warmed by R100), its title as text, and its year. Where the buttons, synopsis and rails will be, it
  shows DEB-5837's detail skeleton blocks.
- It draws no Play or Resume button, no progress, no watched mark and no badge from the card. The detail answer owns
  those (constitution: no derived catalog state). The card is shown verbatim and replaced the moment the detail lands,
  as R212's snapshot is.
- When the detail lands, the hero does not move or resize. Only the blocks below it fill in. A title logo may replace
  the title text in the same box.
- A failed load shows today's error surface. With no card, the page draws the detail skeleton alone, as today.
- On a TV, focus lands on Play once it exists, exactly as today. Nothing in the header is focusable.

### FR-5 — Home's saved copy is written off the main thread
- Android and desktop encode and write the snapshot on a background dispatcher. A newer save replaces a pending one
  (latest wins), and the write-then-rename stays atomic.
- Ravilo Web keeps its synchronous `localStorage` write. It has no second thread.
- The cold-start read stays synchronous (an asynchronous snapshot read is a non-starter: Home would draw a frame without
  it).

### FR-6 — Android's start-up profile covers Home, a row, a title page and the player
This restores R213's 2026-10-23 amendment. It is not a new promise.
- The profile is regenerated on a TV, signed in, through R213's journey. The journey reaches Home, scrolls a row, opens a
  title page and starts a play.
- `scripts/check-baseline-profile.sh` is added. It fails when the committed `baseline-prof.txt` names no class from
  `HomeScreen`, `ContentRow`, `Tile` or a detail screen. It goes green in the commit that lands the regenerated file.
- This needs a device. It waits behind DEB-4608 and is run by a QA seat. The engineer commits the file.

## Design

No round is needed. FR-4 reuses the loaded page's hero and DEB-5837's skeleton blocks. Every other FR changes where a
page opens, or what work happens off the main thread, and draws nothing new. If the owner wants the FR-4 header to look
different from the loaded hero, that belongs to DEB-5837's skeleton work, not this spec's.

## What this does not do yet

- Collection, browse and Search pages keep their own scroll rules (R295, R361). FR-2 covers Home only.
- Play's wait screen stays black with dots. Artwork on it is a design change (R290).
- No measurement of app start, Home time-to-content or jank. That is the measurement work in DEB-5687's "Ready for more
  viewers".
- Samsung's own detail and Home are not changed.
- Stores of visited pages are still never evicted (evidence §5.12).

## Acceptance

1. Film page: scroll to the cast, open a face, press Back. The page is at the same scroll, with focus on that face (TV)
   or the face on screen (phone). Repeat with a related title and a genre chip. A second arrival lands on Play.
2. Series page: the same three, plus R350's Play, Shuffle and episode-card returns unchanged.
3. Home on a TV: scroll rows 2 and 4 right, open a tile in row 3, press Back. Row 3 has focus on that tile. Rows 2 and 4
   are where they were.
4. Two profiles A and B, A last used. Cold start, pick B. Home never shows A's rows, and B's snapshot file never holds
   A's feed.
5. Open a title from a Home tile on a slow link. The first frame shows its backdrop and title. Nothing in the hero moves
   when the detail lands. No Play button shows until it does.
6. The snapshot write runs off the main thread on Android and desktop. Two quick saves leave the later one on disk.
7. A release Android TV build cold-starts with the regenerated profile, and `check-baseline-profile.sh` is green.

## Build order

One engineer, in this order. Each slice is about 200 changed lines or fewer.

1. **FR-3, the profile's own seed.** `RaviloApp.kt` (seed and write-through at `Dest.Home`, store key),
   `HomeStore.kt` if the seed's owner is carried. Test: `HomeSeedForProfileTest`
   (`JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*HomeSeedForProfileTest*' --console=plain`).
   **Junior-ready.**
2. **FR-5, the write off the main thread.** `HomeSnapshotCacheAndroid.kt`, `DesktopStores.kt`, the save call in
   `RaviloApp.kt`. Test: extend `HomeSnapshotCacheTest` (desktop: later save wins, write not on the caller's thread),
   `./gradlew :ravilo-ui:desktopTest --tests '*HomeSnapshotCache*'`. **Junior-ready.**
3. **FR-1a, the film page's return target.** `MovieDetailScreen.kt`, `DetailStore.kt`, a `DetailReturnTarget` next to
   `SeriesReturnTarget` in `SeriesEpisodes.kt`. Test: `MovieDetailFocusTest` (Robolectric, after `SeriesDetailFocusTest`),
   `./gradlew :ravilo-ui:testDebugUnitTest --tests '*MovieDetailFocusTest*'`. Not junior-ready: TV focus timing.
4. **FR-1b, the series page's cast, related and genre returns.** `SeriesDetailScreen.kt`, `SeriesEpisodes.kt`. Test:
   new walks in `SeriesDetailFocusTest`, same task. Not junior-ready.
5. **FR-2, the rows' kept scroll.** `HomeStore.kt`, `ContentRow.kt` (an initial scroll and a scroll callback),
   `HomeScreen.kt`. Test: `HomeRowScrollKeptTest` (desktop, compose UI test: scroll two rows, leave, return),
   `./gradlew :ravilo-ui:desktopTest --tests '*HomeRowScrollKept*'`. Not junior-ready: it touches the R139/R361 restore.
6. **FR-4a, the card on the destination.** `RaviloApp.kt` (`Dest`, `openDetail`), `DetailStore.kt` (a first-load state
   that carries the card). Test: `DetailOpensWithCardTest` (desktop, store level), `./gradlew :ravilo-ui:desktopTest
   --tests '*DetailOpensWithCard*'`. **Junior-ready.**
7. **FR-4b, the header drawn from the card.** After DEB-5837's detail-skeleton slice lands. `MovieDetailScreen.kt`,
   `SeriesDetailScreen.kt`, `Shimmer.kt` (blocks below the hero). Test: `DetailCardHeaderTest` (desktop, compose UI:
   title and backdrop on frame one, hero bounds equal before and after load, no Play node until loaded), same task
   pattern. Not junior-ready: layout parity on four form factors.
8. **FR-6, the regenerated profile and its guard.** `ravilo-android/src/main/baseline-prof.txt`, new
   `scripts/check-baseline-profile.sh`, `:ravilo-android-benchmark` journey if it stops short. Proof: the QA seat's
   generation run on a TV, then `scripts/check-baseline-profile.sh`. Needs a device; waits behind DEB-4608.
