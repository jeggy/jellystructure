/* ai-suggestions-tab round — renders every admin frame on the directions canvas and on the 1:1 shot page.
   Data: suggestions-data.js (window.SUGG), unchanged — the same corpus the Suggestions canvas uses. Every number drawn
   is a value the server sends (SUGG.built, the viewers' finished counts, the list, the dismissed rows, the open-ticket
   count); nothing here counts or derives a figure of its own beyond rendering list.length as "{n} shown".
   A frame is described by data-f="key=val;…": dir A|B · w d|n|dl|nl|side · st · th dk|lt · lang en|da|fo · tabs · nav · dism */
(function () {
  const D = window.SUGG;
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const SUPPORT_OPEN = 2;   // the open-ticket count the server pushes (support-lane stand-in: #41, #38)
  const S = {
    en: { ai: 'AI', key: 'API key valid · model reachable', set: 'Key &amp; model → Settings', support: 'Support', fetchers: 'Fetchers', sugg: 'Suggestions',
      conn: '● Seerr connected', unr: '● Seerr unreachable', built: n => 'Built ' + D.built + ' · ' + n + ' shown of 50', dism: n => 'Dismissed (' + n + ')', rebuild: '⟲ Rebuild now',
      from: 'Built from 112 finished films:', none: n => n + ' · nothing finished yet',
      gAI: 'Grouped by AI this build', gOff: 'Grouped by genre — AI was off', gLim: 'Grouped by genre — this month’s AI limit was reached' },
  };
  const grad = h => 'linear-gradient(160deg,hsl(' + h + ' 45% 34%),hsl(' + ((h + 30) % 360) + ' 55% 14%))';
  const mins = m => Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
  const vname = id => (D.VIEWERS.find(v => v.id === id) || {}).name || id;
  const bucket = id => D.BUCKETS.find(x => x.id === id);
  function andList(a) { const t = a.map(x => '<i>' + esc(x) + '</i>'); return t.length < 2 ? t.join('') : t.slice(0, -1).join(', ') + ' and ' + t[t.length - 1]; }
  const because = it => 'because ' + it.because.map(([v, verb, ts]) => esc(vname(v)) + (verb === 'watching' ? ' is watching ' : ' finished ') + andList(ts.slice(0, 3))).join('; ');
  const requester = it => 'requested as Admin · note: for ' + esc(vname(it.because[0][0]));
  function stateHTML(it) {
    if (it.state === 'requested') return '<div class="sg-state"><span class="badge warn">Requested</span><span class="tiny muted">waiting for approval in Seerr · ' + requester(it) + '</span></div>';
    if (it.state === 'approved') return '<div class="sg-state"><span class="badge nt">Approved</span><span class="tiny muted">Radarr is looking for it · ' + requester(it) + '</span></div>';
    if (it.state === 'downloading') return '<div class="sg-state"><span class="badge nt">Downloading · ' + it.pct + '%</span><span class="sg-bar"><i style="width:' + it.pct + '%"></i></span></div>';
    if (it.state === 'library') return '<div class="sg-state"><span class="badge ok">✓ In the library</span><span class="tiny muted">leaves this list on the next build</span></div>';
    return null;
  }
  function tile(it, o) {
    const b = bucket(it.b), st = stateHTML(it), n = new Set(it.because.map(x => x[0])).size;
    const off = o.st === 'unreach' || o.st === 'offline';
    const multi = n > 1 ? '<span class="chip sg-multi">' + n + ' viewers</span>' : '';
    const acts = st ? '<div class="sg-acts">' + multi + '<span class="sp"></span>' + st + '</div>'
      : '<div class="sg-acts">' + multi + '<span class="sp"></span><span class="btn sm ghost' + (o.st === 'offline' ? ' dis' : '') + '">No thanks</span><span class="btn sm primary' + (off ? ' dis' : '') + '">Download</span>'
        + (o.st === 'unreach' ? '<span class="sg-dis">Seerr can’t be reached right now — this waits until it’s back.</span>' : '') + '</div>';
    return '<div class="sg-tile"><div class="sg-post" style="background:' + grad(b.hue + (it.year % 7) * 6) + '"><span>' + esc(it.title) + '</span></div><div class="sg-body">'
      + '<div class="sg-t">' + esc(it.title) + '<span class="y">' + it.year + '</span></div>'
      + '<div class="sg-meta"><span>★ ' + it.score.toFixed(1) + '</span><span>·</span><span>' + mins(it.mins) + '</span>' + (it.cert ? '<span class="sg-cert">' + esc(it.cert) + '</span>' : '') + (it.fresh ? '<span class="tiny muted">· new this year, few ratings yet</span>' : '') + '</div>'
      + (it.syn ? '<div class="sg-syn">' + esc(it.syn) + '</div>' : '')
      + '<div class="sg-why">' + because(it) + '</div>'
      + (it.franchise ? '<div class="sg-fr">First of a series you don’t have — the match was <b>' + esc(it.franchise) + '</b>.</div>' : '')
      + acts + '</div></div>';
  }
  // owner 2026-10-16: AI-named groups; each group shown once, its weaker matches under "Show {n} more" in the group
  const bh = (b, n) => '<div class="sg-bh"><h3>' + esc(D.AI_NAMES[b.id] || b.label) + '</h3><span class="n">' + n + '</span><span class="ev">the household finished ' + b.finished + ' films like these</span></div>';
  const moreRow = b => { const n = D.MORE.filter(x => x.b === b.id).length; return n ? '<div class="sg-more"><span class="btn sm">Show ' + n + ' more</span><span class="tiny muted">weaker matches from the same build</span></div>' : ''; };
  function bucketsHTML(o, max) {
    let left = max;
    return D.BUCKETS.map(b => {
      if (left <= 0) return '';
      const its = D.LIST.filter(x => x.b === b.id); if (!its.length) return '';
      const show = its.slice(0, left); left -= show.length;
      return '<section class="sg-bucket">' + bh(b, its.length) + '<div class="sg-grid">' + show.map(it => tile(it, o)).join('') + '</div>' + (show.length === its.length ? moreRow(b) : '') + '</section>';
    }).join('');
  }
  function groupEdge(o) {   // scrolled: the end of the first group, its Show more, the start of the next
    const narrow = o.w === 'nl', h = D.BUCKETS[0], f = D.BUCKETS[1];
    const hi = D.LIST.filter(x => x.b === h.id), fi = D.LIST.filter(x => x.b === f.id);
    return '<div style="margin-top:-' + (narrow ? 150 : 120) + 'px"><section class="sg-bucket" style="margin-top:0"><div class="sg-grid">' + hi.slice(narrow ? -1 : -1).map(it => tile(it, o)).join('') + '</div>' + moreRow(h) + '</section>'
      + '<section class="sg-bucket">' + bh(f, fi.length) + '<div class="sg-grid">' + fi.slice(0, narrow ? 2 : 4).map(it => tile(it, o)).join('') + '</div></section></div>';
  }
  const who = L => '<div class="sg-who"><span>' + L.from + '</span>' + D.VIEWERS.map(v => v.finished ? '<span class="chip"><b>' + v.finished + '</b> ' + esc(v.name) + '</span>' : '<span class="chip none">' + L.none(esc(v.name)) + '</span>').join('') + '</div>';
  const grp = (L, k) => '<div class="sg-who"><span class="dot ' + (k === 'ai' ? 'ok' : 'warn') + '"></span><span class="gt">' + (k === 'ai' ? L.gAI + ' · <a>see the answer</a>' : k === 'lim' ? L.gLim : L.gOff) + '</span></div>';
  const SUB = '<p class="page-sub">Films Seerr can get that the library doesn\'t have, picked from what the household <b>finished</b> — each kind given room in proportion to how much of it gets watched. Nothing is requested until someone presses <b>Download</b> and confirms the quality. The same list, per viewer, is a row on Ravilo\'s Request tab (<a>Requests</a>).</p>';

  function seerr(o, L) { return o.st === 'unreach' ? '<span class="badge warn">' + L.unr + '</span>' : '<span class="badge ok">' + L.conn + '</span>'; }
  function sugCtl(o, L) {   // Built · shown · Dismissed · Rebuild — the controls this round moves
    const n = o.st === 'empty' ? 0 : D.LIST.length;
    if (o.st === 'loading') return '<span class="sk" style="width:210px"></span><span class="sp"></span><span class="sk b" style="width:104px"></span><span class="sk b" style="width:118px"></span>';
    return '<span class="tiny muted">' + L.built(n) + '</span><span class="sp"></span><span class="chip' + (o.st === 'dism' || o.st === 'drawer' ? ' on' : '') + '">' + L.dism(D.DISMISSED.length) + '</span><span class="btn sm' + (o.st === 'offline' ? ' dis' : '') + '">' + L.rebuild + '</span>';
  }
  function header(o, L) {
    let h = '<div class="pb"><h1>' + L.ai + '</h1><span class="chk"><span class="dot ok"></span><span class="badge ok">' + L.key + '</span></span>';
    if (o.dir === 'B' && o.tab === 'suggestions') h += (o.st === 'loading' ? '<span class="sk b" style="width:140px"></span>' : seerr(o, L)) + sugCtl(o, L);
    else h += '<span class="sp"></span>';
    return h + '<span class="btn sm ghost">' + L.set + '</span></div>';
  }
  function tabsHTML(o, L) {
    const list = o.tabs.split(',');
    return '<div class="tabs">' + list.map(t => '<span' + (t === o.tab ? ' class="on"' : '') + '>' + (t === 'support' ? L.support + ' <em class="tk-cnt">' + SUPPORT_OPEN + '</em>' : t === 'fetchers' ? L.fetchers : L.sugg + (o.nav === 'alt' ? ' <em class="cnt nt">' + D.newSince + '</em>' : '')) + '</span>').join('') + '</div>';
  }
  function dismissedHTML(o, close) {
    const narrow = o.w === 'n' || o.w === 'nl';
    const head = '<div class="dh"><h3>Dismissed</h3><span class="badge nt">' + D.DISMISSED.length + '</span><span class="sp"></span><span class="btn sm ghost">' + close + '</span></div>'
      + '<p class="tiny muted" style="margin:6px 0 0;line-height:1.5">Everything said no to, and why — and anything blacklisted in Seerr directly. None of it is suggested again; <b>Bring back</b> also takes it off Seerr’s blacklist.</p>';
    const rows = narrow || o.st === 'drawer'
      ? '<div class="dmini" style="margin-top:8px">' + D.DISMISSED.map(x => '<div><span><b>' + esc(x.title) + '</b> <span class="muted">' + x.year + '</span></span><span class="r">' + esc(x.reason) + '</span><span class="w">' + esc(x.who) + ' · ' + esc(x.when) + '</span><span class="btn sm ghost">Bring back</span></div>').join('') + '</div>'
      : '<table class="sg-dtable"><thead><tr><th>Title</th><th>Reason</th><th>Who</th><th>When</th><th></th></tr></thead><tbody>' + D.DISMISSED.map(x => '<tr><td><b>' + esc(x.title) + '</b> <span class="muted">' + x.year + '</span></td><td>' + esc(x.reason) + '</td><td>' + esc(x.who) + '</td><td class="tiny muted">' + esc(x.when) + '</td><td style="text-align:right"><span class="btn sm ghost">Bring back</span></td></tr>').join('') + '</tbody></table>';
    return head + rows;
  }
  function panel(o, L) {
    const narrow = o.w === 'n';
    const nT = narrow ? 3 : 6;
    let h = '';
    if (o.dir === 'A') h += '<div class="sg-tb">' + (o.st === 'loading' ? '<span class="sk b" style="width:140px"></span>' : seerr(o, L)) + sugCtl(o, L) + '</div>';
    if (o.st === 'loading') {
      h += '<div class="sg-who"><span class="sk" style="width:200px"></span><span class="sk b" style="width:70px"></span><span class="sk b" style="width:80px"></span><span class="sk b" style="width:60px"></span></div>'
        + '<div class="sg-bucket"><div class="sg-bh"><span class="sk" style="width:150px;height:18px"></span></div><div class="sg-grid">'
        + Array.from({ length: narrow ? 3 : 4 }, () => '<div class="skt"><div class="p"></div><div class="l"><span class="sk" style="width:60%"></span><span class="sk" style="width:35%;height:10px"></span><span class="sk" style="width:92%;height:10px"></span><span class="sk" style="width:80%;height:10px"></span></div></div>').join('') + '</div></div>';
      return h;
    }
    if (o.st === 'dism') return h + '<div class="card">' + dismissedHTML(o, 'Close') + '</div>';
    if (o.st === 'long') return h + who(L) + grp(L, 'lim');
    h += SUB;
    if (o.st !== 'empty') h += who(L);
    h += grp(L, o.grp || 'ai');
    if (o.st === 'unreach') h += '<div class="note"><span class="badge warn" style="flex:none">Last built list</span><span class="tx">Built ' + D.built + ' · Seerr couldn’t be reached since 09:12. The list is still true; <b>Download</b> waits until Seerr answers again.</span><span class="btn sm ghost">Try again</span></div>';
    if (o.st === 'empty') return h + '<div class="card sg-empty" style="margin-top:18px"><h3>Suggestions appear once someone has finished a few films.</h3></div>';
    return h + bucketsHTML(o, nT);
  }
  function mainHTML(o) {
    const L = S[o.lang || 'en'];
    if (o.st === 'more') return groupEdge(o);
    let h = header(o, L);
    if (o.st === 'offline') h += '<div class="offl"><span class="dot"></span><span><b>Connection to this server lost · reconnecting.</b> What you see is what it last sent. Nothing is sent until it’s back — Download, No thanks and Rebuild wait.</span></div>';
    h += tabsHTML(o, L) + panel(o, L);
    if (o.st === 'drawer') h += '<div class="scrim"></div><div class="drw">' + dismissedHTML(o, '✕ Close') + '</div>';
    return h;
  }
  const NAV1 = [['Dashboard'], ['Library'], ['Activity'], ['AI', 'ai']];
  function sideHTML(o) {
    const sug = o.nav === 'gone' ? '<a class="gone"><span class="ic"></span><s>Suggestions</s><span class="sp"></span></a>' : '';
    const it = n => '<a' + (n[1] === 'ai' ? ' class="on"' : '') + '><span class="ic"></span>' + n[0] + '<span class="sp"></span>' + (n[1] === 'ai' ? '<em class="cnt">' + SUPPORT_OPEN + '</em>' + (o.nav === 'alt' ? '<em class="cnt nt">' + D.newSince + '</em>' : '') : '') + '</a>';
    return '<aside class="sd"><div class="lg"><span class="lgm"></span>Jellystructure</div>'
      + it(NAV1[0]) + it(NAV1[1]) + sug + it(NAV1[2]) + it(NAV1[3])
      + '<div class="gl">Setup</div>' + it(['Metadata']) + it(['Settings'])
      + '<div class="gl">Ravilo</div>' + it(['Layout']) + it(['Live TV']) + it(['Requests']) + it(['Users &amp; devices']) + it(['Preferences'])
      + '<div class="grow"></div><div class="stt"><span><i class="dot ok"></i>Jellyfin online</span><span><i class="dot ok"></i>TMDB key OK</span><span><i class="dot ok"></i>AI key OK</span></div></aside>';
  }
  function frame(o) {
    o = Object.assign({ dir: 'A', w: 'd', st: 'main', th: 'dk', lang: 'en', tabs: 'support,fetchers,suggestions', tab: 'suggestions', nav: 'lean' }, o);
    const cls = 'adm ' + (o.w === 'dl' ? 'd dl' : o.w === 'nl' ? 'n nl' : o.w === 'side' ? 'sideonly' : o.w) + (o.th === 'lt' ? ' lt' : '');
    if (o.w === 'side') return '<div class="' + cls + '">' + sideHTML(o) + '</div>';
    const desk = o.w === 'd' || o.w === 'dl';
    if (desk) return '<div class="' + cls + '">' + sideHTML(o) + '<main class="mn">' + mainHTML(o) + '</main></div>';
    return '<div class="' + cls + '"><div class="tbar"><span class="burg"><i></i><i></i><i></i></span><span class="lgx"><span class="lgm"></span>Jellystructure</span></div><main class="mn">' + mainHTML(o) + '</main>'
      + (o.drawerNav ? '<div class="ndrw"><div class="bk"></div>' + sideHTML(o) + '</div>' : '') + '</div>';
  }
  const DIMS = { d: [1280, 800], n: [390, 844], dl: [1280, 420], nl: [390, 640], side: [248, 600] };
  function parse(s) { const o = {}; s.split(';').forEach(p => { const [k, v] = p.split('='); if (k) o[k.trim()] = v === undefined ? true : v.trim(); }); return o; }
  function mount(el) {
    const o = parse(el.dataset.f), sc = parseFloat(el.dataset.s || '1'), dm = DIMS[o.w || 'd'];
    el.style.width = Math.round(dm[0] * sc) + 'px'; el.style.height = Math.round(dm[1] * sc) + 'px';
    el.innerHTML = '<div class="in" style="transform:scale(' + sc + ');width:' + dm[0] + 'px;height:' + dm[1] + 'px">' + frame(o) + '</div>';
  }
  document.querySelectorAll('[data-f]').forEach(mount);
  window.AISG = { frame, parse, DIMS, mount };
})();
