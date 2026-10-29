/* aiv-build.js — round `ai-per-viewer-and-schedule`, 2026-10-29.
 *
 * The builders for the four surfaces the round draws. Every value they put on screen comes out of
 * `aiv-data.js` (window.AIV); nothing in here invents a name, a count, a price or a date, and
 * nothing computes one — which is also how the admin works (it renders server-pushed state).
 *
 * The markup is the SHIPPED markup wherever the surface already exists, copied from the admin's
 * own source so the pictures are the real components and not a likeness:
 *   - the Re-rank job box: SettingsAi.kt `aiJobCardHtml` (.box + .field + .toggle + .tiny.muted)
 *   - the feature row:     SettingsProcessing.kt `rowHtml`/`partHtml` (.pf-row/.pf-head/.pf-part)
 *   - the Scan dialog:     ScanDialog.kt `twoPanes`/`pick`/`pane` (.pf-dlg/.sd-two/.sd-one/.tick)
 *   - the AI card:         Activity.kt `aiJobHtml` (.jobrow/.jq-pos/.jq-main/.chip/.wk-dot)
 * What this round ADDS is marked with a comment at each site.
 */
(function () {
  'use strict';

  var D = window.AIV;

  /* ─────────────────────────────────────────────────────────────────────────── small helpers */
  function sw(on, extra) {
    return '<span class="toggle' + (on ? ' on' : '') + (extra ? ' ' + extra : '') +
      '" role="switch" aria-checked="' + !!on + '" tabindex="0"></span>';
  }
  /* the dialog's own tick, ScanDialog.kt `tickHtml`: on · part · dis · blank */
  function tick(state) {
    var glyph = state === 'part' ? '–' : '✓';
    var checked = state === 'on' ? 'true' : state === 'part' ? 'mixed' : 'false';
    return '<span class="tick' + (state ? ' ' + state : '') + '" role="checkbox" aria-checked="' +
      checked + '">' + glyph + '</span>';
  }
  function hint(t) { return '<p class="hint" style="margin:6px 0 10px">' + t + '</p>'; }

  /* ═══════════════════════════════════════════════ 1. Settings ▸ AI — the Re-rank job's viewers
   * `variant`: 'small' | 'long' | 'loading' | 'aioff' | 'said' | 'saving'
   */
  function viewerRow(v, opts) {
    opts = opts || {};
    var cls = 'vw-row' + (v.test ? ' vw-test' : '') + (opts.saving ? ' vw-saving' : '');
    var seenNone = v.seen.indexOf('Never') === 0;
    var orderNone = v.order === 'No AI order yet';
    return '<div class="' + cls + '">' +
      '<div class="vw-n">' + v.name + '<span class="vw-why">' +
        (opts.saving ? D.viewerStates.saving : (opts.why || v.why)) + '</span></div>' +
      '<div class="vw-cell' + (seenNone ? ' vw-none' : '') + '">' + (seenNone ? v.seen : '<b>' + v.seen + '</b>') + '</div>' +
      '<div class="vw-cell' + (orderNone ? ' vw-none' : '') + '">' + v.order + '</div>' +
      '<div class="vw-sw">' + sw(v.on, opts.dis ? 'dis' : '') + '</div>' +
      /* NEW: what a switch-off left behind, on the row it happened on */
      (opts.said ? '<div class="vw-said">' + D.viewerStates.switchedOff + '</div>' : '') +
      '</div>';
  }

  function viewerHead() {
    return '<div class="vw-head"><span>Jellyfin user</span><span>Ravilo device</span>' +
      '<span>Recommended row</span><span>Re-rank</span></div>';
  }

  /* the bar over the list: the filter, the three tabs and the server's count line */
  function viewerBar(which, active) {
    var tabs = D.list.tabs.map(function (t, i) {
      return '<span' + (i === (active || 0) ? ' class="on"' : '') + '>' + t + '</span>';
    }).join('');
    return '<div class="vw-bar">' +
      '<input class="input" type="search" placeholder="' + D.list.filterPlaceholder + '">' +
      (which === 'long' ? '<span class="seg">' + tabs + '</span>' : '') +
      '<span class="spacer" style="flex:1"></span>' +
      '<span class="vw-count">' + (which === 'long' ? D.list.lineLong : D.list.lineSmall) + '</span>' +
      '</div>';
  }

  function skeletonList() {
    var row = '<div class="vw-sk-row"><span class="vw-sk w3"></span><span class="vw-sk w2"></span>' +
      '<span class="vw-sk w1"></span><span class="vw-sk-sw"></span></div>';
    return '<div class="vw-list">' + row + row + row + row + '</div>' +
      '<div class="vw-shown">' + D.viewerStates.loadingLine + '</div>';
  }

  function viewerList(variant) {
    if (variant === 'loading') return skeletonList();
    var rows;
    if (variant === 'long') {
      /* the five extra `qa-…` rows the long list shows, then the fold for the rest */
      var extra = D.extraTest.map(function (e) {
        return viewerRow({ name: e.name, on: false, why: 'off — test account', seen: e.seen,
          order: 'Standard order', test: true });
      }).join('');
      rows = D.viewers.map(function (v) { return viewerRow(v); }).join('') + extra +
        '<div class="vw-fold"><div><div class="t">' + D.list.foldedHead + '</div>' +
        '<div class="s">' + D.list.foldedSub + '</div></div>' +
        '<button class="btn sm ghost">Show them</button></div>';
    } else if (variant === 'said') {
      rows = D.viewers.map(function (v, i) {
        if (i === 3) return viewerRow({ name: v.name, on: false, why: D.viewerStates.switchedOffWhen,
          seen: v.seen, order: 'Standard order', test: false }, { said: true });
        if (i === 8) return viewerRow(v, { saving: true });
        return viewerRow(v);
      }).join('');
    } else {
      rows = D.viewers.map(function (v) { return viewerRow(v, { dis: variant === 'aioff' }); }).join('');
    }
    return viewerHead() + '<div class="vw-list">' + rows + '</div>' +
      (variant === 'long' ? '<div class="vw-shown">' + D.list.shownLong + '</div>' : '');
  }

  /* The Re-rank job's box, as SettingsAi.kt draws it, with the list added under its status lines.
     The three `.tiny.muted` lines (spend, last run, the month's estimate) are the shipped ones; the
     estimate's wording is the one FR-3.6 changes. */
  /* the new section alone: the heading, its sentence, the bar and the list. Drawn on its own for
     the first-paint picture, because the fields above it come from the config call and are NOT a
     skeleton at that moment — showing the whole box as a skeleton would be a nicer picture of
     something that does not happen. */
  function listSection(variant) {
    return '<div style="font-size:.86rem;font-weight:600">Re-rank with AI</div>' +
      '<p class="hint" style="margin:4px 0 0">Every user the media server can read, so one can be switched off before their first device signs in. A user with no choice of their own is on — unless their name starts with <b>Test</b> or <b>qa-</b>, which is a test account and is off.</p>' +
      (variant === 'loading' ? '' : viewerBar(variant === 'long' ? 'long' : 'small', 0)) +
      viewerList(variant);
  }

  function jobBox(variant) {
    var off = variant === 'aioff';
    if (variant === 'loading') {
      return '<div class="pfx"><div class="box" style="border:1px solid var(--line);border-radius:8px;padding:14px 16px">' +
        listSection('loading') + '</div></div>';
    }
    return '<div class="pfx">' +
      (off ? '<div class="pf-note warn" style="margin-bottom:14px"><b>' + D.viewerStates.aiOffHead +
        '</b> ' + D.viewerStates.aiOffWhy + '</div>' : '') +
      '<div class="box" style="border:1px solid var(--line);border-radius:8px;padding:14px 16px">' +
        '<div style="display:flex;align-items:center;justify-content:space-between">' +
          '<span style="font-size:.92rem;font-weight:600">Recommendations re-rank</span>' +
          sw(!off, off ? 'dis' : '') +
        '</div>' +
        hint('After the recommendations build, the model reads each viewer’s top 100 and keeps the 50 it would pick, in order, with a reason you can see on Users &amp; devices. It never adds a title of its own. If anything goes wrong, the viewer keeps the standard list.') +
        '<div style="display:flex;gap:14px;flex-wrap:wrap;align-items:flex-end">' +
          '<div class="field" style="margin:0"><label>Model</label>' +
            '<select class="select" style="width:340px;max-width:100%"><option>' + D.activity.model +
            ' · $1 / $5 per M tokens</option></select></div>' +
          '<div class="field" style="margin:0"><label>Monthly limit (USD)</label>' +
            '<input class="input" type="number" value="5" style="width:110px"></div>' +
        '</div>' +
        '<div class="tiny muted" style="margin-top:10px">Spent this month: $4.91 of $5.00 · 37 waiting (Activity ▸ Jobs &amp; workers)</div>' +
        '<div class="tiny muted" style="margin-top:2px">Last run: 27 Oct 04:11 · ran 4 viewers</div>' +
        /* FR-3.6 — the month's estimate follows the schedule and counts only the viewers switched on */
        '<div class="tiny muted" style="margin-top:2px">' + D.parts.recs.estimate + '</div>' +
        /* NEW: one switch per Jellyfin user (FR-1.1) */
        '<hr class="aiv-rule">' +
        listSection(variant) +
      '</div></div>';
  }

  /* ═════════════════════════════════════ 2. Settings ▸ Processing — the row and its two pickers */

  /* the production interval picker (SettingsProcessing.kt `findBodyHtml`), with `monthly` added */
  function picker(label, shot) {
    var modes = D.modes.map(function (m) {
      return '<option' + (m.v === shot.mode ? ' selected' : '') + '>' + m.label + '</option>';
    }).join('');
    var controls;
    if (shot.mode === 'everyN') {
      controls = '<input class="input" type="number" value="' + shot.n + '" style="width:72px;text-align:center">' +
        '<span class="muted tiny">hours, at minute</span>' +
        '<input class="input" type="number" value="' + shot.m + '" style="width:72px;text-align:center">';
    } else if (shot.mode === 'daily') {
      controls = '<input class="input qv-time" type="time" value="' + shot.at + '">';
    } else if (shot.mode === 'weekly') {
      controls = '<select class="select">' + D.weekdays.map(function (d, i) {
        return '<option' + (i === shot.dow ? ' selected' : '') + '>' + d + '</option>';
      }).join('') + '</select><span class="muted tiny">at</span>' +
        '<input class="input qv-time" type="time" value="' + shot.at + '">';
    } else if (shot.mode === 'monthly') {
      /* NEW (FR-3.1): a day from 1 to 28 and a time. Days 29–31 are left out, and the note says so
         where the number is typed rather than in a paragraph under the whole row. */
      controls = '<span class="muted tiny">day</span>' +
        '<input class="input qv-day" type="number" min="1" max="28" value="' + shot.day + '">' +
        '<span class="muted tiny">at</span>' +
        '<input class="input qv-time" type="time" value="' + shot.at + '">' +
        '<span class="muted tiny">' + D.monthlyNote + '</span>';
    } else {
      controls = '<input class="input mono" type="text" value="' + shot.cron + '" style="width:230px">' +
        '<span class="muted tiny">' + D.customNote + '</span>';
    }
    var check = shot.bad
      ? '<span class="badge bad">' + shot.bad + '</span>'
      : shot.check
        ? '<span class="badge ok" style="margin-right:6px">✓ ' + shot.check + '</span>' +
          (shot.next ? '<div class="tiny muted" style="margin-top:4px">' + shot.next + '</div>' : '')
        : '';
    return '<div class="pf-every"><span class="muted tiny">' + label + '</span>' +
      '<select class="select">' + modes + '</select>' + controls + '</div>' +
      (check ? '<div class="tiny qv-check" style="margin-top:6px">' + check + '</div>' : '');
  }

  /* NEW (FR-2.1): the quiet-viewer choice, under the picker it qualifies */
  function quiet(which, chosen) {
    var opts = D.quiet.options.map(function (o) {
      return '<option' + (o.v === chosen ? ' selected' : '') + '>' + o.label + '</option>';
    }).join('');
    var said = D.quiet.options.filter(function (o) { return o.v === chosen; })[0][which];
    return '<div class="qv"><div class="pn">' + D.quiet.label + '</div>' +
      '<div class="qv-row" style="margin-top:7px"><select class="select">' + opts + '</select></div>' +
      '<div class="qv-said">' + said + '</div></div>';
  }

  function part(p, which, shot) {
    return '<div class="pf-part" style="grid-template-columns:44px minmax(0,1fr)">' +
      '<div>' + sw(true) + '</div><div>' +
        '<div class="pn">' + p.name + '</div>' +
        '<div class="ph-line">' + p.help + '</div>' +
        '<div style="margin-top:10px">' + picker('Build ' + p.name.toLowerCase(), shot || p) + '</div>' +
        quiet(which, p.quiet) +
      '</div></div>';
  }

  function settingsRow() {
    return '<div class="pfx"><div class="card" style="padding:4px 18px">' +
      '<div class="pf-list"><div class="pf-row open">' +
        '<div class="pf-head">' +
          '<div>' + sw(true) + '</div>' +
          '<div class="pf-name">' + D.row.name + '</div>' +
          '<div class="pf-when">' + D.row.when + '</div>' +
          '<div><span class="badge ok">On</span></div>' +
          '<div class="pf-chev">⌃</div>' +
        '</div>' +
        '<div class="pf-body">' +
          part(D.parts.recs, 'recs') +
          part(D.parts.sugg, 'sugg') +
          '<div class="pf-note">' + D.quiet.note + '</div>' +
          '<div class="pf-note q"><b>Queued work is not done when the scan ends.</b> ' +
            'Both lists are built inside the run itself. <a href="#">Activity</a>.</div>' +
        '</div>' +
      '</div></div>' +
    '</div></div>';
  }

  /* the five modes, drawn open beside each other. CANVAS composition: the admin shows one. */
  function pickerShots(ph) {
    var cells = D.modeShots.map(function (s, i) {
      var label = s.bad ? 'Custom · refused' : D.modes.filter(function (m) { return m.v === s.mode; })[0].label;
      return '<div><div class="aiv-lab">' + label + '</div>' +
        '<div class="card" style="padding:14px 16px">' + picker('Build recommendations', s) +
        '<div class="tiny muted" style="margin-top:8px;font-family:\'JetBrains Mono\',monospace">' +
        (s.cron || '') + '</div></div></div>';
    }).join('');
    return '<div class="pfx"><div class="aiv-cols' + (ph ? '' : ' three') + '">' + cells + '</div></div>';
  }

  /* the two shapes the quiet choice was drawn in, side by side at the width it really has */
  function quietShapes() {
    var segOpts = D.quiet.options.map(function (o, i) {
      return '<span' + (i === 0 ? ' class="on"' : '') + '>' + o.label + '</span>';
    }).join('');
    return '<div class="pfx"><div class="aiv-cols">' +
      '<div><div class="aiv-lab">Drawn and kept · a select and the chosen option’s sentence</div>' +
        '<div class="card aiv-narrow" style="padding:14px 16px">' + quiet('recs', 'skip_ai') + '</div></div>' +
      '<div><div class="aiv-lab">Drawn and dropped · a three-way seg</div>' +
        /* the same `.qv` label treatment as the kept shape, or the two are not comparable */
        '<div class="card aiv-narrow" style="padding:14px 16px"><div class="qv" style="margin-top:0">' +
          '<div class="pn">' + D.quiet.label + '</div>' +
          '<span class="seg" style="margin-top:9px">' + segOpts + '</span>' +
          '<div class="qv-said">' + D.quiet.options[0].recs + '</div>' +
        '</div></div></div>' +
      '</div></div>';
  }

  /* ═══════════════════════════════════════════════════════════════════ 3. the Scan dialog */

  function pick(f, selected) {
    var line = f.count + (f.queues ? ' · <span class="q">queues work</span>' : '');
    return '<div class="pick' + (f.id === selected ? ' on' : '') + '">' +
      tick(f.on) + '<div><div class="n">' + f.name + '</div><div class="c">' + line + '</div></div>' +
      '<span class="go">›</span></div>';
  }

  /* one part of Recommendations & suggestions in the right pane.
     `state`: 'on' (ticked) | 'off' (unticked); `ai`: 'off' | 'on' | 'dis' */
  function dlgPart(p, state, ai) {
    /* The dependent tick is SHOWN in all three cases, never hidden: disabled because the part is
       not in this scan, disabled because the limit leaves too little, or live. Hiding it would
       make the part's height jump as its own tick is pressed, and would lose the one line that
       says what asking the AI would have cost. */
    var why = ai === 'dis' ? p.aiDisabled : (state === 'off' ? p.aiNotInScan : null);
    var aiBlock = '<div class="sd-ai' + (why ? ' dis' : ai === 'on' ? ' on' : '') + '">' +
      tick(why ? 'dis' : ai === 'on' ? 'on' : '') +
      '<div><div class="t">' + D.dialog.aiTick + '</div>' +
      '<div class="s">' + p.ai + '</div>' +
      (why ? '<div class="why">' + why + '</div>' : '') +
      '</div></div>';
    return '<div class="sd-one' + (state === 'off' ? ' sd-untick' : '') + '">' +
      tick(state === 'on' ? 'on' : '') +
      '<div class="n">' + p.name +
        '<div class="sd-does">' + (state === 'on' ? p.does : p.skips) + '</div>' +
        aiBlock +
      '</div></div>';
  }

  function pane(o) {
    var ticked = (o.recs === 'on' ? 1 : 0) + (o.sugg === 'on' ? 1 : 0);
    return '<div class="sd-pane-head"><h3>Recommendations &amp; suggestions</h3>' +
      '<span class="c">' + ticked + ' of 2 ticked</span></div>' +
      '<div class="sd-lede">' + D.dialog.paneLede + '</div>' +
      '<div class="sd-parts">' +
        dlgPart(D.dialog.recs, o.recs, o.recsAi) +
        dlgPart(D.dialog.sugg, o.sugg, o.suggAi) +
      '</div>';
  }

  /* Which of the server's summary sentences this dialog's ticks select. Derived from the ticks
     rather than named per frame: the first render of `dlg-limit` was given the wrong one by hand
     and printed a summary that contradicted its own two ticked parts. The dialog SELECTS from
     what the server sent (FR-4.4) — this is that selection, and it cannot now disagree. */
  function summaryKey(o) {
    if (o.recs === 'on' && o.sugg === 'on') return 'limit';
    if (o.recsAi === 'on' || o.suggAi === 'on') return 'rerank';
    return 'none';
  }

  /* NEW (FR-4.4): the one summary line, as a band above Start scan */
  function summary(which) {
    var s = D.dialog.summary[which];
    var ai = s.ai === 'none'
      ? '<span class="ai-none">none</span>'
      : '<span class="ai-on">' + s.ai + '</span>';
    /* Three clauses, each its own box. Run together as one sentence, `Skips:` ended a line with
       its own list on the next one and read as a label with nothing after it. */
    return '<div class="sd-sum">' +
      '<span class="cl"><b>Runs:</b> ' + s.runs + '</span>' +
      '<span class="cl"><b>Skips:</b> <span class="skips">' + s.skips + '</span></span>' +
      '<span class="cl"><b>AI:</b> ' + ai + '</span></div>';
  }

  function head(title, lede, back) {
    return '<div class="pf-dlg-head">' +
      (back ? '<div class="sd-back">‹ All features</div>' : '') +
      '<h2>' + title + '</h2><p>' + lede + '</p>' +
      (back ? '' : '<div class="pill-row" style="margin-top:10px"><span class="chip act">Films</span></div>') +
      '</div>';
  }

  function recheckBlock() {
    return '<div class="sd-top"><div class="pf-all">' + sw(false) +
      '<div class="fill"><div class="t">Re-check everything, not only what is due</div>' +
      '<div class="s">' + D.dialog.recheckOff + '</div></div></div></div>';
  }

  function legend() {
    return '<div class="sd-legend"><div class="pf-note q"><b>' + D.dialog.legendHead + '</b> ' +
      D.dialog.legend + ' <a href="#">Activity</a>.</div></div>';
  }

  function foot() {
    return '<div class="pf-dlg-foot"><span class="spacer" style="flex:1"></span>' +
      '<button class="btn ghost">Cancel</button>' +
      '<button class="btn primary">Start scan</button></div>';
  }

  /* The scrim is NOT drawn: `.pfx.pf-scrim` is `position: fixed` in production and would escape a
     frame that has no viewport of its own. The frame draws the dialog; the report says so. */
  function dialog(o) {
    var feats = D.dialog.features.map(function (f) {
      var ff = f;
      if (f.id === 'recs') {
        var n = (o.recs === 'on' ? 1 : 0) + (o.sugg === 'on' ? 1 : 0);
        ff = { id: f.id, name: f.name, count: n + ' of 2', on: n === 2 ? 'on' : n === 0 ? '' : 'part' };
      }
      return pick(ff, 'recs');
    }).join('');
    return '<div class="pfx"><div class="pf-dlg wide">' +
      head(D.dialog.title, D.dialog.lede) +
      recheckBlock() +
      '<div class="sd-two"><div class="left">' + feats + '</div>' +
      '<div class="right">' + pane(o) + '</div></div>' +
      legend() + summary(summaryKey(o)) + foot() +
      '</div></div>';
  }

  /* 412: step two of the drill-in — one feature's parts, with the back control. The dialog's TITLE
     is the feature name, so the pane's own heading is hidden by `.sd-one-pane` (production). */
  function dialogPhone(o) {
    return '<div class="pfx"><div class="pf-dlg">' +
      head('Recommendations &amp; suggestions', 'Tick the parts to run this time. Back keeps whatever you tick here.', true) +
      '<div class="pf-dlg-body"><div class="sd-one-pane">' + pane(o) + '</div></div>' +
      summary(summaryKey(o)) + foot() +
      '</div></div>';
  }

  /* ═══════════════════════════════════════════════════════ 4. Activity ▸ the AI card (FR-5.1) */
  function activityCard() {
    var a = D.activity;
    var rows = a.rows.map(function (r, i) {
      return '<div class="jobrow"><span class="jq-pos">' + (i + 1) + '</span>' +
        '<div class="jq-main"><div class="jq-title">' + r.who + '</div>' +
        '<div class="jq-sub">' + r.reason + ' · queued by ' + r.by + ' · ' + r.at + '</div></div>' +
        /* NEW (FR-5.1): the chip IS the state on a held row — the shipped `waiting` badge is
           dropped here rather than drawn beside it, because two words in one slot say the same
           thing twice (round 9's correction 8). */
        '<span class="chip">' + a.chip + '</span>' +
        '<span class="btn sm ghost">Remove</span></div>';
    }).join('');
    return '<div class="pfx"><div class="card" style="padding:16px 18px;max-width:1100px">' +
      '<div class="row center" style="gap:10px;margin-bottom:4px"><h4 style="margin:0">AI</h4>' +
      '<span class="tiny muted">what is queued, what is out at Anthropic, and what it has cost this month</span></div>' +
      '<hr class="aiv-rule" style="margin:10px 0">' +
      '<div class="row center" style="gap:10px;flex-wrap:wrap">' +
        '<span class="wk-dot idle"></span><b>' + a.job + '</b> <span class="badge">idle</span>' +
        '<span class="muted tiny">' + a.model + '</span>' +
        '<span class="spacer" style="flex:1"></span>' +
        '<span class="chip"><b>' + a.waiting + '</b> waiting</span>' +
        '<span class="chip"><b>' + a.batchOut + '</b> batch out</span>' +
        '<span class="chip">' + a.spent + '</span>' +
      '</div>' +
      /* the shipped held-back sentence, in a class with a light twin instead of an inline colour */
      '<div class="aiq-held">' + a.held + '</div>' +
      /* NEW (FR-5.1): one control, its count the server's */
      '<div class="aiq-acts"><button class="btn sm">' + a.removeAll + '</button>' +
        '<span class="tiny muted">A batch already out is not touched — stop that with <b>Cancel at Anthropic</b>.</span></div>' +
      rows +
      '<div class="tiny muted" style="padding:6px 0 0 34px">' + a.more + '</div>' +
      '</div></div>';
  }

  /* ════════════════════════════════════════════════════════════════════════════ the toasts */
  function toasts() {
    function t(x) {
      return '<div class="pf-toast"><span class="ic">✓</span><div><div>' + x.line + '</div>' +
        '<div class="s">' + x.sub + '</div></div></div>';
    }
    return '<div class="pfx"><div class="aiv-stack">' +
      '<div><div class="aiv-lab">Started with no AI asked for</div>' + t(D.dialog.toastNone) + '</div>' +
      '<div><div class="aiv-lab">Started with the re-rank asked for</div>' + t(D.dialog.toastAi) + '</div>' +
      '</div></div>';
  }

  /* ═══════════════════════════════════════════════════════════════════ the longest strings */
  function longest(ph) {
    var L = D.longest;
    var row = viewerRow({ name: L.name, on: false, why: L.why, seen: L.seen, order: L.order, test: true });
    return '<div class="pfx"><div class="aiv-stack">' +
      '<div><div class="aiv-lab">The longest Jellyfin name a list of every user can hold</div>' +
        '<div class="card" style="padding:4px 16px 10px">' + viewerHead() +
        '<div class="vw-list">' + row + '</div></div></div>' +
      '<div><div class="aiv-lab">The longest quiet-viewer choice and its sentence, in the ' +
        (ph ? 'full' : '300 px') + ' width it really has</div>' +
        '<div class="card aiv-narrow" style="padding:14px 16px">' + quiet('sugg', 'rebuild') + '</div></div>' +
      '<div><div class="aiv-lab">The longest schedule the server will describe back</div>' +
        '<div class="card" style="padding:14px 16px">' + picker('Build recommendations', D.modeShots[4]) + '</div></div>' +
      '<div><div class="aiv-lab">The longest summary line: everything ticked, both AI steps asked for</div>' +
        '<div class="card" style="padding:0;overflow:hidden">' + summary('longest') + '</div></div>' +
      '</div></div>';
  }

  window.AIVB = {
    jobBox: jobBox,
    settingsRow: settingsRow,
    pickerShots: pickerShots,
    quietShapes: quietShapes,
    dialog: dialog,
    dialogPhone: dialogPhone,
    activityCard: activityCard,
    toasts: toasts,
    longest: longest,
  };
})();
