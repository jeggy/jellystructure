/* af-states-build.js — round `auto-fetch-states`, 2026-10-25.

   The frame builders. Every string that is a number, a count, a date or a host comes out of
   af-states-data.js; nothing is typed twice. The markup is round 2's (`design/app/ai.html`,
   `ai.js`) wherever round 2 drew it, so a frame reads as the real page and a diff against round 2
   shows only what this round adds. */

(function (W) {
  'use strict';
  const D = W.AF;

  /* `mono(['text with 403 in it', ['403']])` wraps each named substring in <span class="mono">,
     which is how a code inside plain words is drawn (the brief's lean). */
  function mono(pair) {
    let [s, codes] = pair;
    (codes || []).forEach((c) => { s = s.split(c).join('\u0000' + c + '\u0001'); });
    return s.replace(/\u0000/g, '<span class="mono">').replace(/\u0001/g, '</span>');
  }
  const host = (f) => D.SITES[f.site].host;
  const src = (f) => host(f) + f.path;

  /* ---------------------------------------------------------------- the recipe, read-only.
     Round 2's `script(f)`, with FR-4's words: a recipe is data and nothing in it is executed. */
  function recipe(f) {
    return [
      '<span class="c"># recipe v1 · ' + f.name + ' · tmdb ' + f.tmdb + ' · written by the model, read-only here</span>',
      '<span class="k">source</span>   https://' + src(f) + '  <span class="c"># hosts fixed at save; nothing else may be named</span>',
      '<span class="k">list</span>     GET /api/v2/series/{slug}/episodes  → title, media_id, published',
      '<span class="k">media</span>    GET /api/v2/media/{media_id}/manifest → pick max(height)  <span class="c"># ' + f.q + '</span>',
      '<span class="k">numbering</span> ' + (/^Mapped/.test(f.pat) ? 'mapped  <span class="c"># an explicit item → SxxEyy table, only the rows you accepted</span>' : 'source'),
      '<span class="k">pattern</span>  ' + (/^Mapped/.test(f.pat) ? '—' : f.pat),
      '<span class="k">quality</span>  highest variant, always',
      '<span class="c"># nothing in a recipe is executed. The engine runs it, not the model.</span>',
    ].join('\n');
  }

  function runs(f) {
    /* the run line and the row's badge are the same count, derived — not typed twice */
    const rs = f.waitN
      ? [['wait', f.waitN + ' new item' + (f.waitN === 1 ? '' : 's') + ' found · waiting for a number',
          f.waitWhen || f.runs[0][2]]].concat(f.runs.filter((r) => r[0] !== 'wait'))
      : f.runs;
    return '<div class="af-h">Last runs</div><div class="af-runs">' + rs.map((r) =>
      '<div class="af-run"><span class="dot ' + (r[0] === 'bad' ? 'bad' : r[0] === 'new' ? 'on' : r[0] === 'wait' ? 'wait' : 'ok') + '"></span>' +
      '<span>' + r[1] + '</span><span class="when">' + r[2] + '</span></div>').join('') + '</div>' +
      '<div class="tiny muted" style="margin-top:10px;line-height:1.55">' + f.have +
      ' episodes in the library from this fetcher · TMDB <a href="series.html">' + f.tmdb +
      '</a> · full log in <a href="activity.html">Activity</a></div>';
  }

  /* The config row at the foot of the recipe pane. `blocked` disables what would reach the site. */
  function cfg(f, blocked) {
    const d = blocked ? ' af-dis' : '';
    return '<div class="af-cfg"><div class="field"><label>Name</label><input class="input" value="' + f.name + '" style="width:170px"></div>' +
      '<div class="field"><label>Check</label><span class="select" style="width:150px"><span>' + f.sched + '</span></span></div>' +
      '<span class="btn sm">Save</span><span class="spacer"></span>' +
      '<span class="btn sm ghost' + d + '">↻ Re-investigate page</span>' +
      '<span class="btn sm ghost" style="color:var(--bad)">Delete</span></div>';
  }

  /* ---------------------------------------------------------------- one status slot (FR-10.2) */
  function statusCell(f, state) {
    if (state === 'stopped') {
      const c = D.CAUSE(f.stopCause || 'c403');
      return '<div class="af-stat"><span class="badge bad">Site stopped</span>' +
        '<div class="when"><span class="mono">' + host(f) + '</span> · ' + mono(c.short) + ' · ' + (f.stopAt || c.at) + '</div></div>';
    }
    if (state === 'waiting') {
      const n = f.waitN || 1;
      return '<div class="af-stat"><span class="badge flat">' + n + ' new item' + (n === 1 ? '' : 's') +
        ' waiting for a number</span><div class="when">nothing downloaded for ' + (n === 1 ? 'it' : 'them') + ' · found ' + (f.waitWhen || '3h ago') + '</div></div>';
    }
    if (state === 'reinvestigate') {
      return '<div class="af-stat"><span class="badge warn">Needs re-investigation</span>' +
        '<div class="when">page changed · 0 videos found · ' + f.runs[0][2] + '</div></div>';
    }
    return '<div class="af-stat"><span class="badge ok">Up to date</span>' +
      '<div class="when">no new episodes · ' + f.runs[0][2] + '</div></div>';
  }

  /* ---------------------------------------------------------------- the fetchers card.
     rows: [{ f, state, open, panel }]. `panel` is HTML put in a band above the open detail. */
  function fetcherCard(rows, opts) {
    opts = opts || {};
    const body = rows.map((r) => {
      const f = r.f, blocked = r.state === 'stopped';
      const runNow = '<span class="btn sm ghost' + (blocked ? ' af-dis' : '') + '">▶ Run now</span>';
      let html = '<tr class="af-row' + (r.open ? ' open' : '') + (blocked ? ' af-stopped' : '') + '">' +
        '<td class="nm"><b>' + f.name + '</b><div class="src">' + src(f) + '</div></td>' +
        '<td class="pat hide-m">' + f.pat + '</td>' +
        '<td class="sched">' + f.sched + '</td>' +
        '<td class="st">' + statusCell(f, r.state) + '</td>' +
        '<td class="q hide-m tiny muted">' + f.q + '</td>' +
        '<td class="tg"><span class="toggle' + (f.on ? ' on' : '') + (blocked ? ' af-dis' : '') + '" title="Enabled"></span></td>' +
        '<td class="acts-cell"><div class="acts">' + runNow + '</div></td></tr>';
      if (r.open) {
        html += '<tr class="af-det"><td colspan="7"><div class="af-open">' + (r.panel || '') +
          '<div class="af-det-in"><div><div class="af-h">Recipe</div><pre class="af-script">' + recipe(f) + '</pre>' +
          cfg(f, blocked) + '</div><div>' + runs(f) + '</div></div></div></td></tr>';
      }
      return html;
    }).join('');

    /* Run all now is never disabled by a stopped site: FR-7.2 queues every enabled fetcher into
       its OWN site's lane, so the fetchers on the sites that still run still have work to do. A
       control disabled while it could still act, with no reason shown, is FR-11 broken twice
       (Bárður, 2026-10-25). The header keeps a next check for the same reason. */
    const next = opts.next === null ? '' : '<span class="tiny muted">' + (opts.next || D.NEXT_ALL) + '</span>';
    return '<div class="card">' +
      '<div class="row center"><h3 style="margin:0;font-size:1.1rem;">Auto-fetchers</h3>' +
      '<span class="badge" style="margin-left:8px;">' + rows.length + '</span><span class="spacer"></span>' +
      next + '<span class="btn sm ghost">▶ Run all now</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 10px;">Each one is a stored recipe and a schedule. Click a row for the recipe and its last runs. A run that finds nothing new is the normal outcome, not a failure.</div>' +
      '<table class="af-tbl"><thead><tr><th>Series</th><th class="hide-m">Numbering</th><th>Schedule</th><th>State</th><th class="hide-m">Quality</th><th></th><th></th></tr></thead>' +
      '<tbody>' + body + '</tbody></table></div>';
  }

  /* ---------------------------------------------------------------- the Site stopped band (§1b–d) */
  function stopBand(f, mode) {
    const c = D.CAUSE(f.stopCause || 'c403');
    const h = host(f);
    const resuming = mode === 'resuming';
    const at = mode === 'refused' ? 'today 16:42' : c.at;
    const resume = resuming
      ? '<span class="btn primary af-dis">Checking the site…</span>'
      : '<span class="btn primary">Resume site</span>';
    return '<div class="af-band' + (resuming ? ' hold' : '') + '">' +
      '<span class="bi">' + (resuming ? '↻' : '!') + '</span>' +
      '<h4>Site stopped · ' + h + '</h4>' +
      '<div class="rsn">' + mono(c.long) + '</div>' +
      '<div class="at">' + at + (mode === 'refused' ? ' · refused again when you resumed it' : '') + '</div>' +
      '<div class="seal">Nothing runs on ' + h + ' until you resume it.</div>' +
      '<div class="af-acts">' + resume +
      '<span class="af-why">' + (resuming
        ? 'The server is making one listing request to ' + h + '. Nothing else runs while it does.'
        : '<b>Run now</b> and <b>Re-investigate page</b> are off for every fetcher on ' + h + ' — both would reach the site.') +
      '</span></div>' +
      '<div class="no-timer">A stop is never retried on a timer: a site that has blocked a client and sees it come back on a schedule usually blocks it for good.' +
      (mode === 'refused' ? ' The one listing request you asked for was refused too, so the site is stopped again.' : '') + '</div>' +
      '</div>';
  }

  /* ---------------------------------------------------------------- the waiting band (§2b–e) */
  function waitBand(items, opts) {
    opts = opts || {};
    const lead = opts.cause === 'unproven'
      ? 'The pattern is <b>not proven</b>, so every episode TMDB does not have waits for your number before it is downloaded.'
      : 'This fetcher uses a <b>mapped</b> number, so a new item on the page is never downloaded on a guess. Give it a season and an episode, or ask for a proposal, and accept it.';
    const body = items.map((it, i) => {
      const proposed = opts.proposed && i === 0;
      const sN = proposed ? it.proposal.se.slice(1, 3).replace(/^0/, '') : '';
      const eN = proposed ? it.proposal.se.slice(4).replace(/^0/, '') : '';
      const accept = proposed
        ? '<span class="btn primary sm">Accept</span>'
        : '<span class="btn primary sm af-dis">Accept</span>';
      const propose = opts.proposeOff
        ? '<span class="btn sm af-dis">✦ Propose</span>'
        : '<span class="btn sm">✦ Propose</span>';
      return '<div class="af-item">' +
        '<div class="who"><b>' + it.item + '</b><span class="since">on the page since ' + it.since + '</span>' +
        '<span class="badge flat">not downloaded</span></div>' +
        (it.desc
          ? '<p class="desc">' + it.desc + '<span class="src">from the page — the item’s description, verbatim</span></p>'
          : '<p class="desc none">No description for this item on the page<span class="src">nothing is invented; the number is yours to give</span></p>') +
        (proposed ? '<div class="af-prop"><span class="lbl">Proposed</span><span class="se">' + it.proposal.se +
          '</span><span>· ' + it.proposal.name + ' ·</span><span class="why">' + it.proposal.why + '</span></div>' : '') +
        '<div class="ctl"><div class="field"><label>Season</label><input class="input mono" value="' + sN + '" placeholder="—"></div>' +
        '<div class="field"><label>Episode</label><input class="input mono" value="' + eN + '" placeholder="—"></div>' +
        '<div class="go">' + propose + accept + '</div></div>' +
        (i > 0 ? ''
          : opts.proposeOff
            ? '<div class="wide af-why"><b>Propose</b> is off: ' + opts.proposeOff + '. The season and episode fields still work — type the number yourself and accept it.</div>'
            : proposed ? ''
              : '<div class="wide af-why"><b>Accept</b> is off until a season and an episode are there.</div>') +
        '</div>';
    }).join('');
    return '<div class="af-wait"><p class="lead">' + lead + '</p>' + body + '</div>';
  }

  /* ---------------------------------------------------------------- review: the kv block + proof */
  function kvBlock(f, rows, p, opts) {
    opts = opts || {};
    const mapped = !!opts.mapped;
    const have = rows.filter((r) => r.have).length;
    const notOnTmdb = rows.filter((r) => !r.onTmdb).length;
    let proofRow = '';
    if (mapped) {
      proofRow = '<span class="k">Proof</span><span class="v"><span class="af-proof"><span class="says">' +
        'Each mapping is proved by the row you tick, not by a pattern — nothing is stored that you did not accept.' +
        '</span></span></span>';
    } else if (p.proven) {
      proofRow = '<span class="k">Proof</span><span class="v"><span class="af-proof">' +
        '<span class="mk">✓ proven</span><span class="says">pattern agrees with TMDB on <b>' + p.okN + ' of ' + p.hasN +
        '</b> episodes TMDB has<span class="means">An agreement is a TMDB air date within 7 days of the item’s published date, or a TMDB episode name equal to the item’s title. Proven needs at least two, with none against.</span></span></span></span>';
    } else {
      proofRow = '<span class="k">Proof</span><span class="v"><span class="af-proof unproven">' +
        '<span class="mk">not proven</span><span class="says">TMDB has <b>' + p.hasN + ' of the ' + p.found +
        '</b> episodes found, so there ' + (p.okN === 1 ? 'is 1 agreement' : 'are ' + p.okN + ' agreements') +
        ' and proven needs two.<span class="means">You can save it as it is. Every episode TMDB does not have then waits for your number before it is downloaded — it is never placed on a guess.</span></span></span></span>';
    }
    return '<div class="af-kv">' +
      '<span class="k">Series</span><span class="v"><b>' + f.name + '</b> · TMDB ' + f.tmdb +
      ' · <span class="af-conf">✓ confirmed series</span></span>' +
      '<span class="k">Source</span><span class="v mono" style="font-size:.8rem;">' + host(f) +
      ' · ' + (f.q.indexOf('HLS') >= 0 ? 'HLS manifest' : 'DASH manifest') + ' · best variant <b>' + f.q.split(' ')[0] + '</b></span>' +
      '<span class="k">Found</span><span class="v"><b>' + rows.length + ' episodes</b>' +
      (opts.span ? ' across ' + opts.span : '') + ' — ' + (rows.length - have) + ' missing from the library, ' +
      have + ' already there' + (notOnTmdb ? ' · <b>' + notOnTmdb + ' not on TMDB yet</b>' : '') + '</span>' +
      '<span class="k">' + (mapped ? 'Numbering' : 'Pattern') + '</span><span class="v">' +
      (mapped
        ? 'Mapped. The page’s order is not an episode number, so every item is given one by hand.'
        : 'Yearly seasons. The year in the title is the season; “N. partur” or “(N:M)” is the episode.') +
      '</span>' + proofRow + '</div>';
  }

  /* the right-hand save box, as round 2 drew it */
  function saveBox(f, opts) {
    opts = opts || {};
    const off = opts.saveOff;
    return '<div class="box flat" style="padding:14px 15px;align-self:start;">' +
      '<div class="field"><label>Name</label><input class="input" value="' + f.name + '"></div>' +
      '<div class="field"><label>Check for new episodes</label><span class="select"><span>' + f.sched +
      '</span></span><span class="hint">Re-running is always safe — a run only downloads what the library is missing.</span></div>' +
      '<div class="field"><label>Run the first time</label><span class="seg"><span class="on">Now</span><span>At next check</span></span></div>' +
      '<div class="row center" style="gap:8px;margin-top:4px;flex-wrap:wrap;">' +
      '<span class="btn primary' + (off ? ' af-dis' : '') + '">Save auto-fetcher</span>' +
      '<span class="btn sm ghost">Discard</span>' +
      (off ? '<div class="af-why" style="flex-basis:100%;margin-top:6px;">' + off + '</div>' : '') +
      '</div></div>';
  }

  /* the mapping table — round 2's `.af-map`, plus a tick column and a why column */
  function mapTable(rows, mode) {
    const kept = rows.filter((r) => r.accepted).length;
    const ticked = mode === 'partly';
    const head = '<thead><tr><th class="tk"></th><th>Item on page</th><th class="arr"></th><th>Becomes</th>' +
      '<th>TMDB has</th><th>Why</th><th class="lib">Library</th></tr></thead>';
    const body = rows.map((r) => {
      const on = ticked && r.accepted;
      return '<tr class="' + (on ? 'keep' : '') + '"><td class="tk"><span class="af-tick' + (on ? ' on' : '') + '">✓</span></td>' +
        '<td class="item">' + r.item + '</td><td class="arr">→</td><td class="se mono">' + r.se + '</td>' +
        '<td class="tmdb">' + r.tmdbName + '</td><td class="why">' + r.why + '</td>' +
        '<td class="lib">' + (r.have ? '<span class="badge ok">have · skipped</span>' : '<span class="badge warn">missing</span>') + '</td></tr>';
    }).join('');
    const foot = ticked
      ? '<div class="af-mapfoot"><span class="kept"><b>' + kept + ' of ' + rows.length + ' kept</b></span>' +
        '<span class="btn sm ghost">Tick all</span><span class="spacer"></span>' +
        '<span class="af-why">The ' + (rows.length - kept) + ' you leave unticked are not refused — they are simply not stored, and they come back as items waiting for a number.</span></div>'
      : '<div class="af-mapfoot"><span class="kept">Nothing ticked</span>' +
        '<span class="btn sm ghost">Tick all</span></div>';
    return '<table class="af-map">' + head + '<tbody>' + body + '</tbody></table>' + foot;
  }

  /* ---------------------------------------------------------------- the five steps (§6) */
  function stepsCard(option, variant) {
    const failAt = variant === 'failed' ? D.STEP_FAIL.at : null;
    const runAt = variant === 'failed' ? null : 'videos';
    const rows = D.STEPS.map((s, i) => {
      const iFail = failAt ? D.STEPS.findIndex((x) => x.id === failAt) : -1;
      const iRun = runAt ? D.STEPS.findIndex((x) => x.id === runAt) : -1;
      let cls = '', glyph = String(i + 1), label = s.label, t = '', fx = s.fx;
      if (iFail >= 0) {
        if (i < iFail) { cls = 'done'; glyph = '✓'; t = s.t; }
        else if (i === iFail) { cls = 'fail'; glyph = '✕'; t = s.t; fx = D.STEP_FAIL.fx; }
      } else {
        if (i < iRun) { cls = 'done'; glyph = '✓'; t = s.t; }
        else if (i === iRun) {
          cls = 'run';
          glyph = option === 'rail' ? '▶' : String(i + 1);
          label = option === 'rail'
            ? s.label + '<span class="badge info">now</span>'
            : s.label + ' <span class="af-now">— now</span>';
        }
      }
      return '<div class="af-step ' + cls + '"><span class="ic"><span class="g">' + glyph + '</span></span>' +
        '<div><div class="lb">' + label + '</div><div class="fx">' + fx + '</div></div><span class="t">' + t + '</span></div>';
    }).join('');

    const legend = '<div class="af-legend opt-' + option + '">' +
      '<span class="lg wait"><span class="ic">4</span>waiting</span>' +
      '<span class="lg run"><span class="ic">' + (option === 'rail' ? '▶' : '2') + '</span>running</span>' +
      '<span class="lg done"><span class="ic">✓</span>done</span>' +
      '<span class="lg fail"><span class="ic">✕</span>failed</span></div>';

    return '<div class="card">' +
      '<div class="row center"><h3 style="margin:0;font-size:1.1rem;">New auto-fetcher</h3>' +
      '<span class="badge info" style="margin-left:8px;">series only</span><span class="spacer"></span>' +
      '<span class="tiny muted">one investigation at a time</span></div>' +
      '<div class="af-echo" style="margin-top:12px;"><span class="ph"></span><span><b>Royndin</b> · TV series · 3 seasons listed on TMDB · Faroese · <a href="series.html">not in library yet</a></span></div>' +
      '<div class="af-steps af-mk opt-' + option + '">' + rows + '</div>' +
      (variant === 'failed' ? '' : '<div class="tiny muted" style="margin-top:10px;">Nothing has been written yet. <b>Stop</b> ends the investigation and discards what it found.</div>') +
      legend + '</div>';
  }

  /* ---------------------------------------------------------------- the still (FR-10.3's copy) */
  function stillCard() {
    const s = D.STILL;
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Not on TMDB yet</h3>' +
      '<span class="badge warn" style="margin-left:8px;">1 episode</span><span class="spacer"></span>' +
      '<span class="tiny muted">metadata refresh is paused for these until TMDB has them</span></div>' +
      '<div class="tq-det" style="margin-top:10px;border-radius:var(--radius-s);"><div class="tq-in">' +
      '<div><div class="tq-field"><div class="lab">Episode <span class="btn sm ghost">Copy</span></div>' +
      '<div class="val mono">' + s.series + ' · ' + s.se + ' · aired ' + s.air + ' · ' + s.run + '</div>' +
      '<div class="src">Season and episode from the fetcher’s pattern; air date from the page’s publish date.</div></div>' +
      '<div class="tq-field"><div class="lab">Episode name <span class="btn sm ghost">Copy</span></div>' +
      '<div class="val">' + s.ep + '</div><div class="src">from the page — the episode’s own heading</div></div></div>' +
      '<div><div class="tq-field"><div class="lab">Episode still <span class="btn sm ghost">↓ Download 1920×1080</span></div>' +
      '<div class="tq-big">frame at ' + s.stills[s.pick] + ' · 1920×1080 · jpg</div>' +
      '<div class="tq-stills">' + s.stills.map((x, i) =>
        '<div class="tq-still' + (i === s.pick ? ' on' : '') + '"><span>' + x + '</span></div>').join('') + '</div>' +
      '<div class="src">' + s.copy + ' Click another or <a href="#">pick a different moment</a>.</div></div></div>' +
      '</div></div></div>';
  }

  /* ---------------------------------------------------------------- the Dashboard row (§7) */
  function dashCard() {
    const f = D.FETCHERS.royndin, c = D.CAUSE('c403'), h = D.SITES.varpid.host;
    return '<section class="ov-band w-warning"><div class="ov-sh"><h3>Auto-fetch</h3>' +
      '<span class="ov-size">1 site stopped</span><span class="spacer"></span>' +
      '<span class="badge warn">1 to fix</span></div>' +
      '<div class="ov-row sev-warning"><span class="ov-sev" title="warning"></span>' +
      '<div class="ov-main"><div class="ov-l"><a href="ai.html?tab=fetchers">' + h + ' has stopped us</a></div>' +
      '<div class="ov-s">' + c.long[0] + ' Nothing runs on this site, for any fetcher, until you resume it — a stop is never retried on a timer.</div>' +
      '<div class="ov-path">AI ▸ Fetchers › ' + h + '</div></div>' +
      '<div class="ov-n"><b>2</b> fetchers</div>' +
      '<div class="ov-act"><span class="ov-fix k-open">Open the item</span>' +
      '<a class="btn sm ghost" href="ai.html?tab=fetchers">Fetchers →</a></div></div></section>';
  }

  /* ---------------------------------------------------------------- the four causes (§1, longest) */
  function causesCard() {
    const en = D.STOP_CAUSES.map((c) =>
      '<div class="ck">' + c.key + '</div><div>' + mono(c.long) + '</div>').join('');
    const fo = D.STOP_CAUSES.map((c) =>
      '<div class="ck">' + c.key + '</div><div>' + mono(c.fo) + '</div>').join('');
    return '<div class="card"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Why a site stops</h3>' +
      '<span class="badge" style="margin-left:8px;">4 causes</span><span class="spacer"></span>' +
      '<span class="tiny muted">one reason line each · the longest-string check</span></div>' +
      '<div class="tiny muted" style="margin:6px 0 2px;">The lane stops on any of these four, and on nothing else. Plain words, with the code in mono.</div>' +
      '<div class="af-lang">English</div><div class="af-causes">' + en + '</div>' +
      '<div class="af-lang">Faroese — the longest of the three languages</div><div class="af-causes">' + fo + '</div>' +
      '</div>';
  }

  W.AFB = { mono, host, src, fetcherCard, stopBand, waitBand, kvBlock, saveBox, mapTable,
            stepsCard, stillCard, dashCard, causesCard, recipe };
})(window);
