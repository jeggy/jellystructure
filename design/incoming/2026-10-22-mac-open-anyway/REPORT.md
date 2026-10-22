# Round 8 — the Mac's two *Open Anyway* steps, in pictures (`mac-open-anyway`)

**Lane C, pilot round 1 — drawn in this repository, not in the design tool.** The owner decided on
DEB-1076 (card `add49886`, 2026-10-22) to pilot drawing without the tool: Claude Design goes
read-only and is retired after two clean rounds, Rannvá draws everything, the owner only reviews,
and the pictures go on the task. The brief is Bárður's on DEB-1469 and is unchanged; only the
transport changed. Round reported on DEB-1470.

**No `DesignSync` call was made in this round, read or write. There is no package, no pull, no
quarantine and no paste block** — the paste row DEB-1471 was cancelled as superseded before any of
this was drawn.

Nothing here is promoted, no production file is touched, no spec is written and no number is
allocated. The page itself lives in the hosting tree, not this repository, and was read for its
style and never edited.

## What was asked for, and where it is

| The brief asked for | Where it is |
|---|---|
| The page strip, desktop and phone widths | `png/…-a-desktop.png`, `png/…-a-phone.png` (and direction B at both widths) |
| The `.dmg` background at 1× and 2× | `png/…-dmg-bg-1x.png` (720×520), `png/…-dmg-bg-2x.png` (1440×1040) |
| …shown over a Light and a Dark Finder window | `png/…-dmg-window-light.png`, `png/…-dmg-window-dark.png` |
| …with the point size and the two icon centres written down | below, and in the directions document |
| Alt text for every picture, and the copy, in one list | `Mac Open Anyway - Directions.html`, two tables near the end |
| What is working copy to confirm at QA | below |
| Anything that didn't fit | below |

**One correction carried in from the brief, already agreed:** the brief asked for the strip on a
*light page*. The page is dark (`#16181f`, `color-scheme: dark`, no light theme on the site at all)
and the spec requires its existing style be kept. Bárður confirmed the correction on 2026-10-16 and
it is drawn dark.

## The geometry the engineer places the icons from

| | |
|---|---|
| Finder window content area | **720 × 520 pt** |
| Background image | **720 × 520 px at 1×**, **1440 × 1040 px at 2×** — one image pixel per point |
| Icon size | **128 pt** |
| Ravilo icon centre | **(190, 190) pt** from the content area's top-left |
| Applications centre | **(530, 190) pt** from the content area's top-left |
| Label bands — keep clear | 190 × 34 pt, centred on each icon's x, **top edge at y = 252 pt** |

A 128 pt icon centred at y=190 ends at y=254 and Finder prints its label immediately beneath, which
is what fixes the bands at 252. The window stays inside 800 × 600 pt so it opens whole on a
1280 × 800 screen with the title bar above it.

**The two mid-tone bands are the one real constraint in the `.dmg` half.** Finder draws each icon's
text label itself, in a colour the image cannot see and cannot predict — near-black in Light, white
in Dark — and a Finder background image does not change with the appearance. So the band under each
icon is a flat `#777e8d` (≈4.1:1 against white, ≈5.2:1 against near-black) and carries nothing of
ours. Everywhere else the text is ours, so its contrast is ours to choose. **Both window frames use
the identical background**; only the chrome and the two labels differ, which is the claim those two
PNGs exist to let a reviewer check.

## The exact render command

Run from the repo root. This is the specification of the permanent script, which is the engineer's
to write — `render.mjs` here is this round's prototype and lives beside the round it rendered.

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
  node design/incoming/2026-10-22-mac-open-anyway/render.mjs
```

Three things in that command are load-bearing, and each of them cost a failed run:

- **`--user $(id -u):$(id -g)`** — otherwise every PNG lands owned by root in a shared checkout.
- **The image pin and the lockfile are different versions, on purpose.** `docker-compose.test.yml`
  pins `v1.45.0-noble`; `tests/package-lock.json` resolves Playwright to **1.61.0**. The compose
  service runs `npm ci && npx playwright install --with-deps chromium` inside the container, so the
  image supplies the OS dependencies and the lockfile supplies a matching browser. The install step
  above is the same move, cached host-side so it happens once. **This is not drift to reconcile.**
- **Playwright is resolved by path, not by name.** A bare `import … from 'playwright'` fails from
  `design/incoming/<round>/`: ESM resolves from the importing file's directory upwards and ignores
  `NODE_PATH`. `render.mjs` resolves `../../../tests/node_modules/playwright` instead. A symlink at
  the repo root would also work and must not be used — several sessions share this checkout.

## Directions, and the recommendation

| Handle | |
|---|---|
| `A · The strip below the download grid` | **recommended** |
| `B · The strip inside the Mac card` | drawn, not recommended |
| `Toggle 1 · Always open, no control` | **recommended** |
| `Toggle 2 · Under a How to open it summary, open by default` | drawn, not recommended |
| `Window · Targets above, steps 4 and 5 beneath` | the `.dmg` layout, recommended |

**B is the direction the shipped spec's own sentence describes, and drawing it is what showed it
fails on its own terms.** A download card is one column of a three-column
`repeat(auto-fit, minmax(260px, 1fr))` grid, so a picture inside it gets 96px. At 96px the refusal
dialog is a grey smudge and the Privacy & Security pane is unreadable — the pictures stop carrying
the instruction, which is the entire point of the round, and the sentences are doing all the work
again. It also takes the Mac card to ~1 460px against the other two cards' ~280px and pushes
*Download .dmg* about 1 200px down the page.

**What A gives up, stated plainly:** the steps sit a section below the button they belong to. A's
own heading and the Mac card's note close that in words rather than pretending otherwise.

**Toggle 1 is recommended because of the folded state, not the open one.** Folded, the page says
*less* than it does today — the one honest line it currently has is replaced by a control that hides
everything, and the reader who collapses it is exactly the reader about to meet the first refusal.

## Every state, drawn or named by name

Drawn: page desktop, page phone, steps collapsed, pictures-not-loading (both widths), `.dmg` window
Light, `.dmg` window Dark, the work-managed-Mac line (in every page frame), longest string Danish,
longest string Faroese. **Loading** is folded into the pictures-not-loading frames — a half-loaded
page is the same picture.

**Not applicable, each by name rather than by omission:** *empty* (a static page with no data to be
empty of), *error* (nothing is fetched, so nothing can fail), *offline* (once loaded the page needs
nothing further), *10-foot focus* (no TV surface is in this round).

## Working copy — to confirm at QA

The walk recorded the button names and the Privacy & Security line; it did not record the dialog's
title or body. All four live in one `WORKING` object in `mac-open-anyway.js`, so confirming them is
one edit in one place rather than a hunt through nine frames.

| Where | Drawn as | Status |
|---|---|---|
| Refusal dialog, title | “Ravilo” can't be opened | **working copy** — not recorded on the walk |
| Refusal dialog, body | macOS cannot check it for malicious software. | **working copy** — not recorded on the walk |
| Password prompt, title | Enter your password to open Ravilo. | **working copy** — not recorded on the walk |
| Privacy & Security line | “Ravilo” was blocked to protect your Mac. | **working copy** — from the brief, still unconfirmed |
| *Move to Trash* · *Done* · *Open Anyway* | as drawn | recorded on the walk |

If the real wording differs, the copy changes and none of the layout does.

## What didn't fit, and what is open

1. **The recommended direction contradicts the shipped spec's own wording.** The spec says *"the Mac
   card's one-line `dl-note` becomes the five-step strip"*, which describes direction B. If A is
   adopted that sentence needs a dated amendment — Bárður's, and he has already said he will write
   it when the package is accepted.
2. **The macOS button names stay English in the Danish and Faroese drafts, and that is a decision.**
   The pictures show an English macOS because that is the Mac the behaviour was walked on. A reader
   running a Danish macOS sees *Åbn alligevel*, not *Open Anyway*. Translating the names in our text
   makes the words disagree with the pictures beside them; leaving them English makes the text
   disagree with that reader's screen. **Neither is free and this round does not decide it.** The
   page ships English today, so nothing is blocked on it.
3. **The app icon is drawn, not the real file.** The pictures carry a CSS/SVG facsimile of the
   jellyfish mark. The real `.icns` is the build's, not a design artefact, and copying a binary into
   a design round to show a 26px icon is not worth it. The `.dmg` background does not contain the
   icon at all — Finder draws it.
4. **No second pair of eyes before this landed.** In lanes A and B a drawing arrives from elsewhere
   and is triaged; here the same seat drew it, checked it and committed it. Three defects were found
   only by opening each rendered PNG: a black icon where the jellyfish should be, pictures
   overflowing their card, and a password sheet covering the exact button its sentence told the
   reader to press. **None of the four fences can see any of those.** Recorded in `PROCESS.md` as
   the lane's standing risk.
5. **The 192 KiB-per-PNG rule does not apply in this lane and was not applied.** It was the
   `get_file` truncation wall on the pull; lane C has no transport. Several page frames are over it
   and nothing is wrong with them.

## The fences

Run on the **staged** directory, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked one. Results are in the commit's own report on DEB-1470.

**No real values anywhere in this round, and it is a cheap claim to check here:** the round invents
no title, no person, no host and no URL. The only proper nouns are *Ravilo*, *Applications*, macOS's
own button names, and the file name `ravilo-mac-1.52.dmg`. The password prompt has no user name and
its field is visually empty; the Privacy & Security pane lists Ravilo and nothing else; no frame
contains anything from anybody's media library. There is no `http`/`https` reference, no CDN and no
hosted font — the three families load from `fonts/` beside the markup, copied from `design/app/fonts/`.
None of the four forbidden canvas properties appear: no `content-visibility`, no `backdrop-filter`,
no large `filter: blur`, no infinite animation.

**One deliberate canvas substitution, labelled in both places it appears.** The live download cards
use `backdrop-filter: blur(16px) saturate(125%)` over a translucent fill, which the standing canvas
rule forbids because it breaks the screenshot tool. The cards are drawn as the solid `#21242e` that
filter resolves to; the live page keeps its blur.

## Files

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `Mac Open Anyway - Directions.html` | 22 575 | 289 | `abb5bee98ab8` |
| `mac-open-anyway.css` | 19 601 | 399 | `9cf4c7aa324e` |
| `mac-open-anyway.js` | 14 376 | 249 | `3af9e4f5def6` |
| `page.html` | 7 154 | 145 | `8d241a162e4b` |
| `render.mjs` | 4 855 | 108 | `58b2b7ef50df` |
| `dmg-window.html` | 3 371 | 67 | `8f1e4d2d4a4a` |
| `dmg.html` | 1 713 | 46 | `59eadc7649de` |
| `fonts/jetbrains-mono.woff2` | 31 432 | — | `83c005d49d8a` |
| `fonts/sora.woff2` | 25 284 | — | `811e11966d29` |
| `fonts/space-grotesk.woff2` | 22 288 | — | `0640890476fc` |
| `png/mac-open-anyway-a-desktop.png` | 201 694 | — | `049b0352693c` |
| `png/mac-open-anyway-a-desktop-toggle.png` | 202 688 | — | `3e4be440bdc9` |
| `png/mac-open-anyway-a-desktop-collapsed.png` | 91 601 | — | `7aa27d42eb54` |
| `png/mac-open-anyway-a-desktop-noimg.png` | 204 220 | — | `9fb034fc85b1` |
| `png/mac-open-anyway-a-phone.png` | 256 785 | — | `e1f03e5e39ee` |
| `png/mac-open-anyway-a-phone-noimg.png` | 219 375 | — | `39b9eb0c6358` |
| `png/mac-open-anyway-a-phone-da.png` | 255 171 | — | `ae6896ebc741` |
| `png/mac-open-anyway-a-phone-fo.png` | 254 255 | — | `113b57d04ddc` |
| `png/mac-open-anyway-b-desktop.png` | 179 937 | — | `695d710e7b66` |
| `png/mac-open-anyway-b-phone.png` | 169 137 | — | `a8ea7c6885f9` |
| `png/mac-open-anyway-dmg-bg-1x.png` | 44 183 | — | `7b7d7d879642` |
| `png/mac-open-anyway-dmg-bg-2x.png` | 97 563 | — | `de4718a57656` |
| `png/mac-open-anyway-dmg-window-light.png` | 58 248 | — | `c0f7e8fe8dfc` |
| `png/mac-open-anyway-dmg-window-dark.png` | 59 611 | — | `832a678663f4` |

**24 files, 2 447 117 bytes.** No `STATUS.md`, no `CLAUDE.md`, nothing under a `specs/` path and
nothing under a `scripts/` path was created by this round.
