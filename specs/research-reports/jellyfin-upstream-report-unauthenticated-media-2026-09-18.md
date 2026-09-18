# Upstream report, ready to file — unauthenticated media streaming in Jellyfin 12.1.0

**Prepared 2026-09-18.** Everything below is reproduced on clean, isolated containers, not on a
production server. Related local audit: `jellyfin-12-1-upgrade-audit-2026-09-18.md`.

## Before filing, check this

Jellyfin already has history here and a duplicate helps nobody.

- **#1501** "Video streams completely unauthenticated", opened 2019-07-01, labelled `bug`,
  `confirmed`, `security`. Appears **closed**; the closure reason could not be read reliably
  (GitHub returned a partial page). **Read it first.**
- **#5415** "Collection of potential security issues in Jellyfin", `confirmed` + `security`, closed as
  a duplicate and split into per-controller issues. Frames these as needing breaking changes in a
  hypothetical 11.0+.
- **#13986** "All endpoints in AudioController are unauthenticated", carrying the source comment
  *"TODO: In order to authenticate this in the future, Dlna playback will require updating"*.

**If an open tracker exists, add the 12.1.0 evidence below as a comment instead of filing.** The one
genuinely new thing this report contributes is that **12.0 came and went** — the major version that
was supposed to be the breaking-change opportunity — and the behaviour is byte-for-byte unchanged.

The `VideosController` source comment in `HlsSegmentController` (*"Can't require authentication just
yet due to seeing some requests come from Chrome without full query string"*) suggests this is
**deliberate, for client compatibility**, rather than an oversight. The report is written to ask that
question directly rather than to assert a vulnerability.

---

## Title

Media and image endpoints remain fully unauthenticated in 12.1.0 — is this still intentional?

## Summary

`/Videos/{itemId}/stream`, `/Videos/{itemId}/stream.{container}` and `/Items/{itemId}/Images/Primary`
serve real media and artwork to a caller presenting **no credential at all**, from a **non-local IP**,
on a fully configured server. Knowledge of an item id is sufficient to download the file.

Item ids are not secret. They appear in client payloads, in image URLs, and are 32-character hex
strings derived deterministically from the library path — the same file produced the identical id on
two independently installed servers during this test.

`VideosController` carries no class-level or method-level `[Authorize]` attribute on either stream
endpoint. This matches the observed behaviour exactly, so this is an absence of authorization rather
than a bypass of it.

## Environment

| | |
|---|---|
| Versions tested | **12.1.0** and **10.11.11** |
| Install | Official Docker images, clean config, startup wizard completed via API |
| Library | One movie library, one 113 KB `.mp4`, no internet providers |
| Result | **Identical on both versions.** Not a regression. |

## Reproduction

Clean server, wizard completed, one movie library, no credential used anywhere below.

```bash
# 1. Start a clean server and complete setup (abbreviated; full script in the audit report)
docker run -d --name jf --restart=no -p 18096:8096 \
  -v "$PWD/cfg:/config" -v "$PWD/media:/media:ro" jellyfin/jellyfin:12.1

# 2. Resolve an item id — this step uses an admin token, and is the ONLY step that does
ID=$(curl -s "http://localhost:18096/Items?Recursive=true&IncludeItemTypes=Movie&Limit=1" \
     -H "Authorization: MediaBrowser Token=\"$ADMIN_TOKEN\"" | jq -r .Items[0].Id)

# 3. Now drop every credential. Present as a public internet client.
#    KnownProxies is set to the proxy, so X-Forwarded-For is honoured and the
#    caller is resolved as 8.8.8.8 — definitively NOT local access.

curl -i -r 0-99 -H "X-Forwarded-For: 8.8.8.8" \
  "http://localhost:18096/Videos/$ID/stream?Static=true&MediaSourceId=$ID"
# HTTP/1.1 206 Partial Content
# Content-Type: video/mp4          <-- real media bytes

curl -i -r 0-99 -H "X-Forwarded-For: 8.8.8.8" \
  "http://localhost:18096/Videos/$ID/stream.mp4?Static=true"
# HTTP/1.1 206 Partial Content
# Content-Type: video/mp4

curl -i -H "X-Forwarded-For: 8.8.8.8" \
  "http://localhost:18096/Items/$ID/Images/Primary"
# HTTP/1.1 200 OK
# Content-Type: image/jpeg
```

## Observed vs expected

Measured on 12.1.0 with `KnownProxies` set and the caller resolved as `8.8.8.8`, no credential:

| Request | Result |
|---|---|
| `GET /Videos/{id}/stream` | **206**, `video/mp4` |
| `GET /Videos/{id}/stream.mp4` | **206**, `video/mp4` |
| `GET /Items/{id}/Images/Primary` | **200**, `image/jpeg` |
| `GET /Items?Recursive=true&Limit=1` | 401 |
| `POST /System/Restart` | 401 |

The last two rows are the control, and they are the point. Authentication works correctly on this
server — the data route and the restart route both refuse the same anonymous non-local caller. Only
the media and image routes serve it.

The same matrix on **10.11.11** gives identical media results, so nothing changed in 12.x.

Credential state makes no difference on the media routes. No header, a syntactically valid but
invalid token, and a valid admin token all return 206.

## Impact

Anyone who can reach the server and holds or guesses an item id can download the file. For a
server exposed to the internet, which Jellyfin documents and supports, that is the whole library
given an id, with no rate limit and no audit trail tied to a user.

Because ids are deterministic per path rather than random, they are also more predictable than an
opaque token would be.

## The questions actually being asked

1. **Is this still intentional in 12.x?** The DLNA and Chrome-query-string constraints recorded in
   the source comments suggest it was a deliberate compatibility trade. If it still is, saying so
   plainly and documenting it would let operators reason about their exposure.
2. **If it is intentional, can it be opt-in?** A server-level "require authentication for media"
   setting, default off, would break no existing client and would let an internet-exposed
   installation close this without waiting for a major version.
3. **If it is not intentional, was 12.0 the intended fix window?** #5415 framed these as requiring
   breaking changes in a hypothetical 11.0+. 12.0 has shipped.

## What is explicitly not claimed

- No claim that this is a new vulnerability. It is long-standing, and the labels on #1501 show it has
  been recognised for years.
- No claim about `AudioController`, `HlsSegmentController` or `LiveTvController`, which #5415 and
  #13986 already cover and which were not tested here.
- No exploit, no enumeration technique and no timing beyond the reproduction above.

---

## Not filed: `POST /System/Restart`

A separate finding from the same audit is **deliberately not being reported**, because testing showed
it is working as designed and our own exposure was a configuration gap.

`POST /System/Restart` restarts the server with **no credential at all**, on both 10.11.11 and
12.1.0. That is `Policies.LocalAccessOrRequiresElevation` behaving as written: local callers are
allowed without authentication.

The exposure in our deployment was that Jellyfin sat behind a reverse proxy with an **empty
`KnownProxies`**, so every internet request arrived with the proxy's private IP and was classified as
local. Setting `KnownProxies` fixes it completely. Measured on 12.1.0:

| `KnownProxies` | Caller presented as | `POST /System/Restart` |
|---|---|---|
| empty | anything via the proxy | **204, server restarts** |
| set to the proxy | `8.8.8.8` | **401, refused** |
| set to the proxy | `192.168.1.50` | 204, restarts — by design |

The IP-spoofing variant of this is **CVE-2025-32012** (CVSS 7.5), patched in 10.10.7.

**Possible hardening suggestion, separate from the report above:** Jellyfin could warn an
administrator when `KnownProxies` is empty while requests carry `X-Forwarded-For` headers, since that
combination silently turns every remote caller into a local one and re-opens the CVE's impact without
any spoofing. That is an enhancement request, not a security ticket.
