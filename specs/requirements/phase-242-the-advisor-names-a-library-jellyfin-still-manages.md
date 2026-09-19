# Phase 242 — The advisor names a library Jellyfin is still managing

> The whole premise of this product is that jellystructure owns metadata and Jellyfin reads what is
> on disk. Exactly one setting is checked against that premise today, on a page nobody opens.

## Status

`✓ Built` — written 2026-09-18 after a live audit of the household server on **12.1.0** found one
managed library with Jellyfin's NFO saver switched on and no surface reporting it, **dev-reviewed
2026-09-19 against `main` `dcb97f2c`** (see §Dev review at the foot: the new model fields must be
nullable or FR-242-3's checks fail open, and FR-242-7's cited precedent was drawn but never built),
**built 2026-09-19** on top of phase **246**, which landed the shared `AdvisorFinding` extension the
dev review asked for. Extends phase **212**'s advisor. Backend + admin UI.

**Every dev-review item is honoured in the build.** The new fields are nullable (item 2), including
`MetadataSavers`, which is re-declared on the same touch. `perLibraryFindings`' bare
`?: return emptyList()` (item 3) is gone: a managed library whose whole `LibraryOptions` object is
missing now emits a section carrying `options_unavailable`, and the frontend renders an explicit
"nothing was checked for this library" row — so FR-242-7 ships as a real surface, not only as a health
signal (item 4). `/health/full` and the advisor now read one resolver,
`JellyfinAdvisorService.metadataOwnershipFindings`, behind one gate,
`JellyfinAdvisorService.managedJellyfinIds`, which adopts the advisor's stricter form (item 1) — an
intentional behaviour change to the health endpoint. The model's provenance comment is re-stamped with
the 2026-09-19 date rather than extended (item 5). Open question 2 is closed as subsumed (item 6): with
`EnableInternetProviders: false` and empty `MetadataFetchers`, `Serier`'s `PreferredMetadataLanguage`
is inert by construction, and it could only become live at the moment FR-242-2 or FR-242-3 already
fires. Field names re-confirmed live against 12.1.0 on 2026-09-19, which also showed `MetadataSavers`
genuinely **absent** on two libraries and `[]` on two others — the exact distinction item 2 exists to
preserve.

**Acceptance 6 is a configuration action and is still outstanding**, deliberately: this phase reports,
the operator applies. `Musik`'s NFO saver is on as of 2026-09-19 and has been since March.

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

## Non-goals

- Changing any Jellyfin setting from jellystructure. The advisor is read-only and suggest-only, per
  phase 212, and that is not reopened here.
- A per-library override letting a household declare "Jellyfin owns this one". A skipped library
  already expresses that.
- `SimilarItemProviders`. It is new in 12.1 and defaulted to a local provider; it is recorded as an
  open question rather than guessed at.
- Jellyfin's network and exposure configuration. That is phase **244**.
- Jellyfin's unauthenticated media routes. Phase **244**, and upstream.
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

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced against `advisor/JellyfinAdvisorService.kt`, `auth/Models.kt`, `server/Server.kt` and the shipped
renderer in `ui/Settings.kt`. The diagnosis is right in every particular, and the scoping FR-242-1 asks
for turns out to be free. **Two things need correcting: the one modelling decision that decides whether
FR-242-7 can work at all, and a precedent FR-242-7 cites that was drawn but never built.**

1. **FR-242-1's scoping already exists — and `/health/full` uses a different one.** `computeFindings`
   already filters `cfg.libraries.filter { !it.skip && it.jellyfinId.isNotBlank() }` (`:67`) and skips any
   Jellyfin library not in that set (`:74`), so acceptance 3 (Blandet silent despite its NFO saver) holds
   structurally the moment the finding is added. But `/health/full` builds its own set with
   `filter { !it.skip }` and no `jellyfinId` condition (`Server.kt:473`). FR-242-6 says the two consumers
   must not be able to disagree; they already can. The shared resolver takes the advisor's stricter form,
   which is a small, intentional behaviour change to the health endpoint — worth stating so it is not
   discovered as a regression.
2. **Make the new fields nullable. This is the decision FR-242-7 stands or falls on, and the spec
   identifies the hazard without naming the fix.** Every field on `JellyfinLibraryOptions`
   (`Models.kt:85-95`) carries a value default, so under `ignoreUnknownKeys = true` a renamed key and a
   genuinely-unset one produce the identical value. For `MetadataSavers` that means a rename silently
   stops the NFO warning — which the spec says. For **FR-242-3 it is structurally worse**, because
   `emptyList()` is *also* the correct, healthy value for `MetadataFetchers`/`ImageFetchers`: those checks
   would fail open by construction, silently, forever, and no test that asserts "Film produces no finding"
   could tell the difference. Declare the fields FR-242-4 adds as **nullable with `= null`**
   (`List<String>? = null`, `List<JellyfinTypeOptions>? = null`), so *absent* is a third state the finding
   logic can branch on, and re-declare `MetadataSavers` the same way on the same touch. Phase 240's
   model-field guard catches this at build time; nullability is what catches it at run time, and FR-242-7
   asks for run time.
3. **`perLibraryFindings` already violates FR-242-7, at one line.** `val opts = lib.libraryOptions ?:
   return emptyList()` (`:98`) — a managed library whose entire `LibraryOptions` object is missing yields
   zero findings, no section, and therefore renders exactly like a library whose settings are perfect.
   FR-242-7 is not an additive requirement; it edits this line. Worth naming the line, because the
   requirement reads as though it only governs the new checks.
4. **The precedent FR-242-7 leans on exists in the design, not in the product.** FR-242-7 says "the
   advisor already has the vocabulary for this", citing 212's explicit `4K Movies` *no findings* row. The
   shipped renderer does the opposite and documents it: "*a library/section with no findings gets no
   card, no 'all clear' message — nothing at all*" (`Settings.kt:1251`), and it iterates only
   `result.perLibrary` (`:1275`), which the backend only populates when a library has at least one
   finding (`JellyfinAdvisorService.kt:77`). The explicit no-findings row lives in
   `design/app/settings.html` and was never built. Two consequences: FR-242-7 needs a **transport** for
   the state — an empty `findings` list is already how "fine" is expressed, so it wants a field on
   `LibraryAdvisorSection` such as `options_unavailable` — and it needs **frontend work**, which this
   spec currently reads as backend-only. Either build the design's row now or say plainly that FR-242-7
   ships as a health-endpoint signal only.
5. **Re-stamp the model's provenance, don't just extend it.** `Models.kt:87-88` reads "*Phase 212 — the
   flags FR-212-4's findings read. Confirmed live against 10.11.11 (GET /Library/VirtualFolders,
   2026-09-15)*". The 2026-09-18 audit re-confirmed all six names on 12.x, and FR-242-4 adds new fields
   with a fresh date. If the old comment is left alone the class carries two provenance claims and a
   reader will trust the older one for the older fields — precisely the drift FR-242-4's
   record-the-date rule exists to prevent.
6. **Open question 2 is answerable from the table already in this spec.** `Serier` has
   `EnableInternetProviders: false` and an empty `MetadataFetchers`, so there is no fetcher for
   `PreferredMetadataLanguage = fo` to steer: it is inert **by construction**, not by coincidence. It
   could only become live at the exact moment FR-242-2 or FR-242-3 fires on that library — which is when
   the advisor is already complaining about the real cause. So no separate consistency finding is needed;
   record it as subsumed by the two checks this phase adds, and close the question.
7. **Acceptance 6 is the only item in this whole cluster that is causing harm right now, and it should
   not wait for the phase.** Musik is a managed library with Jellyfin's NFO saver on, dated March —
   meaning Jellyfin has been rewriting jellystructure's NFO output there after every refresh for months.
   That is ongoing loss of this product's own work, entirely independent of whether 242 is ever built, and
   unchecking the box is a two-minute configuration change. Do it now; keep acceptance 6 as the
   verification (including the "were the NFO files overwritten in the meantime" half, which is the part
   that actually needs looking at).
8. **Open question 1 stands.** `SimilarItemProviders` cannot be resolved from this repository. When it is
   answered, the check it joins is FR-242-3 and item 2's nullability rule applies to it as well.
