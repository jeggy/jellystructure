@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure

internal fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")
internal fun historyReplaceState(hash: String): Unit = js("window.history.replaceState(null,'',hash)")
internal fun decodeURIComponent(value: String): String = js("decodeURIComponent(value)")

internal fun prefersDark(): Boolean = js("window.matchMedia('(prefers-color-scheme: dark)').matches")

// Installs a persistent listener that updates data-theme when the OS preference changes,
// but only when the stored preference is "system".
internal fun installSystemThemeWatcher(): Unit =
    js("window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(e){if(localStorage.getItem('js-theme')==='system'){document.documentElement.setAttribute('data-theme',e.matches?'dark':'light')}})")

internal fun scrollIntoViewSmooth(el: JsAny): Unit =
    js("el.scrollIntoView({behavior:'smooth',block:'start'})")

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
