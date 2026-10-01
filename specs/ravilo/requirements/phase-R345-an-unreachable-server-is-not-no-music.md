# Phase R345 — An unreachable server is not "no music"

> Found in R342's dev review (2026-10-01, item 2). Owner, 2026-10-01: spec it now, fix it later.

## Status

`Planned`. Written 2026-10-01 (dev-authored), against `main` `44e26871`. Number verified free (Ravilo specs top at
R344). Not built.

**Amends** nothing. It makes the code do what R321's FR-R321-2 already says: *"A mode stored for a viewer who lost the
grant falls back to video silently."* It does that only for a lost grant, and not when the server can't be reached.
**R342 depends on it:** FR-R342-4's fallback and acceptance 3.

## What happens

1. On every launch, and whenever the viewer changes, `RaviloApp` asks the server whether music is available.
   `RaviloApp.kt:944–947` calls `getAudiobooks()` and `getMusicHome()`, each inside
   `runCatching { … }.getOrDefault(false)`. Any exception counts as *no music*: no network, a timeout, a 5xx, or a
   server that is restarting.
2. `inMusic` (`RaviloApp.kt:949`) goes false, and `LaunchedEffect(musicAvailable)` (`:952`) resets the stack to Home.
3. So a viewer who left Ravilo in music mode, and opens it while the server is unreachable, gets films mode for the
   whole session. The question is not asked again when the server comes back; only a change of viewer asks it again.
4. The stored mode is left at `music`, so the next launch opens in music again (`:582–583`). The viewer sees music,
   then films, then music, depending only on the network.
5. It happens on the phone and on the desktop (R337 opened the listening mode there). The TV has no listening mode.

The opposite case is also wrong. When the server *does* say no, `:952` resets the stack but leaves `music` stored. The
next launch opens in music and steps back again, and R342's icon would flip twice per launch.

## Requirements

**FR-R345-1 — Three outcomes, not two.** The availability check returns `Boolean?`:
- **`false`, a definite no:** the server answered. `/api/tv/music/home` came back with no rows (`MusicTvService.kt:144`
  answers an empty `MusicHome` to a viewer with no music), and the audiobook shelf is absent or empty
  (`TvApiClient.kt:392–398`: a 404 is `null`, which also covers an old server). A 404 from `/tv/music/home` itself
  (a server from before 279) is a definite no too.
- **`null`, unknown:** either request threw for any other reason. `musicAvailable` stays `null`, which `:949` already
  treats as "assume yes". Ravilo stays in the stored mode, and the music pages show their own unreachable states.
- **`true`:** music or audiobooks answered with something, as today.

**FR-R345-2 — A definite no stores films.** On `false`, besides resetting the stack (`:952`),
`ListeningMode.write(VIDEO)` runs. The next launch then opens in films with no flip. Signing out already does this
(`MusicDeviceStore.kt:42–47`).

**FR-R345-3 — Unknown is asked again once the server answers.** While `musicAvailable` is `null` because of a failure,
the check runs again on the next successful request to the server. Hook it on whatever already notices a
reconnection; don't add a timer. It does not run on every request. If the late answer is a definite no, FR-R345-2
applies then.

**FR-R345-4 — Nothing else changes.**
- **Wire:** no route, no DTO and no new string.
- **Opening the right mode:** the first stack is still built from the stored mode (`:582–583`), so films never flash
  first.
- **The TV:** no change.

## Out of scope

- Caching "music is available" on the device. The server's answer stays the only source.
- Anything about R342's icon beyond following `inMusic`, which this phase makes stable.

## Acceptance

1. **Offline:** in music mode, with the server unreachable (phone in flight mode, or the Mac's network off), open
   Ravilo. It opens in music mode and stays there. The music pages show their unreachable states. The same on the
   Pixel 9 and on the Mac.
2. **The server comes back:** turn the network back on. Music loads without a relaunch.
3. **Access removed:** remove the viewer's music access and open Ravilo. It opens in music, steps back to films once,
   and the next launch opens straight in films.
4. **An old server** with no `/tv/music/**` routes: films, stored, with no flip on the next launch.
5. **A 5xx** from `/tv/music/home`: treated as unknown, so Ravilo stays in music.

## Dev notes

- The two calls are in one `LaunchedEffect(activeUserId, listeningLayout)` (`RaviloApp.kt:941–948`). Make them return a
  small result (answered-empty / answered-something / failed) rather than `Boolean`, and combine them:
  - either one answered with something: `true`;
  - both answered empty: `false`;
  - otherwise: `null`.
- `getMusicHome()` calls `assertSuccess()` (`TvApiClient.kt:300–304`). Tell its 404 apart from other failures where
  it is caught, without changing the client's signature for its other callers.
