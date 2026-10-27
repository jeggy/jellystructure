/* rd-data.js — THE round's one data file.
   Every number, time, name, percentage and count in every picture of this round comes from here,
   so "do these pictures agree with each other?" is something a reviewer can check rather than
   trust (PROCESS.md, "Derive every number in every picture from one array").

   THE THREE TV CHANNELS ARE NOT RETYPED. They are read live out of the shipped live-TV mock
   (`design/ravilo/ravilo-livetv-data.js`, loaded before this file), which fixes now at 20:14 and
   generates the schedule from a seeded PRNG. The brief names "1 Kringvarp 1, 2 Kringvarp 2 and
   3 DR1, from that mock, with its programmes" — so the names, the logos, the gradients and the
   programmes are the mock's, and only the NUMBERS are the brief's: the mock numbers that lineup
   101, 102, 104 and the brief's lineup is 1…6. The renumbering is in ONE place, below.

   The three stations are invented here, as the brief names them. Their logos are initials on a
   flat invented gradient, which is the mock's own device for a channel logo — no broadcaster's
   mark is drawn, redrawn or approximated anywhere in this round.
*/
(function () {
  'use strict';
  const LT = window.LiveTV;

  /* 20:14, the mock's fixed live edge. Everything clock-shaped in this round reads it. */
  const NOW = LT.NOW;
  const fmt = LT.fmt;

  /* ---- the TV channels, from the mock, renumbered to the brief's lineup ----------------------
     The index into the mock's array is the only thing written here; the brief's number is the
     position in the array below plus one. */
  const TV = [0, 1, 3].map(function (i, pos) {
    const ch = LT.channels[i];
    const nn = LT.nowNext(ch.id);
    return {
      id: ch.id, num: pos + 1, name: ch.name, kind: 'tv',
      initials: ch.initials, grad: ch.grad, hue: ch.hue,
      cat: ch.cat, catLabel: ch.catLabel,
      now: nn.now, next: nn.next,
    };
  });

  /* ---- the stations, invented, in the brief's words ------------------------------------------
     Harbour Radio's guide is the brief's: Evening News 20:00–20:30 (47 % through at 20:14, which
     is what (NOW − start) / (end − start) gives), then Music through the evening 20:30–22:00.
     Stations 5 and 6 have no guide at all, which is the case FR-6 and FR-7 draw differently. */
  const ST = [
    {
      id: 'st1', num: 4, name: 'Harbour Radio', kind: 'radio',
      initials: 'HR', hue: 188,
      grad: 'linear-gradient(135deg, hsl(188 48% 34%), hsl(206 56% 18%))',
      cat: 'radio', catLabel: 'Radio',
      now: { title: 'Evening News', start: 20 * 60, end: 20 * 60 + 30, sub: 'Live' },
      next: { title: 'Music through the evening', start: 20 * 60 + 30, end: 22 * 60 },
    },
    {
      id: 'st2', num: 5, name: 'Harbour Radio 2', kind: 'radio',
      initials: 'HR2', hue: 204,
      grad: 'linear-gradient(135deg, hsl(204 46% 32%), hsl(222 54% 17%))',
      cat: 'radio', catLabel: 'Radio',
      now: null, next: null,
    },
    {
      id: 'st3', num: 6, name: 'Assembly Radio', kind: 'radio',
      initials: 'AR', hue: 166,
      grad: 'linear-gradient(135deg, hsl(166 42% 31%), hsl(184 50% 17%))',
      cat: 'radio', catLabel: 'Radio',
      now: null, next: null,
    },
  ];

  /* THE ONE LINEUP. FR-3: "Number and order share the one lineup with the TV channels." Radio is
     not a second list anywhere in this round — the live row, the guide, the zap order, Now/Next
     and the admin table all read THIS array, in THIS order, and that is the whole argument that
     a station is placed like a channel. */
  const LINEUP = TV.concat(ST);

  /* The live row the brief names: five of the six, Assembly Radio off the right edge, which is
     also what a 1920 row does with six 340 px cards and a leading guide tile. */
  const ROW = [TV[0], TV[1], TV[2], ST[0], ST[1]];

  /* ---- the speakers (FR-11, R371's rooms) ---------------------------------------------------- */
  const SPEAKERS = {
    living: { id: 'sp-living', line: 'Living room', rooms: [{ id: 'living', name: 'Living room', vol: 38 }] },
    downstairs: {
      id: 'sp-downstairs', line: 'Downstairs', group: true,
      rooms: [
        { id: 'living', name: 'Living room', vol: 38 },
        { id: 'kitchen', name: 'Kitchen', vol: 24 },
      ],
    },
  };
  /* The group's own level, which R371 draws above the rooms. */
  SPEAKERS.downstairs.master = 31;
  SPEAKERS.living.master = 38;

  /* ---- the viewer ---------------------------------------------------------------------------- */
  const VIEWER = 'Anna';

  /* ---- burn-in (FR-8: "the audio layout moves its content a little every few minutes") --------
     FOUR offsets on a 28-minute loop: one step every SEVEN minutes, 56 px of horizontal travel
     and 36 px of vertical, which at 1920×1080 is 2.9 % of the width and 3.3 % of the height. The
     drawn frame is step 1 — right and down — because that is the step furthest from the frame the
     viewer has been looking at, and so the one that shows the movement at all in a still picture.
     Under reduced motion the step is a CUT; otherwise it glides over 600 ms. */
  const BURN = {
    stepMinutes: 7,
    offsets: [{ x: 0, y: 0 }, { x: 28, y: 18 }, { x: 0, y: 36 }, { x: -28, y: 18 }],
    glideMs: 600,
    step: 1,
  };

  /* ---- Music Home's existing rows (the music mock's own), with Radio to be placed among them --
     The album, artist and genre rows are the shipped mock's and come out of the shipped corpus
     (`design/app/music-data.js`, loaded before this file): these ids are `ravilo-music.js`'s own
     RECENT list, less `live-at-the-harbour`, which that list names and the corpus does not hold.
     This round adds nothing to those rows and reorders nothing in them. Only WHERE the Radio row
     goes is the question, so the row list is written as names and the position is an index. */
  const RECENT_ALBUMS = ['salt-on-the-window', 'glass-birds', 'signal-lost', 'brim', 'stormur',
    'undertow', 'kite-weather', 'north-atlantic-songbook'];
  const MUSIC_ROWS = ['recent_albums', 'recent_played', 'artists', 'genres'];

  /* ---- strings. en source, da and fo drafts (SKILL-house: "Words"). ------------------------- */
  const I18N = {
    /* ---- SHIPPED strings are taken from `i18n/*.json` VERBATIM, and the key they came from is
       named beside them. SKILL-house: "The shipped i18n/*.json wins wherever a string already
       exists." Three of this round's drafts disagreed with the shipped file and were corrected
       here — *On Now*, *Recently added* and *Playing on {d}* — which is also how the divergence
       between `design/ravilo/ravilo-i18n.js` and the shipped JSON was found (see REPORT.md).
       The genuinely NEW strings, FR-13's, are marked NEW. ---- */
    en: {
      radio: 'Radio',                                  /* NEW — FR-13 */
      live_radio: 'Live radio',                        /* NEW — FR-13 */
      stopped: 'Stopped',                              /* NEW */
      tune_in_again: 'Press play to tune in again',    /* NEW */
      cant_play: 'Couldn\u2019t play {x}',                /* NEW — music.fail_t, with the name */
      rooms: 'Rooms',                                  /* NEW */
      live: 'LIVE',                                    /* livetv.live_badge */
      on_now: 'On Now',                                /* livetv.on_now */
      next: 'Next',                                    /* livetv.next */
      now: 'Now',                                      /* livetv.now */
      live_tv: 'Live TV',                              /* livetv.guide_kicker */
      open_guide: 'Open TV Guide',                     /* livetv.open_guide */
      guide_sub: 'Browse every channel',               /* livetv.guide_subtitle */
      tv_guide: 'TV Guide',                            /* livetv.guide */
      channels: 'channels', channel: 'Channel', now_next: 'Now / Next',
      browse: 'browse', tune: 'tune', change_channel: 'change channel',
      all_channels: 'All channels', guide_auto: 'guide updates automatically',
      try_again: 'Try again',                          /* music.try_again */
      recent_albums: 'Recently added',                 /* mhome.recent_albums */
      recent_played: 'Recently played',                /* mhome.recent_played */
      artists: 'Artists',                              /* mhome.artists */
      see_all: 'See all',                              /* music.see_all */
      playing_on: 'Playing on {d}',                    /* cast.playing_on */
      add_speaker: 'Add a speaker\u2026',                 /* group.add_speaker */
      up_next: 'Up next',                              /* music.up_next */
      stop: 'Stop',                                    /* cast.stop_room */
      nav_listen: 'Listen', nav_library: 'Library',    /* mnav.listen, nav.library */
      nav_search: 'Search', nav_settings: 'Settings',  /* nav.search, nav.settings */
      shown_in_ravilo: 'In Ravilo', lineup: 'Channel lineup',
      all: 'All', hidden: 'Hidden', brand_new: 'New', unavailable: 'Unavailable',
      epg: 'EPG', no_guide: 'no guide', category: 'Category', guide_col: 'Guide',
      status: 'Status', order: 'Order', new_since: '\u2726 New since sync',
    },
    da: {
      radio: 'Radio',
      live_radio: 'Live radio',
      stopped: 'Stoppet',
      tune_in_again: 'Tryk p\u00e5 afspil for at stille ind igen',
      cant_play: 'Kunne ikke afspille {x}',
      rooms: 'Rum',
      live: 'LIVE',
      on_now: 'Nu',
      next: 'N\u00e6ste',
      now: 'Nu',
      live_tv: 'Live TV',
      open_guide: '\u00c5bn tv-guide',
      guide_sub: 'Gennemse alle kanaler',
      tv_guide: 'TV-guide',
      channels: 'kanaler', channel: 'Kanal', now_next: 'Nu / N\u00e6ste',
      browse: 'gennemse', tune: 'skift', change_channel: 'skift kanal',
      all_channels: 'Alle kanaler', guide_auto: 'guiden opdateres automatisk',
      try_again: 'Pr\u00f8v igen',
      recent_albums: 'Senest tilf\u00f8jet',
      recent_played: 'Senest afspillet',
      artists: 'Kunstnere',
      see_all: 'Se alle',
      playing_on: 'Spiller p\u00e5 {d}',
      add_speaker: 'Tilf\u00f8j en h\u00f8jttaler\u2026',
      up_next: 'N\u00e6ste',
      stop: 'Stop',
      nav_listen: 'Lyt', nav_library: 'Bibliotek',
      nav_search: 'S\u00f8g', nav_settings: 'Indstillinger',
      shown_in_ravilo: 'I Ravilo', lineup: 'Kanaloversigt',
      all: 'Alle', hidden: 'Skjult', brand_new: 'Ny', unavailable: 'Utilg\u00e6ngelig',
      epg: 'EPG', no_guide: 'ingen guide', category: 'Kategori', guide_col: 'Guide',
      status: 'Status', order: 'R\u00e6kkef\u00f8lge', new_since: '\u2726 Ny siden synk',
    },
    fo: {
      radio: '\u00datvarp',
      live_radio: 'Beinlei\u00f0is \u00fatvarp',
      stopped: 'Ste\u00f0ga\u00f0',
      tune_in_again: 'Tr\u00fdst sp\u00e6l fyri at stilla inn aftur',
      cant_play: 'Kundi ikki sp\u00e6la {x}',
      rooms: 'R\u00fam',
      live: 'BEINLEI\u00d0IS',
      on_now: 'Beinlei\u00f0is n\u00fa',
      next: 'N\u00e6st',
      now: 'N\u00fa',
      live_tv: 'Beinlei\u00f0is',
      open_guide: 'Lat upp sj\u00f3nvarpsskr\u00e1',
      guide_sub: 'S\u00ed allar r\u00e1sir',
      tv_guide: 'Sj\u00f3nvarpsskr\u00e1',
      channels: 'r\u00e1sir', channel: 'R\u00e1s', now_next: 'N\u00fa / N\u00e6st',
      browse: 'kaga', tune: 'skifta', change_channel: 'skifta r\u00e1s',
      all_channels: 'Allar r\u00e1sir', guide_auto: 'skr\u00e1in dagf\u00f8rist sj\u00e1lvvirkandi',
      try_again: 'Royn aftur',
      recent_albums: 'N\u00fdliga lagt afturat',
      recent_played: 'N\u00fdliga sp\u00e6lt',
      artists: 'T\u00f3nleikarar',
      see_all: 'S\u00ed \u00f8ll',
      playing_on: 'Sp\u00e6lir \u00e1 {d}',
      add_speaker: 'Legg ein h\u00e1talara afturat\u2026',
      up_next: 'N\u00e6st',
      stop: 'Ste\u00f0ga',
      nav_listen: 'Lurta', nav_library: 'Savn',
      nav_search: 'Leita', nav_settings: 'Stillingar',
      shown_in_ravilo: '\u00cd Ravilo', lineup: 'R\u00e1saskipan',
      all: 'Allar', hidden: 'Fjalt', brand_new: 'N\u00fdtt', unavailable: '\u00d3t\u00f8kt',
      epg: 'EPG', no_guide: 'eingin skr\u00e1', category: 'B\u00f3lkur', guide_col: 'Skr\u00e1',
      status: 'St\u00f8\u00f0a', order: 'Ra\u00f0fylgja', new_since: '\u2726 N\u00fdtt s\u00ed\u00f0an samstilling',
    },
  };

  /* live progress of a programme at 20:14, as a percentage. One function, every surface. */
  function pct(p) {
    if (!p) return 0;
    return Math.max(0, Math.min(100, (NOW - p.start) / (p.end - p.start) * 100));
  }

  window.RD = {
    NOW: NOW, fmt: fmt, pct: pct,
    TV: TV, ST: ST, LINEUP: LINEUP, ROW: ROW,
    SPEAKERS: SPEAKERS, VIEWER: VIEWER, BURN: BURN,
    MUSIC_ROWS: MUSIC_ROWS, RECENT_ALBUMS: RECENT_ALBUMS, I18N: I18N,
    byNum: function (n) { return LINEUP.filter(function (c) { return c.num === n; })[0] || null; },
  };
})();
