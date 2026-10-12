/* The one Ravilo file — the phone's half of the state hand-off (2026-10-04).
   One shape every platform reads and writes (desktop: the same in desktop/ravilo-desktop.js):
     { mode: 'films'|'music',
       page: home · discover · films · series · library · mylist · search · profile        (films)
             listen · playing · artists · albums · songs · genres · playlists · audiobooks · search · profile   (music),
       stack: [{ kind: 'title'|'album'|'artist'|'genre'|'book'|'bauthor'|'playlist', id }],   — what is open, oldest first
       q: the search text,
       play: { queue: [track ids], qi, pos, playing, ctx, shuffle, repeat } }                 — the music that is playing
   window.RaviloState = { get(), set(state) → titles this platform has no stand-in for, miss }.
   ?carry=1 → read localStorage 'ravilo-carry' on start (the shell and the desktop's compact window write it). */
(function () {
  const H = window.RaviloHost, RM = window.RaviloMusic, M = window.MUSIC; if (!H) return;
  const MS = RM && RM.MS, has = id => RM.bar().some(x => x[0] === id), libTab = () => RM.bar().find(x => x[0] === 'm-library' || x[0] === 'm-browse');
  const MUSIC_TAB = { 'm-home': 'listen', 'm-listen': 'listen', 'm-search': 'search', 'm-books': 'audiobooks', 'm-playlists': 'playlists', 'm-now': 'playing', 'm-queue': 'playing', 'm-albums': 'albums', 'm-artists': 'artists', profile: 'profile' };

  function get() {
    const music = !!(MS && MS.mode === 'music'), tab = H.tab;
    const st = { mode: music ? 'music' : 'films', page: 'home', stack: [], q: '' };
    if (!music) {
      st.page = tab === 'library' ? (H.libKind === 'movies' ? 'films' : H.libKind === 'series' ? 'series' : 'library')
        : tab === 'profile' && H.acctWhat === 'mylist' ? 'mylist' : ['home', 'discover', 'search', 'profile'].indexOf(tab) >= 0 ? tab : 'home';
      if (H.detTitle) st.stack.push({ kind: 'title', id: H.detTitle });
      st.q = H.srchQ || '';
    } else {
      const inLib = tab === 'm-library' || tab === 'm-browse';
      st.page = inLib ? (MS.libChip === 'books' ? 'audiobooks' : MS.libChip) : (MUSIC_TAB[tab] || 'listen');
      if (MS.P && MS.P.state.open) st.page = 'playing';
      else {
        if (inLib && MS.libChip === 'genres' && MS.genre) st.stack.push({ kind: 'genre', id: MS.genre });
        MS.detStack.concat(MS.det ? [MS.det] : []).forEach(d => st.stack.push({ kind: d.kind, id: d.id }));
      }
      st.q = MS.srch || '';
    }
    if (MS && MS.P) { const P = MS.P.state; st.play = { queue: P.queue.slice(), qi: P.qi, pos: P.pos, playing: P.playing, ctx: P.ctx, shuffle: P.shuffle, repeat: P.repeat }; }
    return st;
  }

  function set(st) {
    const miss = []; if (!st) return miss;
    const music = st.mode === 'music' && !!(MS && MS.granted);
    const p = st.play;
    if (MS && MS.P && p && Array.isArray(p.queue) && p.queue.every(id => M && M.track(id))) {
      const P = MS.P.state;
      P.queue = p.queue.slice(); P.qi = Math.max(0, Math.min(p.qi || 0, P.queue.length - 1));
      P.pos = P.queue.length ? Math.max(0, Math.min(p.pos || 0, M.track(P.queue[P.qi]).len - 1)) : 0;
      P.playing = !!p.playing && P.queue.length > 0; P.buffering = false; P.ctx = p.ctx || '';
      if ('shuffle' in p) P.shuffle = !!p.shuffle; if (p.repeat) P.repeat = p.repeat;
    }
    if (RM) RM.setMode(music ? 'music' : 'video', true);
    H.closeAcct(); H.closeDetail(); if (RM) RM.closeAllDet(); if (MS && MS.P) MS.P.closeNow();
    const stack = st.stack || [], pg = st.page;
    if (!music) {
      if (pg === 'films' || pg === 'series' || pg === 'library') { H.setLibKind(pg === 'films' ? 'movies' : pg === 'series' ? 'series' : 'all'); H.setTab('library'); }
      else H.setTab(pg === 'mylist' ? 'profile' : ['home', 'discover', 'search', 'profile'].indexOf(pg) >= 0 ? pg : 'home');
      if (typeof st.q === 'string') H.setSrchQ(st.q);
      H.render();
      if (pg === 'mylist') H.openMyList();
      stack.forEach(d => { if (d.kind === 'title') { if (H.knows(d.id)) H.openDetail(d.id); else miss.push(d.id); } });
    } else {
      const lt = libTab(), chips = RM.CHIPS();
      let tab = RM.firstTab();
      if (['artists', 'albums', 'songs', 'genres', 'playlists'].indexOf(pg) >= 0) {
        if (has('m-' + pg)) tab = 'm-' + pg; else if (lt && chips.indexOf(pg) >= 0) { tab = lt[0]; MS.libChip = pg; }
      } else if (pg === 'audiobooks') {
        if (has('m-books')) tab = 'm-books'; else if (lt && chips.indexOf('books') >= 0) { tab = lt[0]; MS.libChip = 'books'; }
      } else if (pg === 'search') tab = has('m-search') ? 'm-search' : (lt ? lt[0] : tab);
      else if (pg === 'profile') tab = 'profile';
      MS.genre = null;
      const g = stack.find(d => d.kind === 'genre');
      if (g && lt) { tab = lt[0]; MS.libChip = 'genres'; MS.genre = g.id; }
      if (typeof st.q === 'string') MS.srch = st.q;
      H.setTab(tab); H.render();
      stack.forEach(d => { if (['album', 'artist', 'book', 'bauthor', 'playlist'].indexOf(d.kind) >= 0) RM.openDet(d.kind, d.id); });
      if (pg === 'playing' && MS.P && MS.P.state.queue.length) MS.P.openNow();
    }
    if (MS && MS.P) { if (MS.P.repaintAll) MS.P.repaintAll(); else MS.P.paintMini(); }
    window.RaviloState.miss = miss;
    return miss;
  }

  window.RaviloState = { get, set, miss: [] };
  if (new URLSearchParams(location.search).get('carry') === '1') {
    let st = null; try { st = JSON.parse(localStorage.getItem('ravilo-carry') || 'null'); } catch (e) {}
    if (st) set(st);
  }
})();
