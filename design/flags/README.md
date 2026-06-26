# Flag assets

99 country flags as 4:3 SVGs in `4x3/<iso-3166-1-alpha-2>.svg`
(source: [lipis/flag-icons](https://github.com/lipis/flag-icons), MIT licence).

## Use in HTML / CSS
`flags.css` (in the project root, one level above this folder) defines the base
`.fi` class plus one `.fi-<cc>` rule per flag. Keep `flags.css` next to the
`flags/` folder so the relative `url("flags/4x3/…")` paths resolve.

```html
<link rel="stylesheet" href="flags.css">
<span class="fi fi-dk"></span>   <!-- Denmark -->
<span class="fi fi-fo"></span>   <!-- Faroe Islands -->
```

Size them with ordinary CSS (`width`/`height`); the SVG fills via `background`.

## Audio-track language → flag (what the media-detail strip uses)
Tracks are tagged with an ISO-639-1 **language** code; flags are **country**
codes, so a small map bridges them. Tracks with no language tag are skipped;
if no track is tagged the strip is hidden. Max 5 flags shown, then `+N`.

```
en→gb  fr→fr  de→de  es→es  da→dk  fo→fo  is→is  no→no  sv→se  fi→fi
nl→nl  it→it  pt→pt  pl→pl  ru→ru  ja→jp  ko→kr  zh→cn  ar→sa  hi→in
```

(The full 99-flag set also covers most other countries directly by code —
`us`, `ca`, `br`, `in`, `cn`, `jp`, `au`, … — see `4x3/` for the complete list.)
