/* ============================================================================
   sp-data.js — every number, name, date and sentence in this round.

   PROCESS.md: "Derive every number in every picture from one array." Nothing in
   a frame is typed by hand into the markup; the builder reads this file, and
   this file refuses to load if two pictures would disagree with each other or
   with the brief.

   Two things it deliberately does NOT use:
     - `toLocaleDateString`. Chromium has no Faroese locale and falls back to
       English silently, so a round that leaned on it would report a formatting
       it never drew. The weekday and month names are tabled below.
     - any derivation of the next five runs from the chosen schedule at render
       time. The admin renders server-pushed state only (constitution
       invariant 4; spec FR-3.8), so every preview here is a literal the
       *server* would have answered, and the assertions at the bottom check it
       against the schedule rather than computing it from it.
   ============================================================================ */
(function () {
  'use strict';

  // ── The clock every picture is taken at ──────────────────────────────────
  // One moment, named once. "Next five runs" in every frame is five fires
  // after THIS instant, in THIS zone.
  const NOW = { text: 'Thu 12 Nov 2026 · 14:20', y: 2026, m: 11, d: 12, hh: 14, mm: 20 };
  const ZONE = 'Atlantic/Faroe';

  const DAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];   // Monday first (FR-3.2)
  const DAY_TOGGLE = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
  const DAY_LONG = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  const MON_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];

  // The unit menu, in the order FR-3.1/3.10 fixes it: Off first (only where the
  // setting can be off), the five units, Custom (cron) last.
  const UNITS = [
    { v: 'off', w: 'Off', offOnly: true },
    { v: 'hours', w: 'hours' },
    { v: 'days', w: 'days' },
    { v: 'weeks', w: 'weeks' },
    { v: 'months', w: 'months' },
    { v: 'years', w: 'years' },
    { v: 'cron', w: 'Custom (cron)', last: true },
  ];

  const ORDINALS = ['first', 'second', 'third', 'fourth', 'last'];

  // ── The two homes ────────────────────────────────────────────────────────
  // The measured widths, not the brief's approximations. See REPORT.md: the
  // brief says "~280 px"; the shipped lane is wider and window-dependent, and
  // 280 is the honest floor to draw the narrow face at.
  const HOMES = {
    panel: {
      key: 'panel', label: 'Auto-fetcher side panel', width: 280,
      fetcher: 'Evening News', site: 'example.net',
      field: 'Check',
      floorMin: 360,                 // 6 h (auto-fetch FR-2.3), unchanged
      floorWhy: '6 hours',
    },
    row: {
      key: 'row', label: 'Settings ▸ Processing · a feature row', width: 1100,
      floorMin: 15,
      floorWhy: '15 minutes',
    },
  };

  // The processing features by their admin names, as the brief fixes them.
  const FEATURES = { find: 'Find new & changed', music: 'Music' };

  // ── The states, exactly the brief's seven ────────────────────────────────
  // `says` and `next` are the server's answer (FR-3.8, FR-6.1). `next` is [] in
  // every state where the server has no runs to give — refused, or off — and
  // the control keeps the slot rather than collapsing it.
  const STATES = [
    {
      key: 'weeks', brief: 1,
      title: 'Weeks · Monday and Tuesday · 04:00',
      mode: 'weeks', n: 1, days: [0, 1], at: '04:00',
      stored: '0 4 * * 1,2',
      storedWhy: 'a cron, because the choice can be said as one (FR-1.2)',
      says: 'Every Monday and Tuesday at 04:00 ' + ZONE + '.',
      next: [
        'Mon 16 Nov 2026 · 04:00', 'Tue 17 Nov 2026 · 04:00', 'Mon 23 Nov 2026 · 04:00',
        'Tue 24 Nov 2026 · 04:00', 'Mon 30 Nov 2026 · 04:00',
      ],
    },
    {
      key: 'fortnight', brief: 2,
      title: 'Every 2 weeks · Wednesday · starting 18 Nov 2026',
      mode: 'weeks', n: 2, days: [2], at: '04:00', from: '2026-11-18', fromText: '18 Nov 2026',
      stored: 'every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18',
      storedWhy: 'a recurrence: an interval above 1 on weeks is the one thing cron cannot say',
      says: 'Every 2 weeks on Wednesday at 04:00 ' + ZONE + ', counting from 18 Nov 2026.',
      next: [
        'Wed 18 Nov 2026 · 04:00', 'Wed 2 Dec 2026 · 04:00', 'Wed 16 Dec 2026 · 04:00',
        'Wed 30 Dec 2026 · 04:00', 'Wed 13 Jan 2027 · 04:00',
      ],
    },
    {
      key: 'lastfri', brief: 3,
      title: 'Months · the last Friday',
      mode: 'months', n: 1, monthShape: 'weekday', ordinal: 4, ordDay: 4, at: '04:00',
      stored: 'every:FREQ=MONTHLY;INTERVAL=1;BYDAY=-1FR;AT=04:00;FROM=2026-11-27',
      storedWhy: 'a recurrence: an ordinal weekday is cron-unsayable',
      says: 'The last Friday of every month at 04:00 ' + ZONE + '.',
      next: [
        'Fri 27 Nov 2026 · 04:00', 'Fri 25 Dec 2026 · 04:00', 'Fri 29 Jan 2027 · 04:00',
        'Fri 26 Feb 2027 · 04:00', 'Fri 26 Mar 2027 · 04:00',
      ],
    },
    {
      key: 'decade', brief: 4,
      title: 'Every 10 years · 1 March · starting 2026',
      mode: 'years', n: 10, month: 2, dayOfMonth: 1, at: '04:00',
      from: '2026-03-01', fromText: '1 Mar 2026',
      stored: 'every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01',
      storedWhy: 'a recurrence: a 5-field cron has no year field',
      says: 'Every 10 years on 1 March at 04:00 ' + ZONE + ', counting from 2026.',
      // 1 Mar 2026 is BEHIND the clock above, so the first fire the server can
      // offer is 2036. This is the round's sharpest finding — see REPORT.md.
      next: [
        'Sat 1 Mar 2036 · 04:00', 'Thu 1 Mar 2046 · 04:00', 'Wed 1 Mar 2056 · 04:00',
        'Mon 1 Mar 2066 · 04:00', 'Sun 1 Mar 2076 · 04:00',
      ],
      lookAhead: 'The anchor it counts from, 1 Mar 2026, is in the past, so the first run it can offer is 2036.',
    },
    {
      key: 'cron-ok', brief: 5,
      title: 'Custom (cron) · a schedule the server accepts',
      mode: 'cron', cron: '30 3 * * 0',
      stored: '30 3 * * 0',
      storedWhy: 'the cron, stored exactly as typed',
      says: 'Every Sunday at 03:30 ' + ZONE + '.',
      next: [
        'Sun 15 Nov 2026 · 03:30', 'Sun 22 Nov 2026 · 03:30', 'Sun 29 Nov 2026 · 03:30',
        'Sun 6 Dec 2026 · 03:30', 'Sun 13 Dec 2026 · 03:30',
      ],
    },
    {
      key: 'cron-bad', brief: 5,
      title: 'Custom (cron) · a schedule the server refuses',
      mode: 'cron', cron: '30 3 * * 9',
      invalid: true,
      error: 'Day of week is 0 to 6. 9 is not a day.',
      says: null,
      next: [],
      saveHeld: 'Save is held until the schedule is one the server accepts.',
    },
    {
      key: 'off', brief: 6,
      title: 'Off',
      mode: 'off',
      stored: '',
      storedWhy: 'the empty string (FR-1.3)',
      says: 'This job runs only when you start it by hand.',
      next: [],
      nextEmpty: 'Nothing scheduled.',
    },
    {
      key: 'floor', brief: 7,
      title: 'Hours · refused under the auto-fetcher’s 6-hour floor',
      mode: 'hours', n: 1, minute: '20',
      invalid: true, floor: true,
      error: 'Checks on a site are never closer than 6 hours apart. Choose 6 hours or more.',
      says: null,
      next: [],
      saveHeld: 'Save is held until the schedule is one the server accepts.',
    },
  ];

  // ── The length cases ─────────────────────────────────────────────────────
  // These are STATES, not a separate structure built in the builder, so every
  // assertion below runs over them too. The first revision built them by
  // patching a copy of a state inside sp-build.js, which went round the
  // assertions — and the render came out with all seven weekdays toggled on
  // over a list of five Wednesdays. A picture contradicted itself exactly
  // where the data file exists to stop it.
  const LENGTH = [
    {
      key: 'longest-says', brief: 2, length: true, title: 'The longest sentence',
      why: 'Seven weekdays named in full, an interval, a time, a zone and an anchor — the longest sentence this control can ask the server for.',
      mode: 'weeks', n: 2, days: [0, 1, 2, 3, 4, 5, 6], at: '04:00',
      from: '2026-11-18', fromText: '18 Nov 2026',
      stored: 'every:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR,SA,SU;AT=04:00;FROM=2026-11-18',
      storedWhy: 'a recurrence: the interval is above 1',
      says: 'Every 2 weeks on Monday, Tuesday, Wednesday, Thursday, Friday, Saturday and Sunday at 04:00 ' + ZONE + ', counting from 18 Nov 2026.',
      // The anchor week is 16–22 Nov and the anchor itself is the Wednesday, so
      // the Monday and Tuesday of that week are behind it and the first five
      // fires are Wed to Sun.
      next: [
        'Wed 18 Nov 2026 · 04:00', 'Thu 19 Nov 2026 · 04:00', 'Fri 20 Nov 2026 · 04:00',
        'Sat 21 Nov 2026 · 04:00', 'Sun 22 Nov 2026 · 04:00',
      ],
    },
    {
      key: 'longest-err', brief: 5, length: true, title: 'The longest refusal',
      why: 'The longest refusal in the round, in a 280 px panel.',
      mode: 'cron', cron: '*/7 * * * *', invalid: true,
      error: 'Minute is 0 to 59, and a step must divide the field it steps through. */7 does not divide 60.',
      says: null, next: [],
      saveHeld: 'Save is held until the schedule is one the server accepts.',
    },
    {
      key: 'longest-zone', brief: 1, length: true, title: 'The longest zone name',
      why: 'The longest IANA name there is, beside the time field. The household’s is Atlantic/Faroe; this is the one that breaks the line.',
      mode: 'weeks', n: 1, days: [0, 1], at: '04:00',
      zone: 'America/Argentina/ComodRivadavia',
      stored: '0 4 * * 1,2', storedWhy: 'a cron',
      says: 'Every Monday and Tuesday at 04:00 America/Argentina/ComodRivadavia.',
      next: [
        'Mon 16 Nov 2026 · 04:00', 'Tue 17 Nov 2026 · 04:00', 'Mon 23 Nov 2026 · 04:00',
        'Tue 24 Nov 2026 · 04:00', 'Mon 30 Nov 2026 · 04:00',
      ],
    },
  ];
  LENGTH.forEach(s => STATES.push(s));

  // The unit menu drawn open, to show Off first and Custom (cron) last in one
  // picture (FR-3.1, FR-3.10).
  const MENU_OPEN = { onUnit: 'weeks' };

  // The longest entry in the unit menu, which is what the select's width is
  // set from. Asserted below against every entry there actually is.
  const LONGEST = { unit: 'Custom (cron)' };

  // ── The three shapes the round compared ──────────────────────────────────
  const SHAPES = [
    { key: 'lines', w: 'Lines', verdict: 'kept',
      gives: 'One decision per line, in the same order at every width. The lines sit side by side when there is room and stack when there is not, so there is one markup and no narrow face to build.',
      costs: 'Taller than the other two in the panel: six lines where the sentence shape takes three.' },
    { key: 'sentence', w: 'Sentence', verdict: 'dropped',
      gives: 'Reads as the thing it means — one wrapping sentence with controls in it. Shortest of the three at full width.',
      costs: 'At 280 px it wraps mid-phrase, and where it wraps changes with the chosen unit. The weekday toggles and the ordinal row cannot sit in running text without becoming the line.' },
    { key: 'summary', w: 'Summary and a sheet', verdict: 'dropped',
      gives: 'The panel shows only the sentence and Change…, so the schedule costs three lines wherever it appears.',
      costs: 'The sentence and the next five runs are the whole point of FR-3.8, and behind a sheet they are not on the page while the choice is being made. It is also a second surface to build and a second place for state to live.' },
  ];

  // ── Assertions ───────────────────────────────────────────────────────────
  // This file refuses to load rather than let two pictures disagree.
  function die(msg) { throw new Error('sp-data: ' + msg); }

  const DMAP = {}; DAY_SHORT.forEach((d, i) => { DMAP[d] = i; });
  const MMAP = {}; MON_SHORT.forEach((m, i) => { MMAP[m] = i; });

  // "Mon 16 Nov 2026 · 04:00" → a sortable number, and a weekday check.
  function parseFire(s, where) {
    const m = /^([A-Z][a-z][a-z]) (\d{1,2}) ([A-Z][a-z][a-z]) (\d{4}) · (\d{2}):(\d{2})$/.exec(s);
    if (!m) die(where + ': "' + s + '" is not a fire in the house format');
    const dow = DMAP[m[1]], day = +m[2], mon = MMAP[m[3]], year = +m[4], hh = +m[5], mi = +m[6];
    if (dow === undefined) die(where + ': "' + m[1] + '" is not a weekday');
    if (mon === undefined) die(where + ': "' + m[3] + '" is not a month');
    // Check the named weekday really is that date's weekday. Zeller, so no Date
    // and no locale is involved.
    const t = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
    let y = year; if (mon < 2) y -= 1;
    const w = (y + ((y / 4) | 0) - ((y / 100) | 0) + ((y / 400) | 0) + t[mon] + day) % 7; // 0 = Sunday
    const mondayFirst = (w + 6) % 7;
    if (mondayFirst !== dow) {
      die(where + ': ' + s + ' — ' + m[2] + ' ' + m[3] + ' ' + m[4] + ' is a ' +
        DAY_LONG[mondayFirst] + ', not a ' + DAY_LONG[dow]);
    }
    return { sort: year * 1e8 + mon * 1e6 + day * 1e4 + hh * 100 + mi, hh, mi, dow, mon, day, year };
  }

  const nowSort = NOW.y * 1e8 + (NOW.m - 1) * 1e6 + NOW.d * 1e4 + NOW.hh * 100 + NOW.mm;

  STATES.forEach(st => {
    const w = 'state ' + st.key;

    // Every state is one of the brief's seven, and all seven are present.
    if (!st.brief) die(w + ' is not one of the brief’s states');

    // A refused or off schedule has NO preview. A live one has exactly five.
    if (st.invalid || st.mode === 'off') {
      if (st.next.length) die(w + ' is refused or off and must show no runs, not stale ones');
    } else if (st.next.length !== 5) {
      die(w + ' must show five runs (FR-3.8), has ' + st.next.length);
    }

    // A refused state says why and holds Save; a live one has the sentence.
    if (st.invalid && !st.error) die(w + ' is refused with no reason from the server');
    if (st.invalid && !st.saveHeld) die(w + ' is refused but does not hold Save (FR-3.8)');
    if (!st.invalid && !st.says) die(w + ' is valid but has no sentence (FR-3.8)');
    if (st.invalid && st.says) die(w + ' is refused and must not also carry a sentence');

    // The fires are in the future, strictly increasing, and each one lands on
    // the weekday its own string names.
    let prev = nowSort;
    st.next.forEach((f, i) => {
      const p = parseFire(f, w + ' run ' + (i + 1));
      if (p.sort <= nowSort) die(w + ' run ' + (i + 1) + ' (' + f + ') is not after the clock ' + NOW.text);
      if (p.sort <= prev) die(w + ' run ' + (i + 1) + ' (' + f + ') does not come after the run before it');
      prev = p.sort;
    });

    // The chosen time is the time every fire happens at.
    if (st.at && st.next.length) {
      st.next.forEach(f => {
        const p = parseFire(f, w);
        const hhmm = String(p.hh).padStart(2, '0') + ':' + String(p.mi).padStart(2, '0');
        if (hhmm !== st.at) die(w + ': chosen ' + st.at + ' but a run fires at ' + hhmm);
      });
    }

    // Weeks: every fire is one of the weekdays that are toggled on, and every
    // weekday toggled on is named in the sentence.
    if (st.mode === 'weeks') {
      if (!st.days || !st.days.length) die(w + ': weeks with no weekday on (FR-3.2)');
      st.next.forEach(f => {
        const p = parseFire(f, w);
        if (st.days.indexOf(p.dow) < 0) die(w + ': ' + f + ' is a ' + DAY_LONG[p.dow] + ', which is not toggled on');
      });
      st.days.forEach(d => {
        if (st.says.indexOf(DAY_LONG[d]) < 0) die(w + ': ' + DAY_LONG[d] + ' is on but the sentence does not name it');
      });
    }

    // Months by ordinal weekday: every fire is that weekday.
    if (st.mode === 'months' && st.monthShape === 'weekday') {
      st.next.forEach(f => {
        const p = parseFire(f, w);
        if (p.dow !== st.ordDay) die(w + ': ' + f + ' is not a ' + DAY_LONG[st.ordDay]);
      });
      if (st.says.indexOf(ORDINALS[st.ordinal]) < 0) die(w + ': the sentence does not say "' + ORDINALS[st.ordinal] + '"');
    }

    // Years: every fire is the chosen month and day, and the interval holds.
    if (st.mode === 'years') {
      st.next.forEach(f => {
        const p = parseFire(f, w);
        if (p.mon !== st.month || p.day !== st.dayOfMonth) die(w + ': ' + f + ' is not ' + st.dayOfMonth + ' ' + MON_SHORT[st.month]);
      });
      for (let i = 1; i < st.next.length; i++) {
        const a = parseFire(st.next[i - 1], w), b = parseFire(st.next[i], w);
        if (b.year - a.year !== st.n) die(w + ': ' + (b.year - a.year) + ' years between two runs, not ' + st.n);
      }
      // The anchor and the interval have to agree with the years shown.
      const anchorYear = +st.from.slice(0, 4);
      st.next.forEach(f => {
        const p = parseFire(f, w);
        if ((p.year - anchorYear) % st.n !== 0) die(w + ': ' + p.year + ' is not ' + st.n + ' years from the anchor ' + anchorYear);
      });
    }

    // The fortnight case the spec names explicitly: 18 Nov and 2 Dec, NOT 25 Nov.
    if (st.key === 'fortnight') {
      if (st.next.join('|').indexOf('25 Nov') >= 0) die('the fortnight state fires on 25 Nov, which is the week it must skip');
      for (let i = 1; i < st.next.length; i++) {
        const a = parseFire(st.next[i - 1], w), b = parseFire(st.next[i], w);
        const days = (b.sort - a.sort) / 1e4;  // same time of day throughout, checked above
        if (Math.round(days) !== 14 && b.year === a.year && b.mon === a.mon) die('the fortnight state has runs ' + days + ' days apart');
      }
    }

    // An N above 1 on days/weeks/months/years shows "starting" (FR-3.6); at
    // N = 1 there is no anchor to show.
    const anchored = ['days', 'weeks', 'months', 'years'].indexOf(st.mode) >= 0;
    if (anchored && st.n > 1 && !st.from) die(w + ': every ' + st.n + ' ' + st.mode + ' with no starting date (FR-3.6)');
    if (anchored && st.n === 1 && st.from) die(w + ': every 1 ' + st.mode + ' must not show a starting date');

    // The sentence names the zone wherever there is a time in it (FR-4.2).
    const zone = st.zone || ZONE;
    if (st.says && st.at && st.says.indexOf(zone) < 0) die(w + ': the sentence gives a time without naming the zone');

    // FR-1.2 — cron is written whenever the choice can be said as a cron, and
    // the recurrence form only for what cron cannot say.
    if (st.stored) {
      const isRec = st.stored.indexOf('every:') === 0;
      const needsRec = (anchored && st.n > 1) ||
        (st.mode === 'months' && st.monthShape === 'weekday') ||
        st.monthDay === 'last';
      if (needsRec && !isRec) die(w + ': needs the recurrence form and is stored as a cron');
      if (!needsRec && isRec) die(w + ': is stored as a recurrence but cron can say it (FR-1.2)');
    }
  });

  // All seven of the brief's states are drawn.
  for (let b = 1; b <= 7; b++) {
    if (!STATES.some(s => s.brief === b)) die('the brief’s state ' + b + ' is not drawn');
  }

  // The unit menu's shape: Off first, Custom (cron) last (FR-3.1, FR-3.10).
  if (UNITS[0].v !== 'off') die('Off is not the first entry in the unit menu');
  if (UNITS[UNITS.length - 1].v !== 'cron') die('Custom (cron) is not the last entry in the unit menu');

  // The longest unit label really is the longest, or the select is sized wrong.
  UNITS.forEach(u => {
    if (u.w.length > LONGEST.unit.length) die('"' + u.w + '" is longer than the unit the width was set from');
  });

  // Exactly one shape is recommended.
  if (SHAPES.filter(s => s.verdict === 'kept').length !== 1) die('the round must keep exactly one shape');

  // No library title, no host but example.*/localhost, no IP literal. The
  // fence reads the tree; this reads the data the pictures are built from.
  const blob = JSON.stringify({ STATES, HOMES, LONGEST, SHAPES, FEATURES, UNITS });
  [/\bhttps?:\/\//, /\b\d{1,3}(\.\d{1,3}){3}\b/, /api_key|passkey|announce|token=/i].forEach(re => {
    if (re.test(blob)) die('the data carries something that must never land: ' + re);
  });
  (blob.match(/[a-z0-9-]+\.(net|com|org|tv|io)\b/g) || []).forEach(h => {
    if (h !== 'example.net' && h !== 'example.com') die('host "' + h + '" is not a stand-in host');
  });

  window.SPD = {
    NOW, ZONE, DAY_SHORT, DAY_TOGGLE, DAY_LONG, MON_SHORT, MON_LONG,
    UNITS, ORDINALS, HOMES, FEATURES, STATES, MENU_OPEN, LONGEST, SHAPES,
    state: k => { const s = STATES.filter(s => s.key === k)[0]; if (!s) die('no state "' + k + '"'); return s; },
  };
})();
