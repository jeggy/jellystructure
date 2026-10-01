/* Ravilo — Start over + Shuffle on a series, round 1 (prospective R343).
   Renders every [data-tv] (1920×1080 inside .shot.w2) and [data-ph] (phone) frame on the canvas.
   Stand-in series: "Lundin og vinir" — fictional kids' show, 3 seasons × 13 episodes × 11 min, viewer Olivar. */
(function () {
  const SH = '<svg viewBox="0 0 24 24"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>';
  const RS = '<svg viewBox="0 0 24 24"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>';
  const DOTS = '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>';
  const EP = {
    1: ['Fyrsta flogið', 'Holan í líðini', 'Stormurin', 'Sildin', 'Tokan', 'Fjaran', 'Bylgjan', 'Regnbogin', 'Myrkrið', 'Vinurin', 'Eggið', 'Ferðin', 'Heim aftur'],
    2: ['Nýggja árið', 'Lomvigin', 'Bátsferðin', 'Vindurin', 'Skýggini', 'Hellisgjógvin', 'Mánin', 'Snjórin', 'Fiskurin', 'Ljósið', 'Klettarnir', 'Sólin', 'Várið'],
    3: ['Stóra ferðin', 'Nýggir grannar', 'Toskurin', 'Ljósvitin', 'Hvalurin', 'Sangurin', 'Bjargið', 'Ólavsøka', 'Kavarokið', 'Gløggið', 'Skipið', 'Leikurin', 'Seinasta kvøldið']
  };
  const p2 = n => String(n).padStart(2, '0');
  const code = (s, n) => 'S' + p2(s) + 'E' + p2(n);
  const still = (s, n) => { const h = (200 + (s * 13 + n) * 23) % 360; return `linear-gradient(135deg,oklch(.42 .09 ${h}),oklch(.68 .11 ${(h + 50) % 360}))`; };
  function st(o, s, n) {
    if (o.st === 'done' || o.st === 'today') return { w: true, p: 0 };
    if (o.st === 'progress') { if (s === 1) return { w: true, p: 0 }; if (s === 2) return n <= 4 ? { w: true, p: 0 } : n === 5 ? { w: false, p: 45 } : { w: false, p: 0 }; return { w: false, p: 0 }; }
    if (o.st === 'after') return s === 1 && n === 1 ? { w: false, p: 18 } : { w: false, p: 0 };
    return { w: false, p: 0 };
  }
  const SEASON = { today: 3, progress: 2, done: 1, after: 1 };
  const FIRST = { today: 10, progress: 4, done: 1, after: 1 };

  /* ---------------- TV detail ---------------- */
  function tvBar() {
    return '<div class="rw-bar"><span class="mk"></span><span class="ni">Home</span><span class="ni">Movies</span><span class="ni cur">Series</span><span class="ni">My List</span><span class="ni">Discover</span>'
      + '<span class="rt"><span>18:42</span><span class="av">O</span></span></div>';
  }
  function tvDetail(o) {
    const done = o.st === 'done', v = o.v, fin = done || o.st === 'today';
    let pri;
    if (o.st === 'today') pri = '▶ Play · S03E13';
    else if (o.st === 'progress') pri = '▶ Resume · S02E05';
    else if (o.st === 'after') pri = '▶ Resume · S01E01';
    else pri = v === 'A' ? RS + ' Start over · S01E01' : '▶ Play · S01E01';
    let next;
    if (o.st === 'today') next = '<span class="dot"></span>Up next · S03E13 “Seinasta kvøldið”';
    else if (o.st === 'progress') next = '<span class="dot"></span>Resume S02E05 “Skýggini” · 6 min left';
    else if (o.st === 'after') next = '<span class="dot"></span>Resume S01E01 “Fyrsta flogið” · 9 min left';
    else next = '<span class="ck">✓</span>All 39 episodes watched';
    const fPri = o.f === 'play' ? ' f' : '';
    let acts = `<div class="rw-btn pri${fPri}">${pri}</div>`;
    if (v === 'A') acts += `<div class="rw-btn ic${o.f === 'shuf' ? ' f' : ''}">${SH}<span class="l">Shuffle</span></div>`;
    acts += '<div class="rw-btn">▷ Trailer</div><div class="rw-btn">＋ My List</div>';
    if (v === 'C') {
      acts += `<div class="rw-btn ic${o.menu ? ' f' : ''}">${DOTS}</div>`;
      if (o.menu) {
        let rows = `<div class="rw-mrow${o.mf === 'shuf' ? ' f' : ''}">${SH}<div><div>Shuffle</div><div class="s">Every episode, in random order</div></div></div>`;
        if (done) rows += `<div class="rw-mrow${o.mf === 'reset' ? ' f' : ''}">${RS}<div><div>Start over</div><div class="s">From S01E01 · every episode back to unwatched</div></div></div>`;
        acts += `<div class="rw-menu">${rows}</div>`;
      }
    }
    const hint = (v === 'A' && done && o.f === 'play') ? `<div class="rw-hint">${RS}Every episode goes back to unwatched — only for Olivar</div>` : '';
    const meta = '<span class="tag">HD</span><span>2021</span><span class="cert"><span class="rg">DK</span><span class="cd">A</span></span>' + (o.st === 'today' ? '<span class="wd">✓ Watched</span>' : '');
    const s = SEASON[o.st], f0 = FIRST[o.st];
    const eps = EP[s];
    const w = eps.filter((_, i) => st(o, s, i + 1).w).length;
    let eh = `<div class="rw-eh"><h2>Episodes</h2><span class="sub">${w} of 13 watched</span><span class="bar"><i style="width:${Math.round(w / 13 * 100)}%"></i></span>`;
    if (v === 'B' && done) eh += `<span class="rw-chip${o.f === 'reset' ? ' f' : ''}">${RS}Reset progress</span>`;
    eh += '</div>';
    let pills = '<div class="rw-pills">';
    for (let k = 1; k <= 3; k++) {
      const ww = EP[k].filter((_, i) => st(o, k, i + 1).w).length;
      const d = ww === 13, part = ww > 0 && !d;
      pills += `<span class="rw-pill${k === s ? ' cur' : ''}${d ? ' done' : part ? ' part' : ''}">Season ${k}${d ? '<span class="ck">✓</span>' : part ? `<span class="fr">${ww}/13</span><span class="pg" style="width:${Math.round(ww / 13 * 100)}%"></span>` : ''}</span>`;
    }
    if (v === 'B') pills += `<span class="rw-sep"></span><span class="rw-pill sh${o.f === 'shufpill' ? ' f' : ''}">${SH}Shuffle</span>`;
    pills += '</div>';
    let cards = '<div class="rw-cards">';
    for (let n = f0; n < f0 + 4; n++) {
      const e = st(o, s, n);
      const up = (o.st === 'progress' && n === 5) || (o.st === 'after' && n === 1);
      cards += `<div class="rw-card"><div class="rw-still" style="background:${still(s, n)}"><span class="num">${n}</span><span class="dur">11m</span>`
        + (e.w ? '<span class="wk">✓</span>' : '') + (e.p ? `<span class="pb"><i style="width:${e.p}%"></i></span>` : '') + (up && !e.p ? '<span class="rib">UP NEXT</span>' : '')
        + `</div><div class="t">${n}. ${eps[n - 1]}</div><div class="s">${code(s, n)} · 11 min</div></div>`;
    }
    cards += '</div>';
    return '<div class="tvs">' + `<div class="rw-hero"><div class="art"></div><div class="scr"></div><span class="ph-note">series backdrop</span></div>` + tvBar()
      + '<div class="rw-body"><div class="rw-kick"><span>Kids · Animation</span><span class="n">3 Seasons</span></div>'
      + '<div class="rw-title">Lundin og vinir</div>'
      + `<div class="rw-meta">${meta}</div>`
      + '<div class="rw-syn">Lundin the puffin and her friends on the bird cliff learn something new with every tide — a gentle animated series for the smallest viewers.</div>'
      + `<div><div class="rw-next">${next}</div></div>`
      + `<div class="rw-acts">${acts}</div>${hint}</div>`
      + `<div class="rw-eps">${eh}${pills}${cards}</div></div>`;
  }

  /* ---------------- TV player (shuffle) ---------------- */
  function tvPlayer(o) {
    let h = `<div class="tvs"><div class="vid" style="background:${still(2, 7)}"></div><span class="ph-note big">episode video</span><div class="rw-plogo">Lundin og vinir</div>`;
    if (o.mode === 'chrome') {
      h += `<div class="tvov"><div class="k rw-pk">${SH}Shuffle · S02E07</div><h3>Mánin</h3><div class="bar"><span class="d"></span><span class="kn"></span></div><div class="tm"><span>04:12</span><span>−06:48</span></div></div>`;
    } else {
      h += `<div class="tvnup"><div class="th" style="background:${still(1, 11)}"></div><div class="bd"><div class="k rw-pk">${SH}Next · shuffled</div><h4>S01E11 · Eggið</h4><div class="cd">Starts in 12 s</div><div class="rw-nacts"><b class="pri">Play now</b><b>Cancel</b></div></div></div>`;
    }
    return h + '</div>';
  }

  /* ---------------- phone detail ---------------- */
  function phDetail(o) {
    const done = o.st === 'done', v = o.v;
    let pri;
    if (o.st === 'progress') pri = '▶ Resume · S02E05';
    else if (o.st === 'after') pri = '▶ Resume · S01E01';
    else pri = v === 'A' ? RS + ' Start over · S01E01' : '▶ Play · S01E01';
    let btns = `<div class="ph-btn pri">${pri}</div>`;
    if (v === 'A') btns += `<div class="ph-btn ic" aria-label="Shuffle">${SH}</div>`;
    btns += '<div class="ph-btn">＋ My List</div>';
    if (v === 'C') btns += `<div class="ph-btn ic">${DOTS}</div>`;
    const s = SEASON[o.st];
    let sec = `<div class="ph-sec">Episodes <span class="s">Season ${s}</span>`;
    if (v === 'B') sec += `<span class="r ph-chip sh">${SH}Shuffle</span>`;
    sec += '</div>';
    if (v === 'B') {
      sec += '<div class="ph-chips">' + [1, 2, 3].map(k => `<span class="ph-chip${k === s ? ' on' : ''}">Season ${k}${done ? '<span class="ck">✓</span>' : ''}</span>`).join('') + '</div>';
      if (done) sec += `<div class="ph-reset"><span>All 39 watched</span><span class="ph-link">${RS}Reset progress</span></div>`;
    }
    const f0 = o.st === 'progress' ? 4 : 1;
    for (let n = f0; n < f0 + 4; n++) {
      const e = st(o, s, n);
      sec += `<div class="ph-ep"><div class="ph-th" style="background:${still(s, n)}"><span class="n">${p2(n)}</span>${e.w ? '<span class="wk">✓</span>' : ''}${e.p ? `<span class="pb"><i style="width:${e.p}%"></i></span>` : ''}</div><div class="b"><div class="t">${EP[s][n - 1]}</div><div class="d">${code(s, n)} · 11 min${e.p ? ' · 9 min left' : ''}</div></div></div>`;
    }
    let h = '<div class="ph"><div class="sbar"><span>18:42</span><span class="ic">▮▮▮ 82%</span></div>'
      + '<div class="ph-hero"><div class="scrim"></div><span class="ph-back">‹</span><span class="ph-note">series backdrop</span></div>'
      + '<div class="ph-body"><div class="ph-title">Lundin og vinir</div><div class="ph-meta"><span>2021</span><span class="pg">A</span><span>·</span><span>Series</span><span>·</span><span>3 seasons</span></div>'
      + (done ? '<div class="ph-done"><span class="ck">✓</span>All 39 episodes watched</div>' : '')
      + `<div class="ph-btns">${btns}</div>`
      + '<div class="ph-syn">Lundin the puffin and her friends on the bird cliff learn something new with every tide.</div>'
      + sec + '</div>';
    if (o.sheet) {
      h += '<div class="scrim"></div><div class="sheet"><div class="grab"></div>'
        + '<div class="ph-shh"><div class="c"></div><div><div class="t">Lundin og vinir</div><div class="d">3 seasons · 39 episodes</div></div></div>'
        + `<div class="ph-srow">${SH}<div><div class="nm">Shuffle</div><div class="sub">Every episode, in random order</div></div></div>`
        + (done ? `<div class="ph-srow">${RS}<div><div class="nm">Start over</div><div class="sub">From S01E01 · every episode back to unwatched</div></div></div>` : '')
        + '</div>';
    }
    return h + '<div class="gpill"></div></div>';
  }

  document.querySelectorAll('[data-tv]').forEach(n => {
    const o = { v: n.dataset.v, st: n.dataset.st, f: n.dataset.f, menu: n.dataset.menu === '1', mf: n.dataset.mf };
    n.innerHTML = '<div class="inner">' + tvDetail(o) + '</div>';
  });
  document.querySelectorAll('[data-pl]').forEach(n => { n.innerHTML = '<div class="inner">' + tvPlayer({ mode: n.dataset.pl }) + '</div>'; });
  document.querySelectorAll('[data-ph]').forEach(n => {
    n.innerHTML = phDetail({ v: n.dataset.v, st: n.dataset.st, sheet: n.dataset.sheet === '1' });
  });
})();
