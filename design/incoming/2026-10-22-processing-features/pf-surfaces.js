/* pf-surfaces.js — the four surfaces, built from the one model in pf-data.js.

   Settings ▸ Processing · the Scan dialog · Activity · a film or series page.
   Every frame is an element with an id; render.mjs screenshots those ids by name.
*/

/* ---------- the shell ---------- */
const NAV = [
  ['Dashboard', '▣'], ['Library', '▤'], ['Music', '♪'], ['Audiobooks', '▦'],
  ['Activity', '◴'], ['Settings', '⚙'],
];

function shell(active, body, o = {}) {
  const side = NAV.map(([n, ic]) => {
    const on = n === active ? ' on' : '';
    const tag = (n === 'Activity' && o.activityTag) ? `<span class="tag">${o.activityTag}</span>` : '';
    return `<div class="fr-nav${on}"><span class="ic">${ic}</span>${n}${tag}</div>`;
  }).join('');
  return `
  <div class="fr-shell">
    <div class="fr-top">
      <div class="burger">☰</div>
      <div class="fr-logo" style="padding:0"><span class="fr-mark"></span>Jellystructure</div>
    </div>
    <div class="fr-side">
      <div class="fr-logo"><span class="fr-mark"></span>Jellystructure</div>
      ${side}
    </div>
    <div class="fr-main">${body}</div>
  </div>`;
}

/* ================================================================
   1 · SETTINGS ▸ PROCESSING
   ================================================================ */

function holdCard() {
  return `
  <div class="card" style="margin-bottom:18px">
    <div class="row center">
      <div>${sw(HOLD.on)}</div>
      <div class="fill">
        <div style="font-weight:600;font-size:.95rem">Wait while someone is watching</div>
        <div class="tiny muted" style="margin-top:3px">One switch for everything here. Work that you
          start yourself never waits.</div>
      </div>
      ${badge('warn', 'Holding now')}
    </div>
    <div class="pf-note" style="margin-top:13px">${HOLD.now}, so the heavy reading is paused. It picks
      up by itself when the TV stops.</div>
  </div>`;
}

/* One sentence under the interval, built from the state rather than glued to "Last ran" — which
   produced "Last ran running · …" and "next holding · …" in the first render. */
function whenLine(f) {
  if (f.state === 'run') return `Running · ${f.last.replace('running · ', '')}`;
  if (f.state === 'hold') return `Waiting · ${HOLD.now} · last ran ${f.last}`;
  if (f.state === 'bad') return `${f.last.charAt(0).toUpperCase()}${f.last.slice(1)} · ${f.next}`;
  return `Last ran ${f.last}${f.next ? ` · next ${f.next}` : ''}`;
}

function featureRow(f, o = {}) {
  const open = o.open === f.id;
  const off = o.off === f.id || f.on === false;
  const inert = o.unmapped === f.id;
  const control = f.locked
    ? sw(true, 'dis')
    : sw(!off && !inert);
  const why = f.why ? `<span class="pf-why">${f.why}</span>`
    : (inert ? `<span class="pf-why">No ${f.id === 'books' ? 'audiobook' : 'music'} library is mapped.
        Map one under <a href="#">Libraries</a> and this switches on.</span>` : '');
  const when = off
    ? `<b>Off</b><br>Nothing runs, and it is not offered in a scan.`
    : inert
      ? `<b>—</b><br>Waiting for a library`
      : `<b>${f.every}</b>${f.also ? ` · ${f.also}` : ''}<br>${whenLine(f)}`;
  const state = off ? badge('flat', 'Off')
    : inert ? badge('flat', 'Not mapped')
      : STATE[f.state](f.stateText);
  return `
  <div class="pf-row${open ? ' open' : ''}${off ? ' off' : ''}${inert ? ' inert' : ''}">
    <div class="pf-head">
      <div>${inert ? '' : control}</div>
      <div class="pf-name">${f.name}${why}</div>
      <div class="pf-when">${when}</div>
      <div>${state}</div>
      <div class="pf-chev">⌄</div>
    </div>
    ${open ? `<div class="pf-body">${f.opts()}</div>` : ''}
  </div>`;
}

function settingsPage(o = {}) {
  if (o.variant === 'loading') {
    return shell('Settings', `
      <div class="fr-bar"><h1>Processing</h1></div>
      <div class="fr-sub"><span class="sk w4"></span></div>
      <div class="card">
        ${[0, 1, 2, 3, 4, 5].map(() => `
        <div class="sk-row"><div class="sk-sw"></div><div class="sk w1"></div>
          <div class="sk w4" style="height:10px"></div><div class="sk w3"></div><div></div></div>`).join('')}
      </div>
      <div class="tiny muted" style="margin-top:14px">Waiting for the first push from the server. Nothing
        is drawn from a cache, so nothing here can be out of date.</div>`);
  }
  const first = o.variant === 'firstrun';
  const rows = F.map((f) => {
    if (first) {
      const g = Object.assign({}, f, { last: null, next: null, state: 'flat', stateText: 'Not run yet' });
      return `
      <div class="pf-row">
        <div class="pf-head">
          <div>${f.locked ? sw(true, 'dis') : sw(true)}</div>
          <div class="pf-name">${f.name}${f.why ? `<span class="pf-why">${f.why}</span>` : ''}</div>
          <div class="pf-when"><b>${f.every}</b><br>Has not run yet</div>
          <div>${badge('flat', 'Not run yet')}</div>
          <div class="pf-chev">⌄</div>
        </div>
      </div>`;
    }
    return featureRow(f, o);
  }).join('');

  const head = `
    <div class="fr-bar"><h1>Processing</h1><span class="spacer"></span>
      ${btn('▶ Scan library', 'primary')}</div>
    <div class="fr-sub">Everything switched on here runs by itself. A new or changed title gets every
      feature at once; everything else is re-checked when its own interval says it is due.
      ${first ? '' : '<a href="#">See what is running now →</a>'}</div>`;

  const firstNote = first ? `
    <div class="card" style="margin-bottom:18px;border-left:3px solid var(--hi)">
      <div style="font-weight:600">Nothing has run yet</div>
      <div class="tiny muted" style="margin-top:4px">The first look starts within the hour, or press
        <i>Scan library</i> to start it now. Every feature below is on by default.</div>
    </div>` : '';

  return shell('Settings', `
    ${head}
    <div class="row" style="gap:8px;margin-bottom:20px;flex-wrap:wrap">
      <span class="chip">Libraries</span><span class="chip act">Processing</span><span class="chip">Players</span>
      <span class="chip">Notifications</span><span class="chip">AI</span><span class="chip">Apps</span>
    </div>
    ${firstNote}
    ${first ? '' : holdCard()}
    <div class="card"><div class="pf-list">${rows}</div></div>
    <div class="tiny muted" style="margin-top:14px">Nine features, and that is the whole list. There is
      no order to arrange: what runs when a title changes, and what order it runs in, is fixed.</div>`);
}

/* the alternative: the same nine rows left under Libraries ▸ Scanning */
function settingsAlt() {
  const rows = F.slice(0, 5).map((f) => featureRow(f, {})).join('');
  return shell('Settings', `
    <div class="fr-bar"><h1>Libraries</h1></div>
    <div class="fr-sub">Where the files are, and what we do to them.</div>
    <div class="row" style="gap:8px;margin-bottom:20px;flex-wrap:wrap">
      <span class="chip act">Libraries</span><span class="chip">Players</span><span class="chip">Notifications</span>
      <span class="chip">AI</span><span class="chip">Apps</span>
    </div>
    <div class="card" style="margin-bottom:16px">
      <h3 style="font-size:1.05rem">Folders</h3>
      <div class="row center" style="border-top:1px solid var(--line);padding-top:12px">
        <div class="fill"><div style="font-weight:600;font-size:.92rem">Films</div>
          <div class="tiny muted">${LIB.films}</div></div>${btn('Scan')}${btn('Edit')}</div>
      <div class="row center" style="border-top:1px solid var(--line);padding-top:12px;margin-top:12px">
        <div class="fill"><div style="font-weight:600;font-size:.92rem">Series</div>
          <div class="tiny muted">${LIB.series}</div></div>${btn('Scan')}${btn('Edit')}</div>
      <div class="row center" style="border-top:1px solid var(--line);padding-top:12px;margin-top:12px">
        <div class="fill"><div style="font-weight:600;font-size:.92rem">Music</div>
          <div class="tiny muted">${LIB.music}</div></div>${btn('Scan')}${btn('Edit')}</div>
    </div>
    <div class="card">
      <h3 style="font-size:1.05rem">Scanning</h3>
      <div class="tiny muted" style="margin-bottom:6px">Nine features, each with its own switch and its
        own interval. Everything on runs by itself.</div>
      <div class="pf-list" style="border-top:1px solid var(--line);margin-top:10px">${rows}</div>
      <div class="pill-row" style="margin-top:14px">${btn('Show the other four')}</div>
    </div>
    <div class="card" style="margin-top:16px">
      <h3 style="font-size:1.05rem">Metadata language</h3>
      <div class="tiny muted">…and six more cards under this tab.</div>
    </div>`);
}

/* ================================================================
   2 · THE SCAN DIALOG
   ================================================================ */

function dlgRow(f, o = {}) {
  const off = o.off === f.id;
  const open = o.open === f.id;
  const partly = o.partly === f.id;
  const n = f.subs.length;
  const ticked = partly ? n - 2 : n;
  const state = off ? 'dis' : f.locked ? 'lock' : partly ? 'part' : 'on';
  const count = off ? `<span class="sd-count"><a href="#">Off in Settings →</a></span>`
    : f.locked ? `<span class="sd-count">always</span>`
      : n ? `<span class="sd-count">${ticked} of ${n}</span>` : '';
  const sub = (open && n) ? `
    <div class="sd-sub">
      ${f.subs.map((s, i) => `
        <div class="sd-one">
          <span class="tick ${partly && i >= n - 2 ? '' : 'on'}">✓</span>
          <div class="n">${s.n}${s.q ? ` ${badge('info', 'queues work')}` : ''}${s.dep ? `<span class="muted tiny"> · ${s.dep}</span>` : ''}</div>
          <div></div>
        </div>`).join('')}
    </div>` : (open && !n) ? `
    <div class="sd-sub"><div class="tiny muted">One tick, no parts.${f.queueOnly ? ` ${badge('info', 'queues work')}` : ''}</div></div>` : '';
  return `
  <div class="sd-row${off ? ' off' : ''}">
    <div class="sd-head">
      <span class="tick ${state}">${state === 'part' ? '–' : '✓'}</span>
      <div class="sd-name">${f.name}${f.queueOnly && !open ? ` ${badge('info', 'queues work')}` : ''}</div>
      ${count}
      <div class="pf-chev">⌄</div>
    </div>
    ${sub}
  </div>`;
}

function dialog(o = {}) {
  const title = o.title || 'Scan library';
  const scoped = !!o.scope;
  const list = F.filter((f) => !(scoped && (f.library || f.id === 'recs')));
  return `
  <div class="pf-scrim">
    <div class="pf-dlg">
      <div class="pf-dlg-head">
        <h2>${title}</h2>
        <p>${scoped
    ? `Films only — ${LIB.films}. Music and audiobooks are not in this library, so they are not here.`
    : 'Everything ticked runs now. What you untick stays unticked the next time, on every page.'}</p>
        ${scoped ? `<div class="pill-row" style="margin-top:10px">${chip('Films', 'act')}</div>` : ''}
      </div>
      <div class="pf-dlg-body">
        <div class="pf-all${o.full ? ' on' : ''}">
          ${sw(!!o.full)}
          <div class="fill"><div class="t">Re-check everything, not only what is due</div>
            <div class="s">${o.full
    ? `On · every title in ${scoped ? 'Films' : 'the library'} is read again, not only the ones that are due.`
    : 'Off · only titles that are due. This is the normal scan.'}</div></div>
        </div>
        ${o.full ? `<div class="pf-note" style="border-left:3px solid var(--warn)">That is
          ${scoped ? LIB.films : `${LIB.films} and ${LIB.series}`} read again. It is safe to leave
          running; nothing waits on it.</div>` : ''}
        ${list.map((f) => dlgRow(f, o)).join('')}
        <div class="pf-note q" style="margin:14px 0 4px"><b>A part that queues work is not finished when
          the scan ends.</b> Ticking it means "queue what is due now" — the reading happens afterwards,
          in its own lane, and what it found shows on <a href="#">Activity</a>.</div>
      </div>
      <div class="pf-dlg-foot">
        <span class="foot-note">Cancel starts nothing.</span>
        <span class="spacer" style="flex:1"></span>
        ${btn('Cancel', 'ghost')}${btn('Start scan', 'primary')}
      </div>
    </div>
  </div>`;
}

/* the alternative to nine expanding rows: pick on the left, parts on the right (a drill-in at 412) */
function dialogAlt(phone = false) {
  const f = byId('health');
  const left = F.map((x) => {
    const on = x.id === 'health' ? ' on' : '';
    const st = x.id === 'recs' ? '<span class="c">off</span>'
      : x.subs.length ? `<span class="c">${x.id === 'music' ? '5 of 7' : `${x.subs.length} of ${x.subs.length}`}</span>` : '';
    return `<div class="pick${on}">
      <span class="tick ${x.id === 'recs' ? 'dis' : x.locked ? 'lock' : x.id === 'music' ? 'part' : 'on'}">${x.id === 'music' ? '–' : '✓'}</span>
      <span>${x.name}</span>${st}</div>`;
  }).join('');
  const right = `
    <h3>${f.name}</h3>
    <div class="tiny muted" style="margin-bottom:12px">Three parts. All three queue work — the reading
      happens afterwards, in its own lane.</div>
    ${f.subs.map((s) => `
      <div class="sd-one sd-one-grid" style="padding:10px 0;border-top:1px dashed var(--line)">
        <span class="tick on">✓</span>
        <div class="n">${s.n} ${badge('info', 'queues work')}
          ${s.warn ? `<div class="tiny" style="color:var(--warn);margin-top:4px">${s.warn} · nothing is measured until it answers</div>` : ''}
        </div><div></div></div>`).join('')}`;
  return `
  <div class="pf-scrim">
    <div class="pf-dlg" style="width:${phone ? 'auto' : '860px'}">
      <div class="pf-dlg-head"><h2>Scan library</h2>
        <p>${phone ? 'Pick a feature to see its parts.' : 'Nine features on the left; the one you are looking at on the right.'}</p></div>
      ${phone
    ? `<div class="pf-dlg-body"><div class="sd-two" style="grid-template-columns:minmax(0,1fr);min-height:0">
          <div class="left" style="border-right:none;padding:0">${left}</div></div>
         <div class="pf-note q" style="margin:12px 0 4px">Tap a feature to open its parts on their own
           screen, with a back arrow. Nothing expands in place.</div></div>`
    : `<div class="sd-two"><div class="left">${left}</div><div class="right">${right}</div></div>`}
      <div class="pf-dlg-foot"><span class="foot-note">Cancel starts nothing.</span>
        <span class="spacer" style="flex:1"></span>${btn('Cancel', 'ghost')}${btn('Start scan', 'primary')}</div>
    </div>
  </div>`;
}

function toastFrame() {
  return `
  <div style="padding:34px;display:flex;flex-direction:column;gap:18px;align-items:flex-start">
    <div class="pf-toast">
      <span style="color:var(--ok);font-size:1.1rem">✓</span>
      <div><div>Scan started · skipped <b>Intro &amp; credits</b></div>
        <div class="s">You unticked it. It stays unticked until you tick it again.</div></div>
    </div>
    <div class="pf-toast" style="border-left-color:var(--info)">
      <span style="color:var(--info);font-size:1.1rem">◴</span>
      <div><div>Scan finished · <b>37 episodes queued for intro &amp; credits</b></div>
        <div class="s">Queued work is not done yet — the reading is happening now.
          <a href="#">Activity →</a></div></div>
    </div>
    <div class="pf-toast" style="border-left-color:var(--warn)">
      <span style="color:var(--warn);font-size:1.1rem">▮</span>
      <div><div>Scan cancelled · nothing was left half-written</div>
        <div class="s">41 of ${LIB.films} were looked at. <a href="#">Continue scan</a></div></div>
    </div>
  </div>`;
}
