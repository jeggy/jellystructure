# Phase R311 — Every Discover tab has the same heading size

> Owner, 2026-09-26: *"In ravilo, the heading for "Coming Soon" is different size compared to all the
> other tabs."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Client only (`ravilo-ui`: TV, phone, web), a
one-value change. **Numbering:** verified against `STATUS.md` the same day — Ravilo taken through
**R307**.

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

## Non-goals

- **The heading's text.** Coming Soon's heading reads `nav.upcoming` (*Upcoming* / *Kommende* /
  *Komandi*), Request's reads `seg.request`, and the walls' read `nav.discover`, while the mockup titles
  every tab *Discover* and lets the subtitle change. The owner raised only the size. See open
  question 1. The Faroese chip label *Komandi skjótt* is corrected separately, in R312.
- The subtitle, the segment bar, the Seerr pill.

## Acceptance

1. On the TV, move along the segment bar from Networks to Request and back: the heading is the same size
   on every tab, and nothing above or below the chips moves. The chip under focus stays where it was.
2. The same on the phone's Discover page and in the web app.

## Open questions

1. **Should the heading read *Discover* on every tab, as the mockup draws it?** Today it changes word
   with the tab (*Upcoming*, *Request*, *Discover*), which repeats the chip that is already highlighted
   just below. **Lean: yes, but only if the owner wants it.** It is a copy change, not the size fix
   asked for.
