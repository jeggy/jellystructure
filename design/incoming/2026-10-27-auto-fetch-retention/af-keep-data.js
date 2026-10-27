/* af-keep-data.js — round `auto-fetch-retention`, 2026-10-27.

   ONE array per fact. Every number, date, code and count in every picture is derived from this
   file, so "do these pictures agree with each other?" is something a reviewer can check rather
   than trust (PROCESS.md, *Derive every number in every picture from one array*).

   Stand-in corpus only, and only the names the brief fixed: `Dagbók` on varpid.example.fo as the
   page-only, date-numbered fetcher, and `Royndin` / `Nordvest` / `Babel Fish` exactly as the
   shipped `ai.js` and the round before this one already draw them. No episode name is invented:
   Dagbók's items carry the page's own generic headings (`Dagbók · 16. oktober`), which is also
   the honest shape of a daily programme, and `Útfall` stays the one cleared Royndin name.

   Chromium has no Faroese locale and `toLocaleDateString` falls back to English in silence
   (PROCESS.md), so the month name is tabled here and no date is ever formatted by the browser. */

(function (W) {
  'use strict';

  const pad = (n) => String(n).padStart(2, '0');

  /* Faroese month names, tabled. Only October is reached by this round's corpus; the rest are
     here so a later frame cannot quietly fall back to English by using an index this file lacks. */
  const MONTH_FO = ['januar', 'februar', 'mars', 'apríl', 'mai', 'juni',
                    'juli', 'august', 'september', 'oktober', 'november', 'desember'];

  /* FR-4.5's identity: season = the published date's year, episode = its day of the year. 1-366,
     computed, never typed — `S2026E300` in a picture is this function's answer or it is a defect. */
  function doy(iso) {
    const t = new Date(iso + 'T00:00:00Z');
    const jan1 = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
    return Math.round((t - jan1) / 86400000) + 1;
  }
  const se = (iso) => 'S' + iso.slice(0, 4) + 'E' + pad(doy(iso));
  const dayMonthFo = (iso) => Number(iso.slice(8, 10)) + '. ' + MONTH_FO[Number(iso.slice(5, 7)) - 1];
  const days = (fromIso, n) => {
    const out = [];
    const t = new Date(fromIso + 'T00:00:00Z');
    for (let i = 0; i < n; i++) { out.push(t.toISOString().slice(0, 10)); t.setUTCDate(t.getUTCDate() + 1); }
    return out;
  };

  /* ---------------------------------------------------------------------------- sites (FR-2) */
  const SITES = {
    varpid:  { key: 'varpid',  host: 'varpid.example.fo' },
    playdk:  { key: 'playdk',  host: 'play.example.dk' },
    watchtv: { key: 'watchtv', host: 'watch.example.tv' },
  };

  /* ====================================================================== Dagbók, page-only.

     The brief fixes the listing: 12 items, 2026-10-16 → 2026-10-27, which FR-4.5 numbers
     S2026E289 → S2026E300. Both ends are checked by `doy` below rather than trusted.

     WHAT IS IN THE LIBRARY, and why it is exactly this. Three of the brief's numbers have to
     agree with each other and with FR-16, and only one arrangement makes all three exact:

       · the run record deletes exactly ONE episode, and it is `S2026E286 · 2026-10-13`;
       · lowering Keep from 12 to 8 deletes exactly FOUR;
       · the Keep hint reads *Keeps 12 of the 12 on the page*.

     So the fetcher's placed rows, just before today's 04:00 run, are twelve: S2026E286, and then
     S2026E289 → S2026E299. Today's run places S2026E300, which makes thirteen, so the newest
     twelve are S2026E289 → S2026E300 and S2026E286 falls out and is deleted — one deletion, the
     brief's own episode, and from then on the folder is exactly the twelve the page lists.

     The gap at 2026-10-14 and 2026-10-15 is not an accident of the corpus and is not a skipped
     broadcast: those two were placed and then removed by hand, so FR-16.2 released their rows —
     *When the recorded path no longer holds a file, the row is released and nothing is deleted.*
     It is the one spec-supported way a daily programme's folder has a hole in it, and it is why
     the deleted episode can be 2026-10-13 rather than 2026-10-15. */
  const DAG_PAGE_FROM = '2026-10-16';
  const DAG_PAGE_N = 12;
  const DAG_ITEMS = days(DAG_PAGE_FROM, DAG_PAGE_N).map((iso) => ({
    iso,
    item: 'Dagbók · ' + dayMonthFo(iso),          /* the page's own heading, generic (FR-15.4) */
    se: se(iso),
    ep: null,                                      /* the page gives no episode name: FR-8.2 */
  }));

  /* placed by this fetcher and still in the library, before today's run, oldest first */
  const DAG_PLACED_BEFORE = ['2026-10-13'].concat(days(DAG_PAGE_FROM, DAG_PAGE_N - 1));
  const DAG_RUN_PLACES = '2026-10-27';            /* today's item, the newest on the page */
  const DAG_RELEASED_BY_HAND = ['2026-10-14', '2026-10-15'];

  /* ---------------------------------------------------- retention, worked out and never typed.
     `keepNewest(placed, n)` is this round's model of FR-16.3: order by air date, newest first,
     keep n, the rest go. It is the only place a deletion set is decided, so the run record, the
     preview line and the hint cannot disagree with each other. */
  function keepNewest(placedIso, n) {
    const byNewest = placedIso.slice().sort().reverse();
    return { keep: byNewest.slice(0, n).sort(), drop: byNewest.slice(n).sort() };
  }

  /* today's run: +1, then retention, as FR-16.3 orders it (deletions at the END of the run) */
  const DAG_AFTER_PLACE = DAG_PLACED_BEFORE.concat([DAG_RUN_PLACES]);
  const DAG_RUN = keepNewest(DAG_AFTER_PLACE, 12);          /* drop → ['2026-10-13'] */
  const DAG_LIBRARY = DAG_RUN.keep;                          /* the twelve, after the run */
  const DAG_LOWERED = keepNewest(DAG_LIBRARY, 8);            /* drop → the oldest four */

  const DAGBOK = {
    id: 'dagbok', name: 'Dagbók', site: 'varpid', path: '/sendingar/dagbok',
    pageOnly: true, tmdb: null,
    numbering: 'date', pat: 'Date · S{year}E{day of year}',
    sched: 'Daily · 04:00', q: '1080p HLS', on: true,
    keep: { mode: 'newest', n: 12, leaves: true },
    size: '1.1 GB',
    /* The page's own words for the programme itself (FR-15.3), read at save and on
       Re-investigate, never on a scheduled run. Generic on purpose: a daily programme's page
       says what the programme is, and this corpus invents nothing beyond that. */
    page: { name: 'Dagbók', desc: 'Dagliga sendingin — ein nýggj hvønn dag.', hasImage: true,
            descSrc: 'from the programme page — its own description, verbatim' },
  };

  /* ============================================================== the other three fetchers.
     Exactly as the shipped `ai.js` draws them, plus the `keep` the amendment adds. A TMDB
     fetcher starts at *All episodes* (FR-16.1) and that is what these carry. */
  const FETCHERS = {
    dagbok: DAGBOK,
    royndin: { id: 'royndin', name: 'Royndin', site: 'varpid', path: '/sendingar/royndin',
      tmdb: 9000001, numbering: 'source', pat: 'S{year}E{partur|(n:m)}',
      sched: 'Daily · 04:00', q: '1080p HLS', on: true, have: 13,
      keep: { mode: 'all', n: 12, leaves: false },
      runs: [['ok', 'no new episodes', '04:00 today']] },
    nordvest: { id: 'nordvest', name: 'Nordvest', site: 'playdk', path: '/serier/nordvest',
      tmdb: 9000002, numbering: 'mapped', pat: 'Mapped · 8 accepted',
      sched: 'Every 6 h', q: '1080p DASH', on: true, have: 8,
      keep: { mode: 'all', n: 12, leaves: false },
      runs: [['new', '+2 · S02E07, S02E08', '3h ago']] },
    babel: { id: 'babel', name: 'Babel Fish', site: 'watchtv', path: '/babel-fish/episodes',
      tmdb: 9000003, numbering: 'source', pat: 'S{nn}E{nn}',
      sched: 'Weekly · Mon', q: '720p MP4', on: true, have: 22,
      keep: { mode: 'all', n: 12, leaves: false },
      runs: [['bad', 'page layout changed — manifest URL not found · re-investigate?', 'Mon 04:00']] },
  };

  /* ------------------------------------------------------------- Royndin's thirteen items.
     The shipped corpus, re-derived here so the TMDB review frames and the page-only ones come
     out of one file. Nine 2022 parts (the first three already in the library), three 2025 parts,
     one 2026 part that TMDB has no episode for. */
  const ROY = [];
  for (let i = 1; i <= 9; i++) {
    ROY.push({ item: 'Royndin 2022: ' + i + '. partur', se: 'S2022E' + pad(i),
               pub: '2022-' + (i <= 4 ? '09-' + pad(4 + (i - 1) * 7) : '10-' + pad((i - 5) * 7 + 2)),
               have: i <= 3, onTmdb: true, ep: null });
  }
  for (let i = 1; i <= 3; i++) {
    ROY.push({ item: 'Royndin 2025: ' + i + '.partur (' + i + ':3)', se: 'S2025E' + pad(i),
               pub: '2025-03-' + pad(2 + (i - 1) * 7), have: false, onTmdb: true, ep: null });
  }
  ROY.push({ item: 'Royndin 2026 (1:3)', se: 'S2026E01', pub: '2026-10-13',
             have: false, onTmdb: false, ep: 'Útfall' });

  /* Royndin's own retention arithmetic, for the frame where the admin chooses *The newest 12*
     on the review state. Thirteen items; S2022E01 is both the oldest AND already in the library,
     so choosing 12 is a real deletion of one and the preview says so. Nothing here is typed. */
  const ROY_PLACED = ROY.filter((r) => r.have).map((r) => r.pub);
  const ROY_ALL = ROY.map((r) => r.pub);
  const ROY_KEEP12 = keepNewest(ROY_ALL, 12);
  const ROY_DELETES = ROY_KEEP12.drop.filter((p) => ROY_PLACED.indexOf(p) >= 0);
  const ROY_FOUND = ROY.length;

  /* ------------------------------------------------------------------ the new strings, en/fo/da.
     English is what the pictures draw. The Faroese and Danish are DRAFTS for a speaker, as the
     standing i18n rule asks, and the shipped `i18n/*.json` wins wherever a string already exists
     there. The admin surface is not in `i18n/` today, so none of these has a shipped twin to
     disagree with; the Faroese leans on the forms `i18n/lexicon/fo.txt` already holds — `partar`,
     `strika`, `nýggjast`, `síðan`, `halda` — and the report names the words it has to add. */
  const STRINGS = [
    ['af.pageonly.tick', 'This programme is not on TMDB',
      'Henda sendingin er ikki á TMDB', 'Dette program er ikke på TMDB'],
    ['af.pageonly.hint', 'The episodes keep the page’s own titles and descriptions, and nothing asks TMDB',
      'Partarnir halda heitini og umrøðurnar á síðuni sjálvari, og einki verður spurt TMDB um',
      'Afsnittene beholder sidens egne titler og beskrivelser, og der spørges ikke TMDB'],
    ['af.pageonly.refused', 'Auto-fetch needs a TMDB id, or tick “not on TMDB”',
      'Auto-fetch krevur eitt TMDB-id, ella set kross við „ikki á TMDB“',
      'Auto-fetch kræver et TMDB-id, eller sæt hak ved „ikke på TMDB“'],
    ['af.pageonly.series', 'page only · not on TMDB',
      'bara síðan · ikki á TMDB', 'kun siden · ikke på TMDB'],
    ['af.pageonly.proof', '{n} items, each with its own date',
      '{n} lutir, hvør við sínum egna degi', '{n} elementer, hver med sin egen dato'],
    ['af.keep.label', 'Keep', 'Varðveit', 'Behold'],
    ['af.keep.all', 'All episodes', 'Allir partar', 'Alle afsnit'],
    /* `Teir nýggjastu` is the literal Faroese and it wrapped to two lines inside its own seg
       option at 412, which made that option a line taller than the other. The draft drops the
       article — `Nýggjastu`, still *the newest* — and fits. A speaker confirms or overrules it;
       the report names it as the one place the three languages were not drawn alike. */
    ['af.keep.newest', 'The newest', 'Nýggjastu', 'De nyeste'],
    ['af.keep.leaves', 'Also delete an episode once it leaves the page',
      'Strika eisini ein part, tá hann fer av síðuni',
      'Slet også et afsnit, når det forsvinder fra siden'],
    ['af.keep.hint', 'Keeps {n} of the {m} on the page. Older ones are deleted from the library.',
      'Varðveitir {n} av teimum {m} á síðuni. Eldri verða strikaðir úr tilfarinum.',
      'Beholder {n} af de {m} på siden. Ældre slettes fra biblioteket.'],
    ['af.keep.hint_all', 'Every episode stays. Nothing is ever deleted.',
      'Allir partar verða varðveittir. Einki verður strikað.',
      'Alle afsnit bliver. Intet slettes nogensinde.'],
    ['af.keep.range', '1 to 999', '1 til 999', '1 til 999'],
    ['af.keep.preview', 'Saving this deletes {n} episodes at the next run',
      'Verður hetta goymt, verða {n} partar strikaðir næstu ferð',
      'Gemmes dette, slettes {n} afsnit ved næste kørsel'],
    ['af.keep.preview_one', 'Saving this deletes 1 episode at the next run',
      'Verður hetta goymt, verður 1 partur strikaður næstu ferð',
      'Gemmes dette, slettes 1 afsnit ved næste kørsel'],
    ['af.list.keeps', 'keeps newest {n}', 'varðveitir nýggjastu {n}', 'beholder nyeste {n}'],
    ['af.run.kept', 'newest {n} kept', 'nýggjastu {n} varðveittir', 'nyeste {n} beholdt'],
    ['af.run.left', 'left the page', 'farin av síðuni', 'forsvundet fra siden'],
    ['af.run.guard', 'left the page: not deleted, the listing could not be read in full',
      'farin av síðuni: ikki strikað, listin kundi ikki lesast til fulnar',
      'forsvundet fra siden: ikke slettet, listen kunne ikke læses fuldt ud'],
    ['af.activity', 'Auto-fetch · {name} · +1 episode · −1 by retention',
      'Auto-fetch · {name} · +1 partur · −1 av varðveitslu',
      'Auto-fetch · {name} · +1 afsnit · −1 ved opbevaring'],
  ];
  const S = (key) => {
    const r = STRINGS.find((x) => x[0] === key);
    if (!r) throw new Error('no such string: ' + key);
    return r;
  };
  /* en('af.keep.hint', {n: 12, m: 12}) — the one way a string reaches a picture. */
  const fill = (s, vars) => s.replace(/\{(\w+)\}/g, (_, k) => {
    if (!vars || vars[k] === undefined) throw new Error('unfilled {' + k + '} in: ' + s);
    return vars[k];
  });
  const en = (key, vars) => fill(S(key)[1], vars);
  const fo = (key, vars) => fill(S(key)[2], vars);
  const da = (key, vars) => fill(S(key)[3], vars);

  /* ------------------------------------------------------------------------ assertions.
     A picture that disagrees with the brief is a defect, and the cheapest place to catch it is
     here, before anything is drawn. A failure throws, the canvas comes up empty, and the render
     script's `--all` finds no frame — a loud failure rather than a wrong number in a PNG. */
  const must = (cond, msg) => { if (!cond) throw new Error('af-keep-data: ' + msg); };
  must(DAG_ITEMS.length === 12, 'Dagbók’s listing is not 12 items');
  must(DAG_ITEMS[0].se === 'S2026E289', 'first item is ' + DAG_ITEMS[0].se + ', not S2026E289');
  must(DAG_ITEMS[11].se === 'S2026E300', 'last item is ' + DAG_ITEMS[11].se + ', not S2026E300');
  must(DAG_ITEMS[0].item === 'Dagbók · 16. oktober', 'first heading is ' + DAG_ITEMS[0].item);
  must(DAG_PLACED_BEFORE.length === 12, 'placed-before is not 12 rows');
  must(DAG_RUN.drop.length === 1 && se(DAG_RUN.drop[0]) === 'S2026E286',
       'today’s run drops ' + DAG_RUN.drop.map(se).join(', ') + ', not S2026E286 alone');
  must(DAG_LIBRARY.length === 12, 'the library after the run is not 12');
  must(DAG_LIBRARY.join() === DAG_ITEMS.map((i) => i.iso).join(),
       'the twelve in the library are not the twelve on the page');
  must(DAG_LOWERED.drop.length === 4, 'lowering to 8 drops ' + DAG_LOWERED.drop.length + ', not 4');
  must(ROY_FOUND === 13, 'Royndin is not 13 items');
  must(ROY_DELETES.length === 1 && ROY_DELETES[0] === '2022-09-04',
       'Royndin at newest 12 deletes ' + ROY_DELETES.length + ' from the library, not S2022E01');

  W.AF = {
    pad, doy, se, dayMonthFo, days, keepNewest,
    SITES, FETCHERS, DAGBOK,
    DAG_ITEMS, DAG_PLACED_BEFORE, DAG_RUN_PLACES, DAG_RELEASED_BY_HAND,
    DAG_RUN, DAG_LIBRARY, DAG_LOWERED,
    ROY, ROY_FOUND, ROY_KEEP12, ROY_DELETES, ROY_PLACED,
    STRINGS, en, fo, da,
  };
})(window);
