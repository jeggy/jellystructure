# Phase R311 — Every Discover tab has the same heading size

> Owner, 2026-09-26: *"In ravilo, the heading for "Coming Soon" is different size compared to all the
> other tabs."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client only (`ravilo-ui`: TV, phone, web) plus one string key removed. **The open question
was decided the same day**: the owner handed the calls over (*"You just decide for me. We want all best
solutions for everything"*), so the heading's words change too (FR-R311-3). **Numbering:** verified
against `STATUS.md` the same day — Ravilo taken through **R307**.

## What is wrong

`DiscoverScreen.kt` draws the page heading above the segment bar with:

```kotlin
fontSize = if (segment == DiscoverSegment.COMING_SOON) 26.sp else 22.sp,
```

So Coming Soon's heading is 26 sp, and Request's and the three walls' headings are 22 sp.

The difference came in with **R262** (`498f3347`, 2026-09-18), which merged three Discover screens into
one frame: the old Coming Soon screen had drawn its heading at 26 sp and the old Request and taxonomy
screens at 22 sp, and the merge kept both as a conditional. It is more than a mismatch:

- **The frame moves on a chip press.** R262 FR-R262-1 says the header and the segment bar are composed
  once and only the content region changes when a chip is pressed. With two heading sizes, the header is
  a few dp taller on Coming Soon, so the subtitle and the whole segment bar shift down when you arrive
  there and back up when you leave.
- **The mockup has one size.** `design/ravilo/ravilo.css` draws the Discover heading and the Coming Soon
  heading from two rules with the same value (`.dischead-row h1` and `.uphead-row h1`, both 52 px on the
  1920 px canvas).

The Request tab's Seerr pill sits in the same row as the heading (about 34 dp tall against the heading
column's ~46 dp), so it does not change the header's height.

## Requirements

**FR-R311-1 — One heading size on every tab.** The heading is **22 sp** on all five segments: the size
four of the five already use, and the one the owner compares Coming Soon against. The same weight
(Bold) and font (Space Grotesk) on every tab. The conditional is removed, not re-valued, so there is one
value to change if the size is ever revisited.

**FR-R311-2 — The frame does not move on a chip press.** Switching between any two segments leaves the
subtitle and the segment bar exactly where they were, on the TV, the phone and the web app. This is the
acceptance of R262 FR-R262-1, restated for the header's height.

**FR-R311-3 — The heading reads *Discover* on every tab.** The heading is the page's name,
`nav.discover` (*Discover* / *Opdag* / *Uppdaga*), whichever chip is selected. The subtitle beneath it
keeps saying what the selected tab holds (`up.subtitle`, `request.sub`, `tx.sub_*`, unchanged). This is
how the mockup draws every tab (`dischead-row h1` and `uphead-row h1` both read `nav_discover`). With the
size fixed, the heading becomes one thing that never changes on a chip press, as FR-R262-1 means the
frame to be. `discoverHeaderTitle` goes. It was the only reader of `nav.upcoming` (checked across every
client, 2026-09-26), so the key is deleted from `en.json`, `da.json` and `fo.json` in the same commit,
and `i18n/lexicon/*.txt` is regenerated with it (`check-i18n-spelling.sh --update-lexicon`). A dead key
is where the next stale translation hides.

## Non-goals

- The subtitle's text, the segment bar, the Seerr pill. The Faroese chip label *Komandi skjótt* is
  corrected separately, in R312.

## Acceptance

1. On the TV, move along the segment bar from Networks to Request and back: the heading reads
   *Discover* (*Uppdaga* in Faroese) at the same size on every tab, only the subtitle changes, and
   nothing above or below the chips moves. The chip under focus stays where it was.
2. The same on the phone's Discover page and in the web app.

## Decisions (2026-09-26, delegated by the owner)

1. **The heading reads *Discover* on every tab** (FR-R311-3). A heading that changes word with the tab
   repeats the chip that is highlighted just below it, and on Coming Soon it said *Komandi* above a chip
   saying *Kemur skjótt* (R312): two words for one tab, stacked. The mockup has always drawn one
   heading, and a heading that never changes is the other half of "the frame stands still".

## Dev review (2026-09-26, against `main` `0e5e434f`)

1. **The whole change is in `DiscoverScreen.kt`.** The conditional size is at `:143`. The title comes
   from `discoverHeaderTitle(segment)` (`:243-247`), which becomes a single `str("nav.discover")` at the
   call site; the function goes. The subtitle function (`:250-254`) stays. The phone renders the same
   frame, so no handset branch is needed.
2. **`nav.upcoming` has exactly one reader:** `DiscoverScreen.kt:244`. Checked across `ravilo-ui`,
   `ravilo-receiver-core`, `ravilo-screen`, the cast receiver and the scripts. Delete it from `en.json`,
   `da.json` and `fo.json` in the same commit, and run `check-i18n-spelling.sh --update-lexicon`. Keys
   are looked up by string, so nothing else refers to it.
3. **FR-R311-2 needs no new code.** One size and one text is what keeps the frame still. The acceptance
   is a device check (the TV and the Pixel 9), plus the web.

**Net effect.** One file, three lines of Kotlin, one key removed in three files, the lexicon regenerated.
