# Jellystructure & Ravilo — presentation source material

**Purpose of this document:** raw material for a design tool to build a short talk about (a) what
this project is and (b) how spec-driven development is used to build it. Everything here is
factual and drawn from the live repository and a real running installation — no invented numbers.
Screenshots referenced by name live in `presentation/screenshots/`, inventoried at the end.

**Audience assumption:** colleagues who don't know the project. They care less about media servers
than about the *working method*. Lead with the method; use the product as the evidence that the
method holds up.

---

## Part 1 — What the project is

### The one-sentence version

**Jellystructure** organises a home media library and keeps its metadata correct; **Ravilo** is the
TV app people actually watch it with. One backend, one design system, four client targets.

### The longer version

A Jellyfin media server knows how to *stream* files. It is much weaker at knowing what those files
*are* — which language the audio is in, which subtitle track is the forced one, whether two files
are the same episode, what age rating "Från 15 år" corresponds to.

Jellystructure sits beside Jellyfin and owns that problem. It scans the library, resolves metadata
against TMDB, writes NFO files, manages artwork, normalises age ratings across a dozen national
certification systems, detects intro and credits segments by audio fingerprinting, and reconciles
all of it back into Jellyfin. It has a web admin frontend for doing this by hand when the automation
gets it wrong.

Ravilo is the consumer half — a TV app that reads Jellystructure's cleaned-up data rather than
Jellyfin's raw view. That distinction is the whole point: because the data is good, the UI can do
things a generic client can't. It can show a country flag beside a native language name instead of
a codec string. It can group four same-language subtitle tracks under one row and let you pick the
version. It can skip an intro because it knows where the intro is.

### The household this actually runs in

This is a real deployment, not a demo:

- **463 media items**, 170 of them series
- Content in **English, Danish and Faroese**, with the UI itself localised to all three
- **Curated channels** — Olivar, DanskTV, Barna Sjónvarp, Varpið, Apple TV+ — each a saved filter
  over the library, not a separate service
- **Live TV** with a real EPG woven into the same home screen
- **Multiple viewer profiles**, including a kids profile, with per-profile layout and maturity limits
- Running on **two Android TVs, a phone, and the web**

### Technology (for the one slide that needs it)

| | |
|---|---|
| Backend | Kotlin/Native (`linuxX64`), Ktor — compiles to a single native binary, no JVM |
| Admin frontend | Kotlin/WASM, DOM-based |
| TV / phone / web app | Compose Multiplatform |
| Additional target | Samsung Tizen |
| Scale | ~79,000 lines of Kotlin, 1,130 commits since 2026-06-11 |

The interesting constraint is that Kotlin/Native is a genuinely thin ecosystem. There is no
compression plugin for Ktor Native. Its socket layer uses `select()`, so file descriptor 1024 kills
the process. These aren't complaints — they're the reason the specs exist. Constraints you
rediscover by crashing are constraints you should have written down.

---

## Part 2 — Spec-driven development, as actually practised

### The shape of it

Every change gets a numbered phase and a spec file **before** any code is written. Not a ticket, not
a comment on a PR — a markdown document in the repository, versioned alongside the code it
describes.

```
specs/
  constitution.md              ← non-negotiable architecture. Wins on conflict.
  plan.md                      ← source layout, data models, API routes, WS protocol
  requirements/phase-NN-*.md   ← one file per admin phase        (41 files)
  ravilo/requirements/phase-R*.md ← one file per app phase        (42 files)
  research-reports/            ← dated deep-dives; research, not spec (15 files)
STATUS.md                      ← the single live status table (308 completed rows)
```

Phase numbers are **monotonic and never reused**. Admin phases are numeric, app phases are prefixed
`R`. At the time of writing the next free numbers are 167 and R203 — which means the numbering
itself is a project history you can read.

### The four rules that make it work

**1. The constitution wins on conflict.**
`specs/constitution.md` holds the things that are not up for renegotiation per-feature: the
authentication model, the language-resolution algorithm, NFO and artwork conventions, the
real-time UI contract, the subprocess hierarchy. When a phase spec and the constitution disagree,
the constitution is right and the phase spec is wrong. This is what stops 83 phase documents from
drifting into 83 different architectures.

**2. Bug fixes get specs too.**
This is the rule people push back on, and it's the one that pays. A fix to already-shipped
behaviour gets a dated spec amendment before the code, in the same format as any new feature. The
reasoning: a bug means the original spec was either wrong or silent. Fixing only the code leaves
the spec wrong, and the next person reads the spec.

**3. Status lives in exactly one place.**
`STATUS.md` at the repository root is the only source of truth for what's shipped. Spec files
describe *what a phase is*; they don't claim to know whether it's done. A checked-in script
(`scripts/check-phases.sh`) verifies the two never drift apart, and runs as a post-push gate.

**4. Design and code are two halves of one repository.**
The HTML/CSS mockups under `design/` are the *visual specification* for the real Compose and WASM
implementations. They're not throwaway comps. A second gate script
(`scripts/check-mobile-css.sh`) fences 18 specific CSS rules that a careless design re-sync had
silently reverted **eight separate times** before the check existed. The fix wasn't "be more
careful" — it was to make the mistake impossible.

### The worked example — use this as the talk's centrepiece

This happened on 2026-08-18 and is the best single illustration of why the method earns its cost.

> **The report.** "Why is there no Continue Watching on the Apple TV channel?"
>
> **The first answer — wrong.** I looked at the code and found this, and reported it as intended
> behaviour:
>
> ```kotlin
> RowKind.CONTINUE -> {
>     if (channelFilter != null) continue  // inherit-mode channels keep R59 behaviour (no Continue)
> ```
>
> A code comment, citing a specific phase number, explaining exactly why the row is absent. Case
> closed.
>
> **The pushback.** The user knew the product: *"It's in 'inherit' layout mode, which means it
> should get the same settings as the home screen has, and this page does have Continue Watching."*
>
> **What the spec actually said.** R59's own text: *"Same as Home / Custom switch (default
> inherit)… System rows still appear unless removed."* The opposite of what the comment claimed.
>
> **What `git log -S` found.** The skip predates R59 entirely. It comes from **R05**, the first
> channel implementation, written before inherit and custom modes existed at all — back when
> *every* channel skipped Continue Watching, correctly. R143 later preserved that line untouched
> while building real custom-mode row config, and labelled it "R59 behaviour" in a comment. It
> never was. It was a leftover from nearly 200 phases earlier that R59 should have retired and
> didn't.
>
> **The outcome.** Fixed as phase R202, with a spec, in production. And a second, more durable
> artefact: a written rule that a code comment claiming "by design per Phase NN" must be traced to
> phase NN's actual text before that claim is repeated to anyone.

**The point for the audience:** the specs didn't prevent the bug. They did something better — they
made it *arguable*. Without a written R59, "the comment says it's intentional" is unfalsifiable and
the bug lives forever. With one, a non-programmer could overrule the code by quoting the
specification. That's the entire value proposition in one story.

### What the method costs, honestly

Worth saying out loud; it makes the rest credible.

- Writing a spec for a two-line fix feels absurd in the moment. It stops feeling absurd the third
  time a spec catches a wrong assumption before it becomes code.
- Specs go stale. That's why `research-reports/` is explicitly labelled "research, not spec, may
  go stale" — the honest label is better than a doomed promise to maintain everything.
- Comments lie, as R202 proved. Specs lie less, because they're reviewed as documents rather than
  skimmed as decoration — but they aren't immune, which is why `STATUS.md` is separate and
  script-checked.

### Where the AI agent fits

Claude Code writes most of the implementation. The specs are what make that safe, and the causality
runs in a specific direction:

- A spec is a **reviewable artefact a human can veto before any code exists.** Reviewing a
  one-page document beats reviewing a 400-line diff.
- Phase numbers give the agent **stable, unambiguous references** across sessions. "Implement R202"
  is precise in a way "fix the channel bug" is not.
- The gate scripts are **guard rails that don't depend on anyone remembering.**
- And when the agent is confidently wrong — as in R202 — the spec is what the human uses to
  win the argument.

Same rules for the human and the machine. That's the design.

---

## Part 3 — Suggested talk structure

Roughly 10–12 minutes.

| # | Beat | Content | Screenshots |
|---|---|---|---|
| 1 | Hook | "A code comment told me a bug was a feature. The specification proved it wrong." | — |
| 2 | What it is | Jellystructure organises, Ravilo plays. 463 items, 3 languages, real household. | 01, 04, 51 |
| 3 | Why bother | Generic clients show codec strings. Good data buys good UI: flags, language names, skip-intro. | 26, 29, 12 |
| 4 | The method | Spec before code. Constitution wins. Bugs get specs. One status file. | file-tree slide |
| 5 | The story | R202 in full — the comment, the pushback, the `git log`, the fix. | 40, 41 |
| 6 | The cost | Honest downsides. Stale specs, overhead on small fixes. | — |
| 7 | The agent | Why specs are what make AI-written code reviewable. | — |
| 8 | Close | Specs didn't prevent the bug. They made it arguable. | 53 or 46 |

### Lines worth keeping verbatim

- "The specs didn't prevent the bug. They made it arguable."
- "A comment is an assertion. A spec is a commitment."
- "Phase numbers are monotonic and never reused — the numbering *is* the history."
- "The fix wasn't 'be more careful.' It was to make the mistake impossible."
- "Same rules for the human and the machine."

---

## Part 4 — Screenshot inventory

All shots are **1920×1080 PNG, captured over adb from a real Android TV** running the release
build against the live library. Real titles, real watch progress, real EPG data. Nothing staged.

Numbering has two small quirks, both harmless: there are two `28-` files (both useful), and `36`/`37`
are named for what they actually show after a navigation drift during capture.

### Home & navigation

| File | Shows | Good for |
|---|---|---|
| `01-home-hero-carousel.png` | Home hero, full-bleed backdrop | Opening slide, product identity |
| `02-home-hero-carousel-alt.png` | Hero, different title | Alternate opener |
| `03-home-hero-carousel-alt2.png` | Hero, third title | Alternate opener |
| `36-home-hero-two-and-a-half-men.png` | Hero + Collections rail entering frame | Shows hero and channels together |
| `04-home-collections-continue-watching.png` | Collections rail above Continue Watching | Home structure |
| `05-home-continue-watching-focused.png` | Continue Watching, card focused | D-pad focus treatment |
| `06/07/08-home-content-row*.png` | Configured content rows | Row variety |
| `52-home-genre-row-comedy.png` | Comedy row, watched ✓ badges visible | Watched-state design |

### Channels — the curated-library concept

| File | Shows | Good for |
|---|---|---|
| `32-home-collections-channel-rail.png` | Branded channel tiles: Olivar, dansktv, Barna, Varpið | **Strong slide** — channels as first-class |
| `51-home-channel-rail-appletv.png` | Same rail scrolled to Apple TV+ | Full channel set |
| `38-home-channel-rail-dansktv-focused.png` | dansktv focused **+ episode badges below** | **Best rail shot** — shows R199 live |
| `33-channel-olivar-page-hero.png` | Olivar channel, own hero | Channel identity |
| `34/35-channel-olivar-rows*.png` | Language-grouped rows: "Føroyskt", "Dansk" | Multilingual library |
| `40-channel-dansktv-hero.png` | DanskTV hero, `· DanskTV` scope chip in navbar | **R202 evidence** — the fixed bug |
| `41-channel-dansktv-continue-watching.png` | Channel's own Continue Watching + Newly Added | **R202 evidence** — the row that was missing |
| `42-channel-dansktv-genre-rows-posters.png` | Danish genre rows, "Soon · S01E06" upcoming badges | Upcoming-episode design |
| `43-channel-dansktv-documentary-row-watched-badge.png` | Documentary row, green ✓ watched badge | Watched state on posters |

### Browse, filter & grids

| File | Shows | Good for |
|---|---|---|
| `09-movies-grid.png` | Movies grid | Baseline browse |
| `10-movies-grid-facet-bar-focused.png` | Facet bar focused | Filter entry point |
| `55-series-grid-facet-bar.png` | Series grid, "170 titles", full facet bar | **Best facet shot** — shows the whole system |
| `11-movies-genre-facet-popover.png` | Genre checklist popover | Multi-select facets |
| `12-maturity-range-picker.png` | Maturity **range** picker over the normalised age ladder | **Strong slide** — the age-normalisation payoff |
| `13-audio-language-facet.png` | Filter by audio language | Multilingual angle |
| `14-sort-options.png` | Sort menu | Completeness |
| `15/16-series-grid*.png` | Series grid, tile focused | D-pad focus |
| `56-my-list.png` | My List, 8 titles | Personal collection |

### Detail pages

| File | Shows | Good for |
|---|---|---|
| `17-series-detail-klovn-hero.png` | Klovn detail, hero treatment | Detail layout |
| `37-series-detail-two-and-a-half-men-resume.png` | Clearlogo, flag audio/subtitle line, "153 of 261 episodes watched", Resume · S7E15 | **Best detail shot** — everything at once |
| `39-series-detail-teletubbies-clearlogo.png` | Clearlogo artwork treatment | Artwork pipeline |
| `31-series-detail-bluey-kids.png` | Kids content detail | Family/profile angle |
| `18/19-series-detail-season-picker*.png` | Season picker, and navigating it | The R201 fix in situ |
| `20-series-detail-episode-rail.png` | Episode rail with stills | Episode-level metadata |
| `21-series-detail-cast-crew.png` | Cast & crew faces | Rich metadata |
| `22-continue-watching-severance-focused.png` | Severance in Continue Watching | Pairs with 23 |
| `23-series-detail-severance-resume.png` | Severance detail with resume point | R198/R199 context |

### Player & the audio/subtitle picker — the technical showpiece

| File | Shows | Good for |
|---|---|---|
| `24-player-transport-chrome.png` | Player transport controls over video | Player design |
| `25-player-audio-subs-button-focused.png` | Audio & Subtitles button focused | Sets up the picker |
| `26-audio-subtitles-picker-level1-languages.png` | **Level 1** — one row per language, flag + native name | **Strong slide** — flag-forward design |
| `27-audio-subtitles-picker-subtitles-tab.png` | Subtitles tab | Tab structure |
| `28-subtitles-picker-language-list-full.png` | Full language list | Scale of real-world track data |
| `28-subtitles-picker-language-list-scrolled.png` | Same, scrolled | Length of the list |
| `29-audio-subtitles-picker-level2-versions.png` | **Level 2** — versions within one language, flag once in header | **Strong slide** — the R195 payoff |
| `30-player-episode-rail.png` | In-player episode rail | Binge navigation |

> **Talking point for 26 + 29:** 46.5% of movies and 47.5% of series in this library have at least
> one file with two or more subtitle tracks in the *same* language; the worst case is 32 unnamed
> tracks in a single file. A flat list is unusable at that scale. Two levels — languages first,
> versions inside, with a plain-language sentence per version instead of a raw track title — is
> what phase R195 specified and built.

### Discover & requests

| File | Shows | Good for |
|---|---|---|
| `44-discover-upcoming-calendar.png` | Upcoming calendar, date strip, "Missing (14)" | Release tracking |
| `45-discover-request-seerr-in-progress.png` | Request tab, "In queue" badge | Acquisition flow |
| `46-discover-seerr-popular-in-queue-in-library.png` | Popular row with "In queue" / "✓ In Library" state per tile | **Strong slide** — library state on discovery |

### Live TV

| File | Shows | Good for |
|---|---|---|
| `53-home-live-tv-on-now-row.png` | "On Now · 5 channels" row inside Home, real programme names and times | **Strong slide** — Live TV woven in, not bolted on |
| `54-live-tv-guide-epg.png` | Full EPG grid, channels × timeline, now-marker | Depth of the feature |

### System & settings

| File | Shows | Good for |
|---|---|---|
| `47-profile-menu.png` | Profile menu: Switch profile, My List, Settings, Add user, Sign out, Unpair this TV | Multi-user model |
| `48-settings-skins-language-playback.png` | Three skins (Aurora/Midnight/Noir), three languages, playback toggles | Theming + i18n |
| `49-search-with-keyboard.png` | Search with on-screen keyboard | TV input reality |
| `50-search-results.png` | Clean search results, "3 results" | Search quality |

### If you only use eight

`01` · `38` · `55` · `12` · `26` · `29` · `53` · `41`

Product identity → channels → filtering → age normalisation → the picker at both levels → Live TV
→ and the bug that the specification argument fixed.

---

## Appendix — a real spec, for the "what does one look like" slide

Opening of `specs/ravilo/requirements/phase-R202-inherit-channel-missing-continue-watching.md`,
lightly trimmed. Note that it records the wrong answer as well as the right one.

```markdown
# Phase R202 — Inherit-mode channels never show Continue Watching (bug fix, FR-RV-R2)

> Reported live: "why is there no continue watching on the Apple TV channel?" First answered
> (wrongly) as by-design behavior per a misleading code comment attributing the skip to R59.
> Corrected by the user: R59's own description is "Same as Home / Custom switch (default
> inherit)... System rows still appear unless removed" — inherit mode means a channel gets Home's
> own rows, Continue Watching included. Traced the actual code history: the skip predates R59
> entirely (it's from the very first channel implementation, R05, back when there was no
> inherit/custom distinction at all). R143 later preserved that skip unmodified while adding the
> real custom-mode system-row config, and mischaracterized it in a comment as "R59 behaviour" —
> it never was.

**Status:** Implemented. Verified via `compileKotlinLinuxX64`.

## Bug report
## Investigation
## Requirements
## Non-goals
## Verification
```

The header block is the important part. It is a written record of a wrong answer, who corrected
it, and what evidence settled it — which is precisely the thing that a commit message, a ticket,
or a code comment all failed to preserve.
