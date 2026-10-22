/* continue-hidden-tags — decided round. Renders every frame of the canvas and of the PNG export.
   Decided: the rule is a tag condition on the Continue Watching system row in the layout (rows)
   editor; admin-only; single-card hiding is out of scope; See all hides the same titles silently.
   SH is the single list of named states (PNG name 'continue-hidden-tags-' + id + '.png'). */
(function () {
  const TOTAL = '2 697';
  const TAGS = { 'kids-tv': 27, 'cartoons': 14, 'kidnapping': 8, 'kids': 6, 'børnefilm': 1, 'animated-family-christmas-specials-from-the-archive': 2, 'old-kids-block': 0 };
  const LONG = 'animated-family-christmas-specials-from-the-archive';
  // Olivar's Continue Watching before the filter, in order. tag = what hides it.
  const T = [
    { n: 'Nordvest', ep: 'S01E04', sub: 'S01E04 · 22 min left', p: .55, h: 262 },
    { n: 'Caminandes', ep: 'S02E03', sub: 'S02E03 · Next up', tag: 'kids-tv', h: 96 },
    { n: 'Havets Hjarta', ep: 'S02E01', sub: 'S02E01 · Next up', h: 200 },
    { n: 'Spring', sub: 'Film · 3 min left', p: .94, tag: 'kids-tv', h: 150 },
    { n: 'Cosmos Laundromat', sub: '12 min left', p: .8, h: 28 },
    { n: 'Agent 327', ep: 'S01E05', sub: 'S01E05 · 9 min left', p: .7, tag: 'kids-tv', h: 352 },
    { n: 'Arvur', ep: 'S01E02', sub: 'S01E02 · Next up', h: 14 },
    { n: 'Big Buck Bunny', sub: '4 min left', p: .9, tag: 'cartoons', h: 112 }
  ];
  T.forEach((t, i) => t.i = i + 1);
  const KIDS_ROW = [T[1], T[3]];                       // a row holding only kids-tv titles (Caminandes, Spring)
  const NEWLY = [6, 3, 1, 0, 5, 7, 4, 2].map(i => T[i]); // a Home row that still carries every title
  const U = {
    olivar: { name: 'Olivar Hansen', first: 'Olivar', custom: false },
    eyd: { name: 'Eyð Restorff', first: 'Eyð', custom: true },
    kids: { name: 'Kids', first: 'Kids', custom: false },
    marjun: { name: 'Marjun í Dali', first: 'Marjun', custom: false }
  };
  const HIDE = { home: ['kids-tv'], eyd: ['kids-tv', 'cartoons'], olivar: ['cartoons'] };
  const filt = (list, hide) => list.filter(t => !hide.includes(t.tag));

  /* ---------------- new strings (en / da / fo — da and fo are drafts) ---------------- */
  const S = {
    en: { facet: 'Hide titles tagged', add: 'Add tag', ph: 'Search {n} tags…', res: '{n} tags contain “{q}”', hint: 'Hides the exact tag only — never tags that merely contain it.',
      cnt1: '1 title', cntN: '{n} titles', none: 'No tag called “{q}”.', none2: 'Tags come from your library — nothing is created here.', remove: 'Remove {tag}', top: 'Most used tags' },
    da: { facet: 'Skjul titler med tagget', add: 'Tilføj tag', ph: 'Søg i {n} tags…', res: '{n} tags indeholder “{q}”', hint: 'Skjuler kun det præcise tag — aldrig tags, der blot indeholder det.',
      cnt1: '1 titel', cntN: '{n} titler', none: 'Intet tag hedder “{q}”.', none2: 'Tags kommer fra dit bibliotek — her oprettes intet.', remove: 'Fjern {tag}', top: 'Mest brugte tags' },
    fo: { facet: 'Fjal heiti merkt við', add: 'Legg frámerki afturat', ph: 'Leita í {n} frámerkjum…', res: '{n} frámerki innihalda “{q}”', hint: 'Fjalir bert akkurat hetta frámerkið — ongantíð frámerki, sum bara innihalda tað.',
      cnt1: '1 heiti', cntN: '{n} heiti', none: 'Einki frámerki eitur “{q}”.', none2: 'Frámerki koma úr savninum — einki verður stovnað her.', remove: 'Strika {tag}', top: 'Mest brúktu frámerki' }
  };
  const SKEY = [
    ['facet', 'Continue row · sub-row label'], ['add', 'Continue row · add button'], ['remove', 'tag chip ✕ · accessible name'],
    ['ph', 'picker · search placeholder'], ['top', 'picker · nothing typed: header'], ['res', 'picker · results header'], ['hint', 'picker · footer'], ['cnt1', 'picker · count, one'], ['cntN', 'picker · count, many'],
    ['none', 'picker · no results'], ['none2', 'picker · no results, second line']
  ];
  const f = (s, o) => s.replace(/\{(\w+)\}/g, (m, k) => (o[k] != null ? o[k] : m));
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const cnt = (n, L) => n === 1 ? L.cnt1 : f(L.cntN, { n });

  /* ================================================================ ADMIN — layout (rows) editor */
  function chip(t, L) {
    const n = TAGS[t];
    return '<span class="tagchip" title="' + esc(t) + ' · ' + cnt(n, L) + '"><span class="nm">' + esc(t) + '</span><span class="n' + (n === 0 ? ' zero' : '') + '">' + n + '</span><span class="x" aria-label="' + esc(f(L.remove, { tag: t })) + '">✕</span></span>';
  }
  function mark(t, q) { const i = t.indexOf(q); return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(q) + '</mark>' + esc(t.slice(i + q.length)); }
  function picker(o, L) {
    const q = o.pick.q.toLowerCase(), sel = o.val || [];
    const items = q ? Object.keys(TAGS).filter(t => t.includes(q)).sort((a, b) => (b.startsWith(q) - a.startsWith(q)) || TAGS[b] - TAGS[a])
      : Object.keys(TAGS).sort((a, b) => TAGS[b] - TAGS[a]).slice(0, 6);   // nothing typed: the most-used tags (Q2 → b)
    let h = '<div class="tagpick"><div class="tp-s"><span style="color:var(--ink-soft)">⌕</span>' + (q ? '<span class="q">' + esc(o.pick.q) + '</span><i class="caret"></i>' : '<i class="caret" style="margin-left:0"></i><span class="ph">' + f(L.ph, { n: TOTAL }) + '</span>') + '</div>';
    if (q && !items.length) h += '<div class="tp-none"><b>' + f(L.none, { q: esc(o.pick.q) }) + '</b><span>' + L.none2 + '</span></div>';
    else h += '<div class="tp-h">' + (q ? f(L.res, { n: items.length, q: esc(q) }) : L.top) + '</div>' + items.map((t, i) => {
      const on = sel.includes(t);
      return '<div class="tp-i' + (on ? ' on' : '') + (o.pick.hov === i ? ' hov' : '') + '"><span class="box">' + (on ? '✓' : '') + '</span><span class="nm">' + mark(t, q) + '</span><span class="n' + (TAGS[t] === 0 ? ' zero' : '') + '">' + cnt(TAGS[t], L) + '</span></div>';
    }).join('');
    return h + '<div class="tp-f">' + L.hint + '</div></div>';
  }
  function scopeBar(g, u) {
    const badge = g ? '<span class="badge ok">applies to everyone</span>' : u.custom ? '<span class="badge warn">custom layout</span>' : '<span class="badge">using global</span>';
    return '<div class="card scope"><div class="row center"><span class="cf-eyebrow">Editing</span><span class="seg-pill"><button class="' + (g ? 'on' : '') + '">Global · all users</button><button class="' + (g ? '' : 'on') + '">A specific user</button></span>' +
      (g ? '' : '<span class="select">' + u.name + ' <span class="muted" style="font-size:.7rem">▾</span></span>') + '<span class="spacer"></span>' + badge + '</div></div>';
  }
  const sysRow = (nm, src, extra) => '<div class="cfg-row' + (extra ? ' hascond' : '') + '"><span class="grab">⠿</span><span class="badge ok">system</span><div class="cm"><div class="nm">' + nm + '</div><div class="src">' + src + '</div></div><span class="muted tiny">show</span><span class="toggle on"></span>' + (extra || '') + '</div>';
  const filterRow = (nm, src) => '<div class="cfg-row"><span class="grab">⠿</span><span class="badge info">filter</span><div class="cm"><div class="nm">' + nm + '</div><div class="src">' + src + '</div></div><span class="muted tiny">show</span><span class="toggle on"></span></div>';
  function adm(o) {
    const L = S[o.lang || 'en'], g = o.scope === 'g', u = g ? null : U[o.user], nar = o.w === 'n';
    const locked = !g && !u.custom && !o.forked;
    if (u && o.forked) u.custom = true;
    let h = '<div class="adm' + (nar ? ' nar' : '') + '" data-theme="' + (o.theme || 'dark') + '" style="width:' + (nar ? 390 : 1000) + 'px">' + scopeBar(g, u);
    if (locked) h += '<div class="card lock"><div class="row center" style="flex-wrap:wrap"><div style="flex:1;min-width:220px"><b>' + u.name + ' uses the global layout</b><div class="tiny muted" style="margin-top:3px;line-height:1.5">Create a custom layout to give ' + u.name + ' a different Ravilo home. It fully replaces the global layout — hero, collections, rows and requests — for them. Behaviour preferences below are separate and keep following your global defaults.</div></div><span class="btn primary">Create custom layout</span></div></div>';
    if (u && u.custom) h += '<div class="card"><div class="note warn" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap"><span style="flex:1;min-width:220px"><b>' + u.name + '</b> has a <b>custom layout</b> that replaces the global <b>layout</b> — global layout changes won’t reach them. Their <b>behaviour preferences</b> are tracked separately below.</span><span class="btn sm ghost">Remove custom layout</span></div></div>';
    if (u && o.forked) u.custom = false;
    const val = o.val || [];
    const cond = '<div class="cond"><span class="cf-facet">' + L.facet + '</span>' + val.map(t => chip(t, L)).join('') + '<span class="tagadd' + (o.pick ? ' open' : '') + '">＋ ' + L.add + '</span>' + (o.pick ? picker(o, L) : '') + '</div>';
    h += '<div class="card' + (locked ? ' scope-dim' : '') + '"><div class="row center"><h3>Content rows</h3><span class="badge info">5–20 rows</span><span class="spacer"></span><span class="btn sm ghost">＋ Add row</span></div><p class="tiny muted" style="margin:6px 0 14px;line-height:1.5"><b>System rows</b> (Continue, Newly Added) sit at the top — toggle or rename them, but they can’t be removed.</p>';
    h += sysRow('Continue Watching', 'Continue + Next Up, merged', cond) + sysRow('Newly Added Movies', 'kind = movie · sort newest') + sysRow('Newly Added Series', 'kind = series · sort newest');
    h += filterRow('Nordic noir', 'tag = “nordic-noir” · 3 hand-picked, then newest first · shows 15') + filterRow('Drama', 'genre = “Drama” · title A → Z') + filterRow('Documentaries', 'genre = “Documentary”');
    return h + '</div></div>';
  }

  /* A collection's own editor — Content rows "Custom" (ravilo-builders.js cf-sysrows). Only a collection
     with its own rows gets its own Continue sub-row; "Same as Home" uses the Home row, tags included. */
  function colEd(o) {
    const L = S.en, nar = o.w === 'n', on = o.cw !== false, val = o.val || [];
    const cond = on ? '<div class="cond"><span class="cf-facet">' + L.facet + '</span>' + val.map(t => chip(t, L)).join('') + '<span class="tagadd' + (o.pick ? ' open' : '') + '">＋ ' + L.add + '</span>' + (o.pick ? picker(o, L) : '') + '</div>' : '';
    return '<div class="adm' + (nar ? ' nar' : '') + '" data-theme="dark" style="width:' + (nar ? 390 : 760) + 'px;' + (o.pick ? 'padding-bottom:170px' : '') + '"><div class="card" style="padding:0"><div class="cf-head"><b>Edit collection</b><span class="badge info">Olivar</span><span class="spacer"></span><span class="muted">✕</span></div><div style="padding:14px 16px 16px">' +
      '<div class="row center" style="flex-wrap:wrap;gap:10px"><span class="cf-eyebrow">Content rows</span><span class="spacer"></span><span class="seg"><span>Same as Home</span><span class="on">Custom</span></span></div>' +
      '<div class="tiny muted" style="margin:7px 0 0;line-height:1.5">By default this channel shows the <b>Home content rows</b>, scoped to it. Switch to <b>Custom</b> to give this channel its own set.</div>' +
      '<div class="cf-sysrows"><div class="cf-eyebrow" style="margin-bottom:2px">System rows</div><div class="tiny muted" style="margin:0 0 9px;line-height:1.5">Time-based rows that pin to the top — not filter-built. Both show <b>only titles from this channel</b>.</div>' +
      '<div class="cf-sysrow' + (on ? ' hascond' : ' off') + '"><span class="badge ok">system</span><div class="cf-sysrow-main"><div class="nm">Continue Watching</div><div class="src">In-progress titles from this collection</div></div><span class="toggle' + (on ? ' on' : '') + '"></span>' + cond + '</div>' +
      '<div class="cf-sysrow"><span class="badge ok">system</span><div class="cf-sysrow-main"><div class="nm">Newly Added</div><div class="src">Newest titles in this collection · Movies + Series</div></div><span class="toggle on"></span></div>' +
      '<div class="cf-sysmerge"><span class="tiny">Merge movies &amp; series into one “Newly Added” row</span><span class="spacer"></span><span class="toggle"></span></div></div>' +
      '<div class="cf-herobox"><div class="cf-eyebrow" style="margin-bottom:8px">Filter rows</div><div class="tiny muted" style="padding:9px 2px">No custom rows yet — add one or more.</div><span class="btn sm ghost">＋ Add row</span></div>' +
      '</div></div></div>';
  }

  /* ================================================================ RAVILO TV — the result (no Ravilo change) */
  const art = t => 'background:hsl(' + t.h + ' 34% 27%)';
  function tland(t, foc, w) {
    return '<div class="rv-t' + (foc ? ' f' : '') + '" style="width:' + (w || 360) + 'px"><div class="art" style="' + art(t) + '"><span class="tt">' + t.n + '</span>' + (t.ep ? '<span class="epb">' + t.ep + '</span>' : '') +
      (t.p ? '<span class="pb"><i style="width:' + Math.round(t.p * 100) + '%"></i></span>' : '') + (foc ? '<span class="play"><span>▶</span></span>' : '') + '</div><div class="lb">' + t.n + '</div><div class="sb">' + t.sub + '</div></div>';
  }
  function tpost(t, foc) { return '<div class="rv-t po' + (foc ? ' f' : '') + '" style="width:210px"><div class="art" style="' + art(t) + '"><span class="tt">' + t.n + '</span></div><div class="lb">' + t.n + '</div></div>'; }
  function bar(av) { return '<div class="rv-bar"><span class="mk"></span>' + ['Home', 'Movies', 'Series', 'My List', 'Discover'].map(n => '<span class="ni' + (n === 'Home' ? ' cur' : '') + '">' + n + '</span>').join('') + '<span class="av">' + av + '</span></div>'; }
  const hero = '<div class="rv-hero"><span class="ph">hero artwork</span><div class="scr"></div><div class="bd"><div class="k">New Season</div><h1>Nordvest</h1><div class="acts"><span class="rv-b pri">▶ Play</span><span class="rv-b">More Info</span></div></div></div>';
  function tvHome(list, av) {
    let h = bar(av) + hero;
    if (list.length) {
      h += '<div class="rv-row" style="top:610px"><div class="rv-rh"><h2>Continue Watching</h2><span class="more">See all ›</span></div><div class="rv-tr">' + list.map((t, i) => tland(t, i === 0)).join('') + '</div></div>';
      h += '<div class="rv-row" style="top:955px"><div class="rv-rh"><h2>Newly Added</h2></div><div class="rv-tr">' + NEWLY.map(t => tpost(t)).join('') + '</div></div>';
    } else h += '<div class="rv-row" style="top:610px"><div class="rv-rh"><h2>Newly Added</h2><span class="more">See all ›</span></div><div class="rv-tr">' + NEWLY.map((t, i) => tpost(t, i === 0)).join('') + '</div></div>';
    return h;
  }
  function stripHtml(all, hide, who) {
    const kept = filt(all, hide);
    return '<div class="strip"><div class="h"><b>The whole row, off-screen part included</b><span>' + who + ' · ' + kept.length + ' card' + (kept.length === 1 ? '' : 's') + (hide.length ? ' · hides ' + hide.join(', ') : ' · hides nothing') + '</span></div><div class="cs">' +
      all.map(t => { const out = hide.includes(t.tag); return '<div class="c' + (out ? ' out' : '') + '"><div class="a" style="' + art(t) + '"><i>' + t.i + '</i>' + t.n + '</div><div class="t">' + (t.tag ? (out ? 'hidden · ' : '') + t.tag : '&nbsp;') + '</div></div>'; }).join('') + '</div></div>';
  }
  const tvbox = (inner, under) => '<div class="tvbox"><div class="rvw"><div class="rv">' + inner + '</div></div>' + (under || '') + '</div>';
  const tvSeeAll = (list, av) => bar(av) + '<div class="rv-sa"><div class="back">‹ Home</div><h1>Continue Watching</h1><div class="rv-grid">' + list.map((t, i) => tland(t, i === 0, 420)).join('') + '</div></div>';

  /* ================================================================ SHOTS — one per named state */
  const SH = [];
  const add = (id, cap, fn) => SH.push({ id, cap, fn });
  const v3 = ['kids-tv', LONG, 'old-kids-block'];
  add('layout-admin-desktop-global-empty', 'Global layout · nothing hidden — the default', () => adm({ scope: 'g', val: [] }));
  add('layout-admin-desktop-global-one-tag', 'Global layout · hides kids-tv', () => adm({ scope: 'g', val: ['kids-tv'] }));
  add('layout-admin-desktop-picker-open', 'Picker · nothing typed · the most-used tags', () => adm({ scope: 'g', val: ['kids-tv'], pick: { q: '', hov: 1 } }));
  add('layout-admin-desktop-picker-search-kid', 'Picker · search “kid” · the near-miss kidnapping is listed, not picked', () => adm({ scope: 'g', val: ['kids-tv'], pick: { q: 'kid', hov: 1 } }));
  add('layout-admin-desktop-picker-no-results', 'Picker · no results', () => adm({ scope: 'g', val: ['kids-tv'], pick: { q: 'kidz' } }));
  add('layout-admin-desktop-longest-and-zero', 'Longest tag as a chip, and a tag carried by 0 titles', () => adm({ scope: 'g', val: v3 }));
  add('layout-admin-desktop-eyd-custom', 'Eyð · custom layout · hides kids-tv and cartoons', () => adm({ scope: 'u', user: 'eyd', val: HIDE.eyd }));
  add('layout-admin-desktop-global-one-tag-light', 'Light theme · global layout · hides kids-tv', () => adm({ scope: 'g', val: ['kids-tv'], theme: 'light' }));
  add('layout-admin-desktop-picker-search-kid-light', 'Light theme · picker · search “kid”', () => adm({ scope: 'g', val: ['kids-tv'], pick: { q: 'kid', hov: 1 }, theme: 'light' }));
  add('collection-admin-desktop-olivar-continue-off', 'Collection Olivar · Custom rows · Continue Watching off — no sub-row', () => colEd({ cw: false }));
  add('collection-admin-desktop-olivar-continue-on', 'Collection Olivar · Continue Watching on · its own tags, chosen again here', () => colEd({ val: [] }));
  add('collection-admin-desktop-olivar-continue-tags', 'Collection Olivar · hides cartoons — independent of Home’s kids-tv', () => colEd({ val: HIDE.olivar }));
  add('collection-admin-desktop-olivar-picker-open', 'Collection Olivar · picker, nothing typed', () => colEd({ val: HIDE.olivar, pick: { q: '', hov: 0 } }));
  add('collection-admin-narrow-olivar-continue-tags', 'Narrow · collection Olivar · hides cartoons', () => colEd({ w: 'n', val: HIDE.olivar }));
  add('layout-admin-narrow-global-one-tag', 'Narrow · global layout · hides kids-tv', () => adm({ w: 'n', scope: 'g', val: ['kids-tv'] }));
  add('layout-admin-narrow-picker-search-kid', 'Narrow · picker · search “kid”', () => adm({ w: 'n', scope: 'g', val: ['kids-tv'], pick: { q: 'kid', hov: 1 } }));
  add('layout-admin-narrow-longest-and-zero', 'Narrow · longest tag and a 0-title tag', () => adm({ w: 'n', scope: 'g', val: v3 }));
  add('layout-admin-narrow-strings-fo', 'Narrow · Faroese sub-row — the longest add label', () => adm({ w: 'n', scope: 'g', val: ['kids-tv'], lang: 'fo' }));
  add('filter-tv-home-before', 'Home · a user on the global layout · before · 8 cards', () => tvbox(tvHome(T, 'M'), stripHtml(T, [], 'Marjun · global layout · before')));
  add('filter-tv-home-after', 'Home · global layout hides kids-tv · 5 cards · the same screen with fewer cards', () => tvbox(tvHome(filt(T, HIDE.home), 'M'), stripHtml(T, HIDE.home, 'Marjun · global layout · after')));
  add('filter-tv-home-after-eyd', 'Home · Eyð’s custom layout hides kids-tv and cartoons · 4 cards', () => tvbox(tvHome(filt(T, HIDE.eyd), 'E'), stripHtml(T, HIDE.eyd, 'Eyð · custom layout · after')));
  add('filter-tv-home-row-omitted', 'Home · every card hidden (a user on the global layout whose row is only kids-tv) · the row and its header are omitted', () => tvbox(tvHome([], 'K'), stripHtml(KIDS_ROW, HIDE.home, 'Kids · global layout')));
  add('filter-tv-see-all', 'See all after the filter · hidden there too, silently · 5 cards', () => tvbox(tvSeeAll(filt(T, HIDE.home), 'M')));

  /* ================================================================ strings table + mounting */
  function stringsTable() {
    return '<table class="tbl"><tr><th>key · where</th><th>en</th><th>da</th><th>fo</th></tr>' + SKEY.map(([k, w]) => '<tr><td class="k">' + k + '<br><span style="color:#4a4f5e;font-family:Sora,sans-serif">' + w + '</span></td><td>' + esc(S.en[k]) + '</td><td>' + esc(S.da[k]) + '</td><td>' + esc(S.fo[k]) + '</td></tr>').join('') + '</table>';
  }
  function item(id) {
    const s = SH.find(x => x.id === id); if (!s) return '<div class="it"><div class="cap">missing ' + id + '</div></div>';
    return '<div class="it"><div class="cap"><b>' + s.cap + '</b>continue-hidden-tags-' + s.id + '.png</div>' + s.fn() + '</div>';
  }
  function mountDoc() {
    document.querySelectorAll('[data-ids]').forEach(el => {
      const ids = el.dataset.ids === '*' ? SH.filter(s => s.id.startsWith(el.dataset.prefix)).map(s => s.id) : el.dataset.ids.split(',').map(x => x.trim());
      el.innerHTML = ids.map(item).join('');
    });
    const st = document.getElementById('strings'); if (st) st.innerHTML = stringsTable();
    place();
  }
  function place() {
    let y = 0;
    document.querySelectorAll('.blk').forEach(b => { b.style.top = y + 'px'; y += b.offsetHeight + (+b.dataset.gap || 70); });
  }
  window.CHT = { SH, S, SKEY, TAGS, T, adm, mountDoc, place, stringsTable };
})();
