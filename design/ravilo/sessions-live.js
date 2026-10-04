/* sessions-live.js — the household's playback sessions (R368–R372 · 304) as ONE stand-in store shared by every mockup:
   Ravilo Mobile (here = Pixel 9), Ravilo Desktop (here = MacBook) and the admin's Users & devices. localStorage
   'ravilo-sessions'; a change in one tab shows in the others (storage event) — the mockup's stand-in for the server's
   session_list / session_state events. Owner rules held (10-03 / 10-04): single places only, rooms added one at a time;
   the bar shows the one you touched last; a busy place always asks, never Add; others' sessions are seen, not
   controlled, unless the household switch is on; the admin is a full remote; paused ends after 24 h; an offline place
   pauses its session. Stand-in names only. */
(function () {
  const KEY = 'ravilo-sessions', TK = 'ravilo-sess-touch', SW = 'ravilo-sess-ctl';
  const PLACES = [
    { id: 'stue', name: 'Stue', icon: 'speaker', vol: 70 },
    { id: 'kontor', name: 'Kontor', icon: 'speaker', vol: 50 },
    { id: 'gaest', name: 'Gæsteværelse', icon: 'speaker', vol: 24, busyApp: 'Spotify' }, // busy outside Ravilo (R324)
    { id: 'bad', name: 'Badeværelse', icon: 'speaker', vol: 30 },
    { id: 'hub', name: 'Køkken hub', icon: 'display', vol: 40, video: true },
    { id: 'stovan', name: 'Stovan', icon: 'tv', vol: 35, video: true },
    { id: 'barn', name: 'Barnarúmið', icon: 'tv', vol: 22, video: true },
    { id: 'pixel', name: 'Pixel 9', icon: 'phone', app: true, video: true },
    { id: 'mac', name: 'MacBook', icon: 'computer', app: true, video: true },
  ];
  const place = id => PLACES.find(p => p.id === id) || { id, name: id, icon: 'speaker' };
  const ART = {
    music: 'radial-gradient(circle at 30% 30%,hsl(200 70% 62% / .95),transparent 45%),radial-gradient(circle at 70% 70%,hsl(255 60% 55%),transparent 50%),#14202e',
    film: 'linear-gradient(160deg,#e9a25a 0%,#a0486a 55%,#2b1b3a 100%)',
    kids: 'linear-gradient(150deg,#5fd0a8 0%,#2c8fc9 60%,#16324d 100%)',
  };
  function seed() {
    const now = Date.now(), m = n => now - n * 60000;
    return { list: [
      { id: 's-music', owner: 'Eyð', ownerId: 'eyd', kind: 'music', art: ART.music,
        queue: [['glass-birds-1', 'Glass Birds', 228], ['glass-birds-2', 'Tin Roof', 201], ['glass-birds-5', 'Letters to Vágar', 277], ['glass-birds-7', 'Small Hours', 239]],
        sub: 'Marta Vang · Glass Birds', index: 0, rooms: ['stue', 'kontor'], vols: { stue: 70, kontor: 50 }, from: 'MacBook',
        state: 'playing', pos: 72, at: now, created: m(14), touchedBy: ['MacBook'],
        events: [[m(14), 'Started on Stue', 'MacBook'], [m(9), 'Kontor added', 'MacBook']] },
      { id: 's-film', owner: 'Eyð', ownerId: 'eyd', kind: 'film', art: ART.film, queue: [['f-sommeren', 'Sommeren ’92', 6420]], sub: 'Film · 2019',
        index: 0, rooms: ['stovan'], vols: { stovan: 35 }, from: 'MacBook', state: 'paused', pos: 2890, at: now, created: m(190), touchedBy: ['MacBook'],
        tracks: { audio: 'Dansk 5.1', subs: 'Off' },
        events: [[m(190), 'Started on Stovan', 'MacBook'], [m(141), 'Paused at 48:10', 'Stovan']] },
      { id: 's-kids', owner: 'Olivar', ownerId: 'olivar', kind: 'episode', art: ART.kids, queue: [['e-lundin-205', 'Lundin og vinir', 660], ['e-lundin-206', 'Lundin og vinir', 660]],
        subs: ['S02E05 · Sjóvarfallið', 'S02E06 · Lítla fjallið'], index: 0, rooms: ['barn'], vols: { barn: 22 }, from: 'Barnarúmið', state: 'playing', pos: 300, at: now, created: m(5), touchedBy: [],
        tracks: { audio: 'Føroyskt', subs: 'Off' },
        events: [[m(5), 'Started on Barnarúmið', 'Barnarúmið']] },
    ], ended: [] };
  }
  let D; const ls = [];
  function load() { try { D = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { D = null; } if (!D || !D.list) { D = seed(); persist(); } }
  function persist() { try { localStorage.setItem(KEY, JSON.stringify(D)); } catch (e) {} }
  function emit() { ls.forEach(f => { try { f(); } catch (e) { console.error(e); } }); }
  function save() { persist(); emit(); }
  window.addEventListener('storage', e => { if (e.key === KEY || e.key === SW || e.key === null) { load(); emit(); } });
  load();

  const live = s => {
    const it = s.queue[s.index] || s.queue[0], dur = it[2];
    let pos = s.pos + (s.state === 'playing' ? (Date.now() - s.at) / 1000 : 0);
    return Math.min(dur, Math.max(0, pos)) % (dur + 0.001);
  };
  function view(s) {
    const it = s.queue[s.index] || s.queue[0];
    return Object.assign({}, s, { title: it[1], trackId: it[0], dur: it[2], subtitle: s.subs ? s.subs[s.index] || s.sub : s.sub, posNow: live(s),
      places: s.rooms.map(place), line: line(s.rooms), icon: s.rooms.length > 1 ? 'group' : place(s.rooms[0]).icon });
  }
  function line(rooms) { const n = rooms.map(r => place(r).name); return n.length <= 1 ? (n[0] || '') : n.length === 2 ? n[0] + ' + ' + n[1] : n[0] + ' + ' + (n.length - 1); }
  function find(id) { return D.list.find(s => s.id === id); }
  function freeze(s) { s.pos = live(s); s.at = Date.now(); }
  function log(s, what, from) { s.events.push([Date.now(), what, from || '']); if (from && s.touchedBy.indexOf(from) < 0 && !/^(Stue|Kontor|Stovan|Barnarúmið)$/.test(from)) s.touchedBy.push(from); }
  const fmt = sec => { sec = Math.max(0, Math.floor(sec)); const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0'); };
  const master = s => Math.max.apply(null, s.rooms.map(r => s.vols[r] == null ? place(r).vol || 40 : s.vols[r]));

  const API = {
    PLACES, place, fmt, line, ART, master: id => master(find(id)),
    /* every live session, minus the ones playing on `here` (that device shows its own player) */
    list(here) { const cut = Date.now() - 60000; return D.list.filter(s => s.rooms.indexOf(here) < 0).map(view).concat(D.ended.filter(s => s.endedAt > cut && s.rooms.indexOf(here) < 0).map(s => Object.assign(view(s), { ended: true }))); },
    all() { return D.list.map(view); }, endedToday() { return D.ended.map(s => Object.assign(view(s), { ended: true })); },
    get(id) { const s = find(id) || D.ended.find(x => x.id === id); return s ? view(s) : null; },
    outside(placeId) { return place(placeId).busyApp || ''; },
    busy(placeId) { const s = D.list.find(x => x.rooms.indexOf(placeId) >= 0); return s ? view(s) : null; },
    get ctlOthers() { return localStorage.getItem(SW) === '1'; },
    set ctlOthers(v) { localStorage.setItem(SW, v ? '1' : '0'); emit(); },
    canControl(s, viewer) { return viewer === 'admin' || s.ownerId === viewer || API.ctlOthers; },
    touch(here, id) { try { localStorage.setItem(TK + ':' + here, id); } catch (e) {} },
    touched(here) { return localStorage.getItem(TK + ':' + here) || ''; },
    /* the bar's pick (owner 10-04): the one this device touched last; before any touch, the newest playing */
    pick(here, ok) {
      const L = API.list(here).filter(s => !s.ended && (!ok || ok(s))), t = API.touched(here);
      if (t === 'self') return null;
      return L.find(s => s.id === t) || L.filter(s => s.state === 'playing').sort((a, b) => b.created - a.created)[0] || null;
    },
    cmd(id, op, arg, from) {
      const s = find(id); if (!s) return;
      const nm = n => place(n).name;
      switch (op) {
        case 'pp': freeze(s); s.state = s.state === 'playing' ? 'paused' : 'playing'; log(s, (s.state === 'playing' ? 'Resumed' : 'Paused at ' + fmt(s.pos)), from); break;
        case 'play': freeze(s); s.state = 'playing'; log(s, 'Resumed', from); break;
        case 'next': if (s.index < s.queue.length - 1) { s.index++; s.pos = 0; s.at = Date.now(); s.state = 'playing'; log(s, 'Next · ' + s.queue[s.index][1], from); } break;
        case 'prev': freeze(s); if (s.pos > 4 || !s.index) s.pos = 0; else { s.index--; s.pos = 0; } s.at = Date.now(); log(s, 'Previous', from); break;
        case 'jump': s.index = +arg; s.pos = 0; s.at = Date.now(); s.state = 'playing'; log(s, 'Jumped to ' + s.queue[s.index][1], from); break;
        case 'seek': s.pos = +arg; s.at = Date.now(); log(s, 'Moved to ' + fmt(arg), from); break;
        case 'vol': { // arg {room?, v}
          if (arg.room) s.vols[arg.room] = +arg.v;
          else { const m0 = master(s) || 1; s.rooms.forEach(r => { const c = s.vols[r] == null ? place(r).vol : s.vols[r]; s.vols[r] = Math.round(Math.min(100, c * (+arg.v) / m0)); }); }
          s._volAt = Date.now(); break; }
        case 'volDone': log(s, arg.room ? 'Volume on ' + nm(arg.room) + ' · ' + s.vols[arg.room] + '%' : 'Volume · ' + master(s) + '%', from); break;
        case 'add': if (s.rooms.indexOf(arg) < 0) { s.rooms.push(arg); s.vols[arg] = place(arg).vol; log(s, nm(arg) + ' added', from); } break;
        case 'remove': if (s.rooms.length <= 1) return API.cmd(id, 'stop', null, from); s.rooms = s.rooms.filter(r => r !== arg); log(s, nm(arg) + ' removed', from); break;
        case 'move': { freeze(s); const back = s.kind === 'music' ? 0 : 2; s.pos = Math.max(0, s.pos - back); s.rooms = [arg]; s.vols = { [arg]: place(arg).vol }; log(s, 'Moved to ' + nm(arg), from); break; }
        case 'track': s.tracks = Object.assign({}, s.tracks, arg); log(s, (arg.audio ? 'Audio · ' + arg.audio : 'Subtitles · ' + arg.subs), from); break;
        case 'stop': case 'replace': case 'here': freeze(s); s.state = 'ended'; s.endedAt = Date.now();
          log(s, op === 'replace' ? 'Replaced from ' + from : op === 'here' ? 'Moved to ' + from + ' (Play here)' : 'Stopped' + (from ? ' from ' + from : ''), '');
          D.list = D.list.filter(x => x !== s); D.ended.unshift(s); break;
      }
      save();
    },
    reset() { D = seed(); localStorage.removeItem(TK + ':pixel'); localStorage.removeItem(TK + ':mac'); save(); },
    on(f) { ls.push(f); },
  };
  window.RavSess = API;
})();
