package dev.jellystructure.ravilo.cast

import dev.jellystructure.shared.tv.LadderRules

/**
 * Phase 309 (FR-309-4/-5) — the receiver enforces the ladder rule over Shaka's own ABR, as Android's track selection does
 * over Media3's: never past the next rung up (and only with enough buffered), and down below the playing rung as soon as
 * the buffer is under 20 s and falling — whatever Shaka's throughput estimate says. Shaka's ABR is throughput-only: on
 * 2026-10-06 a cast stayed on a 24.6 Mbps rung while its own estimate said 15.4 Mbps and stalled 29 s with no step
 * down.
 *
 * CAF does not hand out its Shaka instance, but it passes `PlaybackConfig.shakaConfig` to `player.configure()`, and
 * Shaka builds its ABR manager from the config's `abrFactory`. The factory below wraps Shaka's own
 * `shaka.abr.SimpleAbrManager`: every call is forwarded, Shaka still chooses within the bound, and a timer every 2 s
 * applies the step-down when Shaka would not. The rule itself is [LadderRules] (shared, tested); this file only reads
 * the video element's buffer and the variants' bandwidths.
 *
 * Not verified on a device yet (no Chromecast in reach of this build): if CAF copies the config without its functions,
 * or this framework build's Shaka has another ABR interface, the factory is never called or returns null and Shaka's
 * own manager runs exactly as before — the wrapper can make nothing worse than today.
 */
internal object ReceiverAbr {
    /** Set from each ticket: on our own encoder every rung is running, so a climb needs only the stock buffer. */
    var oursEncoder: Boolean = false

    /** [LadderRules.allowedMaxBps] for the wrapper: -1 = no bound. [last] < 0 = no earlier sample. */
    private val decide: (dynamic, Double, Double, Double) -> Double = { rungs, current, buffered, last ->
        val list = (rungs as Array<Double>).map { it.toLong() }
        if (current < 0) -1.0
        else LadderRules.allowedMaxBps(list, current.toLong(), buffered.toLong(), if (last < 0) null else last.toLong(), oursEncoder)?.toDouble() ?: -1.0
    }

    /** The `abrFactory` to put in `shakaConfig`; [note] writes the receiver's debug line. */
    fun factory(note: (String) -> Unit): dynamic = js(FACTORY)(decide, note)
}

private const val FACTORY = """(function (decide, note) {
  return function () {
    var S = (typeof shaka !== 'undefined' && shaka.abr && shaka.abr.SimpleAbrManager) ? shaka.abr.SimpleAbrManager : null;
    if (!S) { note('309 abr: no shaka.abr.SimpleAbrManager here; Shaka chooses alone'); return null; }
    var inner = new S();
    var w = {};
    var proto = Object.getPrototypeOf(inner);
    while (proto && proto !== Object.prototype) {
      Object.getOwnPropertyNames(proto).forEach(function (k) {
        if (k !== 'constructor' && typeof inner[k] === 'function' && !(k in w)) {
          w[k] = function () { return inner[k].apply(inner, arguments); };
        }
      });
      proto = Object.getPrototypeOf(proto);
    }
    var cb = null, variants = [], media = null, cur = null, last = -1, enabled = false, timer = null;
    function element() {
      if (media) return media;
      var p = document.querySelector('cast-media-player');
      var v = (p && p.shadowRoot) ? p.shadowRoot.querySelector('video') : null;
      return v || document.querySelector('video');
    }
    function ahead(el) {
      if (!el || !el.buffered) return -1;
      var t = el.currentTime;
      for (var i = 0; i < el.buffered.length; i++) {
        if (el.buffered.start(i) <= t + 0.5 && el.buffered.end(i) >= t) return (el.buffered.end(i) - t) * 1000;
      }
      return 0;
    }
    function bands() { return variants.map(function (x) { return x.bandwidth; }); }
    function best(max) {
      var b = null;
      variants.forEach(function (x) { if (x.bandwidth <= max && (!b || x.bandwidth > b.bandwidth)) b = x; });
      return b;
    }
    function bound(v) {
      if (!v || !variants.length) return v;
      var a = ahead(element());
      if (a < 0) return v;
      var max = decide(bands(), cur ? cur.bandwidth : -1, a, last);
      if (max < 0 || v.bandwidth <= max) return v;
      return best(max) || v;
    }
    function tick() {
      var a = ahead(element());
      if (a >= 0 && enabled && cb && cur && variants.length) {
        var max = decide(bands(), cur.bandwidth, a, last);
        if (max >= 0 && cur.bandwidth > max) {
          var b = best(max);
          if (b && b !== cur) {
            note('309 abr: step down ' + cur.bandwidth + ' -> ' + b.bandwidth + ' (' + Math.round(a) + ' ms buffered)');
            cur = b;
            cb(b, false);
          }
        }
      }
      if (a >= 0) last = a;
    }
    w.init = function (switchCb) {
      cb = switchCb;
      return inner.init(function (v) {
        var args = Array.prototype.slice.call(arguments);
        var c = bound(v);
        cur = c;
        args[0] = c;
        return switchCb.apply(null, args);
      });
    };
    w.setVariants = function (vs) { variants = vs || []; return inner.setVariants.apply(inner, arguments); };
    if (typeof inner.setMediaElement === 'function') {
      w.setMediaElement = function (el) { media = el; return inner.setMediaElement.apply(inner, arguments); };
    }
    w.chooseVariant = function () {
      var c = bound(inner.chooseVariant.apply(inner, arguments));
      cur = c;
      return c;
    };
    w.enable = function () { enabled = true; if (!timer) timer = setInterval(tick, 2000); return inner.enable.apply(inner, arguments); };
    w.disable = function () { enabled = false; return inner.disable.apply(inner, arguments); };
    w.stop = function () { if (timer) { clearInterval(timer); timer = null; } enabled = false; return inner.stop.apply(inner, arguments); };
    w.release = function () {
      if (timer) { clearInterval(timer); timer = null; }
      return typeof inner.release === 'function' ? inner.release.apply(inner, arguments) : undefined;
    };
    note('309 abr: Shaka bounded by the ladder rule');
    return w;
  };
})"""
