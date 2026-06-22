/* Ravilo config — interactive builders (Phase R-build)
   Workbench filter builder (Content rows + Channels) and Hero item builder.
   Real title pool → real live match counts. Vanilla JS, no deps.
   Styling via .cf-* classes added to ravilo-config.html. */
(function () {
  'use strict';

  // self-contained toast (pages define their own; fall back if absent)
  function toast(msg) {
    if (window.__toast) return window.__toast(msg);
    const t = document.createElement('div'); t.className = 'toast'; t.textContent = msg;
    document.body.appendChild(t); setTimeout(() => t.remove(), 1900);
  }

  /* ============================ data ============================ */
  // A representative slice of the Jellyfin library so match counts are REAL.
  const G = {
    act:'Action', adv:'Adventure', dra:'Drama', sci:'Sci-Fi', fan:'Fantasy',
    doc:'Documentary', fam:'Family', kid:'Kids', com:'Comedy', thr:'Thriller',
    cri:'Crime', rom:'Romance', ani:'Animation', hor:'Horror', mys:'Mystery'
  };
  const T = (title, year, kind, genres, network, tags, rating) =>
    ({ title, year, kind, genres, network, tags: tags || [], rating });

  const TITLES = [
    T('Cosmos Laundromat', 2015, 'movie', [G.sci, G.adv], 'Blender', ['4k','staff-pick'], 8.1),
    T('Agent 327', 2017, 'movie', [G.act, G.com], 'Blender', [], 7.2),
    T('Spring', 2019, 'movie', [G.fan, G.adv], 'Blender', ['4k'], 7.6),
    T('Caminandes', 2016, 'movie', [G.ani, G.fam, G.com], 'Blender', [], 7.0),
    T('Wing It!', 2023, 'movie', [G.ani, G.sci], 'Blender', ['staff-pick'], 7.4),
    T('Hraðar Ljós', 2024, 'movie', [G.thr, G.dra], 'Kringvarp', ['nordic-noir','award-winner'], 8.3),
    T('Nordvest', 2023, 'series', [G.cri, G.dra], 'Kringvarp', ['nordic-noir'], 8.0),
    T('Havets Hjarta', 2022, 'series', [G.dra, G.mys], 'DR', ['nordic-noir','dansk-tv'], 7.9),
    T('Borgen Vinter', 2021, 'series', [G.dra, G.thr], 'DR', ['dansk-tv','award-winner'], 8.5),
    T('Forbrydelsen Nat', 2019, 'series', [G.cri, G.thr], 'DR', ['nordic-noir','dansk-tv'], 8.7),
    T('Bron Returns', 2020, 'series', [G.cri, G.dra], 'TV 2', ['nordic-noir'], 8.4),
    T('Vikings of Tórshavn', 2024, 'series', [G.act, G.adv, G.dra], 'Kringvarp', ['award-winner'], 7.7),
    T('The Last Fjord', 2018, 'movie', [G.dra, G.rom], 'TV 2', ['dansk-tv'], 7.1),
    T('Midnight Sun Patrol', 2022, 'series', [G.act, G.cri], 'HBO', [], 7.8),
    T('Iron Veil', 2021, 'movie', [G.act, G.sci, G.thr], 'HBO', ['4k'], 7.9),
    T('Quantum Drift', 2023, 'movie', [G.sci, G.adv], 'HBO', ['4k','staff-pick'], 8.2),
    T('Neon Harbor', 2020, 'series', [G.sci, G.cri, G.dra], 'HBO', ['4k'], 8.6),
    T('Wasteland Kings', 2019, 'series', [G.act, G.dra, G.fan], 'HBO', ['award-winner'], 9.0),
    T('Dragon Reach', 2022, 'movie', [G.fan, G.adv], 'Max Originals', ['4k'], 7.5),
    T('Spellbound City', 2024, 'series', [G.fan, G.fam], 'Max Originals', ['staff-pick'], 7.3),
    T('Little Robots', 2021, 'movie', [G.ani, G.kid, G.fam], 'Max Originals', [], 6.9),
    T('Sunny Meadows', 2018, 'series', [G.fam, G.com], 'Netflix', [], 6.7),
    T('Galaxy Scouts', 2023, 'series', [G.sci, G.fam, G.adv], 'Netflix', ['staff-pick'], 7.6),
    T('Comedy Central Hour', 2020, 'series', [G.com], 'Netflix', [], 6.5),
    T('The Heist Crew', 2022, 'movie', [G.cri, G.thr, G.act], 'Netflix', ['4k'], 7.8),
    T('Whispering Pines', 2019, 'series', [G.mys, G.dra, G.hor], 'Netflix', [], 7.4),
    T('Deep Blue Earth', 2021, 'movie', [G.doc], 'BBC', ['award-winner'], 8.8),
    T('Wild Continents', 2023, 'series', [G.doc, G.fam], 'BBC', ['4k','award-winner'], 9.1),
    T('History of Light', 2020, 'movie', [G.doc], 'BBC', [], 7.7),
    T('The Crown Road', 2018, 'series', [G.dra, G.doc], 'BBC', ['award-winner'], 8.9),
    T('Laugh Track', 2024, 'movie', [G.com, G.rom], 'TV 2', [], 6.8),
    T('First Frost', 2022, 'movie', [G.rom, G.dra], 'TV 2', ['dansk-tv'], 7.2),
    T('Edge of Tomorrow Bay', 2021, 'movie', [G.sci, G.act, G.thr], 'HBO', ['4k','staff-pick'], 8.0),
    T('Phantom Circuit', 2023, 'movie', [G.thr, G.mys, G.sci], 'HBO', [], 7.5),
    T('Kids Quest', 2020, 'series', [G.kid, G.ani, G.adv], 'Netflix', [], 6.6),
    T('Mythic Realms', 2024, 'series', [G.fan, G.adv, G.act], 'Max Originals', ['4k','award-winner'], 8.3),
    T('Snowbound', 2019, 'movie', [G.thr, G.dra], 'Kringvarp', ['nordic-noir'], 7.9),
    T('The Quiet Coast', 2022, 'series', [G.dra, G.mys], 'Kringvarp', ['nordic-noir','dansk-tv'], 8.1),
    T('Starlight Express 9', 2023, 'movie', [G.sci, G.fam, G.adv], 'Max Originals', ['staff-pick'], 7.0),
    T('Crimson Tide Rising', 2021, 'movie', [G.act, G.adv, G.thr], 'HBO', ['4k'], 7.6),
  ];

  /* ---- derived audio-track metadata (deterministic, demo-real) ---- */
  const AUD_UNTAGGED = ['Cosmos Laundromat','The Heist Crew','Nordvest','Kids Quest'];
  function audLangs(t) {
    const base = ({ 'Kringvarp':['Faroese','Danish'], 'DR':['Danish'], 'TV 2':['Danish'],
      'BBC':['English'], 'Netflix':['English','Spanish'] })[t.network] || ['English'];
    const out = base.slice();
    if (AUD_UNTAGGED.includes(t.title)) out.push('Untagged');
    return out;
  }
  function audCodecs(t) {
    if (t.tags.includes('4k')) return ['E-AC-3','TrueHD'];
    const out = [ t.year % 2 === 0 ? 'AC-3' : 'AAC' ];
    if (t.genres.includes(G.act) || t.genres.includes(G.thr)) out.push('DTS');
    return out;
  }
  function audTitles(t) {
    if (t.tags.includes('dansk-tv')) return ['Hovedspor','Synstolkning'];
    if (t.tags.includes('4k') && t.tags.includes('staff-pick')) return ['Main','Commentary'];
    return ['Main'];
  }

  const HERO = new Set(['Wasteland Kings', 'Quantum Drift']);   // titles currently featured for the active viewer (demo)

  const FACETS = {
    studio:  { label: 'Studio',  type: 'list', options: ['Blender','Kringvarp','DR','TV 2','HBO','Max Originals','Netflix','BBC'], get: t => [t.studio || t.network] },
    network: { label: 'Network', type: 'list', options: ['HBO','Max Originals','TV 2','Kringvarp','DR','Netflix','BBC','Blender'], get: t => [t.network] },
    genre:   { label: 'Genre',   type: 'list', options: Object.values(G), get: t => t.genres },
    tag:     { label: 'Tag',     type: 'list', options: ['nordic-noir','dansk-tv','4k','staff-pick','award-winner'], get: t => t.tags },
    audioLang:  { label: 'Audio language', group: 'Audio track', type: 'list', options: ['English','Danish','Faroese','Spanish','Untagged'], get: audLangs },
    audioCodec: { label: 'Audio codec',    group: 'Audio track', type: 'list', options: ['E-AC-3','AC-3','DTS','AAC','TrueHD'], get: audCodecs },
    audioTitle: { label: 'Audio track title', group: 'Audio track', type: 'text', get: t => audTitles(t).join(' / ') },
    hero:    { label: 'Hero item', group: 'Ravilo layout', type: 'list', options: ['Featured','Not featured'], get: t => [HERO.has(t.title) ? 'Featured' : 'Not featured'] },
  };
  const LIST_OPS = [ ['isAny','is any of'], ['isNot','is none of'] ];
  const NUM_OPS  = [ ['gte','is on or after'], ['lte','is before or in'], ['eq','is exactly'] ];
  const TEXT_OPS = [ ['contains','contains'], ['ncontains','does not contain'] ];
  function opsFor(f){ const ty = FACETS[f].type; return ty === 'num' ? NUM_OPS : ty === 'text' ? TEXT_OPS : LIST_OPS; }
  function opLabel(f, op){ return (opsFor(f).find(o => o[0]===op) || opsFor(f)[0])[1]; }

  /* ===================== match evaluation ===================== */
  function matchOne(t, cond) {
    const f = FACETS[cond.facet]; if (!f) return true;
    if (f.type === 'num') {
      const v = f.get(t), n = parseFloat(cond.values[0]);
      if (isNaN(n)) return true;
      if (cond.op === 'gte') return v >= n;
      if (cond.op === 'lte') return v <= n;
      return Math.round(v) === Math.round(n);
    }
    if (f.type === 'text') {
      const q = (cond.values[0] || '').toLowerCase();
      if (!q) return true;
      const has = String(f.get(t)).toLowerCase().includes(q);
      return cond.op === 'ncontains' ? !has : has;
    }
    const have = f.get(t).map(String);
    const hit = cond.values.some(v => have.includes(String(v)));
    return cond.op === 'isNot' ? !hit : hit;
  }
  function evaluate(state) {
    return TITLES.filter(t => {
      if (state.include === 'movie' && t.kind !== 'movie') return false;
      if (state.include === 'series' && t.kind !== 'series') return false;
      const conds = state.conditions.filter(c => c.values.length);
      if (!conds.length) return true;
      return state.match === 'any' ? conds.some(c => matchOne(t, c)) : conds.every(c => matchOne(t, c));
    });
  }

  /* ===================== gradient helper ===================== */
  function hashHue(s){ let h = 0; for (let i=0;i<s.length;i++) h = (h*31 + s.charCodeAt(i)) % 360; return h; }
  function grad(s){ const h = hashHue(s); return `linear-gradient(150deg, hsl(${h} 46% 36%), hsl(${(h+40)%360} 52% 14%))`; }

  /* ===================== modal plumbing ===================== */
  const root = document.getElementById('cf-modal-root');
  function closeModal(){ root.innerHTML = ''; root.style.display = 'none'; document.removeEventListener('keydown', onEsc); }
  function onEsc(e){ if (e.key === 'Escape') closeModal(); }
  function openModal(node){
    root.innerHTML = '';
    const back = document.createElement('div'); back.className = 'cf-modal-back';
    back.appendChild(node); root.appendChild(back); root.style.display = 'block';
    back.addEventListener('mousedown', e => { if (e.target === back) closeModal(); });
    document.addEventListener('keydown', onEsc);
  }
  function el(html){ const d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstElementChild; }

  /* lightweight popover for picking facet values / numeric entry */
  function popover(anchor, inner, onPick) {
    document.querySelectorAll('.cf-pop').forEach(p => p.remove());
    const pop = document.createElement('div'); pop.className = 'cf-pop card';
    pop.innerHTML = inner;
    document.body.appendChild(pop);
    const r = anchor.getBoundingClientRect();
    pop.style.left = Math.min(r.left, window.innerWidth - pop.offsetWidth - 12) + 'px';
    pop.style.top = (r.bottom + 6) + 'px';
    pop.addEventListener('click', e => { const it = e.target.closest('[data-val]'); if (it) { onPick(it.dataset.val); } });
    const close = e => { if (!pop.contains(e.target) && e.target !== anchor) { pop.remove(); document.removeEventListener('mousedown', close); } };
    setTimeout(() => document.addEventListener('mousedown', close), 0);
    return pop;
  }

  /* ============================================================
     FILTER BUILDER (workbench) — used by Content rows + Channels
     ============================================================ */
  const CH_COLORS = ['linear-gradient(135deg,#3b2a78,#15102e)','linear-gradient(135deg,#e3122b,#7d0a1a)','linear-gradient(135deg,#0a93a6,#063d47)','linear-gradient(135deg,#1455d8,#0a2766)','linear-gradient(135deg,#c8102e,#1a1a1a)'];

  function openFilter(opts) {
    opts = opts || {};
    const isChannel = opts.mode === 'channel';
    const isLibrary = opts.mode === 'library';
    const state = opts.state || {
      match: 'all', include: 'all',
      conditions: [ { facet: 'genre', op: 'isAny', values: [] } ],
      title: '', chStyle: 'logo', chColor: CH_COLORS[0]
    };
    if (!state.conditions.length) state.conditions.push({ facet:'genre', op:'isAny', values:[] });

    const node = el(`<div class="cf-modal" style="max-width:1000px;"></div>`);

    function autoTitle() {
      const c = state.conditions.find(c => c.values.length);
      if (!c) return isChannel ? 'New channel' : 'New row';
      if (FACETS[c.facet].type === 'num') return FACETS[c.facet].label + ' ' + opLabel(c.facet,c.op) + ' ' + c.values[0];
      return c.values.slice(0,2).join(' & ') + (c.values.length>2 ? ' +' : '');
    }

    function render() {
      const matches = evaluate(state);
      const titleVal = state.title || autoTitle();
      node.innerHTML = `
        <div class="cf-modal-head">
          <span class="cf-grab">⠿</span>
          <b style="font-size:.98rem;">${opts.headTitle || ((opts.edit ? 'Edit' : 'New') + ' ' + (isChannel ? 'channel' : 'content row'))}</b>
          <span class="badge">⚙ Workbench</span>
          <span class="spacer"></span>
          <span class="tiny muted">esc to cancel</span>
          <span class="cf-x" data-x>✕</span>
        </div>
        <div style="display:flex;align-items:stretch;">
          <div style="flex:1;padding:18px;min-width:0;">
            <div class="row center" style="gap:8px;">
              <span class="cf-eyebrow">Match</span>
              <span class="seg cf-match"><span class="${state.match==='all'?'on':''}" data-m="all">ALL</span><span class="${state.match==='any'?'on':''}" data-m="any">ANY</span></span>
              <span class="tiny muted">of these conditions</span>
            </div>

            <div class="cf-conds" style="display:flex;flex-direction:column;gap:8px;margin-top:11px;"></div>
            <div style="padding-left:44px;margin-top:9px;"><span class="vchip add" data-add style="padding:7px 12px;">＋ Add condition</span></div>

            <div class="row center" style="gap:14px;margin-top:16px;flex-wrap:wrap;">
              <div><span class="cf-eyebrow">Include</span>
                <div class="seg cf-include" style="margin-top:7px;">
                  <span class="${state.include==='all'?'on':''}" data-inc="all">All</span>
                  <span class="${state.include==='movie'?'on':''}" data-inc="movie">Movies</span>
                  <span class="${state.include==='series'?'on':''}" data-inc="series">Series</span>
                </div></div>
              <div style="flex:1;min-width:160px;${isLibrary?'display:none;':''}"><span class="cf-eyebrow">${isChannel?'Channel name':'Row title'}</span>
                <input class="input cf-title" style="margin-top:7px;" value="${titleVal.replace(/"/g,'&quot;')}" placeholder="${autoTitle()}"></div>
            </div>

            ${isChannel ? `
            <hr class="dash" style="margin:16px 0;">
            <span class="cf-eyebrow">Channel button</span>
            <div class="row center" style="gap:14px;margin-top:11px;flex-wrap:wrap;">
              <div><div class="tiny muted" style="margin-bottom:6px;">Style</div>
                <span class="seg cf-style"><span class="${state.chStyle==='logo'?'on':''}" data-st="logo">Logo</span><span class="${state.chStyle==='text'?'on':''}" data-st="text">Text</span></span></div>
              <div><div class="tiny muted" style="margin-bottom:6px;">Brand color</div>
                <div class="row" style="gap:7px;" id="cf-colors">${CH_COLORS.map(c => `<span class="cf-sw ${c===state.chColor?'on':''}" data-color="${c}" style="background:${c};"></span>`).join('')}</div></div>
            </div>` : ''}

            <div class="note blue" style="margin-top:16px;display:flex;gap:10px;align-items:center;padding:10px 12px;">
              <span class="badge info" style="flex:none;">${isLibrary ? 'one filter' : 'reusable'}</span>
              <span class="tiny" style="flex:1;">${isLibrary ? 'Build it here, then <b>Save filter as…</b> a Channel or Content row straight into a viewer’s Ravilo layout — same conditions, no retyping.' : 'This same filter works on the <b>Library</b> page — open it anytime from <span class="mono">Library → Filter</span>.'}</span>
            </div>
          </div>

          <div style="width:300px;flex:none;border-left:1px solid var(--line);padding:18px;background:var(--fill-2);">
            <span class="cf-eyebrow">Matches now</span>
            <div class="row center" style="gap:10px;margin-top:10px;">
              <span class="cf-matchcount"><span class="n">${matches.length}</span><span class="l">titles</span></span>
              <span class="spacer"></span><span class="badge ${matches.length?'ok':'warn'}">${matches.length?'live':'none'}</span>
            </div>
            <div class="cf-prevgrid" style="display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:12px;">
              ${matches.slice(0,12).map(m => `<div class="cf-mp" title="${m.title} · ${m.year}" style="background:${grad(m.title)};"><span class="t">${m.title}</span></div>`).join('') || '<div class="tiny muted" style="grid-column:1/-1;padding:18px 4px;">No titles match — loosen a condition.</div>'}
            </div>
            ${matches.length>12 ? `<div class="tiny muted" style="margin-top:8px;">+${matches.length-12} more</div>` : ''}
            ${isChannel ? `<div class="studio-wm cf-chprev" style="width:100%;height:46px;margin-top:14px;font-size:1.1rem;background:${state.chColor};">${(state.title||'Channel').slice(0,10)}</div><div class="tiny muted center-x" style="margin-top:8px;">channel button preview</div>` : ''}
            ${isLibrary ? '' : `<a href="library.html" class="tiny" style="display:block;margin-top:14px;color:var(--acc-ink);">Open these ${matches.length} in Library ↗</a>`}
          </div>
        </div>
        <div class="row center" style="padding:13px 16px;border-top:1px solid var(--line);background:var(--fill-2);">
          <span class="tiny muted">${isLibrary ? 'Filters the library grid live.' : (isChannel?'The same conditions power this channel’s scoped rows on the TV.':'Added to the bottom of the stack · drag to reorder after.')}</span>
          <span class="spacer"></span>
          <span class="btn sm ghost" data-x>Cancel</span>
          <span class="btn sm primary" data-save>${opts.saveLabel || (opts.edit ? 'Save changes' : (isChannel ? 'Add channel' : 'Add row'))}</span>
        </div>`;
      renderConds();
      wire();
    }

    function renderConds() {
      const wrap = node.querySelector('.cf-conds');
      wrap.innerHTML = state.conditions.map((c, i) => {
        const f = FACETS[c.facet];
        const valHtml = f.type === 'num'
          ? (c.values.length ? `<span class="vchip" data-vchip="${i}:0">${c.values[0]} <span class="x" data-rmval="${i}:0">✕</span></span>` : `<span class="vchip add" data-num="${i}">＋ value</span>`)
          : f.type === 'text'
          ? (c.values.length ? `<span class="vchip" data-text="${i}">“${c.values[0]}” <span class="x" data-rmval="${i}:0">✕</span></span>` : `<span class="vchip add" data-text="${i}">＋ text…</span>`)
          : c.values.map((v, vi) => `<span class="vchip">${v} <span class="x" data-rmval="${i}:${vi}">✕</span></span>`).join('') + `<span class="vchip add" data-pick="${i}">＋</span>`;
        return `<div style="display:flex;align-items:center;gap:8px;">
          <span class="cf-join" style="width:36px;text-align:center;visibility:${i===0?'hidden':'visible'};">${state.match==='any'?'OR':'AND'}</span>
          <div class="cf-cond" style="flex:1;">
            <span class="cf-facet" data-facet="${i}">${f.label} <span style="color:var(--ink-soft);">▾</span></span>
            <span class="cf-op" data-op="${i}">${opLabel(c.facet, c.op)} <span style="color:var(--ink-soft);">▾</span></span>
            ${valHtml}
            <span class="spacer"></span>
            ${state.conditions.length>1?`<span class="cf-rmcond" data-rmcond="${i}" title="Remove condition">✕</span>`:''}
          </div>
        </div>`;
      }).join('');
    }

    function wire() {
      node.querySelectorAll('[data-x]').forEach(b => b.onclick = closeModal);
      node.querySelectorAll('.cf-match span').forEach(s => s.onclick = () => { state.match = s.dataset.m; render(); });
      node.querySelectorAll('.cf-include span').forEach(s => s.onclick = () => { state.include = s.dataset.inc; render(); });
      const ti = node.querySelector('.cf-title'); if (ti) ti.oninput = () => { state.title = ti.value; const p = node.querySelector('.cf-chprev'); if (p) p.textContent = (ti.value||'Channel').slice(0,10); };
      node.querySelectorAll('.cf-style span').forEach(s => s.onclick = () => { state.chStyle = s.dataset.st; render(); });
      node.querySelectorAll('.cf-sw').forEach(s => s.onclick = () => { state.chColor = s.dataset.color; render(); });
      node.querySelector('[data-add]').onclick = () => { state.conditions.push({ facet:'genre', op:'isAny', values:[] }); render(); };

      node.querySelectorAll('[data-rmcond]').forEach(b => b.onclick = () => { state.conditions.splice(+b.dataset.rmcond,1); render(); });
      node.querySelectorAll('[data-rmval]').forEach(b => b.onclick = () => { const [i,vi] = b.dataset.rmval.split(':').map(Number); state.conditions[i].values.splice(vi,1); render(); });

      node.querySelectorAll('[data-facet]').forEach(a => a.onclick = () => {
        const i = +a.dataset.facet;
        let last = null;
        const items = Object.keys(FACETS).map(k => {
          const g = FACETS[k].group || '';
          const head = (g && g !== last) ? `<div class="cf-popgroup">${g}</div>` : '';
          last = g;
          return head + `<div class="cf-popitem" data-val="${k}">${FACETS[k].label}</div>`;
        }).join('');
        popover(a, items, v => {
          state.conditions[i].facet = v; state.conditions[i].op = opsFor(v)[0][0]; state.conditions[i].values = []; render();
        });
      });
      node.querySelectorAll('[data-op]').forEach(a => a.onclick = () => {
        const i = +a.dataset.op, f = state.conditions[i].facet;
        popover(a, opsFor(f).map(o => `<div class="cf-popitem" data-val="${o[0]}">${o[1]}</div>`).join(''), v => { state.conditions[i].op = v; render(); });
      });
      node.querySelectorAll('[data-pick]').forEach(a => a.onclick = () => {
        const i = +a.dataset.pick, f = state.conditions[i].facet;
        const cur = state.conditions[i].values;
        popover(a, FACETS[f].options.map(o => `<div class="cf-popitem ${cur.includes(o)?'on':''}" data-val="${o}">${cur.includes(o)?'✓ ':''}${o}</div>`).join(''), v => {
          if (!cur.includes(v)) cur.push(v); render();
        });
      });
      node.querySelectorAll('[data-num]').forEach(a => a.onclick = () => {
        const i = +a.dataset.num, f = FACETS[state.conditions[i].facet];
        popover(a, `<div style="padding:8px;"><input class="input cf-numin" type="number" ${f.min!=null?`min="${f.min}"`:''} ${f.max!=null?`max="${f.max}"`:''} step="${f.step||1}" placeholder="${state.conditions[i].facet==='year'?'2015':'7.5'}" style="width:120px;"><div class="btn sm primary cf-numok" style="margin-top:8px;justify-content:center;">Set</div></div>`, ()=>{});
        const pop = document.querySelector('.cf-pop'); const inp = pop.querySelector('.cf-numin'); inp.focus();
        const ok = () => { if (inp.value!=='') { state.conditions[i].values = [inp.value]; } pop.remove(); render(); };
        pop.querySelector('.cf-numok').onclick = ok;
        inp.onkeydown = e => { if (e.key==='Enter') ok(); };
      });
      node.querySelectorAll('[data-text]').forEach(a => a.onclick = () => {
        const i = +a.dataset.text;
        popover(a, `<div style="padding:8px;"><input class="input cf-txtin" type="text" value="${(state.conditions[i].values[0]||'').replace(/"/g,'&quot;')}" placeholder="e.g. Synstolkning, Commentary, SDH" style="width:200px;"><div class="btn sm primary cf-txtok" style="margin-top:8px;justify-content:center;">Set</div></div>`, ()=>{});
        const pop = document.querySelector('.cf-pop'); const inp = pop.querySelector('.cf-txtin'); inp.focus();
        const ok = () => { state.conditions[i].values = inp.value.trim() ? [inp.value.trim()] : []; pop.remove(); render(); };
        pop.querySelector('.cf-txtok').onclick = ok;
        inp.onkeydown = e => { if (e.key==='Enter') ok(); };
      });

      node.querySelector('[data-save]').onclick = () => commit();
    }

    function commit() {
      const matches = evaluate(state);
      const title = state.title || autoTitle();
      const summary = state.conditions.filter(c=>c.values.length).map(c => {
        const f = FACETS[c.facet];
        return f.label + ' ' + opLabel(c.facet,c.op) + ' ' + (f.type==='num' ? c.values[0] : '“'+c.values.join('”, “')+'”');
      }).join(state.match==='any'?'  OR  ':'  ·  ') || 'all titles';

      if (opts.onSave) { opts.onSave({ state, matches, title, summary, isChannel }); closeModal(); return; }

      if (opts.editRow) {
        opts.editRow.querySelector('.nm').firstChild.textContent = title + ' ';
        opts.editRow.querySelector('.src').textContent = summary;
        opts.editRow._cfState = state;
      } else if (isChannel) {
        const wm = state.chStyle==='logo'
          ? `<div class="studio-wm" style="background:${state.chColor};">${title.slice(0,3).toUpperCase()}</div>`
          : `<div class="studio-wm" style="background:${state.chColor};font-size:.62rem;">${title.slice(0,8)}</div>`;
        const row = el(`<div class="cfg-row cf-new">
          <span class="grab">⠿</span>${wm}
          <div style="flex:1;"><div class="nm">${title} <span class="badge info" style="font-size:.6rem;">${matches.length} titles</span></div><div class="src">${summary}</div></div>
          <span class="seg-pill"><button class="${state.chStyle==='logo'?'on':''}">Logo</button><button class="${state.chStyle==='text'?'on':''}">Text</button></span>
          <span class="toggle on"></span><span class="btn sm ghost rm">✕</span></div>`);
        row._cfState = state; row.dataset.kind = 'channel';
        document.getElementById('studiolist').appendChild(row); bindRow(row, true);
      } else {
        const row = el(`<div class="cfg-row cf-new">
          <span class="grab">⠿</span><span class="badge info" style="flex:none;">filter</span>
          <div style="flex:1;"><div class="nm">${title} <span class="badge" style="font-size:.6rem;">${matches.length} titles</span></div><div class="src">${summary}${state.include!=='all'?' · '+state.include+'s only':''}</div></div>
          <span class="muted tiny">show</span><span class="toggle on"></span><span class="btn sm ghost rm">✕</span></div>`);
        row._cfState = state; row.dataset.kind = 'row';
        document.getElementById('rowlist').appendChild(row); bindRow(row, true);
      }
      closeModal();
      toast((opts.edit?'Updated ':'Added ') + (isChannel?'channel':'row') + ' · ' + matches.length + ' titles');
    }

    openModal(node); render();
  }

  /* ============================================================
     HERO ITEM BUILDER
     ============================================================ */
  const BADGES = ['New Season','4K','Top 10','Premiere','None'];
  function openHero(opts) {
    opts = opts || {};
    const state = opts.state || { pick: TITLES[0], badge: '4K', tagline: 'Featured Film', backdrop: 0, useLogo: true };
    const node = el(`<div class="cf-modal" style="max-width:1040px;"></div>`);
    const VIEWERS = ['Eyð Restorff', 'Marjun í Dali', 'Hjalti Poulsen'];

    function render() {
      const p = state.pick;
      const step1 = opts.lockTitle ? `
            <span class="cf-eyebrow">Featuring</span>
            <div class="row center" style="gap:11px;margin-top:8px;padding:9px;border:1px solid var(--line-2);border-radius:10px;background:var(--fill-2);">
              <div class="cf-mp" style="width:34px;height:51px;background:${grad(p.title)};"></div>
              <div style="flex:1;min-width:0;"><div style="font-weight:600;">${p.title}</div><div class="tiny muted">${p.kind==='movie'?'Film':'Series'}${p.year?' · '+p.year:''}${p.genres&&p.genres.length?' · '+p.genres.slice(0,2).join(' · '):''}</div></div>
              <span class="badge ok">this title</span>
            </div>` : `
            <span class="cf-eyebrow">1 · Pick a title</span>
            <div class="input ph cf-search" style="margin-top:8px;display:flex;align-items:center;gap:8px;cursor:text;"><span>⌕</span><input class="cf-searchin" placeholder="Search your library…" style="border:0;background:transparent;color:inherit;flex:1;outline:none;font:inherit;"></div>
            <div class="cf-results card" style="padding:6px;margin-top:6px;max-height:188px;overflow:auto;"></div>
            <div class="tiny muted" style="margin:6px 2px 0;">Multi-language search — matches every title an item has ever had.</div>`;
      node.innerHTML = `
        <div class="cf-modal-head">
          <span class="cf-grab">⠿</span><b style="font-size:.98rem;">${opts.headTitle || ((opts.edit?'Edit':'New') + ' featured item')}</b>
          <span class="spacer"></span><span class="tiny muted">esc to cancel</span><span class="cf-x" data-x>✕</span>
        </div>
        <div style="display:flex;">
          <div style="width:430px;flex:none;padding:18px;border-right:1px solid var(--line);">
            ${step1}
            <hr class="dash" style="margin:16px 0;">
            <span class="cf-eyebrow">${opts.lockTitle ? 'Dress it for the carousel' : '2 · Dress it for the carousel'}</span>

            <div class="field" style="margin:12px 0;">
              <label>Badge</label>
              <div class="row" style="gap:7px;flex-wrap:wrap;" id="cf-badges">
                ${BADGES.map(b => `<span class="cf-opt ${b===state.badge?'on':''}" data-badge="${b}">${b}</span>`).join('')}
              </div>
            </div>
            <div class="field" style="margin-bottom:12px;">
              <label>Tagline / kicker</label>
              <input class="input cf-tag" value="${(state.tagline||'').replace(/"/g,'&quot;')}" placeholder="e.g. Kringvarp Original">
              <span class="hint">Small line above the title in the banner.</span>
            </div>
            <div class="row center" style="gap:8px;">
              <span class="toggle ${state.useLogo?'on':''}" id="cf-logo"></span>
              <span class="tiny muted">Use clearlogo overlay (instead of text title)</span>
            </div>
            <div style="margin-top:14px;"><span class="cf-eyebrow">Backdrop</span>
              <div class="row" style="gap:7px;margin-top:7px;" id="cf-backdrops">
                ${[0,1,2].map(i => `<div class="cf-bd ${state.backdrop===i?'on':''}" data-bd="${i}" style="background:${grad(p.title+i)};"></div>`).join('')}
              </div></div>
          </div>

          <div style="flex:1;padding:18px;min-width:0;background:var(--fill-2);">
            <div class="row center"><span class="cf-eyebrow">Live preview</span><span class="spacer"></span><span class="badge ok" style="font-size:.6rem;">as seen on TV</span></div>
            <div class="cf-hero" style="margin-top:12px;">
              <div style="position:absolute;inset:0;background:${grad(p.title+state.backdrop)};"></div>
              <div class="cf-hero-scrim"></div>
              <div class="cf-hero-body">
                <div style="font-size:.72rem;letter-spacing:.12em;text-transform:uppercase;color:#cdd2e4;font-weight:700;">${state.tagline||''}</div>
                <div style="font-family:'Space Grotesk',sans-serif;font-weight:800;font-size:2rem;line-height:1.03;margin:6px 0;color:#fff;text-shadow:0 2px 16px rgba(0,0,0,.6);">${p.title}</div>
                <div class="row center" style="gap:8px;margin-bottom:10px;">
                  ${state.badge && state.badge!=='None' ? `<span class="badge" style="background:rgba(255,255,255,.16);color:#fff;border:none;">${state.badge}</span>` : ''}
                  <span class="tiny" style="color:#cdd2e4;">${p.year} · ${p.genres.slice(0,2).join(' · ')}</span>
                </div>
                <div class="row" style="gap:8px;">
                  <span style="background:#fff;color:#111;font-weight:700;font-size:.78rem;padding:7px 16px;border-radius:7px;">▶ Play</span>
                  <span style="background:rgba(255,255,255,.18);color:#fff;font-weight:600;font-size:.78rem;padding:7px 14px;border-radius:7px;">More info</span>
                </div>
              </div>
            </div>
            <div class="tiny muted" style="margin-top:10px;line-height:1.5;">Backdrop, tagline and badge update live. ${state.useLogo?'<b>Clearlogo</b> overlay is on — falls back to text if the title has no logo.':'Showing the <b>text title</b>.'} Hero height &amp; auto-advance are set once for the whole carousel.</div>
          </div>
        </div>
        <div class="row center" style="padding:13px 16px;border-top:1px solid var(--line);background:var(--fill-2);">
          ${opts.viewerPicker
            ? `<span class="tiny muted">For viewer</span><span class="select cf-viewer" style="min-width:150px;margin-left:8px;"><span>${state.viewer||VIEWERS[0]}</span> <span class="muted" style="font-size:.7rem;">▾</span></span>`
            : `<span class="tiny muted">Drag to reorder after adding.</span>`}
          <span class="spacer"></span><span class="btn sm ghost" data-x>Cancel</span>
          <span class="btn sm primary" data-save>${opts.saveLabel || (opts.edit?'Save changes':'Add to carousel')}</span>
        </div>`;
      renderResults('');
      wire();
    }

    function renderResults(q) {
      const box = node.querySelector('.cf-results');
      if (!box) return;
      const list = (q ? TITLES.filter(t => t.title.toLowerCase().includes(q.toLowerCase())) : TITLES).slice(0, 8);
      box.innerHTML = list.map(t => {
        const sel = t === state.pick;
        return `<div class="cf-res row center ${sel?'sel':''}" data-pick="${t.title}" style="gap:10px;padding:7px;border-radius:8px;cursor:pointer;${sel?'background:var(--hi-soft);border:1px solid rgba(123,110,240,.35);':''}">
          <div class="cf-mp" style="width:30px;height:45px;background:${grad(t.title)};"></div>
          <div style="flex:1;min-width:0;"><div style="font-weight:600;font-size:.9rem;">${t.title}</div><div class="tiny muted">${t.kind==='movie'?'Film':'Series'} · ${t.year} · ${t.genres.slice(0,2).join(' · ')}</div></div>
          ${sel?'<span class="badge ok">selected</span>':''}</div>`;
      }).join('') || '<div class="tiny muted" style="padding:10px;">No matches.</div>';
      box.querySelectorAll('[data-pick]').forEach(r => r.onclick = () => { state.pick = TITLES.find(t => t.title === r.dataset.pick); render(); });
    }

    function wire() {
      node.querySelectorAll('[data-x]').forEach(b => b.onclick = closeModal);
      const si = node.querySelector('.cf-searchin'); if (si) si.oninput = () => renderResults(si.value);
      const vsel = node.querySelector('.cf-viewer');
      if (vsel) vsel.onclick = () => popover(vsel, VIEWERS.map(v => `<div class="cf-popitem ${v===(state.viewer||VIEWERS[0])?'on':''}" data-val="${v}">${v}</div>`).join(''), v => { state.viewer = v; render(); });
      node.querySelectorAll('[data-badge]').forEach(b => b.onclick = () => { state.badge = b.dataset.badge; render(); });
      node.querySelectorAll('[data-bd]').forEach(b => b.onclick = () => { state.backdrop = +b.dataset.bd; render(); });
      const tg = node.querySelector('.cf-tag'); tg.oninput = () => { state.tagline = tg.value; const k = node.querySelector('.cf-hero-body > div'); if (k) k.textContent = tg.value; };
      node.querySelector('#cf-logo').onclick = () => { state.useLogo = !state.useLogo; render(); };
      node.querySelector('[data-save]').onclick = () => commit();
    }

    function commit() {
      const p = state.pick;
      if (opts.onSave) { opts.onSave({ state, pick: p }); closeModal(); return; }
      if (opts.editRow) {
        opts.editRow.querySelector('.nm').innerHTML = `${p.title} ${state.badge&&state.badge!=='None'?`<span class="badge" style="font-size:.6rem;">${state.badge}</span>`:''}`;
        opts.editRow.querySelector('.src').textContent = `${p.kind==='movie'?'Film':'Series'} · ${p.network} · ${p.year}`;
        opts.editRow.querySelector('.hero-thumb').style.background = grad(p.title+state.backdrop);
        opts.editRow._cfState = state;
      } else {
        const row = el(`<div class="cfg-row cf-new">
          <span class="grab">⠿</span>
          <div class="hero-thumb" style="background:${grad(p.title+state.backdrop)};"><span class="t">${p.title.slice(0,8)}…</span></div>
          <div style="flex:1;"><div class="nm">${p.title} ${state.badge&&state.badge!=='None'?`<span class="badge" style="font-size:.6rem;">${state.badge}</span>`:''}</div><div class="src">${p.kind==='movie'?'Film':'Series'} · ${p.network} · ${p.year}</div></div>
          <span class="muted tiny">show</span><span class="toggle on"></span><span class="btn sm ghost rm">✕</span></div>`);
        row._cfState = state; row.dataset.kind = 'hero';
        document.getElementById('herolist').appendChild(row); bindRow(row, true);
      }
      closeModal();
      toast((opts.edit?'Updated ':'Added ') + 'hero item · ' + p.title);
    }

    openModal(node); render();
    setTimeout(() => { const s = node.querySelector('.cf-searchin'); if (s) s.focus(); }, 30);
  }

  /* ===================== row binding (edit / remove / toggle) ===================== */
  function bindRow(row, full) {
    if (full) {
      const rm = row.querySelector('.rm'); if (rm) rm.onclick = e => { e.stopPropagation(); row.remove(); };
      const tog = row.querySelector('.toggle'); if (tog) tog.onclick = e => {
        e.stopPropagation(); tog.classList.toggle('on');
        row.classList.toggle('off', !tog.classList.contains('on'));
        const lbl = row.querySelector('.muted.tiny'); if (lbl) lbl.textContent = tog.classList.contains('on') ? 'show' : 'hidden';
      };
      const seg = row.querySelector('.seg-pill'); if (seg) seg.onclick = e => { const b = e.target.closest('button'); if (b) { e.stopPropagation(); seg.querySelectorAll('button').forEach(x => x.classList.toggle('on', x===b)); } };
    }
    // edit by clicking the body (both new + existing rows)
    const body = row.querySelector('.nm') && row.querySelector('.nm').parentElement;
    if (body) { body.style.cursor = 'pointer'; body.title = 'Edit'; body.onclick = () => editRow(row); }
  }

  function editRow(row) {
    const kind = row.dataset.kind;
    if (kind === 'hero') return openHero({ edit:true, editRow:row, state: row._cfState });
    if (kind === 'channel') return openFilter({ edit:true, editRow:row, mode:'channel', state: row._cfState });
    return openFilter({ edit:true, editRow:row, mode:'row', state: row._cfState });
  }

  /* ===================== seed existing demo rows with editable state ===================== */
  function seed() {
    // tag existing custom rows so they open in the builder prefilled
    const tagRow = (sel, kind, state) => { const r = document.querySelector(sel); };
    document.querySelectorAll('#studiolist .cfg-row').forEach((r, i) => {
      r.dataset.kind = 'channel';
      const networks = ['HBO','TV 2','Kringvarp','DR'];
      const isTag = r.querySelector('.src').textContent.includes('tag');
      r._cfState = isTag
        ? { match:'all', include:'all', conditions:[{facet:'tag',op:'isAny',values:['dansk-tv']}], title:r.querySelector('.nm').textContent.trim(), chStyle:'text', chColor:CH_COLORS[4] }
        : { match:'all', include:'all', conditions:[{facet:'network',op:'isAny',values:[networks[i]||'HBO']}], title:r.querySelector('.nm').textContent.trim(), chStyle:'logo', chColor:CH_COLORS[i%CH_COLORS.length] };
      bindRow(r);
    });
    document.querySelectorAll('#rowlist .cfg-row').forEach(r => {
      const isSystem = !!r.querySelector('.badge.ok');
      if (isSystem) return; // system rows aren't filter-editable
      r.dataset.kind = 'row';
      const src = r.querySelector('.src').textContent;
      const vals = (src.match(/“([^”]+)”/g) || []).map(s => s.replace(/[“”]/g,''));
      const facet = src.startsWith('tag') ? 'tag' : 'genre';
      r._cfState = { match:'any', include:'all', conditions:[{facet, op:'isAny', values: vals.length?vals:['Drama']}], title:r.querySelector('.nm').textContent.trim() };
      bindRow(r);
    });
    document.querySelectorAll('#herolist .cfg-row').forEach(r => {
      r.dataset.kind = 'hero';
      const nm = r.querySelector('.nm').textContent.trim().replace(/\s+(New Season|4K|Premiere|Top 10)$/,'');
      const found = TITLES.find(t => nm.startsWith(t.title.slice(0,6))) || TITLES[0];
      const badge = (r.querySelector('.badge') && r.querySelector('.badge').textContent.trim()) || 'None';
      r._cfState = { pick: found, badge, tagline: found.kind==='movie'?'Featured Film':'Featured Series', backdrop:0, useLogo:true };
      bindRow(r);
    });
  }

  /* ===================== wire the "＋ Add" buttons ===================== */
  function wireAdds() {
    const find = (sectionId, label) => {
      const sec = document.getElementById(sectionId);
      if (!sec) return null;
      return [...sec.querySelectorAll('.btn')].find(b => b.textContent.includes(label));
    };
    const aHero = find('sect-hero', 'Add featured'); if (aHero) aHero.onclick = () => openHero();
    const aCh = find('sect-studios', 'Add channel'); if (aCh) aCh.onclick = () => openFilter({ mode:'channel' });
    const aRow = find('sect-rows', 'Add row'); if (aRow) aRow.onclick = () => openFilter({ mode:'row' });
  }

  window.RaviloBuilders = { openFilter, openHero, evaluate, grad, TITLES, FACETS };
  document.addEventListener('DOMContentLoaded', () => { seed(); wireAdds(); });
  if (document.readyState !== 'loading') { seed(); wireAdds(); }
})();
