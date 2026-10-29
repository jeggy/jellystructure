/* aiv-data.js — round `ai-per-viewer-and-schedule`, 2026-10-29.
 *
 * EVERY number, name, date, price and sentence in every picture of this round comes out of this
 * one file, and the frames compute none of their own. That is both the house rule for a round
 * (PROCESS.md: "derive every number in every picture from one array", so a reviewer can CHECK that
 * the pictures agree with each other instead of trusting it) and the honest model of the product:
 * the admin renders server-pushed state and invents none.
 *
 * It also REFUSES TO LOAD if a value the brief named exactly has drifted — see `assert` at the
 * bottom. A silent disagreement between a picture and the brief is the one defect a fence cannot
 * see and a reader will not notice.
 *
 * Today, for every "ago" and "next" in here, is THURSDAY 2026-10-29. The next Tuesday is
 * 2026-11-03. Weekday and month names are TABLED, never `toLocaleDateString`: Chromium has no
 * Faroese locale and falls back to English in silence (PROCESS.md).
 */
(function () {
  'use strict';

  var D = {};

  D.today = { iso: '2026-10-29', weekday: 'Thursday', short: '29 Oct' };
  D.weekdays = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

  /* ─────────────────────────────────────────────────────────── 1. the viewer list (FR-1.1, FR-1.2)
   * The brief's nine rows, in its own order and its own words. `why` is the server's default
   * reason, and it is drawn on EVERY row: FR-1.2 asks that the default is never silent, which cuts
   * both ways — a row that is on by default has to say so too, or "off — test account" reads as the
   * only row with a reason rather than as one of two defaults.
   *
   * `seen` and `order` are the server's two sentences, not fields this page assembles.
   *
   * NOTE for the report: the brief's list is 5 people and 4 test accounts (Gestur is `on`, so he is
   * not a test account), while its own summary sentence says "4 people plus 5 test accounts". The
   * NAMED LIST is what is drawn, exactly; the count sentence is the slip.
   */
  D.viewers = [
    { name: 'Anna',               on: true,  why: 'on by default',      seen: 'Seen today',        order: 'AI order · written 27 Oct', test: false },
    { name: 'Bjarni',             on: true,  why: 'on by default',      seen: 'Seen today',        order: 'AI order · written 27 Oct', test: false },
    { name: 'Dávur',              on: true,  why: 'on by default',      seen: 'Seen today',        order: 'AI order · written 27 Oct', test: false },
    { name: 'Elin',               on: true,  why: 'on by default',      seen: 'Seen today',        order: 'AI order · written 27 Oct', test: false },
    { name: 'qa-deb1000-a1b2c3',  on: false, why: 'off — test account', seen: 'Seen 2 days ago',   order: 'Standard order',            test: true  },
    { name: 'qa-probe-1000',      on: false, why: 'off — test account', seen: 'Seen 4 days ago',   order: 'Standard order',            test: true  },
    { name: 'qa-1000-a',          on: false, why: 'off — test account', seen: 'Seen 6 days ago',   order: 'Standard order',            test: true  },
    { name: 'Test Stream',        on: false, why: 'off — test account', seen: 'Never seen',        order: 'Standard order',            test: true  },
    { name: 'Gestur',             on: true,  why: 'on by default',      seen: 'Never seen',        order: 'No AI order yet',           test: false },
  ];

  /* The same installation after QA has made its throwaway accounts: the nine above and 39 more
   * `qa-…` users. One house, two sizes — so the two pictures can be read against each other.
   * Every count here is the SERVER's; the page composes none of them. */
  D.list = {
    small: { users: 9, on: 5, off: 4, viewers: 4 },
    long:  { users: 48, on: 5, off: 43, viewers: 4 },
    /* the server's own count line, one per size */
    lineSmall: '9 users · 5 switched on · 4 off — test account',
    lineLong: '48 users · 5 switched on · 43 off — test account',
    shownLong: 'Showing 14 of 48 — 34 test accounts are folded away',
    filterPlaceholder: 'Filter by name',
    tabs: ['All 48', 'People 5', 'Test accounts 43'],
    foldedHead: '34 more test accounts',
    foldedSub: 'Every one of them off by the test-account rule. Open it to switch one on.',
  };

  /* The extra `qa-…` rows the long list shows beyond the brief's nine. Drawn from a pattern, not
   * invented one at a time, because a real list of these IS a pattern. */
  D.extraTest = [
    { name: 'qa-deb1000-d4e5f6', seen: 'Seen 2 days ago' },
    { name: 'qa-deb1000-g7h8i9', seen: 'Seen 3 days ago' },
    { name: 'qa-probe-1001',     seen: 'Seen 3 days ago' },
    { name: 'qa-probe-1002',     seen: 'Seen 5 days ago' },
    { name: 'qa-1000-b',         seen: 'Seen 6 days ago' },
  ];

  /* The three states the list itself can be in, each the server's sentence. */
  D.viewerStates = {
    aiOffWhy: 'AI is off for this installation, so nothing is re-ranked. The switches are kept as they are.',
    aiOffHead: 'Use AI is off',
    saving: 'Saving…',
    switchedOff: 'Switched off. Their waiting request was removed and their AI order deleted — their list has been rebuilt in the standard order.',
    switchedOffWhen: 'switched off today',
    loadingLine: 'Reading the users from the media server…',
  };

  /* ───────────────────────────────────────────── 2. Settings ▸ Processing (FR-2.1, FR-3.1, FR-3.6)
   * The row's own line, then the two parts. Every sentence is the server's (FR-12 of DEB-1664).
   */
  D.row = {
    name: 'Recommendations &amp; suggestions',
    when: 'Recommendations weekly · suggestions monthly · Ran 51 min ago · Next Tue 20:00',
  };

  /* The quiet-viewer setting: one label, three choices, and the SERVER's sentence for each. The
   * sentence changes with the choice, which is why it is here per option and not one hint. */
  D.quiet = {
    label: 'When nobody has watched anything new',
    options: [
      { v: 'skip_ai',    label: 'Rebuild, skip the AI',
        recs: 'The free list is rebuilt. No re-rank for that viewer — their stored AI order still applies, filtered to the new list.',
        sugg: 'The free list is rebuilt. No AI groups for this build; it keeps the genre groups.' },
      { v: 'skip_both',  label: 'Skip both',
        recs: 'Nothing is rebuilt for that viewer. Their list stays exactly as it is.',
        sugg: 'Nothing is rebuilt. The household list stays exactly as it is.' },
      { v: 'rebuild',    label: 'Rebuild and re-rank',
        recs: 'Rebuilt and re-ranked every run, whatever anyone watched. This is what it did before.',
        sugg: 'Rebuilt and grouped by AI every run, whatever anyone watched. This is what it did before.' },
    ],
    note: 'Starter lists are always rebuilt — they cost nothing and need no viewer.',
  };

  /* The picker. Five modes; `monthly` is the new one. The cron is what is saved; the mode is how a
   * saved cron reads back (FR-3.2). */
  D.modes = [
    { v: 'everyN',  label: 'Every N hours' },
    { v: 'daily',   label: 'Daily at' },
    { v: 'weekly',  label: 'Weekly on' },
    { v: 'monthly', label: 'Monthly on' },
    { v: 'custom',  label: 'Custom' },
  ];

  D.monthlyNote = 'A day from 1 to 28, so every month has it.';
  D.customNote = 'Leave it empty to build only when you scan.';

  /* The two parts at the brief's stand-in values. */
  D.parts = {
    recs: {
      id: 'build_recommendations',
      name: 'Recommendations',
      help: 'The rows a viewer sees.',
      mode: 'weekly', dow: 2, at: '20:00', cron: '0 20 * * 2',
      quiet: 'skip_ai',
      next: 'Next Tue 20:00',
      estimate: 'A month at this schedule: at most 4 runs × 5 viewers switched on, before any skip.',
    },
    sugg: {
      id: 'build_suggestions',
      name: 'Suggestions',
      help: 'Things to request that we do not hold. These need the request app connected.',
      mode: 'monthly', day: 1, at: '04:00', cron: '0 4 1 * *',
      quiet: 'skip_both',
      next: 'Next 1 Nov 04:00',
      estimate: 'A month at this schedule: at most 1 run for the household, before any skip.',
    },
  };

  /* The five modes drawn open, for the picker frame. `check` is the server's own answer from
   * POST /api/config/validate-schedule — the admin never reads a cron itself. */
  D.modeShots = [
    { mode: 'everyN',  n: 5,  m: 0,                  cron: '0 */5 * * *',
      check: 'Every 5 hours, at minute 0', next: 'Next: 29 Oct 20:00 · 30 Oct 01:00 · 30 Oct 06:00' },
    { mode: 'daily',   at: '04:00',                  cron: '0 4 * * *',
      check: 'Daily at 04:00', next: 'Next: 30 Oct 04:00 · 31 Oct 04:00 · 1 Nov 04:00' },
    { mode: 'weekly',  dow: 2, at: '20:00',          cron: '0 20 * * 2',
      check: 'Weekly on Tuesday at 20:00', next: 'Next: 3 Nov 20:00 · 10 Nov 20:00 · 17 Nov 20:00' },
    { mode: 'monthly', day: 1, at: '04:00',          cron: '0 4 1 * *',
      check: 'Monthly on day 1 at 04:00', next: 'Next: 1 Nov 04:00 · 1 Dec 04:00 · 1 Jan 04:00' },
    { mode: 'custom',  cron: '0 7 */3 * 1-5',
      check: 'At 07:00 on every 3rd day of the month, Monday through Friday',
      next: 'Next: 31 Oct 07:00 · 3 Nov 07:00 · 6 Nov 07:00' },
    { mode: 'custom',  cron: '0 7 */3 * 8',          bad: 'day of week out of range: 8',
      check: null, next: null },
  ];

  /* ─────────────────────────────────────────────────────── 3. the Scan dialog (FR-4.1 – FR-4.4) */
  D.dialog = {
    title: 'Scan library',
    lede: 'Tick what this scan should do. What you untick is remembered for next time.',
    ledePhone: 'Tick what this scan should do.',
    recheckOff: 'Only what is due by its own interval is looked at again.',
    legendHead: 'Queued work is not done when the scan ends.',
    legend: 'The reading carries on in the background —',
    /* the left pane, as the shipped dialog draws it: name, then `n of m` or `one tick` */
    features: [
      { id: 'find',     name: 'Find new &amp; changed',        count: 'one tick',   on: 'on' },
      { id: 'meta',     name: 'Metadata &amp; artwork',        count: '6 of 6',     on: 'on' },
      { id: 'segments', name: 'Intro &amp; credits',           count: '0 of 2',     on: '',   queues: true },
      { id: 'health',   name: 'File health',                   count: '4 of 4',     on: 'on', queues: true },
      { id: 'subs',     name: 'Subtitles',                     count: '2 of 2',     on: 'on', queues: true },
      { id: 'direct',   name: 'Make files play directly',      count: 'one tick',   on: 'on', queues: true },
      { id: 'recs',     name: 'Recommendations &amp; suggestions', count: '1 of 2', on: 'part' },
      { id: 'music',    name: 'Music',                         count: '7 of 7',     on: 'on' },
      { id: 'books',    name: 'Audiobooks',                    count: 'one tick',   on: 'on' },
    ],
    paneLede: 'Both lists are built from what the household has finished watching. Each part runs in this scan whether or not its own schedule is due.',
    /* Each part's own line, per tick state — the server sends both and the dialog SELECTS
     * (FR-4.4: it selects, it does not compose). */
    recs: {
      name: 'Recommendations',
      does: "Rebuilds 4 viewers’ lists now — the schedule would next run Tue 20:00",
      skips: 'Not in this scan — next by schedule Tue 20:00',
      ai: 'Re-ranks 4 viewers switched on · up to $0.26 · $0.09 of this month’s $5.00 left',
      aiDisabled: 'This month’s AI limit leaves too little',
      aiNotInScan: 'Tick Recommendations first',
    },
    sugg: {
      name: 'Suggestions',
      does: 'Rebuilds the household list now — the schedule would next run 1 Nov 04:00',
      skips: 'Not in this scan — next by schedule 1 Nov 04:00',
      ai: 'Asks AI to group the list · up to $0.07',
      aiDisabled: 'This month’s AI limit leaves too little',
      aiNotInScan: 'Tick Suggestions first',
    },
    aiTick: 'and ask AI',
    /* The one summary line above Start scan, in its two faces. Server-sent parts; the dialog shows
     * the ones that match its ticks. */
    /* NOTE for the report: the brief's example line names five of the dialog's ten tickable
     * parts (*Runs: Find new & changed, Metadata & artwork, Recommendations · Skips: Intro &
     * credits, Suggestions*). The dialog it is drawn in has ten, and the left pane shows the other
     * five ticked — so printing the brief's line verbatim would have made each picture contradict
     * itself. Its SHAPE, its three clauses and its AI wording are kept exactly; the two lists are
     * extended to name what the picture actually ticks. The assertion at the bottom of this file
     * is what holds that true from here on.
     */
    summary: {
      none: { runs: 'Find new &amp; changed, Metadata &amp; artwork, File health, Subtitles, Make files play directly, Recommendations, Music, Audiobooks',
              skips: 'Intro &amp; credits, Suggestions',
              ai: 'none' },
      rerank: { runs: 'Find new &amp; changed, Metadata &amp; artwork, File health, Subtitles, Make files play directly, Recommendations, Music, Audiobooks',
                skips: 'Intro &amp; credits, Suggestions',
                ai: 're-rank 4 viewers' },
      /* both parts ticked, neither AI available: Suggestions moves out of Skips and into Runs. The
         first render of `dlg-limit` reused the `none` summary and so contradicted its own ticks —
         which is exactly what one data file per round exists to make visible. */
      limit: { runs: 'Find new &amp; changed, Metadata &amp; artwork, File health, Subtitles, Make files play directly, Recommendations, Suggestions, Music, Audiobooks',
               skips: 'Intro &amp; credits',
               ai: 'none' },
      longest: { runs: 'Find new &amp; changed, Metadata &amp; artwork, Intro &amp; credits, File health, Subtitles, Make files play directly, Recommendations, Suggestions, Music, Audiobooks',
                 skips: 'nothing',
                 ai: 're-rank 4 viewers, group the suggestions' },
    },
    toastNone: { line: 'Scan started · skipped <b>Intro &amp; credits</b>', sub: 'AI: none' },
    toastAi: { line: 'Scan started · skipped <b>Intro &amp; credits</b>', sub: 'AI: re-rank 4 viewers' },
  };

  /* ───────────────────────────────────────────────────────── 4. Activity ▸ the AI card (FR-5.1) */
  D.activity = {
    job: 'Re-rank',
    model: 'Claude Haiku 4.5',
    waiting: 37,
    batchOut: 0,
    spent: '$4.91 of $5.00 this month',
    held: '37 waiting — this month’s limit: $0.09 left, the next request could cost up to $0.08',
    removeAll: 'Remove all 37 waiting',
    chip: 'held by this month’s limit',
    rows: [
      { who: 'Anna',   reason: 'weekly build', by: 'the schedule', at: '04:12' },
      { who: 'Bjarni', reason: 'weekly build', by: 'the schedule', at: '04:12' },
      { who: 'Dávur',  reason: 'weekly build', by: 'the schedule', at: '04:12' },
      { who: 'Elin',   reason: 'weekly build', by: 'the schedule', at: '04:12' },
      { who: 'Anna',   reason: 'started by hand', by: 'an admin', at: '09:48' },
    ],
    more: 'and 32 more',
  };

  /* ──────────────────────────────────────────────────────────────── 5. the longest strings */
  D.longest = {
    /* A Jellyfin display name is whatever somebody typed into Jellyfin, and the list holds every
     * user the server can read — so the longest realistic name is the test case, not a convenience. */
    name: 'qa-deb1000-playback-rung-probe-f0e9d8',
    why: 'off — test account',
    seen: 'Seen 14 days ago',
    order: 'AI order · written 27 Oct',
    quiet: 'Rebuild and re-rank',
    quietHint: 'Rebuilt and grouped by AI every run, whatever anyone watched. This is what it did before.',
    check: 'At 07:00 on every 3rd day of the month, Monday through Friday',
    does: "Rebuilds 4 viewers’ lists now — the schedule would next run Tue 20:00",
    ai: 'Re-ranks 4 viewers switched on · up to $0.26 · $0.09 of this month’s $5.00 left',
  };

  /* ───────────────────────────────────────────────────────────────────────── the self-check
   * Each of these is a value the brief named exactly. If an edit drifts one, nothing renders.
   */
  function assert(ok, what) { if (!ok) throw new Error('aiv-data: ' + what); }

  assert(D.viewers.length === 9, 'the brief names nine rows');
  assert(D.viewers.filter(function (v) { return v.test; }).length === 4,
    'four of the nine are test accounts (Gestur is on, so he is not one)');
  assert(D.viewers.filter(function (v) { return v.on; }).length === D.list.small.on,
    'the switched-on count and the rows must agree');
  assert(D.list.small.on + D.list.small.off === D.list.small.users, 'the small list adds up');
  assert(D.list.long.on + D.list.long.off === D.list.long.users, 'the long list adds up');
  assert(D.list.long.viewers === D.list.small.viewers,
    'the number of VIEWERS (a Ravilo device has been seen) is the same house in both sizes');
  assert(D.viewers.filter(function (v) { return v.seen.indexOf('Never') !== 0 && v.on; }).length === D.list.long.viewers,
    'four viewers: switched on AND seen — which is what every "4 viewers" sentence counts');
  assert(D.dialog.recs.does.indexOf('4 viewers') > 0 && D.dialog.recs.ai.indexOf('4 viewers') > 0,
    'the dialog counts the same four viewers');
  assert(D.parts.recs.cron === '0 20 * * 2' && D.parts.recs.at === '20:00',
    'Recommendations: weekly on Tuesday at 20:00');
  assert(D.parts.sugg.cron === '0 4 1 * *' && D.parts.sugg.day === 1,
    'Suggestions: monthly on day 1 at 04:00');
  assert(D.parts.recs.quiet === 'skip_ai' && D.parts.sugg.quiet === 'skip_both',
    'Recommendations skips the AI; Suggestions skips both');
  assert(D.quiet.options.length === 3, 'three quiet-viewer choices');
  assert(D.quiet.options[0].v === 'skip_ai', 'Rebuild, skip the AI is the default and comes first');
  assert(D.modes.length === 5 && D.modes[3].v === 'monthly', 'five modes, monthly the new one');
  assert(D.activity.waiting === 37 && D.activity.removeAll.indexOf('37') > 0,
    'the Remove all control carries the server’s own count');
  (function () {
    var feats = D.dialog.features.length;   /* 9 rows, but Recommendations & suggestions is 2 parts */
    Object.keys(D.dialog.summary).forEach(function (k) {
      var s2 = D.dialog.summary[k];
      var n = s2.runs.split(', ').length + (s2.skips === 'nothing' ? 0 : s2.skips.split(', ').length);
      assert(n === feats + 1, 'summary "' + k + '" must name all ' + (feats + 1) +
        ' tickable parts across Runs and Skips, once each — it names ' + n);
    });
  })();

  assert(D.row.when === 'Recommendations weekly · suggestions monthly · Ran 51 min ago · Next Tue 20:00',
    'the row line is the brief’s, word for word');

  window.AIV = D;
})();
