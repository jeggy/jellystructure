/* Ravilo localization — interface language per Jellyfin user (set in Jellystructure).
   Supported: English (en), Danish (da), Faroese (fo). window.t(key[, vars]) translates;
   window.setRaviloLang(code) switches; falls back to en for any missing key. */
(function () {
  const STR = {
    en: {
      nav_top10: 'Top 10', request_fetch: 'Request', requesting: 'Requesting…', fetching: 'Fetching', in_library: 'In Library',
      watch_now: 'Watch Now', not_in_library: 'Not in your library yet', requested_via: 'Requested · fetching it now',
      in_queue: 'In queue', importing: 'Importing…', requested: 'Requested', failed: 'Failed', stalled: 'stalled', starting: 'starting', retry_fetch: 'Retry', watch_e1: 'Watch Now · E1',
      req_in_language: 'Request in…', req_change_language: 'Change language', req_waiting_for: 'Waiting for a {lang} release', req_in_progress: 'In progress', req_confirm_hint: 'Select confirms · Back cancels',
      upcoming: 'Airing soon', next_ep: 'Next episode', airs: 'airs', via_sonarr: 'Sonarr',
      top10_sub: 'Trending now · {region}', weeks_on: '{n} wks trending', new_this_week: 'New this week',
      why_trending: 'Why it’s trending', rank_in: '#{n} in {region}', views_week: '{v} this week', via_source: 'via {src}',
      nav_home: 'Home', nav_movies: 'Movies', nav_series: 'Series', nav_mylist: 'My List',
      play: 'Play', resume: 'Resume', more_info: 'More Info', trailer: 'Trailer',
      add_list: 'My List', close: 'Close', see_all: 'See all', next_episode: 'Next episode',
      channels: 'Channels & Collections', channels_sub: 'Configured in Jellystructure',
      more_like_this: 'More Like This', back_home: 'Home', channel: 'Channel',
      channel_sub: 'The same rows you love, filtered to {name}. Continue watching, newly added, and every genre — scoped to this channel.',
      row_continue: 'Continue Watching', row_new_movies: 'Newly Added Movies',
      row_new_series: 'Newly Added Series', row_newly_added: 'Newly Added',
      whos_watching: 'Who\u2019s watching?', switch_profile: 'Switch profile', add_user: 'Add user',
      admin: 'admin', kids: 'KIDS', cancel: 'Cancel', back: 'Back',
      profiles_hint: 'Signed-in users stay on this TV — switching is instant. Manage in Jellystructure.',
      add_title: 'Add a user',
      add_sub: 'On your phone or computer, open Jellystructure → Ravilo → Pair a TV and enter this code. The new user is added here and stays signed in.',
      waiting: 'Waiting for approval…', search_ph: 'Search…', signed_in_as: 'Signed in as {name}',
      lt_live_tv: 'Live TV', lt_live: 'LIVE', lt_on_now: 'On now', lt_next: 'Next', lt_now: 'Now',
      lt_channels: 'channels', lt_from_jellyfin: 'guide updates automatically',
      lt_open_guide: 'Open TV Guide', lt_guide_sub: 'Browse every channel', lt_tv_guide: 'TV Guide',
      lt_all_channels: 'All channels', lt_channel: 'Channel', lt_now_next: 'Now / Next',
      lt_browse: 'browse', lt_tune: 'tune', lt_change_channel: 'change channel', lt_no_channel: 'No channel',
    },
    da: {
      nav_top10: 'Top 10', request_fetch: 'Anmod', requesting: 'Anmoder…', fetching: 'Henter', in_library: 'I biblioteket',
      watch_now: 'Se nu', not_in_library: 'Ikke i dit bibliotek endnu', requested_via: 'Anmodet · henter den nu',
      in_queue: 'I kø', importing: 'Importerer…', requested: 'Anmodet', failed: 'Mislykkedes', stalled: 'i stå', starting: 'starter', retry_fetch: 'Prøv igen', watch_e1: 'Se nu · E1',
      req_in_language: 'Anmod på sprog…', req_change_language: 'Skift sprog', req_waiting_for: 'Venter på en {lang}-udgivelse', req_in_progress: 'I gang', req_confirm_hint: 'Vælg bekræfter · Tilbage annullerer',
      upcoming: 'Kommer snart', next_ep: 'Næste afsnit', airs: 'sendes', via_sonarr: 'Sonarr',
      top10_sub: 'Populært nu · {region}', weeks_on: '{n} uger på listen', new_this_week: 'Ny i denne uge',
      why_trending: 'Hvorfor den er populær', rank_in: '#{n} i {region}', views_week: '{v} i denne uge', via_source: 'via {src}',
      nav_home: 'Hjem', nav_movies: 'Film', nav_series: 'Serier', nav_mylist: 'Min liste',
      play: 'Afspil', resume: 'Forts\u00e6t', more_info: 'Mere info', trailer: 'Trailer',
      add_list: 'Min liste', close: 'Luk', see_all: 'Se alle', next_episode: 'N\u00e6ste afsnit',
      channels: 'Kanaler & samlinger', channels_sub: 'Konfigureret i Jellystructure',
      more_like_this: 'Mere som dette', back_home: 'Hjem', channel: 'Kanal',
      channel_sub: 'De samme r\u00e6kker, filtreret til {name}. Forts\u00e6t med at se, nyligt tilf\u00f8jet og alle genrer — afgr\u00e6nset til denne kanal.',
      row_continue: 'Forts\u00e6t med at se', row_new_movies: 'Nyligt tilf\u00f8jede film',
      row_new_series: 'Nyligt tilf\u00f8jede serier', row_newly_added: 'Nyligt tilf\u00f8jet',
      whos_watching: 'Hvem ser med?', switch_profile: 'Skift profil', add_user: 'Tilf\u00f8j bruger',
      admin: 'admin', kids: 'B\u00d8RN', cancel: 'Annull\u00e9r', back: 'Tilbage',
      profiles_hint: 'Tilmeldte brugere bliver p\u00e5 dette tv — skift er \u00f8jeblikkeligt. Administr\u00e9r i Jellystructure.',
      add_title: 'Tilf\u00f8j en bruger',
      add_sub: 'P\u00e5 din telefon eller computer skal du \u00e5bne Jellystructure → Ravilo → Par et tv og indtaste denne kode. Den nye bruger tilf\u00f8jes her og forbliver logget ind.',
      waiting: 'Venter p\u00e5 godkendelse…', search_ph: 'S\u00f8g…', signed_in_as: 'Logget ind som {name}',
      lt_live_tv: 'Live TV', lt_live: 'LIVE', lt_on_now: 'Sendes nu', lt_next: 'N\u00e6ste', lt_now: 'Nu',
      lt_channels: 'kanaler', lt_from_jellyfin: 'guiden opdateres automatisk',
      lt_open_guide: '\u00c5bn tv-guide', lt_guide_sub: 'Gennemse alle kanaler', lt_tv_guide: 'Tv-guide',
      lt_all_channels: 'Alle kanaler', lt_channel: 'Kanal', lt_now_next: 'Nu / N\u00e6ste',
      lt_browse: 'gennemse', lt_tune: 'stil ind', lt_change_channel: 'skift kanal', lt_no_channel: 'Ingen kanal',
    },
    fo: {
      nav_top10: 'Top 10', request_fetch: 'Bið', requesting: 'Biður…', fetching: 'Heintar', in_library: 'Í savninum',
      watch_now: 'Sígj nú', not_in_library: 'Ikki í savninum enn', requested_via: 'Biðið · heintar hana nú',
      in_queue: 'Í bíðiraði', importing: 'Innflyti…', requested: 'Biðið', failed: 'Miseydnaðist', stalled: 'steðgað', starting: 'byrjar', retry_fetch: 'Royn aftur', watch_e1: 'Sígj nú · E1',
      req_in_language: 'Bið á máli…', req_change_language: 'Skift mál', req_waiting_for: 'Bíðar eftir {lang}-útgávu', req_in_progress: 'Í gongd', req_confirm_hint: 'Vel váttar · Aftur avlýsir',
      upcoming: 'Kemur skjótt', next_ep: 'Næsti táttur', airs: 'verður sendur', via_sonarr: 'Sonarr',
      top10_sub: 'Vinsælt nú · {region}', weeks_on: '{n} vikur á listanum', new_this_week: 'Nýtt hesa viku',
      why_trending: 'Hví tað er vinsælt', rank_in: '#{n} í {region}', views_week: '{v} hesa viku', via_source: 'via {src}',
      nav_home: 'Heim', nav_movies: 'Filmar', nav_series: 'S\u00f8gur', nav_mylist: 'M\u00edn listi',
      play: 'Spæl', resume: 'Hald fram', more_info: 'Meira', trailer: 'Trailer',
      add_list: 'M\u00edn listi', close: 'Lat aftur', see_all: 'S\u00edgj \u00f8ll', next_episode: 'N\u00e6sti part',
      channels: 'R\u00e1sir & savn', channels_sub: 'Sett upp \u00ed Jellystructure',
      more_like_this: 'Meira sum hetta', back_home: 'Heim', channel: 'R\u00e1s',
      channel_sub: 'Somu r\u00f8\u00f0irnar, sila\u00f0ar til {name}. Hald fram, n\u00fdtt og allir sjangrar — avmarka\u00f0 til hesa r\u00e1s.',
      row_continue: 'Hald fram at s\u00edgaccept', row_new_movies: 'N\u00fdggjar filmar',
      row_new_series: 'N\u00fdggjar s\u00f8gur', row_newly_added: 'N\u00fdtt tilskriva\u00f0',
      whos_watching: 'Hv\u00f8r s\u00e6r?', switch_profile: 'Skift vangamynd', add_user: 'Legg afturat brúkara',
      admin: 'admin', kids: 'B\u00d8RN', cancel: 'Avlýs', back: 'Aftur',
      profiles_hint: 'Innrita\u00f0ir br\u00fakarar ver\u00f0a verandi \u00e1 hesum sj\u00f3nvarpi — skift er beinanvegin. Stj\u00f3rna \u00ed Jellystructure.',
      add_title: 'Legg afturat brúkara',
      add_sub: 'Á fartelefon ella telduni: opna Jellystructure → Ravilo → Para sj\u00f3nvarp og skriva henda kotu. N\u00fdggi br\u00fakarin ver\u00f0ur lagdur afturat her.',
      waiting: 'B\u00ed\u00f0ar eftir g\u00f3\u00f0kenning…', search_ph: 'Leita…', signed_in_as: 'Innrita\u00f0ur sum {name}',
      lt_live_tv: 'Beinlei\u00f0is sj\u00f3nvarp', lt_live: 'BEINLEI\u00d0IS', lt_on_now: 'N\u00fa \u00e1 sk\u00edggja', lt_next: 'N\u00e6st', lt_now: 'N\u00fa',
      lt_channels: 'r\u00e1sir', lt_from_jellyfin: 'skr\u00e1in dagf\u00f8rist sj\u00e1lvvirkandi',
      lt_open_guide: 'Lat upp sj\u00f3nvarpsskr\u00e1', lt_guide_sub: 'S\u00edggj allar r\u00e1sir', lt_tv_guide: 'Sj\u00f3nvarpsskr\u00e1',
      lt_all_channels: 'Allar r\u00e1sir', lt_channel: 'R\u00e1s', lt_now_next: 'N\u00fa / N\u00e6st',
      lt_browse: 'kaga', lt_tune: 'stilla', lt_change_channel: 'skift r\u00e1s', lt_no_channel: 'Eingin r\u00e1s',
    },
  };
  // tidy a couple of placeholder-y fo strings that slipped (keep clean fallbacks)
  STR.fo.play = 'Sp\u00e6l';
  STR.fo.row_continue = 'Hald fram';

  // ---- watched-state (R07) ----
  Object.assign(STR.en, {
    mark_watched: 'Mark Watched', watched: 'Watched', play_again: 'Play Again',
    mark_all_watched: 'Mark all watched', mark_all_unwatched: 'Mark all unwatched',
    watched_of: '{w} of {n} watched',
    toast_marked_watched: '\u2713 Marked watched \u00b7 synced to Jellyfin',
    toast_marked_unwatched: 'Marked unwatched \u00b7 synced to Jellyfin',
    toast_all_watched: '\u2713 Season marked watched \u00b7 synced to Jellyfin',
    toast_all_unwatched: 'Season marked unwatched \u00b7 synced to Jellyfin',
  });
  Object.assign(STR.da, {
    mark_watched: 'Mark\u00e9r som set', watched: 'Set', play_again: 'Afspil igen',
    mark_all_watched: 'Mark\u00e9r alle som set', mark_all_unwatched: 'Mark\u00e9r alle som uset',
    watched_of: '{w} af {n} set',
    toast_marked_watched: '\u2713 Markeret som set \u00b7 synket til Jellyfin',
    toast_marked_unwatched: 'Markeret som uset \u00b7 synket til Jellyfin',
    toast_all_watched: '\u2713 S\u00e6son markeret som set \u00b7 synket til Jellyfin',
    toast_all_unwatched: 'S\u00e6son markeret som uset \u00b7 synket til Jellyfin',
  });
  // ---- Profile menu + Discover tabs (nav restructure) ----
  Object.assign(STR.en, { nav_discover: 'Discover', pm_switch: 'Switch', pm_continue: 'Continue Watching', pm_settings: 'Settings', pm_unpair: 'Unpair this TV', seg_coming: 'Coming Soon', seg_request: 'Request', request_sub: 'Browse the catalogue and request what is missing', search_seerr: 'Search Seerr' });
  Object.assign(STR.da, { nav_discover: 'Opdag', pm_switch: 'Skift', pm_continue: 'Fortsæt', pm_settings: 'Indstillinger', pm_unpair: 'Frakobl dette TV', seg_coming: 'Kommende', seg_request: 'Anmod', request_sub: 'Gennemse kataloget og anmod om det, der mangler', search_seerr: 'Søg i Seerr' });
  Object.assign(STR.fo, { nav_discover: 'Uppdaga', pm_switch: 'Skift', pm_continue: 'Hald fram', pm_settings: 'Innstillingar', pm_unpair: 'Frákopla sjónvarp', seg_coming: 'Kemur', seg_request: 'Bið', request_sub: 'Kaga í savninum og bið um tað, sum vantar', search_seerr: 'Leita í Seerr' });

  // ---- Upcoming calendar ----
  Object.assign(STR.en, {
    nav_upcoming: 'Upcoming', upcoming_sub: 'New episodes & movie premieres, coming soon',
    up_today: 'Today', up_tomorrow: 'Tomorrow', up_all: 'All', up_series: 'Series', up_movies: 'Movies',
    up_release: '{n} release', up_releases: '{n} releases', up_nothing: 'Nothing scheduled',
    up_airs_in: 'Airs in {n} days', up_airs_today: 'Airs today', up_airs_tomorrow: 'Airs tomorrow',
    up_schedule: 'Schedule', up_airdate: 'Air date', up_airtime: 'Air time', up_release_type: 'Release',
    up_network: 'Network', up_studio: 'Studio', up_monitored: 'Monitored', up_unmonitored: 'Not monitored',
    up_quality: 'Quality', up_arriving: 'Arriving soon', up_episode: 'Episode', up_movie: 'Movie', up_new_episode: 'New Episode', up_premiere: 'Premiere', up_reldate: 'Release date',
  });
  Object.assign(STR.da, {
    nav_upcoming: 'Kommende', upcoming_sub: 'Nye afsnit & filmpremierer, kommer snart',
    up_episode: 'Afsnit', up_movie: 'Film', up_new_episode: 'Nyt afsnit', up_premiere: 'Premiere', up_arriving: 'Kommer snart', up_reldate: 'Udgivelsesdato', up_quality: 'Kvalitet',
    up_today: 'I dag', up_tomorrow: 'I morgen', up_all: 'Alle', up_series: 'Serier', up_movies: 'Film',
    up_airs_today: 'Sendes i dag', up_airs_tomorrow: 'Sendes i morgen', up_airs_in: 'Sendes om {n} dage',
    set_reminder: 'Mind mig', go_to_series: 'G\u00e5 til serie',
  });
  Object.assign(STR.fo, {
    nav_upcoming: 'Kemur', upcoming_sub: 'Nýggjar tættir & filmar, koma skjótt',
    up_episode: 'Táttur', up_movie: 'Filmur', up_new_episode: 'Nýggjur táttur', up_premiere: 'Frumsýning', up_arriving: 'Kemur skjótt', up_reldate: 'Útgevudato', up_quality: 'Góðska',
    up_today: '\u00cd dag', up_tomorrow: '\u00cd morgin', up_all: '\u00d8ll', up_series: 'S\u00f8gur', up_movies: 'Filmar',
    up_airs_today: 'Verður sendur \u00ed dag', up_airs_tomorrow: 'Verður sendur \u00ed morgin', up_airs_in: 'Verður sendur um {n} dagar',
    set_reminder: 'Minn meg', go_to_series: 'Far til s\u00f8gu',
  });

  Object.assign(STR.fo, {
    mark_watched: 'Merk sum s\u00e6tt', watched: 'S\u00e6tt', play_again: 'Sp\u00e6l aftur',
    mark_all_watched: 'Merk \u00f8ll sum s\u00e6dd', mark_all_unwatched: 'Merk \u00f8ll sum \u00f3s\u00e6dd',
    watched_of: '{w} av {n} s\u00e6dd',
    toast_marked_watched: '\u2713 Merkt sum s\u00e6tt \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_marked_unwatched: 'Merkt sum \u00f3s\u00e6tt \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_all_watched: '\u2713 \u00c1rst\u00ed\u00f0 merkt sum s\u00e6dd \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_all_unwatched: '\u00c1rst\u00ed\u00f0 merkt sum \u00f3s\u00e6dd \u00b7 samstillt vi\u00f0 Jellyfin',
  });

  // ---- Upcoming: available + missing/overdue ----
  Object.assign(STR.en, {
    up_available: 'Already available', up_missing: 'Missing', up_due: 'Was due',
    up_missing_title: 'Missing from your library', up_missing_sub: 'Released, but not downloaded yet',
    up_aired_ago: 'Aired {n} days ago', up_released_ago: 'Released {n} days ago',
    up_aired_yest: 'Aired yesterday', up_released_yest: 'Released yesterday',
  });
  Object.assign(STR.da, {
    up_available: 'Allerede tilg\u00e6ngelig', up_missing: 'Mangler',
    up_missing_title: 'Mangler i dit bibliotek', up_missing_sub: 'Udgivet, men ikke hentet endnu',
    up_aired_ago: 'Sendt for {n} dage siden', up_released_ago: 'Udgivet for {n} dage siden',
    up_aired_yest: 'Sendt i g\u00e5r', up_released_yest: 'Udgivet i g\u00e5r',
  });
  Object.assign(STR.fo, {
    up_available: 'Longu t\u00f8kt', up_missing: 'Vantar', up_due: 'Skuldi komi\u00f0',
    up_missing_title: 'Vantar \u00ed savninum', up_missing_sub: 'Givi\u00f0 \u00fat, men ikki heinta\u00f0 enn',
    up_aired_ago: 'Sent fyri {n} d\u00f8gum s\u00ed\u00f0ani', up_released_ago: 'Givi\u00f0 \u00fat fyri {n} d\u00f8gum s\u00ed\u00f0ani',
    up_aired_yest: 'Sent \u00ed gj\u00e1r', up_released_yest: 'Givi\u00f0 \u00fat \u00ed gj\u00e1r',
  });

  Object.assign(STR.en, { language: 'Language', settings: 'Settings', theme: 'Theme', unpair: 'Unpair this TV', unpair_desc: 'Signs out every user and removes this TV\u2019s pairing.', toast_unpaired: '\u2713 This TV has been unpaired', unpair_confirm: 'Unpair this TV?', unpair_yes: 'Yes, unpair' });
  Object.assign(STR.da, { language: 'Sprog', settings: 'Indstillinger', theme: 'Tema', unpair: 'Fjern parring', unpair_desc: 'Logger alle brugere ud og fjerner dette tv\u2019s parring.', toast_unpaired: '\u2713 Dette tv er blevet fjernet', unpair_confirm: 'Fjern parring?', unpair_yes: 'Ja, fjern parring' });
  Object.assign(STR.fo, { language: 'M\u00e1l', settings: 'Stillingar', theme: 'Tema', unpair: 'Avpara sj\u00f3nvarp', unpair_desc: 'Ritar allar br\u00fakarar \u00fat og strikar parringina \u00e1 hesum sj\u00f3nvarpi.', toast_unpaired: '\u2713 Hetta sj\u00f3nvarpi\u00f0 er avpara\u00f0', unpair_confirm: 'Avpara sj\u00f3nvarp?', unpair_yes: 'Ja, avpara' });

  // ---- R175: username/password login (replaces the pairing code) ----
  Object.assign(STR.en, {
    login_title: 'Sign in to Jellyfin', login_sub: 'Use your Jellyfin username and password. Each person signs in once \u2014 their profile stays on this TV.',
    login_user: 'Username', login_pass: 'Password', login_btn: 'Sign in', login_busy: 'Signing in\u2026',
    login_err_user: 'Enter your Jellyfin username.', login_err_pass: 'Enter your password.',
    login_err_cred: 'Wrong username or password \u2014 check them and try again.',
    key_shift: '\u21e7 Shift', key_space: 'Space', key_del: '\u232b Delete',
  });
  Object.assign(STR.da, {
    login_title: 'Log ind p\u00e5 Jellyfin', login_sub: 'Brug dit Jellyfin-brugernavn og din adgangskode. Hver person logger ind \u00e9n gang \u2014 profilen bliver p\u00e5 dette tv.',
    login_user: 'Brugernavn', login_pass: 'Adgangskode', login_btn: 'Log ind', login_busy: 'Logger ind\u2026',
    login_err_user: 'Indtast dit Jellyfin-brugernavn.', login_err_pass: 'Indtast din adgangskode.',
    login_err_cred: 'Forkert brugernavn eller adgangskode \u2014 tjek dem og pr\u00f8v igen.',
    key_shift: '\u21e7 Skift', key_space: 'Mellemrum', key_del: '\u232b Slet',
  });
  Object.assign(STR.fo, {
    login_title: 'Rita inn \u00e1 Jellyfin', login_sub: 'Br\u00faka t\u00edtt Jellyfin-br\u00fakaranavn og loyniord. Hv\u00f8r persónur ritar inn eina fer\u00f0 \u2014 vangamyndin ver\u00f0ur verandi \u00e1 hesum sj\u00f3nvarpi.',
    login_user: 'Br\u00fakaranavn', login_pass: 'Loyniord', login_btn: 'Rita inn', login_busy: 'Ritar inn\u2026',
    login_err_user: 'Skriva t\u00edtt Jellyfin-br\u00fakaranavn.', login_err_pass: 'Skriva t\u00edtt loyniord.',
    login_err_cred: 'Skeivt br\u00fakaranavn ella loyniord \u2014 kanna tey og royn aftur.',
    key_shift: '\u21e7 Skift', key_space: 'Millumr\u00fam', key_del: '\u232b Strika',
  });

  let lang = 'en';
  window.RAVILO_I18N = STR;
  window.RAVILO_LANGS_UI = [
    { code: 'en', name: 'English', endo: 'English' },
    { code: 'da', name: 'Danish', endo: 'Dansk' },
    { code: 'fo', name: 'Faroese', endo: 'F\u00f8royskt' },
  ];
  window.setRaviloLang = function (code) { lang = STR[code] ? code : 'en'; window.__raviloLang = lang; };
  window.getRaviloLang = function () { return lang; };
  window.t = function (key, vars) {
    let s = (STR[lang] && STR[lang][key]) != null ? STR[lang][key] : (STR.en[key] != null ? STR.en[key] : key);
    if (vars) Object.keys(vars).forEach(k => { s = s.replace('{' + k + '}', vars[k]); });
    return s;
  };
  window.__raviloLang = 'en';
})();
