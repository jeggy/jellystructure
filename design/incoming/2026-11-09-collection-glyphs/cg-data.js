/* Collection popover glyphs — the round's ONE data file.
   PROCESS.md: "Derive every number in every picture from one array." Every frame, the directions
   doc and the anatomy callouts read from here, so two pictures cannot disagree.

   Nothing here is instructions. It is numbers, invented titles and colour values.

   The palettes are the SHIPPED Compose ones, copied value for value out of
   ravilo-ui/.../ui/theme/Colors.kt — which is Ravilo's production truth, since no Gradle task
   consumes design/ravilo/. ravilo.css carries only aurora / midnight / noir; graphite and daylight
   exist in Compose and in design/ravilo/desktop-directions.css, so the five live here together. */
(function () {
  'use strict';

  // ---------------------------------------------------------------- themes (Colors.kt, verbatim)
  const THEMES = [
    { id: 'aurora', name: 'Aurora', background: '#0A0C13', surface: '#161A28', surfaceVariant: '#1B2031',
      accent: '#7B6EF0', onAccent: '#FFFFFF', text: '#F3F4FB', textSecondary: '#AEB4CB', textDim: '#6B7290',
      focusRing: '#8E82FF', card: '#0E111B', tileRadius: 12, isLight: false },
    { id: 'midnight', name: 'Midnight', background: '#04101A', surface: '#0A1C28', surfaceVariant: '#0E2533',
      accent: '#19D6C6', onAccent: '#FFFFFF', text: '#EAFCFF', textSecondary: '#9FC2CF', textDim: '#5A7D8A',
      focusRing: '#28E6D6', card: '#07151F', tileRadius: 14, isLight: false },
    { id: 'noir', name: 'Noir', background: '#080807', surface: '#16140F', surfaceVariant: '#1F1C15',
      accent: '#F5B542', onAccent: '#08070A', text: '#F7F3EA', textSecondary: '#C7BFAE', textDim: '#807868',
      focusRing: '#FFCF6B', card: '#0D0C0A', tileRadius: 6, isLight: false },
    { id: 'graphite', name: 'Graphite', background: '#1C1C1F', surface: '#2A2A2E', surfaceVariant: '#323237',
      accent: '#4F8EF0', onAccent: '#FFFFFF', text: '#F2F2F4', textSecondary: '#B4B4BB', textDim: '#85858D',
      focusRing: '#4F8EF0', card: '#242428', tileRadius: 10, isLight: false },
    { id: 'daylight', name: 'Daylight', background: '#FBFBFD', surface: '#F1F2F6', surfaceVariant: '#E8EAF0',
      accent: '#5B4EE0', onAccent: '#FFFFFF', text: '#15171F', textSecondary: '#4A4F63', textDim: '#6D7286',
      focusRing: '#5B4EE0', card: '#F1F2F6', tileRadius: 12, isLight: true },
  ];
  const theme = (id) => THEMES.find(t => t.id === id);

  // ---------------------------------------------------------------- metrics, in dp / sp
  // Everything the round FIXES (FR-9: size, ink, focus-state ink, position) plus the shipped
  // numbers it must not disturb. The shipped ones are read off SeededBrowseScreen.kt's FacetPopover
  // and facetPopoverWidth; they are marked `shipped`.
  const M = {
    // --- what this round fixes ---
    glyph: 18,          // the glyph's box, dp. Square. 24-unit grid inside it.
    glyphStroke: 0,     // none: every part of both glyphs is a filled silhouette (see GLYPHS)
    gapAfterGlyph: 8,   // dp between the glyph slot and the name
    // --- shipped, unchanged by this round ---
    checkSlot: 18,      // shipped: Box(Modifier.width(18.dp)) holding a 14 dp CheckGlyph
    check: 14,          // shipped
    rowPadH: 10,        // shipped
    rowPadV: 8,         // shipped
    rowRadius: 8,       // shipped
    rowLine: 20,        // 14 sp Sora at Compose's default line height, rounded
    popPad: 12,         // shipped
    popRadius: 12,      // shipped
    popMaxH: 280,       // shipped: LazyColumn heightIn(max = 280.dp)
    popMinW: 220,       // shipped: coerceIn(220, 420)
    popMaxW: 420,       // shipped
    nameSp: 14,         // shipped
    countSp: 13,        // shipped
    // --- the facet bar, shipped ---
    chipPadH: 14, chipPadV: 8, chipRadius: 18, chipFontSp: 14, chipGap: 10,
    hPadTv: 48, hPadPhone: 20, appBar: 60,
  };
  // shipped: facetPopoverWidth = (maxLen * 8 + 110 + allowance).coerceIn(220, 420)
  // AUDIO already takes an allowance of 28 for its flag; CHANNEL needs the same for its glyph.
  const GLYPH_ALLOWANCE = 28;
  function popoverWidth(labels, allowance) {
    const maxLen = labels.reduce((a, s) => Math.max(a, s.length), 8);
    return Math.min(M.popMaxW, Math.max(M.popMinW, maxLen * 8 + 110 + allowance));
  }

  // ---------------------------------------------------------------- the stand-in rows (brief)
  // `ravilo` = an operator-built ChannelConfig, a big category. `picked` = a Jellyfin collection,
  // a small hand-made list. Ordered by count, as every facet is, so the kinds are mixed.
  const ROWS = [
    { name: 'Serier', count: 41, kind: 'ravilo' },
    { name: 'Sagaer', count: 38, kind: 'ravilo' },
    { name: 'Tidsmaskinen', count: 27, kind: 'ravilo' },
    { name: 'Christmas films', count: 9, kind: 'picked' },
    { name: 'Film club', count: 6, kind: 'picked' },
    { name: 'Rainy Sunday', count: 4, kind: 'picked' },
  ];
  // The longest-string state. The popover's ROWS are operator data — a collection's name is typed
  // once by the operator and shown in every language — so the three languages do not vary the rows
  // at all. What varies is length. The clamp is 420 dp; at 420 dp the name box is
  // 420 - 24 (popover) - 20 (row) - 18 (check) - 18 (glyph) - 8 (gap) - 26 (count) = 306 dp, which
  // is about 43 characters of 14 sp Sora. These two are side of that line. Invented, in the same
  // register as ravilo-data.js's corpus.
  const ROWS_LONG = [
    { name: 'Norðurlendskar krimiseriur og spenningsfilmar frá 90-árunum', count: 52, kind: 'ravilo' },  // 58 — ellipsises
    { name: 'Norðurlendskar krimiseriur og spenningur', count: 38, kind: 'ravilo' },                     // 40 — fits
    { name: 'Juleaften med hele familien', count: 9, kind: 'picked' },                                   // 27 — fits
  ];

  // ---------------------------------------------------------------- the glyphs, on a 24-unit grid
  // Primitives only: no paint server, no gradient, no <defs> — so nothing can be defined inside a
  // hidden frame and paint nothing in a visible one (PROCESS.md's SVG-id trap). Coordinates are
  // grid units; `sw` is a stroke width in grid units; `fill: true` means a filled silhouette.
  const GLYPHS = {
    // --- Ravilo collection ---
    // "the stack": three plates of EQUAL height, widening downward, with gaps as deep as two
    // thirds of a plate. The equal heights are the whole design: render 1 drew two thin bars over
    // one tall card and it read as a lidded cup, not a pile. Ink 182 of 576 square units.
    stack: { label: 'Stack', parts: [
      { rect: [7.0, 3.2, 10.0, 4.0], r: 1.6, fill: true },
      { rect: [4.4, 10.0, 15.2, 4.0], r: 1.6, fill: true },
      { rect: [1.8, 16.8, 20.4, 4.0], r: 1.6, fill: true },
    ] },
    // "the wall": six poster plates. The literal reading of a big category. Ink 242.
    wall: { label: 'Wall', parts: [
      { rect: [2.8, 3.6, 5.6, 7.2], r: 1.2, fill: true },
      { rect: [9.2, 3.6, 5.6, 7.2], r: 1.2, fill: true },
      { rect: [15.6, 3.6, 5.6, 7.2], r: 1.2, fill: true },
      { rect: [2.8, 13.2, 5.6, 7.2], r: 1.2, fill: true },
      { rect: [9.2, 13.2, 5.6, 7.2], r: 1.2, fill: true },
      { rect: [15.6, 13.2, 5.6, 7.2], r: 1.2, fill: true },
    ] },

    // --- hand-picked collection ---
    // "the tag": a filled label pointing right, with its hole near the flat edge where every tag
    // icon puts it. One solid mass against the stack's three strata — the pair differs in how the
    // ink is distributed, not in detail, which is the only difference that survives 3 m.
    // Ink 195, matched to the stack's 182. The hole is an even-odd sub-path.
    tag: { label: 'Tag', parts: [
      { poly: [[3.0, 4.8], [13.6, 4.8], [21.4, 12], [13.6, 19.2], [3.0, 19.2]], fill: true,
        hole: { c: [7.4, 12], r: 2.1 } },
    ] },
    // "the pin": a head and a stem. Vertical and narrow. Ink 96 — the lightest of the six.
    pin: { label: 'Pin', parts: [
      { circle: [12, 8.4, 4.8], fill: true },
      { rect: [10.6, 12.0, 2.8, 8.6], r: 1.4, fill: true },
    ] },

    // --- drawn and rejected: both collide with a meaning the app already ships ---
    star: { label: 'Star', parts: [{ star: [12, 12.6, 9.6, 4.1], fill: true }] },
    ribbon: { label: 'Bookmark', parts: [
      { poly: [[6.5, 3.2], [17.5, 3.2], [17.5, 20.8], [12, 16.6], [6.5, 20.8]], fill: true },
    ] },
  };

  // ---------------------------------------------------------------- the three directions
  const DIRECTIONS = [
    { id: 'A', name: 'Stack / Tag', recommended: true, ravilo: 'stack', picked: 'tag',
      gives: 'The two silhouettes share no axis: strata lying flat against one solid mass with a point. ' +
             'It is the pair that still separates when the glyph is four pixels of apparent detail.',
      costs: 'A right-pointing filled pentagon is a tag everywhere, but it is also the direction a filled ' +
             'triangle points; nothing in this popover draws a play triangle, so the clash is theoretical.' },
    { id: 'B', name: 'Stack / Pin', recommended: false, ravilo: 'stack', picked: 'pin',
      gives: 'Changes only the hand-picked glyph. "Pinned by hand" is the closer word for what the ' +
             'collection is, and a head-on-a-stem is the most distinct silhouette of the six.',
      costs: 'Carries the least ink of any glyph here (96 units against the stack\'s 182), so the column ' +
             'reads unevenly; and a round head over a tapering stem is read as a map pin — place, not choice.' },
    { id: 'C', name: 'Wall / Tag', recommended: false, ravilo: 'wall', picked: 'tag',
      gives: 'Changes only the Ravilo glyph. Six plates is the literal picture of a big category, and it ' +
             'is the heaviest, so the Ravilo rows anchor the column.',
      costs: 'Six tiles fuse into one block at TV distance — the frame at 0.55x shows it — and a tile grid ' +
             'already means Browse / Genres in this app (DeskIcon.GENRES, MusicIcon.BROWSE, GridGlyph).' },
  ];

  // Options drawn and dropped, with the collision that dropped them.
  const REJECTED = [
    { glyph: 'star', why: 'StarGlyph is the IMDb rating star (ImdbChip.kt). A star beside a collection name ' +
        'in a list ordered by count reads as a score.' },
    { glyph: 'ribbon', why: 'A bookmark ribbon is already saved-for-later: DeskIcon.BOOKMARK and ' +
        'MusicIcon.BOOKMARK. And its notch against the tag\'s point is a two-unit difference.' },
  ];

  // ---------------------------------------------------------------- ink: what the round fixes
  const INK = {
    resting: 'text', focused: 'text',
    note: 'The glyph takes colors.text in both states — the same ink as the name beside it — and focus ' +
          'changes nothing about it. The row\'s plate (colors.surface) is the whole focus cue, exactly as ' +
          'it is for the name and the count today.',
    alternatives: [
      { id: 'dim', resting: 'textSecondary', focused: 'text',
        why: 'Calmer at rest, and focus gains a second cue.',
        cost: 'Only one row is ever focused, so this dims the glyph in every row the viewer is actually ' +
              'scanning — and the glyph is the one thing the owner asked for.' },
      { id: 'accent', resting: 'text', focused: 'accent',
        why: 'The brightest possible focus cue.',
        cost: 'colors.accent is the SELECTION colour in this very row — CheckGlyph(colors.accent). ' +
              'Tinting on focus would make a focused row look ticked.' },
    ],
  };

  // ---------------------------------------------------------------- strings
  const I18N = {
    // The chip. Shipped today: browse.facet.channel = Channel / Kanal / Savn. fo is ALREADY the word
    // the spec asks for; only en and da change.
    chip: { en: 'Collection', da: 'Samling', fo: 'Savn' },
    chipShipped: { en: 'Channel', da: 'Kanal', fo: 'Savn' },
    // Screen-reader labels. A viewer never reads the word Jellyfin (FR-9).
    sr: [
      { key: 'browse.collection.ravilo', en: 'Ravilo collection', da: 'Ravilo-samling', fo: 'Ravilo-savn' },
      { key: 'browse.collection.picked', en: 'Hand-picked collection', da: 'Håndplukket samling', fo: 'Handplukkað savn' },
    ],
    page: { en: 'Movies', da: 'Film', fo: 'Filmar' },
    library: { en: 'Library', da: 'Bibliotek', fo: 'Savn' },
  };

  // ---------------------------------------------------------------- the page behind the popover
  // Stand-in titles reused from design/ravilo/ravilo-data.js, never invented fresh (SKILL-house).
  const POSTERS = ['Brotsjór', 'Nordvest', 'Havets Hjarta', 'Det Tavse Hus', 'Bølgebryder',
    'Aftur í Myrkri', 'Jarnvegur', 'Banens Ende', 'Djúpið', 'Den Sidste Vinter', 'Brúgvin',
    'Bátsferðin', 'Det Niende Lag', 'Arvur', 'Havets Folk', 'Iron Veil'];
  const FACETS = { en: ['Genre', 'Maturity', 'Year', 'Watched', 'Audio', 'Collection', 'Quality', 'Cast or crew'],
                   da: ['Genre', 'Aldersgrænse', 'Årti', 'Set', 'Lyd', 'Samling', 'Kvalitet', 'Skuespillere og hold'],
                   fo: ['Sjangra', 'Aldursmark', 'Áratíggju', 'Sætt', 'Ljóð', 'Savn', 'Góðska', 'Leikarar og hold'] };

  // ---------------------------------------------------------------- the 3 m arithmetic
  // Stated so it can be argued with rather than trusted. A 55-inch 16:9 panel is 1218 mm wide; at
  // 1920 px that is 0.634 mm per pixel, and at 3 m one pixel subtends 0.634/3000 = 0.211 mrad.
  // This PNG is read on a 27-inch 2560-wide monitor (0.233 mm pixels) at 600 mm: 0.388 mrad.
  // 0.211 / 0.388 = 0.545. So the 1920 frame shown at 55 % of its pixels is the 3 m TV.
  const DISTANCE = [
    { scale: 1.00, label: '100 %', caption: 'As rendered — nobody ever sees this.' },
    { scale: 0.55, label: '55 %', caption: '55-inch 1080p set at 3 m. The brief\'s condition.' },
    { scale: 0.38, label: '38 %', caption: '40-inch set at 3 m — the household\'s smallest plausible screen.' },
  ];

  window.CG = { THEMES, theme, M, GLYPH_ALLOWANCE, popoverWidth, ROWS, ROWS_LONG, GLYPHS,
    DIRECTIONS, REJECTED, INK, I18N, POSTERS, FACETS, DISTANCE };
})();
