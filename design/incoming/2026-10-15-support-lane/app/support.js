/* Jellystructure — Support (support-lane). Renders into #ai-support (AI page tab: list → detail with the forward
   control and the abuse flag) and into #view-support (Activity: the logs-only history). The count on the AI sidebar
   entry is the number of open tickets. Stand-in viewers, agents and titles only. */
(function () {
  const FIRST = 'Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room';
  const TITLE = 'Sound drops in Nordvest S02E04 on the sitting-room TV';   // written by AI Agent from the first message
  const T = {
    41: { viewer:'Jákup Hansen', handle:'jakup', device:'the TV in the sitting room', opened:'Tue 20:14', cls:'Bug', st:'open', holder:'hv', last:'Hervør · 21:43', unread:true, title:TITLE, sees:'Open · with Hervør · replied in 12 minutes',
      msgs:[
        {k:'vw',t:FIRST,time:'20:14'},
        {k:'sys',v:'recv',t:'Received · <b>#41</b> · logged as a bug · titled “'+TITLE+'”',time:'20:14'},
        {k:'ai',t:'Thanks, Jákup. That sounds like the second audio track dropping at a chapter change. Does the sound come back if you pause and play, or only after restarting the episode?',time:'20:14'},
        {k:'vw',t:'Only after restarting. It happened twice tonight.',time:'20:16'},
        {k:'ai',t:'I’ll forward this and they will answer you soon.',time:'20:16'},
        {k:'sys',v:'fwd',t:'AI Agent forwarded this to <b>Hervør</b>',time:'20:16'},
        {k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> is handling this',time:'20:21'},
        {k:'hv',t:'Hi Jákup — the episode carries two audio tracks, and the TV in the sitting room switches between them at 09:52. I’ve pinned the Danish track for that TV. Could you try it again tonight?',time:'20:28'},
        {k:'vw',t:'That did it. The sound stayed on.',time:'21:40'},
        {k:'hv',t:'Great — I’ll close this one. Write here again if it comes back.',time:'21:43'}] },
    38: { viewer:'Ása Dam', handle:'asa', device:'Ravilo on the phone', opened:'Mon 18:02', cls:'Request', st:'open', holder:'owner', last:'forwarded 2 days ago', title:'Remember audio language per series', sees:'Open · with the owner · Hervør will answer here',
      msgs:[
        {k:'vw',t:'could it remember my audio language per series',time:'Mon 18:02'},
        {k:'sys',v:'recv',t:'Received · <b>#38</b> · logged as a request · titled “Remember audio language per series”',time:'18:02'},
        {k:'ai',t:'Logged. Remembering the audio language per series is a change to how Ravilo works, so it needs the owner’s decision — I can’t promise it here. I’ve passed it on, and you’ll hear back in this thread.',time:'18:02'},
        {k:'sys',v:'owner',av:'hv',t:'Forwarded to the owner · <b>Hervør</b> will answer here',time:'18:03'}] },
    36: { viewer:'Sunnvør Holm', handle:'sunnva', device:'Ravilo on the web', opened:'3 Oct 21:10', cls:'Question', st:'ok', holder:'okc', last:'Hervør · 4 Oct', title:'Keeping subtitles on by default', sees:'Resolved · by Hervør',
      msgs:[
        {k:'vw',t:'how do I keep subtitles on by default',time:'3 Oct 21:10'},
        {k:'sys',v:'recv',t:'Received · <b>#36</b> · logged as a question · titled “Keeping subtitles on by default”',time:'21:10'},
        {k:'ai',t:'Open your profile → Settings → Playback → Subtitles and pick <b>Always on</b>. It applies to every device you’re signed in on, including the TV in the sitting room.',time:'21:10'},
        {k:'vw',t:'found it, thanks',time:'21:14'},
        {k:'sys',v:'ok',t:'Resolved by <b>Hervør</b>',time:'4 Oct 09:20'}] }
  };
  const ORDER = [41, 38, 36];
  const LOG = [
    ['Tue 21:43','hv','Hervør','resolved','#41'], ['Tue 20:28','hv','Hervør','replied · 12 min after forward','#41'], ['Tue 20:21','hv','Hervør','took the ticket','#41'],
    ['Tue 20:16','ai','AI Agent','forwarded to Hervør · could not answer','#41'], ['Tue 20:14','ai','AI Agent','classified as bug · titled the case · replied','#41'],
    ['Mon 18:03','ai','AI Agent','forwarded to the owner · request','#38'], ['Mon 18:02','ai','AI Agent','classified as request · titled the case · replied','#38'],
    ['4 Oct 09:20','hv','Hervør','resolved','#36'], ['3 Oct 21:10','ai','AI Agent','classified as question · titled the case · replied','#36']
  ];
  const openCount = () => ORDER.filter(n => T[n].st !== 'ok' && T[n].st !== 'flag').length;
  window.JS_SUPPORT_OPEN = openCount();
  const I = {
    back:'<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
    check:'<svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>'
  };
  const av = (k, c) => k==='ai' ? '<span class="tk-av ai '+(c||'')+'"><i></i></span>'
    : k==='hv' ? '<span class="tk-av hv '+(c||'')+'">H</span>'
    : k==='ad' ? '<span class="tk-av ad '+(c||'')+'">Ha</span>'
    : k==='okc' ? '<span class="tk-av okc '+(c||'')+'">'+I.check+'</span>'
    : k==='vw' ? '<span class="tk-av me '+(c||'')+'">'+(c&&c.ini||'')+'</span>'
    : '<span class="tk-av none '+(c||'')+'"></span>';
  const who = (k, t) => k==='vw' ? '<b>'+t.viewer+'</b><span class="tk-hnd">'+t.handle+'</span>'
    : k==='ai' ? '<b>AI Agent</b><span class="tk-bdg ai">AI</span>'
    : k==='hv' ? '<b>Hervør</b><span class="tk-bdg ai">AI</span><span class="tk-bdg">support</span>'
    : '<b>Hallur</b><span class="tk-bdg">admin</span>';
  const msg = (m, t) => m.k==='sys'
    ? '<div class="tk-sys '+(m.v||'')+'"><span>'+(m.av?av(m.av):'')+'<i></i>'+m.t+'<time>'+m.time+'</time></span></div>'
    : '<div class="tk-m '+m.k+(m.k==='ad'?' me':'')+'">'+(m.k==='vw'?'<span class="tk-av me">'+t.viewer[0]+'</span>':av(m.k))+'<div class="b"><div class="w">'+who(m.k,t)+'<time>'+m.time+'</time></div><div class="t">'+m.t+'</div></div></div>';
  const holder = t => t.holder==='hv' ? av('hv','sm')+'Hervør · support' : t.holder==='ad' ? av('ad','sm')+'Hallur · admin' : t.holder==='owner' ? av('none','sm')+'Owner · Hervør answers' : t.holder==='okc' ? av('okc','sm')+'—' : '<span class="muted">Nobody · no reply will be sent</span>';
  const stBadge = t => t.st==='ok' ? '<span class="badge ok">Resolved</span>' : t.st==='flag' ? '<span class="badge bad">Flagged</span>' : '<span class="badge info">Open</span>';

  function listHTML(filter) {
    const rows = ORDER.filter(n => { const t = T[n]; return filter==='all' || (filter==='open' && t.st==='open') || (filter==='ok' && t.st==='ok') || (filter==='flag' && t.st==='flag') || (filter==='me' && t.holder==='hv'); })
      .map(n => { const t = T[n]; return '<div class="tk-row'+(t.unread?' un':'')+'" data-tk="open:'+n+'"><span class="mono">'+(t.unread?'<u></u>':'')+'#'+n+'</span><span class="vw"><b>'+t.viewer+'</b><span class="tk-hnd">'+t.handle+'</span></span><span class="tt" title="'+t.title.replace(/"/g,'&quot;')+'">'+t.title+'</span><span><span class="tk-cl">'+t.cls+'</span></span><span>'+stBadge(t)+'</span><span class="tk-hold">'+holder(t)+'</span><span class="mono muted">'+t.last+'</span></div>'; }).join('');
    const f = (k, l) => '<span'+(k===filter?' class="on"':'')+' data-tk="filter:'+k+'">'+l+'</span>';
    return '<div class="tk-bar"><span class="seg">'+f('all','All · '+ORDER.length)+f('open','Open · '+openCount())+f('me','With Hervør')+f('ok','Resolved · '+ORDER.filter(n=>T[n].st==='ok').length)+f('flag','Flagged')+'</span></div>'
      + '<p class="page-sub" style="margin-bottom:16px">Every viewer’s tickets, here because AI Agent and Hervør answer them. The count on the AI entry in the sidebar is the number of open tickets. What the viewer sees is the thread, the state and the holder — never this page.</p>'
      + '<div class="card flat scrollbox" style="padding:14px 18px 6px"><div class="tk-tbl"><div class="tk-row hd"><span>#</span><span>Viewer</span><span>Ticket</span><span>Class</span><span>State</span><span>Holder</span><span>Last</span></div>'+(rows||'<div class="muted" style="padding:28px 0;text-align:center">Nothing here</div>')+'</div></div>';
  }
  function detailHTML(n) {
    const t = T[n], flag = t.st==='flag', fwd = t.fwd || (t.holder==='owner' ? 'owner' : 'hv');
    const ro = (k, label, sub) => '<label class="tk-ro'+(fwd===k?' on':'')+'" data-tk="fwd:'+k+'"><i></i>'+av(k==='owner'?'none':k,'sm')+'<span>'+label+(sub?'<small>'+sub+'</small>':'')+'</span></label>';
    return '<span class="tk-back" data-tk="list">'+I.back+'AI · Support</span>'
      + '<div class="tk-det"><section>'
      + '<div class="tk-dh"><div class="kk">#'+n+' · '+t.viewer+' · '+t.handle+' · '+t.device+' · opened '+t.opened+'</div><h2>'+t.title+'</h2></div>'
      + (flag ? '<div class="tk-flag"><i></i><div><b>Flagged as abuse by Hallur.</b> No reply will be sent, not even AI Agent’s. The viewer sees an open ticket and nothing about the flag.</div></div>' : '')
      + '<div class="tk-th">'+t.msgs.map(m => msg(m, t)).join('')+'</div>'
      + (flag ? '<div class="tk-cp"><div class="dead">Replies are off while this ticket is flagged.</div></div>'
              : '<div class="tk-cp"><textarea rows="2" placeholder="Reply as Hallur · admin — it sends itself and lands in the thread as Hallur · admin" id="tkReply"></textarea><span class="btn primary" data-tk="reply:'+n+'">Send</span></div>')
      + '</section><aside class="tk-rail">'
      + '<div class="tk-rc"><div class="tk-kv"><s>State</s><b>'+stBadge(t)+'</b></div><div class="tk-kv"><s>Class</s><b>'+t.cls+'</b></div><div class="tk-kv"><s>Title</s><b class="mute">by AI Agent · from the first message</b></div><div class="tk-kv"><s>Holder</s><b>'+holder(t)+'</b></div><div class="tk-kv"><s>Viewer sees</s><b class="mute">'+(flag?'Open · waiting for a first reply':t.sees)+'</b></div></div>'
      + '<div class="tk-rc'+(flag?' off':'')+'"><h4>Forward to</h4>'+ro('hv','Hervør · support')+ro('ad','Hallur · admin')+ro('owner','Owner','Cannot answer here — Hervør keeps the thread and owes the next reply.')
      + '<div class="tk-acts"><span class="btn primary sm" data-tk="forward:'+n+'">Forward</span><span class="btn sm okb" data-tk="resolve:'+n+'">Resolve</span></div></div>'
      + '<div class="tk-rc">'+(flag ? '<span class="btn sm wide" data-tk="unflag:'+n+'">Remove flag</span>' : '<span class="btn sm badb wide" data-tk="flag:'+n+'">Flag as abuse</span><small>Stops every reply, including AI Agent’s. The viewer is told nothing.</small>')+'</div>'
      + '<a class="tiny" href="activity.html?view=support">Support log for #'+n+' →</a></aside></div>';
  }
  function logHTML() {
    return '<p class="page-sub">History only — what every agent did and every action taken on a ticket. Nothing here is actionable; the tickets themselves live under <a href="ai.html?tab=support">AI › Support</a>.</p>'
      + '<div class="card flat" style="padding:6px 18px"><div class="tk-log">'+LOG.map(l => '<div class="tk-lg"><time>'+l[0]+'</time><b>'+av(l[1],'sm')+l[2]+'</b><span>'+l[3]+'</span><i>'+l[4]+'</i></div>').join('')+'</div></div>';
  }
  /* ---- mounts ---- */
  const mount = document.getElementById('ai-support'), logMount = document.getElementById('view-support');
  let view = 'list', filter = 'all', cur = null;
  function paint() {
    if (mount) mount.innerHTML = view==='list' ? listHTML(filter) : detailHTML(cur);
    document.querySelectorAll('.tk-cnt, .app-side a.nav[href="ai.html"] .count').forEach(c => { c.textContent = openCount(); c.style.display = openCount() ? '' : 'none'; });
  }
  if (logMount) logMount.innerHTML = logHTML();
  if (mount) {
    const main = document.querySelector('main.app-main2');
    const tabs = document.getElementById('ai-tabs');
    const setTab = t => { main.classList.toggle('tk-on', t==='support'); if (tabs) [...tabs.children].forEach(x => x.classList.toggle('on', x.dataset.tab===t)); };
    if (tabs) tabs.addEventListener('click', e => { const s = e.target.closest('[data-tab]'); if (s) setTab(s.dataset.tab); });
    setTab(new URLSearchParams(location.search).get('tab') === 'fetchers' ? 'fetchers' : 'support');   // Support first (owner, 2026-10-14)
    mount.addEventListener('click', e => {
      const b = e.target.closest('[data-tk]'); if (!b) return;
      const [a, v] = b.dataset.tk.split(':'); const t = T[v];
      if (a==='open') { cur = +v; view = 'detail'; T[cur].unread = false; }
      else if (a==='list') view = 'list';
      else if (a==='filter') filter = v;
      else if (a==='fwd') { T[cur].fwd = v; }
      else if (a==='forward') { const k = t.fwd || 'hv'; t.holder = k==='owner' ? 'owner' : k==='hv' ? 'hv' : 'ad'; t.st = 'open'; t.msgs.push(k==='owner' ? {k:'sys',v:'owner',av:'hv',t:'Forwarded to the owner · <b>Hervør</b> will answer here',time:'now'} : {k:'sys',v:'hand',av:k,t:'<b>'+(k==='hv'?'Hervør':'Hallur')+'</b> is handling this',time:'now'}); t.sees = k==='owner' ? 'Open · with the owner · Hervør will answer here' : 'Open · with '+(k==='hv'?'Hervør':'Hallur'); }
      else if (a==='resolve') { t.st = 'ok'; t.holder = 'okc'; t.msgs.push({k:'sys',v:'ok',t:'Resolved by <b>Hallur</b>',time:'now'}); t.sees = 'Resolved · by Hallur'; }
      else if (a==='flag') { t.st = 'flag'; t.holder = 'none'; }
      else if (a==='unflag') { t.st = 'open'; t.holder = 'hv'; }
      else if (a==='reply') { const f = document.getElementById('tkReply'); const txt = f && f.value.trim(); if (!txt) return; t.msgs.push({k:'ad',t:txt.replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])),time:'now'}); if (t.st==='ok') { t.st='open'; t.msgs.push({k:'sys',v:'reopen',t:'Reopened — Hallur wrote',time:'now'}); } t.holder='ad'; t.sees='Open · with Hallur'; }
      paint();
    });
    paint();
  }
  paint();
})();
