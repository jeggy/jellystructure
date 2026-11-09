/* pd-data.js — the ONE array every number in this round's pictures comes from.
   Round slug `phone-downloads`, 2026-11-09. Phone (Pixel 9, 412x915) only.

   Why one file: PROCESS.md — "derive every number in every picture from one array", so
   "do these pictures agree with each other?" is something a reviewer can check rather than
   trust. Every total below is COMPUTED from the per-entry sizes; nothing is typed twice.

   Sizes are stored in MB and formatted decimally (1 GB = 1000 MB), which is the arithmetic
   the brief's own stand-in uses: 8 x 650 MB = 5.2 GB, 4 x 650 MB = 2.6 GB.

   The titles, per-entry sizes, qualities, percentages and the three device figures are the
   brief's STAND-IN, verbatim. Everything else is derived. See REPORT.md "The totals do not
   add up to 37.8 GB" for the one place where a derived total and the brief disagree.
*/
(function () {
  'use strict';

  /* ---- device, from the brief's STAND-IN -------------------------------------------- */
  var DEV = {
    phoneMb: 128000,     // "128 GB phone"
    otherAppsMb: 48600,  // other apps 48.6 GB
    freeMb: 41200,       // free 41.2 GB
    otherProfilesMb: 9400, // other profiles on this phone 9.4 GB
    clock: '20:45',
    profile: 'Eyð'
  };

  /* ---- what is on the phone ---------------------------------------------------------
     `mb` is the size on disk. `pct` is watched progress (FR-9 "progress when watched"),
     NOT download progress — `dl` carries that. `gone` is FR-13's download.gone. */
  var FILMS = [
    { title: 'Heimferð',        q: 'original', mb: 6200, watched: true },
    { title: 'Brotsjór',        q: 'high',     mb: 7900 },
    { title: 'Hraðar Ljós II',  q: 'medium',   mb: 4100, pct: 40 },
    { title: 'Mýrin',           q: 'medium',   mb: 3800, gone: true }
  ];

  var SERIES = [
    { title: 'Nordvest', season: 1, eps: [
      { n: 1, q: 'medium', mb: 650, watched: true },
      { n: 2, q: 'medium', mb: 650, watched: true },
      { n: 3, q: 'medium', mb: 650 },
      { n: 4, q: 'medium', mb: 650 }
    ] },
    { title: 'Havets Hjarta', season: 2, eps: [
      { n: 1, q: 'original', mb: 2300 }
    ] }
  ];

  /* The queue, drawn only in the `downloads-running` state. A running download's bytes on
     disk are its share of the file, which is why the Ravilo segment differs by 0.3 GB
     between the full page and the running page — the partial file is really there. */
  var QUEUE = [
    { title: 'Nordvest', season: 1, n: 5, q: 'medium', mb: 650, dl: 42, state: 'running' },
    { title: 'Arvur',    q: 'high', mb: 0, state: 'wifi' }
  ];

  /* ---- the quality sheets ------------------------------------------------------------
     FR-3: Original with its exact size, then FR-4's smaller copies each marked "about",
     then the phone's free space. `pre` is the preselected row (the last quality picked for
     a title of the same kind). A 1080p original offers no 1080p copy: FR-4 lists a rung
     only when it is clearly smaller than the original. */
  var SHEETS = {
    glasberget: { title: 'Glasberget', kind: 'film', src: '2160p · HEVC', pre: 'high', rows: [
      { q: 'original', mb: 58400, exact: true, label: 'Original', sub: '2160p · HEVC' },
      { q: 'high',     mb: 7900,  label: '1080p' },
      { q: 'medium',   mb: 4100,  label: '720p' },
      { q: 'small',    mb: 1600,  label: '480p' }
    ] },
    heimferd: { title: 'Heimferð', kind: 'film', src: '1080p · H.264', pre: 'medium', rows: [
      { q: 'original', mb: 6200, exact: true, label: 'Original', sub: '1080p · H.264' },
      { q: 'medium',   mb: 3400, label: '720p' },
      { q: 'small',    mb: 1300, label: '480p' }
    ] },
    nordvestS1: { title: 'Nordvest', kind: 'season', season: 1, episodes: 8, have: 4,
      src: '1080p · H.264', pre: 'medium', rows: [
        { q: 'original', mb: 18600, exact: true, label: 'Original', sub: '1080p · H.264' },
        { q: 'medium',   mb: 5200,  label: '720p' }
      ] }
  };

  /* ---- Home / Library cards carrying the mark (FR-8) --------------------------------
     A tile badge is picture quality ONLY (owner 2026-09-28): 4K and HDR, never New Season.
     `pb` here is therefore a quality badge, and `pct` a Continue-row progress bar — the two
     things the brief asks the mark not to fight. */
  var CARDS = {
    /* Continue Watching — every tile here carries a progress bar, because that is what the row
       is. Three of the four combinations the mark has to survive are in this one row: mark +
       progress, mark + progress + 4K badge, and progress + 4K badge with no mark. */
    continue: [
      { title: 'Hraðar Ljós II', year: 2026, genre: 'Thriller', mark: true, pct: 40 },
      { title: 'Brotsjór',       year: 2022, genre: 'Crime', mark: true, pb: '4K', pct: 62 },
      { title: 'Glasberget',     year: 2019, genre: 'Drama', pb: '4K', pct: 18 }
    ],
    /* A plain poster row: the mark alone, and the tiles without it beside it, so "which of
       these is on the phone?" is answerable at a glance. */
    films: [
      { title: 'Heimferð', year: 2021, genre: 'Drama', mark: true },
      { title: 'Nordvest', year: 2023, genre: 'Crime', mark: true },
      { title: 'Mýrin',    year: 2018, genre: 'Crime' },
      { title: 'Arvur',    year: 2023, genre: 'Drama' }
    ]
  };

  /* ---- the film page the control states are drawn on --------------------------------- */
  var FILMPAGE = {
    title: 'Glasberget', year: 2019, rating: '12', runtimeMin: 112,
    genres: ['Drama', 'Mystery']
    // The synopsis is a STRING, not a field: a Danish or Faroese frame that keeps English prose
    // in the one long paragraph on the page is a picture nobody can judge the typography from.
  };

  /* ---- strings. English source, Danish and Faroese drafts (SKILL-house "Words").
     Keys are the spec's own (a-phone-downloads-titles-to-watch-offline.md, "Strings").
     `{size}` is filled at render time from the array above. Keys marked NEW+ are keys this
     round found it needed and the spec does not list — flagged in REPORT.md. ----------- */
  var S = {
    en: {
      'action.play': 'Play', 'action.cancel': 'Cancel', 'action.retry': 'Retry',
      'action.my_list': '＋ My List', 'action.watched': 'Watched',
      'download.action': 'Download',
      'download.queued': 'Queued',
      'download.downloaded': 'Downloaded',
      'download.failed_retry': 'Failed — Retry',
      'download.sheet_title': 'Choose a quality',
      'download.original': 'Original',
      'download.about_size': 'about {size}',
      'download.free_space': '{size} free on this phone',
      'download.no_room': 'Not enough space — free {size} or delete a download',
      'download.not_allowed': "Downloading isn't switched on for your account.",
      'download.waiting_wifi': 'Waiting for Wi-Fi',
      'download.waiting_server': 'Waiting for the server',
      'download.page_title': 'Downloads',
      'download.empty_title': 'Nothing on this phone yet',           // NEW+
      'download.empty': 'Tap Download on a film or an episode and it plays with no connection — on a plane, or anywhere the server cannot be reached.',
      'download.offline': "You're offline — these play without a connection",
      'download.gone': 'No longer in the library',
      'download.delete': 'Delete',
      'download.delete_watched': 'Delete watched',
      'download.delete_all': 'Delete all',
      'download.delete_all_title': 'Delete all downloads?',           // NEW+
      'download.delete_all_confirm': 'This frees {size}.',
      'download.other_profiles': 'Other profiles on this phone',
      'download.season': 'Download season',
      'download.watch_downloads': 'Watch your downloads',
      'download.space_title': 'Space on this phone',                  // NEW+
      'download.seg_ravilo': 'Ravilo', 'download.seg_other': 'Other apps', 'download.seg_free': 'Free',
      'download.select': 'Select',                                    // NEW+
      'download.n_selected': '{n} selected',                          // NEW+
      'download.sheet_button': 'Download · {size}',                   // NEW+
      'download.season_have': '{have} of {all} episodes are already on this phone · {size} to download', // NEW+
      'download.loading': 'Asking the server what it can send…',      // NEW+
      'download.pause': 'Pause', 'download.resume': 'Resume',         // NEW+
      'download.play_offline': 'Play',                                // reuses action.play
      'settings.downloads': 'Downloads',
      'settings.wifi_only': 'Wi-Fi only',
      'settings.wifi_only_sub': 'Only download while this phone is on Wi-Fi.',  // NEW+
      'settings.title': 'Settings', 'settings.general': 'General', 'settings.playback': 'Playback',
      'settings.autoplay_next': 'Autoplay next episode',
      'settings.show_progress': 'Show progress on Continue Watching',
      'error.play.unreachable.title': "Couldn't reach the server",
      'error.play.unreachable.body': 'Check the connection and try again.',
      'profile.account': 'Account', 'nav.home': 'Home', 'nav.library': 'Library',
      'nav.search': 'Search', 'nav.discover': 'Discover', 'nav.profile': 'Profile',
      'row.continue': 'Continue Watching', 'row.films': 'Films', 'row.series': 'Series',
      'row.see_all': 'See all ›', 'lib.films': 'Films',
      'ep.season': 'Season {n}', 'ep.episodes': 'Episodes', 'ep.episode_one': 'Episode',
      'meta.runtime': '{h} h {m} min', 'profile.signed_in': 'Signed in on this phone',
      'film.syn': 'A glassblower returns to the fjord town she left at seventeen, to a workshop that has been waiting for her and a brother who has not.'
    },
    da: {
      'action.play': 'Afspil', 'action.cancel': 'Annuller', 'action.retry': 'Prøv igen',
      'action.my_list': '＋ Min liste', 'action.watched': 'Set',
      'download.action': 'Hent',
      'download.queued': 'I kø',
      'download.downloaded': 'Hentet',
      'download.failed_retry': 'Mislykkedes — prøv igen',
      'download.sheet_title': 'Vælg en kvalitet',
      'download.original': 'Original',
      'download.about_size': 'ca. {size}',
      'download.free_space': '{size} fri på denne telefon',
      'download.no_room': 'Ikke plads nok — frigør {size} eller slet en download',
      'download.not_allowed': 'Download er ikke slået til for din konto.',
      'download.waiting_wifi': 'Venter på Wi-Fi',
      'download.waiting_server': 'Venter på serveren',
      'download.page_title': 'Downloads',
      'download.empty_title': 'Der er intet på denne telefon endnu',
      'download.empty': 'Tryk Hent på en film eller et afsnit, så kan du se det uden forbindelse — i flyet, eller hvor serveren ikke kan nås.',
      'download.offline': 'Du er offline — disse kan ses uden forbindelse',
      'download.gone': 'Ikke længere i biblioteket',
      'download.delete': 'Slet',
      'download.delete_watched': 'Slet set',
      'download.delete_all': 'Slet alle',
      'download.delete_all_title': 'Slet alle downloads?',
      'download.delete_all_confirm': 'Det frigør {size}.',
      'download.other_profiles': 'Andre profiler på denne telefon',
      'download.season': 'Hent sæson',
      'download.watch_downloads': 'Se dine downloads',
      'download.space_title': 'Plads på denne telefon',
      'download.seg_ravilo': 'Ravilo', 'download.seg_other': 'Andre apps', 'download.seg_free': 'Fri',
      'download.select': 'Vælg',
      'download.n_selected': '{n} valgt',
      'download.sheet_button': 'Hent · {size}',
      'download.season_have': '{have} af {all} afsnit er allerede på denne telefon · {size} at hente',
      'download.loading': 'Spørger serveren, hvad den kan sende…',
      'download.pause': 'Pause', 'download.resume': 'Fortsæt',
      'download.play_offline': 'Afspil',
      'settings.downloads': 'Downloads',
      'settings.wifi_only': 'Kun Wi-Fi',
      'settings.wifi_only_sub': 'Hent kun, når denne telefon er på Wi-Fi.',
      'settings.title': 'Indstillinger', 'settings.general': 'Generelt', 'settings.playback': 'Afspilning',
      'settings.autoplay_next': 'Afspil næste episode automatisk',
      'settings.show_progress': 'Vis fremgang på Fortsæt med at se',
      'error.play.unreachable.title': 'Kunne ikke få forbindelse',
      'error.play.unreachable.body': 'Tjek forbindelsen, og prøv igen.',
      'profile.account': 'Konto', 'nav.home': 'Hjem', 'nav.library': 'Bibliotek',
      'nav.search': 'Søg', 'nav.discover': 'Opdag', 'nav.profile': 'Profil',
      'row.continue': 'Fortsæt med at se', 'row.films': 'Film', 'row.series': 'Serier',
      'row.see_all': 'Vis alle ›', 'lib.films': 'Film',
      'ep.season': 'Sæson {n}', 'ep.episodes': 'Afsnit', 'ep.episode_one': 'Afsnit',
      'meta.runtime': '{h} t {m} min', 'profile.signed_in': 'Logget ind på denne telefon',
      'film.syn': 'En glaspuster vender tilbage til den fjordby, hun forlod som syttenårig — til et værksted, der har ventet på hende, og en bror, der ikke har.'
    },
    fo: {
      'action.play': 'Spæl', 'action.cancel': 'Angra', 'action.retry': 'Royn aftur',
      'action.my_list': '＋ Mín listi', 'action.watched': 'Sætt',
      'download.action': 'Tak niður',
      'download.queued': 'Í bíðirøð',
      'download.downloaded': 'Tikið niður',
      'download.failed_retry': 'Miseydnaðist — royn aftur',
      'download.sheet_title': 'Vel eina kvalitet',
      'download.original': 'Original',
      'download.about_size': 'umleið {size}',
      'download.free_space': '{size} tøkt á hesi telefonini',
      'download.no_room': 'Ikki nóg mikið pláss — fríger {size} ella strika eitt niðurtak',
      'download.not_allowed': 'Niðurtak er ikki tendrað fyri tína kontu.',
      'download.waiting_wifi': 'Bíðar eftir Wi-Fi',
      'download.waiting_server': 'Bíðar eftir ambætaranum',
      'download.page_title': 'Niðurtøk',
      'download.empty_title': 'Einki er á hesi telefonini enn',
      'download.empty': 'Trýst Tak niður á einum filmi ella parti, so spælir tað uttan samband — í flogfari, ella har ambætarin ikki fæst.',
      'download.offline': 'Tú ert uttan samband — hesi spæla uttan samband',
      'download.gone': 'Ikki longur í savninum',
      'download.delete': 'Strika',
      'download.delete_watched': 'Strika sædd',
      'download.delete_all': 'Strika alt',
      'download.delete_all_title': 'Strika øll niðurtøk?',
      'download.delete_all_confirm': 'Tað frígerð {size}.',
      'download.other_profiles': 'Aðrir vangar á hesi telefonini',
      'download.season': 'Tak røðina niður',
      'download.watch_downloads': 'Hygg at tínum niðurtøkum',
      'download.space_title': 'Pláss á hesi telefonini',
      'download.seg_ravilo': 'Ravilo', 'download.seg_other': 'Aðrar appir', 'download.seg_free': 'Tøkt',
      'download.select': 'Vel',
      'download.n_selected': '{n} vald',
      'download.sheet_button': 'Tak niður · {size}',
      'download.season_have': '{have} av {all} pørtum eru longu á hesi telefonini · {size} at taka niður',
      'download.loading': 'Spyr ambætaran, hvat hann kann senda…',
      'download.pause': 'Steðga', 'download.resume': 'Halt fram',
      'download.play_offline': 'Spæl',
      'settings.downloads': 'Niðurtøk',
      'settings.wifi_only': 'Bert Wi-Fi',
      'settings.wifi_only_sub': 'Tak bert niður, tá hendan telefonin er á Wi-Fi.',
      'settings.title': 'Stillingar', 'settings.general': 'Alment', 'settings.playback': 'Spæling',
      'settings.autoplay_next': 'Spæl næsta part sjálvvirkandi',
      'settings.show_progress': 'Vís framgongd á Halt fram at síggja',
      'error.play.unreachable.title': 'Fekk ikki samband',
      'error.play.unreachable.body': 'Kanna sambandið og royn aftur.',
      'profile.account': 'Konta', 'nav.home': 'Heim', 'nav.library': 'Savn',
      'nav.search': 'Leita', 'nav.discover': 'Uppdaga', 'nav.profile': 'Vangi',
      'row.continue': 'Halt fram at síggja', 'row.films': 'Filmar', 'row.series': 'Seriur',
      'row.see_all': 'Vís allar ›', 'lib.films': 'Filmar',
      'ep.season': 'Sesong {n}', 'ep.episodes': 'Partar', 'ep.episode_one': 'Partur',
      'meta.runtime': '{h} t {m} min', 'profile.signed_in': 'Ritað inn á hesi telefonini',
      'film.syn': 'Ein glasblásari kemur aftur til fjarðarbýin, hon fór frá, tá hon var seytjan ára — til eitt verkstað, sum hevur bíðað eftir henni, og ein bróður, sum ikki hevur.'
    }
  };

  /* Quality labels a viewer sees on an entry (FR-9): Original, 1080p, 720p, 480p. The same
     four words in all three languages — they are resolutions, not prose. */
  var QLABEL = { original: 'Original', high: '1080p', medium: '720p', small: '480p' };

  /* ---- formatting + derived totals --------------------------------------------------
     Every total in every picture comes out of here. Nothing below is a typed number. */
  /* One decimal, always. `22 GB` beside `26.9 GB` and `4.9 GB` reads as a different kind of
     number; `22.0 GB` reads as the same column. */
  function gb(mb) { return (mb / 1000).toFixed(1) + ' GB'; }
  function size(mb) { return mb < 1000 ? Math.round(mb) + ' MB' : gb(mb); }

  function seriesMb(s) { return s.eps.reduce(function (a, e) { return a + e.mb; }, 0); }
  function queueOnDiskMb(q) { return Math.round(q.mb * (q.dl || 0) / 100); }

  /* `yoursMb(withQueue)` — this profile's bytes on disk. FR-12: another profile never sees
     them, "not in the total", which is what fixes the Profile row and Delete all to THIS
     number rather than to everything Ravilo holds. */
  function yoursMb(withQueue) {
    var t = 0;
    FILMS.forEach(function (f) { t += f.mb; });
    SERIES.forEach(function (s) { t += seriesMb(s); });
    if (withQueue) QUEUE.forEach(function (q) { t += queueOnDiskMb(q); });
    return t;
  }
  /* The storage bar's Ravilo segment is everything RAVILO holds on this phone — this
     profile plus the other profiles' 9.4 GB, which FR-12 names on its own line directly
     beneath. That is the figure the owner asked to be in control of ("how much space the
     app is using"); `yoursMb` is the figure a delete frees. Both are drawn, labelled. */
  function raviloMb(withQueue) { return yoursMb(withQueue) + DEV.otherProfilesMb; }

  /* What `Delete watched` frees (FR-10). Derived, so the number on the control and the ticks in
     the list are the same fact. */
  function watchedMb() {
    var t = 0;
    FILMS.forEach(function (f) { if (f.watched) t += f.mb; });
    SERIES.forEach(function (s) { s.eps.forEach(function (e) { if (e.watched) t += e.mb; }); });
    return t;
  }

  /* FR-3's room test: a choice larger than the free space minus 1 GB is disabled. */
  var ROOM_RESERVE_MB = 1000;
  function roomFor(mb) { return mb <= DEV.freeMb - ROOM_RESERVE_MB; }
  function shortfallMb(mb) { return mb - (DEV.freeMb - ROOM_RESERVE_MB); }

  window.PD = {
    DEV: DEV, FILMS: FILMS, SERIES: SERIES, QUEUE: QUEUE, SHEETS: SHEETS, CARDS: CARDS,
    FILMPAGE: FILMPAGE, S: S, QLABEL: QLABEL,
    gb: gb, size: size, seriesMb: seriesMb, queueOnDiskMb: queueOnDiskMb,
    yoursMb: yoursMb, raviloMb: raviloMb, watchedMb: watchedMb,
    roomFor: roomFor, shortfallMb: shortfallMb, ROOM_RESERVE_MB: ROOM_RESERVE_MB
  };
})();
