// FR-235-8 — moved out of an inline <script> in index.html: the corrected CSP's script-src has no
// 'unsafe-inline', so any script running before the wasm module loads has to live in its own file.
// This is exactly the old inline block, ported verbatim.

// Fullscreen toggle (F key or button)
var fsBtn = document.getElementById('fs-btn');
if (document.fullscreenEnabled || document.webkitFullscreenEnabled) {
    fsBtn.style.display = 'block';
    function toggleFs() {
        if (document.fullscreenElement || document.webkitFullscreenElement) {
            (document.exitFullscreen || document.webkitExitFullscreen).call(document);
        } else {
            var el = document.documentElement;
            (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
        }
    }
    fsBtn.onclick = toggleFs;
    document.addEventListener('keydown', function(e) {
        if ((e.key === 'f' || e.key === 'F') && !window.raviloTextFieldFocused) toggleFs();
    });
}

// Ensure canvas receives focus immediately for keyboard events
window.addEventListener('load', function() {
    var canvas = document.getElementById('ComposeTarget');
    if (canvas) canvas.focus();
});

// Gamepad polling — re-fires gamepad axis/button presses as keyboard events
// so the Compose focus engine handles them identically to hardware D-pad
(function() {
    var AXES = {0: 'ArrowLeft', 1: 'ArrowLeft', 2: 'ArrowRight', 3: 'ArrowRight'};
    var BUTTONS = {0: 'Enter', 1: 'Escape', 12: 'ArrowUp', 13: 'ArrowDown', 14: 'ArrowLeft', 15: 'ArrowRight'};
    var THRESHOLD = 0.5;
    var prevAxes = {}, prevButtons = {};

    function fireKey(key) {
        var canvas = document.getElementById('ComposeTarget');
        if (!canvas) return;
        ['keydown', 'keyup'].forEach(function(t) {
            canvas.dispatchEvent(new KeyboardEvent(t, {key: key, bubbles: true}));
        });
    }

    function poll() {
        var pads = navigator.getGamepads ? navigator.getGamepads() : [];
        for (var i = 0; i < pads.length; i++) {
            var gp = pads[i];
            if (!gp) continue;
            gp.axes.forEach(function(v, ai) {
                var key = ai < 2 ? (v < -THRESHOLD ? 'ArrowLeft' : v > THRESHOLD ? 'ArrowRight' : null)
                                 : (v < -THRESHOLD ? 'ArrowUp'   : v > THRESHOLD ? 'ArrowDown'  : null);
                if (key && prevAxes[ai] !== key) fireKey(key);
                prevAxes[ai] = key;
            });
            gp.buttons.forEach(function(btn, bi) {
                var key = BUTTONS[bi];
                var pressed = btn.pressed;
                if (key && pressed && !prevButtons[bi]) fireKey(key);
                prevButtons[bi] = pressed;
            });
        }
        requestAnimationFrame(poll);
    }
    window.addEventListener('gamepadconnected', function() { poll(); });
})();
