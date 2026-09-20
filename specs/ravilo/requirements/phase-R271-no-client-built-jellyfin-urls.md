# Phase R271 — A Ravilo client never builds a Jellyfin URL

## Status

`✓ Built` 2026-09-20 — written 2026-09-18, dev-reviewed 2026-09-19 against `main` `dcb97f2c`, built
2026-09-20. Client half of **239**. Policy: **243**.

### Build (2026-09-20) — it was the deletion, not the refactor

**Open question 2 decided the shape of the phase, and it is answered by the code.** Both server-side
producers of a `StreamTicket` set `hls_url` **unconditionally** — `PlaybackService`'s direct-play /
`TranscodingUrl` branch and the burn-in restream — so the `hlsUrl`-null fallback at
`PlayerScreen.kt:868` was **dead code**. It never ran. And it was the only consumer of
`StreamTicket.accessToken` in the entire product.

So there is no FR-R271-1 (no new ticket field), no FR-R271-3 decision to make, and open question 1 is
moot: the two clients that could disagree about `static=true` are now one, because **R264 deleted
`ravilo-tizen`** — and `ravilo-screen`, its successor, composes no Jellyfin URL at all. The successor
to the module half this phase was written about was built clean.

- **FR-R271-2** — the fallback is gone. The null case is handled **once, in `PlayerStore`**, where the
  ticket arrives and R237's failure classification already lives, rather than in the screen: a ticket
  with no stream URL is a failed start and surfaces through R237's existing copy. The old line's
  "fallback" could only ever have produced a second, quieter failure anyway — it templated `api_key=`,
  which 12.1 answers 401 to.
- **FR-R271-4 — removed.** `access_token` is out of `StreamTicket`. No Ravilo client holds a raw
  Jellyfin access token for any purpose now. **Carve-out named:** Live TV's own `access_token`
  (`LiveTvModels.kt`) is a different field on a different model, not surveyed here, and stays.
  `PlaybackService`'s `tvTokenForClient` security note was updated — the token still rides the stream
  **URL**, so the 2026-08-02 H2 fix is unchanged by this; the DTO field was a second copy of the same
  credential.
- **FR-R271-5** — nothing branches on a Jellyfin version anywhere in a client; 243's
  `scripts/check-jellyfin-version-use.sh` allow-lists the backend files that may even name the symbol.
- **Fence:** `scripts/check-jellyfin-query-token.sh` covers the client half — no `/Videos/` literal in
  any Ravilo module, and `access_token` may not come back to `StreamTicket`. Verified by putting the
  field back and watching it fail.

⚠ **Rollout consequence, stated rather than discovered.** A client built before this change declares
`access_token` as required, so it cannot deserialize a ticket from a server that no longer sends one.
`ravilo-web` and `ravilo-cast` are served by the backend and move with it; an **already-installed
Android APK must be updated**. This belongs in the release notes.

Acceptance 2 (direct play against 12.1 for a title with no HLS URL) is unreachable as written — there
is no such title, which is the same finding as open question 2. Acceptance 3's "two clients" no longer
exist. Acceptance 1, 4 and 5 hold.

## What is wrong

Two Ravilo clients construct a Jellyfin URL themselves when the play ticket carries no `hlsUrl`:

```kotlin
// ravilo-ui/…/screens/PlayerScreen.kt:868
?: "${s.ticket.jellyfinBaseUrl}/Videos/${s.ticket.itemId}/stream.${s.ticket.container}?api_key=${s.ticket.accessToken}"

// ravilo-tizen/…/tizen/PlayerScreen.kt:60
?: "${ticket.jellyfinBaseUrl}/Videos/${ticket.itemId}/stream.${ticket.container}?static=true&api_key=${ticket.accessToken}"
```

Three things are wrong with that, in increasing order of importance.

**The parameter name is wrong for 12.x.** Jellyfin 12.1 does not honour `api_key`; the spelling it
accepts is `apikey`. These two lines happen to still work, because `/Videos/{id}/stream` answers
anonymously on 12.1 — measured with no credential at all, returning 206 and real `video/mp4` bytes.
The token in these URLs is ignored, not honoured.

**The two clients disagree.** One passes `static=true`, the other does not. Same ticket, same fallback
path, two different requests to Jellyfin. Nobody decided that; it is what happens when the same URL
is written twice.

**A client is deriving state the server already holds.** The constitution's rule is that a frontend
renders server-pushed state and derives nothing, and this project has spent real phases on it — 185
and R222 exist because one stored fact must have one representation. `PlaybackService` already builds
exactly this URL server-side (`PlaybackService.kt:487`). The ticket carries `jellyfinBaseUrl`,
`itemId`, `container` and `accessToken` so that two clients can each reassemble, slightly differently,
a string the server could have sent. That is the actual defect; the parameter name is a symptom of it.

It also means the ticket ships a raw Jellyfin access token to every client, for the sole purpose of
letting the client paste it into a URL.

## Requirements

**FR-R271-1 — The ticket carries the direct-play URL.** `StreamTicket` gains a resolved direct-play
URL beside `hlsUrl`, built by the same server-side code path that already builds one, with the
parameter spelling the current server honours. It is the one place that string is composed.

**FR-R271-2 — Clients select, never compose.** Both players pick between the fields the ticket
carries and build no URL. `ravilo-ui` `PlayerScreen.kt:868` and `ravilo-tizen` `PlayerScreen.kt:60`
become a selection, not a template. Neither file contains the substring `/Videos/` afterwards.

**FR-R271-3 — One decision about `static`.** Whether the direct-play URL carries `static=true` is
decided once, server-side, on the same evidence the R56 device profile negotiation already uses. The
two clients stop disagreeing because neither one is choosing any more.

**FR-R271-4 — The raw token leaves the ticket if nothing needs it.** Once no client composes a URL,
audit whether `accessToken` still has a consumer. If it does, it stays and this requirement is
recorded as "checked, still needed", with the consumer named. If it does not, it comes out of the
DTO, because a credential that travels to a client for no reason is a credential that can leak for no
reason.

**FR-R271-5 — No client-side version branching.** Per FR-243-4, no client inspects a Jellyfin version
or tries a second URL shape on failure. A failed direct play is a failure and surfaces through R237's
existing per-cause copy.

## Non-goals

- The backend's own eight `api_key` sites. Those are **239**.
- Changing playback behaviour, negotiation, or the HLS path. When `hlsUrl` is present nothing here
  applies.
- R245's Cast sender and the `ravilo-cast` receiver, which do not build Jellyfin URLs.
- Removing `jellyfinBaseUrl` from the ticket. Other things may use it; this phase does not survey
  them.

## Acceptance

1. `grep -rn "api_key" ravilo-ui ravilo-tizen` returns nothing.
2. Direct play works on the Android TV client and on the Tizen build, against 12.1, for a title with
   no HLS URL.
3. The two clients issue byte-identical stream URLs for the same ticket, verified by capturing the
   request each one makes.
4. Neither `PlayerScreen.kt` contains a Jellyfin path literal.
5. FR-R271-4 is answered in the build note either way, naming the consumer or recording the removal.

## Open questions

1. Does the Tizen build's AVPlay need `static=true` where ExoPlayer does not? If so FR-R271-3 is not a
   single decision and the ticket carries one URL per delivery method, which is still one composer and
   still satisfies the spirit of this phase. Confirm on the real RU7440 before collapsing them.
2. Is the `hlsUrl`-null fallback still reachable at all? If R183/R216's negotiation always yields an
   HLS URL in practice, these two lines are dead code and the phase is a deletion rather than a
   refactor. Worth measuring before building, because the answer changes the work substantially.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

**Half this phase's subject no longer exists.** `ravilo-tizen` was deleted by **R264** (built
2026-09-19, same commit that added `:ravilo-screen`): it has **zero tracked files**, only a stale
`build/` directory on disk, and `settings.gradle.kts:8` now reads
*"R264 — receiver-only TV app … supersedes R189/:ravilo-tizen"*. The second quoted line, its
`static=true`, and everything this spec says about two clients disagreeing went with it.

1. **One site remains, and the successor module did not inherit the defect.** A grep for `/Videos/`,
   `api_key` and `apikey` across `ravilo-ui`, `ravilo-screen`, `ravilo-cast` and `ravilo-receiver-core`
   returns **exactly one hit**: `ravilo-ui/…/screens/PlayerScreen.kt:868`, unchanged from the quote
   above. **`ravilo-screen` — R264's new Tizen receiver, which plays media over AVPlay — composes no
   Jellyfin URL at all.** That is worth recording: the successor to the module this phase was half
   written about was built clean, so the argument landed before the phase did.
2. **FR-R271-4 is answerable now, and the answer is "remove it".** `StreamTicket.accessToken`
   (`shared/…/tv/Models.kt:174`) has **exactly one consumer in the entire codebase — line 868 itself**,
   the line FR-R271-2 deletes. So the audit does not need to be deferred to a build note: after
   FR-R271-2, no Ravilo client holds a raw Jellyfin access token for any purpose, and the field comes
   out of the DTO. Record it as decided rather than as a question. **One carve-out:** the Live TV ticket
   carries its *own* `access_token` (`shared/…/tv/LiveTvModels.kt:97`), a different field on a different
   model that this phase does not survey — name it so it is neither removed by association nor assumed
   checked.
3. **Open question 2 is now the whole shape of the phase, not a detail.** With one site left and its
   only consumer being the token, the two outcomes are much further apart than when this was written.
   If the `hlsUrl`-null fallback is unreachable, this phase is **a deletion**: four lines and one DTO
   field, no FR-R271-1, no new ticket field, no FR-R271-3 decision to make. If it is reachable, it is
   the refactor as specified. Measure it first — the spec already says so, and it is now the only thing
   that decides what gets built.
4. **Three requirements and three acceptance criteria have lost their subject.** FR-R271-3's "the two
   clients stop disagreeing" is moot — there is one client, and the disagreement was resolved by
   deletion rather than by decision (though *whether* `static=true` belongs on the URL is still a real
   question if item 3 lands on "refactor", so keep the requirement and drop its justification).
   Acceptance 1's grep should drop `ravilo-tizen` and **add `ravilo-screen` and `ravilo-cast`**, which
   are clean today — turning it from a cleanup check into a regression guard on a module that plays
   media and could easily grow one. Acceptance 2's "and on the Tizen build" has no subject; its
   replacement, if any, is R264's receiver, which does not take this path. Acceptance 3 ("the two
   clients issue byte-identical stream URLs") is unsatisfiable and should go.
5. **Open question 1 closes by deletion.** "Does the Tizen build's AVPlay need `static=true` where
   ExoPlayer does not" has no Tizen build to ask about. If the question ever returns it returns for
   `ravilo-screen`, and only if that module ever takes a direct-play URL from a ticket — which today it
   does not.
6. **The argument in *What is wrong* survives all of this intact, and is the reason to still do it.**
   The parameter spelling and the two-client divergence were always symptoms; the defect is a client
   deriving state the server already holds, against the constitution's own rule and against
   `PlaybackService.kt:487`, which builds the same string. That is still true of line 868, and item 2
   means fixing it also takes a credential off the wire. Both remain good reasons whichever way item 3
   resolves.
