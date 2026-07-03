/* Ravilo demo content — Nordic / Faroese-leaning to match the Jellystructure demo.
   In production all of this comes from the Jellystructure API (per-Jellyfin-user
   config + Jellyfin media). Here it's static. window.RAVILO = {...} */
(function () {
  // deterministic colour from a string → a 2-stop gradient + initials
  function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return Math.abs(h); }
  function grad(s) {
    const h = hash(s); const a = h % 360; const b = (a + 36 + (h >> 3) % 50) % 360;
    const l1 = 30 + (h >> 5) % 12, l2 = 12 + (h >> 7) % 8;
    return `linear-gradient(145deg, hsl(${a} 48% ${l1}%), hsl(${b} 52% ${l2}%))`;
  }
  function initials(t) { return t.replace(/[^A-Za-zÀ-ÿ0-9 ]/g, '').split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase(); }

  // studios / categories (the Disney+-style rail; configured in Jellystructure)
  const studios = [
    { id: 'disney',   name: 'Disney+',   wm: 'Disney+',   bg: 'linear-gradient(135deg,#1648d0,#091646)' },
    { id: 'dansktv',  name: 'Dansk TV',  wm: 'Dansk<small>TV</small>', logo: 'assets/brand/dansk-tv-logo.svg', bg: 'linear-gradient(135deg,#c8102e,#1a1a1a)' },
    { id: 'universal',name: 'Universal', wm: 'Universal', bg: 'linear-gradient(135deg,#0b3d91,#06203f)' },
    { id: 'foroyskt', name: 'Varpið',    wm: 'Varpið',    logo: 'assets/brand/varpid-logo.svg', bg: 'linear-gradient(135deg,#0e4f99,#06203f)' },
    { id: 'hbo',      name: 'HBO',       wm: 'HBO',       bg: 'linear-gradient(135deg,#5b2db8,#1a0f3d)',
      hero: [
        { ...T('Iron Veil', 2021, 'Action · Sci-Fi', '16', 'film'), tagline: 'HBO Feature', badge: '4K',
          syn: 'A decommissioned war machine hides in a border town, until the soldiers who built it come looking.' },
        { ...T('Midnight Sun Patrol', 2022, 'Action · Crime', '16', 'series'), tagline: 'HBO Original', badge: 'New Season',
          syn: 'Above the Arctic Circle, a small-town patrol works cases the daylight never lets them forget.' },
        { ...T('Phantom Circuit', 2023, 'Thriller · Mystery', '16', 'film'), tagline: 'Featured', badge: 'Top 10',
          syn: 'A hardware hacker traces a ghost signal through the city grid and finds someone is tracing her back.' },
      ] },
    { id: 'paramount',name: 'Paramount+',wm: 'Paramount+',bg: 'linear-gradient(135deg,#0064ff,#00204d)' },
  ];

  // helper to build a title
  function T(title, year, genre, rating, kind) {
    return { title, year, genre, rating: rating || '12', kind: kind || 'film', grad: grad(title), initials: initials(title) };
  }

  // hero — featured items (1–10; configurable; here 5). Standard demo: Blender open movies, led by Big Buck Bunny.
  const BUNNY = 'linear-gradient(160deg, #8fd4f5 0%, #9ad169 60%, #4e8f2f 100%)';
  const BBB_IMG = 'assets/big-buck-bunny-poster.jpg';
  const hero = [
    { ...T('Big Buck Bunny', 2008, 'Animation · Comedy', 'G', 'film'), grad: BUNNY, backdrop: 'assets/bbb-backdrop-landscape.png', logo: 'assets/bbb-logo.png', tagline: 'Blender Open Movie',
      syn: 'A big, good-natured rabbit takes gentle revenge on three bullying rodents after a sunny morning in the meadow turns cruel.', badge: '4K' },
    { ...T('Nordvest', 2023, 'Crime · Drama', '16', 'series'), tagline: 'Kringvarp Original',
      syn: 'In a fog-bound Faroese fishing town, a detective returns home to a death that reopens a buried family secret.', badge: 'New Season' },
    { ...T('Cosmos Laundromat', 2015, 'Sci-Fi · Adventure', '12', 'film'), tagline: 'Featured Film',
      syn: 'On a desolate island, a suicidal sheep named Franck meets a salesman who offers him the gift — and curse — of a lifetime.', badge: '4K' },
    { ...T('Havets Hjarta', 2022, 'Drama', '12', 'series'), tagline: 'Dansk TV',
      syn: 'Three generations of a trawler family weather the slow collapse of the herring that built their harbour town.', badge: 'Top 10' },
    { ...T('Hraðar Ljós', 2024, 'Thriller', '16', 'film'), tagline: 'Ravilo Premiere',
      syn: 'A night-shift paramedic in Tórshavn races a ticking clock when a routine call turns into something far darker.', badge: 'Premiere' },
    { ...T('Spring', 2019, 'Animation · Family', '7', 'film'), tagline: 'Featured Film',
      syn: 'A young shepherd girl and her dog face ancient spirits to keep the seasons turning — a wordless animated wonder.', badge: '4K' },
  ];

  // pools per genre
  const pool = {
    nordic: ['Nordvest','Havets Hjarta','Frostbarn','Tórshavn 1918','Den Sidste Vinter','Mýrin','Kalkverket','Brúgvin','Saltvatn','Nátt yvir Fjørðin'],
    drama: ['Arvur','Stilla Vatn','Det Tavse Hus','Glasberget','Mod Strømmen','Fars Hænder','Vesterhavet','Lyset i Nord','Hjemkomst','Bølgebryder'],
    action: ['Jarnvegur','Siste Utvei','Kaperen','Nordlys Protocol','Brennur','Fald','Vargtid','Stormkast','Isbjørn','Granat'],
    scifi: ['Cosmos Laundromat','Hraðar Ljós','Tears of Steel','Sintel','Banens Ende','Drift 7','Polstjernen','Aurora Station','Det Niende Lag','Ekko'],
    comedy: ['Klovn i Nord','Sommerhus & Sild','Naboer','Fars Ferie','Den Gode Nabo','Tøris','Kaffepause','Bryllupsballaden','Hytteliv','Strandvask'],
    docs: ['Havets Folk','Ísland frá Lofti','Vulkanens Børn','Stillehavets Dyb','Gletsjeren','Fugleøen','Det Vilde Norden','Tang & Tang','Nordens Ulve','Lyset Vender'],
    family: ['Spring','Caminandes','Agent 327','Glas Halvt','Bukken & Bjørnen','Vintereventyr','Den Lille Havfrue','Skovens Konge','Trolde','Snemand'],
  };
  function row(title, genreKey, kind, cfg) {
    return { title, cfg, items: pool[genreKey].map((t, i) => T(t, 2014 + (i % 11), genreKey, ['7','12','16'][i % 3], kind)) };
  }

  // continue watching (merged Continue + Next Up)
  const continueItems = [
    { ...T('Big Buck Bunny', 2008, 'Animation', 'G', 'film'), grad: BUNNY, image: BBB_IMG, ep: '4 min left', pct: 68 },
    { ...T('Nordvest', 2023, 'Crime', '16', 'series'), ep: 'S1:E4 · 22 min left', pct: 62 },
    { ...T('Havets Hjarta', 2022, 'Drama', '12', 'series'), ep: 'S2:E1 · Next up', pct: 0, next: true },
    { ...T('Cosmos Laundromat', 2015, 'Sci-Fi', '12', 'film'), ep: '12 min left', pct: 78 },
    { ...T('Klovn i Nord', 2020, 'Comedy', '12', 'series'), ep: 'S3:E7 · 18 min left', pct: 41 },
    { ...T('Havets Folk', 2021, 'Documentary', '7', 'film'), ep: '34 min left', pct: 25 },
    { ...T('Arvur', 2023, 'Drama', '16', 'series'), ep: 'S1:E2 · Next up', pct: 0, next: true },
    { ...T('Jarnvegur', 2022, 'Action', '16', 'film'), ep: '47 min left', pct: 12 },
  ];

  // the full home row set (same set is reused inside each category, scoped)
  const rows = [
    { id: 'continue', title: 'Continue Watching', kind: 'continue', cfg: 'Continue + Next Up', items: continueItems },
    { id: 'new-movies', title: 'Newly Added Movies', kind: 'poster', items: [ { ...T('Big Buck Bunny', 2008, 'Animation', 'G', 'film'), grad: BUNNY, image: BBB_IMG, badge: '4K' }, ...row('', 'drama', 'film').items ] },
    { id: 'new-series', title: 'Newly Added Series', kind: 'poster', items: row('', 'nordic', 'series').items },
    { id: 'g-nordic', title: 'Nordic Noir', kind: 'poster', items: row('', 'nordic', 'series').items.slice().reverse() },
    { id: 'g-drama', title: 'Drama', kind: 'poster', items: row('', 'drama', 'film').items.slice().reverse() },
    { id: 'g-action', title: 'Action & Adventure', kind: 'poster', items: row('', 'action', 'film').items },
    { id: 'g-scifi', title: 'Sci-Fi & Fantasy', kind: 'poster', items: row('', 'scifi', 'film').items },
    { id: 'g-comedy', title: 'Comedy', kind: 'poster', items: row('', 'comedy', 'series').items },
    { id: 'g-docs', title: 'Documentary', kind: 'poster', items: row('', 'docs', 'film').items },
    { id: 'g-family', title: 'Family', kind: 'poster', items: row('', 'family', 'film').items },
  ];

  // the "merged newly added" alternative (config toggle in Jellystructure)
  const mergedNew = { id: 'new-all', title: 'Newly Added', kind: 'poster',
    items: row('', 'drama', 'film').items.filter((_, i) => i % 2).concat(row('', 'nordic', 'series').items.filter((_, i) => i % 2)) };

  // ---------- detail-page data (episodes / cast / related) ----------
  function ep(n, title, dur, desc, pct, air) { return { n, title, dur, desc, pct: pct || 0, air: air || '', grad: grad(title + n) }; }
  const SERIES_EP = {
    'Nordvest': [
      [ // Season 1
        ep(1, 'Hvalvík', '58m', 'Detective Sigrun Restorff steps off the ferry into the town that raised her — and a body that won\u2019t let her leave.', 100, '2023-09-03'),
        ep(2, 'Bóndin', '54m', 'A farmer\u2019s confession unravels faster than the rope that bound him.', 100, '2023-09-10'),
        ep(3, 'Grindadráp', '61m', 'The grind paints the bay red; beneath the tide, an older debt surfaces.', 100, '2023-09-17'),
        ep(4, 'Útróður', '57m', 'A prosecutor from Copenhagen arrives, and the case shifts language and loyalty.', 100, '2023-09-24'),
        ep(5, 'Foss', '55m', 'Sigrun follows the money upriver to the salmon farm the whole town depends on.', 100, '2023-10-01'),
        ep(6, 'Náttúra', '59m', 'A storm seals the island. The suspect list narrows to the people she loves.', 100, '2023-10-08'),
        ep(7, 'Heim', '56m', 'The secret her father carried to sea washes back to the harbour wall.', 100, '2023-10-15'),
        ep(8, 'Endi', '63m', 'Two truths, one confession, and a tide that takes everything back.', 100, '2023-10-22'),
      ],
      [ // Season 2
        ep(1, 'Nýtt Ár', '60m', 'A new year, a frozen harbour, and a face Sigrun buried long ago.', 100, '2024-11-10'),
        ep(2, 'Toka', '52m', 'Fog swallows the road north; a routine call goes silent.', 100, '2024-11-17'),
        ep(3, 'Djúpið', '58m', 'Divers find more than the wreck they were paid to forget.', 45, '2024-11-24'),
        ep(4, 'Skuld', '55m', 'An old debt comes due in the only currency the coast respects.', 0, '2024-12-01'),
        ep(5, 'Brot', '57m', 'Everything cracks at once; Sigrun chooses which piece to save.', 0, '2024-12-08'),
        ep(6, 'Lokið', '64m', 'The coast keeps its dead, but not its secrets. Season finale.', 0, '2024-12-15'),
      ],
    ],
  };
  const CAST = {
    'Nordvest': [
      { n: 'Sigrun Restorff', r: 'Detective' }, { n: 'Páll Heinason', r: 'Sergeant' },
      { n: 'Marin Klett', r: 'Prosecutor' }, { n: 'Tóki á Bø', r: 'Harbourmaster' },
      { n: 'Eva Restorff', r: 'Sister' }, { n: 'S. Goedegebure', r: 'Creator' },
    ],
    'Big Buck Bunny': [
      { n: 'Big Buck', r: 'The Bunny' }, { n: 'Frank', r: 'Squirrel' }, { n: 'Rinky', r: 'Squirrel' },
      { n: 'Gimera', r: 'Flying Squirrel' }, { n: 'Sacha Goedegebure', r: 'Director' }, { n: 'Blender Foundation', r: 'Studio' },
    ],
    _default: [
      { n: 'Lead Actor', r: 'Protagonist' }, { n: 'Supporting', r: 'Co-star' }, { n: 'Guest', r: 'Recurring' },
      { n: 'Director', r: 'Director' }, { n: 'Composer', r: 'Score' },
    ],
  };
  function genEps(key, count) {
    const names = pool.nordic.concat(pool.drama);
    const out = [];
    for (let i = 0; i < count; i++) {
      const t = names[(i * 3 + key.length) % names.length];
      const ad = new Date(Date.UTC(2024, 0, 7 + i * 7));
      out.push(ep(i + 1, t, (48 + (i * 7) % 20) + 'm', 'A new chapter, pulled live from Jellyfin and organised by Jellystructure.', i === 0 ? 28 : 0, ad.toISOString().slice(0, 10)));
    }
    return out;
  }
  function episodesFor(item, s) { const a = SERIES_EP[item.title]; if (a) return a[Math.min(s, a.length - 1)]; return genEps(item.title, 8); }
  function seasonsFor(item) { const a = SERIES_EP[item.title]; return a ? a.length : 2; }
  function castFor(item) { return CAST[item.title] || CAST._default; }
  function relatedFor(item) {
    const key = item.genre && /noir|crime|drama/i.test(item.genre) ? 'drama' : 'scifi';
    return pool[key].slice(0, 9).map((t, i) => T(t, 2014 + i, item.kind === 'series' ? 'Series' : 'Drama', ['7', '12', '16'][i % 3], item.kind));
  }

  // Ravilo users on this TV — each is a Jellyfin user with a cached device token (per the
  // pairing model). The client keeps several so switching is instant. "isAdmin" mirrors Jellyfin.
  const profiles = [
    { id: 'eyd',    name: 'Eyð',    initials: 'ER', color: 'linear-gradient(145deg,#7b6ef0,#3fb6f5)', signedIn: true,  isAdmin: true,  kid: false, lang: 'fo', discover: { enabled: true, lists: ['mov-dk', 'tv-dk', 'mov-global', 'noneng', 'alltime'] } },
    { id: 'olivar', name: 'Olivar', initials: 'OL', color: 'linear-gradient(145deg,#19d6c6,#2a8cf0)', signedIn: true,  isAdmin: false, kid: false, lang: 'en', discover: { enabled: true, lists: ['mov-global', 'noneng', 'alltime'] } },
    { id: 'marjun', name: 'Marjun', initials: 'MJ', color: 'linear-gradient(145deg,#f5b542,#e0792f)', signedIn: true,  isAdmin: false, kid: false, lang: 'da', discover: { enabled: false, lists: [] } },
    { id: 'kids',   name: 'Kids',   initials: '★',  color: 'linear-gradient(145deg,#e0639a,#b15cd0)', signedIn: true,  isAdmin: false, kid: true,  lang: 'fo', discover: { enabled: false, lists: [] } },
  ];

  // ---------- Discover / Top 10 (Phase 54 + Ravilo) ----------
  // Pulled from a third-party chart vendor (Netflix via Tudum today; others later) for the
  // user's configured country. Gated by: Radarr enabled in Jellystructure (config.radarr) AND
  // a per-user boolean (profile.discover). Country feeds are rank-only (no hours/views); global
  // & all-time feeds carry real viewership. Each entry's status drives the fetch affordance:
  //   available  → already in the Jellyfin library (Watch Now)
  //   fetching   → Radarr is grabbing it now (progress %)
  //   none       → not in library — the user can request a fetch via Radarr
  const config = { radarr: true, sonarr: true, region: 'DK', regionName: 'Denmark',
    // Age-rating region cascade (global; set in Jellystructure → Settings → Metadata).
    // Jellystructure resolves each title's certification by walking this ordered list and
    // using the first region that has one. Here it drives the badge + the workbench facet.
    ageRating: { cascade: ['DK', 'US', 'GB'] } };

  // Certification systems per region (TMDB “release_dates” certifications), scaled by tier 0–4.
  const CERT_SYS = {
    DK: { name: 'Denmark',        system: 'Medierådet',        scale: ['A', '7', '11', '15', '15'] },
    US: { name: 'United States',  system: 'MPA',              scale: ['G', 'PG', 'PG-13', 'R', 'NC-17'] },
    GB: { name: 'United Kingdom', system: 'BBFC',             scale: ['U', 'PG', '12', '15', '18'] },
    DE: { name: 'Germany',        system: 'FSK',              scale: ['0', '6', '12', '16', '18'] },
    SE: { name: 'Sweden',         system: 'Statens medieråd', scale: ['Btl', '7', '11', '15', '15'] },
    NO: { name: 'Norway',         system: 'Medietilsynet',    scale: ['A', '6', '12', '15', '18'] },
  };
  // map any certification code (or the item's base rating) to a 0–4 maturity tier for colour + scaling
  function certTier(r) {
    r = (r == null ? '' : String(r)).toUpperCase().trim();
    if (r === 'G' || r === 'A' || r === 'U' || r === '0' || r === 'BTL' || r === 'TLL') return 0;
    if (r === '7' || r === '6' || r === '9' || r === 'PG') return 1;
    if (r === '11' || r === '12' || r === '12A' || r === 'PG-13') return 2;
    if (r === '15' || r === '16' || r === 'R') return 3;
    if (r === '18' || r === 'NC-17') return 4;
    const n = parseInt(r, 10);
    if (!isNaN(n)) return n <= 6 ? 1 : n <= 12 ? 2 : n <= 16 ? 3 : 4;
    return 2;
  }
  // deterministic per-title certification map; some regions are intentionally absent so the
  // cascade fallback (e.g. Denmark missing → United States) is visible in the demo.
  function itemCerts(item) {
    if (item._certs) return item._certs;
    const tier = certTier(item.rating), h = hash(item.title), out = {};
    Object.keys(CERT_SYS).forEach((rg, i) => {
      if (((h >> (i * 3)) % 5) === 0) return;               // ~20% of regions absent per title
      const sc = CERT_SYS[rg].scale; out[rg] = sc[Math.min(tier, sc.length - 1)];
    });
    if (!Object.keys(out).length) out.US = CERT_SYS.US.scale[Math.min(tier, 4)];
    return (item._certs = out);
  }
  // resolve the rating to show for an item: first region in the cascade that has a cert, else any.
  function ratingFor(item) {
    if (!item) return null;
    const certs = itemCerts(item);
    const cascade = (config.ageRating && config.ageRating.cascade) || ['US'];
    for (const rg of cascade) {
      if (certs[rg] != null) { const s = CERT_SYS[rg]; return { region: rg, regionName: s.name, system: s.system, code: certs[rg], tier: certTier(certs[rg]) }; }
    }
    const any = Object.keys(certs)[0];
    if (!any) return null;
    const s = CERT_SYS[any];
    return { region: any, regionName: s.name, system: s.system, code: certs[any], tier: certTier(certs[any]), fallback: true };
  }

  const sources = [
    { id: 'netflix', name: 'Netflix', via: 'Tudum', wm: 'N', accent: '#e50914', enabled: true },
    { id: 'disney',  name: 'Disney+', via: 'soon',  wm: 'D+',  accent: '#1f7cf2', enabled: false },
    { id: 'max',     name: 'Max',     via: 'soon',  wm: 'MAX', accent: '#8a44e6', enabled: false },
  ];

  function D(title, year, genre, rating, kind, o) {
    o = o || {};
    let status = o.status || 'not_requested';
    if (status === 'none') status = 'not_requested';
    if (status === 'fetching') status = 'downloading';
    return Object.assign(T(title, year, genre, rating, kind), {
      status, progress: o.progress || 0,
      queuePos: o.queuePos || null, stalled: !!o.stalled, metadata: !!o.metadata,
      epsDone: o.epsDone != null ? o.epsDone : null, epsTotal: o.epsTotal != null ? o.epsTotal : null,
      firstAvailable: !!o.firstAvailable,
      weeks: o.weeks != null ? o.weeks : 1, trend: o.trend || 'same',
      views: o.views || null, syn: o.syn || '', source: o.source || 'netflix',
    });
  }
  // attach rank by position
  function ranked(items) { items.forEach((it, i) => it.rank = i + 1); return items; }

  const discoverLists = [
    { id: 'mov-dk', title: 'Top 10 Movies in Denmark', scope: 'country', category: 'film', metric: 'rank',
      note: 'Ranking only — the country feed has no view counts', items: ranked([
        D('Carry-On', 2024, 'Thriller', '16', 'film', { status: 'fetching', progress: 47, weeks: 2, trend: 'up', syn: 'A young TSA officer is blackmailed by a mysterious traveller into letting a dangerous package slip onto a Christmas Eve flight.' }),
        D('Hraðar Ljós', 2024, 'Thriller', '16', 'film', { status: 'available', weeks: 4, trend: 'same', syn: 'A night-shift paramedic in Tórshavn races a ticking clock when a routine call turns into something far darker.' }),
        D('Saltvatn', 2023, 'Drama', '12', 'film', { status: 'requested', weeks: 1, trend: 'new', syn: 'A widowed lighthouse keeper takes in a stranded sailor as winter storms close the only road home.' }),
        D('Vargtid', 2022, 'Action', '16', 'film', { status: 'queued', queuePos: 3, weeks: 3, trend: 'down', syn: 'A disgraced ranger hunts the wolf pack blamed for a boy’s disappearance — and the men who set them loose.' }),
        D('Cosmos Laundromat', 2015, 'Sci-Fi', '12', 'film', { status: 'available', weeks: 6, trend: 'same' }),
        D('Nordlys Protocol', 2023, 'Action', '16', 'film', { status: 'failed', weeks: 2, trend: 'up' }),
        D('Den Sidste Vinter', 2021, 'Drama', '12', 'film', { status: 'downloading', progress: 12, metadata: true, weeks: 1, trend: 'new' }),
        D('Granat', 2020, 'Action', '16', 'film', { status: 'none', weeks: 5, trend: 'down' }),
        D('Drift 7', 2022, 'Sci-Fi', '12', 'film', { status: 'importing', weeks: 2, trend: 'same' }),
        D('Stormkast', 2019, 'Action', '12', 'film', { status: 'none', weeks: 1, trend: 'new' }),
      ]) },
    { id: 'tv-dk', title: 'Top 10 TV Shows in Denmark', scope: 'country', category: 'series', metric: 'rank',
      note: 'Ranking only — the country feed has no view counts', items: ranked([
        D('Nordvest', 2023, 'Crime', '16', 'series', { status: 'available', weeks: 7, trend: 'same', syn: 'In a fog-bound Faroese fishing town, a detective returns home to a death that reopens a buried family secret.' }),
        D('Arvur', 2023, 'Drama', '16', 'series', { status: 'none', weeks: 2, trend: 'up', syn: 'When the family patriarch dies, three siblings discover the inheritance is a debt none of them can pay.' }),
        D('Havets Hjarta', 2022, 'Drama', '12', 'series', { status: 'available', weeks: 3, trend: 'down' }),
        D('Glasberget', 2024, 'Drama', '16', 'series', { status: 'none', weeks: 1, trend: 'new' }),
        D('Mýrin', 2021, 'Crime', '16', 'series', { status: 'downloading', epsDone: 3, epsTotal: 10, firstAvailable: true, weeks: 4, trend: 'same' }),
        D('Brúgvin', 2022, 'Crime', '16', 'series', { status: 'downloading', epsDone: 0, epsTotal: 8, stalled: true, weeks: 2, trend: 'up' }),
        D('Det Tavse Hus', 2023, 'Drama', '12', 'series', { status: 'none', weeks: 5, trend: 'down' }),
        D('Kalkverket', 2020, 'Crime', '16', 'series', { status: 'none', weeks: 1, trend: 'new' }),
        D('Tórshavn 1918', 2021, 'Drama', '12', 'series', { status: 'none', weeks: 3, trend: 'same' }),
        D('Frostbarn', 2024, 'Crime', '16', 'series', { status: 'none', weeks: 1, trend: 'new' }),
      ]) },
    { id: 'mov-global', title: 'Global Top 10 Movies', scope: 'global', category: 'film', metric: 'views',
      note: 'Global feed — real hours viewed this week', items: ranked([
        D('Red Notice', 2021, 'Action · Comedy', '12', 'film', { status: 'none', weeks: 2, trend: 'up', views: '47.1M', syn: 'An Interpol agent and the world’s most-wanted art thief are forced into an uneasy alliance to catch an even greater rival.' }),
        D('KPop Demon Hunters', 2025, 'Animation', '7', 'film', { status: 'fetching', progress: 28, weeks: 1, trend: 'new', views: '41.7M', syn: 'A chart-topping K-pop trio moonlights as a demon-slaying squad protecting their fans from the underworld.' }),
        D('Carry-On', 2024, 'Thriller', '16', 'film', { status: 'fetching', progress: 47, weeks: 2, trend: 'same', views: '33.0M' }),
        D('The Gray Man', 2022, 'Action', '16', 'film', { status: 'none', weeks: 3, trend: 'down', views: '28.5M' }),
        D('Damsel', 2024, 'Fantasy', '12', 'film', { status: 'none', weeks: 2, trend: 'same', views: '24.2M' }),
        D('Leave the World Behind', 2023, 'Thriller', '16', 'film', { status: 'none', weeks: 1, trend: 'new', views: '21.9M' }),
        D('The Adam Project', 2022, 'Sci-Fi', '12', 'film', { status: 'available', weeks: 4, trend: 'down', views: '19.4M' }),
        D('Don’t Look Up', 2021, 'Comedy', '16', 'film', { status: 'none', weeks: 2, trend: 'same', views: '17.6M' }),
        D('Glass Onion', 2022, 'Mystery', '12', 'film', { status: 'none', weeks: 3, trend: 'up', views: '15.1M' }),
        D('Bird Box', 2018, 'Thriller', '16', 'film', { status: 'none', weeks: 1, trend: 'new', views: '13.8M' }),
      ]) },
    { id: 'noneng', title: 'Top 10 Non-English Films', scope: 'global', category: 'film', metric: 'views',
      note: 'Global feed — surfaces foreign-language hits', items: ranked([
        D('Troll', 2022, 'Action · Fantasy', '12', 'film', { status: 'none', weeks: 2, trend: 'up', views: '23.0M', syn: 'Deep in a Norwegian mountain, an ancient creature awakens and marches on Oslo — and only a rogue palaeontologist believes the legends.' }),
        D('Society of the Snow', 2023, 'Drama', '16', 'film', { status: 'none', weeks: 1, trend: 'new', views: '20.4M', syn: 'The survivors of a 1972 Andes plane crash endure 72 days in the high cordillera, bound by an impossible pact to stay alive.' }),
        D('Lost Bullet', 2020, 'Action', '16', 'film', { status: 'none', weeks: 3, trend: 'same', views: '14.7M' }),
        D('Athena', 2022, 'Drama', '16', 'film', { status: 'none', weeks: 2, trend: 'down', views: '12.3M' }),
        D('The Platform', 2019, 'Sci-Fi · Horror', '18', 'film', { status: 'available', weeks: 4, trend: 'same', views: '11.0M' }),
        D('Wild is the Wind', 2022, 'Crime', '16', 'film', { status: 'none', weeks: 1, trend: 'new', views: '9.6M' }),
        D('Blood Red Sky', 2021, 'Horror', '18', 'film', { status: 'none', weeks: 2, trend: 'up', views: '8.9M' }),
        D('Through My Window', 2022, 'Romance', '16', 'film', { status: 'none', weeks: 3, trend: 'down', views: '7.4M' }),
        D('A Classic Horror Story', 2021, 'Horror', '18', 'film', { status: 'none', weeks: 1, trend: 'new', views: '6.2M' }),
        D('Below Zero', 2021, 'Thriller', '16', 'film', { status: 'none', weeks: 2, trend: 'same', views: '5.5M' }),
      ]) },
    { id: 'alltime', title: 'Most Popular of All Time', scope: 'alltime', category: 'film', metric: 'views91',
      note: 'Ranked by views in the first 91 days', items: ranked([
        D('Red Notice', 2021, 'Action · Comedy', '12', 'film', { status: 'none', weeks: 91, trend: 'same', views: '230.9M', syn: 'An Interpol agent and the world’s most-wanted art thief are forced into an uneasy alliance to catch an even greater rival.' }),
        D('Carry-On', 2024, 'Thriller', '16', 'film', { status: 'fetching', progress: 47, weeks: 64, trend: 'same', views: '172.0M' }),
        D('Don’t Look Up', 2021, 'Comedy', '16', 'film', { status: 'none', weeks: 91, trend: 'same', views: '171.4M' }),
        D('Bird Box', 2018, 'Thriller', '16', 'film', { status: 'none', weeks: 91, trend: 'same', views: '157.4M' }),
        D('Glass Onion', 2022, 'Mystery', '12', 'film', { status: 'none', weeks: 91, trend: 'same', views: '152.0M' }),
        D('The Gray Man', 2022, 'Action', '16', 'film', { status: 'none', weeks: 91, trend: 'same', views: '139.3M' }),
        D('The Adam Project', 2022, 'Sci-Fi', '12', 'film', { status: 'available', weeks: 91, trend: 'same', views: '128.2M' }),
        D('Leave the World Behind', 2023, 'Thriller', '16', 'film', { status: 'none', weeks: 91, trend: 'same', views: '121.4M' }),
        D('Society of the Snow', 2023, 'Drama', '16', 'film', { status: 'none', weeks: 91, trend: 'same', views: '98.5M' }),
        D('Damsel', 2024, 'Fantasy', '12', 'film', { status: 'none', weeks: 91, trend: 'same', views: '94.1M' }),
      ]) },
  ];
  const discover = { config, sources, lists: discoverLists };

  // ---------- watched-state store (R07) ----------
  // In production this is the per-Jellyfin-user PlaybackState merged onto metadata
  // (GET /api/tv/movie|series/…). Here it's a deterministic seed the client reads; marking
  // watched/unwatched in the UI writes through to Jellyfin via jellystructure (R08).
  const _ws = {};                                         // key -> { pct, watched }
  const _ik = tt => 'i:' + tt;                            // item (movie / series level)
  const _ek = (tt, s, n) => 'e:' + tt + '|' + s + '|' + n; // episode
  function itemState(title) { const r = _ws[_ik(title)]; return r ? { pct: r.pct || 0, watched: !!r.watched } : { pct: 0, watched: false }; }
  function setItem(title, patch) { const k = _ik(title); _ws[k] = Object.assign({ pct: 0, watched: false }, _ws[k], patch); return _ws[k]; }
  function setItemWatched(title, on) { return setItem(title, { watched: !!on, pct: on ? 100 : 0 }); }
  function epState(title, s, n, seedPct) { const k = _ek(title, s, n); if (!(k in _ws)) _ws[k] = { pct: seedPct || 0, watched: (seedPct || 0) >= 100 }; const r = _ws[k]; return { pct: r.pct || 0, watched: !!r.watched }; }
  function setEpWatched(title, s, n, on) { return (_ws[_ek(title, s, n)] = { pct: on ? 100 : 0, watched: !!on }); }
  function setEpPct(title, s, n, pct) { return (_ws[_ek(title, s, n)] = { pct: pct, watched: pct >= 100 }); }

  // deterministic initial spread so browse rows / grids show a realistic mix of
  // ✓ watched · ◐ in-progress · unwatched with no interaction needed
  (function seedWatched() {
    const seen = {};
    const apply = it => {
      if (!it || !it.title || seen[it.title]) return; seen[it.title] = 1;
      const h = hash(it.title), m = h % 100;
      if (m < 24) setItem(it.title, { watched: true, pct: 100 });
      else if (m < 38) setItem(it.title, { watched: false, pct: 10 + (h >> 4) % 80 });
    };
    hero.forEach(apply);
    rows.forEach(r => (r.items || []).forEach(apply));
    // continue-watching titles are, by definition, in progress — reflect their exact pct
    continueItems.forEach(it => { if (it.pct >= 100) setItem(it.title, { watched: true, pct: 100 }); else if (it.pct > 0) setItem(it.title, { watched: false, pct: it.pct }); });
    // hand-authored series: seed episode state from the metadata pct so detail + tiles agree
    Object.keys(SERIES_EP).forEach(title => SERIES_EP[title].forEach((eps, s) => eps.forEach(e => { _ws[_ek(title, s, e.n)] = { pct: e.pct || 0, watched: (e.pct || 0) >= 100 }; })));
  })();

  // ---------- Sonarr: upcoming-episode info for ongoing (not-ended) series ----------
  // Sonarr knows each series' status (continuing vs ended) and the air date of the next
  // monitored episode. Surfaced on the series detail page ONLY when the show has not ended.
  // Dates are computed relative to “now” so the mock always reads as genuinely upcoming.
  const SERIES_STATUS = {
    'Nordvest':       { ended: false, season: 3, ep: 1, title: 'Heimferð' },
    'Havets Hjarta':  { ended: false, season: 3, ep: 6, title: 'Brotsjór' },
    'Arvur':          { ended: false, season: 2, ep: 1, title: 'Nýggj Spor' },
    'Klovn i Nord':   { ended: true },
    'Havets Folk':    { ended: true },
  };
  function nextAiringFor(item) {
    if (!config.sonarr || !item || item.kind !== 'series') return null;
    const s = SERIES_STATUS[item.title];
    if (!s || s.ended) return null;
    const days = (hash(item.title) % 18) + 3;           // 3–20 days out, deterministic per title
    const when = new Date(Date.now() + days * 864e5);
    return { season: s.season, ep: s.ep, title: s.title || '', date: when.toISOString().slice(0, 10), days };
  }

  // ---------- Upcoming calendar (Sonarr episodes + Radarr movie releases) ----------
  // A merged air/release schedule. Sonarr supplies the next monitored episodes of
  // continuing series (with air time + network); Radarr supplies movie release dates
  // (digital / physical / in-cinemas) it is monitoring. Gated on config.sonarr || .radarr.
  // Offsets are days from “now”, so the calendar always reads as genuinely upcoming.
  function localYMD(d) {
    const m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + m + '-' + day;
  }
  function upDate(offset) { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + offset); return d; }
  function U(offset, o) {
    const d = upDate(offset);
    return Object.assign(T(o.title, o.year, o.genre, o.rating, o.kind), {
      date: localYMD(d), offset,
      time: o.time || '', ep: o.ep || '', epTitle: o.epTitle || '',
      source: o.source, network: o.network || '', release: o.release || '',
      monitored: o.monitored !== false, status: o.status || 'monitored', progress: o.progress || 0,
      qp: o.qp || (o.kind === 'series' ? 'HD-1080p' : 'Ultra-HD'),
      syn: o.syn || '', grad: grad(o.title + (o.ep || '')),
    });
  }
  const upcoming = [
    // ---- today (offset 0) ----
    U(0, { title: 'Arvur', year: 2023, genre: 'Drama', rating: '16', kind: 'series', ep: 'S2:E1', epTitle: 'Nýggj Spor', time: '20:30', source: 'sonarr', network: 'Viaplay', status: 'downloading', progress: 62,
      syn: 'The estate reopens old wounds as the eldest sibling returns to bury the family’s last secret — and its last debt.' }),
    U(0, { title: 'Hraðar Ljós II', year: 2025, genre: 'Thriller', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'downloading', progress: 38,
      syn: 'The Tórshavn night shift returns. One paramedic, one impossible call, and a city that never quite goes dark.' }),
    // ---- tomorrow (1) ----
    U(1, { title: 'Nordvest', year: 2023, genre: 'Crime', rating: '16', kind: 'series', ep: 'S3:E1', epTitle: 'Heimferð', time: '20:00', source: 'sonarr', network: 'Kringvarp', status: 'monitored',
      syn: 'Detective Sigrun Restorff is called back to Hvalvík a third time — and the tide brings up a name she buried herself.' }),
    // ---- 2 ----
    U(2, { title: 'Havets Hjarta', year: 2022, genre: 'Drama', rating: '12', kind: 'series', ep: 'S3:E6', epTitle: 'Brotsjór', time: '21:00', source: 'sonarr', network: 'Dansk TV', status: 'monitored',
      syn: 'The trawler family faces the storm that the whole harbour has feared since the herring first thinned.' }),
    U(2, { title: 'Carry-On', year: 2024, genre: 'Thriller', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'downloading', progress: 47,
      syn: 'A young TSA officer is blackmailed into letting a dangerous package slip onto a Christmas Eve flight.' }),
    // ---- 3 ----
    U(3, { title: 'Frostbarn', year: 2024, genre: 'Crime', rating: '16', kind: 'series', ep: 'S1:E3', epTitle: 'Ísvøk', time: '22:00', source: 'sonarr', network: 'Kringvarp', status: 'monitored',
      syn: 'A frozen fjord gives up a child’s coat and no child. The town closes ranks; the ice does not.' }),
    U(3, { title: 'Spring: Return', year: 2025, genre: 'Animation · Family', rating: '7', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'announced',
      syn: 'The shepherd girl and her dog climb higher than the seasons have ever reached — a wordless animated wonder returns.' }),
    // ---- 4 ----
    U(4, { title: 'Glasberget', year: 2024, genre: 'Drama', rating: '16', kind: 'series', ep: 'S2:E1', epTitle: 'Sprekk', time: '20:00', source: 'sonarr', network: 'SVT', status: 'monitored',
      syn: 'Everything cracks at once, and the family must choose which piece of the glass mountain to save.' }),
    U(4, { title: 'KPop Demon Hunters', year: 2025, genre: 'Animation', rating: '7', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'announced',
      syn: 'A chart-topping trio moonlights as a demon-slaying squad, protecting their fans from the underworld between shows.' }),
    // ---- 5 ----
    U(5, { title: 'Mýrin', year: 2021, genre: 'Crime', rating: '16', kind: 'series', ep: 'S2:E1', epTitle: 'Aftur í Myrkri', time: '21:30', source: 'sonarr', network: 'Netflix', status: 'available',
      syn: 'The bog keeps its dead well. When it gives one back, the case it reopens is one nobody wanted solved.' }),
    // ---- 6 ----
    U(6, { title: 'Tórshavn 1918', year: 2021, genre: 'Drama', rating: '12', kind: 'series', ep: 'S2:E1', epTitle: 'Spanska Sótt', time: '20:00', source: 'sonarr', network: 'Kringvarp', status: 'monitored',
      syn: 'A century on, the harbour town relives the winter the influenza came ashore with the mail boat.' }),
    U(6, { title: 'Troll 2', year: 2025, genre: 'Action · Fantasy', rating: '12', kind: 'film', source: 'radarr', release: 'In Cinemas', status: 'announced',
      syn: 'The mountain wakes again. This time the legends march south — and the palaeontologist who believed them is out of time.' }),
    // ---- 7 ----
    U(7, { title: 'Arvur', year: 2023, genre: 'Drama', rating: '16', kind: 'series', ep: 'S2:E2', epTitle: 'Skuld', time: '20:30', source: 'sonarr', network: 'Viaplay', status: 'monitored' }),
    U(7, { title: 'Cosmos Laundromat', year: 2015, genre: 'Sci-Fi · Adventure', rating: '12', kind: 'film', source: 'radarr', release: 'Physical Release', status: 'available',
      syn: 'On a desolate island, a suicidal sheep named Franck meets a salesman who offers him the gift — and curse — of a lifetime.' }),
    // ---- 8 ----
    U(8, { title: 'Nordvest', year: 2023, genre: 'Crime', rating: '16', kind: 'series', ep: 'S3:E2', epTitle: 'Toka', time: '20:00', source: 'sonarr', network: 'Kringvarp', status: 'monitored' }),
    // ---- 9 ----
    U(9, { title: 'Havets Hjarta', year: 2022, genre: 'Drama', rating: '12', kind: 'series', ep: 'S3:E7', epTitle: 'Logn', time: '21:00', source: 'sonarr', network: 'Dansk TV', status: 'monitored' }),
    U(9, { title: 'Society of the Snow', year: 2023, genre: 'Drama', rating: '16', kind: 'film', source: 'radarr', release: 'Physical Release', status: 'announced',
      syn: 'The survivors of a 1972 Andes crash endure 72 days in the high cordillera, bound by an impossible pact to stay alive.' }),
    // ---- 10 & 11 have nothing scheduled (gap) ----
    // ---- 12 ----
    U(12, { title: 'Nordlys Protocol', year: 2023, genre: 'Action', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'announced',
      syn: 'When the aurora grid goes dark over Svalbard, the only operative left online has ninety minutes and no orders.' }),
  ];
  // ---- Overdue / missing (released in the past, still not in the library) ----
  // Radarr/Sonarr flagged these as available at the source, but nothing has landed yet.
  // Only surface up to ~6 months back; anything older is written off (the -210 item proves it).
  const overdue = [
    U(-2,  { title: 'The Gray Man', year: 2022, genre: 'Action', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing',
      syn: 'A CIA mercenary uncovers the agency’s dirty secrets and becomes the target of a global manhunt led by a former colleague.' }),
    U(-4,  { title: 'Frostbarn', year: 2024, genre: 'Crime', rating: '16', kind: 'series', ep: 'S1:E1', epTitle: 'Kaldi Fjørður', time: '22:00', source: 'sonarr', network: 'Kringvarp', status: 'missing',
      syn: 'The season opener nobody grabbed — the fjord freezes over, and the first body surfaces beneath the ice.' }),
    U(-8,  { title: 'Damsel', year: 2024, genre: 'Fantasy', rating: '12', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing',
      syn: 'A dutiful maiden discovers her royal marriage is a sacrifice — and the only way out is down, into the dragon’s lair.' }),
    U(-15, { title: 'Blood Red Sky', year: 2021, genre: 'Horror', rating: '18', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing' }),
    U(-26, { title: 'Athena', year: 2022, genre: 'Drama', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing' }),
    U(-44, { title: 'Below Zero', year: 2021, genre: 'Thriller', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing' }),
    U(-210, { title: 'The Old Guard', year: 2020, genre: 'Action', rating: '16', kind: 'film', source: 'radarr', release: 'Digital Release', status: 'missing' }),
  ];
  // group by date, preserving chronological order
  function upcomingByDay() {
    const map = new Map();
    upcoming.forEach(it => { if (!map.has(it.date)) map.set(it.date, []); map.get(it.date).push(it); });
    return [...map.entries()].map(([date, items]) => ({ date, offset: items[0].offset, items }));
  }

  // ---------- TMDB trailers (videos section) ----------
  // Ingested at scan time from TMDB `/videos`: pick one official YouTube/Vimeo "Trailer"
  // (fallback Teaser), preferring the original language then English. Stored per title as
  // { site: 'youtube'|'vimeo', key, name }. Titles with no video simply have no entry — the
  // Ravilo "▷ Trailer" button and the admin trailer card only appear when one exists.
  const TRAILERS = {
    'Big Buck Bunny':    { site: 'youtube', key: 'aqz-KE-bpKQ', name: 'Official Trailer' },
    'Cosmos Laundromat': { site: 'youtube', key: 'Y-rmzh0PI3c', name: 'First Cycle · Trailer' },
    'Spring':            { site: 'youtube', key: 'WhWc3b3KhnY', name: 'Official Trailer' },
    'Nordvest':          { site: 'vimeo',   key: '76979871',    name: 'Teaser' },
    'Havets Hjarta':     { site: 'youtube', key: 'b7Cmt-Ng2S0', name: 'Season 2 Trailer' },
    'Hraðar Ljós':       { site: 'youtube', key: 'aqz-KE-bpKQ', name: 'Premiere Trailer' },
  };
  function trailerFor(item) { return (item && TRAILERS[item.title]) || null; }

  // ---------- IMDb ratings (imdbapi.dev) ----------
  // Aggregate rating + vote count per title. In production this is fetched from
  // https://imdbapi.dev for every title that carries an imdbId, STORED on the item, and
  // refreshed on a periodic sync — never queried ad-hoc at render time. The stored shape
  // mirrors the API response: { aggregateRating, voteCount }. Titles without an imdbId
  // (or not yet synced) have no rating and the detail chip is simply hidden.
  const IMDB = {
    'Big Buck Bunny':    { id: 'tt1254207', rating: 8.1, votes: 24800 },
    'Cosmos Laundromat': { id: 'tt4331594', rating: 7.3, votes: 3900 },
    'Spring':            { id: 'tt5188920', rating: 8.0, votes: 1250 },
    'Nordvest':          { id: 'tt6910212', rating: 7.9, votes: 5400 },
    'Havets Hjarta':     { id: 'tt7180392', rating: 7.2, votes: 2100 },
    'Hraðar Ljós':       { id: 'tt9910233', rating: 4.5, votes: 667 },
  };
  function imdbFor(item) {
    if (!item || !item.title) return null;
    if (IMDB[item.title]) return IMDB[item.title];
    const h = hash(item.title);
    if (h % 10 < 3) return null;                         // ~30% carry no imdbId / rating
    const rating = (55 + (h >> 3) % 40) / 10;            // 5.5 – 9.4
    const votes = 240 + (h % 1200) * 96;                 // ~240 – ~115k
    return { id: 'tt' + (1000000 + h % 8999999), rating, votes };
  }

  const watched = { itemState, setItem, setItemWatched, epState, setEpWatched, setEpPct };

  window.RAVILO = { studios, hero, rows, mergedNew, profiles, discover, upcoming, upcomingByDay, overdue, grad, initials, episodesFor, seasonsFor, castFor, relatedFor, nextAiringFor, trailerFor, imdbFor, ratingFor, itemCerts, CERT_SYS, config, watched };
})();
