/* Ravilo phone — song versions (owner 2026-10-01: direction A, words — in jellystructure AND Ravilo).
   Read-only for the viewer: the same chips the admin sets, in the household's colours, named in the app's language.
   Needs ../app/versions.js (the types, each song's set, and the brief's stand-in songs). Strings ver.* in ravilo-i18n.js. */
(function () {
  const V = window.Versions, M = window.MUSIC; if (!V || !M) return;
  const tr = k => (window.t ? window.t(k) : k);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const nm = k => { const s = tr('ver.' + k); return s === 'ver.' + k ? (V.T[k].s || V.T[k].l) : s; };
  // a phone row is narrow: two chips, then +N (the admin folds at three). Now playing shows them all.
  function chips(x, fold) {
    const ks = V.list(x); if (!ks.length) return '';
    const sh = ks.slice(0, fold || 2), more = ks.length - sh.length;
    return '<span class="rv-vb" role="img" aria-label="' + esc(tr('ver.aria') + ' ' + ks.map(nm).join(', ')) + '">'
      + sh.map(k => '<span class="rv-v" style="--c:' + V.T[k].c + '">' + esc(nm(k)) + '</span>').join('')
      + (more ? '<span class="rv-vm">+' + more + '</span>' : '') + '</span>';
  }
  const title = (x, e) => { const c = chips(x); return c ? '<span class="rv-tt">' + (e || esc)(x.title) + '</span>' + c : (e || esc)(x.title); };
  // so the stand-ins can be found on the phone: two in Recently played, one among Harbour Lights' top songs
  const set = (id, o) => { if (M.byTrack[id]) Object.assign(M.byTrack[id], o); };
  const hl = M.tracksBy ? M.tracksBy('harbour-lights') : [], top = hl.reduce((m, x) => Math.max(m, x.plays || 0), 0);
  set('live-at-the-harbour-1', { plays: Math.max(1, top - 1), last: -2 });
  set('signal-found-4', { last: -1 });
  window.RaviloVersions = { chips, title, names: x => V.list(x).map(nm) };
})();
