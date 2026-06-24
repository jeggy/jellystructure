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
    { id: 'hbo',      name: 'HBO',       wm: 'HBO',        bg: 'linear-gradient(135deg,#3b2a78,#15102e)',
      heroHeight: 48,
      hero: [
        { ...T('Iron Veil', 2021, 'Action · Sci-Fi', '16', 'film'), tagline: 'HBO Feature', badge: '4K',
          syn: 'A decommissioned war machine hides in a border town, until the soldiers who built it come looking.' },
        { ...T('Midnight Sun Patrol', 2022, 'Action · Crime', '16', 'series'), tagline: 'HBO Original', badge: 'New Season',
          syn: 'Above the Arctic Circle, a small-town patrol works cases the daylight never lets them forget.' },
        { ...T('Phantom Circuit', 2023, 'Thriller · Mystery', '16', 'film'), tagline: 'Featured', badge: 'Top 10',
          syn: 'A hardware hacker traces a ghost signal through the city grid and finds someone is tracing her back.' },
      ] },
    { id: 'tv2',      name: 'TV 2',      wm: 'TV<small>2</small>', bg: 'linear-gradient(135deg,#e3122b,#7d0a1a)' },
    { id: 'kringvarp',name: 'Kringvarp', wm: 'KvF',        bg: 'linear-gradient(135deg,#0a93a6,#063d47)' },
    { id: 'dr',       name: 'DR',        wm: 'DR',         bg: 'linear-gradient(135deg,#1455d8,#0a2766)' },
    { id: 'viaplay',  name: 'Viaplay',   wm: 'viaplay',    bg: 'linear-gradient(135deg,#ff2e5b,#8a0f2c)' },
    { id: 'dansktv',  name: 'Dansk TV',  wm: 'Dansk<small>TV</small>', logo: 'assets/brand/dansk-tv-logo.svg', bg: 'linear-gradient(135deg,#c8102e,#1a1a1a)' },
    { id: 'nordisk',  name: 'Nordisk Film', wm: 'NF',      bg: 'linear-gradient(135deg,#2b6f5d,#10342b)' },
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
    { id: 'continue', title: 'Continue Watching', kind: 'land', cfg: 'Continue + Next Up', items: continueItems },
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
  function ep(n, title, dur, desc, pct) { return { n, title, dur, desc, pct: pct || 0, grad: grad(title + n) }; }
  const SERIES_EP = {
    'Nordvest': [
      [ // Season 1
        ep(1, 'Hvalvík', '58m', 'Detective Sigrun Restorff steps off the ferry into the town that raised her — and a body that won\u2019t let her leave.', 100),
        ep(2, 'Bóndin', '54m', 'A farmer\u2019s confession unravels faster than the rope that bound him.', 100),
        ep(3, 'Grindadráp', '61m', 'The grind paints the bay red; beneath the tide, an older debt surfaces.', 100),
        ep(4, 'Útróður', '57m', 'A prosecutor from Copenhagen arrives, and the case shifts language and loyalty.', 62),
        ep(5, 'Foss', '55m', 'Sigrun follows the money upriver to the salmon farm the whole town depends on.', 0),
        ep(6, 'Náttúra', '59m', 'A storm seals the island. The suspect list narrows to the people she loves.', 0),
        ep(7, 'Heim', '56m', 'The secret her father carried to sea washes back to the harbour wall.', 0),
        ep(8, 'Endi', '63m', 'Two truths, one confession, and a tide that takes everything back.', 0),
      ],
      [ // Season 2
        ep(1, 'Nýtt Ár', '60m', 'A new year, a frozen harbour, and a face Sigrun buried long ago.', 0),
        ep(2, 'Toka', '52m', 'Fog swallows the road north; a routine call goes silent.', 0),
        ep(3, 'Djúpið', '58m', 'Divers find more than the wreck they were paid to forget.', 0),
        ep(4, 'Skuld', '55m', 'An old debt comes due in the only currency the coast respects.', 0),
        ep(5, 'Brot', '57m', 'Everything cracks at once; Sigrun chooses which piece to save.', 0),
        ep(6, 'Lokið', '64m', 'The coast keeps its dead, but not its secrets. Season finale.', 0),
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
      out.push(ep(i + 1, t, (48 + (i * 7) % 20) + 'm', 'A new chapter, pulled live from Jellyfin and organised by Jellystructure.', i === 0 ? 28 : 0));
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
  const config = { radarr: true, region: 'DK', regionName: 'Denmark' };

  const sources = [
    { id: 'netflix', name: 'Netflix', via: 'Tudum', wm: 'N', accent: '#e50914', enabled: true },
    { id: 'disney',  name: 'Disney+', via: 'soon',  wm: 'D+',  accent: '#1f7cf2', enabled: false },
    { id: 'max',     name: 'Max',     via: 'soon',  wm: 'MAX', accent: '#8a44e6', enabled: false },
  ];

  function D(title, year, genre, rating, kind, o) {
    o = o || {};
    return Object.assign(T(title, year, genre, rating, kind), {
      status: o.status || 'none', progress: o.progress || 0,
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
        D('Saltvatn', 2023, 'Drama', '12', 'film', { status: 'none', weeks: 1, trend: 'new', syn: 'A widowed lighthouse keeper takes in a stranded sailor as winter storms close the only road home.' }),
        D('Vargtid', 2022, 'Action', '16', 'film', { status: 'none', weeks: 3, trend: 'down', syn: 'A disgraced ranger hunts the wolf pack blamed for a boy’s disappearance — and the men who set them loose.' }),
        D('Cosmos Laundromat', 2015, 'Sci-Fi', '12', 'film', { status: 'available', weeks: 6, trend: 'same' }),
        D('Nordlys Protocol', 2023, 'Action', '16', 'film', { status: 'none', weeks: 2, trend: 'up' }),
        D('Den Sidste Vinter', 2021, 'Drama', '12', 'film', { status: 'fetching', progress: 12, weeks: 1, trend: 'new' }),
        D('Granat', 2020, 'Action', '16', 'film', { status: 'none', weeks: 5, trend: 'down' }),
        D('Drift 7', 2022, 'Sci-Fi', '12', 'film', { status: 'none', weeks: 2, trend: 'same' }),
        D('Stormkast', 2019, 'Action', '12', 'film', { status: 'none', weeks: 1, trend: 'new' }),
      ]) },
    { id: 'tv-dk', title: 'Top 10 TV Shows in Denmark', scope: 'country', category: 'series', metric: 'rank',
      note: 'Ranking only — the country feed has no view counts', items: ranked([
        D('Nordvest', 2023, 'Crime', '16', 'series', { status: 'available', weeks: 7, trend: 'same', syn: 'In a fog-bound Faroese fishing town, a detective returns home to a death that reopens a buried family secret.' }),
        D('Arvur', 2023, 'Drama', '16', 'series', { status: 'none', weeks: 2, trend: 'up', syn: 'When the family patriarch dies, three siblings discover the inheritance is a debt none of them can pay.' }),
        D('Havets Hjarta', 2022, 'Drama', '12', 'series', { status: 'available', weeks: 3, trend: 'down' }),
        D('Glasberget', 2024, 'Drama', '16', 'series', { status: 'none', weeks: 1, trend: 'new' }),
        D('Mýrin', 2021, 'Crime', '16', 'series', { status: 'fetching', progress: 63, weeks: 4, trend: 'same' }),
        D('Brúgvin', 2022, 'Crime', '16', 'series', { status: 'none', weeks: 2, trend: 'up' }),
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

  window.RAVILO = { studios, hero, rows, mergedNew, profiles, discover, grad, initials, episodesFor, seasonsFor, castFor, relatedFor };
})();
