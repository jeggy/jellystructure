/* Collection popover — the movie glyph. Frame builder.
   Every number comes from CGM (cgm-data.js). Nothing is measured off a picture.
   Self-contained: nothing here imports the previous round, which is pruned at adoption. */
(function () {
  'use strict';
  const { M, THEMES, theme, GLYPHS, ROWS, CANDIDATES, I18N, POSTERS, FACETS, DISTANCE,
    GLYPH_ALLOWANCE, popoverWidth } = window.CGM;

  let seq = 0;
  const uid = (p) => `${p}-${++seq}`;
  const el = (tag, cls, txt) => { const n = document.createElement(tag); if (cls) n.className = cls; if (txt != null) n.textContent = txt; return n; };

  // ------------------------------------------------------------------ one shape -> path data
  // Rect, circle and polygon all become path `d`, so a shape and its holes are one <path> with
  // fill-rule="evenodd". No <defs>, no paint server, nothing to resolve in a hidden subtree.
  function d(spec) {
    if (spec.rect) {
      const [x, y, w, h] = spec.rect, r = spec.r || 0;
      if (!r) return `M${x} ${y}H${x + w}V${y + h}H${x}Z`;
      return `M${x + r} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}` +
             `V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}` +
             `H${x + r}A${r} ${r} 0 0 1 ${x} ${y + h - r}` +
             `V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;
    }
    if (spec.circle) {
      const [cx, cy, r] = spec.circle;
      return `M${cx - r} ${cy}A${r} ${r} 0 1 0 ${cx + r} ${cy}A${r} ${r} 0 1 0 ${cx - r} ${cy}Z`;
    }
    if (spec.poly) return 'M' + spec.poly.map(p => p.join(' ')).join('L') + 'Z';
    throw new Error('unknown shape ' + JSON.stringify(spec));
  }

  function glyphSvg(name, sizePx, colour) {
    const g = GLYPHS[name];
    if (!g) throw new Error('no glyph ' + name);
    const body = g.parts.map(p => {
      const path = [d(p.shape)].concat((p.holes || []).map(d)).join('');
      return `<path d="${path}" fill="${colour}" fill-rule="evenodd"/>`;
    }).join('');
    return `<svg class="cgm-g" id="${uid('g')}" width="${sizePx}" height="${sizePx}" viewBox="0 0 24 24" aria-hidden="true">${body}</svg>`;
  }

  // ------------------------------------------------------------------ one popover row
  function rowNode(row, pair, t, opts) {
    const u = opts.u;
    const n = el('div', 'cgm-row' + (opts.focused ? ' on' : ''));
    n.style.padding = `${M.rowPadV * u}px ${M.rowPadH * u}px`;
    n.style.borderRadius = `${M.rowRadius * u}px`;
    if (opts.focused) n.style.background = t.surface;

    const check = el('div', 'cgm-slot');
    check.style.width = `${M.checkSlot * u}px`;
    check.style.height = `${M.rowLine * u}px`;
    if (opts.ticked) {
      const s = M.check * u;
      check.innerHTML = `<svg width="${s}" height="${s}" viewBox="0 0 24 24" id="${uid('c')}" aria-hidden="true">` +
        `<path d="M4.3 12.7 L10.1 18.2 L20.2 6.5" fill="none" stroke="${t.accent}" stroke-width="3.36" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    }
    n.appendChild(check);

    if (pair) {
      const slot = el('div', 'cgm-slot cgm-gslot');
      slot.style.width = `${M.glyph * u}px`;
      slot.style.height = `${M.rowLine * u}px`;
      slot.style.marginRight = `${M.gapAfterGlyph * u}px`;
      slot.innerHTML = glyphSvg(row.kind === 'ravilo' ? pair.ravilo : pair.picked, M.glyph * u, t.text);
      n.appendChild(slot);
    }

    const name = el('div', 'cgm-name', row.name);
    name.style.fontSize = `${M.nameSp * u}px`;
    name.style.lineHeight = `${M.rowLine * u}px`;
    name.style.color = t.text;
    n.appendChild(name);

    const cnt = el('div', 'cgm-count', String(row.count));
    cnt.style.fontSize = `${M.countSp * u}px`;
    cnt.style.lineHeight = `${M.rowLine * u}px`;
    cnt.style.color = t.textSecondary;
    n.appendChild(cnt);
    return n;
  }

  function popoverNode(opts) {
    const { u, t, pair, rows, focusedName, tickedNames } = opts;
    const wDp = popoverWidth(rows.map(r => r.name), pair ? GLYPH_ALLOWANCE : 0);
    const box = el('div', 'cgm-pop');
    box.style.width = `${wDp * u}px`;
    box.style.padding = `${M.popPad * u}px`;
    box.style.borderRadius = `${M.popRadius * u}px`;
    box.style.background = t.surfaceVariant;
    const list = el('div', 'cgm-list');
    list.style.maxHeight = `${M.popMaxH * u}px`;
    rows.forEach(r => list.appendChild(rowNode(r, pair, t, {
      u, focused: r.name === focusedName, ticked: (tickedNames || []).includes(r.name),
    })));
    box.appendChild(list);
    box._wDp = wDp;
    return box;
  }

  function chipBar(opts) {
    const { u, t, lang, activeIndex, summary, openIndex } = opts;
    const bar = el('div', 'cgm-chips');
    bar.style.gap = `${M.chipGap * u}px`;
    FACETS[lang].forEach((label, i) => {
      const chip = el('div', 'cgm-chip');
      const active = i === activeIndex && summary;
      chip.style.padding = `${M.chipPadV * u}px ${M.chipPadH * u}px`;
      chip.style.borderRadius = `${M.chipRadius * u}px`;
      chip.style.background = active ? t.accent : t.surfaceVariant;
      chip.style.fontSize = `${M.chipFontSp * u}px`;
      chip.style.color = active ? t.onAccent : t.textSecondary;
      chip.style.fontWeight = active ? 600 : 400;
      chip.textContent = active ? `${label} (${summary})` : label;   // shipped: "$label ($badge)"
      if (i === openIndex) chip.classList.add('cgm-chip-open');
      bar.appendChild(chip);
    });
    return bar;
  }

  function initials(s) { return s.replace(/[^A-Za-zÀ-ÿ0-9 ]/g, '').split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase(); }
  function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return Math.abs(h); }
  function posterWash(title, t) {
    const x = hash(title), a = x % 360, b = (a + 36 + ((x >> 3) % 50)) % 360;
    const l1 = (t.isLight ? 62 : 30) + ((x >> 5) % 12), l2 = (t.isLight ? 44 : 12) + ((x >> 7) % 8);
    return `linear-gradient(145deg, hsl(${a} 42% ${l1}%), hsl(${b} 46% ${l2}%))`;
  }

  // ------------------------------------------------------------------ the TV page
  function tvFrame(id, opts) {
    const u = 2;                               // a TV is 960 x 540 dp on a 1920 x 1080 stage
    const t = theme(opts.themeId || 'aurora');
    const lang = opts.lang || 'en';
    const f = el('div', 'frame cgm-tv');
    f.id = id;
    f.style.background = t.background;

    const bar = el('div', 'cgm-appbar');
    bar.style.height = `${M.appBar * u}px`;
    bar.style.padding = `0 ${M.hPadTv * u}px`;
    const mark = el('div', 'cgm-mark', 'RAVILO');
    mark.style.color = t.text;
    bar.appendChild(mark);
    const nav = el('div', 'cgm-nav');
    [I18N.page[lang], 'Serier', I18N.library[lang], 'Søk'].forEach((s, i) => {
      const a = el('span', 'cgm-navi' + (i === 0 ? ' on' : ''), s);
      a.style.color = i === 0 ? t.text : t.textSecondary;
      nav.appendChild(a);
    });
    bar.appendChild(nav);
    const av = el('div', 'cgm-av', 'J');
    av.style.background = t.accent; av.style.color = t.onAccent;
    bar.appendChild(av);
    f.appendChild(bar);

    const h = el('h1', 'cgm-h1', I18N.page[lang]);
    h.style.margin = `${18 * u}px ${M.hPadTv * u}px ${12 * u}px`;
    h.style.color = t.text;
    f.appendChild(h);

    const bars = el('div', 'cgm-barwrap');
    bars.style.padding = `0 ${M.hPadTv * u}px`;
    bars.appendChild(chipBar({ u, t, lang, activeIndex: 5, summary: opts.summary, openIndex: 5 }));
    f.appendChild(bars);

    const grid = el('div', 'cgm-grid');
    grid.style.padding = `${20 * u}px ${M.hPadTv * u}px 0`;
    grid.style.gap = `${16 * u}px`;
    POSTERS.forEach(title => {
      const p = el('div', 'cgm-poster');
      p.style.width = `${150 * u}px`; p.style.height = `${225 * u}px`;
      p.style.borderRadius = `${t.tileRadius * u}px`;
      p.style.background = posterWash(title, t);
      const ini = el('div', 'cgm-pini', initials(title));
      ini.style.fontSize = `${28 * u}px`;
      p.appendChild(ini);
      grid.appendChild(p);
    });
    f.appendChild(grid);

    const pop = popoverNode({ u, t, pair: opts.pair, rows: ROWS,
      focusedName: opts.focusedName, tickedNames: opts.tickedNames });
    pop.classList.add('cgm-pop-abs');
    f._pop = pop;
    f.appendChild(pop);
    return f;
  }

  // ------------------------------------------------------------------ the phone page
  function phoneFrame(id, opts) {
    const u = 1;                               // a Pixel 9 is 412 dp wide
    const t = theme(opts.themeId || 'aurora');
    const lang = opts.lang || 'en';
    const f = el('div', 'frame cgm-phone');
    f.id = id;
    f.style.width = '412px'; f.style.height = '915px';
    f.style.background = t.background;

    const status = el('div', 'cgm-status');
    status.style.color = t.textSecondary;
    status.appendChild(el('span', null, '21:40'));
    status.appendChild(el('span', null, '•••'));
    f.appendChild(status);

    const h = el('h1', 'cgm-h1 cgm-h1p', I18N.library[lang]);
    h.style.margin = `4px ${M.hPadPhone}px 10px`;
    h.style.color = t.text;
    f.appendChild(h);

    // The strip is drawn carried to the Collection chip, as a scrolled LazyRow leaves it.
    const bars = el('div', 'cgm-barwrap cgm-barscroll');
    const strip = chipBar({ u, t, lang, activeIndex: 5, summary: opts.summary, openIndex: 5 });
    strip.style.padding = `0 ${M.hPadPhone}px`;
    bars.appendChild(strip);
    f.appendChild(bars);
    f._scrollChips = true;

    const grid = el('div', 'cgm-grid cgm-grid3');
    grid.style.padding = `14px ${M.hPadPhone}px 0`;
    grid.style.gap = '10px';
    POSTERS.slice(0, 12).forEach(title => {
      const p = el('div', 'cgm-poster');
      p.style.height = '174px';
      p.style.borderRadius = `${t.tileRadius}px`;
      p.style.background = posterWash(title, t);
      const ini = el('div', 'cgm-pini', initials(title));
      ini.style.fontSize = '20px';
      p.appendChild(ini);
      grid.appendChild(p);
    });
    f.appendChild(grid);

    const pop = popoverNode({ u, t, pair: opts.pair, rows: ROWS,
      focusedName: opts.focusedName, tickedNames: opts.tickedNames });
    pop.classList.add('cgm-pop-abs');
    f._pop = pop;
    f.appendChild(pop);

    const nav = el('div', 'cgm-bnav');
    nav.style.background = t.surface;
    nav.style.height = '74px';
    ['Hjem', I18N.library[lang], 'Søk', 'Meg'].forEach((s, i) => {
      const b = el('div', 'cgm-bni' + (i === 1 ? ' on' : ''), s);
      b.style.color = i === 1 ? t.text : t.textDim;
      nav.appendChild(b);
    });
    f.appendChild(nav);
    return f;
  }

  function placePopovers(root) {
    root.querySelectorAll('.frame').forEach(f => {
      if (!f._pop) return;
      const chip = f.querySelector('.cgm-chip-open');
      const bar = f.querySelector('.cgm-barwrap');
      if (!chip || !bar) return;
      if (f._scrollChips) {
        const strip = bar.querySelector('.cgm-chips');
        const dx = chip.getBoundingClientRect().left - bar.getBoundingClientRect().left - M.hPadPhone;
        if (dx > 0) strip.style.transform = `translateX(${-dx}px)`;
      }
      const fr = f.getBoundingClientRect(), cr = chip.getBoundingClientRect(), br = bar.getBoundingClientRect();
      const u = f.classList.contains('cgm-tv') ? 2 : 1;
      const hPad = (f.classList.contains('cgm-tv') ? M.hPadTv : M.hPadPhone) * u;
      const w = f._pop.getBoundingClientRect().width;
      let x = cr.left - fr.left;
      x = Math.min(x, fr.width - hPad - w);
      x = Math.max(x, hPad);
      f._pop.style.left = `${x}px`;
      f._pop.style.top = `${br.bottom - fr.top + 8 * u}px`;
    });
  }

  // ------------------------------------------------------------------ canvas furniture
  function panel(id, title, sub) {
    const p = el('section', 'frame cgm-panel');
    p.id = id;
    p.appendChild(el('h2', 'cgm-pt', title));
    if (sub) p.appendChild(el('p', 'cgm-ps', sub));
    return p;
  }
  function plate(t, rows, pair, opts) {
    const box = el('div', 'cgm-dbox');
    box.style.background = t.surfaceVariant;
    box.style.padding = `${M.popPad * 2}px`;
    box.style.borderRadius = `${M.popRadius * 2}px`;
    box.style.width = `${popoverWidth(ROWS.map(r => r.name), GLYPH_ALLOWANCE) * 2}px`;
    rows.forEach(r => box.appendChild(rowNode(r, pair, t, Object.assign({ u: 2, focused: false, ticked: false }, opts || {}))));
    return box;
  }

  // ================================================================== the frames
  function build() {
    const s = document.getElementById('sheet');
    const t = theme('aurora');
    const REC = CANDIDATES.find(c => c.recommended);
    const PAIR = { ravilo: 'wall', picked: REC.id };

    // --- 1 & 2: TV, the recommended pair, both focus states ------------------------------------
    s.appendChild(tvFrame('tv-sagaer-focused', { pair: PAIR, focusedName: 'Sagaer',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));
    s.appendChild(tvFrame('tv-christmas-focused', { pair: PAIR, focusedName: 'Christmas films',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));

    // --- 3: phone ------------------------------------------------------------------------------
    s.appendChild(phoneFrame('phone-popover', { pair: PAIR, focusedName: 'Sagaer',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));

    // --- 5: the pair alone, 1x and TV size, every theme -----------------------------------------
    const gp = panel('glyphs-themes', 'The pair alone — 1x (18 px) and TV size (36 px), five themes',
      'Left of each label 1x, as the phone draws it; right the TV. Ink is colors.text on ' +
      `colors.surfaceVariant, the popover's own plate. Wall ${GLYPHS[PAIR.ravilo].ink} grid units, ` +
      `${GLYPHS[PAIR.picked].label.toLowerCase()} ${GLYPHS[PAIR.picked].ink} — matched, so the column ` +
      'does not read heavier on one kind of row than the other.');
    THEMES.forEach(th => {
      const band = el('div', 'cgm-band');
      band.style.background = th.surfaceVariant;
      const nm = el('div', 'cgm-band-nm', th.name);
      nm.style.color = th.textSecondary;
      band.appendChild(nm);
      [['Ravilo collection', PAIR.ravilo], ['Hand-picked collection', PAIR.picked]].forEach(([title, which]) => {
        const cell = el('div', 'cgm-cell');
        const g1 = el('div', 'cgm-gbox'); g1.innerHTML = glyphSvg(which, M.glyph, th.text);
        const g2 = el('div', 'cgm-gbox'); g2.innerHTML = glyphSvg(which, M.glyph * 2, th.text);
        cell.appendChild(g1); cell.appendChild(g2);
        const lb = el('div', 'cgm-cell-lb', title);
        lb.style.color = th.text;
        cell.appendChild(lb);
        band.appendChild(cell);
      });
      gp.appendChild(band);
    });
    s.appendChild(gp);

    // --- the four movie candidates, each beside the wall ----------------------------------------
    const cp = panel('movie-candidates', 'Four movie glyphs, each in the row beside the wall',
      'One Ravilo row and one hand-picked row per candidate, at TV size, on the popover plate. The ' +
      'recommended one is first. Ink is in grid units of 576; the wall is 242.');
    CANDIDATES.forEach(c => {
      const g = GLYPHS[c.id];
      const col = el('div', 'cgm-dcol');
      col.appendChild(el('div', 'cgm-dhd', `${g.label} — ink ${g.ink}` + (c.recommended ? '  ·  recommended' : '')));
      col.appendChild(plate(t, [ROWS[1], ROWS[4]], { ravilo: 'wall', picked: c.id }));
      col.appendChild(el('div', 'cgm-dnote', 'Gives — ' + c.gives));
      col.appendChild(el('div', 'cgm-dnote cgm-dcost', 'Costs — ' + c.costs));
      cp.appendChild(col);
    });
    s.appendChild(cp);

    // --- the 3 m column, all four -------------------------------------------------------------
    const tp = panel('three-metre', 'The 3 m test — all four, at 100 %, 55 % and 38 %',
      'A 55-inch 1080p panel is 0.634 mm per pixel; at 3 m one pixel subtends 0.211 mrad. This ' +
      'picture read on a 27-inch 2560-wide monitor at 600 mm is 0.388 mrad per pixel. ' +
      '0.211 / 0.388 = 0.55, so the 55 % column is the 3 m set. 38 % is a 40-inch panel at the same ' +
      'distance. All four READ at 55 %; the question this column answers is not whether a glyph is ' +
      'legible but whether it is DISTINCT FROM THE WALL, which is a field of small repeated marks.');
    CANDIDATES.forEach(c => {
      const row = el('div', 'cgm-trow');
      row.appendChild(el('div', 'cgm-tlab', `Wall / ${GLYPHS[c.id].label}`));
      DISTANCE.forEach(dist => {
        const cell = el('div', 'cgm-tcell');
        const shrink = el('div', 'cgm-shrink');
        shrink.style.transform = `scale(${dist.scale})`;
        shrink.appendChild(plate(t, [ROWS[1], ROWS[4]], { ravilo: 'wall', picked: c.id }));
        cell.appendChild(shrink);
        cell.appendChild(el('div', 'cgm-tcap', dist.label));
        row.appendChild(cell);
      });
      tp.appendChild(row);
    });
    tp.appendChild(el('div', 'cgm-ps cgm-ps2', DISTANCE.map(x => `${x.label} — ${x.caption}`).join('   ')));
    s.appendChild(tp);

    // --- correcting a claim the previous round made about this very glyph ----------------------
    const wp = panel('wall-gaps', 'Correcting last round — the wall\'s gaps hold',
      'The previous round listed as a cost of direction C that "six tiles fuse into one block at TV ' +
      'distance". That was wrong, and this is the picture. The tiles sit 0.8 grid units apart — ' +
      '1.2 px at TV size — and they stay apart through 55 % and through 38 %. What they stop being ' +
      'is six posters: at 3 m the wall reads as a FIELD OF SMALL MARKS rather than as a grid of ' +
      'tiles. The wall needs no change; the correction matters because the claim was one of the ' +
      'costs the owner picked C in spite of.');
    const col = el('div', 'cgm-dcol');
    col.appendChild(el('div', 'cgm-dhd', `The wall as picked — 0.8-unit gaps, ink ${GLYPHS.wall.ink}`));
    const wstrip = el('div', 'cgm-wstrip');
    DISTANCE.forEach(dist => {
      const cell = el('div', 'cgm-tcell');
      const shrink = el('div', 'cgm-shrink');
      shrink.style.transform = `scale(${dist.scale})`;
      const band = el('div', 'cgm-wband');
      band.style.background = t.surfaceVariant;
      band.innerHTML = glyphSvg('wall', M.glyph * 2, t.text);
      shrink.appendChild(band);
      cell.appendChild(shrink);
      cell.appendChild(el('div', 'cgm-tcap', dist.label));
      wstrip.appendChild(cell);
    });
    col.appendChild(wstrip);
    wp.appendChild(col);
    s.appendChild(wp);

    placePopovers(s);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
  else build();
})();
