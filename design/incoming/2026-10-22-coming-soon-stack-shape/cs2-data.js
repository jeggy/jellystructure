/* cs2-data.js — round 11 rev 2's stand-in set and string table, in one place.

   Round slug `coming-soon-stack-shape`, 2026-10-22. Drawn in this repository.

   Every count, range, time and badge in every picture of this round is DERIVED from EPISODES
   below. Nothing is written twice: if the eight-episode stack's band says "8" and its subtitle
   says "S02E01–E08", both came out of the same eight rows. That makes "do these pictures agree
   with each other?" something a reviewer can check instead of trust — and it is also the honest
   model of the surface, because the client renders server-pushed state only and invents no
   catalog state of its own.

   THE STAND-IN SET IS ROUND 11'S, UNCHANGED, so the owner compares like with like. One addition,
   flagged in REPORT.md: the brief asks for a 24-episode drop to price the per-episode-mark
   directions, and round 11's set has no stack larger than eight. Rather than invent a seventh
   title — the brief says not to swap names mid-revision — the 24 episodes are a second season of
   a series already named, on a day of its own that no other frame shows.

   Nothing here names anything the household owns.
*/
window.CS2 = (function () {
  'use strict';

  /* ---- the day the round is drawn on. Everything relative ("Today") is measured from here. */
  const TODAY = '2026-10-22';          // Thursday

  /* ---- one picture per series. The calendar holds a series poster and backdrop and NO episode
     stills for unaired episodes — which is the constraint that rules out every direction built on
     showing more than one picture. Here that art is a gradient block with the series' initials,
     which is how the shipped Ravilo mockups stand in for a poster. Gradients are round 11's,
     character for character, so the two rounds' frames are diffable. */
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
     `pct` only on a downloading row. */
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

    /* Today — one episode of a long-running series, which stays one card. It is in frame beside
       the drop on purpose: the question is whether a stack reads as a stack NEXT TO a card that
       is not one. The brief names no episode title for it, so none is drawn. */
    eps('saltmarsh', 35, 8, 8, TODAY, '20:00', 'upcoming'),

    /* Tomorrow — the two-episode stack, airing 45 minutes apart. Per the owner's fixed decision 4
       the card shows the EARLIEST of the two, 21:00. */
    eps('rook', 1, 4, 4, '2026-10-23', '21:00', 'upcoming'),
    eps('rook', 1, 5, 5, '2026-10-23', '21:45', 'upcoming'),

    /* The mixed stack: four held, one downloading at 62 %, one still only scheduled. Sunday 06:00
       is round 11's choice, carried forward unchanged (it was a flagged stand-in fact there and
       is the same flagged fact here). */
    eps('heron', 4, 1, 4, '2026-10-25', '06:00', 'available'),
    eps('heron', 4, 5, 5, '2026-10-25', '06:00', 'downloading', { pct: 62 }),
    eps('heron', 4, 6, 6, '2026-10-25', '06:00', 'upcoming'),

    /* A series we do not hold yet. Monday 22:00, again round 11's flagged choice. */
    eps('ferrow', 1, 1, 3, '2026-10-26', '22:00', 'upcoming'),

    /* THE 24-EPISODE DROP, this round's one addition to the set. It exists to price the
       per-episode-mark directions, which is the thing the brief asks to be drawn and the thing a
       spec has to bound. It sits on a day of its own so it appears in no other frame. */
    eps('tidewater', 3, 1, 24, '2026-10-27', '04:00', 'available'),

    /* The Missing row: three episodes of one series, overdue on three different days. This is
       what tests "grouped by series regardless of day". */
    eps('tidewater', 1, 5, 5, '2026-10-12', '04:00', 'missing'),
    eps('tidewater', 1, 6, 6, '2026-10-14', '04:00', 'missing'),
    eps('tidewater', 1, 7, 7, '2026-10-16', '04:00', 'missing')
  );

  /* A film, which never stacks. Kept out of EPISODES because it has no season or episode number —
     the shape of the data is itself the reason a film cannot join a stack. */
  const FILMS = [{ key: 'orchard', date: '2026-10-23', release: 'release_cinema' }];

  /* ---- grouping. The owner FIXED this on 2026-10-22: a stack is two or more episodes of one
     series on the same day, and the Missing row groups by series whatever the day. There is no
     minStack option in this round because there is no longer a question here. */
  function groupsFor(date, opt) {
    const o = Object.assign({ filter: 'all' }, opt || {});
    const out = [];
    if (o.filter !== 'movies') {
      const byKey = {};
      EPISODES.filter(e => e.date === date).forEach(e => {
        (byKey[e.key] = byKey[e.key] || []).push(e);
      });
      Object.keys(byKey).forEach(key => {
        const list = byKey[key].slice().sort((a, b) => a.s - b.s || a.e - b.e);
        if (list.length >= 2) {
          out.push({ key, kind: 'series', eps: list, date, stacked: true });
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

  /* The Missing row ignores the day entirely — which is why it takes no date argument at all. */
  function missingGroups() {
    const byKey = {};
    EPISODES.filter(e => e.status === 'missing').forEach(e => {
      (byKey[e.key] = byKey[e.key] || []).push(e);
    });
    return Object.keys(byKey).map(key => {
      const list = byKey[key].slice().sort((a, b) => a.date < b.date ? -1 : 1);
      return { key, kind: 'series', eps: list, missing: true, stacked: list.length >= 2 };
    });
  }

  /* The days the strip shows. Only days that actually carry a release — the shipped strip skips
     empty days and jumps to the next one with content, so there is no empty day to draw (round
     11's finding 3, unchanged). 27 Oct is excluded from the default set: the 24-episode drop is
     drawn on its own and is not part of the week the other frames show. */
  const DAYS = ['2026-10-22', '2026-10-23', '2026-10-25', '2026-10-26'];
  const DAY_BIG = ['2026-10-27'];

  /* ---- strings.
     Every key that exists in the shipped i18n/*.json carries the SHIPPED text, because the
     shipped file wins wherever a string already exists. `episodes_count` and `available_of` are
     round 11's new keys, reused here as the brief asks. `episodes_word` is this round's one new
     key and its Danish and Faroese are UNREVIEWED drafts.

     There is deliberately NO `releases` / `{n} releases` key in this table. The brief is explicit
     that the shipped app has no day-header count line, and round 11 found the shipped i18n set
     has no key for one either — so the line round 11 drew is gone from this round entirely. */
  const I18N = {
    en: {
      day_today: 'Today', day_tomorrow: 'Tomorrow',
      all: 'All', series: 'Series', movies: 'Movies',
      episode: 'Episode', movie: 'Movie',
      available: 'Already available', missing: 'Missing',
      was_due: 'Was due {date}', release_cinema: 'In Cinemas',
      subtitle: "What's on the way to your library",
      missing_title: 'Missing from your library', missing_sub: 'Released but never arrived',
      discover: 'Discover', coming: 'Coming Soon', request: 'Request',
      nav: ['Home', 'Films', 'Series', 'Music'],
      episodes_count: '{n} episodes',          /* round 11's key, reused */
      available_of: '{k} of {n} available',    /* round 11's key, reused */
      /* NEW — the band's plural noun, set beside the numeral rather than inside a sentence.
         See REPORT.md for why this is a separate key and what it falls back to. */
      episodes_word: 'episodes',
      dow: { 4: 'Thu', 5: 'Fri', 0: 'Sun', 1: 'Mon', 2: 'Tue' },
      dowLong: { 4: 'Thursday', 5: 'Friday', 0: 'Sunday', 1: 'Monday', 2: 'Tuesday' },
      mon: 'Oct', dateFmt: '{mon} {d}',
    },
    da: {
      day_today: 'I dag', day_tomorrow: 'I morgen',
      all: 'Alle', series: 'Serier', movies: 'Film',
      episode: 'Episode', movie: 'Film',
      available: 'Allerede tilgængelig', missing: 'Mangler',
      was_due: 'Ventedes {date}', release_cinema: 'I biografen',
      subtitle: 'Hvad der er på vej til dit bibliotek',
      missing_title: 'Mangler i dit bibliotek', missing_sub: 'Udgivet, men aldrig modtaget',
      discover: 'Opdag', coming: 'Kommer snart', request: 'Ønsk',
      nav: ['Hjem', 'Film', 'Serier', 'Musik'],
      episodes_count: '{n} afsnit',
      available_of: '{k} af {n} tilgængelige',
      /* NEW, unreviewed */ episodes_word: 'afsnit',
      dow: { 4: 'tor', 5: 'fre', 0: 'søn', 1: 'man', 2: 'tir' },
      dowLong: { 4: 'torsdag', 5: 'fredag', 0: 'søndag', 1: 'mandag', 2: 'tirsdag' },
      mon: 'okt', dateFmt: '{d}. {mon}',
    },
    fo: {
      day_today: 'Í dag', day_tomorrow: 'Í morgin',
      all: 'Alt', series: 'Seriur', movies: 'Filmar',
      episode: 'Partur', movie: 'Filmur',
      available: 'Longu tøkt', missing: 'Manglar',
      was_due: 'Skuldi verið {date}', release_cinema: 'Í biografinum',
      subtitle: 'Tað, sum er á veg í títt savn',
      missing_title: 'Manglar í savninum tínum', missing_sub: 'Útgivið, men aldri komið',
      discover: 'Kanna', coming: 'Kemur skjótt', request: 'Ynskja',
      nav: ['Heim', 'Filmar', 'Seriur', 'Tónleikur'],
      episodes_count: '{n} partar',
      available_of: '{k} av {n} tøk',
      /* NEW, unreviewed */ episodes_word: 'partar',
      dow: { 4: 'hós', 5: 'frí', 0: 'sun', 1: 'mán', 2: 'týs' },
      dowLong: { 4: 'hósdagur', 5: 'fríggjadagur', 0: 'sunnudagur', 1: 'mánadagur',
        2: 'týsdagur' },
      mon: 'okt', dateFmt: '{d}. {mon}',
    },
  };

  return { TODAY, SERIES, EPISODES, FILMS, DAYS, DAY_BIG, I18N, groupsFor, missingGroups };
})();
