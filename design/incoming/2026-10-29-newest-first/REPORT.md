# Newest first — round report

Round `newest-first`, revision 1, 2026-10-29. Rannvá, for Bárður.
Source brief: DEB-2917. Kind of round: **product design** — every instance ships it, so it lands
under `design/incoming/` as `PROCESS.md` step 2 says.

30 PNGs in `png/`, the sources beside them, and
`Newest First - Directions.html` as the self-contained directions doc.

---

## What the round produced

Two directions for the listing-and-play question, plus the surfaces each one touches.

- **A — the *Newest first* bundle** (the brief's lean). One series-level flag that reverses the
  rail, labels every card by its air date, makes the primary button **Play newest · Thu 29 Oct**,
  removes Shuffle / Start over / *x of 12 watched*, and puts one mark on the hero saying why the
  list runs backwards. Drawn in all four viewer states, on TV and phone, in three languages and in
  the noir skin.
- **C — the viewer's own sort control.** Today's page plus a *Newest first / Oldest first* control
  on the Episodes header. Drawn in both positions on TV and once on the phone.
- **Today, reference only** — one frame of the page as it ships, in s1, so A's s1 has something
  exact to sit beside.
- Plus Home's Continue tile in three states on both surfaces, the end of an episode under both
  option A and option B, and the admin switch at both values in both themes.

Every number, date, code, count, duration and percentage in every picture is derived from
`nf-data.js`, which **refuses to load** if one of them disagrees with the brief. It carries 24
self-checks. One of them fired during the build and caught a real bug in my own model of decision
2's alternative rule (see *What only the pictures showed*, item 1).

---

## What only the pictures showed

The look-at-every-PNG pass, which is the lane's one human check. Eleven things, none of which any
fence can see. The first five changed the design; the rest changed the frames.

1. **The alternative Play rule looked viable in s4 and is not.** My first model of "play the newest
   *unwatched*" tested `!watched || secs > 0`, which matches every finished episode — so it appeared
   to have something to play when Anna is caught up. It does not, and that is the entire content of
   decision 2. The data file's self-check caught it before a frame was drawn. **`newest-a-tv-s4` is
   the frame that decides decision 2**, and it decides it because the alternative has no episode to
   name there at all; every fallback is worse than A's answer.

2. **A NEW ribbon on a never-started series is decoration, not information.** The first render put
   it on the newest card in s1. On a series nobody has started, every episode is equally new, so the
   mark lands on an arbitrary card. "New" means "newer than where you got to", which needs a place
   you got to. The rule is now: the newest episode, untouched, **and something older watched**.
   Compare `newest-a-tv-s1` with `-s2`.

3. **The hero said the date twice.** The state pill read *Resume Thu 29 Oct · 15 min left* directly
   above a button reading *Resume · Thu 29 Oct*. On today's shipped page both halves say a
   six-character code and the repetition is cheap; with a ten-character date, two pills a centimetre
   apart read as one fact smeared over two controls. The pill now owns the state word and the button
   owns the date. **The shipped page does repeat itself this way** — that is inherited, not
   introduced, and it is worth a look if A is adopted.

4. **Option C's *selected* state is weaker than its *focused* state, and that is a real problem.**
   Look at `newest-c-tv-c1`. The set value carries the shipped `.spill.cur` marker — a 2 px inset
   accent ring. The focused value carries the shipped `.spill.focused` marker — a full inverted
   white fill. A viewer moving focus onto the option they have *not* chosen sees the louder
   treatment on it and will read it as already selected. I drew c1 with focus on the **unset** pill
   precisely so this is visible; focusing the pill that is already set — the obvious reading of the
   brief — collapses the two states into one and shows neither. **If C is picked, this needs
   settling before it is built.** The cheapest remedy is already in the system: the shipped
   `.spill.done` / `.spill-check` family puts a tick inside the pill, which survives the focus fill.
   I have not drawn it, because which direction wins is the owner's call and not mine.

5. **The Continue tile's second channel is real but weak at 10 feet.** The brief asked whether there
   is a better way to say "a new one has arrived" than a label. There is one the row already owns:
   every other tile carries a filled progress bar and a brand-new episode has none, so the bar's
   **empty slot, drawn dashed**, says it by shape. `newest-a-home-tv-h1-alt` against
   `-h1`. Magnified it reads clearly; at 1× on a 1080p set at three metres a 5 px dashed rule is at
   the limit. **Treat it as a supporting channel, not the primary one** — the word stays the
   primary. On the phone (`newest-a-home-ph-h1-alt`) the slot is 4 px and weaker still.

6. **Every TV frame was being clipped 56 px short of 1920, and every phone frame 56 px short of
   412.** The element the renderer screenshots was a plain block, so it filled the canvas's padded
   content box rather than wrapping the frame inside it. The frames were the right size all along;
   the pictures were not. The only visible symptom was a caption label losing its last two
   characters at the right edge — which is how I found it. All 30 PNGs are now exactly 1920×1080,
   412×915 or the admin's own width.

7. **Option B's *Up next* card rendered as nothing at all.** `.pl-nextup` ships at `opacity: 0` and
   is revealed by `.player.nextup` on the parent. The first B frame was a player with no card in it
   — which is exactly what option A looks like, so the two frames said the same thing. Also fixed in
   the same pass: the card's two buttons (`player.play_in` and `player.watch_credits`, both shipped
   strings) do not fit side by side in the 402 px the shipped 760 px card leaves beside its 280 px
   still, and wrapped to two lines each, growing the card until its foot fell off the frame.
   **That fit problem is real, not a frame artefact** — it is what those two shipped labels do in
   that shipped card, and it is worth knowing if B is picked.

8. **Every phone PNG carried a one-pixel hairline of the light canvas backdrop along its bottom
   edge.** The frames sit at fractional y offsets, because the captions above them had fractional
   line-heights (1.6 × 14 px = 22.4), and Playwright rounds each element clip rect up and samples
   one row below the frame. Pale gray under a dark screen: invisible in a thumbnail, obvious at 4×.
   Fixed by making every line-height in the canvas furniture an integer pixel. A `line-height: 0` on
   the wrapper looked like the fix and was a trap — it inherits into the admin frames, whose
   `wf.css` does not set a line-height on every text node, and silently collapsed them by eighty
   pixels before I caught it.

9. **The phone's primary button was broken in three ways at once.** `ravilo.css` sizes `.btn svg` at
   24 px; `ravilo-phone.css` has no such rule, because no shipped phone button carries a glyph — so
   the play triangle took its intrinsic size and filled the button. `.btn` is `flex: 1`, so the long
   label and the `＋` shared the row equally and *Play newest · Thu 29 Oct* wrapped to three lines
   inside a 44 px control. **Both are real findings about putting a dated label on that button**, not
   frame artefacts. Fixed in the round's own CSS; `newest-a-phone-s1-fo` is the frame to magnify,
   because Faroese is the width to build to and it holds on one line.

10. **The NEW badge on a phone episode row was unstyled.** I reused the shipped `.merng`, which is
    scoped `.mestill .merng` — it belongs to the multi-episode triptych card, not to the 104×59
    `.methumb` — so the word rendered at body size over the picture. This is the `wf.css` class-collision
    failure mode round 9 hit, in the phone stylesheet: a class name that exists is not a class name
    that applies.

11. **Three frames said something twice.** Every Home Continue tile drew its title once over the
    artwork (`.tt`, the shipped no-picture fallback) and once below it (`.label`). `Havets Hjarta`
    appeared as both the Home hero and one of its own Continue tiles, four hundred pixels apart. And
    the player's kicker read *Dagbók · Wed 28 Oct* one line above a title that already begins
    *Dagbók*. All three now say each thing once.

---

## Findings outside the round's own drawing

Three things I found in shipped files while drawing. None is mine to fix; each is reported as data.

### 1. A light-theme contrast defect in the admin, measured

`.ep-added` — the "added {date}" line the shipped `series.html` appends to every episode row —
resolves to `--ink-dim`, which the light theme defines as `rgba(15, 17, 26, 0.25)`. Measured off
`newest-a-admin-oldest-light.png`:

| text | contrast against the light card |
|---|---|
| `aired Sun 18 Oct` (`--ink-dim`) | **1.75 : 1** |
| `28 min` (body ink) | 5.47 : 1 |

1.75:1 is far below the 4.5:1 minimum for body text and below 3:1 even for large text. **My frame
reproduces it deliberately** rather than quietly fixing it: the class is shipped, it is used far
beyond this card (`.jq-sub`, `.mkvh-p` and others take the same token), and drawing a fix nobody
asked for would hide a live defect. Raising it here is the whole of my part.

### 2. `render-design.sh` still cannot render a TV frame without a per-round workaround

`design/ravilo/ravilo.css` and `ravilo-player.css` each open with
`@import url('https://fonts.googleapis.com/…')`. `PROCESS.md` step 3 says to **link** the shipped
stylesheet rather than copy it, and separately says a mockup that loads Google Fonts is landed as
received. But `scripts/render-design.sh` fails the run on any off-host request and deletes the PNGs
it wrote, so the TV and player sets could not be rendered at all. Copying the file to strip one line
would break step 3 and put a stale copy of a **token file** in the tree — the exact shape of bytes
the nineteen wipes were made of.

I used the same four-line CSP meta that `2026-10-23-tv-signin` used, which makes Chromium refuse the
`@import` at the policy before it becomes a request. **That round raised this to you as its Q5 and it
is still open. This is the second round to need it**, which I think settles that it belongs in the
lane rather than in each round: either the script grows a flag for a shipped mockup's own `@import`,
or the two TV files lose the line the way the served admin CSS already has. A round that does not
know this cannot render the TV at all, and nothing tells it.

### 3. The shipped i18n already has most of what this round needs

Worth knowing before any spec is written, because it changes the size of the job:

- **`browse.sort.newest` and `browse.sort.oldest` already ship in all three languages** — *Newest
  first / Nýggjast fyrst / Nyeste først* and *Oldest first / Elst fyrst / Ældste først*. They are
  the exact words both the hero mark and the admin switch need. Used verbatim.
- **The brief's label `Thu 29 Oct` needs no new date string.** It composes from shipped
  `date.weekday_day_month` with `wd.abbr.*` and `month.*`, which gives *Hós 29. Okt* and *Tor 29.
  Okt* for free.
- `up.new_episode`, `tile.badge_new`, `action.resume`, `detail.episodes`, `detail.season`,
  `player.up_next`, `player.play_in`, `player.watch_credits` and `row.continue` all ship and are
  used as they are.
- **Only five strings are new**, and one of them is an accessible label. They are listed flat in the
  directions doc with both drafts.
- One disagreement, reported rather than fixed: **`detail.all_watched` ("All {n} episodes watched")
  is the wrong sentence for a newest-first series**, because it counts a set that is different
  tomorrow. A's s4 says *Caught up* instead, which is a new key.

---

## What was dropped, and why

- **No new CSS class for the admin switch.** The brief allowed one in `wf.css`; the shipped `.seg`
  fits exactly and is already in that card's head. The only new admin CSS is the row's geometry and
  a dashed rule marking the provenance line as the server's statement rather than part of the
  control.
- **The season picker in the admin card head.** The shipped page draws it because its stand-in has
  two seasons; Dagbók has one, and a segmented control with one segment is a control that cannot be
  operated.
- **A third line in the admin card restating the stored order.** An earlier revision had one and it
  put the same two words on the screen three times. The list below the control is the demonstration.
- **An age certificate on the hero.** The brief names the stand-ins exactly and does not name one.
- **Nothing arrived in the round's inputs that is forbidden under `design/`** — no `STATUS.md`,
  nothing from `specs/research-reports/`, nothing from `scripts/`, no `CLAUDE.md`.

## Caveats a reviewer should know

- **`backdrop-filter` is approximated.** Four shipped components use it and it breaks the renderer.
  Each is drawn as a flat fill at the same perceived density; the live product keeps its glass.
  The next-up card needed `.985` rather than its shipped `.95`, because without the 20 px blur the
  seek row's `−0:24` read straight through the card as legible text, which the real player never
  shows.
- **The TV hero is shortened to 560 px in the frames only.** The real detail page scrolls, and the
  shipped 620 px hero plus the shipped `.dsec` padding puts the rail's second label line about 20 px
  below 1080. A picture cannot scroll and this round's whole question is what the rail looks like
  next to the button, so both halves have to be in one frame. **560 is not a proposed hero height.**
- **No device access**, so nothing here has been seen on a real television. The TV frames are drawn
  at 1920 and measured at 1920, which is what `SKILL-house.md` asks, but the 10-foot judgements in
  item 5 above are made from magnified pixels and not from a sofa.

## Open questions for you

1. **Decisions 1–4 are the owner's** and the pictures for each are named in the directions doc. The
   card's options should carry the same names as the comment's headings.
2. **If C is picked, finding 4 needs settling first** — its selected state is quieter than its
   focused state, and a viewer will misread it.
3. **Finding 2 is a lane question, not a round question**, and it is the second time it has been
   raised. I cannot fix it: it is `scripts/` either way.
4. **Finding 1 is a production CSS change** well outside this card. Wherever it goes, it is not a
   design round.
