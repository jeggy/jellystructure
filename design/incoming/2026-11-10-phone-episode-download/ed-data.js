/* ed-data.js — the one array every number, state and word in this round comes out of.
   Nothing in ed-kit.js or frames.html invents a value; if a picture disagrees with another
   picture, the disagreement is in here and can be read.

   Surface: the Ravilo ANDROID PHONE's series page, 412 x 915 dp portrait. The episode titles,
   durations, synopses and air dates are the shipped corpus's own hand-written Nordvest Season 1
   (design/ravilo/ravilo-data.js, SERIES_EP) — not generated, so the title collision the
   2026-11-09 round hit cannot happen here.

   The download STATE of each episode is the brief's, not the corpus's: the corpus marks every
   S1 episode 100 % watched, and the brief names two. */
(function () {
  'use strict';

  /* ---- the series, the season, the viewer, the clock ---------------------------------- */
  var SERIES = {
    title: 'Nordvest',
    year: 2023,
    cert: '16',
    seasonIndex: 1,
    seasonEpisodes: 8,
    genre: 'Crime · Drama'
  };

  var VIEWER = { name: 'Eyð', initials: 'ER' };
  var CLOCK = '20:45';

  /* ---- the episodes --------------------------------------------------------------------
     `state` is the download phase, exactly as the shipped sealed interface names it
     (DownloadPhase.kt): none | queued | running | paused | waitingWifi | waitingNetwork |
     waitingServer | downloaded | failed. `pct` only means anything for running and paused.

     The brief: E01 and E02 downloaded at 720p (650 MB each) and watched; E03 downloaded;
     E04 at 42 %; E05 queued; E06 failed; E07 and E08 not downloaded. */
  var EPISODES = [
    { n: 1, state: 'downloaded', watched: true,  rung: 'medium', mb: 650 },
    { n: 2, state: 'downloaded', watched: true,  rung: 'medium', mb: 650 },
    { n: 3, state: 'downloaded', watched: false, rung: 'medium', mb: 650 },
    { n: 4, state: 'running',    watched: false, rung: 'medium', mb: 650, pct: 42 },
    { n: 5, state: 'queued',     watched: false, rung: 'medium', mb: 650 },
    { n: 6, state: 'failed',     watched: false, rung: 'medium', mb: 650 },
    { n: 7, state: 'none',       watched: false },
    { n: 8, state: 'none',       watched: false }
  ];

  /* The brief asks for "one waiting" in the rail. A wait is not a seventh episode: it is E05's
     own phase on a phone whose Wi-Fi-only switch is on and whose Wi-Fi is off. So the waiting
     frames are the SAME eight episodes with E05 moved, and the round shows all three waits plus
     a pause rather than picking one — the whole point of the open decision is whether they are
     distinguishable here at all. */
  var WAIT_VARIANTS = [
    { key: 'waitingWifi',    on: 5 },
    { key: 'waitingNetwork', on: 5 },
    { key: 'waitingServer',  on: 5 },
    { key: 'paused',         on: 4, pct: 42 }
  ];

  /* A season whose every episode is on the phone — the frame that asks what the
     *Download season* chip is for once there is nothing left to download. */
  var ALL_DOWNLOADED = EPISODES.map(function (e) {
    return { n: e.n, state: 'downloaded', watched: e.n <= 2, rung: 'medium', mb: 650 };
  });

  /* ---- the multi-episode card (one physical file, two episodes) ------------------------
     The shipped rail groups consecutive episodes that share a `file` into ONE card
     (SeriesDetailScreen.episodeGroups, MultiEpisodeCard). A two-episode file is one download:
     one size, one progress, one control. Drawn at 42 % so the single control is visibly the
     file's progress and not either episode's. */
  var MULTI = { ns: [5, 6], state: 'running', pct: 42, mb: 1300, rung: 'medium', watched: false };

  /* ---- sizes and rungs -----------------------------------------------------------------
     FR-4, as amended 2026-11-09: a copy is named by its RUNG, never by the picture's height. */
  var RUNGS = { original: 'original', large: 'large', medium: 'medium', small: 'small' };

  function mb(v) { return v < 1024 ? Math.round(v) + ' MB' : (v / 1024).toFixed(1) + ' GB'; }

  /* 42 % of a 650 MB copy really is 273 MB on the disk. Any frame that names bytes mid-download
     derives them here, so no picture claims a whole file is down while the control says 42 %. */
  function onDisk(e) {
    if (e.state === 'downloaded') return e.mb;
    if (e.state === 'running' || e.state === 'paused') return Math.round(e.mb * (e.pct || 0) / 100);
    return 0;
  }

  /* ---- the strings ----------------------------------------------------------------------
     Shipped keys carry the shipped text verbatim from i18n/{en,da,fo}.json: the shipped file
     wins wherever a string already exists (SKILL-house). A key marked NEW below has no entry in
     i18n yet and is this round's draft, flagged as such in the report.

     `download.waiting_network` is NOT new to this round — the 2026-11-09 amendment to
     a-phone-downloads-titles-to-watch-offline.md names it and its three translations. It is
     listed here because it is not in i18n/*.json yet, so a reader cannot check it against the
     shipped file. */
  var STR = {
    en: {
      'detail.episodes': 'Episodes',
      'detail.season': 'Season',
      'detail.shuffle': 'Shuffle',
      'action.play': 'Play',
      'action.watched': 'Watched',
      'action.mark_watched': 'Mark Watched',
      'action.cancel': 'Cancel',
      'player.up_next': 'UP NEXT',
      'download.action': 'Download',
      'download.queued': 'Queued',
      'download.downloaded': 'Downloaded',
      'download.retry': 'Retry',
      'download.failed': 'The download didn’t finish',
      'download.season': 'Download season',
      'download.delete': 'Delete',
      'download.delete_one_confirm': 'Delete {title}? This frees {size}.',
      'download.waiting_wifi': 'Waiting for Wi-Fi',
      'download.waiting_server': 'Waiting for the server',
      'download.waiting_network': 'Waiting for a connection',   /* specified 2026-11-09, not in i18n yet */
      'download.paused': 'Paused',                              /* NEW */
      'download.pause': 'Pause',
      'download.cancel_dl': 'Cancel download',                  /* NEW */
      'download.season_all': 'Every episode is on this phone',   /* NEW */
      'download.episode_a11y': 'Download {code}',                /* NEW */
      'month.sep': 'Sep', 'month.oct': 'Oct',
      'date.month_day_year': '{month} {day}, {year}'
    },
    da: {
      'detail.episodes': 'Episoder',
      'detail.season': 'Sæson',
      'detail.shuffle': 'Bland',
      'action.play': 'Afspil',
      'action.watched': 'Set',
      'action.mark_watched': 'Marker som set',
      'action.cancel': 'Annuller',
      'player.up_next': 'NÆSTE',
      'download.action': 'Hent',
      'download.queued': 'I kø',
      'download.downloaded': 'Hentet',
      'download.retry': 'Prøv igen',
      'download.failed': 'Hentningen blev ikke færdig',
      'download.season': 'Hent sæson',
      'download.delete': 'Slet',
      'download.delete_one_confirm': 'Slet {title}? Det frigør {size}.',
      'download.waiting_wifi': 'Venter på Wi-Fi',
      'download.waiting_server': 'Venter på serveren',
      'download.waiting_network': 'Venter på forbindelse',
      'download.paused': 'På pause',                        /* NEW */
      'download.pause': 'Pause',
      'download.cancel_dl': 'Afbryd hentningen',            /* NEW */
      'download.season_all': 'Alle episoder er på denne telefon',   /* NEW */
      'download.episode_a11y': 'Hent {code}',                    /* NEW */
      'month.sep': 'Sep', 'month.oct': 'Okt',
      'date.month_day_year': '{day}. {month} {year}'
    },
    fo: {
      'detail.episodes': 'Partar',
      'detail.season': 'Sesong',
      'detail.shuffle': 'Blanda',
      'action.play': 'Spæl',
      'action.watched': 'Sætt',
      'action.mark_watched': 'Merk sum sætt',
      'action.cancel': 'Angra',
      'player.up_next': 'NÆSTI',
      'download.action': 'Tak niður',
      'download.queued': 'Í bíðirøð',
      'download.downloaded': 'Tokið niður',
      'download.retry': 'Royn aftur',
      'download.failed': 'Niðurtakið gjørdist ikki liðugt',
      'download.season': 'Tak røðina niður',
      'download.delete': 'Strika',
      'download.delete_one_confirm': 'Strika {title}? Tað frígerð {size}.',
      'download.waiting_wifi': 'Bíðar eftir Wi-Fi',
      'download.waiting_server': 'Bíðar eftir ambætaranum',
      'download.waiting_network': 'Bíðar eftir sambandi',
      'download.paused': 'Á steðgi',                   /* NEW */
      'download.pause': 'Steðga',
      'download.cancel_dl': 'Strika niðurtakið',       /* NEW */
      'download.season_all': 'Øll pløg eru á hesi telefonini',   /* NEW */
      'download.episode_a11y': 'Tak {code} niður',          /* NEW */
      'month.sep': 'Sep', 'month.oct': 'Okt',
      'date.month_day_year': '{day}. {month} {year}'
    }
  };

  /* ---- the geometry, as numbers ---------------------------------------------------------
     Every one of these is either read out of the shipped Compose (named below) or is this
     round's proposal. The report carries the same table.

     Shipped, unchanged by this round:
       card         320 dp wide, radius 12, 16:9 still, body padding 14 / 12
       rail         gutter 20 dp (raviloHPad, compact), item spacing 12 dp (RaviloDimens)
       card -> line 6 dp (SeriesDetailScreen Spacer)
       toggle       radius 8, padding 10 / 5, glyph 13 dp, gap 5, text 12 sp
       mark         18 dp box, radius 6, inset 5 (MarkSize.EPISODE)
       control      44 dp high, radius 12, border 1, label 15 sp Sora 600 (film page)

     This round's proposal:
       control      52 x 44 dp, icon 17 dp, percentage 13 sp Sora 600
       line         44 dp tall, 320 dp wide, toggle at the start, control at the end
       divider (B)  1 px, full body height, 12 dp left of the control */
  var GEOM = {
    cardW: 320, cardR: 12, bodyPadX: 14, bodyPadY: 12,
    railGutter: 20, railGap: 12, cardToLine: 6,
    lineH: 44, ctlW: 52, ctlH: 44, ctlR: 12, ctlIcon: 17, ctlPct: 13,
    markBox: 18, markR: 6, markInset: 5,
    togR: 8, togPadX: 10, togPadY: 5, togIcon: 13
  };

  /* ---- the two directions ---------------------------------------------------------------- */
  var DIRECTIONS = {
    a: { key: 'a', name: 'Under the card', recommended: true },
    b: { key: 'b', name: 'In the card body', recommended: false }
  };

  window.ED = {
    SERIES: SERIES, VIEWER: VIEWER, CLOCK: CLOCK,
    EPISODES: EPISODES, WAIT_VARIANTS: WAIT_VARIANTS, ALL_DOWNLOADED: ALL_DOWNLOADED,
    MULTI: MULTI, RUNGS: RUNGS, GEOM: GEOM, DIRECTIONS: DIRECTIONS,
    STR: STR, mb: mb, onDisk: onDisk
  };
})();
