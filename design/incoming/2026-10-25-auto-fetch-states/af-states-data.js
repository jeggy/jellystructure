/* af-states-data.js — round `auto-fetch-states`, 2026-10-25.

   ONE array per fact. Every number in every picture is derived from this file, so "do these
   pictures agree with each other?" is something a reviewer can check rather than trust
   (PROCESS.md, *Derive every number in every picture from one array*).

   Stand-in corpus only, and only the names the brief fixed: Royndin / Nordvest / Babel Fish on
   varpid.example.fo / play.example.dk / watch.example.tv. No episode name is invented: Royndin's
   `Útfall` is the one cleared name, Nordvest's items carry the generic `Nordvest · n`, and TMDB's
   side of a Nordvest mapping carries the generic `Afsnit n`. Where a Royndin row has to show what
   TMDB holds, it shows TMDB's AIR DATE rather than a name — a date is a fact we can state without
   inventing a title, and an invented title is the one string the de-anonymisation fence cannot
   judge. */

(function (W) {
  'use strict';

  const pad = (n) => String(n).padStart(2, '0');
  const d = (iso) => iso;                      // dates are tabled, never toLocaleDateString()
  const plus = (iso, days) => {                // small date helper, UTC only
    const t = new Date(iso + 'T00:00:00Z');
    t.setUTCDate(t.getUTCDate() + days);
    return t.toISOString().slice(0, 10);
  };

  /* ---------------------------------------------------------------- sites.
     A site is one lane for every fetcher on it (FR-2). A stop belongs to the SITE. */
  const SITES = {
    varpid:  { key: 'varpid',  host: 'varpid.example.fo' },
    playdk:  { key: 'playdk',  host: 'play.example.dk' },
    watchtv: { key: 'watchtv', host: 'watch.example.tv' },
  };

  /* ------------------------------------------------- the four causes a lane stops on (FR-2.5).
     Plain words with the code in mono. `short` goes in a table row, `long` in the open panel.
     fo/da are the drafts, listed flat in the report; `fo` is the longest-string case. */
  const STOP_CAUSES = [
    { id: 'c403', key: '403',
      short: ['403 on the episode list, which worked at the last run', ['403']],
      long:  ['The site answered 403 on the episode list, which worked at the last run.', ['403']],
      fo:    ['Heimasíðan svaraði 403 á sendingarlistanum, sum virkaði seinast tað rann.', ['403']],
      da:    ['Siden svarede 403 på afsnitslisten, som virkede ved sidste kørsel.', ['403']],
      at: 'today 04:00' },
    { id: 'challenge', key: 'challenge',
      short: ['a challenge page instead of the episode list', []],
      long:  ['The site sent a challenge page — cf-mitigated — instead of the episode list.', ['cf-mitigated']],
      fo:    ['Heimasíðan sendi eina eftirkanningarsíðu — cf-mitigated — í staðin fyri sendingarlistan.', ['cf-mitigated']],
      da:    ['Siden sendte en kontrolside — cf-mitigated — i stedet for afsnitslisten.', ['cf-mitigated']],
      at: 'today 04:00' },
    { id: 'c429', key: '429',
      short: ['429 for the third time today', ['429']],
      long:  ['The site answered 429 for the third time today.', ['429']],
      fo:    ['Heimasíðan svaraði 429 triðju ferð í dag.', ['429']],
      da:    ['Siden svarede 429 for tredje gang i dag.', ['429']],
      at: 'today 16:10' },
    { id: 'robots', key: 'robots.txt',
      short: ['robots.txt no longer allows the episode list', ['robots.txt']],
      long:  ['The site’s robots.txt no longer allows the episode list to be read.', ['robots.txt']],
      fo:    ['robots.txt á heimasíðuni loyvir ikki longur sendingarlistan.', ['robots.txt']],
      da:    ['Sidens robots.txt tillader ikke længere afsnitslisten.', ['robots.txt']],
      at: 'today 03:30' },
  ];
  const CAUSE = (id) => STOP_CAUSES.find((c) => c.id === id);

  /* ---------------------------------------------------------- Royndin: the 13 items on the page.
     2022 · 9 parts, 2025 · 3 parts, 2026 · 1 part published of three = 13 (the brief's found set:
     13 episodes across 2022 · 2025 · 2026). Three are already in the library, ten are missing.
     S2026E01 is the one TMDB has no episode for, so TMDB has 12 — which is where `12 of 12` and
     `1 not on TMDB yet` both come from, and they are the same twelve everywhere. */
  const ROY = [];
  for (let i = 1; i <= 9; i++) {
    ROY.push({
      item: 'Royndin 2022: ' + i + '. partur',
      se: 'S2022E' + pad(i),
      pub: plus('2022-09-04', (i - 1) * 7),
      tmdbAir: plus('2022-09-05', (i - 1) * 7),
      have: i <= 3,
      ep: null,
    });
  }
  for (let i = 1; i <= 3; i++) {
    ROY.push({
      item: 'Royndin 2025: ' + i + '.partur (' + i + ':3)',
      se: 'S2025E' + pad(i),
      pub: plus('2025-03-02', (i - 1) * 7),
      tmdbAir: plus('2025-03-02', (i - 1) * 7),
      have: false,
      ep: null,
    });
  }
  ROY.push({
    item: 'Royndin 2026 (1:3)',
    se: 'S2026E01',
    pub: '2026-10-13',
    tmdbAir: null,                      // TMDB has no such episode — this is the queued one
    have: false,
    ep: 'Útfall',
  });

  /* The three TMDB scenarios for the same thirteen items. A scenario changes what TMDB holds; it
     never changes the items, so `13 found` is one number across all three.
       proven    — TMDB has 12, all within 7 days of the item's published date → 12 agreements.
       sparse    — TMDB has only the oldest → 1 agreement, which is fewer than the two FR-4.2 asks.
       disagree  — TMDB has the same 12, but two of them sit months away from the page's date. */
  const SPARSE_HAS = ['S2022E01'];
  const DISAGREE = { S2025E03: '2025-11-02', S2022E09: '2022-12-04' };

  function royndin(scenario) {
    return ROY.map((r) => {
      let air = r.tmdbAir;
      if (scenario === 'sparse') air = SPARSE_HAS.indexOf(r.se) >= 0 ? r.tmdbAir : null;
      if (scenario === 'disagree' && DISAGREE[r.se]) air = DISAGREE[r.se];
      const onTmdb = !!air;
      const gap = onTmdb ? Math.round((new Date(air) - new Date(r.pub)) / 86400000) : null;
      return Object.assign({}, r, { tmdbAir: air, onTmdb, gap, agrees: onTmdb && Math.abs(gap) <= 7 });
    });
  }
  const proof = (rows) => {
    const has = rows.filter((r) => r.onTmdb);
    const ok = has.filter((r) => r.agrees);
    const against = has.filter((r) => !r.agrees);
    return { found: rows.length, hasN: has.length, okN: ok.length, againstN: against.length,
             against, proven: ok.length >= 2 && against.length === 0 };
  };

  /* ------------------------------------------------- Nordvest: mapped numbering (FR-4.3, Q2).
     The page's order is not an episode number, so the model proposes item → SxxEyy and only the
     rows the admin ticks are stored. Eight proposed rows; five ticked in the partly-ticked frame. */
  const ACCEPTED = [1, 2, 3, 5, 7];
  const NORD = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => ({
    item: 'Nordvest · ' + i,
    se: 'S01E' + pad(i),
    tmdbName: 'Afsnit ' + i,
    why: i % 3 === 0 ? 'matched by description' : 'matched by title',
    have: i <= 2,
    accepted: ACCEPTED.indexOf(i) >= 0,
  }));

  /* Items found on a later run, not downloaded, waiting for a number (FR-4.3). The third has no
     description on the page at all, which is said plainly rather than filled in. */
  const WAITING = [
    { item: 'Nordvest · 9',  since: '2026-10-21', desc: 'Afsnit 9 · Nordvest.',
      proposal: { se: 'S01E09', name: 'Afsnit 9', why: 'matched by description' } },
    { item: 'Nordvest · 10', since: '2026-10-22', desc: 'Afsnit 10 · Nordvest.',
      proposal: { se: 'S01E10', name: 'Afsnit 10', why: 'matched by description' } },
    { item: 'Nordvest · 11', since: '2026-10-23', desc: null,
      proposal: { se: 'S01E11', name: 'Afsnit 11', why: 'matched by title' } },
  ];

  /* A waiting item on a PATTERN fetcher, which is the OTHER cause of the same state: FR-4.2's
     not-proven pattern, where every episode TMDB lacks waits for the admin. Royndin reads its
     numbers out of the item's own title, so the title is what the frame has to show — a generic
     label would make a pattern fetcher's frame unreadable. This is the next part of the 2026
     season the corpus already draws as `Royndin 2026 (1:3)`, derived from it rather than invented,
     and the page carries no description for it, which is said plainly. */
  const WAITING_PATTERN = [
    { item: 'Royndin 2026 (2:3)', since: '2026-10-20', desc: null,
      proposal: { se: 'S2026E02', name: '—', why: 'matched by published date' } },
  ];

  /* The header's next check. Round 2 drew `next check in 5h 12m` and that string stands. With
     varpid.example.fo stopped, Royndin's Daily · 04:00 is not what comes next — Nordvest's
     Every 6 h is, and it ran 3h ago. The header keeps a next check because the OTHER sites still
     run, and names the stopped site as an extra rather than instead (Bárður, 2026-10-25). */
  const NEXT_ALL = 'next check in 5h 12m';
  const NEXT_VARPID_STOPPED = 'next check in 2h 51m · varpid.example.fo is stopped';

  /* The three verbatim reasons a Propose / Investigate control is shown disabled for
     (processing-runs-as-nine-features.md FR-11, quoted in the spec's FR-1.2). */
  const AI_OFF_REASONS = [
    'AI is switched off in Settings ▸ AI',
    'The key was refused — Settings ▸ AI',
    'This month’s Auto-fetch limit is spent ($5.00 of $5.00)',
  ];

  /* ------------------------------------------------------------------------ saved fetchers.
     The three the brief fixes, plus — only in the all-states frame — a SECOND Royndin fetcher, on
     play.example.dk. Four row states need four rows and the corpus has three series; a second
     fetcher for the same series on another site needs no new name, and it is the frame's own
     argument: the stop belongs to varpid.example.fo, so the play.example.dk fetcher for the very
     same series runs normally one row below. See REPORT.md, open question 1. */
  const FETCHERS = {
    royndin: { id: 'royndin', name: 'Royndin', site: 'varpid', path: '/sendingar/royndin',
      tmdb: 9000001, pat: 'S{year}E{partur|(n:m)}', sched: 'Daily · 04:00', q: '1080p HLS',
      on: true, have: 13,
      runs: [['new', '+1 · S2026E01 (1080p, 1.9 GB) · not on TMDB → queued', 'today 04:00'],
             ['ok', 'no new episodes', 'yesterday 04:00'],
             ['new', '+1 · S2025E03 (1080p, 1.8 GB)', 'Oct 12 04:00']] },
    royndinPlay: { id: 'royndin-play', name: 'Royndin', site: 'playdk', path: '/serier/royndin',
      tmdb: 9000001, pat: 'S{year}E{partur|(n:m)}', sched: 'Daily · 04:00', q: '1080p DASH',
      on: true, have: 13,
      runs: [['ok', 'no new episodes', 'today 04:00']] },
    nordvest: { id: 'nordvest', name: 'Nordvest', site: 'playdk', path: '/serier/nordvest',
      tmdb: 9000002, pat: 'Mapped · 8 accepted', sched: 'Every 6 h', q: '1080p DASH',
      on: true, have: 8,
      runs: [['wait', '2 new items found · waiting for a number', '3h ago'],
             ['ok', 'no new episodes', '9h ago'],
             ['ok', 'no new episodes', '15h ago']] },
    babel: { id: 'babel', name: 'Babel Fish', site: 'watchtv', path: '/babel-fish/episodes',
      tmdb: 9000003, pat: 'S{nn}E{nn}', sched: 'Weekly · Mon', q: '720p MP4',
      on: true, have: 22,
      runs: [['bad', 'page layout changed — manifest URL not found · re-investigate?', 'Mon 04:00'],
             ['ok', 'no new episodes', 'Oct 6']] },
  };

  /* --------------------------------------------- the five investigation steps (FR-10.5, round 2). */
  const STEPS = [
    { id: 'page',    label: 'Read the page',
      fx: '13 episode cards · 3 season tabs · player: HLS (m3u8) behind a JSON manifest', t: '2.1s' },
    { id: 'videos',  label: 'Find how the videos are served',
      fx: '/api/v2/media/{id}/manifest → variants 1080p · 720p · 360p · audio-only → highest kept', t: '4.8s' },
    { id: 'series',  label: 'Confirm this is a series',
      fx: '13 items with recurring title + season grouping · matches TMDB 9000001 (3 seasons) · not a film', t: '1.4s' },
    { id: 'pattern', label: 'Prove the numbering against TMDB',
      fx: 'year-in-title → S{YYYY}; “N. partur” or “(N:M)” → E{NN} · 13/13 mapped · 0 ambiguous', t: '3.0s' },
    { id: 'recipe',  label: 'Write the recipe',
      fx: 'declarative, version 1 · nothing in it is executed · hosts fixed to the page’s site', t: '2.6s' },
  ];
  /* The step that fails in the failed-mark frame, and what it says when it does. */
  const STEP_FAIL = { at: 'videos',
    fx: 'every variant carries a Widevine key system → this site protects its videos; Auto-fetch does not get past that' };

  /* ------------------------------------------- the still, with FR-10.3's corrected copy (owner Q4). */
  const STILL = {
    series: 'Royndin', se: 'S2026E01', ep: 'Útfall', air: '2026-10-13', run: '44:12',
    stills: ['06:12', '18:40', '31:05'], pick: 1,
    copy: 'Three frames picked from the video: faces visible, no title card, no black bars.',
    was: 'Three frames the model picked: faces visible, no title card, no black bars.',
  };

  /* FR-10.4's corrected bug-report line. */
  const BUGLINE = 'The report is pre-filled with the site, the TMDB id and the five steps’ outcomes — never the page’s content.';

  W.AF = { pad, d, plus, SITES, STOP_CAUSES, CAUSE, ROY, royndin, proof, NORD, ACCEPTED,
           WAITING, WAITING_PATTERN, NEXT_ALL, NEXT_VARPID_STOPPED,
           AI_OFF_REASONS, FETCHERS, STEPS, STEP_FAIL, STILL, BUGLINE };
})(window);
