/* pd-kit.js — the builders every frame in this round is made of.

   Round slug `phone-downloads`, 2026-11-09.

   One builder per surface, every number pulled through window.PD (pd-data.js) and every string
   through t(). A variant frame is therefore visibly the SAME call with one option changed, which
   is what lets a reviewer ask "do these pictures agree?" instead of trusting that they do.

   ES5 register, to match the rest of design/ravilo/. No framework, no network, no SVG <defs> —
   every icon is a plain stroke path, so the "mint every SVG id from a counter" trap cannot
   apply: there is no id to resolve and nothing painted from a hidden subtree.
*/
(function () {
  'use strict';

  var D = window.PD;
  var LANG = 'en';

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function t(key, vars) {
    var tab = D.S[LANG] || D.S.en;
    var s = tab[key];
    if (s == null) s = D.S.en[key];
    if (s == null) return '«' + key + '»';
    if (vars) Object.keys(vars).forEach(function (k) { s = s.replace('{' + k + '}', vars[k]); });
    return s;
  }
  function setLang(l) { LANG = (D.S[l] ? l : 'en'); }

  /* ---- icons. One 24x24 box and one stroke weight for all of them. ------------------------ */
  var I = {
    // the download glyph: an arrow into a line. The mark is the same idea at 12 px.
    dl:      '<path d="M12 4v10"/><path d="m7.5 10.5 4.5 4.5 4.5-4.5"/><path d="M4.5 19.5h15"/>',
    markg:   '<path d="M12 3.5v10"/><path d="m7 9 5 5 5-5"/><path d="M4 20h16"/>',
    queue:   '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    check:   '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    warn:    '<path d="M12 4.5 2.8 20h18.4z"/><path d="M12 10v4.5"/><path d="M12 17.4h.01"/>',
    play:    '<path d="M7.5 4.8v14.4L19.5 12z" fill="currentColor" stroke="none"/>',
    trash:   '<path d="M4.5 7h15"/><path d="M9.5 7V4.5h5V7"/><path d="M6.5 7l1 13h9l1-13"/>',
    pause:   '<path d="M9 5.5v13"/><path d="M15 5.5v13"/>',
    x:       '<path d="M6 6l12 12"/><path d="M18 6 6 18"/>',
    wifi:    '<path d="M3 8.5a14 14 0 0 1 18 0"/><path d="M6.5 12.5a9 9 0 0 1 11 0"/><path d="M10 16.5a4 4 0 0 1 4 0"/><path d="M12 20h.01"/>',
    nowifi:  '<path d="M3 8.5a14 14 0 0 1 11.5-3.4"/><path d="M6.5 12.5a9 9 0 0 1 5.3-2.4"/><path d="M10 16.5a4 4 0 0 1 4 0"/><path d="M12 20h.01"/><path d="M4 4l16 16"/>',
    server:  '<rect x="3.5" y="4.5" width="17" height="6" rx="2"/><rect x="3.5" y="13.5" width="17" height="6" rx="2"/><path d="M7.5 7.5h.01"/><path d="M7.5 16.5h.01"/>',
    chev:    '<path d="m9.5 5.5 7 6.5-7 6.5"/>',
    kebab:   '<circle cx="12" cy="5.2" r="1.5" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/><circle cx="12" cy="18.8" r="1.5" fill="currentColor" stroke="none"/>',
    back:    '<path d="M19 12H5.5"/><path d="m11 5.5-5.5 6.5 5.5 6.5"/>',
    select:  '<rect x="4" y="4" width="16" height="16" rx="4"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
    disk:    '<rect x="3" y="4.5" width="18" height="15" rx="3"/><path d="M3 14h18"/><path d="M7 17.2h.01"/><path d="M10.5 17.2h.01"/>',
    info:    '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5"/><path d="M12 7.7h.01"/>',
    home:    '<path d="M4 10.5 12 4l8 6.5"/><path d="M6 9.8V20h12V9.8"/>',
    lib:     '<rect x="3.5" y="4.5" width="7" height="15" rx="2"/><rect x="13.5" y="4.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="6" rx="2"/>',
    search:  '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/>',
    disc:    '<circle cx="12" cy="12" r="8.5"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
    plus:    '<path d="M12 5.5v13"/><path d="M5.5 12h13"/>',
    gear:    '<circle cx="12" cy="12" r="3"/><path d="M12 3.5v2.2M12 18.3v2.2M4.9 7.8l1.9 1.1M17.2 15.1l1.9 1.1M4.9 16.2l1.9-1.1M17.2 8.9l1.9-1.1"/>',
    chat:    '<path d="M4.5 6a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H10l-4 4v-4H6.5a2 2 0 0 1-2-2z"/>',
    user:    '<circle cx="12" cy="8" r="3.8"/><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6"/>'
  };
  function ic(name, cls) {
    return '<svg class="pd-ico ' + (cls || '') + '" viewBox="0 0 24 24" aria-hidden="true">' + I[name] + '</svg>';
  }

  /* ---- chrome ----------------------------------------------------------------------------- */
  function sb() {
    return '<div class="pd-sb"><span>' + esc(D.DEV.clock) + '</span>'
      + '<span class="pd-ic">LTE ▮▮▮ 86%</span></div>';
  }

  /* The five shipped slots, never a sixth: Downloads is reached from Profile. `on` is the lit
     one; the gradient pill is positioned over that slot by its index. */
  var NAV = [
    { k: 'nav.home', ico: 'home' }, { k: 'nav.library', ico: 'lib' },
    { k: 'nav.search', ico: 'search' }, { k: 'nav.discover', ico: 'disc' },
    { k: 'nav.profile', ico: null }
  ];
  function nav(on) {
    var slot = 412 / 5;                       // 82.4 dp per slot
    var left = Math.round(on * slot + (slot - 56) / 2);
    return '<div class="pd-nav"><div class="pd-nrow">'
      + '<i class="pd-nind" style="left:' + left + 'px"></i>'
      + NAV.map(function (n, i) {
        var inner = n.ico ? ic(n.ico) : '<span class="pd-av">ER</span>';
        return '<button class="pd-n' + (i === on ? ' on' : '') + '"><span class="p">' + inner
          + '</span><b>' + esc(t(n.k)) + '</b></button>';
      }).join('')
      + '</div><div class="pd-nsafe"></div></div>';
  }

  function grad(title) { return window.RAVILO.grad(title); }
  function ini(title) { return window.RAVILO.initials(title); }
  function nEps(n) { return n + ' ' + (n === 1 ? t('ep.episode_one') : t('ep.episodes')).toLowerCase(); }

  /* Episode titles come from the shipped corpus. For a series the corpus lists by hand
     (Nordvest) that is the hand-written title; for one it generates (Havets Hjarta) the
     generator draws from the same pool the round's FILM titles come from, so it can hand back
     `Glasberget` — the name of the 4K film on the sheet two frames earlier. One name meaning
     two things inside one package is unreadable, so a generated title that collides with a
     title this round already uses steps to the next one. The corpus is not edited. */
  function epsFor(title, seasonIx) {
    var used = {};
    D.FILMS.forEach(function (f) { used[f.title] = 1; });
    D.CARDS.continue.concat(D.CARDS.films).forEach(function (c) { used[c.title] = 1; });
    D.SERIES.forEach(function (s) { used[s.title] = 1; });
    used[D.FILMPAGE.title] = 1;
    Object.keys(D.SHEETS).forEach(function (k) { used[D.SHEETS[k].title] = 1; });
    var list = window.RAVILO.episodesFor({ title: title, kind: 'series' }, seasonIx);
    var taken = {};
    return list.map(function (e, i) {
      if (!used[e.title] && !taken[e.title]) { taken[e.title] = 1; return e; }
      for (var j = i + 1; j < list.length + 8; j++) {
        var alt = list[j % list.length];
        if (!used[alt.title] && !taken[alt.title]) {
          taken[alt.title] = 1;
          return { n: e.n, title: alt.title, dur: e.dur, pct: e.pct };
        }
      }
      return e;
    });
  }

  /* ---- the download control, both directions, five states (FR-8) -------------------------
     `state` is one of: dl | queued | pct | done | failed. `pct` carries the number. */
  function ctlBits(state, pct) {
    if (state === 'queued') return { cls: 'queued', icon: ic('queue'), lbl: t('download.queued') };
    if (state === 'pct')    return { cls: 'running', icon: '', lbl: pct + ' %', fill: pct };
    if (state === 'done')   return { cls: 'done', icon: ic('check'), lbl: t('download.downloaded') };
    if (state === 'failed') return { cls: 'failed', icon: ic('warn'), lbl: t('download.failed_retry') };
    return { cls: '', icon: ic('dl'), lbl: t('download.action') };
  }
  /* Direction A — a third item in the shipped action row. My List loses its label, because
     three labelled 44 dp controls do not fit 372 dp. */
  function btnsA(state, pct) {
    var b = ctlBits(state, pct);
    return '<div class="pd-dbtns a">'
      + '<button class="pd-btn play">' + ic('play') + ' ' + esc(t('action.play')) + '</button>'
      + '<button class="pd-dl ' + b.cls + '">'
      + (b.fill ? '<i class="pd-fill" style="width:' + b.fill + '%"></i>' : '')
      + '<span class="pd-lbl">' + b.icon + esc(b.lbl) + '</span></button>'
      + '<button class="pd-mylist-ico">＋</button>'
      + '</div>';
  }
  /* Direction B — the shipped row untouched, the control full width underneath. */
  function btnsB(state, pct) {
    var b = ctlBits(state, pct);
    return '<div class="pd-dbtns">'
      + '<button class="pd-btn play">' + ic('play') + ' ' + esc(t('action.play')) + '</button>'
      + '<button class="pd-btn ghost">' + esc(t('action.my_list')) + '</button>'
      + '</div>'
      + '<button class="pd-dlfull ' + b.cls + '">'
      + (b.fill ? '<i class="pd-fill" style="width:' + b.fill + '%"></i>' : '')
      + '<span class="pd-lbl">' + b.icon + esc(b.lbl) + '</span></button>';
  }

  /* ---- the film page --------------------------------------------------------------------- */
  function filmPage(o) {
    var f = D.FILMPAGE;
    var btns = o.dir === 'a' ? btnsA(o.state, o.pct) : btnsB(o.state, o.pct);
    return '<div class="pd-detail">'
      + '<div class="pd-dhero" style="background:' + grad(f.title) + '">'
      +   '<div class="pd-scrim"></div></div>'
      + '<button class="pd-back2">' + ic('back') + '</button>'
      + '<div class="pd-dbody">'
      +   '<div class="pd-dtitle">' + esc(f.title) + '</div>'
      +   '<div class="pd-dmeta"><span>' + f.year + '</span><span class="pg">' + esc(f.rating)
      +     '</span><span>' + esc(t('meta.runtime',
              { h: Math.floor(f.runtimeMin / 60), m: f.runtimeMin % 60 })) + '</span><span>' + esc(f.genres.join(' · ')) + '</span></div>'
      +   btns
      +   '<div class="pd-dsyn">' + esc(t('film.syn')) + '</div>'
      + '</div></div>';
  }

  /* ---- the quality sheet (FR-3) ----------------------------------------------------------
     Drawn over the page it was opened from, because a sheet that hides its own context is a
     defect round 8 shipped once already. */
  function sheet(key, opt) {
    opt = opt || {};
    var s = D.SHEETS[key];
    var sel = opt.sel || s.pre;
    var rows = '', notes = '';
    if (opt.loading) {
      rows = '<div class="pd-qskel"></div><div class="pd-qskel"></div><div class="pd-qskel"></div>';
    } else {
      s.rows.forEach(function (r) {
        var room = D.roomFor(r.mb);
        var sizeTxt = r.exact ? D.size(r.mb) : t('download.about_size', { size: D.size(r.mb) });
        var label = r.q === 'original' ? t('download.original') : r.label;
        rows += '<div class="pd-qrow' + (room && r.q === sel ? ' on' : '') + (room ? '' : ' noroom') + '">'
          + '<span class="pd-radio"></span>'
          + '<span class="pd-qn"><b>' + esc(label) + '</b>'
          + (r.sub ? '<span>' + esc(r.sub) + '</span>' : '') + '</span>'
          + '<span class="pd-qs' + (r.exact ? '' : ' est') + '">' + esc(sizeTxt) + '</span></div>';
        if (!room) {
          rows += '<div class="pd-qnoroom">' + ic('warn') + '<span>'
            + t('download.no_room', { size: '<b>' + D.size(D.shortfallMb(r.mb)) + '</b>' })
            + '</span></div>';
        }
      });
    }
    var chosen = s.rows.filter(function (r) { return r.q === sel; })[0] || s.rows[0];
    var goMb = chosen.mb;
    if (s.kind === 'season') {
      /* FR-5's rows show the SEASON's total per quality, and FR-5 queues only the episodes not
         already on the phone. The button therefore cannot carry the row's number: it names what
         pressing it will actually download. The line above says why the two differ. */
      var left = Math.round((s.episodes - s.have) * (chosen.mb / s.episodes));
      goMb = left;
      notes = '<div class="pd-shave">' + esc(t('download.season_have',
        { have: s.have, all: s.episodes, size: D.size(left) })) + '</div>';
    }
    var goSize = (chosen.exact && s.kind !== 'season')
      ? D.size(goMb) : t('download.about_size', { size: D.size(goMb) });
    var sub = s.kind === 'season'
      ? t('ep.season', { n: s.season }) + ' · ' + nEps(s.episodes)
      : s.src;
    return '<div class="pd-scrim2"></div><div class="pd-sheet">'
      + '<div class="pd-grab"></div>'
      + '<h4>' + esc(t('download.sheet_title')) + '</h4>'
      + '<div class="pd-ssub">' + esc(s.title) + ' · ' + esc(sub) + '</div>'
      + '<div class="pd-srows">' + rows + '</div>'
      + (opt.loading
        ? '<div class="pd-sload">' + ic('server') + esc(t('download.loading')) + '</div>'
        : '<div class="pd-sfree">' + ic('disk') + esc(t('download.free_space', { size: D.size(D.DEV.freeMb) })) + '</div>')
      + notes
      + '<button class="pd-sgo"' + (opt.loading ? ' disabled style="opacity:.45"' : '') + '>'
      + ic('dl') + esc(opt.loading ? t('download.action') : t('download.sheet_button', { size: goSize }))
      + '</button></div>';
  }

  /* ---- the season page (FR-5, FR-8) ------------------------------------------------------ */
  function seasonPage() {
    var eps = epsFor('Nordvest', 0);
    var have = { 1: 'mark', 2: 'mark', 3: 'mark', 4: 'mark', 5: 'running', 6: 'queued' };
    var watched = { 1: true, 2: true };
    var rows = eps.map(function (e) {
      var st = have[e.n], extra = '';
      if (st === 'running') {
        extra = '<div class="st run">' + ic('dl') + '<span>42 %</span></div>'
          + '<div class="pd-epbar"><i style="width:42%"></i></div>';
      } else if (st === 'queued') {
        extra = '<div class="st">' + ic('queue') + '<span>' + esc(t('download.queued')) + '</span></div>';
      } else if (st === 'mark') {
        /* The thumb already carries the mark; the line carries the words and the facts. Two
           copies of the same badge on one 59 dp row is noise, which the first render showed. */
        extra = '<div class="st"><span>'
          + esc(t('download.downloaded')) + ' · ' + esc(D.QLABEL.medium) + ' · ' + esc(D.size(650))
          + '</span></div>';
      }
      return '<div class="pd-eprow"><div class="pd-epthumb" style="background:' + grad(e.title + e.n) + '">'
        + '<span class="pd-mn">' + (e.n < 10 ? '0' + e.n : e.n) + '</span>'
        + (st === 'mark' ? '<span class="pd-mark sm">' + ic('markg') + '</span>' : '')
        + (watched[e.n] ? '<span class="pd-wk">✓</span>' : '')
        + '</div><div class="pd-epmeta"><div class="t">' + esc(e.title) + '</div>'
        + '<div class="d">' + esc(e.dur) + '</div>' + extra + '</div></div>';
    }).join('');
    return '<div class="pd-detail">'
      + '<div class="pd-dhero" style="height:230px;background:' + grad('Nordvest') + '">'
      +   '<div class="pd-scrim"></div></div>'
      + '<button class="pd-back2">' + ic('back') + '</button>'
      + '<div class="pd-dbody">'
      +   '<div class="pd-dtitle">Nordvest</div>'
      +   '<div class="pd-dmeta"><span>' + esc(t('ep.season', { n: 1 })) + '</span><span class="pg">16</span>'
      +     '<span>' + esc(nEps(8)) + '</span><span>2023</span></div>'
      +   '<div class="pd-seclbl">' + esc(t('ep.episodes'))
      +     '<button class="pd-seasonbtn">' + ic('dl') + esc(t('download.season')) + '</button></div>'
      +   rows
      + '</div></div>';
  }

  /* ---- the Downloads page (FR-9 to FR-12) ------------------------------------------------
     `mode` picks the state. Every total comes from PD, so the bar and the rows under it are
     the same arithmetic. */
  function spaceBlock(withQueue, legendList) {
    var rav = D.raviloMb(withQueue), oth = D.DEV.otherAppsMb, fre = D.DEV.freeMb;
    var bar = '<div class="pd-bar3">'
      + '<i class="rav" style="flex:' + rav + '"></i>'
      + '<i class="oth" style="flex:' + oth + '"></i>'
      + '<i class="fre" style="flex:' + fre + '"></i></div>';
    var items = [
      { c: 'rav', k: 'download.seg_ravilo', v: rav },
      { c: 'oth', k: 'download.seg_other', v: oth },
      { c: 'fre', k: 'download.seg_free', v: fre }
    ];
    var legend = legendList
      ? '<div class="pd-leglist">' + items.map(function (x) {
          return '<div class="pd-lr"><span class="pd-dot ' + x.c + '"></span>'
            + esc(t(x.k)) + '<span class="pd-lv">' + esc(D.gb(x.v)) + '</span></div>';
        }).join('') + '</div>'
      : '<div class="pd-leg">' + items.map(function (x) {
          return '<span class="pd-li"><span class="pd-dot ' + x.c + '"></span>' + esc(t(x.k))
            + ' <b>' + esc(D.gb(x.v)) + '</b></span>';
        }).join('') + '</div>';
    return '<div class="pd-space"><div class="pd-sptitle">' + esc(t('download.space_title')) + '</div>'
      + bar + legend + '</div>'
      + '<div class="pd-others"><span class="pd-on">' + esc(t('download.other_profiles'))
      + '<span>' + esc(D.gb(D.DEV.otherProfilesMb)) + '</span></span>'
      + '<button class="pd-odel">' + esc(t('download.delete')) + '</button></div>';
  }

  function filmEntry(f, o) {
    o = o || {};
    var meta = '<span class="q">' + esc(D.QLABEL[f.q]) + '</span><span>·</span><span>'
      + esc(D.size(f.mb)) + '</span>';
    if (f.watched) meta += '<span class="pd-ew"><i>✓</i>' + esc(t('action.watched')) + '</span>';
    var extra = '', ctl = '';
    if (f.pct) extra = '<div class="pd-epr"><i style="width:' + f.pct + '%"></i></div>';
    if (f.gone) extra += '<div class="pd-egone">' + ic('info') + esc(t('download.gone')) + '</div>';
    /* A queued film: nothing is on the disk yet, so the size is the size it WILL take and the
       waiting reason is the row's own line. Cancel is the only control a waiting entry has. */
    if (f.state === 'wifi' || f.state === 'server') {
      /* Nothing on the disk yet, so no size is claimed: the brief's stand-in gives Arvur a
         quality and no size, and inventing one would put a number in a picture that no array
         carries. The sheet is where the size was shown; the queue shows what it is waiting for. */
      meta = '<span class="q">' + esc(D.QLABEL[f.q]) + '</span>'
        + (f.mb ? '<span>·</span><span>' + esc(D.size(f.mb)) + '</span>' : '');
      extra = '<div class="pd-ewait">' + ic(f.state === 'wifi' ? 'nowifi' : 'server')
        + esc(t(f.state === 'wifi' ? 'download.waiting_wifi' : 'download.waiting_server')) + '</div>';
      ctl = '<div class="pd-ectl"><button title="cancel">' + ic('x') + '</button></div>';
    }
    return '<div class="pd-entry' + (o.swiped ? ' swiped' : '') + '">'
      + (o.swiped ? '<div class="pd-edel">' + ic('trash') + esc(t('download.delete')) + '</div>' : '')
      + '<div class="pd-erow' + (o.picked ? ' picked' : '') + '">'
      + (o.select ? '<span class="pd-pick">' + ic('check') + '</span>' : '')
      + '<div class="pd-eposter" style="background:' + grad(f.title) + '">'
      /* No downloaded mark on this page: everything here is on the phone by definition, and a
         badge that is always present on every row carries no information. The mark's job is on
         Home, in Library and on an episode row (FR-8). */
      + '<span class="ini">' + esc(ini(f.title)) + '</span></div>'
      + '<div class="pd-emeta"><div class="pd-et">' + esc(f.title) + '</div>'
      + '<div class="pd-em">' + meta + '</div>' + extra + '</div>' + ctl + '</div></div>';
  }

  function epEntry(s, e, o) {
    o = o || {};
    var num = 'S' + (s.season < 10 ? '0' + s.season : s.season) + 'E' + (e.n < 10 ? '0' + e.n : e.n);
    var eps = epsFor(s.title, s.season - 1);
    var ep = eps[e.n - 1] || { title: num, dur: '' };
    var meta = '<span class="q">' + esc(D.QLABEL[e.q]) + '</span><span>·</span><span>'
      + esc(D.size(e.mb)) + '</span>';
    if (e.watched) meta += '<span class="pd-ew"><i>✓</i>' + esc(t('action.watched')) + '</span>';
    var extra = '', ctl = '';
    if (e.state === 'running') {
      meta = '<span class="q">' + esc(D.QLABEL[e.q]) + '</span><span>·</span><span>'
        + esc(D.size(D.queueOnDiskMb(e)) + ' / ' + D.size(e.mb)) + '</span>';
      extra = '<div class="pd-run"><div class="pd-rl">' + ic('dl') + '<span>' + e.dl + ' %</span></div>'
        + '<div class="pd-rb"><i style="width:' + e.dl + '%"></i></div></div>';
      ctl = '<div class="pd-ectl"><button title="pause">' + ic('pause') + '</button>'
        + '<button title="cancel">' + ic('x') + '</button></div>';
    } else if (e.state === 'wifi') {
      meta = '<span class="q">' + esc(D.QLABEL[e.q]) + '</span><span>·</span><span>'
        + esc(D.size(e.sizeMb || 0)) + '</span>';
      extra = '<div class="pd-ewait">' + ic('nowifi') + esc(t('download.waiting_wifi')) + '</div>';
      ctl = '<div class="pd-ectl"><button title="cancel">' + ic('x') + '</button></div>';
    } else if (e.state === 'server') {
      extra = '<div class="pd-ewait">' + ic('server') + esc(t('download.waiting_server')) + '</div>';
    }
    return '<div class="pd-entry' + (o.swiped ? ' swiped' : '') + '">'
      + (o.swiped ? '<div class="pd-edel">' + ic('trash') + esc(t('download.delete')) + '</div>' : '')
      + '<div class="pd-erow' + (o.picked ? ' picked' : '') + '">'
      + (o.select ? '<span class="pd-pick">' + ic('check') + '</span>' : '')
      + '<div class="pd-ethumb" style="background:' + grad(ep.title + e.n) + '">'
      + '<span class="pd-mn">' + (e.n < 10 ? '0' + e.n : e.n) + '</span>'
      + (e.watched ? '<span class="pd-wk">✓</span>' : '')
      + '</div><div class="pd-emeta"><div class="pd-et">' + num + ' · ' + esc(ep.title) + '</div>'
      + '<div class="pd-em">' + meta + '</div>' + extra + '</div>' + ctl + '</div></div>';
  }

  /* A whole series, header first (its header deletes the series, FR-10), then its episodes. */
  function seriesGroup(s, o) {
    o = o || {};
    var mb = D.seriesMb(s) + (o.queue || []).reduce(function (a, q) { return a + D.queueOnDiskMb(q); }, 0);
    var eps = s.eps.concat(o.queue || []);
    return '<div class="pd-group">'
      + '<div class="pd-ghead">'
      + (o.select ? '<span class="pd-pick">' + ic('check') + '</span>' : '')
      + '<div class="pd-eposter" style="background:' + grad(s.title) + '">'
      + '<span class="ini">' + esc(ini(s.title)) + '</span></div>'
      + '<div class="pd-emeta"><div class="pd-et">' + esc(s.title) + '</div>'
      + '<div class="pd-em"><span>' + esc(t('ep.season', { n: s.season })) + '</span><span>·</span>'
      + '<span>' + esc(nEps(eps.length)) + '</span><span>·</span>'
      + '<span class="q">' + esc(D.gb(mb)) + '</span></div></div></div>'
      + '<div class="pd-gseason">' + esc(t('ep.season', { n: s.season })) + '</div>'
      + eps.map(function (e) { return epEntry(s, e, { select: o.select, picked: o.pickEp === e.n }); }).join('')
      + '</div>';
  }

  function downloadsPage(mode, opt) {
    opt = opt || {};
    var running = mode === 'running' || mode === 'launch-running';
    var yours = D.yoursMb(running);
    var select = mode === 'selecting';
    var head;
    if (select) {
      /* The picked set is DECLARED, so the count in the bar, the ticked rows and the size the
         Delete carries are one fact: the first two films and the first episode of Nordvest. */
      var picked = [D.FILMS[0], D.FILMS[1], D.SERIES[0].eps[0]];
      var pickedMb = picked.reduce(function (a, x) { return a + x.mb; }, 0);
      /* Delete says what it frees. The owner's whole ask is being in control of the space, and
         a destructive button that does not name its own number makes him go and add it up. */
      head = '<div class="pd-bar sel"><button class="pd-txtbtn">' + esc(t('action.cancel')) + '</button>'
        + '<h2>' + esc(t('download.n_selected', { n: picked.length })) + '</h2>'
        + '<button class="pd-txtbtn danger" style="margin-left:auto">'
        + esc(t('download.delete')) + ' · ' + esc(D.gb(pickedMb)) + '</button></div>';
    } else {
      /* An empty page has no total to state and nothing for the overflow to act on: both are
         left off rather than shown as 0.0 GB and three dead rows. */
      var bare = mode === 'empty';
      head = '<div class="pd-bar"><button class="pd-back">' + ic('back') + '</button>'
        + '<h2>' + esc(t('download.page_title')) + '</h2>'
        + (bare ? '' : '<span class="pd-btot">' + esc(D.gb(yours)) + '</span>'
          + '<button class="pd-kebab">' + ic('kebab') + '</button>') + '</div>';
    }

    var body = '';
    if (mode === 'empty') {
      body = '<div class="pd-empty"><span class="pd-eico">' + ic('dl') + '</span>'
        + '<h3>' + esc(t('download.empty_title')) + '</h3>'
        + '<p>' + esc(t('download.empty')) + '</p></div>';
    } else {
      if (opt.offline) {
        body += '<div class="pd-offline">' + ic('nowifi') + '<span>' + esc(t('download.offline')) + '</span></div>';
      }
      body += spaceBlock(running, !!opt.legendList);
      if (opt.topacts) {
        body += '<div class="pd-topacts">'
          + '<button>' + ic('select') + esc(t('download.select')) + '</button>'
          + '<button>' + ic('check') + esc(t('download.delete_watched')) + '</button>'
          + '<button class="danger">' + ic('trash') + esc(t('download.delete_all')) + '</button></div>';
        /* The alternative cannot carry the sizes: three labelled buttons across 376 dp leave no
           room for a number, which is itself part of the answer to open decision 3. */
      }
      var filmsMb = D.FILMS.reduce(function (a, f) { return a + f.mb; }, 0);
      body += '<div class="pd-sec"><b>' + esc(t('row.films')) + '</b><span>' + esc(D.gb(filmsMb)) + '</span></div>';
      body += D.FILMS.map(function (f, i) {
        return filmEntry(f, {
          swiped: opt.swipe === i,
          select: select, picked: select && i < 2
        });
      }).join('');
      var seriesMb = D.SERIES.reduce(function (a, s) { return a + D.seriesMb(s); }, 0)
        + (running ? D.queueOnDiskMb(D.QUEUE[0]) : 0);
      body += '<div class="pd-sec"><b>' + esc(t('row.series')) + '</b><span>' + esc(D.gb(seriesMb)) + '</span></div>';
      body += D.SERIES.map(function (s, i) {
        var q = (running && i === 0) ? [D.QUEUE[0]] : [];
        return seriesGroup(s, { select: select, queue: q, pickEp: (select && i === 0) ? 1 : 0 });
      }).join('');
      if (running) {
        /* Arvur's download has not started, so none of its bytes are on the disk and it cannot
           sit in Films with a size it does not yet take. It is the queue's tail, under its own
           head, with the size it WILL take and the reason it is waiting. */
        body += '<div class="pd-sec"><b>' + esc(t('download.queued')) + '</b><span></span></div>'
          + filmEntry(D.QUEUE[1], {});
      }
    }

    var extra = '';
    if (mode === 'overflow') {
      /* Both destructive rows name what they free. The owner asked to be in control of the
         space; a menu that makes him open the page and add the sizes up is not that. */
      extra = '<div class="pd-menu">'
        + '<button>' + ic('select') + esc(t('download.select')) + '</button>'
        + '<button>' + ic('check') + esc(t('download.delete_watched'))
        + '<span class="pd-mr">' + esc(D.gb(D.watchedMb())) + '</span></button>'
        + '<button class="danger">' + ic('trash') + esc(t('download.delete_all'))
        + '<span class="pd-mr">' + esc(D.gb(yours)) + '</span></button></div>';
    }
    if (mode === 'confirm') {
      extra = '<div class="pd-scrim2"></div><div class="pd-msheet"><div class="pd-grab"></div>'
        + '<div class="pd-confirm"><h4>' + esc(t('download.delete_all_title')) + '</h4>'
        + '<p>' + esc(t('download.delete_all_confirm', { size: D.gb(yours) })) + '</p>'
        + '<div class="pd-acts"><button class="go">' + esc(t('download.delete_all')) + '</button>'
        + '<button class="no">' + esc(t('action.cancel')) + '</button></div></div></div>';
    }
    /* `opt.scroll` is the page scrolled down by that many dp. A list page is taller than a
       phone, and a state that lives 700 dp down — a running download, under the storage block
       and the films — is not reviewable from the top of the page alone. The frame is the same
       builder with the body offset; nothing is rearranged to make a picture. */
    var inner = opt.scroll
      ? '<div style="margin-top:-' + opt.scroll + 'px">' + body + '</div>'
      : body;
    return '<div class="pd-page">' + head + '<div class="pd-body">' + inner + '</div></div>'
      + nav(4) + extra;
  }

  /* ---- Profile (FR-8), Settings (FR-17), Home unreachable (FR-11) ------------------------- */
  function profilePage() {
    var yours = D.yoursMb(false);
    return '<div class="pd-page"><div class="pd-appbar"><span class="pd-wm">Ravilo</span>'
      + '<span class="sp"></span></div>'
      + '<div class="pd-body">'
      + '<div class="pd-pid"><span class="pd-pav">ER</span><div>'
      + '<div class="pd-pnm">' + esc(D.DEV.profile) + '</div>'
      + '<div class="pd-psub">' + esc(t('profile.signed_in')) + '</div></div></div>'
      + '<div class="pd-acsec">' + esc(t('profile.account')) + '</div>'
      + '<div class="pd-grp">'
      + '<div class="pd-prow">' + ic('user', 'ic') + '<span class="pd-pr">'
      + esc(t('profile.account')) + '</span>' + ic('chev', 'chev') + '</div>'
      + '<div class="pd-prow">' + ic('dl', 'ic') + '<span class="pd-pr">'
      + esc(t('download.page_title')) + '</span><span class="pd-pv">' + esc(D.gb(yours)) + '</span>'
      + ic('chev', 'chev') + '</div>'
      + '<div class="pd-prow">' + ic('gear', 'ic') + '<span class="pd-pr">'
      + esc(t('settings.title')) + '</span>' + ic('chev', 'chev') + '</div>'
      + '<div class="pd-prow">' + ic('chat', 'ic') + '<span class="pd-pr">Support</span>'
      + ic('chev', 'chev') + '</div>'
      + '</div></div></div>' + nav(4);
  }

  function settingsPage() {
    function row(label, sub, on) {
      return '<div class="pd-prow"><span class="pd-pr">' + esc(label)
        + (sub ? '<span>' + esc(sub) + '</span>' : '') + '</span>'
        + '<span class="pd-sw' + (on ? ' on' : '') + '"></span></div>';
    }
    return '<div class="pd-page">'
      + '<div class="pd-bar"><button class="pd-back">' + ic('back') + '</button>'
      + '<h2>' + esc(t('settings.title')) + '</h2></div>'
      + '<div class="pd-body">'
      + '<div class="pd-acsec" style="margin-top:6px">' + esc(t('settings.playback')) + '</div>'
      + '<div class="pd-grp">' + row(t('settings.autoplay_next'), '', true)
      + row(t('settings.show_progress'), '', true) + '</div>'
      + '<div class="pd-acsec">' + esc(t('settings.downloads')) + '</div>'
      + '<div class="pd-grp">' + row(t('settings.wifi_only'), t('settings.wifi_only_sub'), true) + '</div>'
      + '</div></div>' + nav(4);
  }

  function homeUnreachable() {
    return '<div class="pd-page"><div class="pd-appbar"><span class="pd-wm">Ravilo</span>'
      + '<span class="sp"></span><span class="pd-av">ER</span></div>'
      + '<div class="pd-body"><div class="pd-unreach">'
      + '<span class="pd-uico">' + ic('nowifi') + '</span>'
      + '<h3>' + esc(t('error.play.unreachable.title')) + '</h3>'
      + '<p>' + esc(t('error.play.unreachable.body')) + '</p>'
      + '<div class="pd-uacts">'
      + '<button class="go">' + ic('dl') + esc(t('download.watch_downloads')) + '</button>'
      + '<button class="no">' + esc(t('action.retry')) + '</button>'
      + '</div></div></div></div>' + nav(0);
  }

  /* ---- the mark where a viewer meets it (FR-8) -------------------------------------------- */
  function card(c) {
    return '<div class="pd-tile"><div class="pd-poster" style="background:' + grad(c.title) + '">'
      + '<span class="ini">' + esc(ini(c.title)) + '</span>'
      + (c.pb ? '<span class="pb">' + esc(c.pb) + '</span>' : '')
      + (c.mark ? '<span class="pd-mark">' + ic('markg') + '</span>' : '')
      + (c.pct ? '<span class="pbar"><i style="width:' + c.pct + '%"></i></span>' : '')
      + '</div><div class="pd-tlabel">' + esc(c.title) + '</div>'
      + '<div class="pd-tsub">' + c.year + ' · ' + esc(c.genre) + '</div></div>';
  }
  function homeRow() {
    return '<div class="pd-page"><div class="pd-appbar"><span class="pd-wm">Ravilo</span>'
      + '<span class="sp"></span><button class="pd-ib">' + ic('search') + '</button>'
      + '<span class="pd-av">ER</span></div>'
      + '<div class="pd-body nopad">'
      + '<div class="pd-row"><h3>' + esc(t('row.continue')) + '<span class="more">'
      + esc(t('row.see_all')) + '</span></h3><div class="pd-track">'
      + D.CARDS.continue.map(card).join('') + '</div></div>'
      + '<div class="pd-row"><h3>' + esc(t('row.films')) + '<span class="more">'
      + esc(t('row.see_all')) + '</span></h3><div class="pd-track">'
      + D.CARDS.films.map(card).join('') + '</div></div>'
      + '</div></div>' + nav(0);
  }
  function libraryGrid() {
    return '<div class="pd-page"><div class="pd-appbar"><span class="pd-wm">Ravilo</span>'
      + '<span class="sp"></span><button class="pd-ib">' + ic('search') + '</button>'
      + '<span class="pd-av">ER</span></div>'
      + '<div class="pd-body nopad">'
      + '<div class="pd-libbar"><span class="pd-libf"><span class="lb">'
      + esc(t('lib.films')) + '</span>' + ic('chev') + '</span>'
      + '<span class="pd-libf">A–Z</span></div>'
      /* The Library grid at the shipped 3-up width: the mark has to hold on a poster about
         119 dp wide as well as on the row's fixed 118. */
      + '<div class="pd-grid">' + D.CARDS.films.concat(D.CARDS.continue)
        .concat(D.CARDS.films.slice(0, 2)).map(card).join('') + '</div>'
      + '</div></div>' + nav(1);
  }

  /* ---- the magnified geometry study ------------------------------------------------------
     Drawn on the canvas, in dark ink, at 3x — PROCESS.md's round-11 lesson is that a badge
     defect can be invisible at 1x. */
  function markStudy() {
    var poster = '<div class="pd-poster" style="background:' + grad('Brotsjór') + '">'
      + '<span class="ini">BR</span><span class="pb">4K</span>'
      + '<span class="pd-mark">' + ic('markg') + '</span>'
      + '<span class="pbar"><i style="width:40%"></i></span></div>';
    var thumb = '<div class="pd-epthumb" style="background:' + grad('Hvalvík1') + '">'
      + '<span class="pd-mn">01</span>'
      + '<span class="pd-mark sm">' + ic('markg') + '</span>'
      + '<span class="pd-wk">✓</span></div>';
    var glass = '<div class="pd-poster" style="background:' + grad('Brotsjór') + '">'
      + '<span class="ini">BR</span><span class="pb">4K</span>'
      + '<span class="pd-mark glass">' + ic('markg') + '</span>'
      + '<span class="pbar"><i style="width:40%"></i></span></div>';
    return '<div class="pd-study" style="width:1320px">'
      + '<h4>The downloaded mark, at 3x, beside everything it must not fight</h4>'
      + '<p><b>Left</b>, the recommendation: a 118 x 177 poster carrying all four at once — the '
      + 'shipped 4K quality badge (top-right, 7 dp), the Continue progress bar (3 dp, bottom edge), '
      + 'the downloaded mark (bottom-left, 7 dp) and the title initials. <b>Middle</b>, the same '
      + 'mark in the quality badge\u2019s own dark glass rather than white: quieter, and the reason '
      + 'it is not the recommendation is that it then looks like the same kind of fact as 4K. '
      + '<b>Right</b>: a 104 x 59 episode thumb carrying the mark (bottom-left, 5 dp) beside the '
      + 'shipped green watched tick (bottom-right, 5 dp). The mark is a chip with an arrow, never a '
      + 'green circle, because that shape and that colour already mean watched — an episode that is '
      + 'both downloaded and watched has to read as both, which is what the right-hand pair '
      + 'proves.</p>'
      + '<div class="pd-zrow">'
      + '<div class="pd-zc"><b>poster · 3x</b><div class="pd-z3 poster3"><div class="pd-zin">'
      + poster + '</div></div>'
      + '<div class="pd-dim">mark 20 x 20 dp · radius 7 · inset 7 dp bottom-left\n'
      + 'glyph 12 x 12 · stroke 2.4 · #0a0c13 on #f3f4fb\n'
      + 'clears the 3 dp progress bar by 4 dp\n'
      + 'badge .pb stays top-right, untouched</div></div>'
      + '<div class="pd-zc"><b>alternative · dark glass</b><div class="pd-z3 poster3"><div class="pd-zin">'
      + glass + '</div></div>'
      + '<div class="pd-dim">same 20 x 20 dp box and inset\n'
      + 'rgba(8,10,16,.72) + white ink, matching .pb\n'
      + 'quieter, and reads as the same KIND of fact\n'
      + 'as the 4K badge — which is the argument against</div></div>'
      + '<div class="pd-zc"><b>episode thumb · 3x</b><div class="pd-z3 thumb3"><div class="pd-zin">'
      + thumb + '</div></div>'
      + '<div class="pd-dim">mark 18 x 18 dp · radius 6 · inset 5 dp bottom-left\n'
      + 'watched tick 18 x 18 · #2dd49a · inset 5 dp bottom-right\n'
      + 'both visible together, neither overlapping\n'
      + 'episode number keeps top-left</div></div>'
      + '</div></div>';
  }

  window.PDKit = {
    esc: esc, t: t, setLang: setLang, ic: ic, sb: sb, nav: nav,
    filmPage: filmPage, btnsA: btnsA, btnsB: btnsB, sheet: sheet, seasonPage: seasonPage,
    downloadsPage: downloadsPage, profilePage: profilePage, settingsPage: settingsPage,
    homeUnreachable: homeUnreachable, homeRow: homeRow, libraryGrid: libraryGrid,
    markStudy: markStudy, spaceBlock: spaceBlock, filmEntry: filmEntry
  };
})();
