# Phase R271 — A Ravilo client never builds a Jellyfin URL

## Status

`Planned` — written 2026-09-18 from the 12.1 upgrade audit, not dev-reviewed, not built. Client half
of **239**; the policy behind both is **243**.

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
