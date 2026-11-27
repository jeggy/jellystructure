/* afp-data.js — EVERY number, title, year and string this round draws.
   Round slug `after-film-page`, 2026-11-27.

   PROCESS.md: "Derive every number in every picture from one array." Nothing in afp-kit.js
   invents a title, a year, a count, a width or a word — all of them are read from here, so
   "do these pictures agree with each other?" is something a reviewer can check rather than trust.

   THE CORPUS. SKILL-house says reuse a name before inventing one. None of the seven titles below
   is in design/ravilo/ravilo-data.js: all seven are the BRIEF's own stand-ins, named exactly, and
   the brief is the authority on stand-in data (PROCESS.md step 1). They are therefore written out
   here verbatim and nothing else is invented. Their backdrop colours and initials still come from
   the corpus's own RAVILO.grad() / RAVILO.initials(), so they sit in the same colour world as
   every other Ravilo mockup.

   THE STRINGS. Every word this page prints already exists in the shipped i18n/*.json, in all
   three languages, so this round adds NO new string. The three keys and the shipped values:

     section.related     en "More Like This"  da "Mere som dette"  fo "Meira líkt hesum"
     player.end_of_movie en "End of film"     da "Slut på filmen"  fo "Endi á filminum"
     action.back         en "Back"            da "Tilbage"         fo "Aftur"

   They are copied below so a frame can be rendered with no network and no Kotlin, and the
   shipped file wins on any disagreement (SKILL-house, Words).
*/
(function () {
  'use strict';

  /* ---- the finished film. `logoHeight` is this page's logo size, NOT the title page's: see
     GEO below and REPORT.md. ------------------------------------------------------------- */
  var FILM = {
    title: 'Saltwind',
    year: 2024,
    runtimeMin: 118,
    rating: '12'
  };

  /* ---- the picks, in the brief's order. `unwatched` is the whole eligibility rule the spec
     excerpt states ("up to 6 unwatched titles from the film's own More Like This list"), so it
     is a field rather than an assumption: every row drawn here is unwatched, and a frame that
     wanted to draw a watched one would have to say so. ------------------------------------ */
  var PICKS = [
    { title: 'The Long Tide',        year: 2021, unwatched: true },
    { title: 'Paper Lanterns',       year: 2019, unwatched: true },
    { title: 'Under Basalt',         year: 2023, unwatched: true },
    { title: 'Ninefold',             year: 2018, unwatched: true },
    { title: 'Glass Meridian',       year: 2022, unwatched: true },
    { title: 'North of the Weather', year: 2020, unwatched: true }
  ];

  /* The brief's state 2: "the same with 2 picks (a short row)." Taken from the head of the list
     rather than retyped, so the two states cannot drift apart. */
  var PICKS_TWO = PICKS.slice(0, 2);

  var CLOCK = '21:48';          /* the phone and TV status strips. A film that ended, in an evening. */

  /* =================================================================================================
     GEOMETRY — every one of these is read out of a SHIPPED stylesheet, with the file and the
     selector named, because design/ravilo/ is reference only and the Compose port reads these
     numbers rather than the pictures. A number here that disagrees with the stylesheet is a
     defect in THIS file.
     ================================================================================================= */
  var GEO = {
    tv: {
      stage:      [1920, 1080],   /* ravilo.css #stage — the fixed stage, drawn and measured at 1920 */
      gutter:     64,             /* ravilo.css .track / .crow-head / .dhero-body padding-left */
      bottomSafe: 72,             /* this round's own: the row's floor. R205/R250 overscan — see REPORT.md */
      tile:       210,            /* ravilo.css .tile.poster width */
      tileGap:    18,             /* ravilo.css .track gap */
      tileAspect: '2/3',          /* ravilo.css .tile.poster .art */
      focusScale: 1.07,           /* ravilo.css .tile.focused */
      kicker:     17,             /* ravilo.css .hero-kicker font-size */
      title:      72,            /* ravilo.css .dhero-title font-size */
      heading:    29,            /* ravilo.css .crow-head h2 font-size */
      logoTitle:  116,           /* ravilo.css .dhero-logo height — the TITLE page's logo */
      logoAfter:  96             /* this round's own: the AFTER-FILM page's logo. REPORT.md says why. */
    },
    phone: {
      portrait:  [412, 915],     /* ravilo-phone.css .phone — Pixel 9 */
      landscape: [915, 412],     /* the same device rotated; this round's own frame, see REPORT.md */
      gutter:    18,             /* ravilo-phone.css .track padding-left */
      tile:      118,            /* ravilo-phone.css .tile / .poster width */
      tileH:     177,            /* ravilo-phone.css .poster height */
      tileGap:   11,             /* ravilo-phone.css .track gap */
      heading:   17,             /* ravilo-phone.css .row h3 font-size */
      title:     27             /* ravilo-phone.css .dtitle font-size */
    },
    desk: {
      window:  [1280, 800],      /* ravilo-desktop.js SIZES.large */
      web:     [900, 760],       /* the brief's "Ravilo Web at 900 wide" */
      rail:    84,               /* desktop-directions.css .gside.rail width */
      side:    248,              /* desktop-directions.css .gside width */
      pad:     28,               /* desktop-directions.css .pad padding */
      po:      138,              /* desktop-directions.css .po width */
      poGap:   14,               /* desktop-directions.css .row gap */
      heading: 30,               /* desktop-directions.css .h1 font-size */
      bt:      38               /* desktop-directions.css .bt height */
    }
  };

  /* =================================================================================================
     THE FIT STUDY. The round's one measurable question: does a row of SIX fit the surface it is
     drawn on? Each row below is computed, not asserted — afp-kit.js adds the tiles and the gaps
     and compares the total with the box, and REPORT.md quotes the result. A reviewer can check
     every number in the report against the study's own picture.
     ================================================================================================= */
  var FIT = [
    { surface: 'Android TV',        note: '6 × .tile.poster + 5 gaps, inside 1920 − 2×64',
      n: 6, w: GEO.tv.tile,    gap: GEO.tv.tileGap,    limit: GEO.tv.stage[0] - 2 * GEO.tv.gutter },
    { surface: 'phone · landscape', note: '6 × .poster + 5 gaps, inside 915 − 2×18',
      n: 6, w: GEO.phone.tile, gap: GEO.phone.tileGap, limit: GEO.phone.landscape[0] - 2 * GEO.phone.gutter },
    { surface: 'phone · portrait',  note: '6 × .poster + 5 gaps, inside 412 − 2×18',
      n: 6, w: GEO.phone.tile, gap: GEO.phone.tileGap, limit: GEO.phone.portrait[0] - 2 * GEO.phone.gutter },
    { surface: 'desktop · rail',    note: '6 × .po + 5 gaps, inside 1280 − 84 rail − 2×28',
      n: 6, w: GEO.desk.po,    gap: GEO.desk.poGap,
      limit: GEO.desk.window[0] - GEO.desk.rail - 2 * GEO.desk.pad },
    { surface: 'Ravilo Web · 900',  note: '6 × .po + 5 gaps, inside 900 − 84 rail − 2×28',
      n: 6, w: GEO.desk.po,    gap: GEO.desk.poGap,
      limit: GEO.desk.web[0] - GEO.desk.rail - 2 * GEO.desk.pad }
  ];

  /* =================================================================================================
     STRINGS. Three shipped keys, three languages, nothing new. `t()` in afp-kit.js falls back to
     English for a key a language has not got, and no key below is missing from any of the three.
     The two surface words (`meta.runtime`, the status strip) are drawn from the shipped mockups'
     own phrasing and are not new strings either — they already appear on the title page.
     ================================================================================================= */
  var STR = {
    en: {
      'section.related':     'More Like This',
      'player.end_of_movie': 'End of film',
      'action.back':         'Back',
      'meta.runtime':        '{h}h {m}m'
    },
    da: {
      'section.related':     'Mere som dette',
      'player.end_of_movie': 'Slut på filmen',
      'action.back':         'Tilbage',
      'meta.runtime':        '{h} t {m} min'
    },
    fo: {
      'section.related':     'Meira líkt hesum',
      'player.end_of_movie': 'Endi á filminum',
      'action.back':         'Aftur',
      'meta.runtime':        '{h} t {m} min'
    }
  };

  window.AFP = {
    FILM: FILM, PICKS: PICKS, PICKS_TWO: PICKS_TWO, CLOCK: CLOCK,
    GEO: GEO, FIT: FIT, STR: STR
  };
})();
