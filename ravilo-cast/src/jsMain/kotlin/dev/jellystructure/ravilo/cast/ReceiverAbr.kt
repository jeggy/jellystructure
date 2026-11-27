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
    /** The same video element and buffered range that the ABR wrapper's ahead() reads. */
    fun bufferedAheadMs(): Long {
        val read: dynamic = js("""(function () {
          var p = document.querySelector('cast-media-player');
          var v = (p && p.shadowRoot) ? p.shadowRoot.querySelector('video') : null;
          v = v || document.querySelector('video');
          if (!v || !v.buffered) return -1;
          var t = v.currentTime;
          for (var i = 0; i < v.buffered.length; i++) {
            if (v.buffered.start(i) <= t + 0.5 && v.buffered.end(i) >= t) return (v.buffered.end(i) - t) * 1000;
          }
          return 0;
        })""")
        return ((read() as? Number)?.toDouble() ?: -1.0).toLong()
    }

    /** DEB-5928 — the same element's `readyState` (2 or less: nothing to play past the frame on screen); -1 = unreadable. */
    fun readyState(): Int {
        val read: dynamic = js("""(function () {
          var p = document.querySelector('cast-media-player');
          var v = (p && p.shadowRoot) ? p.shadowRoot.querySelector('video') : null;
          v = v || document.querySelector('video');
          return v ? v.readyState : -1;
        })""")
        return (read() as? Number)?.toInt() ?: -1
    }

    /** Set from each ticket: on our own encoder every rung is running, so a climb needs only the stock buffer. */
    var oursEncoder: Boolean = false

    /** [LadderRules.allowedMaxBps] for the wrapper: -1 = no bound. [last] < 0 = no earlier sample. */
    private val decide: (dynamic, Double, Double, Double) -> Double = { rungs, current, buffered, last ->
        val list = (rungs as Array<Double>).map { it.toLong() }
        if (current < 0) -1.0
        else LadderRules.allowedMaxBps(list, current.toLong(), buffered.toLong(), if (last < 0) null else last.toLong(), oursEncoder)?.toDouble() ?: -1.0
    }

    /** DEB-5927 (FR-7) — segment fetches of this item so far; the estimate clause trusts Shaka's estimate only from 3. Set from each ticket. */
    var segmentsFetched: () -> Int = { 0 }

    /** [LadderRules.estimateStepDown] for the wrapper: the rung to fall to, or -1 for none. */
    private val estimateDown: (dynamic, Double, Double, Double) -> Double = { rungs, current, estimate, buffered ->
        val list = (rungs as Array<Double>).map { it.toLong() }
        LadderRules.estimateStepDown(list, current.toLong(), estimate.toLong(), segmentsFetched(), buffered.toLong())?.toDouble() ?: -1.0
    }

    /** [LadderRules.stallCapBps] for the wrapper: the rung one below, or -1 for none. */
    private val stallCap: (dynamic, Double) -> Double = { rungs, current ->
        val list = (rungs as Array<Double>).map { it.toLong() }
        LadderRules.stallCapBps(list, current.toLong())?.toDouble() ?: -1.0
    }

    /** The newest wrapper's hooks; the receiver calls [onStall] when a delivery stall ends. */
    private val hooks: dynamic = js("({})")

    /** DEB-5927 (FR-7) — a `buffer_empty` stall of 1 s or more caps the play one rung down at once (a no-op when no wrapper is live). */
    fun onStall() {
        runCatching { hooks.stall() }
    }

    /** The `abrFactory` to put in `shakaConfig`; [note] writes the receiver's debug line. */
    fun factory(note: (String) -> Unit): dynamic =
        js(FACTORY)(decide, estimateDown, stallCap, LadderRules.STALL_CAP_HOLD_MS.toDouble(), LadderRules.STALL_CAP_GAP_MS.toDouble(), hooks, note)
}

private const val FACTORY = """(function (decide, estimateDown, stallCap, holdMs, gapMs, hooks, note) {
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
    // DEB-5927 (FR-7) — the ceiling a stall set, when it was set, and how many checks in a row found the video starved.
    var ceiling = -1, ceilingAt = 0, lastCapAt = -1e9, starved = 0;
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
    function held() {
      if (ceiling >= 0 && Date.now() - ceilingAt >= holdMs) ceiling = -1;
      return ceiling;
    }
    function bound(v) {
      if (!v || !variants.length) return v;
      var cap = held();
      if (cap >= 0 && v.bandwidth > cap) v = best(cap) || v;
      var a = ahead(element());
      if (a < 0) return v;
      var max = decide(bands(), cur ? cur.bandwidth : -1, a, last);
      if (max < 0 || v.bandwidth <= max) return v;
      return best(max) || v;
    }
    function switchTo(b, why, a) {
      note('309 abr: step down ' + cur.bandwidth + ' -> ' + b.bandwidth + ' (' + why + ', ' + Math.round(a) + ' ms buffered)');
      cur = b;
      try { cb(b, false); } catch (e) { note('309 abr: switch refused: ' + e); }
    }
    // FR-7 — a stall caps one rung down at once and holds the play under the rung it left until 120 s pass without another.
    function capDown(why, a) {
      var now = Date.now();
      if (!enabled || !cb || !cur || !variants.length || variants.indexOf(cur) < 0 || now - lastCapAt < gapMs) return;
      var to = stallCap(bands(), cur.bandwidth);
      if (to < 0) return;
      var b = best(to);
      if (!b || b === cur || variants.indexOf(b) < 0) return;
      lastCapAt = now; ceiling = b.bandwidth; ceilingAt = now;
      switchTo(b, why, a);
    }
    var mine = function () { capDown('stall', Math.max(ahead(element()), 0)); };
    hooks.stall = mine;
    function starving(el) {
      return !!el && !el.paused && !el.seeking && !el.ended && el.readyState < 3 && el.currentTime > 0;
    }
    function tick() {
      var a = ahead(element());
      // 2026-10-09 (R291 live): right after a (re)load the buffer reads 0 ms before anything arrived. That is not a
      // falling buffer, and acting on it switched Shaka to a variant of the previous manifest: error 7999
      // ("Cannot read property 'retryParameters' of null"). Only a buffer that was positive and fell counts, and only
      // a variant of the current manifest is ever handed to Shaka.
      var falling = a > 0 || last > 0;
      if (a >= 0 && falling && enabled && cb && cur && variants.length && variants.indexOf(cur) >= 0) {
        var max = decide(bands(), cur.bandwidth, a, last);
        if (max >= 0 && cur.bandwidth > max) {
          var b = best(max);
          if (b && b !== cur && variants.indexOf(b) >= 0) switchTo(b, 'buffer', a);
        }
      }
      // DEB-5927 (FR-7) — a buffer pinned near empty is not "falling", so the two rules above never see it. FR-309-5's estimate clause:
      // Shaka's estimate x 0.8 under the rung playing, from 3 fetched segments, with under 20 s buffered.
      if (a >= 0 && enabled && cb && cur && variants.length && variants.indexOf(cur) >= 0 && typeof inner.getBandwidthEstimate === 'function') {
        var est = estimateDown(bands(), cur.bandwidth, inner.getBandwidthEstimate(), a);
        if (est >= 0) {
          var e2 = best(est);
          if (e2 && e2 !== cur && variants.indexOf(e2) >= 0) { ceiling = e2.bandwidth; ceilingAt = Date.now(); switchTo(e2, 'estimate', a); }
        }
      }
      // ... and a video that has had nothing to play for two checks in a row is a stall the buffer rules cannot see.
      starved = (a >= 0 && a < 1000 && starving(element())) ? starved + 1 : 0;
      if (starved >= 2) { starved = 0; capDown('starved', a); }
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
    w.setVariants = function (vs) { variants = vs || []; if (cur && variants.indexOf(cur) < 0) { cur = null; last = -1; } return inner.setVariants.apply(inner, arguments); };
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
    w.stop = function () { if (timer) { clearInterval(timer); timer = null; } enabled = false; variants = []; cur = null; last = -1; media = null; ceiling = -1; starved = 0; if (hooks.stall === mine) hooks.stall = null; return inner.stop.apply(inner, arguments); };
    w.release = function () {
      if (timer) { clearInterval(timer); timer = null; }
      if (hooks.stall === mine) hooks.stall = null;
      return typeof inner.release === 'function' ? inner.release.apply(inner, arguments) : undefined;
    };
    note('309 abr: Shaka bounded by the ladder rule');
    return w;
  };
})"""
