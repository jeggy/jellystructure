/* nf-data.js — round `newest-first`, 2026-10-29.

   ONE array per fact. Every date, code, count, duration, percentage and sentence in every picture
   is derived from this file, so "do these pictures agree with each other?" is something a reviewer
   can check rather than trust (PROCESS.md, *Derive every number in every picture from one array*).
   It is also the honest model of the product, which renders server-pushed state and invents none
   of its own.

   STAND-IN CORPUS ONLY, and only the names the brief fixed:
     · `Dagbók`, the daily evening programme, cleared as a stand-in in the Auto-fetch spec
       (specs/requirements/auto-fetch-…md, the STAND-IN section, added 2026-10-27, DEB-2626).
       Its episode headings are the page's own generic form, `Dagbók · 29. oktober`.
     · Anna, the viewer.
     · Home's other Continue tiles are the ones the shipped Home mock already draws
       (design/ravilo/ravilo-data.js, `continueItems`), so Dagbók's tile is compared against the
       real row rather than against invented neighbours.
   No real programme, broadcaster or still appears anywhere, and no still is a photograph: every
   one is a two-stop oklch gradient minted from the episode's own day of the year.

   TRAPS THIS FILE EXISTS TO AVOID
   · Chromium has no Faroese locale and `toLocaleDateString` falls back to English in silence
     (PROCESS.md). Nothing here is formatted by the browser: weekday and month names are tabled
     per language, LIFTED FROM THE SHIPPED i18n FILES, and `fmt()` composes them with the shipped
     `date.weekday_day_month` pattern.
   · FR-4.5's identity — season = the published date's year, episode = its day of the year — is
     COMPUTED by `doy()`. `S2026E302` in a picture is this function's answer or it is a defect.
*/

(function (W) {
  'use strict';

  const pad = (n) => String(n).padStart(2, '0');

  /* ===========================================================================================
     STRINGS. Every one of these is lifted verbatim out of the shipped i18n/{en,fo,da}.json, with
     its key named. SKILL-house.md: "the shipped i18n/*.json wins wherever a string already
     exists". The round's own NEW strings are in NEW below, separated so the report can list them
     flat and so nobody has to guess which is which.
     =========================================================================================== */

  /* wd.abbr.* — a three-letter weekday. Indexed Sun..Sat to match Date#getUTCDay(). */
  const WD_ABBR = {
    en: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'],
    fo: ['Sun', 'Mán', 'Týs', 'Mik', 'Hós', 'Frí', 'Ley'],
    da: ['Søn', 'Man', 'Tir', 'Ons', 'Tor', 'Fre', 'Lør'],
  };
  /* wd.* — the full weekday, for an accessible label rather than a drawn one. */
  const WD_FULL = {
    en: ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'],
    fo: ['sunnudag', 'mánadag', 'týsdag', 'mikudag', 'hósdag', 'fríggjadag', 'leygardag'],
    da: ['søndag', 'mandag', 'tirsdag', 'onsdag', 'torsdag', 'fredag', 'lørdag'],
  };
  /* month.* — a three-letter month, January first. */
  const MON_ABBR = {
    en: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
    fo: ['Jan', 'Feb', 'Mar', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Des'],
    da: ['Jan', 'Feb', 'Mar', 'Apr', 'Maj', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dec'],
  };
  /* about.month.N — the month written out, which the programme's OWN heading uses (`29. oktober`).
     Only Faroese is ever reached by this corpus, because the heading is the page's and the page is
     Faroese whatever Ravilo is set to — that is the point of FR-15.4 and it is why this round does
     NOT translate an episode title. */
  const MON_FULL_FO = ['januar', 'februar', 'mars', 'apríl', 'mai', 'juni',
                       'juli', 'august', 'september', 'oktober', 'november', 'desember'];

  /* date.weekday_day_month — "{weekday} {day} {month}" in English, "{weekday} {day}. {month}" in
     both Nordic drafts. The brief's label `Thu 29 Oct` is this pattern and nothing new. */
  const DATE_PAT = { en: '{weekday} {day} {month}', fo: '{weekday} {day}. {month}', da: '{weekday} {day}. {month}' };

  const SHIPPED = {
    /* key                     en                fo                     da */
    play:        { k: 'action.play',          en: 'Play',             fo: 'Spæl',              da: 'Afspil' },
    resume:      { k: 'action.resume',        en: 'Resume',           fo: 'Halt fram',         da: 'Fortsæt' },
    episodes:    { k: 'detail.episodes',      en: 'Episodes',         fo: 'Partar',            da: 'Episoder' },
    season:      { k: 'detail.season',        en: 'Season',           fo: 'Sesong',            da: 'Sæson' },
    newest:      { k: 'browse.sort.newest',   en: 'Newest first',     fo: 'Nýggjast fyrst',    da: 'Nyeste først' },
    oldest:      { k: 'browse.sort.oldest',   en: 'Oldest first',     fo: 'Elst fyrst',        da: 'Ældste først' },
    sort:        { k: 'browse.sort',          en: 'Sort',             fo: 'Raða',              da: 'Sortér' },
    newEpisode:  { k: 'up.new_episode',       en: 'New Episode',      fo: 'Nýggjur partur',    da: 'Ny episode' },
    badgeNew:    { k: 'tile.badge_new',       en: 'NEW',              fo: 'NÝTT',              da: 'NY' },
    watched:     { k: 'action.watched',       en: 'Watched',          fo: 'Sætt',              da: 'Set' },
    shuffle:     { k: 'detail.shuffle',       en: 'Shuffle',          fo: 'Blanda',            da: 'Bland' },
    upNext:      { k: 'player.up_next',       en: 'UP NEXT',          fo: 'NÆSTI',             da: 'NÆSTE' },
    contRow:     { k: 'row.continue',         en: 'Continue Watching', fo: 'Halt fram at síggja', da: 'Fortsæt med at se' },
    airDate:     { k: 'up.air_date',          en: 'Air date',         fo: 'Sendidagur',        da: 'Sendedato' },
    playIn:      { k: 'player.play_in',       en: 'Play in {secs}s',  fo: 'Spæl um {secs}s',   da: 'Afspil om {secs}s' },
    watchCredits:{ k: 'player.watch_credits', en: 'Watch credits',    fo: 'Síggj eftirtekstir', da: 'Se rulletekster' },
    /* "{watched} of {total} episodes watched" — drawn ONLY on the `today` reference frame and on
       option C, because option A removes it. Kept here so the two pictures that do draw it cannot
       word it differently from the shipped page. */
    epsWatched:  { k: 'detail.episodes_watched', en: '{w} of {t} episodes watched',
                   fo: '{w} av {t} pørtum sæddir', da: '{w} af {t} afsnit set' },
  };

  /* ===========================================================================================
     THE ROUND'S NEW STRINGS. Five, and each one is here because no shipped key says it.
     The report lists these flat with the reason, which is the form SKILL-house.md asks for.
     =========================================================================================== */
  const NEW = {
    /* The primary button on a newest-first series. `action.play` alone would read as today's
       button and this one deliberately does not do what today's does. */
    playNewest:  { k: 'detail.play_newest',   en: 'Play newest',      fo: 'Spæl nýggjasta',    da: 'Afspil nyeste' },
    /* The hero's state line in s4. `detail.all_watched` ("All 12 episodes watched") is the WRONG
       sentence for a newest-first series: tomorrow there are twelve different episodes, so "all
       12" is a count that expires overnight. "Caught up" is a statement about the viewer. */
    caughtUp:    { k: 'detail.caught_up',     en: 'Caught up',        fo: 'Dagført',           da: 'Ajour' },
    /* The hero's mark. The DRAWN words are `browse.sort.newest` verbatim; this key is only the
       accessible label, which has to say that it is a property of the series and not a control. */
    newestA11y:  { k: 'detail.newest_first_a11y', en: 'Newest first — this series lists its newest episode first',
                   fo: 'Nýggjast fyrst — henda røðin vísir nýggjasta partin fyrst',
                   da: 'Nyeste først — denne serie viser det nyeste afsnit først' },
    /* The Continue tile in s2, and the accessible label behind the NEW ribbon. */
    newOn:       { k: 'tile.new_on',          en: 'New · {date}',     fo: 'Nýtt · {date}',     da: 'Ny · {date}' },
    /* The admin's provenance line. English only — the admin has no string table (DEB-2628 FR-13). */
    setByFetch:  { k: null,                   en: 'Set by Auto-fetch: numbered by air date' },
    orderLabel:  { k: null,                   en: 'Episode order' },
  };

  /* ===========================================================================================
     DATES AND IDENTITY
     =========================================================================================== */

  /* FR-4.5: season = the published date's year, episode = its day of the year. 1–366, computed. */
  function doy(iso) {
    const t = new Date(iso + 'T00:00:00Z');
    const jan1 = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
    return Math.round((t - jan1) / 86400000) + 1;
  }
  const se = (iso) => 'S' + iso.slice(0, 4) + 'E' + pad(doy(iso));
  const dow = (iso) => new Date(iso + 'T00:00:00Z').getUTCDay();

  /* `date.weekday_day_month` composed from the tabled names. NEVER the browser's formatter. */
  function fmt(iso, lang) {
    const L = lang || 'en';
    return DATE_PAT[L]
      .replace('{weekday}', WD_ABBR[L][dow(iso)])
      .replace('{day}', String(Number(iso.slice(8, 10))))
      .replace('{month}', MON_ABBR[L][Number(iso.slice(5, 7)) - 1]);
  }
  /* The long form, for an accessible label and for the longest-string frame. */
  function fmtLong(iso, lang) {
    const L = lang || 'en';
    return DATE_PAT[L]
      .replace('{weekday}', WD_FULL[L][dow(iso)])
      .replace('{day}', String(Number(iso.slice(8, 10))))
      .replace('{month}', MON_ABBR[L][Number(iso.slice(5, 7)) - 1]);
  }
  /* The programme's OWN heading — Faroese, month written out, whatever Ravilo's language is. */
  const heading = (iso) => 'Dagbók · ' + Number(iso.slice(8, 10)) + '. ' + MON_FULL_FO[Number(iso.slice(5, 7)) - 1];

  const days = (fromIso, n) => {
    const out = [];
    const t = new Date(fromIso + 'T00:00:00Z');
    for (let i = 0; i < n; i++) { out.push(t.toISOString().slice(0, 10)); t.setUTCDate(t.getUTCDate() + 1); }
    return out;
  };

  /* ===========================================================================================
     THE PROGRAMME. The brief fixes all of it: 12 kept episodes, one season (Season 2026), 28 min
     each, 2026-10-18 → 2026-10-29, which FR-4.5 numbers S2026E291 → S2026E302. Both ends are
     checked by `doy` at the bottom of this file rather than trusted.
     =========================================================================================== */
  const FIRST = '2026-10-18';
  const KEPT = 12;
  const RUNTIME_MIN = 28;
  const TODAY = '2026-10-29';           /* Thu 29 Oct 2026, 21:40 */
  const CLOCK = '21:40';
  const VIEWER = 'Anna';
  const SEASON = 2026;

  /* A still, minted from the episode's own day of the year: deterministic, so two frames of the
     same episode are the same picture, and no two neighbours in the rail collide. Deliberately a
     gradient and not a photograph — this repository is public. */
  const still = (iso) => {
    const h = (28 + doy(iso) * 31) % 360;
    return 'linear-gradient(140deg,oklch(.40 .085 ' + h + '),oklch(.63 .105 ' + ((h + 44) % 360) + '))';
  };

  /* oldest first, which is how the library holds them; every frame orders its own copy */
  const EPISODES = days(FIRST, KEPT).map((iso) => ({
    iso,
    se: se(iso),                        /* S2026E291 … S2026E302, computed */
    doy: doy(iso),
    title: heading(iso),                /* the page's own heading, FR-15.4 */
    mins: RUNTIME_MIN,
    secs: RUNTIME_MIN * 60,
    still: still(iso),
  }));

  const newestIso = EPISODES[EPISODES.length - 1].iso;
  const prevIso = EPISODES[EPISODES.length - 2].iso;

  /* ===========================================================================================
     THE FOUR VIEWER STATES, as the brief names them. Each returns, per episode iso, what the
     SERVER would have pushed: watched, and a resume position in seconds. Nothing here is derived
     by a client from the dates — the constitution excerpt in the brief forbids exactly that, and
     a data file that modelled it the other way would be drawing a product we are not allowed to
     build.
     =========================================================================================== */
  const PROGRESS = { s3: { iso: newestIso, secs: 12 * 60 + 40 } };   /* 12:40 of 28:00 */

  function progressOf(state) {
    const m = {};
    EPISODES.forEach((e) => { m[e.iso] = { watched: false, secs: 0 }; });
    if (state === 's1') return m;                                  /* nothing watched */
    if (state === 's2') {                                          /* to the end up to Wed 28 Oct */
      EPISODES.forEach((e) => { if (e.iso <= prevIso) m[e.iso] = { watched: true, secs: e.secs }; });
      return m;
    }
    if (state === 's3') {                                          /* …and today part-watched */
      EPISODES.forEach((e) => { if (e.iso <= prevIso) m[e.iso] = { watched: true, secs: e.secs }; });
      m[PROGRESS.s3.iso] = { watched: false, secs: PROGRESS.s3.secs };
      return m;
    }
    if (state === 's4') {                                          /* today watched to the end */
      EPISODES.forEach((e) => { m[e.iso] = { watched: true, secs: e.secs }; });
      return m;
    }
    throw new Error('unknown viewer state ' + state);
  }

  /* NOT DRAWN IN ANY FRAME. Every picture sits on one of the brief's four named states, including
     option C and the reference frame, which the brief puts in s1. This baseline — the three
     watched behind the brief's *3 of 12 watched* sentence about today's page — is kept because it
     is the only state in which today's Play rule lands in the MIDDLE of the season, so it is what
     proves `playToday` picks the oldest unwatched rather than simply the first episode. It backs
     two self-checks below and nothing else. */
  function progressToday() {
    const m = {};
    EPISODES.forEach((e, i) => { m[e.iso] = { watched: i < 3, secs: i < 3 ? e.secs : 0 }; });
    return m;
  }

  const watchedCount = (prog) => EPISODES.filter((e) => prog[e.iso].watched).length;

  /* ===========================================================================================
     WHAT PLAY DOES. Three rules, one function each, so the button's label in a picture is a rule's
     answer and never a typed string. This is the whole of decision 2 and half of decision 1.
     =========================================================================================== */

  /* Option A, the lean: the newest episode, watched or not. Resume if it is part-watched. */
  function playA(prog) {
    const p = prog[newestIso];
    if (p.secs > 0 && !p.watched) return { kind: 'resume', iso: newestIso };
    return { kind: 'playNewest', iso: newestIso };
  }
  /* Option A, the alternative inside decision 2: the newest UNWATCHED. Not drawn as its own frame
     — `newest-a-tv-s4` covers the difference, because s4 is the one state where the two rules
     disagree, and in s4 this rule has no episode to name at all. Kept here because the report has
     to say what the alternative WOULD do, and a sentence in a report that is not in the data file
     is a sentence nobody can check. */
  function playNewestUnwatched(prog) {
    /* `!watched` is the whole test. An episode watched to the END carries secs === e.secs, so a
       predicate of `!watched || secs > 0` matches every finished episode and makes this rule look
       as if it still has something to play in s4. It does not, and that is the point of s4. */
    const cand = EPISODES.slice().reverse().find((e) => !prog[e.iso].watched);
    return cand ? { kind: prog[cand.iso].secs > 0 ? 'resume' : 'playNewest', iso: cand.iso } : null;
  }
  /* Today's rule, which option C keeps: the oldest unwatched, or the one in progress. This is
       what makes C cheap and what makes it not answer the owner's question. */
  function playToday(prog) {
    const inProg = EPISODES.find((e) => !prog[e.iso].watched && prog[e.iso].secs > 0);
    if (inProg) return { kind: 'resume', iso: inProg.iso };
    const next = EPISODES.find((e) => !prog[e.iso].watched);
    return next ? { kind: 'play', iso: next.iso } : { kind: 'startOver', iso: EPISODES[0].iso };
  }

  /* ===========================================================================================
     HOME'S CONTINUE ROW. The other tiles are the shipped Home mock's, named here rather than
     re-invented (design/ravilo/ravilo-data.js, `continueItems`), with Dagbók inserted where a
     most-recently-touched row would put it: first in h1 and h2, absent in h3.
     =========================================================================================== */
  const OTHER_TILES = [
    { title: 'Big Buck Bunny',  sub: '4 min left',            pct: 68, h: 42 },
    { title: 'Nordvest',        sub: 'S01E04 · 22 min left',  pct: 62, h: 224 },
    { title: 'Havets Hjarta',   sub: 'S02E01 · Next up',      pct: 0,  h: 196 },
    { title: 'Cosmos Laundromat', sub: '12 min left',         pct: 78, h: 268 },
    { title: 'Fjollerne i Nord', sub: 'S03E07 · 18 min left', pct: 41, h: 318 },
    { title: 'Havets Folk',     sub: '34 min left',           pct: 25, h: 150 },
  ];

  /* ===========================================================================================
     SELF-CHECK. The file REFUSES TO LOAD if one of the brief's own numbers has drifted, so a
     picture cannot quietly disagree with the brief. Every one of these is a number the brief
     states and this file computes by a different road.
     =========================================================================================== */
  const fails = [];
  const must = (cond, msg) => { if (!cond) fails.push(msg); };

  must(EPISODES.length === 12, 'the kept set is ' + EPISODES.length + ' episodes, not 12');
  must(EPISODES[0].se === 'S2026E291', 'the oldest is ' + EPISODES[0].se + ', not S2026E291');
  must(EPISODES[11].se === 'S2026E302', 'the newest is ' + EPISODES[11].se + ', not S2026E302');
  must(EPISODES[0].iso === '2026-10-18', 'the oldest airs ' + EPISODES[0].iso + ', not 2026-10-18');
  must(EPISODES[11].iso === TODAY, 'the newest airs ' + EPISODES[11].iso + ', not ' + TODAY);
  must(fmt(EPISODES[0].iso, 'en') === 'Sun 18 Oct', 'the oldest reads ' + fmt(EPISODES[0].iso, 'en') + ', not Sun 18 Oct');
  must(fmt(TODAY, 'en') === 'Thu 29 Oct', 'today reads ' + fmt(TODAY, 'en') + ', not Thu 29 Oct');
  must(EPISODES[11].title === 'Dagbók · 29. oktober', 'today’s heading is ' + EPISODES[11].title);
  must(EPISODES.every((e) => e.mins === 28), 'not every episode is 28 min');
  must(watchedCount(progressOf('s1')) === 0, 's1 has ' + watchedCount(progressOf('s1')) + ' watched, not 0');
  must(watchedCount(progressOf('s2')) === 11, 's2 has ' + watchedCount(progressOf('s2')) + ' watched, not 11');
  must(watchedCount(progressOf('s3')) === 11, 's3 has ' + watchedCount(progressOf('s3')) + ' watched, not 11');
  must(watchedCount(progressOf('s4')) === 12, 's4 has ' + watchedCount(progressOf('s4')) + ' watched, not 12');
  must(watchedCount(progressToday()) === 3, 'the today baseline is not 3 of 12 watched');
  must(progressOf('s3')[newestIso].secs === 760, 's3 resumes at ' + progressOf('s3')[newestIso].secs + 's, not 12:40');
  /* the four Play rules, at the states where they differ — this is decision 2 drawn as arithmetic */
  must(playA(progressOf('s1')).iso === TODAY, 'A in s1 does not play the newest');
  must(playA(progressOf('s3')).kind === 'resume', 'A in s3 is not a Resume');
  must(playA(progressOf('s4')).iso === TODAY, 'A in s4 does not play the newest');
  must(playNewestUnwatched(progressOf('s4')) === null, 'the alternative has something to play in s4');
  must(playToday(progressOf('s1')).iso === EPISODES[0].iso, 'today’s rule in s1 does not play the oldest');
  must(playToday(progressToday()).iso === EPISODES[3].iso, 'today’s rule on the baseline is not the 4th episode');
  /* the three languages are complete wherever a drawn string has one */
  ['en', 'fo', 'da'].forEach((L) => {
    must(WD_ABBR[L].length === 7 && MON_ABBR[L].length === 12, L + ' is missing a weekday or month');
    Object.keys(SHIPPED).forEach((k) => must(!!SHIPPED[k][L], 'shipped string ' + k + ' has no ' + L));
  });

  if (fails.length) {
    const msg = 'nf-data.js refuses to load — ' + fails.length + ' disagreement(s) with the brief:\n · ' + fails.join('\n · ');
    document.addEventListener('DOMContentLoaded', () => {
      document.body.innerHTML = '<pre style="font:14px/1.6 monospace;color:#b00;padding:40px;white-space:pre-wrap">' + msg + '</pre>';
    });
    throw new Error(msg);
  }

  W.NF = {
    EPISODES, KEPT, SEASON, TODAY, CLOCK, VIEWER, RUNTIME_MIN,
    newestIso, prevIso, OTHER_TILES, PROGRESS,
    SHIPPED, NEW, WD_ABBR, WD_FULL, MON_ABBR,
    doy, se, fmt, fmtLong, heading, still,
    progressOf, progressToday, watchedCount,
    playA, playNewestUnwatched, playToday,
    /* a shipped string in the frame's language; `t('newest','fo')` → 'Nýggjast fyrst' */
    t: (key, lang) => (SHIPPED[key] || NEW[key])[lang || 'en'] || (SHIPPED[key] || NEW[key]).en,
  };
})(window);
