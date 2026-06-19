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
    { id: 'hbo',      name: 'HBO',       wm: 'HBO',        bg: 'linear-gradient(135deg,#3b2a78,#15102e)' },
    { id: 'tv2',      name: 'TV 2',      wm: 'TV<small>2</small>', bg: 'linear-gradient(135deg,#e3122b,#7d0a1a)' },
    { id: 'kringvarp',name: 'Kringvarp', wm: 'KvF',        bg: 'linear-gradient(135deg,#0a93a6,#063d47)' },
    { id: 'dr',       name: 'DR',        wm: 'DR',         bg: 'linear-gradient(135deg,#1455d8,#0a2766)' },
    { id: 'viaplay',  name: 'Viaplay',   wm: 'viaplay',    bg: 'linear-gradient(135deg,#ff2e5b,#8a0f2c)' },
    { id: 'dansktv',  name: 'Dansk TV',  wm: 'Dansk<small>TV</small>', bg: 'linear-gradient(135deg,#c8102e,#1a1a1a)' },
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
    comedy: ['Fjollerne i Nord','Sommerhus & Sild','Naboer','Fars Ferie','Den Gode Nabo','Tøris','Kaffepause','Bryllupsballaden','Hytteliv','Strandvask'],
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
    { ...T('Fjollerne i Nord', 2020, 'Comedy', '12', 'series'), ep: 'S3:E7 · 18 min left', pct: 41 },
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
    { id: 'eyd',    name: 'Eyð',    initials: 'ER', color: 'linear-gradient(145deg,#7b6ef0,#3fb6f5)', signedIn: true,  isAdmin: true,  kid: false, lang: 'fo' },
    { id: 'olivar', name: 'Olivar', initials: 'OL', color: 'linear-gradient(145deg,#19d6c6,#2a8cf0)', signedIn: true,  isAdmin: false, kid: false, lang: 'en' },
    { id: 'marjun', name: 'Marjun', initials: 'MJ', color: 'linear-gradient(145deg,#f5b542,#e0792f)', signedIn: true,  isAdmin: false, kid: false, lang: 'da' },
    { id: 'kids',   name: 'Kids',   initials: '★',  color: 'linear-gradient(145deg,#e0639a,#b15cd0)', signedIn: true,  isAdmin: false, kid: true,  lang: 'fo' },
  ];

  window.RAVILO = { studios, hero, rows, mergedNew, profiles, grad, initials, episodesFor, seasonsFor, castFor, relatedFor };
})();
