/* pf-data.js — the nine features, once, for all four surfaces.

   The round's question is whether four surfaces can read as one model. So there is one array here
   and every surface renders from it: Settings, the Scan dialog, Activity and a title page. If a
   fact appears twice in a picture it is because it appears twice in this array — which is itself
   the thing to look for when reviewing.

   Every value is a STAND-IN from the brief. Nothing is computed from anything: the admin renders
   server-pushed state only, so each string below stands for a value the server sends. There is no
   total, no "x of y due", no countdown — a number the page would have to work out for itself is
   exactly what the constitution forbids, and the way to not draw one is to not have one here.
*/

/* ---------- small pieces of the shipped vocabulary ---------- */
const sw = (on, extra = '') => `<span class="toggle${on ? ' on' : ''} ${extra}"></span>`;
const tick = (state) => `<span class="tick ${state}">${state === 'part' ? '–' : '✓'}</span>`;
const badge = (kind, text) => `<span class="badge ${kind}">${text}</span>`;
const btn = (label, cls = '') => `<span class="btn sm ${cls}">${label}</span>`;
const sel = (value) => `<span class="select">${value}</span>`;
const chip = (text, cls = '') => `<span class="chip ${cls}">${text}</span>`;

/* the state line a feature shows wherever it is listed. One sentence, server-sent, no arithmetic. */
const STATE = {
  ok:   (t) => badge('ok', t),
  run:  (t) => badge('warn', t),
  hold: (t) => badge('warn', t),
  bad:  (t) => badge('bad', t),
  warn: (t) => badge('warn', t),
  flat: (t) => badge('flat', t),
};

/* ---------- the nine ---------- */
const F = [
  {
    id: 'find', name: 'Find new &amp; changed', on: true, locked: true,
    why: 'Always on. Everything else follows what this finds.',
    every: 'every 1 h', also: null,
    last: '12 min ago · 3 new, 41 changed', next: 'in 48 min', wait: null,
    state: 'ok', stateText: 'Ran 12 min ago',
    subs: [], scope: ['film', 'series'],
    titleRow: { name: 'Find new &amp; changed', chip: ['ok', 'Looked at 12 min ago'], last: '12 min ago', next: 'in 48 min' },
    opts: () => `
      <div class="pf-every">
        <span class="muted tiny">Look for new and changed titles</span>
        ${sel('Every N hours')}<span class="input">1</span>
        <span class="muted tiny">hours</span>
      </div>
      <div class="pf-note">The order the work happens in is fixed and is not configurable. This looks
        first; everything switched on then runs on what it found.</div>
      <div>
        <div class="pf-part" style="grid-template-columns:minmax(0,1fr)">
          <div>
            <div class="pn">Re-check titles that have not changed</div>
            <div class="ph-line">Nothing on disk has changed, so this decides how often we look anyway.
              Older titles change less, so they are looked at less.</div>
            <div class="pf-ages" style="margin-top:11px">
              <label class="pf-age"><span>Released this year</span>${sel('Every 7 days')}</label>
              <label class="pf-age"><span>1–5 years old</span>${sel('Every 30 days')}</label>
              <label class="pf-age"><span>Older</span>${sel('Every 180 days')}</label>
            </div>
          </div>
        </div>
      </div>
      <div class="pf-note">Need a cron instead? ${sel('Custom · 0 30 * * *')} is kept here, and only here.</div>`,
  },
  {
    id: 'meta', name: 'Metadata &amp; artwork', on: true,
    every: 'new titles at once', also: 'unchanged ride the next look',
    last: '12 min ago', next: 'with the next look', wait: 0,
    state: 'ok', stateText: 'Ran 12 min ago',
    subs: [
      { n: 'Database metadata' }, { n: 'Artwork' }, { n: 'Ratings' },
      { n: 'Sidecar files' }, { n: 'Drift check' }, { n: 'Rescan in the downloader apps' },
    ],
    scope: ['film', 'series'],
    titleRow: { name: 'Metadata &amp; artwork', chip: ['warn', 'Stale'],
      why: 'The sidecar files were written before the file changed on disk.',
      last: '12 min ago', next: 'with the next look' },
    opts: () => `
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Overwrite other tools' fields in the sidecar files</div>
        <div class="ph-line">Off leaves a field alone once another tool has written it.</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Download artwork that is missing</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Put our values back when something drifts</div>
        <div class="ph-line">Off still notices the drift and shows it; it just does not correct it.</div></div><div></div></div>
      <div class="pf-note">Rescanning in the downloader apps after a write is configured on their own
        cards. <a href="#">Radarr and Sonarr →</a></div>
      <div class="pf-note">Telling the media server is not a setting. It happens after every write,
        every time — nobody has to remember it.</div>`,
  },
  {
    id: 'intro', name: 'Intro &amp; credits', on: true, queueOnly: true,
    every: 'new episodes at once', also: null,
    last: 'running · <i>Twelve Winters</i> S2, episode 4 of 10', next: null, wait: 37,
    state: 'run', stateText: 'Running',
    subs: [], scope: ['film', 'series'],
    titleRow: { name: 'Intro &amp; credits', chip: ['ok', 'Credits at 1:48:20'], last: '3 h ago', next: 'when the file changes' },
    opts: () => `
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Match by audio fingerprint</div>
        <div class="ph-line">Finds a shared intro across episodes. Slower, and the only thing that works
          when an episode has no chapters.</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Trust a tag that marks a scene after the credits</div></div><div></div></div>
      <div class="pf-part" style="grid-template-columns:minmax(0,1fr)"><div>
        <div class="pn">Chapter names that mean "intro" or "credits"</div>
        <div class="pill-row" style="margin-top:9px">
          ${chip('Intro')}${chip('Opening')}${chip('Opening Credits')}${chip('End Credits')}${chip('Recap')}${chip('+ Add', 'ghost')}
        </div></div></div>
      <div class="pf-note q"><b>This queues work.</b> Switching it on or ticking it in a scan puts
        episodes in a queue; the reading happens in a worker afterwards and shows on
        <a href="#">Activity</a>.</div>`,
  },
  {
    id: 'health', name: 'File health', on: true,
    every: 'by release age', also: null,
    last: '3 h ago', next: 'holding · <i>Living-room TV</i> is playing', wait: 1204, waitNote: 'sound in step',
    state: 'hold', stateText: 'Holding',
    subs: [
      { n: 'Read files end to end', q: true },
      { n: 'Track lengths', q: true },
      { n: 'Sound in step with the picture', q: true, warn: 'The sidecar is not answering' },
    ],
    scope: ['film', 'series'],
    opts: () => `
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Read files end to end</div>
        <div class="ph-line">Proves the file is readable all the way through. This is the expensive one —
          it reads every byte.</div>
        <div class="pf-ages" style="margin-top:10px">
          <label class="pf-age"><span>Released this year</span>${sel('Every year')}</label>
          <label class="pf-age"><span>1–5 years old</span>${sel('Every 2 years')}</label>
          <label class="pf-age"><span>Older</span>${sel('Every 5 years')}</label>
        </div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Track lengths</div>
        <div class="ph-line">Catches audio or subtitles that run past the picture.</div>
        <div class="pf-ages" style="margin-top:10px">
          <label class="pf-age"><span>Released this year</span>${sel('Every year')}</label>
          <label class="pf-age"><span>1–5 years old</span>${sel('Every 2 years')}</label>
          <label class="pf-age"><span>Older</span>${sel('Every 5 years')}</label>
        </div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Sound in step with the picture</div>
        <div class="ph-line">Measured once per file. A file is not measured again unless it changes.</div>
        <div class="pf-note" style="margin-top:10px;border-left:3px solid var(--warn)">
          ${badge('warn', 'The sidecar is not answering')}
          <div style="margin-top:7px">Nothing is measured while it is away, and the queue does not move.
          1 204 files are waiting for it. <a href="#">Where it is configured →</a></div></div>
        </div><div></div></div>
      <div class="pf-note q"><b>All three queue work.</b> They are read in a worker, one lane each,
        and what they found shows on <a href="#">Activity</a> and on the title.</div>`,
  },
  {
    id: 'subs', name: 'Subtitles', on: true,
    every: 'new subtitles at once', also: null,
    last: '2 h ago', next: null, wait: 5,
    state: 'ok', stateText: 'Ran 2 h ago',
    subs: [{ n: 'Pre-warm', q: true }, { n: 'Check fit', q: true }],
    scope: ['film', 'series'],
    titleRow: { name: 'Subtitles', chip: ['ok', 'Pre-warmed, 3 fit'], last: '2 h ago', next: 'when a subtitle arrives' },
    opts: () => `
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Pre-warm</div>
        <div class="ph-line">Prepares a subtitle before anyone presses play, so it appears instantly.</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Check that a subtitle fits its file</div></div><div></div></div>
      <div class="pf-part" style="grid-template-columns:minmax(0,1fr)"><div>
        <div class="pn">When a subtitle does not fit</div>
        <div class="ph-line">Moved here from the subtitle app's own card, because it is a decision about
          our files rather than about that app.</div>
        <div style="margin-top:10px;max-width:420px">${sel('Ask the subtitle app for another one')}</div></div></div>
      <div class="pf-note q"><b>Both queue work.</b> The fit is read afterwards, in the subtitle lane.</div>`,
  },
  {
    id: 'recs', name: 'Recommendations &amp; suggestions', on: true,
    every: 'weekly, Monday 04:00', also: null,
    last: '2 d ago', next: 'in 5 d', wait: null,
    state: 'ok', stateText: 'Ran 2 d ago',
    subs: [{ n: 'Recommendations' }, { n: 'Suggestions' }],
    scope: [],  /* library-wide: it is not a property of one title */
    opts: () => `
      <div class="pf-every">
        <span class="muted tiny">Rebuild</span>
        <span class="seg"><span>Daily</span><span class="on">Weekly</span></span>
        ${sel('Monday')}${sel('04:00')}
      </div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Recommendations</div>
        <div class="ph-line">The rows a viewer sees.</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Suggestions</div>
        <div class="ph-line">Things to request that we do not hold. These need the request app connected,
          and it is connected.</div></div><div></div></div>
      <div class="pf-note">This is the one feature that belongs to the library rather than to any
        title, so it has no row on a film or a series page.</div>`,
  },
  {
    id: 'music', name: 'Music', on: true, library: 'music',
    every: 'every 6 h', also: null,
    last: 'failed 40 min ago · the music database is not answering', next: 'retry in 20 min', wait: null,
    state: 'bad', stateText: 'Failed 40 min ago',
    subs: [
      { n: 'Read the music library' }, { n: 'Match on the music database' }, { n: 'Songs held twice' },
      { n: 'Covers &amp; pictures' }, { n: 'Lyrics' }, { n: 'Album/artist sidecar files' },
      { n: 'Write tags', dep: 'only while tag writing is on' },
    ],
    scope: [],
    opts: () => `
      <div class="pf-every"><span class="muted tiny">Read the music library</span>${sel('Every N hours')}<span class="input">6</span><span class="muted tiny">hours</span></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Match albums and artists on the music database</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Fetch covers and artist pictures</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Fetch lyrics</div></div><div></div></div>
      <div class="pf-part"><div>${sw(false)}</div><div><div class="pn">Write tags into the files</div>
        <div class="ph-line">Off, so <i>Write tags</i> is not offered in a scan either.</div></div><div></div></div>
      <div class="pf-note" style="border-left:3px solid var(--bad)">${badge('bad', 'The music database is not answering')}
        <div style="margin-top:7px">Nothing was matched 40 min ago. It retries by itself in 20 min; nothing
        here needs pressing.</div></div>`,
  },
  {
    id: 'books', name: 'Audiobooks', on: true, library: 'audiobooks',
    every: 'daily', also: null,
    last: '9 h ago', next: 'in 15 h', wait: null,
    state: 'ok', stateText: 'Ran 9 h ago',
    subs: [], scope: [],
    opts: () => `<div class="pf-note">Nothing to configure. It reads the audiobook library and stops.</div>`,
  },
  {
    id: 'play', name: 'Make files play directly', on: true,
    every: 'every minute', also: null,
    last: '1 min ago', next: null, wait: null, waitNote: '3 waiting for your approval',
    state: 'warn', stateText: '3 waiting for approval',
    subs: [], scope: ['film', 'series'],
    titleRow: { name: 'Plays directly', chip: ['warn', '1 repair waiting for you'],
      why: 'Approve it and the file plays without being re-encoded.', last: '1 min ago', next: 'every minute' },
    opts: () => `
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Move the index to the front</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Pull subtitles out of the file</div></div><div></div></div>
      <div class="pf-part"><div>${sw(true)}</div><div><div class="pn">Repack the audio</div></div><div></div></div>
      <div class="pf-part"><div>${sw(false)}</div><div><div class="pn">Drop a track no player can read</div></div><div></div></div>
      <div class="pf-part"><div>${sw(false)}</div><div><div class="pn">Apply a repair without asking</div>
        <div class="ph-line">Off, so each repair waits for you. 3 are waiting now.
          <a href="#">Activity →</a></div></div><div></div></div>`,
  },
];

const byId = (id) => F.find((f) => f.id === id);

/* the library sizes, as the server sends them */
const LIB = {
  films: '1 240 titles',
  series: '186 series, 7 950 episodes',
  music: '412 albums, 5 130 songs',
  books: '23 books',
};

/* the two popover sentences are FIXED WORDING from the owner's decision. They are written out once,
   here, so the drawing cannot drift from them. */
const POP = {
  general: 'Measured already — measuring again would read the same parts of the file. This unlocks when a file changes.',
  cantTell: "Couldn't tell from the parts measured — measuring again would read the same parts. This unlocks when the file changes.",
};

/* the household hold, one switch for everything (not one per feature) */
const HOLD = { on: true, now: '<i>Living-room TV</i> is playing' };
