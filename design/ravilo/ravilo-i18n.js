/* Ravilo localization — interface language per Jellyfin user (set in Jellystructure).
   Supported: English (en), Danish (da), Faroese (fo). window.t(key[, vars]) translates;
   window.setRaviloLang(code) switches; falls back to en for any missing key. */
(function () {
  const STR = {
    en: {
      nav_top10: 'Top 10', request_fetch: 'Request', requesting: 'Requesting…', fetching: 'Fetching', in_library: 'In Library',
      watch_now: 'Watch Now', not_in_library: 'Not in your library yet', requested_via: 'Requested · Radarr is fetching it',
      in_queue: 'In queue', importing: 'Importing…', requested: 'Requested', failed: 'Failed', stalled: 'stalled', starting: 'starting', retry_fetch: 'Retry', watch_e1: 'Watch Now · E1',
      top10_sub: 'Trending now · {region}', weeks_on: '{n} wks on chart', new_this_week: 'New this week',
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
    },
    da: {
      nav_top10: 'Top 10', request_fetch: 'Anmod', requesting: 'Anmoder…', fetching: 'Henter', in_library: 'I biblioteket',
      watch_now: 'Se nu', not_in_library: 'Ikke i dit bibliotek endnu', requested_via: 'Anmodet · Radarr henter den',
      in_queue: 'I kø', importing: 'Importerer…', requested: 'Anmodet', failed: 'Mislykkedes', stalled: 'i stå', starting: 'starter', retry_fetch: 'Prøv igen', watch_e1: 'Se nu · E1',
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
    },
    fo: {
      nav_top10: 'Top 10', request_fetch: 'Bið', requesting: 'Biður…', fetching: 'Heintar', in_library: 'Í savninum',
      watch_now: 'Sígj nú', not_in_library: 'Ikki í savninum enn', requested_via: 'Biðið · Radarr heintar hana',
      in_queue: 'Í bíðiraði', importing: 'Innflyti…', requested: 'Biðið', failed: 'Miseydnaðist', stalled: 'steðgað', starting: 'byrjar', retry_fetch: 'Royn aftur', watch_e1: 'Sígj nú · E1',
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
  Object.assign(STR.fo, {
    mark_watched: 'Merk sum s\u00e6tt', watched: 'S\u00e6tt', play_again: 'Sp\u00e6l aftur',
    mark_all_watched: 'Merk \u00f8ll sum s\u00e6dd', mark_all_unwatched: 'Merk \u00f8ll sum \u00f3s\u00e6dd',
    watched_of: '{w} av {n} s\u00e6dd',
    toast_marked_watched: '\u2713 Merkt sum s\u00e6tt \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_marked_unwatched: 'Merkt sum \u00f3s\u00e6tt \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_all_watched: '\u2713 \u00c1rst\u00ed\u00f0 merkt sum s\u00e6dd \u00b7 samstillt vi\u00f0 Jellyfin',
    toast_all_unwatched: '\u00c1rst\u00ed\u00f0 merkt sum \u00f3s\u00e6dd \u00b7 samstillt vi\u00f0 Jellyfin',
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
