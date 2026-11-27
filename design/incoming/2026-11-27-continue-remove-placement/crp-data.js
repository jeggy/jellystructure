/* crp-data.js — the one data file every number, string and state in this round comes from.
   Round slug `continue-remove-placement`, 2026-11-27.

   PROCESS.md: "Derive every number in every picture from one array." Nothing in crp-kit.js
   invents a title, a duration, a label or a width; it reads them from here. That is what makes
   "do these pictures agree with each other?" a question a reviewer can check rather than trust.

   The stand-in corpus is the brief's STAND-IN section, named exactly and not extended:
     film    Saltwind, 2024, 1 h 58 min, 47 min left
     series  Harbourlight, S2 E5 "The Ferry at Dawn", 22 min left
   Both names are inventions of the brief. No library title, no real host, no id, no number.
*/
(function () {
  'use strict';

  /* =============================================================================================
     STRINGS — English source, with Danish and Faroese.

     Two rows need reading before the pictures are judged.

     1. `action.continue_remove` / `action.continue_restore` are the round's NEW strings. The
        brief's own Faroese stand-ins are drawn as the primary, because the brief names them
        exactly and a designer does not substitute a string the briefer chose:
            Tak burtur úr Halda fram at hyggja   (34 characters)
            Vís í Halda fram at hyggja           (26)
     2. The SHIPPED i18n disagrees with both of them, and the shipped file wins wherever a string
        already exists (SKILL-house, "Words"). `i18n/fo.json` has:
            row.continue        = Halt fram at síggja
            action.remove_list  = Tak úr mínum lista
        Followed, those two give `Tak úr Halt fram at síggja` (26 characters) — EIGHT characters
        shorter than the stand-in, and it is also what the spec's FR-7 asks for ("follow each
        file's own row.continue wording"). So the stand-in is the WIDER string and measuring with
        it is the conservative test, which is why it is the primary here. The shipped-vocabulary
        variant is drawn beside it in the measurement study and both widths are in REPORT.md.
        This is reported, never "fixed" in the drawing.

     The same reading gives the Danish, from `row.continue` = Fortsæt med at se and
     `action.remove_list` = Fjern fra min liste.
  ============================================================================================= */
  var STR = {
    en: {
      'action.play': 'Play',
      'action.resume': 'Resume',
      'action.my_list': 'My List',
      'action.mark_watched': 'Mark Watched',
      'action.trailer': 'Trailer',
      'download.action': 'Download',
      'fd.min_left': '{n} min left',
      'meta.runtime': '{h} h {m} min',
      'meta.seasons': '{n} seasons',
      'row.continue': 'Continue Watching',
      /* NEW — the round's two strings, as the brief and FR-6 give them */
      'action.continue_remove': 'Remove from Continue Watching',
      'action.continue_restore': 'Show in Continue Watching',
      /* the shipped-vocabulary reading, drawn in the study only */
      'study.shipped_remove': 'Remove from Continue Watching',
      'nav.home': 'Home', 'nav.library': 'Library', 'nav.search': 'Search',
      'nav.discover': 'Discover', 'nav.profile': 'Profile',
      'sec.episodes': 'Episodes', 'sec.cast': 'Cast & Crew', 'sec.more': 'More like this',
      'kicker.film': 'Film', 'kicker.series': 'Series',
      'more': 'more',
      'sheet.title': 'Saltwind',
      'overflow.aria': 'More actions',
      'film.syn': 'A harbour pilot who has brought every boat home for thirty years loses her nerve on one crossing, and the village decides what that is worth.',
      'series.syn': 'Two harbour towns share one lighthouse keeper, one ferry and a secret that the winter will not keep.',
      'ep.upnext': 'Resume S02E05 “The Ferry at Dawn” · 22 min left',
      'watched_of': '4 of 10 watched'
    },
    da: {
      'action.play': 'Afspil',
      'action.resume': 'Fortsæt',
      'action.my_list': 'Min liste',
      'action.mark_watched': 'Marker som set',
      'action.trailer': 'Trailer',
      'download.action': 'Hent',
      'fd.min_left': '{n} min tilbage',
      'meta.runtime': '{h} t {m} min',
      'meta.seasons': '{n} sæsoner',
      'row.continue': 'Fortsæt med at se',
      'action.continue_remove': 'Fjern fra Fortsæt med at se',
      'action.continue_restore': 'Vis i Fortsæt med at se',
      'study.shipped_remove': 'Fjern fra Fortsæt med at se',
      'nav.home': 'Hjem', 'nav.library': 'Bibliotek', 'nav.search': 'Søg',
      'nav.discover': 'Opdag', 'nav.profile': 'Profil',
      'sec.episodes': 'Afsnit', 'sec.cast': 'Medvirkende', 'sec.more': 'Mere som dette',
      'kicker.film': 'Film', 'kicker.series': 'Serie',
      'more': 'mere',
      'sheet.title': 'Saltwind',
      'overflow.aria': 'Flere handlinger',
      'film.syn': 'En havnelods, der har bragt hver båd hjem i tredive år, mister modet på én overfart, og landsbyen afgør, hvad det er værd.',
      'series.syn': 'To havnebyer deler én fyrpasser, én færge og en hemmelighed, som vinteren ikke vil holde.',
      'ep.upnext': 'Fortsæt S02E05 “The Ferry at Dawn” · 22 min tilbage',
      'watched_of': '4 af 10 set'
    },
    fo: {
      'action.play': 'Spæl',
      'action.resume': 'Halt fram',
      'action.my_list': 'Mín listi',
      'action.mark_watched': 'Merk sum sætt',
      'action.trailer': 'Trailer',
      'download.action': 'Tak niður',
      'fd.min_left': '{n} min eftir',
      'meta.runtime': '{h} t {m} min',
      'meta.seasons': '{n} árstíðir',
      'row.continue': 'Halt fram at síggja',
      /* the brief's stand-in — the widest string in the round, and the one drawn everywhere */
      'action.continue_remove': 'Tak burtur úr Halda fram at hyggja',
      'action.continue_restore': 'Vís í Halda fram at hyggja',
      /* what `row.continue` + `action.remove_list` already in i18n/fo.json actually give */
      'study.shipped_remove': 'Tak úr Halt fram at síggja',
      'nav.home': 'Heim', 'nav.library': 'Savn', 'nav.search': 'Leita',
      'nav.discover': 'Uppdaga', 'nav.profile': 'Vangi',
      'sec.episodes': 'Partar', 'sec.cast': 'Leikarar', 'sec.more': 'Meira sum hetta',
      'kicker.film': 'Filmur', 'kicker.series': 'Seria',
      'more': 'meira',
      'sheet.title': 'Saltwind',
      'overflow.aria': 'Fleiri valmøguleikar',
      'film.syn': 'Ein havnarleiðari, sum hevur flutt hvønn bát heim í tríati ár, missir dirvið á einari einastu ferð, og bygdin avger, hvat tað er vert.',
      'series.syn': 'Tveir havnarbygdir býta ein vitamann, eina ferðu og eitt loyndarmál, sum vetrurin ikki vil høggja.',
      'ep.upnext': 'Halt fram S02E05 “The Ferry at Dawn” · 22 min eftir',
      'watched_of': '4 av 10 sædd'
    }
  };

  /* =============================================================================================
     THE STAND-IN TITLES — the brief's, exactly.
  ============================================================================================= */
  var FILM = {
    title: 'Saltwind',
    year: 2024,
    rating: '12',
    runtimeMin: 118,          /* 1 h 58 min */
    minLeft: 47,
    genres: ['Drama', 'Mystery'],
    pct: 60                   /* 47 of 118 min left -> 60 % watched; the only place this is derived */
  };
  var SERIES = {
    title: 'Harbourlight',
    year: 2023,
    rating: '12',
    seasons: 2,
    season: 2,
    ep: 5,
    epTitle: 'The Ferry at Dawn',
    minLeft: 22,
    genres: ['Drama'],
    epsInSeason: 10,
    watched: 4
  };

  /* =============================================================================================
     THE THREE STATES OF THE ACTION (spec FR-6, quoted in the brief as a table).
     `none` is drawn on every surface as the CONTROL frame: it is what proves that the row a
     viewer sees when the title is not in Continue Watching has not moved.
  ============================================================================================= */
  var STATES = ['remove', 'restore', 'none'];

  /* =============================================================================================
     THE OPTIONS. Named, never numbered — a drawing in this repository carries no number and no
     task id.
  ============================================================================================= */
  var OPTIONS = {
    a: {
      name: 'Last in the row',
      gist: 'The action is the last item of the action row, after My List, on every surface.'
    },
    b: {
      name: 'A line of its own',
      gist: 'The action row is untouched. Where the row has no room — the phone and the '
          + 'desktop/web hero — the action is a full-width ghost control on its own line '
          + 'beneath it. On the TV, where there is room, it stays last in the row.'
    },
    c: {
      name: 'In the row’s overflow',
      gist: 'A ⋯ control takes the row’s last slot and opens a sheet that carries the action.'
    }
  };

  /* =============================================================================================
     MEASURED GEOMETRY — every box in these pictures, with the file the number came from.
     Nothing here is a guess: each is read out of the shipped stylesheet or the adopted round.
  ============================================================================================= */
  var GEO = {
    tv: {
      src: 'design/ravilo/ravilo.css',
      stage: [1920, 1080],
      heroBody: { left: 64, right: 64, bottom: 44 },   /* .dhero-body */
      content: 1920 - 64 - 64,                          /* 1792 px of row */
      heroH: 620,                                       /* .dhero */
      appbarH: 92,                                      /* .appbar */
      rowGap: 16,                                       /* .dactions */
      btn: { h: 60, padX: 30, radius: 12, size: 21, weight: 600 }  /* .btn */
    },
    phone: {
      src: 'design/ravilo/mobile/ravilo-phone.css + the adopted phone-downloads round',
      screen: [412, 915],
      bodyPadX: 20,                                     /* .pd-dbody / .dbody */
      content: 412 - 20 - 20,                           /* 372 px of row */
      rowGap: 10,                                       /* .pd-dbtns */
      btn: { h: 44, radius: 12, size: 15, weight: 600 },
      myListIco: 44,                                    /* .pd-mylist-ico, icon-only since the
                                                           owner's "beside Play" amendment */
      dlPadX: 14                                        /* .pd-dl, one fixed width per language */
    },
    desk: {
      src: 'design/ravilo/desktop-directions.css + design/ravilo/desktop/ravilo-desktop.css',
      window: [1440, 900],
      heroH: 420,                                       /* .dx-fd .hero */
      hb: { left: 28, bottom: 30, maxWidth: 480 },      /* .hb — THE constraint on this surface */
      rowGap: 10,                                       /* .btns */
      bt: { h: 38, padX: 18, radius: 10, size: 13.5, weight: 600 }  /* .bt */
    }
  };

  /* =============================================================================================
     THE MEASUREMENT STUDY.

     The question this round is asked is NOT "does the label fit" — it is "does the ROW still fit
     once the label is in it". A bar per label would have shown every label comfortably inside its
     surface and answered nothing; the first render of this study did exactly that, and it is why
     the study below measures WHOLE ROWS instead.

     Each entry is a row of controls. `extra` is everything that control adds around its text —
     horizontal padding, border, and an icon plus its gap where it has one — written out per
     control rather than folded into one constant, so the arithmetic in a picture can be checked
     against the stylesheet line by line.

       TV        .btn        padding 0 30, border 2        -> 64; with an icon +22 glyph +12 gap
       phone     .pd-btn     flex, no padding of its own   -> 24 breathing; ▶ 17 + gap 7
                 .pd-dl      padding 0 14, border 1        -> 30; ↓ 17 + gap 7
                 .pd-mylist-ico  fixed 44, icon only       -> fixed
                 .crp-cw     padding 0 12, border 1        -> 26
       desktop   .bt         padding 0 18                  -> 36; .ic 16 + gap 8
  ============================================================================================= */
  var P = {
    /* The time-left half of the Play label is a KEY, not a literal. The first study printed the
       Faroese "47 min eftir" inside the ENGLISH row, because it had been typed in rather than
       read from fd.min_left. In a round whose whole subject is how wide a language is, a
       hard-coded string is the one bug that invalidates the measurement it appears in. */
    tvPlay:   { key: 'action.resume', suffixKey: 'fd.min_left', suffixVars: { n: 47 },
                extra: 64 + 22 + 12, font: 'tv' },
    tvWatch:  { key: 'action.mark_watched', extra: 64 + 22 + 12, font: 'tv' },
    tvTrail:  { key: 'action.trailer', extra: 64 + 22 + 12, font: 'tv' },
    tvList:   { key: 'action.my_list', extra: 64 + 22 + 12, font: 'tv' },
    tvCw:     { key: 'action.continue_remove', extra: 64, font: 'tv' },
    phPlay:   { key: 'action.resume', extra: 24 + 17 + 7, font: 'phone' },
    phDl:     { key: 'download.action', extra: 30 + 17 + 7, font: 'phone' },
    phList:   { fixed: 44, label: '＋', font: 'phone' },
    phCw:     { key: 'action.continue_remove', extra: 26, font: 'phone' },
    phCwFull: { key: 'action.continue_remove', extra: 30, font: 'phone' },
    phCwShip: { key: 'study.shipped_remove', extra: 30, font: 'phone' },
    dkPlay:   { key: 'action.resume', suffixKey: 'fd.min_left', suffixVars: { n: 47 },
                extra: 36 + 16 + 8, font: 'desk' },
    dkList:   { key: 'action.my_list', extra: 36 + 16 + 8, font: 'desk' },
    dkCw:     { key: 'action.continue_remove', extra: 36, font: 'desk' },
    /* .crp-cwdesk — centred, so it needs breathing either side rather than .bt's 18 */
    dkCwFull: { key: 'action.continue_remove', extra: 2 * 14 + 2, font: 'desk' },
    dkCwShip: { key: 'study.shipped_remove', extra: 2 * 14 + 2, font: 'desk' }
  };

  var STUDY = [
    { surface: 'tv', limit: 1792, gap: 16, note: 'A · TV film row, the action last',
      parts: [P.tvPlay, P.tvWatch, P.tvTrail, P.tvList, P.tvCw] },
    { surface: 'tv', limit: 1792, gap: 16, note: 'A · TV series row (no Mark watched)',
      parts: [P.tvPlay, P.tvTrail, P.tvList, P.tvCw] },
    { surface: 'phone', limit: 372, gap: 10, note: 'the shipped phone row, before this round',
      parts: [P.phPlay, P.phDl, P.phList] },
    { surface: 'phone', limit: 372, gap: 10, note: 'A · phone row with the action 4th',
      parts: [P.phPlay, P.phDl, P.phList, P.phCw] },
    { surface: 'phone', limit: 372, gap: 0, note: 'B · phone, the action on its own line',
      parts: [P.phCwFull] },
    { surface: 'phone', limit: 372, gap: 0, note: 'B · phone, shipped vocabulary instead',
      parts: [P.phCwShip] },
    { surface: 'desk', limit: 480, gap: 10, note: 'the shipped desktop/web row, before this round',
      parts: [P.dkPlay, P.dkList] },
    { surface: 'desk', limit: 480, gap: 10, note: 'A · desktop/web row with the action last',
      parts: [P.dkPlay, P.dkList, P.dkCw] },
    { surface: 'desk', limit: 480, gap: 0, note: 'B · desktop/web, the action on its own line',
      parts: [P.dkCwFull] },
    { surface: 'desk', limit: 480, gap: 0, note: 'B · desktop/web, shipped vocabulary instead',
      parts: [P.dkCwShip] }
  ];

  window.CRP = {
    STR: STR, FILM: FILM, SERIES: SERIES, STATES: STATES,
    OPTIONS: OPTIONS, GEO: GEO, STUDY: STUDY,
    LANGS: ['en', 'da', 'fo'],
    /* Chromium has no Faroese locale and toLocaleDateString falls back to English in silence
       (PROCESS.md). Nothing in this round formats a date, and nothing calls a locale function. */
    CLOCK: '20:14'
  };
})();
