/* amp-data.js — the ONE array every number and every word in every picture of this round comes
   from. Round slug album-more-placement, 2026-10-23. Ravilo music, phone (one desktop frame).

   The question: where does an album page's ⋯ go on the phone when the album has editions, so that
   every label stays whole?

   WHAT IS REUSED. The album, its artist, its year, its ten tracks and its covers are
   window.MPL.ALBUM / MPL.ALBUM_TRACKS from the music-playlists round, which is LINKED, not copied.
   Play, Shuffle, Add to playlist… and the da/fo columns for them are window.MPL.W. Nothing here
   restates any of that.

   WHAT IS NEW HERE. Only R373's own strings, which the playlists round never needed, plus the
   option table. The R373 strings are read out of the SHIPPED i18n files — ed.play_album,
   ed.play_extras, ed.n_extras in en.json, da.json and fo.json, at their shipped values — because
   the shipped file wins wherever a string already exists. Two strings have no shipped key at all
   (Play extras / Shuffle extras, which only option A needs); they are marked DRAFT below and
   REPORT.md asks for them rather than letting the invention pass as settled.

   No library title and no real name appears anywhere in this file. */
(function () {
  'use strict';

  /* ---- the one number this round invents ---------------------------------------------------
     R373's header is "{n} songs · {length}" of the official album, then "+ {k} extra(s)" in the
     dimmer ink. The playlists round's editions frame drew no count at all, and the brief does not
     name one. Three is this round's stand-in and REPORT.md asks Bárður for the real k. It is the
     only invented number here; the album is 10 official songs · 39 min, which is MPL.ALBUM. */
  const EXTRAS = 3;

  /* ---- words ------------------------------------------------------------------------------
     SHIPPED means the value is copied out of i18n/<lang>.json as it stands today.
     DRAFT means no key exists and this round proposes one. */
  const W = {
    en: {
      play_album: 'Play album',                 /* SHIPPED ed.play_album */
      play_extras: 'Play album + extras',       /* SHIPPED ed.play_extras */
      n_extras: '+ {n} extras',                 /* SHIPPED ed.n_extras */
      play_extras_row: 'Play extras',           /* DRAFT — option A only */
      shuffle_extras_row: 'Shuffle extras',     /* DRAFT — option A only */
    },
    da: {
      play_album: 'Afspil album',               /* SHIPPED */
      play_extras: 'Afspil album + ekstra',     /* SHIPPED */
      n_extras: '+ {n} ekstra',                 /* SHIPPED */
      play_extras_row: 'Afspil ekstra',         /* DRAFT */
      shuffle_extras_row: 'Bland ekstra',       /* DRAFT */
    },
    fo: {
      play_album: 'Spæl fløguna',               /* SHIPPED */
      play_extras: 'Spæl fløgu + eyka',         /* SHIPPED */
      n_extras: '+ {n} eyka',                   /* SHIPPED */
      play_extras_row: 'Spæl eyka',             /* DRAFT */
      shuffle_extras_row: 'Blanda eyka',        /* DRAFT */
    },
  };

  /* ---- the options, in the order the directions doc and the pictures put them ---------------
     `keepsR373` is the one fact that decides whether an option is the safe kind: an option that
     keeps the split buttons keeps the remembered per-album, per-device pick; one that does not
     has to say in one line what a listener loses. */
  const OPTIONS = [
    { k: 'A', slug: 'merged', name: 'One ⋯ instead of the two ▾ menus',
      where: 'in the action row, third', keepsR373: false,
      loses: 'the remembered pick: the Play button never says what it will play, so a listener who ' +
             'wants the extras asks for them again on every visit, on every album.' },
    { k: 'B', slug: 'topbar', name: 'The ⋯ in the top bar',
      where: 'over the cover, top right, opposite the back button', keepsR373: true, loses: null },
    { k: 'C', slug: 'title', name: 'The ⋯ on the title block',
      where: 'at the end of the title and artist lines', keepsR373: true, loses: null },
    { k: 'D', slug: 'compact', name: 'A compact Shuffle',
      where: 'in the action row, third, with Shuffle reduced to its glyph', keepsR373: true, loses: null },
    { k: 'E', slug: 'tworows', name: 'The play controls take two rows',
      where: 'in the second row, beside Shuffle ▾', keepsR373: true, loses: null },
  ];

  /* ---- the width this round is judged at --------------------------------------------------- */
  const W_PHONE = 360, W_CONTENT = 324;

  window.AMP = { EXTRAS, W, OPTIONS, W_PHONE, W_CONTENT };
})();
