# Phase R263 — Ravilo web installs

> Owner direction 2026-09-18: *"an iPhone installs the PWA and streams to the Samsung TV — our most
> important use case."* Ravilo web already boots on WebKit and lays out at phone width (measured in the
> research report `ravilo-web-pwa-player-cast-2026-09-18.md` §1.4), but nothing about it is an app: no
> manifest, no icons, no service worker, no iOS meta tags, a DOM *Fullscreen* button meant for a TV, and
> a blank page on any iPhone older than iOS 18.2. This phase makes the web build install from Safari on
> iOS and from Chrome on Android, launch from its icon in under a second on the second run, update itself
> without a stale bundle, and tell an unsupported phone the truth.

## Status

`Planned` — written 2026-09-18 from the research report and a read of `ravilo-web/` and
`ravilo-ui/src/wasmJsMain/`. Not dev-reviewed, not built. Client (`ravilo-web` resources and webpack
config, `ravilo-ui` wasmJs actuals + one commonMain seam) plus **design work** for the install card and
the icons. Depends on **235** (a manifest answered with `index.html` cannot install). Siblings **R264**,
**R265**.

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken through
**R262**, admin through **234**. Admin pair: **235**.

## Current state (traced against `main`, 2026-09-18)

- `ravilo-web/src/wasmJsMain/resources/index.html`: hand-written; `<meta name="viewport" …
  viewport-fit=cover>` and `theme-color #0d0d1a` are present; no `<link rel="manifest">`, no
  `apple-mobile-web-app-*`, no `apple-touch-icon`; a fixed `#fs-btn` *Fullscreen* button (inert on iPhone,
  duplicate of the player's own on Android); gamepad polling; `<script src="ravilo.js">`.
- `Main.kt` uses the deprecated `CanvasBasedWindow` (kept deliberately for the video-behind-canvas
  layering; migration is R266's, not this phase's).
- `RaviloRootActuals.kt`: token, device id and base URL in `localStorage`; `deviceDisplayName() =
  "Ravilo Web"`; `platform = "web"` (R252); hash routing via `pushState`/`popstate`.
- **Safe areas:** R244 FR-R244-13 says insets are read on both platforms; the wasm side has no seam —
  the canvas fills the viewport and in a standalone web app paints under the Dynamic Island and the home
  indicator.
- **Platform floor:** Kotlin/Wasm needs WasmGC — Safari/iOS **18.2+**, Chrome 119+, Firefox 120+. Owner
  decision 2026-09-18: **below the floor is unsupported; one notice, no Kotlin/JS fallback bundle.**
- **iOS 26** opens every site added to the Home Screen as a web app by default (WebKit, Safari 26.0);
  a manifest is optional but governs the name and icon. Chrome on Android installs when the manifest has
  `name`/`short_name`, 192 + 512 px icons, `start_url`, `display` and HTTPS.
- Measured: 15.3 MB of wasm per cold load (uncompressed today; 235 fixes the wire size, not the byte
  count the browser must compile).

## Requirements

**FR-R263-1 · The floor is checked before the bundle is fetched.** An inline script in `index.html`, ahead
of `ravilo.js`, validates a minimal WasmGC module with `WebAssembly.validate` (feature detection, never
UA sniffing). On failure it does **not** request `ravilo.js` or any `.wasm`; it replaces the page body with
one notice in en / da / fo chosen by `navigator.language`:
*"Ravilo can't run on this device. It needs iOS 18.2 or newer."* on iOS (detected only for the *wording*
of the sentence), and *"Ravilo can't run in this browser. It needs a browser from 2024 or newer."*
elsewhere. No button, no link, no retry, no bundle. The three strings live in `ravilo-i18n`'s table under
`web.unsupported_ios` / `web.unsupported_browser` and are copied into `index.html` at build time (the
notice runs before any Kotlin exists).

**FR-R263-2 · A manifest.** `manifest.webmanifest` at the app root: `name` *Ravilo*, `short_name`
*Ravilo*, `id` `/`, `start_url` `/`, `scope` `/`, `display` `standalone`, `orientation` `any`,
`background_color` and `theme_color` `#0d0d1a`, `lang` `en`, `icons` 192 and 512 px PNG (`any`) plus a 512
px `maskable`, and a 96 px `monochrome` for Android 13+ themed icons. `<link rel="manifest">` in
`index.html`. On the backend's `/tv/` path (235 OQ 2) `id`/`start_url`/`scope` are `/tv/` — the build emits
both variants or the server rewrites the three fields.

**FR-R263-3 · iOS meta.** `apple-mobile-web-app-capable`, `apple-mobile-web-app-title` *Ravilo*,
`apple-mobile-web-app-status-bar-style` `black-translucent`, and `apple-touch-icon` 180 px. No launch
images (a folder of per-device PNGs for a splash that iOS 26 already synthesises).

**FR-R263-4 · Icons from the brand masters.** All icons are rendered from the R62 lit-mark masters
(`design/ravilo/assets/brand/*.svg`) by the existing SVG raster pipeline, on the `#000B25` navy; the
maskable variant keeps the mark inside the 80 % safe zone. Design hands over the four sizes; no new mark.

**FR-R263-5 · A service worker that makes the second launch instant and the update honest.** `sw.js` at
the root, registered after `load`. It **precaches the current build's asset list** (`index.html`,
`ravilo.js`, both `.wasm`, `composeResources/**`, the icons, the self-hosted player libraries once R264
lands) — the list is generated at build time from the distribution, never hand-maintained — and serves
them cache-first. Everything else (`/api/**`, Jellyfin, images) is **network only**: the service worker
never caches an API response, an artwork URL or a stream. On a new build the worker installs the new
list in the background, and on the next launch it activates and the old caches are deleted. While a new
build is waiting, the app shows one toast — *"Ravilo updated · Reload"* — driven by the worker's
`updatefound`/`controllerchange`, and R252's version string is what the toast compares. No "offline
mode": with no server the app shows its existing connection-error state.

**FR-R263-6 · Safe areas reach Compose.** A wasm actual for the inset seam R244 FR-R244-13 relies on:
`env(safe-area-inset-top/right/bottom/left)` read from a zero-size probe element's computed style on
load and on `resize`/`orientationchange`, delivered as `WindowInsets` to the root. In a standalone iOS
app the app bar clears the Dynamic Island and the player's controls clear the home indicator; in a
Safari tab the values are 0 and nothing moves.

**FR-R263-7 · Standalone mode is an app, not a page.** When `display-mode: standalone` (or
`navigator.standalone`) is true: the DOM *Fullscreen* button is not rendered; the gamepad poller still
runs (harmless); `touch-action: none` on the canvas stays (Compose owns gestures) and the root gains
`overscroll-behavior: none` so a drag at the top does not rubber-band the whole app. The F-key fullscreen
toggle stays for desktop.

**FR-R263-8 · The install is taught, once, where there is no prompt.** On iPhone/iPad in a *browser tab*
(not standalone), the sign-in screen and Settings show an **install card**: the share-sheet glyph, *"Add
Ravilo to your Home Screen"* and the two steps (*Share → Add to Home Screen*), plus one line: *"You'll
sign in again in the installed app."* (an installed web app has its own storage). Dismissible, remembered
per device in `localStorage`, re-surfaced only from Settings → *Install Ravilo*. On Android Chrome the
same card offers **Install** via `beforeinstallprompt`; where the event never fires (already installed,
or a browser that cannot install) the card is absent — never a greyed button. Never shown on the TV
build or above a 900 px viewport. Strings ×3 (`install.title`, `install.step_share`, `install.step_add`,
`install.signin_again`, `install.cta`, `settings.install`) in en / da / fo. **Design hands over the card**
(`design/ravilo/Ravilo Mobile.html`, iPhone frame).

**FR-R263-9 · Nothing is derived that the server owns.** The service worker caches only build artefacts.
The install card's state is per-device UI state, the same class as the remembered skin. No new API.

## Acceptance

- **iPhone (real device, iOS ≥ 18.2, Safari):** open `ravilo.example.net` → Share → Add to Home Screen →
  the icon is the lit mark, the name is *Ravilo*, it opens full-screen with the app bar below the Dynamic
  Island and the sign-in screen painted; second launch shows the sign-in (or Home) in **≤ 1.0 s** to first
  paint from the icon on Wi-Fi (cache-first shell). Rotating to landscape keeps the layout; pull-down at the
  top does not bounce the page.
- **iPhone below 18.2 (or the iOS Simulator on the MacBook):** the notice, in the phone's language, and
  the network inspector shows **no** request for `ravilo.js` or any `.wasm`.
- **Pixel 9, Chrome:** the install card's *Install* leads to a WebAPK; launched from the icon it is
  standalone with a splash from the manifest; `chrome://webapks` lists it.
- **Update:** publish a new build; an open installed app shows *"Ravilo updated · Reload"* within one
  navigation; after reload `window.__RAVILO_VERSION__` is the new one and DevTools → Application → Cache
  Storage holds exactly one build's cache.
- **e2e (Chromium):** `manifest.webmanifest` parses and lists 192/512/maskable icons; `sw.js` registers
  with scope `/`; a second navigation serves `ravilo.js` from the service worker (`transferSize 0`).
- `scripts/check-phases.sh` and `scripts/check-mobile-css.sh` green.

## Non-goals

- No Kotlin/JS fallback bundle (owner decision). No offline browsing, no cached artwork, no background
  sync, no push notifications.
- No `ComposeViewport` migration and no player change — R266 (the web player as the phone player) and
  R264/R265 own those.
- No native wrappers (Capacitor, TWA). No App Store, ever, for this route.
- The Android APK remains the Android phone client; the installed web app is for iPhones and for people
  without the Play Store (report §3.3).

## Open questions

1. **Does the standalone iOS app keep `localStorage` across iOS updates and Safari's site-data clears?**
   Home Screen web apps are exempt from the seven-day script-storage cap; a "Clear History and Website
   Data" in Safari does not touch an installed app's partition — *verify on the device*, since a lost token
   means a sign-in, not data loss.
2. **Does `screen.orientation.lock()` work in a standalone web app on iPhone?** Historically not; R265's
   player follows the sensor regardless (R244 FR-R244-7).
3. **Should the install card also appear on desktop Chrome/Edge** (they install too)? Lean: no — the
   desktop already has a window.

## Dev notes

- The precache list can come from webpack's `assets` at `wasmJsBrowserDistribution` time (a small plugin
  in `ravilo-web/webpack.config.d/` that writes `sw-manifest.json` and injects it into `sw.js`), so a new
  content hash is a new cache key with no human step.
- The inline WasmGC probe must be a hand-assembled ~20-byte module with a `struct` type — keep the bytes
  in a comment with the text form; the R157/await-skiko precedent shows the bootstrap is patched by a
  loader, so put the probe **before** the patched bootstrap, not inside it.
- `check-mobile-css.sh` fences served stylesheets; the install card's styles are Compose, not CSS, but
  the notice page's minimal CSS lives inline in `index.html` and is exempt.
