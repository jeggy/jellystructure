# The invite sheet, the landing page and setup from an invite — report

Round `2026-11-27-invite-and-setup`, drawn 2026-11-27. Product design, so it lands under
`design/incoming/`. 74 PNGs in `png/`, the directions doc is
`Invite and Setup - Directions.html`, and every number and string in every picture comes from
`invite-data.js`.

Three surfaces, one round, on Bárður's brief and the owner's 2026-11-27 change to the Android
button.

---

## 1 · The answer to the round's question

**L1 — *Open in Ravilo* first.** The brief's lean, and the pictures support it for a reason the
brief could not have known:

> **The whole page fits an iPhone 16 viewport with 23 px to spare**, and a Pixel 9 with 86 px.

Every page frame measures its own overflow after layout and prints it in the caption, so that is a
number the browser computed rather than one I claim. Because nothing is below the fold, the order
is **not** about what a viewer sees — they see all of it — it is only about which button carries
the page's one primary. On that narrower question L1 is right: the primary should be the action
most invited viewers will take, which is opening an app they already have. L2's cost is concrete
and visible in its picture — its primary ends up in the *second* group, under a list of four
platforms.

**But L3 is what the spec says today**, and an engineer builds the spec. FR-3 lists the parts as
*Watch in your browser → Open the Ravilo app → Get the app → TV*. Adopting L1 needs a one-line
dated amendment in `an-invite-link-sets-up-a-new-viewers-app.md`. That is yours, not mine.

---

## 2 · What only the pictures showed

Eleven defects in this round's own drawings, all found in the look-at-every-PNG pass and all fixed
before this report. Listed because PROCESS.md's lesson keeps paying.

| # | What the render did | Why it happened |
|---|---|---|
| 1 | **L3 drew two primary buttons** — *Use Ravilo in the browser* and *Open in Ravilo* both filled with the gradient | the primary was hard-coded to the open button instead of being computed from the order. One primary per page is now derived once, in one place |
| 2 | **The setup field and Connect were both focused** — the TV's 3 dp ring *and* the focused fill in one frame | a constitution break (*"exactly one visible focus target"*) that no fence can see. `focus` is now named in every state in the data file |
| 3 | **Midnight drew a violet invite line** on a screen whose accent, wordmark and Connect are teal | the tone table hard-coded Aurora's `#8e82ff` for every dark skin. The colour is now derived from `--accent` per skin |
| 4 | **A 250 px play triangle across the primary button**, pushing its label onto two lines | an inline `<svg>` with only a `viewBox` fills its flex line. Every glyph is now sized by its wrapper, in one rule |
| 5 | **Every button on the phone frames ran 48 px past the screen's right edge** | the landing-page and canvas sets load *no* shipped stylesheet, so nothing was setting `box-sizing: border-box` — the first rule of both wf.css and the shipped page's own CSS |
| 6 | **The page behind the modal was legible through it** — "…on 2 devices" read straight through the sheet's heading | `backdrop-filter` breaks the screenshot render, and without it the shipped `--card-bg` is simply translucent. The sheet is now the three-layer stack composited by hand |
| 7 | **Copy → Copied changed the button's width**, shrinking the link box beside it | …while the CSS comment next to it claimed it did not. `min-width` now makes the sentence true |
| 8 | **"Copied" was washed out in the light theme** — `--ok` #2dd49a is 1.9 : 1 there | wf.css already solves this one line at a time for `.badge`; the promotion takes *its* value, `#128a5f` |
| 9 | **The caret was drawn after the placeholder**, which draws a field that already contains `ravilo.example.com` | an empty field's insertion point is at offset 0 |
| 10 | **"Trying to connect… ● ● ●"** said the same thing twice | the shipped string already ends in an ellipsis. The dots are gone; the dimmed fill is the second signal and it costs nothing |
| 11 | **The render failed outright on the longest host** | see §3 — this one is a requirement, not a drawing defect |

---

## 3 · A requirement the drawing found: the QR's version must follow the link's length

A QR at **version 3-M holds 42 bytes**. `https://` + host + `/join` is 13 bytes of fixed text, so
the code can carry a host of at most **29 characters**. The brief's `jelly.example.com` is 17 and
fits with room; a 37-character host does not, and the first render of this round *failed* on it
rather than drawing a wrong code.

**FR-2's shipped `QrEncoder.encode` must choose its version from the link's length.** If it is
fixed at one version, an operator with a long public hostname gets a sheet whose code cannot be
generated — and the failure is at render time, in front of the admin.

The round's own encoder (`iv-qr.js`, copied from the DEB-2319 round) is version-3-only, so the
admin sheet's longest-host frame uses a 27-character host and the setup screen's longest frame
uses the 37-character one. Both synthetic hosts are named and explained in `invite-data.js`.

**Both codes in these pictures were decoded back.** `verify-qr.py` beside the encoder reads the
matrix the other way round — format BCH in both copies, mask removal, all 26 Reed-Solomon
syndromes zero, byte-mode segment parsed back to the string — and both round-tripped exactly.
**A physical scan is still owed:** there is no camera on this seat and no decoder on this host, so
whether a phone reads the printed picture at 4 px per module is not something I can establish.

---

## 4 · Three sources disagree, and every disagreement is yours to settle

Drawn as a canvas table (`invite-canvas-strings.png`) so it can be looked at rather than argued.

| What | Shipped / spec | Brief | What I drew |
|---|---|---|---|
| `setup.server_prompt` | i18n **ships** *Enter your **jellystructure** server address*; DEB-5836 FR-3 replaces it with *Enter your Ravilo server address* | — | **the Ravilo wording.** SKILL-house says the shipped table wins, but a viewer never reads that word (owner, 2026-10-23) and the constitution outranks it. **DEB-5836 has not shipped**: the forbidden word is live in all three languages today |
| the page's part labels | *Open the Ravilo app · Get the app · Watch in your browser* | *Open in Ravilo · Get the app · Use Ravilo in the browser* | **the brief's**, with one frame in the spec's wording beside it |
| the page's part order | `browser · open · get-app · tv` | `open · get-app · browser · tv` | **all three**, as L1 / L2 / L3 |
| the account line | FR-2: *"must exist in **Jellyfin** first"* | *"a Jellyfin account on this server"* | ***"must exist on this server first."*** The admin reads this sentence out to a viewer, and a viewer never reads that word either. **Confirm or overrule me** |
| an iPhone button | FR-3 names Android, macOS, Linux, Samsung TV; **iOS is in the Out list** | *"Android, iPhone/web, desktop"* | **no iPhone button** — the browser part is the iPhone route, and it has its own label |

---

## 5 · Open questions

1. **Adopt L1?** If yes, FR-3 needs a dated amendment for the order (§1).
2. **The part labels** — the brief's or the spec's (§4). Whichever you pick, the other file changes.
3. **The account line's wording** (§4). I changed a word the spec wrote and I am telling you rather
   than hiding it.
4. **Is a local "Copied" allowed?** The admin *"renders server-pushed state only — no derived or
   optimistic state"* (constitution invariant 4). A clipboard confirmation is neither derived from
   server data nor optimistic about a server write, so I read it as outside that rule and drew it.
   It is the one thing on the sheet that is not server state, so you should say so out loud.
5. **Does the from-invite line survive a failed probe?** Drawn as yes — the field is still
   pre-filled from the link, and the not-found line below it is the new information. FR-6 does not
   say.
6. **Focus starts on Connect, not the field**, when the address arrives from an invite (§6 of the
   directions doc). FR-6 implies it — *"the viewer sees the host and presses Connect"* — but does
   not state it, and it is a one-press-versus-two decision.
7. **The longest-host stand-in is not in the brief.** SKILL-house requires the longest string to be
   drawn and the brief names one short host, so I invented two deliberately synthetic ones rather
   than leave the state undrawn. Flagged per PROCESS.md rather than passed off as a stand-in.

---

## 6 · Observations I am not fixing

- **The light theme's modal sits on a dark page.** `.pair-back` is `rgba(7,9,14,.66)` in *both*
  themes, so opening any admin modal in the light theme darkens the page behind it to roughly a
  dark-theme value. That is shipped behaviour, not this round's, and it is visible in
  `invite-admin-a1-light.png`. Worth its own row if the owner ever notices it; not this round's.
- **A focused Connect has no visible ring in Daylight.** The ring is the accent mixed towards
  white, which on an accent *fill* nearly vanishes. Visible in
  `invite-setup-phone-invite-daylight.png`. It is the shipped treatment and it predates this round.
- **Noir is the weakest skin for the new two-tone pair.** The invite line is Noir's amber accent
  and the warning is its accent-2 `#e0792f`; they are distinguishable but close.
  `invite-setup-tv-hidden-noir.png`. If that is too subtle, the cheapest answer is to give Noir's
  invite line `--ink` instead of the accent — a one-row change to the tone table.
- **White on the gradient button is low-contrast at the blue end.** That is the shipped
  `SingleSignOnPages` value and every app screen beside it uses white; this round did not change
  it. Noted once, as the DEB-2319 round noted it.

---

## 7 · Coverage against SKILL-house's state list

| State | Drawn? |
|---|---|
| Loading, and the slow case | **Yes** — *Trying…* on three surfaces. There is no separate slow case: one 8 s timeout, no intermediate state |
| Empty | **Yes** — the admin's unset sheet, and the setup field with only the new example in it |
| Error | **Yes** — the not-found line on three surfaces, and the page's 404 at two widths |
| The longest string | **Yes** — Faroese and Danish, plus two synthetic long hosts (§5.7) |
| Danish and Faroese | **Yes for the setup screen.** The landing page is **English only on the brief's instruction**; its drafts are in §8 of the directions doc and in `invite-data.js`. The admin console is English and is not in `i18n/` at all (FR-2) |
| 10-foot focus | **Yes** — every TV frame, one target each, named in the data |
| Offline / lost connection | **Deliberately not drawn.** FR-7 and R280 say the not-found line must *not* tell a typo from an offline device, and the setup screen holds no socket. A separate offline state would draw a distinction the spec forbids |
| The round's own states | **Yes** — all of the brief's, plus the unset sheet, the copied button, the no-`webBase` page, and the spec-wording page |
| Both admin themes | **Yes** — five frames each |
| TV skins | Aurora, Midnight, Noir. Graphite and Daylight on the phone; Daylight on the desktop |

---

## 8 · Housekeeping

- **Nothing in the brief's inputs had to be dropped.** No `STATUS.md`, nothing under
  `specs/research-reports/`, `scripts/` or `CLAUDE.md` arrived with it.
- **No network reference of any kind.** The three faces resolve to `design/app/fonts/` by relative
  path; the render has no network and would have failed otherwise.
- **The canvas carries no `backdrop-filter`, no `content-visibility`, no large blur and no
  animation.** The admin frames set `--blur: none` and `--ambient: 0` and approximate the glass
  flatly; the live admin keeps its glass.
- **No library title, no real host, no IP literal.** Every host is `example.com`; the two long ones
  are synthetic and named as such.
- **`iv-qr.js` and `verify-qr.py` are copies** of the DEB-2319 round's files, with their provenance
  in their own headers, because `design/incoming/<round>/` is pruned at adoption and a cross-round
  `<script src>` would stop rendering that day.
- **The setup component's geometry is carried unchanged** from round
  `2026-11-19-setup-hidden-characters`, so the two rounds' pictures of one component are diffable.

### Rendering these again

```sh
R() { scripts/render-design.sh --html design/incoming/2026-11-27-invite-and-setup/frames.html \
        --query "$1" --viewport "$2" --all .iv-hold --prefix invite- ; }
R '?set=admin&theme=dark'        1340x980
R '?set=admin&theme=light'       1340x980
R '?set=join'                    1200x1000
R '?set=setup-tv'                1980x1160
R '?set=setup-tv&lang=fo'        1980x1160
R '?set=setup-tv&lang=da'        1980x1160
R '?set=setup-tv&skin=noir'      1980x1160
R '?set=setup-tv&skin=midnight'  1980x1160
R '?set=setup-phone'             520x1000
R '?set=setup-phone&lang=fo'     520x1000
R '?set=setup-phone&skin=daylight' 520x1000
R '?set=setup-desk'              1280x840
R '?set=setup-desk&skin=daylight' 1280x840
R '?set=canvas'                  1300x1000
```
