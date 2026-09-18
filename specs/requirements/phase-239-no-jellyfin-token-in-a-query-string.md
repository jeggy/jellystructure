# Phase 239 — No Jellyfin token in a query string

## Status

`Planned` — written 2026-09-18 from a live audit of the household server on **12.1.0**, not
dev-reviewed, not built. Backend half. Pair: **R271** (the two URLs a Ravilo client builds itself)
and **238** (the socket, which is the same mistake but is actually broken today).

## What is wrong

Eight backend call sites put a Jellyfin access token in a URL query string as `api_key`:

| File | Lines | URL |
|---|---|---|
| `tv/PlaybackService.kt` | 487 | `/Videos/{id}/stream?Static=true&…&api_key=` |
| `tv/PlaybackService.kt` | 823 | `/Videos/{id}/{id}/Subtitles/{i}/0/Stream.vtt?api_key=` |
| `tv/PlaybackService.kt` | 903 | transcode/HLS URL, `&api_key=` |
| `server/routes/SegmentRoutes.kt` | 451, 463 | segment-editor stream URLs |
| `auth/JellyfinClient.kt` | 1031 | subtitle `Stream.vtt?api_key=` |
| `tv/RaviloArtworkService.kt` | 182 | `/UserImage?userId=…&api_key=` |
| `tv/LiveTvService.kt` | 344 | `/Items/{id}/Images/Primary?api_key=` |

On 12.1 the parameter name `api_key` is no longer honoured. Measured 2026-09-18 against the live
server:

```
/Items?…&api_key=<token>   -> 401 Unauthorized
/Items?…&apikey=<token>    -> 200 OK
```

**None of the eight are broken right now, and that is the whole problem.** Every route in the table
answers *anonymously* on 12.1. Requested with no credential at all, the image routes return 200 and
`/Videos/{id}/stream` returns 206 with real `video/mp4` bytes — verified with a fresh request, no
cookies, and a deliberately invalid token. The token in those URLs is being ignored, not honoured.
Playback, artwork and subtitles work by accident.

That is a bad place to sit. The product currently depends on Jellyfin *not* enforcing authentication
on its media routes. The moment a Jellyfin release enforces it, all eight fail at once, and they fail
in the playback path — the most visible surface there is. The audit found this class of failure
exactly once already, in phase **238**'s socket, and the only reason that one was noticed is that
`/socket` does enforce.

There is a second reason beyond the version risk. A token in a query string is written to proxy
access logs, browser history, and any intermediary that records URLs. A token in a header is not.
The codebase already has one canonical place where a Jellyfin credential becomes wire format
(`jellyfinAuth`, `JellyfinClient.kt:1259`); these eight bypass it.

## Requirements

**FR-239-1 — Server-issued requests send a header.** Every call the backend makes to Jellyfin on its
own behalf carries the credential through `jellyfinAuth`/`jellyfinIdentityHeader` and nothing in the
URL. This covers `RaviloArtworkService.kt:182`, `LiveTvService.kt:344` and `JellyfinClient.kt:1031`,
which are ordinary outbound fetches with no reason to use a query parameter.

**FR-239-2 — URLs handed to a client use `apikey`.** `PlaybackService.kt:487/823/903` and
`SegmentRoutes.kt:451/463` build URLs that are given to a player — a Ravilo client, or an admin
`<video>` element — which cannot attach a header. Those keep a query parameter and use the spelling
the current server honours, `apikey`. This is the one place a token may legitimately ride in a URL,
and it is a short list.

**FR-239-3 — One spelling, one place.** The `apikey` parameter is appended by a single shared helper
rather than string-concatenated at five call sites, so the next rename is one edit. The helper takes
the token and returns the parameter fragment; no call site writes the parameter name itself.

**FR-239-4 — No fallback.** Nothing sends both spellings, retries on a 401 with the other, or
branches on server version. Phase **243** states the supported floor; below it, behaviour is
undefined and we do not accommodate it.

## Non-goals

- The `/socket` handshake. That is phase **238**, which is a live outage rather than a latent one.
- `ravilo-ui` and `ravilo-tizen`'s own fallback URL construction. That is **R271**.
- Making Jellyfin enforce authentication on its media routes. We cannot, and the anonymity finding is
  recorded as an operational item in the research report rather than as work here.
- TMDB's `api_key` parameter, which is a different service and a correct spelling for it.

## Acceptance

1. `grep -rn "api_key=" src/` returns only TMDB call sites. Every Jellyfin one is gone.
2. Subtitles, artwork, Live TV channel logos, segment-editor playback and normal playback all still
   work against 12.1, verified by use rather than by unit test.
3. With Jellyfin's anonymous access hypothetically removed, the URLs in FR-239-2 still authenticate:
   confirmed today by asserting each returns 200/206 with `apikey` present and a *valid* token, and
   401/403 with `apikey` present and an *invalid* one. If the second assertion cannot be made because
   the route answers anonymously regardless, record that explicitly rather than claiming coverage.
4. `scripts/check-phases.sh` stays green.

## Open questions

1. Does `/Videos/{id}/stream` honour `apikey` at all, or is it simply anonymous and ignoring every
   credential? The audit could not distinguish these, because an anonymous route returns 200 for a
   valid token, an invalid token and no token alike. If it is purely anonymous, FR-239-2 is
   future-proofing with no present effect, which is still worth doing but should be stated honestly
   in the build note rather than described as a fix.
2. Is there a Jellyfin server setting that requires authentication on media routes? If one exists,
   turning it on is the real mitigation and would convert open question 1 into a test.
