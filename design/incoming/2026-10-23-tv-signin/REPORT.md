# A TV signs in from your phone — round report

**Round** `2026-10-23-tv-signin` · Ravilo TV, phone, Ravilo Web, desktop, plus one admin page and
four browser pages · drawn in this repository.
**Brief** from Bárður. **Returns** to him on the brief's task with the PNGs.

**97 PNGs.** The written half is `TV Sign-in - Directions.html`: what is fixed, the two directions,
the decisions as drawn, the open questions and every string in three languages. This file is the
round's own record — what was built, what the pictures changed, what was looked at, and what is
still owed.

---

## The brief

**It stood alone and nothing went back.** It named the question as a choice, named every surface,
quoted the constitution as excerpts rather than pointing at it, named the stand-ins exactly, listed
the states per surface, and stated each of the four decisions as a lean. It carried no phase number
and no `DEB-n` for the drawing to name. The spec it cites
(`specs/ravilo/requirements/a-tv-signs-in-from-your-phone-and-sso-signs-in-everywhere.md`) was read
in full and the brief's excerpts match it.

**Nothing in the round's inputs had to be dropped.** No `STATUS.md`, nothing under
`specs/research-reports/`, nothing under `scripts/`, no `CLAUDE.md`.

## What the round is made of

| File | What it is |
|---|---|
| `frames.html` | Every frame, parameterised by `?set=`, `&lang=`, `&skin=`, `&theme=` |
| `ts-data.js` | **The one data file.** Every string, stand-in and measurement |
| `ts-kit.js` | The builders — one per surface, called with options |
| `ts-qr.js` | A real QR encoder, byte mode, version 3-M |
| `verify-qr.py` | A decoder that reads the QR back and checks it |
| `tv-signin.css` | The round's own rules, on top of the shipped stylesheets |
| `TV Sign-in - Directions.html` | The written half |
| `png/` | 97 frames |

**Nothing is copied.** `frames.html` links `../../ravilo/ravilo.css`,
`../../ravilo/mobile/ravilo-phone.css`, `../../ravilo/desktop/ravilo-desktop.css` and
`../../app/wf.css` directly, so every token, component and font in the pictures is the production
one. The three faces are re-declared at the end of `tv-signin.css` from the repo's own
`design/app/fonts/*.woff2`, so the render needs no network and the picture is identical with or
without one.

**One shipped stylesheet set per document, chosen by `?set=`.** The TV, the phone and the admin are
three different token files that name their tokens the same way (`--bg`, `--ink`, `--card`,
`--grad`). Loading two into one document lets one answer for the other and the picture is of a
system that does not exist.

**`?set=pages` loads no shipped stylesheet at all**, on purpose: surface 4's pages ship as single
files and cannot reach for a token file. Loading `ravilo-phone.css` there would have drawn a page
that cannot ship.

---

## The QR is real, and it was wrong twice before it was right

The brief allowed "a real QR of that string or a faithful placeholder". A placeholder would have
been cheaper and the wrong artefact: the one thing a reviewer and QA need to do with these pictures
is point a phone at them. So `ts-qr.js` is a byte-mode, version 3, level-M encoder — the one shape
`https://ravilo.example.net/tv?c=K7P4QX` needs. 38 bytes in a 42-byte capacity, so the household's
real host has **6 bytes of headroom** before the TV needs version 4.

`verify-qr.py` reads the matrix back the other way: it reads the format information and checks its
BCH in **both** copies, removes the mask, walks the data modules, checks **all 26 Reed-Solomon
syndromes are zero** with its own GF(256) tables, and parses the byte-mode segment back to the
string.

```
format        : level M, mask 3  (both copies agree, both pass BCH)
reed-solomon  : all 26 syndromes zero
decoded       : 'https://ravilo.example.net/tv?c=K7P4QX' (38 bytes)
match         : yes
```

**Three things that went wrong, and which check caught each:**

1. **The generator polynomial was built with its two terms transposed** — `g(x)·x` and `g(x)·aⁱ`
   landed in each other's positions. 25 of 26 syndromes non-zero on a matrix that was otherwise
   perfectly well formed. *Caught by the decoder.*
2. **The second format copy was written over the dark module.** Eight bits up the bottom-left
   column where only seven belong, leaving `(8,28)` unwritten. Both copies still passed BCH on
   their own, which is why the markup looked right. *Caught by the decoder.*
3. **The finder patterns were inverted** — a perfectly regular 7×7 eye with its colours swapped.
   **The decoder passed it without a murmur**, because a finder is a function pattern and the
   decoder only reserves it. *Caught by printing the matrix as text and looking at it.*

That third one is the round's own small version of the lane's rule. A verifier checks what it was
written to check; looking at the thing catches what nobody thought to check.

> **A physical scan is still owed.** There is no camera on this seat and no QR decoder on the host.
> Module size, quiet zone, contrast and the render's scaling are physical questions this round
> cannot answer. **QA should point a real phone at `tv-signin-tv-a-first-run.png` on a real TV**,
> and at `tv-signin-tv-b-first-run.png` from the same distance — that is the measurement direction
> B turns on.

**The QR's colours are hardcoded black-on-white in every skin**, including noir and midnight. That
is deliberate: a scanner wants maximum contrast and a bright tile is also what draws the eye to the
one thing to point a phone at. It is the only place in the round that does not take a token.

---

## The sizes, and why they are the argument

| | Direction A | Direction B |
|---|---|---|
| QR | **444 px** (37 modules × 12) | **300 px** |
| Code | 112 px | 80 px |
| On a 55-inch set | **~28 cm** | ~19 cm |
| The 3-metre rule wants | ~30 cm | ~30 cm |

The arithmetic is in `ts-data.js` beside the numbers. 1920 px across a 122 cm panel is ~0.64 mm per
pixel; the field rule for a phone camera is a QR side of about a tenth of the reading distance.
**A is a three-metre code and B is a two-metre code**, and B is not smaller by choice: stacked down
the middle of 1080 lines, FR-5's five blocks plus the 92 px brand row, the 168 px footer and 56 px
of overscan margin leave 764 lines for the stack, and the other four blocks need about 460 of them.

On a 43-inch set A's QR is ~22 cm and already at the rule's limit. That is the smallest screen this
size is honest on, and it is the other thing worth a real phone.

---

## What only the pictures showed

Eleven defects, none of which any of the four fences can see, and none of which was visible in the
markup.

1. **Direction B's QR was not square.** 444 × 310. In a flex column the default `flex-shrink: 1`
   squashed it. **A squashed QR does not scan.** `flex: none`.
2. **The no-QR state was the defect the brief predicted.** With the QR gone, direction A's content
   sat jammed against the left gutter with two thirds of 1920 empty. A's premise is two columns and
   there is no second column, so **A's no-QR state is now a centred single column — which is
   direction B's arrangement.** Reported as a point in B's favour rather than hidden in the CSS.
3. **The focused button's ring was 54 px from the bottom edge.** Vertical overscan on a set that
   still does it is 3–5 % of 1080, i.e. 32–54 px. A focus ring the TV crops is a D-pad user who
   cannot see what is selected (R205, R250). 56 px of foot padding.
4. **"Or go to ravilo.example.net/tv" was still on screen while someone was approving** — stale
   advice, for the same reason the instruction is replaced there. Both now go.
5. **Three silent deviations from the shipped phone system**, introduced by this stylesheet
   restating `.pft-*`, `.pf-*` and `.fld` rules that `ravilo-phone.css` already has. That file has
   **no wrapper selector** — its indentation is a convention, not a scope — so every one of those
   rules applies, and loading later meant this round quietly won: the avatar came out a 22 px
   rounded square where the shipped one is a circle, the field labels came out uppercase where the
   shipped phone form is sentence case, and the primary button's ink came out dark where the
   shipped one is white. All three deleted.
6. **A white focus ring on every code field, phone and Mac.** `--ring` and `--ring-glow` are **TV**
   tokens; `ravilo-phone.css` does not declare them. An undeclared custom property invalidates the
   whole declaration, so `border-color: var(--ring)` fell back to `currentColor`. Now the shipped
   phone idiom, `var(--accent)`.
7. **Cancel was a 190 px-tall slab** on the waiting screen. The shipped `.pf-btns` is a flex
   **column**, so `flex: 0 1 180px` on a child sets its height.
8. **The browser chrome on all eleven surface-4 pages was invisible.** `.ts-page` is
   `position: absolute; inset: 0` and `.ts-wbody` was not positioned, so it resolved against the
   frame and painted over the address bar — in the one set of frames whose constitutional point is
   *which address the viewer is looking at*. Compounded by every token in that chrome resolving to
   `transparent`, because the `pages` set loads no token file. Both fixed; every colour there now
   carries a fallback.
9. **Two filled gradient buttons on one screen**, on the phone, the Mac and the admin — the
   provider button and the form's own *Sign in*. See the decision note below.
10. **The comparison frame rendered one phone where two were the point.** `.ts-pair` had no
    `width: max-content`, so the flex container took the 412 px viewport and the second cell was
    simply not in the picture.
11. **"Sign in" printed twice** on the web desk sign-in — once as the page title, once as the panel
    heading.

Three more that are copy rather than layout:

- **"Get a new code" promised something the button cannot do.** The TV fetches a new code by itself
  (FR-5); the button only returns to the field. Now **"Enter a new code"**.
- **The Mac window's nav said "Films" and stayed English in a Danish or Faroese run.** The shipped
  key is `nav.movies` = "Movies", and it has translations. Scaffolding, but it is in every Mac
  picture and read as a defect in the design.
- **The browser pages had dark ink on the gradient button** while every app screen beside them had
  white. They load no token file, so the value is written out — and it is now the shipped value.

## One observation that is information, not a defect

The shipped `.pf-btn.primary` is `#fff` on `linear-gradient(120deg, #7b6ef0, #3fb6f5)`. At the blue
end that is roughly 2.1:1, below any contrast floor. **This round matches the shipped product
rather than diverging from it**, per the house rule, and notes it once here. It is a product
question for Bárður, not this round's to fix in a drawing.

---

## Canvas rules, and the three places a shipped rule breaks one

Held: no `content-visibility`, no `backdrop-filter`, no large `filter: blur`, no infinite
animations, dark ink on the light backdrop for every caption. Three shipped rules collide with that
and are overridden in the frames, each named here because the live product keeps them:

- **`.signin-panel .cursor`** in `ravilo.css` blinks forever → the password context frame draws no
  cursor.
- **`.profiles.switch`** in `ravilo.css` is `backdrop-filter` → only the plain `.profiles` is drawn.
- **`--blur` on every admin card** in `wf.css` → the admin frame sets `--blur: none`. **The live
  admin keeps its glass.**

Two motion states are drawn **still**, at one position, rather than animated: the approving dots on
the TV and the waiting spinner. A screenshot of an animation is a still frame anyway, and an
infinite one breaks the render.

Every SVG id in the round is minted from a counter (`uid`), including the QR's `<title>` id and
every wordmark gradient.

---

## One lane problem that is not this round's to fix

**`design/ravilo/ravilo.css` opens with `@import url('https://fonts.googleapis.com/…')`, and
`scripts/render-design.sh` fails the run on any off-host request.**

```
render-design: failed, 0 PNG(s) removed
  off-host request: https://fonts.googleapis.com/css2?family=Space+Grotesk…
```

Every earlier TV round used its own `render.mjs`, which had no such check, so this round is the
first to meet it. Two rules collide: *link the shipped stylesheet, do not copy it*, and *no off-host
request*. Copying `ravilo.css` to strip one line would break the first and leave a stale copy of a
token file in the tree — the exact shape of bytes the nineteen wipes were made of.

**The workaround here:** `frames.html` declares a Content-Security-Policy permitting `file:` and
`data:` and nothing else. Chromium refuses the import at the policy, before it becomes a request,
so the renderer never sees one. It is also a stronger guarantee than the renderer's own — the page
cannot reach the network, in the renderer or in a browser opened by hand.

**This belongs in the lane** (question Q5). Either `render-design.sh` grows a flag for a shipped
mockup's own `@import`, or `ravilo.css` loses the line the way the served admin CSS already has.
Until one of those happens, **every round that links `ravilo.css` needs those four lines, and a
round that does not know that cannot render the TV at all.**

---

## The decisions, as drawn

| # | Brief's lean | Outcome |
|---|---|---|
| 1 | QR left, words right | **Held.** Direction A recommended. B drawn in full and costs 144 px of QR. |
| 2 | Print the web address when the server knows it | **Held.** `tv-a-no-address` is the same frame without it. **If picked, FR-5 needs the amendment Bárður offered.** |
| 3 | One field that groups as you type | **Held.** Six boxes drawn in both states as a fair alternative. |
| 4 | A full-width **filled** button above the fields, with an *or* divider | **Full width, above the fields, with the divider: held. Filled: moved.** |

**Decision 4 is the one that moved, and only because it was drawn.** A filled provider button plus
the form's own filled *Sign in* is two equally loud primaries on one screen, with no answer to
"which one is the way in?". One filled control per screen is the house rule. Both weightings are
drawn and `ph-signin-weight-pair` puts them side by side — **that is the frame to decide on**, and
nothing was picked here that the owner has been asked to pick.

---

## Still open, and each one reaches Bárður in writing

The seven questions are written out in full in the directions doc. In one line each:

- **Q1** — *"Approving on ‹name›'s phone…"* can be wrong; the approver may be at a computer and
  FR-3 sends no device kind. May need an FR-3 field.
- **Q2** — the confirm screen's supporting line is **proposed**; the spec gives only the question.
- **Q3** — three outcome strings and two browser-page strings are **proposed**.
- **Q4** — the brief names Ravilo Web's host but **not the backend's**, which is where surface 4's
  pages actually live. One stand-in is doing two jobs, visibly. Name the second and they re-render
  in a minute.
- **Q5** — `ravilo.css`'s `@import` versus `render-design.sh` (above). A lane problem.
- **Q6** — the signed-out arrival says nothing about the TV that is waiting.
- **Q7** — *"This account can't use Ravilo"* is deliberately a dead end; worth confirming.

---

## Coverage, stated rather than implied

| What the house rule asks for | This round |
|---|---|
| Loading, including the slow case | **Drawn** — the waiting state is the slow case; there is no spinner-only frame because no screen here has one |
| Empty | **Drawn** — `ph-code-empty`, `ph-code-boxes-empty`, and the TV's own first run |
| Error | **Drawn** — five outcomes on two surfaces, three browser error pages, the TV's unreachable state |
| The longest string | **Drawn and measured** — Faroese, 118 characters, 16 % over English, two lines |
| Danish and Faroese | **Drawn** — 22 frames across TV, phone, Mac and the browser pages |
| 10-foot focus | **Drawn** — the one focusable thing on the TV is focused in every TV frame |
| Offline / lost connection | **Drawn** — `tv-ctx-unreachable`, `ph-out-offline`, `mac-out-offline`, all using the **shipped** string |
| The round's own states | **All of them**, plus three context frames and six alternatives |
| TV skins | aurora, **noir** (amber accent) and **midnight** |
| Admin themes | dark and light, all four admin frames in both |
| Phone frames | Pixel 9 412×915 and iPhone 16 393×852 |

**Not drawn, deliberately:** Tizen and the Cast receiver, which the spec puts out of scope; the
Linux desktop, which is the Mac layout in its own chrome and would be the same picture; and the
profile picker's own redesign, which this round does not touch.

## Looked at

**Every PNG was opened and magnified before this was written.** The eleven layout defects and three
copy defects above are what that found, and the QR's inverted finders are what it found in a file a
decoder had already passed. The frames were then re-rendered from scratch and looked at again.

## Fences

Run on the **staged** directory, after `git add`, because `check-deanonymization.sh` enumerates
`git ls-files` and prints a cheerful OK over an untracked tree.

Every `http`/`https` literal in the round was read before it landed:

- `https://ravilo.example.net/tv?c=K7P4QX` — the brief's stand-in, inside the QR and three address
  bars.
- `https://ravilo.example.net/sso/OID/redirect/HarbourID` — surface 4's own return route. It is
  **jellystructure's** route and names nothing of Jellyfin's; `OID` is OIDC.
- `http://127.0.0.1:7845/signin` — the desktop's loopback hand-back, which FR-11 fixes by RFC 8252
  §7.3. **It is an IP literal**, which `PROCESS.md` asks a human to read before it lands: it is a
  loopback address required by the spec, not a household address, and the port is a stand-in.
- `https://fonts.googleapis.com/…` — **not in this round**. It is inside the shipped `ravilo.css`
  that the frames link, and the round's own CSP is what keeps it from being fetched.

No `@import url(http…)`, no `<script src="http`, no hosted font in anything landed, no `passkey`,
no `announce`, no `api_key`, no `token=` with a value after it. Every string literal in the round
comes from the brief's stand-in list.

## Not promoted

**Nothing here is production CSS.** `design/ravilo/` is reference only — no Gradle task consumes it
— so these rules are a specification of intent for the Compose port, which is exactly why the hex,
px, weight, radius and easing are written out rather than left to the implementer. Promotion is a
separate hand-made commit through `PROCESS.md`'s gate, and it is Bárður's call after the owner
picks.

The one file in this round that has a production twin is the admin's sign-in page, and it is
**hand-edited Kotlin in `Login.kt`**, not CSS to copy: the frames show where the button goes and
which `wf.css` classes it takes (`.btn`, `.btn.primary`, `.field`, `.input`), and nothing more.
