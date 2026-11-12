/* ============================================================================
   sp-build.js — the builders. Every value comes from sp-data.js; nothing in
   here is a literal that appears in a picture, except the control's own fixed
   words ("Repeat every", "on", "at", "starting", "Next five runs").

   The control is built ONCE, by `control()`. The two homes wrap it and change
   nothing inside it — which is the point of FR-3 and the thing these pictures
   have to prove.
   ============================================================================ */
(function () {
  'use strict';
  const D = window.SPD;
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  // ── the control ──────────────────────────────────────────────────────────

  /**
   * The unit's word, in the number the chosen N puts it in. Drawn plural-only
   * first, and the render showed why not: at N = 1 every picture read
   * "Repeat every 1 weeks". Both the closed select and the open menu follow N,
   * so the two can never disagree with each other — which is what a menu
   * listing "weeks" under a select reading "week" would look like.
   */
  function unitWord(v, n) {
    const u = D.UNITS.filter(x => x.v === v)[0];
    if (!u || u.offOnly || u.last) return u ? u.w : v;
    return n === 1 ? u.w.replace(/s$/, '') : u.w;
  }

  function unitSelect(st, opts) {
    const o = opts || {};
    const n = st.n || 1;
    const unit = st.mode === 'off' ? 'Off' : st.mode === 'cron' ? 'Custom (cron)' : unitWord(st.mode, n);
    const sel = `<span class="select sch-unit">${esc(unit)}</span>`;
    if (!o.open) return sel;
    // The shipped .menu, drawn open. A native select popup cannot be rendered.
    const items = D.UNITS.map(u => {
      const on = u.v === st.mode;
      const rule = u.last ? ' sch-mi-last' : '';
      return `<div class="menu-item${rule}${on ? ' sch-mi-on' : ''}"><span class="mi-ic">${on ? '✓' : ''}</span>${esc(unitWord(u.v, n))}</div>`;
    }).join('');
    return `<span class="menu-wrap open sch-unit-wrap">${sel}<div class="menu sch-menu">${items}</div></span>`;
  }

  function days(st) {
    return `<div class="sch-days">` + D.DAY_TOGGLE.map((d, i) =>
      `<span class="${st.days.indexOf(i) >= 0 ? 'on' : ''}" title="${esc(D.DAY_LONG[i])}">${esc(d)}</span>`).join('') + `</div>`;
  }

  /** The lines above the foot: one decision each, in the same order at every width. */
  function lines(st, opts) {
    const o = opts || {};
    const L = [];
    const bad = st.invalid ? ' bad' : '';

    if (st.mode === 'off' || st.mode === 'cron') {
      L.push(`<div class="sch-line"><span class="sch-lead">Repeat</span>${unitSelect(st, o)}</div>`);
    } else {
      L.push(`<div class="sch-line"><span class="sch-lead">Repeat every</span>` +
        `<span class="input sch-n${bad}">${esc(st.n)}</span>${unitSelect(st, o)}</div>`);
    }

    if (st.mode === 'weeks') L.push(`<div class="sch-line"><span class="sch-lead">on</span>${days(st)}</div>`);

    if (st.mode === 'months') {
      L.push(`<div class="sch-line"><span class="sch-lead">on</span>` +
        `<div class="seg sch-mshape">` +
        `<span class="${st.monthShape === 'day' ? 'on' : ''}">a day</span>` +
        `<span class="${st.monthShape === 'weekday' ? 'on' : ''}">a weekday</span></div></div>`);
      if (st.monthShape === 'day') {
        L.push(`<div class="sch-line"><span class="sch-lead">day</span>` +
          `<span class="select sch-mday">${esc(st.monthDay === 'last' ? 'Last day' : st.monthDay)}</span></div>`);
      } else {
        L.push(`<div class="sch-line"><span class="sch-lead">the</span>` +
          `<span class="select sch-ord">${esc(D.ORDINALS[st.ordinal])}</span>` +
          `<span class="select sch-ordday">${esc(D.DAY_LONG[st.ordDay])}</span></div>`);
      }
    }

    if (st.mode === 'years') {
      L.push(`<div class="sch-line"><span class="sch-lead">on</span>` +
        `<span class="select sch-month">${esc(D.MON_LONG[st.month])}</span>` +
        `<span class="select sch-dom">${esc(st.dayOfMonth)}</span></div>`);
    }

    if (st.mode === 'cron') {
      L.push(`<div class="sch-line sch-line-wide"><span class="input mono sch-cron${bad}">${esc(st.cron)}</span></div>`);
    }

    if (st.mode === 'hours') {
      L.push(`<div class="sch-line"><span class="sch-lead">at minute</span>` +
        `<span class="input mono sch-min">${esc(st.minute)}</span>` +
        `<span class="sch-zone">${esc(st.zone || D.ZONE)}</span></div>`);
    } else if (st.at) {
      L.push(`<div class="sch-line"><span class="sch-lead">at</span>` +
        `<span class="input mono sch-at">${esc(st.at)}</span>` +
        `<span class="sch-zone">${esc(st.zone || D.ZONE)}</span></div>`);
    }

    if (st.from) {
      L.push(`<div class="sch-line"><span class="sch-lead">starting</span>` +
        `<span class="input sch-from">${esc(st.fromText)}</span></div>`);
    }

    return `<div class="sch-lines">${L.join('')}</div>`;
  }

  /** The foot. Always both halves, always both slots — never collapsed. */
  function foot(st) {
    // Not a `.badge`: see schedule-picker.css. Carrying `.badge.bad` as well
    // put the sentence back in `--bad` at a specificity this file's own rule
    // could not reach, which is how a comment claiming "needs no light twin"
    // stopped being true without anything changing in this file.
    const saysBody = st.invalid
      ? `<div class="sch-err">${esc(st.error)}</div>`
      : esc(st.says);
    const says = `<div class="sch-says${st.invalid ? ' bad' : ''}">${saysBody}</div>`;

    let nextBody;
    if (st.next.length) {
      nextBody = `<ol class="sch-runs">` + st.next.map(r => {
        const p = r.split(' · ');
        return `<li><span class="d">${esc(p[0])}</span><span class="t mono">${esc(p[1])}</span></li>`;
      }).join('') + `</ol>`;
    } else {
      nextBody = `<div class="sch-none">${esc(st.nextEmpty || 'No next runs while the schedule is refused.')}</div>`;
    }
    const look = st.lookAhead ? `<div class="sch-look">${esc(st.lookAhead)}</div>` : '';

    // The slot never goes away (FR-3.8), but its heading does not promise five
    // when it is about to explain that there are none. Drawn as a fixed
    // "Next five runs" first; over "Nothing scheduled." it read as a broken
    // list rather than as an answer.
    const head = st.next.length ? 'Next five runs' : 'Next runs';

    return `<div class="sch-foot">${says}` +
      `<div class="sch-next"><div class="sch-next-h">${head}</div>${nextBody}${look}</div></div>`;
  }

  /** THE control. One function, used by both homes, unchanged between them. */
  function control(st, opts) {
    const o = opts || {};
    return `<div class="sch${st.invalid ? ' bad' : ''}${o.wide ? ' wide' : ''}" data-mode="${st.mode}">` +
      lines(st, o) + foot(st) + `</div>`;
  }

  // ── home 1: the auto-fetcher side panel ──────────────────────────────────

  function panel(st, opts) {
    const o = opts || {}, H = D.HOMES.panel;
    const dirty = !st.invalid;
    const save = `<span class="btn sm${dirty ? '' : ' af-dis'}">Save</span>`;
    const why = st.saveHeld ? `<div class="af-why sch-held">${esc(st.saveHeld)}</div>` : '';
    return `<div class="afx sch-panel" style="width:${H.width}px">` +
      `<div class="af-h">${esc(H.fetcher)} · settings</div>` +
      `<div class="af-cfg sch-cfg"><div class="field sch-field">` +
      `<label>${esc(H.field)}</label>${control(st, o)}</div></div>` +
      `<div class="af-cfg-go sch-go">${save}<span class="spacer"></span></div>${why}</div>`;
  }

  // ── home 2: a Processing feature row ─────────────────────────────────────

  function row(st, opts) {
    const o = opts || {};
    const feature = o.feature || D.FEATURES.find;
    const when = st.invalid
      ? `<b>Not saved</b> · the schedule is refused`
      : st.mode === 'off'
        ? `<b>Off</b> · started by hand only`
        : `Next <b>${esc(st.next[0].replace(' · ', ' '))}</b>`;
    return `<div class="pfx sch-rowhost"><div class="card"><div class="pf-list">` +
      `<div class="pf-row open${st.mode === 'off' ? ' off' : ''}">` +
      `<div class="pf-head"><div><span class="toggle${st.mode === 'off' ? '' : ' on'}"></span></div>` +
      `<div class="pf-name">${esc(feature)}</div>` +
      `<div class="pf-when">${when}</div><div></div><div class="pf-chev">⌄</div></div>` +
      `<div class="pf-body"><div class="field sch-field"><label>Schedule</label>` +
      `${control(st, Object.assign({ wide: true }, o))}</div></div></div>` +
      `</div></div></div>`;
  }

  // ── the shape comparison, all three at the panel's 280 px ────────────────

  function shapeLines(st) { return `<div class="sch-shape"><div class="sch-shape-h">Lines</div>${control(st)}</div>`; }

  function shapeSentence(st) {
    const dd = D.DAY_TOGGLE.map((d, i) =>
      `<span class="${st.days.indexOf(i) >= 0 ? 'on' : ''}">${esc(d)}</span>`).join('');
    return `<div class="sch-shape"><div class="sch-shape-h">Sentence</div>` +
      `<div class="sch sch-sent"><div class="sch-flow">Repeat every ` +
      `<span class="input sch-n">${esc(st.n)}</span> <span class="select sch-unit">${esc(unitWord(st.mode, st.n))}</span> on ` +
      `<span class="sch-days">${dd}</span> at <span class="input mono sch-at">${esc(st.at)}</span> ` +
      `<span class="sch-zone">${esc(D.ZONE)}</span></div>` + foot(st) + `</div></div>`;
  }

  function shapeSummary(st) {
    return `<div class="sch-shape"><div class="sch-shape-h">Summary and a sheet</div>` +
      `<div class="sch sch-sum"><div class="sch-says">${esc(st.says)}</div>` +
      `<div class="sch-sumline"><span class="btn sm ghost">Change…</span>` +
      `<span class="sch-nextone">Next <b>${esc(st.next[0].replace(' · ', ' '))}</b></span></div></div></div>`;
  }

  function shapes() {
    const st = D.state('weeks');
    return `<div class="sch-shapes">${shapeLines(st)}${shapeSentence(st)}${shapeSummary(st)}</div>` +
      `<div class="sch-verdicts">` + D.SHAPES.map(s =>
        `<div class="sch-verdict ${s.verdict}"><h4>${esc(s.w)} <span class="badge ${s.verdict === 'kept' ? 'ok' : 'flat'}">${s.verdict}</span></h4>` +
        `<p><b>Gives</b> ${esc(s.gives)}</p><p><b>Costs</b> ${esc(s.costs)}</p></div>`).join('') + `</div>`;
  }

  // ── length ───────────────────────────────────────────────────────────────

  /**
   * The length cases. Each is a state in sp-data.js — asserted like every
   * other one — rather than a copy patched here, which is how revision 1 came
   * out with seven weekdays toggled on over a list of five Wednesdays.
   */
  function longest(narrow) {
    const w = narrow ? D.HOMES.panel.width : 1100;
    return D.STATES.filter(s => s.length).map(st =>
      `<div class="sch-long"><div class="sch-long-h">${esc(st.title || st.key)}</div>` +
      `<div class="sch-long-why">${esc(st.why)}</div>` +
      `<div class="sch-long-box" style="width:${w}px">${control(st, { wide: !narrow })}</div></div>`
    ).join('');
  }

  // ── the unit menu, open ──────────────────────────────────────────────────

  function menu() {
    const st = D.state('weeks');
    return `<div class="sch-menushot">${panel(st, { open: true })}` +
      `<div class="sch-menunote"><b>Off</b> heads the menu where the setting can be off (FR-3.10); ` +
      `<b>Custom (cron)</b> is last (FR-3.1), under a rule, because it is the one entry that is not a unit.</div></div>`;
  }

  window.SPB = { control, panel, row, shapes, longest, menu };
})();
