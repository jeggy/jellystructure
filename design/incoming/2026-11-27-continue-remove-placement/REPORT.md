# Where "Remove from Continue Watching" sits on the title page

Round slug `continue-remove-placement`, 2026-11-27. Android TV, the phone, and desktop / Ravilo Web.
Product design, so it lands under `design/incoming/`. Drawn for Bárður's brief.

**The package.** `Continue Remove Placement - Directions.html` is the document to read; 111 PNGs are
in `png/`; `frames.html` + `crp-data.js` + `crp-kit.js` + `continue-remove-placement.css` are what
the Kotlin is ported from.

**The shipped stylesheets are linked, not copied** — `ravilo.css` for TV, `mobile/ravilo-phone.css`
for the phone, `desktop-directions.css` + `desktop/ravilo-desktop.css` for desktop — so every token,
radius, weight and font in these pictures is the production one. Exactly one surface's stylesheet is
in a document at a time, selected by `?set`, because all three declare bare `.btn` / `.hero` /
`.detail` and loading two together is the class collision round 9 paid for.

The phone frames additionally link the **adopted** `2026-11-09-phone-downloads` round. The owner
amended that spec's FR-8 to *beside Play* (card `7c8bec51`), which made the shipped phone row
Play · Download · My List with My List reduced to a 44 dp icon and Download on a fixed width per
language. That is the row this round has to fit one more action into, so it is reused rather than
redrawn, and the two rounds' PNGs are diffable.

**Three options, not two.** The brief asked for the lean plus an overflow fallback on the phone if
the lean did not fit. It did not fit — and not only on the phone — so the round draws the lean (A),
the overflow (C), and a third the brief did not name (B), which is the one it recommends.

---

## The recommendation

**Ship B — "a line of its own".** The action row is left alone. On the phone and on desktop /
Ravilo Web the action is a full-width ghost control on its own line beneath the row. On the TV,
where there is room, it stays last in the row — which is exactly the brief's lean, kept wherever
the lean works.

It is the only option in which every label is whole on every surface in every language; the only
one that leaves Play alone; and the only one in which the restore really is the undo — the same
control, in the same place, with different words. Its entire cost is one more tap target on two
surfaces, and it costs the TV nothing at all.

---

## 1. The finding that decides the brief's question

**The brief's lean fails, and it fails more widely than the brief expected.** The brief asked
whether the phone row can take one more action *at Faroese width*. Measured, option A breaks in all
three languages, and on two surfaces rather than one.

Each figure is the whole row — every control's text in Sora at that surface's size and weight, plus
that control's padding, border and icon, plus the row's gaps — against the row's real width. The
study renders them (`study-widths.png`, `-da`, `-fo`) so each number can be checked against a
picture rather than trusted.

| Row | Box | en | da | fo | |
|---|---|---|---|---|---|
| TV film, action last (A and B) | 1792 | 1332 | — | 1373 | fits |
| TV series, action last (A and B) | 1792 | 1076 | — | 1111 | fits |
| Phone, the shipped row today | 372 | 296 | 255 | 297 | fits |
| **Phone, option A** | 372 | **568** | **484** | **577** | **over by 196–205** |
| Phone, option B's own line | 372 | 266 | 223 | 274 | fits |
| Desktop / Web, the shipped row today | 480 | 314 | 336 | 323 | fits |
| **Desktop / Web, option A** | 480 | **573** | **556** | **589** | **over by 76–109** |
| Desktop / Web, option B's own line | 480 | 243 | — | 250 | fits |

**Desktop and Ravilo Web were not expected to be a casualty and are.** `.hero .hb` has
`max-width: 480px` and that cap does not grow with the window — a 1440 px window has the same 480 px
of row as the shipped 1280 px "Large" preset. The brief's "the same row at desktop width" reads as
though width were the variable; it is not.

## 2. What the pictures showed that the markup did not

**a. On the phone, option A takes Play's word, not the new control's.** The arithmetic says "over by
205 px". The picture says Play renders as **"▶ …"** in English and Faroese, and "▶ Fo…" in Danish,
while the new action is itself cut to "Tak burtur ú …". Two labels destroyed to add one — and the
one destroyed first is the primary action the owner's own amendment protected with *Play must not
move*. `a-phone-film-remove-fo.png`, and the same in `-da` and the English frame.

**b. The phone action row is unreadable in the Daylight theme, today, before this round adds
anything.** `design/ravilo/mobile/ravilo-phone.css:184` is
`.btn.play { background: var(--ink); color: #0a0c13 }`, and line 14's Daylight skin sets
`--ink: #15171f`. That is `#0a0c13` text on a `#15171f` fill: a contrast ratio of **1.09:1**. It is
visible in every Daylight phone frame this round rendered — the Play pill is a solid dark rectangle
with no readable word in it, at thumbnail size.

- **Not caused by this round and not this round's to fix.** Reported because the light-theme pass
  the brief asked for is what exposed it.
- **The root cause is a rule that did not keep up.** `ravilo-phone.css` has
  `html[data-appearance="light"] .hcard, .mp { …dark tokens… }` — the house rule that a surface
  sitting on artwork or video stays dark in a light theme. The **detail page** sits on artwork too
  and is not in that list.
- **The desktop does not have it**, because `.lt .hero .hb` does re-declare white ink. Compare
  `b-desk-film-remove-fo-daylight.png` with `b-phone-film-remove-fo-daylight.png`.
- Worth its own task for Bárður. It is one selector.

**c. Option A on desktop first rendered as a wrapped second row** — a picture almost indistinguishable
from option B's. Two options that look the same and are not is the defect option D shipped in the
album-more round. It does not wrap now: `ravilo-ui`'s detail page is a Compose `Row`, a `Row` does
not wrap, and the last control ellipsizes. That is both the honest prediction and the picture that
tells the two options apart.

**d. Option C's TV panel and desktop menu both first covered the control that opened them** —
PROCESS.md's round-8 defect, twice in one round. The TV panel was centred and sat over the whole
action row; the desktop menu was positioned from a hand-typed offset and landed across the title and
half the synopsis. Both are now anchored to the row itself, so the row, the `⋯` and the title stay
visible. An option has to be judged in the shape its designer would ship, and C is not the
recommendation — drawing it badly would have made the recommendation worthless.

The TV panel took **two** goes: the second render used the row's lower edge (668) where it needed
the upper one (608), and still clipped the top of the row by 16 px.

**e. The desktop menu inherited the hero's white-on-artwork ink** and came out as white words on a
pale panel in Daylight — invisible. `.hero .hb` re-declares `--ink` to white; a menu is a surface of
its own and must declare its own. This file already carried that warning in a comment for the other
desktop control, and the menu walked into it anyway.

**f. One hard-coded Faroese string had leaked into the English measurement.** The Play label's
"47 min eftir" had been typed in rather than read from `fd.min_left`, so the English study row read
"Resume · 47 min eftir". In a round whose whole subject is how wide a language is, that is the one
bug that invalidates the figure it appears in. It is a key now.

**g. Looked at and cleared:** on the series page the current season's pill carries
`inset 0 0 0 2px var(--accent)` while the focused control carries an outset 4 px ring *plus* an
inverted fill — at 1× the two read similarly enough to check, but they are distinguishable and only
one thing in each frame is focused. No frame has round 11's "one outline drawn as two".

## 3. The strings, and a disagreement with the shipped i18n

Two new keys. The Danish and Faroese follow each file's own `row.continue` and `action.remove_list`:

| Key | English | Danish | Faroese |
|---|---|---|---|
| `action.continue_remove` | Remove from Continue Watching | Fjern fra Fortsæt med at se | Tak úr Halt fram at síggja |
| `action.continue_restore` | Show in Continue Watching | Vis i Fortsæt med at se | Vís í Halt fram at síggja |

**The frames draw a different Faroese from that table, deliberately.** The brief named its Faroese
stand-in exactly — *Tak burtur úr Halda fram at hyggja* — and a designer does not substitute a string
the briefer chose. But `i18n/fo.json` already has `row.continue` = *Halt fram at síggja* and
`action.remove_list` = *Tak úr mínum lista*, the shipped file wins wherever a string exists, and the
spec's own FR asks the new strings to follow `row.continue`. Read together they give
*Tak úr Halt fram at síggja* — **eight characters and 66 px shorter** than the stand-in.

Every frame is therefore drawn with the **wider** of the two, which makes the whole measurement
conservative: what ships will have more room than these pictures show, never less. Both widths are
in the study, as the *shipped vocabulary* rows. Reported, not resolved in the drawing.

## 4. Where the brief could not be met as written

- **There is no light theme on the TV.** The brief asks for "Aurora dark and the light theme" for
  the Android TV and phone surfaces. `ravilo.css` has Aurora, Midnight and Noir and no light skin;
  Daylight and Graphite are phone and desktop skins. The TV is drawn in **Aurora and Noir** instead
  — Noir being the one that inverts the accent to amber and therefore the real test of a focus ring
  — and the phone and desktop are drawn in Aurora and **Daylight**. Not a gap filled by invention;
  flagging it so the brief's checklist and the product agree next time.
- **The brief names no cast and no people.** The TV film frame's cast row therefore shows the
  corpus's generic roles (Lead Actor, Supporting, Guest, Director, Composer) rather than invented
  names. The round invents no person.
- **The two stand-in titles are the brief's and are the only invented names in the round.** Every
  other title, colour and set of initials comes from `design/ravilo/ravilo-data.js` through
  `RAVILO.grad()`, `RAVILO.initials()`, `RAVILO.castFor()` and `RAVILO.relatedFor()`.
- **The TV backdrops are dark.** `RAVILO.grad()` on these two invented titles produces a
  low-contrast gradient which, under the shipped `.hero-scrim`, leaves the top third of the TV frame
  almost flat. A real title has backdrop artwork there. It does not affect the action row, which is
  what the round is about, but it is why the TV frames look emptier than a real page.
- **The TV frame is the top 1080 px of a page that scrolls.** `.dhero` is 620 px and the section
  below it is clipped exactly where a viewer's screen clips it. The series frames show the Episodes
  header and the season pills and stop; the episode track is below the fold.

## 5. Open, for Bárður

1. **Which option.** B is recommended. A and C are both drawn in full and at their best.
2. **The Faroese string.** The spec's rule (follow `row.continue`) and the brief's stand-in
   disagree. The spec's reading is 66 px shorter and is almost certainly the right one — but it is a
   string decision, not a design one.
3. **The Daylight contrast defect on the phone action row** (§2b) needs a task of its own. One
   selector; it is not this round's and should not ride along in it.
4. **Does the TV really want it last?** It is the brief's lean, it fits, and it satisfies all three
   of the spec's focus promises. The alternative — right after Play — was not drawn, because it
   would move My List and Trailer, which the spec forbids. Saying so rather than leaving it silent.
5. **Nothing was dropped from the round's inputs.** No `STATUS.md`, nothing from
   `specs/research-reports/`, nothing from `scripts/`, no `CLAUDE.md`. No phase number and no task
   id appears in any drawing or any file name.

## 6. Fences

Run on the **staged** directory, as PROCESS.md requires. `check-deanonymization.sh`,
`check-phases.sh`, `check-css-scoping.sh` and `check-mobile-css.sh` — results in the commit message
of the receiving commit. No network reference of any kind in anything landed: the three faces
resolve to `design/app/fonts/`, and `ravilo.css`'s Google Fonts `@import` is answered from the same
directory by `scripts/render-design.sh` (DEB-2922). No `backdrop-filter`, no `content-visibility`,
no large blur and no infinite animation reaches the renderer; the shipped `.dback` blur is replaced
by the adopted round's flat `.pd-back2`, which these frames use.
