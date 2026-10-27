/* af-keep-build.js — round `auto-fetch-retention`, 2026-10-27.

   The frame builders. Every count, code, date and host comes out of af-keep-data.js and nothing
   is typed twice. The markup is the shipped page's wherever the shipped page draws it
   (`design/app/ai.html`, `ai.js`, and the `af-*` components promoted into `design/app/ai.css` on
   2026-10-25), so a frame reads as the real screen and a diff against the shipped files shows
   only what this round adds.

   What this round adds, all of it `af-*` so it promotes into `ai.css` as it stands:
     .af-keep / .af-num   the Keep control: a two-option seg with the number inside its option
     .af-opt              a tick with a label beside it, over the shipped .af-tick glyph
     .af-prev             the delete preview line — the warning FR-16.7 says replaces a dialog
     .af-err              a refused field: its ring, and the sentence under it
     .af-sub              the per-deletion and guard lines under a run line
     .af-tbl .sched .keep the list row's retention note, under the schedule
     .af-echo.page        the two-line echo a page-only fetcher needs (name, image, description)
     .af-map .se .d       the date beside the code in the Becomes column
     .af-strings          a canvas-only three-up for the longest-string check */

(function (W) {
  'use strict';
  const D = W.AF;
  const host = (f) => D.SITES[f.site].host;
  const src = (f) => host(f) + f.path;

  /* ============================================================ the Keep control (FR-16.1)

     One component, three use sites — the review state's settings box (300 px), the open row's
     config row and the longest-string frame — because it is one control and a second copy of it
     is how two screens start disagreeing.

     The number lives INSIDE the option it belongs to, so *The newest 12* reads as the spec words
     it, and so that choosing the option does not reflow the box. With *All episodes* chosen the
     number is still drawn, as dimmed text at the same width: it is not a control that cannot act
     (FR-11) — it is the other option's own value, shown so the default is visible before you
     choose it, and nothing moves when you do.

     opts: { mode:'all'|'newest', n, onPage, leaves, previewN, lang, note } */
  function keepControl(opts) {
    const o = opts || {};
    const L = o.lang === 'fo' ? D.fo : o.lang === 'da' ? D.da : D.en;
    const newest = o.mode === 'newest';
    const hint = newest
      ? L('af.keep.hint', { n: o.n, m: o.onPage })
      : L('af.keep.hint_all');
    const prev = !newest || !o.previewN ? '' :
      '<div class="af-prev"><span class="bi">!</span><span>' +
      (o.previewN === 1 ? L('af.keep.preview_one') : L('af.keep.preview', { n: o.previewN })) +
      '</span></div>';
    return '<div class="field af-keepf"><label>' + L('af.keep.label') + '</label>' +
      '<div class="af-keep"><span class="seg">' +
        '<span' + (newest ? '' : ' class="on"') + '>' + L('af.keep.all') + '</span>' +
        '<span' + (newest ? ' class="on"' : '') + '>' + L('af.keep.newest') +
          '<input class="af-num" value="' + o.n + '" aria-label="' + L('af.keep.range') + '"></span>' +
      '</span></div>' +
      (newest
        ? '<label class="af-opt"><span class="af-tick' + (o.leaves ? ' on' : '') + '">✓</span>' +
          '<span>' + L('af.keep.leaves') + '</span></label>'
        : '') +
      '<span class="hint">' + hint + '</span>' +
      (o.note ? '<span class="hint">' + o.note + '</span>' : '') +
      prev + '</div>';
  }

  /* Retention only ever removes episodes THIS fetcher placed (FR-16.2), and a fetcher being
     reviewed has placed none. So no review state can preview a deletion, whatever N says, and
     the picture has to say so or it promises something that cannot happen at save. */
  const NOTHING_PLACED_YET =
    'Nothing is deleted when you save: retention only ever removes episodes this fetcher has ' +
    'placed itself, and it has placed none yet.';

  /* ============================================================ the form (FR-15.1, FR-10.6) */
  function form(mode) {
    const pageOnly = mode === 'pageonly';
    const refused = mode === 'refused';
    const f = D.DAGBOK;
    const url = pageOnly || refused ? 'https://' + src(f) : 'https://' + src(D.FETCHERS.royndin);

    const idField = pageOnly
      ? '<div class="field"><label>TMDB id</label>' +
        '<input class="input af-dis" value="" placeholder="—">' +
        '<span class="hint">' + D.en('af.pageonly.hint') + '</span></div>'
      : refused
        ? '<div class="field"><label>TMDB id</label>' +
          '<input class="input af-err" value="" placeholder="—">' +
          '<span class="hint af-err">' + D.en('af.pageonly.refused') + '</span></div>'
        : '<div class="field"><label>TMDB id</label><input class="input" value="9000001">' +
          '<span class="hint">Must resolve to a TV series on TMDB.</span></div>';

    /* the tick sits UNDER the id field (FR-10.6), so it spans the form grid's own row */
    const tick = '<label class="af-opt"><span class="af-tick' + (pageOnly ? ' on' : '') + '">✓</span>' +
      '<span>' + D.en('af.pageonly.tick') + '</span></label>';

    const echo = pageOnly
      ? '<div class="af-echo page"><span class="ph"></span><div>' +
        '<div><b>' + f.page.name + '</b> · from the programme page · 12 items listed · ' +
        'no TMDB id, and none will be asked for</div>' +
        '<div class="src">“' + f.page.desc + '” — ' + f.page.descSrc + '</div></div></div>'
      : '<div class="af-echo"><span class="ph"></span><span>Enter a TMDB id to confirm the series.</span></div>';

    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">New auto-fetcher</h3>' +
      '<span class="badge info" style="margin-left:8px;">series only</span><span class="spacer"></span>' +
      '<span class="tiny muted">one investigation at a time</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 14px;">Films, clips and anything that is not a series are refused on purpose — a wrong guess here would file episodes under the wrong title for ever.</div>' +
      '<div class="af-form">' +
        '<div class="field"><label>Page URL</label><input class="input mono" value="' + url + '">' +
        '<span class="hint">The series’ own page — the one that lists its episodes.</span></div>' +
        idField +
        '<span class="btn primary">⌕ Investigate</span>' +
        tick +
      '</div>' + echo + '</div>';
  }

  /* ============================================================ the review state (FR-10.6) */

  /* the page-only kv block: no TMDB id, no confirmed-series mark, the date proof */
  function kvPageOnly(f, items) {
    return '<div class="af-kv">' +
      '<span class="k">Series</span><span class="v"><b>' + f.name + '</b> · ' +
        '<span class="badge flat">' + D.en('af.pageonly.series') + '</span></span>' +
      '<span class="k">Source</span><span class="v mono" style="font-size:.8rem;">' + host(f) +
        ' · HLS manifest · best variant <b>1080p</b></span>' +
      '<span class="k">Found</span><span class="v"><b>' + items.length + ' episodes</b> dated ' +
        items[0].iso + ' → ' + items[items.length - 1].iso +
        ' — all ' + items.length + ' missing from the library, none already there</span>' +
      '<span class="k">Numbering</span><span class="v">By date. The item’s published date is its ' +
        'identity: its year is the season, its day of the year is the episode.</span>' +
      '<span class="k">Proof</span><span class="v"><span class="af-proof"><span class="mk">✓ proven</span>' +
        '<span class="says">' + D.en('af.pageonly.proof', { n: items.length }) +
        '<span class="means">The listing yields at least two items, each with a link and a date; ' +
        'every item maps to exactly one identity and no two to the same one; one manifest probes; ' +
        'and the page gives the programme a name. There is no TMDB agreement to check, and nothing ' +
        'goes to <i>Not on TMDB yet</i>.</span></span></span></span>' +
      '</div>';
  }

  function kvTmdb(f, rows) {
    const have = rows.filter((r) => r.have).length;
    const notOnTmdb = rows.filter((r) => !r.onTmdb).length;
    return '<div class="af-kv">' +
      '<span class="k">Series</span><span class="v"><b>' + f.name + '</b> · TMDB ' + f.tmdb +
        ' · <span class="af-conf">✓ confirmed series</span></span>' +
      '<span class="k">Source</span><span class="v mono" style="font-size:.8rem;">' + host(f) +
        ' · HLS manifest · best variant <b>1080p</b></span>' +
      '<span class="k">Found</span><span class="v"><b>' + rows.length + ' episodes</b> across ' +
        '2022 · 2025 · 2026 — ' + (rows.length - have) + ' missing from the library, ' + have +
        ' already there' + (notOnTmdb ? ' · <b>' + notOnTmdb + ' not on TMDB yet</b>' : '') + '</span>' +
      '<span class="k">Pattern</span><span class="v">Yearly seasons. The year in the title is the ' +
        'season; “N. partur” or “(N:M)” is the episode.</span>' +
      '<span class="k">Proof</span><span class="v"><span class="af-proof"><span class="mk">✓ proven</span>' +
        '<span class="says">pattern agrees with TMDB on <b>12 of 12</b> episodes TMDB has' +
        '<span class="means">An agreement is a TMDB air date within 7 days of the item’s published ' +
        'date, or a TMDB episode name equal to the item’s title. Proven needs at least two, with ' +
        'none against.</span></span></span></span></div>';
  }

  /* the page-only mapping table: FOUR columns, and no TMDB column at all. At 412 `ai.css` prefixes
     a `td.tmdb` with `· TMDB `, so a page-only table that carried an empty one would print the
     word TMDB on the one screen that must never say it. */
  function mapPageOnly(items) {
    const show = [items[11], items[10], items[0]];
    return '<table class="af-map no-tick"><thead><tr><th>Item on page</th><th class="arr"></th>' +
      '<th>Becomes</th><th class="lib">Library</th></tr></thead><tbody>' +
      show.map((r) => '<tr><td class="item">' + r.item + '</td><td class="arr">→</td>' +
        '<td class="se mono">' + r.se + '<span class="d">· ' + r.iso + '</span></td>' +
        '<td class="lib"><span class="badge warn">missing</span></td></tr>').join('') +
      '<tr><td colspan="4" class="tiny muted" style="padding:8px 10px;">' + (items.length - show.length) +
      ' more · <a href="#">show all ' + items.length + ' items</a></td></tr></tbody></table>';
  }

  function mapTmdb(rows) {
    const show = [rows[12], rows[11], rows[0]];
    return '<table class="af-map no-tick"><thead><tr><th>Title on page</th><th class="arr"></th>' +
      '<th>Becomes</th><th>TMDB has</th><th class="lib">Library</th></tr></thead><tbody>' +
      show.map((r) => '<tr' + (r.have ? ' class="have"' : '') + '><td class="item">' + r.item + '</td>' +
        '<td class="arr">→</td><td class="se mono">' + r.se + '</td>' +
        '<td class="tmdb mono">' + (r.onTmdb ? 'aired ' + r.pub : '— no such episode') + '</td>' +
        '<td class="lib">' + (r.have ? '<span class="badge ok">have · skipped</span>'
          : '<span class="badge warn">missing</span>' +
            (r.onTmdb ? '' : ' <span class="badge flat">not on TMDB</span>')) + '</td></tr>').join('') +
      '<tr><td colspan="5" class="tiny muted" style="padding:8px 10px;">' + (rows.length - show.length) +
      ' more · <a href="#">show all ' + rows.length + ' mappings</a></td></tr></tbody></table>';
  }

  /* the settings box, with Keep between Check and Run the first time (FR-10.6) */
  function saveBox(f, keep, nameHint) {
    return '<div class="box flat" style="padding:14px 15px;align-self:start;">' +
      '<div class="field"><label>Name</label><input class="input" value="' + f.name + '">' +
      (nameHint ? '<span class="hint">' + nameHint + '</span>' : '') + '</div>' +
      '<div class="field"><label>Check for new episodes</label><span class="select"><span>' + f.sched +
      '</span></span><span class="hint">Re-running is always safe — a run only downloads what the ' +
      'library is missing.</span></div>' +
      keepControl(keep) +
      '<div class="field"><label>Run the first time</label><span class="seg"><span class="on">Now</span>' +
      '<span>At next check</span></span></div>' +
      '<div class="row center" style="gap:8px;margin-top:4px;flex-wrap:wrap;">' +
      '<span class="btn primary">Save auto-fetcher</span><span class="btn sm ghost">Discard</span>' +
      '</div></div>';
  }

  function review(which, keepMode) {
    const pageOnly = which === 'dagbok';
    const f = pageOnly ? D.DAGBOK : D.FETCHERS.royndin;
    const left = pageOnly
      ? kvPageOnly(f, D.DAG_ITEMS) + mapPageOnly(D.DAG_ITEMS)
      : kvTmdb(f, D.ROY) + mapTmdb(D.ROY);
    const keep = pageOnly
      ? { mode: 'newest', n: 12, onPage: D.DAG_ITEMS.length, leaves: true, note: NOTHING_PLACED_YET }
      : keepMode === 'newest'
        ? { mode: 'newest', n: 12, onPage: D.ROY_FOUND, leaves: false, note: NOTHING_PLACED_YET }
        : { mode: 'all', n: 12, onPage: D.ROY_FOUND, leaves: false };
    const echo = pageOnly
      ? '<div class="af-echo page" style="margin-top:12px;"><span class="ph"></span><div>' +
        '<div><b>' + f.page.name + '</b> · from the programme page · ' + D.DAG_ITEMS.length +
        ' items listed · no TMDB id</div><div class="src">“' + f.page.desc + '” — ' +
        f.page.descSrc + '</div></div></div>'
      : '<div class="af-echo" style="margin-top:12px;"><span class="ph"></span><span><b>Royndin</b> · ' +
        'TV series · 3 seasons listed on TMDB · Faroese · <a href="series.html">not in library yet</a>' +
        '</span></div>';
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">New auto-fetcher</h3>' +
      '<span class="badge info" style="margin-left:8px;">series only</span><span class="spacer"></span>' +
      '<span class="tiny muted">found · review</span></div>' + echo +
      '<div class="af-review"><div>' + left + '</div>' +
      saveBox(f, keep, pageOnly ? 'From the programme page. What you save becomes the series folder’s ' +
        'name and the title in its <span class="mono">tvshow.nfo</span>.' : '') +
      '</div></div>';
  }

  /* ============================================================ the fetchers list (FR-10.6) */
  function statusCell(f, text, when) {
    return '<div class="af-stat"><span class="badge ok">Up to date</span>' +
      '<div class="when">' + text + ' · ' + when + '</div></div>';
  }

  /* `keeps newest 12` sits under the schedule, so the row gains no column (FR-10.2's rule, kept) */
  function schedCell(f) {
    return '<td class="sched">' + f.sched +
      (f.keep.mode === 'newest' ? '<div class="keep">' + D.en('af.list.keeps', { n: f.keep.n }) + '</div>' : '') +
      '</td>';
  }

  function row(f, state, open, inner) {
    return '<tr class="af-row' + (open ? ' open' : '') + '">' +
      '<td class="nm"><b>' + f.name + '</b><div class="src">' + src(f) + '</div></td>' +
      '<td class="pat hide-m">' + f.pat + '</td>' + schedCell(f) +
      '<td class="st">' + state + '</td>' +
      '<td class="q hide-m tiny muted">' + f.q + '</td>' +
      '<td class="tg"><span class="toggle' + (f.on ? ' on' : '') + '" title="Enabled"></span></td>' +
      '<td class="acts-cell"><div class="acts"><span class="btn sm ghost">▶ Run now</span></div></td></tr>' +
      (open ? '<tr class="af-det"><td colspan="7"><div class="af-open"><div class="af-det-in">' +
        inner + '</div></div></td></tr>' : '');
  }

  function listCard(rows) {
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Auto-fetchers</h3>' +
      '<span class="badge" style="margin-left:8px;">' + rows.length + '</span><span class="spacer"></span>' +
      '<span class="tiny muted">next check in 5h 12m</span>' +
      '<span class="btn sm ghost">▶ Run all now</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 10px;">Each one is a stored recipe and a schedule. ' +
      'Click a row for the recipe and its last runs. A run that finds nothing new is the normal ' +
      'outcome, not a failure.</div>' +
      '<table class="af-tbl"><thead><tr><th>Series</th><th class="hide-m">Numbering</th>' +
      '<th>Schedule</th><th>State</th><th class="hide-m">Quality</th><th></th><th></th></tr></thead>' +
      '<tbody>' + rows.join('') + '</tbody></table></div>';
  }

  function fetchersList() {
    const dag = D.DAGBOK, R = D.FETCHERS;
    return listCard([
      row(dag, statusCell(dag, '+1 episode · −1 by retention', '04:00 today')),
      row(R.royndin, statusCell(R.royndin, 'no new episodes', '04:00 today')),
      row(R.nordvest, '<div class="af-stat"><span class="badge ok">Up to date</span>' +
        '<div class="when">+2 episodes · 3h ago</div></div>'),
      row(R.babel, '<div class="af-stat"><span class="badge warn">Needs re-investigation</span>' +
        '<div class="when">page changed · 0 videos found · Mon 04:00</div></div>'),
    ]);
  }

  /* ============================================================ the open row (FR-10.6) */
  function recipe(f) {
    return [
      '<span class="c"># recipe v1 · ' + f.name + ' · page only, no TMDB id · written by the model, read-only here</span>',
      '<span class="k">source</span>    https://' + src(f) + '  <span class="c"># hosts fixed at save; nothing else may be named</span>',
      '<span class="k">list</span>      GET /api/v2/series/{slug}/episodes  → title, link, published',
      '<span class="k">media</span>     GET /api/v2/media/{media_id}/manifest → pick max(height)  <span class="c"># ' + f.q + '</span>',
      '<span class="k">numbering</span> date  <span class="c"># season = the published year, episode = its day of the year</span>',
      '<span class="k">series</span>    the programme page’s own name, description and image  <span class="c"># read at save and on Re-investigate, never on a run</span>',
      '<span class="k">retention</span> newest ' + f.keep.n + ' · also delete once it leaves the page',
      '<span class="k">quality</span>   highest variant, always',
      '<span class="c"># nothing in a recipe is executed. The engine runs it, not the model.</span>',
    ].join('\n');
  }

  /* the config row at the foot of the recipe pane, with Keep editable in place */
  function cfg(f, keep) {
    return '<div class="af-cfg"><div class="field"><label>Name</label>' +
      '<input class="input" value="' + f.name + '" style="width:170px"></div>' +
      '<div class="field"><label>Check</label><span class="select" style="width:150px"><span>' +
      f.sched + '</span></span></div>' + keepControl(keep) +
      '<div class="af-cfg-go"><span class="btn sm">Save</span><span class="spacer"></span>' +
      '<span class="btn sm ghost">↻ Re-investigate page</span>' +
      '<span class="btn sm ghost" style="color:var(--bad)">Delete</span></div></div>';
  }

  /* ------------------------------------------------------------------- the run record (FR-16.6)

     Three runs, and every line in them is the data file's arithmetic.

     The brief asked for one run record holding a +1 line, a −1 line and a guard line. FR-16.5
     rules that out: a run whose listing could not be read in full *applies no retention at all,
     neither rule*, so the run that carries FR-16.7's guard line cannot also carry a `−1`. They
     are two runs, and drawn as two they also explain each other — yesterday's partial read is
     exactly why nothing left the page yesterday, and why today's deletion is a *newest 12 kept*
     one and not a *left the page* one. The report carries this as finding 1. */
  function runRecord(f) {
    const placed = D.se(D.DAG_RUN_PLACES);
    const dropped = D.se(D.DAG_RUN.drop[0]);
    const prev = D.se(D.DAG_PLACED_BEFORE[D.DAG_PLACED_BEFORE.length - 1]);
    const prevIso = D.DAG_PLACED_BEFORE[D.DAG_PLACED_BEFORE.length - 1];
    const before = D.DAG_PLACED_BEFORE[D.DAG_PLACED_BEFORE.length - 2];

    const sub = (kind, text) => '<div class="af-sub ' + kind + '">' + text + '</div>';
    /* a run and its own lines are one record, so the group carries the rule between runs */
    const run = (dot, text, when, subs) =>
      '<div class="af-runx"><div class="af-run"><span class="dot ' + dot + '"></span>' +
      '<span>' + text + '</span><span class="when">' + when + '</span></div>' +
      (subs || '') + '</div>';

    return '<div class="af-h">Last runs</div><div class="af-runs">' +
      run('on', '+1 episode · −1 by retention', 'today 04:00',
        sub('add', '+1 · <span class="mono">' + placed + '</span> (' + D.DAG_RUN_PLACES + ') · 1080p, ' + f.size) +
        sub('del', '−1 · <span class="mono">' + dropped + '</span> (' + D.DAG_RUN.drop[0] + ') · ' +
          D.en('af.run.kept', { n: f.keep.n }))) +
      run('warn', '+1 episode · retention not applied', 'yesterday 04:00',
        sub('add', '+1 · <span class="mono">' + prev + '</span> (' + prevIso + ') · 1080p, ' + f.size) +
        sub('guard', D.en('af.run.guard'))) +
      run('on', '+1 episode', 'Oct 25 04:00',
        sub('add', '+1 · <span class="mono">' + D.se(before) + '</span> (' + before + ') · 1080p, ' + f.size)) +
      '</div>' +
      '<div class="tiny muted" style="margin-top:10px;line-height:1.55">' + D.DAG_LIBRARY.length +
      ' episodes in the library from this fetcher · page only, no TMDB id · full log in ' +
      '<a href="activity.html">Activity</a></div>';
  }

  /* variant: 'rest' | 'lowered' | 'runs'

     `runs` is the run record ON ITS OWN. The first render drew it as a third open row and it came
     out pixel-identical to `rest`: the record is part of the open row and is already in both of
     the other two. A duplicate frame is a frame nobody reads, so this one became the close-up of
     the pane the brief actually asks about, where its lines can be read at 412 as well. */
  function openRow(variant) {
    const f = D.DAGBOK;
    if (variant === 'runs') {
      return '<div class="card"><div class="row center">' +
        '<h3 style="margin:0;font-size:1.1rem;">Dagbók · the run record</h3>' +
        '<span class="badge flat" style="margin-left:8px;">the open row’s right-hand pane</span>' +
        '<span class="spacer"></span><span class="tiny muted">one line per run · one line per ' +
        'deletion and per guard</span></div>' +
        '<div class="tiny muted" style="margin:6px 0 10px;">Drawn on its own so the lines can be ' +
        'read. In the screen it is the right-hand pane of the two frames above, unchanged.</div>' +
        runRecord(f) + '</div>';
    }
    const lowered = variant === 'lowered';
    const keep = {
      mode: 'newest',
      n: lowered ? 8 : f.keep.n,
      onPage: D.DAG_ITEMS.length,
      leaves: true,
      previewN: lowered ? D.DAG_LOWERED.drop.length : 0,
    };
    const inner = '<div><div class="af-h">Recipe</div><pre class="af-script">' + recipe(f) + '</pre>' +
      cfg(f, keep) + '</div><div>' + runRecord(f) + '</div>';
    return listCard([
      row(f, statusCell(f, '+1 episode · −1 by retention', '04:00 today'), true, inner),
      row(D.FETCHERS.royndin, statusCell(D.FETCHERS.royndin, 'no new episodes', '04:00 today')),
    ]);
  }

  /* ============================================================ the Activity entry (FR-11)

     The ENTRY'S WORDS, not Activity's surface: `design/app/activity.html` draws the scan console
     and has no History entry for a fetcher to borrow, so inventing one here would be drawing a
     screen nobody briefed. Shown in the same components the run record uses, so a reader can see
     that the two say the same thing — which is the whole point of the line. */
  function activityEntry() {
    const f = D.DAGBOK;
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Activity ▸ History</h3>' +
      '<span class="badge flat" style="margin-left:8px;">the entry’s words only</span>' +
      '<span class="spacer"></span><span class="tiny muted">Activity’s own surface is not this round’s</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 10px;">FR-11. The deletions are lines under the ' +
      'entry, in the same words as the fetcher’s own run record above — one set of words, two places.</div>' +
      '<div class="af-runs"><div class="af-runx"><div class="af-run"><span class="dot on"></span>' +
      '<span>' + D.en('af.activity', { name: f.name }) + '</span>' +
      '<span class="when">today 04:00</span></div>' +
      '<div class="af-sub add">+1 · <span class="mono">' + D.se(D.DAG_RUN_PLACES) + '</span> (' +
      D.DAG_RUN_PLACES + ') · 1080p, ' + f.size + '</div>' +
      '<div class="af-sub del">−1 · <span class="mono">' + D.se(D.DAG_RUN.drop[0]) + '</span> (' +
      D.DAG_RUN.drop[0] + ') · ' + D.en('af.run.kept', { n: f.keep.n }) +
      '</div></div></div></div>';
  }

  /* ============================================================ the longest-string check
     The Keep field at the width it really has — the 300 px settings box, minus its padding —
     in all three languages. Faroese is the longest of the three and therefore the real test. */
  function strings() {
    const base = { mode: 'newest', n: 12, onPage: D.DAG_ITEMS.length, leaves: true, previewN: 4 };
    const one = (lang, label) =>
      '<div class="af-strings-col"><div class="af-lang">' + label + '</div>' +
      '<div class="box flat" style="padding:14px 15px;">' +
      keepControl(Object.assign({}, base, { lang: lang })) + '</div></div>';
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Keep · the three languages</h3>' +
      '<span class="badge" style="margin-left:8px;">drafts</span><span class="spacer"></span>' +
      '<span class="tiny muted">the box is 300 px wide on the review state · Faroese is the longest</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 12px;">English is what the other frames draw. The ' +
      'Faroese and Danish are drafts for a speaker; the shipped <span class="mono">i18n/*.json</span> ' +
      'wins wherever a string already exists there.</div>' +
      '<div class="af-strings">' + one('en', 'English') + one('fo', 'Faroese — drafts') +
      one('da', 'Danish — drafts') + '</div></div>';
  }

  W.AFB = { host, src, keepControl, form, review, fetchersList, openRow, activityEntry, strings,
            NOTHING_PLACED_YET };
})(window);
