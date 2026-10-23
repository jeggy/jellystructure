/* or-data.js — every value every frame in this round draws, in one place.

   PROCESS.md: "Derive every number in every picture from one array." So the film's length is
   written once here and the seek bar, the two clocks and the mini bar's progress thread are all
   computed from it. A reviewer can then ask "do these pictures agree with each other?" and get an
   answer by reading one file instead of trusting twenty-six.

   The stand-ins are the ones the brief named, exactly. Nothing here is a real title, a real host
   or a real person: the corpus is invented and the places are Danish/Faroese household words.

   NO ARTWORK FILES. Every art card is a CSS gradient minted from the title's own name, because a
   gradient cannot accidentally be a frame of the household's library. The wordmark fallback the
   Cast remote already specifies is drawn as its own state.
*/
window.OR = (function () {
  'use strict';

  const mmss = s => Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0');
  const hms = s => {
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = Math.floor(s % 60);
    return (h ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(x).padStart(2, '0');
  };

  /* ---- the places ------------------------------------------------------------------------
     `reach` is how the device is actually talked to. It is here ONLY so the report can prove no
     frame renders it: the whole point of the round is that a cast link and a server link produce
     the same screen. No builder in or-kit.js reads this field. */
  const PLACES = {
    stuetv:   { id: 'stuetv',   name: 'Stue TV',          icon: 'tv',     video: true,  reach: 'cast',   reports: true },
    sovetv:   { id: 'sovetv',   name: 'Soveværelse TV',   icon: 'tv',     video: true,  reach: 'server', reports: true },
    stue:     { id: 'stue',     name: 'Stue',             icon: 'spk',    video: false, reach: 'server', reports: true },
    gaeste:   { id: 'gaeste',   name: 'Gæsteværelse',     icon: 'spk',    video: false, reach: 'server', reports: true },
    kontor:   { id: 'kontor',   name: 'Kontor',           icon: 'spk',    video: false, reach: 'server', reports: false },
    pixel:    { id: 'pixel',    name: 'Pixel 9',          icon: 'phone',  video: true,  reach: 'server', reports: true },
    macbook:  { id: 'macbook',  name: 'MacBook',          icon: 'laptop', video: true,  reach: 'server', reports: true },
  };

  /* ---- the people ---- */
  const ME = { id: 'eyd', name: 'Eyð' };
  const OTHER = { id: 'olivar', name: 'Olivar' };

  /* ---- what is playing. Lengths and positions are seconds, written once. ---- */
  const FILM = {
    key: 'film', kind: 'film',
    title: 'Sommeren ’92', kicker: '', sub: 'Film · 2024',
    dur: 1 * 3600 + 52 * 60 + 30,        // 1:52:30
    pos: 48 * 60 + 10,                   // 48:10
    audio: [{ label: 'Dansk', ch: '5.1', flag: 'dk', on: true }, { label: 'English', ch: '2.0', flag: 'gb', on: false }],
    subs: [{ label: 'Off', flag: null, on: false }, { label: 'Dansk', flag: 'dk', on: true }, { label: 'English', flag: 'gb', on: false }],
  };
  const EP = {
    key: 'ep', kind: 'episode',
    title: 'The Ferry', kicker: 'Tidewater · S01E05', sub: 'Tidewater · S01E05',
    dur: 52 * 60 + 14,                   // 52:14
    pos: 49 * 60 + 40,                   // 49:40
    next: { code: 'S01E06', title: 'Low Water', inSec: 8 },
    audio: FILM.audio, subs: FILM.subs,
  };
  const EP4 = {
    key: 'ep4', kind: 'episode',
    title: 'Slack Tide', kicker: 'Tidewater · S01E04', sub: 'Tidewater · S01E04',
    dur: 49 * 60 + 2, pos: 21 * 60 + 35,
    audio: FILM.audio, subs: FILM.subs,
  };
  const SONG = {
    key: 'song', kind: 'music',
    title: 'Cannery Lights', kicker: 'Marra Quay', sub: 'Marra Quay · Harbour Lights',
    album: 'Harbour Lights', artist: 'Marra Quay',
    dur: 3 * 60 + 41,                    // 3:41
    pos: 1 * 60 + 12,                    // 1:12
    index: 3, of: 12,
    /* Up next is songs 4–6 of the same album. Plain English words, per the brief. */
    upNext: [
      { n: 4, title: 'Net Mender', len: 4 * 60 + 2 },
      { n: 5, title: 'Rope and Salt', len: 3 * 60 + 18 },
      { n: 6, title: 'Morning Tide', len: 5 * 60 + 7 },
    ],
    /* R344 — the Playing page shows every version of the song, as chips at the start of the
       artist · album line. Two, so the line has to make room for them. */
    versions: [{ label: 'Remaster', c: '#3fb6f5' }, { label: 'Live', c: '#7b6ef0' }],
    fav: true,
    /* The desktop Playing page puts the lyrics BESIDE the cover, so the Mac frame needs lines.
       Invented verse about a harbour at night: nothing here is a real lyric or a real title.
       `now` marks the line the stand-in position (1:12) falls on, which is the shipped `.lyr p.now`. */
    lyrics: [
      { t: 'The cannery lights come on at four' },
      { t: 'and the water keeps the colour of the door' },
      { t: 'Boots on the boards, a radio low' },
      { t: 'nobody counts the boats any more', now: true },
      { t: 'Salt in the rope and salt in the seam' },
      { t: 'the harbour holds the shape of a dream' },
      { t: 'Morning will come in on the tide' },
      { t: 'and the lights will go out one at a time' },
    ],
  };

  /* ---- the volumes the brief fixes ---- */
  const VOL = { master: 60, stue: 70, gaeste: 50 };

  /* ---- art. A deterministic two-stop gradient per title, so two frames of the same title are
     the same picture and two different titles never collide by accident. ---- */
  const ART = {
    film:  'linear-gradient(145deg,#2a3550 0%,#5c4a7e 52%,#1d2a3c 100%)',
    ep:    'linear-gradient(145deg,#15323a 0%,#2d6070 48%,#0e2029 100%)',
    ep4:   'linear-gradient(145deg,#1b2d33 0%,#3a5a63 48%,#101e23 100%)',
    song:  'linear-gradient(145deg,#4a2f3e 0%,#8a5b4a 46%,#2a1a22 100%)',
  };
  const art = k => ART[k] || ART.film;

  /* ---- strings. English is the source; Danish and Faroese are drafts for Bárður's table.
     Keys that already ship (volume, play_here, stop, move_to, subs_audio, next_episode,
     subtitle_size, try_again, no_volume) keep the shipped English wording — the shipped
     i18n/*.json wins and a disagreement here would be a defect, not a design. ---- */
  const I18N = {
    en: {
      playing_on: 'Playing on {place}', paused_on: 'Paused on {place}',
      starting_on: 'Starting on {place}…',
      moving_to: 'Moving to {place}…', move_failed: 'Couldn’t move to {place}',
      lost: 'Lost contact with {place}', try_again: 'Try again',
      offline: '{place} is offline · paused at {t}',
      stopped_by: '{who} stopped this', ended: 'Ended',
      watching: '{who} is watching', listening: '{who} is listening',
      applies_on: 'Applies on {place}',
      volume: 'Volume', no_volume: '{place} doesn’t report its volume',
      play_here: 'Play here', stop: 'Stop', move_to: 'Move to…',
      next_episode: 'Next episode', subs_audio: 'Subtitles & audio',
      subtitle_size: 'Subtitle size', audio: 'Audio', subtitles: 'Subtitles',
      play_in: 'Play in {n}', watch_credits: 'Watch credits',
      up_next: 'Up next', lyrics: 'Lyrics', watch_again: 'Watch again', done: 'Done',
      stop_q: 'Stop {who}’s {title}?', cancel: 'Cancel',
      this_phone: 'This phone', off: 'Off', ready: 'Ready',
      playing_here: 'Playing here', busy_with: '{who} is watching',
      more_playing: '{n} more playing', song_of: 'Song {i} of {n}',
      size_s: 'S', size_m: 'M', size_l: 'L',
      /* The shipped Playing page's own strings, taken from i18n/*.json rather than drafted:
         music.from, mnav.listen/browse/now_short/queue, nav.profile, music.more, music.up_next.
         They are here so the da/fo music frames read the shipped words. */
      from: 'Playing from {x}', nav_listen: 'Listen', nav_browse: 'Browse',
      nav_playing: 'Playing', nav_queue: 'Queue', nav_profile: 'Profile', more: 'More',
      /* The transient pill a hardware volume key raises while a place is playing. DRAWN, not
         quoted: no such pill was found in the Kotlin — see the report. */
      hw_vol: '{place} · {n} %',
    },
    da: {
      playing_on: 'Spiller på {place}', paused_on: 'Sat på pause på {place}',
      starting_on: 'Starter på {place}…',
      moving_to: 'Flytter til {place}…', move_failed: 'Kunne ikke flytte til {place}',
      lost: 'Mistede kontakten med {place}', try_again: 'Prøv igen',
      offline: '{place} er offline · på pause ved {t}',
      stopped_by: '{who} stoppede det', ended: 'Slut',
      watching: '{who} ser det', listening: '{who} lytter',
      applies_on: 'Gælder på {place}',
      volume: 'Lydstyrke', no_volume: '{place} melder ikke sin lydstyrke',
      play_here: 'Afspil her', stop: 'Stop', move_to: 'Flyt til…',
      next_episode: 'Næste afsnit', subs_audio: 'Undertekster og lyd',
      subtitle_size: 'Undertekststørrelse', audio: 'Lyd', subtitles: 'Undertekster',
      play_in: 'Spiller om {n}', watch_credits: 'Se rulletekster',
      up_next: 'Næste', lyrics: 'Sangtekst', watch_again: 'Se igen', done: 'Færdig',
      stop_q: 'Stop {who}s {title}?', cancel: 'Annullér',
      this_phone: 'Denne telefon', off: 'Fra', ready: 'Klar',
      playing_here: 'Spiller her', busy_with: '{who} ser det',
      more_playing: '{n} mere spiller', song_of: 'Nummer {i} af {n}',
      size_s: 'S', size_m: 'M', size_l: 'L',
      from: 'Afspiller fra {x}', nav_listen: 'Lyt', nav_browse: 'Gennemse',
      nav_playing: 'Afspiller', nav_queue: 'Kø', nav_profile: 'Profil', more: 'Mere',
      hw_vol: '{place} · {n} %',
    },
    fo: {
      playing_on: 'Spælir á {place}', paused_on: 'Steðgað á {place}',
      starting_on: 'Byrjar á {place}…',
      moving_to: 'Flytur til {place}…', move_failed: 'Kundi ikki flyta til {place}',
      lost: 'Misti sambandið við {place}', try_again: 'Royn aftur',
      offline: '{place} er ikki á netinum · steðgað við {t}',
      stopped_by: '{who} steðgaði hesum', ended: 'Endað',
      watching: '{who} hyggur at hesum', listening: '{who} lurtar',
      applies_on: 'Virkar á {place}',
      volume: 'Ljóðstyrki', no_volume: '{place} boðar ikki frá ljóðstyrkinum',
      play_here: 'Spæl her', stop: 'Steðga', move_to: 'Flyt til…',
      next_episode: 'Næsti táttur', subs_audio: 'Undirtekstir og ljóð',
      subtitle_size: 'Stødd á undirtekstum', audio: 'Ljóð', subtitles: 'Undirtekstir',
      play_in: 'Spælir um {n}', watch_credits: 'Hygg at endatekstunum',
      up_next: 'Næst', lyrics: 'Lagtekstur', watch_again: 'Hygg aftur', done: 'Liðugt',
      stop_q: 'Steðga {title} hjá {who}?', cancel: 'Avlýs',
      this_phone: 'Hesin telefonin', off: 'Av', ready: 'Klár',
      playing_here: 'Spælir her', busy_with: '{who} hyggur at hesum',
      more_playing: '{n} fleiri spæla', song_of: 'Lag {i} av {n}',
      size_s: 'S', size_m: 'M', size_l: 'L',
      from: 'Spælir úr {x}', nav_listen: 'Lurta', nav_browse: 'Kaga',
      nav_playing: 'Spælir', nav_queue: 'Bíðirøð', nav_profile: 'Vangi', more: 'Meira',
      hw_vol: '{place} · {n} %',
    },
  };

  return { PLACES, ME, OTHER, FILM, EP, EP4, SONG, VOL, I18N, art, mmss, hms };
})();
