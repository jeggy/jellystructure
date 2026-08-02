/* Ravilo browse — the generic filter/sort catalog page (Direction A: top facet bar + popover).
   Reached from the Movies / Series nav (whole library) or a row's end-of-track "See all" tile
   (seeded to that row; added filters stack AND-wise on top of the seed, multi-select OR within
   a facet). Sort defaults to Recently added; popover values sort by count desc, ties A–Z.
   In production seeds/counts come from Jellystructure query endpoints; here they're derived
   from the demo catalog (channel + a few facets deterministically synthesized). */
(function () {
  const NF = it => it; // id helper
  function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return Math.abs(h); }

  const GMAP = { nordic: 'Crime', docs: 'Documentary', scifi: 'Sci-Fi', 'sci-fi': 'Sci-Fi' };
  function normGenre(tok) { const k = tok.trim().toLowerCase(); return GMAP[k] || (tok.trim().charAt(0).toUpperCase() + tok.trim().slice(1)); }

  function create(ctx) {
    const { R, W, el, esc, scroll, appbar, go, buildGridRows, tracksFor, rowTitle, catalog, stopHero, getView, castFor, seerrEnabled, seerrCatalog, rankTile } = ctx;
    const t = (k, v) => window.t(k, v);
    let v = null;          // current browse view object (state lives on it: filters, sort)
    let pop = null;        // open popover { el, chip, kind:'facet'|'sort', facetId, idx, opts }

    /* ---- facet value extraction ---- */
    // normalized age (0–18) — in production this is the jellystructure Metadata → Age ratings
    // mapping (raw certification → number); the viewer never sees “G” / “TV-PG” / “Btl”
    function normAge(it) { if (!it.rating) return 18; return it.rating === 'G' ? 0 : (parseInt(it.rating, 10) || 18); }   // no rating ⇒ 18 (155 FR-AGE1-2)
    // audio-facet flags: ISO-639-1 → flag-icons country code; label→cc captured as tracks are read
    const LANG_CC = { en: 'gb', fr: 'fr', de: 'de', es: 'es', da: 'dk', fo: 'fo', is: 'is', no: 'no', sv: 'se', fi: 'fi', nl: 'nl', it: 'it', pt: 'pt', pl: 'pl', ru: 'ru', ja: 'jp', ko: 'kr', zh: 'cn', ar: 'sa', hi: 'in' };
    const audCc = {};
    const PEOPLE = ['Sigrun Restorff','Páll Heinason','Marin Klett','Eva Restorff','Tóki á Bø','Lena Björk','Anders Holm','Freya Dahl','Mikkel Sørensen','Ingrid Vold','Johan Máni','Sara Winther','Colin Reeves','Nadia Hassan'];
    // deterministic 2–3 cast/crew per title so a person spans several titles (demo; prod: credits index)
    function castOf(it) { const h = hash(it.title); const n = 2 + (h % 2); const out = []; for (let i = 0; i < n; i++) out.push(PEOPLE[(h + i * 5) % PEOPLE.length]); return Array.from(new Set(out)); }
    function genres(it) { return Array.from(new Set((it.genre || '').split(/\s*·\s*/).filter(Boolean).map(normGenre))); }
    function watchedState(it) {
      const ws = W.itemState(it.title);
      return ws.watched ? t('br_w_watched') : (ws.pct > 0 ? t('br_w_inprogress') : t('br_w_unwatched'));
    }
    function audioLangs(it) {
      const ts = tracksFor(it);
      const out = []; const seen = new Set();
      (ts.audio || []).forEach(a => { if (a.lang && !seen.has(a.lang)) { seen.add(a.lang); out.push(a.label); if (LANG_CC[a.lang]) audCc[a.label] = LANG_CC[a.lang]; } });
      return out;
    }
    // demo-only: deterministic channel membership (production: Jellystructure channel queries)
    function channelsOf(it) {
      const names = R.studios.map(s => s.name);
      return [names[hash(it.title) % names.length]];
    }
    function facetDefs() {
      const d = [
        { id: 'genre', label: t('br_genre'), vals: it => genres(it) },
        { id: 'type', label: t('br_type'), vals: it => [it.kind === 'series' ? t('br_t_series') : t('br_t_film')] },
        { id: 'maturity', label: t('br_maturity'), range: true, vals: it => [normAge(it) + '+'] },
        { id: 'year', label: t('br_year'), vals: it => [Math.floor((it.year || 2018) / 10) * 10 + 's'] },
        { id: 'watched', label: t('br_watched'), vals: it => [watchedState(it)] },
        { id: 'person', label: t('br_person'), vals: it => castOf(it) },
        { id: 'audio', label: t('br_audio'), vals: it => audioLangs(it) },
        { id: 'channel', label: t('br_channel'), vals: it => channelsOf(it) },
        { id: 'quality', label: t('br_quality'), vals: it => [it.badge === '4K' ? '4K' : it.badge === 'HDR' ? 'HDR' : 'HD'] },
      ];
      return v && v.kind ? d.filter(f => f.id !== 'type') : d;   // Movies/Series pages fix the type
    }
    const SORTS = () => [
      ['added', t('br_s_added')], ['az', 'A–Z'], ['za', 'Z–A'],
      ['year', t('br_s_year')], ['maturity', t('br_s_maturity')], ['imdb', t('br_s_imdb')],
    ];

    /* ---- seed + filtering ---- */
    // person mode: everything featuring this cast/crew member. Production seeds this from a
    // Jellystructure people query; the demo derives a deterministic filmography from the catalog
    // (the source title is always in, plus a stable ~1/3 slice + any real cast match).
    function appearsIn(it, name) {
      if (v.personFrom && it.title === v.personFrom) return true;
      if (castFor && (castFor(it) || []).some(c => c && c.n === name)) return true;
      return hash(name + '|' + it.title) % 100 < 34;
    }
    function seed() {
      if (v._seed) return v._seed;
      let s;
      if (v.person) s = catalog().filter(it => appearsIn(it, v.person.n));
      else if (v.row) { const seen = new Set(); s = v.row.items.filter(it => it && it.title && !seen.has(it.title) && seen.add(it.title) !== null); }
      else if (v.kind) s = catalog().filter(it => it.kind === v.kind || (v.kind === 'film' && it.kind !== 'series'));
      else s = catalog();
      return v._seed = s;
    }
    /* ---- maturity range (D-pad: ◂ ▸ adjusts From / Up-to over the normalized 0–18 ladder) ---- */
    function seedAges() { return Array.from(new Set(seed().map(normAge))).sort((a, b) => a - b); }
    function matRange() { const m = v.filters.maturity; return (m && typeof m === 'object' && !Array.isArray(m)) ? m : null; }
    function matLabel(m) {
      if (!m || (m.min == null && m.max == null)) return '';
      if (m.min != null && m.max != null) return m.min === m.max ? m.min + '' : m.min + '–' + m.max;
      return m.min != null ? m.min + '+' : '≤ ' + m.max;
    }
    function matActive() { const m = matRange(); return !!(m && (m.min != null || m.max != null)); }
    function matches(it, skipFacet) {
      const defs = facetDefs();
      for (const d of defs) {
        if (d.id === skipFacet) continue;
        if (d.range) {   // maturity: numeric range, not value list
          const m = matRange();
          if (!m) continue;
          const a = normAge(it);
          if (m.min != null && a < m.min) return false;
          if (m.max != null && a > m.max) return false;
          continue;
        }
        const sel = v.filters[d.id];
        if (!sel || !sel.length) continue;
        const vals = d.vals(it);
        if (!sel.some(x => vals.includes(x))) return false;   // OR within a facet
      }
      return true;                                            // AND across facets
    }
    function filtered() {
      let r = seed().filter(it => matches(it, null));
      const im = it => { const x = R.imdbFor(it); return x ? x.rating : 0; };
      if (v.sort === 'az') r = r.slice().sort((a, b) => a.title.localeCompare(b.title));
      else if (v.sort === 'za') r = r.slice().sort((a, b) => b.title.localeCompare(a.title));
      else if (v.sort === 'year') r = r.slice().sort((a, b) => (b.year || 0) - (a.year || 0));
      else if (v.sort === 'maturity') r = r.slice().sort((a, b) => normAge(a) - normAge(b) || a.title.localeCompare(b.title));
      else if (v.sort === 'imdb') r = r.slice().sort((a, b) => im(b) - im(a));
      return r;   // 'added' = seed order (library feed arrives newest-first)
    }
    // popover values for one facet: count against seed with all OTHER facets applied;
    // ordered by count desc, ties alphabetical (A–Z)
    function facetValues(def) {
      const base = seed().filter(it => matches(it, def.id));
      const counts = {};
      base.forEach(it => def.vals(it).forEach(val => { counts[val] = (counts[val] || 0) + 1; }));
      // keep selected values visible even at count 0 under sibling filters
      (v.filters[def.id] || []).forEach(val => { if (!(val in counts)) counts[val] = 0; });
      return Object.keys(counts)
        .sort((a, b) => counts[b] - counts[a] || a.localeCompare(b, undefined, { numeric: true }))
        .map(val => ({ val, n: counts[val], on: (v.filters[def.id] || []).includes(val) }));
    }
    function activeCount() { return facetDefs().reduce((a, d) => a + (d.range ? (matActive() ? 1 : 0) : ((v.filters[d.id] || []).length)), 0); }

    /* ---- render ---- */
    function render(view) {
      v = view; v.filters = v.filters || {}; v.sort = v.sort || 'added';
      stopHero(); closePop(); scroll.innerHTML = '';
      const title = v.person ? v.person.n : (v.row ? rowTitle(v.row) : v.title);
      const wrap = el('div', 'gridscreen browse');
      let crumb = '';
      if (v.person) crumb = `<div class="crumb">◂ ${esc(v.personFrom || fromLabel())} · <b>${esc(title)}</b></div>`;
      else if (v.row) crumb = `<div class="crumb">◂ ${esc(fromLabel())} · <b>${esc(title)}</b></div>`;
      const pmeta = (v.person && v.person.r) ? `<div class="pmeta">${esc(v.person.r)}</div>` : '';
      wrap.innerHTML = `<div class="gridhead browsehead"><div>${crumb}<h1>${esc(title)}</h1>${pmeta}<div class="gridsub"></div></div><span class="gridcount"></span></div>`;
      const fbar = el('div', 'fbar focus-row');
      wrap.appendChild(fbar);
      const grid = el('div', 'pgrid'); wrap.appendChild(grid);
      scroll.appendChild(wrap);
      refreshBar(); refreshGrid();
      renderSeerrRow(wrap);
      wrap.appendChild(el('div', 'screen-end'));
      appbar.querySelectorAll('.navitem').forEach(n => n.classList.toggle('cur', n.dataset.nav === v.nav));
    }
    // Seerr row (person pages only): requestable titles featuring this person that Jellystructure
    // doesn't already have. Appears after all library results, when Seerr is configured.
    function seerrPeopleItems(name) {
      if (!seerrCatalog) return [];
      const inLib = new Set(seed().map(it => it.title));
      return seerrCatalog().filter(it => it && it.title && !inLib.has(it.title) && hash(name + '|seerr|' + it.title) % 100 < 42).slice(0, 12);
    }
    function renderSeerrRow(wrap) {
      if (!v.person || !(seerrEnabled && seerrEnabled()) || !rankTile) return;
      const items = seerrPeopleItems(v.person.n);
      if (!items.length) return;
      const sec = el('div', 'crow seerrmore');
      sec.innerHTML = `<div class="crow-head"><h2>${esc(t('br_seerr_more', { name: v.person.n }))}</h2><span class="seerrtag">⚡ Seerr</span></div>`;
      const tr = el('div', 'track focus-row');
      items.forEach(it => tr.appendChild(rankTile(it, { scope: 'global', metric: '' })));
      sec.appendChild(tr);
      wrap.appendChild(sec);
    }
    function fromLabel() {
      const f = v.from || {};
      if (f.type === 'category') { const s = R.studios.find(x => x.id === f.studio); return s ? s.name : t('back_home'); }
      return t('back_home');
    }
    function refreshBar() {
      const fbar = scroll.querySelector('.fbar'); if (!fbar) return;
      fbar.innerHTML = '';
      facetDefs().forEach(d => {
        const c = el('div', 'facet foc');
        c._facet = d.id;
        if (d.range) {
          const lab = matLabel(matRange());
          c.classList.toggle('active', !!lab);
          c.innerHTML = `${esc(d.label)}${lab ? `<span class="cnt">${esc(lab)}</span>` : ''}<span class="caret">▾</span>`;
        } else {
          const n = (v.filters[d.id] || []).length;
          c.classList.toggle('active', !!n);
          c.innerHTML = `${esc(d.label)}${n ? `<span class="cnt">${n}</span>` : ''}<span class="caret">▾</span>`;
        }
        fbar.appendChild(c);
      });
      if (activeCount()) { const r = el('div', 'facet reset foc', `✕ ${t('br_reset')}`); r._facetreset = 1; fbar.appendChild(r); }
      const s = el('div', 'fsort foc');
      s._sortbtn = 1;
      s.innerHTML = `<span class="lb">${t('br_sort')}</span>${esc(SORTS().find(x => x[0] === v.sort)[1])} ▾`;
      fbar.appendChild(s);
    }
    function refreshGrid() {
      const grid = scroll.querySelector('.browse .pgrid');
      if (!grid) { closePop(); return; }   // view changed under us — drop stale popover state
      const items = filtered();
      buildGridRows(grid, items);
      scroll.querySelector('.gridcount').textContent = items.length + ' ' + t('br_titles');
      const sub = scroll.querySelector('.gridsub');
      const parts = [];
      if (v.row) parts.push(t('br_from_row', { row: rowTitle(v.row) }));
      const sel = [];
      facetDefs().forEach(d => {
        if (d.range) { const lab = matLabel(matRange()); if (lab) sel.push(d.label + ' ' + lab); return; }
        (v.filters[d.id] || []).forEach(x => sel.push(x));
      });
      if (sel.length) parts.push(sel.join(' + '));
      sub.innerHTML = parts.map(esc).join(' · ');
      sub.style.display = parts.length ? '' : 'none';
    }

    /* ---- popover (owns the D-pad while open, like the player language picker) ---- */
    function onChip(f) {
      if (f._facetreset) { v.filters = {}; refreshBar(); refreshGrid(); return; }
      if (pop) { closePop(); return; }
      if (f._sortbtn) openPop(f, 'sort');
      else openPop(f, 'facet', f._facet);
    }
    function openPop(chip, kind, facetId) {
      closePop();
      const wrap = scroll.querySelector('.gridscreen.browse'); if (!wrap) return;
      const p = el('div', 'fpop');
      pop = { el: p, chip, kind, facetId, idx: 0, opts: [] };
      chip.classList.add('open');
      buildPopContents();
      wrap.appendChild(p);
      const fbar = scroll.querySelector('.fbar');
      const left = Math.min(chip.offsetLeft + fbar.offsetLeft, 1920 - 420);
      p.style.left = left + 'px';
      p.style.top = (fbar.offsetTop + fbar.offsetHeight + 10) + 'px';
    }
    function buildPopContents() {
      const p = pop.el;
      if (pop.kind === 'sort') {
        pop.opts = SORTS().map(([id, label]) => ({ id, label, on: v.sort === id }));
        p.innerHTML = `<div class="ph">${t('br_sort')}</div>` + pop.opts.map((o, i) =>
          `<div class="opt${o.on ? ' on' : ''}${i === pop.idx ? ' focused' : ''}" data-pidx="${i}"><span class="box round">${o.on ? '●' : ''}</span>${esc(o.label)}</div>`).join('');
      } else if (facetDefs().find(d => d.id === pop.facetId && d.range)) {
        // range picker: two rows (From / Up to) + ladder viz; ◂ ▸ adjusts, OK closes
        const def = facetDefs().find(d => d.id === pop.facetId);
        const ages = ALL_AGES;
        const m = matRange() || (v.filters.maturity = { min: null, max: null });
        pop.range = true;
        p.classList.add('wide');
        pop.opts = [{ row: 'min' }, { row: 'max' }];
        const cell = a => `<span class="lad mini${inRange(a, m) ? ' in' : ''}">${a}</span>`;
        p.innerHTML = `<div class="ph">${esc(def.label)}</div>
          <div class="ladviz">${ages.map(cell).join('')}</div>
          <div class="opt range${pop.idx === 0 ? ' focused' : ''}" data-pidx="0"><span class="rlb">${t('br_from')}</span><span class="radj"><span class="rar" data-rd="-1">◂</span><b>${m.min == null ? t('br_any') : m.min + '+'}</b><span class="rar" data-rd="1">▸</span></span></div>
          <div class="opt range${pop.idx === 1 ? ' focused' : ''}" data-pidx="1"><span class="rlb">${t('br_to')}</span><span class="radj"><span class="rar" data-rd="-1">◂</span><b>${m.max == null ? t('br_any') : m.max + '+'}</b><span class="rar" data-rd="1">▸</span></span></div>
          <div class="rhint">${t('br_range_hint')}</div>`;
      } else {
        const def = facetDefs().find(d => d.id === pop.facetId);
        pop.opts = facetValues(def);
        p.innerHTML = `<div class="ph">${esc(def.label)} · ${t('br_pick_any')}</div>` + pop.opts.map((o, i) =>
          `<div class="opt${o.on ? ' on' : ''}${i === pop.idx ? ' focused' : ''}" data-pidx="${i}"><span class="box">${o.on ? '✓' : ''}</span>${pop.facetId === 'person' ? `<span class="fpop-av" style="background:${R.grad(o.val)}">${R.initials(o.val)}</span>` : ''}${pop.facetId === 'audio' && audCc[o.val] ? `<span class="fi fi-${audCc[o.val]} fpop-flag"></span>` : ''}${esc(o.val)}<span class="cnt-side">${o.n}</span></div>`).join('');
      }
    }
    const ALL_AGES = Array.from({ length: 19 }, (_, i) => i);   // any age 0–18
    function inRange(a, m) { return (m.min == null || a >= m.min) && (m.max == null || a <= m.max); }
    function rangeAdjust(d) {
      const ages = ALL_AGES;
      const m = matRange(); if (!m) return;
      const steps = [null].concat(ages);          // index 0 = Any (no bound)
      const key = pop.idx === 0 ? 'min' : 'max';
      let i;
      if (key === 'min') {
        i = m.min == null ? 0 : steps.indexOf(m.min);
        i = Math.max(0, Math.min(steps.length - 1, i + d));
        m.min = steps[i];
      } else {
        // for the upper bound, Any sits past the top: [ages..., Any]
        const up = ages.concat([null]);
        i = m.max == null ? up.length - 1 : up.indexOf(m.max);
        i = Math.max(0, Math.min(up.length - 1, i + d));
        m.max = up[i];
      }
      if (m.min != null && m.max != null && m.min > m.max) { if (key === 'min') m.max = m.min; else m.min = m.max; }
      buildPopContents();
      refreshBarKeepFocus(); refreshGrid();
    }
    function closePop() {
      if (!pop) return;
      pop.chip.classList.remove('open');
      pop.el.remove();
      const wasRange = pop.range; pop = null;
      if (wasRange && v && v.filters) {   // tidy an untouched { null, null } range
        const m = v.filters.maturity;
        if (m && !Array.isArray(m) && m.min == null && m.max == null) delete v.filters.maturity;
      }
    }
    function popMove(d) {
      pop.idx = Math.max(0, Math.min(pop.opts.length - 1, pop.idx + d));
      pop.el.querySelectorAll('.opt').forEach((o, i) => o.classList.toggle('focused', i === pop.idx));
      const f = pop.el.querySelector('.opt.focused');
      if (f) { const top = f.offsetTop - 90; pop.el.scrollTop = Math.max(0, top); }
    }
    function popChoose() {
      if (pop.range) { closePopAndBar(); return; }   // OK confirms the range
      if (pop.kind === 'sort') {
        v.sort = pop.opts[pop.idx].id;
        closePopAndBar(); refreshGrid();
        return;
      }
      const o = pop.opts[pop.idx];
      const sel = v.filters[pop.facetId] = v.filters[pop.facetId] || [];
      const at = sel.indexOf(o.val);
      if (at >= 0) sel.splice(at, 1); else sel.push(o.val);
      // live update: grid + count + chip badges + popover counts (multi-select stays open)
      const focVal = o.val;
      buildPopContents();
      const ni = pop.opts.findIndex(x => x.val === focVal);
      if (ni >= 0 && ni !== pop.idx) { pop.idx = ni; buildPopContents(); }
      refreshBarKeepFocus(); refreshGrid();
    }
    function closePopAndBar() { closePop(); refreshBarKeepFocus(); }
    function refreshBarKeepFocus() {
      // rebuild chips but keep the same chip focused + popover anchored
      const wasFacet = pop && pop.facetId, wasSort = pop && pop.kind === 'sort';
      refreshBar();
      const fbar = scroll.querySelector('.fbar');
      let chip = null;
      if (wasSort) chip = fbar.querySelector('.fsort');
      else if (wasFacet) chip = Array.from(fbar.querySelectorAll('.facet')).find(c => c._facet === wasFacet);
      if (pop && chip) {
        pop.chip = chip; chip.classList.add('open'); chip.classList.add('focused');
        pop.el.style.left = Math.min(chip.offsetLeft + fbar.offsetLeft, 1920 - 420) + 'px';
      }
    }
    // capture-phase keys while the popover is open
    window.addEventListener('keydown', e => {
      if (!pop) return;
      const k = e.key;
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ', 'Backspace', 'Escape'].includes(k)) { e.preventDefault(); e.stopImmediatePropagation(); }
      if (k === 'ArrowUp') popMove(-1);
      else if (k === 'ArrowDown') popMove(1);
      else if (pop.range && (k === 'ArrowLeft' || k === 'ArrowRight')) rangeAdjust(k === 'ArrowLeft' ? -1 : 1);
      else if (k === 'Enter' || k === ' ') popChoose();
      else if (k === 'Backspace' || k === 'Escape' || k === 'ArrowLeft' || k === 'ArrowRight') closePop();
    }, true);
    // pointer fallback
    scroll.addEventListener('click', e => {
      if (!pop) return;
      const ar = e.target.closest('.fpop .rar');
      if (ar) { pop.idx = +ar.closest('.opt').dataset.pidx; buildPopContents(); rangeAdjust(+ar.dataset.rd); e.stopPropagation(); return; }
      const o = e.target.closest('.fpop .opt');
      if (o) { pop.idx = +o.dataset.pidx; pop.range ? buildPopContents() : popChoose(); e.stopPropagation(); }
      else if (!e.target.closest('.fpop')) closePop();
    }, true);

    return { render, onChip, isOpen: () => !!pop, closePop };
  }

  window.RaviloBrowse = { create };
})();
