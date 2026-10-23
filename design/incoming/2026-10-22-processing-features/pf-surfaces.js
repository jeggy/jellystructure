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
  /* The chip carries the state word, so the line must not carry it again — the first pass at
     correction 8 moved the duplication rather than removing it ("Failed" beside "Failed 40 min
     ago", "Running" beside "Running · Twelve Winters"). The line says WHEN and WHY; the chip says
     WHAT. */
  if (f.state === 'run') return f.last.replace('running · ', '');
  if (f.state === 'hold') return `Last ran ${f.last} · ${HOLD.now}`;
  if (f.state === 'bad') return `Last ran ${f.last.replace(/^failed /, '')}${f.next ? ` · ${f.next}` : ''}`;
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
  /* correction 8: the chip is the state, the line beside it is when. Not both. */
  const state = off ? badge('flat', 'Off')
    : inert ? badge('flat', 'Not mapped')
      : chipFor(f);
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
          <div class="pf-when"><b>${f.every}</b></div>
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

/* ================================================================
   2 · THE SCAN DIALOG — two panes (the owner's pick, 2026-10-23)
   ================================================================

   REVISION 1. Round 1 recommended one expanding row per feature and drew two panes as the
   alternative; the owner picked the two panes, so this is now the direction and every state in the
   brief is drawn in it. The expanding rows are gone from this file — they are in 62dbcd6e's history.

   The left pane has to keep the two things the expanding version gave away for free: every feature's
   OWN tick, and its `n of m` count. "Eight of nine are fully ticked" is readable here without
   opening anything, which is the whole reason the counts are on the left and not only on the right.
*/

/* how many of a feature's parts are ticked. `partly` unticks the last two, which is what makes
   Music read "5 of 7" — the brief's own example. */
const tickedOf = (f, o) => (o.partly === f.id ? f.subs.length - 2 : f.subs.length);

/* THE LEFT PANE. One row per feature: its own tick, its own count, and the queues-work marker when
   every part of it queues work (correction 7 — File health and Subtitles both do). */
function pickRow(f, o = {}) {
  const off = o.off === f.id;
  const partly = o.partly === f.id;
  const n = f.subs.length;
  const tick = off ? 'dis' : f.locked ? 'lock' : partly ? 'part' : 'on';
  const count = off ? '<a href="#">Off in Settings →</a>'
    : f.locked ? 'always'
      : n ? `${tickedOf(f, o)} of ${n}` : 'one tick';
  const mark = (allQueue(f) || f.queueOnly) ? ' · <span class="q">queues work</span>' : '';
  /* At 412 the panes are two screens, and nothing in the first render said so — the rows read as a
     flat list. A feature that HAS parts gets a chevron, which is the only affordance saying the row
     opens a screen of its own. A feature with no parts has nothing to open, so it has none. */
  return `
  <div class="pick${o.sel === f.id ? ' on' : ''}${off ? ' off' : ''}">
    <span class="tick ${tick}">${tick === 'part' ? '–' : '✓'}</span>
    <div><div class="n">${f.name}</div><div class="c">${count}${off ? '' : mark}</div></div>
    ${f.subs.length ? '<span class="go">›</span>' : '<span></span>'}
  </div>`;
}

/* THE RIGHT PANE. The selected feature's parts, each with its own tick. A feature that is off in
   Settings is inert HERE TOO, not just on the left: the owner's rule is shown-and-disabled with the
   reason, and a right pane that stayed live would be the one place the rule broke. */
function pane(f, o = {}) {
  const off = o.off === f.id;
  const partly = o.partly === f.id;
  const n = f.subs.length;
  /* A part whose own switch is off in Settings (`dep`) is not the same thing as a part the admin
     unticked for this run, and the first render drew them identically — Music's "Write tags" sat
     there as a plain empty box while Settings says tag writing is off. It gets the round's standing
     shape instead: shown, disabled, with the reason. */
  const parts = n ? f.subs.map((s, i) => `
    <div class="sd-one">
      <span class="tick ${off || s.dep ? 'dis' : (partly && i >= n - 2) ? '' : 'on'}">✓</span>
      <div class="n">${s.n}${s.q ? ` ${badge('info', 'queues work')}` : ''}
        ${s.dep ? `<div class="tiny muted" style="margin-top:4px">Off in Settings — ${s.dep}, so it cannot
          be ticked for one scan either. <a href="#">Settings ▸ Processing →</a></div>` : ''}
        ${s.warn ? `<div class="tiny" style="color:var(--warn);margin-top:4px">${s.warn} · nothing is
          measured until it comes back, and the queue does not move.</div>` : ''}</div>
    </div>`).join('') : `
    <div class="sd-none">No parts to tick — the whole feature is the one tick on the left.</div>`;
  return `
    <div class="sd-pane-head">
      <h3>${f.name}</h3>
      <span class="c">${off ? 'off' : f.locked ? 'always runs' : n ? `${tickedOf(f, o)} of ${n} ticked` : 'one tick'}</span>
    </div>
    <div class="sd-lede">${f.lede}</div>
    ${off ? `<div class="pf-note" style="border-left:3px solid var(--line-2);margin-bottom:12px"><b>Off in
      Settings — nothing here would run.</b> Its parts cannot be ticked for one scan either. Switch the
      feature on under <a href="#">Settings ▸ Processing</a> and it runs by itself from then on.</div>` : ''}
    <div class="sd-parts${off ? ' off' : ''}">${parts}</div>`;
  /* No per-pane queue note. The first render printed the legend's sentence ("Ticking it means
     'queue what is due now' …") a second time, word for word, inside the pane — the same fact twice
     in one picture, which is exactly what this round's one-model claim says to look for. The lede
     says the feature queues work, the parts carry the badge, and the legend below both panes
     explains the badge once. */
}

/* the full-rescan face is one switch above both panes — the only thing that changes between the two
   faces of the dialog. */
function rescanSwitch(o, scoped) {
  return `
    <div class="pf-all${o.full ? ' on' : ''}">
      ${sw(!!o.full)}
      <div class="fill"><div class="t">Re-check everything, not only what is due</div>
        <div class="s">${o.full
    ? `On · every title in ${scoped ? 'Films' : 'the library'} is read again, not only the ones that are due.`
    : 'Off · only titles that are due. This is the normal scan.'}</div></div>
    </div>
    ${o.full ? `<div class="pf-note" style="border-left:3px solid var(--warn);margin:0 0 10px">That is
      ${scoped ? LIB.films : `${LIB.films} and ${LIB.series}`} read again. It is safe to leave running;
      nothing waits on it.</div>` : ''}`;
}

function dialog(o = {}) {
  const title = o.title || 'Scan library';
  const scoped = !!o.scope;
  const list = F.filter((f) => !(scoped && (f.library || f.id === 'recs')));
  const sel = byId(o.sel || 'health');
  return `
  <div class="pf-scrim">
    <div class="pf-dlg wide">
      <div class="pf-dlg-head">
        <h2>${title}</h2>
        <p>${scoped
    ? `Films only — ${LIB.films}. Music and audiobooks are not in this library, so they are not here.`
    : 'Everything ticked runs now. What you untick stays unticked the next time, on every page.'}</p>
        ${scoped ? `<div class="pill-row" style="margin-top:10px">${chip('Films', 'act')}</div>` : ''}
      </div>
      <div class="sd-top">${rescanSwitch(o, scoped)}</div>
      <div class="sd-two">
        <div class="left">${list.map((f) => pickRow(f, o)).join('')}</div>
        <div class="right">${pane(sel, o)}</div>
      </div>
      <div class="sd-legend"><div class="pf-note q"><b>A part that queues work is not finished when the
        scan ends.</b> Ticking it means "queue what is due now" — the reading happens afterwards, in the
        background, and what it found shows on <a href="#">Activity</a>.</div></div>
      <div class="pf-dlg-foot">
        <span class="spacer" style="flex:1"></span>
        ${btn('Cancel', 'ghost')}${btn('Start scan', 'primary')}
      </div>
    </div>
  </div>`;
}

/* 412 px: the two panes become two screens. Step one is the list — which is why the counts had to be
   on the left row: at this width they are the only place the ticked state is visible at all. */
function dialogPhone(o = {}) {
  const sel = byId(o.sel || 'music');
  const parts = o.step === 'parts';
  return `
  <div class="pf-scrim">
    <div class="pf-dlg">
      <div class="pf-dlg-head">
        ${parts ? `<div class="sd-back">‹ All features</div>` : ''}
        <h2>${parts ? sel.name : 'Scan library'}</h2>
        <p>${parts
    ? 'Tick the parts to run this time. Back keeps whatever you tick here.'
    : 'Everything ticked runs now. Tap a feature to open its parts.'}</p>
      </div>
      ${parts ? `
      <div class="pf-dlg-body"><div class="sd-one-pane">${pane(sel, o)}</div></div>`
    : `
      <div class="sd-top">${rescanSwitch(o, false)}</div>
      <div class="sd-two one">
        <div class="left">${F.map((f) => pickRow(f, o)).join('')}</div>
      </div>
      <div class="sd-legend"><div class="pf-note q"><b>A part that queues work is not finished when the
        scan ends.</b> The reading happens afterwards, in the background, and shows on
        <a href="#">Activity</a>.</div></div>`}
      <div class="pf-dlg-foot">
        <span class="spacer" style="flex:1"></span>
        ${btn('Cancel', 'ghost')}${btn('Start scan', 'primary')}
      </div>
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
      <div><div>Scan cancelled</div>
        <div class="s">41 of ${LIB.films} were looked at. <a href="#">Continue scan</a></div></div>
    </div>
  </div>`;
}
