/* cs-data.js — the round's stand-in set and its string table, in one place.

   Round slug `coming-soon-stacks`, 2026-10-22, lane C (drawn in this repository).

   Every count, range, time and badge in every picture of this round is DERIVED from EPISODES
   below. Nothing is written twice: if the eight-episode stack says "8 episodes" and its subtitle
   says "S02E01–E08", both came out of the same eight rows. That makes "do these pictures agree
   with each other?" something a reviewer can check instead of trust — and it is also the honest
   model of the surface, because the client renders server-pushed state only and invents no
   catalog state of its own.

   The names are the brief's STAND-IN set, exactly as commissioned. Nothing here names anything
   the household owns.
*/
window.CS = (function () {
  'use strict';

  /* ---- the day the round is drawn on. Everything relative ("Today") is measured from here. */
  const TODAY = '2026-10-22';          // Thursday

  /* ---- one picture per series. The calendar holds a series poster and backdrop and no episode
     stills for unaired episodes, so every layer of a stack is the same art or a tint of it. Here
     that art is a gradient block with the series' initials, which is how the shipped Ravilo
     mockups stand in for a poster. */
  const SERIES = {
    tidewater: { title: 'Tidewater Station', network: 'Brightwave+', year: 2022, held: true,
      initials: 'TS', grad: 'linear-gradient(145deg,#2f5c8f 0%,#1d3457 48%,#101a2e 100%)' },
    saltmarsh: { title: 'Saltmarsh Rangers', network: 'Channel North', year: 2009, held: true,
      initials: 'SR', grad: 'linear-gradient(145deg,#4a6b46 0%,#2b3f2c 50%,#161f18 100%)' },
    rook: { title: 'Rook & Ember', network: 'Channel North', year: 2025, held: true,
      initials: 'RE', grad: 'linear-gradient(145deg,#7a3f52 0%,#49243a 52%,#1d1018 100%)' },
    heron: { title: 'Heron Vale', network: 'Brightwave+', year: 2024, held: true,
      initials: 'HV', grad: 'linear-gradient(145deg,#2e6a74 0%,#1d4049 52%,#101d22 100%)' },
    ferrow: { title: 'Ferrow Bay', network: 'Northlight', year: 2026, held: false,
      initials: 'FB', grad: 'linear-gradient(145deg,#5a5190 0%,#372f5e 52%,#171429 100%)' },
    orchard: { title: 'Glass Orchard', kind: 'film', network: null, year: 2026, held: true,
      initials: 'GO', grad: 'linear-gradient(145deg,#8a7a3c 0%,#55492a 52%,#201c12 100%)' },
  };

  /* ---- the episode rows. status: available | downloading | upcoming | missing
     `pct` only on a downloading row. `due` only on a missing row. */
  function eps(key, season, from, to, date, time, status, extra) {
    const out = [];
    for (let n = from; n <= to; n++) {
      out.push(Object.assign({ key, s: season, e: n, date, time, status }, extra || {}));
    }
    return out;
  }

  const EPISODES = [].concat(
    /* Today — a whole season dropped at once, which is the occasion for the round. */
    eps('tidewater', 2, 1, 8, TODAY, '04:00', 'available'),

    /* Today — one episode of a long-running series, which stays one card. The brief names no
       episode title for it, so none is drawn: see REPORT.md, findings. */
    eps('saltmarsh', 35, 8, 8, TODAY, '20:00', 'upcoming'),

    /* Tomorrow — a two-episode stack whose episodes air at different times. */
    eps('rook', 1, 4, 4, '2026-10-23', '21:00', 'upcoming'),
    eps('rook', 1, 5, 5, '2026-10-23', '21:45', 'upcoming'),

    /* A mixed stack: four held, one downloading, one still only scheduled. The brief names no
       day or time for it; Sunday 06:00 was chosen here. See REPORT.md, findings. */
    eps('heron', 4, 1, 4, '2026-10-25', '06:00', 'available'),
    eps('heron', 4, 5, 5, '2026-10-25', '06:00', 'downloading', { pct: 62 }),
    eps('heron', 4, 6, 6, '2026-10-25', '06:00', 'upcoming'),

    /* A series we do not hold yet, for the not-held detail a stack opens. The brief names no day
       or time for it either; Monday 22:00 was chosen here. See REPORT.md, findings. */
    eps('ferrow', 1, 1, 3, '2026-10-26', '22:00', 'upcoming'),

    /* The Missing row: three episodes of one series, overdue on three different days. This is
       what tests "grouped by series regardless of day". */
    eps('tidewater', 1, 5, 5, '2026-10-12', '04:00', 'missing'),
    eps('tidewater', 1, 6, 6, '2026-10-14', '04:00', 'missing'),
    eps('tidewater', 1, 7, 7, '2026-10-16', '04:00', 'missing')
  );

  /* A film, which never stacks. Kept out of EPISODES because it has no season or episode number
     — the shape of the data is itself the reason a film cannot join a stack. */
  const FILMS = [{ key: 'orchard', date: '2026-10-23', release: 'release_cinema' }];

  /* ---- grouping. This is the only place the round decides what a stack IS, so decision 2 is a
     one-line change here and cannot drift between surfaces.
       minStack  2 = two or more (the lean) · 3 = three or more · 99 = never
       filter    'all' | 'series' | 'movies'                                                   */
  function groupsFor(date, opt) {
    const o = Object.assign({ minStack: 2, filter: 'all' }, opt || {});
    const out = [];
    if (o.filter !== 'movies') {
      const byKey = {};
      EPISODES.filter(e => e.date === date).forEach(e => {
        (byKey[e.key] = byKey[e.key] || []).push(e);
      });
      Object.keys(byKey).forEach(key => {
        const list = byKey[key].slice().sort((a, b) => a.s - b.s || a.e - b.e);
        if (list.length >= o.minStack) {
          out.push({ key, kind: 'series', eps: list, date, stacked: list.length > 1 });
        } else {
          list.forEach(e => out.push({ key, kind: 'series', eps: [e], date, stacked: false }));
        }
      });
    }
    if (o.filter !== 'series') {
      FILMS.filter(f => f.date === date).forEach(f => {
        out.push({ key: f.key, kind: 'film', eps: [], date, release: f.release, stacked: false });
      });
    }
    return out;
  }

  /* The Missing row groups by series and ignores the day entirely — which is why it takes no
     date argument at all. */
  function missingGroups(opt) {
    const o = Object.assign({ minStack: 2 }, opt || {});
    const byKey = {};
    EPISODES.filter(e => e.status === 'missing').forEach(e => {
      (byKey[e.key] = byKey[e.key] || []).push(e);
    });
    return Object.keys(byKey).map(key => {
      const list = byKey[key].slice().sort((a, b) => a.date < b.date ? -1 : 1);
      return { key, kind: 'series', eps: list, missing: true,
        stacked: list.length >= o.minStack && list.length > 1 };
    });
  }

  /* The days the strip shows. Only days that actually carry a release — the shipped strip skips
     empty days and jumps to the next one with content, so there is no empty day to draw. */
  const DAYS = ['2026-10-22', '2026-10-23', '2026-10-25', '2026-10-26'];

  /* ---- strings.
     Every key below that exists in the shipped i18n/*.json carries the SHIPPED text, because the
     shipped file wins wherever a string already exists. The two new keys are marked NEW and their
     Danish and Faroese are unreviewed drafts. */
  const I18N = {
    en: {
      day_today: 'Today', day_tomorrow: 'Tomorrow',
      all: 'All', series: 'Series', movies: 'Movies',
      episode: 'Episode', movie: 'Movie',
      available: 'Already available', missing: 'Missing',
      was_due: 'Was due {date}', release_cinema: 'In Cinemas',
      missing_jump: 'Missing ({count})', nothing: 'Nothing scheduled',
      subtitle: "What's on the way to your library",
      missing_title: 'Missing from your library', missing_sub: 'Released but never arrived',
      schedule: 'Schedule', network: 'Network',
      foot_upcoming: 'Added to your library automatically when it airs or releases.',
      discover: 'Discover', coming: 'Coming Soon', request: 'Request',
      /* app-bar context only, NOT part of this round's string set */
      nav: ['Home', 'Films', 'Series', 'Music'],
      releases: '{n} releases', release1: '1 release',
      /* NEW */ episodes_count: '{n} episodes',
      /* NEW */ available_of: '{k} of {n} available',
      /* NEW, only if decision 5 picks option C */ times_count: '{n} times',
      detail_loading: 'Loading…', detail_err_title: "The schedule didn't load",
      detail_err_body: 'The library did not answer.',
      detail_err_ok: 'Try again', offline_title: 'No connection',
      offline_body: 'Showing the schedule as it was at 03:40. It will refresh on its own.',
      not_held: 'Not in your library yet', request_it: 'Request',
      dow: { 4: 'Thu', 5: 'Fri', 0: 'Sun', 1: 'Mon' },
      dowLong: { 4: 'Thursday', 5: 'Friday', 0: 'Sunday', 1: 'Monday' },
      mon: 'Oct', dateFmt: '{mon} {d}',
    },
    da: {
      day_today: 'I dag', day_tomorrow: 'I morgen',
      all: 'Alle', series: 'Serier', movies: 'Film',
      episode: 'Episode', movie: 'Film',
      available: 'Allerede tilgængelig', missing: 'Mangler',
      was_due: 'Ventedes {date}', release_cinema: 'I biografen',
      missing_jump: 'Mangler ({count})', nothing: 'Intet planlagt',
      subtitle: 'Hvad der er på vej til dit bibliotek',
      missing_title: 'Mangler i dit bibliotek', missing_sub: 'Udgivet, men aldrig modtaget',
      schedule: 'Tidsplan', network: 'Kanal',
      foot_upcoming: 'Føjes automatisk til dit bibliotek, når det sendes eller udgives.',
      discover: 'Opdag', coming: 'Kommer snart', request: 'Ønsk',
      nav: ['Hjem', 'Film', 'Serier', 'Musik'],
      releases: '{n} udgivelser', release1: '1 udgivelse',
      /* NEW, unreviewed */ episodes_count: '{n} afsnit',
      /* NEW, unreviewed */ available_of: '{k} af {n} tilgængelige',
      /* NEW, unreviewed */ times_count: '{n} tidspunkter',
      detail_loading: 'Indlæser…', detail_err_title: 'Tidsplanen blev ikke indlæst',
      detail_err_body: 'Biblioteket svarede ikke.',
      detail_err_ok: 'Prøv igen', offline_title: 'Ingen forbindelse',
      offline_body: 'Viser tidsplanen som den var 03:40. Den opdateres af sig selv.',
      not_held: 'Ikke i dit bibliotek endnu', request_it: 'Ønsk',
      dow: { 4: 'tor', 5: 'fre', 0: 'søn', 1: 'man' },
      dowLong: { 4: 'torsdag', 5: 'fredag', 0: 'søndag', 1: 'mandag' },
      mon: 'okt', dateFmt: '{d}. {mon}',
    },
    fo: {
      day_today: 'Í dag', day_tomorrow: 'Í morgin',
      all: 'Alt', series: 'Seriur', movies: 'Filmar',
      episode: 'Partur', movie: 'Filmur',
      available: 'Longu tøkt', missing: 'Manglar',
      was_due: 'Skuldi verið {date}', release_cinema: 'Í biografinum',
      missing_jump: 'Manglar ({count})', nothing: 'Einki á veg',
      subtitle: 'Tað, sum er á veg í títt savn',
      missing_title: 'Manglar í savninum tínum', missing_sub: 'Útgivið, men aldri komið',
      schedule: 'Ætlan', network: 'Sjónvarpsrás',
      foot_upcoming: 'Verður sett í savnið títt sjálvvirkandi, tá tað kemur út ella verður útgivið.',
      discover: 'Kanna', coming: 'Kemur skjótt', request: 'Ynskja',
      nav: ['Heim', 'Filmar', 'Seriur', 'Tónleikur'],
      releases: '{n} útgávur', release1: '1 útgáva',
      /* NEW, unreviewed */ episodes_count: '{n} partar',
      /* NEW, unreviewed */ available_of: '{k} av {n} tøk',
      /* NEW, unreviewed */ times_count: '{n} tíðir',
      detail_loading: 'Innlesur…', detail_err_title: 'Ætlanin varð ikki innlisin',
      detail_err_body: 'Savnið svaraði ikki.',
      detail_err_ok: 'Royn aftur', offline_title: 'Eingin sambinding',
      offline_body: 'Vísir ætlanina sum hon var 03:40. Hon dagførist av sær sjálvari.',
      not_held: 'Ikki í savninum tínum enn', request_it: 'Ynskja',
      dow: { 4: 'hós', 5: 'frí', 0: 'sun', 1: 'mán' },
      dowLong: { 4: 'hósdagur', 5: 'fríggjadagur', 0: 'sunnudagur', 1: 'mánadagur' },
      mon: 'okt', dateFmt: '{d}. {mon}',
    },
  };

  return { TODAY, SERIES, EPISODES, FILMS, DAYS, I18N, groupsFor, missingGroups };
})();
