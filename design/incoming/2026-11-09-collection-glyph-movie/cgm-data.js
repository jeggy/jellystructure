/* Collection popover — the movie glyph. Follow-up round's ONE data file.

   The owner picked the first round's direction C wall for a Ravilo collection and asked for "some
   movie icon" in place of the tag for a hand-picked one. The wall is carried over here unchanged,
   so the two rounds' pictures can be diffed; what this round draws is the second glyph. (The card
   and the spec clause that record the pick are cited in REPORT.md, not here: a drawing carries
   neither a task id nor a number.)

   Self-contained on purpose: `design/incoming/<round>/` is pruned at adoption, so a file here must
   not import the previous round's. Everything the previous round fixed is restated below as data.

   Nothing here is instructions. It is numbers, invented titles and colour values. The palettes are
   the shipped Compose ones, copied value for value out of ravilo-ui/.../ui/theme/Colors.kt. */
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
  // Unchanged from the previous round, which fixed them. Restated, not imported.
  const M = {
    glyph: 18, gapAfterGlyph: 8,          // fixed by the previous round
    checkSlot: 18, check: 14,             // shipped
    rowPadH: 10, rowPadV: 8, rowRadius: 8, rowLine: 20,
    popPad: 12, popRadius: 12, popMaxH: 280, popMinW: 220, popMaxW: 420,
    nameSp: 14, countSp: 13,
    chipPadH: 14, chipPadV: 8, chipRadius: 18, chipFontSp: 14, chipGap: 10,
    hPadTv: 48, hPadPhone: 20, appBar: 60,
  };
  const GLYPH_ALLOWANCE = 28;             // CHANNEL takes the allowance AUDIO already takes
  function popoverWidth(labels, allowance) {
    const maxLen = labels.reduce((a, s) => Math.max(a, s.length), 8);
    return Math.min(M.popMaxW, Math.max(M.popMinW, maxLen * 8 + 110 + allowance));
  }

  // ---------------------------------------------------------------- the stand-in rows (brief)
  const ROWS = [
    { name: 'Serier', count: 41, kind: 'ravilo' },
    { name: 'Sagaer', count: 38, kind: 'ravilo' },
    { name: 'Tidsmaskinen', count: 27, kind: 'ravilo' },
    { name: 'Christmas films', count: 9, kind: 'picked' },
    { name: 'Film club', count: 6, kind: 'picked' },
    { name: 'Rainy Sunday', count: 4, kind: 'picked' },
  ];

  // ---------------------------------------------------------------- the glyphs, 24-unit grid
  // A part is { shape, holes? }. A shape or a hole is one of
  //   { rect: [x, y, w, h], r }   { circle: [cx, cy, r] }   { poly: [[x,y], ...] }
  // Everything is FILLED — the rule the previous round fixed and the owner's pick keeps. Holes are
  // even-odd sub-paths of the same <path>, so there is no paint server anywhere and nothing can be
  // defined in a hidden frame (PROCESS.md's SVG-id trap).
  // `ink` is the filled area in square grid units of 576, computed by hand. It is what makes two
  // glyphs sit evenly in a column, and it is the number direction B lost on last round.
  const GLYPHS = {

    // ---- the Ravilo glyph: the owner's pick, carried over UNCHANGED from round 1 direction C ----
    wall: { label: 'Wall', ink: 242, parts: [
      { shape: { rect: [2.8, 3.6, 5.6, 7.2], r: 1.2 } },
      { shape: { rect: [9.2, 3.6, 5.6, 7.2], r: 1.2 } },
      { shape: { rect: [15.6, 3.6, 5.6, 7.2], r: 1.2 } },
      { shape: { rect: [2.8, 13.2, 5.6, 7.2], r: 1.2 } },
      { shape: { rect: [9.2, 13.2, 5.6, 7.2], r: 1.2 } },
      { shape: { rect: [15.6, 13.2, 5.6, 7.2], r: 1.2 } },
    ] },

    // ---- the movie glyph: four candidates ----

    // RECOMMENDED. A clapperboard: a solid body under a slanted clapper bar, with a wedge of
    // background widening between them. The bar is THREE filled parallelograms rather than one bar
    // with two holes — no coincident edges, no even-odd, and the Compose port is three Paths.
    // The diagonal is the only diagonal in the whole shipped glyph set.
    //
    // Render 1 drew the bar 5.0 thick with 2.4-wide stripes and it read as three floating chevrons,
    // not a striped bar: a gap nearly half the bar's own thickness separates rather than cuts. The
    // bar is now 5.6 thick with 1.8-wide stripes (ratio 0.32, was 0.48), the body is taller and
    // narrower, and the bar overhangs it by 0.8 each side — a hinge stub left, a clapper tip right.
    clap: { label: 'Clapperboard', ink: 236, parts: [
      { shape: { rect: [3.2, 12.0, 17.6, 9.2], r: 2.0 } },
      { shape: { poly: [[2.4, 4.7], [8.8, 4.0], [6.2, 9.88], [2.4, 10.3]] } },
      { shape: { poly: [[10.6, 3.8], [15.6, 3.25], [13.0, 9.13], [8.0, 9.68]] } },
      { shape: { poly: [[17.4, 3.05], [21.6, 2.59], [21.6, 8.19], [14.8, 8.94]] } },
    ] },

    // A film strip stood on end, so its two columns of perforations run vertically against the
    // wall's horizontal rows. This is the candidate the brief named as the risk to check.
    strip: { label: 'Film strip', ink: 193, parts: [
      { shape: { rect: [6.4, 2.4, 11.2, 19.2], r: 1.8 }, holes: [
        { rect: [7.7, 4.3, 1.9, 1.9], r: 0.5 }, { rect: [14.4, 4.3, 1.9, 1.9], r: 0.5 },
        { rect: [7.7, 11.05, 1.9, 1.9], r: 0.5 }, { rect: [14.4, 11.05, 1.9, 1.9], r: 0.5 },
        { rect: [7.7, 17.8, 1.9, 1.9], r: 0.5 }, { rect: [14.4, 17.8, 1.9, 1.9], r: 0.5 },
      ] },
    ] },

    // A reel: a disc with a hub and four spoke windows.
    reel: { label: 'Reel', ink: 192, parts: [
      { shape: { circle: [12, 12, 9.6] }, holes: [
        { circle: [12, 12, 2.0] },
        { circle: [16.03, 7.97, 2.6] }, { circle: [7.97, 7.97, 2.6] },
        { circle: [7.97, 16.03, 2.6] }, { circle: [16.03, 16.03, 2.6] },
      ] },
    ] },

    // One poster with a play notch cut out of it: the "one film, not a wall of them" reading.
    poster: { label: 'Poster', ink: 222, parts: [
      { shape: { rect: [5.2, 2.6, 13.6, 18.8], r: 2.0 }, holes: [
        { poly: [[9.4, 7.6], [17.0, 12.0], [9.4, 16.4]] },
      ] },
    ] },
  };

  // ---------------------------------------------------------------- the four candidates, ranked
  const CANDIDATES = [
    { id: 'clap', recommended: true,
      gives: 'All four read at 18 dp — the distance column shows that, so legibility is not what ' +
             'decides this. What decides it is that the WALL IS A FIELD OF SMALL REPEATED MARKS, and ' +
             'three of the four candidates are another one: the strip is six dots, the reel is a ring ' +
             'of them, the poster is a plain plate. The clapperboard\'s dominant feature is a ' +
             'DIAGONAL, and nothing else in DeskIcons, MusicGlyphs or Glyphs has a diagonal at all. ' +
             'Against a dot field that is the most distinct thing available. Ink 236 against the ' +
             'wall\'s 242: the column reads evenly.',
      costs: 'The most detailed of the four. Its two stripes are 1.8 units wide — they hold at 55 % ' +
             'and go by 38 %, leaving a plain slanted slab. It has to work without them, and the ' +
             'distance column is there to judge whether it does.' },
    { id: 'poster', recommended: false,
      gives: 'The cleanest MEANING of the four: one film against a wall of them, which is exactly the ' +
             'difference between a hand-made list and a big category. The notch survives 55 % intact. ' +
             'If the owner reads the pair as singular-against-plural, this is the one.',
      costs: 'A filled plate with a play triangle in it is the shape `MusicIcon.FILM` already uses for ' +
             'a video (a rounded rect with a filled triangle inside). And at 13.6 units it is the ' +
             'narrowest mark here against the wall\'s 18.4, so the hand-picked rows read smaller down ' +
             'the column even though the ink is close.' },
    { id: 'strip', recommended: false,
      gives: 'The most literal "movie", and stood on end so its perforations run vertically against the ' +
             'wall\'s horizontal rows — which is the orientation that gives it the best chance.',
      costs: 'At 55 % it is a narrow field of small dots and the wall is a wide field of small dots. ' +
             'That is the same vocabulary twice, which is the one thing this pair must not be. ' +
             '`DeskIcon.FILM` is also already a film strip, meaning the Movies page in the desktop rail.' },
    { id: 'reel', recommended: false,
      gives: 'A circle against a rectangle is the most categorical silhouette difference available, and ' +
             'it survives any amount of shrinking.',
      costs: '`DeskIcon.DISC` is a disc with a hub — a music album — and at 3 m a reel and a disc are ' +
             'the same circle. That is the collision that dropped the star and the bookmark last round, ' +
             'so dropping it here is the same rule applied twice. Its four spoke windows are a ring of ' +
             'small dots, echoing the wall again, and ink 192 against 242 leaves the column uneven.' },
  ];

  // ---------------------------------------------------------------- unchanged, restated
  const INK = { resting: 'text', focused: 'text' };
  const I18N = {
    chip: { en: 'Collection', da: 'Samling', fo: 'Savn' },
    sr: [
      { key: 'browse.collection.ravilo', en: 'Ravilo collection', da: 'Ravilo-samling', fo: 'Ravilo-savn' },
      { key: 'browse.collection.picked', en: 'Hand-picked collection', da: 'Håndplukket samling', fo: 'Handplukkað savn' },
    ],
    page: { en: 'Movies', da: 'Film', fo: 'Filmar' },
    library: { en: 'Library', da: 'Bibliotek', fo: 'Savn' },
  };
  const POSTERS = ['Brotsjór', 'Nordvest', 'Havets Hjarta', 'Det Tavse Hus', 'Bølgebryder',
    'Aftur í Myrkri', 'Jarnvegur', 'Banens Ende', 'Djúpið', 'Den Sidste Vinter', 'Brúgvin',
    'Bátsferðin', 'Det Niende Lag', 'Arvur', 'Havets Folk', 'Iron Veil'];
  const FACETS = { en: ['Genre', 'Maturity', 'Year', 'Watched', 'Audio', 'Collection', 'Quality', 'Cast or crew'],
                   da: ['Genre', 'Aldersgrænse', 'Årti', 'Set', 'Lyd', 'Samling', 'Kvalitet', 'Skuespillere og hold'],
                   fo: ['Sjangra', 'Aldursmark', 'Áratíggju', 'Sætt', 'Ljóð', 'Savn', 'Góðska', 'Leikarar og hold'] };

  // The same arithmetic as the previous round, restated so this round stands alone: a 55-inch
  // 1080p panel is 0.634 mm per pixel and at 3 m one pixel subtends 0.211 mrad; this PNG on a
  // 27-inch 2560-wide monitor at 600 mm is 0.388 mrad. 0.211 / 0.388 = 0.55.
  const DISTANCE = [
    { scale: 1.00, label: '100 %', caption: 'As rendered — nobody ever sees this.' },
    { scale: 0.55, label: '55 %', caption: '55-inch 1080p set at 3 m. The brief\'s condition.' },
    { scale: 0.38, label: '38 %', caption: '40-inch set at 3 m.' },
  ];

  window.CGM = { THEMES, theme, M, GLYPH_ALLOWANCE, popoverWidth, ROWS, GLYPHS, CANDIDATES,
    INK, I18N, POSTERS, FACETS, DISTANCE };
})();
