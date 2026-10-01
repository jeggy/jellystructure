/* Dashboard as one overview — renderer. Three directions of brief §C behind the Q1 pick (lean 1), §D's rules held
   in all three, §E's states behind the preview fence, §F's questions as marked options that switch the page live. */
(function () {
  const D = window.DASH, $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fmt = n => n >= 1000 ? String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ') : String(n);
  const LS = 'js-dash-q', LSALL = 'js-dash-showall';
  // owner 2026-09-28: Q1 Direction 2 · Q3 hidden · Q4 3 · Q5 This server on the Dashboard (Settings shows an indicator only) · Q7 replaces · Q8 folded · Q9 split · Q10 the dock is gone.
  // Q2 and Q6 were unclear to the owner — mine: count titles (the instances stay in the sentence); the intro-detection queue stays one information row under Series.
  const LEAN = { dir: '2', count: 'instances', zero: 'hidden', fold: '3', host: 'dash', queue: 'series', since: 'replaces', quick: 'groups', split: 'yes' };
  const MINE = { count: 1, queue: 1 };
  const LSV = 'js-dash-q-v2';
  // every §F question is decided, so the page has no question panel any more — it simply is the decisions
  let Q = Object.assign({}, LEAN);
  const qp = new URLSearchParams(location.search);
  if (qp.get('dir')) Q.dir = qp.get('dir');
  let state = qp.get('state') || 'today', scan = qp.get('scan') || 'idle', showAll = localStorage.getItem(LSALL) === '1', open = {}, chip = 'all';
  const SEV = { critical: 0, warning: 1, info: 2 };
  const unit = (u, n) => (D.UNITS[u] || [u, u])[n === 1 ? 0 : 1];

  function rows() {
    let r = D.ROWS.slice();
    if (Q.zero === 'hidden') r = r.filter(x => !x.zero);
    if (Q.queue === 'activity') r = r.filter(x => !x.q6);
    if (Q.host === 'settings') r = r.filter(x => x.g !== 'host');
    if (window.MusicQ && MusicQ.get('wma') !== 'convert') r = r.filter(x => !x.wma);
    switch (state) {
      case 'clear': return [];
      case 'first': return [];
      case 'critical': return r.filter(x => x.id === 'j-nfo');
      case 'jfdown': return r.filter(x => x.g !== 'jf' && x.g !== 'host');
      case 'bazarroff': return r.filter(x => x.g !== 'subs');
      case 'seerroff': return r.filter(x => !x.seerr);
      case 'musiconly': return r.filter(x => x.g === 'music' || x.g === 'jf' || x.g === 'host' || x.g === 'svc');
      case 'infoonly': return r.filter(x => !x.fixable);
    }
    return r;
  }
  function groups() {
    let G = D.GROUPS.slice();
    if (Q.split === 'no') G = [{ id: 'fs', label: 'Films & series', size: '321 films · 9 269 episodes', lib: 'library.html' }].concat(G.filter(g => g.id !== 'films' && g.id !== 'series'));
    return G;
  }
  const gOf = x => (Q.split === 'no' && (x.g === 'films' || x.g === 'series')) ? 'fs' : x.g;
  const sortRows = a => a.slice().sort((x, y) => (x.zero - y.zero) || (SEV[x.sev] - SEV[y.sev]) || ((y.n || 1) - (x.n || 1)));

  function countHTML(x) {
    if (x.zero) return '<span class="ov-n z">✓ 0</span>';
    if (x.n == null) return x.now ? '<span class="ov-now" title="the value now">' + esc(x.now) + '</span>' : '';
    const inst = Q.count === 'instances' && x.inst;
    const n = inst ? x.inst : x.n, u = inst ? x.iu : unit(x.u, x.n);
    return '<span class="ov-n" title="' + (x.inst ? 'across ' + fmt(x.n) + ' ' + unit(x.u, x.n) : '') + '"><b>' + fmt(n) + '</b> ' + esc(u) + '</span>';
  }
  function sentence(x) {
    let s = x.s || '';
    s = s.replace('{titles}', fmt(x.n) + ' ' + unit(x.u, x.n));
    return s;
  }
  function fixHTML(x) {
    if (x.zero) return '<span class="ov-fix k-open">Library filter</span>';
    if (x.fix === 'here') return '<span class="ov-fix k-here">One click here</span><span class="btn sm" data-act="' + x.id + '">' + esc(x.act || 'Fix') + '</span>' + (x.act2 ? '<span class="btn sm ghost" data-act2="' + x.id + '">' + esc(x.act2) + '</span>' : '');
    if (x.fix === 'open') return '<span class="ov-fix k-open">Open the item</span><a class="btn sm ghost" href="' + x.href + '">' + esc(x.act || 'Open') + ' →</a>';
    if (x.fix === 'elsewhere') return '<span class="ov-fix k-else">Change ' + (x.where === 'the host' ? 'on ' : 'in ') + esc(x.where) + '</span>' + (x.act ? '<span class="btn sm ghost" data-act="' + x.id + '">' + esc(x.act) + '</span>' : '');
    return '<span class="ov-fix k-info">For information</span>' + (x.act ? '<a class="btn sm ghost" href="' + x.href + '">' + esc(x.act) + ' →</a>' : '');
  }
  function rowHTML(x, withChip) {
    const g = groups().find(g => g.id === gOf(x));
    return '<div class="ov-row sev-' + x.sev + (x.zero ? ' zero' : '') + '">'
      + '<span class="ov-sev" title="' + x.sev + '"></span>'
      + '<div class="ov-main"><div class="ov-l">' + (withChip ? '<span class="ov-dom">' + esc(g.label) + '</span>' : '') + '<a href="' + x.href + '">' + esc(x.label) + '</a>' + (x.p284 ? '<span class="ov-new">284</span>' : '') + (x.speak ? '<span class="ov-new">286</span>' : '') + '</div>'
      + (sentence(x) ? '<div class="ov-s">' + esc(sentence(x)) + '</div>' : '')
      + (x.path ? '<div class="ov-path">' + esc(x.where) + ' › ' + esc(x.path) + '</div>' : '') + '</div>'
      + countHTML(x)
      + '<div class="ov-act">' + fixHTML(x) + '</div></div>';
  }
  function worst(rs) { const live = rs.filter(r => !r.zero); if (!live.length) return 'none'; return live.map(r => r.sev).sort((a, b) => SEV[a] - SEV[b])[0]; }
  function sevMark(rs) {
    const c = rs.filter(r => r.sev === 'critical').length, w = rs.filter(r => r.sev === 'warning' && !r.zero).length, i = rs.filter(r => r.sev === 'info').length;
    return (c ? '<span class="badge bad">' + c + ' critical</span>' : '') + (w ? '<span class="badge warn">' + w + ' to fix</span>' : '') + (i && !c && !w ? '<span class="badge">' + i + ' for information</span>' : i ? '<span class="tiny muted">+ ' + i + ' for information</span>' : '');
  }
  const N = () => Q.fold === 'all' ? 1e9 : +Q.fold;
  function folded(key, rs) {
    const n = N(), all = showAll || open[key];
    const shown = all ? rs : rs.slice(0, n), more = rs.length - shown.length;
    return { shown, more };
  }
  function moreBtn(key, more) { return more > 0 ? '<button class="ov-more" data-more="' + key + '">+' + more + ' more</button>' : (open[key] && !showAll ? '<button class="ov-more" data-less="' + key + '">Show fewer</button>' : ''); }

  // ---- Direction 1 · domain sections (lean)
  function dir1(R) {
    return '<div class="ov-grid">' + groups().map(g => {
      let rs = sortRows(R.filter(x => gOf(x) === g.id));
      if (!rs.length) return '';
      const f = folded(g.id, rs);
      return '<section class="ov-sec w-' + worst(rs) + '"><div class="ov-sh"><h3><a href="' + g.lib + '">' + g.label + '</a></h3><span class="ov-size">' + esc(g.size) + '</span><span class="spacer"></span>' + sevMark(rs) + '</div>'
        + f.shown.map(x => rowHTML(x)).join('') + moreBtn(g.id, f.more) + '</section>';
    }).join('') + '</div>';
  }
  // ---- Direction 2 · severity first, domain as chips
  function dir2(R) {
    const G = groups().filter(g => R.some(x => gOf(x) === g.id && !x.zero));
    const R2 = chip === 'all' ? R : R.filter(x => gOf(x) === chip);
    const chips = '<div class="ov-chips"><button class="chip' + (chip === 'all' ? ' on' : '') + '" data-chip="all">All · ' + R.filter(x => !x.zero).length + '</button>' + G.map(g => '<button class="chip' + (chip === g.id ? ' on' : '') + '" data-chip="' + g.id + '">' + g.label + ' · ' + R.filter(x => gOf(x) === g.id && !x.zero).length + '</button>').join('') + '</div>';
    const bands = [['critical', 'Critical'], ['warning', 'To fix'], ['info', 'For information']];
    return chips + '<div class="ov-list">' + bands.map(([s, l]) => {
      const rs = sortRows(R2.filter(x => x.sev === s && !x.zero)).concat(s === 'warning' ? R2.filter(x => x.zero) : []);
      if (!rs.length) return '';
      const f = folded('b-' + s, rs);
      return '<section class="ov-band w-' + s + '"><div class="ov-sh"><h3>' + l + '</h3><span class="ov-size">' + rs.filter(x => !x.zero).length + ' rows</span></div>' + f.shown.map(x => rowHTML(x, true)).join('') + moreBtn('b-' + s, f.more) + '</section>';
    }).join('') + '</div>';
  }
  // ---- Direction 3 · two queues: here and elsewhere
  function dir3(R) {
    const col = (title, sub, pred, key) => {
      const rs = R.filter(pred);
      return '<div class="ov-col"><div class="ov-colh"><h3>' + title + '</h3><span class="tiny muted">' + sub + '</span></div>' + groups().map(g => {
        const gr = sortRows(rs.filter(x => gOf(x) === g.id));
        if (!gr.length) return '';
        const f = folded(key + g.id, gr);
        return '<section class="ov-sub"><div class="ov-subh">' + g.label + '<span class="tiny muted">' + gr.filter(x => !x.zero).length + '</span></div>' + f.shown.map(x => rowHTML(x)).join('') + moreBtn(key + g.id, f.more) + '</section>';
      }).join('') + '</div>';
    };
    return '<div class="ov-two">' + col('Fix it here', 'one click, or open the item', x => x.fix === 'here' || x.fix === 'open' || (x.fix === 'info' && x.g !== 'jf' && x.g !== 'host'), 'h-')
      + col('Change it elsewhere', 'in Jellyfin, on the host, in another service', x => x.fix === 'elsewhere' || (x.fix === 'info' && (x.g === 'jf' || x.g === 'host')), 'e-') + '</div>';
  }

  function totals(R) {
    const live = R.filter(x => !x.zero && x.sev !== 'info');
    const titles = live.filter(x => x.n != null && !['library', 'setting', 'speaker'].includes(x.u)).reduce((a, x) => a + (x.inst || x.n), 0);
    const settings = live.filter(x => x.n == null || ['library', 'setting', 'speaker'].includes(x.u)).length;
    const crit = R.filter(x => x.sev === 'critical').length;
    return { titles, settings, crit };
  }
  function scanHTML() {
    const S = {
      scanning: ['ok', 'Scanning — 412 of 9 590 items…', '<span class="btn sm bad">Stop scan</span>'],
      paused: ['warn', 'Paused — TV is watching (Stue TV)', '<span class="tiny muted">resumes when playback stops</span>'],
      stopped: ['warn', 'Stopped — Jellyfin still finishing 3 subtitle extractions', '<span class="tiny muted">the scan waits for them</span>'],
    }[scan];
    return S ? '<div class="ov-scan ' + S[0] + '"><span class="dot ' + S[0] + '"></span><b>' + S[1] + '</b><span class="spacer"></span>' + S[2] + (scan === 'scanning' ? '<span class="ov-bar"><i style="width:4.3%"></i></span>' : '') + '</div>' : '';
  }
  function sinceHTML() {
    if (state === 'first') return '';
    const nothing = state === 'sincenone' || state === 'clear';
    const items = nothing ? [] : D.SINCE.items.filter(([g]) => groups().some(x => x.id === g || (g === 'films' || g === 'series') && x.id === 'fs'));
    const strip = '<div class="ov-since"><span class="lb">Since your last visit</span><span class="tiny muted">' + D.SINCE.when + '</span>'
      + (items.length ? items.map(([g, t]) => '<span class="chip"><b>' + (groups().find(x => x.id === g) || { label: 'Films & series' }).label + '</b> ' + esc(t) + '</span>').join('') : '<span class="tiny">Nothing new since then.</span>')
      + '<a class="tiny" href="activity.html" style="margin-left:auto">the full log is on Activity →</a></div>';
    if (Q.since === 'replaces') return strip;
    return strip + '<div class="card ov-recent"><h4>Recently processed</h4>' + D.RECENT.map(t => '<div class="tiny mono"><span class="dot ok"></span> ' + esc(t) + '</div>').join('') + '</div>';
  }
  function quickHTML() {
    if (Q.quick === 'groups') return '';
    return '<div class="ov-quick"><span class="lb">Quick actions</span>' + ['View items needing attention', 'Manage tracks', 'Re-pull artwork', 'Repair corrupt artwork', 'Sync NFOs to Jellyfin', 'Jellyfin: rescan its library', 'View activity'].map(c => '<span class="chip">' + c + '</span>').join('') + '</div>';
  }

  function render() {
    const R = rows(), t = totals(R);
    const head = $('ov-head');
    if (state === 'first') head.innerHTML = '<span class="ov-big">Nothing scanned yet</span>';
    else if (!R.filter(x => !x.zero).length) head.innerHTML = '<span class="ov-big">Nothing needs you</span>';
    else head.innerHTML = '<span class="ov-big">' + fmt(t.titles) + ' things and ' + t.settings + ' settings could be fixed</span>' + (t.crit ? '<span class="badge bad">' + t.crit + ' critical</span>' : '')
      + '<span class="spacer"></span><button class="btn sm ghost" id="ov-all">' + (showAll ? 'Fold every group' : 'Show all') + '</button>';
    const side = document.querySelector('.app-side .status a.row');
    if (side) side.innerHTML = '<span class="dot ' + (t.crit ? 'bad' : t.titles ? 'warn' : 'ok') + '"></span> ' + (t.titles ? fmt(t.titles) + ' could be fixed' : 'nothing to fix');
    $('ov-scanb').innerHTML = scanHTML();
    $('ov-since').innerHTML = sinceHTML();
    $('ov-quick').innerHTML = quickHTML();
    let body;
    if (state === 'first') body = '<div class="card ov-empty"><h3>Nothing scanned yet</h3><p>The first scan reads every library, then this page fills in, group by group. It takes a while on a large library; you can leave this page.</p><span class="btn primary">▶ Scan library</span></div>';
    else if (!R.filter(x => !x.zero).length && Q.zero === 'hidden') body = '<div class="card ov-empty"><h3>Nothing needs you.</h3><p>Last scan 13:30, next 14:30.</p></div>';
    else body = (Q.dir === '2' ? dir2(R) : Q.dir === '3' ? dir3(R) : dir1(R));
    if (state === 'jfdown') body = '<div class="ov-down"><span class="dot bad"></span><b>Jellyfin can’t be reached</b><span class="tiny muted">— its settings and this server’s can’t be checked until it answers. Last answer 13:02.</span><a class="tiny" href="settings.html#connections" style="margin-left:auto">Connections →</a></div>' + body;
    if (Q.host === 'settings' && state !== 'jfdown' && state !== 'first' && state !== 'clear') body += '<div class="ov-foot tiny muted">This server’s findings (disks, swap, proxies) live in <a href="settings.html#host">Settings → Host</a>.</div>';
    $('ov-body').innerHTML = body;
  }

  function syncFence() {
    document.querySelectorAll('#ov-state span').forEach(s => s.classList.toggle('on', s.dataset.s === state));
    document.querySelectorAll('#ov-scanst span').forEach(s => s.classList.toggle('on', s.dataset.s === scan));
  }
  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2000); }
  document.addEventListener('click', e => {
    const t = e.target.closest('[data-more],[data-less],[data-chip],[data-act],[data-act2],#ov-all,.seg > span');
    if (!t) return;
    if (t.dataset.act2) { const x = D.ROWS.find(r => r.id === t.dataset.act2); return toast(x.done2 || x.act2); }
    if (t.dataset.more) { open[t.dataset.more] = true; return render(); }
    if (t.dataset.less) { open[t.dataset.less] = false; return render(); }
    if (t.dataset.chip) { chip = t.dataset.chip; return render(); }
    if (t.dataset.act) { const x = D.ROWS.find(r => r.id === t.dataset.act); return toast(x.done || (x.fix === 'here' ? x.act + ' — queued; watch it on Activity' : x.act === 'Re-check' ? 'Re-checking…' : 'Opening ' + x.where + '’s step')); }
    if (t.id === 'ov-all') { showAll = !showAll; open = {}; localStorage.setItem(LSALL, showAll ? '1' : '0'); return render(); }
    const sg = t.parentElement;
    if (sg.id === 'ov-state') { state = t.dataset.s; syncFence(); return render(); }
    if (sg.id === 'ov-scanst') { scan = t.dataset.s; syncFence(); return render(); }
  });
  syncFence(); render();
  if (window.MusicQ) MusicQ.on(render);
})();
