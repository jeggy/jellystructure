/* Slim TV header — the round's one source of numbers and strings.
 *
 * PROCESS.md: "Derive every number in every picture from one array." Everything the frames draw
 * comes from this file: the bar variants, the shipped TV geometry they sit in, the two browse
 * ideas, and the stand-in corpus the brief named exactly.
 *
 * UNITS. Every geometry number below is **dp** and every type number is **sp**, exactly as the
 * Compose source states them — never px. The stage converts:
 *
 *     px(dp) = dp * PX_PER_DP * s          (FR-2: density x s)
 *     px(sp) = sp * PX_PER_DP * t          (FR-2: text at t = 1 - (1 - s)/2)
 *
 * A TV is 960 x 540 dp on a 1920 x 1080 stage, so PX_PER_DP = 2.
 *
 * PROVENANCE. The `standard` bar and the SHIPPED block are read out of the Kotlin, not out of
 * design/ravilo/ravilo.css: the mockup's `.appbar` is 92 px (46 dp) and drifted away from the
 * shipped 60 dp years ago. See REPORT.md, "The mockup had already drifted".
 */
(function () {
  'use strict';

  var PX_PER_DP = 2;            // 1920 px stage / 960 dp screen
  var SCREEN_W = 960;           // dp
  var SCREEN_H = 540;           // dp

  /* ---------------------------------------------------------------------------
   * The three bars. Every part named in FR-9, in dp (geometry) and sp (type).
   * ------------------------------------------------------------------------- */
  var BARS = {
    /* Today. AppBar.kt + RaviloDimens.appBarHeight, verified 2026-11-08. */
    standard: {
      label: 'Standard', note: "today's bar",
      height: 60,
      mark: 26, wordmark: 22, wordmarkTrack: -1, brandGap: 8,
      navSp: 15, navPadH: 12, navPadV: 6, navRadius: 11,
      clusterGap: 18,
      /* ringGap 0 = today: the ring is drawn hard against the fill. See REPORT.md, "The focus ring
       * on the circles is nearly invisible" — Standard is drawn as it ships, not as it should be. */
      circle: 40, glyph: 22, ring: 2, ringGap: 0, initials: 14,
      clock: 15,
    },
    /* RECOMMENDED. */
    slim48: {
      label: 'Slim', note: '48 dp — recommended',
      height: 48,
      mark: 20, wordmark: 17, wordmarkTrack: -0.8, brandGap: 7,
      navSp: 14, navPadH: 11, navPadV: 5, navRadius: 9,
      clusterGap: 14,
      /* ringGap 2 = the ring is held 2 dp clear of the fill, the way RaviloButton's already is
       * (focusRing(..., outset = 5.dp)). `--ring` #8e82ff on `--accent` #7b6ef0 has nowhere near
       * enough contrast to read at three metres when the two touch. */
      circle: 32, glyph: 18, ring: 2, ringGap: 2, initials: 12,
      clock: 13,
    },
    /* The other half of the open decision. */
    slim44: {
      label: 'Slim', note: '44 dp — the tighter option',
      height: 44,
      mark: 18, wordmark: 16, wordmarkTrack: -0.8, brandGap: 6,
      navSp: 13, navPadH: 10, navPadV: 4, navRadius: 8,
      clusterGap: 12,
      circle: 30, glyph: 17, ring: 2, ringGap: 2, initials: 11,
      clock: 12,
    },
  };

  /* ---------------------------------------------------------------------------
   * The screen the bar sits in. Shipped values, so a frame is today's TV with
   * one bar swapped and nothing else moved.
   * ------------------------------------------------------------------------- */
  var SHIPPED = {
    padH: 48,                   // RaviloDimens.screenPadH (raviloHPad on a TV)
    rowGap: 36,                 // RaviloDimens.rowGap
    trackPadV: 20,              // RaviloDimens.trackPadV
    itemSpacing: 16,            // RaviloDimens.itemSpacing
    rowHeadPadB: 10,            // RaviloDimens.rowHeadPadB
    heroHeight: 460,            // HeroCarousel heightDp
    heroBodyBot: 44,            // RaviloDimens.heroBodyBot
    heroBodyW: 560,             // HeroCarousel synopsis width
    pageTitleGap: 24,           // DiscoverScreen: Spacer(appBarHeight + 24.dp)

    rowTitleSp: 18,             // ContentRow: 18 sp SemiBold Space Grotesk
    rowTitleTrack: -0.3,
    rowTrailingSp: 13,          // ContentRow trailingInfo — the count's size already in the code
    seeAllSp: 15,

    poster: { w: 155, h: 232 }, // Tile.kt POSTER_W / POSTER_H
    land: { w: 256, h: 144 },   // Tile.kt LANDSCAPE_W / LANDSCAPE_H
    tileLabelSp: 18,
    tileSubSp: 14,
    tileLabelGap: 8,
    tileRing: 3,
    tileRadius: 6,              // tile corner on the TV skin
    tileFocusScale: 1.07,

    heroKickerSp: 11, heroTaglineSp: 14, heroTitleSp: 44, heroMetaSp: 13,
    heroChipSp: 12, heroRatingSp: 15, heroSynSp: 14,

    btnMinH: 44, btnRadius: 10, btnPadH: 18, btnPadV: 8, btnSp: 15,
    btnRing: 2, btnRingOutset: 5, btnGap: 10,

    chipBarRadius: 16, chipBarPad: 5,
    chipSp: 14, chipPadH: 16, chipPadV: 8, chipRadius: 12,

    discoverTitleSp: 22, discoverSubSp: 13, discoverTitleGap: 12,

    /* Content size = COMPACT on prod (RaviloConfig.uiDensity), so every tile is x 0.82.
     * The frames draw what the household's TV actually shows. */
    tileScale: 0.82,

    /* A row is a LazyRow: it draws past the right edge and is clipped there. Enough tiles that the
     * track always runs off the screen, at every scale — the first render drew exactly six and left
     * a 300 px dead gutter at 90 %, which hid the whole point of the setting. */
    tilesPerRow: 9,
    landPerRow: 6,
  };

  /* ---------------------------------------------------------------------------
   * Idea A — "Surprise me". Two forms of the same entry, both in the right
   * cluster beside Search so its position says "action", not "a fifth tab".
   * ------------------------------------------------------------------------- */
  var SURPRISE = {
    /* A1 — a labelled pill with a hairline at rest (recommended of the two). */
    pill: { sp: 13, padH: 12, padV: 5, radius: 9, glyph: 15, glyphGap: 6, border: 1 },
    /* A2 — a bare glyph in a circle, the same circle Search uses. */
    icon: { /* takes the bar's own circle/glyph/ring numbers */ },
  };

  /* ---------------------------------------------------------------------------
   * Idea B — a quieter row heading with the row's count beside it.
   * ------------------------------------------------------------------------- */
  var QUIET_HEAD = {
    titleSp: 16,                // one step down from the shipped 18 sp
    countSp: 13,                // the size ContentRow's trailingInfo already uses
    dotGap: 7,                  // space either side of the middot
  };

  /* ---------------------------------------------------------------------------
   * The stand-in corpus. Named exactly in the brief; every string is an
   * invention already present in design/ravilo/ravilo-data.js.
   * ------------------------------------------------------------------------- */
  var TITLES = [
    'Heimferð', 'Brotsjór', 'Nýggj Spor', 'Arvur',
    'Hraðar Ljós II', 'Nordvest', 'Havets Hjarta',
  ];

  var ROWS = [
    { key: 'continue', title: 'Continue Watching', count: null, kind: 'land' },
    { key: 'nordic', title: 'Nordic Noir', count: 24, kind: 'poster' },
    { key: 'new', title: 'Newly Added Movies', count: 38, kind: 'poster' },
    { key: 'drama', title: 'Drama', count: 51, kind: 'poster' },
  ];

  /* Continue Watching carries an episode line and a progress bar, as it ships. */
  var CONTINUE = [
    { title: 'Nordvest', ep: 'S03E01 · 22 min left', pct: 62 },
    { title: 'Havets Hjarta', ep: 'S03E06 · Next up', pct: 0 },
    { title: 'Arvur', ep: 'S02E01 · Next up', pct: 0 },
    { title: 'Hraðar Ljós II', ep: '38 min left', pct: 41 },
    { title: 'Heimferð', ep: 'S01E02 · 11 min left', pct: 78 },
    { title: 'Brotsjór', ep: 'Next up', pct: 0 },
  ];

  var HERO = {
    title: 'Heimferð',
    kicker: 'FEATURED',
    tagline: 'Kringvarp Original',
    year: 2023, cert: '16', rating: '8.1',
    genre: 'Crime · Drama · Mystery',
    syn: 'A detective comes home to the town she left, and to a death the town has agreed not to talk about.',
  };

  /* The "Surprise me" landing title, named in the brief. */
  var LANDING = {
    title: 'Brotsjór',
    kicker: 'SURPRISE',
    year: 2022, cert: '12', rating: '7.6',
    genre: 'Drama · Family',
    runtime: '1 h 52 min',
    syn: 'Three weeks before the boats go out, a family that has not spoken since the funeral has to agree on one thing.',
  };

  /* The corpus's first profile (design/ravilo/ravilo-data.js, profiles[0]). */
  var VIEWER = { name: 'Eyð', initials: 'ER' };

  var CLOCK = '20:45';

  /* ---------------------------------------------------------------------------
   * Strings, three languages. English is the source; da/fo are drafts for the
   * two new strings and the shipped value for everything that already exists.
   * The shipped i18n/*.json wins wherever a key is already there (SKILL-house).
   * ------------------------------------------------------------------------- */
  var STR = {
    en: {
      nav: ['Home', 'Movies', 'Series', 'Discover'],
      surprise: 'Surprise me', another: 'Another one',
      play: 'Play', resume: 'Resume', shuffle: 'Shuffle', myList: 'My List',
      discover: 'Discover', discoverSub: 'Everything in your library, by genre',
      segs: ['Coming Soon', 'Request', 'Studios', 'Networks', 'Genres'],
      continueRow: 'Continue Watching', seeAll: 'See All',
    },
    da: {
      nav: ['Hjem', 'Film', 'Serier', 'Opdag'],
      surprise: 'Overrask mig', another: 'En mere',
      play: 'Afspil', resume: 'Fortsæt', shuffle: 'Bland', myList: 'Min liste',
      discover: 'Opdag', discoverSub: 'Alt i dit bibliotek, efter genre',
      segs: ['Snart klar', 'Anmod', 'Studier', 'Stationer', 'Genrer'],
      continueRow: 'Fortsæt med at se', seeAll: 'Se alle',
    },
    fo: {
      nav: ['Heim', 'Filmar', 'Seriur', 'Uppdaga'],
      surprise: 'Yvirraska meg', another: 'Eitt annað',
      play: 'Spæl', resume: 'Halt fram', shuffle: 'Blanda', myList: 'Mín listi',
      discover: 'Uppdaga', discoverSub: 'Alt í tínum savni, eftir sjangru',
      segs: ['Kemur skjótt', 'Umbøn', 'Studio', 'Sjónvarpsrásir', 'Sjangrur'],
      continueRow: 'Halt fram at síggja', seeAll: 'Síggj alt',
    },
  };

  /* ---------------------------------------------------------------------------
   * Poster art. The same deterministic gradient + initials the corpus uses, so
   * a title looks the same here as in design/ravilo/.
   * ------------------------------------------------------------------------- */
  function hash(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return Math.abs(h);
  }
  function grad(s) {
    var h = hash(s);
    var a = h % 360, b = (a + 36 + (h >> 3) % 50) % 360;
    var l1 = 30 + (h >> 5) % 12, l2 = 12 + (h >> 7) % 8;
    return 'linear-gradient(145deg, hsl(' + a + ' 48% ' + l1 + '%), hsl(' + b + ' 52% ' + l2 + '%))';
  }
  function initials(t) {
    return t.replace(/[^A-Za-zÀ-ÿ0-9 ]/g, '').split(/\s+/).slice(0, 2)
      .map(function (w) { return w[0]; }).join('').toUpperCase();
  }

  /* FR-2, exactly as written: the text factor for a screen scale. */
  function textFactor(s) { return 1 - (1 - s) / 2; }

  window.SLIM = {
    PX_PER_DP: PX_PER_DP, SCREEN_W: SCREEN_W, SCREEN_H: SCREEN_H,
    BARS: BARS, SHIPPED: SHIPPED, SURPRISE: SURPRISE, QUIET_HEAD: QUIET_HEAD,
    TITLES: TITLES, ROWS: ROWS, CONTINUE: CONTINUE, HERO: HERO, LANDING: LANDING,
    VIEWER: VIEWER, CLOCK: CLOCK, STR: STR,
    grad: grad, initials: initials, textFactor: textFactor,
  };
})();
