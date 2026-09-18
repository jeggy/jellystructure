# Phase 242 — The advisor names a library Jellyfin is still managing

> The whole premise of this product is that jellystructure owns metadata and Jellyfin reads what is
> on disk. Exactly one setting is checked against that premise today, on a page nobody opens.

## Status

`Planned` — written 2026-09-18 after a live audit of the household server on **12.1.0** found one
managed library with Jellyfin's NFO saver switched on and no surface reporting it, not dev-reviewed,
not built. Extends phase **212**'s advisor. Backend + admin UI.

## What is wrong

**The one check that exists is in the wrong place.** `/health/full` reads each managed library's
`MetadataSavers` and fails a check when it contains `Nfo` (`server/Server.kt:476-483`), with good
copy:

> NFO Metadata Saver is ON — Jellyfin re-writes NFO files after every refresh, overwriting
> Jellystructure's metadata.

Phase 212 then built a whole Jellyfin settings advisor, in Settings → Libraries, per library, with
navigation paths and Jellyfin's own on-screen labels. It does not include this finding. The advisor
checks chapter images, trickplay, LUFS and the I/O scheduler — all performance — and says nothing
about metadata ownership. So the one finding that goes to the heart of what jellystructure is lives
on a health endpoint, and the page built to surface Jellyfin misconfiguration does not carry it.

**The other settings are not checked at all.** `JellyfinLibraryOptions` (`auth/Models.kt:85-95`)
carries six fields, five of them phase 212's performance flags plus `MetadataSavers`. Nothing reads
`EnableInternetProviders`, and nothing reads the per-type `MetadataFetchers` / `ImageFetchers`
arrays. Those are the settings that decide whether Jellyfin goes to TMDB itself. A household could
have internet providers enabled on a managed library and no part of this product would notice.

**It is not hypothetical.** Measured on the household server, 2026-09-18:

| Library | Managed | `MetadataSavers` | `EnableInternetProviders` | `MetadataFetchers` |
|---|---|---|---|---|
| Film | yes | empty | false | empty |
| Serier | yes | empty | false | empty |
| Musik | yes | **`["Nfo"]`** | false | empty |
| Blandet | no, skipped | `["Nfo"]` | false | empty |

**Musik** is a managed library with Jellyfin's NFO saver on. Jellyfin rewrites NFO files there after
every refresh. Its options file is dated March, so this predates the 12.1 upgrade and has been true,
unreported on any page, for months.

**The 12.1 upgrade is the reason this is worth doing now.** The audit confirmed all six field names
survive on 12.x, so the checks are not blind — but it also confirmed how they *would* go blind.
`OutboundHttp.kt:190` parses with `ignoreUnknownKeys = true`, so a renamed field becomes
`emptyList()` and the NFO warning silently stops firing, with no error and no log line. The upgrade
also rewrote Film's and Serier's option files and introduced a new key, `SimilarItemProviders`, which
defaulted to the local `Local Genre/Tag` provider. It happened to default to something local. Nothing
would have told us if it had not.

## Requirements

**FR-242-1 — Metadata ownership is an advisor finding.** The `MetadataSavers` contains `Nfo` case
becomes a per-library finding in phase 212's advisor, in its established shape: summary, current
value, cost here, navigation path, Jellyfin's exact on-screen field label, recommendation, trade-off.
It fires only for libraries jellystructure manages (`!skip` and a non-blank `jellyfinId`), because a
skipped library is Jellyfin's to manage and a finding there would be noise — `Blandet` in the table
above is exactly that case.

**FR-242-2 — Internet providers are checked.** A finding when a managed library has
`EnableInternetProviders` true. This is the master switch for Jellyfin fetching metadata itself and
is currently unread by any code in the repository.

**FR-242-3 — Per-type fetchers are checked.** A finding when a managed library's `TypeOptions` carry
a non-empty `MetadataFetchers`, naming the type and the fetchers. Image fetchers get the same
treatment with one carve-out: purely local extractors are not findings. On the household server
`Musik` lists `Embedded Image Extractor` and `Screen Grabber`, which read the file on disk and reach
no external service, and flagging them would be wrong. The carve-out is a named allow-list, not a
substring guess.

**FR-242-4 — The model carries what the findings read.** `JellyfinLibraryOptions` gains
`EnableInternetProviders` and a `TypeOptions` structure with `Type`, `MetadataFetchers` and
`ImageFetchers`. Every field name is confirmed against a live 12.x server before the code is written,
per this project's standing rule, and the verification date is recorded in the model's comment the
way phase 212's own fields already are.

**FR-242-5 — Silence where it is already right.** Phase 212's discipline holds exactly: a library
whose settings are correct produces nothing. Two of the owner's three original optimisations were
already correct, which is why 212 was built to be silent rather than to recite best practice. Film
and Serier must render no metadata findings at all.

**FR-242-6 — `/health/full` keeps its check.** The health endpoint's existing NFO check is not moved
or deleted. It is the machine-readable signal and it costs nothing; the advisor is the human one. The
two read the same field and must not be able to disagree — one resolver, both consumers, which is the
185/R222 discipline.

**FR-242-7 — A renamed field is loud, not silent.** Where a finding depends on a field being present,
absence of the whole `LibraryOptions` object or of the specific key is distinguishable from the key
being present and correct. The advisor already has the vocabulary for this: phase 212 renders `4K
Movies` as an explicit "no findings" because a skipped library's storage is unknown, and unknown
suppresses rather than guesses. The same rule applies here. Phase **240**'s model-field guard is the
other half of this and catches it at build time.

**FR-242-8 — An empty `KnownProxies` behind a reverse proxy is a server-wide finding.** Jellyfin's
`POST /System/Restart` carries `[Authorize(Policy = Policies.LocalAccessOrRequiresElevation)]`, so a
caller Jellyfin considers local may restart it **with no credential at all**. When Jellyfin sits
behind a reverse proxy and `KnownProxies` is empty, it ignores `X-Forwarded-For` and classifies every
request — including every internet request — by the proxy's own private address. Every remote caller
therefore becomes a local one, and the restart route is open to the internet.

Measured on a clean 12.1.0 instance, 2026-09-18:

| `KnownProxies` | Caller presented as | `POST /System/Restart`, no credential |
|---|---|---|
| empty | anything via the proxy | 204, server restarts |
| set to the proxy | `8.8.8.8` | 401, refused |
| set to the proxy | `192.168.1.50` | 204, restarts, by design |

This is the impact of **CVE-2025-32012** (CVSS 7.5, patched in 10.10.7) reached without any of the IP
spoofing that CVE describes. On the household server `KnownProxies` is empty and Jellyfin is behind
Caddy at a private address, so it is currently reachable.

The advisor fires a server-wide finding when `GET /System/Configuration/network` reports an empty
`KnownProxies` while `public_url` is set — the configuration that means this installation is reached
through something. The finding names the consequence in plain language, points at Dashboard →
Networking, and is not phrased as a performance trade, because it is not one.

This belongs in the advisor rather than anywhere else for the reason phase 212 exists: it is a
Jellyfin setting that an operator has to change in Jellyfin, which jellystructure can see and they
cannot easily. It is also the one finding here that is a security matter rather than a correctness
one, and should render first.

## Non-goals

- Changing any Jellyfin setting from jellystructure. The advisor is read-only and suggest-only, per
  phase 212, and that is not reopened here.
- A per-library override letting a household declare "Jellyfin owns this one". A skipped library
  already expresses that.
- `SimilarItemProviders`. It is new in 12.1 and defaulted to a local provider; it is recorded as an
  open question rather than guessed at.
- Changing Jellyfin's network configuration from jellystructure. FR-242-8 reports; the operator acts.
- Jellyfin's unauthenticated media routes. That is Jellyfin's own behaviour, long known upstream, and
  no setting exists to change it — see `jellyfin-upstream-report-unauthenticated-media-2026-09-18.md`.
- Fixing the household's Musik library. That is a configuration action, listed in acceptance so it is
  not forgotten, not code.

## Acceptance

1. With the household server as-is, the advisor shows exactly one metadata finding, on **Musik**,
   naming the NFO saver, with the navigation path to that library's metadata savers.
2. Film and Serier show no metadata findings.
3. Blandet, being skipped, shows no metadata findings despite also having the NFO saver on.
4. Unchecking Nfo on Musik makes the finding disappear on the next advisor refresh, and
   `/health/full`'s corresponding check passes in the same moment.
5. Enabling internet providers on a test library produces the FR-242-2 finding.
6. Musik's NFO saver is actually unchecked on the household server, and the NFO files jellystructure
   wrote for that library are verified not to have been overwritten in the meantime.
7. With `KnownProxies` empty, the FR-242-8 finding renders. With it set to Caddy's address, it
   disappears, and `POST /System/Restart` carrying a public `X-Forwarded-For` and no credential
   answers 401 instead of restarting the server.

## Open questions

1. What is `SimilarItemProviders`, new in 12.1, and can it reach an external service? It defaulted to
   `Local Genre/Tag` on Film and empty on Serier, so nothing is wrong today. If any of its values are
   network-backed it belongs in FR-242-3's checks; if they are all local it belongs nowhere.
2. `Serier` has `PreferredMetadataLanguage` set to `fo` while the others are empty. That is a Jellyfin
   preference on a library whose metadata Jellyfin does not fetch, so it should be inert. Worth
   confirming it is genuinely inert rather than a second path into TMDB, and worth deciding whether an
   inert-but-contradictory setting deserves a consistency finding like 212's trickplay pair.
3. ~~Does a music library need different treatment?~~ **Answered 2026-09-18.** `Musik` holds 22
   `MusicVideo` items and nothing else, and jellystructure tracks all 22 as `MUSIC_VIDEO`. It is an
   ordinary managed library and the NFO saver there is a genuine conflict, not a deliberate "Jellyfin
   owns music" setting.
