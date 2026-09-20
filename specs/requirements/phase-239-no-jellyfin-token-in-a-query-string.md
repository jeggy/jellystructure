# Phase 239 — No Jellyfin token in a query string

## Status

`✓ Built` 2026-09-20 — written 2026-09-18, dev-reviewed 2026-09-19 against `main` `dcb97f2c`, built
2026-09-20. Client half: **R271**, built the same day. Policy: **243**.

### Build (2026-09-20)

**Both open questions were answered by measurement against the live household server** (12.1.0,
2026-09-20), not by argument.

**The spelling.** On `/Users`, which genuinely enforces (401 with no credential):
`?api_key=<valid>` → **401**; `?apikey=<valid>`, `?ApiKey=<valid>`, `?APIKEY=<valid>` → 200;
`?apikey=<bogus>` → 401. So `api_key` is not merely the old fashion — it does not authenticate.

**Open question 1 closes: the route is anonymous, and FR-239-2 is future-proofing with no present
effect.** `/Videos/{id}/stream` answered **206 `video/mp4` identically** with no credential, with a
bogus `apikey`, and with a valid one. That is the exact test the audit said it could not perform, and
it says the credential on that URL is doing nothing today. Worth doing anyway; not a fix. Acceptance 3
is therefore recorded as measured anonymity rather than claimed coverage.

**Review item 1's new open question closes too, and the answer is reassuring.** A real `PlaybackInfo`
on 12.1 templates `ApiKey=` into `TranscodingUrl` — the same parameter, matched case-insensitively.
Jellyfin is **not** handing out URLs its own server refuses to authenticate. A negotiated
`transcodingUrl` is passed through verbatim (**FR-239-5**, added from review item 1): its spelling is
Jellyfin's to get right, and nothing here may rewrite a server-generated URL. That also explains why
the pre-239 grep would have looked clean while every real transcoded play still carried a
Jellyfin-spelled token: the hand-built URL is the branch that runs *least* often.

- **FR-239-1** — `RaviloArtworkService`'s avatar fetch, `LiveTvService`'s channel-logo fetch and
  `warmSubtitleExtraction` now send a header. Review item 4: `jellyfinAuth` was widened to `internal`
  (phase 238 landed that; both phases needed it).
- **FR-239-3** — `withJellyfinToken(url, token)` in `auth/JellyfinClient.kt`, beside
  `jellyfinIdentityHeader`. Per review item 5 it owns the **separator** as well as the name: one call
  site was a multi-line concatenation whose last fragment began with a hand-written `&`.
- **Review item 3 recorded in the helper's own doc:** a query parameter carries the token and nothing
  else, so `Client`/`Device`/`Version` do not travel — meaning if Jellyfin ever enforces on these
  routes, the request authenticates while the session gets no client identity, which phase 110's
  dashboard name and R216's QoE both depend on. No alternative exists for a `<video>` element; the
  point is that "it authenticates now, we're fine" must not be the reading.
- **Open question 2 closes as no**, via 244's research: there is no server setting, and proxy-level
  authentication would break Ravilo playback.
- **Fence:** `scripts/check-jellyfin-query-token.sh` (in `ci / checks`) rejects any `api_key=` in a
  Jellyfin URL, any hand-written `apikey=` outside the helper, and R271's client-side half. Verified
  by reintroducing the old line and watching it fail.
- **Tests:** `WithJellyfinTokenTest` (4, green) pins the spelling and the separator.

Acceptance 2 and 5 (subtitles, artwork, Live TV logos, segment-editor playback, normal playback and a
**transcoded** play, all against 12.1) are verified by use on deploy.

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

## Dev review (2026-09-19, against `main` `dcb97f2c`)

The table is accurate: all eight sites exist at the cited lines, and `grep` finds no ninth Jellyfin one
(`RemoteRoutes.kt:202` reads `api_key` as a query parameter, but that is *jellystructure's own* API key
on the `/api/remote/events` handshake, correctly out of scope; `ravilo-ui/PlayerScreen.kt:868` is R271's).
The classification into FR-239-1 and FR-239-2 is right — `JellyfinClient.kt:1031` really is an ordinary
outbound fetch (`warmSubtitleExtraction` → `httpGet(url)`), and `:823`/`:487` really are handed to a
player. **One thing the phase does not currently reach changes what it can claim, and open question 1 is
already answered by the audit's own measurement.**

1. **The transcode URL is Jellyfin's, not ours, and this phase cannot respell it.** `PlaybackService.kt`
   has two branches. The direct-play URL at `:487` is ours. The transcode URL is
   `source.transcodingUrl` (`:484-486`), taken verbatim from Jellyfin's `PlaybackInfo` response, and
   Jellyfin templates its own credential parameter into that string. The same shape repeats in the
   burn-in path: `:892` prefers Jellyfin's `negotiated` URL and `:895-903` is only the **fallback** used
   when `PlaybackInfo` is unavailable — so the line the table cites is the branch that runs *least*
   often. Consequence: after this phase, acceptance 1's grep passes while the product still hands clients
   a Jellyfin-spelled token URL on every transcoded play, because we never wrote it. **Add a requirement
   and an open question.** The requirement: state explicitly that a negotiated `transcodingUrl` is passed
   through verbatim and its spelling is Jellyfin's to get right, so nobody later "fixes" it by rewriting a
   server-generated URL. The open question: **what does 12.1 actually template into `TranscodingUrl`** —
   if it is `api_key=` and 12.1 ignores `api_key=`, then Jellyfin is handing out a URL its own server
   will not authenticate, which is worth knowing and is not something this phase can fix. Measure it once
   from a real `PlaybackInfo` response; it is one field.
2. **Open question 1 is answered, by evidence already in this spec.** The *What is wrong* section records
   that `/Videos/{id}/stream` returns 206 with real `video/mp4` bytes when requested **with no credential
   at all**. That is exactly the test that distinguishes the two cases OQ1 says it could not distinguish:
   an enforcing route answers 401 to no-credential, an anonymous one answers 206. It answered 206.
   **The route is anonymous, and FR-239-2 is future-proofing with no present effect** — which is the
   honest framing OQ1 asked for, so write it into the build note now rather than leaving it open.
   Acceptance 3 follows: its second assertion (401/403 with an invalid `apikey`) **cannot be satisfied**,
   and its own escape clause is the outcome rather than the exception. Reword it as "record the measured
   anonymity per route", which is testable today.
3. **`apikey` is not equivalent to the header, and FR-239-2 should say what is lost.** A query parameter
   carries the token and nothing else. `Client`, `Device`, `DeviceId` and `Version` exist only in the
   `Authorization` header (`jellyfinIdentityHeader`, `JellyfinClient.kt:1243`). The existing URLs work
   around this by passing `DeviceId=` as a separate query parameter (`:487`, `SegmentRoutes.kt:451/463`),
   which those endpoints do read — but `Client`/`Device`/`Version` do not travel at all. So if Jellyfin
   ever does enforce on these routes, `apikey` alone authenticates the request while giving the session no
   client identity, and phase 110's dashboard name and R216's QoE attribution both depend on that
   identity. Not a reason to change FR-239-2 — there is no alternative for a `<video>` element — but state
   it, so the future "it authenticates now, we're fine" reading does not get made.
4. **FR-239-1 needs the same widening 238 does.** `jellyfinAuth` (`JellyfinClient.kt:1259`) is `private`.
   `JellyfinClient.kt:1031` is inside that file and can use it. The other two cannot:
   `RaviloArtworkService.kt:182` and `LiveTvService.kt:344` each hold their own `OutboundHttp.client`
   (`:48`, `:59`) in a different file. Both are one-line changes once `jellyfinAuth` is `internal` —
   `http.get(url) { jellyfinAuth(token) }` — and `JellyfinClient.kt:1031`'s `httpGet` already takes an
   `HttpRequestBuilder.() -> Unit` block (`:225`), so it needs no new plumbing either. **238's review asks
   for the same widening; do it once, in whichever phase lands first, and let the other cite it.** Both
   sites pass the *server* token, so `jellyfinAuth`'s `DeviceIdentityRegistry` lookup correctly resolves
   to the server identity and nothing else changes.
5. **FR-239-3's helper should own the separator, not just the parameter name.** "Returns the parameter
   fragment" leaves each of the five call sites writing its own `?` or `&`, which is the half of the
   problem that actually breaks (`:903` is a multi-line string concatenation whose first character is
   `&`). Make it append to a URL — `internal fun withJellyfinToken(url: String, token: String): String` —
   so the separator is decided in the same one place as the spelling. It belongs beside
   `jellyfinIdentityHeader` in `auth/JellyfinClient.kt`, which is already the "one place a Jellyfin
   credential becomes wire format" this phase is trying to restore.
6. **Open question 2 is answered by 244, which already looked.** Phase 244's research records Jellyfin's
   unauthenticated media routes as a finding it deliberately offers **no fix** for, because none exists,
   and explicitly warns that proxy-level authentication would break Ravilo playback. So there is no server
   setting to turn on; OQ2 closes as "no", with the cross-reference, and the operational item stays where
   244 put it.

**Acceptance.** 1 is sound as written (the surviving `api_key` hits — `@SerialName("api_key")`,
`api_key = "***"` in the TOML preview, `queryParameters["api_key"]` — none match the literal `api_key=`).
2 is the right shape. 3 becomes item 2's reworded version. 4 is unaffected. Add a fifth: **a transcoded
play still works**, since item 1 means that path is untouched by this phase and should be confirmed
untouched rather than assumed.
