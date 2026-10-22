/* Ravilo phone — Support (round support-lane, decided 2026-10-14). Direction A "Presence": the ticket header is a
   chat header, state changes are inline system lines, the pending instant is composer-local (server-pushed state
   only — the bubble appears when the echo arrives). Reached from Profile › Support; the unread dot sits on the
   Profile avatar. Host hooks: RaviloHost.openAcct / closeAcct / esc. Stand-ins only. */
(function(){
  const S = {
    ph:'Write to support…', phRe:'Write here again to reopen', you:'you', writing:'AI Agent is writing',
    open:'Open', resolved:'Resolved', waiting:'waiting for a first reply', owner:'the owner', ownerLine:'Hervør will answer here',
    replied:'replied in 12 minutes', fwdAgo:'forwarded 2 days ago', myTickets:'My tickets', write:'Write to support',
    hint:'Tell us what happened and where — which TV or phone helps.', from:'Sent from Ravilo on the phone',
    emptyT:'No tickets yet', emptyP:'Something wrong, or something you wish Ravilo did? Write to support — a reply usually comes within minutes.',
    sending:'Sending…', notSent:'Not sent — you’re offline. Your message is kept here.', retry:'Try again', newReply:'1 new reply',
    cls:{bug:'Bug',request:'Request',question:'Question'}
  };
  const T = {
    41:{cls:'bug', st:'open', holder:'hv', extra:S.replied, unread:true, when:'21:43',
        title:'Sound drops in Nordvest S02E04 on the sitting-room TV',
        msgs:[
          {k:'me',t:'Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room',time:'20:14'},
          {k:'sys',v:'recv',t:'Received · <b>#41</b> · logged as a bug · titled “Sound drops in Nordvest S02E04 on the sitting-room TV”',time:'20:14'},
          {k:'ai',t:'Thanks, Eyð. That sounds like the second audio track dropping at a chapter change. Does the sound come back if you pause and play, or only after restarting the episode?',time:'20:14'},
          {k:'me',t:'Only after restarting. It happened twice tonight.',time:'20:16'},
          {k:'ai',t:'I’ll forward this and they will answer you soon.',time:'20:16'},
          {k:'sys',v:'fwd',t:'AI Agent forwarded this to <b>Hervør</b>',time:'20:16'},
          {k:'sys',v:'hand',av:'hv',t:'<b>Hervør</b> is handling this',time:'20:21'},
          {k:'hv',t:'Hi Eyð — the episode carries two audio tracks, and the TV in the sitting room switches between them at 09:52. I’ve pinned the Danish track for that TV. Could you try it again tonight?',time:'20:28'}]},
    38:{cls:'request', st:'owner', holder:'hv', extra:S.fwdAgo, when:'Mon',
        title:'Remember audio language per series',
        msgs:[
          {k:'me',t:'could it remember my audio language per series',time:'Mon 18:02'},
          {k:'sys',v:'recv',t:'Received · <b>#38</b> · logged as a request · titled “Remember audio language per series”',time:'18:02'},
          {k:'ai',t:'Logged. Remembering the audio language per series is a change to how Ravilo works, so it needs the owner’s decision — I can’t promise it here. I’ve passed it on, and you’ll hear back in this thread.',time:'18:02'},
          {k:'sys',v:'owner',av:'hv',t:'Forwarded to the owner · <b>Hervør</b> will answer here',time:'18:03'}]},
    36:{cls:'question', st:'ok', holder:'okc', when:'3 Oct',
        title:'Keeping subtitles on by default',
        msgs:[
          {k:'me',t:'how do I keep subtitles on by default',time:'3 Oct 21:10'},
          {k:'sys',v:'recv',t:'Received · <b>#36</b> · logged as a question · titled “Keeping subtitles on by default”',time:'21:10'},
          {k:'ai',t:'Open your profile → Settings → Playback → Subtitles and pick <b>Always on</b>. It applies to every device you’re signed in on, including the TV in the sitting room.',time:'21:10'},
          {k:'me',t:'found it, thanks',time:'21:14'},
          {k:'sys',v:'ok',t:'Resolved by <b>Hervør</b>',time:'4 Oct 09:20'}]}
  };
  const ORDER = [41, 38, 36];
  let cur = null, draft = '', busy = null, nextNum = 42;
  const H = () => window.RaviloHost;
  const esc = s => H() ? H().esc(s) : String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const I = {
    back:'<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
    send:'<svg viewBox="0 0 24 24"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
    check:'<svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>',
    chat:'<svg class="ic" viewBox="0 0 24 24"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-5A8 8 0 1 1 21 12z"/></svg>',
    chev:'<svg class="chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>'
  };
  const av = (k, c) => k==='ai' ? '<span class="sup-av ai '+(c||'')+'"><i></i></span>'
    : k==='hv' ? '<span class="sup-av hv '+(c||'')+'">H</span>'
    : k==='okc' ? '<span class="sup-av okc '+(c||'')+'">'+I.check+'</span>'
    : '<span class="sup-av none '+(c||'')+'"></span>';
  const who = k => k==='me' ? '<b>Eyð</b><span>· '+S.you+'</span>'
    : k==='ai' ? '<b>AI Agent</b><span class="sup-bdg ai">AI</span>'
    : '<b>Hervør</b><span class="sup-bdg ai">AI</span><span class="sup-bdg">support</span>';
  const msg = m => m.k==='sys'
    ? '<div class="sup-sys '+(m.v||'')+'"><span>'+(m.av?av(m.av):'')+'<i></i>'+m.t+(m.time?'<time>'+m.time+'</time>':'')+'</span></div>'
    : m.k==='typing'
    ? '<div class="sup-m ai">'+av('ai')+'<div class="b"><div class="w">'+who('ai')+'<span class="wr">'+S.writing+'</span></div><div class="t typing"><i></i><i></i><i></i></div></div></div>'
    : '<div class="sup-m '+m.k+'">'+(m.k==='me'?'':av(m.k))+'<div class="b"><div class="w">'+who(m.k)+'<time>'+m.time+'</time></div><div class="t">'+m.t+'</div></div></div>';
  function pres(t){
    const k = t.st==='ok' ? 'ok' : t.st==='wait' ? 'wait' : t.st==='owner' ? 'own' : '';
    const txt = t.st==='ok' ? S.resolved+' · by Hervør'
      : t.st==='wait' ? S.open+' · '+S.waiting
      : t.st==='owner' ? S.open+' · with '+S.owner+' · '+S.ownerLine
      : t.st==='ai' ? S.open+' · with AI Agent'
      : S.open+' · with Hervør'+(t.extra?' · '+t.extra:'');
    return '<div class="sup-pres '+k+'"><i></i><span>'+txt+'</span></div>';
  }
  const unreadCount = () => ORDER.filter(n => T[n].unread).length;
  function paintDot(){
    const a = document.getElementById('avatarBtn'); if(a) a.classList.toggle('sup-unread', unreadCount()>0);
    const r = document.querySelector('[data-pf="support"] .rsub'); if(r) r.textContent = unreadCount() ? S.newReply : ORDER.length+' tickets';
  }
  /* ---- screens ---- */
  function listHTML(){
    const rows = ORDER.map(n => { const t = T[n]; return '<button class="sup-tk'+(t.unread?' un':'')+'" data-sup="open:'+n+'">'+av(t.holder)
      +'<span class="b"><span class="r1"><span class="num">#'+n+'</span><span class="sup-cl">'+S.cls[t.cls]+'</span><time>'+t.when+'</time></span>'
      +'<span class="ti">'+(t.unread?'<u></u>':'')+esc(t.title)+'</span>'+pres(t)+'</span></button>'; }).join('');
    return '<div class="acct-bar"><button data-acct="back">‹</button><h2>'+S.myTickets+'</h2></div>'
      + '<div class="sup-list">'+(ORDER.length ? rows : '<div class="sup-emp">'+I.chat+'<h3>'+S.emptyT+'</h3><p>'+S.emptyP+'</p></div>')
      + '<button class="sup-cta'+(ORDER.length?' ghost':'')+'" data-sup="new">'+S.write+'</button></div>';
  }
  function threadHTML(){
    const t = cur==='new' ? null : T[cur];
    const head = t
      ? '<div class="sup-hd"><button class="bk" data-sup="list">'+I.back+'</button><div class="tx"><div class="kk">#'+cur+' <span class="sup-cl">'+S.cls[t.cls]+'</span></div><h1>'+esc(t.title)+'</h1>'+pres(t)+'</div>'+av(t.holder,'lg')+'</div>'
      : '<div class="sup-hd"><button class="bk" data-sup="list">'+I.back+'</button><div class="tx"><div class="kk">—</div><h1>New ticket</h1>'+pres({st:'wait'})+'</div>'+av('none','lg')+'</div>';
    const body = t ? t.msgs.map(msg).join('') + (busy && busy.typing ? msg({k:'typing'}) : '')
      : '<div class="sup-intro"><h2>'+S.write+'</h2><p>'+S.hint+'</p><small>'+S.from+' · Eyð</small></div>';
    const lock = busy && busy.lock;
    const note = lock ? '<div class="sup-cpn lock">'+S.sending+'</div>' : (busy && busy.fail ? '<div class="sup-cpn nsent">'+S.notSent+'<b data-sup="send">'+S.retry+'</b></div>' : '');
    const comp = '<div class="sup-cp">'+(lock?'<i class="prog"></i>':'')
      + '<textarea class="f" id="supField" rows="1" placeholder="'+(t&&t.st==='ok'?S.phRe:S.ph)+'"'+(lock?' disabled':'')+'>'+esc(draft)+'</textarea>'
      + (lock ? '<span class="s"><i class="rg"></i></span>' : '<button class="s'+(draft.trim()?'':' off')+'" data-sup="send">'+I.send+'</button>')+'</div>';
    return '<div class="sup-scn">'+head+'<div class="sup-th" id="supTh">'+body+'</div>'+note+comp+'</div>';
  }
  /* the pushed layer scrolls its own content; the thread screen takes the layer's full height and pads for the bottom bar
     plus whichever mini bar (cast / music) is docked above it, so the composer is never covered */
  /* re-render only the parts that changed when the thread is already up, so bubbles can enter with motion
     instead of the whole layer repainting; a full openAcct only when the screen itself changes */
  function show(html, what){
    const scnOld = document.querySelector('.sup-scn');
    if(scnOld && html.indexOf('<div class="sup-scn">')===0){
      const tmp = document.createElement('div'); tmp.innerHTML = html; const scnNew = tmp.firstChild;
      const thOld = scnOld.querySelector('#supTh'), thNew = scnNew.querySelector('#supTh');
      const oldN = thOld.children.length, newN = thNew.children.length;
      // header (presence may have changed)
      const hdOld = scnOld.querySelector('.sup-hd'), hdNew = scnNew.querySelector('.sup-hd');
      if(hdOld.innerHTML !== hdNew.innerHTML){ hdOld.innerHTML = hdNew.innerHTML; const p = hdOld.querySelector('.sup-pres'); if(p){ p.classList.add('flash'); } }
      // thread: drop a stale typing indicator, append what is new
      const typ = thOld.querySelector('.sup-m.ai .typing'); if(typ && !thNew.querySelector('.typing')){ const row = typ.closest('.sup-m'); row.classList.add('out'); setTimeout(() => row.remove(), 180); }
      const intro = thOld.querySelector('.sup-intro'); if(intro && !thNew.querySelector('.sup-intro')) intro.remove();
      const have = [...thOld.children].filter(x => !x.classList.contains('out')).length;
      for(let k = have; k < newN; k++){ const el = thNew.children[k].cloneNode(true); el.classList.add('in'); thOld.appendChild(el); }
      // composer + strip
      [...scnOld.querySelectorAll('.sup-cpn')].forEach(x => x.remove());
      const cpOld = scnOld.querySelector('.sup-cp'), cpNew = scnNew.querySelector('.sup-cp'), noteNew = scnNew.querySelector('.sup-cpn');
      if(noteNew){ noteNew.classList.add('in'); scnOld.insertBefore(noteNew, cpOld); }
      cpOld.replaceWith(cpNew);
      padForBars(scnOld);
      requestAnimationFrame(() => requestAnimationFrame(() => { thOld.scrollTo({ top: thOld.scrollHeight, behavior: 'smooth' }); }));
      return;
    }
    H().openAcct(html, what||'support');
    const scn = document.querySelector('.sup-scn'); if(scn) padForBars(scn);
    const th = document.getElementById('supTh'); if(th) requestAnimationFrame(() => { th.scrollTop = th.scrollHeight; });
  }
  /* the pushed layer sits under the bottom bar and under any docked mini bar (cast / music); measure what is
     actually docked and pad the screen's foot so the composer always clears it */
  function padForBars(scn){
    const bar = document.getElementById('bnav'); const barH = bar ? bar.offsetHeight : 104;
    let miniH = 0; document.querySelectorAll('.screen > *').forEach(el => { if(el === bar || el.classList.contains('acct') || el.classList.contains('detail') || el.classList.contains('scroll') || el.classList.contains('psheet') || el.classList.contains('sheet-scrim') || el.classList.contains('statusbar')) return; const cs = getComputedStyle(el); if(cs.position === 'absolute' && cs.display !== 'none' && el.offsetHeight > 0 && el.offsetHeight < 140 && /mini|dock/.test(el.className)) miniH = Math.max(miniH, el.offsetHeight); });
    scn.style.paddingBottom = (barH + miniH + (miniH ? 8 : 0)) + 'px';
  }
  function openList(){ cur = null; busy = null; show(listHTML()); }
  function openThread(n){ cur = n; draft = ''; busy = null; if(T[n]) { T[n].unread = false; paintDot(); } show(threadHTML()); }
  /* AI Agent names the case from its first message (server-side in production; a stand-in here): first clause,
     capitalised, trimmed to ~56 chars. The viewer's message is never the title — it stays the first message. */
  function aiTitle(text){
    let t = text.trim().replace(/\s+/g,' ').split(/[.!?\n]/)[0];
    t = t.replace(/^(hi|hello|hey)[,!]?\s*/i,'').replace(/^(could it|can it|could you|can you|please|would it be possible to)\s+/i,'').replace(/^how do i\s+/i,'');
    // drop where/when tails — the thread keeps them, the title does not need them
    t = t.replace(/,?\s+(on|in|at|from|with|even|since|about|after|every|when|while)\s.*$/i, m => (t.length - m.length) >= 18 ? '' : m);
    t = t.replace(/\s+(keeps|is|are|was|were)\s+(\w+ing)\b/i, (m,a,b) => ' '+b).replace(/\bloses its\b/i,'loses');
    if(t.length > 48) t = t.slice(0, 48).replace(/\s\S*$/,'');
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
  /* the send: lock → (server echo) bubble + receipt → AI Agent writing → reply. No optimistic bubble. */
  function send(){
    const text = draft.trim(); if(!text || (busy && busy.lock)) return;
    busy = {lock:true}; show(threadHTML());
    setTimeout(() => {
      const now = new Date(), hhmm = now.getHours()+':'+String(now.getMinutes()).padStart(2,'0');
      if(cur==='new'){ const n = nextNum++; const title = aiTitle(text); T[n] = {cls:'bug', st:'wait', holder:'none', when:hhmm, title:title, msgs:[{k:'me',t:esc(text),time:hhmm},{k:'sys',v:'recv',t:'Received · <b>#'+n+'</b> · logged as a bug · titled “'+esc(title)+'”',time:hhmm}]}; ORDER.unshift(n); cur = n; }
      else { const t = T[cur]; const wasOk = t.st==='ok'; t.msgs.push({k:'me',t:esc(text),time:hhmm}); if(wasOk){ t.msgs.push({k:'sys',v:'reopen',t:'Reopened — you wrote again',time:hhmm}); t.st='open'; t.holder='hv'; t.extra=''; } }
      draft = ''; busy = {typing:true}; const t = T[cur]; if(t.st==='wait'){ t.st='ai'; t.holder='ai'; } show(threadHTML());
      setTimeout(() => { busy = null; const t = T[cur]; t.msgs.push({k:'ai',t:t.st==='open' ? 'Thanks — I’ve added this to the thread. Hervør has it and will answer here.' : 'Thanks, Eyð. I’ve logged it and I’m looking at it now — you’ll hear back in this thread.',time:hhmm}); show(threadHTML()); }, 1600);
    }, 900);
  }
  document.addEventListener('click', e => {
    const b = e.target.closest('[data-sup]'); if(!b) return;
    const a = b.dataset.sup;
    if(a==='list') openList();
    else if(a==='new'){ cur = 'new'; draft=''; busy=null; show(threadHTML()); }
    else if(a.indexOf('open:')===0) openThread(+a.slice(5));
    else if(a==='send') send();
  });
  document.addEventListener('input', e => { if(e.target.id!=='supField') return; draft = e.target.value; e.target.style.height='auto'; e.target.style.height=Math.min(e.target.scrollHeight,120)+'px'; const s = e.target.parentNode.querySelector('.s'); if(s) s.classList.toggle('off', !draft.trim()); });
  window.RaviloSupport = {
    profileRow(){ return '<button class="pft-row" data-pf="support">'+I.chat+'<span>Support<span class="rsub">'+(unreadCount()?S.newReply:ORDER.length+' tickets')+'</span></span>'+(unreadCount()?'<u class="sup-dot"></u>':'')+I.chev+'</button>'; },
    open: openList, paintDot, unread: unreadCount
  };
  document.addEventListener('DOMContentLoaded', paintDot); setTimeout(paintDot, 0);
})();
