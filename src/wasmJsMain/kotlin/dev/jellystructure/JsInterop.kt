@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure

internal fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")
internal fun historyReplaceState(hash: String): Unit = js("window.history.replaceState(null,'',hash)")
internal fun historyPushState(hash: String): Unit = js("window.history.pushState(null,'',hash)")
internal fun decodeURIComponent(value: String): String = js("decodeURIComponent(value)")

internal fun prefersDark(): Boolean = js("window.matchMedia('(prefers-color-scheme: dark)').matches")

// Installs a persistent listener that updates data-theme when the OS preference changes,
// but only when the stored preference is "system".
internal fun installSystemThemeWatcher(): Unit =
    js("window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(e){if(localStorage.getItem('js-theme')==='system'){document.documentElement.setAttribute('data-theme',e.matches?'dark':'light')}})")

internal fun scrollIntoViewSmooth(el: JsAny): Unit =
    js("el.scrollIntoView({behavior:'smooth',block:'start'})")

// epochSecStr = epoch-seconds as decimal string; formats as HH:mm:ss same-day, or "MMM d, HH:mm" cross-day
internal fun formatStoredTs(epochSecStr: String): String = js("""(function(){
    var d=new Date(parseFloat(epochSecStr)*1000);
    var n=new Date();
    var same=d.getFullYear()===n.getFullYear()&&d.getMonth()===n.getMonth()&&d.getDate()===n.getDate();
    var tOpts={hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false};
    return same?d.toLocaleTimeString([],tOpts):d.toLocaleString([],{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false});
})()""")

// Phase 108: absolute "YYYY-MM-DD HH:mm" for a stored epoch-seconds value (passed as a string to
// sidestep Kotlin/Wasm's Long↔JS marshaling — same pattern as formatStoredTs above).
internal fun formatFullDateTime(epochSecStr: String): String = js("""(function(){
    var d = new Date(parseFloat(epochSecStr) * 1000);
    var p = function(n){ return String(n).padStart(2,'0'); };
    return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes());
})()""")

// Phase 108: relative "2y ago" / "4d ago" / "just now" hint for a stored epoch-seconds value.
internal fun formatRelativeAgo(epochSecStr: String): String = js("""(function(){
    var diffSec = (Date.now()/1000) - parseFloat(epochSecStr);
    if (diffSec < 60) return 'just now';
    var mins = Math.floor(diffSec/60); if (mins < 60) return mins+'m ago';
    var hrs = Math.floor(mins/60); if (hrs < 24) return hrs+'h ago';
    var days = Math.floor(hrs/24); if (days < 30) return days+'d ago';
    var months = Math.floor(days/30); if (months < 12) return months+'mo ago';
    var years = Math.floor(days/365); return years+'y ago';
})()""")

// Observes elements whose ids are in the comma-separated `idsCsv` string; calls `onVisible(id)`
// when one enters the viewport within the given rootMargin (CSS-style, e.g. "-10% 0px -80% 0px").
// True when the element's top edge is within [marginPx] of the viewport bottom — used by the
// Library infinite-scroll fill loop to decide whether the current viewport still needs more cards.
internal fun elemNearViewportBottom(id: String, marginPx: Int): Boolean =
    js("(function(){var el=document.getElementById(id);if(!el)return false;var r=el.getBoundingClientRect();return r.top <= (window.innerHeight + marginPx);})()")

internal fun observeSections(idsCsv: String, rootMargin: String, onVisible: (String) -> Unit): Unit =
    js("""(function(){
        var obs = new IntersectionObserver(function(entries){
            entries.forEach(function(e){ if(e.isIntersecting) onVisible(e.target.id); });
        },{rootMargin:rootMargin});
        idsCsv.split(',').forEach(function(id){
            var el=document.getElementById(id);
            if(el) obs.observe(el);
        });
    })()""")

internal fun callOpenAttentionDock(): Unit =
    js("(function(){ if(typeof window.openAttentionDock === 'function') window.openAttentionDock(); })()")

// Clipboard write with execCommand fallback for non-HTTPS (HTTP) contexts
internal fun copyToClipboard(text: String): Unit = js("""(function(){
    try { navigator.clipboard.writeText(text); }
    catch(e) {
        var ta=document.createElement('textarea');
        ta.value=text; ta.style.position='fixed'; ta.style.opacity='0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        try{ document.execCommand('copy'); }catch(_){}
        document.body.removeChild(ta);
    }
})()""")

// Phase 97 — Seeding surface JS bridge (window.Seeding from seeding.js)
internal fun seedingPillHtml(torrentsJson: String): String =
    js("window.Seeding ? window.Seeding.pillHTML(JSON.parse(torrentsJson)) : ''")

internal fun seedingRenderMovie(el: JsAny, reportJson: String, trackersJson: String): Unit =
    js("(function(){ if(!window.Seeding) return; if(window.Seeding.setTrackers) window.Seeding.setTrackers(JSON.parse(trackersJson)); window.Seeding.renderMovie(el, JSON.parse(reportJson)); })()")

internal fun seedingRenderSeries(el: JsAny, reportJson: String, trackersJson: String): Unit =
    js("(function(){ if(!window.Seeding) return; if(window.Seeding.setTrackers) window.Seeding.setTrackers(JSON.parse(trackersJson)); window.Seeding.renderSeries(el, JSON.parse(reportJson)); })()")
