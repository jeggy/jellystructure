/* apb-data.js — the ONE array every number and every word in every picture of this round comes
   from. Round slug album-play-button, 2026-10-23. Ravilo music, phone, plus one desktop frame per
   variant.

   The question: the album page keeps ONE split button, "Play album ▾", whose menu holds every way
   of playing the album, with a ⋯ beside it for everything else. Four variants, which differ in
   what the main part says after a pick and in how the menu groups its rows.

   WHAT IS REUSED, not copied. The album, its artist, its year, its ten tracks, its covers, and the
   words Play / Shuffle / Add to playlist… are window.MPL from the music-playlists round, which is
   LINKED. The shells, type, skins and glyphs are the shipped music-play-all pair, linked through
   it. The album-more-placement round's 44 px correction is restated in apb.css, with its source.

   WHAT IS NEW HERE. R373's own strings, read out of the SHIPPED i18n files at their shipped
   values, plus the variant table, plus ONE drafted string: a shuffle that takes the extras, which
   has no shipped key because today's Shuffle has no second order of its own (see SHIPPED TODAY
   below). It is marked DRAFT and REPORT.md asks for it rather than letting the invention pass.

   SHIPPED TODAY, read out of design/ravilo/mobile/ravilo-music.js:239-253 and ravilo-music.css:
   412-424 — the thing these variants change, stated exactly so the pictures can be judged against
   it rather than against a recollection of it:

     · TWO split buttons: "Play album ▾" (primary) and "Shuffle ▾".
     · BOTH ▾s open the SAME menu, and it has exactly TWO rows — ed.play_album and ed.play_extras,
       each with a subtitle (ed.sub_album / ed.sub_extras) and a ✓ on the current one. The rows
       carry NO glyph, because both of them are Play.
     · So there is ONE axis, the ORDER, remembered per album per device; Shuffle is not a second
       pick, it shuffles inside whatever order is picked ("both buttons follow it", FR-R373-2
       point 3). That is why "Shuffle + extras" has no shipped string: today it is not a row.
     · The pick relabels the PLAY button only. Shuffle always says Shuffle.
     · The ▾ half is 44 px wide and its glyph 18 px (ravilo-music.css:414 and .re-dd svg).
     · The ▾'s accessible name is ed.more_play, "More ways to play" — already shipped, already
       about this exact menu, which is why variant D can put it on screen without a new string.

   No library title and no real name appears anywhere in this file. */
(function () {
  'use strict';

  /* ---- the numbers ------------------------------------------------------------------------
     The album is MPL.ALBUM: 10 official songs · 39 min. EXTRAS is this round's stand-in for {k}
     and is carried over from the album-more-placement round, which asked Bárður for the real
     one and has not had it. ALL is what the shipped subtitle's {n} counts on the extras row —
     ed.sub_extras is filled with CTX['alx:…'].length, which is official + extras + B-sides. This
     stand-in album draws no B-sides, so ALL is 10 + 3; on a real album it would be larger than
     the official count plus {k}. REPORT.md says so rather than letting 13 read as a rule. */
  const EXTRAS = 3;
  const OFFICIAL = 10;
  const ALL = OFFICIAL + EXTRAS;

  /* ---- words ------------------------------------------------------------------------------
     SHIPPED means the value is copied out of i18n/<lang>.json as it stands today, at the key
     named beside it. DRAFT means no key exists and this round proposes one. */
  const W = {
    en: {
      play_album: 'Play album',                       /* SHIPPED ed.play_album */
      play_extras: 'Play album + extras',             /* SHIPPED ed.play_extras */
      sub_album: '{n} songs · the official order',    /* SHIPPED ed.sub_album */
      sub_extras: '{n} songs · extras, then the B-sides', /* SHIPPED ed.sub_extras */
      n_extras: '+ {n} extras',                       /* SHIPPED ed.n_extras */
      more_play: 'More ways to play',                 /* SHIPPED ed.more_play */
      shuffle_extras: 'Shuffle + extras',             /* DRAFT — every variant needs it */
    },
    da: {
      play_album: 'Afspil album',                     /* SHIPPED */
      play_extras: 'Afspil album + ekstra',           /* SHIPPED */
      sub_album: '{n} sange · den officielle rækkefølge',  /* SHIPPED */
      sub_extras: '{n} sange · ekstra, så B-siderne', /* SHIPPED */
      n_extras: '+ {n} ekstra',                       /* SHIPPED */
      more_play: 'Flere måder at afspille',           /* SHIPPED ed.more_play */
      shuffle_extras: 'Bland + ekstra',               /* DRAFT */
    },
    fo: {
      play_album: 'Spæl fløguna',                     /* SHIPPED */
      play_extras: 'Spæl fløgu + eyka',               /* SHIPPED */
      sub_album: '{n} løg · upprunaliga raðfylgjan',   /* SHIPPED */
      sub_extras: '{n} løg · eyka, síðan B-síðurnar', /* SHIPPED */
      n_extras: '+ {n} eyka',                         /* SHIPPED */
      more_play: 'Fleiri mátar at spæla',             /* SHIPPED ed.more_play */
      shuffle_extras: 'Blanda + eyka',                /* DRAFT */
    },
  };

  /* ---- the four variants ------------------------------------------------------------------
     Everything about the row is FIXED by the owner and is the same in all four: one split button
     labelled Play album with a ▾ on its right, no separate Shuffle button, a ⋯ to the right of
     it holding Add to playlist…. The four differ only in the two things the brief asks:

       relabel  does the main part BECOME the last pick, as R373 relabels it today?
       group    'order'  — Play album · Shuffle | Play album + extras · Shuffle + extras
                'action' — Play album · Play album + extras | Shuffle · Shuffle + extras
       tick     is the remembered order ticked, as R373 ticks it today?
       subs     do the Play rows keep R373's own subtitles?
       heading  does the menu name itself with the shipped ed.more_play? */
  const VARIANTS = [
    {
      k: 'A', slug: 'fixed', name: 'The label never changes',
      relabel: false, group: 'order', tick: false, subs: false, heading: false,
      line: 'The main part always says Play album, and the menu is four plain rows of what it can '
          + 'do. Nothing is remembered and nothing is ticked.',
      loses: 'R373’s remembered pick, and its two subtitles with it: a listener who wants the '
           + 'extras asks for them again on every visit, and the button never says what it will '
           + 'play. It is the only one of the four that changes shipped behaviour.',
    },
    {
      k: 'B', slug: 'order', name: 'Relabels · grouped by order',
      relabel: true, group: 'order', tick: true, subs: true, heading: false,
      line: 'The owner’s own example. Each group is one ORDER with its two ways of playing it: '
          + 'the official album above the separator, the album with its extras below.',
      loses: null,
    },
    {
      k: 'C', slug: 'action', name: 'Relabels · grouped by action',
      relabel: true, group: 'action', tick: true, subs: true, heading: false,
      line: 'The two ways of playing in order above the separator, the two ways of shuffling '
          + 'below. The first group is R373’s shipped menu unchanged, row for row, with the '
          + 'shuffles added under it.',
      loses: null,
    },
    {
      k: 'D', slug: 'named', name: 'Relabels · the menu names itself',
      relabel: true, group: 'order', tick: true, subs: true, heading: true,
      line: 'B, with the menu’s own heading on screen: More ways to play. That string is already '
          + 'shipped (ed.more_play) and is already this ▾’s accessible name, so the heading is '
          + 'the label a screen reader hears today, drawn.',
      loses: null,
    },
  ];

  /* ---- the width this round is judged at ---------------------------------------------------
     360 dp phone, 324 dp of content (the shipped 18 px page padding each side). The row is the
     split button, 8 px, and the ⋯ at 48 px — so the split has 268 px, of which the ▾ takes the
     shipped 44, leaving 224 px for the glyph, its 6 px gap, 10 px of padding each side and the
     label. That is the number every label in every language is measured against, and it is the
     one the pictures have to confirm rather than the arithmetic. */
  const W_PHONE = 360, W_CONTENT = 324, DD_W = 44, MORE_W = 48, GAP = 8;

  window.APB = { EXTRAS, OFFICIAL, ALL, W, VARIANTS, W_PHONE, W_CONTENT, DD_W, MORE_W, GAP };
})();
