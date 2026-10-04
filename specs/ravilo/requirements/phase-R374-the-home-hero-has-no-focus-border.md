# Phase R374 — The Home hero has no focus border

> Owner, 2026-10-04: *"Recently we've started seeing a border around the carousel when it's in focus, this makes it
> ugly. Let's remove this border again, just like there was no border some days ago."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from the owner's direction. Client only (`ravilo-ui` commonMain) plus
the design mockup; no string, DTO, backend or config change. **Reverses R350 FR-R350-8's first bullet only** (the
Home hero's inset ring). The other rings FR-R350-8 added stay: a focused `RaviloButton`, the synopsis and the
trailer overlay's Close.

## What changed, and when

R350 (built 2026-10-02, commit `4010f445`) found that Home opened with focus on the hero and nothing marked it, and
added the design's `.hero-hit.focused` inset ring: a 2 dp `colors.focusRing` border drawn over the whole hero while
it holds focus (`HeroCarousel.kt:119`, `:189-190`, `:381`). Home opens with focus on the hero, so this border is
the first thing every viewer sees on a TV, and it frames the backdrop like a selected tile. Before R350 the hero had
no focused state, and the owner prefers that.

## Requirements

### FR-R374-1 — No border on the hero
`HeroCarouselContent` draws no border, ring or outline when the hero is focused, on any platform (TV, desktop on
the keyboard, phone). Remove the `heroFocused` state, its `onFocused`/`onBlurred` callbacks on `dpadFocusable`, the
`matchParentSize().border(...)` box and `HERO_FOCUS_RING_TAG`. Focus behaviour is unchanged: the hero is still
the focus target on arrival (R53); Left/Right still change the slide, and Up/Down/OK do what they do today.

The same `HeroCarousel` renders a channel's hero (`ChannelScreen.kt`), so that loses the border too.

### FR-R374-2 — The other R350 rings stay
`RaviloButton`'s ring, `DetailSynopsis`'s ring and the trailer's Close ring are untouched. Those are small targets,
where a ring is what shows focus; the hero is the whole top of the screen.

### FR-R374-3 — The design mockup follows
`design/ravilo/ravilo.css:340` (`.hero-hit.focused { box-shadow: inset 0 0 0 4px var(--ring); }`) is removed (or
set to `box-shadow: none`), so the mockup and the app agree. That is a design-side edit; it goes back with the next
design export.

### FR-R374-4 — Tests
`HeroFocusRingTest` turns around: the focused hero draws **no** ring (assert there is no border node, for example
by removing the tag and asserting nothing with the old tag exists), and Down still moves focus to the row below.
Rename it `HeroNoFocusRingTest` or keep the file and change the test name; either is fine.

## Acceptance

1. On the TV, Home opens with focus on the hero and no border around the carousel; Left/Right change the slide, Down
   goes to the first row, Up goes to the app bar, as before.
2. A channel's hero shows no border either.
3. A focused button on a detail page, the synopsis and the trailer's Close still show their rings.
4. The hero test is green with the reversed assertion.
