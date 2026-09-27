/* Audiobooks — shared stand-in data for the admin (app/) and the phone (ravilo/). Part 2 of the music round.
   Shaped on the household library measured 2026-09-27 (audiobooks report §1): ONE book, 14 MP3 parts at
   96 kbps, 2.4–32 min each, 5 h 24 min in all, part numbers 1–15 with 6 missing, tags only (title · author ·
   year 2008 · genre "Audiobook"), no cover, no narrator, no description, no chapters, nobody has listened
   through Jellyfin. Every name is fictional. `extra` books exist only behind the "many" preview. */
(function () {
  const M = window.MUSIC || {};
  const hash = M.hash || (s => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0; return h; });
  const L = [144, 1860, 1745, 1920, 1690, 246, 1810, 1655, 1790, 1720, 1880, 294, 1760, 926];
  const NUMS = [1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15];
  function mk(o) {
    let acc = 0;
    o.parts = o.parts.map((p, i) => { const r = Object.assign({ start: acc }, p); acc += p.len; return r; });
    o.len = acc; o.hue = hash(o.id) % 360; return o;
  }
  const household = mk({
    id: 'vinterfaergen', title: 'Vinterfærgen', subtitle: '', authors: ['ingrid-lykke'], narrators: [], year: 2008, language: 'da', publisher: '', genres: ['Audiobook'],
    series: null, cover: false, desc: '', source: 'files', folder: 'Lydbøger/Ingrid Lykke/Vinterfærgen', format: 'MP3', kbps: 96,
    parts: L.map((len, i) => ({ n: NUMS[i], title: 'Vinterfærgen ' + String(NUMS[i]).padStart(2, '0'), len, jfPos: i === 1 ? 760 : null })),
    chaptersFrom: 'files', household: true,
  });
  household.chapters = household.parts.map((p, i) => ({ n: i + 1, title: i === 0 ? 'Indledning' : 'Kapitel ' + i, start: p.start, len: p.len }));
  const extra = [
    mk({ id: 'the-salt-road', title: 'The Salt Road', subtitle: 'A novel', authors: ['anna-berg'], narrators: ['Tom Hale'], year: 2019, language: 'en', publisher: 'Northlight Audio', genres: ['Historical fiction'],
      series: { name: 'Northern Roads', n: 2, of: 5 }, cover: true, source: 'itunes', folder: 'Lydbøger/Anna Berg/The Salt Road', format: 'M4B', kbps: 64, chaptersFrom: 'embedded',
      desc: 'Two sisters walk the old salt road from the coast to the mountain markets, carrying their father\'s debts and a letter neither of them is allowed to open.',
      parts: [{ n: 1, title: 'The Salt Road', len: 39720 }] }),
    mk({ id: 'nordlys', title: 'Nordlys', subtitle: '', authors: ['ingrid-lykke'], narrators: ['Mette Dahl'], year: 2011, language: 'da', publisher: '', genres: ['Roman'],
      series: null, cover: false, source: 'edited', folder: 'Lydbøger/Ingrid Lykke/Nordlys', format: 'MP3', kbps: 96, chaptersFrom: 'files', desc: '',
      parts: [1, 2, 3, 4, 5, 7, 8, 9, 10].map(n => ({ n, title: 'Nordlys ' + n, len: 1500 + (n * 97) % 400 })) }),
    mk({ id: 'stille-vand', title: 'Stille vand', subtitle: 'Noveller', authors: ['per-holm'], narrators: ['Per Holm'], year: 2016, language: 'da', publisher: 'Lille Forlag', genres: ['Noveller'],
      series: { name: 'Havnebyen', n: 1, of: 3 }, cover: true, source: 'edited', folder: 'Lydbøger/Per Holm/Stille vand', format: 'MP3', kbps: 128, chaptersFrom: 'files', finishedBy: 1,
      desc: 'Ti noveller fra en lille havneby, hvor alle kender hinanden og ingen siger det, de mener.',
      parts: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(n => ({ n, title: 'Stille vand ' + n, len: 900 + (n * 131) % 700 })) }),
    mk({ id: 'samlede', title: 'Samlede fortællinger', subtitle: '', authors: ['ingrid-lykke'], narrators: [], year: 2014, language: 'da', publisher: '', genres: ['Audiobook'],
      series: null, cover: false, source: 'files', folder: 'Lydbøger/Ingrid Lykke/Samlede', format: 'MP3', kbps: 96, chaptersFrom: 'files', twoBooks: ['Samlede fortællinger I', 'Samlede fortællinger II'], desc: '',
      parts: [1, 2, 3, 4, 5, 6].map(n => ({ n, title: (n <= 3 ? 'Samlede I ' : 'Samlede II ') + n, len: 1400 + n * 60 })) }),
  ];
  extra.forEach(b => { if (!b.chapters) b.chapters = b.chaptersFrom === 'embedded'
    ? Array.from({ length: 24 }, (_, i) => ({ n: i + 1, title: i === 0 ? 'Opening credits' : 'Chapter ' + i, start: Math.round(i * b.len / 24), len: Math.round(b.len / 24) }))
    : b.parts.map((p, i) => ({ n: i + 1, title: 'Kapitel ' + (i + 1), start: p.start, len: p.len })); });
  const authors = [
    { id: 'ingrid-lykke', name: 'Ingrid Lykke', sort: 'Lykke, Ingrid', image: false, bio: '' },
    { id: 'anna-berg', name: 'Anna Berg', sort: 'Berg, Anna', image: true, bio: 'Anna Berg writes historical novels set along the North Sea coast. The Salt Road is the second of five Northern Roads books.' },
    { id: 'per-holm', name: 'Per Holm', sort: 'Holm, Per', image: false, bio: '' },
  ];
  const byAuthor = {}; authors.forEach(a => { a.hue = hash(a.id) % 360; byAuthor[a.id] = a; });
  // per-listener state (a jellystructure table: Jellyfin cannot hold it — the 5-minute rule)
  const listeners = { 'vinterfaergen': [{ who: 'Eyð', pos: 7920, last: 'last night' }, { who: 'Olivar', pos: null }, { who: 'Katrin', pos: null }, { who: 'Bedstemor', pos: null }],
    'the-salt-road': [{ who: 'Eyð', pos: 14220, last: 'Tuesday' }, { who: 'Katrin', pos: 'finished', last: 'in March' }], 'stille-vand': [{ who: 'Katrin', pos: 'finished', last: 'in August' }] };
  function fmtH(s) { s = Math.round(s); const h = Math.floor(s / 3600), m = Math.round(s % 3600 / 60); return h ? h + ' h ' + m + ' min' : m + ' min'; }
  function partAt(b, pos) { let i = 0; b.parts.forEach((p, k) => { if (p.start <= pos) i = k; }); return i; }
  function chapterAt(b, pos) { let i = 0; b.chapters.forEach((c, k) => { if (c.start <= pos) i = k; }); return i; }
  function gaps(b) { const g = []; for (let k = 1; k < b.parts.length; k++) for (let n = b.parts[k - 1].n + 1; n < b.parts[k].n; n++) g.push(n); return g; }
  window.BOOKS = { household, extra, all: [household].concat(extra), authors, byAuthor, listeners, fmtH, partAt, chapterAt, gaps,
    book: id => [household].concat(extra).find(b => b.id === id), author: id => byAuthor[id],
    authorNames: ids => ids.map(i => (byAuthor[i] || {}).name).filter(Boolean).join(' & '),
    coverStyle: b => (M.coverStyle ? M.coverStyle({ id: b.id, hue: b.hue }) : ''), wordmarkStyle: b => (M.wordmarkStyle ? M.wordmarkStyle(b.title) : '') };
})();
