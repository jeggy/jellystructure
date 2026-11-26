# An open admin popup is drawn above the page

Task: DEB-5688

> Owner, 2026-11-26, with a screenshot of a title page: *"Once again is this dropdown behind stuff"*. The Tags card's
> add-a-tag list is open, the Collections card under it is painted over the list, and the card below that shows through
> what is left of it.

## Scope

The admin frontend only: `design/app/wf.css` and the popups in `src/wasmJsMain/kotlin/dev/jellystructure/ui/`. No
backend, no wire change, no Ravilo client. It changes no layout, spacing or copy. A popup that was hidden under the next
card is now drawn over it. It supersedes the one-card lift DEB-1529 added for the Continue Watching tag picker
(`36f7950f5`, `wf.css` `.card:has(.cw-pick)`), which FR-3 folds into one rule.

## Why it keeps coming back

- Every `.card` and `.box` has `backdrop-filter` (`wf.css`, *generic surfaces*). That makes each card its own stacking
  context. A popup's `z-index` then only ranks it among things inside its own card. The next card on the page is painted
  over the whole card, popup included, and it takes the popup's clicks.
- The title page's tag list (`#tag-dropdown`, `MediaDetail.kt` `tagsChipsHtml`) is `position:absolute; z-index:50` inside
  the Tags card. Since DEB-4075 (`0f9304175`) the Collections card (`#jc-section`) comes straight after the Tags card, so
  any list taller than the space left in the Tags card goes under the Collections card.
- The list is a `.card` itself. `--card-bg` is 74 % opaque, and a blur nested inside another card's blur cannot reach the
  page behind it, so whatever is painted over or under the list shows through.
- The genre suggestions on the same page (`#genre-suggest`) are built the same way, an absolute box with its own
  `z-index` inside a card.
- DEB-1529 hit the same thing on 2026-10-17 and lifted that one picker's card. Each popup since has had to remember to
  do the same, and this one did not. The requirement is therefore one rule that every popup is held to, not a second
  one-card fix.

## Requirements

**FR-1 — An open popup is drawn above the page.** Any admin popup that is open is painted above every card, box and page
section on its page, and its rows take their own hover and clicks. This covers menus, suggestion lists, pickers and help
or reason popovers. It stays under the sticky Ravilo page bar (`z-index: 50`), the phone-width top bar (`70`) and every
modal, dialog and overlay.

**FR-2 — Nothing shows through a popup.** A popup's surface is opaque: `var(--fill)` with `.menu`'s border, radius and
shadow. A popup is never a `.card` or a `.box`. Those classes are for surfaces that sit on the page, and their
translucent glass is wrong for something drawn over it.

**FR-3 — One lift rule and one marker, in `wf.css`.** One rule lifts the `.card`, `.box` or `.rav-tab-section` that holds
an open popup, and every such ancestor of it: `position: relative; z-index: 30`. It carries a comment saying why
(`backdrop-filter` makes each card its own stacking context).
- Code that opens a popup by setting an inline style or by inserting it gives the popup the class `pop-open` while it is
  shown, and removes the class when it hides the popup.
- A popup opened by a class it already has is named in that same rule. Those are `.menu-wrap.open`, `.split.open` and
  `.lmenu.open`, and `.cw-pick`, which exists only while it is open. FR-4's check added `.rc-add.open` (Settings' add-region
  menu), `.sp-menu.open` (the season picker), `.chk-pop.open` (a blocked check's reason), `.mu-fc.open` (a music filter
  facet) and `.help-dot:hover` (a help tip, which is hover-only and has no class to name).
- The marker is set and cleared by one helper, `HTMLElement.setPopup(show, display)` in `ui/Popup.kt`, so no call site
  writes `style.display` for a popup by hand. It is used by the title page's tag list and genre suggestions and by the Ravilo
  Discover page's add-row menu (`#req-addmenu`).
- The `.card:has(.cw-pick)` line folds into this rule.

A new popup is covered by using the marker. It never needs a lift line of its own.

**FR-4 — Every popup meets FR-1 and FR-2, not only the reported one.** The build checks each popup class in
`design/app/*.css` and each inline popup in `src/wasmJsMain`. Each one is either inside no card, or is covered by FR-3.
The commit names the ones it changed. The reported two come first: the title page's add-a-tag list and its genre
suggestions.

**FR-5 — Proof.** A Playwright case on a title page holds this. The collections answer is routed so the Collections card is
drawn, as it is on prod, and enough in-scope jellystructure tags are routed that the open tag list runs past the bottom of
the Tags card.
- With the tag input focused, `document.elementFromPoint` at the centre of the list's first and last rows lands inside the
  list.
- A click on the last row adds that tag.
- The same holds for the genre suggestions.
- `ravilo-continue-hide-tags.spec.ts` stays green after `.cw-pick`'s line is folded in.

## What this does not do

- **It does not move a popup out of a scroller that clips it.** A menu inside `.mu-scroll` is cut off by `overflow`, not
  hidden by stacking. That is DEB-3907, fixed with fixed positioning on its own branch.
- **It does not take the blur off the cards.** Removing `backdrop-filter` from `.card` would also end every case at once,
  but it changes the look of every card in the admin. That is a design call this bug does not need.
- **It does not mount every popup on `<body>`.** `LanguagePicker.kt` and `TrackEditor.kt` already do this, with
  `position: fixed`. It escapes clipping as well as stacking, but each popup then has to place itself again on every
  scroll and resize. It stays the tool for a popup that a scroller clips.
