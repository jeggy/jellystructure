/* pf-surfaces2.js — Activity, a title page, and the frame registry. */

/* ================================================================
   3 · ACTIVITY — Now · Schedule · History
   ================================================================ */

function views(on) {
  return `<span class="seg">
    <span class="${on === 'now' ? 'on' : ''}">Now</span>
    <span class="${on === 'sched' ? 'on' : ''}">Schedule</span>
    <span class="${on === 'hist' ? 'on' : ''}">History</span></span>`;
}

function laneFor(f, o = {}) {
  const dot = f.state === 'run' ? 'busy' : f.state === 'bad' ? 'bad' : f.state === 'hold' ? '' : 'ok';
  const wait = (f.wait === null || f.wait === undefined)
    ? (f.waitNote ? f.waitNote : 'nothing waiting')
    : `${f.wait === 1204 && o.longQueue ? '9\u00a0600' : f.wait.toLocaleString('en').replace(/,/g, '\u00a0')} waiting${f.waitNote ? ` · ${f.waitNote}` : ''}`;
  const sub = f.state === 'run'
    ? `${f.last} · ${wait}<div class="prog"><i style="width:40%"></i></div>`
    : f.state === 'hold'
      ? `${badge('warn', 'Holding')} ${HOLD.now} · ${wait}`
      : f.state === 'bad'
        ? `${badge('bad', 'Failed')} ${f.last.replace(/^failed [^·]*· /, '')} · ${f.next}`
        : `${f.last}${f.next ? ` · next ${f.next}` : ''} · ${wait}`;
  return `
  <div class="lane">
    <div class="dot ${dot}"></div>
    <div><div class="t">${f.name}</div><div class="s">${sub}</div></div>
    <div class="acts">${btn('Run now', o.offline ? 'dis' : '')}${(f.wait || o.longQueue) ? btn('Empty', o.offline ? 'dis' : '') : ''}</div>
  </div>`;
}

function activityNow(o = {}) {
  const run = o.offline ? `
    <div class="card pf-stale" style="margin-bottom:16px">
      <div class="row center"><div class="fill">
        <div style="font-weight:600;font-size:1.02rem">Scan · started 12 min ago</div>
        <div class="tiny muted" style="margin-top:3px">As of the last push, 40 s ago. It may have
          finished since.</div></div>${badge('flat', 'last seen running')}${btn('Cancel run', 'dis')}</div>
    </div>` : o.cancelled ? `
    <div class="card" style="margin-bottom:16px;border-left:3px solid var(--warn)">
      <div class="row center"><div class="fill">
        <div style="font-weight:600">Scan cancelled 2 min ago</div>
        <div class="tiny muted" style="margin-top:4px">41 of ${LIB.films} were looked at. What was
          already queued is still being read — that is the one thing a cancel does not undo.</div>
      </div>${btn('Continue scan', 'primary')}</div>
    </div>` : `
    <div class="card" style="margin-bottom:16px">
      <div class="row center" style="margin-bottom:12px">
        <div class="fill"><div style="font-weight:600;font-size:1.02rem">Scan · started 12 min ago</div>
          <div class="tiny muted" style="margin-top:3px">Started from the dashboard with everything
            ticked. What it <i>queued</i> is being read in the card below — the run did not wait
            for that.</div></div>
        ${badge('warn', 'Running')}${btn('Cancel run')}
      </div>
      <div class="lane" style="border-top:1px solid var(--line)">
        <div class="dot ok"></div>
        <div><div class="t">Find new &amp; changed</div>
          <div class="s">Finished · 3 new, 41 changed</div></div><div></div>
      </div>
      <div class="lane">
        <div class="dot ok"></div>
        <div><div class="t">Intro &amp; credits</div>
          <div class="s">Queued 37 episodes · ${badge('info', 'being read in the background')}</div></div><div></div>
      </div>
      <div class="lane">
        <div class="dot"></div>
        <div><div class="t">File health</div>
          <div class="s">${badge('warn', 'Holding')} ${HOLD.now}</div></div><div></div>
      </div>
    </div>`;

  /* REVISION 1, correction 2. Only the features that QUEUE work appear here. Metadata & artwork,
     music, audiobooks and recommendations run inside the run itself and have nothing to show between
     runs — their `Run now` is on Schedule, which is where a thing you can start lives. */
  const lanes = ['intro', 'health', 'subs', 'play'].map((id) => laneFor(byId(id), o)).join('');

  const offline = o.offline ? `
    <div class="pf-offline">
      <span>◴</span>
      <div>Reconnecting… Nothing below has changed since <b>40 s ago</b>, so treat every count as
        that old. Nothing has stopped at the server — but nothing here can be started from this page
        until it is back, so the controls are off.</div>
    </div>` : '';

  return shell('Activity', `
    <div class="fr-bar"><h1>Activity</h1><span class="spacer"></span>${views('now')}</div>
    <div class="fr-sub">What is running now, and what is waiting to be read. Every feature's schedule —
      including the ones that run inside the run and have nothing waiting — is on
      <a href="#">Schedule</a>.</div>
    ${offline}
    ${run}
    <div class="card" style="margin-bottom:16px">
      <h3 style="font-size:1.05rem">Queued work</h3>
      <div class="tiny muted" style="margin-bottom:6px">The four features that queue work, and what is
        waiting for each. <i>Empty</i> clears only that feature's waiting work. A feature that is
        holding is waiting for a TV, not stuck.</div>
      <div style="border-top:1px solid var(--line);margin-top:10px"
        class="${o.offline ? 'pf-stale' : ''}">${lanes}</div>
    </div>
    <div class="grid" style="grid-template-columns:1fr 1fr">
      <div class="card"><h3 style="font-size:1rem">Pacing</h3>
        <div class="tiny muted">Unchanged by this round — it already paces the readers.</div>
        <div class="pill-row" style="margin-top:11px">${chip('2 readers')}${chip('normal')}</div></div>
      <div class="card"><h3 style="font-size:1rem">AI</h3>
        <div class="tiny muted">Unchanged by this round. Follow-ups are configured on the AI tab.</div>
        <div class="pill-row" style="margin-top:11px">${chip('idle')}</div></div>
    </div>`, { activityTag: o.offline ? '' : '3' });
}

function schedRow(f, o = {}) {
  const off = o.off === f.id;
  const wait = off ? '—'
    : (f.wait === null || f.wait === undefined) ? (f.waitNote || '—')
      : `${(f.wait === 1204 && o.longQueue) ? '9\u00a0600' : String(f.wait).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0')}${f.waitNote ? ` · ${f.waitNote}` : ''}`;
  const runNow = off
    ? `<span class="rr-wrap">${btn('Run now', 'dis')}${o.offPop ? `<div class="pf-pop">Off in
        Settings — nothing would run. Switch it on there and it runs by itself.</div>` : ''}</span>`
    : btn('Run now');
  return `
  <tr class="${off ? 'off' : ''}">
    <td><div class="f">${f.name}</div>${f.locked ? '<div class="sub">always on</div>' : off ? '<div class="sub"><a href="#">Off in Settings →</a></div>' : ''}</td>
    <td>${off ? '—' : f.every}${f.also && !off ? `<div class="sub">${f.also}</div>` : ''}</td>
    <td>${off ? '—' : f.last}</td>
    <td>${off ? '—' : (f.next || '—')}</td>
    <td class="num">${wait}</td>
    <td><div class="acts">${runNow}${(f.wait && !off) || (o.longQueue && f.id === 'health') ? btn('Empty') : ''}</div></td>
  </tr>`;
}

function activitySched(o = {}) {
  return shell('Activity', `
    <div class="fr-bar"><h1>Activity</h1><span class="spacer"></span>${views('sched')}</div>
    <div class="fr-sub">Every feature, when it last ran, when it runs next, and what is waiting.
      Nothing here is a guess: each value is the one the server sent.</div>
    <div class="card">
      <table class="sched">
        <thead><tr><th>Feature</th><th>Every</th><th>Last ran</th><th>Next</th><th>Waiting</th><th></th></tr></thead>
        <tbody>${F.map((f) => schedRow(f, o)).join('')}</tbody>
      </table>
    </div>
    ${o.longQueue ? `
    <div style="margin-top:18px;display:flex;justify-content:center">
      <div class="pf-confirm">
        <h3>Empty the sound-in-step queue?</h3>
        <p><b>9 600 waiting files are removed from the queue.</b> They are not measured, and they go
          back in the queue the next time they are due.</p>
        <p>What is being measured right now keeps going. This cannot be undone.</p>
        <div class="row center" style="margin-top:16px"><span class="spacer" style="flex:1"></span>
          ${btn('Cancel', 'ghost')}${btn('Empty queue', 'bad')}</div>
      </div>
    </div>` : `
    <div class="tiny muted" style="margin-top:14px"><i>Empty</i> removes that feature's waiting work and
      never stops what is running. It asks once and cannot be undone.</div>`}`, { activityTag: '3' });
}

function activitySchedPhone(o = {}) {
  const cards = F.map((f) => {
    const off = o.off === f.id;
    return `
    <div class="sched-card">
      <div class="r1"><span class="f">${f.name}</span>${off ? badge('flat', 'Off') : chipFor(f, { short: true })}</div>
      <dl class="kv">
        <dt>Every</dt><dd>${off ? '—' : f.every}</dd>
        <dt>Last ran</dt><dd>${off ? '—' : f.last}</dd>
        <dt>Next</dt><dd>${off ? '—' : (f.next || '—')}</dd>
        <dt>Waiting</dt><dd>${(f.wait === null || f.wait === undefined) ? (f.waitNote || '—') : String(f.wait).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0')}${f.waitNote && f.wait ? ` · ${f.waitNote}` : ''}</dd>
      </dl>
      <div class="acts">${btn('Run now', off ? 'dis' : '')}${f.wait && !off ? btn('Empty') : ''}</div>
    </div>`;
  }).join('');
  return shell('Activity', `
    <div class="fr-bar"><h1>Activity</h1></div>
    <div style="margin-bottom:14px">${views('sched')}</div>
    <div class="fr-sub" style="margin-bottom:14px">One card per feature. The table does not fit here and
      is not pretended to.</div>
    <div class="card"><div class="sched-cards">${cards}</div></div>`, { activityTag: '3' });
}

function activityHist() {
  return shell('Activity', `
    <div class="fr-bar"><h1>Activity</h1><span class="spacer"></span>${views('hist')}</div>
    <div class="fr-sub">Past runs, and the log of the one you pick.</div>
    <div class="card" style="margin-bottom:16px">
      <div class="lane" style="border-top:none"><div class="dot busy"></div>
        <div><div class="t">Scan · 12 min ago</div><div class="s">Running · started from the dashboard</div></div>
        <div class="acts">${btn('Open log')}</div></div>
      <div class="lane"><div class="dot ok"></div>
        <div><div class="t">Scan · 1 h 12 min ago</div><div class="s">Finished in 4 min 31 s · 1 new, 7 changed</div></div>
        <div class="acts">${btn('Open log')}</div></div>
      <div class="lane"><div class="dot bad"></div>
        <div><div class="t">Music · 40 min ago</div><div class="s">Failed · the music database is not answering</div></div>
        <div class="acts">${btn('Open log')}</div></div>
      <div class="lane"><div class="dot ok"></div>
        <div><div class="t">Scan · 2 h 12 min ago</div><div class="s">Finished in 3 min 58 s · nothing new</div></div>
        <div class="acts">${btn('Open log')}</div></div>
    </div>
    <div class="card">
      <h3 style="font-size:1.02rem">Log · the run 12 min ago</h3>
      <div class="logbox" style="margin-top:12px">
        <div><span class="run">14:02:11</span> <b>Find new &amp; changed</b> · 3 new, 41 changed</div>
        <div><span class="run">14:02:44</span> <b>Metadata &amp; artwork</b> · 44 titles written</div>
        <div><span class="run">14:02:51</span> <b>Metadata &amp; artwork</b> · media server told</div>
        <div><span class="run">14:02:58</span> <b>Intro &amp; credits</b> · 37 episodes queued</div>
        <div><span class="run">14:03:02</span> <b>File health</b> · holding, a TV is playing</div>
        <div><span class="run">14:03:02</span> <b>Subtitles</b> · 5 queued</div>
        <div><span class="run">14:03:03</span> <b>Make files play directly</b> · 3 repairs waiting for approval</div>
      </div>
    </div>`, { activityTag: '3' });
}

/* ================================================================
   4 · A FILM OR SERIES PAGE
   ================================================================ */

function refreshMenu(kind) {
  const items = F.filter((f) => f.scope.includes(kind === 'series' ? 'series' : 'film'));
  return `
  <div class="pf-menu">
    ${kind === 'series' ? `
    <div class="scope"><div class="mh" style="padding:0 0 7px">What to refresh</div>
      <span class="seg"><span class="on">Whole series</span><span>Season 2</span></span></div>` : ''}
    <div class="mh">Run now, for this ${kind === 'series' ? 'series' : 'film'}</div>
    ${items.map((f) => {
    const partly = f.id === 'health';
    return `<div class="mi">
        <div>${f.name}${partly ? '<span class="s">Runs two of its three checks. Sound in step is measured already and is skipped.</span>'
      : f.queueOnly ? '<span class="s">Queues it; the reading shows on Activity afterwards.</span>' : ''}</div>
        ${partly ? badge('flat', 'skips 1') : ''}
      </div>`;
  }).join('')}
    <div class="mfoot">Each one runs exactly what the automatic run runs. Recommendations are built for
      the whole library, so they are not here. <a href="#">Activity →</a></div>
  </div>`;
}

function rrControl(o = {}) {
  if (!o.disabled) return `<div class="rr-wrap"><span class="rr">↻</span></div>`;
  return `
  <div class="rr-wrap">
    <span class="rr dis">↻</span>
    ${o.pop ? `<div class="pf-pop">${o.pop === 'cantTell' ? POP.cantTell : POP.general}</div>` : ''}
  </div>`;
}

function chkRow(o) {
  return `
  <div class="chk-row">
    <div class="n-wrap"><div class="n">${o.name} ${badge(o.chip[0], o.chip[1])}</div>
      ${o.why ? `<div class="why ${o.whyBad ? 'bad' : ''}">${o.why}</div>` : ''}
      <div class="when-ph">Last ran ${o.last} · next ${o.next}</div></div>
    <div class="when"><b>Last ran</b>${o.last}</div>
    <div class="when"><b>Next due</b>${o.next}</div>
    ${rrControl(o)}
  </div>`;
}

function checksCard(o = {}) {
  const rows = [
    chkRow({ name: 'Find new &amp; changed', chip: ['ok', 'ok'], last: '12 min ago', next: 'in 48 min' }),
    chkRow({
      name: 'Metadata &amp; artwork', chip: ['warn', 'stale'],
      why: 'The NFO files were written before the file changed on disk.',
      last: '12 min ago', next: 'with the next look',
    }),
    chkRow({ name: 'Intro &amp; credits', chip: ['ok', 'credits at 1:48:20'], last: '3 h ago', next: 'when the file changes' }),
    `<div class="chk-row" style="padding-bottom:4px"><div><div class="n" style="font-weight:600">File health</div>
       <div class="why">Three checks. Each one is read in the background and reported here.</div></div>
       <div></div><div></div><div></div></div>`,
    `<div style="padding-left:18px;border-left:2px solid var(--line);margin-left:4px">
      ${chkRow({ name: 'Readable end to end', chip: ['ok', 'read 3 h ago'], last: '3 h ago', next: 'in 1 y' })}
      ${chkRow({
      name: 'Track lengths', chip: ['bad', 'audio runs past the picture'],
      why: 'The audio track is 1.4 s longer than the video.', whyBad: true,
      last: '3 h ago', next: 'in 1 y',
    })}
      ${chkRow({
      name: 'Sound in step with the picture', chip: ['flat', "can't tell"],
      why: 'Only one of the parts measured read clearly enough to measure.', whyBad: true,
      last: '3 h ago', next: 'when the file changes',
      disabled: true, pop: o.pop === 'row' ? 'cantTell' : null,
    })}
    </div>`,
    chkRow({ name: 'Subtitles', chip: ['ok', 'pre-warmed, 3 fit'], last: '2 h ago', next: 'when a subtitle arrives' }),
    chkRow({
      name: 'Make files play directly', chip: ['warn', '1 repair waiting for you'],
      why: 'Approve it and the file plays without being re-encoded.',
      last: '1 min ago', next: 'every minute',
    }),
  ].join('');
  return `
  <div class="card">
    <div class="row center" style="margin-bottom:10px">
      <h3 style="font-size:1.05rem;margin:0" class="fill">Checks</h3>
      <span class="tiny muted">The same nine features, for this title</span>
      ${rrControl({ disabled: true, pop: o.pop === 'card' ? 'general' : null })}
    </div>
    <div class="chk" style="border-top:1px solid var(--line)">${rows}</div>
  </div>`;
}

function titleFilm(o = {}) {
  return shell('Library', `
    <div class="ti-head">
      <div class="ti-art"></div>
      <div class="ti-meta">
        <h1>Midsummer Static</h1>
        <div class="line">2024 · Film · 1 h 52 min · ${badge('ok', 'plays directly')}</div>
        <div class="ti-acts">
          <div class="menu-wrap">${btn('Refresh ▾', 'primary')}${o.menu ? refreshMenu('film') : ''}</div>
          ${btn('Edit metadata')}${btn('Files')}${btn('Artwork')}
        </div>
      </div>
    </div>
    ${checksCard(o)}`);
}

function titleSeries(o = {}) {
  const eps = [
    ['S02E01', 'ok', 'ok', 'in step'],
    ['S02E02', 'ok', 'ok', 'in step'],
    ['S02E03', 'ok', 'ok', 'in step'],
    ['S02E04', 'ok', 'ok', 'in step'],
    ['S02E05 · Ice Road', 'flat', "can't tell", null],
  ];
  return shell('Library', `
    <div class="ti-head">
      <div class="ti-art"></div>
      <div class="ti-meta">
        <h1>Twelve Winters</h1>
        <div class="line">3 seasons · 28 episodes · Series</div>
        <div class="ti-acts">
          <div class="menu-wrap">${btn('Refresh ▾', 'primary')}${o.menu ? refreshMenu('series') : ''}</div>
          ${btn('Edit metadata')}${btn('Episodes')}${btn('Artwork')}
        </div>
      </div>
    </div>
    <div class="card">
      <div class="row center" style="margin-bottom:10px">
        <h3 style="font-size:1.05rem;margin:0" class="fill">Checks · season 2</h3>
        <span class="tiny muted">Sound in step with the picture</span>
        ${rrControl({ disabled: true, pop: o.pop === 'card' ? 'general' : null })}
      </div>
      <div class="chk" style="border-top:1px solid var(--line)">
        ${eps.map(([n, kind, label, sub]) => chkRow({
    name: n, chip: [kind, label],
    why: sub ? null : 'Only one of the parts measured read clearly enough to measure.',
    whyBad: !sub,
    last: '3 h ago', next: sub ? 'when the file changes' : 'when the file changes',
    disabled: !sub, pop: (!sub && o.pop === 'row') ? 'cantTell' : null,
  })).join('')}
      </div>
      <div class="pf-note" style="margin-top:14px">Four episodes read clearly. <i>Ice Road</i> could not
        be told either way — that is settled, not pending, so it is not waiting for anything.</div>
    </div>`);
}

/* the longest strings, both widths, on the surface where length actually breaks something */
function longest(phone = false) {
  const f = byId('recs');
  return `
  <div style="padding:${phone ? '16px 14px 150px' : '26px 26px 120px'};display:flex;flex-direction:column;gap:18px">
    <div class="card">
      <div class="pf-list">
        ${featureRow(Object.assign({}, f, {
    name: 'Recommendations &amp; suggestions',
    every: 'weekly, Monday 04:00',
    also: 'rebuilt for every viewer in the household',
    last: 'failed 2 d ago · the request app refused the connection and has not answered since',
    next: 'in 5 d',
    stateText: 'Failed · the request app is not answering',
    state: 'bad',
  }), {})}
        ${featureRow(Object.assign({}, byId('health'), {
    last: '3 h ago',
    next: 'holding · <i>Living-room TV</i> is playing',
    stateText: 'Holding · a TV is playing',
  }), {})}
      </div>
    </div>
    ${phone ? `
    <div class="card"><div class="sched-cards"><div class="sched-card">
      <div class="r1"><span class="f">Recommendations &amp; suggestions</span>
        ${badge('bad', 'Failed · the request app is not answering')}</div>
      <dl class="kv">
        <dt>Every</dt><dd>weekly, Monday 04:00</dd>
        <dt>Last ran</dt><dd>failed 2 d ago · the request app refused the connection and has not
          answered since</dd>
        <dt>Next</dt><dd>in 5 d</dd>
        <dt>Waiting</dt><dd>9 600 · sound in step</dd>
      </dl>
      <div class="acts">${btn('Run now')}${btn('Empty')}</div>
    </div></div></div>` : `
    <table class="sched card" style="padding:16px">
      <thead><tr><th>Feature</th><th>Every</th><th>Last ran</th><th>Next</th><th>Waiting</th><th></th></tr></thead>
      <tbody>
        <tr><td><div class="f">Recommendations &amp; suggestions</div></td>
          <td>weekly, Monday 04:00</td>
          <td>failed 2 d ago · the request app refused the connection</td>
          <td>in 5 d</td><td class="num">9 600 · sound in step</td>
          <td><div class="acts">${btn('Run now')}${btn('Empty')}</div></td></tr>
      </tbody>
    </table>`}
    ${chkRow({
    name: 'Sound in step with the picture', chip: ['flat', "can't tell"],
    why: 'Only one of the parts measured read clearly enough to measure, and the measuring service is not answering.',
    /* No popover here. This frame exists to test the LONGEST STRINGS, and an open popover sat on
       top of the longest one of them — the picture was testing nothing. The wordings are proved in
       title-film-pop, title-row-pop and title-phone. */
    whyBad: true, last: '3 h ago', next: 'when the file changes', disabled: true,
  })}
  </div>`;
}
