/* Music — shared stand-in data for the admin (app/) and the phone (ravilo/Ravilo Mobile.html).
   Shaped on the household library measured 2026-09-27 (research §0/§1.1): 60 tracks · 30 albums ·
   23 artists · 18 genres · 38 WMA (128 kbps) + 22 MP3 · no disc numbers · mostly partial albums.
   Every name is a fictional stand-in (the title sweep: no real artists or releases in the mockups).
   State shown is "after the first run": 27 albums matched (1 locked), 2 need you, 1 unmatched,
   26 covers fetched, 4 albums without a cover, 2 album artists without a picture. */
(function () {
  function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0; return h; }
  function mbid(seed) { const hx = n => (hash(seed + n).toString(16) + '00000000').slice(0, 8); const a = hx('a'), b = hx('b'), c = hx('c'), d = hx('d');
    return a + '-' + b.slice(0, 4) + '-4' + b.slice(5, 8) + '-' + (8 + hash(seed) % 4).toString(16) + c.slice(1, 4) + '-' + c.slice(4) + d; }
  const L = s => { const [m, x] = s.split(':'); return +m * 60 + +x; };

  // [id, name, sort, type, country, span, disambiguation, hasImage, match]
  const AR = [
    ['harbour-lights', 'Harbour Lights', 'Harbour Lights', 'Group', 'NO', '1999–', 'Norwegian indie-folk band', true, 'matched'],
    ['marta-vang', 'Marta Vang', 'Vang, Marta', 'Person', 'DK', 'b. 1976', 'Danish singer-songwriter', true, 'matched'],
    ['ferrymen', 'The Ferrymen', 'Ferrymen, The', 'Group', 'FO', '1994–2008', 'Faroese folk group', true, 'matched'],
    ['kvold', 'Kvøld', 'Kvøld', 'Group', 'FO', '2002–', '', false, 'matched'],
    ['ola-brink', 'Ola Brink', 'Brink, Ola', 'Person', 'SE', 'b. 1981', '', true, 'matched'],
    ['nightbus', 'Nightbus Orchestra', 'Nightbus Orchestra', 'Group', 'DK', '1996–2004', '', true, 'matched'],
    ['sunniva', 'Sunniva & the Tides', 'Sunniva & the Tides', 'Group', 'NO', '2001–2009', '', true, 'matched'],
    ['copperline', 'Copperline', 'Copperline', 'Group', 'GB', '1995–2001', 'UK post-rock band', true, 'matched'],
    ['jens-holm', 'Jens Holm', 'Holm, Jens', 'Person', 'DK', 'b. 1969', 'Danish visesanger', true, 'matched'],
    ['velvet-static', 'Velvet Static', 'Velvet Static', 'Group', 'IS', '2003–', '', true, 'matched'],
    ['aldan', 'Aldan', 'Aldan', 'Person', 'FO', 'b. 1984', 'Faroese composer', true, 'matched'],
    ['lighthouse', 'The Lighthouse Keepers', 'Lighthouse Keepers, The', 'Group', 'GB', '2000–2006', '', true, 'matched'],
    ['emil-rask', 'Emil Rask', 'Rask, Emil', 'Person', 'DK', 'b. 1978', '', true, 'matched'],
    ['bylgja', 'Bylgja', 'Bylgja', 'Group', 'FO', '2003–', '', true, 'matched'],
    ['moss-garden', 'Moss Garden', 'Moss Garden', 'Group', 'SE', '2000–', 'Swedish ambient duo', true, 'matched'],
    ['petra-lind', 'Petra Lind', 'Lind, Petra', 'Person', 'NO', 'b. 1983', '', true, 'matched'],
    ['red-kite', 'Red Kite Radio', 'Red Kite Radio', 'Group', 'GB', '1997–2003', '', true, 'matched'],
    ['frost-fjord', 'Frost & Fjord', 'Frost & Fjord', 'Group', 'NO', '2002–', 'film composers', true, 'matched'],
    ['hanna-dal', 'Hanna Dal', 'Dal, Hanna', 'Person', 'DK', 'b. 1990', '', true, 'matched'],
    ['tangle', 'Tangle', 'Tangle', 'Group', 'IE', '2001–2007', 'Irish folk-rock band', true, 'matched'],
    ['skerry', 'Skerry', 'Skerry', 'Group', 'FO', '1999–2005', '', false, 'matched'],
    ['disa', 'Dísa', 'Dísa', 'Person', 'FO', '', '', false, 'unmatched'],
    ['oskar-mo', 'Oskar Mø', 'Mø, Oskar', 'Person', 'NO', 'b. 1972', '', true, 'matched'],
  ];
  const BIO = {
    'harbour-lights': 'Harbour Lights formed in a boathouse outside Ålesund in 1999 and made their name with quiet, harmony-led records about coastal towns. Their second album, Salt on the Window, was recorded in eleven days in a disused cannery and is still the one people ask for. The band have released five albums and tour mostly in winter.',
    'marta-vang': 'Marta Vang writes spare piano songs in English and Danish. After two self-released tapes she signed to a small Copenhagen label in 2003; Glass Birds followed a year later and was written, she has said, on the ferry to Vágar.',
    'ferrymen': 'A five-piece from Klaksvík who played traditional Faroese material alongside their own songs, mostly on stage. Their live records were cut from single concerts, straight to two tracks.',
  };
  const BIO_SRC = { 'harbour-lights': 'Wikipedia (en)', 'marta-vang': 'Wikipedia (da)', 'ferrymen': 'Wikidata' };

  // [id, title, artistId, year, type, totalOnRelease, match, cover, codec, kbps, genres, tracks[[n,title,len,extra]]]
  const AL = [
    ['salt-on-the-window', 'Salt on the Window', 'harbour-lights', 2003, 'album', 9, 'matched', true, 'MP3', 320, [['indie folk', 41], ['folk rock', 18], ['chamber pop', 6]],
      [[1, 'Salt on the Window', '3:52', { ly: 'synced', plays: 6, last: 0 }], [2, 'Paper Lanterns', '4:10', { ly: 'synced', plays: 2 }], [3, 'The Long Way Round', '3:31', { ly: 'synced' }], [4, 'Weathervane', '4:44', { ly: 'plain', plays: 1, last: 3 }], [5, 'Northbound', '3:58', { ly: 'synced' }], [6, 'Two Harbours', '4:21', { feat: ['oskar-mo'] }], [7, 'Ferry Lights', '3:15'], [8, 'Undertow Waltz', '5:02'], [9, 'Home Before Dark', '4:38']]],
    ['low-tide-radio', 'Low Tide Radio', 'harbour-lights', 2005, 'album', 18, 'needs', false, 'WMA', 128, [['indie folk', 0]], [[2, 'Static Bloom', '3:44'], [18, 'Low Tide Radio', '5:31']]],
    ['early-recordings', 'Early Recordings', 'harbour-lights', 2008, 'compilation', 14, 'matched', true, 'WMA', 128, [['indie folk', 9]], [[7, 'First Light (demo)', '3:02']]],
    ['glass-birds', 'Glass Birds', 'marta-vang', 2004, 'album', 11, 'matched', true, 'WMA', 128, [['singer-songwriter', 22], ['chamber pop', 11], ['indie folk', 4]],
      [[1, 'Glass Birds', '3:48', { ly: 'synced', plays: 3, last: 1 }], [2, 'Tin Roof', '3:21'], [5, 'Letters to Vágar', '4:37'], [7, 'Small Hours', '3:59', { rec: 'other' }]]],
    ['winter-tapes', 'Winter Tapes', 'marta-vang', 2001, 'album', 10, 'matched', true, 'WMA', 128, [['singer-songwriter', 7]], [[3, 'Snowline', '4:05']]],
    ['live-at-torshavn', 'Live at Tórshavn', 'marta-vang', 2006, 'live', 13, 'matched', true, 'WMA', 128, [['singer-songwriter', 5]], [[4, 'Glass Birds (live)', '4:22'], [9, 'Tin Roof (live)', '3:40']]],
    ['north-atlantic-songbook', 'North Atlantic Songbook', 'ferrymen', 2002, 'live', 12, 'matched', true, 'WMA', 128, [['nordic folk', 14], ['folk', 8]], [[1, 'Rowing Song', '3:12'], [2, 'The Grey Mare', '4:01'], [3, 'Nólsoy Nights', '5:15']]],
    ['myrkrid-og-ljosid', 'Myrkrið og ljósið', 'kvold', 2006, 'album', 10, 'locked', true, 'WMA', 128, [['post-rock', 6], ['nordic folk', 3]], [[1, 'Myrkrið', '4:48'], [4, 'Ljósið', '3:56'], [6, 'Heim', '4:12']]],
    ['kvold', 'Kvøld', 'kvold', 2004, 'album', 0, 'unmatched', false, 'WMA', 128, [], [[2, 'Kvøldsól', '3:33']]],
    ['paper-planes', 'Paper Planes', 'ola-brink', 2004, 'single', 3, 'matched', true, 'MP3', 128, [['pop', 12]], [[1, 'Paper Planes', '3:19']]],
    ['last-stop-everybody', 'Last Stop, Everybody', 'nightbus', 2000, 'album', 12, 'matched', true, 'WMA', 128, [['post-rock', 19], ['jazz', 7]], [[1, 'Last Stop, Everybody', '4:02'], [5, 'Night Tram', '3:47', { plays: 1, last: 2 }], [11, 'Terminus', '6:10']]],
    ['night-bus-ep', 'Night Bus', 'nightbus', 1998, 'ep', 4, 'matched', true, 'WMA', 128, [['post-rock', 4]], [[2, 'Route 9', '3:28']]],
    ['undertow', 'Undertow', 'sunniva', 2004, 'album', 10, 'matched', true, 'WMA', 128, [['indie folk', 8], ['dream pop', 6]], [[3, 'Riptide', '3:55'], [8, 'Harbour Wall', '4:18']]],
    ['copperline', 'Copperline', 'copperline', 1998, 'album', 11, 'matched', true, 'WMA', 128, [['post-rock', 15], ['shoegaze', 9]], [[2, 'Wire and Rain', '3:40'], [6, 'Copper Sky', '4:26']]],
    ['summer-hits-2004', 'Summer Hits 2004', null, 2004, 'compilation', 40, 'needs', false, 'WMA', 128, [['pop', 0]], [[5, 'Paper Planes (radio edit)', '3:02', { by: ['ola-brink'] }], [12, 'Kite Weather', '3:36', { by: ['harbour-lights'] }], [18, 'Paper Kites', '3:22', { by: ['petra-lind'] }], [23, 'Sommerbrisen', '3:14', { by: ['disa'] }]]],
    ['kaffe-og-cigaretter', 'Kaffe og cigaretter', 'jens-holm', 2003, 'album', 11, 'matched', true, 'WMA', 128, [['singer-songwriter', 6]], [[1, 'Kaffe og cigaretter', '3:27'], [4, 'Nattoget', '4:09']]],
    ['signal-lost', 'Signal Lost', 'velvet-static', 2005, 'album', 10, 'matched', true, 'MP3', 256, [['electronic', 21], ['trip hop', 9]], [[1, 'Signal Lost', '3:41', { ly: 'synced', plays: 2, last: 1 }], [2, 'Dead Air', '4:03']]],
    ['signal-found', 'Signal Found', 'harbour-lights', 2003, 'album', 14, 'matched', true, 'MP3', 320, [['indie folk', 9], ['folk rock', 4]], [[1, 'Signal Found', '3:48'], [2, 'Lighthouse Code', '4:10']]],
    ['brim', 'Brim', 'aldan', 2007, 'album', 9, 'matched', true, 'MP3', 192, [['ambient', 11], ['post-rock', 7]], [[3, 'Brim', '4:24'], [4, 'Saltvatn', '3:52']]],
    ['foghorn-lullabies', 'Foghorn Lullabies', 'lighthouse', 2004, 'album', 12, 'matched', false, 'WMA', 128, [['indie folk', 3]], [[6, 'Foghorn Lullaby', '4:40']]],
    ['night-shift', 'Night Shift', 'emil-rask', 2002, 'album', 10, 'matched', true, 'WMA', 128, [['jazz', 5]], [[8, 'Graveyard Shift', '5:05']]],
    ['stormur', 'Stormur', 'bylgja', 2005, 'album', 9, 'matched', true, 'MP3', 192, [['post-rock', 9], ['alternative rock', 5]], [[1, 'Stormur', '3:49'], [2, 'Aldan brotnar', '4:15']]],
    ['slow-green', 'Slow Green', 'moss-garden', 2003, 'album', 8, 'matched', true, 'WMA', 128, [['ambient', 13]], [[4, 'Fern', '4:57']]],
    ['morning-fields', 'Morning Fields', 'moss-garden', 2005, 'album', 9, 'matched', true, 'WMA', 128, [['ambient', 6]], [[2, 'Dew', '3:38']]],
    ['kite-weather', 'Kite Weather', 'harbour-lights', 2006, 'album', 11, 'matched', true, 'MP3', 320, [['indie folk', 12], ['folk rock', 5]], [[1, 'Kite Weather', '3:36'], [2, 'Northern Line', '3:58'], [4, 'String and Paper', '4:40']]],
    ['frequencies', 'Frequencies', 'red-kite', 2001, 'album', 13, 'matched', true, 'WMA', 128, [['electronic', 7], ['alternative rock', 4]], [[9, '88.4 FM', '4:11']]],
    ['the-long-winter', 'The Long Winter', 'frost-fjord', 2004, 'soundtrack', 18, 'matched', true, 'WMA', 128, [['soundtrack', 9], ['ambient', 3]], [[1, 'Main Title', '2:48'], [14, 'Thaw', '3:22']]],
    ['hjem-igen', 'Hjem igen', 'hanna-dal', 2006, 'single', 2, 'matched', true, 'MP3', 160, [['pop', 4]], [[1, 'Hjem igen', '3:29']]],
    ['knots', 'Knots', 'tangle', 2004, 'album', 10, 'matched', true, 'MP3', 128, [['folk rock', 8], ['alternative rock', 6]], [[5, 'Bowline', '3:14']]],
    ['grey-waters', 'Grey Waters', 'skerry', 2003, 'album', 10, 'matched', true, 'MP3', 128, [['alternative rock', 7], ['rock', 5]], [[2, 'Grey Waters', '4:02'], [7, 'Lowland', '3:45', { codec: 'WMA', kbps: 128 }]]],
  ];
  const LABEL = { 'salt-on-the-window': 'Fjordlight Records', 'glass-birds': 'Havnelyd', 'signal-lost': 'Northern Wire', 'myrkrid-og-ljosid': 'Tutl-ish', 'the-long-winter': 'Polar Score' };
  const ADDED = ['2026-09-23'];

  const artists = AR.map(a => ({ id: a[0], name: a[1], sort: a[2], type: a[3], country: a[4], span: a[5], disamb: a[6], image: a[7], match: a[8],
    bio: BIO[a[0]] || '', bioSrc: BIO_SRC[a[0]] || '', mbid: mbid(a[0]), hue: hash(a[0]) % 360 }));
  const byArtist = {}; artists.forEach(a => byArtist[a.id] = a);
  byArtist['various'] = { id: 'various', name: 'Various Artists', sort: 'Various Artists', type: 'Special', image: false, match: 'matched', mbid: '89ad4ac3-39f7-470e-963a-56509c546377', hue: 210 };

  const albums = [], tracks = [];
  AL.forEach((a, i) => {
    const al = { id: a[0], title: a[1], artistId: a[2] || 'various', year: a[3], type: a[4], total: a[5], match: a[6], cover: a[7], codec: a[8], kbps: a[9],
      genres: a[10].map(g => ({ name: g[0], votes: g[1] })), mbid: mbid('rg' + a[0]), relMbid: mbid('rel' + a[0]), hue: hash(a[1]) % 360,
      label: LABEL[a[0]] || ['Fjordlight Records', 'Havnelyd', 'Northern Wire', 'Grey Pier', 'Klaksvík Tónar'][hash(a[0]) % 5],
      country: byArtist[a[2] || 'various'].country || 'XE', added: ADDED[0], gain: -(8.7 + (hash(a[0]) % 19) / 10), order: i };
    al.trackIds = [];
    a[11].forEach(t => {
      const x = t[3] || {};
      const tr = { id: a[0] + '-' + t[0], albumId: a[0], n: t[0], title: t[1], len: L(t[2]), artistIds: x.by || [al.artistId], feat: x.feat || [],
        codec: x.codec || al.codec, kbps: x.kbps || al.kbps, khz: 44.1, lyrics: x.ly || null, rec: x.rec || (al.match === 'matched' || al.match === 'locked' ? 'ok' : 'none'),
        gain: -(8.7 + (hash(a[0] + t[0]) % 19) / 10), plays: x.plays || 0, last: x.last != null ? x.last : null };
      tracks.push(tr); al.trackIds.push(tr.id);
    });
    albums.push(al);
  });
  const byAlbum = {}; albums.forEach(a => byAlbum[a.id] = a);
  const byTrack = {}; tracks.forEach(t => byTrack[t.id] = t);

  // candidates for the two "needs you" albums and the unmatched one (Find match…)
  const CANDS = {
    'low-tide-radio': [
      { title: 'Low Tide Radio', artist: 'Harbour Lights', type: 'Album', year: 2005, score: 100, agree: { hit: 2, of: 2, partial: true, off: 0 },
        releases: [{ country: 'NO', date: '2005-03-14', label: 'Fjordlight Records', format: 'CD', tracks: 18, agree: 2 }, { country: 'XE', date: '2005-05-02', label: 'Fjordlight Records', format: 'CD', tracks: 18, agree: 2 }, { country: 'XW', date: '2019-11-08', label: 'Fjordlight', format: 'Digital Media', tracks: 20, agree: 1 }] },
      { title: 'Low Tide Radio (Deluxe Edition)', artist: 'Harbour Lights', type: 'Album', year: 2015, score: 91, agree: { hit: 2, of: 2, partial: true, off: 9 },
        releases: [{ country: 'XW', date: '2015-06-01', label: 'Fjordlight', format: 'Digital Media', tracks: 24, agree: 2 }] },
      { title: 'Low Tide Radio', artist: 'Harbour Lights', type: 'Single', year: 2005, score: 74, agree: { hit: 1, of: 2, partial: false, off: 0, note: 'the single has 2 tracks; only “Low Tide Radio” is on it' },
        releases: [{ country: 'NO', date: '2005-02-07', label: 'Fjordlight Records', format: 'CD', tracks: 2, agree: 1 }] },
    ],
    'summer-hits-2004': [
      { title: 'Summer Hits 2004', artist: 'Various Artists', type: 'Compilation', year: 2004, score: 100, agree: { hit: 3, of: 3, partial: true, off: 2 },
        releases: [{ country: 'DK', date: '2004-06-07', label: 'Sommerlyd', format: '2×CD', tracks: 40, agree: 3 }] },
      { title: 'Summer Hits 2004', artist: 'Various Artists', type: 'Compilation', year: 2004, score: 100, agree: { hit: 1, of: 3, partial: true, off: 0 },
        releases: [{ country: 'NO', date: '2004-06-21', label: 'Nordic Pop', format: '2×CD', tracks: 38, agree: 1 }] },
      { title: 'Summer Hits 2004', artist: 'Various Artists', type: 'Compilation', year: 2004, score: 98, agree: { hit: 0, of: 3, partial: true, off: 0 },
        releases: [{ country: 'SE', date: '2004-05-30', label: 'Sommarhits', format: 'CD', tracks: 20, agree: 0 }] },
    ],
    'kvold': [
      { title: 'Kvöld', artist: 'Kvöldvaka', type: 'Album', year: 2009, score: 61, agree: { hit: 0, of: 1, partial: true, off: 40, note: 'no track title agrees; the nearest length is 40 s off' },
        releases: [{ country: 'IS', date: '2009-10-02', label: 'Smekkleysa-ish', format: 'CD', tracks: 9, agree: 0 }] },
      { title: 'Kvøld', artist: 'Kvøld', type: 'Single', year: 2011, score: 58, agree: { hit: 0, of: 1, partial: false, off: 0, note: 'right artist, but a 2011 single with a different track' },
        releases: [{ country: 'FO', date: '2011-04-15', label: '—', format: 'Digital Media', tracks: 1, agree: 0 }] },
    ],
  };
  // what AcoustID answered for each (Identify by sound)
  const FP = { 'low-tide-radio': '2 of 2 tracks identified → 1 release-group (Low Tide Radio, 2005)', 'summer-hits-2004': '3 of 3 tracks identified → recordings on 7 compilations; 1 release-group holds all three (Summer Hits 2004 · DK)', 'kvold': '0 of 1 tracks identified — AcoustID has never heard this recording' };
  // albums MusicBrainz knows that the library lacks (H4's greyed discography)
  const DISCO = { 'harbour-lights': [['Harbour Lights', 1999, 'ep'], ['Flood Year', 2011, 'album'], ['Lanterns: Live in Bergen', 2013, 'live'], ['Weathervane', 2003, 'single']],
    'marta-vang': [['Nine Rooms', 2009, 'album'], ['Small Hours', 2004, 'single']] };
  // videos from the MUSIC-VIDEO library (168's MUSIC_VIDEO items), by the filename's artist
  const VIDEOS = { 'harbour-lights': [{ title: 'Salt on the Window', kind: 'Music video', year: 2003, len: '3:58' }, { title: 'Live at the Nordic House', kind: 'Concert film', year: 2009, len: '1:24:10' }],
    'marta-vang': [{ title: 'Glass Birds', kind: 'Music video', year: 2004, len: '3:51' }] };

  // synced lyrics (LRCLIB) — original stand-in text; [seconds, line]
  const LYRICS = {
    'salt-on-the-window-1': [[0, '♪'], [14, 'Morning comes in sideways'], [19, 'through the harbour glass'], [24, 'kettle on the window ledge'], [29, 'waiting for the boats to pass'], [36, 'You wrote your name in salt'], [41, 'where the rain had been'], [46, 'I left it there all winter'], [51, 'so I could read it in between'], [60, '♪'], [72, 'Oh the tide keeps its own time'], [78, 'and it never asks for mine'], [84, 'salt on the window'], [89, 'light on the line'], [96, '♪'], [110, 'Streetlamps on the water'], [115, 'nets hung out to dry'], [120, 'every house along the quay'], [125, 'leaves a light on for the sky'], [134, 'Oh the tide keeps its own time'], [140, 'and it never asks for mine'], [146, 'salt on the window'], [151, 'light on the line'], [160, '♪']],
    'glass-birds-1': [[0, '♪'], [11, 'Glass birds on the sill'], [16, 'catch the ferry light'], [21, 'one for every year you stayed'], [26, 'one for every night'], [34, 'I dust them when it rains'], [39, 'I count them when it snows'], [44, 'and none of them will fly away'], [49, 'and that is how it goes'], [58, '♪']],
    'signal-lost-1': [[0, '♪'], [18, 'Static on the dial'], [23, 'numbers in the dark'], [28, 'somebody is counting down'], [33, 'from a burnt-out park'], [42, 'Signal lost'], [46, 'signal lost'], [50, 'say it again'], [56, '♪']],
  };
  const PLAIN = { 'salt-on-the-window-4': 'Turn with the wind, old weathervane,\npoint me home and point again.\nNorth to the fish and south to the rain,\nturn with the wind, old weathervane.\n\nThe roof is loud, the gutter sings,\nthe gulls are arguing over things,\nand every gust that the evening brings\nturns you round on your rusted wings.' };

  const TYPE_LABEL = { album: 'Album', single: 'Single', ep: 'EP', compilation: 'Compilation', live: 'Live', soundtrack: 'Soundtrack' };
  function fmtLen(s) { s = Math.max(0, Math.round(s)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); }
  function fmtTotal(s) { const m = Math.round(s / 60); return m >= 60 ? Math.floor(m / 60) + ' h ' + (m % 60) + ' min' : m + ' min'; }
  // a cover that exists = an abstract art stand-in; no cover = the title set as a wordmark (R243's rule on the viewer side)
  function coverStyle(al) {
    const h = al.hue, k = hash(al.id), x1 = 20 + k % 50, y1 = 20 + (k >> 3) % 50, x2 = 40 + (k >> 5) % 50, y2 = 45 + (k >> 7) % 45;
    return 'background:radial-gradient(circle at ' + x1 + '% ' + y1 + '%, hsl(' + h + ' 70% 62% / .95), transparent 42%),radial-gradient(circle at ' + x2 + '% ' + y2 + '%, hsl(' + ((h + 55) % 360) + ' 62% 48%), transparent 52%),linear-gradient(' + (k % 180) + 'deg, hsl(' + h + ' 42% 20%), hsl(' + ((h + 30) % 360) + ' 48% 9%))';
  }
  function wordmarkStyle(name) { const h = hash(name) % 360; return 'background:linear-gradient(150deg, hsl(' + h + ' 46% 36%), hsl(' + ((h + 40) % 360) + ' 52% 14%))'; }
  function artistStyle(ar) { const h = ar.hue; return 'background:radial-gradient(circle at 50% 38%, hsl(' + h + ' 30% 70%) 0 17%, transparent 18%),radial-gradient(ellipse at 50% 100%, hsl(' + h + ' 28% 58%) 0 38%, transparent 39%),linear-gradient(160deg, hsl(' + ((h + 200) % 360) + ' 35% 30%), hsl(' + ((h + 220) % 360) + ' 40% 14%))'; }
  function initials(n) { return String(n).replace(/[^\p{L}\s]/gu, '').split(/\s+/).filter(Boolean).map(s => s[0]).slice(0, 2).join('').toUpperCase(); }
  function artistNames(ids) { return ids.map(id => (byArtist[id] || {}).name).filter(Boolean).join(' & '); }

  window.MUSIC = {
    artists, albums, tracks, byArtist, byAlbum, byTrack, CANDS, FP, DISCO, VIDEOS, LYRICS, PLAIN, TYPE_LABEL,
    album: id => byAlbum[id], artist: id => byArtist[id], track: id => byTrack[id],
    tracksOf: id => (byAlbum[id] ? byAlbum[id].trackIds.map(t => byTrack[t]) : []),
    albumsOf: id => albums.filter(a => a.artistId === id),
    tracksBy: id => tracks.filter(t => t.artistIds.indexOf(id) >= 0 || t.feat.indexOf(id) >= 0),
    albumLen: id => (byAlbum[id] ? byAlbum[id].trackIds.reduce((s, t) => s + byTrack[t].len, 0) : 0),
    genres: () => { const m = {}; albums.forEach(a => a.genres.forEach(g => { const n = g.name; m[n] = m[n] || { name: n, albums: 0, songs: 0 }; m[n].albums++; m[n].songs += a.trackIds.length; })); return Object.values(m).sort((a, b) => b.albums - a.albums || a.name.localeCompare(b.name)); },
    fmtLen, fmtTotal, coverStyle, wordmarkStyle, artistStyle, initials, artistNames, hash, mbid,
    reencodes: t => t.codec === 'WMA',
  };
})();
