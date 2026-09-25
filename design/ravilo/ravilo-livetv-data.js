/* Live TV mock data for the Ravilo prototype.
   Surfaces "Jellyfin Live TV" channels + a generated EPG. Deterministic (seeded) so the
   guide is stable across reloads/screenshots. Times are minutes-from-midnight; NOW is fixed.
   Exposes window.LiveTV. */
(function () {
  const NOW = 20 * 60 + 14;                 // 20:14, the fixed "live edge"
  const WIN_START = 19 * 60;                // guide window 19:00 …
  const WIN_END = 24 * 60 + 30;             // … 24:30 (next-day 00:30)
  const SLOT = 30;                          // minutes per guide column

  // seeded PRNG (mulberry32)
  function rng(seed) { return function () { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

  // category → hue + program-title pool
  const CATS = {
    news:   { label: 'News',          hue: 208 },
    sport:  { label: 'Sport',         hue: 150 },
    ent:    { label: 'Entertainment', hue: 280 },
    film:   { label: 'Film',          hue: 20  },
    kids:   { label: 'Kids',          hue: 45  },
    doc:    { label: 'Documentary',   hue: 190 },
    music:  { label: 'Music',         hue: 330 },
    nordic: { label: 'Nordic',        hue: 260 },
  };
  const TITLES = {
    news: ['Kvøldnýtíðindi', 'Nordic News at Nine', 'World Report', 'Business Tonight', 'The Briefing', 'Weather & Seas', 'Regional Roundup', 'Late Edition'],
    sport: ['Premier League Live', 'Handball: KÍ vs HB', 'Match of the Day', 'Sailing: Atlantic Cup', 'Cycling Highlights', 'Football Weekly', 'Rowing Championship', 'Darts Night'],
    ent: ['Stormester', 'The Great Bake', 'Quiz Night', 'Talk of the Town', 'Dancing on Ice', 'Comedy Hour', 'Game Show Gold', 'Saturday Live'],
    film: ['The Last Fjord', 'Iron Veil', 'Midnight Sun Patrol', 'Snowbound', 'Crimson Tide Rising', 'Phantom Circuit', 'Edge of Tomorrow Bay', 'The Quiet Coast'],
    kids: ['Little Robots', 'Cartoon Corner', 'Adventure Bay', 'Draw With Me', 'Dino Squad', 'Bedtime Tales', 'Puppet Playhouse', 'Space Cadets'],
    doc: ['Havets Hjarta', 'Wild Scandinavia', 'Engineering Giants', 'Deep Ocean', 'Ancient Roads', 'The Human Body', 'Cosmos Explained', 'Vanishing Glaciers'],
    music: ['Beat Sessions', 'Live at the Harbour', 'Chart Countdown', 'Classical Evening', 'Jazz Café', 'Faroese Folk', 'Indie Spotlight', 'Rewind: 90s'],
    nordic: ['Nordvest', 'Borgen Vinter', 'Efterforskningen Nat', 'Bron Returns', 'Vikings of Tórshavn', 'Hraðar Ljós', 'First Frost', 'The Crown Road'],
  };
  const SUB = {
    news: 'Live', sport: 'Live', ent: 'New episode', film: 'Feature film', kids: 'Series',
    doc: 'Documentary', music: 'Live music', nordic: 'Drama series',
  };

  // 40 channels — fictional Nordic/Faroese-flavoured lineup, consistent with existing demo networks
  const CH = [
    ['Kringvarp 1', 'news'], ['Kringvarp 2', 'nordic'], ['KVF Sport', 'sport'], ['DR1', 'ent'],
    ['DR2', 'doc'], ['TV 2', 'ent'], ['TV 2 Nyheder', 'news'], ['TV 2 Sport', 'sport'],
    ['Atlantic Film', 'film'], ['Fjord Film', 'film'], ['Nordic Noir', 'nordic'], ['Saga Drama', 'nordic'],
    ['Kids Kanal', 'kids'], ['Mini Toons', 'kids'], ['Nature HD', 'doc'], ['Planet Doc', 'doc'],
    ['Beat FM TV', 'music'], ['Klassisk', 'music'], ['World News 24', 'news'], ['Business Live', 'news'],
    ['Premier Sport 1', 'sport'], ['Premier Sport 2', 'sport'], ['Sejl & Hav', 'sport'], ['Comedy Central', 'ent'],
    ['Game Show TV', 'ent'], ['Retro Movies', 'film'], ['Action Max', 'film'], ['Indie Cinema', 'film'],
    ['Faroese Folk', 'music'], ['Chart Hits', 'music'], ['History Now', 'doc'], ['Science World', 'doc'],
    ['Kids Læring', 'kids'], ['Cartoon Bay', 'kids'], ['Regional Nord', 'news'], ['Vejr & Hav', 'news'],
    ['Drama One', 'nordic'], ['Nordic Life', 'nordic'], ['Sport Extra', 'sport'], ['Late Night', 'ent'],
  ];

  const channels = CH.map((c, i) => {
    const cat = c[1], h = CATS[cat].hue;
    return {
      id: 'ch' + (i + 1),
      num: 101 + i,
      name: c[0],
      cat,
      catLabel: CATS[cat].label,
      // logo placeholder: initials + a per-channel gradient
      initials: c[0].split(' ').map(w => w[0]).join('').slice(0, 3).toUpperCase(),
      grad: `linear-gradient(135deg, hsl(${h} 55% 38%), hsl(${(h + 40) % 360} 60% 20%))`,
      hue: h,
    };
  });

  // build a schedule for each channel across the window
  const schedule = {};
  channels.forEach((ch, ci) => {
    const r = rng(1000 + ci * 7);
    const pool = TITLES[ch.cat];
    const progs = [];
    let t = WIN_START - ((r() * 3 | 0) * 30);   // stagger some channels' program boundaries
    let k = ci;
    while (t < WIN_END) {
      const durChoices = ch.cat === 'film' ? [90, 120] : ch.cat === 'news' ? [30, 30, 60] : [30, 60, 60, 90];
      const dur = durChoices[(r() * durChoices.length) | 0];
      const title = pool[k % pool.length]; k++;
      progs.push({ title, start: t, end: t + dur, sub: SUB[ch.cat], rating: [7, 11, 13, 16, 0][(r() * 5) | 0] });
      t += dur;
    }
    schedule[ch.id] = progs;
  });

  function fmt(min) {
    let m = ((min % (24 * 60)) + 24 * 60) % (24 * 60);
    const h = (m / 60) | 0, mm = m % 60;
    return String(h).padStart(2, '0') + ':' + String(mm).padStart(2, '0');
  }
  function progAt(chId, min) { return (schedule[chId] || []).find(p => min >= p.start && min < p.end) || null; }
  function nowNext(chId) {
    const list = schedule[chId] || [];
    const now = progAt(chId, NOW);
    const idx = now ? list.indexOf(now) : list.findIndex(p => p.start > NOW);
    return { now, next: list[idx + 1] || null, list, idx };
  }
  function pct(p) { return p ? Math.max(0, Math.min(100, (NOW - p.start) / (p.end - p.start) * 100)) : 0; }

  window.LiveTV = {
    NOW, WIN_START, WIN_END, SLOT, CATS,
    channels, schedule,
    fmt, progAt, nowNext, pct,
    // "On now" for the Home row — the current program on every channel
    onNow: () => channels.map(ch => ({ ch, ...nowNext(ch.id) })).filter(x => x.now),
    slots: () => { const out = []; for (let t = WIN_START; t < WIN_END; t += SLOT) out.push(t); return out; },
  };
})();
