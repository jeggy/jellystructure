# Phase R279 — Every Ravilo string lives outside Kotlin, in one place

> Owner: *"Lets try to improve our translations. Currently we have faroese, english and Danish for
> ravilo. We want to extract these texts out, so all texts live outside of Kotlin code for all ravilo
> clients and Google Cast receiver and the tizen reciver. This way translation in the future becomes
> easier and also easier to add new languages."*

## Status

`✓ Built` — design-authored and built 2026-09-20. **Not dev-reviewed, and not yet run on a device.**

### Build (2026-09-20)

`i18n/{en,da,fo}.json` + `i18n/README.md` are the source of truth; `:ravilo-i18n`
(`androidTarget` · `linuxX64` · `wasmJs` · `js(IR)`) generates `RaviloStringsTable.kt` from them and
is consumed by `:ravilo-ui`, `:ravilo-receiver-core` (and through it both receivers) and the admin's
`wasmJsMain`. **387 keys × 3 languages.**

The extraction was verified by parsing the *old* `Strings.kt` and `ReceiverCore.kt` tables and
diffing them against the generated one: all 364 original keys survive byte for byte, the only
differences are FR-R279-9's four, and all 23 added keys are accounted for.

22 tests in `:ravilo-i18n`'s `commonTest`, run on `linuxX64`. The ladder tests name no shipped
language — adding or removing one must not make a test about *resolution* go red — so exactly one
test asserts which languages this build ships.

**Measured, and accepted:** the receivers now carry the *whole* table, not only the fourteen strings
they draw — dead-code elimination cannot drop it, because `t()` reaches `LOCALES` and the receivers
call `t()`. That is **46 KB raw / ~15 KB gzipped**, against a 243 KB (Chromecast) and 256 KB (Tizen)
gzipped bundle. The Tizen bundle is a local file inside a sideloaded `.wgt` and never crosses a
network at all; the Chromecast fetches its bundle from the household's own backend over the LAN,
once. Splitting the table per surface to save it would reintroduce exactly the two-tables-that-drift
problem this phase exists to remove, and would have to be maintained forever.

Two things worth remembering, both found the hard way:

- **Kotlin block comments nest.** `i18n/*.json` inside a KDoc opens a comment that never closes;
  the error points at the end of the file, not at the line.
- Acceptance 2 and 3 were run for real, not reasoned about: a language file was deleted and the
  build re-run, and a one-key `es.json` was added and the pickers checked.

## Context — today there are four places a string can hide

| Where | What | Who reads it |
|---|---|---|
| `ravilo-ui/.../i18n/Strings.kt` | 1157 lines; three hand-written `mapOf` blocks, **364 keys** each | Android TV, phone, `ravilo-web` |
| `ravilo-receiver-core/.../ReceiverCore.kt` | `object ReceiverStrings`; a **second** table, 14 keys × 3, its own `t(key, n)` | `ravilo-cast` (Chromecast), `ravilo-screen` (Tizen) |
| `cast-receiver/index.html`, `ravilo-screen/wgt/index.html` | English baked into the markup — `Ready to play from your phone`, `Loading…` | both receivers, before the bundle boots |
| ~20 call sites | Copy that never reached a table at all | whichever screen draws it |

And the list of supported languages is written out **twice more**, in
`SettingsScreen.kt:375` and the admin's `RaviloConfig.kt:2345`, as
`listOf("en" to "English", "da" to "Dansk", "fo" to "Føroyskt")`.

So adding a fourth language today means editing **five Kotlin files plus two HTML files**, and a
translator has to be given a Kotlin source tree and told which `mapOf` to touch. Two of those tables
have already drifted apart: seven of the receiver's fourteen keys are byte-identical duplicates of an
app key, and two more — `loading` and `nextep` — say the *same thing in different spellings*
(`Indlæser…` in the receiver, `Indlaeder...` in the app).

That last pair is not a one-off. A word-level audit of the shipped tables finds the same Danish and
Faroese words spelled two ways, one of them accent-stripped, across **82 Danish and 83 Faroese**
words — `loyniord` beside `loyniorð`, `Spael` beside `Spæl`, `sjonvarpinum` beside `sjónvarpinum`,
`Naeste` beside `Næste`. Nobody could see it, because nothing ever compared the three tables to each
other. **This phase does not rewrite that copy** (see FR-R279-9); it makes it visible and checkable.

## Goals

1. **One source of truth, outside Kotlin**, in a format a translator can be handed on its own.
2. **One table for every client** — Compose (Android TV · phone · web) and both plain-Kotlin/JS
   receivers (Chromecast · Tizen) — so a string is written once and a key cannot mean two things.
3. **Adding a language is adding one file.** No Kotlin edit, anywhere.
4. **No rendered text changes** in this phase, except where two tables already disagreed and one of
   them was plainly the mangled copy of the other.

## Non-goals

- **Rewriting the Danish and Faroese copy.** The audit is delivered; acting on it is its own phase,
  and the Faroese is the owner's own language to correct.
- **`design/ravilo/ravilo-i18n.js`.** The mockups keep their own table, in their own key style
  (`pl_subtitles` vs `pl.subtitles`). `design/` is a two-way Cosmos mirror that has overwritten repo
  files repeatedly; generating into it invites the next incident. Recorded as a known divergence.
- **Serving strings from the backend.** Tempting — a new language without a client release — but
  every receiver needs strings *before* it has a server (R269's own server-setup screen is the proof),
  so the baked-in table has to exist regardless. Build on this phase, not instead of it.
- **Language *names*** (`LanguageIdentity.kt`'s "Faroese" / "Føroyskt" for audio and subtitle tracks).
  That is a different table with a different job — naming a track's language, not the UI's.
- **Raw exception text** (`HomeStore`'s `"Unknown error"`, `PlayerStore`'s
  `"Failed to start playback"`). These are fallbacks for a server message, not designed copy.

## Functional requirements

### FR-R279-1 — the source of truth is `i18n/<code>.json`, one file per language

At the **repo root**, not inside a module: a translator receives `i18n/` and nothing else.

```
i18n/
  README.md      how to add a language, how the pipeline works
  en.json        the base language
  da.json
  fo.json
```

A file is a flat JSON object. A value is either the string itself, or an object carrying a
**translator note** beside it:

```json
{
  "_meta": { "code": "da", "name": "Dansk", "englishName": "Danish" },

  "nav.home": "Hjem",
  "cast.connecting": {
    "text": "Forbinder til {device}…",
    "note": "R245 — {device} is the TV's own name, as the viewer named it."
  }
}
```

Flat, because the keys are already dotted and a nested shape would make every diff a re-indent.
JSON, because it is the one format every translation tool reads and nothing in the toolchain has to
be taught. `_meta` is reserved and is the **only** key that may start with `_`.

Every `//` comment in today's `Strings.kt` and `ReceiverCore.kt` becomes the `note` on the key it
preceded — including the ones that say `draft Faroese, review before release`, which is exactly the
kind of thing a translator must be able to see.

### FR-R279-2 — `en` is the base, and the fallback

`en.json` defines the key set. A key absent from another language falls back to English at runtime,
as it does today.

### FR-R279-3 — a Gradle task generates the Kotlin table; the table is never committed

New module **`:ravilo-i18n`**, package `dev.jellystructure.ravilo.i18n`, targeting `androidTarget`,
`linuxX64`, `wasmJs` and `js(IR)` — the same four the existing `:shared` module carries, which is
every client plus the backend.

`generateRaviloStrings` reads `i18n/*.json` and writes a generated source file into the build
directory, wired in the same way `:shared` wires `generateBuildInfo` (R252): a `srcDir` on
`commonMain` plus an explicit `dependsOn` from every `compile*Kotlin*` task.

The generated file is **build output**: `.gitignore`d, never committed, never edited. Reading JSON
at runtime instead was rejected — resource loading differs on Kotlin/Native, Kotlin/JS, wasmJs and
Android, and a Tizen `.wgt` is a local file with no origin to fetch from.

### FR-R279-4 — the generator validates, and the build is honest about which failures are real

| Condition | Result |
|---|---|
| A key in a translation that `en.json` does not have | **Build fails** — always a typo |
| Placeholders differ from English (`{device}` vs `{enhed}`) | **Build fails** — always a bug, and one no test would catch |
| A key missing from a translation | **Warns**, names the keys, builds — English is rendered |
| `_meta` missing `code` / `name` / `englishName` | **Build fails** |
| A file's `_meta.code` disagreeing with its filename | **Build fails** |

The asymmetry is the point: a half-finished new language must be able to build, or nobody will start
one. A wrong key or a broken placeholder can never be intentional.

### FR-R279-5 — the generator also emits the list of languages, and the pickers read it

`SUPPORTED_LANGUAGES: List<RaviloLanguage>` (code · native name · English name) is generated from the
`_meta` blocks, base language first, then the rest by code.

Both hardcoded lists are deleted and read it instead: Ravilo's own **Settings → Interface language**
(`SettingsScreen.kt`) and the **admin config editor** (`src/wasmJsMain/.../RaviloConfig.kt`, which
gains `:ravilo-i18n` as a `wasmJsMain` dependency). After this, dropping `i18n/es.json` in makes
Spanish appear in both pickers with no Kotlin edit — which is the whole point of the phase.

### FR-R279-6 — `t()` and `str()` keep their signatures

`t(key, lang, vars)` moves to `:ravilo-i18n`. `ravilo-ui`'s `i18n` package keeps a forwarder of the
same name, so the **382 `str(...)` and 47 `t(...)` call sites do not move** and the diff stays
reviewable. `str()` and `WithLocale` stay in `ravilo-ui` — they are Compose, and `:ravilo-i18n` must
stay Compose-free for the receivers.

### FR-R279-7 — the two receivers read the same table as the app

`ReceiverStrings` keeps its object and its mutable `lang` (the receivers are not Compose and have no
`CompositionLocal`), but its private tables are deleted and it delegates to the shared `t()`.

The seven receiver keys that are **byte-identical in all three languages** to an app key are deleted
and their call sites use the app key:

| was | now |
|---|---|
| `ready` | `cast.ready` |
| `noserver` | `cast.no_server` |
| `noserver_s` | `cast.no_server_sub` |
| `busy` | `srv.busy` |
| `busy_s` | `srv.busy_sub` |
| `waiting` | `cast.waiting` |
| `nextep` | `player.up_next` — see FR-R279-9 |

The six with no app equivalent are namespaced: `receiver.starts_in`, `receiver.setup_title`,
`receiver.setup_hint`, `receiver.setup_connect`, `receiver.setup_trying`,
`receiver.setup_not_found`.

### FR-R279-8 — the receivers' HTML carries no English

`cast-receiver/index.html` and `ravilo-screen/wgt/index.html` hard-code
`Ready to play from your phone` and `Loading…` in the markup. Both elements are filled by the bundle
on every state change already, so the markup is emptied. A Danish household currently sees English
for the first frame; after this it sees the mark alone, then its own language.

### FR-R279-9 — exactly two strings change, and neither is invented

Where the app table and the receiver table say the same thing in two spellings, the receiver's is
kept, because it is the correctly-accented one and the app's is the accent-stripped copy:

| key | language | was | becomes |
|---|---|---|---|
| `loading` | da | `Indlaeder...` | `Indlæser…` |
| `loading` | fo | `Ledur inn...` | `Løðir…` |
| `player.up_next` | da | `NAESTE` | `NÆSTE` |
| `player.up_next` | fo | `NAESTA` | `NÆSTA` |

Both replacements are lifted verbatim from `ReceiverCore.kt`'s own shipped table. Nothing else in
any language is touched by this phase.

### FR-R279-10 — the copy that never reached a table, reaches it

Found by sweep, all user-facing, all currently English on every device:

- `RequestScreen.discoverStatusLabel` — the whole acquisition-status vocabulary:
  `acq.in_library`, `acq.requested`, `acq.in_queue`, `acq.in_queue_n`, `acq.fetching`,
  `acq.flag_stalled`, `acq.flag_starting`, `acq.importing`, `acq.failed`. The function takes a `lang`
  parameter; both call sites are composables that already have `LocalLang`.
- `DiscoverDetailScreen` — `discover.go_to_series`, `discover.retry_request`, `discover.working`,
  and `detail.kind_series` for the singular *Series* on the facts line (the existing `up.series` is
  the plural filter label: Danish *Serier*, not *Serie*). *Movie* reuses `up.movie`; the `Cast`
  heading reuses `up.cast`.
- `ServerSetupScreen` — `setup.server_label`, `setup.server_prompt`.
- `MultiEpisodeCard`'s `"UP NEXT"` reuses `player.up_next`.

`192.168.1.1:8097` (an example address), the brand words `Ravilo` and `IMDb`, `✓`, `·` and `★` stay
as they are: they are not prose.

⚠ **The first draft of this list was incomplete, and the incompleteness was the point.** It was
assembled by reading `grep` output, which is how the strings below survived the first pass and were
only caught when the sweep was redone properly (see FR-R279-14):

- **`AudioFlagStrip`'s `AUDIO` and `SUBTITLES`** headings — keys already existed (`fd.audio` /
  `fd.subs`, from the focus-detail line); the flag strip simply never used them.
- **`EpisodeCard`'s `UP NEXT`** — `MultiEpisodeCard`'s copy of the same badge was fixed in the first
  pass and this one, four files away, was not.
- **`Tile`'s `NEW` and `Soon • {label}` corner badges** → `tile.badge_new`, `tile.badge_soon`.
- **A series' `{watched} of {total} episodes watched`** → `detail.episodes_watched`.
- **`ImdbChip`'s `contentDescription`** → `imdb.a11y`. Read aloud, never drawn, so no sweep of
  `Text(` would ever have found it.
- **Twelve English month names and seven English weekday names**, in two separate private tables
  (`EpisodeCard`'s `EP_AIR_MONTHS`, `UpcomingScreen`'s `weekdayAbbrev`/`monthAbbrev`) → one shared
  `month.*` / `wd.abbr.*` set. **The entire Upcoming calendar rail was English in every language.**
- **Two date orders** → `date.month_day_year`, `date.weekday_day_month`. `Sep 22, 2003` is how
  English writes a date; the order belongs to the translator, not to a `String` template.
- **The Request tab's empty state** → `request.empty`.
- **A film's `Resume · {n} min left`** → `action.resume_mins_left`.
- **`ServerSetupScreen`'s `Connect` button** → `receiver.setup_connect`, the string the Tizen
  receiver's own setup screen already draws. Found only by FR-R279-14's checker, after two manual
  passes had missed it.
- **The Chromecast's CAF subtitle-track name fallback** → `pl.subtitles`.
- **The web app's `⛶ Fullscreen` shell button** — see FR-R279-13.

Danish and Faroese for the new keys ship as **drafts carrying a `note` that says so**, per the
convention the existing table already uses for R244/R245.

### FR-R279-11 — the drift audit runs in the build

`checkRaviloStrings` compares every language against itself and reports, without failing:

- the same word spelled two ways within one language, once diacritics and `æ/ø/å/ð` are folded
  (`loyniord` / `loyniorð`);
- a translation that is byte-identical to English (untranslated, or correctly identical — a human
  decides which);
- `...` where the base language uses `…`.

It writes `build/reports/ravilo-i18n/drift.txt`. It never fails the build, because none of these is
provably an error — but none of them was findable at all before this phase.

### FR-R279-12 — one ladder decides which language a client draws in

Added after the first draft, on the owner's direction: *"the cast receiver should also respect the
language configured for that specific user. And when no user is logged in then it should use the
language from the last session, and if no last session then fallback to English."*

```
the signed-in viewer's configured language     RaviloConfig.uiLanguage
  ↓ nobody is signed in, or it names a language this build has no strings for
the language this device last drew in          remembered locally; survives sign-out
  ↓ this device has never drawn one
English                                        BASE_LANGUAGE
```

`resolveLanguage(configured, lastSession)` in `:ravilo-i18n` is the whole of it, and is pure so it
can be tested once instead of four times badly. It **normalises** what it is handed — case,
`_` vs `-`, and a region subtag (`fo-FO` → `fo`) — so a caller may pass a stale config field, a
`localStorage` string written by an older build, or a TV's reported locale without checking it
first. Blank and unknown codes are *skipped*, not adopted.

The middle rung is new, and is most of the phase's visible effect. Every surface drawn **before a
user is known** was English regardless of the household: the server-setup screen, the login screen,
the profile picker, the whole of a cold start before `getConfig()` returns, a Chromecast sitting
idle between casts, and a Tizen set showing its pairing code. For a Faroese or Danish house that is
much of what they see before they have done anything.

`LastLanguage` holds it, behind a `LastLanguageStore` each client binds to storage it already has:
SharedPreferences (`ravilo_locale`, deliberately **not** `ravilo_sessions`, which a sign-out clears)
on Android, `localStorage["ravilo.lang"]` in the web app and in both receivers. A code the build has
no strings for is never remembered — a bad value must not outlive the session that produced it.

#### FR-R279-12a — a Chromecast draws in the language of whoever cast to it

The hand-off payload's `lang` is the **casting viewer's** own `uiLanguage`, read off their config by
the sender. It is the only thing on that device that knows which of a household's viewers pressed
play, so the receiver adopts it first and the config it fetches for itself only as a fallback.

That order is a fix, not a preference. The receiver set `lang = data.lang` and then **overwrote** it
with `config?.uiLanguage`, where `config` is fetched once and cached for the life of the receiver —
so a household's second viewer casting to the same Chromecast was drawn in the **first** viewer's
language, indefinitely.

#### FR-R279-12b — a Tizen set draws in the language of the viewer it is acting for

`:ravilo-screen` holds a token per `(device, user)`, so "the user" changes: whoever last sent it
something to play is who it is acting for. Its config was fetched exactly once, on the **first**
pairing — the same bug in a different shape. It now re-reads on every change of the active viewer,
and re-renders idle if the language changed, since idle is long-lived enough to sit in the wrong
language until something else redraws it.

#### FR-R279-12c — R269's rung is kept, below the remembered one

R269 FR-R269-9 gave the Tizen server-setup screen the **TV's own reported language**, because a set
out of its box has no server to ask and nothing to remember. That rung stays, ordered *after* the
remembered language: a set that has been used before knows more about the household than its factory
locale does. Only a set with nothing remembered can reach it — which is the only situation the setup
screen it was written for occurs in.

### FR-R279-13 — the web app's pre-boot shell has strings too

`ravilo-web`'s `index.html` draws a fullscreen button before the Wasm bundle exists, so it cannot
call `t()`. `generateRaviloStrings` also emits `ravilo-shell-strings.js` — the two keys that shell
needs, from the same `i18n` files — and `boot.js` picks a language out of it using the same
`localStorage["ravilo.lang"]` that FR-R279-12 writes. Generated, never committed: a hand-written
copy in the shell is exactly the drift this phase exists to remove.

### FR-R279-14 — a check that fails when viewer-facing text is written into Kotlin

`scripts/check-ravilo-strings.sh`. Thirteen strings had never reached a table, none was visible to
any test, and two manual sweeps each missed some — the second one found what the first had not, and
the checker then found one the second had not. A rule nothing enforces is a rule that decays, and
this one decayed before the phase was even finished.

It reads only the positions that actually paint text — `Text(…)`, `RaviloButton(…)` and the
`label` / `title` / `placeholder` / `contentDescription` family — and fails on a literal in one. It
does **not** try to judge prose anywhere else: a check that guesses is a check people switch off.
Its allow-list is short and each entry carries its reason (the brand words, the `OK` key cap, a
number with an SI unit, Compose's `animate*AsState(label = …)` debug names).

It needs a real Kotlin string reader rather than a regex, because `Text("▷ ${str("action.play")}")`
has a quote inside an interpolation and a regex stops at it — which is how the first version of the
check reported ten false positives and one real miss in the same run.

## Acceptance

1. `i18n/en.json` has every key `Strings.kt` had, with the same text, byte for byte.
2. Deleting `da.json` and rebuilding produces a working app in English, and Danish is gone from both
   language pickers — no Kotlin edit.
3. Adding `i18n/es.json` containing only `_meta` and `nav.home` builds with a warning naming the
   missing keys, and Spanish appears in both pickers.
4. A key in `da.json` that `en.json` lacks fails the build and is named.
5. `"Playing on {device}"` translated with `{enhed}` fails the build and is named.
6. The Chromecast receiver and the Tizen receiver render Danish idle/busy/no-server/next-up copy
   identical to today's, from the shared table.
7. `grep -rn 'mapOf(' ravilo-ui/.../i18n/` finds nothing.
8. The first frame of either receiver, before its bundle boots, contains no English sentence.
9. A viewer whose language is Faroese casts, the cast ends: the Chromecast's idle screen is Faroese,
   and still is after the receiver is power-cycled.
10. A second viewer whose language is Danish casts to that same Chromecast: it is Danish
    immediately, not after a re-pair.
11. Sign out of the phone app: the login screen is in the language that was just being used, not
    English.
12. A device that has never drawn anything, with nobody signed in, is English.
13. `scripts/check-ravilo-strings.sh` passes, and fails when a `Text("…")` literal is planted.
14. The Upcoming calendar's day rail, and an episode's air date, are in the viewer's language.

## Found and deliberately not fixed

Two things this phase's sweep turned up that are real, and are not translation problems:

- **The player draws `DIRECT PLAY` / `HLS`** (`PlayerScreen`'s `StreamPill`, unconditionally, in the
  top chrome). It is untranslated English — and **R180 FR-RV-ASP1-2 says a delivery cue must not be
  drawn at all**. Translating it would be doing the wrong work carefully. It is allow-listed in the
  checker with that reasoning written down, so the next person meets the argument rather than the
  string.
- **Seven screens render a store's raw error sentence** (`Text(s.message)` in `ChannelScreen`,
  `DiscoverDetailScreen`, `SettingsScreen`, `LiveTvGuideScreen`, `UpcomingDetailScreen`,
  `SeededBrowseScreen`, `BrowseScreen`; `HomeScreen` draws it under a translated headline). That
  message is either a raw exception's text — Ktor's, in English, usually technical — or one of a
  dozen hardcoded English fallbacks (`"Unknown error"`, `"Couldn't load channels"`, `"Channel not
  found"`, `"Failed to load settings"`, `"Not found"`, `"Failed to restream"`, …).

  This cannot be fixed by extraction: the stores are plain classes with no language, so a store must
  carry a **cause** and let the screen say the sentence. **R237 already did exactly this** for
  playback — `PlayerErrorKind` plus the `error.play.*` keys — so the pattern exists and the work is
  applying it to the other nine stores. Its own phase.

- **Language and region names** (`LanguageIdentity`'s `LANGUAGE_TABLE`, `PlayerScreen`'s
  `REGION_MARKERS`) are untouched, and mostly correctly so: what the viewer sees is the **endonym**
  (`Dansk`, `Føroyskt`, `中文`), which is right in every interface language by R180's flag-forward
  rule. The eleven region qualifiers (`Latin American`, `Brazilian`, `Simplified`, …) genuinely are
  English and genuinely are drawn; they belong with that table, not this one.
