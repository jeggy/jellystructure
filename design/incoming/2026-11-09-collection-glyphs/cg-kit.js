/* Collection popover glyphs — the frame builder.
   Every number comes from CG (cg-data.js). Nothing is measured off a picture.

   SVG hygiene (PROCESS.md): no <defs>, no gradient, no paint server of any kind — flat fills only,
   so nothing can be defined inside a hidden frame and paint nothing in a visible one. Ids are still
   minted from a counter, because the trap costs nothing to avoid and an hour to find. */
(function () {
  'use strict';
  const { M, THEMES, theme, GLYPHS, ROWS, ROWS_LONG, DIRECTIONS, REJECTED, INK, I18N, POSTERS,
    FACETS, DISTANCE, GLYPH_ALLOWANCE, popoverWidth } = window.CG;

  let seq = 0;
  const uid = (p) => `${p}-${++seq}`;
  const el = (tag, cls, txt) => { const n = document.createElement(tag); if (cls) n.className = cls; if (txt != null) n.textContent = txt; return n; };

  // ------------------------------------------------------------------ the glyph, as flat SVG
  // size is in CSS px; the 24-unit grid scales to it. `sw` strokes scale with the box, so the
  // stroke/box ratio is fixed at 0.10 whatever the size — the same way DeskIcon and MusicGlyph work.
  function glyphSvg(name, sizePx, colour) {
    const g = GLYPHS[name];
    if (!g) throw new Error('no glyph ' + name);
    const body = g.parts.map(p => {
      if (p.line) {
        const [x1, y1, x2, y2] = p.line;
        return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${colour}" stroke-width="${p.sw}" stroke-linecap="round"/>`;
      }
      if (p.rect) {
        const [x, y, w, h] = p.rect;
        return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${p.r || 0}" fill="${p.fill ? colour : 'none'}"${p.fill ? '' : ` stroke="${colour}" stroke-width="${p.sw}"`}/>`;
      }
      if (p.circle) {
        const [cx, cy, r] = p.circle;
        return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${p.fill ? colour : 'none'}"${p.fill ? '' : ` stroke="${colour}" stroke-width="${p.sw}"`}/>`;
      }
      if (p.poly) {
        let d = 'M' + p.poly.map(pt => pt.join(' ')).join(' L') + ' Z';
        if (p.hole) {
          const [cx, cy] = p.hole.c, r = p.hole.r;
          // a circle as two arcs, wound so even-odd makes it a hole
          d += ` M${cx - r} ${cy} A${r} ${r} 0 1 0 ${cx + r} ${cy} A${r} ${r} 0 1 0 ${cx - r} ${cy} Z`;
        }
        return `<path d="${d}" fill="${colour}" fill-rule="evenodd"/>`;
      }
      if (p.star) {
        const [cx, cy, R, r] = p.star, pts = [];
        for (let i = 0; i < 10; i++) {
          const rad = (i % 2 ? r : R), a = -Math.PI / 2 + i * Math.PI / 5;
          pts.push([(cx + rad * Math.cos(a)).toFixed(2), (cy + rad * Math.sin(a)).toFixed(2)]);
        }
        return `<path d="M${pts.map(q => q.join(' ')).join(' L')} Z" fill="${colour}"/>`;
      }
      return '';
    }).join('');
    return `<svg class="cg-g" id="${uid('g')}" width="${sizePx}" height="${sizePx}" viewBox="0 0 24 24" aria-hidden="true">${body}</svg>`;
  }

  // ------------------------------------------------------------------ one popover row
  // The shipped row, part for part: an 18 dp check slot, then — new — an 18 dp glyph slot and an
  // 8 dp gap, then the name at 14 sp taking the remaining width, then the count at 13 sp.
  function rowNode(row, dir, t, opts) {
    const u = opts.u;                       // CSS px per dp
    const n = el('div', 'cg-row' + (opts.focused ? ' on' : ''));
    n.style.padding = `${M.rowPadV * u}px ${M.rowPadH * u}px`;
    n.style.borderRadius = `${M.rowRadius * u}px`;
    if (opts.focused) n.style.background = t.surface;

    const check = el('div', 'cg-slot');
    check.style.width = `${M.checkSlot * u}px`;
    check.style.height = `${M.rowLine * u}px`;
    if (opts.ticked) {
      // the shipped CheckGlyph: 14 dp, colors.accent, stroke 0.14 of its box
      const s = M.check * u;
      check.innerHTML = `<svg width="${s}" height="${s}" viewBox="0 0 24 24" id="${uid('c')}" aria-hidden="true">` +
        `<path d="M4.3 12.7 L10.1 18.2 L20.2 6.5" fill="none" stroke="${t.accent}" stroke-width="3.36" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    }
    n.appendChild(check);

    if (dir) {
      const slot = el('div', 'cg-slot cg-gslot');
      slot.style.width = `${M.glyph * u}px`;
      slot.style.height = `${M.rowLine * u}px`;
      slot.style.marginRight = `${M.gapAfterGlyph * u}px`;
      const which = row.kind === 'ravilo' ? dir.ravilo : dir.picked;
      slot.innerHTML = glyphSvg(which, M.glyph * u, t.text);
      n.appendChild(slot);
    }

    const name = el('div', 'cg-name', row.name);
    name.style.fontSize = `${M.nameSp * u}px`;
    name.style.lineHeight = `${M.rowLine * u}px`;
    name.style.color = t.text;
    n.appendChild(name);

    const cnt = el('div', 'cg-count', String(row.count));
    cnt.style.fontSize = `${M.countSp * u}px`;
    cnt.style.lineHeight = `${M.rowLine * u}px`;
    cnt.style.color = t.textSecondary;
    n.appendChild(cnt);
    return n;
  }

  // ------------------------------------------------------------------ the popover
  function popoverNode(opts) {
    const { u, t, dir, rows, focusedName, tickedNames, lang } = opts;
    const labels = rows.map(r => r.name);
    const wDp = popoverWidth(labels, dir ? GLYPH_ALLOWANCE : 0);
    const box = el('div', 'cg-pop');
    box.style.width = `${wDp * u}px`;
    box.style.padding = `${M.popPad * u}px`;
    box.style.borderRadius = `${M.popRadius * u}px`;
    box.style.background = t.surfaceVariant;
    const list = el('div', 'cg-list');
    list.style.maxHeight = `${M.popMaxH * u}px`;
    rows.forEach(r => list.appendChild(rowNode(r, dir, t, {
      u, focused: r.name === focusedName, ticked: (tickedNames || []).includes(r.name),
    })));
    box.appendChild(list);
    box._wDp = wDp;
    box._lang = lang;
    return box;
  }

  // ------------------------------------------------------------------ the facet chip bar
  function chipBar(opts) {
    const { u, t, lang, activeIndex, summary, openIndex } = opts;
    const bar = el('div', 'cg-chips');
    bar.style.gap = `${M.chipGap * u}px`;
    const names = FACETS[lang];
    names.forEach((label, i) => {
      const chip = el('div', 'cg-chip');
      const active = i === activeIndex && summary;
      chip.style.padding = `${M.chipPadV * u}px ${M.chipPadH * u}px`;
      chip.style.borderRadius = `${M.chipRadius * u}px`;
      chip.style.background = active ? t.accent : t.surfaceVariant;
      chip.style.fontSize = `${M.chipFontSp * u}px`;
      chip.style.color = active ? t.onAccent : t.textSecondary;
      chip.style.fontWeight = active ? 600 : 400;
      // shipped: Text(if (badge != null) "$label ($badge)" else label)
      chip.textContent = active ? `${label} (${summary})` : label;
      if (i === openIndex) chip.classList.add('cg-chip-open');
      bar.appendChild(chip);
    });
    return bar;
  }

  // ------------------------------------------------------------------ a TV page behind it all
  function tvFrame(id, opts) {
    const u = 2;                             // a TV is 960 x 540 dp on a 1920 x 1080 stage
    const t = theme(opts.themeId || 'aurora');
    const lang = opts.lang || 'en';
    const f = el('div', 'frame cg-tv');
    f.id = id;
    f.style.background = t.background;
    f.style.color = t.text;

    // app bar
    const bar = el('div', 'cg-appbar');
    bar.style.height = `${M.appBar * u}px`;
    bar.style.padding = `0 ${M.hPadTv * u}px`;
    const mark = el('div', 'cg-mark', 'RAVILO');
    mark.style.color = t.text;
    bar.appendChild(mark);
    const nav = el('div', 'cg-nav');
    [I18N.page[lang], 'Serier', I18N.library[lang], 'Søk'].forEach((s, i) => {
      const a = el('span', 'cg-navi' + (i === 0 ? ' on' : ''), s);
      a.style.color = i === 0 ? t.text : t.textSecondary;
      nav.appendChild(a);
    });
    bar.appendChild(nav);
    const av = el('div', 'cg-av', 'J');
    av.style.background = t.accent; av.style.color = t.onAccent;
    bar.appendChild(av);
    f.appendChild(bar);

    // page title
    const h = el('h1', 'cg-h1', I18N.page[lang]);
    h.style.margin = `${18 * u}px ${M.hPadTv * u}px ${12 * u}px`;
    h.style.color = t.text;
    f.appendChild(h);

    // the chip bar, with the Collection chip at index 5
    const bars = el('div', 'cg-barwrap');
    bars.style.padding = `0 ${M.hPadTv * u}px`;
    bars.appendChild(chipBar({ u, t, lang, activeIndex: 5, summary: opts.summary, openIndex: opts.popover ? 5 : -1 }));
    f.appendChild(bars);

    // the grid
    const grid = el('div', 'cg-grid');
    grid.style.padding = `${20 * u}px ${M.hPadTv * u}px 0`;
    grid.style.gap = `${16 * u}px`;
    POSTERS.forEach(title => {
      const p = el('div', 'cg-poster');
      p.style.width = `${150 * u}px`;
      p.style.height = `${225 * u}px`;
      p.style.borderRadius = `${t.tileRadius * u}px`;
      p.style.background = posterWash(title, t);
      const ini = el('div', 'cg-pini', initials(title));
      ini.style.fontSize = `${28 * u}px`;
      p.appendChild(ini);
      grid.appendChild(p);
    });
    f.appendChild(grid);

    // the popover, placed with its left edge under the chip it opened (R362)
    if (opts.popover) {
      const pop = popoverNode({ u, t, dir: opts.dir, rows: opts.rows || ROWS,
        focusedName: opts.focusedName, tickedNames: opts.tickedNames, lang });
      pop.classList.add('cg-pop-abs');
      // measured after layout; set in place() below
      f._pop = pop;
      f.appendChild(pop);
    }
    return f;
  }

  // after layout: put the popover's left edge under the open chip, clamped to the gutter
  function placePopovers(root) {
    root.querySelectorAll('.frame').forEach(f => {
      if (!f._pop) return;
      const chip = f.querySelector('.cg-chip-open');
      const bar = f.querySelector('.cg-barwrap');
      if (!chip || !bar) return;
      if (f._scrollChips) {
        // carry the open chip to the gutter, clipping the chips before it
        const strip = bar.querySelector('.cg-chips');
        const dx = chip.getBoundingClientRect().left - bar.getBoundingClientRect().left - M.hPadPhone;
        if (dx > 0) strip.style.transform = `translateX(${-dx}px)`;
      }
      const fr = f.getBoundingClientRect(), cr = chip.getBoundingClientRect(), br = bar.getBoundingClientRect();
      const u = f.classList.contains('cg-tv') ? 2 : 1;
      const hPad = (f.classList.contains('cg-tv') ? M.hPadTv : M.hPadPhone) * u;
      const w = f._pop.getBoundingClientRect().width;
      let x = cr.left - fr.left;
      x = Math.min(x, fr.width - hPad - w);
      x = Math.max(x, hPad);
      f._pop.style.left = `${x}px`;
      f._pop.style.top = `${br.bottom - fr.top + 8 * u}px`;
    });
  }

  function initials(t) { return t.replace(/[^A-Za-zÀ-ÿ0-9 ]/g, '').split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase(); }
  function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return Math.abs(h); }
  // the same deterministic two-stop wash ravilo-data.js uses for a poster with no artwork
  function posterWash(title, t) {
    const x = hash(title), a = x % 360, b = (a + 36 + ((x >> 3) % 50)) % 360;
    const l1 = (t.isLight ? 62 : 30) + ((x >> 5) % 12), l2 = (t.isLight ? 44 : 12) + ((x >> 7) % 8);
    return `linear-gradient(145deg, hsl(${a} 42% ${l1}%), hsl(${b} 46% ${l2}%))`;
  }

  // ------------------------------------------------------------------ a phone page behind it all
  function phoneFrame(id, opts) {
    const u = 1;                             // a Pixel 9 is 412 dp wide
    const t = theme(opts.themeId || 'aurora');
    const lang = opts.lang || 'en';
    const f = el('div', 'frame cg-phone');
    f.id = id;
    f.style.width = '412px'; f.style.height = '915px';
    f.style.background = t.background;

    const status = el('div', 'cg-status');
    status.style.color = t.textSecondary;
    status.appendChild(el('span', null, '21:40'));
    status.appendChild(el('span', null, '•••'));
    f.appendChild(status);

    const h = el('h1', 'cg-h1 cg-h1p', I18N.library[lang]);
    h.style.margin = `4px ${M.hPadPhone}px 10px`;
    h.style.color = t.text;
    f.appendChild(h);

    // The strip scrolls on a phone exactly as it does on a TV (shipped: a LazyRow keeping its
    // position). Opening Collection means Collection has been scrolled to, so the bar is drawn
    // carried to it, with the chips before it clipped at the gutter.
    const bars = el('div', 'cg-barwrap cg-barscroll');
    const strip = chipBar({ u, t, lang, activeIndex: 5, summary: opts.summary, openIndex: 5 });
    strip.style.padding = `0 ${M.hPadPhone}px`;
    bars.appendChild(strip);
    f.appendChild(bars);
    f._scrollChips = true;

    const grid = el('div', 'cg-grid cg-grid3');
    grid.style.padding = `14px ${M.hPadPhone}px 0`;
    grid.style.gap = '10px';
    POSTERS.slice(0, 12).forEach(title => {
      const p = el('div', 'cg-poster');
      p.style.height = '174px';
      p.style.borderRadius = `${t.tileRadius}px`;
      p.style.background = posterWash(title, t);
      const ini = el('div', 'cg-pini', initials(title));
      ini.style.fontSize = '20px';
      p.appendChild(ini);
      grid.appendChild(p);
    });
    f.appendChild(grid);

    const pop = popoverNode({ u, t, dir: opts.dir, rows: opts.rows || ROWS,
      focusedName: opts.focusedName, tickedNames: opts.tickedNames, lang });
    pop.classList.add('cg-pop-abs');
    f._pop = pop;
    f.appendChild(pop);

    const nav = el('div', 'cg-bnav');
    nav.style.background = t.surface;
    nav.style.height = '74px';
    ['Hjem', I18N.library[lang], 'Søk', 'Meg'].forEach((s, i) => {
      const b = el('div', 'cg-bni' + (i === 1 ? ' on' : ''), s);
      b.style.color = i === 1 ? t.text : t.textDim;
      nav.appendChild(b);
    });
    f.appendChild(nav);
    return f;
  }

  // ------------------------------------------------------------------ canvas furniture
  function sheet() { return document.getElementById('sheet'); }
  function panel(id, title, sub) {
    const p = el('section', 'frame cg-panel');
    p.id = id;
    p.appendChild(el('h2', 'cg-pt', title));
    if (sub) p.appendChild(el('p', 'cg-ps', sub));
    return p;
  }
  function caption(f, text) {
    const c = el('div', 'cg-cap', text);
    f.appendChild(c);
    return f;
  }

  // ================================================================== the frames
  function build() {
    const s = sheet();

    // --- 1 & 2: TV, direction A, the two focus states the brief asks for ------------------------
    const A = DIRECTIONS[0], B = DIRECTIONS[1], C = DIRECTIONS[2];
    s.appendChild(tvFrame('tv-a-sagaer-focused', { dir: A, popover: true, focusedName: 'Sagaer',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));
    s.appendChild(tvFrame('tv-a-christmas-focused', { dir: A, popover: true, focusedName: 'Christmas films',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));

    // --- 3: phone ------------------------------------------------------------------------------
    s.appendChild(phoneFrame('phone-a-popover', { dir: A, focusedName: 'Sagaer',
      tickedNames: ['Film club'], summary: 'Sagaer, Film club' }));

    // --- 4: the chip bar, closed ---------------------------------------------------------------
    const cb = panel('chipbar-closed', 'The chip bar, closed',
      'The Collection chip carries the shipped summary format — "label (two names, then +N)" — and no glyph. ' +
      'Drawn at 1920. The strip is cut at the right because the facet bar scrolls, exactly as it does on the TV.');
    [['en', 'Sagaer, Film club'], ['da', 'Sagaer, Film club'], ['fo', 'Sagaer, Film club']].forEach(([lang, sum]) => {
      const t = theme('aurora');
      const line = el('div', 'cg-striprow');
      line.appendChild(el('div', 'cg-strip-lab', lang.toUpperCase()));
      const w = el('div', 'cg-strip');
      w.style.background = t.background;
      const inner = el('div', 'cg-barwrap');
      inner.style.padding = `20px ${M.hPadTv * 2}px`;
      inner.appendChild(chipBar({ u: 2, t, lang, activeIndex: 5, summary: sum, openIndex: -1 }));
      w.appendChild(inner);
      line.appendChild(w);
      cb.appendChild(line);
    });
    s.appendChild(cb);

    // --- 5: the two glyphs alone, 1x and TV size, in every theme --------------------------------
    const gp = panel('glyphs-themes', 'The pair alone — 1x (18 px) and TV size (36 px), five themes',
      'Left column 1x, as the phone draws it: an 18 dp box at density 1. Right column the TV: the same ' +
      '18 dp box at density 2. Ink is colors.text on colors.surfaceVariant, the popover\'s own plate.');
    THEMES.forEach(t => {
      const band = el('div', 'cg-band');
      band.style.background = t.surfaceVariant;
      const nm = el('div', 'cg-band-nm', t.name);
      nm.style.color = t.textSecondary;
      band.appendChild(nm);
      [['Ravilo collection', A.ravilo], ['Hand-picked collection', A.picked]].forEach(([title, which]) => {
        const cell = el('div', 'cg-cell');
        const g1 = el('div', 'cg-gbox');
        g1.innerHTML = glyphSvg(which, M.glyph, t.text);
        const g2 = el('div', 'cg-gbox');
        g2.innerHTML = glyphSvg(which, M.glyph * 2, t.text);
        cell.appendChild(g1); cell.appendChild(g2);
        const lb = el('div', 'cg-cell-lb', title);
        lb.style.color = t.text;
        cell.appendChild(lb);
        band.appendChild(cell);
      });
      gp.appendChild(band);
    });
    s.appendChild(gp);

    // --- 6: the three directions, the same row, side by side -------------------------------------
    const dp = panel('directions-compare', 'The three directions, in the row they live in',
      'One Ravilo row and one hand-picked row per direction, at TV size (36 px glyph, 28 px name), on ' +
      'the popover plate. The recommended pair is first.');
    DIRECTIONS.forEach(d => {
      const t = theme('aurora');
      const col = el('div', 'cg-dcol');
      const hd = el('div', 'cg-dhd', `${d.id} — ${d.name}${d.recommended ? '  ·  recommended' : ''}`);
      col.appendChild(hd);
      const box = el('div', 'cg-dbox');
      box.style.background = t.surfaceVariant;
      box.style.padding = `${M.popPad * 2}px`;
      box.style.borderRadius = `${M.popRadius * 2}px`;
      [ROWS[1], ROWS[4]].forEach(r => box.appendChild(rowNode(r, d, t, { u: 2, focused: false, ticked: false })));
      col.appendChild(box);
      col.appendChild(el('div', 'cg-dnote', 'Gives — ' + d.gives));
      col.appendChild(el('div', 'cg-dnote cg-dcost', 'Costs — ' + d.costs));
      dp.appendChild(col);
    });
    const rj = el('div', 'cg-dcol cg-rej');
    rj.appendChild(el('div', 'cg-dhd', 'Drawn and dropped'));
    const rbox = el('div', 'cg-dbox');
    const tA = theme('aurora');
    rbox.style.background = tA.surfaceVariant;
    rbox.style.padding = `${M.popPad * 2}px`;
    rbox.style.borderRadius = `${M.popRadius * 2}px`;
    REJECTED.forEach(r => {
      const row = el('div', 'cg-rejrow');
      const g = el('div', 'cg-gbox');
      g.innerHTML = glyphSvg(r.glyph, M.glyph * 2, tA.text);
      row.appendChild(g);
      const tx = el('div', 'cg-rejtx', GLYPHS[r.glyph].label + ' — ' + r.why);
      row.appendChild(tx);
      rbox.appendChild(row);
    });
    rj.appendChild(rbox);
    dp.appendChild(rj);
    s.appendChild(dp);

    // --- 7: the distance test -------------------------------------------------------------------
    const tp = panel('three-metre', 'The 3 m test — the same rows at 100 %, 55 % and 38 %',
      'A 55-inch 1080p panel is 0.634 mm per pixel; at 3 m one pixel subtends 0.211 mrad. This picture ' +
      'read on a 27-inch 2560-wide monitor at 600 mm is 0.388 mrad per pixel. 0.211 / 0.388 = 0.55, so ' +
      'the 55 % column is the 3 m set. 38 % is a 40-inch panel at the same distance.');
    DIRECTIONS.forEach(d => {
      const t = theme('aurora');
      const row = el('div', 'cg-trow');
      row.appendChild(el('div', 'cg-tlab', `${d.id} — ${d.name}`));
      DISTANCE.forEach(dist => {
        const cell = el('div', 'cg-tcell');
        const shrink = el('div', 'cg-shrink');
        shrink.style.transform = `scale(${dist.scale})`;
        const box = el('div', 'cg-dbox');
        box.style.background = t.surfaceVariant;
        box.style.padding = `${M.popPad * 2}px`;
        box.style.borderRadius = `${M.popRadius * 2}px`;
        box.style.width = `${popoverWidth(ROWS.map(r => r.name), GLYPH_ALLOWANCE) * 2}px`;
        [ROWS[1], ROWS[4]].forEach(r => box.appendChild(rowNode(r, d, t, { u: 2, focused: false, ticked: false })));
        shrink.appendChild(box);
        cell.appendChild(shrink);
        cell.appendChild(el('div', 'cg-tcap', dist.label));
        row.appendChild(cell);
      });
      tp.appendChild(row);
    });
    const dcap = el('div', 'cg-ps cg-ps2', DISTANCE.map(d => `${d.label} — ${d.caption}`).join('   '));
    tp.appendChild(dcap);
    s.appendChild(tp);

    // --- 8: the row's anatomy, measured --------------------------------------------------------
    const ap = panel('row-anatomy', 'What the round fixes, in dp',
      'The row at TV size with every number this round sets or leaves alone. dp, not px: the TV draws ' +
      'these at density 2 and the phone at density 1.');
    const t = theme('aurora');
    const abox = el('div', 'cg-abox');
    abox.style.background = t.surfaceVariant;
    abox.style.padding = `${M.popPad * 2}px`;
    abox.style.borderRadius = `${M.popRadius * 2}px`;
    abox.style.width = `${popoverWidth(ROWS.map(r => r.name), GLYPH_ALLOWANCE) * 2}px`;
    abox.appendChild(rowNode(ROWS[1], A, t, { u: 2, focused: true, ticked: false }));
    abox.appendChild(rowNode(ROWS[4], A, t, { u: 2, focused: false, ticked: true }));
    const aw = el('div', 'cg-awrap');
    aw.appendChild(abox);
    const tbl = el('table', 'cg-tbl');
    [
      ['glyph box', `${M.glyph} dp square`, 'fixed by this round'],
      ['glyph construction', 'filled silhouettes on a 24-unit grid, no stroke',
        'fixed by this round — a 1.8-unit stroke is 2.7 px of a 36 px glyph and is gone at 3 m; the set ' +
        'already fills (DeskIcon.PLAY/PAUSE/NEXT, MusicIcon.MORE)'],
      ['glyph position', `after the ${M.checkSlot} dp check slot, then ${M.gapAfterGlyph} dp before the name`, 'fixed by this round — the AUDIO flag\'s own pattern'],
      ['glyph ink, resting', 'colors.text', 'fixed by this round'],
      ['glyph ink, focused', 'colors.text — unchanged', 'fixed by this round'],
      ['check slot', `${M.checkSlot} dp, ${M.check} dp CheckGlyph in colors.accent`, 'shipped, untouched'],
      ['row padding', `${M.rowPadH} dp / ${M.rowPadV} dp`, 'shipped, untouched'],
      ['row height', `${M.rowPadV * 2 + M.rowLine} dp`, 'shipped — the 18 dp glyph is shorter than the 20 dp text line, so it adds nothing'],
      ['name', `${M.nameSp} sp Sora, colors.text`, 'shipped, untouched'],
      ['count', `${M.countSp} sp Sora, colors.textSecondary`, 'shipped, untouched'],
      ['popover width', `(longest x 8 + 110 + ${GLYPH_ALLOWANCE}) clamped ${M.popMinW}..${M.popMaxW} dp`,
        `the ONE change: CHANNEL takes the allowance AUDIO already takes. Here ${popoverWidth(ROWS.map(r => r.name), GLYPH_ALLOWANCE)} dp.`],
      ['popover height', `${M.popPad * 2 + ROWS.length * (M.rowPadV * 2 + M.rowLine)} dp of a ${M.popMaxH} dp maximum`, 'shipped — six rows do not scroll'],
      ['focus', 'one target per row, the plate is the whole cue', 'shipped, untouched'],
    ].forEach(([k, v, n]) => {
      const tr = el('tr');
      tr.appendChild(el('th', null, k));
      tr.appendChild(el('td', 'cg-tdv', v));
      tr.appendChild(el('td', 'cg-tdn', n));
      tbl.appendChild(tr);
    });
    aw.appendChild(tbl);
    ap.appendChild(aw);
    s.appendChild(ap);

    // --- 9: the ink alternatives ---------------------------------------------------------------
    const ip = panel('ink-alternatives', 'Focus-state ink — the recommendation and the two it beat',
      INK.note);
    [{ id: 'same', resting: 'text', focused: 'text', why: 'Recommended. The glyph is content, like the name.', cost: '' }]
      .concat(INK.alternatives).forEach(alt => {
        const col = el('div', 'cg-dcol');
        col.appendChild(el('div', 'cg-dhd', `resting ${alt.resting} / focused ${alt.focused}` + (alt.id === 'same' ? '  ·  recommended' : '')));
        const box = el('div', 'cg-dbox');
        box.style.background = t.surfaceVariant;
        box.style.padding = `${M.popPad * 2}px`;
        box.style.borderRadius = `${M.popRadius * 2}px`;
        box.style.width = `${popoverWidth(ROWS.map(r => r.name), GLYPH_ALLOWANCE) * 2}px`;
        [[ROWS[1], true], [ROWS[4], false]].forEach(([r, focused]) => {
          const node = rowNode(r, A, t, { u: 2, focused, ticked: !focused });
          const svg = node.querySelector('.cg-gslot svg');
          const ink = focused ? t[alt.focused] : t[alt.resting];
          svg.querySelectorAll('[fill]:not([fill="none"])').forEach(n => n.setAttribute('fill', ink));
          svg.querySelectorAll('[stroke]').forEach(n => n.setAttribute('stroke', ink));
          box.appendChild(node);
        });
        col.appendChild(box);
        col.appendChild(el('div', 'cg-dnote', alt.why));
        if (alt.cost) col.appendChild(el('div', 'cg-dnote cg-dcost', 'Costs — ' + alt.cost));
        ip.appendChild(col);
      });
    s.appendChild(ip);

    // --- 10: the longest string, and the other two languages -----------------------------------
    const lp = panel('longest-string', 'The longest string — and why there is no Danish or Faroese popover',
      'The rows in this popover are OPERATOR data: a collection is named once, by the person who made ' +
      'it, and Ravilo shows that name in every language. So en, da and fo give the same six rows, and ' +
      'the only string this round translates is the chip above (the previous frame) and the two ' +
      'screen-reader labels. What does vary is length — these are the two sides of the clamp.');
    [
      ['Every language — the shipped six', ROWS, 'Film club'],
      ['The clamp, both sides — 58 characters ellipsises, 40 fits with about 2 dp to spare', ROWS_LONG, null],
    ].forEach(([head, rows, tick]) => {
      const col = el('div', 'cg-dcol' + (rows === ROWS_LONG ? ' cg-dwide' : ''));
      col.appendChild(el('div', 'cg-dhd', head));
      const pop = popoverNode({ u: 2, t, dir: A, rows, focusedName: rows[1].name,
        tickedNames: tick ? [tick] : [], lang: 'en' });
      col.appendChild(pop);
      col.appendChild(el('div', 'cg-dnote', `popover ${pop._wDp} dp wide` +
        (rows === ROWS_LONG ? ' — the 420 dp clamp. The glyph and its gap take 26 dp of the name box, about four characters.' : '')));
      lp.appendChild(col);
    });
    s.appendChild(lp);

    placePopovers(s);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
  else build();
})();
