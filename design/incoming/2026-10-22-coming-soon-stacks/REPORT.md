# Round 11 — Coming Soon stacks a series' same-day episodes (`coming-soon-stacks`)

**Lane C — drawn in this repository, not in the design tool.** No `DesignSync` call was made in
either direction. There is no package, no pull, no quarantine and no paste block: the paste row
DEB-2109 was cancelled as superseded before any of this was drawn, and its block is the brief this
round was drawn from, together with the description on DEB-2107. Brief and decisions are Bárður's,
from the owner's live ask on DEB-2078. Reported on DEB-2107.

**This round allocates no number and writes no spec.** The task id is the identity.

**Drawn with the house design system (`design/SKILL-house.md`) and `design/PROCESS.md`.** The
`frontend-design` skill is selected on this seat and was read under them, as the house file requires;
what it contributed is noted at the end, and it changed no identity, type or token.

## What this round answers

When several episodes of one series land on the same day, that day's row shows them as **one card**
instead of one card each — on the TV, the phone and the desktop. Three directions are drawn, six
decisions are put in front of the owner, and nothing is picked that was his to pick.

The directions document is **`Coming Soon Stacks - Directions.html`**; it is the thing to open first.

## The exact render command

Run from the repo root. This is the specification of the permanent script, which is the engineer's to
write — `render.mjs` here is this round's prototype and lives beside the round it rendered.

```sh
# once, to put a browser matching tests/package-lock.json in a host-side cache
mkdir -p ~/.cache/ms-playwright
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  sh -c 'cd /work/tests && npx playwright install chromium'

# every render after that
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  node design/incoming/2026-10-22-coming-soon-stacks/render.mjs
```

Everything rounds 8 and 9 recorded about that command held again: `--user` keeps the PNGs out of
root's hands in a shared checkout, the image pin (`v1.45.0-noble`) and the lockfile (1.61.0) are
different on purpose, and Playwright is resolved **by path** because ESM resolves from the importing
file's directory and ignores `NODE_PATH`. The recipe is stable across three rounds now.

### What is new about rendering the TV, for the next round

Rounds 8 and 9 were a public web page and the admin. This is the first lane-C round on Ravilo, and
four things about the TV frames are worth carrying forward:

1. **The 1920×1080 stage is drawn flat, not scaled.** The shipped `#stage` is centred by a transform
   and scaled to the viewport; a frame has to be screenshot-able on its own, so `.cs-tvframe` is a
   static 1920×1080 block with the same children. A measurement off these PNGs is a measurement in
   stage pixels, and that is the only way the numbers in a spec can be read back off them.
2. **One shipped stylesheet per document, chosen by the frame set.** The TV and the phone are two
   separate token files that name their tokens the same way. Loading both into one document lets one
   answer for the other, so `frames.html` takes `?set=` and links `ravilo.css` **or**
   `ravilo-phone.css`, never both. The round's own CSS always comes last.
3. **`ravilo.css` opens with an `@import` of `fonts.googleapis.com`.** That is normal in a mockup and
   useless in a container. The round re-declares the three faces from the repo's own
   `design/app/fonts/*.woff2` at the end of the cascade, so the picture is identical with or without
   a network and nothing is copied. `font-display: block` plus `document.fonts.ready` keeps the first
   frame from rendering in a fallback face while every later one gets the real one.
4. **Chromium has no Faroese locale.** `toLocaleDateString` silently falls back to English, and a
   round that did that would be reporting a translation it never drew. Weekday and month names are
   tabled per language in `cs-data.js` instead.

## Directions, and the recommendation

| Handle (character for character) | |
|---|---|
| `A — Stacked edge` | **recommended** |
| `B — Wide list card` | drawn; see the conflict below |
| `C — Count only` | drawn as the control |

**Why A.** The occasion was a row that stopped reading as "a season arrived" and started reading as
noise. A fixes exactly that and costs the row nothing: the card's footprint is unchanged at 430 px on
the stage, because the front face gives up the 32 px the deck occupies rather than the card growing.
B answers a different question — *which* episodes — and answers it best precisely where the problem
is smallest, while being at its most redundant on the uniform whole-season drop that prompted the
round. C is honest, cheap, and does not fix the thing that was wrong.

**One mixed answer worth the owner's attention:** B is clearly better in the **Missing** row and on a
**mixed** stack, because it can say *which* episode is at 62 % and *which three days* were missed,
where A can only say "4 of 6" and the earliest due date. "A in the day rows, B in the Missing row" is
a legitimate answer to decision 1 and is not a compromise — the two rows have different jobs.

## The findings

Nine, in the order they would cost someone else time. The first three are Bárður's to route.

1. **The brief contradicts itself, and the round priced it rather than hiding it.** *Nothing may
   change the card's height or width* and *one wider card that lists its episodes* cannot both hold.
   B is drawn keeping the **height** and the row's baseline, and spending **two card slots** of width
   (878 px where A spends 430). On the 1920 stage with its 64 px gutters that is two fewer ordinary
   cards visible in a row that contains one B stack. B is not unbuildable; it is expensive, and the
   price is legible in `tv-b-today.png`.

2. **B does not scale down — it re-flows.** At the desktop card size an eight-episode list needs
   168 px in a 152 px panel, and the first desktop render **silently clipped S02E08**: a card whose
   whole claim is that it lists its episodes, quietly listing seven of eight. It is drawn in two
   columns on the desktop, as on the TV. If B is adopted, "the list re-flows, it never truncates" is
   a spec sentence, not an implementation detail.

3. **The brief asked for an untouched empty day in the strip; there is no such thing.** The shipped
   renderer builds the strip from days that actually carry a release and jumps to the next day with
   content, so an empty day never appears. The state is reported skipped rather than invented.

4. **Three stand-in facts the brief did not name, chosen here and flagged.** The brief gives the mixed
   stack (*Heron Vale*) and the not-held series (*Ferrow Bay*) no day and no time; Sunday 25 Oct at
   06:00 and Monday 26 Oct at 22:00 were chosen. The brief names no episode title for the single
   episode (*Saltmarsh Rangers* S35E08), so the card is drawn without one. None of the three is a
   design decision, but all three are visible in the pictures and a spec written from them would
   inherit them.

5. **The phone's Coming Soon is a list, not a card grid.** `ravilo-phone.js` renders it as `.t10row`
   list rows — poster, title, meta, status — while the TV uses `.up-card`. So "all three surfaces
   share one card component" is true of the Compose app and **not** true of the mockups this round
   extends. A stack on the phone is therefore a stacked *row*, drawn here with the same deck idea at
   list-row size. Worth knowing before a spec promises one component.

6. **The desktop mockup has no Coming Soon page at all.** `ravilo-desktop.js`'s Discover is the Seerr
   suggestion grid. The desktop frames here are therefore new drawings: the card and the row are the
   shipped vocabulary at desktop scale, and the left rail is **indicative** — it is not the shipped
   desktop shell's markup, and nothing should be specified from it.

7. **Three tokens the phone's set is missing.** `ravilo-phone.css` defines `--good` but **no
   `--warn`**, so the phone has no token for "mixed" at all; and it defines `--ring` / `--ring-glow`
   only inside the `daylight` block, so a compact desktop window in a dark skin has no focus ring
   token. Written without fallbacks, the focus-ring rule drew nothing at all, which is how it was
   found. Both need adding to the phone's token set rather than hard-coding.

8. **The phone has no noir and no midnight.** `ravilo-phone.css` carries graphite and daylight as full
   skins and gives midnight and noir nothing but a `--bg-rgb` override, so `?skin=noir` on the phone
   renders aurora exactly — verified by rendering it and finding the two frames byte-comparable. The
   three skins the brief asks for are covered on the **TV**, where they are defined; the phone is
   covered in the skins it actually has (aurora, graphite, daylight). The mislabelled frame was
   deleted rather than shipped.

9. **Two disagreements inside the shipped `i18n/*.json`, reported not fixed.** Danish `up.episode` is
   *"Episode"* while `up.episodes_range` is *"Afsnit {a}–{b}"*, so a Danish card carries an *Episode*
   badge beside an *8 afsnit* count — visible in `tv-a-da.png`. And the shipped set has no key for the
   day row's *"{n} releases"* at all, though the shipped mockup renders one. The shipped file wins
   wherever a string exists; a design-mirror disagreement is information.

**Also, and smaller:** the brief quotes the card's third line as *network · year* while the shipped
mockup renders *network · rating+*. The brief was followed, so no rating is invented anywhere in this
round.

## What looking at the renders found, which no fence can

Six defects went into renders of this round and were taken out again. Listing them because pilot
round 1's lesson is the one that keeps paying:

- **The deck was invisible.** The first version used the card greys for the offset layers and they
  vanished into the page at 3 m. The fix was also the correction: the brief says every layer is the
  art *or a tint of it*, so the edges carry the series' own gradient, dimmed in two steps.
- **A `z-index: 1` on the edge's highlight painted it over the card in front.** A positive z-index on
  a child of an auto-z-index absolute box clears its later siblings too. The dim and the highlight
  are one two-stop gradient now, with no stacking order to get wrong. **Only magnifying the render at
  6× showed this** — at 1× it looked like two stray hairlines.
- **The phone's poster never moved.** The shipped `.t10p` carries `width: 58px`, which with `left: 0`
  over-constrains the box and makes `right` **ignored outright**, so the deck was drawn entirely
  behind the poster. `width: auto` is load-bearing.
- **The mixed stack's status pill wrapped to two lines.** *Episode* + *4 of 6 available* + *62 %* +
  *6 episodes* is four chips in 430 px. The count badge moved to the bottom corner beside the air
  time — which also reads better, because the time and the count are one fact about the release.
- **B drew two focus rings with a gap down the middle**, which a D-pad user would reasonably read as
  two targets. One outline round the pair now.
- **Two cards said the same thing twice.** A film's subtitle repeated its own *In Cinemas* release
  label, and a missing stack showed a single air time for episodes that were due on three different
  days. Both lines were removed rather than restyled.

## The frames — 45 PNGs

| Surface | Size | Count |
|---|---|---|
| TV, the 1920×1080 stage | 1920×1080 | 26 |
| TV date strip, cropped to the strip | 1920×216 | 2 |
| Phone, Pixel 9 portrait | 412×915 | 9 |
| Phone, landscape | 915×412 | 1 |
| Desktop, wide | 1440×900 | 6 |
| Desktop, compact window (= the phone layout) | 560×900 | 1 |

9 872 568 bytes in total; the largest single frame is 627 254 B. **The 192 KiB-per-PNG rule does not
apply in this lane** — that was the `get_file` truncation wall on the pull, and lane C has no
transport.

## The sources

| File | Bytes | Lines |
|---|---|---|
| `Coming Soon Stacks - Directions.html` | 16 907 | 227 |
| `coming-soon-stacks.css` | 22 826 | 340 |
| `cs-kit.js` | 24 045 | 442 |
| `cs-data.js` | 11 935 | 218 |
| `frames.html` | 6 133 | 143 |
| `render.mjs` | 6 609 | 133 |

**Every count, range, time and badge in every picture is derived from one array.** `cs-data.js` holds
the stand-in set as episode-level rows; `cs-kit.js` groups them and builds the markup; the three
surfaces call the *same* `card()` and differ only in the wrapper class they sit in. So "do these 45
pictures agree with each other?" is something a reviewer can check rather than trust — and it is the
honest model of the surface, because the client renders server-pushed state and invents no catalog
state of its own.

Classes with no prefix in these files are the **shipped** Ravilo classes; everything this round
invents is prefixed `cs-`. Nothing is copied: `frames.html` links `../../ravilo/ravilo.css` and
`../../ravilo/mobile/ravilo-phone.css` directly, and the fonts resolve to `../../app/fonts/`.
The round is therefore not portable outside this repository, which in a lane with no transport costs
nothing and buys an exact system.

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
all of those are fixed here. What it was actually useful for: *spend your boldness in one place*,
which is why the count badge is neutral glass rather than a second coloured chip competing with the
status pill; and *critique your own render instead of shipping it*, which is the discipline the six
defects above were found by. Nothing it advised was overruled, because nothing it advised reached
the identity.

## What is still open

All six decisions, which are the owner's. Plus the three stand-in facts in finding 4 and the two
i18n disagreements in finding 9, which are Bárður's to route. Nothing here was turned into a board
task or an owner card.
