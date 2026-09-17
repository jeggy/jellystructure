/* Ravilo config — interactive builders (Phase R-build)
   Workbench filter builder (Content rows + Channels) and Hero item builder.
   Queries are BLOCKS: recursive AND/OR groups with per-block NOT and nestable
   sub-blocks. Per-channel rows carry the channel's own query as a locked first
   block. Real title pool → real live match counts. Vanilla JS, no deps.
   Styling via .cf-* classes (ravilo-builders.css). */
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

  const HERO = new Set(['Wasteland Kings', 'Quantum Drift']);   // titles currently in the active viewer's hero carousel (demo)

  /* ---- cast & crew (deterministic; the workbench People axis mirrors admin credits) ----
     Production: the real cast/crew index per title. Demo: a stable 2–3 people per title from a
     shared pool so a person genuinely spans several titles (the point of a person filter). */
  const PEOPLE = ['Sigrun Restorff','Páll Heinason','Marin Klett','Eva Restorff','Tóki á Bø',
    'Lena Björk','Anders Holm','Freya Dahl','Mikkel Sørensen','Ingrid Vold',
    'Johan Máni','Sara Winther','Colin Reeves','Nadia Hassan'];
  function castOf(t){
    const h = hashHue(t.title);
    const n = 2 + (h % 2);   // 2–3 people
    const out = [];
    for (let i = 0; i < n; i++) out.push(PEOPLE[(h + i * 5) % PEOPLE.length]);
    return Array.from(new Set(out));
  }

  /* ---- resolved age-rating certification (deterministic; mirrors the region cascade) ---- */
  function certCode(t) {
    const h = hashHue(t.title + (t.year || ''));
    const kids = t.genres && (t.genres.includes('Animation') || t.genres.includes('Family'));
    const tier = kids ? (h % 2) : (h % 5);
    const dk = ['A', '7', '11', '15', '15'], us = ['G', 'PG', 'PG-13', 'R', 'NC-17'];
    return ((h % 4) === 0 ? us : dk)[Math.min(tier, 4)];   // ~25% resolve via the US fallback
  }

  const FACETS = {
    studio:  { label: 'Studio',  type: 'list', options: ['Blender','Kringvarp','DR','TV 2','HBO','Max Originals','Netflix','BBC'], get: t => [t.studio || t.network] },
    network: { label: 'Network', type: 'list', options: ['HBO','Max Originals','TV 2','Kringvarp','DR','Netflix','BBC','Blender'], get: t => [t.network] },
    genre:   { label: 'Genre',   type: 'list', options: Object.values(G), get: t => t.genres },
    tag:     { label: 'Tag',     type: 'list', options: ['nordic-noir','dansk-tv','4k','staff-pick','award-winner'], get: t => t.tags },
    ageRating: { label: 'Age rating', type: 'list', options: ['A','7','11','15','G','PG','PG-13','R','NC-17'], get: t => [certCode(t)] },
    person:  { label: 'Cast or crew', group: 'People', type: 'list', options: PEOPLE.slice().sort(), get: castOf },
    audioLang:  { label: 'Audio language', group: 'Audio track', type: 'list', options: ['English','Danish','Faroese','Spanish','Untagged'], get: audLangs },
    audioCodec: { label: 'Audio codec',    group: 'Audio track', type: 'list', options: ['E-AC-3','AC-3','DTS','AAC','TrueHD'], get: audCodecs },
    audioTitle: { label: 'Audio track title', group: 'Audio track', type: 'text', get: t => audTitles(t).join(' / ') },
    hero:    { label: 'Hero item', group: 'Ravilo layout', type: 'list', options: ['In hero','Not in hero'], get: t => [HERO.has(t.title) ? 'In hero' : 'Not in hero'] },
    // membership in a channel's content rows — values are { name, state } row filters.
    // contextual: only offered in the builder when a rows context is supplied (e.g. the
    // channel's own rows on the Library coverage hand-off). Evaluated via matchRows().
    row:     { label: 'Content row', group: 'Ravilo layout', type: 'rows', contextual: true },
  };
  const LIST_OPS = [ ['isAny','is any of'], ['isNot','is none of'] ];
  const NUM_OPS  = [ ['gte','is on or after'], ['lte','is before or in'], ['eq','is exactly'] ];
  const TEXT_OPS = [ ['contains','contains'], ['ncontains','does not contain'] ];
  function opsFor(f){ const ty = FACETS[f].type; return ty === 'num' ? NUM_OPS : ty === 'text' ? TEXT_OPS : LIST_OPS; }
  function opLabel(f, op){ return (opsFor(f).find(o => o[0]===op) || opsFor(f)[0])[1]; }

  /* ===================== query tree (blocks) =====================
     A workbench query is a recursive tree:
       group: { kind:'group', join:'and'|'or', not:false, children:[ cond|group ] }
       cond:  { kind:'cond', facet, op, values[] }
     state.root is always a group whose children are the top-level BLOCKS
     (each itself a group); conditions never sit directly at the root. */
  function mkCond(facet, op, values){ facet = facet || 'genre'; return { kind:'cond', facet, op: op || opsFor(facet)[0][0], values: values || [] }; }
  function mkGroup(join, children, not){ return { kind:'group', join: join || 'or', not: !!not, children: children || [] }; }
  function isGroup(n){ return !!n && (n.kind === 'group' || Array.isArray(n.children)); }
  function condLive(c){ return !!(c.values && c.values.length); }
  function nodeLive(n){ return isGroup(n) ? n.children.some(nodeLive) : condLive(n); }
  // legacy flat { match, conditions[] } → tree.  ALL → one block per condition · ANY → a single OR block.
  function migrateState(state){
    if (!state || state.root) return state;
    const conds = (state.conditions || []).map(c => ({ kind:'cond', facet: c.facet, op: c.op, values: (c.values || []).slice() }));
    const live = conds.length ? conds : [mkCond()];
    state.root = state.match === 'any'
      ? mkGroup('and', [ mkGroup('or', live) ])
      : mkGroup('and', live.map(c => mkGroup('or', [c])));
    delete state.conditions; delete state.match;
    return state;
  }
  function matchNode(t, n){
    if (!isGroup(n)) return matchOne(t, n);
    const kids = n.children.filter(nodeLive);
    if (!kids.length) return !n.not;                    // an empty block is neutral
    const hit = n.join === 'or' ? kids.some(k => matchNode(t, k)) : kids.every(k => matchNode(t, k));
    return n.not ? !hit : hit;
  }
  function stateHasConditions(state){
    if (!state) return false;
    return state.root ? nodeLive(state.root) : (state.conditions || []).some(c => c.values && c.values.length);
  }
  // human summary of a query — "(Tag “a” or Genre “b”) and Studio “c”"
  function condSummary(c){
    const f = FACETS[c.facet];
    const valTxt = f.type === 'num' ? c.values[0]
      : f.type === 'rows' ? c.values.map(v => v && v.name).filter(Boolean).join(', ')
      : '“' + c.values.join('”, “') + '”';
    return f.label + ' ' + opLabel(c.facet, c.op) + ' ' + valTxt;
  }
  function groupSummary(g, top){
    if (!isGroup(g)) return condLive(g) ? condSummary(g) : '';
    const kids = g.children.filter(nodeLive).map(k => isGroup(k) ? groupSummary(k, false) : condSummary(k)).filter(Boolean);
    if (!kids.length) return '';
    let s = kids.join(g.join === 'or' ? ' or ' : ' and ');
    if (!top && (kids.length > 1 || g.not)) s = '(' + s + ')';
    if (g.not) s = 'not ' + s;
    return s;
  }
  function querySummary(state){ migrateState(state); return groupSummary(state.root, true); }
  // deep-copy + drop empty conditions / empty groups (root always stays a group)
  function pruneNode(n){
    if (!isGroup(n)) return condLive(n) ? n : null;
    const kids = n.children.map(pruneNode).filter(Boolean);
    if (!kids.length) return null;
    return { kind:'group', join: n.join, not: n.not, children: kids };
  }
  function pruneState(state){
    const s = migrateState(JSON.parse(JSON.stringify(state)));
    s.root = pruneNode(s.root) || mkGroup('and', []);
    return s;
  }

  /* ===================== match evaluation ===================== */
  function matchOne(t, cond) {
    const f = FACETS[cond.facet]; if (!f) return true;
    if (f.type === 'rows') return matchRows(t, cond);
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
  // membership in another saved filter (a content row): each value is { name, state }
  function matchRows(t, cond) {
    const vals = (cond.values || []).filter(v => v && v.state);
    if (!vals.length) return true;
    const hit = vals.some(rv => matchesState(t, rv.state));
    return cond.op === 'isNot' ? !hit : hit;
  }
  // evaluate one title against a whole filter state (shared by evaluate() + the row facet)
  function matchesState(t, state) {
    if (state.include === 'movie' && t.kind !== 'movie') return false;
    if (state.include === 'series' && t.kind !== 'series') return false;
    migrateState(state);
    return matchNode(t, state.root);
  }
  function evaluate(state) {
    return TITLES.filter(t => matchesState(t, state));
  }

  /* Coverage gap: titles inside a channel's filter that no custom content row would show.
     pool = everything matching the channel filter; covered = union of each row's matches
     intersected with the pool; uncovered = pool − covered. System rows (Continue / Newly
     Added) are time-based and not counted. */
  function rowCoverage(channelState) {
    const pool = evaluate(channelState);
    const items = (channelState.rows && channelState.rows.items) || [];
    const covered = new Set();
    const perRow = items.map(it => {
      const inRow = evaluate(it.state).filter(t => pool.includes(t));
      inRow.forEach(t => covered.add(t.title));
      return { title: it.title, count: inRow.length };
    });
    const uncovered = pool.filter(t => !covered.has(t.title));
    return { pool, uncovered, coveredCount: covered.size, perRow };
  }

  /* ===================== gradient helper ===================== */
  function hashHue(s){ let h = 0; for (let i=0;i<s.length;i++) h = (h*31 + s.charCodeAt(i)) % 360; return h; }

  /* ===================== row order (prospective phase 225) =====================
     A workbench row lines up one of three automatic ways, each in either direction, optionally
     behind an ordered hand-picked prefix. Absent `sort` = { by:'added', descending:true } — the
     order every row has today (HomeFeedService: compareByDescending(recencyKey).thenBy(title)).
     `pinned` is an ordered list of ids (titles in this mockup); a pin the filter no longer matches
     is KEPT and skipped on the TV, never deleted behind the admin's back. The server sorts; the TV
     renders — nothing here reaches Ravilo but the already-ordered items. */
  // How many titles the TV shows of a row. Configurable per row (owner decision 2026-09-17); the
  // default is what the viewer sees today. The number shown is also the ceiling on hand-picks.
  const ROW_LIMIT_DEFAULT = 10, ROW_LIMIT_MIN = 3, ROW_LIMIT_MAX = 30;
  function rowLimit(state){ const n = state && +state.limit; return n >= ROW_LIMIT_MIN && n <= ROW_LIMIT_MAX ? n : ROW_LIMIT_DEFAULT; }
  const ORDER_KEYS = {
    added: { label: 'Date added',   dir: ['newest first', 'oldest first'] },
    title: { label: 'Title',        dir: ['A → Z', 'Z → A'] },
    year:  { label: 'Release year', dir: ['newest release first', 'oldest release first'] }
  };
  const DEFAULT_SORT = { by: 'added', descending: true };
  // demo "date added" — days ago, deterministic per title (the catalog's real field is Jellyfin's DateCreated)
  function addedDays(t){ return 1 + (hashHue(t.title + '|added') * 7) % 720; }
  // Jellyfin's own SortName drops a leading article — the same key the browse page should use
  function sortName(t){ return t.title.replace(/^(the|a|an|de|det|en|et)\s+/i, '').toLowerCase(); }
  const CMP = {
    added: (x, y) => addedDays(x) - addedDays(y) || sortName(x).localeCompare(sortName(y)),        // natural = newest first
    title: (x, y) => sortName(x).localeCompare(sortName(y)),
    year:  (x, y) => (y.year || 0) - (x.year || 0) || sortName(x).localeCompare(sortName(y))       // natural = newest release first
  };
  function rowSort(state){ const s = state && state.sort; return s && ORDER_KEYS[s.by] ? { by: s.by, descending: s.descending !== false } : Object.assign({}, DEFAULT_SORT); }
  function isDefaultSort(state){ const s = rowSort(state); return s.by === 'added' && s.descending; }
  // 'added'/'year' comparators already run newest-first, so descending = natural; 'title' natural is A → Z (= descending:false)
  function autoSorted(list, state){ const s = rowSort(state); const out = list.slice().sort(CMP[s.by]); const natural = s.by === 'title' ? !s.descending : s.descending; return natural ? out : out.reverse(); }
  function pinsOf(state){ return (state && state.pinned) || []; }
  // matched pins in pin order, then everything else under the automatic key
  function orderTitles(matches, state){
    const pins = pinsOf(state).map(n => matches.find(t => t.title === n)).filter(Boolean);
    return pins.concat(autoSorted(matches.filter(t => !pins.includes(t)), state));
  }
  function stalePins(matches, state){ return pinsOf(state).filter(n => !matches.some(t => t.title === n)); }
  function dirLabel(state){ const s = rowSort(state); return ORDER_KEYS[s.by].dir[s.descending ? 0 : 1]; }
  function keyPhrase(state){ const s = rowSort(state); return s.by === 'title' ? 'title ' + dirLabel(state) : dirLabel(state); }
  // the row-list summary, in words — never "asc/desc"; '' when the row runs today's order with no hand-picks
  function orderSummary(state){
    const n = pinsOf(state).length;
    if (n) return n + ' hand-picked, then ' + keyPhrase(state);
    return isDefaultSort(state) ? '' : keyPhrase(state);
  }
  function grad(s){ const h = hashHue(s); return `linear-gradient(150deg, hsl(${h} 46% 36%), hsl(${(h+40)%360} 52% 14%))`; }
  // value-picker glyphs: a person avatar (initials on a stable gradient) + an audio-language flag
  function personInitials(name){ const p = String(name).trim().split(/\s+/); return (((p[0]||'')[0]||'') + ((p[1]||'')[0]||'')).toUpperCase(); }
  const AUD_CC = { English:'gb', Danish:'dk', Faroese:'fo', Spanish:'es', Icelandic:'is', Norwegian:'no', Swedish:'se' };
  function valIcon(facet, v){
    if (facet === 'person') return `<span class="wb-av" style="background:${grad(v)}">${personInitials(v)}</span>`;
    if (facet === 'audioLang'){ const cc = AUD_CC[v]; return cc ? `<span class="fi fi-${cc} wb-flag"></span>` : `<span class="wb-flag wb-flag-none">—</span>`; }
    return '';
  }

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

  // full-screen editor host (channels open as their own screen, not a popup) — so the
  // hero-item picker can open as a normal modal popup on top of it.
  const screenRoot = document.getElementById('cf-screen-root');
  function onScreenEsc(e){ if (e.key === 'Escape' && root.style.display !== 'block') closeScreen(); }
  function closeScreen(){ if (screenRoot) screenRoot.innerHTML = ''; document.body.classList.remove('cf-screen-on'); document.removeEventListener('keydown', onScreenEsc); window.scrollTo(0, 0); }
  function openScreen(node){
    if (!screenRoot) return openModal(node);
    screenRoot.innerHTML = ''; screenRoot.appendChild(node);
    document.body.classList.add('cf-screen-on'); window.scrollTo(0, 0);
    document.addEventListener('keydown', onScreenEsc);
  }
  function el(html){ const d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstElementChild; }

  /* lightweight popover for picking facet values / numeric entry */
  function popover(anchor, inner, onPick) {
    document.querySelectorAll('.cf-pop').forEach(p => p.remove());
    const pop = document.createElement('div'); pop.className = 'cf-pop card';
    pop.innerHTML = inner;
    document.body.appendChild(pop);
    const r = anchor.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - pop.offsetWidth - 12)) + 'px';
    pop.style.top = Math.max(8, Math.min(r.bottom + 6, window.innerHeight - pop.offsetHeight - 10)) + 'px';
    pop.addEventListener('click', e => { const it = e.target.closest('[data-val]'); if (it) { onPick(it.dataset.val); } });
    const close = e => { if (!pop.contains(e.target) && e.target !== anchor) { pop.remove(); document.removeEventListener('mousedown', close); } };
    setTimeout(() => document.addEventListener('mousedown', close), 0);
    return pop;
  }

  /* ============================================================
     FILTER BUILDER (workbench) — used by Content rows + Channels
     ============================================================ */
  const CH_COLORS = ['linear-gradient(135deg,#3b2a78,#15102e)','linear-gradient(135deg,#e3122b,#7d0a1a)','linear-gradient(135deg,#0a93a6,#063d47)','linear-gradient(135deg,#1455d8,#0a2766)','linear-gradient(135deg,#c8102e,#1a1a1a)'];

  // Channel-button logo assets (the user's brand-logo library) + render helper.
  const PRESET_LOGOS = [
    { id:'hbo', label:'HBO' }, { id:'tv2', label:'TV 2' }, { id:'kvf', label:'KvF' },
    { id:'dr', label:'DR' }, { id:'nrk', label:'NRK' }, { id:'viaplay', label:'Viaplay' }
  ];
  function logoAsset(label){
    const svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 220 88'><text x='110' y='59' text-anchor='middle' font-family='Space Grotesk, Sora, sans-serif' font-weight='800' font-size='44' letter-spacing='-1' fill='#ffffff'>" + label + "</text></svg>";
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }
  function customCss(cg){ return cg.type === 'solid' ? cg.c1 : 'linear-gradient(' + cg.angle + 'deg, ' + cg.c1 + ', ' + cg.c2 + ')'; }
  // per-display padding (separate sets for logo image vs. text) → inline style
  function chPadCss(state){
    const mode = state.chStyle === 'text' ? 'text' : 'logo';
    const p = (state.chPad && state.chPad[mode]) || {};
    const t = p.t||0, r = p.r||0, b = p.b||0, l = p.l||0;
    if (!(t||r||b||l)) return '';
    return 'box-sizing:border-box;padding:' + t + 'px ' + r + 'px ' + b + 'px ' + l + 'px;';
  }
  function chLogoSrc(state){
    if (!state || !state.chLogo) return null;
    if (state.chLogo.type === 'upload') return state.chLogo.src;
    const p = PRESET_LOGOS.find(l => l.id === state.chLogo.id);
    return p ? logoAsset(p.label) : null;
  }
  // Build the channel button (the .studio-wm chip) for a given state: image when Logo, text when Text.
  function channelWM(state, title, opts){
    opts = opts || {};
    const cls = opts.cls || 'studio-wm';
    const base = 'background:' + (state.chColor || CH_COLORS[0]) + ';' + chPadCss(state) + (opts.style || '');
    if (state.chStyle === 'logo'){
      const src = chLogoSrc(state);
      if (src) return '<div class="' + cls + '" style="' + base + '"><img class="cf-wm-img" src="' + src + '" alt=""></div>';
      return '<div class="' + cls + '" style="' + base + '">' + (title || '').slice(0,3).toUpperCase() + '</div>';
    }
    const t = state.chText || title || 'Channel';
    return '<div class="' + cls + '" style="' + base + 'font-size:' + (opts.font || '.62rem') + ';">' + t.slice(0,12) + '</div>';
  }

  function openFilter(opts) {
    opts = opts || {};
    const isChannel = opts.mode === 'channel';
    const isLibrary = opts.mode === 'library';
    const asScreen = isChannel;   // channels get a dedicated full screen
    const state = migrateState(opts.state) || {
      include: 'all',
      root: mkGroup('and', [ mkGroup('or', [ mkCond() ]) ]),
      title: '', chStyle: 'logo', chColor: CH_COLORS[0], chText: '', chLogo: null, customGrad: null,
      chPad: { logo: { t:0, r:0, b:0, l:0 }, text: { t:0, r:0, b:0, l:0 } },
      hero: { on:false, items:[], height:48, advance:7 },
      rows: { mode:'inherit', items:[], system: { continue:{ show:true }, newly:{ show:true, merge:false } } }
    };
    state.root.children = state.root.children.map(b => isGroup(b) ? b : mkGroup('or', [ b ]));
    if (!state.root.children.length) state.root.children.push(mkGroup('or', [ mkCond() ]));
    state.root.children.forEach(b => { if (!b.children.length) b.children.push(mkCond()); });

    // padding panel open/closed per display mode — defaults open only when already configured
    const padOpen = {};
    function isPadOpen(m){
      if (padOpen[m] === undefined) { const p = (state.chPad && state.chPad[m]) || {}; padOpen[m] = !!(p.t||p.r||p.b||p.l); }
      return padOpen[m];
    }
    function padSummary(m){ const p = (state.chPad && state.chPad[m]) || {}; return (p.t||p.r||p.b||p.l) ? `${p.t||0}/${p.r||0}/${p.b||0}/${p.l||0}` : ''; }

    const node = el(`<div class="cf-modal${asScreen ? ' cf-screen' : ''}" style="max-width:${asScreen ? '1040px' : '1000px'};"></div>`);
    function dismiss(){ asScreen ? closeScreen() : closeModal(); }

    function firstLiveCond(n){
      if (!isGroup(n)) return condLive(n) ? n : null;
      for (const k of n.children){ const f = firstLiveCond(k); if (f) return f; }
      return null;
    }
    function autoTitle() {
      const c = firstLiveCond(state.root);
      if (!c) return isChannel ? 'New channel' : 'New row';
      if (FACETS[c.facet].type === 'num') return FACETS[c.facet].label + ' ' + opLabel(c.facet,c.op) + ' ' + c.values[0];
      return c.values.slice(0,2).map(v => (v && typeof v === 'object') ? v.name : v).join(' & ') + (c.values.length>2 ? ' +' : '');
    }

    function render() {
      let matches = evaluate(state);
      if (opts.channelContext && opts.channelContext.state) matches = matches.filter(t => matchesState(t, opts.channelContext.state));
      const isRow = !isChannel && !isLibrary;
      const ordered = isRow ? orderTitles(matches, state) : matches;
      const pins = pinsOf(state), stale = isRow ? stalePins(matches, state) : [];
      const handpick = isRow && (state.handpick || pins.length > 0);
      const livePinN = pins.filter(p => matches.some(t => t.title === p)).length;
      const showRank = isRow && (handpick || !isDefaultSort(state));
      const limit = isRow ? rowLimit(state) : Infinity;
      const full = handpick && pins.length >= limit;
      const titleVal = state.title || autoTitle();
      const isCustom = isChannel && state.chColor && CH_COLORS.indexOf(state.chColor) === -1;
      const hero = state.hero || {};
      const heroOn = !!hero.on, heroItems = hero.items || [];
      const rowsCfg = state.rows || { mode: 'inherit', items: [] };
      const rowsMode = rowsCfg.mode || 'inherit', rowItems = rowsCfg.items || [];
      const sys = rowsCfg.system || { continue:{ show:true }, newly:{ show:true, merge:false } };
      const sysC = sys.continue || { show:true };
      const sysN = sys.newly || { show:true, merge:false };
      // coverage gap — titles in this channel not shown by any custom row
      const cov = (isChannel && rowsMode === 'custom') ? rowCoverage(state) : null;
      let coverageHtml = '';
      if (cov && cov.pool.length) {
        const u = cov.uncovered, poolN = cov.pool.length;
        const head = `<div class="row center" style="margin-bottom:2px;"><span class="cf-eyebrow">Not shown by any row</span><span class="spacer"></span><span class="badge ${u.length ? 'warn' : 'ok'}" style="flex:none;">${u.length} of ${poolN}</span></div>`;
        if (!u.length) {
          coverageHtml = `<div class="cf-coverage ok">${head}<div class="cf-cov-note"><span class="cf-cov-ic">✓</span><span>Every title in this channel appears in at least one row — nothing falls through the gaps.</span></div></div>`;
        } else {
          const lead = cov.perRow.length === 0
            ? `No rows yet — all <b>${u.length}</b> titles that match this channel would be unreachable.`
            : `These <b>${u.length}</b> titles match the channel filter but <b>aren’t shown by any content row</b>, so viewers browsing this channel won’t find them.`;
          coverageHtml = `<div class="cf-coverage warn">${head}
            <div class="tiny" style="margin:2px 0 11px;line-height:1.5;color:var(--ink-soft);">${lead} <span class="cf-cov-sys">System rows (Continue, Newly Added) aren’t counted.</span></div>
            <div class="cf-cov-grid">${u.map(m => `<div class="cf-mp" title="${m.title} · ${m.kind === 'movie' ? 'Film' : 'Series'} · ${m.year} · ${m.network}" style="background:${grad(m.title)};"><span class="t">${m.title}</span></div>`).join('')}</div>
            <div class="cf-cov-actions"><button type="button" class="btn sm ghost" data-coveradd>＋ Add a catch-all row for these</button><a class="cf-cov-link" data-coverlink href="library.html?view=coverage">Open these ${u.length} in Library ↗</a></div>
          </div>`;
        }
      }
      const cg = state.customGrad || { type: 'gradient', c1: '#7b6ef0', c2: '#3fb6f5', angle: 135 };
      const PAD_SIDES = [['t','Top'],['r','Right'],['b','Bottom'],['l','Left']];
      const padOf = m => (state.chPad && state.chPad[m]) || {};
      const padFields = m => PAD_SIDES.map(([s,lbl]) => `<label class="cf-padfield"><span class="tiny muted">${lbl}</span><input type="number" min="0" max="40" class="input cf-pad" data-padmode="${m}" data-pad="${s}" value="${padOf(m)[s]||0}"></label>`).join('');
      const PAD_ICON = '<svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true"><rect x="1.5" y="1.5" width="13" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="1.3"/><rect x="5" y="5" width="6" height="6" rx="1" fill="currentColor"/></svg>';
      const padPanel = (m, hint) => `<div class="cf-padwrap ${isPadOpen(m)?'open':''}">
        <button type="button" class="cf-padtoggle" data-padtoggle="${m}" title="Button padding">${PAD_ICON}<span class="tiny">Padding</span>${padSummary(m)?`<span class="cf-padsum">${padSummary(m)}</span>`:''}<span class="cf-padcaret">▾</span></button>
        <div class="cf-padbody"><div class="tiny muted" style="margin:10px 0 8px;">${hint}</div><div class="cf-padgrid">${padFields(m)}</div></div>
      </div>`;
      node.innerHTML = `
        ${asScreen ? `
        <div class="cf-screen-head">
          <span class="cf-back" data-x>‹ Back to layout</span>
          <b style="font-size:1rem;">${opts.headTitle || ((opts.edit ? 'Edit' : 'New') + ' channel')}</b>
          <span class="badge">⚙ Workbench</span>
          <span class="spacer"></span>
          <span class="btn sm ghost" data-x>Cancel</span>
          <span class="btn sm primary" data-save>${opts.saveLabel || (opts.edit ? 'Save changes' : 'Add channel')}</span>
        </div>` : `
        <div class="cf-modal-head">
          <span class="cf-grab">⠿</span>
          <b style="font-size:.98rem;">${opts.headTitle || ((opts.edit ? 'Edit' : 'New') + ' ' + (isChannel ? 'channel' : 'content row'))}</b>
          <span class="badge">⚙ Workbench</span>
          <span class="spacer"></span>
          <span class="tiny muted">esc to cancel</span>
          <span class="cf-x" data-x>✕</span>
        </div>`}
        <div class="cf-body">
          <div class="cf-main">
            <div class="row center" style="gap:8px;">
              <span class="cf-eyebrow">Query</span>
              <span class="tiny muted">blocks combine top-to-bottom — click a connector pill to flip AND / OR</span>
            </div>

            <div class="cf-blocks"></div>
            <div class="cf-addblock-row"><span class="vchip add" data-addblock style="padding:7px 12px;">＋ Add block</span></div>

            <div class="row center" style="gap:14px;margin-top:16px;flex-wrap:wrap;">
              <div><span class="cf-eyebrow">Include</span>
                <div class="seg cf-include" style="margin-top:7px;">
                  <span class="${state.include==='all'?'on':''}" data-inc="all">All</span>
                  <span class="${state.include==='movie'?'on':''}" data-inc="movie">Movies</span>
                  <span class="${state.include==='series'?'on':''}" data-inc="series">Series</span>
                  <span class="${state.include==='musicvideo'?'on':''}" data-inc="musicvideo">Music videos</span>
                </div></div>
              <div style="flex:1;min-width:160px;${isLibrary?'display:none;':''}"><span class="cf-eyebrow">${isChannel?'Channel name':'Row title'}</span>
                <input class="input cf-title" style="margin-top:7px;" value="${titleVal.replace(/"/g,'&quot;')}" placeholder="${autoTitle()}"></div>
            </div>

            ${isRow ? orderHtml() : ''}

            ${isChannel ? `
            <hr class="dash" style="margin:16px 0;">
            <span class="cf-eyebrow">Channel button</span>
            <div class="row center" style="gap:14px;margin-top:11px;flex-wrap:wrap;">
              <div><div class="tiny muted" style="margin-bottom:6px;">Display</div>
                <span class="seg cf-style"><span class="${state.chStyle==='logo'?'on':''}" data-st="logo">Logo</span><span class="${state.chStyle==='text'?'on':''}" data-st="text">Text</span></span></div>
              <div><div class="tiny muted" style="margin-bottom:6px;">Brand color</div>
                <div class="row" style="gap:7px;flex-wrap:wrap;" id="cf-colors">${CH_COLORS.map(c => `<span class="cf-sw ${c===state.chColor?'on':''}" data-color="${c}" style="background:${c};"></span>`).join('')}<span class="cf-sw cf-sw-custom ${isCustom?'on':''}" data-customsw title="Custom color or gradient" style="${isCustom?`background:${state.chColor};`:''}">${isCustom?'':'+'}</span></div></div>
            </div>
            <div class="cf-gradbuilder" style="display:${isCustom?'block':'none'};">
              <div class="row center" style="gap:9px;margin-bottom:11px;">
                <span class="cf-eyebrow">Custom</span>
                <span class="seg cf-gradmode"><span class="${cg.type==='solid'?'on':''}" data-gm="solid">Solid</span><span class="${cg.type==='gradient'?'on':''}" data-gm="gradient">Gradient</span></span>
              </div>
              <div class="row center" style="gap:16px;flex-wrap:wrap;">
                <label class="cf-cfield"><span class="tiny muted">${cg.type==='gradient'?'From':'Color'}</span><input type="color" class="cf-c1" value="${cg.c1}"></label>
                <label class="cf-cfield cf-c2field" style="display:${cg.type==='gradient'?'':'none'};"><span class="tiny muted">To</span><input type="color" class="cf-c2" value="${cg.c2}"></label>
                <label class="cf-cfield cf-anglefield" style="display:${cg.type==='gradient'?'':'none'};flex:1;min-width:150px;"><span class="tiny muted">Angle <b class="cf-angle-val">${cg.angle}\u00b0</b></span><input type="range" min="0" max="360" step="5" class="cf-angle" value="${cg.angle}"></label>
              </div>
            </div>
            ${state.chStyle==='logo' ? `
              <div class="tiny muted" style="margin:12px 0 8px;">Pick a logo asset or upload your own — a transparent PNG or SVG sits cleanly on the brand color.</div>
              <div class="cf-logogrid">
                ${PRESET_LOGOS.map(l => `<button class="cf-logotile ${state.chLogo&&state.chLogo.type==='preset'&&state.chLogo.id===l.id?'on':''}" data-logo="${l.id}" title="${l.label}"><img src="${logoAsset(l.label)}" alt="${l.label}"></button>`).join('')}
                ${state.chLogo&&state.chLogo.type==='upload' ? `<button class="cf-logotile on" data-logo="__current" title="${(state.chLogo.label||'uploaded').replace(/"/g,'&quot;')}"><img src="${state.chLogo.src}" alt="uploaded"></button>` : ''}
                <button class="cf-logotile cf-logoupload" data-upload><span class="cf-up-i">⤒</span><span class="tiny">Upload</span></button>
              </div>
              <input type="file" accept="image/png,image/svg+xml,image/*" class="cf-logofile" style="display:none;">
              ${padPanel('logo', 'Inset the image inside the button (px).')}
            ` : `
              <div class="tiny muted" style="margin:12px 0 8px;">Type the label shown on the channel button.</div>
              <input class="input cf-chtext" value="${(state.chText||'').replace(/"/g,'&quot;')}" placeholder="${(state.title||autoTitle()).replace(/"/g,'&quot;')}" style="max-width:300px;">
              ${padPanel('text', 'Inset the label inside the button (px).')}
            `}` : ''}

            ${isChannel ? `
            <hr class="dash" style="margin:16px 0;">
            <div class="row center"><span class="cf-eyebrow">Page hero</span><span class="badge info" style="margin-left:8px;font-size:.58rem;">optional</span><span class="spacer"></span><span class="toggle ${heroOn?'on':''}" data-herotog></span></div>
            <div class="tiny muted" style="margin:7px 0 0;">Show a hero carousel at the top of this channel’s page. Off = the page opens straight into the rows.</div>
            ${heroOn ? `
              <div class="cf-herobox">
                <div class="cf-herolist">${heroItems.length ? heroItems.map((it,i)=>`<div class="cf-heroitem" data-hiedit="${i}"><span class="cf-heromini" style="background:${grad(it.pick.title+(it.backdrop||0))};"></span><span style="flex:1;min-width:0;">${it.pick.title}${it.badge&&it.badge!=='None'?` <span class="badge" style="font-size:.56rem;">${it.badge}</span>`:''}</span><button type="button" class="cf-herorm" data-herorm="${i}" title="Remove">✕</button></div>`).join('') : `<div class="tiny muted" style="padding:9px 2px;">No hero items yet — add one or more titles.</div>`}</div>
                <button type="button" class="btn sm ghost" data-heroadd style="margin-top:9px;">＋ Add hero item</button>
                <div class="tiny muted" style="margin-top:13px;line-height:1.5;"><b>Height</b> and <b>auto-advance</b> follow the global <b>Home hero</b> settings — set once for every hero.</div>
              </div>
            ` : ''}
            ` : ''}

            ${isChannel ? `
            <hr class="dash" style="margin:16px 0;">
            <div class="row center"><span class="cf-eyebrow">Content rows</span><span class="spacer"></span>
              <span class="seg cf-rowsmode"><span class="${rowsMode==='inherit'?'on':''}" data-rmode="inherit">Same as Home</span><span class="${rowsMode==='custom'?'on':''}" data-rmode="custom">Custom</span></span>
            </div>
            <div class="tiny muted" style="margin:7px 0 0;">By default this channel shows the <b>Home content rows</b>, scoped to it. Switch to <b>Custom</b> to give this channel its own set.</div>
            ${rowsMode==='custom' ? `
              <div class="cf-sysrows">
                <div class="cf-eyebrow" style="margin-bottom:2px;">System rows</div>
                <div class="tiny muted" style="margin:0 0 9px;line-height:1.5;">Time-based rows that pin to the top — not filter-built. Both show <b>only titles from this channel</b>.</div>
                <div class="cf-sysrow${sysC.show?'':' off'}">
                  <span class="badge ok" style="flex:none;">system</span>
                  <div class="cf-sysrow-main"><div class="nm">Continue Watching</div><div class="src">In-progress titles from this collection</div></div>
                  <span class="toggle ${sysC.show?'on':''}" data-systog="continue"></span>
                </div>
                <div class="cf-sysrow${sysN.show?'':' off'}">
                  <span class="badge ok" style="flex:none;">system</span>
                  <div class="cf-sysrow-main"><div class="nm">Newly Added</div><div class="src">Newest titles in this collection · ${sysN.merge?'combined':'Movies + Series'}</div></div>
                  <span class="toggle ${sysN.show?'on':''}" data-systog="newly"></span>
                </div>
                <div class="cf-sysmerge${sysN.show?'':' off'}">
                  <span class="tiny">Merge movies &amp; series into one “Newly Added” row</span><span class="spacer"></span>
                  <span class="toggle ${sysN.merge?'on':''}" data-sysmerge></span>
                </div>
              </div>
              <div class="cf-herobox">
                <div class="cf-eyebrow" style="margin-bottom:8px;">Filter rows</div>
                <div class="cf-herolist">${rowItems.length ? rowItems.map((it,i)=>`<div class="cf-heroitem" data-rdedit="${i}"><span class="badge info" style="font-size:.56rem;flex:none;">row</span><span style="flex:1;min-width:0;"><b>${it.title}</b>${it.summary?` <span class="muted">· ${it.summary}</span>`:''}</span><span class="badge" style="font-size:.56rem;flex:none;">${evaluate(it.state).filter(t => matchesState(t, state)).length} in this channel</span><button type="button" class="cf-herorm" data-rdrm="${i}" title="Remove">✕</button></div>`).join('') : `<div class="tiny muted" style="padding:9px 2px;">No custom rows yet — add one or more.</div>`}</div>
                <button type="button" class="btn sm ghost" data-rowadd style="margin-top:9px;">＋ Add row</button>
                <div class="tiny muted" style="margin-top:11px;line-height:1.5;">These workbench rows render below the system rows, replacing the Home rows on this channel’s page.</div>
              </div>
              ${coverageHtml}
            ` : ''}
            ` : ''}

            <div class="note blue" style="margin-top:16px;display:flex;gap:10px;align-items:center;padding:10px 12px;">
              <span class="badge info" style="flex:none;">${isLibrary ? 'one filter' : 'reusable'}</span>
              <span class="tiny" style="flex:1;">${isLibrary ? 'Build it here, then <b>Save filter as…</b> a Channel or Content row straight into a viewer’s Ravilo layout — same conditions, no retyping.' : 'This same filter works on the <b>Library</b> page — open it anytime from <span class="mono">Library → Filter</span>.'}</span>
            </div>
          </div>

          <div class="cf-side">
            <span class="cf-eyebrow">Matches now${showRank ? ' · in this order' : ''}</span>
            <div class="row center" style="gap:10px;margin-top:10px;">
              <span class="cf-matchcount"><span class="n">${matches.length}</span><span class="l">titles${opts.channelContext ? ' in “' + opts.channelContext.name + '”' : ''}</span></span>
              <span class="spacer"></span><span class="badge ${matches.length?'ok':'warn'}">${matches.length?'live':'none'}</span>
            </div>
            <div class="cf-prevgrid" style="display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:12px;">
              ${ordered.slice(0,12).map((m, i) => `<div class="cf-mp${handpick && i >= livePinN ? ' cf-auto' : ''}${isRow && i >= limit ? ' cf-past' : ''}" title="${m.title} · ${m.year}" style="background:${grad(m.title)};">${showRank ? `<span class="cf-rk">${i+1}</span>` : ''}<span class="t">${m.title}</span></div>`).join('') || '<div class="tiny muted" style="grid-column:1/-1;padding:18px 4px;">No titles match — loosen a condition.</div>'}
            </div>
            ${matches.length>12 ? `<div class="tiny muted" style="margin-top:8px;line-height:1.5;">+${matches.length-12} more${isRow && matches.length > limit ? ` · the TV shows the <b>first ${limit}</b> in this order, See all shows every one` : ''}</div>` : ''}
            ${isChannel ? `${channelWM(state, state.title||autoTitle(), {cls:'studio-wm cf-chprev', style:'width:100%;height:46px;margin-top:14px;', font:'1.05rem'})}<div class="tiny muted center-x" style="margin-top:8px;">channel button preview</div>` : ''}
            ${isLibrary ? '' : `<a href="library.html" class="tiny" style="display:block;margin-top:14px;color:var(--acc-ink);">Open these ${matches.length} in Library ↗</a>`}
          </div>
        </div>
        <div class="row center" style="padding:13px 16px;border-top:1px solid var(--line);background:var(--fill-2);">
          <span class="tiny muted">${isLibrary ? 'Filters the library grid live.' : (isChannel?'The same conditions power this channel’s scoped rows on the TV.':'Added to the bottom of the stack · drag to reorder after.')}</span>
          <span class="spacer"></span>
          <span class="btn sm ghost" data-x>Cancel</span>
          <span class="btn sm primary" data-save>${opts.saveLabel || (opts.edit ? 'Save changes' : (isChannel ? 'Add channel' : 'Add row'))}</span>
        </div>`;
      renderBlocks();
      wire();

      /* ---- Order section (rows only — system rows never open this editor; channels have no order of their own) ---- */
      function orderHtml(){
        const s = rowSort(state);
        const keySeg = cls => `<div class="seg ${cls}">${Object.keys(ORDER_KEYS).map(k => `<span class="${s.by===k && (cls !== 'cf-ordkeys' || !handpick) ? 'on' : ''}" data-okey="${k}">${ORDER_KEYS[k].label}</span>`).join('')}${cls === 'cf-ordkeys' ? `<span class="${handpick?'on':''}" data-okey="pins">Hand-picked first</span>` : ''}</div>`;
        const dirBtn = `<button type="button" class="cf-dirbtn" data-odir title="Flip the direction">${dirLabel(state)} <span class="arr">⇅</span></button>`;
        const scopeName = opts.channelContext ? opts.channelContext.name : null;
        let body = '';
        if (handpick) {
          const livePins = pins.map(n => matches.find(t => t.title === n)).filter(Boolean);
          const autoRest = autoSorted(matches.filter(t => !livePins.includes(t)), state);
          const SHOW = 6;
          const pinTile = (t, i) => `<div class="cf-mp cf-pin" draggable="true" data-pin="${t.title}" title="${t.title} · ${t.year} — drag to reorder, or across the seam to release" style="background:${grad(t.title)};"><span class="cf-rk">${i+1}</span><span class="cf-unpin" data-unpin="${t.title}" title="Release">✕</span><span class="t">${t.title}</span></div>`;
          const staleTile = (n, i) => `<div class="cf-mp cf-pin cf-stale" draggable="true" data-pin="${n}" title="${n} — hand-picked but no longer matches this row; skipped on the TV" style="background:${grad(n)};"><span class="cf-rk">${i+1}</span><span class="cf-stalemark">!</span><span class="cf-unpin" data-unpin="${n}" title="Release">✕</span><span class="t">${n}</span></div>`;
          const pinTiles = pins.map((n, i) => { const t = matches.find(x => x.title === n); return t ? pinTile(t, i) : staleTile(n, i); }).join('');
          const autoTile = t => `<div class="cf-mp cf-auto${full ? ' cf-nopick' : ''}" draggable="${full ? 'false' : 'true'}" data-auto="${t.title}" title="${t.title} · ${t.year}${full ? ' — all ' + limit + ' places are hand-picked' : ' — drag left of the seam (or click) to hand-pick'}" style="background:${grad(t.title)};"><span class="t">${t.title}</span></div>`;
          body = `
          <div class="cf-strip">
            <div class="cf-zone cf-zone-pins" data-dropzone="pins">${pinTiles || `<div class="tiny muted cf-zone-empty">Drag a title here — or click one on the right — to hand-pick it</div>`}</div>
            <div class="cf-seam" title="Left: your order. Right: the automatic order"><span>then ${keyPhrase(state)}</span></div>
            <div class="cf-zone cf-zone-auto" data-dropzone="auto">${autoRest.slice(0, SHOW).map(autoTile).join('')}${autoRest.length > SHOW ? `<span class="cf-more">+${autoRest.length - SHOW}</span>` : ''}${!autoRest.length ? '<span class="tiny muted" style="padding:0 6px;">every match is hand-picked</span>' : ''}</div>
          </div>
          <div class="tiny muted" style="margin-top:8px;line-height:1.5;"><b>${livePins.length} hand-picked</b>, then ${autoRest.length} automatic${autoRest.length + livePins.length > limit ? ` (the TV shows the first ${limit})` : ''}. ${full ? `All <b>${limit}</b> places are hand-picked — release one, or show more titles, to pick another.` : `Drag a title <b>left of the seam</b> to hand-pick it, back across to let the sort place it; drag within the left zone to reorder. Up to <b>${limit}</b> — the number this row shows.`}</div>
          ${stale.length ? `<div class="note warn cf-ordnote"><span class="badge warn" style="flex:none;">${stale.length} skipped</span><span class="tiny">${stale.length === 1 ? `<b>${stale[0]}</b> is hand-picked but no longer matches this row${scopeName ? ` inside “${scopeName}”` : ''}. It stays in your list and is <b>skipped on the TV</b> until it matches again, or you release it.` : `<b>${stale.length} hand-picked titles</b> (${stale.join(', ')}) no longer match this row${scopeName ? ` inside “${scopeName}”` : ''}. They stay in your list and are <b>skipped on the TV</b> until they match again, or you release them.`}</span></div>` : ''}
          <div class="cf-thenby"><span class="tiny" style="font-weight:600;">Then the rest by</span>${keySeg('cf-ordfallback')}${dirBtn}<span class="spacer"></span>${full ? '' : `<span class="cf-pinsearch" data-pinsearch title="Find a title to hand-pick">⌕ <span class="muted">Pin a title…</span></span>`}</div>`;
        } else {
          body = `<div class="tiny muted" style="margin-top:8px;line-height:1.5;">The TV shows this row's <b>first ${limit}</b> titles in this order; the rest are one Select away on <b>See all</b>, which opens in the same order.${isDefaultSort(state) ? ' This is the default — what every row does today.' : ''}</div>`;
        }
        return `<div class="cf-order">
          <div class="row center" style="gap:8px;"><span class="cf-eyebrow">Order</span><span class="tiny muted">how the row lines up on the TV${scopeName ? ` — inside “${scopeName}”, hand-picks outside it simply don’t appear` : ''}</span></div>
          <div class="row center" style="gap:10px;margin-top:8px;flex-wrap:wrap;">${keySeg('cf-ordkeys')}${handpick ? '' : dirBtn}<span class="spacer"></span><span class="cf-limit" title="How many titles the TV shows of this row (${ROW_LIMIT_MIN}–${ROW_LIMIT_MAX})"><span class="tiny">Show</span><button type="button" data-olimit="-1" ${limit <= Math.max(ROW_LIMIT_MIN, pins.length) ? 'disabled' : ''}>−</button><b>${limit}</b><button type="button" data-olimit="1" ${limit >= ROW_LIMIT_MAX ? 'disabled' : ''}>+</button><span class="tiny muted">titles</span></span></div>
          ${body}
        </div>`;
      }
    }

    /* ---------------- recursive block renderer ---------------- */
    const MAX_DEPTH = 3;    // block → sub-block → one more; deeper turns unreadable
    const LOCK_SVG = '<svg width="11" height="12" viewBox="0 0 11 12" aria-hidden="true"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"></rect><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.5"></path></svg>';
    function nodeAt(path){ let n = state.root; for (const i of path.split('.')) n = n.children[+i]; return n; }
    function parentOf(path){ const parts = path.split('.'); const idx = +parts.pop(); let n = state.root; for (const i of parts) n = n.children[+i]; return { parent: n, idx }; }
    // keep the editor usable after removals: dissolve empty groups, root keeps ≥1 block
    function cleanup(){
      (function walk(g){
        g.children.forEach(k => { if (isGroup(k)) walk(k); });
        g.children = g.children.filter(k => !isGroup(k) || k.children.length);
      })(state.root);
      if (!state.root.children.length) state.root.children.push(mkGroup('or', [ mkCond() ]));
    }
    function scopePool(){
      const base = opts.scopeState ? evaluate(opts.scopeState) : TITLES;
      return base.filter(t => state.include === 'movie' ? t.kind === 'movie' : state.include === 'series' ? t.kind === 'series' : true);
    }
    function condHtml(c, path, locked){
      const f = FACETS[c.facet];
      const x = vi => locked ? '' : ` <span class="x" data-rmval="${path}:${vi}">✕</span>`;
      let valHtml = '';
      if (f.type === 'num') {
        valHtml = c.values.length ? `<span class="vchip${locked?' lk':''}">${c.values[0]}${x(0)}</span>`
          : (locked ? '' : `<span class="vchip add" data-num="${path}">＋ value</span>`);
      } else if (f.type === 'text') {
        valHtml = c.values.length ? `<span class="vchip${locked?' lk':''}">“${c.values[0]}”${x(0)}</span>`
          : (locked ? '' : `<span class="vchip add" data-text="${path}">＋ text…</span>`);
      } else if (f.type === 'rows') {
        valHtml = c.values.map((v, vi) => `<span class="vchip${locked?' lk':''}">${(v && v.name) || ''}${x(vi)}</span>`).join('')
          + (locked ? '' : ((opts.rowsContext && opts.rowsContext.length) ? `<span class="vchip add" data-pickrows="${path}">＋</span>` : (c.values.length ? '' : `<span class="tiny muted">no rows</span>`)));
      } else {
        valHtml = c.values.map((v, vi) => `<span class="vchip${locked?' lk':''}">${v}${x(vi)}</span>`).join('')
          + (locked ? '' : `<span class="vchip add" data-pick="${path}">＋</span>`);
      }
      const facetEl = locked ? `<span class="cf-facet lk">${f.label}</span>`
        : `<span class="cf-facet" data-facet="${path}">${f.label} <span style="color:var(--ink-soft);">▾</span></span>`;
      const opEl = locked ? `<span class="cf-op lk">${opLabel(c.facet, c.op)}</span>`
        : `<span class="cf-op" data-op="${path}">${opLabel(c.facet, c.op)} <span style="color:var(--ink-soft);">▾</span></span>`;
      const rm = locked ? '' : `<span class="cf-rmcond" data-rmnode="${path}" title="Remove condition">✕</span>`;
      return `<div class="cf-cond">${facetEl}${opEl}${valHtml}<span class="spacer"></span>${rm}</div>`;
    }
    function joinPill(g, path){
      return `<div class="cf-railjoin"><span class="cf-jpill" data-join="${path}" title="Click to switch to ${g.join==='or'?'AND':'OR'}">${g.join==='or'?'OR':'AND'} <span class="c">⇅</span></span></div>`;
    }
    function blockHtml(g, path, depth){
      const pool = scopePool();
      const live = nodeLive(g);
      const eff = live ? pool.filter(t => matchNode(t, g)).length : pool.length;
      const rows = g.children.map((k, i) => {
        const p = path + '.' + i;
        return (i ? joinPill(g, path) : '') + `<div class="cf-brow">${isGroup(k) ? blockHtml(k, p, depth + 1) : condHtml(k, p, false)}</div>`;
      }).join('');
      return `<div class="cf-block${depth > 1 ? ' sub' : ''}${g.not ? ' not' : ''}">
        <div class="cf-block-head">
          <span class="cf-not${g.not ? ' on' : ''}" data-not="${path}" title="${g.not ? 'This block excludes its matches — click to match normally' : 'Click to exclude everything this block matches'}">NOT</span>
          ${g.not ? '<span class="tiny cf-notlabel">excludes</span>' : ''}
          <span class="spacer"></span>
          <span class="badge${g.not ? ' bad' : ''}" style="font-size:.6rem;">${live ? eff + (opts.channelContext ? ' in channel' : ' titles') : 'no conditions yet'}</span>
          <span class="cf-rmblock" data-rmnode="${path}" title="Remove ${depth > 1 ? 'sub-block' : 'block'}">✕</span>
        </div>
        ${rows}
        <div class="cf-block-adds"><span class="vchip add" data-addcond="${path}">＋ Condition</span>${depth < MAX_DEPTH ? `<span class="vchip add" data-addsub="${path}">＋ Sub-block</span>` : ''}</div>
      </div>`;
    }
    function renderBlocks() {
      const wrap = node.querySelector('.cf-blocks');
      let html = '';
      const ctx = opts.channelContext;
      if (ctx && ctx.state) {
        migrateState(ctx.state);
        const chN = TITLES.filter(t => matchesState(t, ctx.state)).length;
        const lockedRows = g => g.children.filter(nodeLive).map((k, i) =>
          (i ? `<div class="cf-railjoin lk"><span class="cf-jpill lk">${g.join==='or'?'OR':'AND'}</span></div>` : '')
          + `<div class="cf-brow">${isGroup(k)
              ? `<div class="cf-block sub lk-sub">${k.not ? '<div class="cf-block-head"><span class="cf-not on lk">NOT</span><span class="tiny cf-notlabel">excludes</span></div>' : ''}${lockedRows(k)}</div>`
              : condHtml(k, '', true)}</div>`
        ).join('');
        const liveKids = ctx.state.root.children.filter(nodeLive);
        const single = liveKids.length === 1 && isGroup(liveKids[0]) && !liveKids[0].not;
        const body = single ? lockedRows(liveKids[0]) : lockedRows(ctx.state.root);
        html += `<div class="cf-block locked">
          <div class="cf-block-head">
            <span class="cf-lock">${LOCK_SVG} Channel</span>
            <b class="cf-lockname">${ctx.name}</b>
            <span class="spacer"></span>
            <span class="badge" style="font-size:.6rem;">${chN} titles</span>
          </div>
          ${body || '<div class="tiny muted" style="padding:2px 2px 4px;">No conditions — the channel shows everything.</div>'}
          <div class="cf-lockfoot tiny muted">Locked — this row can only show titles inside “${ctx.name}”. Edit the filter on the channel itself.</div>
        </div>
        <div class="cf-bjoin lk"><span class="cf-jpill lk">${LOCK_SVG} AND</span></div>`;
      }
      html += state.root.children.map((b, i) =>
        (i ? `<div class="cf-bjoin"><span class="cf-jpill" data-join="__root" title="Click to switch to ${state.root.join==='or'?'AND':'OR'}">${state.root.join==='or'?'OR':'AND'} <span class="c">⇅</span></span></div>` : '')
        + blockHtml(b, String(i), 1)
      ).join('');
      wrap.innerHTML = html;
    }

    function wire() {
      node.querySelectorAll('[data-x]').forEach(b => b.onclick = dismiss);
      /* ---- Order ---- */
      const ensureSort = () => { state.sort = rowSort(state); return state.sort; };
      node.querySelectorAll('.cf-ordkeys [data-okey], .cf-ordfallback [data-okey]').forEach(s => s.onclick = () => {
        const k = s.dataset.okey;
        if (k === 'pins') { state.handpick = true; state.pinned = state.pinned || []; render(); return; }
        ensureSort().by = k;
        if (s.closest('.cf-ordkeys')) {   // choosing a plain key on the top row leaves hand-pick mode
          state.handpick = false;
          if ((state.pinned || []).length) { state.pinned = []; toast('Hand-picks released · the row now runs ' + keyPhrase(state)); }
        }
        render();
      });
      node.querySelectorAll('[data-odir]').forEach(b => b.onclick = () => { const s = ensureSort(); s.descending = !s.descending; render(); });
      const canPin = () => { const ok = (state.pinned || []).length < rowLimit(state); if (!ok) toast('All ' + rowLimit(state) + ' places are hand-picked · release one or show more titles'); return ok; };
      node.querySelectorAll('[data-olimit]').forEach(b => b.onclick = () => {
        const next = rowLimit(state) + (+b.dataset.olimit);
        const floor = Math.max(ROW_LIMIT_MIN, (state.pinned || []).length);   // never below the pins — a pin is never dropped behind the admin's back
        if (next < floor) { toast('Release a hand-pick to show fewer than ' + floor); return; }
        state.limit = Math.min(ROW_LIMIT_MAX, next); render();
      });
      node.querySelectorAll('[data-unpin]').forEach(b => b.onclick = e => { e.stopPropagation(); state.pinned = (state.pinned || []).filter(n => n !== b.dataset.unpin); render(); });
      node.querySelectorAll('[data-auto]').forEach(t => t.onclick = () => { if (!canPin()) return; state.pinned = (state.pinned || []).concat([t.dataset.auto]); render(); });
      // drag: a tile carries its title; dropping on a pin inserts before it, on the pins zone appends, on the auto zone releases
      let dragName = null;
      node.querySelectorAll('.cf-strip [draggable]').forEach(t => {
        t.ondragstart = e => { dragName = t.dataset.pin || t.dataset.auto; t.classList.add('dragging'); try { e.dataTransfer.setData('text/plain', dragName); e.dataTransfer.effectAllowed = 'move'; } catch (err) {} };
        t.ondragend = () => { dragName = null; t.classList.remove('dragging'); node.querySelectorAll('.cf-zone.over, .cf-pin.over').forEach(z => z.classList.remove('over')); };
      });
      node.querySelectorAll('.cf-strip .cf-pin').forEach(p => {
        p.ondragover = e => { if (!dragName || dragName === p.dataset.pin) return; e.preventDefault(); e.stopPropagation(); p.classList.add('over'); };
        p.ondragleave = () => p.classList.remove('over');
        p.ondrop = e => { e.preventDefault(); e.stopPropagation(); if (!dragName) return; if (!(state.pinned || []).includes(dragName) && !canPin()) return; const list = (state.pinned || []).filter(n => n !== dragName); const at = list.indexOf(p.dataset.pin); list.splice(at < 0 ? list.length : at, 0, dragName); state.pinned = list; render(); };
      });
      node.querySelectorAll('.cf-strip [data-dropzone]').forEach(z => {
        z.ondragover = e => { if (!dragName) return; e.preventDefault(); z.classList.add('over'); };
        z.ondragleave = () => z.classList.remove('over');
        z.ondrop = e => { e.preventDefault(); if (!dragName) return; if (z.dataset.dropzone === 'pins' && !(state.pinned || []).includes(dragName) && !canPin()) return; const list = (state.pinned || []).filter(n => n !== dragName); if (z.dataset.dropzone === 'pins') list.push(dragName); state.pinned = list; render(); };
      });
      const ps = node.querySelector('[data-pinsearch]');
      if (ps) ps.onclick = () => {
        let matches = evaluate(state);
        if (opts.channelContext && opts.channelContext.state) matches = matches.filter(t => matchesState(t, opts.channelContext.state));
        const cur = state.pinned || [];
        const avail = autoSorted(matches.filter(t => !cur.includes(t.title)), { sort: { by: 'title', descending: false } });
        popover(ps, `<div style="padding:6px 6px 2px;"><input class="input cf-pinq" type="text" placeholder="Type a title…" style="width:100%;box-sizing:border-box;"></div><div class="cf-pinres">${avail.map(t => `<div class="cf-popitem" data-val="${t.title}"><span class="cf-mp" style="width:20px;height:30px;background:${grad(t.title)};"></span><span class="wb-vname">${t.title}</span><span class="wb-vcount">${t.year}</span></div>`).join('') || '<div class="cf-popitem" style="opacity:.6;cursor:default;">Every match is already hand-picked</div>'}</div>`, v => { if (!canPin()) return; state.pinned = (state.pinned || []).concat([v]); render(); });
        const pop = document.querySelector('.cf-pop'); const inp = pop && pop.querySelector('.cf-pinq'); if (!inp) return; inp.focus();
        inp.oninput = () => { const q = inp.value.trim().toLowerCase(); pop.querySelectorAll('.cf-pinres .cf-popitem[data-val]').forEach(it => { it.style.display = !q || it.dataset.val.toLowerCase().includes(q) ? '' : 'none'; }); };
        inp.onkeydown = e => { if (e.key === 'Enter') { const first = [...pop.querySelectorAll('.cf-pinres .cf-popitem[data-val]')].find(it => it.style.display !== 'none'); if (first) first.click(); } };
      }
      node.querySelectorAll('.cf-include span').forEach(s => s.onclick = () => { state.include = s.dataset.inc; render(); });
      function refreshChPrev(){ const box = node.querySelector('.cf-chprev'); if (box){ const fresh = el(channelWM(state, state.title||autoTitle(), {cls:'studio-wm cf-chprev', style:'width:100%;height:46px;margin-top:14px;', font:'1.05rem'})); box.replaceWith(fresh); } }
      const ti = node.querySelector('.cf-title'); if (ti) ti.oninput = () => { state.title = ti.value; const ct0 = node.querySelector('.cf-chtext'); if (ct0) ct0.placeholder = ti.value || autoTitle(); refreshChPrev(); };
      node.querySelectorAll('.cf-style span').forEach(s => s.onclick = () => { state.chStyle = s.dataset.st; render(); });
      node.querySelectorAll('.cf-pad').forEach(inp => inp.oninput = () => {
        const m = inp.dataset.padmode, s = inp.dataset.pad;
        let v = parseInt(inp.value, 10); if (isNaN(v)) v = 0; v = Math.max(0, Math.min(40, v));
        state.chPad = state.chPad || { logo:{}, text:{} };
        state.chPad[m] = state.chPad[m] || {};
        state.chPad[m][s] = v;
        const sum = node.querySelector(`.cf-padwrap.open .cf-padtoggle[data-padtoggle="${m}"] .cf-padsum`);
        const sumStr = padSummary(m);
        const tog = node.querySelector(`.cf-padtoggle[data-padtoggle="${m}"]`);
        if (tog) { let el2 = tog.querySelector('.cf-padsum');
          if (sumStr && !el2) { el2 = document.createElement('span'); el2.className = 'cf-padsum'; tog.insertBefore(el2, tog.querySelector('.cf-padcaret')); }
          if (el2) { if (sumStr) el2.textContent = sumStr; else el2.remove(); } }
        refreshChPrev();
      });
      node.querySelectorAll('[data-padtoggle]').forEach(b => b.onclick = () => {
        const m = b.dataset.padtoggle, wrap = b.closest('.cf-padwrap');
        const open = !wrap.classList.contains('open');
        wrap.classList.toggle('open', open); padOpen[m] = open;
      });
      function ensureHero(){ state.hero = state.hero || { on:false, items:[], height:48, advance:7 }; return state.hero; }
      const heroTog = node.querySelector('[data-herotog]');
      if (heroTog) heroTog.onclick = () => { const h = ensureHero(); h.on = !h.on; render(); };
      const heroAddBtn = node.querySelector('[data-heroadd]');
      if (heroAddBtn) heroAddBtn.onclick = () => openHero({
        headTitle: 'Add hero item', saveLabel: 'Add to hero carousel',
        state: { pick: TITLES[0], badge: 'None', tagline: '', backdrop: 0, useLogo: true },
        onSave: ({ state: hs }) => { ensureHero().items.push(hs); render(); }
      });
      node.querySelectorAll('[data-hiedit]').forEach(it => it.onclick = e => {
        if (e.target.closest('[data-herorm]')) return;
        const i = +it.dataset.hiedit, h = ensureHero();
        openHero({ edit:true, headTitle:'Edit hero item', saveLabel:'Save hero item', state: h.items[i],
          onSave: ({ state: hs }) => { h.items[i] = hs; render(); } });
      });
      node.querySelectorAll('[data-herorm]').forEach(b => b.onclick = e => { e.stopPropagation(); const h = ensureHero(); h.items.splice(+b.dataset.herorm, 1); render(); });
      function ensureRows(){
        state.rows = state.rows || { mode:'inherit', items:[] };
        if (!state.rows.items) state.rows.items = [];
        if (!state.rows.system) state.rows.system = { continue:{ show:true }, newly:{ show:true, merge:false } };
        return state.rows;
      }
      node.querySelectorAll('.cf-rowsmode span').forEach(s => s.onclick = () => { ensureRows().mode = s.dataset.rmode; render(); });
      node.querySelectorAll('[data-systog]').forEach(b => b.onclick = () => { const r = ensureRows(); r.system[b.dataset.systog].show = !r.system[b.dataset.systog].show; render(); });
      const sysMerge = node.querySelector('[data-sysmerge]'); if (sysMerge) sysMerge.onclick = () => { const r = ensureRows(); r.system.newly.merge = !r.system.newly.merge; render(); };
      const rowAddBtn = node.querySelector('[data-rowadd]');
      if (rowAddBtn) rowAddBtn.onclick = () => openFilter({
        mode: 'row', headTitle: 'Add channel row', saveLabel: 'Add row',
        channelContext: { name: state.title || autoTitle(), state: state }, scopeState: state,
        onSave: ({ state: rs, title, summary }) => { ensureRows().items.push({ title, summary, state: rs }); render(); }
      });
      node.querySelectorAll('[data-rdedit]').forEach(it => it.onclick = e => {
        if (e.target.closest('[data-rdrm]')) return;
        const i = +it.dataset.rdedit, rw = ensureRows();
        openFilter({ mode:'row', edit:true, headTitle:'Edit channel row', saveLabel:'Save row', state: rw.items[i].state,
          channelContext: { name: state.title || autoTitle(), state: state }, scopeState: state,
          onSave: ({ state: rs, title, summary }) => { rw.items[i] = { title, summary, state: rs }; render(); } });
      });
      node.querySelectorAll('[data-rdrm]').forEach(b => b.onclick = e => { e.stopPropagation(); const rw = ensureRows(); rw.items.splice(+b.dataset.rdrm, 1); render(); });
      const coverAdd = node.querySelector('[data-coveradd]');
      if (coverAdd) coverAdd.onclick = () => openFilter({
        mode: 'row', headTitle: 'Add catch-all row', saveLabel: 'Add row',
        channelContext: { name: state.title || autoTitle(), state: state }, scopeState: state,
        state: { include: 'all', root: mkGroup('and', [ mkGroup('or', [ mkCond() ]) ]), title: 'More titles' },
        onSave: ({ state: rs, title, summary }) => { ensureRows().items.push({ title, summary, state: rs }); render(); }
      });
      const coverLink = node.querySelector('[data-coverlink]');
      if (coverLink) coverLink.onclick = e => {
        e.preventDefault();
        const items = (state.rows && state.rows.items) || [];
        const rowsCtx = items.map(it => ({ name: it.title, state: it.state }));
        // The coverage gap as an ordinary workbench filter: the channel's own conditions,
        // ANDed with "Content row is none of <its rows>". Fully editable in the Library builder.
        const chan = pruneState(state);
        const filter = {
          include: 'all',
          root: mkGroup('and', [
            ...chan.root.children,
            mkGroup('or', [ { kind: 'cond', facet: 'row', op: 'isNot', values: rowsCtx.map(r => ({ name: r.name, state: r.state })) } ])
          ])
        };
        const payload = { label: state.title || autoTitle(), filter, rows: rowsCtx };
        try { localStorage.setItem('js-cov-filter', JSON.stringify(payload)); } catch (err) {}
        location.href = 'library.html?view=coverage';
      };
      node.querySelectorAll('.cf-sw[data-color]').forEach(s => s.onclick = () => { state.chColor = s.dataset.color; render(); });
      const customSw = node.querySelector('[data-customsw]');
      if (customSw) customSw.onclick = () => { if (!state.customGrad) state.customGrad = { type:'gradient', c1:'#7b6ef0', c2:'#3fb6f5', angle:135 }; state.chColor = customCss(state.customGrad); render(); };
      node.querySelectorAll('.cf-gradmode span').forEach(s => s.onclick = () => { if (!state.customGrad) state.customGrad = { type:'gradient', c1:'#7b6ef0', c2:'#3fb6f5', angle:135 }; state.customGrad.type = s.dataset.gm; state.chColor = customCss(state.customGrad); render(); });
      function applyCustom(){ state.chColor = customCss(state.customGrad); const sw = node.querySelector('[data-customsw]'); if (sw){ sw.style.background = state.chColor; sw.textContent = ''; sw.classList.add('on'); } node.querySelectorAll('.cf-sw[data-color]').forEach(x => x.classList.remove('on')); refreshChPrev(); }
      const c1i = node.querySelector('.cf-c1'); if (c1i) c1i.oninput = () => { state.customGrad.c1 = c1i.value; applyCustom(); };
      const c2i = node.querySelector('.cf-c2'); if (c2i) c2i.oninput = () => { state.customGrad.c2 = c2i.value; applyCustom(); };
      const angi = node.querySelector('.cf-angle'); if (angi) angi.oninput = () => { state.customGrad.angle = +angi.value; const v = node.querySelector('.cf-angle-val'); if (v) v.textContent = angi.value + '\u00b0'; applyCustom(); };
      const ct = node.querySelector('.cf-chtext'); if (ct) ct.oninput = () => { state.chText = ct.value; refreshChPrev(); };
      node.querySelectorAll('[data-logo]').forEach(b => b.onclick = () => {
        if (b.dataset.logo === '__current') return;
        state.chLogo = { type:'preset', id: b.dataset.logo };
        node.querySelectorAll('[data-logo]').forEach(x => x.classList.toggle('on', x===b));
        refreshChPrev();
      });
      const upBtn = node.querySelector('[data-upload]'); const upFile = node.querySelector('.cf-logofile');
      if (upBtn && upFile){
        upBtn.onclick = () => upFile.click();
        upFile.onchange = () => { const f = upFile.files && upFile.files[0]; if (!f) return; const rd = new FileReader(); rd.onload = () => { state.chLogo = { type:'upload', src: rd.result, label: f.name }; render(); }; rd.readAsDataURL(f); };
      }
      const groupOf = p => p === '__root' ? state.root : nodeAt(p);
      node.querySelectorAll('[data-join]').forEach(a => a.onclick = () => { const g = groupOf(a.dataset.join); g.join = g.join === 'or' ? 'and' : 'or'; render(); });
      node.querySelectorAll('[data-not]').forEach(a => a.onclick = () => { const g = nodeAt(a.dataset.not); g.not = !g.not; render(); });
      const addBlockBtn = node.querySelector('[data-addblock]');
      if (addBlockBtn) addBlockBtn.onclick = () => { state.root.children.push(mkGroup('or', [ mkCond() ])); render(); };
      node.querySelectorAll('[data-addcond]').forEach(a => a.onclick = () => { nodeAt(a.dataset.addcond).children.push(mkCond()); render(); });
      node.querySelectorAll('[data-addsub]').forEach(a => a.onclick = () => {
        const g = nodeAt(a.dataset.addsub);
        g.children.push(mkGroup(g.join === 'or' ? 'and' : 'or', [ mkCond() ]));   // a sub-block exists to mix operators — default to the opposite join
        render();
      });
      node.querySelectorAll('[data-rmnode]').forEach(b => b.onclick = () => {
        const { parent, idx } = parentOf(b.dataset.rmnode);
        parent.children.splice(idx, 1);
        cleanup(); render();
      });
      node.querySelectorAll('[data-rmval]').forEach(b => b.onclick = () => {
        const s = b.dataset.rmval, ci = s.lastIndexOf(':');
        nodeAt(s.slice(0, ci)).values.splice(+s.slice(ci + 1), 1); render();
      });

      node.querySelectorAll('[data-facet]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.facet);
        let last = null;
        const rowsOk = !!(opts.rowsContext && opts.rowsContext.length);
        const items = Object.keys(FACETS).filter(k => rowsOk || !FACETS[k].contextual).map(k => {
          const g = FACETS[k].group || '';
          const head = (g && g !== last) ? `<div class="cf-popgroup">${g}</div>` : '';
          last = g;
          return head + `<div class="cf-popitem" data-val="${k}">${FACETS[k].label}</div>`;
        }).join('');
        popover(a, items, v => {
          c.facet = v; c.op = opsFor(v)[0][0]; c.values = []; render();
        });
      });
      node.querySelectorAll('[data-op]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.op);
        popover(a, opsFor(c.facet).map(o => `<div class="cf-popitem" data-val="${o[0]}">${o[1]}</div>`).join(''), v => { c.op = v; render(); });
      });
      node.querySelectorAll('[data-pick]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.pick);
        const cur = c.values;
        const fac = FACETS[c.facet];
        // R127 — item count per facet value, ordered by count desc. When the workbench is
        // scoped to a channel (opts.scopeState / a locked channel block), counts narrow to
        // that channel's set and values absent from it disappear; library-wide shows global.
        const scopeSrc = opts.scopeState || (opts.channelContext && opts.channelContext.state);
        const scope = scopeSrc ? evaluate(scopeSrc) : TITLES;
        const counts = fac.options
          .map(o => ({ o, n: scope.filter(t => fac.get(t).map(String).includes(String(o))).length }))
          .filter(x => !scopeSrc || x.n > 0)
          .sort((x, y) => y.n - x.n);
        const inner = counts.map(x =>
          `<div class="cf-popitem ${cur.includes(x.o)?'on':''}" data-val="${x.o}">${cur.includes(x.o)?'✓ ':''}${valIcon(c.facet, x.o)}<span class="wb-vname">${x.o}</span><span class="wb-vcount">${x.n}</span></div>`
        ).join('') || '<div class="cf-popitem" style="opacity:.6;cursor:default;">No values in scope</div>';
        popover(a, inner, v => {
          if (!cur.includes(v)) cur.push(v); render();
        });
      });
      node.querySelectorAll('[data-pickrows]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.pickrows);
        const cur = c.values.map(v => v && v.name);
        const avail = (opts.rowsContext || []);
        const inner = avail.map((r, ri) => `<div class="cf-popitem ${cur.includes(r.name)?'on':''}" data-val="${ri}">${cur.includes(r.name)?'✓ ':''}${r.name}</div>`).join('') || '<div class="cf-popitem" style="opacity:.6;cursor:default;">No content rows</div>';
        popover(a, inner, v => {
          const r = avail[+v]; if (r && !cur.includes(r.name)) c.values.push({ name: r.name, state: r.state }); render();
        });
      });
      node.querySelectorAll('[data-num]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.num), f = FACETS[c.facet];
        popover(a, `<div style="padding:8px;"><input class="input cf-numin" type="number" ${f.min!=null?`min="${f.min}"`:''} ${f.max!=null?`max="${f.max}"`:''} step="${f.step||1}" placeholder="${c.facet==='year'?'2015':'7.5'}" style="width:120px;"><div class="btn sm primary cf-numok" style="margin-top:8px;justify-content:center;">Set</div></div>`, ()=>{});
        const pop = document.querySelector('.cf-pop'); const inp = pop.querySelector('.cf-numin'); inp.focus();
        const ok = () => { if (inp.value!=='') { c.values = [inp.value]; } pop.remove(); render(); };
        pop.querySelector('.cf-numok').onclick = ok;
        inp.onkeydown = e => { if (e.key==='Enter') ok(); };
      });
      node.querySelectorAll('[data-text]').forEach(a => a.onclick = () => {
        const c = nodeAt(a.dataset.text);
        popover(a, `<div style="padding:8px;"><input class="input cf-txtin" type="text" value="${(c.values[0]||'').replace(/"/g,'&quot;')}" placeholder="e.g. Synstolkning, Commentary, SDH" style="width:200px;"><div class="btn sm primary cf-txtok" style="margin-top:8px;justify-content:center;">Set</div></div>`, ()=>{});
        const pop = document.querySelector('.cf-pop'); const inp = pop.querySelector('.cf-txtin'); inp.focus();
        const ok = () => { c.values = inp.value.trim() ? [inp.value.trim()] : []; pop.remove(); render(); };
        pop.querySelector('.cf-txtok').onclick = ok;
        inp.onkeydown = e => { if (e.key==='Enter') ok(); };
      });

      node.querySelector('[data-save]').onclick = () => commit();
    }

    function commit() {
      const matches = evaluate(state);
      const title = state.title || autoTitle();
      const isRow = !isChannel && !isLibrary;
      if (isRow && !(state.pinned || []).length) state.handpick = false;   // "Hand-picked first" with nothing picked saves as the plain key
      const tail = isRow ? [orderSummary(state), rowLimit(state) !== ROW_LIMIT_DEFAULT ? 'shows ' + rowLimit(state) : ''].filter(Boolean).join(' · ') : '';
      const ordTag = tail ? '  ·  ' + tail : '';
      const summary = (querySummary(state) || 'all titles') + ordTag;
      const heroTag = isChannel && state.hero && state.hero.on ? '  ·  ⊳ hero' : '';
      const rowsTag = isChannel && state.rows && state.rows.mode === 'custom' ? '  ·  ▤ custom rows' : '';

      if (opts.onSave) { opts.onSave({ state, matches, title, summary, isChannel }); dismiss(); return; }

      if (opts.editRow) {
        opts.editRow.querySelector('.nm').firstChild.textContent = title + ' ';
        opts.editRow.querySelector('.src').textContent = summary + heroTag + rowsTag;
        if (isChannel) { const wmEl = opts.editRow.querySelector('.studio-wm'); if (wmEl) wmEl.outerHTML = channelWM(state, title); }
        opts.editRow._cfState = state;
      } else if (isChannel) {
        const row = el(`<div class="cfg-row cf-new">
          <span class="grab">⠿</span>${channelWM(state, title)}
          <div style="flex:1;"><div class="nm">${title} <span class="badge info" style="font-size:.6rem;">${matches.length} titles</span></div><div class="src">${summary}${heroTag}${rowsTag}</div></div>
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
      dismiss();
      toast((opts.edit?'Updated ':'Added ') + (isChannel?'channel':'row') + ' · ' + matches.length + ' titles');
    }

    (asScreen ? openScreen : openModal)(node); render();
  }

  /* ============================================================
     HERO ITEM BUILDER
     ============================================================ */
  const BADGES = ['New Season','4K','Top 10','Premiere','None'];
  function openHero(opts) {
    opts = opts || {};
    const state = opts.state || { pick: TITLES[0], badge: '4K', tagline: '', backdrop: 0, useLogo: true };
    const node = el(`<div class="cf-modal" style="max-width:1040px;"></div>`);
    const VIEWERS = ['Eyð Restorff', 'Marjun í Dali', 'Hjalti Poulsen'];

    function render() {
      const p = state.pick;
      const step1 = opts.lockTitle ? `
            <span class="cf-eyebrow">Hero item</span>
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
          <span class="cf-grab">⠿</span><b style="font-size:.98rem;">${opts.headTitle || ((opts.edit?'Edit':'New') + ' hero item')}</b>
          <span class="spacer"></span><span class="tiny muted">esc to cancel</span><span class="cf-x" data-x>✕</span>
        </div>
        <div class="cf-hbody">
          <div class="cf-hleft">
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
          <span class="btn sm primary" data-save>${opts.saveLabel || (opts.edit?'Save changes':'Add to hero carousel')}</span>
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
    // visible edit affordance — makes the editor popup discoverable
    if (!row.querySelector('.edit-ic')) {
      const eic = document.createElement('span');
      eic.className = 'edit-ic'; eic.title = 'Edit';
      eic.innerHTML = '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>';
      eic.onclick = e => { e.stopPropagation(); editRow(row); };
      const tog = row.querySelector('.toggle');
      if (tog) row.insertBefore(eic, tog);
      else if (row.querySelector('.rm')) row.insertBefore(eic, row.querySelector('.rm'));
      else row.appendChild(eic);
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
      const chTitle = r.querySelector('.nm').textContent.trim();
      r._cfState = isTag
        ? { include:'all', root: mkGroup('and', [ mkGroup('or', [ mkCond('tag','isAny',['dansk-tv']) ]) ]), title: chTitle, chStyle:'text', chColor:CH_COLORS[4] }
        : networks[i] === 'Kringvarp'
        // block showcase: (Tag nordic-noir or dansk-tv or Genre Thriller) AND (Network Kringvarp)
        ? { include:'all', title: chTitle, chStyle:'logo', chColor:CH_COLORS[i%CH_COLORS.length],
            root: mkGroup('and', [
              mkGroup('or', [ mkCond('tag','isAny',['nordic-noir','dansk-tv']), mkCond('genre','isAny',['Thriller']) ]),
              mkGroup('or', [ mkCond('network','isAny',['Kringvarp']) ])
            ]) }
        : { include:'all', root: mkGroup('and', [ mkGroup('or', [ mkCond('network','isAny',[networks[i]||'HBO']) ]) ]), title: chTitle, chStyle:'logo', chColor:CH_COLORS[i%CH_COLORS.length] };
      bindRow(r);
    });
    document.querySelectorAll('#rowlist .cfg-row').forEach(r => {
      const isSystem = !!r.querySelector('.badge.ok');
      if (isSystem) return; // system rows aren't filter-editable
      r.dataset.kind = 'row';
      const src = r.querySelector('.src').textContent;
      const vals = (src.match(/“([^”]+)”/g) || []).map(s => s.replace(/[“”]/g,''));
      const facet = src.startsWith('tag') ? 'tag' : 'genre';
      // prospective 225 — a seeded row may declare its order: data-sort="title:asc" · data-pinned="Title A|Title B"
      const ds = (r.dataset.sort || '').split(':');
      const sort = ORDER_KEYS[ds[0]] ? { by: ds[0], descending: ds[1] !== 'asc' } : Object.assign({}, DEFAULT_SORT);
      const pinned = r.dataset.pinned ? r.dataset.pinned.split('|').map(s => s.trim()).filter(Boolean) : [];
      const limit = rowLimit({ limit: r.dataset.limit });
      r._cfState = { include:'all', root: mkGroup('and', [ mkGroup('or', [ mkCond(facet, 'isAny', vals.length ? vals : ['Drama']) ]) ]), title: r.querySelector('.nm').textContent.trim(), sort, pinned, limit, handpick: pinned.length > 0 };
      bindRow(r, true);
    });
    document.querySelectorAll('#herolist .cfg-row').forEach(r => {
      r.dataset.kind = 'hero';
      const nm = r.querySelector('.nm').textContent.trim().replace(/\s+(New Season|4K|Premiere|Top 10)$/,'');
      const found = TITLES.find(t => nm.startsWith(t.title.slice(0,6))) || TITLES[0];
      const badge = (r.querySelector('.badge') && r.querySelector('.badge').textContent.trim()) || 'None';
      r._cfState = { pick: found, badge, tagline: '', backdrop:0, useLogo:true };
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
    const aHero = find('sect-hero', 'Add hero item'); if (aHero) aHero.onclick = () => openHero();
    const aCh = find('sect-studios', 'Add channel'); if (aCh) aCh.onclick = () => openFilter({ mode:'channel' });
    const aRow = find('sect-rows', 'Add row'); if (aRow) aRow.onclick = () => openFilter({ mode:'row' });
  }

  window.RaviloBuilders = { openFilter, openHero, evaluate, matchesState, rowCoverage, grad, TITLES, FACETS,
    mkGroup, mkCond, migrateState, pruneState, stateHasConditions, nodeHasValues: nodeLive, groupSummary, querySummary,
    orderTitles, orderSummary, rowSort, rowLimit, ORDER_KEYS, ROW_LIMIT_DEFAULT };
  document.addEventListener('DOMContentLoaded', () => { seed(); wireAdds(); });
  if (document.readyState !== 'loading') { seed(); wireAdds(); }
})();
