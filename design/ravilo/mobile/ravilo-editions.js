/* Ravilo — music editions (brief 2026-10-03; owner 2026-10-04: D1 A · Q2 Shuffle gets the same ▾ · Q3 "Play album + extras"
   also plays the B-sides, after the extras · Q4 extras unnumbered · Q5 the better file stands for a song · Q6 Bonus after
   the version chips, no hue, never folded into +N · one copy of every song in lists). Read-only for the viewer.
   Needs ../app/editions.js (the official album, extras, singles, copies). Used by the phone (ravilo-music.js,
   ravilo-music-player.js) and the desktop (ravilo-desktop.js). localStorage ravilo-ed-play: the ▾ pick per album. */
(function () {
  const E = window.Editions, M = window.MUSIC; if (!E || !M) return;
  const tr = (k, v) => (window.t ? window.t(k, v) : k);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const PK = 'ravilo-ed-play';
  let pick = {}; try { pick = JSON.parse(localStorage.getItem(PK) || '{}'); } catch (e) {}
  function of(id) {
    if (!E.of(id)) return null;   // unmatched or no edition ⇒ the plain list, as today
    const o = E.official(id), S = E.singlesOf(id);
    return { off: o.off, ex: o.ex, singles: S, bs: S.reduce((a, s) => a.concat(E.bsides(s).map(t => ({ t, s }))), []), edition: E.of(id).edition };
  }
  // "Play album + extras" = the album, its extras, then the B-sides in single order (Q3)
  const ids = (id, all) => { const d = of(id); if (!d) return M.tracksOf(id).map(x => x.id); return (all ? d.off.concat(d.ex, d.bs.map(b => b.t)) : d.off).map(x => x.id); };
  const getPick = id => (pick[id] === 'alx' ? 'alx' : 'al');
  const setPick = (id, v) => { if (v === 'alx') pick[id] = v; else delete pick[id]; try { localStorage.setItem(PK, JSON.stringify(pick)); } catch (e) {} };
  const bonus = x => (E.isExtra(x) ? '<span class="rv-bonus">' + esc(tr('ed.bonus')) + '</span>' : '');
  // every song row outside its own album page carries Bonus after the version chips (Q6)
  const RV = window.RaviloVersions;
  if (RV) {
    const ch = RV.chips, add = (c, b) => (c ? c.replace(/<\/span>$/, b + '</span>') : b ? '<span class="rv-vb">' + b + '</span>' : '');
    RV.chipsB = (x, n) => add(ch(x, n), bonus(x));
    RV.title = (x, e) => { e = e || esc; const c = RV.chipsB(x); return c ? '<span class="rv-tt">' + e(x.title) + '</span>' + c : e(x.title); };
  }
  const fold = list => E.fold(list, false).map(r => r.t);   // lists show a song once; the shown copy is the better file
  const also = x => E.copiesOf(x).filter(y => y !== x);
  const kindLabel = a => (a.type === 'album' ? tr('ed.album') : tr('music.type.' + a.type));
  function singleLine(id) {
    const a = M.album(id), h = a && (a.type === 'single' || a.type === 'ep') && E.home(id);
    return h ? '<div class="re-sfrom">' + tr('ed.single_from', { a: '<a data-mu="album" data-al="' + h + '" data-id="' + h + '">' + esc(M.album(h).title) + '</a>' }) + '</div>' : '';
  }
  function underNote(artistId) {
    const u = E.underOf(artistId); if (!u.length) return '';
    const hs = [...new Set(u.map(s => E.home(s.id)))];
    return '<div class="re-under">' + esc(tr('ed.under', { n: u.length })) + ' — ' + hs.map(h => '<a data-mu="album" data-al="' + h + '" data-id="' + h + '">' + esc(M.album(h).title) + '</a>').join(' · ') + '</div>';
  }
  // the phone's album-page clicks (ravilo-music.js routes unknown data-mu keys here)
  function onClick(k, el) {
    const RM = window.RaviloMusic; if (!RM || k.indexOf('ed-') !== 0) return false;
    const MS = RM.MS, p = (el.dataset.id || '').split('|');
    if (k === 'ed-menu') { MS.edMenu = MS.edMenu && MS.edMenu.id === p[0] && MS.edMenu.k === p[1] ? null : { id: p[0], k: p[1] }; RM.paintDet(); }
    else if (k === 'ed-pick') { setPick(p[0], p[1]); MS.edMenu = null; RM.paintDet(); const c = p[1] + ':' + p[0]; MS.P.playCtx(MS.CTX[c] || ids(p[0], p[1] === 'alx'), 0, c, p[2] === 'shuf'); }
    else if (k === 'ed-bs') { MS.edB = MS.edB === p[0] ? null : p[0]; RM.paintDet(); }
    else return false;
    return true;
  }
  window.RaviloEditions = { of, ids, getPick, setPick, bonus, fold, also, kindLabel, singleLine, underNote, onClick, home: E.home };
})();
