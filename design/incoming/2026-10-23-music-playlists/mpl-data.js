/* mpl-data.js — the ONE array every number in every picture of this round comes from.
   Round slug music-playlists, 2026-10-23. Ravilo music: phone + desktop (Mac, Linux, Ravilo Web).

   Nothing is totalled in the kit and nothing is invented in a frame: a count in a picture is a
   count in this file. That is what makes "do these pictures agree with each other?" something a
   reviewer can check rather than trust.

   STAND-INS. The playlists, the shared playlist, the song, the album and the queue length are the
   brief's, named exactly. Song titles inside a playlist and inside the queue are the music mockup's
   own corpus (design/ravilo/music-play-all/music-play-all-kit.js, which takes them from
   design/app/music-data.js) — a playlist and a queue legitimately span albums, so reusing them
   introduces no contradiction. The nine tracks that sit beside Northern Lights on its own album are
   the one thing the brief did not name; see REPORT.md, which asks for them rather than keeping the
   invention quiet.

   No library title and no real name appears anywhere in this file. */
(function () {
  'use strict';

  /* ---- the viewer's own playlists ---------------------------------------------------------
     `n` is the song count the server would send. `cov` is the four titles the collage takes, in
     order; they are corpus album titles so two playlists never collage identically.
     D6 orders the picker BY NAME, so these are held in name order and the kit never re-sorts. */
  const MINE = [
    { id: 'pl-late', name: 'Late Night', n: 17, cov: ['Signal Lost', 'Myrkrið og ljósið', 'Undertow', 'Brim'] },
    { id: 'pl-morn', name: 'Morning Coffee', n: 12, cov: ['Kite Weather', 'Glass Birds', 'Low Tide Radio', 'Salt on the Window'] },
    { id: 'pl-rain', name: 'Rainy Day', n: 8, cov: ['Salt on the Window', 'Undertow', 'Foghorn Lullabies', 'Kvøld'] },
    { id: 'pl-road', name: 'Road Trip', n: 24, cov: ['Last Stop, Everybody', 'Kite Weather', 'North Atlantic Songbook', 'Stormur'] },
    { id: 'pl-run', name: 'Running', n: 31, cov: ['Stormur', 'Signal Found', 'Brim', 'Velvet Static'] },
  ];

  /* The nine the long list adds, 5–40 songs each (the brief's range). Merged with MINE and sorted
     by name gives the 14-row list that scrolls. */
  const MINE_MORE = [
    { id: 'pl-work', name: 'Workout', n: 19, cov: ['Signal Found', 'Stormur', 'Brim', 'Last Stop, Everybody'] },
    { id: 'pl-focu', name: 'Focus', n: 33, cov: ['Myrkrið og ljósið', 'Kvøld', 'Glass Birds', 'Undertow'] },
    { id: 'pl-dinn', name: 'Dinner', n: 14, cov: ['Low Tide Radio', 'Foghorn Lullabies', 'Glass Birds', 'Kite Weather'] },
    { id: 'pl-sum', name: 'Summer 2026', n: 36, cov: ['Kite Weather', 'Brim', 'Low Tide Radio', 'Signal Found'] },
    { id: 'pl-chil', name: 'Chill', n: 22, cov: ['Undertow', 'Kvøld', 'Salt on the Window', 'Low Tide Radio'] },
    { id: 'pl-kitc', name: 'Kitchen', n: 9, cov: ['Glass Birds', 'Last Stop, Everybody', 'Kite Weather', 'Myrkrið og ljósið'] },
    { id: 'pl-sund', name: 'Sunday', n: 5, cov: ['Foghorn Lullabies', 'Kvøld', 'North Atlantic Songbook', 'Salt on the Window'] },
    { id: 'pl-part', name: 'Party', n: 40, cov: ['Last Stop, Everybody', 'Signal Lost', 'Stormur', 'Signal Found'] },
    { id: 'pl-stud', name: 'Study', n: 28, cov: ['Kvøld', 'Myrkrið og ljósið', 'Low Tide Radio', 'Foghorn Lullabies'] },
  ];

  const byName = (a, b) => a.name.localeCompare(b.name, 'en');
  const MINE_LONG = MINE.concat(MINE_MORE).slice().sort(byName);

  /* ---- a playlist shared with the viewer (mine = false) -----------------------------------
     Listed and playable, read-only. No ⋯, and absent from the picker. */
  const SHARED = { id: 'pl-fam', name: 'Family Favourites', n: 40, mine: false, owner: 'someone else',
    cov: ['Kite Weather', 'Glass Birds', 'Last Stop, Everybody', 'Low Tide Radio'] };

  /* ---- a playlist of the viewer's own with nothing in it ---------------------------------- */
  const EMPTY = { id: 'pl-new', name: 'Stand-in Mix', n: 0, mine: true, cov: [] };

  /* ---- the song being added --------------------------------------------------------------- */
  const SONG = { t: 'Northern Lights', a: 'The Stand-ins', al: 'Harbour Songs', d: '4:12' };

  /* ---- the album the ⋯ is added to (FR-6) -------------------------------------------------- */
  const ALBUM = { t: 'Harbour Songs', a: 'The Stand-ins', year: 2024, n: 10, len: '39 min' };

  /* The album's ten tracks. Only Northern Lights is the brief's; the other nine are corpus titles
     reused so the page has a body, and REPORT.md asks Bárður for the real stand-ins rather than
     letting the invention pass as settled. Order is the order Play plays them. */
  const ALBUM_TRACKS = [
    ['Northern Lights', '4:12'], ['Two Harbours', '4:21'], ['Harbour Wall', '4:18'],
    ['Lighthouse Code', '4:10'], ['Paper Lanterns', '4:10'], ['Weathervane', '4:44'],
    ['String and Paper', '4:40'], ['The Long Way Round', '3:31'], ['Night Tram', '3:47'],
    ['Low Tide Radio', '3:52'],
  ];

  /* ---- what Road Trip holds (24 songs; the page shows the first of them) -------------------
     Corpus songs from several albums, which is what a playlist is. [title, artist, album, length] */
  const ROAD_TRIP = [
    ['Northern Lights', 'The Stand-ins', 'Harbour Songs', '4:12'],
    ['Night Tram', 'Nightbus Orchestra', 'Last Stop, Everybody', '3:47'],
    ['Kite Weather', 'Harbour Lights', 'Kite Weather', '3:36'],
    ['Stormur', 'Bylgja', 'Stormur', '4:02'],
    ['Glass Birds', 'Marta Vang', 'Glass Birds', '3:48'],
    ['The Long Way Round', 'Harbour Lights', 'Salt on the Window', '3:31'],
    ['Low Tide Radio', 'Harbour Lights', 'Low Tide Radio', '3:52'],
    ['Weathervane', 'Harbour Lights', 'Salt on the Window', '4:44'],
    ['Signal Found', 'Harbour Lights', 'Signal Found', '3:48'],
    ['Two Harbours', 'Harbour Lights', 'Salt on the Window', '4:21'],
  ];

  /* ---- the queue to save (FR-7): 7 songs, the playing song first --------------------------- */
  const QUEUE = [
    ['Kite Weather', 'Harbour Lights', 'Kite Weather', '3:36'],
    ['Northern Line', 'Harbour Lights', 'Kite Weather', '3:58'],
    ['String and Paper', 'Harbour Lights', 'Kite Weather', '4:40'],
    ['Signal Found', 'Harbour Lights', 'Signal Found', '3:48'],
    ['Lighthouse Code', 'Harbour Lights', 'Signal Found', '4:10'],
    ['Salt on the Window', 'Harbour Lights', 'Salt on the Window', '3:52'],
    ['Paper Lanterns', 'Harbour Lights', 'Salt on the Window', '4:10'],
  ];

  /* ---- words (FR-16's table, verbatim; en is what this round draws) ------------------------
     The da and fo columns are here because the LONGEST STRING is a state the house system makes
     every round draw, and on these surfaces Faroese wins. They are the spec's own drafts, not new
     translations invented in a drawing. */
  const W = {
    en: {
      pl_create: 'Create', pl_name: 'Playlist name', pl_name_empty: 'Give it a name',
      pl_created: 'Created {name}', pl_added: 'Added to {name}', pl_added_n: 'Added {n} songs to {name}',
      pl_already: 'Already in {name}', pl_save_queue: 'Save as playlist', pl_saved_n: 'Saved {n} songs as {name}',
      pl_edit: 'Edit', pl_done: 'Done', pl_rename: 'Rename', pl_delete: 'Delete playlist',
      pl_delete_q: 'Delete {name}?', pl_delete_line: "This can't be undone.", pl_deleted: 'Deleted {name}',
      pl_remove: 'Remove from playlist', pl_removed: 'Removed from {name}',
      pl_failed: "Couldn't change the playlist", pl_empty: 'No songs yet',
      pl_empty_hint: 'Add songs from their ⋯ menu',
      /* NEW, this round, and not in FR-16's table: the one line the picker shows when the viewer
         owns no playlist. REPORT.md asks for it as a key rather than assuming it. */
      pl_none: 'Playlists you make show up here.',
      add_playlist: 'Add to playlist…', new_playlist: 'New playlist', playlists: 'Playlists',
      play: 'Play', shuffle: 'Shuffle', cancel: 'Cancel', save: 'Save', remove: 'Remove',
      songs_n: '{n} songs', one_song: '1 song', up_next: 'Up next', now_playing: 'Now playing',
      clear_queue: 'Clear queue', queue: 'Queue', play_next: 'Play next', add_queue: 'Add to queue',
      go_album: 'Go to album', go_artist: 'Go to artist', my_list: 'My List',
      shared_by: 'Shared with you', by_n: 'By {n}',
    },
    /* Danish and Faroese: FR-16's own drafts, used only for the longest-string frames. */
    da: {
      pl_create: 'Opret', pl_name: 'Navn på playliste', pl_name_empty: 'Giv den et navn',
      pl_added: 'Føjet til {name}', pl_already: 'Allerede i {name}',
      pl_created: 'Oprettede {name}', pl_added_n: 'Føjede {n} sange til {name}',
      pl_saved_n: 'Gemte {n} sange som {name}', pl_removed: 'Fjernet fra {name}',
      pl_deleted: 'Slettede {name}',
      pl_save_queue: 'Gem som playliste', pl_edit: 'Rediger', pl_done: 'Færdig', pl_rename: 'Omdøb',
      pl_delete: 'Slet playliste', pl_delete_q: 'Slet {name}?', pl_delete_line: 'Det kan ikke fortrydes.',
      pl_remove: 'Fjern fra playliste', pl_failed: 'Kunne ikke ændre playlisten',
      pl_empty: 'Ingen sange endnu', pl_empty_hint: 'Føj sange til fra deres ⋯-menu',
      pl_none: 'Playlister, du laver, vises her.',
      add_playlist: 'Føj til playliste…', new_playlist: 'Ny playliste',
      cancel: 'Annullér', save: 'Gem', songs_n: '{n} sange', one_song: '1 sang',
      /* already shipped in i18n/da.json — the shipped file wins wherever a string exists, so
         these are read out of it rather than drafted here */
      play: 'Afspil', shuffle: 'Bland', up_next: 'Næste', now_playing: 'Afspiller nu',
      clear_queue: 'Ryd kø', queue: 'Kø', play_next: 'Afspil som næste', add_queue: 'Føj til kø',
      go_album: 'Gå til album', go_artist: 'Gå til kunstner', playlists: 'Playlister',
    },
    fo: {
      pl_create: 'Stovna', pl_name: 'Navn á spælilista', pl_name_empty: 'Gev honum eitt navn',
      pl_added: 'Lagt í {name}', pl_already: 'Longu í {name}',
      pl_created: '{name} stovnaður', pl_added_n: '{n} løg løgd í {name}',
      pl_saved_n: '{n} løg goymd sum {name}', pl_removed: 'Tikið úr {name}',
      pl_deleted: '{name} strikaður',
      pl_save_queue: 'Goym sum spælilista', pl_edit: 'Broyt', pl_done: 'Liðugt', pl_rename: 'Gev nýtt navn',
      pl_delete: 'Strika spælilista', pl_delete_q: 'Strika {name}?',
      pl_delete_line: 'Hetta kann ikki gerast ógjørt.',
      pl_remove: 'Tak úr spælilistanum', pl_failed: 'Tað eydnaðist ikki at broyta spælilistan',
      pl_empty: 'Eingi løg enn', pl_empty_hint: 'Legg løg afturat úr ⋯-valmyndini hjá teimum',
      pl_none: 'Spælilistar, tú stovnar, síggjast her.',
      add_playlist: 'Legg í spælilista…', new_playlist: 'Nýggjur spælilisti',
      cancel: 'Avlýs', save: 'Goym', songs_n: '{n} løg', one_song: '1 lag',
      /* already shipped in i18n/fo.json — read out of it, not drafted here */
      play: 'Spæl', shuffle: 'Blanda', up_next: 'Næst', now_playing: 'Spælir nú',
      clear_queue: 'Tøm bíðirøð', queue: 'Bíðirøð', play_next: 'Spæl sum næst',
      add_queue: 'Legg í bíðirøð', go_album: 'Far til fløgu', go_artist: 'Far til tónleikara',
      playlists: 'Spælilistar',
    },
  };

  /* The six toast lines, resolved against the stand-ins above. One frame draws all six. */
  const TOASTS = [
    ['added', 'pl_added', { name: 'Road Trip' }],
    ['already', 'pl_already', { name: 'Road Trip' }],
    ['added_n', 'pl_added_n', { n: 10, name: 'Road Trip' }],
    ['removed', 'pl_removed', { name: 'Road Trip' }],
    ['deleted', 'pl_deleted', { name: 'Road Trip' }],
    ['failed', 'pl_failed', {}],
  ];

  window.MPL = { MINE, MINE_MORE, MINE_LONG, SHARED, EMPTY, SONG, ALBUM, ALBUM_TRACKS, ROAD_TRIP, QUEUE, W, TOASTS };
})();
