# Round 11 rev 2 — what a Coming Soon stack looks like (`coming-soon-stack-shape`)

Drawn in this repository. There is no design tool in the loop in either direction, and no
`DesignSync` call was made — Claude Design was retired on 2026-10-22 and this round is the lane as
`design/PROCESS.md` now describes it. Brief and fixed decisions are Bárður's, from the owner's live
answers. Reported on the round's own task.

**This round allocates no number, names no task id in any drawing, and writes no spec.**

**Drawn with `design/SKILL-house.md` and `design/PROCESS.md`.** The `frontend-design` skill is
selected on this seat and was read under them, as the house file requires; what it contributed is
noted at the end, and it changed no identity, type or token.

## What this round answers

The owner saw round 11's A (stacked edge), B (wide list) and C (count only) and picked none of them
for the shape: *"Lets ask help from our design team to come up with something"*. He fixed the other
four questions. So this round asks one thing — **what should a season drop or a double-bill look
like, so it reads at a glance from 3 m on a TV as "this series is arriving with N episodes"?** —
and answers it with four directions, each different in kind from A, B and C.

The directions document is **`Coming Soon Stack Shape - Directions.html`**; it is the thing to open
first. **A is drawn once**, as the comparison frame, exactly as the owner already saw it.

## Directions, and the recommendation

| Handle (character for character) | |
|---|---|
| `D — Season band` | **recommended** |
| `E — Episode rail` | drawn |
| `F — Focus reveal` | drawn; see the caveat below |
| `G — Tall season row` | drawn; priced |
| `A — Stacked edge` | round 11's, comparison frame only |

**Why D.** At three metres a viewer reads size and contrast, not detail. The question is about a
glance, so the answer is a numeral big enough to survive the distance on a surface flat enough to
guarantee it: `8` at 60 px on a solid band, with the plural beside it and the earliest air time at
the band's right end. It costs the row nothing — same 430 px, same 242 px of art, same footprint
focused and unfocused, in every row.

**What D costs, stated plainly:** 86 px — just over a third — of the only picture the card has. The
calendar holds one image per series and no episode stills, so that picture is already carrying less
than it looks like it is; but it is a real trade and `tv-d-today` is the frame to judge it on.
**If the owner's answer is that the artwork may not be covered, G is the direction**, and a
permanently 79 px taller row is its price.

**A fifth direction, named rather than built:** D's band has room along its lower edge for E's rail,
which would give D the per-episode mixed answer at no further cost. Combining two directions the
owner is being asked to choose between is not mine to do, so it is written down here instead.

## The findings

Seven, in the order they would cost someone else time. The first three are Bárður's to route.

1. **F is direction C until you focus it, and that is a verdict rather than a flaw to fix.**
   `tv-f-today` and round 11's `tv-c-today` are the same card. "Reveal on focus" was a seed in the
   brief and it is drawn honestly, but a direction whose resting state is the one the owner has
   already declined cannot be the answer to a question about a glance. It is in the set because the
   brief asked for it and because it is genuinely the cheapest thing a calm row could do — not
   because it competes.

2. **G's price is not "a taller row", it is "a taller row only where the data has a stack".** A row
   with a stack is 79 px taller than a row without one, so the page's vertical rhythm becomes
   data-dependent — roughly one fewer day row per screenful on a day that has a drop. That is the
   jumpiness R367 removed, arriving through a different door: not movement under focus, but a layout
   whose shape depends on what landed that day. It is drawn and priced rather than quietly avoided,
   because the brief relaxed the footprint constraint and asked for exactly this accounting.

3. **The per-episode-mark directions have a hard ceiling at 12 and the brief's 24-episode frame is
   where it was measured.** Above 12 the segments are thinner than the gaps between them and at
   three metres read as a solid bar regardless, so past that the rail becomes one proportional bar
   with k of n filled and the count badge carries the number. `tv-e-24` and `tv-g-24` are that rule
   rendered. **This is a spec sentence, not an implementation detail** — and it also exposes E's
   real limit: at 24 episodes E's rail says nothing at all, and the whole answer falls back to a
   26 px badge.

4. **One stand-in fact this round adds, and flags.** Round 11's set has no stack larger than eight,
   and the brief asks for a 24-episode drop. Rather than invent a seventh title — the brief says not
   to swap names mid-revision — the 24 episodes are *Tidewater Station* S03E01–E24 on Tuesday 27 Oct
   at 04:00, on a day of its own that appears in no other frame. Round 11's two flagged choices
   (*Heron Vale* Sunday 06:00, *Ferrow Bay* Monday 22:00) are carried forward unchanged.

5. **The phone has no card component at all, and this round had to declare one.** The brief fixes
   the phone as the same card, and that is right about the Compose app — but `ravilo-phone.css`
   defines none of the `up-*` card classes. They live in `ravilo.css`, which the phone frames
   deliberately do not load, because the two token files name their tokens the same way and loading
   both lets one answer for the other. So roughly fifty lines of
   `coming-soon-stack-shape.css` are the card component at phone scale, written against the phone's
   own tokens. Every px in it is this round's invention and a spec written from the phone frames
   would inherit all of it. **Round 11's finding 5 stands as a fact about the mockup**
   (`ravilo-phone.js` renders Coming Soon as `.t10row` list rows); it is the mockup that disagrees
   with the app, not this round.

6. **Two phone token gaps, unchanged from round 11 and still worth fixing in the phone's set.**
   `ravilo-phone.css` defines `--good` but **no `--warn`**, so the phone has no token for "mixed";
   and it defines `--ring` / `--ring-glow` only inside the `daylight` block. Every rule in this
   round that uses either carries a fallback for that reason. The phone also still has no real noir
   or midnight — they override `--bg-rgb` and nothing else — so the three TV skins the brief asks
   for are covered on the **TV**, where they are defined.

7. **In noir the accent *is* amber, which is the same hue as `--warn`.** The mixed pill therefore
   cannot lean on colour there, and does not: the words carry the state, as the house rule requires.
   `tv-d-noir-mixed` is the frame that proves it.

**Also, and smaller:** the day-header `{n} releases` line round 11 drew is **gone from this round
entirely**, per the brief, and there is no key for it in this round's string table. The round uses
`.up-src.cs-kind` in place of the shipped source-chip class, identical in paint, so that no source
name appears in any file of this round — not even as a CSS class.

## What looking at the renders found, which no fence can

Seven defects went into renders of this round and were taken out again. Listing them because it is
the lesson that keeps paying.

- **The band was a hole, not a surface.** It was `rgba(6,8,14,.90)` — *darker than the page itself*
  — so the card's bottom third read as a gap punched through the card and the card's silhouette at
  three metres shrank to just its artwork. This is round 11's "offset layers in the card greys that
  vanished into the page", in a new costume. The band is `var(--card-2)` now.
- **…and the same hard-coded value was a skin leak.** `rgba(6,8,14,…)` is a *cool* near-black; noir's
  page is a *warm* `#080807`. At magnification the band was visibly the wrong temperature in the one
  skin this system's leaks always show up in. A token fixes both defects with one value.
- **The phone had no artwork at all.** `.up-art .grad` is positioned in `ravilo.css`, which the phone
  frames do not load, so every poster rendered as an empty grey box with initials floating in it.
  The rule is declared in the round's own CSS now. **Only opening the phone render found this** — the
  TV and desktop frames were correct throughout.
- **A downloading segment had two different "not here yet" colours side by side.** The trough was an
  amber tint at .26 which over teal artwork read olive, beside a scheduled segment's neutral grey —
  two colours, one meaning, touching each other. The amber belongs to the fill only.
- **F's reveal repeated the card's own subtitle.** `S02E01–E08` in the panel, forty pixels above
  `S02E01–E08` under the title. Deleted rather than restyled.
- **D's band went hollow on a Missing stack.** No air time means no right-hand anchor, and two
  thirds of a bar that is anchored at both ends in every other state read as something that failed to
  load. The status pill takes the slot, which also clears the lone pill off the top of the artwork.
- **"EPISODES" broke in half.** At `.07em` the S-O-D run opened a gap wide enough to read as a word
  break. Danish *AFSNIT* and Faroese *PARTAR* never showed it — which is exactly why the English
  frame had to be looked at on its own rather than trusted because its siblings were fine. `.05em`
  now.

## States drawn, and states skipped

Named, because partial coverage is a reportable fact and not a quiet omission.

| House state | This round |
|---|---|
| 10-foot focus | **drawn**, every direction, `tv-*-focus` |
| Longest string | **drawn**, `tv-d-da-longest` (*4 af 6 tilgængelige*) |
| Danish and Faroese | **drawn**, `tv-d-da`, `tv-d-fo` |
| Skins | **drawn** on the TV: aurora, midnight, noir |
| The round's own states | **drawn**: single, two, eight, 24, mixed, missing, not-held, film |
| Loading · Error | **skipped** — round 11 drew these on the detail the stack opens, and the owner fixed that the detail frames stand. Nothing here changes them. |
| Offline | **skipped** — the offline strip sits above the rows and is unaffected by the card's shape. Round 11's `tv-a-offline` still describes it. |
| Empty | **skipped** — the shipped strip is built only from days that carry a release and jumps to the next day with content, so an empty day never appears. Round 11's finding 3, unchanged. |
| Desktop compact window | **skipped** — the brief asks for wide and compact; compact is the phone layout, and `ph-*-today` is that layout. No separate frame was drawn and that is a gap, not a decision. |

## The frames — 38 PNGs

| Surface | Size | Count |
|---|---|---|
| TV, the 1920×1080 stage | 1920×1080 | 30 |
| Phone, Pixel 9 portrait | 412×915 | 4 |
| Desktop, wide | 1440×900 | 4 |

8 015 361 bytes in total; the largest single frame is 289 725 B. **The 192 KiB-per-PNG rule does not
apply in this lane** — that was the `get_file` truncation wall on the retired pull, and there is no
transport here.

## The sources

| File | Bytes | Lines |
|---|---|---|
| `Coming Soon Stack Shape - Directions.html` | 15 160 | 268 |
| `coming-soon-stack-shape.css` | 27 758 | 400 |
| `cs2-kit.js` | 24 879 | 460 |
| `cs2-data.js` | 11 504 | 210 |
| `frames.html` | 5 016 | 119 |
| `render.mjs` | 4 954 | 114 |

**Every count, range, time and badge in every picture is derived from one array.** `cs2-data.js`
holds the stand-in set as episode-level rows; `cs2-kit.js` groups them and builds the markup; the
three surfaces call the *same* `card()` and differ only in the wrapper class they sit in. So "do
these 38 pictures agree with each other?" is something a reviewer can check rather than trust — and
it is the honest model of the surface, because the client renders server-pushed state and invents no
catalog state of its own.

Classes with no prefix are the **shipped** Ravilo classes; everything this round invents is prefixed
`cs-`. Nothing is copied: `frames.html` links `../../ravilo/ravilo.css` and
`../../ravilo/mobile/ravilo-phone.css` directly, and the fonts resolve to `../../app/fonts/`.

## The exact render command

`scripts/render-design.sh` (DEB-2125, the engineer's) is **not merged** as of this round, so
`PROCESS.md`'s command was used, run from the repo root:

```sh
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  node design/incoming/2026-10-22-coming-soon-stack-shape/render.mjs
```

Everything the earlier rounds recorded about it held again: `--user` keeps the PNGs out of root's
hands in a shared checkout, the image pin and the lockfile are different versions on purpose, and
Playwright is resolved **by path** because ESM resolves from the importing file's directory and
ignores `NODE_PATH`. Chromium still has no Faroese locale, so weekday and month names are tabled per
language in `cs2-data.js` rather than taken from `toLocaleDateString`.

## The four fences, on the staged directory

| Fence | Result |
|---|---|
| `check-deanonymization.sh` | OK |
| `check-css-scoping.sh` | OK |
| `check-mobile-css.sh` | OK |
| `check-phases.sh` | OK |

Run against the **staged** tree, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked one.

**Nothing named `STATUS.md` or `CLAUDE.md` was created, and nothing under `specs/` or `scripts/`.**
This commit touches `design/` only. No network reference of any kind is in any file: the three faces
are the repo's own woff2 and there is no CDN, no `@import url(http…)` and no `<script src="http`.
No IP literal, no hostname, and no `passkey` / `announce` / `api_key` / `token=`. Every title,
network and person is the brief's stand-in set — *Tidewater Station*, *Saltmarsh Rangers*,
*Rook & Ember*, *Heron Vale*, *Ferrow Bay*, *Glass Orchard*, *Brightwave+*, *Channel North*,
*Northlight* — and nothing names what the household owns.

## What `frontend-design` contributed

Read under `design/SKILL-house.md`, which wins. It changed no identity, no typeface and no token —
all of those are fixed here. What it was useful for: *spend your boldness in one place*, which is
the whole argument for D (one numeral at display size, and every other element on the card stepping
back so nothing competes with it — the band is neutral ink, not a coloured event ribbon); and
*critique your own render instead of shipping it*, which is the discipline the seven defects above
were found by. Nothing it advised was overruled, because nothing it advised reached the identity.

## What is still open

- **The shape.** The owner's pick between D, E, F and G — and, if D, whether covering 86 px of the
  artwork is acceptable. If it is not, G is the answer and the taller row is the price.
- **The fifth direction** (D's band carrying E's rail), named above and deliberately not drawn.
- **One new string**, `up.episodes_word`, whose Danish and Faroese are unreviewed drafts — and the
  bare-plural fallback rule that goes with it.
- **The two phone token gaps** (finding 6) and the Danish `up.episode` / `up.episodes_count`
  disagreement, which are Bárður's to route.
- **The desktop compact frame**, not drawn.

Nothing here was turned into a board task or an owner card.
