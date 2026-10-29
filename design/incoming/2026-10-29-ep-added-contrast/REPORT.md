# `.ep-added` contrast — round `ep-added-contrast`, revision 1

2026-10-29. A fix round, not a direction round: nothing here asks anyone to choose. It carries the
one-line production change and the two pictures that measure it.

## What shipped, and where

`design/app/detail.css` gains one rule beside the existing `.ep-added`:

```css
.ep-added { color:var(--ink-dim); }
[data-theme="light"] .ep-added { color:var(--ink-soft); }
```

That is the whole fix. `detail.css` is in the `syncDesignAssets` include list, so the edited file is
the file the admin serves.

## The defect, confirmed on the shipped surface

The brief located `.ep-added` from the shipped `series.html` mockup, where the span is appended
inside `.ep-meta .s`. In the **admin that actually runs**, it comes from a different place:
`src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt:1713` puts
`<span class="tiny ep-added">added …</span>` **directly into the `.ep-toggle-row`**, whose
background is `--fill-2`. `.tiny` is `font-size` only and nothing else on the row sets a colour, so
`--ink-dim` reaches the pixels unchanged. The defect is real on the running surface, and it is
slightly worse there than in the mockup, where the span inherits `--ink-soft` from its parent.

Measured off the rendered elements (WCAG 2.x relative luminance, the token's alpha composited over
the row's own `--fill-2`), not asserted:

| theme | token | effective ink | on surface | ratio |
| --- | --- | --- | --- | --- |
| light, before | `rgba(15,17,26,.25)` | `#babcc2` | `#f3f5fa` | **1.74 : 1** — fails |
| light, after | `rgb(93,99,120)` | `#5d6378` | `#f3f5fa` | **5.47 : 1** — passes |
| dark, unchanged | `rgba(221,225,236,.32)` | `#5c606b` | `#20242f` | **2.48 : 1** |

1.74 is the brief's 1.75 to rounding, and 5.47 is exactly the brief's figure for the body ink beside
it — which is the confirmation that `--ink-soft` *is* that body ink.

## Why this target and not the other one

The brief offered two routes and asked for the smaller blast radius.

- **Raising `--ink-dim` in the light theme** reaches roughly a hundred use sites across `wf.css`,
  `detail.css`, `music.css`, `ravilo-builders.css`, `metadata.css`, `ai.css` and more. Most of them
  are not text that has to read — drag grips, chevrons, idle dots, mono path fragments, the `·`
  separators, the `off` state of a toggle. Making all of those darker is a visible change to every
  admin page, and a change a viewer would notice is an owner decision rather than a fix.
- **Retargeting the one class** to `--ink-soft` is one selector, one existing token, and it changes
  nothing anywhere else.

`--ink-soft` is the brief's "existing dimmer-but-legible token": it is dimmer than `--ink` (which is
`#181a22` and reads about 15 : 1) and it is what the row's own sibling text already uses.

## What only the picture showed

The dark frame's first render drew the episode code, the title and the `DAN` lang badge in near-black
on near-black. That was **this round's scaffolding, not production** — the canvas sets dark ink for
its light-gray backdrop and the frame inherited it instead of taking the admin's `--ink`. Fixed in
`ep-added.css` and re-rendered; the note is in the file at the rule. Worth recording because it is
the failure mode the look-at-every-PNG step exists for: the light frame was correct and would have
been reported as a clean pair.

## Open — for Bárður

1. **The dark theme fails too, at 2.48 : 1.** The brief says the dark theme is not affected and to
   leave it as it is; measured, it is affected — just less. I have honoured the brief literally: the
   fix is scoped to `[data-theme="light"]` and the dark pixels are byte-identical to what ships. If
   you want dark fixed as well it is the smallest change in this file — delete the theme scope, so
   the rule reads `.ep-added { color:var(--ink-soft); }`, and dark goes from 2.48 : 1 to 5.95 : 1. I
   did not do that unasked because it changes a surface the brief told me not to touch. One word
   either way; your call.
2. **`--ink-dim` is below 4.5 : 1 in both themes everywhere it is used**, by construction — it is a
   25 %/32 % alpha. Most of its ~100 sites are decoration and do not need to pass, but some are text
   a person reads: `.jq-sub` (a queue row's subtitle), `.ep-ts .k` (the timestamp group labels),
   `.ud-id`, `.ud-note`, `.ud-st`, `.mu-cell .yr`, the "No language matches" and "No season N" empty
   states. That is a sweep, not this task, and it wants a brief of its own — probably "a second dim
   token that is legible, and a pass over which sites are decoration and which are text". Raised
   here so it does not stay buried in a round report a second time.

## The files

- `frames.html` — one document, `?theme=light | dark`. Links the shipped `wf.css`, `app.css` and
  `detail.css` and copies no token.
- `ep-added.css` — scaffolding only, `.ea-*`, nothing production-bound. It also re-pins the defect
  on the `before` half, since `detail.css` now carries the fix.
- `ep-added.js` — the row transcribed from `MediaDetail.kt`, and the measurement.
- `png/ep-added-light.png`, `png/ep-added-dark.png`.

The stand-in episode is **Mountain Road**, `S02E05`, added `2026-09-14`, audio `dan`/`eng` — an
invented title that names nothing in the household's library. The still is a flat gradient and never
a broadcaster's picture.
