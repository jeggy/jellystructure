// R225 (FR-235-8) / R263 — everything here runs before ravilo.js exists, which is why it's the one
// place a notice, a feature probe or a service-worker registration can live at all under this app's
// CSP (script-src 'self', no 'unsafe-inline' — see phase 235's §Dev review item 2).

// ---------------------------------------------------------------------------------------------
// FR-R263-1 — the floor is checked before the bundle is fetched, by feature detection
// (WebAssembly.validate), never UA sniffing. The probe is a hand-assembled module declaring one
// WasmGC struct type with zero fields — the smallest module an engine with no GC support cannot
// parse (pre-GC wasm only allows 0x60 "functype" in the type section; 0x5F "structtype" is a GC-only
// discriminator). Verified directly against a real WasmGC-supporting engine (Node 22 / V8) before
// being pasted here: WebAssembly.validate(bytes) === true there, and the shape of the failure for an
// engine with no GC support is a type-section parse error, which validate() reports as false rather
// than throwing (same guarantee either way — this is wrapped in try/catch below regardless).
//
// Text form (WAT): (module (type $s (struct)))
var RAVILO_WASM_GC_PROBE = new Uint8Array([
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // \0asm, version 1
    0x01, 0x03, 0x01, 0x5f, 0x00                    // type section: 1 type, struct, 0 fields
]);

function raviloSupportsWasmGC() {
    try { return typeof WebAssembly !== 'undefined' && WebAssembly.validate(RAVILO_WASM_GC_PROBE); }
    catch (e) { return false; }
}

function raviloShowUnsupportedNotice() {
    var ua = navigator.userAgent || '';
    var isIOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && (navigator.maxTouchPoints || 0) > 1);
    var lang = ((navigator.language || 'en').slice(0, 2)).toLowerCase();
    // Drafts (da/fo) — the shipped Kotlin string table has no bearing here (dev review item 2: these
    // can never render from Kotlin, since nothing Kotlin exists yet when this runs).
    var STRINGS = {
        en: {
            ios: "Ravilo can't run on this device. It needs iOS 18.2 or newer.",
            browser: "Ravilo can't run in this browser. It needs a browser from 2024 or newer.",
        },
        da: {
            ios: "Ravilo kan ikke køre på denne enhed. Den kræver iOS 18.2 eller nyere.",
            browser: "Ravilo kan ikke køre i denne browser. Den kræver en browser fra 2024 eller nyere.",
        },
        fo: {
            ios: "Ravilo kann ikki koyra á hesum tólinum. Tað krevur iOS 18.2 ella nýggjari.",
            browser: "Ravilo kann ikki koyra í hesum kagaranum. Tað krevur ein kagara frá 2024 ella nýggjari.",
        },
    };
    var t = STRINGS[lang] || STRINGS.en;
    document.body.innerHTML =
        '<div style="display:flex;align-items:center;justify-content:center;height:100%;padding:32px;' +
        'color:#fff;font:15px/1.5 -apple-system,BlinkMacSystemFont,sans-serif;text-align:center;">' +
        (isIOS ? t.ios : t.browser) + '</div>';
}

if (!raviloSupportsWasmGC()) {
    // No request for ravilo.js or any .wasm — the whole point of checking before appending the tag
    // rather than letting the bundle fail to instantiate after a multi-megabyte download.
    raviloShowUnsupportedNotice();
} else {
    // ---------------------------------------------------------------------------------------------
    // FR-R263-7 — standalone mode is an app, not a page.
    var raviloStandalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
    if (raviloStandalone) {
        document.documentElement.style.overscrollBehavior = 'none';
    }

    // Fullscreen toggle (F key or button) — not rendered at all in standalone mode (FR-R263-7): a
    // installed app has no need to ask for the fullscreen API it's already effectively in.
    var fsBtn = document.getElementById('fs-btn');
    if (!raviloStandalone && (document.fullscreenEnabled || document.webkitFullscreenEnabled)) {
        // R279 — the only two strings drawn before the bundle exists. The table is generated from
        // i18n/*.json, and the language is the one LastLanguage remembered, so this button is in the
        // household's language on every visit after the first. Falls back to the base language.
        var shell = (window.raviloShellStrings || {});
        var remembered = null;
        try { remembered = localStorage.getItem('ravilo.lang'); } catch (e) { /* blocked site data */ }
        var shellStrings = shell[remembered] || shell['en'] || {};
        fsBtn.textContent = shellStrings['web.fullscreen'] || '\u26F6 Fullscreen';
        fsBtn.title = shellStrings['web.fullscreen_title'] || 'Toggle fullscreen';
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

    // Shared by the mobile-keyboard bridge and the gamepad poller below — both feed Compose's key
    // handling by re-firing whatever they receive as a synthetic KeyboardEvent on the canvas, since
    // CanvasBasedWindow has no DOM text field of its own for either to target instead.
    function raviloFireCanvasKey(key) {
        var canvas = document.getElementById('ComposeTarget');
        if (!canvas) return;
        ['keydown', 'keyup'].forEach(function(t) {
            canvas.dispatchEvent(new KeyboardEvent(t, {key: key, bubbles: true}));
        });
    }

    // R281 — mobile on-screen keyboard bridge. CanvasBasedWindow routes all text entry through
    // Compose's own focus system on a single canvas (see TextFieldFocusBridge.kt's doc comment) —
    // there is no DOM `<input>` for document.activeElement to ever become, and no mobile browser
    // raises its keyboard for anything else. On a coarse-pointer (touch) device only, the hidden
    // #ravilo-kb-bridge input steps in as that DOM anchor: window.raviloMobileKeyboardBridge(focused)
    // — called from TextFieldFocusBridge.kt on every Compose text-field focus/blur, the same edge
    // that already drives the fullscreen-toggle guard above — focuses or blurs it, and its own
    // beforeinput/keydown handlers translate what the on-screen keyboard produces into a
    // raviloFireCanvasKey call each (proved to reach Compose's own key handling, single Unicode
    // characters included, the same way the gamepad poller below already does for D-pad keys).
    // Composition input (CJK IME) isn't bridged — this household's languages (en/da/fo) don't need
    // it, and beforeinput.preventDefault() below is what keeps the hidden input's own value from
    // ever accumulating anything to bridge in the first place.
    (function() {
        var isTouch = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
        var bridge = document.getElementById('ravilo-kb-bridge');
        if (!isTouch || !bridge) return;

        window.raviloMobileKeyboardBridge = function(focused) {
            if (focused) {
                bridge.value = '';
                bridge.focus();
            } else if (document.activeElement === bridge) {
                bridge.blur();
            }
        };

        bridge.addEventListener('beforeinput', function(e) {
            if (e.inputType === 'deleteContentBackward') {
                e.preventDefault();
                raviloFireCanvasKey('Backspace');
            } else if (e.inputType === 'deleteContentForward') {
                e.preventDefault();
                raviloFireCanvasKey('Delete');
            } else if (e.inputType && e.inputType.indexOf('insert') === 0 && e.data) {
                e.preventDefault();
                Array.from(e.data).forEach(raviloFireCanvasKey);
            }
        });

        bridge.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                raviloFireCanvasKey('Enter');
            }
        });
    })();

    // Gamepad polling — re-fires gamepad axis/button presses as keyboard events so the Compose focus
    // engine handles them identically to hardware D-pad. Harmless in standalone mode (FR-R263-7);
    // still runs there.
    (function() {
        var BUTTONS = {0: 'Enter', 1: 'Escape', 12: 'ArrowUp', 13: 'ArrowDown', 14: 'ArrowLeft', 15: 'ArrowRight'};
        var THRESHOLD = 0.5;
        var prevAxes = {}, prevButtons = {};

        function poll() {
            var pads = navigator.getGamepads ? navigator.getGamepads() : [];
            for (var i = 0; i < pads.length; i++) {
                var gp = pads[i];
                if (!gp) continue;
                gp.axes.forEach(function(v, ai) {
                    var key = ai < 2 ? (v < -THRESHOLD ? 'ArrowLeft' : v > THRESHOLD ? 'ArrowRight' : null)
                                     : (v < -THRESHOLD ? 'ArrowUp'   : v > THRESHOLD ? 'ArrowDown'  : null);
                    if (key && prevAxes[ai] !== key) raviloFireCanvasKey(key);
                    prevAxes[ai] = key;
                });
                gp.buttons.forEach(function(btn, bi) {
                    var key = BUTTONS[bi];
                    var pressed = btn.pressed;
                    if (key && pressed && !prevButtons[bi]) raviloFireCanvasKey(key);
                    prevButtons[bi] = pressed;
                });
            }
            requestAnimationFrame(poll);
        }
        window.addEventListener('gamepadconnected', function() { poll(); });
    })();

    // ---------------------------------------------------------------------------------------------
    // FR-R263-8 — Android's install affordance never fires unless something calls .preventDefault()
    // on this event and holds onto it; captured here (before ravilo.js exists) so the install card's
    // Compose code (which can't listen for a page-load-time event that fired before it was mounted)
    // can trigger it later via window.raviloTriggerInstall(). Absent entirely (already installed, or
    // a browser that can't install) ⇒ the card must render as absent, never a greyed button — it
    // checks for window.__raviloInstallPrompt itself.
    window.addEventListener('beforeinstallprompt', function(e) {
        e.preventDefault();
        window.__raviloInstallPrompt = e;
    });
    window.raviloTriggerInstall = function() {
        var e = window.__raviloInstallPrompt;
        if (!e) return;
        window.__raviloInstallPrompt = null;
        e.prompt();
    };
    window.addEventListener('appinstalled', function() {
        window.__raviloInstallPrompt = null;
    });

    // ---------------------------------------------------------------------------------------------
    // FR-R263-5 — registered after load so it never competes with the app's own first paint for the
    // network/CPU. sw.js itself (and its precache manifest) is generated at build time — see
    // ravilo-web/build.gradle.kts's :generateServiceWorker task. window.__raviloUpdateAvailable is the
    // bridge the Compose "Ravilo updated · Reload" toast polls (RaviloRootActuals.kt).
    if ('serviceWorker' in navigator) {
        window.addEventListener('load', function() {
            navigator.serviceWorker.register('sw.js').then(function(reg) {
                function watch(worker) {
                    if (!worker) return;
                    worker.addEventListener('statechange', function() {
                        if (worker.state === 'installed' && navigator.serviceWorker.controller) {
                            window.__raviloUpdateAvailable = true;
                        }
                    });
                }
                watch(reg.installing);
                reg.addEventListener('updatefound', function() { watch(reg.installing); });
            }).catch(function() { /* no service worker is not a hard failure — see FR-R263-5 */ });
        });
    }

    // Fetched from the app's own origin either way; loaded last, only once the checks above have
    // passed. Not a static <script> tag in index.html (FR-R263-1/dev review item 1): a static tag
    // would be requested regardless of what the probe found, which is exactly what "no request for
    // ravilo.js or any .wasm" rules out.
    var raviloMain = document.createElement('script');
    raviloMain.src = 'ravilo.js';
    document.body.appendChild(raviloMain);
}
